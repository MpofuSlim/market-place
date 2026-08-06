package com.innbucks.marketplaceservice.report;

/** Why a listing was reported — a bounded vocabulary (CHECK-constrained in
 *  V7, and the tag on {@code marketplace.reports}; free text goes in the
 *  separate sanitized {@code detail} field, never in the reason). */
public enum ReportReason {
    PROHIBITED_ITEM,
    COUNTERFEIT,
    MISLEADING,
    OFFENSIVE,
    SCAM,
    OTHER
}
