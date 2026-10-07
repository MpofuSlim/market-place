package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An upload's renditions are rendered with NO transaction open and NO
 * connection bound to the request thread — on every upload path, through the
 * real HTTP stack, the real transaction manager and real Postgres. Rendering is
 * decode + scale + encode, seconds of CPU for a large photo; inside the upload's
 * transaction it held a pooled connection for all of it, so a handful of large
 * uploads could starve every other request of the pool.
 *
 * <p>The witness is Spring's own transaction state, read by a
 * {@link ProbingRenderer} that stands in for {@link ImageVariantRenderer} (the
 * fleet's {@code @Primary} test-bean shape — {@code @MockitoBean} is unreliable
 * on Boot 4): {@code isActualTransactionActive()} and the thread's resource map
 * (a JPA {@code EntityManagerHolder} or JDBC {@code ConnectionHolder} bound there
 * is a connection held). {@link #theProbeSeesAnOpenTransaction} proves the probe
 * would notice. The rows are then checked in Postgres, so the write still
 * happens — one transaction, after the render.
 */
@Import(ImageRenderOutsideTransactionIT.ProbeConfig.class)
class ImageRenderOutsideTransactionIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        @Primary
        ProbingRenderer probingRenderer(ImagePixelBudget budget, ImageDecodePermits permits,
                                        MeterRegistry registry) {
            return new ProbingRenderer(budget, permits, registry);
        }
    }

    /** The real renderer, recording the transaction state of every upload render. */
    static final class ProbingRenderer extends ImageVariantRenderer {
        final AtomicInteger uploadRenders = new AtomicInteger();
        final List<String> violations = new CopyOnWriteArrayList<>();

        ProbingRenderer(ImagePixelBudget budget, ImageDecodePermits permits, MeterRegistry registry) {
            super(budget, permits, registry);
        }

        @Override
        public List<RenderedVariant> renderAll(byte[] source, String contentType) {
            uploadRenders.incrementAndGet();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                violations.add("rendered with a transaction open");
            }
            if (!TransactionSynchronizationManager.getResourceMap().isEmpty()) {
                violations.add("rendered with resources bound to the thread: "
                        + TransactionSynchronizationManager.getResourceMap().values().stream()
                                .map(value -> value.getClass().getSimpleName()).toList());
            }
            return super.renderAll(source, contentType);
        }

        void reset() {
            uploadRenders.set(0);
            violations.clear();
        }
    }

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private ProbingRenderer probe;

    @Autowired
    private TransactionOperations transactions;

    private String merchantToken;

    @BeforeEach
    void setUp() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        probe.reset();
    }

    private static MockMultipartFile png(String part, int width, int height) {
        return new MockMultipartFile(part, part + ".png", "image/png", TestImages.gradient(width, height, "png"));
    }

    private long variantRows(String listingId) {
        Long rows = jdbc.queryForObject("""
                SELECT count(*) FROM listing_image_variant v JOIN listing_image i ON i.id = v.image_id
                 WHERE i.listing_id = ?""", Long.class, UUID.fromString(listingId));
        return rows == null ? 0 : rows;
    }

    @Test
    void theProbeSeesAnOpenTransaction() {
        transactions.executeWithoutResult(tx ->
                probe.renderAll(TestImages.gradient(200, 100, "png"), "image/png"));

        assertThat(probe.violations).isNotEmpty();
    }

    @Test
    void everyUploadPathRendersWithNoTransactionOpen_andStillWritesEveryRow() throws Exception {
        // Multipart create: a primary and two more.
        String created = mockMvc.perform(multipart("/marketplace/listings")
                        .file(new MockMultipartFile("listing", "", "application/json", LISTING_BODY.getBytes()))
                        .file(png("image", 1200, 900))
                        .file(png("images", 1000, 800))
                        .file(png("images", 640, 480))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        assertThat(probe.uploadRenders).hasValue(3);
        long afterCreate = variantRows(listingId);
        assertThat(afterCreate).as("renditions stored for the three images").isGreaterThanOrEqualTo(3);

        // POST /{id}/image as a replace of that primary.
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(png("image", 1600, 1200))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(3));
        // POST /{id}/images.
        mockMvc.perform(multipart("/marketplace/listings/{id}/images", listingId)
                        .file(png("image", 800, 600))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(4));

        // POST /{id}/image into an empty gallery (a JSON-created listing).
        String jsonCreated = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String emptyListingId = JsonPath.read(jsonCreated, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", emptyListingId)
                        .file(png("image", 1200, 900))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(1));

        assertThat(probe.uploadRenders).hasValue(6);
        assertThat(probe.violations).as("every upload render ran outside any transaction").isEmpty();
        assertThat(variantRows(listingId)).as("the added image's renditions were written")
                .isGreaterThan(afterCreate);
        assertThat(variantRows(emptyListingId)).isPositive();
    }

    @Test
    void aGalleryAtTheLimitIsRefusedWithoutARender() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        for (int i = 0; i < ListingService.MAX_GALLERY_IMAGES; i++) {
            mockMvc.perform(multipart("/marketplace/listings/{id}/images", listingId)
                            .file(png("image", 64, 48))
                            .header("Authorization", "Bearer " + merchantToken))
                    .andExpect(status().isOk());
        }
        probe.reset();

        mockMvc.perform(multipart("/marketplace/listings/{id}/images", listingId)
                        .file(png("image", 1600, 1200))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("image_limit_reached"))
                .andExpect(jsonPath("$.message").value("A listing can have at most 10 images"));

        assertThat(probe.uploadRenders).hasValue(0);
        Long images = jdbc.queryForObject("SELECT count(*) FROM listing_image WHERE listing_id = ?",
                Long.class, UUID.fromString(listingId));
        assertThat(images).isEqualTo(ListingService.MAX_GALLERY_IMAGES);
    }
}
