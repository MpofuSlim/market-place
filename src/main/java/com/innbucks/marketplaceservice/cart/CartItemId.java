package com.innbucks.marketplaceservice.cart;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite key of {@link CartItem} — (buyer, listing), the same shape V6's
 * favorites use. One row per listing per buyer is what makes "add this twice"
 * an upsert by construction rather than a duplicate the read has to fold.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartItemId implements Serializable {

    private UUID buyerUuid;
    private UUID listingId;
}
