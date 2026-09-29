package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.innbucks.marketplaceservice.delivery.DeliveryMethod.COLLECTION;
import static com.innbucks.marketplaceservice.delivery.DeliveryMethod.DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryPlanTest {

    private static final Set<DeliveryMethod> BOTH = EnumSet.allOf(DeliveryMethod.class);
    private static final UUID A = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID B = UUID.fromString("4b1c2d3e-0f1a-4b2c-8d3e-5f6a7b8c9d0e");

    @Test
    @DisplayName("A uniform plan gives every seller the basket's method; the summary is that method")
    void uniformPlan() {
        DeliveryPlan collection = DeliveryPlan.uniform(COLLECTION, BOTH);
        assertThat(collection.methodFor(A)).isEqualTo(COLLECTION);
        assertThat(collection.needsDestination()).isFalse();
        assertThat(collection.summary()).isEqualTo(COLLECTION);

        DeliveryPlan delivery = DeliveryPlan.uniform(DELIVERY, BOTH);
        assertThat(delivery.methodFor(B)).isEqualTo(DELIVERY);
        assertThat(delivery.needsDestination()).isTrue();
        assertThat(delivery.summary()).isEqualTo(DELIVERY);
    }

    @Test
    @DisplayName("The cart's plan names no method and needs no destination")
    void noneIsTheCart() {
        assertThat(DeliveryPlan.NONE.isNone()).isTrue();
        assertThat(DeliveryPlan.NONE.methodFor(A)).isNull();
        assertThat(DeliveryPlan.NONE.needsDestination()).isFalse();
        assertThat(DeliveryPlan.NONE.summary()).isNull();
        assertThatThrownBy(() -> DeliveryPlan.uniform(null, BOTH))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A PARTIAL choice with a DELIVERY default still needs a destination: the unnamed "
            + "seller delivers")
    void partialChoicesNameEverySeller() {
        // A chose collection; B said nothing and so takes the DELIVERY default.
        // Built from the partial map directly, needsDestination would read only
        // A's COLLECTION and send B's parcel nowhere.
        DeliveryPlan plan = DeliveryPlan.forSellers(DELIVERY, Map.of(A, COLLECTION), List.of(A, B), BOTH);

        assertThat(plan.methodFor(A)).isEqualTo(COLLECTION);
        assertThat(plan.methodFor(B)).isEqualTo(DELIVERY);
        assertThat(plan.bySeller()).containsOnlyKeys(A, B);
        assertThat(plan.needsDestination()).isTrue();
        assertThat(plan.summary()).isEqualTo(DELIVERY);
    }

    @Test
    @DisplayName("A choice for a seller no longer in the basket is dropped; no choices is uniform")
    void staleChoicesAreDropped() {
        DeliveryPlan plan = DeliveryPlan.forSellers(COLLECTION, Map.of(B, DELIVERY), List.of(A), BOTH);
        assertThat(plan.bySeller()).containsOnlyKeys(A);
        assertThat(plan.needsDestination()).isFalse();
        assertThat(plan.summary()).isEqualTo(COLLECTION);

        assertThat(DeliveryPlan.forSellers(DELIVERY, Map.of(), List.of(A, B), BOTH))
                .isEqualTo(DeliveryPlan.uniform(DELIVERY, BOTH));
    }
}
