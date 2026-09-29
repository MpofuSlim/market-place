package com.innbucks.marketplaceservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.task.TaskExecutorMetricsAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The executor and scheduler wiring, proved against Boot's REAL auto-config
 * (scheduling + executor metrics) and the REAL {@code application.yaml} —
 * not against hand-built pools, because what broke in production was
 * configuration: no scheduling pool size at all (Boot's default is one
 * thread), and one shared notification pool that ran overflow on the caller.
 */
class AsyncConfigTest {

    /** Longest the slow job waits for the fast one to run beside it. */
    private static final long OVERLAP_WAIT_MS = 1500;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    TaskSchedulingAutoConfiguration.class,
                    TaskExecutorMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(AsyncConfig.class, SchedulingOn.class);

    @Test
    @DisplayName("application.yaml gives the @Scheduled jobs a 4-thread pool, env-overridable")
    void schedulerPoolIsSizedFromTheYaml() {
        runner.run(ctx -> {
            // The CONFIGURED size (getPoolSize() is the live thread count, 0 until a job runs).
            assertThat(schedulerThreads(ctx.getBean("taskScheduler", ThreadPoolTaskScheduler.class)))
                    .isEqualTo(4);
        });
        runner.withPropertyValues("MARKETPLACE_SCHEDULER_POOL_SIZE=6").run(ctx ->
                assertThat(schedulerThreads(ctx.getBean("taskScheduler", ThreadPoolTaskScheduler.class)))
                        .isEqualTo(6));
    }

    @Test
    @DisplayName("A job blocked on the scheduler no longer stops another job from running")
    void aSlowJobDoesNotStarveAnother() {
        runner.withUserConfiguration(OverlappingJobs.class).run(ctx -> {
            OverlappingJobs jobs = ctx.getBean(OverlappingJobs.class);
            assertThat(jobs.firstSlowRunDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(jobs.fastRanWhileSlowWasBlocked.get()).isTrue();
        });
    }

    @Test
    @DisplayName("Negative control: on Boot's default single thread the same jobs run serially")
    void theOverlapTestDiscriminates() {
        runner.withPropertyValues("spring.task.scheduling.pool.size=1")
                .withUserConfiguration(OverlappingJobs.class).run(ctx -> {
                    OverlappingJobs jobs = ctx.getBean(OverlappingJobs.class);
                    assertThat(jobs.firstSlowRunDone.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(jobs.fastRanWhileSlowWasBlocked.get()).isFalse();
                });
    }

    @Test
    @DisplayName("Three fixed-size notification pools, named apart; per-order and bulk discard overflow, "
            + "the security pool runs it on the caller")
    void twoBoundedNotificationPools() {
        runner.run(ctx -> {
            ThreadPoolTaskExecutor perOrder =
                    ctx.getBean(AsyncConfig.NOTIFICATION_EXECUTOR, ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor bulk =
                    ctx.getBean(AsyncConfig.BULK_NOTIFICATION_EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(perOrder).isNotSameAs(bulk);

            assertThat(perOrder.getCorePoolSize()).isEqualTo(4);
            assertThat(perOrder.getMaxPoolSize()).isEqualTo(4);
            assertThat(perOrder.getQueueCapacity()).isEqualTo(500);
            assertThat(perOrder.getThreadNamePrefix()).isEqualTo("marketplace-notify-");

            assertThat(bulk.getCorePoolSize()).isEqualTo(2);
            assertThat(bulk.getMaxPoolSize()).isEqualTo(2);
            assertThat(bulk.getQueueCapacity()).isEqualTo(50);
            assertThat(bulk.getThreadNamePrefix()).isEqualTo("marketplace-notify-bulk-");

            ThreadPoolTaskExecutor security =
                    ctx.getBean(AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR, ThreadPoolTaskExecutor.class);
            assertThat(security).isNotSameAs(perOrder).isNotSameAs(bulk);
            assertThat(security.getCorePoolSize()).isEqualTo(1);
            assertThat(security.getMaxPoolSize()).isEqualTo(1);
            assertThat(security.getQueueCapacity()).isEqualTo(50);
            assertThat(security.getThreadNamePrefix()).isEqualTo("marketplace-notify-security-");

            assertThat(overflowMode(perOrder)).isEqualTo(MeteredRejectionPolicy.Mode.DISCARD);
            assertThat(overflowMode(bulk)).isEqualTo(MeteredRejectionPolicy.Mode.DISCARD);
            assertThat(overflowMode(security)).isEqualTo(MeteredRejectionPolicy.Mode.CALLER_RUNS);
            // An unqualified @Async lands on the per-order pool, never on a
            // SimpleAsyncTaskExecutor fallback.
            assertThat(ctx.getBean(AsyncConfig.class).getAsyncExecutor()).isSameAs(perOrder);
        });
    }

    @Test
    @DisplayName("Pool sizes are env-overridable")
    void poolSizesAreConfigurable() {
        runner.withPropertyValues("MARKETPLACE_NOTIFY_BULK_THREADS=3",
                        "MARKETPLACE_NOTIFY_BULK_QUEUE_CAPACITY=7",
                        "MARKETPLACE_NOTIFY_SECURITY_THREADS=2",
                        "MARKETPLACE_NOTIFY_SECURITY_QUEUE_CAPACITY=9")
                .run(ctx -> {
                    ThreadPoolTaskExecutor bulk = ctx.getBean(
                            AsyncConfig.BULK_NOTIFICATION_EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(bulk.getMaxPoolSize()).isEqualTo(3);
                    assertThat(bulk.getQueueCapacity()).isEqualTo(7);
                    ThreadPoolTaskExecutor security = ctx.getBean(
                            AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR, ThreadPoolTaskExecutor.class);
                    assertThat(security.getMaxPoolSize()).isEqualTo(2);
                    assertThat(security.getQueueCapacity()).isEqualTo(9);
                });
    }

    @Test
    @DisplayName("Boot's executor metrics bind both notification pools and the scheduler, "
            + "and the rejection series exist at 0 from boot")
    void everyPoolIsMetered() {
        runner.run(ctx -> {
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            for (String name : new String[] {AsyncConfig.NOTIFICATION_EXECUTOR,
                    AsyncConfig.BULK_NOTIFICATION_EXECUTOR, AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR,
                    "taskScheduler"}) {
                assertThat(registry.find("executor.queued").tag("name", name).gauge())
                        .as("executor.queued for %s", name).isNotNull();
                assertThat(registry.find("executor.active").tag("name", name).gauge())
                        .as("executor.active for %s", name).isNotNull();
            }
            for (String executor : new String[] {AsyncConfig.NOTIFICATION_EXECUTOR,
                    AsyncConfig.BULK_NOTIFICATION_EXECUTOR}) {
                assertThat(registry.get(MeteredRejectionPolicy.METRIC)
                        .tags("executor", executor, "policy", "discard", "reason", "saturated")
                        .counter().count()).isZero();
            }
            assertThat(registry.get(MeteredRejectionPolicy.METRIC)
                    .tags("executor", AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR,
                            "policy", "caller_runs", "reason", "saturated")
                    .counter().count()).isZero();
        });
    }

    static MeteredRejectionPolicy.Mode overflowMode(ThreadPoolTaskExecutor pool) {
        assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(MeteredRejectionPolicy.class);
        return ((MeteredRejectionPolicy) pool.getThreadPoolExecutor().getRejectedExecutionHandler()).mode();
    }

    static int schedulerThreads(ThreadPoolTaskScheduler scheduler) {
        return scheduler.getScheduledThreadPoolExecutor().getCorePoolSize();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class SchedulingOn {
    }

    /**
     * Two jobs: the slow one blocks until the fast one runs WHILE it is blocked
     * (or the wait runs out). On one scheduler thread the fast job can only run
     * when the slow one is not, so the answer is false; with a pool it is true.
     */
    static class OverlappingJobs {

        final CountDownLatch fastRanDuringSlow = new CountDownLatch(1);
        final CountDownLatch firstSlowRunDone = new CountDownLatch(1);
        final AtomicReference<Boolean> fastRanWhileSlowWasBlocked = new AtomicReference<>();
        private volatile boolean slowInProgress;

        @Scheduled(fixedDelay = 20)
        void slow() throws InterruptedException {
            if (firstSlowRunDone.getCount() == 0) {
                return;
            }
            slowInProgress = true;
            boolean overlapped = fastRanDuringSlow.await(OVERLAP_WAIT_MS, TimeUnit.MILLISECONDS);
            slowInProgress = false;
            fastRanWhileSlowWasBlocked.compareAndSet(null, overlapped);
            firstSlowRunDone.countDown();
        }

        @Scheduled(fixedDelay = 20)
        void fast() {
            if (slowInProgress) {
                fastRanDuringSlow.countDown();
            }
        }
    }
}
