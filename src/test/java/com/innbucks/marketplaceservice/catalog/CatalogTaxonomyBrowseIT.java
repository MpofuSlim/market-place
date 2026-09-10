package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V4 taxonomy + browse filters against real Postgres and the real SQL the
 * Specification browse renders — the proof that conditionally-built
 * predicates never bind a null (see CatalogService's class comment; the
 * lower(bytea) regression is exactly what these queries would hit if a null
 * bind ever crept back in):
 *
 * <ul>
 *   <li>GET /marketplace/categories — migration-seeded two-level tree,
 *       anonymous, cacheable.</li>
 *   <li>Browse: parent-category expansion (parent code matches children's
 *       listings), condition filter, case-insensitive exact city filter,
 *       unknown-category leniency, invalid-condition 400, and every filter
 *       combined with q.</li>
 *   <li>Writes: unknown categoryCode refused 400; categoryCode normalized;
 *       city/area stored sanitized and returned on the public read.</li>
 * </ul>
 */
class CatalogTaxonomyBrowseIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String merchantToken;
    private UUID merchantId;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
    }

    /** Creates an ACTIVE listing (image uploaded to satisfy the publish gate). */
    private String activeListing(String title, String categoryCode, String condition,
                                 String city) throws Exception {
        return activeListing(title, categoryCode, condition, city, 1500, 5, merchantToken);
    }

    private String activeListing(String title, String categoryCode, String condition, String city,
                                 int priceCents, int stockQty, String token) throws Exception {
        String conditionField = condition == null ? "" : "\"condition\": \"%s\",".formatted(condition);
        String cityField = city == null ? "" : "\"city\": \"%s\",".formatted(city);
        String body = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "categoryCode": "%s",
                                  %s
                                  %s
                                  "priceCents": %d,
                                  "stockQty": %d
                                }""".formatted(title, categoryCode, conditionField, cityField,
                                priceCents, stockQty)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.data.id");
        return activate(id, token);
    }

    private String activate(String id, String token) throws Exception {
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", id)
                        .file(new MockMultipartFile("image", "p.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void categoryTreeIsAnonymousTwoLevelAndCacheable() throws Exception {
        mockMvc.perform(get("/marketplace/categories"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(jsonPath("$.code").value("OK"))
                // All ten top-level categories, each child carrying no grandchildren.
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data[?(@.code == 'electronics')].name").value("Electronics"))
                .andExpect(jsonPath("$.data[?(@.code == 'electronics')].children[?(@.code == 'tv-audio')]")
                        .exists())
                // Display-name order is deterministic: index 6 is "Other",
                // a leaf top-level node (empty children array).
                .andExpect(jsonPath("$.data[6].code").value("other"))
                .andExpect(jsonPath("$.data[6].children").isEmpty());
    }

    @Test
    void browseExpandsAParentCategoryToItsChildren() throws Exception {
        String phone = activeListing("Budget Smartphone", "phones-tablets", null, null);
        String tv = activeListing("Smart TV 43in", "tv-audio", null, null);
        activeListing("Running Shoes", "shoes", null, null);

        // Parent code matches BOTH children's listings, not the shoes.
        mockMvc.perform(get("/marketplace/catalog").param("category", "electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')]".formatted(phone)).exists())
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')]".formatted(tv)).exists());

        // A child code stays narrow.
        mockMvc.perform(get("/marketplace/catalog").param("category", "tv-audio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(tv))
                .andExpect(jsonPath("$.data.items[0].categoryName").value("TV & Audio"));

        // Unknown category code: lenient empty page (public surface), not an error.
        mockMvc.perform(get("/marketplace/catalog").param("category", "no-such-category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));
    }

    @Test
    void browseFiltersByConditionAndCity() throws Exception {
        String usedHarare = activeListing("Used Laptop", "computers", "USED_GOOD", "Harare");
        String newBulawayo = activeListing("New Laptop", "computers", "NEW", "Bulawayo");

        // Condition filter, case-insensitive value parsing.
        mockMvc.perform(get("/marketplace/catalog").param("condition", "used_good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(usedHarare))
                .andExpect(jsonPath("$.data.items[0].condition").value("USED_GOOD"));

        // City filter: exact but case-insensitive.
        mockMvc.perform(get("/marketplace/catalog").param("city", "HARARE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(usedHarare))
                .andExpect(jsonPath("$.data.items[0].city").value("Harare"));

        // All filters combined (q + category-parent + condition + city) —
        // the fullest predicate set the browse can render.
        mockMvc.perform(get("/marketplace/catalog")
                        .param("q", "laptop")
                        .param("category", "electronics")
                        .param("condition", "NEW")
                        .param("city", "bulawayo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(newBulawayo));

        // Garbage condition is a clean 400, never a silently unfiltered dump.
        mockMvc.perform(get("/marketplace/catalog").param("condition", "MINT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_condition"));
    }

    @Test
    void browseSortsByPriceAndFiltersOnAPriceWindow() throws Exception {
        String cheap = activeListing("Lantern A", "other", null, null, 1000, 5, merchantToken);
        String mid = activeListing("Lantern B", "other", null, null, 3000, 5, merchantToken);
        String dear = activeListing("Lantern C", "other", null, null, 9000, 5, merchantToken);

        mockMvc.perform(get("/marketplace/catalog").param("sort", "price_asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(cheap))
                .andExpect(jsonPath("$.data.items[2].id").value(dear));

        mockMvc.perform(get("/marketplace/catalog").param("sort", "price_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(dear))
                .andExpect(jsonPath("$.data.items[2].id").value(cheap));

        // Both bounds are INCLUSIVE, in minor units — the same unit the
        // response reports.
        mockMvc.perform(get("/marketplace/catalog")
                        .param("minPriceCents", "1000").param("maxPriceCents", "3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')]".formatted(cheap)).exists())
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')]".formatted(mid)).exists());

        // One bound alone contributes only its own comparison.
        mockMvc.perform(get("/marketplace/catalog").param("minPriceCents", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(dear));

        // An inverted window is refused rather than returning an empty page
        // that reads as "nothing is for sale in your budget".
        mockMvc.perform(get("/marketplace/catalog")
                        .param("minPriceCents", "5000").param("maxPriceCents", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_price_range"));

        mockMvc.perform(get("/marketplace/catalog").param("sort", "cheapest"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_sort"));
    }

    @Test
    void browseHidesZeroStockListingsOnlyWhenAsked() throws Exception {
        String inStock = activeListing("Kettle", "other", null, null, 2000, 4, merchantToken);
        String soldOut = activeListing("Toaster", "other", null, null, 2500, 0, merchantToken);

        // An ACTIVE listing may sit at stockQty 0 — by default it is still
        // shown, which is the historical behaviour.
        mockMvc.perform(get("/marketplace/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2));

        mockMvc.perform(get("/marketplace/catalog").param("inStock", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(inStock));

        // false means "don't filter", never "show me the sold-out ones".
        mockMvc.perform(get("/marketplace/catalog").param("inStock", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[?(@.id == '%s')]".formatted(soldOut)).exists());

        mockMvc.perform(get("/marketplace/catalog").param("inStock", "yes"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_boolean"));
    }

    @Test
    void browseFiltersToOneSellerAndRefusesAnUnknownParameter() throws Exception {
        UUID otherMerchant = UUID.randomUUID();
        String otherToken = TestJwts.merchantAdmin(UUID.randomUUID(), otherMerchant, jwtSecret);
        String mine = activeListing("Solar Panel", "other", null, null, 8000, 2, merchantToken);
        activeListing("Solar Inverter", "other", null, null, 12000, 2, otherToken);

        mockMvc.perform(get("/marketplace/catalog")
                        .param("merchantId", merchantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(mine));

        // Unknown seller: a lenient empty page, never a 404 confirming which
        // merchant ids exist.
        mockMvc.perform(get("/marketplace/catalog")
                        .param("merchantId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(0));

        mockMvc.perform(get("/marketplace/catalog").param("merchantId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_merchant_id"));

        // The refusal that stops a filter being silently dropped: a misspelt
        // parameter is a 400 naming it, not a confident 200 with the bound
        // ignored.
        mockMvc.perform(get("/marketplace/catalog").param("minPrice", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_parameter"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("minPrice")));
    }

    @Test
    void theSellerProfileAnswersForKnownAndUnknownMerchantsAlike() throws Exception {
        activeListing("Blender", "other", null, null, 3500, 3, merchantToken);
        activeListing("Mixer", "other", null, null, 4500, 3, merchantToken);

        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.activeListingCount").value(2))
                // Unrated is null, never 0.0 — which would read as a terrible
                // seller rather than a new one.
                .andExpect(jsonPath("$.data.ratingAvg").doesNotExist())
                .andExpect(jsonPath("$.data.reviewCount").value(0))
                // The seller record is created by the first listing, and a
                // PENDING seller is not vetted.
                .andExpect(jsonPath("$.data.verified").value(false));

        // Never a 404: an unknown merchant is a zeroed profile, so the public
        // catalogue is not an oracle for which merchant ids exist.
        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeListingCount").value(0))
                .andExpect(jsonPath("$.data.displayName").doesNotExist());

        mockMvc.perform(get("/marketplace/catalog/merchants/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_merchant_id"));
    }

    @Test
    void writesValidateAndNormaliseTheTaxonomyAndSanitiseLocation() throws Exception {
        // Unknown code refused with the specific 400.
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Mystery Box",
                                  "categoryCode": "not-a-category",
                                  "priceCents": 900,
                                  "stockQty": 1
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_category"));

        // Mixed-case code is normalized; HTML in city/area is stripped; the
        // public read returns the stored (sanitized) values.
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Garden Bench",
                                  "categoryCode": "  Garden-Outdoor ",
                                  "condition": "USED_FAIR",
                                  "city": "<b>Mutare</b>",
                                  "area": "Murambi <script>x()</script>",
                                  "priceCents": 4500,
                                  "stockQty": 2
                                }"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryCode").value("garden-outdoor"))
                .andExpect(jsonPath("$.data.categoryName").value("Garden & Outdoor"))
                .andExpect(jsonPath("$.data.condition").value("USED_FAIR"))
                .andExpect(jsonPath("$.data.city").value("Mutare"))
                .andExpect(jsonPath("$.data.area").value("Murambi"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.data.id");

        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", id)
                        .file(new MockMultipartFile("image", "p.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", id)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/marketplace/catalog/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryCode").value("garden-outdoor"))
                .andExpect(jsonPath("$.data.city").value("Mutare"));
    }
}
