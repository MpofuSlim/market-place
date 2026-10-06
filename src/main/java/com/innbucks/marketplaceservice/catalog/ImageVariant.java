package com.innbucks.marketplaceservice.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The renditions the public image endpoints serve: the original ({@code ?w=}
 * absent) and one per width in {@link ImageResizer#ALLOWED_WIDTHS}. The set is
 * closed — an arbitrary width is a 400, never a new rendition — so every
 * rendition an image can ever be asked for is known at upload and stored once
 * ({@code listing_image_variant}, V25). {@code code} is the stored value and is
 * pinned by the V25 CHECK constraint; {@code ImageVariantTest} ties the widths
 * to the resizer's allow-list.
 */
public enum ImageVariant {

    ORIGINAL("original", null),
    W120("w120", 120),
    W240("w240", 240),
    W480("w480", 480),
    W960("w960", 960);

    /** The renditions produced by the resizer, smallest first. */
    public static final List<ImageVariant> RESIZED = List.of(W120, W240, W480, W960);

    private final String code;
    private final Integer width;

    ImageVariant(String code, Integer width) {
        this.code = code;
        this.width = width;
    }

    public String code() {
        return code;
    }

    /** The requested width; null for {@link #ORIGINAL}. */
    public Integer width() {
        return width;
    }

    /** {@code null} → {@link #ORIGINAL}; an allowed width → its rendition; anything else → empty. */
    public static Optional<ImageVariant> forWidth(Integer width) {
        if (width == null) {
            return Optional.of(ORIGINAL);
        }
        return Arrays.stream(values())
                .filter(v -> width.equals(v.width))
                .findFirst();
    }
}
