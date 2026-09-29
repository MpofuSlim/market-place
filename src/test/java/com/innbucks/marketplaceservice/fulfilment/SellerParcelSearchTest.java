package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.SellerParcelQueryService.Search;
import com.innbucks.marketplaceservice.fulfilment.SellerParcelQueryService.SearchKind;
import com.innbucks.marketplaceservice.order.MarketOrder;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one search box, read by shape — pinned per shape. */
class SellerParcelSearchTest {

    private final Msisdns msisdns = new Msisdns("ZW");

    private Search parse(String q) {
        return Search.parse(q, msisdns);
    }

    @Test
    @DisplayName("An order reference, however it was typed")
    void orderRef() {
        assertThat(parse("MKT-4F9A1C22B7D3")).isEqualTo(new Search(SearchKind.ORDER_REF, "MKT-4F9A1C22B7D3"));
        assertThat(parse(" mkt 4f9a1c22b7d3 ")).isEqualTo(new Search(SearchKind.ORDER_REF, "MKT-4F9A1C22B7D3"));
    }

    @Test
    @DisplayName("A tracking code, forgiving the characters people confuse")
    void trackingCode() {
        assertThat(parse("trk-7f3k-9q2m-4x")).isEqualTo(new Search(SearchKind.TRACKING_CODE, "TRK-7F3K9Q2M4X"));
        assertThatThrownBy(() -> parse("TRK-123"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("invalid_search");
    }

    @Test
    @DisplayName("A phone in any local spelling becomes the E.164 every payer is stored as")
    void phone() {
        assertThat(parse("0771234567")).isEqualTo(new Search(SearchKind.PHONE, "+263771234567"));
        assertThat(parse("+263 77 123 4567")).isEqualTo(new Search(SearchKind.PHONE, "+263771234567"));
        // Something dialable-looking that is not a number is refused, not a name search.
        assertThatThrownBy(() -> parse("0000000"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("invalid_msisdn");
    }

    @Test
    @DisplayName("Anything else is part of a name, lower-cased and with LIKE wildcards neutralised")
    void name() {
        assertThat(parse("Tariro")).isEqualTo(new Search(SearchKind.NAME, "tariro"));
        assertThat(parse("50%_off!")).isEqualTo(new Search(SearchKind.NAME, "50!%!_off!!"));
        assertThatThrownBy(() -> parse("T"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("invalid_search");
    }

    @Test
    @DisplayName("Blank means no search; an essay is refused")
    void blankAndTooLong() {
        assertThat(parse(null)).isNull();
        assertThat(parse("   ")).isNull();
        assertThatThrownBy(() -> parse("x".repeat(81)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("invalid_search");
    }

    // ------------------------------------------------------------------
    // The Specification (V21): who the delivery recipient is stays with the
    // DELIVERY parcel. On a mixed order the collecting seller's card never
    // shows the destination, so their search must not test a phone or a name
    // against it either (design C7).
    // ------------------------------------------------------------------

    /** A mocked Criteria world: the parcel root, and two order subqueries in
     *  the order the Specification asks for them. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final class Criteria {
        final Root<OrderFulfilment> root = mock(Root.class);
        final CriteriaQuery<?> query = mock(CriteriaQuery.class);
        final CriteriaBuilder cb = mock(CriteriaBuilder.class);
        final Subquery<UUID> anyParcel = mock(Subquery.class, Mockito.RETURNS_SELF);
        final Subquery<UUID> deliveryOnly = mock(Subquery.class, Mockito.RETURNS_SELF);
        final Root<MarketOrder> orderA = mock(Root.class);
        final Root<MarketOrder> orderB = mock(Root.class);
        final Path orderId = mock(Path.class);
        final Path parcelMethod = mock(Path.class);
        final Predicate inA = mock(Predicate.class);
        final Predicate inB = mock(Predicate.class);
        final Predicate isDelivery = mock(Predicate.class);

        Criteria() {
            when(query.subquery(UUID.class)).thenReturn(anyParcel, deliveryOnly);
            when(anyParcel.from(MarketOrder.class)).thenReturn(orderA);
            when(deliveryOnly.from(MarketOrder.class)).thenReturn(orderB);
            when(orderA.get(anyString())).thenAnswer(inv -> mock(Path.class));
            when(orderB.get(anyString())).thenAnswer(inv -> mock(Path.class));
            when(root.get("orderId")).thenReturn(orderId);
            when(root.get("deliveryMethod")).thenReturn(parcelMethod);
            // The varargs overload the production code calls with one subquery.
            when(orderId.in(new Expression[] {anyParcel})).thenReturn(inA);
            when(orderId.in(new Expression[] {deliveryOnly})).thenReturn(inB);
            when(cb.equal(parcelMethod, DeliveryMethod.DELIVERY)).thenReturn(isDelivery);
        }

        Predicate build(DeliveryMethod method, Search search) {
            return SellerParcelQueryService.specification(null, null, method, search)
                    .toPredicate(root, (CriteriaQuery) query, cb);
        }
    }

    @Test
    @DisplayName("C7: a phone matches the buyer or gift recipient on any parcel, the delivery recipient on a DELIVERY parcel only")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deliveryRecipientPhoneMatchesDeliveryParcelsOnly() {
        Criteria c = new Criteria();
        Path buyer = mock(Path.class);
        Path giftRecipient = mock(Path.class);
        Path deliveryRecipient = mock(Path.class);
        when(c.orderA.get("buyerMsisdn")).thenReturn(buyer);
        when(c.orderA.get("recipientMsisdn")).thenReturn(giftRecipient);
        when(c.orderB.get("deliveryRecipientMsisdn")).thenReturn(deliveryRecipient);
        Predicate buyerIs = mock(Predicate.class);
        Predicate giftIs = mock(Predicate.class);
        Predicate deliveryRecipientIs = mock(Predicate.class);
        Predicate eitherParty = mock(Predicate.class);
        Predicate deliveryArm = mock(Predicate.class);
        when(c.cb.equal(buyer, "+263771234567")).thenReturn(buyerIs);
        when(c.cb.equal(giftRecipient, "+263771234567")).thenReturn(giftIs);
        when(c.cb.equal(deliveryRecipient, "+263771234567")).thenReturn(deliveryRecipientIs);
        when(c.cb.or(buyerIs, giftIs)).thenReturn(eitherParty);
        when(c.cb.and(c.isDelivery, c.inB)).thenReturn(deliveryArm);

        c.build(null, new Search(SearchKind.PHONE, "+263771234567"));

        // The delivery recipient's number is tested only inside the second
        // subquery, and that subquery is only ever reached ANDed with "this
        // parcel is a DELIVERY".
        verify(c.deliveryOnly).where((Expression<Boolean>) deliveryRecipientIs);
        verify(c.cb).and(c.isDelivery, c.inB);
        verify(c.cb).or(c.inA, deliveryArm);
        verify(c.anyParcel).where((Expression<Boolean>) eitherParty);
        // ...and never on the any-parcel side.
        verify(c.orderA, never()).get("deliveryRecipientMsisdn");
    }

    @Test
    @DisplayName("C7: a name matches the named collector on any parcel, the delivery recipient on a DELIVERY parcel only")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deliveryRecipientNameMatchesDeliveryParcelsOnly() {
        Criteria c = new Criteria();
        Path collector = mock(Path.class);
        Path deliveryRecipient = mock(Path.class);
        when(c.orderA.get("recipientName")).thenReturn(collector);
        when(c.orderB.get("deliveryRecipientName")).thenReturn(deliveryRecipient);
        Expression lowerCollector = mock(Expression.class);
        Expression lowerDeliveryRecipient = mock(Expression.class);
        when(c.cb.lower(collector)).thenReturn(lowerCollector);
        when(c.cb.lower(deliveryRecipient)).thenReturn(lowerDeliveryRecipient);
        Predicate collectorLike = mock(Predicate.class);
        Predicate deliveryRecipientLike = mock(Predicate.class);
        when(c.cb.like(lowerCollector, "%tariro%", '!')).thenReturn(collectorLike);
        when(c.cb.like(lowerDeliveryRecipient, "%tariro%", '!')).thenReturn(deliveryRecipientLike);
        Predicate deliveryArm = mock(Predicate.class);
        when(c.cb.and(c.isDelivery, c.inB)).thenReturn(deliveryArm);

        c.build(null, new Search(SearchKind.NAME, "tariro"));

        verify(c.anyParcel).where((Expression<Boolean>) collectorLike);
        verify(c.deliveryOnly).where((Expression<Boolean>) deliveryRecipientLike);
        verify(c.cb).or(c.inA, deliveryArm);
        verify(c.orderA, never()).get("deliveryRecipientName");
    }

    @Test
    @DisplayName("?deliveryMethod= filters on the PARCEL's own method, with no order subquery at all")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void methodFilterIsOnTheParcel() {
        Criteria c = new Criteria();
        Predicate collected = mock(Predicate.class);
        when(c.cb.equal(c.parcelMethod, DeliveryMethod.COLLECTION)).thenReturn(collected);

        c.build(DeliveryMethod.COLLECTION, null);

        verify(c.cb).equal(c.parcelMethod, DeliveryMethod.COLLECTION);
        verify(c.query, never()).subquery(any(Class.class));
        verify(c.cb, never()).like(any(Expression.class), anyString(), anyChar());
        verify(c.cb, never()).equal(any(Expression.class), eq(DeliveryMethod.DELIVERY));
    }
}
