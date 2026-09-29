package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V21 — the CONTRACT half of V20 — against the rows a live cell holds the moment
 * it runs, on real Postgres.
 *
 * <p>How: Flyway is driven directly against a side schema (the V19MigrationIT
 * pattern). Each test migrates to 20, seeds what the V19 and PR A images leave
 * behind (orders with no seller rows, parcels with no method, a mixed order PR A
 * could never write but V21 must accept, stray values), migrates to 21 and
 * asserts: the stragglers are backfilled, NOT NULL holds, the foreign key and
 * the CHECKs refuse what they exist to refuse, and the PR A image's own SQL
 * still commits. The two refusals V21 raises instead of rewriting money or
 * guessing a method each get a test of their own, proving the migration stops
 * with nothing applied.
 */
class V21MigrationIT extends PostgresTestContainer {

    private static final String SCHEMA = "v21mig";
    private static final Instant SEEDED_AT = Instant.parse("2026-09-20T08:00:00Z");

    /** A V19-created COLLECTION order, paid on a V19 replica: no seller row, NULL parcel method. */
    private static final UUID V19_COLLECTION_ORDER = UUID.fromString("0a1b2c3d-1111-4a6e-b173-0f4c8d2e6a01");
    /** A pre-V20 DELIVERY order with two sellers, paid before V20: no rows, NULL methods. */
    private static final UUID OLD_DELIVERY_ORDER = UUID.fromString("0a1b2c3d-2222-4a6e-b173-0f4c8d2e6a02");
    /** A PR A order, already consistent: seller row + stamped parcel. */
    private static final UUID PR_A_ORDER = UUID.fromString("0a1b2c3d-3333-4a6e-b173-0f4c8d2e6a03");
    /** An unpaid V19 order: no seller rows, no parcels yet. */
    private static final UUID UNPAID_V19_ORDER = UUID.fromString("0a1b2c3d-4444-4a6e-b173-0f4c8d2e6a04");
    /** A parcel whose seller has no item on the order (defence-in-depth step). */
    private static final UUID ORPHAN_PARCEL_ORDER = UUID.fromString("0a1b2c3d-5555-4a6e-b173-0f4c8d2e6a05");

    private static final UUID M_COLLECT = UUID.fromString("9b0e1c52-0001-4d51-8f0e-2b7c5d1a4e60");
    private static final UUID M_DELIVER_1 = UUID.fromString("9b0e1c52-0002-4d51-8f0e-2b7c5d1a4e60");
    private static final UUID M_DELIVER_2 = UUID.fromString("9b0e1c52-0003-4d51-8f0e-2b7c5d1a4e60");
    private static final UUID M_PR_A = UUID.fromString("9b0e1c52-0004-4d51-8f0e-2b7c5d1a4e60");
    private static final UUID M_UNPAID = UUID.fromString("9b0e1c52-0005-4d51-8f0e-2b7c5d1a4e60");
    private static final UUID M_ITEM = UUID.fromString("9b0e1c52-0006-4d51-8f0e-2b7c5d1a4e60");
    private static final UUID M_NO_ITEM = UUID.fromString("9b0e1c52-0007-4d51-8f0e-2b7c5d1a4e60");

    /** The V19 image's OrderFulfilmentRepository.openIfAbsent, VERBATIM: no delivery_method. */
    private static final String V19_OPEN_IF_ABSENT = """
            INSERT INTO order_fulfilment
                (id, order_id, merchant_id, status, delivery_fee_cents,
                 tracking_code, created_at, updated_at, version)
            VALUES (:id, :orderId, :merchantId, 'PREPARING', :deliveryFeeCents,
                    :trackingCode, :now, :now, 0)
            ON CONFLICT (order_id, merchant_id) DO NOTHING
            """;

    /** The PR A (V20) image's openIfAbsent, VERBATIM: names the parcel's method. */
    private static final String PR_A_OPEN_IF_ABSENT = """
            INSERT INTO order_fulfilment
                (id, order_id, merchant_id, status, delivery_fee_cents, delivery_method,
                 tracking_code, created_at, updated_at, version)
            VALUES (:id, :orderId, :merchantId, 'PREPARING', :deliveryFeeCents, :deliveryMethod,
                    :trackingCode, :now, :now, 0)
            ON CONFLICT (order_id, merchant_id) DO NOTHING
            """;

    /** The PR A MarketOrderSeller entity's INSERT (Hibernate, @IdClass). */
    private static final String PR_A_SELLER_INSERT =
            "insert into market_order_seller (delivery_method,merchant_id,order_id) values (?,?,?)";

    @Autowired
    private Environment environment;

    private DriverManagerDataSource sideSchema;
    private JdbcTemplate side;
    private NamedParameterJdbcTemplate sideNamed;

    @BeforeEach
    void migrateTo20() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        sideSchema = new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        sideSchema.setSchema(SCHEMA);
        side = new JdbcTemplate(sideSchema);
        sideNamed = new NamedParameterJdbcTemplate(side);

        MigrateResult to20 = flywayTo("20").migrate();
        assertThat(to20.success).isTrue();
        assertThat(to20.targetSchemaVersion).isEqualTo("20");
        assertThat(isNullable("order_fulfilment", "delivery_method"))
                .as("the seed must land on the EXPAND schema, where the column is nullable")
                .isTrue();
    }

    @AfterEach
    void dropSideSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    // ---------------------------------------------------------------------
    // The backfill
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Stragglers are backfilled: every (order, seller) with items gets a row, every parcel a method, and NOT NULL holds")
    void backfillsTheStragglers() {
        seedTheCell();

        migrateTo21();

        // An order with NO seller rows at all - paid, and unpaid - now has one per seller.
        assertThat(sellerMethod(V19_COLLECTION_ORDER, M_COLLECT)).isEqualTo("COLLECTION");
        assertThat(sellerMethod(OLD_DELIVERY_ORDER, M_DELIVER_1)).isEqualTo("DELIVERY");
        assertThat(sellerMethod(OLD_DELIVERY_ORDER, M_DELIVER_2)).isEqualTo("DELIVERY");
        assertThat(sellerMethod(UNPAID_V19_ORDER, M_UNPAID)).isEqualTo("COLLECTION");
        // A PR A row is left exactly as it was.
        assertThat(sellerMethod(PR_A_ORDER, M_PR_A)).isEqualTo("DELIVERY");
        assertThat(count("SELECT count(*) FROM market_order_seller WHERE order_id = ?", PR_A_ORDER))
                .isEqualTo(1);

        // Every parcel carries its method, from its seller row.
        assertThat(parcelMethod(V19_COLLECTION_ORDER, M_COLLECT)).isEqualTo("COLLECTION");
        assertThat(parcelMethod(OLD_DELIVERY_ORDER, M_DELIVER_1)).isEqualTo("DELIVERY");
        assertThat(parcelMethod(OLD_DELIVERY_ORDER, M_DELIVER_2)).isEqualTo("DELIVERY");
        assertThat(parcelMethod(PR_A_ORDER, M_PR_A)).isEqualTo("DELIVERY");
        // A parcel whose seller has no item still gets the order's method, and
        // a seller row so the foreign key can hold (defence in depth).
        assertThat(parcelMethod(ORPHAN_PARCEL_ORDER, M_NO_ITEM)).isEqualTo("COLLECTION");
        assertThat(sellerMethod(ORPHAN_PARCEL_ORDER, M_NO_ITEM)).isEqualTo("COLLECTION");
        assertThat(sellerMethod(ORPHAN_PARCEL_ORDER, M_ITEM)).isEqualTo("COLLECTION");

        // Money was not touched.
        assertThat(side.queryForObject("SELECT delivery_fee_cents FROM order_fulfilment "
                        + "WHERE order_id = ? AND merchant_id = ?", Long.class,
                OLD_DELIVERY_ORDER, M_DELIVER_1)).isEqualTo(300L);

        assertThat(isNullable("order_fulfilment", "delivery_method")).isFalse();
        assertThat(count("SELECT count(*) FROM order_fulfilment WHERE delivery_method IS NULL"))
                .isZero();
    }

    @Test
    @DisplayName("Stray values are cleared V17-style: a code on a DELIVERY parcel, a position on a COLLECTION parcel - nothing else")
    void clearsMeaninglessStrays() {
        seedTheCell();
        // A collection code on a delivery parcel (PR A stamped DELIVERY), and a
        // courier position on a collection parcel (NULL method, backfilled to
        // COLLECTION). Neither can happen through the code; both must go before
        // the CHECKs can be added.
        side.update("""
                UPDATE order_fulfilment SET collect_code_hash = 'deadbeef', collect_code_issued_at = ?,
                       collect_code_attempts = 2
                 WHERE order_id = ? AND merchant_id = ?""", ts(SEEDED_AT), PR_A_ORDER, M_PR_A);
        side.update("""
                UPDATE order_fulfilment SET last_latitude = -17.829220, last_longitude = 31.053961,
                       last_accuracy_m = 12, last_location_at = ?, last_location_by = 'driver'
                 WHERE order_id = ? AND merchant_id = ?""", ts(SEEDED_AT), V19_COLLECTION_ORDER, M_COLLECT);
        // Legitimate values on the right kind of parcel stay.
        side.update("""
                UPDATE order_fulfilment SET last_latitude = -20.150000, last_longitude = 28.580000,
                       last_location_at = ?
                 WHERE order_id = ? AND merchant_id = ?""", ts(SEEDED_AT), OLD_DELIVERY_ORDER, M_DELIVER_1);

        migrateTo21();

        Map<String, Object> code = side.queryForMap("""
                SELECT collect_code_hash, collect_code_issued_at, collect_code_attempts
                  FROM order_fulfilment WHERE order_id = ? AND merchant_id = ?""", PR_A_ORDER, M_PR_A);
        assertThat(code.get("collect_code_hash")).isNull();
        assertThat(code.get("collect_code_issued_at")).isNull();
        // Not governed by the CHECK, and the only trace of what happened.
        assertThat(code.get("collect_code_attempts")).isEqualTo(2);

        Map<String, Object> strayPosition = side.queryForMap("""
                SELECT last_latitude, last_longitude, last_accuracy_m, last_location_at, last_location_by
                  FROM order_fulfilment WHERE order_id = ? AND merchant_id = ?""",
                V19_COLLECTION_ORDER, M_COLLECT);
        assertThat(strayPosition.values()).containsOnlyNulls();

        assertThat(side.queryForObject("SELECT last_location_at IS NOT NULL FROM order_fulfilment "
                        + "WHERE order_id = ? AND merchant_id = ?", Boolean.class,
                OLD_DELIVERY_ORDER, M_DELIVER_1)).isTrue();
    }

    // ---------------------------------------------------------------------
    // The constraints
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("The foreign key refuses a parcel whose method differs from its seller row - on INSERT and on UPDATE")
    void foreignKeyRefusesADisagreeingParcel() {
        seedTheCell();
        migrateTo21();

        // UNPAID_V19_ORDER's backfilled seller row says COLLECTION.
        assertThatThrownBy(() -> openWithPrA(UNPAID_V19_ORDER, M_UNPAID, "DELIVERY", 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_fulfilment_seller_method");
        assertThatThrownBy(() -> side.update("""
                UPDATE order_fulfilment SET delivery_method = 'DELIVERY'
                 WHERE order_id = ? AND merchant_id = ?""", V19_COLLECTION_ORDER, M_COLLECT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_fulfilment_seller_method");
        // A seller row a parcel points at cannot be switched under it either.
        assertThatThrownBy(() -> side.update("""
                UPDATE market_order_seller SET delivery_method = 'DELIVERY'
                 WHERE order_id = ? AND merchant_id = ?""", V19_COLLECTION_ORDER, M_COLLECT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_fulfilment_seller_method");
    }

    @Test
    @DisplayName("The CHECKs refuse a code on a delivery, a position on a collection and a fee on a collection; the right kind of parcel still takes each")
    void checksRefuseTheWrongParcel() {
        seedTheCell();
        migrateTo21();

        // A code: COLLECTION yes, DELIVERY no.
        assertThat(side.update("""
                UPDATE order_fulfilment SET collect_code_hash = 'abc'
                 WHERE order_id = ? AND merchant_id = ?""", V19_COLLECTION_ORDER, M_COLLECT)).isEqualTo(1);
        assertThatThrownBy(() -> side.update("""
                UPDATE order_fulfilment SET collect_code_hash = 'abc'
                 WHERE order_id = ? AND merchant_id = ?""", PR_A_ORDER, M_PR_A))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_fulfilment_code_on_collection");

        // A position: DELIVERY yes, COLLECTION no - and not even a lone
        // accuracy or author, which chk_fulfilment_location_complete allows.
        assertThat(side.update("""
                UPDATE order_fulfilment SET last_latitude = -17.8, last_longitude = 31.0,
                       last_location_at = now()
                 WHERE order_id = ? AND merchant_id = ?""", PR_A_ORDER, M_PR_A)).isEqualTo(1);
        assertThatThrownBy(() -> side.update("""
                UPDATE order_fulfilment SET last_latitude = -17.8, last_longitude = 31.0,
                       last_location_at = now()
                 WHERE order_id = ? AND merchant_id = ?""", V19_COLLECTION_ORDER, M_COLLECT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_fulfilment_location_on_delivery");
        assertThatThrownBy(() -> side.update("""
                UPDATE order_fulfilment SET last_location_by = 'driver'
                 WHERE order_id = ? AND merchant_id = ?""", V19_COLLECTION_ORDER, M_COLLECT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_fulfilment_location_on_delivery");

        // A fee: V20's CHECK, now over a column that can never be NULL.
        assertThatThrownBy(() -> side.update("""
                UPDATE order_fulfilment SET delivery_fee_cents = 100
                 WHERE order_id = ? AND merchant_id = ?""", V19_COLLECTION_ORDER, M_COLLECT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_fulfilment_collection_no_fee");
    }

    // ---------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("The PR A image's order and payment SQL still commits on V21; the V19 image's parcel INSERT does not (rollback is roll-forward)")
    void prAImageStillWorksAndV19CannotConfirm() {
        seedTheCell();
        migrateTo21();

        // PR A creating an order: the seller row, then (at PAID) the parcel
        // from that row.
        UUID order = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        insertOrder(order, "MKT-PRA000000001", "DELIVERY", "PAID", 500);
        insertItem(order, merchant);
        assertThat(side.update(PR_A_SELLER_INSERT, "DELIVERY", merchant, order)).isEqualTo(1);
        assertThat(openWithPrA(order, merchant, "DELIVERY", 500)).isEqualTo(1);
        // A replayed confirm is still a no-op.
        assertThat(openWithPrA(order, merchant, "DELIVERY", 500)).isZero();

        // The V19 image cannot confirm a payment any more: its INSERT omits the
        // column. This is why V21 waits for PR A to be 100% rolled out.
        UUID lateOrder = UUID.randomUUID();
        UUID lateMerchant = UUID.randomUUID();
        insertOrder(lateOrder, "MKT-V19000000001", "COLLECTION", "PAID", 0);
        insertItem(lateOrder, lateMerchant);
        side.update(PR_A_SELLER_INSERT, "COLLECTION", lateMerchant, lateOrder);
        assertThatThrownBy(() -> sideNamed.update(V19_OPEN_IF_ABSENT, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("orderId", lateOrder)
                .addValue("merchantId", lateMerchant)
                .addValue("deliveryFeeCents", 0L)
                .addValue("trackingCode", "TRK-V19000001")
                .addValue("now", ts(Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("delivery_method");
    }

    @Test
    @DisplayName("A MIXED order - which only the next API can create - is exactly what V21 accepts: two sellers, two methods, two parcels")
    void aMixedOrderIsRepresentable() {
        migrateTo21();

        UUID order = UUID.randomUUID();
        UUID delivers = UUID.randomUUID();
        UUID collects = UUID.randomUUID();
        insertOrder(order, "MKT-MIX000000001", "DELIVERY", "PAID", 800);
        insertItem(order, delivers);
        insertItem(order, collects);
        side.update(PR_A_SELLER_INSERT, "DELIVERY", delivers, order);
        side.update(PR_A_SELLER_INSERT, "COLLECTION", collects, order);

        assertThat(openWithPrA(order, delivers, "DELIVERY", 800)).isEqualTo(1);
        assertThat(openWithPrA(order, collects, "COLLECTION", 0)).isEqualTo(1);
        // ...and the collected half still cannot carry the other half's fee.
        side.update("DELETE FROM order_fulfilment WHERE order_id = ? AND merchant_id = ?", order, collects);
        assertThatThrownBy(() -> openWithPrA(order, collects, "COLLECTION", 800))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_fulfilment_collection_no_fee");
    }

    // ---------------------------------------------------------------------
    // The refusals
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("GUARD: a NULL-method parcel on a COLLECTION order carrying a fee stops the migration - money is never rewritten, nothing is applied")
    void refusesToBackfillAFeeOntoACollection() {
        seedTheCell();
        side.update("UPDATE order_fulfilment SET delivery_fee_cents = 250 WHERE order_id = ? AND merchant_id = ?",
                V19_COLLECTION_ORDER, M_COLLECT);

        assertThatThrownBy(() -> flywayTo("21").migrate())
                .hasMessageContaining("V21 refused")
                .hasMessageContaining("1 order_fulfilment row(s)")
                .hasMessageContaining("delivery fee");

        // Rolled back whole: still V20, the column still nullable, the parcel
        // untouched and no seller row invented.
        assertThat(isNullable("order_fulfilment", "delivery_method")).isTrue();
        assertThat(side.queryForObject("SELECT delivery_fee_cents FROM order_fulfilment "
                        + "WHERE order_id = ? AND merchant_id = ?", Long.class,
                V19_COLLECTION_ORDER, M_COLLECT)).isEqualTo(250L);
        assertThat(count("SELECT count(*) FROM market_order_seller WHERE order_id = ?",
                V19_COLLECTION_ORDER)).isZero();
        assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '21' AND success"))
                .isZero();
    }

    @Test
    @DisplayName("GUARD: a parcel whose method disagrees with its seller row stops the migration by name, before the foreign key")
    void refusesADisagreeingParcel() {
        seedTheCell();
        // The expand schema has no foreign key, so this is storable at V20.
        side.update("UPDATE order_fulfilment SET delivery_method = 'COLLECTION', delivery_fee_cents = 0 "
                + "WHERE order_id = ? AND merchant_id = ?", PR_A_ORDER, M_PR_A);

        assertThatThrownBy(() -> flywayTo("21").migrate())
                .hasMessageContaining("V21 refused")
                .hasMessageContaining("different from their market_order_seller row");
        assertThat(isNullable("order_fulfilment", "delivery_method")).isTrue();
    }

    // ---------------------------------------------------------------------
    // Seed: what a V20 cell holds
    // ---------------------------------------------------------------------

    private void seedTheCell() {
        // 1. V19 created + paid, COLLECTION: no seller row, NULL parcel method.
        insertOrder(V19_COLLECTION_ORDER, "MKT-A00000000001", "COLLECTION", "PAID", 0);
        insertItem(V19_COLLECTION_ORDER, M_COLLECT);
        openLikeV19(V19_COLLECTION_ORDER, M_COLLECT, 0);

        // 2. Pre-V20 DELIVERY order, two sellers, NULL methods, one with a fee.
        insertOrder(OLD_DELIVERY_ORDER, "MKT-A00000000002", "DELIVERY", "PAID", 300);
        insertItem(OLD_DELIVERY_ORDER, M_DELIVER_1);
        insertItem(OLD_DELIVERY_ORDER, M_DELIVER_2);
        openLikeV19(OLD_DELIVERY_ORDER, M_DELIVER_1, 300);
        openLikeV19(OLD_DELIVERY_ORDER, M_DELIVER_2, 0);

        // 3. PR A: seller row written at creation, parcel stamped at PAID.
        insertOrder(PR_A_ORDER, "MKT-A00000000003", "DELIVERY", "PAID", 500);
        insertItem(PR_A_ORDER, M_PR_A);
        side.update(PR_A_SELLER_INSERT, "DELIVERY", M_PR_A, PR_A_ORDER);
        openWithPrA(PR_A_ORDER, M_PR_A, "DELIVERY", 500);

        // 4. V19 created, not yet paid: no seller rows, no parcels.
        insertOrder(UNPAID_V19_ORDER, "MKT-A00000000004", "COLLECTION", "PENDING_PAYMENT", 0);
        insertItem(UNPAID_V19_ORDER, M_UNPAID);

        // 5. A parcel for a seller with no item on its order.
        insertOrder(ORPHAN_PARCEL_ORDER, "MKT-A00000000005", "COLLECTION", "PAID", 0);
        insertItem(ORPHAN_PARCEL_ORDER, M_ITEM);
        openLikeV19(ORPHAN_PARCEL_ORDER, M_ITEM, 0);
        openLikeV19(ORPHAN_PARCEL_ORDER, M_NO_ITEM, 0);
    }

    private void insertOrder(UUID id, String ref, String method, String status, long feeCents) {
        boolean delivery = "DELIVERY".equals(method);
        side.update("""
                INSERT INTO market_order (id, order_ref, buyer_uuid, buyer_msisdn, status,
                    subtotal_cents, delivery_fee_cents, total_cents, currency, delivery_method,
                    delivery_recipient_name, delivery_recipient_msisdn, delivery_line1, delivery_city,
                    paid_at, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, '+263771234567', ?, 1000, ?, ?, 'USD', ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, ref, UUID.randomUUID(), status, feeCents, 1000 + feeCents, method,
                delivery ? "Tariro Moyo" : null, delivery ? "+263771234567" : null,
                delivery ? "14 Samora Machel Ave" : null, delivery ? "Harare" : null,
                "PAID".equals(status) ? ts(SEEDED_AT) : null,
                ts(SEEDED_AT.plus(15, ChronoUnit.MINUTES)), ts(SEEDED_AT), ts(SEEDED_AT));
    }

    private void insertItem(UUID orderId, UUID merchantId) {
        side.update("""
                INSERT INTO market_order_item (id, order_id, listing_id, merchant_id, title_snapshot,
                    unit_price_cents, quantity, line_total_cents)
                VALUES (?, ?, ?, ?, 'Solar Lantern 20W', 500, 1, 500)""",
                UUID.randomUUID(), orderId, UUID.randomUUID(), merchantId);
    }

    private int openLikeV19(UUID orderId, UUID merchantId, long feeCents) {
        return sideNamed.update(V19_OPEN_IF_ABSENT, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("orderId", orderId)
                .addValue("merchantId", merchantId)
                .addValue("deliveryFeeCents", feeCents)
                .addValue("trackingCode", trackingCode())
                .addValue("now", ts(SEEDED_AT)));
    }

    private int openWithPrA(UUID orderId, UUID merchantId, String method, long feeCents) {
        return sideNamed.update(PR_A_OPEN_IF_ABSENT, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("orderId", orderId)
                .addValue("merchantId", merchantId)
                .addValue("deliveryFeeCents", feeCents)
                .addValue("deliveryMethod", method)
                .addValue("trackingCode", trackingCode())
                .addValue("now", ts(SEEDED_AT)));
    }

    // ---------------------------------------------------------------------

    private void migrateTo21() {
        MigrateResult to21 = flywayTo("21").migrate();
        assertThat(to21.success).isTrue();
        assertThat(to21.migrationsExecuted).isEqualTo(1);
        assertThat(to21.targetSchemaVersion).isEqualTo("21");
    }

    private Flyway flywayTo(String target) {
        return Flyway.configure()
                .dataSource(sideSchema)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private String sellerMethod(UUID orderId, UUID merchantId) {
        List<String> rows = side.queryForList("SELECT delivery_method FROM market_order_seller "
                + "WHERE order_id = ? AND merchant_id = ?", String.class, orderId, merchantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String parcelMethod(UUID orderId, UUID merchantId) {
        return side.queryForObject("SELECT delivery_method FROM order_fulfilment "
                + "WHERE order_id = ? AND merchant_id = ?", String.class, orderId, merchantId);
    }

    private boolean isNullable(String table, String column) {
        return "YES".equals(side.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
                String.class, SCHEMA, table, column));
    }

    private long count(String sql, Object... args) {
        Long n = side.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static int codes;

    private static String trackingCode() {
        return "TRK-" + String.format("%010d", ++codes);
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
