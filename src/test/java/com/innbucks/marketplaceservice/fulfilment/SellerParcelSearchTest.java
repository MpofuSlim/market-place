package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.fulfilment.SellerParcelQueryService.Search;
import com.innbucks.marketplaceservice.fulfilment.SellerParcelQueryService.SearchKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
