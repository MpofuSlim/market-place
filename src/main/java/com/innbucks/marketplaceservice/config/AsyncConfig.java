package com.innbucks.marketplaceservice.config;

import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables {@link org.springframework.scheduling.annotation.Async @Async}
 * app-wide and provides the bounded executor the notification listeners run on
 * (fleet copy — InnRewards' AsyncConfig). Moving the SMS/WhatsApp/S2S fan-out
 * off the committing thread means a wedged notification gateway can never
 * delay the payments service's confirm-payment response or a restocking
 * cancel/update.
 *
 * <p>The pool is deliberately small + bounded:
 * <ul>
 *   <li>core 2 / max 4 covers order-paid + restock alert rates, not customer
 *       traffic.</li>
 *   <li>Queue capacity 100: absorbs a restock fan-out burst (the per-event
 *       recipient cap keeps a single event from monopolising it); small
 *       enough to surface an upstream stall.</li>
 *   <li>{@link ThreadPoolExecutor.CallerRunsPolicy}: if the queue fills, the
 *       caller runs the task itself rather than dropping a notification
 *       silently — at worst that one burst degrades to inline delivery.</li>
 *   <li>Uncaught exceptions go to {@link SimpleAsyncUncaughtExceptionHandler}.
 *       The listeners already swallow their own gateway exceptions; this
 *       handler is defence in depth for anything that escapes.</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("marketplace-notify-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
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
