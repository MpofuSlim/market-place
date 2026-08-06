package com.innbucks.marketplaceservice.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config for the external WhatsApp notification gateway used as the FALLBACK
 * channel for buyer order-paid alerts. Third-party service (not in Eureka), so
 * consumed via a plain RestClient with an explicit {@code base-url}. Same
 * {@code WHATSAPP_*} env-var convention as booking/user/payment/loyalty, so
 * the marketplace reads the same values the cell already provisions.
 *
 * <p>Blank {@code api-key} = channel disabled (boot-time WARN, no fallback
 * attempted) — deliberately a blank default here, unlike booking's
 * {@code change-me} placeholder, so an unprovisioned cell degrades instead of
 * posting a placeholder key at the gateway.
 */
@Data
@ConfigurationProperties(prefix = "whatsapp")
public class WhatsAppProperties {
    private String baseUrl;
    private String apiKey;
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 10000;

    /** True when the gateway is reachable in principle (base-url + api-key set). */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }
}
