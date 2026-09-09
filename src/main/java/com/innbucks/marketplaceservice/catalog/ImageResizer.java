package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 */
@Slf4j
public final class ImageResizer {

    /** The only widths that may be requested. See the class note on why. */
    public static final Set<Integer> ALLOWED_WIDTHS = Set.of(120, 240, 480, 960);

    private static final String JPEG = "image/jpeg";
    private static final String PNG = "image/png";

    private ImageResizer() {}

    /**
     * @param width requested width; {@code null} means "serve the original".
     * @return the original bytes when no resize is requested, the source is not
     *         a decodable raster, or it is already narrower than the request.
     */
    public static Resized resize(byte[] source, String contentType, Integer width) {
        if (width == null) {
            return Resized.original(source, contentType);
        }
        if (!ALLOWED_WIDTHS.contains(width)) {
            throw ApiException.badRequest("unsupported_image_width",
                    "Width must be one of " + ALLOWED_WIDTHS.stream().sorted().toList());
        }
        if (!JPEG.equalsIgnoreCase(contentType) && !PNG.equalsIgnoreCase(contentType)) {
            // WebP (and anything else that ever gets allow-listed on upload):
            // no decoder, so honour the request with the full-size original
            // rather than 415-ing a perfectly good image.
            return Resized.original(source, contentType);
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null) {
                // Passed the upload magic-byte check but ImageIO cannot decode
                // it. Serving the original keeps the endpoint's behaviour
                // unchanged for a caller who merely asked for a smaller copy.
                log.warn("Image could not be decoded for resize contentType={} bytes={}",
                        contentType, source.length);
                return Resized.original(source, contentType);
            }
            if (image.getWidth() <= width) {
                // Never upscale: it costs a re-encode and adds bytes to make
                // the picture worse.
                return Resized.original(source, contentType);
            }
            int height = Math.max(1, Math.round(
                    image.getHeight() * (width / (float) image.getWidth())));
            BufferedImage scaled = render(image, width, height, contentType);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            String format = JPEG.equalsIgnoreCase(contentType) ? "jpeg" : "png";
            if (!ImageIO.write(scaled, format, out)) {
                log.warn("No ImageIO writer for format={} — serving original", format);
                return Resized.original(source, contentType);
            }
            return new Resized(out.toByteArray(), contentType, true);
        } catch (IOException | RuntimeException ex) {
            // A resize is an optimisation. Failing it must never fail the read.
            log.warn("Image resize failed, serving original contentType={} width={} reason={}",
                    contentType, width, ex.toString());
            return Resized.original(source, contentType);
        }
    }

    private static BufferedImage render(BufferedImage image, int width, int height, String contentType) {
        // JPEG has no alpha channel: writing a TYPE_INT_ARGB raster as JPEG
        // produces the notorious pink/inverted image rather than an error, so
        // the target type has to follow the OUTPUT format, not the input.
        int type = JPEG.equalsIgnoreCase(contentType)
                ? BufferedImage.TYPE_INT_RGB
                : BufferedImage.TYPE_INT_ARGB;
        BufferedImage target = new BufferedImage(width, height, type);
        var g = target.createGraphics();
        try {
            g.drawImage(image.getScaledInstance(width, height, Image.SCALE_SMOOTH), 0, 0, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** Result of a resize attempt; {@code resized} is false when the original is served. */
    public record Resized(byte[] bytes, String contentType, boolean resized) {
        static Resized original(byte[] bytes, String contentType) {
            return new Resized(bytes, contentType, false);
        }
    }
}
