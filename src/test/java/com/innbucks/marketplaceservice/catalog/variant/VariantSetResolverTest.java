package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.dto.VariantRequest;
import com.innbucks.marketplaceservice.catalog.variant.VariantSetResolver.Draft;
import com.innbucks.marketplaceservice.catalog.variant.VariantSetResolver.VariantPlan;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V19 editor rules, decided BEFORE anything is written: option names and
 * values, how an entry finds the existing option it keeps, the stock bounds
 * and the floor rule that keeps {@code listing.price_cents} the lowest option
 * price. Every refusal is a 400 whose message names the field the seller got
 * wrong. The canonical data is the Swagger "Cotton Crew Tee".
 */
class VariantSetResolverTest {

    private static final UUID LISTING = UUID.fromString("e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41");
    private static final UUID M_BLACK = UUID.fromString("0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352");
    private static final UUID L_BLACK = UUID.fromString("1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463");
    private static final UUID XL_BLACK = UUID.fromString("2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574");
    private static final Instant CREATED = Instant.parse("2026-09-20T08:00:00Z");

    private static final long PRICE = 1999;
    private static final int MAX_VARIANTS = 50;
    private static final List<String> SIZE_COLOUR = List.of("Size", "Colour");

    // ---- fixtures ---------------------------------------------------------

    private static VariantRequest entry(String size, String colour, Long priceCents, Integer stockQty) {
        return new VariantRequest(null, List.of(size, colour), priceCents, stockQty);
    }

    private static VariantRequest entry(UUID id, String size, String colour, Long priceCents,
                                        Integer stockQty) {
        return new VariantRequest(id, List.of(size, colour), priceCents, stockQty);
    }

    private static ListingVariant row(UUID id, String value1, String value2, Long priceCents,
                                      int stockQty, int position) {
        ListingVariant row = ListingVariant.builder()
                .id(id)
                .listingId(LISTING)
                .priceCents(priceCents)
                .stockQty(stockQty)
                .position(position)
                .createdAt(CREATED)
                .updatedAt(CREATED)
                .version(0L)
                .build();
        row.setValues(value1, value2);
        return row;
    }

    /** M/Black (4), L/Black (0), XL/Black at 22.99 (6) - the Swagger listing. */
    private static List<ListingVariant> cottonCrewTee() {
        return List.of(
                row(M_BLACK, "M", "Black", null, 4, 0),
                row(L_BLACK, "L", "Black", null, 0, 1),
                row(XL_BLACK, "XL", "Black", 2299L, 6, 2));
    }

    private static VariantPlan create(List<String> options, List<VariantRequest> variants) {
        return VariantSetResolver.resolve(options, variants, PRICE, List.of(), MAX_VARIANTS);
    }

    private static VariantPlan replace(List<VariantRequest> variants, List<ListingVariant> existing) {
        return VariantSetResolver.resolve(SIZE_COLOUR, variants, PRICE, existing, MAX_VARIANTS);
    }

    private static void refused(ThrowingCallable call, String code, String message) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo(code);
                    assertThat(api.getMessage()).isEqualTo(message);
                });
    }

    // ---- the canonical create --------------------------------------------

    @Test
    @DisplayName("A create drafts every option as new, in the seller's order, with the listing total as their sum")
    void createsTheCanonicalPlan() {
        VariantPlan plan = create(SIZE_COLOUR, List.of(
                entry("M", "Black", null, 4),
                entry("L", "Black", null, 0),
                entry("XL", "Black", 2299L, 6)));

        assertThat(plan.optionNames()).containsExactly("Size", "Colour");
        assertThat(plan.drafts()).allSatisfy(d -> assertThat(d.isNew()).isTrue());
        assertThat(plan.drafts()).extracting(Draft::value1).containsExactly("M", "L", "XL");
        assertThat(plan.drafts()).extracting(Draft::value2).containsExactly("Black", "Black", "Black");
        assertThat(plan.drafts()).extracting(Draft::override).containsExactly(null, null, 2299L);
        assertThat(plan.drafts()).extracting(Draft::stockQty).containsExactly(4, 0, 6);
        assertThat(plan.drafts()).extracting(Draft::position).containsExactly(0, 1, 2);
        assertThat(plan.totalStock()).isEqualTo(10);
        assertThat(plan.removed()).isEmpty();
    }

    @Test
    @DisplayName("A one-axis listing takes one value per option and has no second value")
    void oneAxis() {
        VariantPlan plan = create(List.of("Size"), List.of(
                new VariantRequest(null, List.of("M"), null, 3),
                new VariantRequest(null, List.of("L"), null, 2)));

        assertThat(plan.optionNames()).containsExactly("Size");
        assertThat(plan.drafts()).extracting(Draft::value1).containsExactly("M", "L");
        assertThat(plan.drafts()).extracting(Draft::value2).containsExactly(null, null);
        assertThat(plan.totalStock()).isEqualTo(5);
    }

    // ---- option names -----------------------------------------------------

    @Test
    @DisplayName("Variants without options are refused - a value means nothing without the axis it belongs to")
    void variantsNeedOptions() {
        List<VariantRequest> variants = List.of(entry("M", "Black", null, 4));

        refused(() -> create(null, variants), "variant_options_required",
                "variants need options - name each option, for example Size");
        refused(() -> create(List.of(), variants), "variant_options_required",
                "variants need options - name each option, for example Size");
    }

    @Test
    @DisplayName("A listing names at most two options")
    void atMostTwoOptions() {
        refused(() -> create(List.of("Size", "Colour", "Sleeve"),
                        List.of(new VariantRequest(null, List.of("M", "Black"), null, 1))),
                "too_many_variant_options", "A listing can have at most 2 options");
    }

    @Test
    @DisplayName("Option names are unique ignoring case and surrounding whitespace")
    void duplicateOptionNames() {
        refused(() -> create(List.of("Size", "  SIZE "), List.of(entry("M", "L", null, 1))),
                "duplicate_variant_option", "options names SIZE more than once");
    }

    @Test
    @DisplayName("Option names have whitespace collapsed and trimmed before they are stored")
    void optionNamesAreNormalised() {
        VariantPlan plan = create(List.of("  Shoe \t  Size ", "Colour"),
                List.of(entry("42", "Brown", null, 1)));

        assertThat(plan.optionNames()).containsExactly("Shoe Size", "Colour");
    }

    @Test
    @DisplayName("A blank, comma-bearing or over-long option name is refused naming options[i]")
    void badOptionNames() {
        List<VariantRequest> variants = List.of(entry("M", "Black", null, 1));

        refused(() -> create(List.of("Size", "   "), variants),
                "invalid_variant_option", "options[1] must not be blank");
        refused(() -> create(Arrays.asList("Size", null), variants),
                "invalid_variant_option", "options[1] must not be blank");
        refused(() -> create(List.of("Size, UK", "Colour"), variants),
                "invalid_variant_option", "options[0] must not contain a comma");
        refused(() -> create(List.of("S".repeat(31), "Colour"), variants),
                "invalid_variant_option", "options[0] must be at most 30 characters");
    }

    // ---- option values ----------------------------------------------------

    @Test
    @DisplayName("A blank, comma-bearing or over-long value is refused naming variants[i].values[j]")
    void badValues() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 1),
                        entry("L", "  ", null, 1))),
                "invalid_variant_option", "variants[1].values[1] must not be blank");
        refused(() -> create(SIZE_COLOUR, List.of(entry("M, L", "Black", null, 1))),
                "invalid_variant_option", "variants[0].values[0] must not contain a comma");
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 1),
                        entry("L", "Black", null, 1),
                        entry("XL", "B".repeat(41), null, 1))),
                "invalid_variant_option", "variants[2].values[1] must be at most 40 characters");
    }

    @Test
    @DisplayName("Values keep '/' and have whitespace collapsed and trimmed")
    void valuesAreNormalisedAndSlashIsAllowed() {
        VariantPlan plan = create(SIZE_COLOUR, List.of(
                entry("  XL  ", "Navy/White", null, 1),
                entry("6/128GB", "Navy \t  Blue", null, 1)));

        assertThat(plan.drafts()).extracting(Draft::value1).containsExactly("XL", "6/128GB");
        assertThat(plan.drafts()).extracting(Draft::value2).containsExactly("Navy/White", "Navy Blue");
    }

    @Test
    @DisplayName("Each entry gives exactly one value per option, else variant_values_mismatch naming the options")
    void valuesMustMatchTheOptions() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 1),
                        entry("L", "Black", null, 1),
                        new VariantRequest(null, List.of("XL"), null, 1))),
                "variant_values_mismatch",
                "variants[2].values must give one value for each of Size, Colour");
        refused(() -> create(List.of("Size"), List.of(
                        new VariantRequest(null, List.of("M", "Black"), null, 1))),
                "variant_values_mismatch", "variants[0].values must give one value for each of Size");
        refused(() -> create(SIZE_COLOUR, List.of(new VariantRequest(null, null, null, 1))),
                "variant_values_mismatch",
                "variants[0].values must give one value for each of Size, Colour");
    }

    @Test
    @DisplayName("A null entry in the list is a 400 naming it, never an NPE rendered as a 500")
    void nullEntryIsRefused() {
        refused(() -> create(SIZE_COLOUR, Arrays.asList(entry("M", "Black", null, 1), null)),
                "invalid_variant_option", "variants[1] must not be empty");
    }

    @Test
    @DisplayName("The same combination twice is refused ignoring case and whitespace, named as the seller typed it")
    void duplicateCombinations() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 1),
                        entry("m", "BLACK", null, 1))),
                "duplicate_variant", "variants names m - BLACK more than once");
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 1),
                        entry(" M ", "Black  ", null, 1))),
                "duplicate_variant", "variants names M - Black more than once");
    }

    @Test
    @DisplayName("Sharing one value across different combinations is not a duplicate")
    void differentCombinationsAreFine() {
        VariantPlan plan = create(SIZE_COLOUR, List.of(
                entry("M", "Black", null, 1),
                entry("M", "White", null, 1),
                entry("L", "Black", null, 1)));

        assertThat(plan.drafts()).hasSize(3);
    }

    @Test
    @DisplayName("A listing owns at most the configured number of options")
    void tooManyVariants() {
        List<VariantRequest> four = List.of(
                entry("S", "Black", null, 1), entry("M", "Black", null, 1),
                entry("L", "Black", null, 1), entry("XL", "Black", null, 1));
        refused(() -> VariantSetResolver.resolve(SIZE_COLOUR, four, PRICE, List.of(), 3),
                "too_many_variants", "A listing can have at most 3 variants");
        assertThat(VariantSetResolver.resolve(SIZE_COLOUR, four.subList(0, 3), PRICE, List.of(), 3)
                .drafts()).hasSize(3);

        List<VariantRequest> fiftyOne = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            fiftyOne.add(entry("EU " + i, "Black", null, 1));
        }
        refused(() -> create(SIZE_COLOUR, fiftyOne),
                "too_many_variants", "A listing can have at most 50 variants");
        assertThat(create(SIZE_COLOUR, fiftyOne.subList(0, 50)).drafts()).hasSize(50);
    }

    // ---- stock ------------------------------------------------------------

    @Test
    @DisplayName("Each option's stock is 0..1000000, refused naming the entry")
    void stockOutOfRangePerEntry() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 1),
                        entry("L", "Black", null, -1))),
                "stock_out_of_range", "variants[1].stockQty must be between 0 and 1000000");
        refused(() -> create(SIZE_COLOUR, List.of(entry("M", "Black", null, 1_000_001))),
                "stock_out_of_range", "variants[0].stockQty must be between 0 and 1000000");
    }

    @Test
    @DisplayName("The options' stock together may not exceed 1000000 - exactly the limit is accepted")
    void totalStockBound() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 600_000),
                        entry("L", "Black", null, 400_001))),
                "stock_out_of_range", "The options' stock adds up to more than 1000000");

        assertThat(create(SIZE_COLOUR, List.of(
                entry("M", "Black", null, 600_000),
                entry("L", "Black", null, 400_000))).totalStock()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("The total bound counts a kept option's CURRENT stock when its entry omits stockQty")
    void totalStockBoundUsesKeptStock() {
        List<ListingVariant> existing = List.of(row(M_BLACK, "M", "Black", null, 700_000, 0));

        refused(() -> replace(List.of(
                        entry(M_BLACK, "M", "Black", null, null),
                        entry("L", "Black", null, 300_001)), existing),
                "stock_out_of_range", "The options' stock adds up to more than 1000000");

        VariantPlan atTheLimit = replace(List.of(
                entry(M_BLACK, "M", "Black", null, null),
                entry("L", "Black", null, 300_000)), existing);
        assertThat(atTheLimit.totalStock()).isEqualTo(1_000_000);

        // A kept option that SENDS stock is counted at what it sends.
        VariantPlan reset = replace(List.of(
                entry(M_BLACK, "M", "Black", null, 1),
                entry("L", "Black", null, 999_999)), existing);
        assertThat(reset.totalStock()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("A kept option may omit stockQty to keep its stock; a given stockQty is an absolute set")
    void omittedStockKeepsOnAMatchedEntry() {
        VariantPlan plan = replace(List.of(
                entry(M_BLACK, "M", "Black", null, null),
                entry("L", "Black", null, null),
                entry("XL", "Black", 2299L, 9)), cottonCrewTee());

        assertThat(plan.drafts()).extracting(Draft::stockQty).containsExactly(null, null, 9);
        assertThat(plan.drafts()).extracting(Draft::effectiveStock).containsExactly(4, 0, 9);
        assertThat(plan.totalStock()).isEqualTo(13);
    }

    @Test
    @DisplayName("A NEW option must say how many are in stock")
    void newOptionNeedsStock() {
        refused(() -> replace(List.of(
                        entry(M_BLACK, "M", "Black", null, null),
                        entry("L", "Black", null, null),
                        entry("S", "Black", null, null)), cottonCrewTee()),
                "variant_stock_required", "variants[2].stockQty is required for a new option");
        refused(() -> create(SIZE_COLOUR, List.of(entry("M", "Black", null, null))),
                "variant_stock_required", "variants[0].stockQty is required for a new option");
    }

    // ---- identity on replace ----------------------------------------------

    @Test
    @DisplayName("An entry with an id keeps that option even when its values change (a typo fix), and nothing is written")
    void identityById() {
        List<ListingVariant> existing = cottonCrewTee();
        VariantPlan plan = replace(List.of(
                entry(M_BLACK, "M", "Black", null, null),
                entry(L_BLACK, "Large", "Black", null, null),
                entry(XL_BLACK, "XL", "Black", 2299L, null)), existing);

        Draft renamed = plan.drafts().get(1);
        assertThat(renamed.existing()).isSameAs(existing.get(1));
        assertThat(renamed.isNew()).isFalse();
        assertThat(renamed.value1()).isEqualTo("Large");
        assertThat(plan.removed()).isEmpty();
        // Pure: the plan describes the write; the existing row is untouched.
        assertThat(existing.get(1).getOption1Value()).isEqualTo("L");
        assertThat(existing.get(1).getOptionKey()).isEqualTo("l,black");
    }

    @Test
    @DisplayName("An entry without an id keeps the unclaimed option with the same values, ignoring case")
    void identityByValues() {
        List<ListingVariant> existing = cottonCrewTee();
        VariantPlan plan = replace(List.of(
                entry("m", "BLACK", null, null),
                entry("  L ", "black", null, 7),
                entry("XL", "Black", 2299L, null)), existing);

        assertThat(plan.drafts()).extracting(Draft::existing)
                .containsExactly(existing.get(0), existing.get(1), existing.get(2));
        assertThat(plan.drafts().get(0).value1()).isEqualTo("m");
        assertThat(plan.drafts().get(0).value2()).isEqualTo("BLACK");
        assertThat(plan.drafts()).extracting(Draft::effectiveStock).containsExactly(4, 7, 6);
        assertThat(plan.removed()).isEmpty();
    }

    @Test
    @DisplayName("An entry matching nothing is a NEW option")
    void identityNew() {
        VariantPlan plan = replace(List.of(
                entry(M_BLACK, "M", "Black", null, null),
                entry("S", "Black", null, 3)), cottonCrewTee());

        Draft s = plan.drafts().get(1);
        assertThat(s.isNew()).isTrue();
        assertThat(s.existing()).isNull();
        assertThat(s.stockQty()).isEqualTo(3);
    }

    @Test
    @DisplayName("Ids claim first: an option kept by id is not matched again by another entry's values")
    void idsClaimBeforeValues() {
        List<ListingVariant> existing = cottonCrewTee();
        VariantPlan plan = replace(List.of(
                entry("M", "Black", null, 2),
                entry(M_BLACK, "Medium", "Black", null, null)), existing);

        assertThat(plan.drafts().get(0).isNew())
                .as("M's row is claimed by entry 1's id, so entry 0 is a new option").isTrue();
        assertThat(plan.drafts().get(1).existing()).isSameAs(existing.get(0));
        assertThat(plan.removed()).containsExactly(existing.get(1), existing.get(2));
    }

    @Test
    @DisplayName("An id that is not one of THIS listing's options is refused naming the entry")
    void unknownVariant() {
        UUID foreign = UUID.fromString("9f1d3e5a-0b2c-4d6e-8f7a-1b3c5d7e9f02");

        refused(() -> replace(List.of(
                        entry(M_BLACK, "M", "Black", null, null),
                        entry(foreign, "L", "Black", null, 1)), cottonCrewTee()),
                "unknown_variant", "variants[1].id is not a variant of this listing");
        refused(() -> create(SIZE_COLOUR, List.of(entry(M_BLACK, "M", "Black", null, 1))),
                "unknown_variant", "variants[0].id is not a variant of this listing");
    }

    @Test
    @DisplayName("One id claimed by two entries is refused - one option cannot become two")
    void oneIdClaimedTwice() {
        refused(() -> replace(List.of(
                        entry(M_BLACK, "M", "Black", null, null),
                        entry(M_BLACK, "L", "Black", null, null)), cottonCrewTee()),
                "duplicate_variant", "variants[1].id names an option already kept by another entry");
    }

    @Test
    @DisplayName("Existing options no entry keeps are removed, in their stored order")
    void unclaimedRowsAreRemoved() {
        List<ListingVariant> existing = cottonCrewTee();
        VariantPlan plan = replace(List.of(
                entry(XL_BLACK, "XL", "Black", 2299L, null),
                entry("S", "Black", null, 5)), existing);

        assertThat(plan.removed()).containsExactly(existing.get(0), existing.get(1));
        assertThat(plan.drafts()).extracting(Draft::existing).containsExactly(existing.get(2), null);
    }

    @Test
    @DisplayName("Positions follow the request order, so a reorder is just a resend in the new order")
    void positionsFollowRequestOrder() {
        List<ListingVariant> existing = cottonCrewTee();
        VariantPlan plan = replace(List.of(
                entry("XL", "Black", 2299L, null),
                entry("S", "Black", null, 1),
                entry("M", "Black", null, null),
                entry("L", "Black", null, null)), existing);

        assertThat(plan.drafts()).extracting(Draft::position).containsExactly(0, 1, 2, 3);
        assertThat(plan.drafts()).extracting(Draft::value1).containsExactly("XL", "S", "M", "L");
        assertThat(plan.drafts()).extracting(Draft::existing)
                .containsExactly(existing.get(2), null, existing.get(0), existing.get(1));
    }

    // ---- the floor rule ---------------------------------------------------

    @Test
    @DisplayName("An own price EQUAL to the listing price is stored as no own price, so it follows the listing price")
    void overrideEqualToThePriceIsNormalised() {
        VariantPlan plan = create(SIZE_COLOUR, List.of(
                entry("M", "Black", 1999L, 4),
                entry("XL", "Black", 2299L, 6)));

        assertThat(plan.drafts()).extracting(Draft::override).containsExactly(null, 2299L);
    }

    @Test
    @DisplayName("A kept option whose entry omits priceCents sells at the listing price - price is replaced, not kept")
    void omittedPriceInherits() {
        VariantPlan plan = replace(List.of(
                entry(M_BLACK, "M", "Black", null, null),
                entry(XL_BLACK, "XL", "Black", null, null)), cottonCrewTee());

        assertThat(plan.drafts().get(1).existing().getPriceCents()).isEqualTo(2299L);
        assertThat(plan.drafts().get(1).override()).isNull();
    }

    @Test
    @DisplayName("An own price BELOW the listing price is refused naming the entry and the option")
    void overrideBelowThePrice() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("S", "Black", 1500L, 2),
                        entry("M", "Black", null, 4))),
                "variant_price_below_listing_price",
                "variants[0] (S - Black) costs less than priceCents - make priceCents the lowest "
                        + "option price");
    }

    @Test
    @DisplayName("At least one option must sell at the listing price, else listing_price_not_offered")
    void listingPriceMustBeOffered() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", 2099L, 4),
                        entry("XL", "Black", 2299L, 6))),
                "listing_price_not_offered",
                "No option sells at priceCents - make priceCents the lowest option price");

        // Every option sent AT the listing price is normalised, so it IS offered.
        VariantPlan allAtThePrice = create(SIZE_COLOUR, List.of(
                entry("M", "Black", 1999L, 4),
                entry("L", "Black", 1999L, 0)));
        assertThat(allAtThePrice.drafts()).extracting(Draft::override).containsExactly(null, null);
    }

    @Test
    @DisplayName("An own price outside 1..100000000 is price_out_of_range naming the entry, checked before the floor")
    void overrideOutOfRange() {
        refused(() -> create(SIZE_COLOUR, List.of(
                        entry("M", "Black", null, 4),
                        entry("L", "Black", null, 0),
                        entry("XL", "Black", 0L, 6))),
                "price_out_of_range", "variants[2].priceCents must be between 1 and 100000000");
        refused(() -> create(SIZE_COLOUR, List.of(entry("M", "Black", 100_000_001L, 4))),
                "price_out_of_range", "variants[0].priceCents must be between 1 and 100000000");
    }

    // ---- revalidateKept (variants: null) ----------------------------------

    @Test
    @DisplayName("Raising priceCents above a kept option's own price is refused naming that option")
    void revalidateRefusesARaiseAboveAnOverride() {
        refused(() -> VariantSetResolver.revalidateKept(cottonCrewTee(), 2500),
                "variant_price_below_listing_price",
                "The option XL - Black costs less than priceCents - make priceCents the lowest "
                        + "option price");
    }

    @Test
    @DisplayName("Lowering priceCents keeps every own price (they are still surcharges) and clears nothing")
    void revalidateAcceptsALowerPrice() {
        List<ListingVariant> existing = cottonCrewTee();

        assertThat(VariantSetResolver.revalidateKept(existing, 1499)).isEmpty();
        assertThat(existing.get(2).getPriceCents()).isEqualTo(2299L);
    }

    @Test
    @DisplayName("Raising priceCents to EXACTLY an own price returns that option to be cleared, without writing it")
    void revalidateNormalisesAnEqualOverride() {
        List<ListingVariant> existing = cottonCrewTee();

        assertThat(VariantSetResolver.revalidateKept(existing, 2299))
                .containsExactly(existing.get(2));
        assertThat(existing.get(2).getPriceCents())
                .as("the caller clears it; the resolver is pure").isEqualTo(2299L);
    }

    @Test
    @DisplayName("A kept set with no option at the listing price is still refused, and no options means nothing to check")
    void revalidateStillRequiresTheListingPrice() {
        List<ListingVariant> allSurcharged = List.of(
                row(M_BLACK, "M", "Black", 2099L, 4, 0),
                row(XL_BLACK, "XL", "Black", 2299L, 6, 1));

        refused(() -> VariantSetResolver.revalidateKept(allSurcharged, PRICE),
                "listing_price_not_offered",
                "No option sells at priceCents - make priceCents the lowest option price");
        assertThat(VariantSetResolver.revalidateKept(List.of(), PRICE)).isEmpty();
    }
}
