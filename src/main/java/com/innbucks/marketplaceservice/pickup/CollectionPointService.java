package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.delivery.DeliveryTownCatalog;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingProperties;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointRequest;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.MarketplaceSellerRepository;
import com.innbucks.marketplaceservice.seller.SellerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A seller's collection points (V18): where buyers collect.
 *
 * <p><b>Exactly one default whenever the seller has any.</b> The first point a
 * seller adds becomes it; making another the default demotes FIRST, then
 * marks (so the partial unique index never sees two); removing the default
 * promotes the oldest survivor. The index stops two defaults; only this
 * service can stop zero, so every write for one seller takes that seller's
 * row lock first — two concurrent first points must not both try to become
 * the default and turn a double-tap into a 500. The first write for a seller
 * with no record yet creates it race-safely before locking
 * ({@link SellerService#ensureExistsAndLock}).
 *
 * <p><b>Replace, never merge</b> — the payout destination's rule. A point is
 * redefined whole, hours included: "move the address" and "rename it" are
 * the same act of redefining where buyers go.
 *
 * <p>Audited without free text: the point id, its town and who acted. Not
 * notified: this is where goods are handed over, not where money goes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionPointService {

    /** A seller with more counters than this is a chain, and a different
     *  conversation. It also caps every per-seller batch on the read side. */
    static final int MAX_POINTS_PER_SELLER = 10;

    private final CollectionPointRepository points;
    private final CollectionPointHoursRepository hours;
    private final CollectionPointViews views;
    private final MarketplaceSellerRepository sellers;
    private final SellerService sellerService;
    private final DeliveryTownCatalog towns;
    private final TrackingProperties bounds;
    private final Msisdns msisdns;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<CollectionPointResponse> list(UUID merchantId) {
        return views.forMerchant(merchantId);
    }

    @Transactional
    public CollectionPointResponse create(AuthenticatedUser caller, UUID merchantId,
                                         CollectionPointRequest request, boolean bySeller) {
        Instant now = Instant.now();
        CollectionPoint point = CollectionPoint.builder()
                .id(UUID.randomUUID())
                .merchantId(merchantId)
                .createdAt(now)
                .build();
        // Validate FIRST: apply() writes nothing, so a refused request never
        // creates (or audits) a seller record it then rolls back.
        List<CollectionPointHours> weekly = apply(point, request, now);
        // Race-safe even for a seller with no row yet — a plain ensureExists
        // then lockForUpdate would let two first taps both insert the seller
        // and 500 on its primary key before the lock could serialise them.
        sellerService.ensureExistsAndLock(merchantId);
        if (points.countByMerchantId(merchantId) >= MAX_POINTS_PER_SELLER) {
            throw ApiException.conflict("collection_point_limit_reached",
                    "You can have at most " + MAX_POINTS_PER_SELLER + " collection points");
        }
        points.saveAndFlush(point);
        hours.saveAll(weekly);
        boolean first = points.countByMerchantId(merchantId) == 1;
        if (first) {
            points.markDefault(point.getId(), merchantId, now);
        }
        audit(AuditEventType.COLLECTION_POINT_CREATED, caller, point, bySeller,
                Map.of("madeDefault", first));
        log.info("Collection point created merchantId={} pointId={} town={} default={}",
                merchantId, point.getId(), point.getTownCode(), first);
        return views.one(merchantId, point.getId());
    }

    @Transactional
    public CollectionPointResponse update(AuthenticatedUser caller, UUID merchantId, UUID pointId,
                                         CollectionPointRequest request, boolean bySeller) {
        sellers.lockForUpdate(merchantId);
        CollectionPoint point = require(merchantId, pointId);
        Instant now = Instant.now();
        List<CollectionPointHours> weekly = apply(point, request, now);
        points.saveAndFlush(point);
        hours.deleteForPoint(point.getId());
        hours.saveAll(weekly);
        audit(AuditEventType.COLLECTION_POINT_UPDATED, caller, point, bySeller, Map.of());
        return views.one(merchantId, pointId);
    }

    /**
     * Removes a point. Orders already placed keep the snapshot they took, so
     * nobody's collection moves. Removing the default promotes the oldest
     * remaining point, keeping "exactly one default whenever there are any".
     */
    @Transactional
    public void delete(AuthenticatedUser caller, UUID merchantId, UUID pointId, boolean bySeller) {
        sellers.lockForUpdate(merchantId);
        CollectionPoint point = require(merchantId, pointId);
        boolean wasDefault = point.isDefaultPoint();
        points.delete(point);
        points.flush();
        UUID promoted = null;
        if (wasDefault) {
            List<CollectionPoint> survivors = points.findForMerchant(merchantId);
            if (!survivors.isEmpty()) {
                promoted = survivors.getFirst().getId();
                points.markDefault(promoted, merchantId, Instant.now());
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("wasDefault", wasDefault);
        if (promoted != null) {
            extra.put("promotedPointId", promoted.toString());
        }
        audit(AuditEventType.COLLECTION_POINT_DELETED, caller, point, bySeller, extra);
    }

    @Transactional
    public CollectionPointResponse makeDefault(AuthenticatedUser caller, UUID merchantId,
                                              UUID pointId, boolean bySeller) {
        sellers.lockForUpdate(merchantId);
        CollectionPoint point = require(merchantId, pointId);
        if (!point.isDefaultPoint()) {
            Instant now = Instant.now();
            // Demote THEN mark: the partial unique index must never see two.
            points.demoteDefaults(merchantId, now);
            points.markDefault(pointId, merchantId, now);
            audit(AuditEventType.COLLECTION_POINT_DEFAULT_CHANGED, caller, point, bySeller,
                    Map.of());
        }
        return views.one(merchantId, pointId);
    }

    // ------------------------------------------------------------------

    /**
     * Validates the whole request onto the point and returns its hours.
     * Everything is checked before anything is written, so a bad field leaves
     * the stored point exactly as it was.
     */
    private List<CollectionPointHours> apply(CollectionPoint point, CollectionPointRequest request,
                                             Instant now) {
        String name = requireText(request.name(), "name");
        String line1 = requireText(request.line1(), "line1");
        String townCode = towns.require(request.townCode(), "townCode").getCode();
        String phone = blankToNull(request.phone()) == null
                ? null : msisdns.normalize(request.phone(), "phone");
        BigDecimal[] pin = pin(request.latitude(), request.longitude());
        List<CollectionPointHours> weekly = OpeningHours.validate(point.getId(),
                request.openingHours());

        point.setName(name);
        point.setTownCode(townCode);
        point.setLine1(line1);
        point.setLine2(optionalText(request.line2()));
        point.setArea(optionalText(request.area()));
        point.setLandmark(optionalText(request.landmark()));
        point.setPhone(phone);
        point.setHoursNote(optionalText(request.hoursNote()));
        point.setLatitude(pin == null ? null : pin[0]);
        point.setLongitude(pin == null ? null : pin[1]);
        point.setUpdatedAt(now);
        return weekly;
    }

    /** Both or neither, and inside the market: a pin at 0,0 is a phone with
     *  no fix, not a shop in the Atlantic. */
    private BigDecimal[] pin(Double latitude, Double longitude) {
        if (latitude == null && longitude == null) {
            return null;
        }
        if (latitude == null || longitude == null) {
            throw ApiException.badRequest("pin_incomplete",
                    "Send both latitude and longitude, or neither");
        }
        if (!bounds.contains(latitude, longitude)) {
            throw ApiException.unprocessable("location_out_of_bounds",
                    "That position is outside the area we deliver in - check the map pin");
        }
        return new BigDecimal[] {
                BigDecimal.valueOf(latitude).setScale(6, RoundingMode.HALF_UP),
                BigDecimal.valueOf(longitude).setScale(6, RoundingMode.HALF_UP)};
    }

    private CollectionPoint require(UUID merchantId, UUID pointId) {
        return points.findByIdAndMerchantId(pointId, merchantId)
                .orElseThrow(() -> ApiException.notFound("collection_point_not_found",
                        "Collection point not found"));
    }

    private void audit(AuditEventType type, AuthenticatedUser caller, CollectionPoint point,
                       boolean bySeller, Map<String, Object> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("merchantId", point.getMerchantId().toString());
        metadata.put("townCode", point.getTownCode());
        metadata.put("bySeller", bySeller);
        metadata.putAll(extra);
        auditService.record(type, caller == null ? null : caller.uuid(), point.getId().toString(),
                metadata);
    }

    /** Sanitized, then required: text that is nothing but markup arrives empty. */
    private static String requireText(String value, String field) {
        String clean = blankToNull(TextSanitizer.sanitize(value));
        if (clean == null) {
            throw ApiException.badRequest("collection_point_field_required", field + " is required");
        }
        return clean;
    }

    private static String optionalText(String value) {
        return blankToNull(TextSanitizer.sanitize(value));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
