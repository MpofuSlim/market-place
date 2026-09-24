package com.innbucks.marketplaceservice.fulfilment.tracking;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Courier position reporting ({@code marketplace.tracking.*}).
 *
 * <p>The bounds default to Zimbabwe's bounding box because each cell serves one
 * country: a courier position outside it is a phone with a broken fix or a
 * made-up point, and showing either on a buyer's map is worse than showing
 * nothing. A cell in another country sets its own box.
 */
@Data
@ConfigurationProperties(prefix = "marketplace.tracking")
public class TrackingProperties {

    /** A courier's phone may report as often as it likes; we store at most one
     *  position per parcel in this window. Keeps a runaway client from turning
     *  a map into a write storm. */
    private int minPingIntervalSeconds = 5;

    /** A fix older than this when it arrives (a phone that buffered offline)
     *  is ignored rather than shown as "now". */
    private int maxPingAgeSeconds = 600;

    private double minLatitude = -22.5;
    private double maxLatitude = -15.5;
    private double minLongitude = 25.0;
    private double maxLongitude = 33.1;
}
