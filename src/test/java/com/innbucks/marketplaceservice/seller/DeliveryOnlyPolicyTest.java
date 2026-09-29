package com.innbucks.marketplaceservice.seller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.innbucks.marketplaceservice.checkout.CheckoutProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The boot WARN that says a cell's two V20 switches disagree: delivery-only
 * sellers ON with per-seller methods OFF is the one configuration in which a
 * basket mixing a delivery-only and a collection-only seller cannot be checked
 * out as one order. A WARN, never an ERROR - both switches are legitimate on
 * their own - and silent in every other combination.
 */
class DeliveryOnlyPolicyTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(DeliveryOnlyPolicy.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void capture() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
    }

    private static CheckoutProperties perSeller(boolean enabled) {
        CheckoutProperties properties = new CheckoutProperties();
        properties.getDelivery().setPerSellerMethodsEnabled(enabled);
        return properties;
    }

    @Test
    @DisplayName("Delivery-only ON with per-seller methods OFF logs ONE WARN naming the stranded basket")
    void warnsWhenMixedBasketsAreStranded() {
        DeliveryOnlyPolicy policy = new DeliveryOnlyPolicy(true, perSeller(false));

        assertThat(policy.mixedBasketsStranded()).isTrue();
        List<ILoggingEvent> events = appender.list;
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains(
                    "a basket mixing a delivery-only and a collection-only seller cannot be "
                            + "checked out in one order");
        });
    }

    @Test
    @DisplayName("Silent when per-seller methods are on, or delivery-only sellers are off")
    void silentOtherwise() {
        assertThat(new DeliveryOnlyPolicy(true, perSeller(true)).mixedBasketsStranded()).isFalse();
        assertThat(new DeliveryOnlyPolicy(false, perSeller(false)).mixedBasketsStranded()).isFalse();
        assertThat(new DeliveryOnlyPolicy(false, perSeller(true)).mixedBasketsStranded()).isFalse();

        assertThat(appender.list).isEmpty();
    }
}
