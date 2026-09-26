package com.cabaccess;

import com.cabaccess.access.AccessService;
import com.cabaccess.access.GateWorker;
import com.cabaccess.authority.AuthorityApi;
import com.cabaccess.authority.AuthorityService;
import com.cabaccess.billing.*;
import com.cabaccess.driver.DriverApi;
import com.cabaccess.driver.DriverService;
import com.cabaccess.identity.Actor;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.movement.MovementApi;
import com.cabaccess.movement.MovementService;
import com.cabaccess.operations.DurableWorker;
import com.cabaccess.operations.NotificationPort;
import com.cabaccess.operations.OperationsService;
import com.cabaccess.operations.Outbox;
import com.cabaccess.plan.CalendarPolicy;
import com.cabaccess.plan.PlanApi;
import com.cabaccess.plan.PlanService;
import com.cabaccess.shared.Crypto;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import com.cabaccess.shared.SecretBox;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestRuntime.TimeConfiguration.class)
class PlatformIntegrationTest {
    @Autowired
    Db db;
    @Autowired
    TenantTx tx;
    @Autowired
    AuthorityService authorities;
    @Autowired
    DriverService drivers;
    @Autowired
    PlanService plans;
    @Autowired
    BillingService billing;
    @Autowired
    BillingWorker billingWorker;
    @Autowired
    Providers providers;
    @Autowired
    SimulatorProvider simulator;
    @Autowired
    DurableWorker worker;
    @Autowired
    AccessService access;
    @Autowired
    MovementService movement;
    @Autowired
    GateWorker gates;
    @Autowired
    OperationsService operations;
    @Autowired
    SecretBox secrets;
    @Autowired
    Outbox outbox;
    @Autowired
    ObjectMapper json;
    @Autowired
    TestRuntime.MutableClock clock;
    @MockitoBean
    FulfillmentGuard guard;
    @MockitoBean
    NotificationPort delivery;
    @LocalServerPort
    int port;
    Fixture f;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        TestRuntime.properties(r);
    }

    <T> T run(Supplier<T> work) {
        return tx.system(f.t(), work);
    }

    @BeforeEach
    void setup() {
        clock.at(Instant.parse("2026-09-26T12:00:00Z"));
        reset(guard, delivery);
        when(delivery.deliver(any())).thenAnswer(x -> "confirmed-" + ((NotificationPort.Message) x.getArgument(0)).id());
        f = fixture();
    }

    Fixture fixture() {
        UUID t = UUID.randomUUID(), merchant = UUID.randomUUID();
        String suffix = t.toString();
        Actor admin = new Actor("admin-" + suffix, false), driver = new Actor("driver-" + suffix, false), finance = new Actor("finance-" + suffix, false), checker = new Actor("checker-" + suffix, false), operator = new Actor("operator-" + suffix, false), supervisor = new Actor("supervisor-" + suffix, false);
        authorities.onboard(new Actor("platform", true), new AuthorityApi.AuthorityInput(t, "Synthetic", "Fictional collector", "a@example.invalid", merchant, "simulator", "TEST", admin.subject()));
        return tx.system(t, () -> {
            UUID facility = Db.id(authorities.facility(t, admin, new AuthorityApi.FacilityInput("Synthetic Facility", "Asia/Kolkata")), "id");
            UUID zone = Db.id(authorities.zone(t, admin, facility, "Pickup"), "id");
            UUID entry = Db.id(authorities.gate(t, admin, new AuthorityApi.GateInput(facility, zone, "Entry", "ENTRY")), "id");
            UUID exit = Db.id(authorities.gate(t, admin, new AuthorityApi.GateInput(facility, zone, "Exit", "EXIT")), "id");
            var ed = authorities.device(t, admin, new AuthorityApi.DeviceInput(facility, entry, "ANPR"));
            var xd = authorities.device(t, admin, new AuthorityApi.DeviceInput(facility, exit, "ANPR"));
            authorities.membership(t, finance.subject(), "FINANCE_OFFICER", facility);
            authorities.membership(t, checker.subject(), "FINANCE_OFFICER", facility);
            authorities.membership(t, operator.subject(), "GATE_OPERATOR", facility);
            authorities.membership(t, supervisor.subject(), "GATE_SUPERVISOR", facility);
            drivers.register(t, driver, new DriverApi.DriverInput("Synthetic Driver", "driver@example.invalid"));
            var challenge = drivers.challenge(t, driver);
            String code = secrets.decrypt(Db.str(db.one("select code from local_delivery where challenge_id=?", Db.id(challenge, "id")), "code"));
            assertEquals(true, drivers.verify(t, driver, Db.id(challenge, "id"), code).get("verified"));
            UUID vehicle = Db.id(drivers.vehicle(t, driver, new DriverApi.VehicleInput("DEMO1234", "SEDAN")), "id");
            drivers.review(t, admin, vehicle, facility, new DriverApi.ReviewInput("APPROVED", "Synthetic review"));
            UUID plan = Db.id(plans.create(t, admin, new PlanApi.PlanInput(facility, "Synthetic plan")), "id");
            UUID version = Db.id(plans.publish(t, admin, plan, version(zone, 123400)), "id");
            return new Fixture(t, admin, driver, finance, checker, operator, supervisor, facility, zone, entry, exit, Db.id(ed, "id"), Db.id(xd, "id"), Db.str(ed, "secret"), Db.str(xd, "secret"), vehicle, plan, version, merchant);
        });
    }

    PlanApi.VersionInput version(UUID zone, long amount) {
        return new PlanApi.VersionInput(CalendarPolicy.Duration.MONTHLY, amount, "Pickup only", "Parking/tax/waiting excluded", "Synthetic terms", "SEDAN", List.of(zone));
    }

    UUID purchase() {
        return run(() -> {
            var q = plans.quote(f.t(), f.driver(), new PlanApi.QuoteInput(f.vehicle(), f.version(), null));
            return Db.id(billing.purchase(f.t(), f.driver(), Db.id(q, "id"), UUID.randomUUID().toString()), "id");
        });
    }

    PaymentProvider.Payment capture(UUID order) {
        billingWorker.createOrder(f.t(), order);
        String providerOrder = run(() -> Db.str(db.one("select * from purchase_order where id=?", order), "provider_order_id"));
        return simulator.capture(providers.merchant(f.merchant()), providerOrder);
    }

    byte[] payload(PaymentProvider.Payment p) {
        return db.json(Map.of("event", "payment.captured", "payload", Map.of("payment", Map.of("entity", Map.of("id", p.id(), "order_id", p.orderId(), "amount", p.amount(), "currency", p.currency(), "status", p.status()))))).getBytes(StandardCharsets.UTF_8);
    }

    Map<String, Object> webhook(PaymentProvider.Payment p, String event) {
        byte[] raw = payload(p);
        return billing.webhook(f.merchant(), event, Crypto.hmac(raw, simulator.webhookSecret(providers.merchant(f.merchant()))), raw);
    }

    UUID activate() {
        UUID o = purchase();
        var p = capture(o);
        var event = webhook(p, UUID.randomUUID().toString());
        billingWorker.verifyPayment(f.t(), Db.id(event, "id"));
        billingWorker.fulfill(f.t(), o);
        return o;
    }

    UUID subscription(UUID order) {
        return run(() -> Db.id(db.one("select * from entitlement_period where order_id=?", order), "subscription_id"));
    }

    MovementApi.EventInput event(String source, String kind, Instant at, boolean buffered, String passage) {
        return new MovementApi.EventInput(source, kind, at, "DEMO1234", new BigDecimal("0.99"), buffered, passage);
    }

    void assertCode(String code, Runnable action) {
        var e = assertThrows(Failure.class, action::run);
        assertEquals(code, e.code);
    }

    @Test
    void restrictedRoleRlsCrossTenantReadsWritesAndForeignKeys() {
        var other = fixture();
        run(() -> {
            assertEquals("cab_app", Db.str(db.one("select current_user"), "current_user"));
            assertEquals(false, db.one("select rolbypassrls from pg_roles where rolname=current_user").get("rolbypassrls"));
            assertEquals(0, db.count("select count(*) from vehicle where id=?", other.vehicle()));
            assertEquals(0, db.update("update vehicle set plate='LEAK123' where id=?", other.vehicle()));
            return true;
        });
        assertThrows(RuntimeException.class, () -> run(() -> db.update("insert into zone(id,tenant_id,facility_id,name) values(?,?,?,?)", UUID.randomUUID(), f.t(), other.facility(), "cross reference")));
        assertThrows(RuntimeException.class, () -> run(() -> db.update("insert into facility(id,tenant_id,name,timezone) values(?,?,?,'UTC')", UUID.randomUUID(), other.t(), "cross tenant")));
        assertEquals(0, db.count("select count(*) from vehicle"));
    }

    @Test
    void membershipRoleOwnershipAndExpiredSupport() {
        var other = fixture();
        assertCode("TENANT_MEMBERSHIP_REQUIRED", () -> run(() -> tx.member(f.t(), other.admin())));
        assertCode("ROLE_FORBIDDEN", () -> run(() -> {
            tx.permit(f.t(), f.operator(), f.facility(), "FINANCE_OFFICER");
            return true;
        }));
        Actor platform = new Actor("platform", true);
        assertCode("TENANT_MEMBERSHIP_REQUIRED", () -> run(() -> tx.member(f.t(), platform)));
        run(() -> {
            db.update("insert into support_grant(id,tenant_id,subject,expires_at,granted_by,reason) values(?,?,?,?,?,?)", UUID.randomUUID(), f.t(), platform.subject(), clock.instant().plusSeconds(10), f.admin().subject(), "Support incident");
            assertEquals("SUPPORT_AGENT", tx.member(f.t(), platform).get("role"));
            return true;
        });
        clock.advance(Duration.ofSeconds(11));
        assertCode("TENANT_MEMBERSHIP_REQUIRED", () -> run(() -> tx.member(f.t(), platform)));
    }

    @Test
    void duplicateAndConcurrentCallbacksCreateOneBusinessEffect() throws Exception {
        UUID order = purchase();
        var payment = capture(order);
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) tasks.add(() -> webhook(payment, "same-event"));
            for (var future : pool.invokeAll(tasks)) future.get();
        }
        worker.pump(f.t(), 50);
        run(() -> {
            assertEquals(1, db.count("select count(*) from provider_event"));
            assertEquals(1, db.count("select count(*) from payment_attempt"));
            assertEquals(1, db.count("select count(*) from entitlement_period"));
            return true;
        });
        var delayed = webhook(payment, "later-duplicate-capture");
        billingWorker.verifyPayment(f.t(), Db.id(delayed, "id"));
        assertEquals(1, run(() -> db.count("select count(*) from entitlement_period")));
    }

    @Test
    void forgedChangedAndReorderedCallbacks() {
        UUID o = purchase();
        var p = capture(o);
        assertCode("INVALID_SIGNATURE", () -> billing.webhook(f.merchant(), "bad", "forged", payload(p)));
        webhook(p, "stable");
        assertCode("EVENT_PAYLOAD_MISMATCH", () -> webhook(new PaymentProvider.Payment(p.id(), p.orderId(), p.amount() + 1, p.currency(), p.status()), "stable"));
        var event = webhook(p, "captured");
        billingWorker.verifyPayment(f.t(), Db.id(event, "id"));
        billingWorker.fulfill(f.t(), o);
        byte[] late = db.json(Map.of("event", "payment.authorized", "payload", Map.of())).getBytes(StandardCharsets.UTF_8);
        billing.webhook(f.merchant(), "late-authorized", Crypto.hmac(late, simulator.webhookSecret(providers.merchant(f.merchant()))), late);
        assertEquals("FULFILLED", run(() -> db.one("select status from purchase_order where id=?", o).get("status")));
    }

    @Test
    void mismatchedAmountCurrencyOrderAndMerchantRejected() {
        UUID o = purchase();
        var p = capture(o);
        var bad = webhook(new PaymentProvider.Payment(p.id(), p.orderId(), p.amount() + 1, "USD", "captured"), "bad-fields");
        assertCode("WEBHOOK_PAYMENT_MISMATCH", () -> billingWorker.verifyPayment(f.t(), Db.id(bad, "id")));
        assertCode("PAYMENT_AMOUNT_CURRENCY_MISMATCH", () -> run(() -> {
            billing.accept(f.t(), providers.merchant(f.merchant()), new PaymentProvider.Payment("fake", p.orderId(), p.amount() + 1, p.currency(), "captured"), null);
            return true;
        }));
        assertCode("NOT_FOUND", () -> run(() -> {
            billing.accept(f.t(), providers.merchant(f.merchant()), new PaymentProvider.Payment("fake", "wrong-order", p.amount(), p.currency(), "captured"), null);
            return true;
        }));
        var other = fixture();
        assertCode("NOT_FOUND", () -> simulator.payment(providers.merchant(other.merchant()), p.id()));
        assertEquals(0, run(() -> db.count("select count(*) from entitlement_period")));
    }

    @Test
    void confirmedPaymentSurvivesActivationFailureAndWorkerRestart() {
        UUID o = purchase();
        var p = capture(o);
        webhook(p, "recover");
        doThrow(new IllegalStateException("Injected fulfillment outage")).when(guard).check(o);
        worker.pump(f.t(), 50);
        assertEquals("CONFIRMED", run(() -> db.one("select status from purchase_order where id=?", o).get("status")));
        assertEquals(1, run(() -> db.count("select count(*) from payment_attempt")));
        assertEquals(0, run(() -> db.count("select count(*) from entitlement_period")));
        reset(guard);
        clock.advance(Duration.ofSeconds(65));
        worker.pump(f.t(), 50);
        assertEquals(1, run(() -> db.count("select count(*) from entitlement_period")));
    }

    @Test
    void concurrentPurchasesSerializeNonoverlappingRenewals() throws Exception {
        UUID first = purchase(), second = purchase();
        for (UUID o : List.of(first, second)) {
            var p = capture(o);
            var e = webhook(p, UUID.randomUUID().toString());
            billingWorker.verifyPayment(f.t(), Db.id(e, "id"));
        }
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> billingWorker.fulfill(f.t(), first));
            var b = pool.submit(() -> billingWorker.fulfill(f.t(), second));
            a.get();
            b.get();
        }
        run(() -> {
            var rows = db.list("select * from entitlement_period order by start_at");
            assertEquals(2, rows.size());
            assertEquals(Db.instant(rows.getFirst(), "end_at"), Db.instant(rows.getLast(), "start_at"));
            assertEquals(1, db.count("select count(*) from subscription"));
            return true;
        });
    }

    @Test
    void planEditsDoNotAlterPurchasedSnapshot() {
        UUID o = purchase();
        run(() -> plans.publish(f.t(), f.admin(), f.plan(), version(f.zone(), 999999)));
        var p = capture(o);
        var e = webhook(p, "immutable");
        billingWorker.verifyPayment(f.t(), Db.id(e, "id"));
        billingWorker.fulfill(f.t(), o);
        run(() -> {
            var period = db.one("select * from entitlement_period where order_id=?", o);
            assertEquals(123400, ((Number) db.parse(Db.str(period, "snapshot")).get("amount_minor")).longValue());
            return true;
        });
        assertThrows(RuntimeException.class, () -> run(() -> db.update("update plan_version set amount_minor=1 where id=?", f.version())));
    }

    @Test
    void commandAckIsNotPassageAndBufferedEventsNeverOpen() {
        activate();
        var observation = run(() -> movement.ingest(f.t(), f.entryDevice(), event("observe", "OBSERVATION", clock.instant(), false, null)));
        var decision = (Map<String, Object>) observation.get("decision");
        assertEquals("ALLOW", decision.get("outcome"));
        UUID command = run(() -> Db.id(db.one("select * from gate_command where decision_id=?", Db.id(decision, "id")), "id"));
        gates.dispatch(f.t(), command);
        run(() -> movement.acknowledge(f.entryDevice(), command, "OPENED"));
        assertEquals(0, run(() -> db.count("select count(*) from visit")));
        var historical = run(() -> movement.ingest(f.t(), f.entryDevice(), event("buffered", "OBSERVATION", clock.instant().minusSeconds(120), true, null)));
        assertFalse(historical.containsKey("decision"));
        assertEquals(1, run(() -> db.count("select count(*) from gate_command")));
    }

    @Test
    void duplicateAmbiguousObservationsAndStaleCommands() {
        activate();
        var input = event("same", "OBSERVATION", clock.instant(), false, null);
        var a = run(() -> movement.ingest(f.t(), f.entryDevice(), input));
        assertEquals(a.get("id"), run(() -> movement.ingest(f.t(), f.entryDevice(), input)).get("id"));
        assertCode("EVENT_PAYLOAD_MISMATCH", () -> run(() -> movement.ingest(f.t(), f.entryDevice(), event("same", "OBSERVATION", clock.instant().minusSeconds(1), false, null))));
        var b = run(() -> movement.ingest(f.t(), f.entryDevice(), event("different", "OBSERVATION", clock.instant(), false, null)));
        assertEquals("MANUAL_REVIEW", ((Map<?, ?>) b.get("decision")).get("outcome"));
        UUID command = run(() -> Db.id(db.one("select * from gate_command limit 1"), "id"));
        clock.advance(Duration.ofSeconds(11));
        gates.dispatch(f.t(), command);
        assertEquals("EXPIRED", run(() -> db.one("select status from gate_command where id=?", command).get("status")));
        assertCode("STALE_OR_UNDISPATCHED_COMMAND", () -> run(() -> movement.acknowledge(f.entryDevice(), command, "OPENED")));
        gates.dispatch(f.t(), command);
        assertEquals("EXPIRED", run(() -> db.one("select status from gate_command where id=?", command).get("status")));
    }

    @Test
    void suspensionEligibilityZoneFacilityAndExpiryDecisions() {
        UUID o = activate();
        UUID sub = subscription(o);
        run(() -> {
            db.update("update subscription set suspended=true where id=?", sub);
            assertEquals("SUBSCRIPTION_SUSPENDED", access.decide(f.t(), f.entryGate(), f.vehicle(), BigDecimal.ONE, "TEST", "evidence", false).get("reason"));
            db.update("update subscription set suspended=false where id=?", sub);
            drivers.review(f.t(), f.admin(), f.vehicle(), f.facility(), new DriverApi.ReviewInput("REJECTED", "Changed eligibility"));
            assertEquals("VEHICLE_NOT_APPROVED", access.decide(f.t(), f.entryGate(), f.vehicle(), BigDecimal.ONE, "TEST", "evidence", false).get("reason"));
            drivers.review(f.t(), f.admin(), f.vehicle(), f.facility(), new DriverApi.ReviewInput("APPROVED", "Restored"));
            UUID zone = Db.id(authorities.zone(f.t(), f.admin(), f.facility(), "Premium"), "id");
            UUID gate = Db.id(authorities.gate(f.t(), f.admin(), new AuthorityApi.GateInput(f.facility(), zone, "Premium", "ENTRY")), "id");
            assertEquals("ZONE_NOT_COVERED", access.decide(f.t(), gate, f.vehicle(), BigDecimal.ONE, "TEST", "evidence", false).get("reason"));
            return true;
        });
        clock.at(run(() -> Db.instant(db.one("select * from entitlement_period where order_id=?", o), "end_at")));
        assertEquals("NO_VALID_ENTITLEMENT", run(() -> access.decide(f.t(), f.entryGate(), f.vehicle(), BigDecimal.ONE, "TEST", "evidence", false)).get("reason"));
    }

    @Test
    void exitBeforeEntryArrivalMatchesByEventTimeAndCorrectionsPreserveOriginal() {
        Instant start = clock.instant().minusSeconds(60), end = clock.instant().minusSeconds(10);
        var exit = run(() -> movement.ingest(f.t(), f.exitDevice(), event("exit", "PASSAGE", end, true, "cross-exit")));
        run(() -> {
            movement.match(f.t(), Db.id(exit, "id"));
            return true;
        });
        assertEquals(1, run(() -> db.count("select count(*) from movement_exception where reason='UNMATCHED_EXIT' and status='OPEN'")));
        var entry = run(() -> movement.ingest(f.t(), f.entryDevice(), event("entry", "PASSAGE", start, true, "cross-entry")));
        run(() -> {
            movement.match(f.t(), Db.id(entry, "id"));
            return true;
        });
        run(() -> {
            var visit = db.one("select * from visit");
            assertEquals("MATCHED", visit.get("quality"));
            assertEquals(end, Db.instant(visit, "exit_at"));
            db.update("insert into visit_correction(id,tenant_id,visit_id,corrected_exit_at,actor,reason,created_at) values(?,?,?,?,?,?,?)", UUID.randomUUID(), f.t(), Db.id(visit, "id"), end.plusSeconds(2), f.supervisor().subject(), "Evidence correction", clock.instant());
            assertEquals(end, Db.instant(db.one("select * from visit"), "exit_at"));
            assertEquals(0, db.count("select count(*) from movement_exception where reason='UNMATCHED_EXIT' and status='OPEN'"));
            return true;
        });
    }

    @Test
    void unauthorizedOverrideAndMissingReason() {
        var d = run(() -> access.decide(f.t(), f.entryGate(), f.vehicle(), BigDecimal.ONE, "TEST", "test", false));
        assertCode("ROLE_FORBIDDEN", () -> run(() -> access.override(f.t(), f.operator(), Db.id(d, "id"), "Reason", "Evidence")));
        assertCode("OVERRIDE_REASON_EVIDENCE_REQUIRED", () -> run(() -> access.override(f.t(), f.supervisor(), Db.id(d, "id"), "", "Evidence")));
        run(() -> access.override(f.t(), f.supervisor(), Db.id(d, "id"), "Supervised exception", "Document ref"));
        assertEquals(1, run(() -> db.count("select count(*) from audit_entry where action='ACCESS_OVERRIDE'")));
        assertEquals(0, run(() -> db.count("select count(*) from entitlement_period")));
    }

    @Test
    void refundMakerCheckerBalanceAndConfirmationTreatment() {
        UUID order = activate();
        UUID payment = run(() -> Db.id(db.one("select * from payment_attempt where order_id=?", order), "id"));
        var refund = run(() -> billing.refund(f.t(), f.finance(), payment, 100, "Synthetic adjustment", "refund-key-1"));
        assertEquals(refund.get("id").toString(), run(() -> billing.refund(f.t(), f.finance(), payment, 100, "Synthetic adjustment", "refund-key-1")).get("id").toString());
        assertCode("REFUND_EXCEEDS_BALANCE", () -> run(() -> billing.refund(f.t(), f.finance(), payment, 123400, "Too much", "refund-key-2")));
        assertCode("REFUND_SECOND_APPROVER_REQUIRED", () -> run(() -> billing.approveRefund(f.t(), f.finance(), Db.id(refund, "id"))));
        run(() -> billing.approveRefund(f.t(), f.checker(), Db.id(refund, "id")));
        assertEquals(false, run(() -> db.one("select suspended from subscription").get("suspended")));
        billingWorker.refund(f.t(), Db.id(refund, "id"));
        billingWorker.refund(f.t(), Db.id(refund, "id"));
        assertEquals(true, run(() -> db.one("select suspended from subscription").get("suspended")));
        assertEquals(1, run(() -> db.count("select count(*) from simulator_refund")));
    }

    @Test
    void notificationsRetryAndExpiryDeduplicate() {
        UUID order = activate();
        doThrow(new Failure(503, "TEST_OUTAGE")).when(delivery).deliver(any());
        worker.pump(f.t(), 30);
        assertEquals(1, run(() -> db.count("select count(*) from entitlement_period")));
        assertEquals(0, run(() -> db.count("select count(*) from notification where status='DELIVERED'")));
        doReturn("confirmed-retry").when(delivery).deliver(any());
        clock.advance(Duration.ofSeconds(65));
        worker.pump(f.t(), 30);
        assertEquals(2, run(() -> db.count("select count(*) from notification where status='DELIVERED'")));
        Instant end = run(() -> Db.instant(db.one("select * from entitlement_period where order_id=?", order), "end_at"));
        clock.at(end.minusSeconds(60));
        worker.maintenance(f.t());
        worker.maintenance(f.t());
        assertEquals(1, run(() -> db.count("select count(*) from notification where kind='EXPIRING'")));
        clock.at(end);
        worker.maintenance(f.t());
        worker.maintenance(f.t());
        assertEquals(1, run(() -> db.count("select count(*) from notification where kind='EXPIRED'")));
    }

    @Test
    void workerLeaseRecoveryAndTenantJobs() {
        UUID order = purchase();
        var other = fixture();
        var claimed = worker.claim(f.t()).orElseThrow();
        assertEquals(f.t(), Db.id(claimed, "tenant_id"));
        clock.advance(Duration.ofSeconds(31));
        worker.pump(f.t(), 50);
        assertEquals("PENDING", run(() -> db.one("select status from purchase_order where id=?", order).get("status")));
        assertEquals(0, tx.system(other.t(), () -> db.count("select count(*) from purchase_order")));
    }

    @Test
    void reportsExportsAndResourceSubstitutionAreIsolated() {
        UUID o = activate();
        var other = fixture();
        assertEquals(123400L, run(() -> operations.financial(f.t(), f.finance(), f.facility(), clock.instant().minusSeconds(60), clock.instant().plusSeconds(60))).get("gross_collections_minor"));
        assertTrue(tx.system(other.t(), () -> operations.export(other.t(), other.admin(), other.facility(), "payments", 0)).isEmpty());
        assertCode("NOT_FOUND", () -> tx.system(other.t(), () -> billing.order(other.t(), other.admin(), o)));
        assertCode("FACILITY_FORBIDDEN", () -> run(() -> operations.financial(f.t(), f.finance(), other.facility(), clock.instant().minusSeconds(1), clock.instant().plusSeconds(1))));
    }

    @Test
    void contactChallengesSingleUseAttemptsAndExpiry() {
        clock.advance(Duration.ofMinutes(2));
        var c = run(() -> drivers.challenge(f.t(), f.driver()));
        UUID id = Db.id(c, "id");
        String code = run(() -> secrets.decrypt(Db.str(db.one("select code from local_delivery where challenge_id=?", id), "code")));
        for (int i = 0; i < 5; i++)
            assertEquals(false, run(() -> drivers.verify(f.t(), f.driver(), id, "xxxxxx")).get("verified"));
        assertEquals(false, run(() -> drivers.verify(f.t(), f.driver(), id, code)).get("verified"));
        clock.advance(Duration.ofMinutes(2));
        var c2 = run(() -> drivers.challenge(f.t(), f.driver()));
        clock.advance(Duration.ofMinutes(6));
        assertEquals(false, run(() -> drivers.verify(f.t(), f.driver(), Db.id(c2, "id"), "000000")).get("verified"));
    }

    @Test
    void oidcSignatureAudienceExpiryAndHttpTenantSelection() throws Exception {
        UUID o = activate();
        assertEquals(200, http("GET", "/api/v1/tenants/" + f.t() + "/orders/" + o, jwt(f.driver(), "cab-api", Instant.now().plusSeconds(300)), null));
        assertEquals(401, http("GET", "/api/v1/tenants/" + f.t() + "/orders/" + o, jwt(f.driver(), "wrong-aud", Instant.now().plusSeconds(300)), null));
        assertEquals(401, http("GET", "/api/v1/tenants/" + f.t() + "/orders/" + o, jwt(f.driver(), "cab-api", Instant.now().minusSeconds(300)), null));
        assertEquals(401, http("GET", "/api/v1/tenants/" + f.t() + "/orders/" + o, "forged.jwt.token", null));
        var other = fixture();
        assertEquals(403, http("GET", "/api/v1/tenants/" + f.t() + "/orders/" + o, jwt(other.admin(), "cab-api", Instant.now().plusSeconds(300)), null));
    }

    String jwt(Actor actor, String audience, Instant expires) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(TestRuntime.OIDC.baseUrl()).subject(actor.subject()).audience(audience).expirationTime(Date.from(expires)).issueTime(Date.from(Instant.now().minusSeconds(600))).claim("roles", actor.platformAdmin() ? List.of("PLATFORM_ADMIN") : List.of()).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
        token.sign(new RSASSASigner(TestRuntime.KEY));
        return token.serialize();
    }

    int http(String method, String path, String token, Object payload) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(db.json(payload))).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    record Fixture(UUID t, Actor admin, Actor driver, Actor finance, Actor checker, Actor operator, Actor supervisor,
                   UUID facility, UUID zone, UUID entryGate, UUID exitGate, UUID entryDevice, UUID exitDevice,
                   String entrySecret, String exitSecret, UUID vehicle, UUID plan, UUID version, UUID merchant) {
    }
}
