package com.innbucks.marketplaceservice.report;

import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Report + moderation lifecycle on real Postgres: anonymous report POST is a
 * 401 (the catalog permitAll is GET-scoped — the SecuritySurfaceIT pin,
 * re-proven inside the flow), duplicate OPEN report 409s against the V7
 * partial unique index's app check, the SUPER_ADMIN queue reads oldest-first
 * with a live listing summary, and RESOLVE with deactivateListing flips the
 * listing INACTIVE with both audit rows written.
 */
class ModerationFlowIT extends PostgresTestContainer {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private ListingRepository listingRepository;

    private String merchantToken;
    private String customerToken;
    private String adminToken;

    @BeforeEach
    void mintTokens() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        customerToken = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        adminToken = TestJwts.superAdmin(UUID.randomUUID(), jwtSecret);
    }

    @Test
    void reportModerationLifecycle_resolveWithDeactivationFlipsListingInactive() throws Exception {
        String listingId = createActiveListing();

        // --- Anonymous report: 401 (permitAll on catalog is GET-scoped) -----
        mockMvc.perform(post("/marketplace/catalog/{id}/report", listingId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SCAM\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // --- Customer files a report (detail sanitized) ----------------------
        String created = mockMvc.perform(post("/marketplace/catalog/{id}/report", listingId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"COUNTERFEIT","detail":"Fake <b>logo</b> on the box"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CREATED"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.reason").value("COUNTERFEIT"))
                .andExpect(jsonPath("$.data.detail").value("Fake logo on the box"))
                .andExpect(jsonPath("$.data.listingTitle").value("Wireless Bluetooth Speaker"))
                .andReturn().getResponse().getContentAsString();
        String reportId = JsonPath.read(created, "$.data.id");

        // --- Duplicate OPEN report from the same user: 409 -------------------
        mockMvc.perform(post("/marketplace/catalog/{id}/report", listingId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SCAM\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("report_already_open"));

        // A DIFFERENT authenticated user (merchant role) may still report.
        mockMvc.perform(post("/marketplace/catalog/{id}/report", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"MISLEADING\"}"))
                .andExpect(status().isCreated());

        // --- Queue is SUPER_ADMIN-only; default filter OPEN, oldest first ----
        mockMvc.perform(get("/marketplace/reports")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/marketplace/reports")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(reportId)) // oldest first
                .andExpect(jsonPath("$.data.items[0].listingStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.items[0].listingTitle").value("Wireless Bluetooth Speaker"));

        // --- RESOLVE + deactivateListing: report closed, listing INACTIVE ----
        mockMvc.perform(patch("/marketplace/reports/{id}", reportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"RESOLVE","resolutionNote":"Confirmed counterfeit",
                                 "deactivateListing":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.listingStatus").value("INACTIVE"))
                .andExpect(jsonPath("$.data.resolutionNote").value("Confirmed counterfeit"));
        assertThat(listingRepository.findById(UUID.fromString(listingId)).orElseThrow().getStatus())
                .isEqualTo(ListingStatus.INACTIVE);
        // ...and it is gone from the public catalog.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("listing_not_found"));

        // Both audit rows landed: the resolution AND the status change.
        Integer resolvedAudits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'LISTING_REPORT_RESOLVED' AND target_id = ?",
                Integer.class, reportId);
        assertThat(resolvedAudits).isEqualTo(1);
        Integer statusAudits = jdbc.queryForObject("""
                SELECT count(*) FROM audit_events
                 WHERE event_type = 'LISTING_STATUS_CHANGED'
                   AND target_id = ? AND metadata LIKE '%"via":"moderation"%'
                """, Integer.class, listingId);
        assertThat(statusAudits).isEqualTo(1);
        Integer reportedAudits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE event_type = 'LISTING_REPORTED' AND target_id = ?",
                Integer.class, listingId);
        assertThat(reportedAudits).isEqualTo(2);

        // --- Closed reports are terminal: 409 report_not_open ----------------
        mockMvc.perform(patch("/marketplace/reports/{id}", reportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DISMISS\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("report_not_open"));

        // After their first report is closed the same customer may re-report.
        mockMvc.perform(post("/marketplace/catalog/{id}/report", listingId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"OTHER\",\"detail\":\"still listed elsewhere\"}"))
                .andExpect(status().isCreated());

        // --- DISMISS the merchant's report; status filter shows both sides ---
        String queue = mockMvc.perform(get("/marketplace/reports")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(2))
                .andReturn().getResponse().getContentAsString();
        String merchantReportId = JsonPath.read(queue, "$.data.items[0].id");
        // deactivateListing on a DISMISS is refused loudly.
        mockMvc.perform(patch("/marketplace/reports/{id}", merchantReportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DISMISS\",\"deactivateListing\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("deactivate_requires_resolve"));
        mockMvc.perform(patch("/marketplace/reports/{id}", merchantReportId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DISMISS\",\"resolutionNote\":\"Genuine product\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISMISSED"));
        mockMvc.perform(get("/marketplace/reports?status=DISMISSED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(merchantReportId));

        // Unknown report id and garbage status filter: clean specific errors.
        mockMvc.perform(patch("/marketplace/reports/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RESOLVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("report_not_found"));
        mockMvc.perform(get("/marketplace/reports?status=NONSENSE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_status"));
    }

    @Test
    void reportingAnUnknownListingIs404() throws Exception {
        mockMvc.perform(post("/marketplace/catalog/{id}/report", UUID.randomUUID())
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SCAM\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("listing_not_found"));
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private String createActiveListing() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Wireless Bluetooth Speaker","categoryCode":"tv-audio",
                                 "priceCents":2599,"stockQty":120}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String listingId = JsonPath.read(created, "$.data.id");
        mockMvc.perform(multipart(HttpMethod.PUT, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        return listingId;
    }
}
