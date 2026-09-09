package com.innbucks.marketplaceservice.seller;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A seller's standing with the platform (V8).
 *
 * <p><b>Only APPROVED is "verified" to a buyer, and only REJECTED/SUSPENDED
 * stop a seller trading.</b> Those are deliberately different lines:
 *
 * <ul>
 *   <li>{@link #PENDING} — the default, and where every pre-V8 merchant was
 *       backfilled. They may publish, but carry no badge. Making PENDING a hard
 *       gate would turn the marketplace into an approval-queued platform, which
 *       is a product decision about onboarding friction rather than a
 *       consequence of adding a trust record — and it is a one-line change to
 *       {@link #canPublish()} if that is later wanted.</li>
 *   <li>{@link #APPROVED} — vetted. The only status that earns the badge.</li>
 *   <li>{@link #REJECTED} — refused. May not publish.</li>
 *   <li>{@link #SUSPENDED} — was trading, stopped by an admin. May not publish,
 *       and {@code SellerService.suspend} also takes their live listings down —
 *       a suspension that left goods on sale would mean nothing.</li>
 * </ul>
 */
@Schema(description = "A seller's standing with the platform")
public enum SellerStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUSPENDED;

    /**
     * Whether this seller may move a listing to ACTIVE — i.e. make it publicly
     * visible and orderable. Gating publication rather than creation is the
     * meaningful line: a DRAFT is private, so an un-vetted seller drafting
     * costs nobody anything, while ACTIVE is the moment goods reach buyers.
     * It also matches the existing publish-gate discipline (a primary image is
     * required on the same transition).
     */
    public boolean canPublish() {
        return this == PENDING || this == APPROVED;
    }

    /** Whether a buyer should be shown the verified badge. APPROVED only. */
    public boolean isVerified() {
        return this == APPROVED;
    }
}
