package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The window AFTER an order commits, end to end against real Postgres.
 *
 * <p>Failures are injected with Postgres triggers rather than Spring test
 * beans, so the application under test is exactly the production wiring (and
 * the shared context cache is not split): a trigger that refuses DELETEs on
 * {@code cart_item} breaks the post-commit cart clean-up, and one that refuses
 * completing an {@code idempotency_record} strands the claim IN_PROGRESS the
 * way a crash between commit and storing the replay body would.
 *
 * <p>Before the fix the first case answered 500 with the order committed and
 * the claim stranded, and the second case's takeover re-ran the creation into
 * {@code uq_order_idempotency_key} and answered 500 on every retry.
 */
class OrderIdempotencyRecoveryIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String merchantToken;
    private String customerToken;
    private String listingId;

    @BeforeEach
    void publishListing() throws Exception {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Solar Lantern 20W","categoryCode":"electronics",
                                 "priceCents":1550,"stockQty":10}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void dropFaults() {
        jdbc.execute("DROP TRIGGER IF EXISTS qw_fail_cart_delete ON cart_item");
        jdbc.execute("DROP TRIGGER IF EXISTS qw_fail_claim_complete ON idempotency_record");
        jdbc.execute("DROP FUNCTION IF EXISTS qw_fail_cart_delete()");
        jdbc.execute("DROP FUNCTION IF EXISTS qw_fail_claim_complete()");
    }

    @Test
    void aCartCleanupFailureAfterCommitStillAnswers201AndTheRetryReplaysIt() throws Exception {
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":2}".formatted(listingId)))
                .andExpect(status().isOk());
        jdbc.execute("""
                CREATE FUNCTION qw_fail_cart_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'injected: cart clean-up unavailable'; END $$""");
        jdbc.execute("""
                CREATE TRIGGER qw_fail_cart_delete BEFORE DELETE ON cart_item
                FOR EACH ROW EXECUTE FUNCTION qw_fail_cart_delete()""");

        String first = createFromCart("post-commit-cart-blip")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andReturn().getResponse().getContentAsString();

        // The claim was completed with the replay body BEFORE the clean-up ran.
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_record", Integer.class))
                .isEqualTo(201);
        // The lost clean-up leaves the line for the shopper - the better failure.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cart_item", Integer.class)).isEqualTo(1);

        // A retry (the client never trusted the first answer) replays the
        // ORIGINAL bytes - no 409, no second order, no second reservation.
        String retried = createFromCart("post-commit-cart-blip")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(retried).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order", Integer.class)).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void aStrandedClaimIsTakenOverAndReplaysTheCommittedOrderInsteadOf500() throws Exception {
        // Completing the claim fails: the order commits, the replay body is
        // never stored, and the claim stays IN_PROGRESS - the crash window.
        jdbc.execute("""
                CREATE FUNCTION qw_fail_claim_complete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                  IF NEW.status <> 0 THEN RAISE EXCEPTION 'injected: replay store unavailable'; END IF;
                  RETURN NEW;
                END $$""");
        jdbc.execute("""
                CREATE TRIGGER qw_fail_claim_complete BEFORE UPDATE ON idempotency_record
                FOR EACH ROW EXECUTE FUNCTION qw_fail_claim_complete()""");

        String first = createItems("stranded-claim")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(first, "$.data.id");
        String orderRef = JsonPath.read(first, "$.data.orderRef");
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_record", Integer.class))
                .isZero();
        jdbc.execute("DROP TRIGGER qw_fail_claim_complete ON idempotency_record");

        // Inside the grace the claim still reads as in flight.
        createItems("stranded-claim")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request_in_flight"));

        // Past the grace, the retry takes the claim over, finds the order the
        // dead request committed, and replays it.
        jdbc.update("UPDATE idempotency_record SET created_at = now() - interval '2 minutes'");
        String recovered = createItems("stranded-claim")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.id").value(orderId))
                .andExpect(jsonPath("$.data.orderRef").value(orderRef))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.totalCents").value(3100))
                .andReturn().getResponse().getContentAsString();

        // The claim is now complete, and from here every retry is a plain
        // byte-for-byte replay of what the recovery answered.
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_record", Integer.class))
                .isEqualTo(201);
        assertThat(createItems("stranded-claim").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).isEqualTo(recovered);

        // One order, stock reserved exactly once, one ORDER_CREATED audit row,
        // and the recovery on the chain as itself (the byte-identical replay
        // after it adds nothing).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM market_order", Integer.class)).isEqualTo(1);
        assertThat(stock()).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'ORDER_CREATED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'ORDER_CREATE_RECOVERED'",
                Integer.class)).isEqualTo(1);

        // Same key, different body is still refused - the claim's fingerprint
        // survived the recovery.
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "stranded-claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"buyerMsisdn\":\"+263771234567\",\"items\":[{\"listingId\":\"%s\","
                                + "\"quantity\":1}]}").formatted(listingId)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("idempotency_key_reuse"));
    }

    private ResultActions createFromCart(String key) throws Exception {
        return mockMvc.perform(post("/marketplace/orders")
                .header("Authorization", "Bearer " + customerToken)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"buyerMsisdn\":\"+263771234567\",\"fromCart\":true}"));
    }

    private ResultActions createItems(String key) throws Exception {
        return mockMvc.perform(post("/marketplace/orders")
                .header("Authorization", "Bearer " + customerToken)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"buyerMsisdn\":\"+263771234567\",\"items\":[{\"listingId\":\"%s\","
                        + "\"quantity\":2}]}").formatted(listingId)));
    }

    private int stock() {
        return jdbc.queryForObject("SELECT stock_qty FROM listing WHERE id = ?::uuid",
                Integer.class, listingId);
    }
}
