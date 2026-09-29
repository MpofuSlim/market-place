package com.innbucks.marketplaceservice.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The overflow policy of the notification pools ({@link AsyncConfig}). Every
 * rejection is COUNTED and the handler never THROWS — an exception from an
 * {@code @Async} submit would escape an after-commit callback into a caller
 * whose commit already succeeded. What happens to the task depends on the pool:
 * <ul>
 *   <li>{@link Mode#DISCARD} — dropped, never run on the submitting thread.
 *       For the per-order and bulk pools, whose submitter can be the thread
 *       that just committed a payment confirm (that was {@code CallerRunsPolicy},
 *       which put SMS timeouts on it).</li>
 *   <li>{@link Mode#CALLER_RUNS} — run inline on the submitting thread. Only
 *       for the security pool, whose ONLY submitters are a seller's or an
 *       operator's own payout-destination request: a notice that must not be
 *       lost is worth slowing that one request down for.</li>
 * </ul>
 * A task submitted after the pool began draining on a graceful stop is
 * discarded in either mode (as {@code CallerRunsPolicy} does too), and counted
 * apart.
 *
 * <p>Counter {@code marketplace.notifications.executor_rejected} tagged
 * {@code executor} (bean name), {@code policy} ({@code discard} |
 * {@code caller_runs}) and {@code reason}: {@code saturated} (queue full —
 * alert on it: a provider is hung) or {@code shutdown} (expected in small
 * numbers during a rollout). Both series are registered at construction, so
 * they exist and read 0 from boot and an alert has a series to fire on.
 *
 * <p><b>The WARN is rate-limited, the counter is not.</b> Rejections only
 * happen during a sustained provider outage, and then every paid order, parcel
 * move and restock would add a line — flooding the log exactly when operators
 * are reading it. So the first rejection logs at once, then at most one line
 * per {@link #LOG_INTERVAL} per pool, carrying how many were rejected since the
 * last line. The line names the pool and its state only: the task is an opaque
 * {@code Runnable} wrapping a listener call whose arguments can carry a phone
 * number, so it is never logged.
 */
@Slf4j
final class MeteredRejectionPolicy implements RejectedExecutionHandler {

    static final String METRIC = "marketplace.notifications.executor_rejected";
    static final Duration LOG_INTERVAL = Duration.ofSeconds(60);

    enum Mode {
        DISCARD("discard"),
        CALLER_RUNS("caller_runs");

        final String tag;

        Mode(String tag) {
            this.tag = tag;
        }
    }

    private final String executorName;
    private final Mode mode;
    private final Counter saturated;
    private final Counter shutdown;
    private final LongSupplier nanoClock;
    private final long logIntervalNanos;
    private final AtomicLong nextLogAt;
    private final AtomicLong unlogged = new AtomicLong();

    /** {@code registry} may be null (a context without metrics): then only the WARN. */
    MeteredRejectionPolicy(String executorName, Mode mode, MeterRegistry registry) {
        this(executorName, mode, registry, System::nanoTime, LOG_INTERVAL);
    }

    MeteredRejectionPolicy(String executorName, Mode mode, MeterRegistry registry,
                           LongSupplier nanoClock, Duration logInterval) {
        this.executorName = executorName;
        this.mode = mode;
        this.saturated = counter(registry, executorName, mode, "saturated");
        this.shutdown = counter(registry, executorName, mode, "shutdown");
        this.nanoClock = nanoClock;
        this.logIntervalNanos = logInterval.toNanos();
        // Due now: the FIRST rejection always logs.
        this.nextLogAt = new AtomicLong(nanoClock.getAsLong());
    }

    Mode mode() {
        return mode;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
        boolean stopping = pool.isShutdown();
        Counter counter = stopping ? shutdown : saturated;
        if (counter != null) {
            counter.increment();
        }
        boolean runHere = mode == Mode.CALLER_RUNS && !stopping;
        maybeLog(pool, stopping, runHere);
        if (runHere) {
            try {
                task.run();
            } catch (RuntimeException ex) {
                // An @Async submit wraps the call in a FutureTask, which keeps
                // its own exception; this is defence in depth for anything else.
                log.warn("Notification run on the caller failed: executor={} cause={}",
                        executorName, ex.getClass().getSimpleName());
            }
        }
    }

    private void maybeLog(ThreadPoolExecutor pool, boolean stopping, boolean runHere) {
        unlogged.incrementAndGet();
        long now = nanoClock.getAsLong();
        long due = nextLogAt.get();
        if (now - due < 0 || !nextLogAt.compareAndSet(due, now + logIntervalNanos)) {
            return;
        }
        long count = unlogged.getAndSet(0);
        log.warn("Notification executor rejected tasks: executor={} policy={} reason={} "
                        + "rejectedSinceLastLog={} active={} queued={} poolSize={} - {}",
                executorName, mode.tag, stopping ? "shutdown" : "saturated", count,
                pool.getActiveCount(), pool.getQueue().size(), pool.getPoolSize(),
                runHere ? "run on the caller" : "discarded, not run on the caller");
    }

    private static Counter counter(MeterRegistry registry, String executor, Mode mode, String reason) {
        if (registry == null) {
            return null;
        }
        return Counter.builder(METRIC)
                .description("Notification tasks their executor could not accept "
                        + "(policy=discard: dropped; policy=caller_runs: run on the submitting thread)")
                .tag("executor", executor)
                .tag("policy", mode.tag)
                .tag("reason", reason)
                .register(registry);
    }
}
