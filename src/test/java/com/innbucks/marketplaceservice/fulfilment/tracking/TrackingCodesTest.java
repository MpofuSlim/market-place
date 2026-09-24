package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingCodesTest {

    @Test
    @DisplayName("A minted code is TRK- plus ten Crockford characters, and codes do not repeat")
    void mintShape() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String code = TrackingCodes.mint();
            assertThat(code).matches("TRK-[0-9A-HJKMNP-TV-Z]{10}");
            seen.add(code);
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    @DisplayName("A code read off a screen or over the phone still finds the parcel")
    void normalizeForgivesHowPeopleTypeIt() {
        assertThat(TrackingCodes.normalize("TRK-7F3K9Q2M4X")).isEqualTo("TRK-7F3K9Q2M4X");
        assertThat(TrackingCodes.normalize(" trk-7f3k9q2m4x ")).isEqualTo("TRK-7F3K9Q2M4X");
        assertThat(TrackingCodes.normalize("7F3K 9Q2M 4X")).isEqualTo("TRK-7F3K9Q2M4X");
        assertThat(TrackingCodes.normalize("TRK7F3K-9Q2M-4X")).isEqualTo("TRK-7F3K9Q2M4X");
        // The characters people confuse fold onto the ones the alphabet uses.
        assertThat(TrackingCodes.normalize("TRK-7F3K9QZMIO")).isEqualTo("TRK-7F3K9QZM10");
        assertThat(TrackingCodes.normalize("TRK-7F3K9QZMLo")).isEqualTo("TRK-7F3K9QZM10");
    }

    @Test
    @DisplayName("Anything that cannot be a code is null, so it never reaches the database")
    void normalizeRefusesNonCodes() {
        assertThat(TrackingCodes.normalize(null)).isNull();
        assertThat(TrackingCodes.normalize("")).isNull();
        assertThat(TrackingCodes.normalize("TRK-123")).isNull();
        assertThat(TrackingCodes.normalize("TRK-7F3K9Q2M4X1")).isNull();
        assertThat(TrackingCodes.normalize("TRK-7F3K9Q2M4!")).isNull();
        assertThat(TrackingCodes.normalize("MKT-4F9A1C22B7D3")).isNull();
    }

    @Test
    @DisplayName("The tracker's four words map every fulfilment state, cancellation included")
    void trackingStatusVocabulary() {
        assertThat(TrackingStatus.of(FulfilmentStatus.PREPARING)).isEqualTo(TrackingStatus.RECEIVED);
        assertThat(TrackingStatus.of(FulfilmentStatus.DISPATCHED)).isEqualTo(TrackingStatus.DISPATCHED);
        assertThat(TrackingStatus.of(FulfilmentStatus.DELIVERED)).isEqualTo(TrackingStatus.DELIVERED);
        assertThat(TrackingStatus.of(FulfilmentStatus.UNFULFILLED)).isEqualTo(TrackingStatus.CANCELLED);
        for (FulfilmentStatus status : FulfilmentStatus.values()) {
            assertThat(TrackingStatus.of(status)).isNotNull();
        }
    }
}
