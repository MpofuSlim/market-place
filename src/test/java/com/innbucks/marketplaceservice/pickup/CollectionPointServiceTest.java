package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingProperties;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointRequest;
import com.innbucks.marketplaceservice.pickup.dto.OpeningHoursEntry;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.MarketplaceSellerRepository;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.support.TestTowns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollectionPointServiceTest {

    private static final UUID MERCHANT = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final AuthenticatedUser SELLER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"), MERCHANT.toString(), null, null,
            "ZW");

    private CollectionPointRepository points;
    private CollectionPointHoursRepository hours;
    private CollectionPointViews views;
    private MarketplaceSellerRepository sellers;
    private SellerService sellerService;
    private AuditService audit;
    private CollectionPointService service;

    @BeforeEach
    void setUp() {
        points = mock(CollectionPointRepository.class);
        hours = mock(CollectionPointHoursRepository.class);
        views = mock(CollectionPointViews.class);
        sellers = mock(MarketplaceSellerRepository.class);
        sellerService = mock(SellerService.class);
        audit = mock(AuditService.class);
        service = new CollectionPointService(points, hours, views, sellers, sellerService,
                TestTowns.zimbabwe(), new TrackingProperties(), new Msisdns("ZW"), audit);
    }

    private static CollectionPointRequest request() {
        return new CollectionPointRequest("Avondale shop", "harare", "14 Samora Machel Ave",
                "Shop 3, Avondale Shopping Centre", "Avondale", "Next to the pharmacy",
                "0242123456", "Closed on public holidays", -17.7985, 31.0452,
                List.of(new OpeningHoursEntry(DayOfWeek.MONDAY, "08:00", "17:00")));
    }

    private static CollectionPointRequest withName(String name) {
        CollectionPointRequest r = request();
        return new CollectionPointRequest(name, r.townCode(), r.line1(), r.line2(), r.area(),
                r.landmark(), r.phone(), r.hoursNote(), r.latitude(), r.longitude(),
                r.openingHours());
    }

    private static CollectionPointRequest withPin(Double lat, Double lon) {
        CollectionPointRequest r = request();
        return new CollectionPointRequest(r.name(), r.townCode(), r.line1(), r.line2(), r.area(),
                r.landmark(), r.phone(), r.hoursNote(), lat, lon, r.openingHours());
    }

    private static CollectionPoint stored(boolean isDefault) {
        return CollectionPoint.builder()
                .id(UUID.randomUUID())
                .merchantId(MERCHANT)
                .name("Old name")
                .townCode("bulawayo")
                .line1("1 Old Rd")
                .defaultPoint(isDefault)
                .createdAt(Instant.parse("2026-09-01T08:00:00Z"))
                .build();
    }

    private static ApiException api(Throwable ex) {
        return (ApiException) ex;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditedMetadata(AuditEventType type) {
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(type), eq(SELLER.uuid()), anyString(), meta.capture());
        return meta.getValue();
    }

    // ------------------------------------------------------------------ create

    @Test
    @DisplayName("A seller's first point becomes the default, under the seller's row lock")
    void firstPointBecomesDefault() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(0L, 1L);

        service.create(SELLER, MERCHANT, request(), true);

        ArgumentCaptor<CollectionPoint> saved = ArgumentCaptor.forClass(CollectionPoint.class);
        InOrder order = inOrder(sellerService, sellers, points, hours);
        order.verify(sellerService).ensureExists(MERCHANT);
        order.verify(sellers).lockForUpdate(MERCHANT);
        order.verify(points).countByMerchantId(MERCHANT);
        order.verify(points).saveAndFlush(saved.capture());
        order.verify(hours).saveAll(anyList());
        order.verify(points).markDefault(eq(saved.getValue().getId()), eq(MERCHANT), any());
        assertThat(auditedMetadata(AuditEventType.COLLECTION_POINT_CREATED))
                .containsEntry("madeDefault", true)
                .containsEntry("bySeller", true);
        verify(views).one(MERCHANT, saved.getValue().getId());
    }

    @Test
    @DisplayName("A later point is added without touching the default")
    void laterPointIsNotDefault() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(2L, 3L);

        service.create(SELLER, MERCHANT, request(), true);

        verify(points, never()).markDefault(any(), any(), any());
        verify(points, never()).demoteDefaults(any(), any());
        assertThat(auditedMetadata(AuditEventType.COLLECTION_POINT_CREATED))
                .containsEntry("madeDefault", false);
    }

    @Test
    @DisplayName("The eleventh point is refused 409 before anything is written")
    void capIsEnforced() {
        when(points.countByMerchantId(MERCHANT))
                .thenReturn((long) CollectionPointService.MAX_POINTS_PER_SELLER);

        assertThatThrownBy(() -> service.create(SELLER, MERCHANT, request(), true))
                .satisfies(ex -> {
                    assertThat(api(ex).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api(ex).code()).isEqualTo("collection_point_limit_reached");
                });
        verify(points, never()).saveAndFlush(any());
        verifyNoInteractions(hours, audit);
    }

    @Test
    @DisplayName("Fields are cleaned: the phone is stored E.164, the pin at six decimals, blanks as null")
    void normalisesFields() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(0L, 1L);
        CollectionPointRequest r = request();

        service.create(SELLER, MERCHANT, new CollectionPointRequest("  Avondale shop ", "HARARE",
                r.line1(), "   ", r.area(), r.landmark(), "0242 123 456", null, r.latitude(),
                r.longitude(), null), true);

        ArgumentCaptor<CollectionPoint> saved = ArgumentCaptor.forClass(CollectionPoint.class);
        verify(points).saveAndFlush(saved.capture());
        CollectionPoint p = saved.getValue();
        assertThat(p.getName()).isEqualTo("Avondale shop");
        assertThat(p.getTownCode()).isEqualTo("harare");
        assertThat(p.getLine2()).isNull();
        assertThat(p.getHoursNote()).isNull();
        assertThat(p.getPhone()).isEqualTo("+263242123456");
        assertThat(p.getLatitude()).isEqualByComparingTo(new BigDecimal("-17.798500"));
        assertThat(p.getLatitude().scale()).isEqualTo(6);
        assertThat(p.getMerchantId()).isEqualTo(MERCHANT);
    }

    @Test
    @DisplayName("A name that is nothing but markup arrives empty and is refused")
    void markupOnlyNameIsRequired() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(SELLER, MERCHANT,
                withName("<script>alert(1)</script>"), true))
                .satisfies(ex -> {
                    assertThat(api(ex).code()).isEqualTo("collection_point_field_required");
                    assertThat(ex.getMessage()).isEqualTo("name is required");
                });
        verify(points, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("An unknown town is a 400 unknown_town naming townCode")
    void unknownTown() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(0L);
        CollectionPointRequest r = request();

        assertThatThrownBy(() -> service.create(SELLER, MERCHANT, new CollectionPointRequest(
                r.name(), "johannesburg", r.line1(), null, null, null, null, null, null, null,
                null), true))
                .satisfies(ex -> assertThat(api(ex).code()).isEqualTo("unknown_town"));
        verify(points, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Half a pin is a 400; a pin outside the market is a 422; neither writes")
    void pinRules() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(SELLER, MERCHANT, withPin(-17.8, null), true))
                .satisfies(ex -> assertThat(api(ex).code()).isEqualTo("pin_incomplete"));
        assertThatThrownBy(() -> service.create(SELLER, MERCHANT, withPin(0.0, 0.0), true))
                .satisfies(ex -> {
                    assertThat(api(ex).status().value()).isEqualTo(422);
                    assertThat(api(ex).code()).isEqualTo("location_out_of_bounds");
                });
        verify(points, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("No pin at all is fine")
    void noPin() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(0L, 1L);

        service.create(SELLER, MERCHANT, withPin(null, null), true);

        ArgumentCaptor<CollectionPoint> saved = ArgumentCaptor.forClass(CollectionPoint.class);
        verify(points).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getLatitude()).isNull();
        assertThat(saved.getValue().getLongitude()).isNull();
    }

    @Test
    @DisplayName("The audit row names the town and the actor, never the seller's free text")
    void auditCarriesNoFreeText() {
        when(points.countByMerchantId(MERCHANT)).thenReturn(0L, 1L);

        service.create(SELLER, MERCHANT, request(), false);

        Map<String, Object> meta = auditedMetadata(AuditEventType.COLLECTION_POINT_CREATED);
        assertThat(meta).containsOnlyKeys("merchantId", "townCode", "bySeller", "madeDefault");
        assertThat(meta).containsEntry("bySeller", false).containsEntry("townCode", "harare");
        assertThat(meta.values()).noneMatch(v -> String.valueOf(v).contains("Samora")
                || String.valueOf(v).contains("pharmacy") || String.valueOf(v).contains("Avondale"));
    }

    // ------------------------------------------------------------------ update

    @Test
    @DisplayName("Another seller's point (or a missing one) is the same 404")
    void updateIsOwnerScoped() {
        UUID id = UUID.randomUUID();
        when(points.findByIdAndMerchantId(id, MERCHANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(SELLER, MERCHANT, id, request(), true))
                .satisfies(ex -> {
                    assertThat(api(ex).status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api(ex).code()).isEqualTo("collection_point_not_found");
                });
        verify(sellers).lockForUpdate(MERCHANT);
        verify(points, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Update replaces the whole point and its hours: delete the old hours, then insert")
    void updateReplacesWhole() {
        CollectionPoint existing = stored(true);
        when(points.findByIdAndMerchantId(existing.getId(), MERCHANT))
                .thenReturn(Optional.of(existing));

        service.update(SELLER, MERCHANT, existing.getId(), request(), true);

        assertThat(existing.getName()).isEqualTo("Avondale shop");
        assertThat(existing.getTownCode()).isEqualTo("harare");
        assertThat(existing.getUpdatedAt()).isNotNull();
        InOrder order = inOrder(points, hours);
        order.verify(points).saveAndFlush(existing);
        order.verify(hours).deleteForPoint(existing.getId());
        order.verify(hours).saveAll(anyList());
        verify(audit).record(eq(AuditEventType.COLLECTION_POINT_UPDATED), eq(SELLER.uuid()),
                eq(existing.getId().toString()), any());
    }

    @Test
    @DisplayName("A bad field leaves the stored point exactly as it was")
    void badUpdateChangesNothing() {
        CollectionPoint existing = stored(true);
        when(points.findByIdAndMerchantId(existing.getId(), MERCHANT))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.update(SELLER, MERCHANT, existing.getId(),
                withPin(-17.8, null), true))
                .satisfies(ex -> assertThat(api(ex).code()).isEqualTo("pin_incomplete"));
        assertThat(existing.getName()).isEqualTo("Old name");
        assertThat(existing.getTownCode()).isEqualTo("bulawayo");
        verify(points, never()).saveAndFlush(any());
        verify(hours, never()).deleteForPoint(any());
    }

    // ------------------------------------------------------------------ delete

    @Test
    @DisplayName("Deleting the default promotes the oldest survivor")
    void deletingDefaultPromotes() {
        CollectionPoint doomed = stored(true);
        CollectionPoint survivor = stored(false);
        when(points.findByIdAndMerchantId(doomed.getId(), MERCHANT)).thenReturn(Optional.of(doomed));
        when(points.findForMerchant(MERCHANT)).thenReturn(List.of(survivor));

        service.delete(SELLER, MERCHANT, doomed.getId(), true);

        InOrder order = inOrder(points);
        order.verify(points).delete(doomed);
        order.verify(points).flush();
        order.verify(points).markDefault(eq(survivor.getId()), eq(MERCHANT), any());
        assertThat(auditedMetadata(AuditEventType.COLLECTION_POINT_DELETED))
                .containsEntry("wasDefault", true)
                .containsEntry("promotedPointId", survivor.getId().toString());
    }

    @Test
    @DisplayName("Deleting the last point leaves none, and promotes nothing")
    void deletingTheLastPoint() {
        CollectionPoint doomed = stored(true);
        when(points.findByIdAndMerchantId(doomed.getId(), MERCHANT)).thenReturn(Optional.of(doomed));
        when(points.findForMerchant(MERCHANT)).thenReturn(List.of());

        service.delete(SELLER, MERCHANT, doomed.getId(), true);

        verify(points, never()).markDefault(any(), any(), any());
        assertThat(auditedMetadata(AuditEventType.COLLECTION_POINT_DELETED))
                .containsEntry("wasDefault", true)
                .doesNotContainKey("promotedPointId");
    }

    @Test
    @DisplayName("Deleting a non-default point leaves the default alone")
    void deletingNonDefault() {
        CollectionPoint doomed = stored(false);
        when(points.findByIdAndMerchantId(doomed.getId(), MERCHANT)).thenReturn(Optional.of(doomed));

        service.delete(SELLER, MERCHANT, doomed.getId(), true);

        verify(points, never()).findForMerchant(any());
        verify(points, never()).markDefault(any(), any(), any());
    }

    // ------------------------------------------------------------ makeDefault

    @Test
    @DisplayName("Making a point the default demotes FIRST, then marks — the index never sees two")
    void makeDefaultDemotesThenMarks() {
        CollectionPoint target = stored(false);
        when(points.findByIdAndMerchantId(target.getId(), MERCHANT)).thenReturn(Optional.of(target));

        service.makeDefault(SELLER, MERCHANT, target.getId(), true);

        InOrder order = inOrder(sellers, points);
        order.verify(sellers).lockForUpdate(MERCHANT);
        order.verify(points).demoteDefaults(eq(MERCHANT), any());
        order.verify(points).markDefault(eq(target.getId()), eq(MERCHANT), any());
        verify(audit).record(eq(AuditEventType.COLLECTION_POINT_DEFAULT_CHANGED), any(), any(),
                any());
    }

    @Test
    @DisplayName("Making the current default the default again is a no-op, not an audit row")
    void makeDefaultIsIdempotent() {
        CollectionPoint target = stored(true);
        when(points.findByIdAndMerchantId(target.getId(), MERCHANT)).thenReturn(Optional.of(target));

        service.makeDefault(SELLER, MERCHANT, target.getId(), true);

        verify(points, never()).demoteDefaults(any(), any());
        verify(points, never()).markDefault(any(), any(), any());
        verifyNoInteractions(audit);
        verify(views).one(MERCHANT, target.getId());
    }
}
