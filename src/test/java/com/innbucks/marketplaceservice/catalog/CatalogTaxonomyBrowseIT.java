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

    @BeforeEach
    void mintTokens() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
    }

    /** Creates an ACTIVE listing (image uploaded to satisfy the publish gate). */
    private String activeListing(String title, String categoryCode, String condition,
                                 String city) throws Exception {
        String conditionField = condition == null ? "" : "\"condition\": \"%s\",".formatted(condition);
        String cityField = city == null ? "" : "\"city\": \"%s\",".formatted(city);
        String body = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "categoryCode": "%s",
                                  %s
                                  %s
                                  "priceCents": 1500,
                                  "stockQty": 5
                                }""".formatted(title, categoryCode, conditionField, cityField)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", id)
                        .file(new MockMultipartFile("image", "p.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", id)
                        .header("Authorization", "Bearer " + merchantToken)
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
