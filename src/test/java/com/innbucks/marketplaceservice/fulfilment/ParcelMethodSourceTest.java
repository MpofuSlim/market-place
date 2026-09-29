package com.innbucks.marketplaceservice.fulfilment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * getter alone - and through native SQL, which names neither: a query joining
 * {@code market_order} and reading ITS {@code delivery_method} column (the
 * shape two pre-V21 readers had) is caught through the table's alias.
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
    @DisplayName("No parcel-level package reads the order's delivery summary - getter, JPQL, "
            + "Criteria or native SQL")
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
                    Set<String> orderAliases = orderAliases(String.join("\n", lines));
                    for (int i = 0; i < lines.size(); i++) {
                        if (readsOrderMethod(lines.get(i), orderAliases)) {
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
    @DisplayName("Native SQL reading the ORDER's delivery_method through a market_order alias is "
            + "caught; the parcel's own column is not")
    void nativeSqlReadsOfTheOrderColumnAreCaught() {
        // The shape two pre-V21 readers had: a native query joining the order
        // and filtering on ITS method. Neither the getter nor the JPQL name
        // appears, so the property-name scan alone would stay green.
        String preV21 = """
                SELECT count(*) FROM order_fulfilment f
                  JOIN market_order o ON o.id = f.order_id
                 WHERE o.delivery_method = 'COLLECTION' AND f.status = 'DISPATCHED'""";
        Set<String> aliases = orderAliases(preV21);
        assertThat(aliases).contains("o");
        assertThat(preV21.lines().anyMatch(l -> readsOrderMethod(l, aliases))).isTrue();
        assertThat(readsOrderMethod("WHERE market_order.delivery_method = 'DELIVERY'", Set.of()))
                .isTrue();

        // The parcel's own column, and the item table's alias, are fine.
        String parcelLevel = """
                SELECT count(*) FROM order_fulfilment f
                  JOIN market_order o ON o.id = f.order_id
                  JOIN market_order_item i ON i.order_id = o.id
                 WHERE f.delivery_method = 'COLLECTION'""";
        Set<String> parcelAliases = orderAliases(parcelLevel);
        assertThat(parcelAliases).containsExactly("o");
        assertThat(parcelLevel.lines().anyMatch(l -> readsOrderMethod(l, parcelAliases))).isFalse();
    }

    // ------------------------------------------------------------------

    /** {@code market_order o}, {@code market_order AS o}: the aliases a native
     *  query binds to the ORDER table (never {@code market_order_item}). */
    private static final Pattern ORDER_ALIAS = Pattern.compile(
            "\\bmarket_order\\s+(?:as\\s+)?([a-z_][a-z0-9_]*)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> SQL_WORDS = Set.of(
            "where", "on", "join", "left", "right", "inner", "outer", "using", "set", "and",
            "or", "group", "order", "limit", "for", "returning", "values");

    static Set<String> orderAliases(String source) {
        Set<String> aliases = new HashSet<>();
        Matcher m = ORDER_ALIAS.matcher(source);
        while (m.find()) {
            String alias = m.group(1).toLowerCase(Locale.ROOT);
            if (!SQL_WORDS.contains(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    /** The summary by its Java/JPQL name in any form, or the order table's
     *  {@code delivery_method} column read through the table or an alias. */
    static boolean readsOrderMethod(String line, Set<String> orderAliases) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("deliverysummary") || lower.contains("market_order.delivery_method")) {
            return true;
        }
        for (String alias : orderAliases) {
            if (Pattern.compile("\\b" + Pattern.quote(alias) + "\\.delivery_method\\b").matcher(lower).find()) {
                return true;
            }
        }
        return false;
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
