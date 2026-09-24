package com.innbucks.marketplaceservice.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * A cart line that names an OPTION of a listing (V19
 * {@code cart_variant_item}): "2 x Cotton Crew Tee, M - Black".
 *
 * <p>A sibling of {@link CartItem}, not a change to it. Altering
 * {@code cart_item}'s key would have taken the {@code ON CONFLICT (buyer_uuid,
 * listing_id)} arbiter away from the previous image while it still served
 * during the rolling restart — every cart write 500s until the new pod is up,
 * and again after any rollback. A second table is purely additive: an older
 * image simply does not see variant lines.
 *
 * <p>The same discipline as {@link CartItem}: quantities only, no price, no
 * stock. {@code listingId} is kept beside the variant so the pricer and
 * "remove this item" work without the variant row; {@code variantId} has no
 * FK on purpose, so a seller removing an option leaves the shopper's line
 * visible with a VARIANT_UNAVAILABLE issue rather than silently gone.
 */
@Entity
@Table(name = "cart_variant_item")
@IdClass(CartVariantItemId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartVariantItem {

    @Id
    @Column(name = "buyer_uuid", nullable = false)
    private UUID buyerUuid;

    @Id
    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
