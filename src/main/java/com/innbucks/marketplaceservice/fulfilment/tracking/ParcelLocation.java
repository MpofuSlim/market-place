package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** The courier's last known position for one parcel. */
@Schema(description = "Where the parcel was last seen")
public record ParcelLocation(

        @Schema(example = "-17.829220")
        double latitude,

        @Schema(example = "31.053961")
        double longitude,

        @Schema(description = "The phone's own accuracy estimate, metres", example = "12",
                nullable = true)
        Integer accuracyMeters,

        @Schema(description = "When the courier's phone took this fix. Show its age: a position "
                + "from 20 minutes ago is not current.", example = "2026-09-24T12:14:05Z")
        Instant recordedAt) {

    /** Null when no position has been posted for this parcel. */
    public static ParcelLocation of(OrderFulfilment parcel) {
        if (parcel.getLastLatitude() == null || parcel.getLastLongitude() == null
                || parcel.getLastLocationAt() == null) {
            return null;
        }
        return new ParcelLocation(parcel.getLastLatitude().doubleValue(),
                parcel.getLastLongitude().doubleValue(), parcel.getLastAccuracyMeters(),
                parcel.getLastLocationAt());
    }
}
