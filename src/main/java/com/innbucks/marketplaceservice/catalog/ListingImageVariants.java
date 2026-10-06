package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.ImageVariantRenderer.RenderedVariant;
import com.innbucks.marketplaceservice.catalog.ListingImageVariantRepository.ImageSource;
import com.innbucks.marketplaceservice.catalog.ListingImageVariantRepository.StoredVariant;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stored renditions of listing images (V25) — written once, served by key.
 *
 * <p><b>Write side</b> (the upload paths in {@link ListingService}):
 * {@link #render} runs the resizer for every rendition BEFORE the listing
 * transaction writes anything (so no row lock is held across a decode), and
 * {@link #store} writes them in the same transaction as the image row.
 * Replacing an image rewrites its renditions; deleting one deletes them.
 *
 * <p><b>Read side</b> ({@link #serve}): one indexed row read answers a
 * request — the rendition's own bytes, or, for a rendition that IS the
 * original (WebP, an image already narrower than the width, ?w= absent), the
 * original's bytes by primary key. A request whose rendition is not stored yet
 * (an image uploaded before V25, or a width the upload could not get a decode
 * permit for) renders it from the original exactly as every request used to,
 * then stores it — once; the next request reads it. A matching
 * {@code If-None-Match} is answered 304 from the stored tag without reading the
 * original at all.
 */
@Slf4j
@Service
public class ListingImageVariants {

    private final ListingImageVariantRepository repository;
    private final ImageVariantRenderer renderer;
    private final TransactionTemplate lazyWrite;
    private final Counter lazyStored;
    private final Counter lazyStoreFailed;

    public ListingImageVariants(ListingImageVariantRepository repository, ImageVariantRenderer renderer,
                                PlatformTransactionManager transactionManager, MeterRegistry registry) {
        this.repository = repository;
        this.renderer = renderer;
        this.lazyWrite = new TransactionTemplate(transactionManager);
        this.lazyWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lazyStored = Counter.builder("marketplace.listing.image.variant.lazy_stored")
                .description("Renditions stored by the first request for them (images older than V25)")
                .register(registry);
        this.lazyStoreFailed = Counter.builder("marketplace.listing.image.variant.lazy_store_failed")
                .description("First-request renditions that were served but could not be stored")
                .register(registry);
    }

    // ------------------------------------------------------------------
    // Write side
    // ------------------------------------------------------------------

    /** Every rendition of a validated upload. Pure: touches no database. */
    public List<RenderedVariant> render(byte[] bytes, String contentType) {
        return renderer.renderAll(bytes, contentType);
    }

    /** Stores {@code variants} as the renditions of {@code imageId}, in the
     *  caller's transaction, replacing any it had. */
    public void store(UUID imageId, Instant sourceCreatedAt, List<RenderedVariant> variants) {
        repository.replaceAll(imageId, sourceCreatedAt, variants == null ? List.of() : variants);
    }

    /** Deletes the renditions of {@code imageId}, in the caller's transaction. */
    public void delete(UUID imageId) {
        repository.deleteForImage(imageId);
    }

    // ------------------------------------------------------------------
    // Read side
    // ------------------------------------------------------------------

    /**
     * @param imageId null = the listing's primary image.
     * @param width   the {@code ?w=} parameter (null = the original).
     * @throws ApiException 404 {@code image_not_found} when the URL names no
     *         image (checked first, as before V25), then 400
     *         {@code unsupported_image_width} for a width outside the allow-list.
     */
    public ServedImage serve(UUID listingId, UUID imageId, Integer width, String ifNoneMatch) {
        Optional<ImageVariant> requested = ImageVariant.forWidth(width);
        if (requested.isEmpty()) {
            repository.findMeta(listingId, imageId).orElseThrow(ListingImageVariants::notFound);
            throw ApiException.badRequest("unsupported_image_width",
                    "Width must be one of " + ImageResizer.ALLOWED_WIDTHS.stream().sorted().toList());
        }
        ImageVariant variant = requested.get();
        Optional<StoredVariant> stored = repository.findVariant(listingId, imageId, variant);
        if (stored.isPresent()) {
            StoredVariant row = stored.get();
            if (ImageEtags.matches(ifNoneMatch, row.etag())) {
                return ServedImage.notModified(row.etag(), row.resized());
            }
            if (row.resized()) {
                return ServedImage.ok(row.bytes(), row.contentType(), row.etag(), true);
            }
            Optional<byte[]> original = repository.findOriginalBytes(row.imageId(), row.sourceCreatedAt());
            if (original.isPresent()) {
                return ServedImage.ok(original.get(), row.contentType(), row.etag(), false);
            }
            // Replaced between the two reads: answer from the new bytes below.
        }
        return renderOnFirstRequest(listingId, imageId, variant, ifNoneMatch);
    }

    private ServedImage renderOnFirstRequest(UUID listingId, UUID imageId, ImageVariant variant,
                                             String ifNoneMatch) {
        ImageSource source = repository.findSource(listingId, imageId)
                .orElseThrow(ListingImageVariants::notFound);
        RenderedVariant rendered = renderer.render(source.bytes(), source.contentType(), variant);
        if (!rendered.cacheable()) {
            // No decode permit free: the original, uncacheable, exactly as
            // before V25. Nothing stored — the next request tries again.
            return ServedImage.busy(source.bytes(), source.contentType());
        }
        storeLazily(source, rendered);
        if (ImageEtags.matches(ifNoneMatch, rendered.etag())) {
            return ServedImage.notModified(rendered.etag(), rendered.resized());
        }
        return ServedImage.ok(rendered.resized() ? rendered.bytes() : source.bytes(),
                rendered.contentType(), rendered.etag(), rendered.resized());
    }

    /** Best effort: a rendition that cannot be stored is still served. */
    private void storeLazily(ImageSource source, RenderedVariant rendered) {
        try {
            Boolean saved = lazyWrite.execute(status ->
                    repository.saveIfCurrent(source.imageId(), source.createdAt(), rendered));
            if (Boolean.TRUE.equals(saved)) {
                lazyStored.increment();
            }
        } catch (RuntimeException ex) {
            lazyStoreFailed.increment();
            log.warn("Could not store image rendition imageId={} variant={} reason={}",
                    source.imageId(), rendered.variant().code(), ex.toString());
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("image_not_found", "No image has been uploaded for this listing");
    }

    /**
     * What the controller sends. {@code notModified}: a 304 with no body.
     * {@code etag} is the bare hex (null only for a busy, uncacheable answer).
     */
    public record ServedImage(boolean notModified, byte[] bytes, String contentType, String etag,
                              boolean resized, boolean cacheable) {

        static ServedImage ok(byte[] bytes, String contentType, String etag, boolean resized) {
            return new ServedImage(false, bytes, contentType, etag, resized, true);
        }

        static ServedImage notModified(String etag, boolean resized) {
            return new ServedImage(true, null, null, etag, resized, true);
        }

        static ServedImage busy(byte[] original, String contentType) {
            return new ServedImage(false, original, contentType, null, false, false);
        }
    }
}
