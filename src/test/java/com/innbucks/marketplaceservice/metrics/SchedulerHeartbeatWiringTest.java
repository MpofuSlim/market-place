package com.innbucks.marketplaceservice.metrics;

import com.innbucks.marketplaceservice.audit.AuditEventRepository;
import com.innbucks.marketplaceservice.audit.AuditIntegrityVerifier;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.variant.VariantStockDriftSweeper;
import com.innbucks.marketplaceservice.fulfilment.CollectionOverdueSweeper;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.notify.SellerAlertService;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderExpirySweeper;
import com.innbucks.marketplaceservice.order.OrderService;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementReleaseSweeper;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import com.innbucks.marketplaceservice.settlement.StaleEscrowSweeper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every real {@code @Scheduled} job runs its pass through the heartbeat UNDER
 * ITS OWN {@code @SchedulerLock} NAME. {@link SchedulerHeartbeatTest}'s scan
 * proves each lock name is listed in {@link MarketplaceMetrics#SCHEDULED_JOBS};
 * it cannot prove the method body actually calls {@code runScheduledJob}, or
 * with the matching tag. A job listed but not wrapped (or wrapped under a
 * typo'd tag) would ship with a {@code last_success} stuck at 0 — a staleness
 * alert that fires forever for a healthy job, or never for a dead one.
 *
 * <p>Each job is built with mocked collaborators and invoked through the method
 * that carries {@code @Scheduled}; the tag asserted is read off that method's
 * annotation, so the wiring is proven rather than restated. The case list must
 * cover {@code SCHEDULED_JOBS} exactly, so a new job fails here until it has a
 * case. (The ShedLock proxy path itself is driven by
 * {@code VariantStockDriftSweeperIT}.)
 */
class SchedulerHeartbeatWiringTest {

    /** A mock whose every call throws: the collaborator is "down". */
    private static <T> T down(Class<T> type) {
        return mock(type, invocation -> {
            throw new IllegalStateException("collaborator down");
        });
    }

    /** A job factory: (metrics, failing?) -> the job instance. */
    record Job(String label, BiFunction<MarketplaceMetrics, Boolean, Object> factory) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Job> jobs() {
        return Stream.of(
                new Job("OrderExpirySweeper", (metrics, failing) -> new OrderExpirySweeper(
                        failing ? down(MarketOrderRepository.class) : mock(MarketOrderRepository.class),
                        mock(OrderService.class), metrics)),
                new Job("SettlementReleaseSweeper", (metrics, failing) -> new SettlementReleaseSweeper(
                        failing ? down(MerchantSettlementRepository.class)
                                : mock(MerchantSettlementRepository.class),
                        mock(SettlementService.class), metrics)),
                new Job("StaleEscrowSweeper", (metrics, failing) -> new StaleEscrowSweeper(
                        failing ? down(SettlementService.class) : mock(SettlementService.class),
                        metrics)),
                new Job("CollectionOverdueSweeper", (metrics, failing) -> new CollectionOverdueSweeper(
                        failing ? down(OrderFulfilmentRepository.class)
                                : mock(OrderFulfilmentRepository.class),
                        mock(SellerAlertService.class), metrics, 7)),
                new Job("VariantStockDriftSweeper", (metrics, failing) -> new VariantStockDriftSweeper(
                        failing ? down(ListingRepository.class) : mock(ListingRepository.class),
                        metrics)),
                new Job("AuditIntegrityVerifier", (metrics, failing) -> {
                    AuditEventRepository repository;
                    if (failing) {
                        repository = down(AuditEventRepository.class);
                    } else {
                        repository = mock(AuditEventRepository.class);
                        when(repository.findAll(any(Pageable.class))).thenReturn(Page.empty());
                    }
                    return new AuditIntegrityVerifier(repository, mock(AuditService.class), metrics, 5000);
                }));
    }

    private static Method scheduledMethod(Object job) {
        List<Method> scheduled = Arrays.stream(job.getClass().getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .toList();
        assertThat(scheduled).as(job.getClass().getSimpleName() + " @Scheduled methods").hasSize(1);
        return scheduled.getFirst();
    }

    private static String lockName(Method method) {
        return method.getAnnotation(SchedulerLock.class).name();
    }

    /** Runs the scheduled method; a rethrown pass failure is expected, not an error. */
    private static void run(Object job, Method method) throws Exception {
        try {
            method.invoke(job);
        } catch (InvocationTargetException ex) {
            if (!(ex.getCause() instanceof IllegalStateException)) {
                throw ex;
            }
        }
    }

    private static double lastSuccess(SimpleMeterRegistry registry, String job) {
        return registry.find("marketplace.scheduler.last_success").tag("job", job).gauge().value();
    }

    private static double failures(SimpleMeterRegistry registry, String job) {
        return registry.find("marketplace.scheduler.failures").tag("job", job).counter().count();
    }

    @ParameterizedTest(name = "{0}: a clean pass stamps last_success under its lock name")
    @MethodSource("jobs")
    void aCleanPassStampsItsOwnHeartbeat(Job job) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MarketplaceMetrics metrics = new MarketplaceMetrics(registry);
        Object instance = job.factory().apply(metrics, false);
        Method method = scheduledMethod(instance);
        String name = lockName(method);

        run(instance, method);

        assertThat(lastSuccess(registry, name)).as(name).isPositive();
        assertThat(failures(registry, name)).as(name).isZero();
        // Stamped under ITS name only — no other job's series moved.
        for (String other : MarketplaceMetrics.SCHEDULED_JOBS) {
            if (!other.equals(name)) {
                assertThat(lastSuccess(registry, other)).as(other).isZero();
            }
        }
    }

    @ParameterizedTest(name = "{0}: a failed pass counts a failure under its lock name")
    @MethodSource("jobs")
    void aFailedPassCountsItsOwnFailure(Job job) throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MarketplaceMetrics metrics = new MarketplaceMetrics(registry);
        Object instance = job.factory().apply(metrics, true);
        Method method = scheduledMethod(instance);
        String name = lockName(method);

        run(instance, method);

        assertThat(failures(registry, name)).as(name).isEqualTo(1.0);
        assertThat(lastSuccess(registry, name)).as(name).isZero();
    }

    @Test
    void theCasesCoverEveryScheduledJob() {
        MarketplaceMetrics metrics = new MarketplaceMetrics(new SimpleMeterRegistry());
        List<String> covered = jobs()
                .map(job -> lockName(scheduledMethod(job.factory().apply(metrics, false))))
                .toList();
        assertThat(covered).containsExactlyInAnyOrderElementsOf(MarketplaceMetrics.SCHEDULED_JOBS);
    }
}
