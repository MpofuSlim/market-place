package com.innbucks.marketplaceservice.config;

import com.innbucks.marketplaceservice.favorite.RestockAlertListener;
import com.innbucks.marketplaceservice.notify.PayoutDestinationNotificationListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which pool every {@code @Async} in the service runs on, read off the
 * annotations themselves. The bulkhead only holds while fan-outs stay OFF the
 * per-order pool and per-order notices stay OFF the bulk one — and an
 * unqualified {@code @Async} silently lands on whatever the default executor
 * happens to be, so every one must name its pool.
 */
class NotificationExecutorRoutingTest {

    /** The fan-outs: many recipients per task. Everything else is per-order. */
    private static final Set<String> BULK = Set.of(
            RestockAlertListener.class.getSimpleName() + "#onRestock");

    /**
     * Security notices whose loss is a gap, not an inconvenience: the pool whose
     * overflow runs on the caller. Only a listener whose every publisher is a
     * seller's/operator's own request may be added here — never one reachable
     * from payment-service's confirm-payment.
     */
    private static final Set<String> SECURITY = Set.of(
            PayoutDestinationNotificationListener.class.getSimpleName() + "#onPayoutDestinationChanged");

    @Test
    @DisplayName("Restock fan-out runs on the bulk pool, the payout-change warning on the security pool; "
            + "every other @Async names the per-order pool")
    void everyAsyncNamesTheRightPool() throws Exception {
        Map<String, String> routing = asyncRouting();

        assertThat(routing).as("the scan found the listeners").hasSizeGreaterThanOrEqualTo(10);
        assertThat(routing).containsKeys(BULK.toArray(String[]::new));
        assertThat(routing).containsKeys(SECURITY.toArray(String[]::new));
        routing.forEach((method, executor) -> {
            String expected = BULK.contains(method) ? AsyncConfig.BULK_NOTIFICATION_EXECUTOR
                    : SECURITY.contains(method) ? AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR
                    : AsyncConfig.NOTIFICATION_EXECUTOR;
            assertThat(executor).as("@Async executor of %s", method).isEqualTo(expected);
        });
    }

    private static Map<String, String> asyncRouting() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        Map<String, String> routing = new TreeMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.innbucks.marketplaceservice")) {
            Class<?> type = ClassUtils.forName(candidate.getBeanClassName(),
                    NotificationExecutorRoutingTest.class.getClassLoader());
            for (Method method : type.getDeclaredMethods()) {
                Async async = method.getAnnotation(Async.class);
                if (async != null) {
                    routing.put(type.getSimpleName() + "#" + method.getName(), async.value());
                }
            }
        }
        return routing;
    }
}
