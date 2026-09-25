package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The V19 drift sweep, driven through the SPRING BEAN — deliberately not a
 * {@code new VariantStockDriftSweeper(...)}.
 *
 * <p>The first cut returned {@code int}. ShedLock's default {@code PROXY_METHOD}
 * mode refuses a locked method returning a primitive, so every scheduled run
 * threw {@code LockingNotSupportedException} before the query and the gauge
 * sat at 0 forever: the alert the rollback runbook leans on could never fire.
 * A unit test over a hand-built instance skips the proxy and stays green
 * through exactly that bug, so this one goes through the context.
 */
class VariantStockDriftSweeperIT extends PostgresTestContainer {

    private static final String OPTIONS_BODY = """
            {
              "title": "Cotton Crew Tee",
              "description": "100% cotton, pre-shrunk",
              "categoryCode": "other",
              "priceCents": 1999,
              "options": ["Size"],
              "variants": [
                { "values": ["M"], "stockQty": 4 },
                { "values": ["L"], "stockQty": 6 }
              ]
            }""";

    private static final String PLAIN_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private VariantStockDriftSweeper sweeper;

    @Autowired
    private MeterRegistry meterRegistry;

    private String merchantToken;

    @BeforeEach
    void mintToken() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
    }

    @Test
    @DisplayName("The sweep runs through its ShedLock proxy, reports an out-of-band drift, and a movement heals it")
    void driftIsReportedThroughTheProxyAndHealedByTheNextMovement() throws Exception {
        String optionsListing = create(OPTIONS_BODY);
        String plainListing = create(PLAIN_BODY);

        // Healthy: the total is the sum of the options, and a plain listing
        // has no options to disagree with.
        sweeper.sweep();
        assertThat(driftGauge()).isZero();
        assertThat(stockOf(optionsListing)).isEqualTo(10);

        // An out-of-band write - an older image after a rollback moving the
        // listing's own column - is the only way this service's totals drift.
        jdbc.update("UPDATE listing SET stock_qty = stock_qty - 3 WHERE id = ?::uuid",
                optionsListing);
        // A plain listing's column IS its stock: moving it is never drift.
        jdbc.update("UPDATE listing SET stock_qty = 2 WHERE id = ?::uuid", plainListing);

        sweeper.sweep();
        assertThat(driftGauge()).isEqualTo(1.0);
        // Reported, never repaired: the sweep changed nothing.
        assertThat(stockOf(optionsListing)).isEqualTo(7);

        // The next movement recomputes the total under the listing lock.
        String variantId = firstVariantId(optionsListing);
        mockMvc.perform(patch("/marketplace/listings/{id}/variants/{variantId}/stock",
                                optionsListing, variantId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stockQty\":5}"))
                .andExpect(status().isOk());
        assertThat(stockOf(optionsListing)).isEqualTo(11);

        sweeper.sweep();
        assertThat(driftGauge()).isZero();
    }

    @Test
    @DisplayName("The operator repair SQL in the sweeper's javadoc heals every drifted listing")
    void theDocumentedRepairHealsDrift() throws Exception {
        String optionsListing = create(OPTIONS_BODY);
        jdbc.update("UPDATE listing SET stock_qty = 0 WHERE id = ?::uuid", optionsListing);
        sweeper.sweep();
        assertThat(driftGauge()).isEqualTo(1.0);

        jdbc.update("""
                UPDATE listing l SET stock_qty = (SELECT COALESCE(SUM(v.stock_qty), 0)
                  FROM listing_variant v WHERE v.listing_id = l.id) WHERE l.has_variants""");

        sweeper.sweep();
        assertThat(driftGauge()).isZero();
        assertThat(stockOf(optionsListing)).isEqualTo(10);
    }

    private String create(String body) throws Exception {
        String json = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.id");
    }

    private String firstVariantId(String listingId) {
        return jdbc.queryForObject(
                "SELECT id::text FROM listing_variant WHERE listing_id = ?::uuid ORDER BY position LIMIT 1",
                String.class, listingId);
    }

    private int stockOf(String listingId) {
        return jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, listingId);
    }

    private double driftGauge() {
        var gauge = meterRegistry.find("marketplace.stock.aggregate_drift").gauge();
        return gauge == null ? -1 : gauge.value();
    }
}
