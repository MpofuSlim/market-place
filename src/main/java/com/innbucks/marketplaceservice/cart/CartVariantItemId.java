package com.innbucks.marketplaceservice.cart;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/** Composite key of {@link CartVariantItem}: (buyer, option). Two sizes of one
 *  listing are two lines; the same size twice is an upsert. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartVariantItemId implements Serializable {

    private UUID buyerUuid;
    private UUID variantId;
}
