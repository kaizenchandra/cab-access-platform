package com.cabaccess.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PlanVersions extends JpaRepository<PlanVersion, UUID> {
}
