package com.innbucks.marketplaceservice.seller;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DEFAULT configuration (V20): the cell switch for delivery-only sellers is
 * OFF, so nobody can turn collection off — and a refused first request
 * registers nobody. Separate from {@link CollectionToggleFlowIT}, which runs
 * with the switch on: the two prove opposite halves of the same rule and
 * cannot share a configuration.
 */
class CollectionToggleDefaultOffIT extends PostgresTestContainer {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    @DisplayName("With the switch at its default (off), turning collection off is 422 "
            + "delivery_only_disabled and registers nobody; keeping it on still answers 200")
    void theSwitchIsOffByDefault() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);

        mockMvc.perform(put("/marketplace/sellers/me/collection")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionEnabled\":false}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("delivery_only_disabled"))
                .andExpect(jsonPath("$.message").value("Turning collection off is not available yet"));
        // The operator is held to the same switch.
        mockMvc.perform(put("/marketplace/admin/sellers/{id}/collection", merchantId)
                        .header("Authorization", "Bearer " + TestJwts.superAdmin(
                                UUID.randomUUID(), jwtSecret))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionEnabled\":false}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("delivery_only_disabled"));
        mockMvc.perform(put("/marketplace/sellers/me/collection")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectionEnabled").value(true));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM marketplace_seller WHERE merchant_id = ?::uuid",
                Integer.class, merchantId.toString())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isZero();
    }
}
