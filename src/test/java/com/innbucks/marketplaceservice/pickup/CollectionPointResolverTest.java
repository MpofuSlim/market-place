package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointChoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CollectionPointResolverTest {

    private static final UUID SELLER_A = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID SELLER_B = UUID.fromString("2d6f1b83-9c4e-4a57-8e21-6b3f9d0c7a15");
    private static final UUID SELLER_C = UUID.fromString("9a1b2c3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d");

    private CollectionPointRepository points;
    private OrderCollectionPointRepository snapshots;
    private CollectionPointResolver resolver;

    private final CollectionPoint aDefault = point(SELLER_A, "A main", true);
    private final CollectionPoint aSecond = point(SELLER_A, "A second", false);
    private final CollectionPoint bDefault = point(SELLER_B, "B main", true);

    @BeforeEach
    void setUp() {
        points = mock(CollectionPointRepository.class);
        snapshots = mock(OrderCollectionPointRepository.class);
        resolver = new CollectionPointResolver(points, snapshots);
        // The repository's own order: per seller, default first.
        when(points.findForMerchants(anyCollection()))
                .thenReturn(List.of(aDefault, aSecond, bDefault));
    }

    private static CollectionPoint point(UUID merchant, String name, boolean isDefault) {
        return CollectionPoint.builder()
                .id(UUID.randomUUID())
                .merchantId(merchant)
                .name(name)
                .townCode("harare")
                .line1("1 Main Rd")
                .area("CBD")
                .landmark("Blue door")
                .phone("+263242123456")
                .latitude(new BigDecimal("-17.829200"))
                .longitude(new BigDecimal("31.052200"))
                .defaultPoint(isDefault)
                .createdAt(Instant.parse("2026-09-01T08:00:00Z"))
                .build();
    }

    private static String code(Throwable ex) {
        return ((ApiException) ex).code();
    }

    @Test
    @DisplayName("With no choice, each seller resolves to their default; a seller with none is absent")
    void defaultsWhenNothingChosen() {
        Map<UUID, CollectionPoint> resolved = resolver.resolve(
                List.of(SELLER_A, SELLER_B, SELLER_C), null);

        assertThat(resolved).containsExactly(
                Map.entry(SELLER_A, aDefault), Map.entry(SELLER_B, bDefault));
    }

    @Test
    @DisplayName("The buyer's choice wins over the default, per seller")
    void choiceWins() {
        Map<UUID, CollectionPoint> resolved = resolver.resolve(List.of(SELLER_A, SELLER_B),
                List.of(new CollectionPointChoice(SELLER_A, aSecond.getId())));

        assertThat(resolved.get(SELLER_A)).isSameAs(aSecond);
        assertThat(resolved.get(SELLER_B)).isSameAs(bDefault);
    }

    @Test
    @DisplayName("Choosing another seller's point is refused - it is not one of this seller's")
    void crossSellerChoiceIsRefused() {
        assertThatThrownBy(() -> resolver.resolve(List.of(SELLER_A, SELLER_B),
                List.of(new CollectionPointChoice(SELLER_A, bDefault.getId()))))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("unknown_collection_point"));
    }

    @Test
    @DisplayName("Choosing a point that does not exist is refused, never quietly replaced by the default")
    void unknownPointIsRefused() {
        assertThatThrownBy(() -> resolver.resolve(List.of(SELLER_A),
                List.of(new CollectionPointChoice(SELLER_A, UUID.randomUUID()))))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("unknown_collection_point"));
    }

    @Test
    @DisplayName("Naming one seller twice is refused, even with the same point")
    void duplicateSellerIsRefused() {
        assertThatThrownBy(() -> resolver.resolve(List.of(SELLER_A), List.of(
                new CollectionPointChoice(SELLER_A, aDefault.getId()),
                new CollectionPointChoice(SELLER_A, aDefault.getId()))))
                .satisfies(ex -> assertThat(code(ex))
                        .isEqualTo("duplicate_collection_point_choice"));
    }

    @Test
    @DisplayName("A choice for a seller no longer in the basket is ignored, not an error")
    void choiceForAbsentSellerIsIgnored() {
        Map<UUID, CollectionPoint> resolved = resolver.resolve(List.of(SELLER_B),
                List.of(new CollectionPointChoice(SELLER_A, aSecond.getId())));

        assertThat(resolved).containsExactly(Map.entry(SELLER_B, bDefault));
    }

    @Test
    @DisplayName("Half-filled choices are skipped rather than 500ing")
    void incompleteChoicesAreSkipped() {
        List<CollectionPointChoice> choices = new ArrayList<>();
        choices.add(null);
        choices.add(new CollectionPointChoice(SELLER_A, null));
        choices.add(new CollectionPointChoice(null, aSecond.getId()));

        Map<UUID, CollectionPoint> resolved = resolver.resolve(List.of(SELLER_A), choices);

        assertThat(resolved.get(SELLER_A)).isSameAs(aDefault);
    }

    @Test
    @DisplayName("An empty basket asks the database nothing")
    void emptyBasket() {
        assertThat(resolver.resolve(List.of(), null)).isEmpty();
        verify(points, never()).findForMerchants(any());
    }

    @Test
    @DisplayName("Recording copies each resolved point onto the order as a snapshot")
    @SuppressWarnings("unchecked")
    void recordSnapshots() {
        UUID order = UUID.randomUUID();

        resolver.record(order, Map.of(SELLER_A, aSecond));

        ArgumentCaptor<List<OrderCollectionPoint>> saved = ArgumentCaptor.forClass(List.class);
        verify(snapshots).saveAll(saved.capture());
        OrderCollectionPoint row = saved.getValue().getFirst();
        assertThat(row.getOrderId()).isEqualTo(order);
        assertThat(row.getMerchantId()).isEqualTo(SELLER_A);
        assertThat(row.getCollectionPointId()).isEqualTo(aSecond.getId());
        assertThat(row.getName()).isEqualTo("A second");
        assertThat(row.getTownCode()).isEqualTo("harare");
        assertThat(row.getLine1()).isEqualTo("1 Main Rd");
        assertThat(row.getArea()).isEqualTo("CBD");
        assertThat(row.getLandmark()).isEqualTo("Blue door");
        assertThat(row.getPhone()).isEqualTo("+263242123456");
        assertThat(row.getLatitude()).isEqualByComparingTo("-17.8292");
        assertThat(row.getLongitude()).isEqualByComparingTo("31.0522");
    }

    @Test
    @DisplayName("Nothing resolved, nothing recorded")
    void recordNothing() {
        resolver.record(UUID.randomUUID(), Map.of());
        resolver.record(UUID.randomUUID(), null);
        verifyNoInteractions(snapshots);
    }
}
