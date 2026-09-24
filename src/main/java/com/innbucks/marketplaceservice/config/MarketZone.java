package com.innbucks.marketplaceservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * The civil time zone of the market this cell serves — for the few places a
 * PERSON's calendar matters: a seller asking for "September", a statement's
 * date column, a CSV's timestamps. Storage and every JSON timestamp stay UTC.
 *
 * <p>An unmapped country FAILS at construction rather than defaulting to UTC:
 * a UTC fallback would put every "today" two hours wrong in Zimbabwe while
 * looking perfectly healthy. Adding a market is a one-line reviewed change.
 * None of these markets observes daylight saving.
 */
@Component
public class MarketZone {

    private static final Map<String, String> ZONES = Map.ofEntries(
            Map.entry("ZW", "Africa/Harare"),
            Map.entry("ZA", "Africa/Johannesburg"),
            Map.entry("ZM", "Africa/Lusaka"),
            Map.entry("MW", "Africa/Blantyre"),
            Map.entry("MZ", "Africa/Maputo"),
            Map.entry("BW", "Africa/Gaborone"),
            Map.entry("KE", "Africa/Nairobi"),
            Map.entry("UG", "Africa/Kampala"),
            Map.entry("TZ", "Africa/Dar_es_Salaam"),
            Map.entry("NG", "Africa/Lagos"),
            Map.entry("GH", "Africa/Accra"));

    private final ZoneId zone;

    public MarketZone(@Value("${innbucks.country}") String country) {
        String code = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        String id = ZONES.get(code);
        if (id == null) {
            throw new IllegalStateException("No market time zone is mapped for innbucks.country="
                    + country + " - add it to MarketZone before this cell can start");
        }
        this.zone = ZoneId.of(id);
    }

    public ZoneId zone() {
        return zone;
    }

    /** The first instant of {@code day} in this market. */
    public Instant startOf(LocalDate day) {
        return day.atStartOfDay(zone).toInstant();
    }

    /** The first instant AFTER {@code day} in this market — an exclusive bound. */
    public Instant endOf(LocalDate day) {
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public LocalDate dateOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /** The instant as this market's wall clock, e.g. {@code 2026-09-24T14:05:00+02:00}. */
    public OffsetDateTime atMarket(Instant instant) {
        return instant.atZone(zone).toOffsetDateTime();
    }
}
