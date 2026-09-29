package com.innbucks.marketplaceservice.catalog;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;

/**
 * Reads an image's pixel dimensions from its HEADER, never its raster.
 *
 * <p>This is the guard in front of every decode. A small, highly compressible
 * PNG can declare 30000 x 30000 pixels in a few hundred KB, and decoding it is
 * ~3.6 GB of heap — so the only safe moment to learn how big an image is, is
 * before anything allocates for it. JPEG and PNG go through the JDK's own
 * reader with {@code setInput(.., seekForwardOnly, ignoreMetadata)} and stop at
 * {@code getWidth(0)}/{@code getHeight(0)}, which parse the SOF segment / IHDR
 * chunk and nothing else.
 *
 * <p><b>WebP is parsed by hand</b>, because the JDK ships no WebP reader and
 * this service deliberately adds no codec (see {@link ImageResizer}). WebP is
 * never decoded server-side, so the server is not what it protects — the
 * app and the portal are, since every client decodes the full raster to show
 * it. The three bitstream headers are fixed-offset fields, a few lines each:
 * {@code VP8 } (lossy, 14-bit sides after the {@code 9D 01 2A} start code),
 * {@code VP8L} (lossless, 14-bit sides minus one after the {@code 0x2F}
 * signature) and {@code VP8X} (extended, 24-bit canvas sides minus one).
 *
 * <p>Reads are always against an in-memory stream
 * ({@link MemoryCacheImageInputStream}), never
 * {@code ImageIO.createImageInputStream}, which with the default
 * {@code ImageIO.getUseCache() == true} spools the bytes to a temp file on
 * every call — disk I/O per public image request for bytes already in memory.
 *
 * <p>Pixel dimensions are not the whole memory story for a JPEG: a multi-scan
 * (e.g. progressive) one also costs a full-resolution native buffer however it
 * is subsampled — see {@link #jpegWholeImageBufferBytes(byte[])}.
 *
 * <p>Empty means "could not tell" (truncated, corrupt, or a format with no
 * reader). Callers decide what that means: upload accepts it (the image is
 * then never decoded here, because {@link ImageResizer} refuses to decode an
 * image whose header it cannot read), the resizer serves the original.
 */
public final class ImageDimensions {

    private ImageDimensions() {}

    /** Width and height in pixels, both positive. */
    public record Size(int width, int height) {
        public long pixels() {
            return (long) width * height;
        }
    }

    public static Optional<Size> read(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        if (isWebp(bytes)) {
            return webp(bytes);
        }
        return viaImageIo(bytes);
    }

    private static Optional<Size> viaImageIo(byte[] bytes) {
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return positive(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            // Corrupt or truncated header: "could not tell", never an error —
            // the caller's fallback is the safe one either way.
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------
    // JPEG: the whole-image coefficient buffer
    // ------------------------------------------------------------------

    /**
     * Bytes of NATIVE memory the JDK's JPEG reader must allocate before it
     * produces a single row of {@code jpeg}, or 0 when it needs no such buffer.
     *
     * <p>A single-pass JPEG (baseline/extended sequential, all components in
     * the first scan) decodes one MCU row at a time. A multi-scan one —
     * progressive ({@code SOF2} and friends), or sequential with a
     * non-interleaved first scan — cannot: libjpeg's
     * {@code has_multiple_scans} is set, the JDK reader switches to
     * buffered-image mode, and libjpeg allocates a coefficient array for the
     * WHOLE image at FULL resolution (128 bytes per 8x8 block per component,
     * in memory — the JDK's libjpeg has no backing store). Source subsampling
     * drops pixels after the IDCT, so it does not shrink that buffer: an
     * 8160 x 6120 4:2:0 progressive photo costs ~150 MB of native memory,
     * outside the heap, however small the requested width. The count here is
     * libjpeg's own ({@code jdcoefct.c}: each component's width/height in
     * blocks, rounded up to its sampling factor).
     *
     * <p>{@link Long#MAX_VALUE} when the markers cannot be walked to the first
     * scan — "could not tell" is treated as too big, so the caller serves the
     * original rather than guessing.
     */
    public static long jpegWholeImageBufferBytes(byte[] b) {
        if (b == null || b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
            return Long.MAX_VALUE;
        }
        int pos = 2;
        int sof = -1;
        int width = 0;
        int height = 0;
        int[] h = null;
        int[] v = null;
        while (pos + 2 <= b.length) {
            if ((b[pos] & 0xFF) != 0xFF) {
                return Long.MAX_VALUE;
            }
            int marker = b[pos + 1] & 0xFF;
            if (marker == 0xFF) {
                pos++; // fill byte
                continue;
            }
            pos += 2;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue; // standalone markers carry no length
            }
            if (marker == 0xD8 || marker == 0xD9 || pos + 2 > b.length) {
                return Long.MAX_VALUE;
            }
            int len = be16(b, pos);
            if (len < 2 || pos + len > b.length) {
                return Long.MAX_VALUE;
            }
            if (isSof(marker)) {
                int nf = len >= 8 ? b[pos + 7] & 0xFF : 0;
                if (nf == 0 || len < 8 + 3 * nf) {
                    return Long.MAX_VALUE;
                }
                sof = marker;
                height = be16(b, pos + 3);
                width = be16(b, pos + 5);
                h = new int[nf];
                v = new int[nf];
                for (int i = 0; i < nf; i++) {
                    int factors = b[pos + 8 + 3 * i + 1] & 0xFF;
                    h[i] = factors >> 4;
                    v[i] = factors & 0x0F;
                    if (h[i] < 1 || h[i] > 4 || v[i] < 1 || v[i] > 4) {
                        return Long.MAX_VALUE;
                    }
                }
            } else if (marker == 0xDA) {
                if (sof < 0 || len < 3 || width == 0 || height == 0) {
                    return Long.MAX_VALUE;
                }
                int componentsInFirstScan = b[pos + 2] & 0xFF;
                boolean singlePass = (sof == 0xC0 || sof == 0xC1) && componentsInFirstScan == h.length;
                return singlePass ? 0 : coefficientBufferBytes(width, height, h, v);
            }
            pos += len;
        }
        return Long.MAX_VALUE;
    }

    /** SOF0..SOF15, minus the three C-range markers that are not frames (DHT, JPG, DAC). */
    private static boolean isSof(int marker) {
        return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
    }

    private static long coefficientBufferBytes(int width, int height, int[] h, int[] v) {
        int hMax = 0;
        int vMax = 0;
        for (int i = 0; i < h.length; i++) {
            hMax = Math.max(hMax, h[i]);
            vMax = Math.max(vMax, v[i]);
        }
        long blocks = 0;
        for (int i = 0; i < h.length; i++) {
            long wide = roundUp(divRoundUp((long) width * h[i], 8L * hMax), h[i]);
            long high = roundUp(divRoundUp((long) height * v[i], 8L * vMax), v[i]);
            blocks += wide * high;
        }
        return blocks * 128; // DCTSIZE2 (64) coefficients of 2 bytes per block
    }

    private static long divRoundUp(long a, long b) {
        return (a + b - 1) / b;
    }

    private static long roundUp(long a, long multiple) {
        return divRoundUp(a, multiple) * multiple;
    }

    private static int be16(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    // ------------------------------------------------------------------
    // WebP: RIFF container, first chunk at offset 12 names the bitstream.
    // https://developers.google.com/speed/webp/docs/riff_container
    // ------------------------------------------------------------------

    private static boolean isWebp(byte[] b) {
        return b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    private static Optional<Size> webp(byte[] b) {
        if (b.length < 30) {
            return Optional.empty();
        }
        // Chunk payload starts at 20 (12 RIFF header + 4 fourcc + 4 size).
        if (fourcc(b, 12, "VP8 ")) {
            // 3-byte frame tag, then the key-frame start code 9D 01 2A, then
            // 2 bytes each of width/height — low 14 bits are the size, top 2
            // the upscaling hint.
            if ((b[23] & 0xFF) != 0x9D || (b[24] & 0xFF) != 0x01 || (b[25] & 0xFF) != 0x2A) {
                return Optional.empty();
            }
            return positive(le16(b, 26) & 0x3FFF, le16(b, 28) & 0x3FFF);
        }
        if (fourcc(b, 12, "VP8L")) {
            if ((b[20] & 0xFF) != 0x2F) {
                return Optional.empty();
            }
            long bits = (b[21] & 0xFFL) | (b[22] & 0xFFL) << 8 | (b[23] & 0xFFL) << 16 | (b[24] & 0xFFL) << 24;
            return positive((int) (bits & 0x3FFF) + 1, (int) ((bits >> 14) & 0x3FFF) + 1);
        }
        if (fourcc(b, 12, "VP8X")) {
            // 1 flag byte + 3 reserved, then 24-bit canvas width-1, height-1.
            return positive(le24(b, 24) + 1, le24(b, 27) + 1);
        }
        return Optional.empty();
    }

    private static boolean fourcc(byte[] b, int at, String code) {
        for (int i = 0; i < 4; i++) {
            if (b[at + i] != code.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static int le24(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16;
    }

    private static Optional<Size> positive(int width, int height) {
        return width > 0 && height > 0 ? Optional.of(new Size(width, height)) : Optional.empty();
    }
}
