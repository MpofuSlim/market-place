package com.innbucks.marketplaceservice.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementCsvTest {

    @Test
    @DisplayName("Other people's text cannot become a spreadsheet formula, and quotes survive")
    void textCellsAreSafe() {
        assertThat(SettlementQueryService.csvText("=HYPERLINK(\"http://x\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://x\"\")\"");
        assertThat(SettlementQueryService.csvText("+263771234567")).isEqualTo("'+263771234567");
        assertThat(SettlementQueryService.csvText("-5")).isEqualTo("'-5");
        assertThat(SettlementQueryService.csvText("@cmd")).isEqualTo("'@cmd");
        assertThat(SettlementQueryService.csvText("2 x Lantern, 1 x Hose"))
                .isEqualTo("\"2 x Lantern, 1 x Hose\"");
        assertThat(SettlementQueryService.csvText("MKT-4F9A1C22B7D3")).isEqualTo("MKT-4F9A1C22B7D3");
        assertThat(SettlementQueryService.csvText(null)).isEmpty();
    }
}
