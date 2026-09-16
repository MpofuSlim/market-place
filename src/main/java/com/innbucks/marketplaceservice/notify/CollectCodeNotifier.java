package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Sends a collection code to whoever is collecting.
 *
 * <p><b>Synchronous and best-effort — deliberately neither of the two postures
 * the rest of the fleet uses.</b> The OTP path in the middleware rolls its
 * transaction back when the SMS fails, because there the message IS the
 * delivery channel and a challenge nobody received is worse than no challenge.
 * The order-paid listener, at the other extreme, is after-commit and async,
 * because nobody is waiting on it. This sits between them: the buyer already
 * has the code in their mint response, so a dead gateway costs the
 * convenience of forwarding it and nothing else — but they ARE watching the
 * screen, so the send happens inline and the response tells them the truth
 * about whether it went.
 *
 * <p>Never throws. The caller has minted a code and must return it whatever
 * the gateway did.
 */
@Slf4j
@Component
public class CollectCodeNotifier {

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final MarketplaceMetrics metrics;

    public CollectCodeNotifier(SmsNotificationClient sms, WhatsAppNotificationClient whatsApp,
                               MarketplaceMetrics metrics) {
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.metrics = metrics;
    }

    /**
     * @return the masked destination when a channel accepted the message, else
     *         null — which the mint response reports honestly rather than
     *         claiming a send that never happened
     */
    public String send(String msisdn, String orderRef, String groupedCode) {
        if (msisdn == null || msisdn.isBlank()) {
            return null;
        }
        String message = OrderNotificationComposer.collectCodeMessage(orderRef, groupedCode);
        if (sms.isConfigured()) {
            try {
                sms.sendSms(msisdn, message, orderRef);
                metrics.notificationOutcome("collect_code", "sent");
                return MsisdnMasking.mask(msisdn);
            } catch (RuntimeException ex) {
                log.warn("Collect-code SMS failed for {} orderRef={}, {}: {}",
                        MsisdnMasking.mask(msisdn), orderRef,
                        whatsApp.isConfigured() ? "falling back to WhatsApp"
                                : "no WhatsApp fallback configured",
                        ex.getMessage());
            }
        }
        if (whatsApp.isConfigured()) {
            try {
                whatsApp.sendCustomNotification(msisdn, message);
                metrics.notificationOutcome("collect_code", "fallback");
                return MsisdnMasking.mask(msisdn);
            } catch (RuntimeException ex) {
                log.warn("Collect-code WhatsApp fallback failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(msisdn), orderRef, ex.getMessage());
            }
        }
        metrics.notificationOutcome("collect_code",
                sms.isConfigured() || whatsApp.isConfigured() ? "failed" : "disabled");
        // The code is in the response either way; the buyer forwards it.
        return null;
    }
}
