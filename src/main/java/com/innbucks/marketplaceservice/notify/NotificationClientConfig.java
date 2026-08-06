package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.config.CorrelationIdPropagatingInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient beans for the two outbound notification gateways: the InnBucks
 * public notification API (email + SMS, authed — X-Api-Key + bearer from
 * /auth/third-party, handled in {@link EmailNotificationClient}) and the
 * WhatsApp gateway. Both are external services reached by an explicit
 * {@code base-url} (not Eureka), with the same correlation-ID propagation the
 * fleet uses so an order's traceId follows the notification across the wire.
 *
 * <p>Graceful degradation (booking-service posture): blank credentials mean
 * that channel is DISABLED — {@link #logChannelStatus()} WARNs once at boot,
 * the listeners record {@code outcome=disabled}, and nothing crashes. These
 * credentials are deliberately NOT in {@code ProductionSecretsGuard}'s
 * boot-required set: a cell without notification creds must still take orders.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({InnbucksNotifyProperties.class, WhatsAppProperties.class,
        MarketplaceNotificationProperties.class})
public class NotificationClientConfig {

    private final InnbucksNotifyProperties notifyProperties;
    private final WhatsAppProperties whatsAppProperties;

    public NotificationClientConfig(InnbucksNotifyProperties notifyProperties,
                                    WhatsAppProperties whatsAppProperties) {
        this.notifyProperties = notifyProperties;
        this.whatsAppProperties = whatsAppProperties;
    }

    @Bean("innbucksNotifyRestClient")
    public RestClient innbucksNotifyRestClient(InnbucksNotifyProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }

    @Bean("whatsAppRestClient")
    public RestClient whatsAppRestClient(WhatsAppProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        return RestClient.builder()
                // A blank base-url would make RestClient reject every call with a
                // cryptic "URI is not absolute"; the client never sends when the
                // channel is unconfigured, but the bean must still construct.
                .baseUrl(properties.isConfigured() ? properties.getBaseUrl() : "http://whatsapp-disabled.invalid")
                .requestFactory(factory)
                .requestInterceptor(new CorrelationIdPropagatingInterceptor())
                .build();
    }

    /**
     * The shipped {@link MerchantAdminResolver}: resolves nobody, because
     * user-service has no merchantId→admin-users internal lookup yet (see the
     * TODO on the interface). {@code @ConditionalOnMissingBean} so the day a
     * real S2S client lands, defining its bean replaces this one with no other
     * change.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(MerchantAdminResolver.class)
    public MerchantAdminResolver merchantAdminResolver() {
        return new MerchantAdminResolver.Unavailable();
    }

    /** One boot-time line per channel so an unprovisioned cell is visible in
     *  the startup log instead of surfacing as silent {@code outcome=disabled}
     *  metrics. WARN, never a crash — degradation is deliberate. */
    @jakarta.annotation.PostConstruct
    void logChannelStatus() {
        if (notifyProperties.isConfigured()) {
            log.info("InnBucks notification API channel (SMS/email) is configured");
        } else {
            log.warn("InnBucks notification API channel (SMS/email) is DISABLED — "
                    + "BANK_API_URL/BANK_API_KEY/BANK_API_USERNAME/BANK_API_PASSWORD not fully set; "
                    + "buyer order-paid SMS will record outcome=disabled");
        }
        if (whatsAppProperties.isConfigured()) {
            log.info("WhatsApp fallback channel is configured");
        } else {
            log.warn("WhatsApp fallback channel is DISABLED — WHATSAPP_GATEWAY_URL/WHATSAPP_API_KEY "
                    + "not set; failed SMS sends will not fall back");
        }
    }
}
