package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where a parcel is: the courier's side (the run, and position reports) and the
 * buyer's side (the tracking screen).
 *
 * <p><b>The position is the LATEST only.</b> A report overwrites the last one;
 * no route is ever kept. "Where is my parcel now" needs one point, and a trail
 * of a driver's movements ending at a buyer's front door is personal data
 * nobody asked for — not keeping it is also why there is no retention job.
 *
 * <p><b>Who may report</b> is any member of the selling organization
 * ({@code COURIER}, which {@code JwtFilter} grants OWNER, ADMIN and STAFF
 * alike), for that organization's DELIVERY parcels while they are DISPATCHED —
 * nothing else. A courier sees no money: their run carries destinations and
 * items, not prices or settlements.
 *
 * <p><b>The buyer sees the position only while it means something</b>: a
 * DELIVERY parcel in transit. Once it is delivered or cancelled the pin
 * disappears from the buyer's screen — the last point is often their own
 * doorstep, and afterwards it tells them nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParcelTrackingService {

    static final int MAX_RUN_SIZE = 100;

    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository itemRepository;
    private final TrackingProperties properties;
    private final MarketplaceMetrics metrics;

    // ------------------------------------------------------------------
    // Courier side
    // ------------------------------------------------------------------

    /** The caller's organization's DELIVERY parcels in transit, longest out
     *  first. Two batch reads for the whole run, never one per parcel. */
    @Transactional(readOnly = true)
    public List<CourierParcelResponse> courierRun(AuthenticatedUser caller, int page, int size) {
        UUID organization = requireCourierScope(caller);
        List<OrderFulfilment> parcels = fulfilmentRepository.findRun(organization,
                FulfilmentStatus.DISPATCHED, DeliveryMethod.DELIVERY,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_RUN_SIZE)));
        if (parcels.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = parcels.stream().map(OrderFulfilment::getOrderId).distinct().toList();
        Map<UUID, MarketOrder> orders = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(MarketOrder::getId, Function.identity()));
        Map<UUID, List<MarketOrderItem>> itemsByOrder = itemRepository.findByOrderIdIn(orderIds)
                .stream().collect(Collectors.groupingBy(MarketOrderItem::getOrderId));
        List<CourierParcelResponse> run = new ArrayList<>(parcels.size());
        for (OrderFulfilment parcel : parcels) {
            MarketOrder order = orders.get(parcel.getOrderId());
            if (order == null) {
                continue;
            }
            run.add(new CourierParcelResponse(
                    parcel.getId(),
                    parcel.getTrackingCode(),
                    order.getOrderRef(),
                    TrackingStatus.of(parcel.getStatus()),
                    parcel.getDispatchedAt(),
                    FulfilmentDestination.from(order),
                    itemsByOrder.getOrDefault(order.getId(), List.of()).stream()
                            .filter(item -> parcel.getMerchantId().equals(item.getMerchantId()))
                            .map(item -> new CourierParcelResponse.Item(item.getTitleSnapshot(),
                                    item.getQuantity()))
                            .toList(),
                    parcel.getLastLocationAt()));
        }
        return run;
    }

    /**
     * Records where the courier is with one parcel.
     *
     * <p>Every reason to IGNORE a report — sent within the throttle window,
     * older than the stored fix, too old to be "now" — is a {@code 200} with
     * {@code accepted: false}, never an error: a phone reporting every few
     * seconds must not fill the courier's screen with failures for doing its
     * job. Only a request that can never succeed is refused: not this
     * organization's parcel (404), not in transit (409), outside the market's
     * bounds (422).
     */
    @Transactional
    public LocationPingResponse recordLocation(AuthenticatedUser caller, UUID fulfilmentId,
                                               LocationPingRequest request) {
        UUID organization = requireCourierScope(caller);
        OrderFulfilment parcel = fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(ParcelTrackingService::notFound);
        if (!parcel.getMerchantId().equals(organization)) {
            // Another business's parcel is the same 404 as a missing one.
            throw notFound();
        }
        MarketOrder order = orderRepository.findById(parcel.getOrderId())
                .orElseThrow(ParcelTrackingService::notFound);
        if (order.getDeliveryMethod() != DeliveryMethod.DELIVERY
                || parcel.getStatus() != FulfilmentStatus.DISPATCHED) {
            metrics.trackingPing("not_in_transit");
            throw ApiException.conflict("parcel_not_in_transit",
                    "Positions can only be reported for a delivery that is on its way");
        }
        if (!withinBounds(request.latitude(), request.longitude())) {
            metrics.trackingPing("out_of_bounds");
            throw ApiException.unprocessable("location_out_of_bounds",
                    "That position is outside the area we deliver in - check the phone's GPS");
        }

        Instant now = Instant.now();
        Instant recordedAt = request.recordedAt() == null || request.recordedAt().isAfter(now)
                ? now : request.recordedAt();
        if (recordedAt.isBefore(now.minusSeconds(properties.getMaxPingAgeSeconds()))) {
            metrics.trackingPing("ignored");
            return new LocationPingResponse(false, parcel.getLastLocationAt());
        }
        // Stored only when newer than the last fix by at least the throttle
        // window — which also drops an out-of-order report from a phone that
        // buffered while offline. The update count IS the answer.
        int stored = fulfilmentRepository.recordLocation(parcel.getId(),
                sixPlaces(request.latitude()), sixPlaces(request.longitude()),
                request.accuracyMeters(), recordedAt, caller.uuid(),
                recordedAt.minus(Duration.ofSeconds(properties.getMinPingIntervalSeconds())));
        if (stored == 1) {
            metrics.trackingPing("accepted");
            return new LocationPingResponse(true, recordedAt);
        }
        metrics.trackingPing("ignored");
        return new LocationPingResponse(false, parcel.getLastLocationAt());
    }

    // ------------------------------------------------------------------
    // Buyer side
    // ------------------------------------------------------------------

    /**
     * The buyer's tracking screen for one of their parcels. Owner-masked: a
     * parcel on someone else's order, or one that does not belong to the order
     * named in the path, is the same 404 as one that does not exist.
     */
    @Transactional(readOnly = true)
    public ParcelTrackingResponse buyerTracking(AuthenticatedUser buyer, UUID orderId,
                                                UUID fulfilmentId) {
        OrderFulfilment parcel = fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(ParcelTrackingService::notFound);
        MarketOrder order = orderRepository.findById(parcel.getOrderId())
                .orElseThrow(ParcelTrackingService::notFound);
        if (!order.getId().equals(orderId)
                || !order.getBuyerUuid().equals(UUID.fromString(buyer.uuid()))) {
            throw notFound();
        }
        boolean inTransit = order.getDeliveryMethod() == DeliveryMethod.DELIVERY
                && parcel.getStatus() == FulfilmentStatus.DISPATCHED;
        return new ParcelTrackingResponse(
                parcel.getId(),
                order.getId(),
                order.getOrderRef(),
                parcel.getTrackingCode(),
                order.getDeliveryMethod(),
                TrackingStatus.of(parcel.getStatus()),
                timeline(parcel),
                FulfilmentDestination.from(order),
                inTransit ? ParcelLocation.of(parcel) : null,
                parcel.getStatus() == FulfilmentStatus.UNFULFILLED
                        ? parcel.getUnfulfilledReason() : null,
                parcel.getStatus() == FulfilmentStatus.UNFULFILLED
                        ? parcel.getUnfulfilledBy() : null);
    }

    /** Every stage the parcel reached, oldest first, from the stamps the
     *  lifecycle already keeps — no separate history table to disagree with
     *  them. */
    static List<ParcelTrackingResponse.Stage> timeline(OrderFulfilment parcel) {
        List<ParcelTrackingResponse.Stage> stages = new ArrayList<>(3);
        stages.add(new ParcelTrackingResponse.Stage(TrackingStatus.RECEIVED, parcel.getCreatedAt()));
        if (parcel.getDispatchedAt() != null) {
            stages.add(new ParcelTrackingResponse.Stage(TrackingStatus.DISPATCHED,
                    parcel.getDispatchedAt()));
        }
        if (parcel.getDeliveredAt() != null) {
            stages.add(new ParcelTrackingResponse.Stage(TrackingStatus.DELIVERED,
                    parcel.getDeliveredAt()));
        }
        if (parcel.getUnfulfilledAt() != null) {
            stages.add(new ParcelTrackingResponse.Stage(TrackingStatus.CANCELLED,
                    parcel.getUnfulfilledAt()));
        }
        return stages;
    }

    // ------------------------------------------------------------------

    private boolean withinBounds(double latitude, double longitude) {
        return latitude >= properties.getMinLatitude() && latitude <= properties.getMaxLatitude()
                && longitude >= properties.getMinLongitude()
                && longitude <= properties.getMaxLongitude();
    }

    /** Six decimal places is ~11 cm: finer than any phone's fix, and exactly
     *  the column's scale, so nothing is rounded twice. */
    private static BigDecimal sixPlaces(double degrees) {
        return BigDecimal.valueOf(degrees).setScale(6, RoundingMode.HALF_UP);
    }

    private static UUID requireCourierScope(AuthenticatedUser caller) {
        String organization = caller.deliversFor();
        if (organization == null || organization.isBlank()) {
            throw ApiException.forbidden("courier_scope_missing",
                    "Sign in with a business that sells on the marketplace to deliver its parcels");
        }
        try {
            return UUID.fromString(organization.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.forbidden("courier_scope_missing",
                    "Sign in with a business that sells on the marketplace to deliver its parcels");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("fulfilment_not_found", "Fulfilment not found");
    }
}
