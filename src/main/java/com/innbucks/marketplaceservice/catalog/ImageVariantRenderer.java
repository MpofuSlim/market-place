package com.innbucks.marketplaceservice.catalog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Makes the stored renditions of one image ({@link ImageVariant}). Every resize
 * goes through {@link ImageResizer#resize(byte[], String, Integer, ImagePixelBudget, ImageDecodePermits)}
 * with the cell's budget and permits — the exact call the read path made per
 * request before V25 — so a stored rendition is byte-for-byte what that request
 * would have returned on the same JDK, header guard, decode ceiling,
 * never-upscale and WebP pass-through included.
 *
 * <p>Each resize counts on {@code marketplace.listing.image.resizes{trigger}}:
 * {@code upload} (at most four per uploaded image) and {@code lazy} (the first
 * request for a rendition of an image stored before V25, or one an upload left
 * out because no decode permit came free). Steady state, {@code lazy} is flat.
 */
@Component
public class ImageVariantRenderer {

    /** How long an upload waits for a decode permit per rendition before
     *  leaving that rendition to the first request. */
    static final Duration UPLOAD_PERMIT_WAIT = Duration.ofMillis(500);

    public enum Trigger { UPLOAD, LAZY }

    private final ImagePixelBudget budget;
    private final ImageDecodePermits readPermits;
    private final ImageDecodePermits uploadPermits;
    private final Counter uploadResizes;
    private final Counter lazyResizes;

    public ImageVariantRenderer(ImagePixelBudget budget, ImageDecodePermits permits, MeterRegistry registry) {
        this.budget = budget;
        this.readPermits = permits;
        this.uploadPermits = permits.waitingUpTo(UPLOAD_PERMIT_WAIT);
        this.uploadResizes = Counter.builder("marketplace.listing.image.resizes")
                .description("Listing image resizes (decode + scale + encode), by what triggered them")
                .tag("trigger", "upload")
                .register(registry);
        this.lazyResizes = Counter.builder("marketplace.listing.image.resizes")
                .description("Listing image resizes (decode + scale + encode), by what triggered them")
                .tag("trigger", "lazy")
                .register(registry);
    }

    /**
     * Every rendition of a just-validated upload: the original's tag plus one
     * row per width. A width whose resize found no decode permit within
     * {@link #UPLOAD_PERMIT_WAIT} is left out — the first request makes it.
     */
    public List<RenderedVariant> renderAll(byte[] source, String contentType) {
        String originalEtag = ImageEtags.sha256Hex(source);
        Optional<ImageDimensions.Size> originalSize = ImageDimensions.read(source);
        List<RenderedVariant> out = new ArrayList<>(ImageVariant.values().length);
        out.add(RenderedVariant.passThrough(ImageVariant.ORIGINAL, source, contentType, originalEtag, originalSize));
        for (ImageVariant variant : ImageVariant.RESIZED) {
            RenderedVariant rendered = resize(source, contentType, variant, Trigger.UPLOAD,
                    originalEtag, originalSize);
            if (rendered.cacheable()) {
                out.add(rendered);
            }
        }
        return out;
    }

    /** One rendition, for a request that found none stored. Never waits for a permit. */
    public RenderedVariant render(byte[] source, String contentType, ImageVariant variant) {
        if (variant == ImageVariant.ORIGINAL) {
            return RenderedVariant.passThrough(variant, source, contentType,
                    ImageEtags.sha256Hex(source), ImageDimensions.read(source));
        }
        return resize(source, contentType, variant, Trigger.LAZY, null, null);
    }

    private RenderedVariant resize(byte[] source, String contentType, ImageVariant variant, Trigger trigger,
                                   String originalEtag, Optional<ImageDimensions.Size> originalSize) {
        (trigger == Trigger.UPLOAD ? uploadResizes : lazyResizes).increment();
        ImageResizer.Resized result = ImageResizer.resize(source, contentType, variant.width(), budget,
                trigger == Trigger.UPLOAD ? uploadPermits : readPermits);
        if (!result.cacheable()) {
            return RenderedVariant.busy(variant, contentType, source.length);
        }
        if (!result.resized()) {
            return RenderedVariant.passThrough(variant, source, result.contentType(),
                    originalEtag != null ? originalEtag : ImageEtags.sha256Hex(source),
                    originalSize != null ? originalSize : ImageDimensions.read(source));
        }
        byte[] bytes = result.bytes();
        Optional<ImageDimensions.Size> size = ImageDimensions.read(bytes);
        return new RenderedVariant(variant, true, true, result.contentType(), bytes,
                size.map(ImageDimensions.Size::width).orElse(null),
                size.map(ImageDimensions.Size::height).orElse(null),
                bytes.length, ImageEtags.sha256Hex(bytes));
    }

    /**
     * One rendition. {@code resized == false} means the request is answered
     * with the ORIGINAL bytes, so {@code bytes} is null (never a second copy of
     * the original); {@code etag} is then the original's. {@code cacheable ==
     * false} means no decode permit was free: nothing to store, the original is
     * served {@code no-store} and {@code etag} is null.
     */
    public record RenderedVariant(ImageVariant variant, boolean cacheable, boolean resized, String contentType,
                                  byte[] bytes, Integer width, Integer height, int byteSize, String etag) {

        static RenderedVariant passThrough(ImageVariant variant, byte[] original, String contentType,
                                           String originalEtag, Optional<ImageDimensions.Size> size) {
            return new RenderedVariant(variant, true, false, contentType, null,
                    size.map(ImageDimensions.Size::width).orElse(null),
                    size.map(ImageDimensions.Size::height).orElse(null),
                    original.length, originalEtag);
        }

        static RenderedVariant busy(ImageVariant variant, String contentType, int originalLength) {
            return new RenderedVariant(variant, false, false, contentType, null, null, null,
                    originalLength, null);
        }
    }
}
