package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.catalog.ImageVariantRenderer.RenderedVariant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for the V25 {@code listing_image_variant} table and the narrow reads of
 * {@code listing_image} that serving needs. Plain JDBC (the
 * {@code IdempotencyService} precedent) because the write that matters is an
 * {@code INSERT … ON CONFLICT} behind a {@code FOR SHARE} guard, and because
 * every statement here names its columns — which is the point:
 *
 * <ul>
 *   <li>{@link #findVariant} is the hot path: one row by primary key, joined to
 *       its image only to resolve the listing/primary and to check the
 *       rendition still describes the image's current bytes. It never selects
 *       {@code listing_image.image_bytes}.</li>
 *   <li>Only {@link #findSource} and {@link #findOriginalBytes} read the
 *       original's bytes — the first request for a rendition of an old image,
 *       and a request answered with the original itself.</li>
 * </ul>
 *
 * Statements run on the caller's transaction when there is one (Spring's
 * {@code DataSourceUtils} under {@code JpaTransactionManager}) — the upload
 * path relies on that; reads outside one autocommit.
 */
@Repository
public class ListingImageVariantRepository {

    private static final String VARIANT_COLUMNS =
            "v.image_id, v.source_created_at, v.resized, v.content_type, v.etag, v.bytes";

    static final String FIND_PRIMARY_VARIANT = "SELECT " + VARIANT_COLUMNS + """
             FROM listing_image i
             JOIN listing_image_variant v
               ON v.image_id = i.id AND v.variant = ? AND v.source_created_at = i.created_at
            WHERE i.listing_id = ? AND i.is_primary""";

    static final String FIND_IMAGE_VARIANT = "SELECT " + VARIANT_COLUMNS + """
             FROM listing_image i
             JOIN listing_image_variant v
               ON v.image_id = i.id AND v.variant = ? AND v.source_created_at = i.created_at
            WHERE i.id = ? AND i.listing_id = ?""";

    static final String FIND_PRIMARY_META =
            "SELECT id, content_type, created_at FROM listing_image WHERE listing_id = ? AND is_primary";

    static final String FIND_IMAGE_META =
            "SELECT id, content_type, created_at FROM listing_image WHERE id = ? AND listing_id = ?";

    static final String FIND_PRIMARY_SOURCE =
            "SELECT id, content_type, created_at, image_bytes FROM listing_image WHERE listing_id = ? AND is_primary";

    static final String FIND_IMAGE_SOURCE =
            "SELECT id, content_type, created_at, image_bytes FROM listing_image WHERE id = ? AND listing_id = ?";

    static final String FIND_ORIGINAL_BYTES =
            "SELECT image_bytes FROM listing_image WHERE id = ? AND created_at = ?";

    /** The lazy writer's guard: the image still holds the bytes the rendition
     *  was made from, and nobody is replacing or deleting it right now (a
     *  locked row is skipped, never waited for — the request is not held up
     *  and the next one stores it). */
    static final String LOCK_CURRENT_SOURCE =
            "SELECT 1 FROM listing_image WHERE id = ? AND created_at = ? FOR SHARE SKIP LOCKED";

    private static final String INSERT = """
            INSERT INTO listing_image_variant (image_id, variant, source_created_at, resized, content_type,
                                               bytes, width, height, byte_size, etag)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (image_id, variant) DO UPDATE SET
                source_created_at = EXCLUDED.source_created_at,
                resized = EXCLUDED.resized,
                content_type = EXCLUDED.content_type,
                bytes = EXCLUDED.bytes,
                width = EXCLUDED.width,
                height = EXCLUDED.height,
                byte_size = EXCLUDED.byte_size,
                etag = EXCLUDED.etag,
                created_at = now()""";

    /** Upload: the rows ARE the image's renditions now, whatever was there. */
    static final String UPSERT_UNCONDITIONAL = INSERT;

    /** Lazy: two first requests racing write once (the loser's conflict is a
     *  no-op); only a row describing OLDER bytes is overwritten. */
    static final String UPSERT_IF_STALE = INSERT + """

            WHERE listing_image_variant.source_created_at IS DISTINCT FROM EXCLUDED.source_created_at""";

    static final String DELETE_FOR_IMAGE = "DELETE FROM listing_image_variant WHERE image_id = ?";

    /** A stored rendition, joined to the image's CURRENT bytes. */
    public record StoredVariant(UUID imageId, Instant sourceCreatedAt, boolean resized, String contentType,
                               String etag, byte[] bytes) {}

    /** The image a URL names, without its bytes. */
    public record ImageMeta(UUID imageId, String contentType, Instant createdAt) {}

    /** The image a URL names, with its bytes — the first-request path only. */
    public record ImageSource(UUID imageId, String contentType, Instant createdAt, byte[] bytes) {}

    private final JdbcTemplate jdbc;

    public ListingImageVariantRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** @param imageId null = the listing's primary image. */
    public Optional<StoredVariant> findVariant(UUID listingId, UUID imageId, ImageVariant variant) {
        List<StoredVariant> rows = imageId == null
                ? jdbc.query(FIND_PRIMARY_VARIANT, ListingImageVariantRepository::storedVariant,
                        variant.code(), listingId)
                : jdbc.query(FIND_IMAGE_VARIANT, ListingImageVariantRepository::storedVariant,
                        variant.code(), imageId, listingId);
        return rows.stream().findFirst();
    }

    /** @param imageId null = the listing's primary image. */
    public Optional<ImageMeta> findMeta(UUID listingId, UUID imageId) {
        List<ImageMeta> rows = imageId == null
                ? jdbc.query(FIND_PRIMARY_META, (rs, n) -> new ImageMeta(uuid(rs, "id"),
                        rs.getString("content_type"), instant(rs, "created_at")), listingId)
                : jdbc.query(FIND_IMAGE_META, (rs, n) -> new ImageMeta(uuid(rs, "id"),
                        rs.getString("content_type"), instant(rs, "created_at")), imageId, listingId);
        return rows.stream().findFirst();
    }

    /** @param imageId null = the listing's primary image. */
    public Optional<ImageSource> findSource(UUID listingId, UUID imageId) {
        List<ImageSource> rows = imageId == null
                ? jdbc.query(FIND_PRIMARY_SOURCE, ListingImageVariantRepository::source, listingId)
                : jdbc.query(FIND_IMAGE_SOURCE, ListingImageVariantRepository::source, imageId, listingId);
        return rows.stream().findFirst();
    }

    /** The original's bytes, only while it still holds the version {@code createdAt} names. */
    public Optional<byte[]> findOriginalBytes(UUID imageId, Instant createdAt) {
        return jdbc.query(FIND_ORIGINAL_BYTES, (rs, n) -> rs.getBytes(1), imageId, timestamp(createdAt))
                .stream().findFirst();
    }

    /**
     * Upload: replaces every stored rendition of {@code imageId} with
     * {@code variants}, in the caller's transaction. The image row must
     * already be written on that connection (flush first).
     */
    public void replaceAll(UUID imageId, Instant sourceCreatedAt, List<RenderedVariant> variants) {
        jdbc.update(DELETE_FOR_IMAGE, imageId);
        for (RenderedVariant variant : variants) {
            if (variant.cacheable()) {
                jdbc.update(UPSERT_UNCONDITIONAL, insertArgs(imageId, sourceCreatedAt, variant));
            }
        }
    }

    /** Explicit delete in the caller's transaction (ON DELETE CASCADE is the backstop). */
    public void deleteForImage(UUID imageId) {
        jdbc.update(DELETE_FOR_IMAGE, imageId);
    }

    /**
     * Lazy write, inside its OWN short transaction (the caller's): stores the
     * rendition only while the image still holds the bytes it was made from.
     *
     * @return true when the guard held and the upsert ran.
     */
    public boolean saveIfCurrent(UUID imageId, Instant sourceCreatedAt, RenderedVariant variant) {
        List<Integer> current = jdbc.query(LOCK_CURRENT_SOURCE, (rs, n) -> rs.getInt(1),
                imageId, timestamp(sourceCreatedAt));
        if (current.isEmpty()) {
            return false;
        }
        jdbc.update(UPSERT_IF_STALE, insertArgs(imageId, sourceCreatedAt, variant));
        return true;
    }

    private static Object[] insertArgs(UUID imageId, Instant sourceCreatedAt, RenderedVariant v) {
        return new Object[]{
                imageId,
                v.variant().code(),
                timestamp(sourceCreatedAt),
                v.resized(),
                v.contentType(),
                new SqlParameterValue(Types.BINARY, v.resized() ? v.bytes() : null),
                new SqlParameterValue(Types.INTEGER, v.width()),
                new SqlParameterValue(Types.INTEGER, v.height()),
                v.byteSize(),
                v.etag()};
    }

    private static StoredVariant storedVariant(ResultSet rs, int rowNum) throws SQLException {
        return new StoredVariant(uuid(rs, "image_id"), instant(rs, "source_created_at"),
                rs.getBoolean("resized"), rs.getString("content_type"), rs.getString("etag"),
                rs.getBytes("bytes"));
    }

    private static ImageSource source(ResultSet rs, int rowNum) throws SQLException {
        return new ImageSource(uuid(rs, "id"), rs.getString("content_type"), instant(rs, "created_at"),
                rs.getBytes("image_bytes"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
