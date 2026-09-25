package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V19 schema's own guarantees, proven with plain SQL against real
 * Postgres — the backstops that hold even for a write path that forgets a
 * rule the service enforces.
 *
 * <p>Every CHECK is exercised on both sides: the shape it must refuse, named
 * by the constraint in the error, and the legal shape next to it, so a CHECK
 * that has quietly become too strict fails here too. Every CHECK over a
 * nullable column names {@code IS [NOT] NULL} explicitly (the V16/V17
 * lesson: a CHECK that evaluates to UNKNOWN passes), and the NULL-bearing
 * shapes are exactly the ones tested.
 *
 * <p>The option uniqueness is {@code DEFERRABLE INITIALLY DEFERRED}, so it is
 * tested on a raw connection with explicit transactions: a swap passes
 * through a state that holds two equal keys and must still commit, while a
 * real duplicate must be refused — at COMMIT, not at the statement.
 *
 * <p>Last, the drift sweep's three shapes: a total that disagrees with its
 * options, and a discriminator that disagrees with whether option rows exist.
 */
class VariantSchemaIT extends PostgresTestContainer {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private VariantStockDriftSweeper sweeper;

    @Autowired
    private MeterRegistry meterRegistry;

    // ------------------------------------------------------------------
    // listing: chk_listing_variant_axes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A listing that sells options must name its first option axis")
    void optionsWithoutAFirstAxisAreRefused() {
        assertRefusedBy("chk_listing_variant_axes",
                () -> insertListing(UUID.randomUUID(), true, null, null, 0));
        assertRefusedBy("chk_listing_variant_axes",
                () -> insertListing(UUID.randomUUID(), true, null, "Colour", 0));

        assertThatCode(() -> insertListing(UUID.randomUUID(), true, "Size", null, 0))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertListing(UUID.randomUUID(), true, "Size", "Colour", 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A listing without options carries no option axis names at all")
    void aPlainListingWithAxisNamesIsRefused() {
        assertRefusedBy("chk_listing_variant_axes",
                () -> insertListing(UUID.randomUUID(), false, "Size", null, 5));
        assertRefusedBy("chk_listing_variant_axes",
                () -> insertListing(UUID.randomUUID(), false, null, "Colour", 5));

        UUID plain = UUID.randomUUID();
        insertListing(plain, false, null, null, 5);
        // Nor can an existing plain row acquire a name without becoming a
        // listing with options in the same write.
        assertRefusedBy("chk_listing_variant_axes", () -> jdbc.update(
                "UPDATE listing SET option1_name = 'Size' WHERE id = ?", plain));
    }

    // ------------------------------------------------------------------
    // listing_variant: stock, price
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An option's stock can never go below zero - not by an INSERT, not by an UPDATE")
    void negativeOptionStockIsRefused() {
        UUID listing = UUID.randomUUID();
        insertListing(listing, true, "Size", null, 0);

        assertRefusedBy("chk_listing_variant_stock",
                () -> insertVariant(UUID.randomUUID(), listing, "M", null, null, -1, 0));

        UUID m = UUID.randomUUID();
        insertVariant(m, listing, "M", null, null, 0, 0);
        assertRefusedBy("chk_listing_variant_stock", () -> jdbc.update(
                "UPDATE listing_variant SET stock_qty = stock_qty - 1 WHERE id = ?", m));
        assertThat(variantStock(m)).isZero();
    }

    @Test
    @DisplayName("An option's own price is positive when set - 0 is refused, NULL means the listing price")
    void aZeroPriceOverrideIsRefused() {
        UUID listing = UUID.randomUUID();
        insertListing(listing, true, "Size", null, 0);

        assertRefusedBy("chk_listing_variant_price",
                () -> insertVariant(UUID.randomUUID(), listing, "M", null, 0L, 4, 0));
        assertRefusedBy("chk_listing_variant_price",
                () -> insertVariant(UUID.randomUUID(), listing, "L", null, -2299L, 4, 1));

        assertThatCode(() -> insertVariant(UUID.randomUUID(), listing, "M", null, null, 4, 0))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertVariant(UUID.randomUUID(), listing, "XL", null, 2299L, 6, 1))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // market_order_item: chk_order_item_variant_snapshot
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An order line snapshots its option whole - an id without a label, or a label "
            + "without an id, is refused; both or neither is accepted")
    void anOrderLineSnapshotsItsOptionWholeOrNotAtAll() {
        UUID order = insertOrder();
        UUID listing = UUID.randomUUID();
        UUID variant = UUID.randomUUID();

        assertRefusedBy("chk_order_item_variant_snapshot",
                () -> insertOrderItem(order, listing, variant, null));
        assertRefusedBy("chk_order_item_variant_snapshot",
                () -> insertOrderItem(order, listing, null, "M - Black"));

        // Neither: every pre-V19 line, and every line without an option.
        assertThatCode(() -> insertOrderItem(order, listing, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertOrderItem(order, listing, variant, "M - Black"))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // cart_variant_item
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A cart line for an option holds at least one unit - a zero can never be a silent delete")
    void aZeroQuantityOptionCartLineIsRefused() {
        UUID listing = UUID.randomUUID();
        insertListing(listing, true, "Size", null, 0);
        UUID buyer = UUID.randomUUID();

        assertRefusedBy("chk_cart_variant_item_quantity",
                () -> insertCartLine(buyer, UUID.randomUUID(), listing, 0));
        assertRefusedBy("chk_cart_variant_item_quantity",
                () -> insertCartLine(buyer, UUID.randomUUID(), listing, -1));

        assertThatCode(() -> insertCartLine(buyer, UUID.randomUUID(), listing, 1))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A buyer has at most one cart line per option - the key the add-to-cart upsert "
            + "conflicts on - while two options of one listing are two lines")
    void oneCartLinePerBuyerAndOption() {
        UUID listing = UUID.randomUUID();
        insertListing(listing, true, "Size", null, 0);
        UUID buyer = UUID.randomUUID();
        UUID m = UUID.randomUUID();
        insertCartLine(buyer, m, listing, 2);

        assertThatThrownBy(() -> insertCartLine(buyer, m, listing, 1))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("cart_variant_item_pkey");

        // Another option of the SAME listing, and another buyer of the same
        // option, are different lines.
        assertThatCode(() -> insertCartLine(buyer, UUID.randomUUID(), listing, 1))
                .doesNotThrowAnyException();
        assertThatCode(() -> insertCartLine(UUID.randomUUID(), m, listing, 1))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(
                "SELECT quantity FROM cart_variant_item WHERE buyer_uuid = ? AND variant_id = ?",
                Integer.class, buyer, m)).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // uq_listing_variant_option_key - DEFERRABLE INITIALLY DEFERRED
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Swapping two options' values (M -> L while L -> M) inside ONE transaction "
            + "commits: the uniqueness is checked at COMMIT, not per statement")
    void aSwapInsideOneTransactionCommits() throws SQLException {
        UUID listing = UUID.randomUUID();
        insertListing(listing, true, "Size", "Colour", 10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertVariant(first, listing, "M", "Black", null, 4, 0);
        insertVariant(second, listing, "L", "Black", null, 6, 1);

        // The intermediate state really is a duplicate: checked immediately,
        // the first half of the swap is refused on the spot.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET CONSTRAINTS uq_listing_variant_option_key IMMEDIATE");
                assertThatThrownBy(() -> statement.executeUpdate(setValue(first, "L", "l,black")))
                        .isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"))
                        .hasMessageContaining("uq_listing_variant_option_key");
            } finally {
                connection.rollback();
            }
        }

        // Deferred - the constraint's own mode - the whole swap commits.
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(setValue(first, "L", "l,black"));
                statement.executeUpdate(setValue(second, "M", "m,black"));
            }
            connection.commit();
        }

        assertThat(optionKey(first)).isEqualTo("l,black");
        assertThat(optionKey(second)).isEqualTo("m,black");
    }

    @Test
    @DisplayName("A duplicate option key ('m,black' twice - 'M' and 'm' are the same option) "
            + "is accepted by the statement and refused at COMMIT, leaving nothing behind")
    void aDuplicateKeyIsRefusedAtCommit() throws SQLException {
        UUID listing = UUID.randomUUID();
        insertListing(listing, true, "Size", "Colour", 4);
        insertVariant(UUID.randomUUID(), listing, "M", "Black", null, 4, 0);
        UUID duplicate = UUID.randomUUID();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // The statement itself succeeds: the check is deferred.
                assertThat(statement.executeUpdate("""
                        INSERT INTO listing_variant (id, listing_id, option1_value, option2_value,
                            option_key, price_cents, stock_qty, position, created_at, updated_at)
                        VALUES ('%s', '%s', 'm', 'Black', 'm,black', NULL, 2, 1, now(), now())"""
                        .formatted(duplicate, listing))).isEqualTo(1);
            }
            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23505"))
                    .hasMessageContaining("uq_listing_variant_option_key");
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM listing_variant WHERE listing_id = ?", Integer.class, listing))
                .isEqualTo(1);
        // The key is per LISTING: the same option on another listing is fine.
        UUID other = UUID.randomUUID();
        insertListing(other, true, "Size", "Colour", 4);
        assertThatCode(() -> insertVariant(UUID.randomUUID(), other, "M", "Black", null, 4, 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The editor's own swap - both ids kept, their values exchanged - saves through "
            + "the service, which relies on the deferred check")
    void theEditorSwapsTwoOptionsInOneSave() throws Exception {
        String merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                                 "options":["Size","Colour"],
                                 "variants":[{"values":["M","Black"],"stockQty":4},
                                             {"values":["L","Black"],"stockQty":6}]}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        String m = JsonPath.read(created, "$.data.variants[0].id");
        String l = JsonPath.read(created, "$.data.variants[1].id");

        mockMvc.perform(put("/marketplace/listings/{id}", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Cotton Crew Tee","categoryCode":"other","priceCents":1999,
                                 "options":["Size","Colour"],
                                 "variants":[{"id":"%s","values":["L","Black"]},
                                             {"id":"%s","values":["M","Black"]}]}"""
                                .formatted(m, l)))
                .andExpect(status().isOk());

        assertThat(optionKey(UUID.fromString(m))).isEqualTo("l,black");
        assertThat(optionKey(UUID.fromString(l))).isEqualTo("m,black");
        // An id-kept option keeps its stock: the typo-fix semantics.
        assertThat(variantStock(UUID.fromString(m))).isEqualTo(4);
        assertThat(variantStock(UUID.fromString(l))).isEqualTo(6);
        assertThat(listingRepository.findStockDrift()).isEmpty();
    }

    // ------------------------------------------------------------------
    // The drift sweep
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The drift sweep reads 0 on a clean database and on a consistent listing with "
            + "options, and 1 once an out-of-band UPDATE moves that listing's total")
    void driftSweepCountsAnOutOfBandTotal() {
        sweeper.sweep();
        assertThat(listingRepository.findStockDrift()).isEmpty();
        assertThat(driftGauge()).isZero();

        UUID listing = UUID.randomUUID();
        insertListing(listing, true, "Size", null, 10);
        insertVariant(UUID.randomUUID(), listing, "M", null, null, 4, 0);
        insertVariant(UUID.randomUUID(), listing, "L", null, null, 6, 1);
        UUID plain = UUID.randomUUID();
        insertListing(plain, false, null, null, 3);

        sweeper.sweep();
        assertThat(driftGauge()).isZero();

        jdbc.update("UPDATE listing SET stock_qty = stock_qty + 1 WHERE id = ?", listing);
        // A plain listing's column IS its stock: moving it is never drift.
        jdbc.update("UPDATE listing SET stock_qty = stock_qty + 1 WHERE id = ?", plain);

        sweeper.sweep();
        assertThat(listingRepository.findStockDrift()).containsExactly(listing);
        assertThat(driftGauge()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("The drift sweep also counts a discriminator that disagrees with the option "
            + "rows: options claimed with none stored, and option rows on a plain listing")
    void driftSweepCountsADiscriminatorThatDisagreesWithTheRows() {
        // Claims options, has none - and a total of 0, so only the "no option
        // rows" branch of the query can flag it.
        UUID claimsOptions = UUID.randomUUID();
        insertListing(claimsOptions, true, "Size", null, 0);
        // Plain, but carries an option row.
        UUID strayOption = UUID.randomUUID();
        insertListing(strayOption, false, null, null, 7);
        insertVariant(UUID.randomUUID(), strayOption, "M", null, null, 7, 0);

        sweeper.sweep();

        assertThat(listingRepository.findStockDrift())
                .containsExactlyInAnyOrder(claimsOptions, strayOption);
        assertThat(driftGauge()).isEqualTo(2.0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void assertRefusedBy(String constraint, ThrowingCallable write) {
        assertThatThrownBy(write)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private void insertListing(UUID id, boolean hasVariants, String option1, String option2,
                               int stockQty) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO listing (id, merchant_id, title, price_cents, currency, stock_qty,
                    status, created_at, updated_at, has_variants, option1_name, option2_name)
                VALUES (?, ?, 'Cotton Crew Tee', 1999, 'USD', ?, 'ACTIVE', ?, ?, ?, ?, ?)
                """, id, UUID.randomUUID(), stockQty, now, now, hasVariants, option1, option2);
    }

    private void insertVariant(UUID id, UUID listingId, String value1, String value2,
                               Long priceCents, int stockQty, int position) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO listing_variant (id, listing_id, option1_value, option2_value,
                    option_key, price_cents, stock_qty, position, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, listingId, value1, value2, VariantLabels.key(value1, value2),
                priceCents, stockQty, position, now, now);
    }

    private void insertCartLine(UUID buyer, UUID variantId, UUID listingId, int quantity) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO cart_variant_item (buyer_uuid, variant_id, listing_id, quantity,
                    added_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, buyer, variantId, listingId, quantity, now, now);
    }

    private UUID insertOrder() {
        UUID orderId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO market_order (id, order_ref, buyer_uuid, buyer_msisdn, status,
                    subtotal_cents, delivery_fee_cents, total_cents, currency, delivery_method,
                    paid_at, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, '+263771234567', 'PAID', 1999, 0, 1999, 'USD', 'COLLECTION',
                    ?, ?, ?, ?)
                """,
                orderId, "MKT-" + orderId.toString().substring(0, 12).replace("-", "").toUpperCase(),
                UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now.plusSeconds(3600)),
                Timestamp.from(now), Timestamp.from(now));
        return orderId;
    }

    private void insertOrderItem(UUID orderId, UUID listingId, UUID variantId, String variantLabel) {
        jdbc.update("""
                INSERT INTO market_order_item (id, order_id, listing_id, merchant_id,
                    title_snapshot, unit_price_cents, quantity, line_total_cents,
                    variant_id, variant_label)
                VALUES (?, ?, ?, ?, 'Cotton Crew Tee', 1999, 1, 1999, ?, ?)
                """, UUID.randomUUID(), orderId, listingId, UUID.randomUUID(), variantId, variantLabel);
    }

    private static String setValue(UUID variantId, String value1, String optionKey) {
        return "UPDATE listing_variant SET option1_value = '%s', option_key = '%s' WHERE id = '%s'"
                .formatted(value1, optionKey, variantId);
    }

    private String optionKey(UUID variantId) {
        return jdbc.queryForObject("SELECT option_key FROM listing_variant WHERE id = ?",
                String.class, variantId);
    }

    private int variantStock(UUID variantId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing_variant WHERE id = ?",
                Integer.class, variantId);
    }

    private double driftGauge() {
        var gauge = meterRegistry.find("marketplace.stock.aggregate_drift").gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
