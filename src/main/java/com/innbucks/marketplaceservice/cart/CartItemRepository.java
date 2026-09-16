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
 * Every mutation below is {@code clearAutomatically = true}: they are bulk /
 * native statements that bypass the persistence context, and each one is
 * immediately followed by a re-read that renders the response. Without the
 * clear, that re-read would serve the shopper a cached row from before their
 * own edit.
 */
public interface CartItemRepository extends JpaRepository<CartItem, CartItemId> {

    /** The caller's cart, most recently added first — the order the app renders. */
    List<CartItem> findByBuyerUuidOrderByAddedAtDesc(UUID buyerUuid);

    /**
     * Add-to-cart: insert, or ADD to what is already there. Native SQL because
     * JPQL has no upsert, and an upsert is what makes the button idempotent
     * against a double tap — two inserts can never become two rows.
     *
     * <p>{@code added_at} is deliberately NOT touched on conflict: bumping it
     * would reshuffle the shopper's cart under their thumb every time they
     * changed a quantity.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO cart_item (buyer_uuid, listing_id, quantity, added_at, updated_at)
            VALUES (:buyerUuid, :listingId, :quantity, :now, :now)
            ON CONFLICT (buyer_uuid, listing_id) DO UPDATE
                SET quantity   = LEAST(cart_item.quantity + EXCLUDED.quantity, :maxQuantity),
                    updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int addQuantity(@Param("buyerUuid") UUID buyerUuid,
                    @Param("listingId") UUID listingId,
                    @Param("quantity") int quantity,
                    @Param("maxQuantity") int maxQuantity,
                    @Param("now") Instant now);

    /**
     * Set-to-an-exact-quantity, the stepper control's write. Separate from
     * {@link #addQuantity} on purpose: "make it 3" and "add 3 more" are
     * different intentions, and collapsing them into one endpoint is how a
     * retried request silently buys six.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO cart_item (buyer_uuid, listing_id, quantity, added_at, updated_at)
            VALUES (:buyerUuid, :listingId, :quantity, :now, :now)
            ON CONFLICT (buyer_uuid, listing_id) DO UPDATE
                SET quantity   = EXCLUDED.quantity,
                    updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int setQuantity(@Param("buyerUuid") UUID buyerUuid,
                    @Param("listingId") UUID listingId,
                    @Param("quantity") int quantity,
                    @Param("now") Instant now);

    /** Idempotent remove — 0 rows deleted is a legal outcome, not an error. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartItem c where c.buyerUuid = :buyerUuid and c.listingId = :listingId")
    int remove(@Param("buyerUuid") UUID buyerUuid, @Param("listingId") UUID listingId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartItem c where c.buyerUuid = :buyerUuid")
    int clear(@Param("buyerUuid") UUID buyerUuid);

    /**
     * Removes exactly the lines an order just took, leaving anything the
     * shopper did not check out. Called AFTER the order commits — see
     * {@code OrderService}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CartItem c where c.buyerUuid = :buyerUuid and c.listingId in :listingIds")
    int removeAll(@Param("buyerUuid") UUID buyerUuid,
                  @Param("listingIds") Collection<UUID> listingIds);

    long countByBuyerUuid(UUID buyerUuid);
}
