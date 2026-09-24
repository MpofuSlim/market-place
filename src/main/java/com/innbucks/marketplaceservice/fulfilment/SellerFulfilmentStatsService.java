package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository.DispatchTiming;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository.ParcelCounts;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentStatsResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.SellerFulfilmentStats;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Turns a seller's REAL fulfilment history into the trust figures on their
 * public profile — the platform's answer to "how do I know this seller ships?"
 * on a marketplace where the alternative is a WhatsApp seller's own word.
 *
 * <p>Three disciplines, all load-bearing:
 * <ul>
 *   <li><b>Computed, never asserted.</b> Every figure comes off
 *       {@code order_fulfilment} rows that a real paid order created. The
 *       profile refused to carry a response time while there was nothing to
 *       compute one from; that refusal stands for anything this service
 *       cannot derive (logo, return policy).</li>
 *   <li><b>Silent below the sample floor.</b> "100% confirmed" over two
 *       parcels is noise wearing a percentage. Each figure is null until it
 *       rests on {@code min-sample} parcels, and the whole block is null for
 *       a seller with no completed parcel — the app renders "new seller",
 *       not a zero that reads like a verdict.</li>
 *   <li><b>Understate, never flatter.</b> The dispatch median rounds UP to
 *       whole hours. A stat that flatters a seller once is a stat shoppers
 *       stop believing everywhere.</li>
 * </ul>
 */
@Service
public class SellerFulfilmentStatsService {

    private final OrderFulfilmentRepository fulfilmentRepository;
    private final int minSample;
    private final int collectionOverdueDays;

    public SellerFulfilmentStatsService(
            OrderFulfilmentRepository fulfilmentRepository,
            @Value("${marketplace.seller-stats.min-sample:5}") int minSample,
            @Value("${marketplace.fulfilment.collection-overdue-days:7}") int collectionOverdueDays) {
        this.fulfilmentRepository = fulfilmentRepository;
        this.minSample = minSample;
        this.collectionOverdueDays = collectionOverdueDays;
    }

    /**
     * The public block for one seller's profile, or null when they have no
     * completed parcel at all — absent beats a page of zeroes that reads
     * like a failing seller rather than a new one.
     */
    @Transactional(readOnly = true)
    public SellerFulfilmentStats publicStats(UUID merchantId) {
        return publicStats(fulfilmentRepository.countParcels(merchantId), merchantId);
    }

    /**
     * The seller's own view: the public figures from the SAME computation the
     * shopper sees, plus the live queue counts that are their business alone.
     */
    @Transactional(readOnly = true)
    public MerchantFulfilmentStatsResponse merchantStats(AuthenticatedUser caller,
                                                         UUID merchantIdFilter) {
        UUID merchantId = resolveMerchant(caller, merchantIdFilter);
        ParcelCounts counts = fulfilmentRepository.countParcels(merchantId);
        OrderFulfilmentRepository.OpenParcelCounts open = fulfilmentRepository.countOpenParcels(
                merchantId, Instant.now().minus(Duration.ofDays(collectionOverdueDays)));
        return new MerchantFulfilmentStatsResponse(
                publicStats(counts, merchantId),
                counts.getAwaitingDispatch(),
                counts.getInTransit(),
                counts.getDelivered(),
                open.getOnTheWay(),
                open.getReadyToCollect(),
                open.getReadyToCollectOverdue(),
                collectionOverdueDays);
    }

    private SellerFulfilmentStats publicStats(ParcelCounts counts, UUID merchantId) {
        long delivered = counts.getDelivered();
        if (delivered == 0) {
            return null;
        }
        return new SellerFulfilmentStats(
                delivered,
                medianDispatchHours(merchantId),
                buyerConfirmedPercent(counts));
    }

    /**
     * Median payment→dispatch, in whole hours ROUNDED UP with a floor of 1 —
     * a same-hour dispatch reads "within 1 hour", never the absurd "0 hours",
     * and the platform understates speed rather than overstating it.
     */
    private Integer medianDispatchHours(UUID merchantId) {
        DispatchTiming timing = fulfilmentRepository.dispatchTiming(merchantId);
        if (timing.getSample() < minSample || timing.getMedianSeconds() == null) {
            return null;
        }
        long hours = (long) Math.ceil(timing.getMedianSeconds() / 3600.0);
        return (int) Math.max(1, hours);
    }

    private Integer buyerConfirmedPercent(ParcelCounts counts) {
        if (counts.getDelivered() < minSample) {
            return null;
        }
        return (int) Math.round(100.0 * counts.getBuyerConfirmed() / counts.getDelivered());
    }

    /**
     * Whose stats: SUPER_ADMIN may name any merchant (and must name one — an
     * admin token carries no merchant scope to default to); a MERCHANT_ADMIN
     * is always their own claim, and the filter is IGNORED for them — the same
     * cannot-widen-own-scope stance the queue takes.
     */
    private static UUID resolveMerchant(AuthenticatedUser caller, UUID merchantIdFilter) {
        if (caller.isSuperAdmin()) {
            if (merchantIdFilter == null) {
                throw ApiException.badRequest("merchant_id_required",
                        "merchantId is required when a SUPER_ADMIN reads a merchant's stats");
            }
            return merchantIdFilter;
        }
        String claim = caller.merchantId();
        if (claim == null || claim.isBlank()) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
    }
}
