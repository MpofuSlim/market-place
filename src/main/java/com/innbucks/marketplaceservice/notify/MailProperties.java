package com.innbucks.marketplaceservice.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Our half of the SMTP email config — who the message says it is from. The
 * transport half (host/port/credentials) is Spring's own {@code spring.mail.*}.
 *
 * <p>Named {@code app.mail} rather than reusing {@code spring.mail} so it can't
 * collide with Boot's {@code MailProperties}, which owns that prefix. Copied
 * from booking-service.
 */
@Component
@ConfigurationProperties(prefix = "app.mail")
@Data
public class MailProperties {

    /**
     * Send via SMTP instead of the InnBucks notification API. Default false:
     * a cell keeps its existing delivery path until it provisions SES
     * credentials and flips this on.
     */
    private boolean enabled = false;

    /**
     * Envelope + header sender. Must be an identity verified in SES for the
     * region, or SES rejects the message.
     */
    private String from;

    /**
     * Display name shown in the recipient's inbox, e.g. "InnBucks Marketplace".
     * Blank sends the bare address.
     */
    private String senderName;
}
