package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A merchant's FIRST write creates their seller record, and two first writes
 * can arrive at once (a double-tapped "create listing", two portal tabs saving
 * a payout destination). Against real Postgres: both succeed, the record exists
 * once, and the registration is audited once — never a 500 on the record's
 * primary key, never a second {@code SELLER_REGISTERED} for a registration that
 * rolled back.
 */
class SellerRecordConcurrencyIT extends PostgresTestContainer {

    private static final String LISTING = """
            {"title":"Solar Lantern 20W","categoryCode":"electronics",
             "priceCents":1550,"stockQty":10}""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    private UUID merchantId;
    private String merchantToken;

    @BeforeEach
    void mintToken() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
    }

    @Test
    @DisplayName("Two concurrent first listing creates: both 201, one seller record, one registration")
    void concurrentFirstListings() throws Exception {
        assertThat(sellerRows()).isZero();

        List<Integer> statuses = together(
                () -> post("/marketplace/listings").content(LISTING),
                () -> post("/marketplace/listings").content(LISTING));

        assertThat(statuses).containsExactly(201, 201);
        assertThat(sellerRows()).isEqualTo(1);
        assertThat(registrations()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM listing WHERE merchant_id = ?::uuid",
                Integer.class, merchantId.toString())).isEqualTo(2);
    }

    @Test
    @DisplayName("Two concurrent first payout destinations: both 200, one seller record, one "
            + "registration, and the destination is a whole one of the two")
    void concurrentFirstPayoutDestinations() throws Exception {
        List<Integer> statuses = together(
                () -> put("/marketplace/sellers/me/payout-destination").content("""
                        {"method":"MOBILE_MONEY","accountName":"Rudo Chikwanha",
                         "msisdn":"0771234567"}"""),
                () -> put("/marketplace/sellers/me/payout-destination").content("""
                        {"method":"BANK","accountName":"Rudo Traders",
                         "bankName":"CBZ Bank","accountNumber":"01123456789012"}"""));

        assertThat(statuses).containsExactly(200, 200);
        assertThat(sellerRows()).isEqualTo(1);
        assertThat(registrations()).isEqualTo(1);
        // Replace, never merge: whichever write landed last, the row holds ONE
        // complete destination (the V13/V17 CHECK would refuse a mixture).
        String method = jdbc.queryForObject(
                "SELECT payout_method FROM marketplace_seller WHERE merchant_id = ?::uuid",
                String.class, merchantId.toString());
        assertThat(method).isIn("MOBILE_MONEY", "BANK");
    }

    @Test
    @DisplayName("A refused first listing registers nobody: validation runs before the record")
    void aRefusedFirstListingRegistersNobody() throws Exception {
        mockMvc.perform(authed(post("/marketplace/listings").content("""
                        {"title":"<img src=x>","categoryCode":"electronics",
                         "priceCents":1550,"stockQty":10}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("title_invalid"));

        assertThat(sellerRows()).isZero();
        assertThat(registrations()).isZero();
    }

    @Test
    @DisplayName("A refused first payout destination registers nobody")
    void aRefusedFirstPayoutDestinationRegistersNobody() throws Exception {
        mockMvc.perform(authed(put("/marketplace/sellers/me/payout-destination").content("""
                        {"method":"MOBILE_MONEY","accountName":"Rudo Chikwanha","msisdn":"12"}""")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_msisdn"));

        assertThat(sellerRows()).isZero();
        assertThat(registrations()).isZero();
    }

    // ------------------------------------------------------------------

    /** Fires every request at the same moment and returns their statuses in order. */
    @SafeVarargs
    private List<Integer> together(Callable<MockHttpServletRequestBuilder>... requests)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.length);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (Callable<MockHttpServletRequestBuilder> request : requests) {
                results.add(pool.submit(() -> {
                    MockHttpServletRequestBuilder builder = authed(request.call());
                    start.await();
                    return mockMvc.perform(builder).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + merchantToken)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private int sellerRows() {
        return jdbc.queryForObject("SELECT count(*) FROM marketplace_seller WHERE merchant_id = ?::uuid",
                Integer.class, merchantId.toString());
    }

    private int registrations() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                 WHERE event_type = 'SELLER_REGISTERED' AND target_id = ?""",
                Integer.class, merchantId.toString());
    }
}
