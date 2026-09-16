package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository.PayoutRow;
import com.innbucks.marketplaceservice.settlement.dto.SettlementPageResponse;
import com.innbucks.marketplaceservice.settlement.dto.SettlementResponse;
import com.innbucks.marketplaceservice.settlement.dto.SettlementSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The escrow's READ surfaces: the seller's money view, the "where is my
 * money" summary, and the operator's payout report.
 *
 * <p>Scoping is the fulfilment queue's, verbatim: a MERCHANT_ADMIN is always
 * their own claim and any {@code merchantId} parameter is IGNORED (a seller
 * cannot widen their own scope); SUPER_ADMIN reads any merchant and the
 * fleet-wide views.
 */
@Service
@RequiredArgsConstructor
public class SettlementQueryService {

    /** Same hard page cap as every other paged surface. */
    static final int MAX_PAGE_SIZE = 50;

    private final MerchantSettlementRepository settlementRepository;
    private final SellerService sellerService;

    @Transactional(readOnly = true)
    public SettlementPageResponse list(AuthenticatedUser caller, SettlementStatus status,
                                       UUID merchantIdFilter, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        Page<MerchantSettlement> result;
        if (caller.isSuperAdmin()) {
            if (merchantIdFilter != null) {
                result = status == null
                        ? settlementRepository.findByMerchantIdOrderByCreatedAtDesc(
                                merchantIdFilter, pageable)
                        : settlementRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                                merchantIdFilter, status, pageable);
            } else {
                result = status == null
                        ? settlementRepository.findAllByOrderByCreatedAtDesc(pageable)
                        : settlementRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
            }
        } else {
            UUID merchantId = requireMerchantId(caller);
            result = status == null
                    ? settlementRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                    : settlementRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                            merchantId, status, pageable);
        }
        return SettlementPageResponse.from(result.map(SettlementResponse::from));
    }

    /** One merchant's parcels + net totals grouped by escrow state. SUPER_ADMIN
     *  must name the merchant (an admin token has no scope to default to). */
    @Transactional(readOnly = true)
    public SettlementSummaryResponse summary(AuthenticatedUser caller, UUID merchantIdFilter) {
        UUID merchantId;
        if (caller.isSuperAdmin()) {
            if (merchantIdFilter == null) {
                throw ApiException.badRequest("merchant_id_required",
                        "merchantId is required when a SUPER_ADMIN reads a merchant's summary");
            }
            merchantId = merchantIdFilter;
        } else {
            merchantId = requireMerchantId(caller);
        }
        List<SettlementSummaryResponse.Line> totals = settlementRepository.summarize(merchantId)
                .stream()
                .map(row -> new SettlementSummaryResponse.Line(
                        row.getStatus(), row.getParcels(), row.getNetCents()))
                .toList();
        return new SettlementSummaryResponse(merchantId, totals);
    }

    /**
     * The payout report as CSV — the sheet finance pays from: every merchant
     * with RELEASABLE money, biggest owed first, with the trading name where
     * the platform knows one. The period is in the FILENAME (fleet CSV rule —
     * a preamble row breaks every parser that treats line 1 as the header).
     */
    @Transactional(readOnly = true)
    public Csv payoutReportCsv() {
        List<PayoutRow> rows = settlementRepository.payoutReport();
        Map<UUID, MarketplaceSeller> sellers = sellerService.findAllByMerchantIds(
                rows.stream().map(PayoutRow::getMerchantId).toList());
        StringBuilder csv = new StringBuilder("merchantId,displayName,parcels,netCents,currency\n");
        for (PayoutRow row : rows) {
            MarketplaceSeller seller = sellers.get(row.getMerchantId());
            csv.append(row.getMerchantId()).append(',')
                    .append(csvField(seller == null ? null : seller.getDisplayName())).append(',')
                    .append(row.getParcels()).append(',')
                    .append(row.getNetCents()).append(',')
                    .append(row.getCurrency()).append('\n');
        }
        String filename = "marketplace-payout-report-"
                + LocalDate.now(ZoneOffset.UTC) + ".csv";
        return new Csv(filename, csv.toString());
    }

    /** RFC-4180 quoting for the one free-text column. */
    private static String csvField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    public record Csv(String filename, String content) {
    }

    /** Merchant scope comes from the JWT, never from a request parameter. */
    private static UUID requireMerchantId(AuthenticatedUser caller) {
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
