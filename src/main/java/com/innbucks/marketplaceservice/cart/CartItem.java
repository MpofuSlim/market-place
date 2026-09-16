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
 * One line of a buyer's cart (V9 {@code cart_item} table).
 *
 * <p>Deliberately stores ONLY the quantity. Price, title, stock and status are
 * resolved live from the listing on every read: a cart can sit for weeks, and a
 * price copied into it would quietly become a promise the catalogue no longer
 * makes. The snapshot belongs on the ORDER, where the buyer has agreed to it —
 * see {@code MarketOrderItem}.
 *
 * <p>A cart line holds no stock. Reservation happens once, atomically, at order
 * creation; a cart that reserved would let anyone freeze a merchant's inventory
 * for free.
 *
 * <p>{@code addedAt} is preserved by a quantity change so the cart keeps a
 * stable order as the shopper edits it — the same reason a repeat favorite does
 * not bump its position.
 */
@Entity
@Table(name = "cart_item")
@IdClass(CartItemId.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CartItem {

    @Id
    @Column(name = "buyer_uuid", nullable = false)
    private UUID buyerUuid;

    @Id
    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
