package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

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
 * The WHOLE buyer journey over real HTTP semantics (MockMvc through the real
 * SecurityFilterChain) and a real Postgres: browse, cart, saved address,
 * checkout quote, order, payment confirmed by the payments service over the
 * S2S surface, the seller dispatching, and the buyer confirming receipt.
 *
 * <p>Deliberately ONE long test rather than a dozen isolated ones: the point is
 * that the steps compose — a cart that becomes a quote that becomes an order
 * that becomes a parcel — and a suite of independent cases would each set up a
 * shape the previous step never actually produces.
 */
class CheckoutJourneyIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10,
              "deliveryTowns": [{ "townCode": "harare", "feeCents": 0 }]
            }""";

    private static final String ADDRESS_BODY = """
            {
              "label": "Home",
              "recipientName": "Tariro Moyo",
              "recipientMsisdn": "0771234567",
              "line1": "14 Samora Machel Ave",
              "line2": "Flat 3B",
              "city": "Harare",
              "area": "Avondale",
              "landmark": "Opposite the clinic, blue gate"
            }""";

    /** Real PNG signature + filler — the publish gate requires a primary image
     *  before a listing may go ACTIVE. */
    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7};

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private MarketOrderRepository orderRepository;

    @Autowired
    private OrderFulfilmentRepository fulfilmentRepository;

    private UUID merchantId;
    private String merchantToken;
    private String customerToken;

    @BeforeEach
    void mintTokens() {
        merchantId = UUID.randomUUID();
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret);
        // The payer is the CALLER: a real CUSTOMER token carries the phone
        // claim, and the order flow prefers it over anything in the body.
        customerToken = TestJwts.forUser(UUID.randomUUID())
                .role("CUSTOMER").phoneNumber("+263771234567").sign(jwtSecret);
    }

    @Test
    @DisplayName("browse -> cart -> address -> quote -> order -> pay -> dispatch -> received")
    void theWholeJourney() throws Exception {
        String listingId = publishListing();

        // --- The shopper fills a cart -------------------------------------
        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":1}".formatted(listingId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuantity").value(1))
                .andExpect(jsonPath("$.data.subtotalCents").value(1550))
                // The cart resolves the LIVE listing, so the app renders it
                // without a second fetch.
                .andExpect(jsonPath("$.data.items[0].listing.title").value("Solar Lantern 20W"))
                .andExpect(jsonPath("$.data.checkoutReady").value(true));

        // Setting an exact quantity is a different write from adding, so a
        // retried stepper tap can never buy twice.
        mockMvc.perform(put("/marketplace/cart/items/{listingId}", listingId)
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuantity").value(2))
                .andExpect(jsonPath("$.data.subtotalCents").value(3100));

        // The cart holds NO stock — it is reserved once, at order creation.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQty").value(10));

        // --- and saves where it should go ---------------------------------
        String savedAddress = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS_BODY))
                .andExpect(status().isCreated())
                // The first address saved is the default, whatever was asked.
                .andExpect(jsonPath("$.data.defaultAddress").value(true))
                // Normalised to E.164 server-side — the courier rings this.
                .andExpect(jsonPath("$.data.recipientMsisdn").value("+263771234567"))
                .andReturn().getResponse().getContentAsString();
        String addressId = JsonPath.read(savedAddress, "$.data.id");

        // --- Checkout quote: priced, addressed, payable, reserving NOTHING --
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromCart":true,"deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                                .formatted(addressId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subtotalCents").value(3100))
                // The seller delivers to Harare for free (a fee of 0 is a
                // real choice); DeliveryTrackingFlowIT covers a charged fee.
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(0))
                .andExpect(jsonPath("$.data.totalCents").value(3100))
                .andExpect(jsonPath("$.data.deliveryAddress.city").value("Harare"))
                .andExpect(jsonPath("$.data.checkoutReady").value(true))
                .andExpect(jsonPath("$.data.paymentMethods[0].rail").value("INNBUCKS_CODE"));

        // Quoting reserved nothing.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(10));

        // --- The order: stock reserved, and it says how to pay -------------
        String orderBody = """
                {"fromCart":true,"deliveryMethod":"DELIVERY","deliveryAddressId":"%s"}"""
                .formatted(addressId);
        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "journey-attempt-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.subtotalCents").value(3100))
                .andExpect(jsonPath("$.data.totalCents").value(3100))
                .andExpect(jsonPath("$.data.deliveryMethod").value("DELIVERY"))
                // SNAPSHOT, not a reference — editing the book entry later
                // must not redirect this parcel.
                .andExpect(jsonPath("$.data.deliveryAddress.line1").value("14 Samora Machel Ave"))
                // The cross-service call the app no longer has to hardcode.
                .andExpect(jsonPath("$.data.payment.endpoint").value("POST /payments"))
                .andExpect(jsonPath("$.data.payment.orderType").value("MARKETPLACE"))
                .andExpect(jsonPath("$.data.payment.amountCents").value(3100))
                // Nothing to pack until the money moves.
                .andExpect(jsonPath("$.data.fulfilments").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");
        String orderRef = JsonPath.read(created, "$.data.orderRef");

        // Stock reserved once: 10 - 2 = 8. The cart's ordered line is gone.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(8));
        mockMvc.perform(get("/marketplace/cart")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        // --- The payments service settles it over the S2S surface ----------
        mockMvc.perform(get("/marketplace/internal/orders/{ref}", orderRef)
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isOk())
                // The internal contract is unchanged by the money split: the
                // payments service still collects ONE number.
                .andExpect(jsonPath("$.data.totalCents").value(3100));

        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0001\",\"amountCents\":3100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));

        // Paying opened exactly one parcel — one seller in this order.
        assertThat(fulfilmentRepository.count()).isEqualTo(1);
        String paidOrder = mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.fulfilmentStatus").value("PREPARING"))
                .andExpect(jsonPath("$.data.fulfilments[0].merchantId").value(merchantId.toString()))
                .andExpect(jsonPath("$.data.fulfilments[0].items[0].quantity").value(2))
                // A paid order is no longer asking to be paid for.
                .andExpect(jsonPath("$.data.payment").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String fulfilmentId = JsonPath.read(paidOrder, "$.data.fulfilments[0].id");

        // A replayed confirm must not double the seller's queue.
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment", orderRef)
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-0001\",\"amountCents\":3100}"))
                .andExpect(status().isOk());
        assertThat(fulfilmentRepository.count()).isEqualTo(1);

        // --- The seller works their queue ----------------------------------
        mockMvc.perform(get("/marketplace/fulfilments")
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(fulfilmentId))
                .andExpect(jsonPath("$.data.items[0].orderRef").value(orderRef))
                .andExpect(jsonPath("$.data.items[0].status").value("PREPARING"))
                // The seller gets the destination they have to ship to...
                .andExpect(jsonPath("$.data.items[0].destination.line1")
                        .value("14 Samora Machel Ave"))
                // ...and only THEIR subtotal, never the order total.
                .andExpect(jsonPath("$.data.items[0].subtotalCents").value(3100));

        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Swift Couriers, waybill 88213\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"));

        // Another seller cannot see or touch it — the same 404 as nonexistent.
        String otherSellerToken =
                TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
        mockMvc.perform(post("/marketplace/fulfilments/{id}/dispatch", fulfilmentId)
                        .header("Authorization", "Bearer " + otherSellerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("fulfilment_not_found"));

        // The buyer sees the tracking note on their own order.
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.fulfilmentStatus").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.fulfilments[0].dispatchNote")
                        .value("Swift Couriers, waybill 88213"));

        // --- The buyer confirms receipt: the journey closes -----------------
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fulfilmentStatus").value("DELIVERED"))
                // A buyer's own confirmation is stronger evidence than the
                // seller's say-so, and the record keeps them apart.
                .andExpect(jsonPath("$.data.fulfilments[0].deliveredBy").value("BUYER"));

        // Terminal: a repeat confirmation is refused, never re-applied.
        mockMvc.perform(post("/marketplace/orders/{id}/fulfilments/{fid}/received",
                        orderId, fulfilmentId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal_fulfilment_state"));

        // Payment state is untouched by fulfilment — the two lifecycles are
        // separate questions about the same order, and the verified-purchase
        // review gate still finds a PAID order to lean on.
        assertThat(orderRepository.findByOrderRef(orderRef).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(UUID.fromString(orderId))
                .getFirst().getStatus()).isEqualTo(FulfilmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("A quote shows every problem at once and refuses to be ordered")
    void aQuoteShowsWhatNeedsFixingBeforeAnythingIsReserved() throws Exception {
        String listingId = publishListing();

        mockMvc.perform(post("/marketplace/cart/items")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":\"%s\",\"quantity\":1}".formatted(listingId)))
                .andExpect(status().isOk());

        // The merchant takes it off sale while it sits in the cart.
        mockMvc.perform(patch("/marketplace/listings/{id}/status", listingId)
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk());

        // The cart STILL shows it, flagged — dropping it silently is how a
        // shopper reaches checkout with a total they do not recognise.
        mockMvc.perform(get("/marketplace/cart")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].issue.reason").value("LISTING_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.subtotalCents").value(0))
                .andExpect(jsonPath("$.data.checkoutReady").value(false));

        // The quote is a 200 carrying the problem, not an error: the shopper
        // has to SEE the basket in order to fix it.
        mockMvc.perform(post("/marketplace/checkout/quote")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true,\"deliveryMethod\":\"COLLECTION\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkoutReady").value(false))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("LISTING_UNAVAILABLE"));

        // Ordering it IS an error, and names every failing line.
        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "journey-unavailable-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromCart\":true,\"deliveryMethod\":\"COLLECTION\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("listing_unavailable"))
                .andExpect(jsonPath("$.data.rejections[0].reason").value("LISTING_UNAVAILABLE"));

        // A refused order leaves the cart exactly as it was.
        mockMvc.perform(get("/marketplace/cart")
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(jsonPath("$.data.items").isNotEmpty());
    }

    @Test
    @DisplayName("A DELIVERY order from a buyer with no saved address is refused, reserving nothing")
    void deliveryNeedsSomewhereToSendIt() throws Exception {
        String listingId = publishListing();

        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "journey-no-address-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"DELIVERY"}"""
                                .formatted(listingId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("delivery_address_required"));

        // Refused before any stock was touched.
        mockMvc.perform(get("/marketplace/catalog/{id}", listingId))
                .andExpect(jsonPath("$.data.stockQty").value(10));
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    @DisplayName("An un-updated client sending no delivery method still orders exactly as before")
    void anUnstatedMethodStillWorks() throws Exception {
        String listingId = publishListing();

        mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "journey-legacy-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":1}]}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                // COLLECTION: the same thing an order meant before delivery
                // existed here, so no address is demanded and no fee is added.
                .andExpect(jsonPath("$.data.deliveryMethod").value("COLLECTION"))
                .andExpect(jsonPath("$.data.deliveryFeeCents").value(0))
                .andExpect(jsonPath("$.data.totalCents").value(1550))
                .andExpect(jsonPath("$.data.deliveryAddress").doesNotExist());
    }

    @Test
    @DisplayName("Editing or deleting an address never changes an order already placed")
    void theOrdersDestinationIsASnapshot() throws Exception {
        String listingId = publishListing();
        String savedAddress = mockMvc.perform(post("/marketplace/addresses")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String addressId = JsonPath.read(savedAddress, "$.data.id");

        String created = mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", "journey-snapshot-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"listingId":"%s","quantity":1}],"deliveryMethod":"DELIVERY",
                                 "deliveryAddressId":"%s"}""".formatted(listingId, addressId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderId = JsonPath.read(created, "$.data.id");

        // The buyer moves house, then deletes the entry entirely.
        mockMvc.perform(delete("/marketplace/addresses/{id}", addressId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());

        // The parcel already placed still knows where it was going.
        mockMvc.perform(get("/marketplace/orders/{id}", orderId)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deliveryAddress.line1").value("14 Samora Machel Ave"))
                .andExpect(jsonPath("$.data.deliveryAddress.city").value("Harare"));
    }

    /** Merchant creates a listing and publishes it (the publish gate needs a
     *  primary image first). Returns the listing id. */
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        return listingId;
    }
}
