package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.SampleModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;

/**
 * Downscales a stored catalogue image on read, so a 3MB poster stops being
 * shipped into a 120px grid tile on metered mobile data.
 *
 * <p><b>Resize on READ, not a stored thumbnail.</b> A {@code thumbnail_bytes}
 * column would be faster per request but leaves every image already in the
 * catalogue without one until a backfill runs — this works for the whole
 * existing gallery the moment it deploys, needs no migration, and cannot drift
 * from the original. The cost is CPU per miss, which the endpoint's existing
 * 1-hour public cache absorbs: the query string is part of the cache key, so
 * each (image, width) pair is decoded once per hour at most.
 *
 * <p><b>Widths are an ALLOW-LIST, deliberately.</b> An arbitrary {@code ?w=}
 * lets one caller mint unlimited distinct cache entries and force a decode for
 * each — CPU amplification and cache poisoning from a public, unauthenticated
 * endpoint. Four sizes cover a grid tile, a card, a detail view and a
 * lightbox; anything else is a 400 rather than a slow yes.
 *
 * <p><b>WebP passes through unresized.</b> The JDK ships no WebP reader (JPEG
 * and PNG writers exist; {@code ImageIO.getImageReadersByFormatName("webp")} is
 * empty on 21), and pulling in a native WebP codec to shrink a thumbnail is a
 * new dependency and a new CVE surface for a medium-priority saving. Uploads
 * still accept WebP, so those images serve at full size — callers get the
 * original bytes and the {@code X-Image-Resized: false} header rather than an
 * error, because a slightly heavy image beats a broken one.
 *
 * <p><b>The header is read BEFORE anything is decoded</b>
 * ({@link ImageDimensions}), because this endpoint is public and the decode is
 * what an attacker would aim at: a few-hundred-KB PNG can declare a raster of
 * gigabytes. An image over the {@link ImagePixelBudget}, or one whose header
 * cannot be read, is served as the ORIGINAL bytes and never decoded. Upload
 * refuses over-budget images now, so those can only be rows stored before the
 * guard; serving them unresized keeps those listings working (a heavy
 * image beats a broken one, again) while the server spends no memory on them —
 * the cost moves to the client that asked, which is where it was before
 * {@code ?w=} existed.
 *
 * <p><b>Decodes at reduced resolution, inside a byte ceiling.</b> A 4000 px
 * photo requested at 240 px is read with source subsampling
 * ({@link ImageReadParam#setSourceSubsampling}) to about twice the target
 * width, so the raster held is a few MB rather than the full 48 MB; the last
 * step is a bilinear {@link Graphics2D} downscale, halving progressively, which
 * comes close to {@code SCALE_SMOOTH}'s area averaging at a fraction of its CPU
 * (subsampling itself is point sampling, which is why it stops at about twice
 * the target). The decoded raster may not exceed {@link #DECODE_CEILING_BYTES},
 * counted at the reader's OWN bytes per pixel (a 16-bit RGBA PNG decodes at 8,
 * not 4): past it the resizer subsamples harder, down to just above the
 * target, and if even that would exceed it, serves the original.
 *
 * <p><b>Subsampling does not help a multi-scan JPEG.</b> A progressive JPEG
 * makes libjpeg allocate a coefficient buffer for the whole image at full
 * resolution, in native memory, before any subsampling applies
 * ({@link ImageDimensions#jpegWholeImageBufferBytes}); one over the same
 * ceiling is served as the original. Most progressive JPEGs are web exports of
 * a few MP and still resize; a 12 MP progressive photo does not.
 *
 * <p><b>At most {@link ImageDecodePermits} decodes at once, never waiting.</b>
 * Each decode is bounded, but the endpoint is public, and a burst on one large
 * image would otherwise add the bounds up (and pin every core — a JPEG is
 * entropy-decoded and IDCT'd in full however it is subsampled). With no permit
 * free the ORIGINAL is served, marked uncacheable ({@link Resized#cacheable()}),
 * so the busy moment is not frozen into the public cache for an hour.
 */
@Slf4j
public final class ImageResizer {

    /** The only widths that may be requested. See the class note on why. */
    public static final Set<Integer> ALLOWED_WIDTHS = Set.of(120, 240, 480, 960);

    /**
     * The most bytes any single decode may hold — the decoded raster, and for a
     * multi-scan JPEG libjpeg's native whole-image buffer. 24 MiB is sized to
     * the largest output: at w=960 the decode keeps ~1920 px, and a 3:4
     * portrait at 1920 x 2560 is ~20 MB at 4 bytes per pixel. With
     * {@link ImageDecodePermits#DEFAULT_PERMITS} decodes at once that is a
     * worst case of tens of MB against a ~450 MiB heap, not hundreds.
     */
    static final long DECODE_CEILING_BYTES = 24L * 1024 * 1024;

    /** Assumed when a reader will not say what it decodes to: the widest common type. */
    private static final int UNKNOWN_BYTES_PER_PIXEL = 8;

    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";

    /** Shared by the overloads built without Spring; production passes the configured bean. */
    private static final ImageDecodePermits DEFAULT_PERMITS = ImageDecodePermits.defaults();

    private ImageResizer() {}

    /** {@link #resize(byte[], String, Integer, ImagePixelBudget, ImageDecodePermits)} at the production defaults. */
    public static Resized resize(byte[] source, String contentType, Integer width) {
        return resize(source, contentType, width, ImagePixelBudget.defaults());
    }

    /** At the default decode concurrency. */
    public static Resized resize(byte[] source, String contentType, Integer width, ImagePixelBudget budget) {
        return resize(source, contentType, width, budget, DEFAULT_PERMITS);
    }

    /**
     * @param width requested width; {@code null} means "serve the original".
     * @param budget the cell's pixel budget; an image over it is never decoded.
     * @param permits the cell's decode concurrency; none free = the original.
     * @return the original bytes when no resize is requested, the source is not
     *         a decodable raster, is over the budget or the decode ceiling, is
     *         already narrower than the request, or no decode permit is free.
     */
    public static Resized resize(byte[] source, String contentType, Integer width,
                                 ImagePixelBudget budget, ImageDecodePermits permits) {
        if (width == null) {
            return Resized.original(source, contentType);
        }
        if (!ALLOWED_WIDTHS.contains(width)) {
            throw ApiException.badRequest("unsupported_image_width",
                    "Width must be one of " + ALLOWED_WIDTHS.stream().sorted().toList());
        }
        boolean jpeg = JPEG.equalsIgnoreCase(contentType);
        if (!jpeg && !PNG.equalsIgnoreCase(contentType)) {
            // WebP (and anything else that ever gets allow-listed on upload):
            // no decoder, so honour the request with the full-size original
            // rather than 415-ing a perfectly good image.
            return Resized.original(source, contentType);
        }
        // Header FIRST: nothing below allocates for a raster until we know how
        // big it is. See the class note.
        Optional<ImageDimensions.Size> header = ImageDimensions.read(source);
        if (header.isEmpty()) {
            // Passed the upload magic-byte check but its header cannot be read.
            // Serving the original keeps the endpoint's behaviour unchanged for
            // a caller who merely asked for a smaller copy.
            log.warn("Image header unreadable, serving original contentType={} bytes={}",
                    contentType, source.length);
            return Resized.original(source, contentType);
        }
        ImageDimensions.Size size = header.get();
        if (!budget.admits(size)) {
            log.warn("Image over the pixel budget, serving original unresized width={} height={} bytes={}",
                    size.width(), size.height(), source.length);
            return Resized.original(source, contentType);
        }
        if (size.width() <= width) {
            // Never upscale: it costs a re-encode and adds bytes to make
            // the picture worse.
            return Resized.original(source, contentType);
        }
        if (jpeg) {
            long wholeImageBuffer = ImageDimensions.jpegWholeImageBufferBytes(source);
            if (wholeImageBuffer > DECODE_CEILING_BYTES) {
                log.warn("Multi-scan JPEG would need a whole-image buffer over the decode ceiling, "
                        + "serving original width={} height={} bufferBytes={}",
                        size.width(), size.height(), wholeImageBuffer);
                return Resized.original(source, contentType);
            }
        }
        if (!permits.tryAcquire()) {
            // Not a warning: this is the limiter doing its job. Uncacheable, so
            // the next request (once the burst passes) gets the real thumbnail.
            log.debug("Image decode permits exhausted, serving original width={} height={}",
                    size.width(), size.height());
            return Resized.busy(source, contentType);
        }
        try {
            BufferedImage image = decode(source, size, width);
            if (image == null) {
                return Resized.original(source, contentType);
            }
            int height = Math.max(1, Math.round(
                    size.height() * (width / (float) size.width())));
            BufferedImage scaled = render(image, width, height, contentType);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String format = jpeg ? "jpeg" : "png";
            if (!ImageIO.write(scaled, format, out)) {
                log.warn("No ImageIO writer for format={} — serving original", format);
                return Resized.original(source, contentType);
            }
            return new Resized(out.toByteArray(), contentType, true, true);
        } catch (IOException | RuntimeException ex) {
            // A resize is an optimisation. Failing it must never fail the read.
            log.warn("Image resize failed, serving original contentType={} width={} reason={}",
                    contentType, width, ex.toString());
            return Resized.original(source, contentType);
        } finally {
            permits.release();
        }
    }

    /**
     * Source subsampling for a decode that ends at {@code target} px wide, at
     * {@code bytesPerPixel} of decoded raster. Prefers keeping about twice the
     * target (so the final filtered downscale has real pixels to average —
     * subsampling itself just drops them), then subsamples harder one step at a
     * time while the raster would exceed {@link #DECODE_CEILING_BYTES}, never
     * below the target width; returns 0 when even that would exceed it.
     */
    static int subsampling(ImageDimensions.Size size, int target, int bytesPerPixel) {
        int fine = Math.max(1, size.width() / (2 * target));
        int coarse = Math.max(fine, size.width() / target);
        for (int s = fine; s <= coarse; s++) {
            if (decodedPixels(size, s) * bytesPerPixel <= DECODE_CEILING_BYTES) {
                return s;
            }
        }
        return 0;
    }

    private static long decodedPixels(ImageDimensions.Size size, int subsampling) {
        long w = (size.width() + subsampling - 1) / subsampling;
        long h = (size.height() + subsampling - 1) / subsampling;
        return w * h;
    }

    /**
     * Bytes per pixel of the raster {@code reader} will decode into by default
     * (the first of {@code getImageTypes}, which is what {@code read} uses when
     * no destination is set) — read from its sample model, so a 16-bit PNG
     * counts 2 bytes per sample. Unknown = {@value #UNKNOWN_BYTES_PER_PIXEL}.
     */
    static int bytesPerPixel(ImageReader reader) throws IOException {
        Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        if (types == null || !types.hasNext()) {
            return UNKNOWN_BYTES_PER_PIXEL;
        }
        SampleModel model = types.next().getSampleModel(1, 1);
        int bits = DataBuffer.getDataTypeSize(model.getDataType()) * model.getNumDataElements();
        return Math.max(1, (bits + 7) / 8);
    }

    /** @return the subsampled raster, or null (logged) when it cannot or may not be decoded. */
    private static BufferedImage decode(byte[] source, ImageDimensions.Size size, int target) throws IOException {
        // In-memory stream, never ImageIO.createImageInputStream — see
        // ImageDimensions on the temp-file cache.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                log.warn("Image could not be decoded for resize: no reader bytes={}", source.length);
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int bytesPerPixel = bytesPerPixel(reader);
                int subsampling = subsampling(size, target, bytesPerPixel);
                if (subsampling == 0) {
                    log.warn("Image decode would exceed the decode ceiling, serving original "
                            + "width={} height={} bytesPerPixel={}", size.width(), size.height(), bytesPerPixel);
                    return null;
                }
                ImageReadParam param = reader.getDefaultReadParam();
                if (subsampling > 1) {
                    param.setSourceSubsampling(subsampling, subsampling, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    log.warn("Image could not be decoded for resize bytes={}", source.length);
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private static BufferedImage render(BufferedImage image, int width, int height, String contentType) {
        // JPEG has no alpha channel: writing a TYPE_INT_ARGB raster as JPEG
        // produces the notorious pink/inverted image rather than an error, so
        // the target type has to follow the OUTPUT format, not the input.
        int type = JPEG.equalsIgnoreCase(contentType)
                ? BufferedImage.TYPE_INT_RGB
                : BufferedImage.TYPE_INT_ARGB;
        // Progressive halving: one bilinear step samples only 2x2 source
        // pixels, so a single jump of more than 2x skips pixels and aliases.
        // Halving until within 2x of the target averages every pixel, at the
        // cost of one or two small intermediate rasters.
        BufferedImage current = image;
        int w = image.getWidth();
        int h = image.getHeight();
        while (w / 2 >= width && h / 2 >= height) {
            w /= 2;
            h /= 2;
            current = scale(current, w, h, type);
        }
        // The last step always runs on the decoded raster itself, so the
        // output type follows the output format even when no halving did.
        if (current == image || w != width || h != height) {
            current = scale(current, width, height, type);
        }
        return current;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height, int type) {
        BufferedImage target = new BufferedImage(width, height, type);
        Graphics2D g = target.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /**
     * Result of a resize attempt; {@code resized} is false when the original is
     * served. {@code cacheable} is false only when the original was served
     * because no decode permit was free — a transient answer the public cache
     * must not keep for an hour under a thumbnail URL.
     */
    public record Resized(byte[] bytes, String contentType, boolean resized, boolean cacheable) {
        static Resized original(byte[] bytes, String contentType) {
            return new Resized(bytes, contentType, false, true);
        }

        static Resized busy(byte[] bytes, String contentType) {
            return new Resized(bytes, contentType, false, false);
        }
    }
}
