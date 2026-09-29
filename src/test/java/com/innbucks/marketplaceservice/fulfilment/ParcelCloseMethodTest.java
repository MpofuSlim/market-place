package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ParcelCloseMethodTest {

    private static OrderFulfilment parcel(FulfilmentStatus status, DeliveryConfirmer by,
                                          boolean dispatched, DeliveryMethod method) {
        Instant now = Instant.now();
        return OrderFulfilment.builder().status(status).deliveredBy(by)
                .deliveryMethod(method)
                .deliveredAt(status == FulfilmentStatus.DELIVERED ? now : null)
                .unfulfilledAt(status == FulfilmentStatus.UNFULFILLED ? now : null)
                .dispatchedAt(dispatched ? now.minusSeconds(3600) : null).build();
    }

    @Test
    @DisplayName("Each way a parcel ends reads as the seller would say it")
    void everyCloseHasAName() {
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.BUYER,
                true, DeliveryMethod.DELIVERY))).isEqualTo(ParcelCloseMethod.BUYER_CONFIRMED);
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.DELIVERED,
                DeliveryConfirmer.RECIPIENT, true, DeliveryMethod.COLLECTION)))
                .isEqualTo(ParcelCloseMethod.COLLECTION_CODE);
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.DELIVERED,
                DeliveryConfirmer.MERCHANT, true, DeliveryMethod.DELIVERY)))
                .isEqualTo(ParcelCloseMethod.SELLER_MARKED);
        // A collection declined after it was set aside = never picked up.
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, true,
                DeliveryMethod.COLLECTION))).isEqualTo(ParcelCloseMethod.NOT_COLLECTED);
        // Declined before it ever left, either method.
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, false,
                DeliveryMethod.COLLECTION))).isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, false,
                DeliveryMethod.DELIVERY))).isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
    }

    @Test
    @DisplayName("NOT_COLLECTED is decided by the PARCEL's method: a dispatched delivery that ended UNFULFILLED is never read as a no-show (V21)")
    void notCollectedReadsTheParcelsOwnMethod() {
        // Only the parcel carries a method now — there is no order argument to
        // get wrong. A collected parcel of a mixed order (whose summary is
        // DELIVERY) still reads NOT_COLLECTED; a delivered one never does.
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, true,
                DeliveryMethod.COLLECTION))).isEqualTo(ParcelCloseMethod.NOT_COLLECTED);
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, true,
                DeliveryMethod.DELIVERY))).isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
    }

    @Test
    @DisplayName("A buyer's cancellation is never read as the seller failing to supply (V16)")
    void buyerCancellationIsItsOwnClose() {
        for (DeliveryMethod method : DeliveryMethod.values()) {
            OrderFulfilment cancelled = parcel(FulfilmentStatus.UNFULFILLED, null, false, method);
            cancelled.setUnfulfilledBy(UnfulfilledBy.BUYER);
            assertThat(ParcelCloseMethod.of(cancelled))
                    .isEqualTo(ParcelCloseMethod.BUYER_CANCELLED);
            assertThat(ParcelCloseMethod.closedAt(cancelled)).isEqualTo(cancelled.getUnfulfilledAt());

            OrderFulfilment declined = parcel(FulfilmentStatus.UNFULFILLED, null, false, method);
            declined.setUnfulfilledBy(UnfulfilledBy.SELLER);
            assertThat(ParcelCloseMethod.of(declined))
                    .isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
        }
    }

    @Test
    @DisplayName("An open parcel has no close method and no close time")
    void openParcelsAreNotClosed() {
        for (FulfilmentStatus open : new FulfilmentStatus[]{FulfilmentStatus.PREPARING,
                FulfilmentStatus.DISPATCHED}) {
            OrderFulfilment p = parcel(open, null, open == FulfilmentStatus.DISPATCHED,
                    DeliveryMethod.DELIVERY);
            assertThat(ParcelCloseMethod.of(p)).isNull();
            assertThat(ParcelCloseMethod.closedAt(p)).isNull();
        }
        assertThat(ParcelCloseMethod.of(null)).isNull();
    }
}
