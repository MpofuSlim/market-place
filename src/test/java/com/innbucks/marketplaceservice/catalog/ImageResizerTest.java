package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real ImageIO round-trips, no mocks: the point of this class is what the
 * bytes actually come out as, which a mocked codec could not tell us.
 */
class ImageResizerTest {

    private static byte[] image(int width, int height, String format) throws Exception {
        // TYPE_INT_RGB for both: a JPEG writer cannot encode alpha, and using
        // one source type for both formats keeps the fixture honest about what
        // an uploaded photo looks like.
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void noWidthRequested_servesTheOriginalUntouched() throws Exception {
        byte[] source = image(1000, 500, "jpeg");

        ImageResizer.Resized out = ImageResizer.resize(source, "image/jpeg", null);

        assertThat(out.bytes()).isSameAs(source);
        assertThat(out.resized()).isFalse();
    }

    @Test
    void downscalesJpeg_preservingAspectRatio() throws Exception {
        byte[] source = image(1000, 500, "jpeg");

        ImageResizer.Resized out = ImageResizer.resize(source, "image/jpeg", 240);

        assertThat(out.resized()).isTrue();
        assertThat(out.contentType()).isEqualTo("image/jpeg");
        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getWidth()).isEqualTo(240);
        assertThat(decoded.getHeight()).isEqualTo(120); // 2:1 kept
        // The whole point of the feature: fewer bytes on the wire.
        assertThat(out.bytes().length).isLessThan(source.length);
    }

    @Test
    void downscalesPng() throws Exception {
        byte[] source = image(800, 800, "png");

        ImageResizer.Resized out = ImageResizer.resize(source, "image/png", 120);

        assertThat(out.resized()).isTrue();
        assertThat(decode(out.bytes()).getWidth()).isEqualTo(120);
        assertThat(out.contentType()).isEqualTo("image/png");
    }

    @Test
    void neverUpscales_anAlreadySmallImageIsServedAsIs() throws Exception {
        byte[] source = image(100, 100, "png");

        ImageResizer.Resized out = ImageResizer.resize(source, "image/png", 960);

        // Re-encoding to make it bigger costs bytes and quality for nothing.
        assertThat(out.bytes()).isSameAs(source);
        assertThat(out.resized()).isFalse();
    }

    @Test
    void anExactWidthMatchIsNotReEncoded() throws Exception {
        byte[] source = image(240, 240, "png");

        ImageResizer.Resized out = ImageResizer.resize(source, "image/png", 240);

        assertThat(out.bytes()).isSameAs(source);
        assertThat(out.resized()).isFalse();
    }

    @Test
    void webpIsServedUnresized_ratherThanRefused() {
        // The JDK has no WebP reader. Uploads accept WebP, so refusing here
        // would break images that are otherwise perfectly serveable — a
        // slightly heavy image beats a broken one.
        byte[] source = "RIFF....WEBPVP8 ".getBytes();

        ImageResizer.Resized out = ImageResizer.resize(source, "image/webp", 240);

        assertThat(out.bytes()).isSameAs(source);
        assertThat(out.resized()).isFalse();
    }

    @Test
    void anUndecodableImageFallsBackToTheOriginal_ratherThanErroring() {
        // A resize is an optimisation; failing it must never fail the read.
        byte[] junk = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};

        ImageResizer.Resized out = ImageResizer.resize(junk, "image/png", 120);

        assertThat(out.bytes()).isSameAs(junk);
        assertThat(out.resized()).isFalse();
    }

    @Test
    void anUnlistedWidthIsRefused() throws Exception {
        // Not a slow yes: an arbitrary ?w= lets one caller mint unlimited cache
        // entries and force a decode for each, from a public endpoint.
        byte[] source = image(1000, 500, "jpeg");

        assertThatThrownBy(() -> ImageResizer.resize(source, "image/jpeg", 137))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("120");
    }

    @Test
    void everyAllowedWidthActuallyWorks() throws Exception {
        byte[] source = image(2000, 1000, "jpeg");

        for (int w : ImageResizer.ALLOWED_WIDTHS) {
            ImageResizer.Resized out = ImageResizer.resize(source, "image/jpeg", w);
            assertThat(out.resized()).as("width %d", w).isTrue();
            assertThat(decode(out.bytes()).getWidth()).as("width %d", w).isEqualTo(w);
        }
    }

    @Test
    void aVeryWideImageStillProducesAtLeastOnePixelOfHeight() throws Exception {
        // 4000x3 at w=120 rounds the height to 0 without the Math.max guard,
        // and BufferedImage throws on a zero dimension — which the catch would
        // swallow into "serve the original", quietly disabling the resize.
        byte[] source = image(4000, 3, "png");

        ImageResizer.Resized out = ImageResizer.resize(source, "image/png", 120);

        assertThat(out.resized()).isTrue();
        assertThat(decode(out.bytes()).getHeight()).isGreaterThanOrEqualTo(1);
    }
}
