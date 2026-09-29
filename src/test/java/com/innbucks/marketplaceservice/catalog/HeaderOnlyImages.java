package com.innbucks.marketplaceservice.catalog;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Hand-built image files that DECLARE arbitrary pixel dimensions in a few
 * dozen bytes — the shape of a decompression bomb, without ever allocating
 * the raster. A test that produced a 30000 x 30000 image through
 * {@code BufferedImage} would itself need ~3.6 GB of heap, which is exactly
 * the failure under test.
 *
 * <p>Each file is structurally valid up to and including the header a
 * dimension reader looks at (PNG: signature + IHDR with a correct CRC; JPEG:
 * SOI + SOF0 + SOS; WebP: RIFF + the bitstream header), and carries no real
 * pixel data after it.
 */
final class HeaderOnlyImages {

    private HeaderOnlyImages() {}

    /** PNG signature + IHDR (8-bit RGBA) + a minimal IDAT + IEND, CRCs correct. */
    static byte[] png(int width, int height) {
        return png(width, height, 8);
    }

    /** As {@link #png(int, int)}, RGBA at {@code bitDepth} (8 or 16) bits per sample. */
    static byte[] png(int width, int height, int bitDepth) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        ByteBuffer ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        ihdr.putInt(width).putInt(height)
                .put((byte) bitDepth)
                .put((byte) 6)   // colour type: RGBA
                .put((byte) 0)   // compression
                .put((byte) 0)   // filter
                .put((byte) 0);  // interlace
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", emptyZlib());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** SOI + baseline SOF0 (3 components, 4:2:0) + SOS + EOI. No tables, no scan data. */
    static byte[] jpeg(int width, int height) {
        return jpeg(width, height, 0xC0, 3);
    }

    /** SOI + progressive SOF2 (3 components, 4:2:0) + SOS + EOI — the multi-scan shape. */
    static byte[] progressiveJpeg(int width, int height) {
        return jpeg(width, height, 0xC2, 1);
    }

    /**
     * SOI + an SOF of type {@code sofMarker} (3 components, 4:2:0) + an SOS
     * naming the first {@code componentsInFirstScan} of them + EOI.
     */
    static byte[] jpeg(int width, int height, int sofMarker, int componentsInFirstScan) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});                        // SOI
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) sofMarker, 0x00, 0x11, 0x08});  // SOFn, len 17, 8-bit
        out.writeBytes(new byte[] {(byte) (height >> 8), (byte) height, (byte) (width >> 8), (byte) width});
        out.writeBytes(new byte[] {0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01});
        int ns = componentsInFirstScan;
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xDA, 0x00, (byte) (6 + 2 * ns), (byte) ns}); // SOS
        for (int i = 1; i <= ns; i++) {
            out.writeBytes(new byte[] {(byte) i, (byte) (i == 1 ? 0x00 : 0x11)});
        }
        out.writeBytes(new byte[] {0x00, 0x3F, 0x00});
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD9});                        // EOI
        return out.toByteArray();
    }

    /** Lossy WebP: {@code VP8 } chunk, key-frame start code, 14-bit sides. */
    static byte[] webpLossy(int width, int height) {
        byte[] payload = new byte[10];
        payload[3] = (byte) 0x9D;
        payload[4] = 0x01;
        payload[5] = 0x2A;
        payload[6] = (byte) width;
        payload[7] = (byte) ((width >> 8) & 0x3F);
        payload[8] = (byte) height;
        payload[9] = (byte) ((height >> 8) & 0x3F);
        return riff("VP8 ", payload);
    }

    /** Lossless WebP: {@code VP8L} chunk, 0x2F signature, 14-bit sides minus one. */
    static byte[] webpLossless(int width, int height) {
        long bits = (width - 1L) | (height - 1L) << 14;
        byte[] payload = {0x2F, (byte) bits, (byte) (bits >> 8), (byte) (bits >> 16), (byte) (bits >> 24),
                0, 0, 0, 0, 0};
        return riff("VP8L", payload);
    }

    /** Extended WebP: {@code VP8X} chunk, 24-bit canvas sides minus one. */
    static byte[] webpExtended(int width, int height) {
        int w = width - 1;
        int h = height - 1;
        byte[] payload = {0, 0, 0, 0,
                (byte) w, (byte) (w >> 8), (byte) (w >> 16),
                (byte) h, (byte) (h >> 8), (byte) (h >> 16)};
        return riff("VP8X", payload);
    }

    private static byte[] riff(String fourcc, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(20 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(12 + payload.length)
                .put("WEBP".getBytes(StandardCharsets.US_ASCII))
                .put(fourcc.getBytes(StandardCharsets.US_ASCII)).putInt(payload.length)
                .put(payload);
        return b.array();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] emptyZlib() {
        Deflater deflater = new Deflater();
        deflater.finish();
        byte[] buf = new byte[64];
        int n = deflater.deflate(buf);
        deflater.end();
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
