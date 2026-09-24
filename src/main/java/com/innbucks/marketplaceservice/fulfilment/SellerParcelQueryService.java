package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentPageResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingCodes;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The seller's parcel queue: filtered, searchable, oldest first.
 *
 * <p><b>One search box, four kinds of answer.</b> The seller at a counter has
 * whatever the buyer standing there can give them — the order reference off
 * their screen, the tracking code, their phone, or a name — so {@code q} is
 * read by SHAPE: {@code MKT-…} is an order reference, {@code TRK-…} a tracking
 * code, something dialable a phone number (normalised exactly as every payer's
 * number is), anything else part of a name. A phone that cannot be a number
 * is a 400, never silently a name search that finds nothing.
 *
 * <p><b>A name matches who the parcel is FOR</b>: the delivery recipient, or
 * whoever the buyer named to collect it. The platform keeps no name for a
 * buyer collecting for themselves (their account lives elsewhere), so for
 * them the phone or the reference is the search.
 *
 * <p><b>Built as appended Criteria predicates, never a nullable bind</b> — the
 * catalogue browse's rule, for the catalogue browse's reason (Postgres infers
 * {@code bytea} for an untyped null and dies inside {@code lower()}). An
 * absent filter contributes no predicate at all. Ordering ends on {@code id}
 * so paging is a total order.
 */
@Service
@RequiredArgsConstructor
public class SellerParcelQueryService {

    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_SEARCH_LENGTH = 80;
    private static final Pattern PHONE_SHAPE = Pattern.compile("^[+0-9 ()\\-]{7,20}$");

    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MerchantParcelViewAssembler parcelViews;
    private final Msisdns msisdns;

    /** Every filter optional; {@code merchantId} is honoured for SUPER_ADMIN only. */
    public record ParcelQuery(FulfilmentStatus status, DeliveryMethod deliveryMethod, String q,
                              UUID merchantId, int page, int size) {
    }

    @Transactional(readOnly = true)
    public MerchantFulfilmentPageResponse queue(AuthenticatedUser caller, ParcelQuery query) {
        UUID scope = caller.isSuperAdmin() ? query.merchantId() : requireMerchantId(caller);
        Specification<OrderFulfilment> spec = specification(scope, query.status(),
                query.deliveryMethod(), Search.parse(query.q(), msisdns));
        Pageable pageable = PageRequest.of(Math.max(query.page(), 0),
                Math.clamp(query.size(), 1, MAX_PAGE_SIZE),
                // FIFO — the order that has waited longest never starves —
                // and id last so equal timestamps still page deterministically.
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        Page<OrderFulfilment> result = fulfilmentRepository.findAll(spec, pageable);
        // One batch for the whole page, never a query per card.
        List<MerchantFulfilmentResponse> cards = parcelViews.toViews(result.getContent());
        return MerchantFulfilmentPageResponse.from(
                new PageImpl<>(cards, pageable, result.getTotalElements()));
    }

    static Specification<OrderFulfilment> specification(UUID merchantId, FulfilmentStatus status,
                                                        DeliveryMethod method, Search search) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (merchantId != null) {
                where.add(cb.equal(root.get("merchantId"), merchantId));
            }
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (search != null && search.kind() == SearchKind.TRACKING_CODE) {
                where.add(cb.equal(root.get("trackingCode"), search.value()));
            }
            // Everything that lives on the ORDER rides one subquery.
            List<Predicate> onOrder = new ArrayList<>();
            Subquery<UUID> orders = query.subquery(UUID.class);
            Root<MarketOrder> order = orders.from(MarketOrder.class);
            if (method != null) {
                onOrder.add(cb.equal(order.get("deliveryMethod"), method));
            }
            if (search != null) {
                switch (search.kind()) {
                    case ORDER_REF -> onOrder.add(cb.equal(order.get("orderRef"), search.value()));
                    case PHONE -> onOrder.add(cb.or(
                            cb.equal(order.get("buyerMsisdn"), search.value()),
                            cb.equal(order.get("deliveryRecipientMsisdn"), search.value()),
                            cb.equal(order.get("recipientMsisdn"), search.value())));
                    case NAME -> {
                        String pattern = "%" + search.value() + "%";
                        onOrder.add(cb.or(
                                cb.like(cb.lower(order.get("deliveryRecipientName")), pattern, '!'),
                                cb.like(cb.lower(order.get("recipientName")), pattern, '!')));
                    }
                    case TRACKING_CODE -> {
                        // Matched on the parcel above.
                    }
                }
            }
            if (!onOrder.isEmpty()) {
                orders.select(order.get("id")).where(onOrder.toArray(Predicate[]::new));
                where.add(root.get("orderId").in(orders));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    enum SearchKind { ORDER_REF, TRACKING_CODE, PHONE, NAME }

    /** What the search box was given, read by shape. */
    record Search(SearchKind kind, String value) {

        static Search parse(String raw, Msisdns msisdns) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String q = raw.trim();
            if (q.length() > MAX_SEARCH_LENGTH) {
                throw ApiException.badRequest("invalid_search",
                        "Search for at most " + MAX_SEARCH_LENGTH + " characters");
            }
            String upper = q.toUpperCase(Locale.ROOT);
            if (upper.startsWith("MKT")) {
                String compact = upper.replace(" ", "").replace("-", "");
                return new Search(SearchKind.ORDER_REF, "MKT-" + compact.substring(3));
            }
            if (upper.startsWith("TRK")) {
                String code = TrackingCodes.normalize(q);
                if (code == null) {
                    throw ApiException.badRequest("invalid_search",
                            "That is not a tracking code - they look like TRK-7F3K9Q2M4X");
                }
                return new Search(SearchKind.TRACKING_CODE, code);
            }
            if (PHONE_SHAPE.matcher(q).matches()) {
                return new Search(SearchKind.PHONE, msisdns.normalize(q, "q"));
            }
            if (q.length() < 2) {
                throw ApiException.badRequest("invalid_search",
                        "Type at least 2 letters of the name");
            }
            return new Search(SearchKind.NAME, escapeLike(q.toLowerCase(Locale.ROOT)));
        }

        private static String escapeLike(String value) {
            return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
    }

    /** Merchant scope comes from the JWT, never from a request parameter. */
    private static UUID requireMerchantId(AuthenticatedUser caller) {
        String claim = caller.merchantId();
        if (claim == null || claim.isBlank()) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
    }
}
