package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.checkout.CheckoutProperties;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.dto.CollectionRequiredDetails;
import com.innbucks.marketplaceservice.seller.dto.CollectionSettingResponse;
import com.innbucks.marketplaceservice.seller.dto.SellerResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A seller turning collection off (V20): every refusal comes before any write,
 * so a refused request never registers (or audits) a seller; a request that
 * changes nothing writes nothing — judged on the EFFECTIVE value, a missing
 * record reading as collecting; turning collection back on is never gated; and
 * a real change goes through the one bulk UPDATE, audited with who made it.
 */
class SellerCollectionTest {

    private static final UUID MERCHANT = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID SELLER_USER = UUID.fromString("3f1c9d24-a77e-4e21-9c60-11ab22cd33ef");
    private static final UUID ADMIN_USER = UUID.fromString("1f0e2d3c-4b5a-6978-8695-a4b3c2d1e0f9");
    private static final AuthenticatedUser SELLER = new AuthenticatedUser(
            SELLER_USER.toString(), Set.of("MERCHANT_ADMIN"), MERCHANT.toString(), null, null, "ZW");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(
            ADMIN_USER.toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");

    private MarketplaceSellerRepository sellers;
    private ListingRepository listings;
    private AuditService audit;
    private SimpleMeterRegistry registry;

    /** The cell switch and the cell's delivery offer, as a cell would wire them. */
    static DeliveryOnlyPolicy policy(boolean enabled, boolean deliveryOffered) {
        CheckoutProperties properties = new CheckoutProperties();
        properties.getDelivery().setMethods(deliveryOffered
                ? EnumSet.allOf(DeliveryMethod.class)
                : EnumSet.of(DeliveryMethod.COLLECTION));
        return new DeliveryOnlyPolicy(enabled, properties);
    }

    @BeforeEach
    void setUp() {
        sellers = mock(MarketplaceSellerRepository.class);
        listings = mock(ListingRepository.class);
        audit = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        when(listings.findActiveWithoutDeliveryTowns(any(), any())).thenReturn(List.of());
    }

    private SellerService service(boolean enabled, boolean deliveryOffered) {
        return new SellerService(sellers, listings, audit, new Msisdns("ZW"),
                mock(ApplicationEventPublisher.class), ids -> Map.of(),
                policy(enabled, deliveryOffered), new MarketplaceMetrics(registry));
    }

    private MarketplaceSeller seller(boolean collectionEnabled) {
        return MarketplaceSeller.builder()
                .merchantId(MERCHANT)
                .status(SellerStatus.APPROVED)
                .createdAt(Instant.parse("2026-04-01T09:15:00Z"))
                .collectionEnabled(collectionEnabled)
                .collectionUpdatedAt(collectionEnabled ? null : Instant.parse("2026-09-20T10:00:00Z"))
                .build();
    }

    /** The row as the database holds it under the lock. */
    private static MarketplaceSellerRepository.CollectionState state(boolean enabled, Instant at) {
        return new MarketplaceSellerRepository.CollectionState() {
            @Override
            public Boolean getCollectionEnabled() {
                return enabled;
            }

            @Override
            public Instant getCollectionUpdatedAt() {
                return at;
            }
        };
    }

    private static ListingRepository.ListingTitle titled(UUID id, String title) {
        return new ListingRepository.ListingTitle() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getTitle() {
                return title;
            }
        };
    }

    private double changes(boolean enabled) {
        var counter = registry.find("marketplace.seller.collection_changed")
                .tag("enabled", Boolean.toString(enabled)).counter();
        return counter == null ? 0 : counter.count();
    }

    /** Nothing was registered, locked, written, audited or counted. */
    private void assertNothingWritten() {
        verify(sellers, never()).insertIfAbsent(any(), any());
        verify(sellers, never()).lockForUpdate(any());
        verify(sellers, never()).setCollectionEnabled(any(), anyBoolean(), any(), any());
        verify(sellers, never()).save(any());
        verifyNoInteractions(audit);
        assertThat(changes(true) + changes(false)).isZero();
    }

    private static ApiException refusal(Runnable call) {
        try {
            call.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("expected an ApiException");
    }

    // ---- the entity and the policy -----------------------------------------

    @Test
    @DisplayName("A builder-made seller, and a seller with no record at all, collect")
    void everySellerCollectsUntilTheyTurnItOff() {
        // Without @Builder.Default every MarketplaceSeller.builder() in the
        // test suite would silently build a delivery-only seller.
        MarketplaceSeller built = MarketplaceSeller.builder()
                .merchantId(MERCHANT).status(SellerStatus.PENDING).createdAt(Instant.now()).build();
        assertThat(built.isCollectionEnabled()).isTrue();
        assertThat(MarketplaceSeller.collects(built)).isTrue();
        assertThat(MarketplaceSeller.collects(null)).isTrue();
        assertThat(MarketplaceSeller.collects(seller(false))).isFalse();
    }

    @Test
    @DisplayName("The policy reads the cell switch and whether the cell offers delivery")
    void thePolicyReadsTheSwitchAndTheCellsMethods() {
        assertThat(policy(true, true).enabled()).isTrue();
        assertThat(policy(false, true).enabled()).isFalse();
        assertThat(policy(true, true).deliveryOffered()).isTrue();
        assertThat(policy(true, false).deliveryOffered()).isFalse();
    }

    @Test
    @DisplayName("The admin view of a seller carries its collection setting")
    void theSellerResponseCarriesTheSetting() {
        assertThat(SellerResponse.from(seller(true)).collectionEnabled()).isTrue();
        assertThat(SellerResponse.from(seller(false)).collectionEnabled()).isFalse();
    }

    // ---- reads ----------------------------------------------------------------

    @Test
    @DisplayName("Reading the setting of a merchant with no record answers collecting, never "
            + "changed - and registers nobody")
    void readingNeverRegisters() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());

        CollectionSettingResponse out = service(true, true).collectionSetting(MERCHANT);

        assertThat(out.collectionEnabled()).isTrue();
        assertThat(out.updatedAt()).isNull();
        assertNothingWritten();
    }

    @Test
    @DisplayName("isDeliveryOnly: no record and a collecting seller are false, an opted-out one "
            + "is true")
    void isDeliveryOnlyReadsTheFlag() {
        SellerService service = service(true, true);
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());
        assertThat(service.isDeliveryOnly(MERCHANT)).isFalse();
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller(true)));
        assertThat(service.isDeliveryOnly(MERCHANT)).isFalse();
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller(false)));
        assertThat(service.isDeliveryOnly(MERCHANT)).isTrue();
    }

    // ---- refusals: all before any write ------------------------------------------

    @Test
    @DisplayName("Turning collection off while the cell switch is off is 422 "
            + "delivery_only_disabled, and nothing is registered or written")
    void theCellSwitchGatesTheMoveToDeliveryOnly() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());

        ApiException ex = refusal(() ->
                service(false, true).setCollectionEnabled(SELLER, MERCHANT, false, true));

        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.code()).isEqualTo("delivery_only_disabled");
        assertThat(ex.getMessage()).isEqualTo("Turning collection off is not available yet");
        // Cheapest first: the switch is decided before any listing is read.
        verify(listings, never()).findActiveWithoutDeliveryTowns(any(), any());
        assertNothingWritten();
    }

    @Test
    @DisplayName("Turning collection off in a market with no delivery is 422 "
            + "delivery_not_offered")
    void aMarketWithoutDeliveryCannotHaveDeliveryOnlySellers() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller(true)));

        ApiException ex = refusal(() ->
                service(true, false).setCollectionEnabled(SELLER, MERCHANT, false, true));

        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ex.code()).isEqualTo("delivery_not_offered");
        assertThat(ex.getMessage()).isEqualTo(
                "Delivery is not offered in this market, so collection cannot be turned off");
        verify(listings, never()).findActiveWithoutDeliveryTowns(any(), any());
        assertNothingWritten();
    }

    @Test
    @DisplayName("Items on sale that could only be collected are a 409 collection_required "
            + "naming at most 20 of them, asked for 21, and nothing is changed for the seller")
    void strandedListingsAreNamedNeverFixed() {
        List<ListingRepository.ListingTitle> stranded = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            stranded.add(titled(UUID.randomUUID(), "Item " + i));
        }
        when(listings.findActiveWithoutDeliveryTowns(eq(MERCHANT), any())).thenReturn(stranded);
        // No record yet: a refused FIRST request must register nobody.
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());

        ApiException ex = refusal(() ->
                service(true, true).setCollectionEnabled(SELLER, MERCHANT, false, true));

        assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.code()).isEqualTo("collection_required");
        assertThat(ex.getMessage()).isEqualTo("Some of your items on sale can only be collected - "
                + "add delivery towns to them or take them off sale first");
        CollectionRequiredDetails details = (CollectionRequiredDetails) ex.details();
        assertThat(details.listings()).hasSize(20);
        assertThat(details.truncated()).isTrue();
        assertThat(details.listings().getFirst().id()).isEqualTo(stranded.getFirst().getId());
        assertThat(details.listings().getFirst().title()).isEqualTo("Item 0");
        assertThat(details.listings()).extracting(CollectionRequiredDetails.StrandedListing::title)
                .doesNotContain("Item 20");
        ArgumentCaptor<Limit> limit = ArgumentCaptor.forClass(Limit.class);
        verify(listings).findActiveWithoutDeliveryTowns(eq(MERCHANT), limit.capture());
        assertThat(limit.getValue().max()).isEqualTo(21);
        // Refused, never fixed: no listing was taken off sale.
        verify(listings, never()).deactivateActiveListingsOf(any(), any());
        assertNothingWritten();
    }

    @Test
    @DisplayName("A short stranded list names every item and says it is not truncated")
    void aShortStrandedListIsComplete() {
        UUID earbuds = UUID.randomUUID();
        when(listings.findActiveWithoutDeliveryTowns(eq(MERCHANT), any()))
                .thenReturn(List.of(titled(earbuds, "Wireless Earbuds")));
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller(true)));

        ApiException ex = refusal(() ->
                service(true, true).setCollectionEnabled(ADMIN, MERCHANT, false, false));

        CollectionRequiredDetails details = (CollectionRequiredDetails) ex.details();
        assertThat(details.listings()).containsExactly(
                new CollectionRequiredDetails.StrandedListing(earbuds, "Wireless Earbuds"));
        assertThat(details.truncated()).isFalse();
        assertNothingWritten();
    }

    // ---- no-ops -------------------------------------------------------------------

    @Test
    @DisplayName("Keeping collection on for a merchant with no record is a 200 that registers "
            + "nobody and audits nothing")
    void aNoOpOnAMissingRowRegistersNobody() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty());

        CollectionSettingResponse out =
                service(false, false).setCollectionEnabled(SELLER, MERCHANT, true, true);

        assertThat(out.collectionEnabled()).isTrue();
        assertThat(out.updatedAt()).isNull();
        assertNothingWritten();
    }

    @Test
    @DisplayName("Turning collection off for a seller already delivery-only changes nothing")
    void aRepeatedOptOutIsANoOp() {
        MarketplaceSeller already = seller(false);
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(already));

        CollectionSettingResponse out =
                service(true, true).setCollectionEnabled(SELLER, MERCHANT, false, true);

        assertThat(out.collectionEnabled()).isFalse();
        assertThat(out.updatedAt()).isEqualTo(already.getCollectionUpdatedAt());
        assertNothingWritten();
    }

    // ---- real changes -------------------------------------------------------------

    @Test
    @DisplayName("Turning collection off runs every check, then ensures, locks, re-reads and "
            + "writes through the bulk UPDATE - never an entity save - and audits it")
    void turningCollectionOffIsOneBulkWriteAudited() {
        MarketplaceSeller current = seller(true);
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(current));
        when(sellers.collectionStateOf(MERCHANT)).thenReturn(state(true, null));

        CollectionSettingResponse out =
                service(true, true).setCollectionEnabled(SELLER, MERCHANT, false, true);

        assertThat(out.collectionEnabled()).isFalse();
        assertThat(out.updatedAt()).isNotNull();
        InOrder order = inOrder(listings, sellers);
        order.verify(listings).findActiveWithoutDeliveryTowns(eq(MERCHANT), any());
        order.verify(sellers).insertIfAbsent(eq(MERCHANT), any());
        order.verify(sellers).lockForUpdate(MERCHANT);
        order.verify(sellers).collectionStateOf(MERCHANT);
        order.verify(sellers).setCollectionEnabled(eq(MERCHANT), eq(false), eq(out.updatedAt()),
                eq(SELLER_USER));
        verify(sellers, never()).save(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditEventType.SELLER_COLLECTION_CHANGED), eq(SELLER_USER.toString()),
                eq(MERCHANT.toString()), meta.capture());
        assertThat(meta.getValue()).containsExactly(
                Map.entry("merchantId", MERCHANT.toString()),
                Map.entry("collectionEnabled", false),
                Map.entry("previous", true),
                Map.entry("bySeller", true));
        assertThat(changes(false)).isEqualTo(1);
    }

    @Test
    @DisplayName("A seller's FIRST action being a switch-off registers them only after every "
            + "check has passed")
    void aFirstOptOutRegistersAfterTheChecks() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.empty(), Optional.of(seller(true)));
        when(sellers.insertIfAbsent(eq(MERCHANT), any())).thenReturn(1);
        when(sellers.collectionStateOf(MERCHANT)).thenReturn(state(true, null));

        service(true, true).setCollectionEnabled(SELLER, MERCHANT, false, true);

        InOrder order = inOrder(listings, sellers, audit);
        order.verify(listings).findActiveWithoutDeliveryTowns(eq(MERCHANT), any());
        order.verify(sellers).insertIfAbsent(eq(MERCHANT), any());
        order.verify(audit).record(eq(AuditEventType.SELLER_REGISTERED), any(),
                eq(MERCHANT.toString()), anyMap());
        order.verify(sellers).setCollectionEnabled(eq(MERCHANT), eq(false), any(), any());
        order.verify(audit).record(eq(AuditEventType.SELLER_COLLECTION_CHANGED), any(),
                eq(MERCHANT.toString()), anyMap());
    }

    @Test
    @DisplayName("Turning collection back on is never gated - not by the switch, the market or "
            + "stranded listings - and an operator's change is audited bySeller=false")
    void reEnablingIsNeverGatedAndTheAdminIsAudited() {
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller(false)));
        when(sellers.collectionStateOf(MERCHANT))
                .thenReturn(state(false, Instant.parse("2026-09-20T10:00:00Z")));

        // Switch off AND no delivery in the market: the move back is still allowed.
        CollectionSettingResponse out =
                service(false, false).setCollectionEnabled(ADMIN, MERCHANT, true, false);

        assertThat(out.collectionEnabled()).isTrue();
        verify(listings, never()).findActiveWithoutDeliveryTowns(any(), any());
        verify(sellers).setCollectionEnabled(eq(MERCHANT), eq(true), any(), eq(ADMIN_USER));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(AuditEventType.SELLER_COLLECTION_CHANGED), eq(ADMIN_USER.toString()),
                eq(MERCHANT.toString()), meta.capture());
        assertThat(meta.getValue())
                .containsEntry("collectionEnabled", true)
                .containsEntry("previous", false)
                .containsEntry("bySeller", false);
        assertThat(changes(true)).isEqualTo(1);
    }

    @Test
    @DisplayName("When a concurrent request made the same change first, the re-read under the "
            + "lock sees it and nothing is written twice")
    void theReReadUnderTheLockWins() {
        // Loaded before the lock: still collecting. Under the lock: already off.
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller(true)));
        Instant theirs = Instant.parse("2026-09-29T08:10:00Z");
        when(sellers.collectionStateOf(MERCHANT)).thenReturn(state(false, theirs));

        CollectionSettingResponse out =
                service(true, true).setCollectionEnabled(SELLER, MERCHANT, false, true);

        assertThat(out.collectionEnabled()).isFalse();
        assertThat(out.updatedAt()).isEqualTo(theirs);
        verify(sellers).lockForUpdate(MERCHANT);
        verify(sellers, never()).setCollectionEnabled(any(), anyBoolean(), any(), any());
        verify(audit, never()).record(eq(AuditEventType.SELLER_COLLECTION_CHANGED), any(), any(),
                anyMap());
        assertThat(changes(false)).isZero();
    }

    @Test
    @DisplayName("A caller whose uuid is not a UUID is still recorded as unattributed rather "
            + "than failing the change")
    void aLegacyCallerUuidIsTolerated() {
        AuthenticatedUser legacy = new AuthenticatedUser("legacy@example.com",
                Set.of("SUPER_ADMIN"), null, null, null, "ZW");
        when(sellers.findById(MERCHANT)).thenReturn(Optional.of(seller(true)));
        when(sellers.collectionStateOf(MERCHANT)).thenReturn(state(true, null));

        service(true, true).setCollectionEnabled(legacy, MERCHANT, false, false);

        verify(sellers).setCollectionEnabled(eq(MERCHANT), eq(false), any(), eq(null));
    }
}
