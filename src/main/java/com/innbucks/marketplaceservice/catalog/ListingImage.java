package com.innbucks.marketplaceservice.catalog;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One image in a listing's gallery (V3 {@code listing_image} table): exactly
 * ONE primary per listing (partial unique index {@code uq_listing_image_primary}
 * is the DB backstop) plus up to 9 additional images, ordered by
 * {@code position} (append-only; primary sorts first regardless).
 *
 * <p>The gallery is deliberately NOT mapped as a collection on {@link Listing}
 * — metadata reads go through {@link ListingImageRepository}'s bytes-free
 * projection so list endpoints never pull image bytes, and mutations use bulk
 * {@code @Modifying} statements whose execution order keeps the one-primary
 * index satisfied mid-transaction (demote THEN mark).
 *
 * <p>Implements {@link Persistable} because the id is manually assigned and
 * the table carries no {@code @Version} column: without {@code isNew()},
 * {@code save()} on a new row would merge (an is-it-in-the-DB SELECT per
 * insert). Rows built via {@link #builder()} are INSERTs ({@code isNew=true}
 * default); JPA-loaded rows get {@code isNew=false} from the no-arg
 * constructor path, so managed updates keep working.
 */
@Entity
@Table(name = "listing_image")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingImage implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    // BYTEA + lazy for the same reason as the V2 single-image column: no
    // Postgres size cap truncating real photos, and list/metadata paths must
    // never load bytes (they use the ImageMeta projection instead).
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "image_bytes", columnDefinition = "BYTEA", nullable = false)
    @ToString.Exclude
    private byte[] imageBytes;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    /** Named {@code primaryImage} (not {@code primary}) so derived-query and
     *  JPQL identifiers stay clear of SQL's PRIMARY keyword. */
    @Column(name = "is_primary", nullable = false)
    private boolean primaryImage;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** True for builder-constructed rows (INSERT path); false once persisted
     *  or when JPA hydrates a loaded row. Never a column. */
    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
