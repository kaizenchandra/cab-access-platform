package com.cabaccess.plan;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlanVersions extends JpaRepository<PlanVersion,UUID> {}
