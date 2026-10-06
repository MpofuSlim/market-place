package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.ImageVariantRenderer.RenderedVariant;
import com.innbucks.marketplaceservice.catalog.ListingImageVariants.ServedImage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The serving and storing rules of {@link ListingImageVariants} over a REAL
 * renderer (spied, so "the resizer was not invoked" is a verified fact) and an
 * in-memory repository with the SQL's semantics: a rendition is served only
 * while its {@code source_created_at} matches the image's {@code created_at},
 * and the original's bytes are read only by the two byte-reading calls, which
 * the fake counts. The SQL itself is pinned against Postgres by
 * {@code ListingImageVariantsIT}.
 */
class ListingImageVariantsTest {

    private static final UUID LISTING = UUID.randomUUID();

    private SimpleMeterRegistry registry;
    private ImageVariantRenderer renderer;
    private FakeRepository repository;
    private ListingImageVariants variants;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        renderer = spy(new ImageVariantRenderer(ImagePixelBudget.defaults(), ImageDecodePermits.defaults(),
                registry));
        repository = new FakeRepository();
        variants = new ListingImageVariants(repository, renderer, mock(PlatformTransactionManager.class),
                registry);
    }

    /** An image as an upload writes it: row + every rendition. */
    private UUID uploaded(byte[] bytes, String contentType, boolean primary) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.putImage(id, LISTING, primary, contentType, createdAt, bytes);
        variants.store(id, createdAt, variants.render(bytes, contentType));
        return id;
    }

    /** An image stored before V25: a row and no renditions. */
    private UUID legacy(byte[] bytes, String contentType, boolean primary) {
        UUID id = UUID.randomUUID();
        repository.putImage(id, LISTING, primary, contentType,
                Instant.now().truncatedTo(ChronoUnit.MICROS), bytes);
        return id;
    }

    /** Summed over tags ({@code resizes} has one series per trigger). */
    private double counter(String name) {
        return registry.get(name).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    @Test
    void anUploadStoresEveryRendition() {
        byte[] source = TestImages.gradient(1600, 1200, "jpeg");
        UUID id = uploaded(source, "image/jpeg", true);

        assertThat(repository.variantCodes(id))
                .containsExactlyInAnyOrder("original", "w120", "w240", "w480", "w960");
        assertThat(repository.variant(id, "w480").bytes())
                .isEqualTo(ImageResizer.resize(source, "image/jpeg", 480).bytes());
        assertThat(repository.variant(id, "original").bytes()).isNull();
    }

    // ------------------------------------------------------------------
    // Serving a stored rendition
    // ------------------------------------------------------------------

    @Test
    void aStoredRenditionIsServedFromItsRow_withoutTheResizerOrTheOriginal() {
        byte[] source = TestImages.gradient(1600, 1200, "png");
        UUID id = uploaded(source, "image/png", true);
        RenderedVariant stored = repository.variant(id, "w240");
        org.mockito.Mockito.clearInvocations(renderer);

        ServedImage primary = variants.serve(LISTING, null, 240, null);
        ServedImage byId = variants.serve(LISTING, id, 240, null);

        for (ServedImage served : List.of(primary, byId)) {
            assertThat(served.notModified()).isFalse();
            assertThat(served.bytes()).isEqualTo(stored.bytes())
                    .isEqualTo(ImageResizer.resize(source, "image/png", 240).bytes());
            assertThat(served.contentType()).isEqualTo("image/png");
            assertThat(served.resized()).isTrue();
            assertThat(served.cacheable()).isTrue();
            assertThat(served.etag()).isEqualTo(ImageEtags.sha256Hex(stored.bytes()));
        }
        verify(renderer, never()).render(any(), any(), any());
        assertThat(repository.originalReads).as("the original's bytes are never read").isZero();
        assertThat(counter("marketplace.listing.image.resizes")).isEqualTo(4); // the upload's, only
    }

    @Test
    void aStoredPassThroughReadsTheOriginalByKey_andNothingElse() {
        byte[] source = HeaderOnlyImages.webpLossy(2000, 1500);
        UUID id = uploaded(source, "image/webp", true);
        org.mockito.Mockito.clearInvocations(renderer);

        ServedImage original = variants.serve(LISTING, id, null, null);
        ServedImage w240 = variants.serve(LISTING, id, 240, null);

        assertThat(original.bytes()).isEqualTo(source);
        assertThat(w240.bytes()).isEqualTo(source);
        assertThat(w240.resized()).isFalse();
        assertThat(w240.etag()).isEqualTo(original.etag()).isEqualTo(ImageEtags.sha256Hex(source));
        assertThat(repository.originalReads).isEqualTo(2);
        assertThat(repository.sourceReads).as("never the first-request path").isZero();
        verify(renderer, never()).render(any(), any(), any());
    }

    @Test
    void aMatchingIfNoneMatchIs304_fromTheStoredTagAlone() {
        byte[] source = TestImages.gradient(800, 600, "png");
        UUID id = uploaded(source, "image/png", false);
        String w120 = repository.variant(id, "w120").etag();
        String original = repository.variant(id, "original").etag();

        ServedImage resized = variants.serve(LISTING, id, 120, ImageEtags.quoted(w120));
        ServedImage full = variants.serve(LISTING, id, null, "W/" + ImageEtags.quoted(original));
        ServedImage stale = variants.serve(LISTING, id, 120, ImageEtags.quoted(original));

        assertThat(resized.notModified()).isTrue();
        assertThat(resized.bytes()).isNull();
        assertThat(resized.etag()).isEqualTo(w120);
        assertThat(full.notModified()).isTrue();
        assertThat(repository.originalReads).as("a 304 never reads the original").isZero();
        assertThat(stale.notModified()).as("another width's tag is not this one's").isFalse();
    }

    // ------------------------------------------------------------------
    // Images stored before V25
    // ------------------------------------------------------------------

    @Test
    void aLegacyImageIsRenderedOnItsFirstRequest_storedOnce_andReadThereafter() {
        byte[] source = TestImages.gradient(1600, 1200, "jpeg");
        UUID id = legacy(source, "image/jpeg", true);

        ServedImage first = variants.serve(LISTING, null, 480, null);
        ServedImage second = variants.serve(LISTING, null, 480, null);
        ServedImage third = variants.serve(LISTING, id, 480, null);

        byte[] expected = ImageResizer.resize(source, "image/jpeg", 480).bytes();
        assertThat(first.bytes()).isEqualTo(expected);
        assertThat(second.bytes()).isEqualTo(expected);
        assertThat(third.bytes()).isEqualTo(expected);
        assertThat(first.etag()).isEqualTo(second.etag()).isEqualTo(ImageEtags.sha256Hex(expected));
        verify(renderer, times(1)).render(any(), any(), any());
        assertThat(counter("marketplace.listing.image.resizes")).isEqualTo(1);
        assertThat(counter("marketplace.listing.image.variant.lazy_stored")).isEqualTo(1);
        assertThat(repository.variantCodes(id)).containsExactly("w480");
        assertThat(repository.sourceReads).isEqualTo(1);
    }

    @Test
    void aLegacyOriginalIsTaggedOnItsFirstRequest() {
        byte[] source = TestImages.gradient(300, 200, "png");
        legacy(source, "image/png", true);

        ServedImage first = variants.serve(LISTING, null, null, null);
        ServedImage revalidated = variants.serve(LISTING, null, null, ImageEtags.quoted(first.etag()));

        assertThat(first.bytes()).isEqualTo(source);
        assertThat(first.etag()).isEqualTo(ImageEtags.sha256Hex(source));
        assertThat(revalidated.notModified()).isTrue();
        assertThat(counter("marketplace.listing.image.resizes")).isZero();
    }

    @Test
    void aBusyFirstRequestServesTheOriginalUncacheable_andStoresNothing() {
        ImageDecodePermits permits = new ImageDecodePermits(1);
        assertThat(permits.tryAcquire()).isTrue();
        ListingImageVariants busy = new ListingImageVariants(repository,
                new ImageVariantRenderer(ImagePixelBudget.defaults(), permits, registry),
                mock(PlatformTransactionManager.class), registry);
        byte[] source = TestImages.gradient(800, 600, "png");
        UUID id = legacy(source, "image/png", true);

        ServedImage served = busy.serve(LISTING, null, 240, null);

        assertThat(served.bytes()).isEqualTo(source);
        assertThat(served.cacheable()).isFalse();
        assertThat(served.resized()).isFalse();
        assertThat(served.etag()).isNull();
        assertThat(repository.variantCodes(id)).isEmpty();
    }

    @Test
    void aRenditionThatCannotBeStoredIsStillServed() {
        repository.failWrites = true;
        byte[] source = TestImages.gradient(800, 600, "png");
        legacy(source, "image/png", true);

        ServedImage served = variants.serve(LISTING, null, 240, null);

        assertThat(served.resized()).isTrue();
        assertThat(served.bytes()).isEqualTo(ImageResizer.resize(source, "image/png", 240).bytes());
        assertThat(counter("marketplace.listing.image.variant.lazy_store_failed")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Replace / delete
    // ------------------------------------------------------------------

    @Test
    void replacingAnImageRewritesItsRenditions_soTheOldOnesAreNeverServed() {
        byte[] before = TestImages.gradient(1600, 1200, "png");
        UUID id = uploaded(before, "image/png", true);
        String oldTag = variants.serve(LISTING, null, 240, null).etag();

        // What ListingService.uploadImage does: same id, new bytes and stamp.
        byte[] after = TestImages.gradient(1000, 1000, "jpeg");
        Instant stamp = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS);
        repository.putImage(id, LISTING, true, "image/jpeg", stamp, after);
        variants.store(id, stamp, variants.render(after, "image/jpeg"));

        ServedImage served = variants.serve(LISTING, null, 240, ImageEtags.quoted(oldTag));
        assertThat(served.notModified()).as("the old tag no longer matches").isFalse();
        assertThat(served.bytes()).isEqualTo(ImageResizer.resize(after, "image/jpeg", 240).bytes());
        assertThat(served.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void aRenditionOfOlderBytesIsIgnored_evenIfNobodyDeletedIt() {
        // A pod on the previous image replaced the bytes and stamped a new
        // created_at, but knows nothing of renditions.
        byte[] before = TestImages.gradient(1600, 1200, "png");
        UUID id = uploaded(before, "image/png", true);
        byte[] after = TestImages.gradient(1200, 1200, "png");
        repository.putImage(id, LISTING, true, "image/png",
                Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.MICROS), after);

        ServedImage served = variants.serve(LISTING, null, 240, null);

        assertThat(served.bytes()).isEqualTo(ImageResizer.resize(after, "image/png", 240).bytes());
        // ... and the stale row is overwritten, so the next request reads it.
        org.mockito.Mockito.clearInvocations(renderer);
        assertThat(variants.serve(LISTING, null, 240, null).bytes()).isEqualTo(served.bytes());
        verify(renderer, never()).render(any(), any(), any());
    }

    @Test
    void deletingAnImageDeletesItsRenditions() {
        UUID id = uploaded(TestImages.gradient(800, 600, "png"), "image/png", true);

        variants.delete(id);

        assertThat(repository.variantCodes(id)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Errors keep their pre-V25 order
    // ------------------------------------------------------------------

    @Test
    void noImageIs404_beforeAnyWidthCheck() {
        for (Integer width : new Integer[]{null, 240, 250}) {
            assertThatThrownBy(() -> variants.serve(UUID.randomUUID(), null, width, null))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(ex.code()).isEqualTo("image_not_found");
                    });
        }
        UUID foreignImage = legacy(TestImages.gradient(100, 100, "png"), "image/png", false);
        assertThatThrownBy(() -> variants.serve(UUID.randomUUID(), foreignImage, 240, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("image_not_found"));
    }

    @Test
    void anUnsupportedWidthOfARealImageIs400_withTheSameMessage() {
        legacy(TestImages.gradient(100, 100, "png"), "image/png", true);

        assertThatThrownBy(() -> variants.serve(LISTING, null, 250, null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.code()).isEqualTo("unsupported_image_width");
                    assertThat(ex.getMessage()).isEqualTo("Width must be one of [120, 240, 480, 960]");
                });
        assertThat(repository.sourceReads).as("refused without reading the bytes").isZero();
    }

    // ------------------------------------------------------------------
    // In-memory repository with the SQL's semantics
    // ------------------------------------------------------------------

    private record Image(UUID listingId, boolean primary, String contentType, Instant createdAt, byte[] bytes) {}

    private record Row(Instant sourceCreatedAt, RenderedVariant variant) {}

    private static final class FakeRepository extends ListingImageVariantRepository {

        final Map<UUID, Image> images = new HashMap<>();
        final Map<UUID, Map<String, Row>> rows = new HashMap<>();
        int originalReads;
        int sourceReads;
        boolean failWrites;

        FakeRepository() {
            super(mock(DataSource.class));
        }

        void putImage(UUID id, UUID listingId, boolean primary, String contentType, Instant createdAt,
                      byte[] bytes) {
            images.put(id, new Image(listingId, primary, contentType, createdAt, bytes));
        }

        List<String> variantCodes(UUID id) {
            return List.copyOf(rows.getOrDefault(id, Map.of()).keySet());
        }

        RenderedVariant variant(UUID id, String code) {
            return rows.get(id).get(code).variant();
        }

        private Optional<Map.Entry<UUID, Image>> resolve(UUID listingId, UUID imageId) {
            return images.entrySet().stream()
                    .filter(e -> e.getValue().listingId().equals(listingId))
                    .filter(e -> imageId == null ? e.getValue().primary() : e.getKey().equals(imageId))
                    .findFirst();
        }

        @Override
        public Optional<StoredVariant> findVariant(UUID listingId, UUID imageId, ImageVariant variant) {
            return resolve(listingId, imageId).flatMap(e -> {
                Row row = rows.getOrDefault(e.getKey(), Map.of()).get(variant.code());
                if (row == null || !row.sourceCreatedAt().equals(e.getValue().createdAt())) {
                    return Optional.empty();
                }
                RenderedVariant v = row.variant();
                return Optional.of(new StoredVariant(e.getKey(), row.sourceCreatedAt(), v.resized(),
                        v.contentType(), v.etag(), v.bytes()));
            });
        }

        @Override
        public Optional<ImageMeta> findMeta(UUID listingId, UUID imageId) {
            return resolve(listingId, imageId)
                    .map(e -> new ImageMeta(e.getKey(), e.getValue().contentType(), e.getValue().createdAt()));
        }

        @Override
        public Optional<ImageSource> findSource(UUID listingId, UUID imageId) {
            sourceReads++;
            return resolve(listingId, imageId).map(e -> new ImageSource(e.getKey(), e.getValue().contentType(),
                    e.getValue().createdAt(), e.getValue().bytes()));
        }

        @Override
        public Optional<byte[]> findOriginalBytes(UUID imageId, Instant createdAt) {
            originalReads++;
            Image image = images.get(imageId);
            return image != null && image.createdAt().equals(createdAt)
                    ? Optional.of(image.bytes()) : Optional.empty();
        }

        @Override
        public void replaceAll(UUID imageId, Instant sourceCreatedAt, List<RenderedVariant> variants) {
            Map<String, Row> forImage = new HashMap<>();
            variants.stream().filter(RenderedVariant::cacheable)
                    .forEach(v -> forImage.put(v.variant().code(), new Row(sourceCreatedAt, v)));
            rows.put(imageId, forImage);
        }

        @Override
        public void deleteForImage(UUID imageId) {
            rows.remove(imageId);
        }

        @Override
        public boolean saveIfCurrent(UUID imageId, Instant sourceCreatedAt, RenderedVariant variant) {
            if (failWrites) {
                throw new IllegalStateException("database unavailable");
            }
            Image image = images.get(imageId);
            if (image == null || !image.createdAt().equals(sourceCreatedAt)) {
                return false;
            }
            Map<String, Row> forImage = rows.computeIfAbsent(imageId, k -> new HashMap<>());
            Row existing = forImage.get(variant.variant().code());
            if (existing == null || !existing.sourceCreatedAt().equals(sourceCreatedAt)) {
                forImage.put(variant.variant().code(), new Row(sourceCreatedAt, variant));
            }
            return true;
        }
    }
}
