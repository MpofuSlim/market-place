package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Sort;

import java.util.Locale;

/**
 * The orderings the public catalogue offers.
 *
 * <p><b>Every one ends with the same total-order tiebreaker</b>
 * ({@code createdAt DESC, id}). Without it a sort on a non-unique column is
 * only a PARTIAL order, and Postgres is free to return tied rows in a
 * different sequence per query — so paging through a catalogue where many
 * items share a price silently repeats some listings and skips others. The
 * bug is invisible on page 1 and reads as "the catalogue is broken" rather
 * than "the sort was ambiguous". {@code id} last makes the order total, since
 * two listings can share both a price and a creation instant.
 */
@Schema(name = "ListingSort", description = "Ordering for the catalogue browse.")
public enum ListingSort {

    /** Default, and the historical behaviour. */
    NEWEST("newest", Sort.by(Sort.Direction.DESC, "createdAt")),
    PRICE_ASC("price_asc", Sort.by(Sort.Direction.ASC, "priceCents")),
    PRICE_DESC("price_desc", Sort.by(Sort.Direction.DESC, "priceCents"));

    private static final Sort TIEBREAK =
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    private final String wireName;
    private final Sort primary;

    ListingSort(String wireName, Sort primary) {
        this.wireName = wireName;
        this.primary = primary;
    }

    public String wireName() {
        return wireName;
    }

    /** The primary ordering, then the total-order tiebreaker. */
    public Sort sort() {
        return primary.and(TIEBREAK);
    }

    /** Comma-joined wire names, for error messages and Swagger. */
    public static String wireNames() {
        StringBuilder sb = new StringBuilder();
        for (ListingSort value : values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(value.wireName);
        }
        return sb.toString();
    }

    /**
     * Case-insensitive parse; blank/absent is {@link #NEWEST}. An unrecognised
     * value is a clean 400 rather than a silent fallback to the default —
     * falling back would return a confidently wrong ORDER for a client that
     * believes it asked for cheapest-first, which is exactly the failure the
     * unknown-parameter refusal exists to prevent.
     */
    public static ListingSort parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NEWEST;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (ListingSort candidate : values()) {
            if (candidate.wireName.equals(value)) {
                return candidate;
            }
        }
        throw ApiException.badRequest("invalid_sort", "sort must be one of " + wireNames());
    }
}
