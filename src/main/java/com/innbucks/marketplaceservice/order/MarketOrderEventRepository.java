package com.innbucks.marketplaceservice.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketOrderEventRepository extends JpaRepository<MarketOrderEvent, Long> {

    List<MarketOrderEvent> findByOrderIdOrderByIdAsc(UUID orderId);
}
