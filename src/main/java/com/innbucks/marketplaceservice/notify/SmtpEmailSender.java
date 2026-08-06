package com.innbucks.marketplaceservice.notify;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Sends email straight over SMTP (Amazon SES) instead of through the InnBucks
 * notification API. Copied from booking-service.
 *
 * <p><b>Why this exists.</b> The notification API composes the {@code From}
 * header itself, so every product that rides it arrives under the same display
 * name ("Online Banking"). Sending our own message lets this service present
 * its own identity: {@code InnBucks Marketplace <banking@innbucks.co.zw>}. The
 * address stays the platform's verified SES identity; only the display name is
 * ours.
 *
 * <p><b>Opt-in.</b> Inactive unless {@code app.mail.enabled=true} AND a
 * {@code JavaMailSender} exists (which Spring only auto-configures when
 * {@code spring.mail.host} is set). Both unset is the default, so merging this
 * changes nothing until a cell provisions SES credentials —
 * {@link #isEnabled()} lets the caller decide whether to use this path or the
 * notification API.
 *
 * <p>Failures throw {@link NotificationDeliveryException}, matching
 * {@link EmailNotificationClient}, so callers keep their best-effort semantics
 * and can fall back to the API path.
 */
@Slf4j
@Component
public class SmtpEmailSender {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailProperties properties;

    public SmtpEmailSender(ObjectProvider<JavaMailSender> mailSender, MailProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * True when this cell is configured to send its own mail. False leaves the
     * notification API as the delivery path, unchanged.
     */
    public boolean isEnabled() {
        return properties.isEnabled()
                && properties.getFrom() != null && !properties.getFrom().isBlank()
                && mailSender.getIfAvailable() != null;
    }

    /**
     * <p><b>The body is passed through untouched.</b> Callers render exactly what
     * they already send through the notification API — the branded
     * {@code BrandedEmailRenderer} HTML, or plain text closed with
     * {@code EmailSignature} — so switching transport must not change a single
     * pixel of what lands in the inbox. {@code html} says which of the two it
     * is; getting it wrong would either show markup as text or collapse a
     * plain-text body's newlines into one paragraph.
     *
     * @param to      recipient address
     * @param subject subject line, sent as-is (SMTP is 8-bit clean here, unlike
     *                the SMS path — no GSM-7 transliteration)
     * @param body    fully rendered body, already in its final form
     * @param html    true when {@code body} is HTML, false for plain text
     */
    public void send(String to, String subject, String body, boolean html) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new NotificationDeliveryException("SMTP is not configured (no spring.mail.host)");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, html);
            sender.send(message);
        } catch (UnsupportedEncodingException e) {
            // Only reachable if the JVM lacks UTF-8, but the checked signature
            // forces the branch — surface it like any other delivery failure.
            throw new NotificationDeliveryException("Unable to encode the sender name: " + e.getMessage(), e);
        } catch (RuntimeException | jakarta.mail.MessagingException e) {
            throw new NotificationDeliveryException("SMTP delivery failed: " + e.getMessage(), e);
        }
    }

    /**
     * {@code InnBucks Marketplace <banking@innbucks.co.zw>} when a sender name
     * is set, otherwise the bare address. The name is what the recipient's
     * client shows in their inbox list.
     *
     * <p>Note for anyone debugging "it still shows the old name": Gmail prefers
     * a matching Google Contacts entry over the header, so a tester who has the
     * address saved under another name keeps seeing that name no matter what we
     * send. Check the raw header via "Show original" before changing config.
     */
    InternetAddress fromAddress() throws UnsupportedEncodingException {
        String name = properties.getSenderName();
        return (name == null || name.isBlank())
                ? new InternetAddress(properties.getFrom(), null, StandardCharsets.UTF_8.name())
                : new InternetAddress(properties.getFrom(), name, StandardCharsets.UTF_8.name());
    }
}
