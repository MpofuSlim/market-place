package com.innbucks.marketplaceservice.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Saturates TINY pools built by the production factory
 * ({@link AsyncConfig#boundedPool}) and pins the overflow contract of both
 * modes: DISCARD drops and counts the extra task and never runs it on the
 * submitting thread (which, for an after-commit listener, can be the thread
 * that just committed a payment confirm); CALLER_RUNS counts it and runs it on
 * the submitter. Neither throws. And the WARN is rate-limited while the counter
 * counts every rejection.
 */
class MeteredRejectionPolicyTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final CountDownLatch release = new CountDownLatch(1);
    private ThreadPoolTaskExecutor pool;

    @AfterEach
    void stop() {
        release.countDown();
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("DISCARD: a full pool drops the task, counts it and never runs it on the caller")
    void saturationDropsAndMetersWithoutRunningOnTheCaller() throws Exception {
        pool = AsyncConfig.boundedPool("tinyExecutor", "tiny-", 1, 1,
                MeteredRejectionPolicy.Mode.DISCARD, registry);
        List<String> ranOn = saturate(pool);

        // These two have nowhere to go. Neither may throw at the submitter,
        // and neither may run here.
        String caller = Thread.currentThread().getName();
        assertThatCode(() -> pool.execute(() -> ranOn.add(caller + "-inline"))).doesNotThrowAnyException();
        assertThatCode(() -> pool.submit(() -> ranOn.add(caller + "-inline"))).doesNotThrowAnyException();

        assertThat(count("tinyExecutor", "discard", "saturated")).isEqualTo(2.0);
        assertThat(ranOn).noneMatch(name -> name.startsWith(caller));

        // The queued task still runs once the worker frees up: only the
        // overflow was lost.
        release.countDown();
        pool.shutdown();
        assertThat(pool.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ranOn).hasSize(2).allMatch(name -> name.startsWith("tiny-"));
    }

    @Test
    @DisplayName("CALLER_RUNS: a full pool runs the task on the submitter and counts it")
    void callerRunsRunsOnTheSubmitterAndMeters() throws Exception {
        pool = AsyncConfig.boundedPool("securityTiny", "security-tiny-", 1, 1,
                MeteredRejectionPolicy.Mode.CALLER_RUNS, registry);
        List<String> ranOn = saturate(pool);

        String caller = Thread.currentThread().getName();
        assertThatCode(() -> pool.execute(() -> ranOn.add(Thread.currentThread().getName())))
                .doesNotThrowAnyException();
        // A task that throws on the caller still never throws at the submitter.
        assertThatCode(() -> pool.execute(() -> {
            throw new IllegalStateException("gateway down");
        })).doesNotThrowAnyException();

        assertThat(count("securityTiny", "caller_runs", "saturated")).isEqualTo(2.0);
        assertThat(ranOn).contains(caller);
    }

    @Test
    @DisplayName("A submit after shutdown is counted apart from saturation and never run, in either mode")
    void submissionsAfterShutdownAreTaggedShutdown() {
        for (MeteredRejectionPolicy.Mode mode : MeteredRejectionPolicy.Mode.values()) {
            String name = "stopping-" + mode.tag;
            ThreadPoolTaskExecutor stopping = AsyncConfig.boundedPool(name, "stopping-", 1, 1, mode, registry);
            stopping.shutdown();
            List<String> ran = new CopyOnWriteArrayList<>();

            assertThatCode(() -> stopping.execute(() -> ran.add("ran"))).doesNotThrowAnyException();

            assertThat(count(name, mode.tag, "shutdown")).isEqualTo(1.0);
            assertThat(count(name, mode.tag, "saturated")).isZero();
            assertThat(ran).as("a task submitted to a draining %s pool", mode).isEmpty();
        }
    }

    @Test
    @DisplayName("The WARN is rate-limited per pool and carries the count; the counter sees every rejection")
    void warnIsRateLimitedButEveryRejectionIsCounted() {
        AtomicLong clock = new AtomicLong(1_000L);
        MeteredRejectionPolicy policy = new MeteredRejectionPolicy("floodExecutor",
                MeteredRejectionPolicy.Mode.DISCARD, registry, clock::get, Duration.ofSeconds(60));
        ThreadPoolExecutor bare = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        Logger logger = (Logger) LoggerFactory.getLogger(MeteredRejectionPolicy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            policy.rejectedExecution(() -> { }, bare);              // first: logs at once
            for (int i = 0; i < 99; i++) {
                policy.rejectedExecution(() -> { }, bare);          // inside the interval: silent
            }
            clock.addAndGet(Duration.ofSeconds(61).toNanos());
            policy.rejectedExecution(() -> { }, bare);              // next window: one line for 100

            List<ILoggingEvent> warns = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN).toList();
            assertThat(warns).hasSize(2);
            assertThat(warns.get(0).getFormattedMessage()).contains("executor=floodExecutor")
                    .contains("rejectedSinceLastLog=1");
            assertThat(warns.get(1).getFormattedMessage()).contains("rejectedSinceLastLog=100");
            assertThat(count("floodExecutor", "discard", "saturated")).isEqualTo(101.0);
        } finally {
            logger.detachAppender(appender);
            bare.shutdownNow();
        }
    }

    @Test
    @DisplayName("Without a meter registry the policy still discards, never throws")
    void worksWithoutARegistry() {
        MeteredRejectionPolicy policy = new MeteredRejectionPolicy("bare",
                MeteredRejectionPolicy.Mode.DISCARD, null);
        pool = AsyncConfig.boundedPool("bare", "bare-", 1, 1, MeteredRejectionPolicy.Mode.DISCARD, null);
        assertThatCode(() -> policy.rejectedExecution(() -> { }, pool.getThreadPoolExecutor()))
                .doesNotThrowAnyException();
    }

    /** One task occupies the only thread (until {@link #release}), one fills the one-slot queue. */
    private List<String> saturate(ThreadPoolTaskExecutor target) throws InterruptedException {
        List<String> ranOn = new CopyOnWriteArrayList<>();
        CountDownLatch workerBusy = new CountDownLatch(1);
        target.execute(() -> {
            ranOn.add(Thread.currentThread().getName());
            workerBusy.countDown();
            await(release);
        });
        assertThat(workerBusy.await(5, TimeUnit.SECONDS)).isTrue();
        target.execute(() -> ranOn.add(Thread.currentThread().getName()));
        return ranOn;
    }

    private double count(String executor, String policy, String reason) {
        return registry.get(MeteredRejectionPolicy.METRIC)
                .tags("executor", executor, "policy", policy, "reason", reason)
                .counter().count();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
