package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V25 stored renditions against real Postgres, through the real HTTP stack:
 * an upload stores every rendition; a GET reads one row and never runs the
 * resizer (the {@code marketplace.listing.image.resizes} counter is the
 * witness); an image stored before V25 is rendered on its first request and
 * stored once, also under a burst of concurrent first requests; a matching
 * {@code If-None-Match} is a 304; replace and delete rewrite / remove the
 * rows; and Postgres' own statement log ({@code pg_stat_statements}, preloaded
 * by {@link PostgresTestContainer}) shows that no listing read and no stored
 * resized rendition selects {@code listing_image.image_bytes}.
 */
class ListingImageVariantsIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    private static final String MUST_REVALIDATE = "max-age=3600, must-revalidate, public";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private MeterRegistry meterRegistry;

    private String merchantToken;

    @BeforeEach
    void mintToken() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
    }

    private double resizes() {
        return meterRegistry.find("marketplace.listing.image.resizes").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private String createDraftListing() throws Exception {
        String body = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    /** POST /{id}/image; returns the primary's image id. */
    private UUID uploadPrimary(String listingId, byte[] bytes, String contentType) throws Exception {
        String body = mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo", contentType, bytes))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.data.imageUrls[0]");
        return UUID.fromString(url.substring(url.lastIndexOf('/') + 1));
    }

    private UUID storeLegacyPrimary(String listingId, byte[] bytes, String contentType) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO listing_image (id, listing_id, image_bytes, content_type, is_primary,
                                           position, created_at)
                VALUES (?, ?, ?, ?, TRUE, 0, now())""",
                id, UUID.fromString(listingId), bytes, contentType);
        return id;
    }

    private List<String> variantCodes(UUID imageId) {
        return jdbc.queryForList(
                "SELECT variant FROM listing_image_variant WHERE image_id = ? ORDER BY variant",
                String.class, imageId);
    }

    @Test
    void anUploadStoresEveryRendition_andAGetServesItWithoutResizing() throws Exception {
        String listingId = createDraftListing();
        byte[] original = TestImages.gradient(1600, 1200, "png");
        UUID imageId = uploadPrimary(listingId, original, "image/png");

        assertThat(variantCodes(imageId)).containsExactly("original", "w120", "w240", "w480", "w960");
        Map<String, Object> w240Row = jdbc.queryForMap("""
                SELECT v.bytes, v.width, v.height, v.etag, v.resized,
                       v.source_created_at = i.created_at AS current
                  FROM listing_image_variant v JOIN listing_image i ON i.id = v.image_id
                 WHERE v.image_id = ? AND v.variant = 'w240'""", imageId);
        byte[] stored = (byte[]) w240Row.get("bytes");
        assertThat(stored).isEqualTo(ImageResizer.resize(original, "image/png", 240).bytes());
        assertThat(w240Row).containsEntry("width", 240).containsEntry("height", 180)
                .containsEntry("resized", true).containsEntry("current", true)
                .containsEntry("etag", ImageEtags.sha256Hex(stored));
        assertThat(jdbc.queryForObject("SELECT bytes IS NULL FROM listing_image_variant "
                + "WHERE image_id = ? AND variant = 'original'", Boolean.class, imageId)).isTrue();

        double before = resizes();
        String etag = ImageEtags.quoted(ImageEtags.sha256Hex(stored));
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "240"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Image-Resized", "true"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", MUST_REVALIDATE))
                .andExpect(header().string("ETag", etag))
                .andExpect(content().bytes(stored));
        mockMvc.perform(get("/marketplace/catalog/{id}/images/{imageId}", listingId, imageId)
                        .param("w", "240"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(stored));
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Image-Resized", "false"))
                .andExpect(header().string("ETag", ImageEtags.quoted(ImageEtags.sha256Hex(original))))
                .andExpect(content().bytes(original));
        assertThat(resizes()).as("no request resized anything").isEqualTo(before);
    }

    @Test
    void aMatchingIfNoneMatchIs304WithNoBody() throws Exception {
        String listingId = createDraftListing();
        byte[] original = TestImages.gradient(800, 600, "jpeg");
        uploadPrimary(listingId, original, "image/jpeg");
        String etag = mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "120"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");

        MockHttpServletResponse notModified = mockMvc.perform(
                        get("/marketplace/catalog/{id}/image", listingId).param("w", "120")
                                .header("If-None-Match", etag))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", etag))
                .andExpect(header().string("Cache-Control", MUST_REVALIDATE))
                .andReturn().getResponse();
        assertThat(notModified.getContentAsByteArray()).isEmpty();

        // Another width's tag is not this width's.
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "240")
                        .header("If-None-Match", etag))
                .andExpect(status().isOk());
    }

    @Test
    void aLegacyImageIsRenderedOnItsFirstRequestOnly() throws Exception {
        String listingId = createDraftListing();
        byte[] original = TestImages.gradient(1600, 1200, "jpeg");
        UUID imageId = storeLegacyPrimary(listingId, original, "image/jpeg");
        byte[] expected = ImageResizer.resize(original, "image/jpeg", 480).bytes();

        double before = resizes();
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "480"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Image-Resized", "true"))
                .andExpect(content().bytes(expected));
        assertThat(resizes()).isEqualTo(before + 1);
        assertThat(variantCodes(imageId)).containsExactly("w480");

        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "480"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(expected));
        mockMvc.perform(get("/marketplace/catalog/{id}/images/{imageId}", listingId, imageId)
                        .param("w", "480"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(expected));
        assertThat(resizes()).as("the second request read the stored row").isEqualTo(before + 1);
    }

    @Test
    void concurrentFirstRequestsAllSucceed_andStoreOneRow() throws Exception {
        String listingId = createDraftListing();
        byte[] original = TestImages.gradient(1200, 900, "png");
        UUID imageId = storeLegacyPrimary(listingId, original, "image/png");
        byte[] expected = ImageResizer.resize(original, "image/png", 240).bytes();

        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<MockHttpServletResponse>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<MockHttpServletResponse> call = () -> {
                    start.await();
                    return mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "240"))
                            .andReturn().getResponse();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            for (Future<MockHttpServletResponse> result : results) {
                MockHttpServletResponse response = result.get(60, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(200);
                if ("true".equals(response.getHeader("X-Image-Resized"))) {
                    assertThat(response.getContentAsByteArray()).isEqualTo(expected);
                } else {
                    // No decode permit free: the original, uncacheable — as before V25.
                    assertThat(response.getContentAsByteArray()).isEqualTo(original);
                    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(variantCodes(imageId)).containsExactly("w240");
        double before = resizes();
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "240"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(expected));
        assertThat(resizes()).isEqualTo(before);
    }

    @Test
    void replacingThePrimaryRewritesItsRenditions_deletingRemovesThem() throws Exception {
        String listingId = createDraftListing();
        UUID imageId = uploadPrimary(listingId, TestImages.gradient(1600, 1200, "png"), "image/png");
        String oldTag = jdbc.queryForObject("SELECT etag FROM listing_image_variant "
                + "WHERE image_id = ? AND variant = 'w240'", String.class, imageId);

        byte[] replacement = TestImages.gradient(1000, 1000, "jpeg");
        assertThat(uploadPrimary(listingId, replacement, "image/jpeg")).as("in place: same id").isEqualTo(imageId);

        Timestamp imageStamp = jdbc.queryForObject(
                "SELECT created_at FROM listing_image WHERE id = ?", Timestamp.class, imageId);
        assertThat(jdbc.queryForList("SELECT source_created_at FROM listing_image_variant WHERE image_id = ?",
                Timestamp.class, imageId))
                .hasSize(ImageVariant.values().length)
                .allSatisfy(stamp -> assertThat(stamp).isEqualTo(imageStamp));
        byte[] expected = ImageResizer.resize(replacement, "image/jpeg", 240).bytes();
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "240")
                        .header("If-None-Match", ImageEtags.quoted(oldTag)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(content().bytes(expected));

        mockMvc.perform(delete("/marketplace/listings/{id}/images/{imageId}", listingId, imageId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        assertThat(variantCodes(imageId)).isEmpty();
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "240"))
                .andExpect(status().isNotFound());
    }

    @Test
    void noListingReadAndNoStoredRenditionSelectsTheOriginalsBytes() throws Exception {
        String listingId = createDraftListing();
        UUID imageId = uploadPrimary(listingId, TestImages.gradient(1600, 1200, "png"), "image/png");
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        // Every statement the server runs from here on, whoever sends it
        // (Hibernate, JDBC, prepared or not), is recorded by Postgres itself.
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        jdbc.execute("SELECT pg_stat_statements_reset()");

        mockMvc.perform(get("/marketplace/catalog"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/listings/mine")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        for (String width : List.of("120", "240", "480", "960")) {
            mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", width))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Image-Resized", "true"));
            mockMvc.perform(get("/marketplace/catalog/{id}/images/{imageId}", listingId, imageId)
                            .param("w", width))
                    .andExpect(status().isOk());
        }
        assertThat(statementsReadingImageBytes()).isEmpty();

        // Control: the ORIGINAL is its bytes, so that request reads them — by
        // primary key, and the recorder sees it.
        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId))
                .andExpect(status().isOk());
        assertThat(statementsReadingImageBytes())
                .containsExactly("SELECT image_bytes FROM listing_image WHERE id = $1 AND created_at = $2");
    }

    private List<String> statementsReadingImageBytes() {
        return jdbc.queryForList("""
                SELECT query FROM pg_stat_statements
                 WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                   AND query ILIKE '%image_bytes%'
                   AND query NOT ILIKE '%pg_stat_statements%'""", String.class);
    }
}
