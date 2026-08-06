package com.innbucks.marketplaceservice.report;

/** Moderation lifecycle of a report: OPEN until SUPER_ADMIN resolves or
 *  dismisses it; both closed states are terminal (409 {@code report_not_open}
 *  on any further action). */
public enum ReportStatus {
    OPEN,
    RESOLVED,
    DISMISSED
}
