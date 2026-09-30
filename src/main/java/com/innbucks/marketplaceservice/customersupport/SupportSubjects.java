package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.cart.CartItemRepository;
import com.innbucks.marketplaceservice.cart.CartVariantItemRepository;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.delivery.DeliveryAddressRepository;
import com.innbucks.marketplaceservice.favorite.ListingFavoriteRepository;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.seller.MarketplaceSellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Does this buyer, order or seller exist here? Support views and notes refuse
 * a subject this service has never seen — a 404, not an empty profile, so a
 * mistyped id reads as a mistake rather than as a customer with no history.
 *
 * <p>A BUYER exists once they have left any trace: an order, an address, a
 * basket line or a favourite. A SELLER exists once they have a seller record,
 * a listing, or a line sold in an order (the snapshot column — a seller whose
 * listings were archived still sold).
 */
@Component
@RequiredArgsConstructor
public class SupportSubjects {

    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository orderItemRepository;
    private final DeliveryAddressRepository addressRepository;
    private final CartItemRepository cartItemRepository;
    private final CartVariantItemRepository cartVariantItemRepository;
    private final ListingFavoriteRepository favoriteRepository;
    private final MarketplaceSellerRepository sellerRepository;
    private final ListingRepository listingRepository;

    public boolean buyerExists(UUID buyerUuid) {
        return orderRepository.existsByBuyerUuid(buyerUuid)
                || addressRepository.countByBuyerUuid(buyerUuid) > 0
                || cartItemRepository.countByBuyerUuid(buyerUuid) > 0
                || cartVariantItemRepository.countByBuyerUuid(buyerUuid) > 0
                || favoriteRepository.countByIdBuyerUuid(buyerUuid) > 0;
    }

    public boolean sellerExists(UUID merchantId) {
        return sellerRepository.existsById(merchantId)
                || listingRepository.countByMerchantId(merchantId) > 0
                || orderItemRepository.existsByMerchantId(merchantId);
    }

    public void requireBuyer(UUID buyerUuid) {
        if (!buyerExists(buyerUuid)) {
            throw ApiException.notFound("buyer_not_found", "No marketplace buyer with that id");
        }
    }

    public MarketOrder requireOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> ApiException.notFound("order_not_found", "Order not found"));
    }

    public void requireSeller(UUID merchantId) {
        if (!sellerExists(merchantId)) {
            throw ApiException.notFound("seller_not_found", "No marketplace seller with that id");
        }
    }

    public void require(SubjectKind kind, UUID id) {
        switch (kind) {
            case BUYER -> requireBuyer(id);
            case ORDER -> requireOrder(id);
            case SELLER -> requireSeller(id);
        }
    }
}
