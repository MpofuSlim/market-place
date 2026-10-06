package com.innbucks.marketplaceservice.catalog;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored rendition set is exactly what the endpoint can be asked for:
 * the original plus every allowed width, no more (a width nobody can request
 * would be a wasted decode at every upload) and no less (a missing one would
 * be resized on read again, forever).
 */
class ImageVariantTest {

    @Test
    void theResizedRenditionsAreExactlyTheAllowedWidths() {
        assertThat(ImageVariant.RESIZED.stream().map(ImageVariant::width).collect(Collectors.toSet()))
                .isEqualTo(ImageResizer.ALLOWED_WIDTHS);
        assertThat(ImageVariant.values()).hasSize(ImageResizer.ALLOWED_WIDTHS.size() + 1);
    }

    @Test
    void widthsResolveToTheirRendition_andNothingElseDoes() {
        assertThat(ImageVariant.forWidth(null)).contains(ImageVariant.ORIGINAL);
        assertThat(ImageVariant.forWidth(240)).contains(ImageVariant.W240);
        assertThat(ImageVariant.forWidth(960)).contains(ImageVariant.W960);
        assertThat(ImageVariant.forWidth(250)).isEmpty();
        assertThat(ImageVariant.forWidth(0)).isEmpty();
        assertThat(ImageVariant.forWidth(-120)).isEmpty();
    }

    @Test
    void everyCodeIsAllowedByTheV25CheckConstraint() throws Exception {
        String v25 = Files.readString(
                Path.of("src/main/resources/db/migration/V25__listing_image_variant.sql"),
                StandardCharsets.UTF_8);
        String allowed = Arrays.stream(ImageVariant.values())
                .map(v -> "'" + v.code() + "'")
                .collect(Collectors.joining(", "));
        assertThat(v25).contains("CHECK (variant IN (" + allowed + "))");
    }
}
