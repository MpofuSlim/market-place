package com.innbucks.marketplaceservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketZoneTest {

    @Test
    @DisplayName("A Harare day starts at 22:00 UTC the evening before")
    void harareDayBounds() {
        MarketZone zw = new MarketZone("zw");
        LocalDate day = LocalDate.parse("2026-09-24");

        assertThat(zw.startOf(day)).isEqualTo(Instant.parse("2026-09-23T22:00:00Z"));
        assertThat(zw.endOf(day)).isEqualTo(Instant.parse("2026-09-24T22:00:00Z"));
        // 23:30 UTC on the 24th is already the 25th in Harare.
        assertThat(zw.dateOf(Instant.parse("2026-09-24T23:30:00Z")))
                .isEqualTo(LocalDate.parse("2026-09-25"));
        assertThat(zw.atMarket(Instant.parse("2026-09-24T12:14:05Z")).toString())
                .isEqualTo("2026-09-24T14:14:05+02:00");
    }

    @Test
    @DisplayName("An unmapped market refuses to start rather than silently running on UTC")
    void unmappedCountryFails() {
        assertThatThrownBy(() -> new MarketZone("XX"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("innbucks.country=XX");
    }
}
