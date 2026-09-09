package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.dto.SellerDecisionRequest;
import com.innbucks.marketplaceservice.seller.dto.SellerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the seller trust record and the SUPER_ADMIN decisions on it (V8).
 *
 * <p><b>What this exists for.</b> A "seller" used to be nothing but a
 * merchant_id stamped on a listing — there was no record to hang a decision on,
 * so the platform could neither vet a seller nor tell a buyer that it had.
 *
 * <p>Unit test — no Spring context, no Docker.
 */
class SellerServiceTest {

    private static final UUID MERCHANT = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID ADMIN = UUID.fromString("1f0e2d3c-4b5a-6978-8695-a4b3c2d1e0f9");
    private static final AuthenticatedUser CALLER =
            new AuthenticatedUser(ADMIN.toString(), Set.of("ROLE_SUPER_ADMIN"), null, null, null, null);

    private MarketplaceSellerRepository sellers;
    private ListingRepository listings;
    private AuditService audit;
    private SellerService service;

    @BeforeEach
    void setUp() {
        sellers = mock(MarketplaceSellerRepository.class);
        listings = mock(ListingRepository.class);
        audit = mock(AuditService.class);
        when(sellers.save(any(MarketplaceSeller.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new SellerService(sellers, listings, audit);
    }

    private MarketplaceSeller existing(SellerStatus status) {
        MarketplaceSeller s = MarketplaceSeller.builder()
                .merchantId(MERCHANT).status(status).createdAt(Instant.parse("2026-04-01T09:15:00Z"))
                .build();
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(s));
        return s;
    }

    // ---- the two lines the whole design rests on ---------------------------

    @Test
    void onlyApprovedIsVerified() {
        // "Verified" is a claim the platform makes on its own behalf, so it must
        // never follow from merely having a merchant record.
        assertThat(SellerStatus.APPROVED.isVerified()).isTrue();
        assertThat(SellerStatus.PENDING.isVerified()).isFalse();
        assertThat(SellerStatus.REJECTED.isVerified()).isFalse();
        assertThat(SellerStatus.SUSPENDED.isVerified()).isFalse();
    }

    @Test
    void onlyRejectedAndSuspendedAreStoppedFromPublishing() {
        // Deliberately a DIFFERENT line from "verified": a PENDING seller may
        // still trade, unbadged. Making PENDING a hard gate would turn this into
        // an approval-queued marketplace, which is a product decision rather
        // than a consequence of adding a trust record.
        assertThat(SellerStatus.PENDING.canPublish()).isTrue();
        assertThat(SellerStatus.APPROVED.canPublish()).isTrue();
        assertThat(SellerStatus.REJECTED.canPublish()).isFalse();
        assertThat(SellerStatus.SUSPENDED.canPublish()).isFalse();
    }

    @Test
    void aMerchantWithNoRecordYetMayPublish() {
        // The record is written on their first listing. Treating an ABSENCE as a
        // refusal would block a brand-new seller on a row that has not been
        // created yet — failing closed on "new" rather than on "barred".
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());

        assertThat(service.canPublish(MERCHANT)).isTrue();
    }

    @Test
    void aSuspendedMerchantMayNotPublish() {
        existing(SellerStatus.SUSPENDED);

        assertThat(service.canPublish(MERCHANT)).isFalse();
    }

    // ---- registration -------------------------------------------------------

    @Test
    void ensureExistsCreatesAPendingRecordOnFirstSight() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());

        MarketplaceSeller created = service.ensureExists(MERCHANT);

        assertThat(created.getStatus()).isEqualTo(SellerStatus.PENDING);
        assertThat(created.getMerchantId()).isEqualTo(MERCHANT);
        verify(audit).record(eq(AuditEventType.SELLER_REGISTERED), any(), eq(MERCHANT.toString()), anyMap());
    }

    @Test
    void ensureExistsNeverOverwritesADecisionAlreadyMade() {
        // A suspended seller reaching the create path must stay suspended.
        existing(SellerStatus.SUSPENDED);

        assertThat(service.ensureExists(MERCHANT).getStatus()).isEqualTo(SellerStatus.SUSPENDED);
        verify(sellers, never()).save(any());
    }

    // ---- decisions ----------------------------------------------------------

    @Test
    void approveMarksVerifiedAndRecordsWhoDecided() {
        existing(SellerStatus.PENDING);

        SellerResponse res = service.approve(CALLER, MERCHANT,
                new SellerDecisionRequest(null, "Rudo Traders"));

        assertThat(res.status()).isEqualTo(SellerStatus.APPROVED);
        assertThat(res.verified()).isTrue();
        assertThat(res.displayName()).isEqualTo("Rudo Traders");
        assertThat(res.decidedBy()).isEqualTo(ADMIN);
        assertThat(res.decidedAt()).isNotNull();
    }

    @Test
    void suspendTakesTheSellersLiveListingsDown() {
        // A suspension that left goods on sale would mean nothing.
        existing(SellerStatus.APPROVED);
        when(listings.deactivateActiveListingsOf(eq(MERCHANT), any())).thenReturn(4);

        SellerResponse res = service.suspend(CALLER, MERCHANT,
                new SellerDecisionRequest("Counterfeit goods reported.", null));

        assertThat(res.status()).isEqualTo(SellerStatus.SUSPENDED);
        assertThat(res.canPublish()).isFalse();
        verify(listings).deactivateActiveListingsOf(eq(MERCHANT), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditEventType.SELLER_STATUS_CHANGED), eq(ADMIN.toString()),
                eq(MERCHANT.toString()), meta.capture());
        assertThat(meta.getValue()).containsEntry("listingsDeactivated", 4);
        assertThat(meta.getValue()).containsEntry("from", "APPROVED").containsEntry("to", "SUSPENDED");
    }

    @Test
    void rejectTakesNoListingsDown() {
        // Reject is a decision on a seller, not a moderation action on goods —
        // and a rejected seller has nothing published to remove anyway.
        existing(SellerStatus.PENDING);

        service.reject(CALLER, MERCHANT, new SellerDecisionRequest("Verification outstanding.", null));

        verify(listings, never()).deactivateActiveListingsOf(any(), any());
    }

    @Test
    void rejectAndSuspendBothRequireANote() {
        // A seller told only "no" cannot fix anything, and a second admin cannot
        // see what a colleague decided.
        existing(SellerStatus.PENDING);

        assertThatThrownBy(() -> service.reject(CALLER, MERCHANT, new SellerDecisionRequest("  ", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("note is required");
        assertThatThrownBy(() -> service.suspend(CALLER, MERCHANT, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("note is required");
        verify(sellers, never()).save(any());
    }

    @Test
    void approveNeedsNoNote() {
        existing(SellerStatus.PENDING);

        assertThat(service.approve(CALLER, MERCHANT, null).status()).isEqualTo(SellerStatus.APPROVED);
    }

    @Test
    void aRepeatedSuspendIsRefusedRatherThanSilentlyRedone() {
        existing(SellerStatus.SUSPENDED);

        assertThatThrownBy(() -> service.suspend(CALLER, MERCHANT,
                new SellerDecisionRequest("again", null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already SUSPENDED");
    }

    @Test
    void reApprovingIsAllowed_soADisplayNameCanBeCorrected() {
        existing(SellerStatus.APPROVED);

        SellerResponse res = service.approve(CALLER, MERCHANT,
                new SellerDecisionRequest(null, "Rudo Traders (Pvt) Ltd"));

        assertThat(res.displayName()).isEqualTo("Rudo Traders (Pvt) Ltd");
    }

    @Test
    void reinstateLiftsASuspension_butDoesNotRePublishTheListingsItTookDown() {
        // The seller chooses what goes back on sale; silently re-listing goods an
        // admin removed is the wrong default.
        existing(SellerStatus.SUSPENDED);

        SellerResponse res = service.reinstate(CALLER, MERCHANT, null);

        assertThat(res.status()).isEqualTo(SellerStatus.APPROVED);
        verify(listings, never()).deactivateActiveListingsOf(any(), any());
    }

    @Test
    void reinstateIsRefusedForASellerThatIsNotSuspendedOrRejected() {
        existing(SellerStatus.PENDING);

        assertThatThrownBy(() -> service.reinstate(CALLER, MERCHANT, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only a SUSPENDED or REJECTED seller");
    }

    @Test
    void decisionsOnAnUnknownSellerAre404() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(CALLER, MERCHANT, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No seller record");
    }

    @Test
    void pagingIsBounded() {
        assertThatThrownBy(() -> service.list(null, -1, 20))
                .isInstanceOf(ApiException.class).hasMessageContaining("page must be");
        assertThatThrownBy(() -> service.list(null, 0, 101))
                .isInstanceOf(ApiException.class).hasMessageContaining("size must be");
    }

    @Test
    void aLegacyTokenWhoseUuidIsNotAUuidStillDecides() {
        // Attribution is worth recording, never worth failing a moderation
        // action over.
        existing(SellerStatus.PENDING);
        AuthenticatedUser legacy = new AuthenticatedUser("admin@innbucks.co.zw",
                Set.of("ROLE_SUPER_ADMIN"), null, null, null, null);

        SellerResponse res = service.approve(legacy, MERCHANT, null);

        assertThat(res.status()).isEqualTo(SellerStatus.APPROVED);
        assertThat(res.decidedBy()).isNull();
    }
}
