package com.innbucks.marketplaceservice.metrics;

import com.innbucks.marketplaceservice.audit.AuditEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scheduler heartbeat: {@code marketplace.scheduler.last_success{job}} and
 * {@code marketplace.scheduler.failures{job}}. A quiet job and a dead one used
 * to look identical — every sweeper reports only when it did something — so
 * "has this job succeeded lately" is now a series an alert can read, for every
 * {@code @Scheduled} job, from boot.
 */
class SchedulerHeartbeatTest {

    private static final String JOB = "orderExpirySweeper";

    private SimpleMeterRegistry registry;
    private MarketplaceMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MarketplaceMetrics(registry);
    }

    private double lastSuccess(String job) {
        return registry.find("marketplace.scheduler.last_success").tag("job", job).gauge().value();
    }

    private double failures(String job) {
        return registry.find("marketplace.scheduler.failures").tag("job", job).counter().count();
    }

    @Test
    @DisplayName("Every job's series exists from boot and reads 0, before any run")
    void everyJobIsRegisteredAtConstruction() {
        for (String job : MarketplaceMetrics.SCHEDULED_JOBS) {
            assertThat(lastSuccess(job)).as(job).isZero();
            assertThat(failures(job)).as(job).isZero();
        }
    }

    @Test
    @DisplayName("The invariant-zero failure counters exist from boot at 0, for every bounded tag value")
    void failureCountersAreRegisteredAtConstruction() {
        // increase()/rate() need an earlier sample: a counter that first
        // appears already at 1 reads as zero increase, hiding the first failure.
        for (AuditEventType type : AuditEventType.values()) {
            assertThat(registry.get("marketplace.audit.write_failed").tag("type", type.name())
                    .counter().count()).as(type.name()).isZero();
        }
        for (String store : List.of(MarketplaceMetrics.REVOCATION_STORE_DENYLIST,
                MarketplaceMetrics.REVOCATION_STORE_TOKEN_VERSION)) {
            assertThat(registry.get("marketplace.auth.revocation_check_failed").tag("store", store)
                    .counter().count()).as(store).isZero();
        }
        assertThat(registry.find("marketplace.auth.revocation_check_failed").counters()).hasSize(2);

        metrics.auditWriteFailed(AuditEventType.ORDER_PAID.name());
        metrics.revocationCheckFailed(MarketplaceMetrics.REVOCATION_STORE_DENYLIST);
        assertThat(registry.get("marketplace.audit.write_failed").tag("type", "ORDER_PAID")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("marketplace.auth.revocation_check_failed").tag("store", "denylist")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A pass that returns stamps last_success with the current epoch seconds")
    void aCleanPassStampsLastSuccess() {
        double before = Instant.now().toEpochMilli() / 1000.0;

        metrics.runScheduledJob(JOB, () -> { });

        assertThat(lastSuccess(JOB)).isBetween(before, Instant.now().toEpochMilli() / 1000.0);
        assertThat(failures(JOB)).isZero();
        // Only that job moved.
        assertThat(lastSuccess("auditIntegrityVerifier")).isZero();
    }

    @Test
    @DisplayName("A pass that throws counts a failure, is rethrown untouched, and leaves last_success alone")
    void aThrowingPassCountsAFailureAndIsRethrown() {
        metrics.runScheduledJob(JOB, () -> { });
        double stamped = lastSuccess(JOB);
        IllegalStateException boom = new IllegalStateException("db down");

        assertThatThrownBy(() -> metrics.runScheduledJob(JOB, () -> { throw boom; }))
                .isSameAs(boom);

        assertThat(failures(JOB)).isEqualTo(1.0);
        assertThat(lastSuccess(JOB)).isEqualTo(stamped);
    }

    @Test
    @DisplayName("A reporting pass that says it did not complete is a failure, exactly like a throw")
    void aReportedFailureIsAFailure() {
        metrics.runScheduledJobReporting("auditIntegrityVerifier", () -> false);

        assertThat(failures("auditIntegrityVerifier")).isEqualTo(1.0);
        assertThat(lastSuccess("auditIntegrityVerifier")).isZero();

        metrics.runScheduledJobReporting("auditIntegrityVerifier", () -> true);
        assertThat(lastSuccess("auditIntegrityVerifier")).isPositive();
    }

    @Test
    @DisplayName("An unlisted job name still gets a working series rather than failing the job")
    void anUnlistedJobIsRegisteredLazily() {
        metrics.runScheduledJob("somethingNew", () -> { });

        assertThat(lastSuccess("somethingNew")).isPositive();
        assertThat(failures("somethingNew")).isZero();
    }

    @Test
    @DisplayName("Every @Scheduled method is ShedLock-named, and that name is in SCHEDULED_JOBS (and nothing else is)")
    void everyScheduledJobHasAHeartbeat() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        List<String> lockNames = new ArrayList<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("com.innbucks.marketplaceservice")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Scheduled.class)) {
                    continue;
                }
                SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
                assertThat(lock).as("@SchedulerLock on " + type.getSimpleName() + "."
                        + method.getName()).isNotNull();
                // ShedLock's PROXY_METHOD mode refuses a primitive return (the V19 bug).
                assertThat(method.getReturnType()).as(type.getSimpleName() + "."
                        + method.getName() + " return type").isEqualTo(void.class);
                lockNames.add(lock.name());
            }
        }
        assertThat(lockNames).containsExactlyInAnyOrderElementsOf(MarketplaceMetrics.SCHEDULED_JOBS);
    }
}
