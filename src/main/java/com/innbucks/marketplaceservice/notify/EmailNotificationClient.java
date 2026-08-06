package com.innbucks.marketplaceservice.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Sends plain-text email AND SMS through the InnBucks public notification API:
 * {@code POST /api/notification/email} and {@code POST /api/notification/sms} on
 * the same API Gateway the payment rail uses. Auth mirrors the fleet's proven
 * client (booking-service, copied here — the marketplace depends on no fleet
 * module): an {@code X-Api-Key} header plus a bearer token obtained from
 * {@code POST /auth/third-party} (cached until its JWT {@code exp}, refreshed
 * once on a 401).
 *
 * <p>Wire bodies: email {@code {subject, message, reference, destinationEmail}},
 * SMS {@code {message, reference, destinationMsisdn}}. The endpoint validates
 * {@code reference} (max ~46 chars) and charset-validates the email
 * {@code subject} and the SMS {@code message} (GSM/ASCII only — an em-dash
 * draws 400); all are normalised here so callers can't trip them. The email
 * body accepts Unicode and is passed through untouched.
 *
 * <p>Any rejection / connectivity failure surfaces as
 * {@link NotificationDeliveryException} so callers keep best-effort semantics.
 * Never logs the subject, message body, or an unmasked MSISDN.
 */
@Slf4j
@Component
public class EmailNotificationClient {

    private static final String LOGIN_PATH = "/auth/third-party";
    private static final String EMAIL_PATH = "/api/notification/email";
    private static final String SMS_PATH = "/api/notification/sms";
    private static final String API_KEY_HEADER = "X-Api-Key";
    /** Longest reference observed to pass the notification API's validation. */
    private static final int MAX_REFERENCE_LENGTH = 46;

    private final RestClient restClient;
    private final InnbucksNotifyProperties properties;
    private final ObjectMapper objectMapper;
    /**
     * Own-SMTP delivery (SES). When enabled it replaces the notification
     * API for EMAIL only — SMS keeps riding the API either way.
     */
    private final SmtpEmailSender smtpEmailSender;

    private String accessToken;
    private Instant tokenExpiry = Instant.EPOCH;

    @org.springframework.beans.factory.annotation.Autowired
    public EmailNotificationClient(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                   InnbucksNotifyProperties properties,
                                   ObjectMapper objectMapper,
                                   SmtpEmailSender smtpEmailSender) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.smtpEmailSender = smtpEmailSender;
    }

    /**
     * Notification-API-only constructor: no SMTP sender, so
     * {@link #sendEmail} always takes the API path. Used by the contract
     * tests, which pin that wire format.
     */
    public EmailNotificationClient(@Qualifier("innbucksNotifyRestClient") RestClient restClient,
                                   InnbucksNotifyProperties properties,
                                   ObjectMapper objectMapper) {
        this(restClient, properties, objectMapper, null);
    }

    /**
     * True when the notification-API credentials are present. Callers use this
     * for the graceful-degradation check ({@code outcome=disabled}) BEFORE
     * attempting a send — {@link #sendEmail}/{@link #sendSms} still throw on an
     * unconfigured call, so nothing silently no-ops.
     */
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /**
     * Send a plain-text email to a single recipient.
     *
     * @throws NotificationDeliveryException on blank input, missing config, an
     *         upstream rejection, or a connectivity failure.
     */
    public void sendEmail(String to, String subject, String message, String reference) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("Email recipient is blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new NotificationDeliveryException("Email subject is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("Email message is blank");
        }
        // Branded HTML (default — the gateway renders the message field as HTML)
        // or, as a rollback, plain text closed with the standard footer. Built
        // here so both delivery paths below send the identical body.
        String body = properties.isHtmlEnabled()
                ? BrandedEmailRenderer.render(subject, message, properties.getLogoUrl())
                : EmailSignature.appendTo(message);

        // Own-SMTP first when this cell is configured for it (app.mail.enabled).
        // It is the only path where WE compose the From header, so it is the
        // only way the message can present its own display name (e.g.
        // "InnBucks Marketplace") instead of whatever the shared notification
        // API stamps on every product's mail. The API stays as the fallback
        // below, so a broken SES config degrades to the previous behaviour
        // rather than silently dropping the message.
        if (smtpEmailSender != null && smtpEmailSender.isEnabled()) {
            try {
                smtpEmailSender.send(to, subject, body, properties.isHtmlEnabled());
                log.info("Email delivered via SMTP");
                return;
            } catch (RuntimeException e) {
                log.warn("SMTP email delivery failed, falling back to the notification API: {}",
                        e.getMessage());
            }
        }
        requireConfigured();
        String candidate = (reference != null && !reference.isBlank())
                ? reference
                : "MKT-EMAIL-" + UUID.randomUUID();
        // Observed live by the ticketing fleet (2026-07-23): the email endpoint
        // 400s {"errors":["Invalid reference"]} for references longer than ~46
        // chars. Clamp defensively so a long caller-supplied ref degrades to a
        // truncated trace id instead of killing the whole send. Final so the
        // auth-retry lambda can capture it.
        final String ref;
        if (candidate.length() > MAX_REFERENCE_LENGTH) {
            ref = candidate.substring(0, MAX_REFERENCE_LENGTH);
            log.warn("Email reference '{}' exceeds {} chars — clamped to '{}'",
                    candidate, MAX_REFERENCE_LENGTH, ref);
        } else {
            ref = candidate;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        // Observed live (2026-07-23): the email endpoint ALSO charset-validates
        // the SUBJECT — an em-dash draws 400 {"errors":["Invalid subject"]}
        // while the identical ASCII text is accepted. The message body was NOT
        // flagged on the same sends, so only the subject is transliterated;
        // the body keeps its typography.
        payload.put("subject", SmsTextSanitizer.toGsmSafe(subject));
        payload.put("message", body);
        payload.put("reference", ref);
        payload.put("destinationEmail", to);

        withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(EMAIL_PATH)
                        .header(API_KEY_HEADER, properties.getApiKey())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401) {
                    throw new UnauthorizedException();
                }
                log.warn("Notification API rejected email ref={} status={} body={}",
                        ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected email: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for email ref={} error={}", ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
        log.info("Email notification accepted by notification API ref={}", ref);
    }

    /**
     * Send a plain-text SMS to a single MSISDN through the same authenticated
     * notification API as {@link #sendEmail} ({@code POST /api/notification/sms},
     * body {@code {message, reference, destinationMsisdn}}). Never logs the
     * message body or an unmasked MSISDN.
     *
     * @throws NotificationDeliveryException on blank input, missing config, an
     *         upstream rejection, or a connectivity failure.
     */
    public void sendSms(String to, String message, String reference) {
        if (to == null || to.isBlank()) {
            throw new NotificationDeliveryException("SMS recipient is blank");
        }
        if (message == null || message.isBlank()) {
            throw new NotificationDeliveryException("SMS message is blank");
        }
        requireConfigured();
        String ref = (reference != null && !reference.isBlank())
                ? reference
                : "MKT-SMS-" + UUID.randomUUID();

        Map<String, Object> payload = new LinkedHashMap<>();
        // GSM-7 safety: the SMS gateway rejects non-GSM/non-ASCII chars (e.g. the
        // em-dash in a "— The InnBucks Team" sign-off) with 400 "Invalid message".
        // Transliterate to ASCII on the SMS path only (email keeps its typography).
        payload.put("message", SmsTextSanitizer.toGsmSafe(message));
        payload.put("reference", ref);
        payload.put("destinationMsisdn", to);

        withAuthRetryOn401(token -> {
            try {
                restClient.post()
                        .uri(SMS_PATH)
                        .header(API_KEY_HEADER, properties.getApiKey())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() == 401) {
                    throw new UnauthorizedException();
                }
                log.warn("Notification API rejected SMS to={} ref={} status={} body={}",
                        MsisdnMasking.mask(to), ref, ex.getStatusCode(), ex.getResponseBodyAsString());
                throw new NotificationDeliveryException(
                        "Notification API rejected SMS: HTTP " + ex.getStatusCode().value(), ex);
            } catch (NotificationDeliveryException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn("Notification API unreachable for SMS to={} ref={} error={}",
                        MsisdnMasking.mask(to), ref, ex.getMessage());
                throw new NotificationDeliveryException(
                        "Notification API unreachable: " + ex.getMessage(), ex);
            }
        });
        log.info("SMS notification accepted by notification API to={} ref={}",
                MsisdnMasking.mask(to), ref);
    }

    /** Run an authed call; on 401, force one token refresh and replay once. */
    private <T> T withAuthRetryOn401(Function<String, T> call) {
        try {
            return call.apply(currentToken(false));
        } catch (UnauthorizedException first) {
            log.info("Notification API returned 401 — refreshing token and replaying once");
            try {
                return call.apply(currentToken(true));
            } catch (UnauthorizedException second) {
                throw new NotificationDeliveryException(
                        "Notification API rejected our credentials twice (401) — check BANK_API_USERNAME/PASSWORD/KEY");
            }
        }
    }

    private synchronized String currentToken(boolean force) {
        if (!force && accessToken != null && Instant.now().isBefore(tokenExpiry)) {
            return accessToken;
        }
        try {
            String raw = restClient.post()
                    .uri(LOGIN_PATH)
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", properties.getUsername(),
                            "password", properties.getPassword()))
                    .retrieve()
                    .body(String.class);
            Map<String, Object> parsed = parseJson(raw);
            Object token = parsed.get("accessToken");
            if (token == null || token.toString().isBlank()) {
                throw new NotificationDeliveryException("Notification API login returned no accessToken");
            }
            accessToken = token.toString();
            tokenExpiry = deriveExpiry(accessToken).minusSeconds(30);
            log.info("Notification API login succeeded; token cached until {}", tokenExpiry);
            return accessToken;
        } catch (RestClientResponseException e) {
            throw new NotificationDeliveryException(
                    "Notification API login failed: HTTP " + e.getStatusCode().value(), e);
        } catch (NotificationDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NotificationDeliveryException(
                    "Unable to reach the notification API for login: " + e.getMessage(), e);
        }
    }

    /** Best-effort JWT exp parse; falls back to the configured token TTL. */
    private Instant deriveExpiry(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                Object exp = parseJson(payloadJson).get("exp");
                if (exp instanceof Number n) {
                    return Instant.ofEpochSecond(n.longValue());
                }
            }
        } catch (RuntimeException ignored) {
            // Opaque token — fall through to TTL.
        }
        return Instant.now().plus(properties.getTokenTtl());
    }

    private Map<String, Object> parseJson(String raw) {
        try {
            return objectMapper.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new NotificationDeliveryException("Notification API returned an unparseable response", e);
        }
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new NotificationDeliveryException(
                    "Notification API is not configured — set BANK_API_URL/BANK_API_KEY/BANK_API_USERNAME/BANK_API_PASSWORD");
        }
    }

    /** Internal marker for a 401 so {@link #withAuthRetryOn401} can replay once. */
    private static final class UnauthorizedException extends RuntimeException {
    }
}
