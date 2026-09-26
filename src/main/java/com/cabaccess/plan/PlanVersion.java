package com.cabaccess.plan;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "plan_version")
@Immutable
public class PlanVersion {
    @Id
    private UUID id;
    private UUID tenantId;
    private UUID planId;
    private UUID facilityId;
    private int versionNumber;
    @Enumerated(EnumType.STRING)
    private CalendarPolicy.Duration duration;
    private long amountMinor;
    private String currency;
    private String coverage;
    private String exclusions;
    private String terms;
    private String vehicleClass;
    private Instant publishedAt;

    protected PlanVersion() {
    }

    public PlanVersion(UUID id, UUID tenant, UUID plan, UUID facility, int version, PlanApi.VersionInput input, Instant now) {
        this.id = id;
        tenantId = tenant;
        planId = plan;
        facilityId = facility;
        versionNumber = version;
        duration = input.duration();
        amountMinor = input.amountMinor();
        currency = "INR";
        coverage = input.coverage();
        exclusions = input.exclusions();
        terms = input.terms();
        vehicleClass = input.vehicleClass();
        publishedAt = now;
    }

    public UUID id() {
        return id;
    }
}
