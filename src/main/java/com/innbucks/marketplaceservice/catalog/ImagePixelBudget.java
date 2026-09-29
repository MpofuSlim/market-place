package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The largest image, in PIXELS, a listing may carry — enforced at upload and
 * re-checked before any server-side decode.
 *
 * <p>The 10 MB byte cap says nothing about memory: a flat-colour PNG of
 * 30000 x 30000 is a few hundred KB on disk and ~3.6 GB decoded (4 bytes per
 * pixel). Bytes bound the upload; pixels bound the image every app and portal
 * decodes to render it, and are the first thing {@link ImageResizer} checks
 * before a decode of its own.
 *
 * <p><b>Why 50 MP and 8192 px by default — and why this is NOT the memory
 * guard.</b> The pod's heap is ~450 MiB (a 640 MiB limit at
 * {@code MaxRAMPercentage=70}), so a full-resolution decode of even one 50 MP
 * image (~200 MB) is unaffordable, and the resizer never does one: memory is
 * protected by {@link ImageResizer}'s byte ceiling on every decode
 * ({@link ImageResizer#DECODE_CEILING_BYTES}, which also refuses a progressive
 * JPEG's full-resolution native buffer) and by {@link ImageDecodePermits}
 * bounding how many decodes run at once. This budget is chosen to admit what
 * phone cameras actually produce and nothing stranger: 12 MP is the default
 * shot on almost every phone, and the full-resolution 48/50 MP modes top out at
 * 8160 x 6120 (49.9 MP). Anything larger from a camera (108/200 MP modes) is a
 * 15-40 MB JPEG that the byte cap refuses already, so above 50 MP the only
 * images left are synthetic ones — and every app and portal decodes the full
 * raster to show an image, so the budget is what protects THEM. The side limit
 * refuses the shapes that are never product photos (panoramas, strips).
 *
 * <p>Configurable per cell ({@code marketplace.listing.image-max-pixels} /
 * {@code image-max-side}). Raising it does not need more heap, and more heap
 * does not need a higher budget: an image the resizer cannot decode inside its
 * ceiling is served as the original.
 */
@Component
public class ImagePixelBudget {

    public static final long DEFAULT_MAX_PIXELS = 50_000_000L;
    public static final int DEFAULT_MAX_SIDE = 8192;

    private final long maxPixels;
    private final int maxSide;

    public ImagePixelBudget(@Value("${marketplace.listing.image-max-pixels:50000000}") long maxPixels,
                            @Value("${marketplace.listing.image-max-side:8192}") int maxSide) {
        if (maxPixels <= 0 || maxSide <= 0) {
            throw new IllegalArgumentException(
                    "marketplace.listing.image-max-pixels and image-max-side must be positive");
        }
        this.maxPixels = maxPixels;
        this.maxSide = maxSide;
    }

    /** The production defaults — for code built without Spring (tests). */
    public static ImagePixelBudget defaults() {
        return new ImagePixelBudget(DEFAULT_MAX_PIXELS, DEFAULT_MAX_SIDE);
    }

    public long maxPixels() {
        return maxPixels;
    }

    public int maxSide() {
        return maxSide;
    }

    public boolean admits(ImageDimensions.Size size) {
        return size.width() <= maxSide && size.height() <= maxSide && size.pixels() <= maxPixels;
    }

    /**
     * Upload guard: 400 {@code image_dimensions_too_large} when the header
     * declares more than the budget. An image whose header cannot be read is
     * let through, exactly as before this guard existed — the magic-byte check
     * already vouched for its type, and {@link ImageResizer} never decodes an
     * image whose size it cannot read, so nothing unbounded can follow.
     */
    public void requireUploadable(byte[] bytes) {
        ImageDimensions.read(bytes)
                .filter(size -> !admits(size))
                .ifPresent(size -> {
                    throw ApiException.badRequest("image_dimensions_too_large", refusalMessage());
                });
    }

    /** Customer-safe: names the limit, never the endpoint or the internals. */
    String refusalMessage() {
        return "That image has too many pixels. Please use one of at most "
                + megapixels() + " megapixels and no more than "
                + String.format(Locale.ROOT, "%,d", maxSide) + " pixels on its longest side.";
    }

    private String megapixels() {
        if (maxPixels % 1_000_000 == 0) {
            return Long.toString(maxPixels / 1_000_000);
        }
        return String.format(Locale.ROOT, "%.1f", maxPixels / 1_000_000.0);
    }
}
