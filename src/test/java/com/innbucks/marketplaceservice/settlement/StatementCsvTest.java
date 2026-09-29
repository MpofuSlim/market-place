package com.innbucks.marketplaceservice.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SettlementQueryService#csvText} is the ONE text cell of every CSV this
 * service writes — the seller statement and the finance payout report — so
 * these cases pin both files at once.
 */
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
        assertThat(SettlementQueryService.csvText("")).isEmpty();
    }

    @ParameterizedTest(name = "a cell starting with {0} is neutralised")
    @ValueSource(strings = {"=", "+", "-", "@"})
    @DisplayName("Every formula trigger gets the apostrophe, and nothing else changes")
    void everyTriggerIsNeutralised(String trigger) {
        assertThat(SettlementQueryService.csvText(trigger + "1+1")).isEqualTo("'" + trigger + "1+1");
    }

    @Test
    @DisplayName("A leading TAB is neutralised; a leading CR is neutralised AND quoted")
    void tabAndCarriageReturnAreTriggersToo() {
        assertThat(SettlementQueryService.csvText("\t=1+1")).isEqualTo("'\t=1+1");
        // CR is a record separator to some parsers, so the cell must also be
        // quoted — around the apostrophe, never inside it.
        assertThat(SettlementQueryService.csvText("\r=1+1")).isEqualTo("\"'\r=1+1\"");
    }

    @Test
    @DisplayName("A cell needing both is neutralised first, then RFC-4180 quoted")
    void neutralisedCellsAreStillQuotedCorrectly() {
        assertThat(SettlementQueryService.csvText("=SUM(A1,B1)")).isEqualTo("\"'=SUM(A1,B1)\"");
        assertThat(SettlementQueryService.csvText("@a\nb")).isEqualTo("\"'@a\nb\"");
        assertThat(SettlementQueryService.csvText("=cmd|'/c calc'!A1"))
                .isEqualTo("'=cmd|'/c calc'!A1");
        assertThat(SettlementQueryService.csvText("-\"x\"")).isEqualTo("\"'-\"\"x\"\"\"");
    }

    @Test
    @DisplayName("A phone number keeps its plus, digits and length behind the apostrophe")
    void phoneNumbersSurviveIntact() {
        String cell = SettlementQueryService.csvText("+263771234567");
        assertThat(cell).startsWith("'").endsWith("+263771234567").hasSize(14);
    }

    @Test
    @DisplayName("Ordinary text, including a formula character that is not LEADING, is untouched")
    void innocentTextIsUntouched() {
        assertThat(SettlementQueryService.csvText("Rudo Chikwanha")).isEqualTo("Rudo Chikwanha");
        assertThat(SettlementQueryService.csvText("A=B")).isEqualTo("A=B");
        assertThat(SettlementQueryService.csvText("01123456789012")).isEqualTo("01123456789012");
    }
}
