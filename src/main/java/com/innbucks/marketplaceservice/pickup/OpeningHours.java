package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.pickup.dto.OpeningHoursEntry;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A collection point's weekly hours: validated on the way in, rendered and
 * evaluated on the way out.
 *
 * <p><b>Market-local wall-clock times, never instants.</b> "08:00" at a Harare
 * counter is 08:00 in Harare; "open now" is evaluated against the market's
 * clock ({@code MarketZone}), never the JVM's.
 *
 * <p><b>The server renders, the client prints.</b> {@link #summary} is the
 * line a screen shows as-is ("Mon-Fri 08:00-17:00, Sat 08:00-13:00"), so no
 * client keeps its own day-grouping logic that could disagree with ours.
 */
final class OpeningHours {

    /** Two opening periods a day covers a lunch break; more is a data-entry slip. */
    static final int MAX_INTERVALS_PER_DAY = 2;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private OpeningHours() {
    }

    /**
     * Validates and orders a request's hours: each interval opens before it
     * closes, at most {@value #MAX_INTERVALS_PER_DAY} per day, none overlapping.
     *
     * @throws ApiException 400 {@code invalid_opening_hours}, naming the day
     */
    static List<CollectionPointHours> validate(UUID pointId, List<OpeningHoursEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<DayOfWeek, List<LocalTime[]>> byDay = new EnumMap<>(DayOfWeek.class);
        for (OpeningHoursEntry entry : entries) {
            // Bean Validation skips a null list ELEMENT, so an entry reaching
            // here unchecked must be a 400, never an NPE rendered as a 500.
            if (entry == null || entry.day() == null) {
                throw ApiException.badRequest("invalid_opening_hours",
                        "Every opening-hours entry needs a day, an opening and a closing time");
            }
            LocalTime opens = time(entry.day(), entry.opens());
            LocalTime closes = time(entry.day(), entry.closes());
            if (!opens.isBefore(closes)) {
                throw invalid(entry.day(), "closes before it opens");
            }
            byDay.computeIfAbsent(entry.day(), d -> new ArrayList<>())
                    .add(new LocalTime[] {opens, closes});
        }
        List<CollectionPointHours> rows = new ArrayList<>();
        for (Map.Entry<DayOfWeek, List<LocalTime[]>> day : byDay.entrySet()) {
            List<LocalTime[]> intervals = day.getValue();
            if (intervals.size() > MAX_INTERVALS_PER_DAY) {
                throw invalid(day.getKey(), "has more than " + MAX_INTERVALS_PER_DAY
                        + " opening periods");
            }
            intervals.sort(Comparator.comparing(i -> i[0]));
            for (int i = 1; i < intervals.size(); i++) {
                if (intervals.get(i)[0].isBefore(intervals.get(i - 1)[1])) {
                    throw invalid(day.getKey(), "has opening periods that overlap");
                }
            }
            for (LocalTime[] interval : intervals) {
                rows.add(new CollectionPointHours(pointId, (short) day.getKey().getValue(),
                        interval[0], interval[1]));
            }
        }
        return rows;
    }

    /** The hours as the API returns them, Monday first; empty when none given. */
    static List<OpeningHoursEntry> entries(List<CollectionPointHours> rows) {
        return sorted(rows).stream()
                .map(h -> new OpeningHoursEntry(DayOfWeek.of(h.getDayOfWeek()),
                        HH_MM.format(h.getOpensAt()), HH_MM.format(h.getClosesAt())))
                .toList();
    }

    /**
     * One printable line: consecutive days with identical hours are grouped
     * ("Mon-Fri 08:00-17:00, Sat 08:00-13:00"); a split day reads
     * "08:00-12:00 and 13:00-17:00". Null when no hours are given.
     */
    static String summary(List<CollectionPointHours> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<DayOfWeek, String> perDay = new EnumMap<>(DayOfWeek.class);
        for (CollectionPointHours h : sorted(rows)) {
            String interval = HH_MM.format(h.getOpensAt()) + "-" + HH_MM.format(h.getClosesAt());
            perDay.merge(DayOfWeek.of(h.getDayOfWeek()), interval, (a, b) -> a + " and " + b);
        }
        List<String> groups = new ArrayList<>();
        DayOfWeek start = null;
        DayOfWeek previous = null;
        for (DayOfWeek day : DayOfWeek.values()) {
            String hours = perDay.get(day);
            boolean continues = start != null && previous == day.minus(1)
                    && Objects.equals(perDay.get(start), hours);
            if (hours == null || !continues) {
                if (start != null) {
                    groups.add(group(start, previous, perDay.get(start)));
                    start = null;
                }
                if (hours != null) {
                    start = day;
                }
            }
            previous = hours == null ? null : day;
        }
        if (start != null) {
            groups.add(group(start, previous, perDay.get(start)));
        }
        return String.join(", ", groups);
    }

    /** Whether any interval covers {@code now} (market-local); null when no hours are given. */
    static Boolean openNow(List<CollectionPointHours> rows, ZonedDateTime now) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        int today = now.getDayOfWeek().getValue();
        LocalTime time = now.toLocalTime();
        for (CollectionPointHours h : rows) {
            if (h.getDayOfWeek() == today && !time.isBefore(h.getOpensAt())
                    && time.isBefore(h.getClosesAt())) {
                return true;
            }
        }
        return false;
    }

    private static List<CollectionPointHours> sorted(List<CollectionPointHours> rows) {
        return rows.stream()
                .sorted(Comparator.comparingInt((CollectionPointHours h) -> h.getDayOfWeek())
                        .thenComparing(CollectionPointHours::getOpensAt))
                .toList();
    }

    private static String group(DayOfWeek from, DayOfWeek to, String hours) {
        String days = from == to ? shortName(from) : shortName(from) + "-" + shortName(to);
        return days + " " + hours;
    }

    private static String shortName(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static LocalTime time(DayOfWeek day, String value) {
        if (value == null) {
            throw invalid(day, "needs an opening and a closing time");
        }
        try {
            return LocalTime.parse(value, HH_MM);
        } catch (DateTimeParseException ex) {
            throw invalid(day, "has a time that is not HH:mm");
        }
    }

    private static ApiException invalid(DayOfWeek day, String problem) {
        return ApiException.badRequest("invalid_opening_hours",
                shortName(day) + " " + problem);
    }
}
