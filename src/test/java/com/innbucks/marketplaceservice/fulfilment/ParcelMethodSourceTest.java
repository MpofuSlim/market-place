package com.innbucks.marketplaceservice.fulfilment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A source scan that keeps the order's delivery SUMMARY out of every
 * parcel-level package (V21).
 *
 * <p>{@code MarketOrder.deliverySummary} is DELIVERY when any seller on the
 * order delivers — on a mixed order it is wrong for at least one parcel. Every
 * rule about a parcel (who may close it, whether a collection code or a courier
 * position means anything, whether a destination is shown, which queue count
 * it lands in, how its close is named) must read
 * {@code OrderFulfilment.deliveryMethod} instead. The compiler found every
 * reader when the field was renamed; this test stops a new one from creeping
 * back in through a getter, a JPQL path or a Criteria {@code get("...")},
 * which is why it scans for the property name in any form rather than for the
 * getter alone.
 *
 * <p>Only the order's own rendering (OrderService, OrderViewAssembler, the order
 * DTOs) and checkout may read it — none of them live in the packages below.
 */
class ParcelMethodSourceTest {

    private static final Path MAIN = Path.of("src/main/java/com/innbucks/marketplaceservice");

    /** Where a parcel is decided, rendered, settled or announced. */
    private static final List<String> PARCEL_LEVEL = List.of(
            "fulfilment", "settlement", "notify", "pickup");

    @Test
    @DisplayName("No parcel-level package reads the order's delivery summary - getter, JPQL or Criteria")
    void parcelLevelCodeNeverReadsTheOrderSummary() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String pkg : PARCEL_LEVEL) {
            Path root = MAIN.resolve(pkg);
            assertThat(root).as("package %s must exist, or this scan proves nothing", pkg)
                    .isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    scanned++;
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).toLowerCase(Locale.ROOT).contains("deliverysummary")) {
                            offenders.add(MAIN.relativize(file) + ":" + (i + 1) + "  "
                                    + lines.get(i).trim());
                        }
                    }
                }
            }
        }
        assertThat(scanned).as("the scan must actually read the parcel-level sources")
                .isGreaterThan(50);
        assertThat(offenders)
                .as("Parcel-level code must read OrderFulfilment.getDeliveryMethod(), never the "
                        + "order's summary (wrong for one parcel of every mixed order)")
                .isEmpty();
    }

    @Test
    @DisplayName("The scanned name is the real one: the order's own view still reads it")
    void theGuardedNameIsReal() throws IOException {
        // A positive control - if the property were renamed again, the scan
        // above would pass vacuously while guarding a name nothing uses.
        String orderView = Files.readString(
                MAIN.resolve("order/OrderViewAssembler.java"), StandardCharsets.UTF_8);
        assertThat(orderView).contains("getDeliverySummary()");
    }
}
