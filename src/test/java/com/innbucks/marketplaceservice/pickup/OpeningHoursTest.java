package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.pickup.dto.OpeningHoursEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpeningHoursTest {

    private static final UUID POINT = UUID.fromString("5c1d8e2a-3b4f-4a6d-9e7c-2f8a1b3c4d5e");
    private static final ZoneId HARARE = ZoneId.of("Africa/Harare");

    private static OpeningHoursEntry e(DayOfWeek day, String opens, String closes) {
        return new OpeningHoursEntry(day, opens, closes);
    }

    private static List<OpeningHoursEntry> weekdays(String opens, String closes) {
        List<OpeningHoursEntry> out = new ArrayList<>();
        for (DayOfWeek d : List.of(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)) {
            out.add(e(d, opens, closes));
        }
        return out;
    }

    private static List<CollectionPointHours> rows(OpeningHoursEntry... entries) {
        return OpeningHours.validate(POINT, Arrays.asList(entries));
    }

    private static String code(Throwable ex) {
        return ((ApiException) ex).code();
    }

    @Test
    @DisplayName("No hours at all is allowed and says nothing: no summary, no open/closed verdict")
    void noHoursSaysNothing() {
        assertThat(OpeningHours.validate(POINT, null)).isEmpty();
        assertThat(OpeningHours.validate(POINT, List.of())).isEmpty();
        assertThat(OpeningHours.summary(List.of())).isNull();
        assertThat(OpeningHours.openNow(List.of(), ZonedDateTime.now(HARARE))).isNull();
        assertThat(OpeningHours.entries(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Consecutive days with the same hours group into one range")
    void groupsConsecutiveDays() {
        List<OpeningHoursEntry> in = weekdays("08:00", "17:00");
        in.add(e(SATURDAY, "08:00", "13:00"));
        List<CollectionPointHours> rows = OpeningHours.validate(POINT, in);

        assertThat(OpeningHours.summary(rows)).isEqualTo("Mon-Fri 08:00-17:00, Sat 08:00-13:00");
    }

    @Test
    @DisplayName("A gap in the week or a change of hours starts a new group")
    void breaksGroupsOnGapsAndChanges() {
        List<CollectionPointHours> rows = rows(
                e(MONDAY, "08:00", "17:00"), e(TUESDAY, "08:00", "17:00"),
                e(THURSDAY, "08:00", "17:00"),
                e(FRIDAY, "09:00", "17:00"),
                e(SUNDAY, "10:00", "12:00"));

        assertThat(OpeningHours.summary(rows))
                .isEqualTo("Mon-Tue 08:00-17:00, Thu 08:00-17:00, Fri 09:00-17:00, Sun 10:00-12:00");
    }

    @Test
    @DisplayName("A day closed for lunch reads as two periods, in time order whatever the input order")
    void splitDay() {
        List<CollectionPointHours> rows = rows(
                e(MONDAY, "13:00", "17:00"), e(MONDAY, "08:00", "12:00"),
                e(TUESDAY, "08:00", "12:00"), e(TUESDAY, "13:00", "17:00"));

        assertThat(OpeningHours.summary(rows)).isEqualTo("Mon-Tue 08:00-12:00 and 13:00-17:00");
        assertThat(OpeningHours.entries(rows)).containsExactly(
                e(MONDAY, "08:00", "12:00"), e(MONDAY, "13:00", "17:00"),
                e(TUESDAY, "08:00", "12:00"), e(TUESDAY, "13:00", "17:00"));
    }

    @Test
    @DisplayName("Entries come back Monday first, whatever order they were sent in")
    void entriesAreMondayFirst() {
        List<CollectionPointHours> rows = rows(
                e(SATURDAY, "08:00", "13:00"), e(MONDAY, "08:00", "17:00"));

        assertThat(OpeningHours.entries(rows)).extracting(OpeningHoursEntry::day)
                .containsExactly(MONDAY, SATURDAY);
        assertThat(rows).allSatisfy(r -> assertThat(r.getPointId()).isEqualTo(POINT));
    }

    @Test
    @DisplayName("Open now: inside a period, not before it opens, not at or after it closes")
    void openNow() {
        List<CollectionPointHours> rows = rows(
                e(MONDAY, "08:00", "12:00"), e(MONDAY, "13:00", "17:00"));
        // 2026-09-21 is a Monday.
        ZonedDateTime monday = ZonedDateTime.of(2026, 9, 21, 0, 0, 0, 0, HARARE);

        assertThat(OpeningHours.openNow(rows, monday.with(LocalTime.of(7, 59)))).isFalse();
        assertThat(OpeningHours.openNow(rows, monday.with(LocalTime.of(8, 0)))).isTrue();
        assertThat(OpeningHours.openNow(rows, monday.with(LocalTime.of(12, 30)))).isFalse();
        assertThat(OpeningHours.openNow(rows, monday.with(LocalTime.of(16, 59)))).isTrue();
        assertThat(OpeningHours.openNow(rows, monday.with(LocalTime.of(17, 0)))).isFalse();
        assertThat(OpeningHours.openNow(rows, monday.plusDays(1).with(LocalTime.of(9, 0))))
                .as("no hours on Tuesday = closed, not unknown").isFalse();
    }

    @Test
    @DisplayName("Open now is judged on the market's clock, not the instant's UTC reading")
    void openNowUsesTheGivenZone() {
        List<CollectionPointHours> rows = rows(e(MONDAY, "08:00", "09:00"));
        // 06:30 UTC on a Monday is 08:30 in Harare: open.
        ZonedDateTime utc = ZonedDateTime.of(2026, 9, 21, 6, 30, 0, 0, ZoneId.of("UTC"));

        assertThat(OpeningHours.openNow(rows, utc)).isFalse();
        assertThat(OpeningHours.openNow(rows, utc.withZoneSameInstant(HARARE))).isTrue();
    }

    @Test
    @DisplayName("A period that closes before (or when) it opens is refused, naming the day")
    void closesBeforeOpens() {
        assertThatThrownBy(() -> rows(e(WEDNESDAY, "17:00", "08:00")))
                .satisfies(ex -> {
                    assertThat(code(ex)).isEqualTo("invalid_opening_hours");
                    assertThat(ex.getMessage()).isEqualTo("Wed closes before it opens");
                });
        assertThatThrownBy(() -> rows(e(WEDNESDAY, "08:00", "08:00")))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("invalid_opening_hours"));
    }

    @Test
    @DisplayName("Overlapping periods on one day are refused")
    void overlapping() {
        assertThatThrownBy(() -> rows(e(FRIDAY, "08:00", "13:00"), e(FRIDAY, "12:00", "17:00")))
                .satisfies(ex -> assertThat(ex.getMessage())
                        .isEqualTo("Fri has opening periods that overlap"));
    }

    @Test
    @DisplayName("Back-to-back periods are not an overlap")
    void touchingIsFine() {
        assertThat(rows(e(FRIDAY, "08:00", "12:00"), e(FRIDAY, "12:00", "17:00"))).hasSize(2);
    }

    @Test
    @DisplayName("More than two periods on one day is refused")
    void tooManyPeriods() {
        assertThatThrownBy(() -> rows(e(MONDAY, "08:00", "10:00"), e(MONDAY, "11:00", "12:00"),
                e(MONDAY, "13:00", "17:00")))
                .satisfies(ex -> assertThat(ex.getMessage())
                        .isEqualTo("Mon has more than 2 opening periods"));
    }

    @Test
    @DisplayName("A null entry or a malformed time is a 400, never an NPE rendered as a 500")
    void malformedEntriesAreRefused() {
        List<OpeningHoursEntry> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> OpeningHours.validate(POINT, withNull))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("invalid_opening_hours"));
        assertThatThrownBy(() -> rows(e(null, "08:00", "17:00")))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("invalid_opening_hours"));
        assertThatThrownBy(() -> rows(e(MONDAY, null, "17:00")))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("invalid_opening_hours"));
        assertThatThrownBy(() -> rows(e(MONDAY, "8am", "17:00")))
                .satisfies(ex -> assertThat(ex.getMessage())
                        .isEqualTo("Mon has a time that is not HH:mm"));
    }
}
