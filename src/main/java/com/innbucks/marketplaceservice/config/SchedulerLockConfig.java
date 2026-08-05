package com.innbucks.marketplaceservice.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock storage for the {@code @Scheduled} jobs (order-expiry sweep, audit
 * verifier), so a second replica never double-fires them.
 * {@code @EnableScheduling} + {@code @EnableSchedulerLock} live on
 * {@code MarketplaceServiceApplication}; this supplies the {@link LockProvider}
 * they require. The {@code shedlock} table is provisioned by the V1 migration.
 *
 * <p>{@code usingDbTime()} keys lock expiry on the DATABASE clock, so replicas
 * with skewed local clocks can't steal each other's live locks.
 */
@Configuration
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
