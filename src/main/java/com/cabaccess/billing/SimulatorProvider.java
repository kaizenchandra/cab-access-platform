package com.cabaccess.billing;

import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.Db;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@Profile({"local", "test"})
public class SimulatorProvider implements PaymentProvider {
    private final TenantTx tx;
    private final Db db;

    public SimulatorProvider(TenantTx tx, Db db) {
        this.tx = tx;
        this.db = db;
    }

    public ProviderOrder createOrder(Merchant m, OrderRequest r) {
        return tx.system(m.tenant(), () -> {
            String id = "sim_order_" + r.id();
            db.update("insert into simulator_order(id,tenant_id,merchant_id,receipt,amount_minor,currency) values(?,?,?,?,?,?) on conflict do nothing", id, m.tenant(), m.id(), r.id(), r.amount(), r.currency());
            return new ProviderOrder(id, r.amount(), r.currency());
        });
    }

    public Optional<ProviderOrder> findOrder(Merchant m, UUID receipt) {
        return tx.system(m.tenant(), () -> db.optional("select * from simulator_order where merchant_id=? and receipt=?", m.id(), receipt).map(r -> new ProviderOrder(Db.str(r, "id"), Db.num(r, "amount_minor"), Db.str(r, "currency"))));
    }

    public Payment capture(Merchant m, String order) {
        return tx.system(m.tenant(), () -> {
            var o = db.one("select * from simulator_order where id=? and merchant_id=?", order, m.id());
            String id = order.replace("sim_order_", "sim_pay_");
            db.update("insert into simulator_payment(id,tenant_id,order_id,amount_minor,currency,status) values(?,?,?,?,?,'captured') on conflict do nothing", id, m.tenant(), order, Db.num(o, "amount_minor"), Db.str(o, "currency"));
            return paymentRow(db.one("select * from simulator_payment where id=?", id));
        });
    }

    public Payment payment(Merchant m, String id) {
        return tx.system(m.tenant(), () -> paymentRow(db.one("select p.* from simulator_payment p join simulator_order o on o.id=p.order_id where p.id=? and o.merchant_id=?", id, m.id())));
    }

    public List<Payment> payments(Merchant m, String id) {
        return tx.system(m.tenant(), () -> db.list("select p.* from simulator_payment p join simulator_order o on o.id=p.order_id where o.id=? and o.merchant_id=?", id, m.id()).stream().map(this::paymentRow).toList());
    }

    private Payment paymentRow(Map<String, Object> r) {
        return new Payment(Db.str(r, "id"), Db.str(r, "order_id"), Db.num(r, "amount_minor"), Db.str(r, "currency"), Db.str(r, "status"));
    }

    public ProviderRefund refund(Merchant m, String payment, long amount, UUID receipt) {
        return tx.system(m.tenant(), () -> {
            payment(m, payment);
            String id = "sim_refund_" + receipt;
            db.update("insert into simulator_refund(id,tenant_id,payment_id,amount_minor,receipt,status) values(?,?,?,?,?,'processed') on conflict do nothing", id, m.tenant(), payment, amount, receipt);
            return new ProviderRefund(id, payment, amount, "processed", receipt.toString());
        });
    }

    public Optional<ProviderRefund> findRefund(Merchant m, String payment, UUID receipt) {
        return tx.system(m.tenant(), () -> {
            payment(m, payment);
            return db.optional("select * from simulator_refund where payment_id=? and receipt=?", payment, receipt).map(r -> new ProviderRefund(Db.str(r, "id"), payment, Db.num(r, "amount_minor"), Db.str(r, "status"), receipt.toString()));
        });
    }

    public String webhookSecret(Merchant merchant) {
        return "local-webhook-only-" + merchant.id();
    }
}
