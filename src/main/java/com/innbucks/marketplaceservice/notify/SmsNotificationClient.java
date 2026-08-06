package com.innbucks.marketplaceservice.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends SMS through the InnBucks public notification API
 * ({@code POST /api/notification/sms}) — the SAME authenticated gateway the
 * email client uses (an {@code X-Api-Key} header plus a bearer obtained from
 * {@code POST /auth/third-party}).
 *
 * <p>The auth/token machinery lives in {@link EmailNotificationClient} (the
 * notification-API client), so this delegates to it and shares its cached
 * bearer — the booking-service shape, kept as a distinct bean so callers
 * ({@code OrderPaidNotificationListener}) name the channel they mean.
 * Failures surface as {@link NotificationDeliveryException} so callers keep
 * best-effort semantics.
 */
@Slf4j
@Component
public class SmsNotificationClient {

    private final EmailNotificationClient notificationApi;

    public SmsNotificationClient(EmailNotificationClient notificationApi) {
        this.notificationApi = notificationApi;
    }

    /** True when the BANK_API_* credentials are present (channel enabled). */
    public boolean isConfigured() {
        return notificationApi.isConfigured();
    }

    /**
     * Dispatch an SMS to {@code destination} (E.164). Delegates to the shared
     * notification-API client ({@link EmailNotificationClient#sendSms}); a
     * non-2xx or connectivity failure becomes a
     * {@link NotificationDeliveryException} so the caller can fall back.
     */
    public void sendSms(String destination, String message, String reference) {
        notificationApi.sendSms(destination, message, reference);
    }
}
