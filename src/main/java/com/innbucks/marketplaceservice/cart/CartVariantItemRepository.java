package com.innbucks.marketplaceservice.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Variant cart lines (V19). The same shapes and the same
 * {@code clearAutomatically} discipline as {@link CartItemRepository}, keyed on
 * the option instead of the listing.
 */
public interface CartVariantItemRepository extends JpaRepository<CartVariantItem, CartVariantItemId> {

    List<CartVariantItem> findByBuyerUuidOrderByAddedAtDesc(UUID buyerUuid);

    /** Add-to-cart: insert, or ADD to what is there, clamped at the cap. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO cart_variant_item (buyer_uuid, variant_id, listing_id, quantity, added_at, updated_at)
            VALUES (:buyerUuid, :variantId, :listingId, :quantity, :now, :now)
            ON CONFLICT (buyer_uuid, variant_id) DO UPDATE
                SET quantity   = LEAST(cart_variant_item.quantity + EXCLUDED.quantity, :maxQuantity),
                    updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int addQuantity(@Param("buyerUuid") UUID buyerUuid,
                    @Param("variantId") UUID variantId,
                    @Param("listingId") UUID listingId,
                    @Param("quantity") int quantity,
                    @Param("maxQuantity") int maxQuantity,
                    @Param("now") Instant now);

    /** Set-to-an-exact-quantity. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO cart_variant_item (buyer_uuid, variant_id, listing_id, quantity, added_at, updated_at)
            VALUES (:buyerUuid, :variantId, :listingId, :quantity, :now, :now)
            ON CONFLICT (buyer_uuid, variant_id) DO UPDATE
                SET quantity   = EXCLUDED.quantity,
                    updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int setQuantity(@Param("buyerUuid") UUID buyerUuid,
                    @Param("variantId") UUID variantId,
                    @Param("listingId") UUID listingId,
                    @Param("quantity") int quantity,
                    @Param("now") Instant now);

    /** Removes one option's line; 0 rows is a legal outcome. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartVariantItem c where c.buyerUuid = :buyerUuid and c.variantId = :variantId")
    int remove(@Param("buyerUuid") UUID buyerUuid, @Param("variantId") UUID variantId);

    /** "Remove this item" without naming an option: every option of the listing. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartVariantItem c where c.buyerUuid = :buyerUuid and c.listingId = :listingId")
    int removeAllOfListing(@Param("buyerUuid") UUID buyerUuid, @Param("listingId") UUID listingId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartVariantItem c where c.buyerUuid = :buyerUuid")
    int clear(@Param("buyerUuid") UUID buyerUuid);

    /** Removes exactly the option lines an order just took. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartVariantItem c where c.buyerUuid = :buyerUuid and c.variantId in :variantIds")
    int removeAll(@Param("buyerUuid") UUID buyerUuid,
                  @Param("variantIds") Collection<UUID> variantIds);

    long countByBuyerUuid(UUID buyerUuid);
}
