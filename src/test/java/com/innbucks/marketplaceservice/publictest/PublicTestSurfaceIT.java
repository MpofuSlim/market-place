package com.innbucks.marketplaceservice.publictest;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public test surface with the switch ON, over the real security chain and
 * a real Postgres.
 *
 * <p>Two things are being proved, and the second matters more than the first:
 * that the pre-checkout journey genuinely works without a token, and that the
 * surface's boundary holds — no order, no payment, no reach into another
 * buyer's data.
 */
@TestPropertySource(properties = "marketplace.public-test.enabled=true")
class PublicTestSurfaceIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    private static final String ADDRESS_BODY = """
            {
              "label": "Home",
              "recipientName": "Tariro Moyo",
              "recipientMsisdn": "0771234567",
              "line1": "14 Samora Machel Ave",
              "city": "Harare",
              "area": "Avondale",
              "landmark": "Opposite the clinic, blue gate"
            }""";

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String merchantToken;

    @BeforeEach
    void mintMerchantToken() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
    }

    @Test
    @DisplayName("browse -> cart -> address -> quote, with no token at any point")
    void thePreCheckoutJourneyWorksUnauthenticated() throws Exception {
        String listingId = publishListing();

        // Browse was already public; it is here to show the journey starts
        // where the app starts, not at a special test endpoint.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk());

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/cart/items", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":2}".formatted(listingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuantity").value(2))
                .andExpect(jsonPath("$.data.subtotalCents").value(3100))
                // The same live resolution the authenticated cart does — this
                // surface calls the real service, so it cannot drift from it.
                .andExpect(jsonPath("$.data.items[0].listing.title").value("Solar Lantern 20W"));

        // The cart holds NO stock, here as anywhere else.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(10));

        String savedAddress = mockMvc.perform(post("/marketplace/public/buyers/{handle}/addresses", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.defaultAddress").value(true))
                // Normalised server-side exactly as on the real surface —
                // nothing is ever sent to it from here.
                .andExpect(jsonPath("$.data.recipientMsisdn").value("+263771234567"))
                .andReturn().getResponse().getContentAsString();
        String addressId = JsonPath.read(savedAddress, "$.data.id");

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/checkout/quote", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                                .formatted(addressId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtotalCents").value(3100))
                .andExpect(jsonPath("$.data.checkoutReady").value(true));

        // The quote reserved nothing — the whole reason it exists.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(10));
    }

    @Test
    void theOrderRailIsOffOnAnUNGATEDCellEvenThoughTheSurfaceIsOn() throws Exception {
        // This class runs enabled-but-ungated (no api-key property), which is
        // the state an operator lands in by setting one env var and stopping.
        // The cart above works. Ordering must not: it reserves a merchant's
        // real stock and writes the number payment-service will prompt to pay,
        // and an operator mid-provisioning is far more likely than a deliberate
        // choice to take orders from anyone on the internet.
        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"buyerMsisdn":"0771234567"}"""))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/orders", "alice"))
                .andExpect(status().isNotFound());

        UUID someId = UUID.randomUUID();
        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders/{o}/cancel", "alice", someId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders/{o}/fulfilments/{f}/received",
                        "alice", someId, someId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/marketplace/public/buyers/{handle}/orders/{o}/fulfilments/{f}/collect-code",
                        "alice", someId, someId))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRealOrderEndpointIsStillRefusedWithoutAToken() throws Exception {
        mockMvc.perform(post("/marketplace/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oneHandleCannotSeeAnothersBasket() throws Exception {
        String listingId = publishListing();

        mockMvc.perform(post("/marketplace/public/buyers/{handle}/cart/items", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":1}".formatted(listingId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/cart", "bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineCount").value(0));
    }

    @Test
    void aHandlesAddressIsNotReadableByAnotherHandle() throws Exception {
        String saved = mockMvc.perform(post("/marketplace/public/buyers/{handle}/addresses", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String addressId = JsonPath.read(saved, "$.data.id");

        // Owner-masked 404, the same answer the authenticated surface gives —
        // not a 403, which would confirm the address exists.
        mockMvc.perform(get("/marketplace/public/buyers/{handle}/addresses/{id}", "bob", addressId))
                .andExpect(status().isNotFound());
    }

    @Test
    void theHandleNeverReachesTheDatabaseInItsTypedForm() throws Exception {
        mockMvc.perform(post("/marketplace/public/buyers/{handle}/addresses", "alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS_BODY))
                .andExpect(status().isCreated());

        String storedBuyer = jdbc.queryForObject(
                "SELECT buyer_uuid::text FROM delivery_address LIMIT 1", String.class);

        assertThat(storedBuyer).isEqualTo(PublicTestIdentity.derivedUuid("alice").toString());
        // The derived id is version 5; every real customer's is version 4, so
        // this row can never be mistaken for — or collide with — a real one.
        assertThat(UUID.fromString(storedBuyer).version()).isEqualTo(5);
    }

    @Test
    void aBlankOrOverLongHandleIsRefused() throws Exception {
        mockMvc.perform(get("/marketplace/public/buyers/{handle}/cart", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_handle"));

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/cart", "x".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_handle"));
    }

    @Test
    void favoritesAndTheirListingRoundTrip() throws Exception {
        String listingId = publishListing();

        mockMvc.perform(put("/marketplace/public/buyers/{handle}/favorites/{id}", "alice", listingId))
                .andExpect(status().isOk());
        // Idempotent, like its authenticated twin.
        mockMvc.perform(put("/marketplace/public/buyers/{handle}/favorites/{id}", "alice", listingId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/favorites", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));

        mockMvc.perform(delete("/marketplace/public/buyers/{handle}/favorites/{id}", "alice", listingId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/marketplace/public/buyers/{handle}/favorites", "alice"))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    void checkoutOptionsNeedNoHandle() throws Exception {
        mockMvc.perform(get("/marketplace/public/checkout/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentMethods").isArray());
    }

    @Test
    void theSurfaceGrantsNoSellerOrOperatorReach() throws Exception {
        // The derived caller is CUSTOMER and nothing else, so none of the
        // merchant or operator surfaces become reachable by going through it.
        mockMvc.perform(get("/marketplace/listings/mine"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/settlements/summary"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/marketplace/reports"))
                .andExpect(status().isUnauthorized());
    }

    private String publishListing() throws Exception {
        String created = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
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
