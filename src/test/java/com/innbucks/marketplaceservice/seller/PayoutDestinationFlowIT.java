package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where a seller's money goes, end to end over the real security chain and a
 * real Postgres — including the DB CHECK that makes a half-destination
 * unrepresentable, which no mocked repository can prove.
 */
class PayoutDestinationFlowIT extends PostgresTestContainer {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private UUID merchantId;
    private String merchantToken;
    private String adminToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
    }

    @Test
    @DisplayName("A seller sets their own destination, and reads it back in full")
    void theSellerSetsAndReadsTheirOwn() throws Exception {
        // Nothing on file yet — a normal 200, because the screen's job is to
        // ask for one, and a 404 would read as "no such seller".
        mockMvc.perform(get("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.method").doesNotExist());

        mockMvc.perform(put("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"MOBILE_MONEY","accountName":"Rudo Chikwanha",
                                 "msisdn":"0771234567"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.msisdn").value("+263771234567"));

        // Read back UNMASKED: they typed it, and "is this the right account?"
        // is the one question this screen exists to answer.
        mockMvc.perform(get("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.accountName").value("Rudo Chikwanha"))
                .andExpect(jsonPath("$.data.msisdn").value("+263771234567"))
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

        // The change is in the tamper-evident chain — "who moved it, when" is
        // what a redirected payout is investigated with.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                 WHERE event_type = 'SELLER_PAYOUT_DESTINATION_CHANGED'""", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Switching rails clears the other's columns — the DB CHECK holds")
    void switchingRailsLeavesNoHalfDestination() throws Exception {
        setDestination(merchantToken, """
                {"method":"BANK","accountName":"Rudo Chikwanha","bankName":"CBZ Bank",
                 "accountNumber":"01123456789012"}""");
        assertThat(jdbc.queryForObject(
                "SELECT payout_account_number FROM marketplace_seller WHERE merchant_id = ?::uuid",
                String.class, merchantId.toString())).isEqualTo("01123456789012");

        setDestination(merchantToken, """
                {"method":"MOBILE_MONEY","accountName":"Rudo Chikwanha","msisdn":"0771234567"}""");

        // chk_seller_payout_destination would have refused the row outright if
        // the bank columns had survived — this asserts they did not.
        assertThat(jdbc.queryForMap("""
                SELECT payout_method, payout_msisdn, payout_bank_name, payout_account_number
                  FROM marketplace_seller WHERE merchant_id = ?::uuid""",
                merchantId.toString()))
                .containsEntry("payout_method", "MOBILE_MONEY")
                .containsEntry("payout_msisdn", "+263771234567")
                .containsEntry("payout_bank_name", null)
                .containsEntry("payout_account_number", null);
    }

    @Test
    @DisplayName("One rail's complete details with NO method are refused by the DB (V17)")
    void detailsWithoutAMethodAreRefused() throws Exception {
        // The GET creates the seller row (ensureExists) with no destination:
        // all five columns NULL. Without the row every UPDATE below would
        // match nothing and the failure would point at the wrong thing.
        mockMvc.perform(get("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false));
        String id = merchantId.toString();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM marketplace_seller WHERE merchant_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);

        // V13's CHECK let EXACTLY these two through: one rail's COMPLETE
        // details with no method. With payout_method NULL that branch's method
        // comparison is UNKNOWN and every other conjunct is TRUE, so the whole
        // expression is UNKNOWN, and a CHECK that evaluates to UNKNOWN passes.
        // (A partial set was always refused: FALSE AND UNKNOWN is FALSE.)
        // Raw SQL on purpose: no app path produces this shape, so only the
        // constraint can stop it. Both of these succeed on a V13-only schema.
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE marketplace_seller
                   SET payout_account_name = 'Rudo Chikwanha', payout_msisdn = '+263771234567'
                 WHERE merchant_id = ?::uuid""", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_seller_payout_destination");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE marketplace_seller
                   SET payout_account_name = 'Rudo Chikwanha', payout_bank_name = 'CBZ Bank',
                       payout_account_number = '01123456789012'
                 WHERE merchant_id = ?::uuid""", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_seller_payout_destination");

        // Nothing landed: the row is still the honest "none on file".
        assertThat(jdbc.queryForMap("""
                SELECT payout_method, payout_account_name, payout_msisdn, payout_bank_name,
                       payout_account_number
                  FROM marketplace_seller WHERE merchant_id = ?::uuid""", id))
                .allSatisfy((column, value) -> assertThat(value).as(column).isNull());

        // Shapes V13 already refused stay refused, BY THIS constraint (the
        // column has a second CHECK, on the method's values, so the name is
        // asserted): a stray partial detail, and a method with its account
        // missing, which is the dangerous one because it reads as configured.
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE marketplace_seller SET payout_account_number = '01123456789012'
                 WHERE merchant_id = ?::uuid""", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_seller_payout_destination");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE marketplace_seller
                   SET payout_method = 'MOBILE_MONEY', payout_account_name = 'Rudo Chikwanha'
                 WHERE merchant_id = ?::uuid""", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_seller_payout_destination");

        // And the legal shapes are untouched: a complete destination is
        // stored, then cleared back to none.
        assertThat(jdbc.update("""
                UPDATE marketplace_seller
                   SET payout_method = 'MOBILE_MONEY', payout_account_name = 'Rudo Chikwanha',
                       payout_msisdn = '+263771234567'
                 WHERE merchant_id = ?::uuid""", id)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT payout_method, payout_msisdn FROM marketplace_seller
                 WHERE merchant_id = ?::uuid""", id))
                .containsEntry("payout_method", "MOBILE_MONEY")
                .containsEntry("payout_msisdn", "+263771234567");
        assertThat(jdbc.update("""
                UPDATE marketplace_seller
                   SET payout_method = NULL, payout_account_name = NULL, payout_msisdn = NULL
                 WHERE merchant_id = ?::uuid""", id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payout_method FROM marketplace_seller WHERE merchant_id = ?::uuid",
                String.class, id)).isNull();
    }

    @Test
    @DisplayName("A field the chosen method needs is a 400 naming it — nothing is stored")
    void anIncompleteDestinationIsRefused() throws Exception {
        mockMvc.perform(put("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"BANK","accountName":"Rudo Chikwanha",
                                 "bankName":"CBZ Bank"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("payout_field_required"));

        mockMvc.perform(get("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.configured").value(false));
    }

    @Test
    @DisplayName("An operator can set one on a seller's behalf, and it is recorded as theirs")
    void theOperatorOverrideIsAttributed() throws Exception {
        mockMvc.perform(put("/marketplace/admin/sellers/{id}/payout-destination", merchantId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"BANK","accountName":"Rudo Chikwanha","bankName":"CBZ Bank",
                                 "accountNumber":"01123456789012"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true));

        // The seller sees what the operator entered on their own screen.
        mockMvc.perform(get("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.bankName").value("CBZ Bank"))
                .andExpect(jsonPath("$.data.accountNumber").value("01123456789012"));

        // ...and the audit says it was not them, which is the difference
        // between "we did this for you" and "someone did this".
        String metadata = jdbc.queryForObject("""
                SELECT metadata::text FROM audit_events
                 WHERE event_type = 'SELLER_PAYOUT_DESTINATION_CHANGED'""", String.class);
        assertThat(metadata).contains("bySeller").contains("false");
        // ...and the account itself is NOT in the audit log. An account number
        // there is an account number in one more place.
        assertThat(metadata).doesNotContain("01123456789012").doesNotContain("CBZ");
    }

    @Test
    @DisplayName("The seller's money summary says whether they can be paid at all")
    void theMoneySummaryFlagsAMissingDestination() throws Exception {
        // The one screen a seller is on when the answer matters to them, and
        // the only place they would learn they need to provide one.
        mockMvc.perform(get("/marketplace/settlements/summary")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payoutDestinationConfigured").value(false));

        setDestination(merchantToken, """
                {"method":"MOBILE_MONEY","accountName":"Rudo Chikwanha","msisdn":"0771234567"}""");

        mockMvc.perform(get("/marketplace/settlements/summary")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(jsonPath("$.data.payoutDestinationConfigured").value(true));
    }

    @Test
    @DisplayName("The payout report carries the destination — the sheet finance pays from")
    void thePayoutReportCarriesTheDestination() throws Exception {
        setDestination(merchantToken, """
                {"method":"BANK","accountName":"Rudo Chikwanha","bankName":"CBZ Bank",
                 "accountNumber":"01123456789012"}""");

        String csv = mockMvc.perform(get("/marketplace/settlements/payout-report")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No releasable money yet, so the merchant is not owed anything and
        // does not appear — but the COLUMNS are there for when they are.
        assertThat(csv).startsWith("merchantId,displayName,parcels,netCents,currency,"
                + "payoutMethod,payoutAccountName,payoutMsisdn,payoutBankName,"
                + "payoutAccountNumber,payoutChangedAt\n");
        assertThat(csv).doesNotContain(merchantId.toString());

        // The operator can read the destination directly, unmasked — a masked
        // account number cannot be paid into.
        mockMvc.perform(get("/marketplace/admin/sellers/{id}/payout-destination", merchantId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountNumber").value("01123456789012"))
                .andExpect(jsonPath("$.data.bankName").value("CBZ Bank"));
    }

    private void setDestination(String token, String body) throws Exception {
        mockMvc.perform(put("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
