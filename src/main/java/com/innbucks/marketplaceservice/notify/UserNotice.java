package com.innbucks.marketplaceservice.notify;

/**
 * One notification for user-service's {@code POST /users/internal/{uuid}/notify}.
 *
 * <p>Everything past {@code message} is optional on that ingress, and it is
 * what turns a line in the bell into something a person can act on:
 * {@code type} (a user-service {@code NotificationType} name — an unknown one is
 * stored and served as-is, never refused), {@code severity}
 * ({@code INFO}/{@code SUCCESS}/{@code WARNING}/{@code ERROR}), the
 * {@code subjectKind}/{@code subjectId} the console de-duplicates on, and the
 * {@code deepLink} it sends the person to. The link is supplied HERE so the
 * console keeps no type-to-route map to go stale.
 */
public record UserNotice(String subject,
                         String message,
                         String type,
                         String severity,
                         String subjectKind,
                         String subjectId,
                         String deepLink) {

    public static final String INFO = "INFO";
    public static final String SUCCESS = "SUCCESS";
    public static final String WARNING = "WARNING";

    /** Subject + message only — lands in the bell as GENERAL / INFO. */
    public static UserNotice plain(String subject, String message) {
        return new UserNotice(subject, message, null, null, null, null, null);
    }
}
