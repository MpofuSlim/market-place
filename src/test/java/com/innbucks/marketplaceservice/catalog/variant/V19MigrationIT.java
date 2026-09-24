package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves V19 is purely ADDITIVE for the previous (V18) image, which is the
 * claim the whole rollout rests on: marketplace-service runs one replica with
 * maxSurge 1 and a 45s readiness delay, so the OLD pod serves against the V19
 * schema for at least a minute, and permanently after any image rollback. A
 * V19 that broke one of the old image's statements would fail every cart
 * write (or every order) for that window.
 *
 * <p>How: Flyway is driven DIRECTLY, not through the Spring context's schema,
 * against a side schema of the same database. Each test migrates it to 18,
 * seeds the rows a live cell already holds, migrates to 19, and then replays
 * the old image's SQL against the result:
 * <ul>
 *   <li>the {@code CartItemRepository} upserts, copied VERBATIM from the V18
 *       image (and byte-identical in V19, which is the point: cart_item is not
 *       altered, so {@code ON CONFLICT (buyer_uuid, listing_id)} keeps its
 *       arbiter);</li>
 *   <li>the pre-V19 {@code Listing} entity's INSERT and full-row UPDATE, which
 *       name every column the old entity mapped and none of V19's;</li>
 *   <li>the pre-V19 {@code MarketOrderItem} entity's INSERT, with no variant
 *       columns;</li>
 *   <li>the old JPQL reserve / restock / stock read, expressed as SQL.</li>
 * </ul>
 *
 * <p>The side schema is reached through a connection whose {@code search_path}
 * is ONLY that schema, so an unqualified name can never quietly resolve to the
 * Spring context's own tables in {@code public}. The URL and credentials come
 * from the Spring environment, i.e. whatever {@link PostgresTestContainer}
 * registered: the Testcontainer in CI.
 */
class V19MigrationIT extends PostgresTestContainer {

    private static final String SCHEMA = "v19mig";

    private static final UUID MERCHANT = UUID.fromString("9b0e1c52-6a3f-4d51-8f0e-2b7c5d1a4e60");
    private static final UUID BUYER = UUID.fromString("1f4d8a2c-3b6e-4c71-9a05-7e2d6b8c0f13");
    /** A listing, cart line, order and order line that existed BEFORE V19. */
    private static final UUID EXISTING_LISTING = UUID.fromString("0c6a1d9e-52b4-4f3a-8e17-4d9b2a6c7f01");
    private static final UUID EXISTING_ORDER = UUID.fromString("5e8b3f1a-9c2d-4a6e-b173-0f4c8d2e6a92");
    private static final UUID EXISTING_ORDER_ITEM = UUID.fromString("7a2c9e4b-1d6f-4b83-a5e0-3c9f7b1d2e48");

    private static final Instant SEEDED_AT = Instant.parse("2026-09-01T08:00:00Z");

    // ---------------------------------------------------------------------
    // The OLD image's statements, replayed as it would send them.
    // ---------------------------------------------------------------------

    /** CartItemRepository.addQuantity, VERBATIM from the V18 image. */
    private static final String OLD_CART_ADD_QUANTITY = """
            INSERT INTO cart_item (buyer_uuid, listing_id, quantity, added_at, updated_at)
            VALUES (:buyerUuid, :listingId, :quantity, :now, :now)
            ON CONFLICT (buyer_uuid, listing_id) DO UPDATE
                SET quantity   = LEAST(cart_item.quantity + EXCLUDED.quantity, :maxQuantity),
                    updated_at = EXCLUDED.updated_at
            """;

    /** CartItemRepository.setQuantity, VERBATIM from the V18 image. */
    private static final String OLD_CART_SET_QUANTITY = """
            INSERT INTO cart_item (buyer_uuid, listing_id, quantity, added_at, updated_at)
            VALUES (:buyerUuid, :listingId, :quantity, :now, :now)
            ON CONFLICT (buyer_uuid, listing_id) DO UPDATE
                SET quantity   = EXCLUDED.quantity,
                    updated_at = EXCLUDED.updated_at
            """;

    /** CartItemRepository.remove's JPQL delete, as SQL. */
    private static final String OLD_CART_REMOVE =
            "DELETE FROM cart_item WHERE buyer_uuid = :buyerUuid AND listing_id = :listingId";

    /** The pre-V19 Listing entity's INSERT: every column it mapped, and NONE
     *  of has_variants / option1_name / option2_name. */
    private static final String OLD_LISTING_INSERT = """
            insert into listing (area,category_code,city,condition,created_at,currency,description,\
            merchant_id,price_cents,rating_count,rating_sum,shop_id,status,stock_qty,title,updated_at,\
            version,id) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    /** The pre-V19 Listing entity's full-row UPDATE (changeStatus, touch, update
     *  and moderation all save through it). It writes stock_qty, but never V19's
     *  columns. */
    private static final String OLD_LISTING_UPDATE = """
            update listing set area=?,category_code=?,city=?,condition=?,created_at=?,currency=?,\
            description=?,merchant_id=?,price_cents=?,rating_count=?,rating_sum=?,shop_id=?,status=?,\
            stock_qty=?,title=?,updated_at=?,version=? where id=? and version=?""";

    /** The pre-V19 MarketOrderItem entity's INSERT: no variant_id, no variant_label. */
    private static final String OLD_ORDER_ITEM_INSERT = """
            insert into market_order_item (line_total_cents,listing_id,merchant_id,order_id,quantity,\
            title_snapshot,unit_price_cents,id) values (?,?,?,?,?,?,?,?)""";

    /** The old JPQL ListingRepository.reserveStock, as SQL: the ACTIVE +
     *  enough-stock guard, and no has_variants guard (the old image cannot see it). */
    private static final String OLD_RESERVE_STOCK = """
            UPDATE listing SET stock_qty = stock_qty - :q
             WHERE id = :id AND stock_qty >= :q AND status = 'ACTIVE'""";

    /** The old JPQL ListingRepository.restock, as SQL: deliberately no status guard. */
    private static final String OLD_RESTOCK =
            "UPDATE listing SET stock_qty = stock_qty + :q WHERE id = :id";

    /** The old JPQL ListingRepository.stockQtyOf, as SQL. */
    private static final String OLD_STOCK_QTY_OF = "SELECT stock_qty FROM listing WHERE id = :id";

    @Autowired
    private Environment environment;

    /** Connections pinned to {@link #SCHEMA} alone. */
    private DriverManagerDataSource sideSchema;
    private JdbcTemplate side;
    private NamedParameterJdbcTemplate sideNamed;

    @BeforeEach
    void migrateTo18SeedThenMigrateTo19() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);

        sideSchema = new DriverManagerDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
        sideSchema.setSchema(SCHEMA);
        side = new JdbcTemplate(sideSchema);
        sideNamed = new NamedParameterJdbcTemplate(side);

        MigrateResult to18 = flywayTo("18").migrate();
        assertThat(to18.success).isTrue();
        assertThat(to18.targetSchemaVersion).isEqualTo("18");
        assertThat(columnExists("listing", "has_variants"))
                .as("the seed must land on a genuinely pre-V19 schema").isFalse();

        seedTheRowsALiveCellAlreadyHolds();

        MigrateResult to19 = flywayTo("19").migrate();
        assertThat(to19.success).isTrue();
        assertThat(to19.migrationsExecuted).isEqualTo(1);
        assertThat(to19.targetSchemaVersion).isEqualTo("19");
    }

    @AfterEach
    void dropSideSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private Flyway flywayTo(String target) {
        return Flyway.configure()
                .dataSource(sideSchema)
                .schemas(SCHEMA)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    /** At V18, with plain JDBC: what production holds the moment V19 runs. */
    private void seedTheRowsALiveCellAlreadyHolds() {
        side.update("""
                INSERT INTO listing (id, merchant_id, title, price_cents, currency, stock_qty,
                    status, created_at, updated_at)
                VALUES (?, ?, 'Canvas sneakers', 1500, 'USD', 10, 'ACTIVE', ?, ?)""",
                EXISTING_LISTING, MERCHANT, ts(SEEDED_AT), ts(SEEDED_AT));
        side.update("""
                INSERT INTO cart_item (buyer_uuid, listing_id, quantity, added_at, updated_at)
                VALUES (?, ?, 2, ?, ?)""",
                BUYER, EXISTING_LISTING, ts(SEEDED_AT), ts(SEEDED_AT));
        side.update("""
                INSERT INTO market_order (id, order_ref, buyer_uuid, buyer_msisdn, status,
                    subtotal_cents, delivery_fee_cents, total_cents, currency, delivery_method,
                    expires_at, created_at, updated_at)
                VALUES (?, 'MKT-5E8B3F1A9C2D', ?, '+263771234567', 'PENDING_PAYMENT',
                    3000, 0, 3000, 'USD', 'COLLECTION', ?, ?, ?)""",
                EXISTING_ORDER, BUYER, ts(SEEDED_AT.plus(15, ChronoUnit.MINUTES)),
                ts(SEEDED_AT), ts(SEEDED_AT));
        side.update("""
                INSERT INTO market_order_item (id, order_id, listing_id, merchant_id, title_snapshot,
                    unit_price_cents, quantity, line_total_cents)
                VALUES (?, ?, ?, ?, 'Canvas sneakers', 1500, 2, 3000)""",
                EXISTING_ORDER_ITEM, EXISTING_ORDER, EXISTING_LISTING, MERCHANT);
    }

    // ---------------------------------------------------------------------
    // Existing rows
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("V19 backfills nothing: an existing listing is plain with no axes, an existing order line has no variant, the cart is untouched")
    void existingRowsReadAsVariantLess() {
        Map<String, Object> listing = side.queryForMap(
                "SELECT has_variants, option1_name, option2_name, stock_qty FROM listing WHERE id = ?",
                EXISTING_LISTING);
        assertThat(listing.get("has_variants")).isEqualTo(false);
        assertThat(listing.get("option1_name")).isNull();
        assertThat(listing.get("option2_name")).isNull();
        assertThat(listing.get("stock_qty")).isEqualTo(10);

        Map<String, Object> item = side.queryForMap(
                "SELECT variant_id, variant_label, title_snapshot FROM market_order_item WHERE id = ?",
                EXISTING_ORDER_ITEM);
        assertThat(item.get("variant_id")).isNull();
        assertThat(item.get("variant_label")).isNull();
        assertThat(item.get("title_snapshot")).isEqualTo("Canvas sneakers");

        assertThat(side.queryForObject(
                "SELECT quantity FROM cart_item WHERE buyer_uuid = ? AND listing_id = ?",
                Integer.class, BUYER, EXISTING_LISTING)).isEqualTo(2);

        // No variant, variant cart line or variant order line was ever
        // observed, so none is invented.
        assertThat(count("SELECT count(*) FROM listing_variant")).isZero();
        assertThat(count("SELECT count(*) FROM cart_variant_item")).isZero();
        assertThat(count("SELECT count(*) FROM listing WHERE has_variants")).isZero();
        assertThat(count("SELECT count(*) FROM market_order_item WHERE variant_id IS NOT NULL")).isZero();
    }

    // ---------------------------------------------------------------------
    // The old image's statements against the V19 schema
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("The old image's cart upserts still find their ON CONFLICT (buyer_uuid, listing_id) arbiter: cart_item is not altered")
    void oldCartUpsertsStillWork() {
        UUID secondListing = UUID.randomUUID();
        insertListingAsTheOldImage(secondListing, "ACTIVE", 4);
        Instant now = SEEDED_AT.plus(1, ChronoUnit.DAYS);

        // A new line: a plain insert.
        assertThat(sideNamed.update(OLD_CART_ADD_QUANTITY,
                cartParams(secondListing, 1, now).addValue("maxQuantity", 10))).isEqualTo(1);
        assertThat(cartQuantity(secondListing)).isEqualTo(1);

        // The existing V18 line: the conflict branch accumulates and clamps
        // (2 + 5 capped at 6), and added_at is left alone.
        assertThat(sideNamed.update(OLD_CART_ADD_QUANTITY,
                cartParams(EXISTING_LISTING, 5, now).addValue("maxQuantity", 6))).isEqualTo(1);
        assertThat(cartQuantity(EXISTING_LISTING)).isEqualTo(6);
        assertThat(side.queryForObject(
                "SELECT added_at FROM cart_item WHERE buyer_uuid = ? AND listing_id = ?",
                Timestamp.class, BUYER, EXISTING_LISTING).toInstant()).isEqualTo(SEEDED_AT);

        // The stepper's exact set, through the same arbiter.
        assertThat(sideNamed.update(OLD_CART_SET_QUANTITY,
                cartParams(EXISTING_LISTING, 3, now))).isEqualTo(1);
        assertThat(cartQuantity(EXISTING_LISTING)).isEqualTo(3);

        // Remove stays idempotent.
        MapSqlParameterSource remove = new MapSqlParameterSource()
                .addValue("buyerUuid", BUYER).addValue("listingId", secondListing);
        assertThat(sideNamed.update(OLD_CART_REMOVE, remove)).isEqualTo(1);
        assertThat(sideNamed.update(OLD_CART_REMOVE, remove)).isZero();

        assertThat(count("SELECT count(*) FROM cart_item")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM cart_variant_item")).isZero();
    }

    @Test
    @DisplayName("The old image's listing INSERT and full-row UPDATE, which name no V19 column, succeed and leave a plain listing")
    void oldListingWritesStillWork() {
        UUID created = UUID.randomUUID();
        assertThat(insertListingAsTheOldImage(created, "DRAFT", 7)).isEqualTo(1);

        Map<String, Object> row = side.queryForMap(
                "SELECT has_variants, option1_name, option2_name, stock_qty FROM listing WHERE id = ?",
                created);
        assertThat(row.get("has_variants")).isEqualTo(false);
        assertThat(row.get("option1_name")).isNull();
        assertThat(row.get("option2_name")).isNull();
        assertThat(row.get("stock_qty")).isEqualTo(7);

        // changeStatus on the listing that existed before V19: the old entity
        // save writes every column it maps, stock_qty included, version-checked.
        Instant now = SEEDED_AT.plus(2, ChronoUnit.DAYS);
        assertThat(side.update(OLD_LISTING_UPDATE,
                null, "other", null, "NEW", ts(SEEDED_AT), "USD", null,
                MERCHANT, 1500L, 0, 0L, null, "INACTIVE",
                10, "Canvas sneakers", ts(now), 1L,
                EXISTING_LISTING, 0L)).isEqualTo(1);

        Map<String, Object> updated = side.queryForMap(
                "SELECT status, version, has_variants, option1_name FROM listing WHERE id = ?",
                EXISTING_LISTING);
        assertThat(updated.get("status")).isEqualTo("INACTIVE");
        assertThat(updated.get("version")).isEqualTo(1L);
        assertThat(updated.get("has_variants")).isEqualTo(false);
        assertThat(updated.get("option1_name")).isNull();
    }

    @Test
    @DisplayName("The old image's order-line INSERT, with no variant columns, satisfies chk_order_item_variant_snapshot and reads variant-less")
    void oldOrderItemInsertStillWorks() {
        UUID itemId = UUID.randomUUID();
        assertThat(side.update(OLD_ORDER_ITEM_INSERT,
                1500L, EXISTING_LISTING, MERCHANT, EXISTING_ORDER, 1,
                "Canvas sneakers", 1500L, itemId)).isEqualTo(1);

        Map<String, Object> row = side.queryForMap(
                "SELECT variant_id, variant_label FROM market_order_item WHERE id = ?", itemId);
        assertThat(row.get("variant_id")).isNull();
        assertThat(row.get("variant_label")).isNull();
        assertThat(count("SELECT count(*) FROM market_order_item WHERE order_id = ?",
                EXISTING_ORDER)).isEqualTo(2);
    }

    @Test
    @DisplayName("The old image's reserve, restock and stock read still move a plain listing, and the reserve guard still refuses an oversell")
    void oldStockStatementsStillWork() {
        assertThat(sideNamed.update(OLD_RESERVE_STOCK, stock(EXISTING_LISTING, 3))).isEqualTo(1);
        assertThat(stockOf(EXISTING_LISTING)).isEqualTo(7);

        // Oversell: the guarded UPDATE matches nothing and moves nothing.
        assertThat(sideNamed.update(OLD_RESERVE_STOCK, stock(EXISTING_LISTING, 8))).isZero();
        assertThat(stockOf(EXISTING_LISTING)).isEqualTo(7);

        // Cancel/expiry release: no status guard.
        assertThat(sideNamed.update(OLD_RESTOCK, stock(EXISTING_LISTING, 3))).isEqualTo(1);
        assertThat(stockOf(EXISTING_LISTING)).isEqualTo(10);

        // A listing that is not ACTIVE is never reserved from.
        UUID draft = UUID.randomUUID();
        insertListingAsTheOldImage(draft, "DRAFT", 5);
        assertThat(sideNamed.update(OLD_RESERVE_STOCK, stock(draft, 1))).isZero();
        assertThat(stockOf(draft)).isEqualTo(5);
    }

    // ---------------------------------------------------------------------
    // V19's own CHECKs
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("chk_listing_variant_axes: a variant listing must name its first axis, and a plain listing may name none")
    void listingAxesCheck() {
        // Every has_variants x option1 x option2 combination, on the listing
        // that existed before V19. Each UPDATE sets all three, so order is
        // irrelevant.
        assertAxesAccepted(false, null, null);
        assertAxesAccepted(true, "Size", null);
        assertAxesAccepted(true, "Size", "Colour");

        assertAxesRefused(true, null, null);
        assertAxesRefused(true, null, "Colour");
        assertAxesRefused(false, "Size", null);
        assertAxesRefused(false, null, "Colour");
        assertAxesRefused(false, "Size", "Colour");
    }

    @Test
    @DisplayName("chk_order_item_variant_snapshot: a variant id and its label are recorded together or not at all")
    void orderItemVariantSnapshotCheck() {
        UUID variantId = UUID.randomUUID();

        assertThatThrownBy(() -> insertOrderItem(variantId, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_order_item_variant_snapshot");
        assertThatThrownBy(() -> insertOrderItem(null, "M - Black"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_order_item_variant_snapshot");

        assertThat(insertOrderItem(variantId, "M - Black")).isEqualTo(1);
        assertThat(insertOrderItem(null, null)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM market_order_item WHERE variant_id IS NOT NULL"))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // The old pod restarting mid-rollout
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("An old pod restarting mid-rollout still boots: Flyway with only V1..V18 validates a V19 schema and applies nothing")
    void oldMigrationSetAcceptsAV19Schema(@TempDir Path oldImageMigrations) throws IOException {
        int copied = 0;
        for (Resource migration : new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql")) {
            String name = migration.getFilename();
            if (name != null && Integer.parseInt(name.substring(1, name.indexOf("__"))) <= 18) {
                try (InputStream in = migration.getInputStream()) {
                    Files.copy(in, oldImageMigrations.resolve(name));
                }
                copied++;
            }
        }
        assertThat(copied).isEqualTo(18);

        Flyway oldImage = Flyway.configure()
                .dataSource(sideSchema)
                .schemas(SCHEMA)
                .locations("filesystem:" + oldImageMigrations.toAbsolutePath())
                .load();

        // V19 is applied but unknown to this image: a "future" migration,
        // which Flyway's default ignoreMigrationPatterns (*:future) tolerates.
        assertThat(oldImage.validateWithResult().validationSuccessful).isTrue();
        MigrateResult result = oldImage.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isZero();
        assertThat(count(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '19' AND success"))
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private int insertListingAsTheOldImage(UUID id, String status, int stockQty) {
        return side.update(OLD_LISTING_INSERT,
                null, "other", null, "NEW", ts(SEEDED_AT), "USD", null,
                MERCHANT, 2500L, 0, 0L, null, status,
                stockQty, "Leather belt", ts(SEEDED_AT), 0L, id);
    }

    private int insertOrderItem(UUID variantId, String variantLabel) {
        return side.update("""
                INSERT INTO market_order_item (id, order_id, listing_id, merchant_id, title_snapshot,
                    unit_price_cents, quantity, line_total_cents, variant_id, variant_label)
                VALUES (?, ?, ?, ?, 'Canvas sneakers', 1500, 1, 1500, ?, ?)""",
                UUID.randomUUID(), EXISTING_ORDER, EXISTING_LISTING, MERCHANT,
                variantId, variantLabel);
    }

    private void assertAxesAccepted(boolean hasVariants, String option1, String option2) {
        assertThat(setAxes(hasVariants, option1, option2))
                .as("has_variants=%s option1=%s option2=%s", hasVariants, option1, option2)
                .isEqualTo(1);
    }

    private void assertAxesRefused(boolean hasVariants, String option1, String option2) {
        assertThatThrownBy(() -> setAxes(hasVariants, option1, option2))
                .as("has_variants=%s option1=%s option2=%s", hasVariants, option1, option2)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_listing_variant_axes");
    }

    private int setAxes(boolean hasVariants, String option1, String option2) {
        return side.update(
                "UPDATE listing SET has_variants = ?, option1_name = ?, option2_name = ? WHERE id = ?",
                hasVariants, option1, option2, EXISTING_LISTING);
    }

    private MapSqlParameterSource cartParams(UUID listingId, int quantity, Instant now) {
        return new MapSqlParameterSource()
                .addValue("buyerUuid", BUYER)
                .addValue("listingId", listingId)
                .addValue("quantity", quantity)
                .addValue("now", ts(now));
    }

    private static MapSqlParameterSource stock(UUID listingId, int q) {
        return new MapSqlParameterSource().addValue("id", listingId).addValue("q", q);
    }

    private Integer stockOf(UUID listingId) {
        return sideNamed.queryForObject(OLD_STOCK_QTY_OF,
                new MapSqlParameterSource("id", listingId), Integer.class);
    }

    private Integer cartQuantity(UUID listingId) {
        return side.queryForObject(
                "SELECT quantity FROM cart_item WHERE buyer_uuid = ? AND listing_id = ?",
                Integer.class, BUYER, listingId);
    }

    private boolean columnExists(String table, String column) {
        return count("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema = ? AND table_name = ? AND column_name = ?""",
                SCHEMA, table, column) > 0;
    }

    private long count(String sql, Object... args) {
        Long n = side.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }
}
