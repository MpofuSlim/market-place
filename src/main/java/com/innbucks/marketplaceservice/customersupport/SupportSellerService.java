package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.customersupport.dto.SupportSellerResponse;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.SellerFulfilmentStatsService;
import com.innbucks.marketplaceservice.fulfilment.SellerParcelQueryService;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentPageResponse;
import com.innbucks.marketplaceservice.notify.MsisdnMasking;
import com.innbucks.marketplaceservice.pickup.CollectionPointService;
import com.innbucks.marketplaceservice.report.ListingReportRepository;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.MarketplaceSellerRepository;
import com.innbucks.marketplaceservice.seller.NameResolvingRead;
import com.innbucks.marketplaceservice.seller.PayoutMethod;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.settlement.DisputeStatus;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository;
import com.innbucks.marketplaceservice.settlement.SettlementQueryService;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A seller as the call center sees them, and their parcel queue.
 *
 * <p>The figures are the SAME computations the seller's own portal runs
 * ({@code SellerFulfilmentStatsService.statsFor}, {@code
 * SettlementQueryService.summaryFor}, {@code SellerParcelQueryService.queueFor}),
 * so "the agent's screen says X, my portal says Y" cannot happen. They take the
 * merchant as an argument because a support agent has no seller scope of their
 * own — never by pretending to be SUPER_ADMIN.
 */
@Service
@RequiredArgsConstructor
public class SupportSellerService {

    static final int LIST_LIMIT = 20;
    static final int RECENT_NOTES = 3;

    private final SupportSubjects subjects;
    private final SupportActivityLog activityLog;
    private final SupportNoteService notes;
    private final MarketplaceSellerRepository sellerRepository;
    private final SellerService sellerService;
    private final ListingRepository listingRepository;
    private final CollectionPointService collectionPointService;
    private final SellerFulfilmentStatsService statsService;
    private final SettlementQueryService settlementQueryService;
    private final SellerParcelQueryService parcelQueryService;
    private final SettlementDisputeRepository disputeRepository;
    private final MerchantSettlementRepository settlementRepository;
    private final ListingReportRepository reportRepository;

    @NameResolvingRead
    public SupportSellerResponse profile(SupportAgent agent, UUID merchantId) {
        subjects.requireSeller(merchantId);
        activityLog.record(agent, SupportActions.VIEW_SELLER, SubjectKind.SELLER, merchantId, null);

        MarketplaceSeller seller = sellerRepository.findById(merchantId).orElse(null);
        Map<ListingStatus, Long> listings = new EnumMap<>(ListingStatus.class);
        for (ListingStatus status : ListingStatus.values()) {
            listings.put(status, listingRepository.countByMerchantIdAndStatus(merchantId, status));
        }
        List<SettlementDispute> disputes = disputeRepository.findByMerchantIdAndStatusOrderByCreatedAtAsc(
                merchantId, DisputeStatus.OPEN, PageRequest.of(0, LIST_LIMIT));
        Map<UUID, MerchantSettlement> disputedMoney = settlementRepository
                .findAllById(disputes.stream().map(SettlementDispute::getSettlementId).distinct().toList())
                .stream().collect(Collectors.toMap(MerchantSettlement::getId, Function.identity()));

        Map<UUID, MarketplaceSeller> local = new LinkedHashMap<>();
        if (seller != null) {
            local.put(merchantId, seller);
        }
        String name = sellerService.displayNames(List.of(merchantId), local).get(merchantId);
        return new SupportSellerResponse(
                merchantId,
                name,
                seller == null ? null : seller.getStatus(),
                seller == null ? null : seller.getDecisionNote(),
                seller == null ? null : seller.getDecidedAt(),
                seller == null ? null : seller.getCreatedAt(),
                // No record yet = collecting, the V20 default for every seller.
                seller == null || seller.isCollectionEnabled(),
                payout(seller),
                collectionPointService.list(merchantId),
                listings,
                statsService.statsFor(merchantId),
                settlementQueryService.summaryFor(merchantId),
                disputes.stream().map(d -> DisputeResponse.from(d, disputedMoney.get(d.getSettlementId()))).toList(),
                reportRepository.countOpenForMerchant(merchantId),
                notes.recent(SubjectKind.SELLER, merchantId, RECENT_NOTES));
    }

    /** The seller's parcel queue, filtered exactly as their own portal filters it. */
    public MerchantFulfilmentPageResponse parcels(SupportAgent agent, UUID merchantId, FulfilmentStatus status,
                                                  DeliveryMethod deliveryMethod, String q, int page, int size) {
        subjects.requireSeller(merchantId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("page", Math.max(page, 0));
        if (status != null) {
            detail.put("status", status.name());
        }
        activityLog.record(agent, SupportActions.VIEW_SELLER_PARCELS, SubjectKind.SELLER, merchantId, detail);
        return parcelQueryService.queueFor(merchantId,
                new SellerParcelQueryService.ParcelQuery(status, deliveryMethod, q, merchantId, page, size));
    }

    private static SupportSellerResponse.Payout payout(MarketplaceSeller seller) {
        if (seller == null || !seller.hasPayoutDestination()) {
            return new SupportSellerResponse.Payout(false, null, null, null, null, null);
        }
        String destination = seller.getPayoutMethod() == PayoutMethod.MOBILE_MONEY
                ? MsisdnMasking.mask(seller.getPayoutMsisdn())
                : lastFour(seller.getPayoutAccountNumber());
        return new SupportSellerResponse.Payout(true, seller.getPayoutMethod(), seller.getPayoutAccountName(),
                destination, seller.getPayoutBankName(), seller.getPayoutUpdatedAt());
    }

    private static String lastFour(String accountNumber) {
        if (accountNumber == null || accountNumber.length() <= 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
