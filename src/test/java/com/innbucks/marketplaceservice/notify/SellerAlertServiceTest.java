package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.settlement.DisputeReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The seller's bell: every alert reaches every OWNER/ADMIN of the selling
 * organization, typed and linked, and nothing about a notification failure
 * ever reaches the caller (after-commit listeners and a scheduled sweep).
 */
class SellerAlertServiceTest {

    private static final String REF = "MKT-4F9A1C22B7D3";
    private static final UUID MERCHANT = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID PARCEL = UUID.fromString("3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31");

    private MarketplaceNotificationProperties properties;
    private MerchantAdminResolver resolver;
    private UserNotifyGateway gateway;
    private SimpleMeterRegistry registry;
    private SellerAlertService alerts;

    @BeforeEach
    void setUp() {
        properties = new MarketplaceNotificationProperties();
        resolver = mock(MerchantAdminResolver.class);
        gateway = mock(UserNotifyGateway.class);
        registry = new SimpleMeterRegistry();
        alerts = new SellerAlertService(properties, resolver, gateway,
                new MarketplaceMetrics(registry));
    }

    private double outcome(String type, String outcome) {
        var counter = registry.find("marketplace.notifications")
                .tag("type", type).tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private UserNotice sentTo(UUID admin) {
        ArgumentCaptor<UserNotice> notice = ArgumentCaptor.forClass(UserNotice.class);
        verify(gateway).notify(eq(admin), notice.capture());
        return notice.getValue();
    }

    @Test
    @DisplayName("a dispute reaches every runner of the business as a WARNING linked to the parcel")
    void disputeReachesEveryAdmin() {
        UUID owner = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of(owner, admin));
        when(gateway.notify(any(), any(UserNotice.class))).thenReturn(true);

        alerts.disputeOpened(MERCHANT, REF, PARCEL, DisputeReason.NOT_RECEIVED);

        UserNotice expected = new UserNotice("Buyer dispute on order " + REF,
                "The buyer has disputed their parcel from order " + REF + " - not received. The "
                        + "money for it is on hold until our team decides. Ref " + REF,
                "PARCEL_DISPUTED", "WARNING", "PARCEL", PARCEL.toString(),
                "/marketplace/parcels?q=" + REF);
        assertThat(sentTo(owner)).isEqualTo(expected);
        assertThat(sentTo(admin)).isEqualTo(expected);
        assertThat(outcome("seller_dispute", "sent")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("each alert carries its own type, severity and link")
    void everyAlertIsTyped() {
        UUID admin = UUID.randomUUID();
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of(admin));
        when(gateway.notify(any(), any(UserNotice.class))).thenReturn(true);

        alerts.collectionOverdue(MERCHANT, REF, PARCEL, 7);
        alerts.payoutSent(MERCHANT, "IB-PAY-2209", 3, 61998, "USD");
        alerts.buyerNotReached(MERCHANT, REF, PARCEL, "that it is on its way");
        alerts.cancelledByBuyer(MERCHANT, REF, PARCEL, "Ordered the wrong size");

        ArgumentCaptor<UserNotice> notices = ArgumentCaptor.forClass(UserNotice.class);
        verify(gateway, org.mockito.Mockito.times(4)).notify(eq(admin), notices.capture());
        List<UserNotice> sent = notices.getAllValues();

        assertThat(sent).extracting(UserNotice::type).containsExactly(
                "COLLECTION_OVERDUE", "PAYOUT_SENT", "BUYER_NOT_NOTIFIED",
                "ORDER_CANCELLED_BY_BUYER");
        assertThat(sent).extracting(UserNotice::severity).containsExactly(
                "WARNING", "SUCCESS", "WARNING", "WARNING");
        // A payout is about the seller's money, not one parcel: it links to the
        // earnings screen and is keyed on the payout reference.
        assertThat(sent.get(1).deepLink()).isEqualTo("/marketplace/earnings");
        assertThat(sent.get(1).subjectKind()).isEqualTo("PAYOUT");
        assertThat(sent.get(1).subjectId()).isEqualTo("IB-PAY-2209");
        assertThat(sent.get(1).subject()).isEqualTo("Payout sent - USD 619.98");
        assertThat(List.of(sent.get(0), sent.get(2), sent.get(3)))
                .allSatisfy(n -> {
                    assertThat(n.deepLink()).isEqualTo("/marketplace/parcels?q=" + REF);
                    assertThat(n.subjectKind()).isEqualTo("PARCEL");
                    assertThat(n.subjectId()).isEqualTo(PARCEL.toString());
                });
        assertThat(sent.get(3).message()).contains("Reason - Ordered the wrong size.");
    }

    @Test
    @DisplayName("the deep link comes from the deployment's template")
    void linkTemplateIsConfigurable() {
        properties.getSellerAlerts().setParcelLink("/portal/orders/{orderRef}/parcels");
        assertThat(alerts.parcelLink(REF)).isEqualTo("/portal/orders/" + REF + "/parcels");
        // Never an NPE on a missing reference.
        assertThat(alerts.parcelLink(null)).isEqualTo("/portal/orders//parcels");
    }

    @Test
    @DisplayName("switched off: nothing is resolved or sent, and it is counted")
    void disabled() {
        properties.getSellerAlerts().setEnabled(false);

        alerts.payoutSent(MERCHANT, "IB-PAY-2209", 3, 61998, "USD");

        verifyNoInteractions(resolver, gateway);
        assertThat(outcome("seller_payout", "disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("nobody to tell is counted, not an error")
    void noRecipients() {
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of());

        alerts.collectionOverdue(MERCHANT, REF, PARCEL, 7);

        verifyNoInteractions(gateway);
        assertThat(outcome("seller_collection_overdue", "no_recipients")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a refused delivery is counted per person and the rest still go")
    void refusalsCountedPerAdmin() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(resolver.adminUserUuids(MERCHANT)).thenReturn(List.of(first, second));
        when(gateway.notify(eq(first), any(UserNotice.class))).thenReturn(false);
        when(gateway.notify(eq(second), any(UserNotice.class))).thenReturn(true);

        alerts.cancelledByBuyer(MERCHANT, REF, PARCEL, null);

        assertThat(outcome("seller_buyer_cancelled", "failed")).isEqualTo(1.0);
        assertThat(outcome("seller_buyer_cancelled", "sent")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an exploding resolver never escapes — the callers are on the money path's edge")
    void neverThrows() {
        when(resolver.adminUserUuids(any())).thenThrow(new IllegalStateException("user-service down"));

        assertThatCode(() -> alerts.disputeOpened(MERCHANT, REF, PARCEL, null))
                .doesNotThrowAnyException();
        assertThat(outcome("seller_dispute", "failed")).isEqualTo(1.0);
    }
}
