package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ParcelCloseMethodTest {

    private static OrderFulfilment parcel(FulfilmentStatus status, DeliveryConfirmer by,
                                          boolean dispatched) {
        Instant now = Instant.now();
        return OrderFulfilment.builder().status(status).deliveredBy(by)
                .deliveredAt(status == FulfilmentStatus.DELIVERED ? now : null)
                .unfulfilledAt(status == FulfilmentStatus.UNFULFILLED ? now : null)
                .dispatchedAt(dispatched ? now.minusSeconds(3600) : null).build();
    }

    @Test
    @DisplayName("Each way a parcel ends reads as the seller would say it")
    void everyCloseHasAName() {
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.BUYER,
                true), DeliveryMethod.DELIVERY)).isEqualTo(ParcelCloseMethod.BUYER_CONFIRMED);
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.DELIVERED,
                DeliveryConfirmer.RECIPIENT, true), DeliveryMethod.COLLECTION))
                .isEqualTo(ParcelCloseMethod.COLLECTION_CODE);
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.DELIVERED,
                DeliveryConfirmer.MERCHANT, true), DeliveryMethod.DELIVERY))
                .isEqualTo(ParcelCloseMethod.SELLER_MARKED);
        // A collection declined after it was set aside = never picked up.
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, true),
                DeliveryMethod.COLLECTION)).isEqualTo(ParcelCloseMethod.NOT_COLLECTED);
        // Declined before it ever left, either method.
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, false),
                DeliveryMethod.COLLECTION)).isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
        assertThat(ParcelCloseMethod.of(parcel(FulfilmentStatus.UNFULFILLED, null, false),
                DeliveryMethod.DELIVERY)).isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
    }

    @Test
    @DisplayName("A buyer's cancellation is never read as the seller failing to supply (V16)")
    void buyerCancellationIsItsOwnClose() {
        for (DeliveryMethod method : DeliveryMethod.values()) {
            OrderFulfilment cancelled = parcel(FulfilmentStatus.UNFULFILLED, null, false);
            cancelled.setUnfulfilledBy(UnfulfilledBy.BUYER);
            assertThat(ParcelCloseMethod.of(cancelled, method))
                    .isEqualTo(ParcelCloseMethod.BUYER_CANCELLED);
            assertThat(ParcelCloseMethod.closedAt(cancelled)).isEqualTo(cancelled.getUnfulfilledAt());

            OrderFulfilment declined = parcel(FulfilmentStatus.UNFULFILLED, null, false);
            declined.setUnfulfilledBy(UnfulfilledBy.SELLER);
            assertThat(ParcelCloseMethod.of(declined, method))
                    .isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
        }
    }

    @Test
    @DisplayName("An open parcel has no close method and no close time")
    void openParcelsAreNotClosed() {
        for (FulfilmentStatus open : new FulfilmentStatus[]{FulfilmentStatus.PREPARING,
                FulfilmentStatus.DISPATCHED}) {
            OrderFulfilment p = parcel(open, null, open == FulfilmentStatus.DISPATCHED);
            assertThat(ParcelCloseMethod.of(p, DeliveryMethod.DELIVERY)).isNull();
            assertThat(ParcelCloseMethod.closedAt(p)).isNull();
        }
        assertThat(ParcelCloseMethod.of(null, DeliveryMethod.DELIVERY)).isNull();
    }
}
