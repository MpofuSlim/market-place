package com.innbucks.marketplaceservice.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables {@link org.springframework.scheduling.annotation.Async @Async}
 * app-wide and provides the THREE bounded executors the notification listeners
 * run on. Moving the SMS/WhatsApp/S2S sends off the committing thread means a
 * wedged notification gateway can never delay the payments service's
 * confirm-payment response, a seller's dispatch or a restocking cancel.
 *
 * <p><b>Separate pools, because one pool was not a bulkhead</b>
 * <ul>
 *   <li><b>{@value #NOTIFICATION_EXECUTOR}</b> — per-order notices: order
 *       paid, parcel progress, parcel unfulfilled, refund sent, dispute
 *       resolved, seller alerts. Each task is ONE recipient's message (or one
 *       seller's handful of admins), and several of them carry money-path
 *       meaning — the seller-marked "delivered" SMS is what tells the buyer
 *       their dispute window has started (the window itself runs from the
 *       parcel's {@code delivered_at}, sent or not).</li>
 *   <li><b>{@value #BULK_NOTIFICATION_EXECUTOR}</b> — fan-outs: one restock
 *       event is up to {@code max-recipients-per-event} (200) sequential calls
 *       to user-service, minutes of thread time when it is slow. On a shared
 *       pool a few viral restocks put every order-paid and parcel SMS behind
 *       marketing-grade traffic. Only the fan-out waits here.</li>
 *   <li><b>{@value #SECURITY_NOTIFICATION_EXECUTOR}</b> — the payout-destination
 *       change notice (V13), the anti-redirect control: "the seller's admins are
 *       told on EVERY change". It is the one notice whose loss is a security
 *       gap rather than an inconvenience, and the one whose only submitters are
 *       a seller's or an operator's own payout-destination request — never the
 *       money path. So its overflow runs ON THE CALLER (metered
 *       {@code policy=caller_runs}): under saturation that request pays the
 *       send, and the warning still goes out. Its own pool, so a per-order
 *       backlog can never delay it and its caller-runs can never be reached by
 *       a payment confirm.</li>
 * </ul>
 * <p>{@code CollectionOverdueSweeper}'s seller alerts are deliberately NOT handed
 * to the bulk pool: each is sent right after its at-most-once claim, so a
 * discard there would lose an already-claimed alert for good; they stay
 * synchronous on their own scheduler thread, which the scheduler pool
 * ({@code spring.task.scheduling.pool.size}, default 4) keeps from starving
 * the every-minute order-expiry sweep.
 *
 * <p>All three notification pools are fixed-size (core == max): a {@code ThreadPoolExecutor} only grows
 * past core once its queue is FULL, so "core 2 / max 4" really meant two
 * threads until the moment the pool was already drowning. Sizes are
 * env-overridable ({@code marketplace.executors.*}).
 *
 * <p><b>Rejection: DISCARD and meter — never run on the caller</b> (per-order
 * and bulk pools; the security pool is the documented exception above)
 * A full queue now drops the notification ({@link MeteredRejectionPolicy}:
 * rate-limited WARN + {@code marketplace.notifications.executor_rejected{executor,policy,reason}}).
 * The previous {@code CallerRunsPolicy} ran the listener INLINE on whatever
 * thread published the event — for an AFTER_COMMIT listener that is the thread
 * that just committed: payment-service's confirm-payment request, a seller's
 * dispatch, the expiry sweep. That is precisely the thread this class exists to
 * protect, and it inherited 30-50 s of SMS + WhatsApp timeouts exactly during a
 * provider incident, which is the only time the queue fills.
 *
 * <p>The trade-off, taken knowingly: a saturated pool LOSES that notice rather
 * than blocking the money path. It is the same bargain every listener here
 * already makes (fire-and-forget; a crash between commit and send loses the
 * message; the order, parcel and ledger rows are the record), and a queue that
 * full means the providers are hung, so running the send inline would mostly
 * have failed after its timeouts anyway. The per-order pool's queue is sized
 * large (500 by default — the tasks are tiny) so it drops only under a
 * sustained outage; alert on the counter. The bulk pool's queue is small on
 * purpose: a dropped restock batch is the cheapest loss the platform has.
 * <b>A dropped parcel notice records nothing</b>: {@code buyer_notice_*} and
 * {@code BUYER_NOT_NOTIFIED} are written by the listener itself, so a task that
 * never runs leaves the seller card showing the previous notice and alerts no
 * seller; the rejection counter is the only trace.
 * The handler must never THROW either — an exception from an {@code @Async}
 * submit would escape the after-commit callback into the committing caller.
 *
 * <p>All three pools (and the scheduler pool, {@code spring.task.scheduling.pool.size})
 * are {@code ThreadPoolTaskExecutor}/{@code ThreadPoolTaskScheduler} BEANS, so
 * Boot's executor metrics bind them: {@code executor.active},
 * {@code executor.queued}, {@code executor.queue.remaining} tagged
 * {@code name=<bean name>}. The bean methods declare the concrete type on
 * purpose — the binder resolves {@code TaskExecutor} candidates by type, and
 * a bean declared as plain {@code Executor} can be invisible to it.
 *
 * <p>Uncaught exceptions go to {@link SimpleAsyncUncaughtExceptionHandler}. The
 * listeners already swallow their own gateway exceptions; this handler is
 * defence in depth for anything that escapes. Pinned by {@code AsyncConfigTest},
 * {@code MeteredRejectionPolicyTest}, {@code NotificationExecutorRoutingTest} and
 * {@code NotificationExecutorsIT}.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** Per-order notices. Also the default for an unqualified {@code @Async}. */
    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    /** Fan-outs (restock alerts): many recipients per task, lowest value per message. */
    public static final String BULK_NOTIFICATION_EXECUTOR = "bulkNotificationExecutor";

    /** Security notices (payout-destination changes): overflow runs on the caller, never dropped. */
    public static final String SECURITY_NOTIFICATION_EXECUTOR = "securityNotificationExecutor";

    private final ObjectProvider<MeterRegistry> meterRegistry;
    private final int notificationThreads;
    private final int notificationQueueCapacity;
    private final int bulkThreads;
    private final int bulkQueueCapacity;
    private final int securityThreads;
    private final int securityQueueCapacity;

    public AsyncConfig(ObjectProvider<MeterRegistry> meterRegistry,
                       @Value("${marketplace.executors.notification.threads:4}") int notificationThreads,
                       @Value("${marketplace.executors.notification.queue-capacity:500}")
                       int notificationQueueCapacity,
                       @Value("${marketplace.executors.bulk-notification.threads:2}") int bulkThreads,
                       @Value("${marketplace.executors.bulk-notification.queue-capacity:50}")
                       int bulkQueueCapacity,
                       @Value("${marketplace.executors.security-notification.threads:1}") int securityThreads,
                       @Value("${marketplace.executors.security-notification.queue-capacity:50}")
                       int securityQueueCapacity) {
        this.meterRegistry = meterRegistry;
        this.notificationThreads = notificationThreads;
        this.notificationQueueCapacity = notificationQueueCapacity;
        this.bulkThreads = bulkThreads;
        this.bulkQueueCapacity = bulkQueueCapacity;
        this.securityThreads = securityThreads;
        this.securityQueueCapacity = securityQueueCapacity;
    }

    @Bean(name = NOTIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor notificationExecutor() {
        return boundedPool(NOTIFICATION_EXECUTOR, "marketplace-notify-",
                notificationThreads, notificationQueueCapacity, MeteredRejectionPolicy.Mode.DISCARD,
                meterRegistry.getIfAvailable());
    }

    @Bean(name = BULK_NOTIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor bulkNotificationExecutor() {
        return boundedPool(BULK_NOTIFICATION_EXECUTOR, "marketplace-notify-bulk-",
                bulkThreads, bulkQueueCapacity, MeteredRejectionPolicy.Mode.DISCARD,
                meterRegistry.getIfAvailable());
    }

    @Bean(name = SECURITY_NOTIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor securityNotificationExecutor() {
        return boundedPool(SECURITY_NOTIFICATION_EXECUTOR, "marketplace-notify-security-",
                securityThreads, securityQueueCapacity, MeteredRejectionPolicy.Mode.CALLER_RUNS,
                meterRegistry.getIfAvailable());
    }

    /**
     * A fixed-size, bounded pool whose overflow is metered and then discarded or
     * run on the submitter, per {@code overflow}. Package-private so the
     * saturation behaviour can be driven on a tiny pool in a test.
     */
    static ThreadPoolTaskExecutor boundedPool(String name, String threadNamePrefix, int threads,
                                              int queueCapacity, MeteredRejectionPolicy.Mode overflow,
                                              MeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new MeteredRejectionPolicy(name, overflow, registry));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return notificationExecutor();
    }

    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }
}
