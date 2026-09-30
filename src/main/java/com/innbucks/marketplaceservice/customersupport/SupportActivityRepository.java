package com.innbucks.marketplaceservice.customersupport;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface SupportActivityRepository
        extends JpaRepository<SupportActivity, UUID>, JpaSpecificationExecutor<SupportActivity> {
}
