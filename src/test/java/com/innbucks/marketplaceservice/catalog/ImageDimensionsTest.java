package com.innbucks.marketplaceservice.catalog;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The header reader in front of every decode. The oversized cases are
 * hand-built ({@link HeaderOnlyImages}) and carry no pixel data, so no decode
 * could report their sizes: the JDK refuses a raster past 2 GB, and there is
 * nothing to decode anyway. A passing assertion therefore proves the size came
 * from the header alone.
 */
class ImageDimensionsTest {

    private static byte[] real(int width, int height, String format) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    void readsRealJpegAndPngHeaders() throws Exception {
        assertThat(ImageDimensions.read(real(640, 480, "jpeg")))
                .contains(new ImageDimensions.Size(640, 480));
        assertThat(ImageDimensions.read(real(33, 700, "png")))
                .contains(new ImageDimensions.Size(33, 700));
    }

    @Test
    void readsABombPngFromItsHeaderAlone() {
        byte[] bomb = HeaderOnlyImages.png(30_000, 30_000);

        assertThat(bomb.length).isLessThan(100);
        assertThat(ImageDimensions.read(bomb)).contains(new ImageDimensions.Size(30_000, 30_000));
        assertThat(ImageDimensions.read(bomb).orElseThrow().pixels()).isEqualTo(900_000_000L);
    }

    @Test
    void readsAHugeJpegFromItsSof0Segment() {
        assertThat(ImageDimensions.read(HeaderOnlyImages.jpeg(60_000, 40_000)))
                .contains(new ImageDimensions.Size(60_000, 40_000));
    }

    @Test
    void readsAllThreeWebpBitstreamHeaders() {
        // The JDK has no WebP reader, so these are parsed by hand.
        assertThat(ImageDimensions.read(HeaderOnlyImages.webpLossy(16_383, 9_000)))
                .contains(new ImageDimensions.Size(16_383, 9_000));
        assertThat(ImageDimensions.read(HeaderOnlyImages.webpLossless(16_384, 1)))
                .contains(new ImageDimensions.Size(16_384, 1));
        assertThat(ImageDimensions.read(HeaderOnlyImages.webpExtended(1 << 24, 20_000)))
                .contains(new ImageDimensions.Size(1 << 24, 20_000));
    }

    @Test
    void anUnreadableHeaderIsUnknown_neverAGuessNorAnError() {
        assertThat(ImageDimensions.read(null)).isEmpty();
        assertThat(ImageDimensions.read(new byte[0])).isEmpty();
        assertThat(ImageDimensions.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8})).isEmpty();
        // Magic bytes only — what the upload signature check alone accepts.
        assertThat(ImageDimensions.read(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7})).isEmpty();
        assertThat(ImageDimensions.read("RIFF....WEBPVP8 ".getBytes())).isEmpty();
        // Truncated inside the header.
        byte[] png = HeaderOnlyImages.png(100, 100);
        assertThat(ImageDimensions.read(Arrays.copyOf(png, 20))).isEmpty();
        byte[] webp = HeaderOnlyImages.webpLossy(100, 100);
        assertThat(ImageDimensions.read(Arrays.copyOf(webp, 27))).isEmpty();
    }

    @Test
    void aLossyWebpWithoutItsStartCodeIsUnknown() {
        byte[] webp = HeaderOnlyImages.webpLossy(100, 100);
        webp[23] = 0; // corrupt 9D 01 2A

        assertThat(ImageDimensions.read(webp)).isEmpty();
    }

    // ------------------------------------------------------------------
    // JPEG whole-image coefficient buffer (progressive / multi-scan)
    // ------------------------------------------------------------------

    @Test
    void aSinglePassJpegNeedsNoWholeImageBuffer() throws Exception {
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(HeaderOnlyImages.jpeg(8160, 6120))).isZero();
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(real(640, 480, "jpeg"))).isZero();
    }

    @Test
    void aProgressiveJpegCostsLibjpegsFullResolutionBuffer() {
        // 8160 x 6120 4:2:0 = Y 1020 x 766 blocks + 2 x (510 x 383) chroma
        // blocks = 1,171,980 blocks x 128 bytes: ~150 MB of native memory,
        // whatever width is requested.
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(HeaderOnlyImages.progressiveJpeg(8160, 6120)))
                .isEqualTo(1_171_980L * 128);
    }

    @Test
    void aSequentialJpegWithANonInterleavedFirstScanIsMultiScanToo() {
        // Baseline SOF0, but the first scan carries one component of three:
        // libjpeg's has_multiple_scans, the same full buffer as progressive.
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(HeaderOnlyImages.jpeg(8160, 6120, 0xC0, 1)))
                .isEqualTo(1_171_980L * 128);
    }

    @Test
    void aRealProgressiveJpegIsRecognised() throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
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

        assertThat(ImageDimensions.jpegWholeImageBufferBytes(out.toByteArray())).isPositive();
    }

    @Test
    void jpegMarkersThatCannotBeWalkedAreTooBig_neverZero() {
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(null)).isEqualTo(Long.MAX_VALUE);
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(new byte[] {(byte) 0xFF, (byte) 0xD8}))
                .isEqualTo(Long.MAX_VALUE);
        byte[] truncated = Arrays.copyOf(HeaderOnlyImages.progressiveJpeg(4000, 3000), 12);
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(truncated)).isEqualTo(Long.MAX_VALUE);
        assertThat(ImageDimensions.jpegWholeImageBufferBytes(HeaderOnlyImages.png(10, 10)))
                .isEqualTo(Long.MAX_VALUE);
    }
}
