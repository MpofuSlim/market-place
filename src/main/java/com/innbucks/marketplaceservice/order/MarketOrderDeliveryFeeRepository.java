package com.innbucks.marketplaceservice.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MarketOrderDeliveryFeeRepository
        extends JpaRepository<MarketOrderDeliveryFee, MarketOrderDeliveryFee.Key> {

    List<MarketOrderDeliveryFee> findByOrderId(UUID orderId);

    /** A whole page of orders in one query. */
    List<MarketOrderDeliveryFee> findByOrderIdIn(Collection<UUID> orderIds);
}
