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
    private final SettlementService settlementService;
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
        // Read from the seller record rather than inferred from anything
        // here: "can this seller be paid" is a property of the seller, and a
        // summary that guessed would be the one screen telling them they are
        // fine when they are not.
        boolean configured = sellerService.findAllByMerchantIds(List.of(merchantId))
                .values().stream()
                .anyMatch(MarketplaceSeller::hasPayoutDestination);
        return new SettlementSummaryResponse(merchantId, configured, totals);
    }

    /**
     * Money still HELD past the staleness threshold, oldest first (V12).
     *
     * <p>Operator-only by its caller's {@code @PreAuthorize}, and deliberately
     * a separate read rather than a filter on {@link #list}: staleness is a
     * property of the whole ledger, not of one merchant's view of it, and
     * folding it into the scoped list would put a per-merchant answer behind a
     * fleet-wide question.
     */
    @Transactional(readOnly = true)
    public SettlementPageResponse stale(int size) {
        List<SettlementResponse> rows = settlementService
                .staleHeld(Math.clamp(size, 1, MAX_PAGE_SIZE))
                .stream()
                .map(SettlementResponse::from)
                .toList();
        return new SettlementPageResponse(rows, 0, rows.size(), rows.size(), 1);
    }

    /**
     * The payout report as CSV — the sheet finance pays from: every merchant
     * with RELEASABLE money, biggest owed first, with the trading name where
     * the platform knows one. The period is in the FILENAME (fleet CSV rule —
     * a preamble row breaks every parser that treats line 1 as the header).
     *
     * <p><b>It carries the DESTINATION (V13), and that is the whole point of
     * the column set.</b> Until then this sheet said who was owed and how
     * much, and an operator had to find each seller's bank details somewhere
     * outside this system entirely. The one fact a payment cannot be made
     * without was the one fact the report did not hold.
     *
     * <p><b>Unmasked, deliberately.</b> A masked account number cannot be paid
     * into, so masking here would only send the operator back to the
     * spreadsheet this column replaced. The surface is SUPER_ADMIN-only and
     * the data is exactly what a payment instruction contains.
     *
     * <p><b>{@code payoutChangedAt} is a fraud control, not bookkeeping.</b>
     * Re-pointing a payout is what a compromised seller account is used for,
     * and this report is read in the moment BEFORE money moves — the last
     * point at which a human can notice that a destination moved yesterday.
     * The seller is warned at change time too; this is the other side of it.
     */
    @Transactional(readOnly = true)
    public Csv payoutReportCsv() {
        List<PayoutRow> rows = settlementRepository.payoutReport();
        List<UUID> merchantIds = rows.stream().map(PayoutRow::getMerchantId).toList();
        Map<UUID, MarketplaceSeller> sellers = sellerService.findAllByMerchantIds(merchantIds);
        // The name finance checks a transfer against. Operator-set wins; the
        // organization registry fills the gap, so a seller who was never approved
        // is no longer a bare UUID on the sheet money is paid from. One batch
        // for the whole report, and an unreachable registry just leaves the
        // column as it was.
        Map<UUID, String> names = sellerService.displayNames(merchantIds, sellers);
        StringBuilder csv = new StringBuilder("merchantId,displayName,parcels,netCents,currency,"
                + "payoutMethod,payoutAccountName,payoutMsisdn,payoutBankName,"
                + "payoutAccountNumber,payoutChangedAt\n");
        for (PayoutRow row : rows) {
            MarketplaceSeller seller = sellers.get(row.getMerchantId());
            csv.append(row.getMerchantId()).append(',')
                    .append(csvField(names.get(row.getMerchantId()))).append(',')
                    .append(row.getParcels()).append(',')
                    .append(row.getNetCents()).append(',')
                    .append(row.getCurrency()).append(',')
                    // Every destination column is EMPTY for a seller with none
                    // on file. That row still appears — the money is genuinely
                    // owed, and a sheet that silently dropped it would hide a
                    // seller who cannot be paid instead of surfacing them.
                    .append(seller == null || !seller.hasPayoutDestination()
                            ? ",,,,," : destinationFields(seller))
                    .append('\n');
        }
        String filename = "marketplace-payout-report-"
                + LocalDate.now(ZoneOffset.UTC) + ".csv";
        return new Csv(filename, csv.toString());
    }

    /** The six destination columns, in header order. Bank and wallet fields
     *  are mutually exclusive by {@code chk_seller_payout_destination}, so
     *  exactly one of them is ever populated on a row. */
    private static String destinationFields(MarketplaceSeller seller) {
        return seller.getPayoutMethod().name() + ','
                + csvField(seller.getPayoutAccountName()) + ','
                + csvField(seller.getPayoutMsisdn()) + ','
                + csvField(seller.getPayoutBankName()) + ','
                + csvField(seller.getPayoutAccountNumber()) + ','
                + (seller.getPayoutUpdatedAt() == null ? "" : seller.getPayoutUpdatedAt());
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
