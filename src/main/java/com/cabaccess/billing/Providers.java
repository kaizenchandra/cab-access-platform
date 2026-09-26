package com.cabaccess.billing;

import com.cabaccess.shared.Db;
import com.cabaccess.shared.Failure;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class Providers {
    private final Db db;
    private final RazorpayProvider real;
    private final ObjectProvider<SimulatorProvider> simulator;

    public Providers(Db db, RazorpayProvider real, ObjectProvider<SimulatorProvider> simulator) {
        this.db = db;
        this.real = real;
        this.simulator = simulator;
    }

    public PaymentProvider.Merchant merchant(UUID id) {
        var m = db.one("select * from merchant_route where id=?", id);
        return new PaymentProvider.Merchant(id, Db.id(m, "tenant_id"), Db.str(m, "provider"), Db.str(m, "credential_prefix"));
    }

    public PaymentProvider adapter(PaymentProvider.Merchant m) {
        if (m.provider().equals("simulator")) {
            var p = simulator.getIfAvailable();
            Failure.require(p != null, 503, "SIMULATOR_DISABLED");
            return p;
        }
        return real;
    }
}
