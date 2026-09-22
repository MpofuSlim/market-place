package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.dto.PayoutDestinationRequest;
import com.innbucks.marketplaceservice.seller.dto.PayoutDestinationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where a seller's money goes (V13): the per-method contract, the
 * replace-never-merge rule, and the two things that make a redirected payout
 * survivable — an audit row that names who moved it, and an event that tells
 * the seller.
 */
class PayoutDestinationTest {

    private static final UUID MERCHANT = UUID.randomUUID();
    private static final AuthenticatedUser SELLER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"), MERCHANT.toString(), null, null, "ZW");

    private MarketplaceSellerRepository sellers;
    private AuditService auditService;
    private ApplicationEventPublisher eventPublisher;
    private SellerService service;

    @BeforeEach
    void setUp() {
        sellers = mock(MarketplaceSellerRepository.class);
        auditService = mock(AuditService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new SellerService(sellers, mock(ListingRepository.class), auditService,
                new Msisdns("ZW"), eventPublisher,
                // No registry in a plain unit test: names resolve locally only.
                ids -> java.util.Map.of());
        when(sellers.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private MarketplaceSeller existing() {
        MarketplaceSeller seller = MarketplaceSeller.builder()
                .merchantId(MERCHANT)
                .status(SellerStatus.APPROVED)
                .createdAt(Instant.now())
                .build();
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller));
        return seller;
    }

    private static PayoutDestinationRequest wallet(String msisdn) {
        return new PayoutDestinationRequest(
                PayoutMethod.MOBILE_MONEY, "Rudo Chikwanha", msisdn, null, null);
    }

    private static PayoutDestinationRequest bank(String bankName, String accountNumber) {
        return new PayoutDestinationRequest(
                PayoutMethod.BANK, "Rudo Chikwanha", null, bankName, accountNumber);
    }

    @Test
    @DisplayName("A wallet destination stores the msisdn NORMALISED, and no bank fields")
    void mobileMoneyIsStoredNormalised() {
        MarketplaceSeller seller = existing();

        PayoutDestinationResponse out =
                service.setPayoutDestination(SELLER, MERCHANT, wallet("0771234567"), true);

        assertThat(out.configured()).isTrue();
        assertThat(out.method()).isEqualTo(PayoutMethod.MOBILE_MONEY);
        // Stored E.164 — a payout target in a spelling the rails will not
        // accept is a transfer that fails at the counter, not here.
        assertThat(out.msisdn()).isEqualTo("+263771234567");
        assertThat(seller.getPayoutBankName()).isNull();
        assertThat(seller.getPayoutAccountNumber()).isNull();
        assertThat(seller.getPayoutUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("A bank destination stores both bank fields, and no msisdn")
    void bankIsStoredWithNoWalletFields() {
        MarketplaceSeller seller = existing();

        PayoutDestinationResponse out = service.setPayoutDestination(
                SELLER, MERCHANT, bank("CBZ Bank", "01123456789012"), true);

        assertThat(out.method()).isEqualTo(PayoutMethod.BANK);
        assertThat(out.bankName()).isEqualTo("CBZ Bank");
        assertThat(out.accountNumber()).isEqualTo("01123456789012");
        assertThat(seller.getPayoutMsisdn()).isNull();
    }

    @Test
    @DisplayName("Switching rails CLEARS the old rail's fields — never a half-changed destination")
    void switchingMethodClearsTheOtherRail() {
        MarketplaceSeller seller = existing();
        service.setPayoutDestination(SELLER, MERCHANT, bank("CBZ Bank", "01123456789012"), true);

        service.setPayoutDestination(SELLER, MERCHANT, wallet("0771234567"), true);

        // A row naming one rail with the other's account still behind it is
        // exactly what chk_seller_payout_destination refuses, and what would
        // otherwise read as configured on every screen.
        assertThat(seller.getPayoutMethod()).isEqualTo(PayoutMethod.MOBILE_MONEY);
        assertThat(seller.getPayoutMsisdn()).isEqualTo("+263771234567");
        assertThat(seller.getPayoutBankName()).isNull();
        assertThat(seller.getPayoutAccountNumber()).isNull();
    }

    @Test
    @DisplayName("A missing per-method field is a 400 NAMING the field")
    void missingMethodFieldIsRefusedByName() {
        existing();

        assertThatThrownBy(() -> service.setPayoutDestination(
                SELLER, MERCHANT, bank(null, "01123456789012"), true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("payout_field_required");

        assertThatThrownBy(() -> service.setPayoutDestination(
                SELLER, MERCHANT, bank("CBZ Bank", "   "), true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("payout_field_required");
    }

    @Test
    @DisplayName("An unusable phone number is refused, and nothing is stored")
    void anInvalidMsisdnIsRefused() {
        MarketplaceSeller seller = existing();

        assertThatThrownBy(() -> service.setPayoutDestination(
                SELLER, MERCHANT, wallet("12"), true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("invalid_msisdn");
        // Validate before mutate: the refused request left no trace.
        assertThat(seller.hasPayoutDestination()).isFalse();
        verify(sellers, never()).save(any());
    }

    @Test
    @DisplayName("A field that sanitizes to nothing is refused, not silently stored as markup")
    void markupOnlyFieldIsRefused() {
        existing();

        assertThatThrownBy(() -> service.setPayoutDestination(SELLER, MERCHANT,
                new PayoutDestinationRequest(PayoutMethod.BANK, "<b></b>", null,
                        "CBZ Bank", "01123456789012"), true))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("payout_field_required");
    }

    @Test
    @DisplayName("The audit row names the method and who, and carries NO account details")
    void auditRecordsTheChangeButNotTheAccount() {
        existing();

        service.setPayoutDestination(SELLER, MERCHANT, bank("CBZ Bank", "01123456789012"), true);

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.captor();
        verify(auditService).record(eq(AuditEventType.SELLER_PAYOUT_DESTINATION_CHANGED),
                anyString(), eq(MERCHANT.toString()), meta.capture());
        assertThat(meta.getValue())
                .containsEntry("method", "BANK")
                .containsEntry("replacedExisting", false)
                .containsEntry("bySeller", true);
        // An account number in the audit log is an account number in one more
        // place — the method is enough to investigate a redirect with.
        assertThat(meta.getValue().values().stream().map(String::valueOf))
                .noneMatch(v -> v.contains("01123456789012") || v.contains("CBZ"));
    }

    @Test
    @DisplayName("The seller is told — and the event distinguishes a CHANGE from a first set")
    void theSellerIsWarnedOnAChange() {
        MarketplaceSeller seller = existing();
        service.setPayoutDestination(SELLER, MERCHANT, wallet("0771234567"), true);

        ArgumentCaptor<PayoutDestinationChanged> first = ArgumentCaptor.captor();
        verify(eventPublisher).publishEvent(first.capture());
        assertThat(first.getValue().replacedAnExistingOne()).isFalse();
        assertThat(first.getValue().changedBySeller()).isTrue();

        // Now a real change — the case the warning exists for.
        assertThat(seller.hasPayoutDestination()).isTrue();
        service.setPayoutDestination(SELLER, MERCHANT, bank("CBZ Bank", "01123456789012"), true);

        ArgumentCaptor<PayoutDestinationChanged> both = ArgumentCaptor.captor();
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(both.capture());
        assertThat(both.getAllValues().get(1).replacedAnExistingOne()).isTrue();
    }

    @Test
    @DisplayName("An admin acting for a seller is recorded as NOT the seller")
    void anAdminOverrideIsDistinguishable() {
        existing();
        AuthenticatedUser admin = new AuthenticatedUser(
                UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");

        service.setPayoutDestination(admin, MERCHANT, wallet("0771234567"), false);

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.captor();
        verify(auditService).record(any(), anyString(), anyString(), meta.capture());
        assertThat(meta.getValue()).containsEntry("bySeller", false);
        ArgumentCaptor<PayoutDestinationChanged> event = ArgumentCaptor.captor();
        verify(eventPublisher).publishEvent(event.capture());
        // "We changed this for you" and "someone changed this" are different
        // messages to the reader.
        assertThat(event.getValue().changedBySeller()).isFalse();
    }

    @Test
    @DisplayName("No destination on file is a normal 200, not a 404 — the seller still exists")
    void noDestinationIsAnOrdinaryAnswer() {
        existing();

        PayoutDestinationResponse out = service.payoutDestination(MERCHANT);

        assertThat(out.configured()).isFalse();
        assertThat(out.method()).isNull();
        assertThat(out.accountName()).isNull();
        assertThat(out.merchantId()).isEqualTo(MERCHANT);
    }

    @Test
    @DisplayName("A merchant with no trust record yet gets one — onboarding order is free")
    void readingCreatesTheRecordOnFirstSight() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());
        when(sellers.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Asking for bank details before a seller has listed anything is an
        // ordinary onboarding order; a 404 would make the screen unreachable
        // for exactly the sellers who have not started.
        PayoutDestinationResponse out = service.payoutDestination(MERCHANT);

        assertThat(out.configured()).isFalse();
        assertThat(out.merchantId()).isEqualTo(MERCHANT);
    }
}
