package com.innbucks.marketplaceservice.support;

import com.innbucks.marketplaceservice.fulfilment.MerchantParcelViewAssembler;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository;
import com.innbucks.marketplaceservice.settlement.SettlementService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link MerchantParcelViewAssembler} whose batch reads answer from the
 * SAME per-id stubs a unit test already sets up ({@code findById},
 * {@code findByOrderId}, {@code SettlementService.forParcel}) — so moving the
 * card to batch queries did not mean rewriting every fulfilment test's
 * fixtures, and those tests keep asserting on the real card.
 */
public final class TestParcelViews {

    private TestParcelViews() {
    }

    public static MerchantParcelViewAssembler over(MarketOrderRepository orders,
                                                   MarketOrderItemRepository items,
                                                   SettlementService settlements) {
        when(orders.findAllById(any())).thenAnswer(inv -> {
            List<MarketOrder> out = new ArrayList<>();
            for (UUID id : inv.<Iterable<UUID>>getArgument(0)) {
                orders.findById(id).ifPresent(out::add);
            }
            return out;
        });
        when(items.findByOrderIdIn(any())).thenAnswer(inv -> {
            List<MarketOrderItem> out = new ArrayList<>();
            for (UUID id : inv.<Collection<UUID>>getArgument(0)) {
                out.addAll(items.findByOrderId(id));
            }
            return out;
        });
        MerchantSettlementRepository settlementRepository = mock(MerchantSettlementRepository.class);
        when(settlementRepository.findByFulfilmentIdIn(any())).thenAnswer(inv -> {
            List<MerchantSettlement> out = new ArrayList<>();
            for (UUID id : inv.<Collection<UUID>>getArgument(0)) {
                MerchantSettlement s = settlements == null ? null : settlements.forParcel(id);
                if (s != null) {
                    out.add(s);
                }
            }
            return out;
        });
        return new MerchantParcelViewAssembler(orders, items, settlementRepository,
                mock(SettlementDisputeRepository.class), 10);
    }
}
