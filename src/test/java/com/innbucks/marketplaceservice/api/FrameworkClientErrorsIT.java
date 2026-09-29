package com.innbucks.marketplaceservice.api;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC's own client errors render with their NATIVE status in the
 * {@code ApiResult} envelope over the REAL security chain — they used to fall
 * into {@code GlobalExceptionHandler}'s catch-all as {@code 500 INTERNAL_ERROR}
 * and an ERROR log line. Specific status codes only (fleet rule).
 *
 * <p>Missing {@code @RequestParam} / {@code @RequestHeader} are pinned in
 * {@code GlobalExceptionHandlerTest}: no production endpoint requires either
 * today, so there is nothing real to aim this class at.
 */
class FrameworkClientErrorsIT extends PostgresTestContainer {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Test
    void wrongVerbIs405WithAllowAndTheEnvelope() throws Exception {
        String customer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(delete("/marketplace/categories")
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("method_not_allowed"))
                .andExpect(jsonPath("$.message").value("That HTTP method is not supported on this path"));
    }

    @Test
    void anonymousWrongVerbOnAProtectedPathIsStillTheFilterChains401() throws Exception {
        // The security chain answers before handler mapping: an anonymous
        // caller learns nothing about which verbs a path supports.
        mockMvc.perform(delete("/marketplace/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void wrongContentTypeIs415WithTheEnvelope() throws Exception {
        String customer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("listingId=abc"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", containsString("application/json")))
                .andExpect(jsonPath("$.code").value("unsupported_media_type"))
                .andExpect(jsonPath("$.message").value("That Content-Type is not supported on this path"));
    }

    @Test
    void unsatisfiableAcceptIs406AndTheBodyIsStillJson() throws Exception {
        mockMvc.perform(get("/marketplace/categories").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("not_acceptable"));
    }

    @Test
    void multipartListingCreateWithoutTheListingPartIs400MissingPart() throws Exception {
        String merchant = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(multipart("/marketplace/listings")
                        .file(new MockMultipartFile("image", "photo.png", "image/png", new byte[] {1, 2, 3}))
                        .header("Authorization", "Bearer " + merchant))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_part"))
                .andExpect(jsonPath("$.message").value("Required part 'listing' is missing"));
    }

    @Test
    void methodSecurity403IsUnchanged() throws Exception {
        String customer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Wireless Bluetooth Speaker",
                                 "description":"Portable speaker with 12h battery life.",
                                 "categoryCode":"electronics","priceCents":2599,"stockQty":120}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"));
    }

    @Test
    void previouslyMappedFrameworkErrorsKeepTheirCodes() throws Exception {
        String customer = TestJwts.customer(UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is malformed"));
        mockMvc.perform(get("/marketplace/no-such-path")
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.message").value("Not found"));
    }
}
