package com.cabaccess.driver;

import com.cabaccess.identity.Actor;
import com.cabaccess.identity.TenantTx;
import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import com.cabaccess.shared.SecretBox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{t}")
public class DriverApi {
    private final DriverService service;
    private final TenantTx tx;
    private final Db db;
    private final boolean local;
    private final SecretBox secrets;

    public DriverApi(DriverService service, TenantTx tx, Db db, @Value("${cab.local-delivery}") boolean local, SecretBox secrets) {
        this.service = service;
        this.tx = tx;
        this.db = db;
        this.local = local;
        this.secrets = secrets;
    }

    @PostMapping("/drivers")
    Mono<Map<String, Object>> register(@PathVariable UUID t, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DriverInput x) {
        return Mono.fromCallable(() -> service.register(t, Actor.from(jwt), x)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/drivers/me/contact-challenges")
    Mono<Map<String, Object>> challenge(@PathVariable UUID t, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.challenge(t, a));
    }

    @PostMapping("/drivers/me/contact-challenges/{id}/verify")
    Mono<Map<String, Object>> verify(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyInput x) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.verify(t, a, id, x.code()));
    }

    @GetMapping("/local/contact-challenges/{id}")
    Mono<Map<String, Object>> code(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> {
            Failure.require(local, 404, "NOT_FOUND");
            var delivery = db.one("select challenge_id,code from local_delivery where challenge_id=? and subject=?", id, a.subject());
            return Map.of("challenge_id", id, "code", secrets.decrypt(Db.str(delivery, "code")));
        });
    }

    @PostMapping("/vehicles")
    Mono<Map<String, Object>> vehicle(@PathVariable UUID t, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VehicleInput x) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.vehicle(t, a, x));
    }

    @GetMapping("/vehicles/{id}")
    Mono<Map<String, Object>> vehicle(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID facilityId) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.ownedVehicle(t, a, id, facilityId, "AUTHORITY_ADMIN", "VEHICLE_REVIEWER", "SUPPORT_AGENT"));
    }

    @PutMapping("/vehicles/{id}/eligibility/{f}")
    Mono<Map<String, Object>> review(@PathVariable UUID t, @PathVariable UUID id, @PathVariable UUID f, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReviewInput x) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> service.review(t, a, id, f, x));
    }

    @PostMapping("/vehicles/{id}/transfer")
    Mono<Map<String, Object>> transfer(@PathVariable UUID t, @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        var a = Actor.from(jwt);
        return tx.call(t, a, () -> {
            service.ownedVehicle(t, a, id, null, "AUTHORITY_ADMIN");
            throw new Failure(422, "VEHICLE_TRANSFER_UNSUPPORTED");
        });
    }

    public record DriverInput(@NotBlank @Size(max = 120) String name,
                              @NotBlank @Email @Size(max = 200) String contact) {
    }

    public record VehicleInput(@NotBlank @Pattern(regexp = "[A-Za-z0-9 -]{4,20}") String plate,
                               @NotBlank @Pattern(regexp = "SEDAN|SUV|VAN") String vehicleClass) {
    }

    public record ReviewInput(@NotNull @Pattern(regexp = "APPROVED|REJECTED|PENDING") String status,
                              @NotBlank @Size(max = 500) String reason) {
    }

    public record VerifyInput(@NotNull @Pattern(regexp = "[0-9]{6}") String code) {
    }
}
