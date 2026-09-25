package com.innbucks.marketplaceservice.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.marketplaceservice.catalog.dto.ListingOptionResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingVariantResponse;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository;
import com.innbucks.marketplaceservice.pickup.CollectionPointViews;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.support.TestTowns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * V19: how {@link ListingViewAssembler} puts a listing's options on the wire.
 * A page loads every option with ONE batch query, asked only for the listings
 * that sell options, so a page of listings without them costs nothing extra;
 * the picker's axes list their values in the seller's order; {@code
 * maxPriceCents} is the dearest option; and an option shows a price of its own
 * only when it has one. The canonical data is the Swagger "Cotton Crew Tee"
 * beside the plain "Wireless Bluetooth Speaker".
 */
class ListingViewAssemblerTest {

    private static final UUID MERCHANT = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID TEE = UUID.fromString("e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41");
    private static final UUID SPEAKER = UUID.fromString("b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93");
    private static final UUID HOODIE = UUID.fromString("5d0c7e21-8a4b-4c3f-9e1d-6b2a7f8c9d10");
    private static final UUID LANTERN = UUID.fromString("9a3e5c71-4f2b-4d8a-b6c1-0e7f2d9a4b58");
    private static final UUID M_BLACK = UUID.fromString("0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352");
    private static final UUID L_BLACK = UUID.fromString("1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463");
    private static final UUID XL_BLACK = UUID.fromString("2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574");
    private static final Instant CREATED = Instant.parse("2026-09-20T08:00:00Z");

    private ListingVariantRepository variantRepository;
    private ListingViewAssembler assembler;

    @BeforeEach
    void setUp() {
        variantRepository = mock(ListingVariantRepository.class);
        assembler = new ListingViewAssembler(mock(ListingImageRepository.class),
                mock(CategoryRepository.class), mock(SellerService.class),
                mock(ListingDeliveryTownRepository.class), TestTowns.zimbabwe(),
                mock(CollectionPointViews.class), variantRepository);
    }

    // ---- fixtures ---------------------------------------------------------

    private static Listing listing(UUID id, String title, long priceCents, int stockQty,
                                   String option1Name, String option2Name) {
        return Listing.builder()
                .id(id).merchantId(MERCHANT).title(title)
                .priceCents(priceCents).currency("USD").stockQty(stockQty)
                .hasVariants(option1Name != null)
                .option1Name(option1Name).option2Name(option2Name)
                .status(ListingStatus.ACTIVE).createdAt(CREATED).updatedAt(CREATED)
                .build();
    }

    /** From 19.99, two axes, a total of 10. */
    private static Listing tee() {
        return listing(TEE, "Cotton Crew Tee", 1999, 10, "Size", "Colour");
    }

    /** One axis, no option priced above the listing. */
    private static Listing hoodie() {
        return listing(HOODIE, "Fleece Hoodie", 3500, 3, "Size", null);
    }

    private static Listing speaker() {
        return listing(SPEAKER, "Wireless Bluetooth Speaker", 4599, 25, null, null);
    }

    private static Listing lantern() {
        return listing(LANTERN, "Solar Lantern 20W", 2599, 0, null, null);
    }

    private static ListingVariant option(UUID listingId, UUID id, String value1, String value2,
                                         Long priceCents, int stockQty, int position) {
        ListingVariant row = ListingVariant.builder()
                .id(id).listingId(listingId).priceCents(priceCents).stockQty(stockQty)
                .position(position).createdAt(CREATED).updatedAt(CREATED).version(0L)
                .build();
        row.setValues(value1, value2);
        return row;
    }

    /** M/Black (4), L/Black (0), XL/Black at 22.99 (6). */
    private static List<ListingVariant> teeOptions() {
        return List.of(
                option(TEE, M_BLACK, "M", "Black", null, 4, 0),
                option(TEE, L_BLACK, "L", "Black", null, 0, 1),
                option(TEE, XL_BLACK, "XL", "Black", 2299L, 6, 2));
    }

    private static List<ListingVariant> hoodieOptions() {
        return List.of(
                option(HOODIE, UUID.fromString("6e1d8f32-9b5c-4d4a-8f2e-7c3b0a9d1e21"),
                        "S", null, null, 2, 0),
                option(HOODIE, UUID.fromString("7f2e9a43-0c6d-4e5b-9a3f-8d4c1b0e2f32"),
                        "M", null, null, 1, 1));
    }

    /** What the one batch query returns: ordered by listing, then position. */
    private void stubPageOptions() {
        List<ListingVariant> rows = new ArrayList<>(hoodieOptions());
        rows.addAll(teeOptions());
        when(variantRepository.findByListingIdInOrderByListingIdAscPositionAsc(any()))
                .thenReturn(rows);
    }

    private static List<String> labels(ListingResponse response) {
        return response.variants().stream().map(ListingVariantResponse::label).toList();
    }

    // ---- one query per page -----------------------------------------------

    @Test
    @DisplayName("A page holding listings with options loads every option in ONE query for the "
            + "whole page, asked only for the listings that sell options")
    @SuppressWarnings("unchecked")
    void aPageWithOptionsCostsOneVariantQuery() {
        stubPageOptions();

        List<ListingResponse> page = assembler.toResponsePage(
                new PageImpl<>(List.of(tee(), speaker(), hoodie()))).getContent();

        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(variantRepository, times(1))
                .findByListingIdInOrderByListingIdAscPositionAsc(asked.capture());
        assertThat(asked.getValue()).containsExactly(TEE, HOODIE);
        // Never a per-listing read on the page path.
        verifyNoMoreInteractions(variantRepository);

        // Each listing gets its own options back, in the page's order.
        assertThat(page).extracting(ListingResponse::id).containsExactly(TEE, SPEAKER, HOODIE);
        assertThat(labels(page.get(0))).containsExactly("M - Black", "L - Black", "XL - Black");
        assertThat(page.get(1).variants()).isEmpty();
        assertThat(labels(page.get(2))).containsExactly("S", "M");
        assertThat(page.get(2).options())
                .containsExactly(new ListingOptionResponse("Size", List.of("S", "M")));
    }

    @Test
    @DisplayName("The cart and quote's batch path costs the same single option query")
    void theBatchPathForHeldRowsCostsOneVariantQuery() {
        stubPageOptions();

        Map<UUID, ListingResponse> byId = assembler.toResponsesById(
                List.of(hoodie(), lantern(), tee()));

        verify(variantRepository, times(1)).findByListingIdInOrderByListingIdAscPositionAsc(any());
        verifyNoMoreInteractions(variantRepository);
        assertThat(labels(byId.get(TEE))).containsExactly("M - Black", "L - Black", "XL - Black");
        assertThat(labels(byId.get(HOODIE))).containsExactly("S", "M");
        assertThat(byId.get(LANTERN).variants()).isEmpty();
    }

    @Test
    @DisplayName("A page of listings without options costs no option query at all, and each "
            + "renders hasVariants false, empty options and variants, and maxPriceCents = priceCents")
    void aPageOfPlainListingsCostsNoVariantQuery() {
        List<ListingResponse> page = assembler.toResponsePage(
                new PageImpl<>(List.of(speaker(), lantern()))).getContent();
        ListingResponse single = assembler.toResponse(speaker());
        Map<UUID, ListingResponse> byId = assembler.toResponsesById(List.of(lantern()));

        verifyNoInteractions(variantRepository);
        List<ListingResponse> all = new ArrayList<>(page);
        all.add(single);
        all.addAll(byId.values());
        for (ListingResponse response : all) {
            assertThat(response.hasVariants()).isFalse();
            // Never null: the deliveryTowns convention.
            assertThat(response.options()).isNotNull().isEmpty();
            assertThat(response.variants()).isNotNull().isEmpty();
            assertThat(response.maxPriceCents()).isEqualTo(response.priceCents());
        }
        assertThat(page.get(0).stockQty()).isEqualTo(25);
        assertThat(page.get(1).stockQty()).isZero();
    }

    // ---- the picker ---------------------------------------------------------

    @Test
    @DisplayName("A single listing with options costs one option read, and each axis lists its "
            + "distinct values in option position order - not alphabetically, never twice")
    void optionValuesFollowPositionOrder() {
        // Position order interleaves both axes; alphabetical order would be
        // L/M/S/XL and Black/Navy.
        when(variantRepository.findByListingIdOrderByPositionAsc(TEE)).thenReturn(List.of(
                option(TEE, UUID.randomUUID(), "S", "Navy", null, 1, 0),
                option(TEE, UUID.randomUUID(), "M", "Navy", null, 1, 1),
                option(TEE, UUID.randomUUID(), "S", "Black", null, 1, 2),
                option(TEE, UUID.randomUUID(), "M", "Black", null, 1, 3),
                option(TEE, UUID.randomUUID(), "XL", "Black", null, 1, 4)));

        ListingResponse response = assembler.toResponse(tee());

        verify(variantRepository, times(1)).findByListingIdOrderByPositionAsc(TEE);
        verifyNoMoreInteractions(variantRepository);
        assertThat(response.options()).containsExactly(
                new ListingOptionResponse("Size", List.of("S", "M", "XL")),
                new ListingOptionResponse("Colour", List.of("Navy", "Black")));
        assertThat(labels(response)).containsExactly(
                "S - Navy", "M - Navy", "S - Black", "M - Black", "XL - Black");
    }

    // ---- prices -----------------------------------------------------------

    @Test
    @DisplayName("maxPriceCents is the dearest option's price, the listing price when no option "
            + "costs more, and priceCents for a listing without options")
    void maxPriceCentsIsTheHighestEffectivePrice() {
        stubPageOptions();

        List<ListingResponse> page = assembler.toResponsePage(
                new PageImpl<>(List.of(tee(), speaker(), hoodie()))).getContent();

        ListingResponse tee = page.get(0);
        assertThat(tee.priceCents()).isEqualTo(1999L); // the "from" price
        assertThat(tee.maxPriceCents()).isEqualTo(2299L);
        assertThat(tee.stockQty()).isEqualTo(10);
        assertThat(page.get(1).maxPriceCents()).isEqualTo(4599L);
        assertThat(page.get(2).maxPriceCents()).isEqualTo(3500L);
    }

    @Test
    @DisplayName("An option carries priceOverrideCents only when it has a price of its own; one "
            + "selling at the listing price has no such key on the wire")
    void priceOverrideCentsIsPresentOnlyForAnOwnPrice() throws Exception {
        when(variantRepository.findByListingIdOrderByPositionAsc(TEE)).thenReturn(teeOptions());

        List<ListingVariantResponse> variants = assembler.toResponse(tee()).variants();

        ListingVariantResponse m = variants.get(0);
        ListingVariantResponse xl = variants.get(2);
        assertThat(m.priceCents()).isEqualTo(1999L);
        assertThat(m.priceOverrideCents()).isNull();
        assertThat(m.stockQty()).isEqualTo(4);
        assertThat(xl.priceCents()).isEqualTo(2299L);
        assertThat(xl.priceOverrideCents()).isEqualTo(2299L);
        assertThat(xl.stockQty()).isEqualTo(6);

        ObjectMapper json = new ObjectMapper();
        assertThat(json.writeValueAsString(m)).doesNotContain("priceOverrideCents");
        assertThat(json.writeValueAsString(xl)).contains("\"priceOverrideCents\":2299");
    }
}
