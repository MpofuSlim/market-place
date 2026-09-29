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

    // ------------------------------------------------------------------
    // Pixel budget: the header is read BEFORE anything is decoded
    // ------------------------------------------------------------------

    @Test
    void aStoredBombIsServedAsTheOriginal() {
        // 30000 x 30000 declared in < 100 bytes. Rows stored before the upload
        // guard can look like this. NOTE this alone does not prove the header
        // check ran first: the pre-guard code also served these as originals,
        // because the JDK refuses a >2 GB raster with an exception it caught.
        // The ORDER is proven by the tight-budget test below, on an image
        // that genuinely decodes.
        byte[] bomb = HeaderOnlyImages.png(30_000, 30_000);

        ImageResizer.Resized out = ImageResizer.resize(bomb, "image/png", 240);

        assertThat(out.bytes()).isSameAs(bomb);
        assertThat(out.resized()).isFalse();
        assertThat(out.contentType()).isEqualTo("image/png");

        byte[] jpegBomb = HeaderOnlyImages.jpeg(60_000, 60_000);
        assertThat(ImageResizer.resize(jpegBomb, "image/jpeg", 960).bytes()).isSameAs(jpegBomb);
    }

    @Test
    void theBudgetIsCheckedBeforeTheDecode_aDecodableImageOverItIsNotResized() throws Exception {
        // Proof of ORDER rather than of failure: this image resizes fine under
        // the default budget (next assertion), so the only thing that can stop
        // it under a tight budget is the header check, ahead of any decode.
        byte[] source = image(800, 600, "png");
        assertThat(ImageResizer.resize(source, "image/png", 240).resized()).isTrue();

        ImageResizer.Resized out = ImageResizer.resize(source, "image/png", 240,
                new ImagePixelBudget(100_000L, 8192));

        assertThat(out.bytes()).isSameAs(source);
        assertThat(out.resized()).isFalse();
        assertThat(ImageResizer.resize(source, "image/png", 240, new ImagePixelBudget(50_000_000L, 700))
                .resized()).as("the side limit alone also stops it").isFalse();
    }

    @Test
    void aLargePhotoIsDecodedAtReducedResolution_andStillComesOutExact() throws Exception {
        // 3000 x 2000 at w=120: subsampled 12x on read, then filtered down.
        byte[] source = image(3000, 2000, "jpeg");

        ImageResizer.Resized out = ImageResizer.resize(source, "image/jpeg", 120);

        assertThat(out.resized()).isTrue();
        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getWidth()).isEqualTo(120);
        assertThat(decoded.getHeight()).isEqualTo(80);
    }

    @Test
    void theFilteredDownscaleAveragesRatherThanPointSamples() throws Exception {
        // One-pixel black/white stripes: a nearest-neighbour or single-jump
        // bilinear scale lands on whichever stripe the sample hits; the
        // progressive halving lands near mid-grey everywhere. 400 px at w=120
        // is under 4x the target, so no source subsampling happens and this
        // isolates the filter. (Subsampling itself IS point sampling — which
        // is why it stops at about twice the target and leaves the last step
        // to this filter.)
        BufferedImage img = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 400; x++) {
            for (int y = 0; y < 400; y++) {
                img.setRGB(x, y, x % 2 == 0 ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);

        BufferedImage out = decode(ImageResizer.resize(bytes.toByteArray(), "image/png", 120).bytes());

        int grey = out.getRGB(60, 60) & 0xFF;
        assertThat(grey).isBetween(96, 160);
    }

    @Test
    void subsampling_keepsAboutTwiceTheTargetAndNeverGoesBelowIt() {
        // 12 MP phone photo at w=240: read at 504 x 378, not 4032 x 3024.
        assertThat(ImageResizer.subsampling(new ImageDimensions.Size(4032, 3024), 240, 4)).isEqualTo(8);
        // Less than 4x the target: no subsampling.
        assertThat(ImageResizer.subsampling(new ImageDimensions.Size(1000, 750), 480, 4)).isEqualTo(1);
        // 50 MP full-res mode at the largest width: 2040 x 1530.
        assertThat(ImageResizer.subsampling(new ImageDimensions.Size(8160, 6120), 960, 4)).isEqualTo(4);
    }

    @Test
    void subsampling_aTallStripFallsBackToJustAboveTheTarget_orIsRefused() {
        // 3839 x 8192 at 960: the 2x-target decode (full size, 126 MB) and
        // the next step (1920 x 4096, 31 MB) are over the 24 MiB ceiling, so
        // it is read at 1280 x 2731 instead.
        assertThat(ImageResizer.subsampling(new ImageDimensions.Size(3839, 8192), 960, 4)).isEqualTo(3);
        // A strip too narrow to subsample and too tall for the ceiling (only
        // reachable on a cell that raised image-max-side): 0 = serve original.
        assertThat(ImageResizer.subsampling(new ImageDimensions.Size(1900, 9000), 960, 4)).isZero();
    }

    @Test
    void subsampling_stepsUpOneAtATime_soAPortraitStillGetsAFilteredLastStep() {
        // 12 MP portrait at w=960, JPEG (3 bytes per pixel): full size is 36 MB,
        // so it is read at 1512 x 2016 (2x), not point-sampled straight to 3x.
        assertThat(ImageResizer.subsampling(new ImageDimensions.Size(3024, 4032), 960, 3)).isEqualTo(2);
    }

    @Test
    void subsampling_countsTheReadersOwnBytesPerPixel() {
        // 1900 x 3000 at w=960 cannot be subsampled at all (it is under 2x the
        // target). At 4 bytes per pixel that is 22.8 MB, inside the ceiling; the
        // same header as a 16-bit RGBA PNG decodes at 8 bytes, 45.6 MB, and is
        // refused. Assuming 4 for everything was the hole.
        ImageDimensions.Size size = new ImageDimensions.Size(1900, 3000);
        assertThat(ImageResizer.subsampling(size, 960, 4)).isEqualTo(1);
        assertThat(ImageResizer.subsampling(size, 960, 8)).isZero();
    }

    @Test
    void bytesPerPixel_isReadFromTheReader_so16BitPngsCountDouble() throws Exception {
        assertThat(bytesPerPixel(HeaderOnlyImages.png(1900, 3000, 16))).isEqualTo(8);
        assertThat(bytesPerPixel(HeaderOnlyImages.png(1900, 3000, 8))).isEqualTo(4);
        assertThat(bytesPerPixel(image(40, 30, "jpeg"))).isEqualTo(3);
    }

    private static int bytesPerPixel(byte[] bytes) throws Exception {
        try (var in = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            javax.imageio.ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in, true, true);
                return ImageResizer.bytesPerPixel(reader);
            } finally {
                reader.dispose();
            }
        }
    }

    // ------------------------------------------------------------------
    // Progressive JPEG: libjpeg's full-resolution native buffer
    // ------------------------------------------------------------------

    private static byte[] progressive(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    @Test
    void aLargeProgressiveJpegIsServedAsTheOriginal_itsBufferIgnoresSubsampling() throws Exception {
        // 3400 x 2600 (8.8 MP) progressive: well inside the pixel budget, and a
        // w=240 decode subsampled 7x would be tiny — but libjpeg would first
        // allocate a ~27 MB whole-image coefficient buffer at full resolution,
        // over the 24 MiB ceiling. This one genuinely decodes (the next test),
        // so serving it as the original proves the check ran before a decode.
        byte[] source = progressive(3400, 2600);
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(source))
                .isGreaterThan(ImageResizer.DECODE_CEILING_BYTES);

        ImageResizer.Resized out = ImageResizer.resize(source, "image/jpeg", 240);

        assertThat(out.bytes()).isSameAs(source);
        assertThat(out.resized()).isFalse();
        assertThat(out.cacheable()).isTrue();

        byte[] handBuilt = HeaderOnlyImages.progressiveJpeg(8160, 6120);
        assertThat(ImageResizer.resize(handBuilt, "image/jpeg", 960).bytes()).isSameAs(handBuilt);
    }

    @Test
    void aSmallProgressiveJpegStillResizes() throws Exception {
        byte[] source = progressive(1200, 900);

        ImageResizer.Resized out = ImageResizer.resize(source, "image/jpeg", 240);

        assertThat(out.resized()).isTrue();
        BufferedImage decoded = decode(out.bytes());
        assertThat(decoded.getWidth()).isEqualTo(240);
        assertThat(decoded.getHeight()).isEqualTo(180);
    }

    // ------------------------------------------------------------------
    // Decode concurrency
    // ------------------------------------------------------------------

    @Test
    void withNoDecodePermitFree_theOriginalIsServed_uncacheable_andNothingWaits() throws Exception {
        byte[] source = image(800, 600, "png");
        ImageDecodePermits permits = new ImageDecodePermits(1);
        assertThat(permits.tryAcquire()).isTrue(); // another request is decoding

        ImageResizer.Resized busy = ImageResizer.resize(source, "image/png", 240,
                ImagePixelBudget.defaults(), permits);

        assertThat(busy.bytes()).isSameAs(source);
        assertThat(busy.resized()).isFalse();
        assertThat(busy.cacheable()).as("a busy answer must not be cached for an hour").isFalse();

        permits.release();
        ImageResizer.Resized after = ImageResizer.resize(source, "image/png", 240,
                ImagePixelBudget.defaults(), permits);
        assertThat(after.resized()).isTrue();
        assertThat(after.cacheable()).isTrue();
        assertThat(permits.tryAcquire()).as("the resize released its permit").isTrue();
    }

    @Test
    void aFailedDecodeStillReleasesItsPermit() {
        // Header says 800 x 600 but there is no pixel data: the decode throws.
        byte[] broken = HeaderOnlyImages.png(800, 600);
        ImageDecodePermits permits = new ImageDecodePermits(1);

        ImageResizer.Resized out = ImageResizer.resize(broken, "image/png", 240,
                ImagePixelBudget.defaults(), permits);

        assertThat(out.bytes()).isSameAs(broken);
        assertThat(permits.tryAcquire()).isTrue();
    }
}
