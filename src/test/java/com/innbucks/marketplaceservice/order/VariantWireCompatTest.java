package com.innbucks.marketplaceservice.order;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteRequest;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.order.dto.CreateOrderRequest;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19's wire promises to everything that was built before it: a body without
 * an option serialises, and a stored body reads back, exactly as it did.
 *
 * <p>Two bytes-level contracts ride on this, both in {@link OrderService}:
 * <ul>
 *   <li><b>The idempotency fingerprint</b> — SHA-256 over
 *       {@code objectMapper.writeValueAsBytes(CreateOrderRequest)}. A POST that
 *       straddles the deploy (sent before, retried after with the same
 *       Idempotency-Key) must fingerprint identically, or the retry is refused
 *       422 {@code idempotency_key_reuse} instead of replaying the order the
 *       first attempt created. So a request that names no option must serialise
 *       with NO {@code variantId} key — and, since V18, no
 *       {@code collectionPoints} key — which the component-level
 *       {@code @JsonInclude(NON_NULL)} buys. Asserting the literal string is
 *       asserting the fingerprint: the digest is a pure function of these
 *       bytes.</li>
 *   <li><b>The stored replay body</b> — {@code readStoredResponse} reads an
 *       {@link OrderResponse} written by whichever image took the first
 *       attempt, possibly the pre-V19 one.</li>
 * </ul>
 *
 * <p>The mapper is the one production injects into {@code OrderService}: the
 * {@code com.fasterxml} (Jackson 2) {@code ObjectMapper} bean from
 * spring-boot-jackson2's {@link Jackson2AutoConfiguration}, built here by that
 * same auto-configuration (the service defines no customizer or module of its
 * own). A hand-built {@code new ObjectMapper()} differs from it in exactly the
 * settings this test is about — Instant rendering and unknown-property
 * tolerance.
 */
class VariantWireCompatTest {

    // The canonical Swagger data.
    private static final UUID SPEAKER = UUID.fromString("b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93");
    private static final UUID TEE = UUID.fromString("e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41");
    private static final UUID XL_BLACK = UUID.fromString("2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574");

    private static ObjectMapper mapper;

    @BeforeAll
    @SuppressWarnings("removal") // spring-boot-jackson2 is deprecated upstream; production still runs it
    static void productionMapper() {
        AtomicReference<ObjectMapper> bean = new AtomicReference<>();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Jackson2AutoConfiguration.class))
                .run(context -> bean.set(context.getBean(ObjectMapper.class)));
        mapper = bean.get();
        assertThat(mapper).isNotNull();
    }

    // ------------------------------------------------------------------
    // Request fingerprints
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An order line without an option serialises to the pre-V19 bytes: no variantId key")
    void orderItemWithoutOptionHasNoVariantKey() throws Exception {
        String preV19 = "{\"listingId\":\"b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93\",\"quantity\":2}";

        assertThat(mapper.writeValueAsString(new CreateOrderRequest.Item(SPEAKER, 2)))
                .isEqualTo(preV19);
        // The canonical constructor with an explicit null is the same line.
        assertThat(mapper.writeValueAsString(new CreateOrderRequest.Item(SPEAKER, 2, null)))
                .isEqualTo(preV19);
    }

    @Test
    @DisplayName("A pre-V19 (and pre-V18) order body fingerprints byte-identically: no variantId, no collectionPoints")
    void orderRequestFingerprintIsUnchanged() throws Exception {
        CreateOrderRequest minimal = new CreateOrderRequest(null, null,
                List.of(new CreateOrderRequest.Item(SPEAKER, 2)), null, null, null);
        CreateOrderRequest representative = new CreateOrderRequest("+263771234567", null,
                List.of(new CreateOrderRequest.Item(SPEAKER, 2),
                        new CreateOrderRequest.Item(TEE, 1)),
                DeliveryMethod.COLLECTION, null, null);

        // Exactly what the six-component record V17 shipped wrote (checked by
        // serialising that record, as it stood before V18, with this same
        // mapper): every top-level component is present — the record has no
        // class-level NON_NULL, so its own nulls ARE part of the fingerprint —
        // and the two later additions are absent.
        assertThat(mapper.writeValueAsString(minimal)).isEqualTo(
                "{\"buyerMsisdn\":null,\"fromCart\":null,"
                        + "\"items\":[{\"listingId\":\"b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93\",\"quantity\":2}],"
                        + "\"deliveryMethod\":null,\"deliveryAddressId\":null,\"recipient\":null}");
        assertThat(mapper.writeValueAsString(representative)).isEqualTo(
                "{\"buyerMsisdn\":\"+263771234567\",\"fromCart\":null,"
                        + "\"items\":[{\"listingId\":\"b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93\",\"quantity\":2},"
                        + "{\"listingId\":\"e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41\",\"quantity\":1}],"
                        + "\"deliveryMethod\":\"COLLECTION\",\"deliveryAddressId\":null,\"recipient\":null}");
    }

    @Test
    @DisplayName("A fromCart order body is unchanged too — it names no line at all")
    void fromCartOrderRequestIsUnchanged() throws Exception {
        CreateOrderRequest fromCart = new CreateOrderRequest(null, true, null,
                DeliveryMethod.COLLECTION, null, null);

        assertThat(mapper.writeValueAsString(fromCart)).isEqualTo(
                "{\"buyerMsisdn\":null,\"fromCart\":true,\"items\":null,"
                        + "\"deliveryMethod\":\"COLLECTION\",\"deliveryAddressId\":null,\"recipient\":null}");
    }

    @Test
    @DisplayName("A line that names an option appends variantId after quantity, and only then")
    void orderItemWithOptionAppendsVariantId() throws Exception {
        assertThat(mapper.writeValueAsString(new CreateOrderRequest.Item(TEE, 1, XL_BLACK)))
                .isEqualTo("{\"listingId\":\"e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41\",\"quantity\":1,"
                        + "\"variantId\":\"2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574\"}");
    }

    @Test
    @DisplayName("A pre-V19 (and pre-V18) quote body is byte-identical, and its lines match the order's")
    void quoteRequestIsUnchanged() throws Exception {
        CheckoutQuoteRequest quote = new CheckoutQuoteRequest(null,
                List.of(new CheckoutQuoteRequest.Item(SPEAKER, 2)), DeliveryMethod.COLLECTION, null);

        assertThat(mapper.writeValueAsString(quote)).isEqualTo(
                "{\"fromCart\":null,"
                        + "\"items\":[{\"listingId\":\"b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93\",\"quantity\":2}],"
                        + "\"deliveryMethod\":\"COLLECTION\",\"deliveryAddressId\":null}");
        // "Send the body you quoted with": a quote line and an order line are
        // the same bytes, with and without an option.
        assertThat(mapper.writeValueAsString(new CheckoutQuoteRequest.Item(SPEAKER, 2)))
                .isEqualTo(mapper.writeValueAsString(new CreateOrderRequest.Item(SPEAKER, 2)));
        assertThat(mapper.writeValueAsString(new CheckoutQuoteRequest.Item(TEE, 1, XL_BLACK)))
                .isEqualTo(mapper.writeValueAsString(new CreateOrderRequest.Item(TEE, 1, XL_BLACK)));
    }

    // ------------------------------------------------------------------
    // Stored replay bodies
    // ------------------------------------------------------------------

    /**
     * An {@link OrderResponse} as the pre-V19 image stored it on the
     * idempotency row: a paid COLLECTION order, one parcel, two lines — and no
     * {@code variantId}, {@code variantLabel} or {@code actions} key anywhere,
     * because none existed. Instants are ISO strings (the production mapper
     * does not write timestamps as numbers). The pre-V19 {@code OrderResponse}
     * and {@code FulfilmentResponse} records read this body and write it back
     * byte for byte, so it is the shape that image really stored.
     */
    private static final String PRE_V19_ORDER = "{"
            + "\"id\":\"5d9e1f3a-7b2c-4e8d-a6f0-1c3b5e7d9f21\","
            + "\"orderRef\":\"MKT-4F2A9C1B77D0\","
            + "\"status\":\"PAID\","
            + "\"subtotalCents\":7797,\"deliveryFeeCents\":0,\"totalCents\":7797,"
            + "\"currency\":\"USD\",\"deliveryMethod\":\"COLLECTION\","
            + "\"expiresAt\":\"2026-09-14T11:32:44Z\","
            + "\"createdAt\":\"2026-09-14T11:02:44Z\","
            + "\"paidAt\":\"2026-09-14T11:20:10Z\","
            + "\"items\":["
            + "{\"listingId\":\"b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93\",\"titleSnapshot\":\"Wireless Bluetooth Speaker\","
            + "\"unitPriceCents\":2599,\"quantity\":2,\"lineTotalCents\":5198},"
            + "{\"listingId\":\"9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d\",\"titleSnapshot\":\"Solar Lantern 20W\","
            + "\"unitPriceCents\":2599,\"quantity\":1,\"lineTotalCents\":2599}],"
            + "\"fulfilmentStatus\":\"PREPARING\","
            + "\"fulfilments\":[{"
            + "\"id\":\"8a3c5e7f-1b2d-4f6a-9c8e-0d2f4b6a8c1e\","
            + "\"merchantId\":\"7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54\","
            + "\"sellerName\":\"Sunrise Electronics\","
            + "\"status\":\"PREPARING\","
            + "\"items\":["
            + "{\"listingId\":\"b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93\",\"titleSnapshot\":\"Wireless Bluetooth Speaker\","
            + "\"unitPriceCents\":2599,\"quantity\":2,\"lineTotalCents\":5198},"
            + "{\"listingId\":\"9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d\",\"titleSnapshot\":\"Solar Lantern 20W\","
            + "\"unitPriceCents\":2599,\"quantity\":1,\"lineTotalCents\":2599}],"
            + "\"trackingCode\":\"TRK-7F3K9Q2M4X\","
            + "\"trackingStatus\":\"RECEIVED\","
            + "\"deliveryFeeCents\":0}]"
            + "}";

    @Test
    @DisplayName("A stored pre-V19 order body reads back, every line with a null variantId and variantLabel")
    void preV19ReplayBodyReadsBack() throws Exception {
        OrderResponse stored = mapper.readValue(PRE_V19_ORDER, OrderResponse.class);

        assertThat(stored.orderRef()).isEqualTo("MKT-4F2A9C1B77D0");
        assertThat(stored.totalCents()).isEqualTo(7797);
        assertThat(stored.items()).hasSize(2).allSatisfy(line -> {
            assertThat(line.variantId()).isNull();
            assertThat(line.variantLabel()).isNull();
        });
        assertThat(stored.items().getFirst()).isEqualTo(new OrderResponse.Line(SPEAKER,
                "Wireless Bluetooth Speaker", 2599, 2, 5198));
        assertThat(stored.fulfilments()).singleElement().satisfies(parcel ->
                assertThat(parcel.items()).hasSize(2).allSatisfy(line -> {
                    assertThat(line.variantId()).isNull();
                    assertThat(line.variantLabel()).isNull();
                }));
        // Newer additions are simply absent from an old body.
        assertThat(stored.actions()).isNull();
        assertThat(stored.fulfilments().getFirst().actions()).isNull();
    }

    @Test
    @DisplayName("A pre-V19 body re-renders to the SAME bytes: a line without an option gained no key")
    void preV19ReplayBodyRoundTripsByteForByte() throws Exception {
        OrderResponse stored = mapper.readValue(PRE_V19_ORDER, OrderResponse.class);

        assertThat(mapper.writeValueAsString(stored)).isEqualTo(PRE_V19_ORDER);
    }

    @Test
    @DisplayName("The production mapper ignores unknown keys — what lets a rolled-back pod read a post-V19 body")
    void anOlderShapeReadsANewerBody() throws Exception {
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();

        String variantLine = mapper.writeValueAsString(new OrderResponse.Line(TEE, "Cotton Crew Tee",
                2299, 1, 2299, XL_BLACK, "XL - Black"));
        PreV19Line asTheOldImageSeesIt = mapper.readValue(variantLine, PreV19Line.class);

        assertThat(asTheOldImageSeesIt)
                .isEqualTo(new PreV19Line(TEE, "Cotton Crew Tee", 2299, 1, 2299));
    }

    /** {@link OrderResponse.Line} exactly as the pre-V19 image declared it. */
    record PreV19Line(UUID listingId, String titleSnapshot, long unitPriceCents, int quantity,
                      long lineTotalCents) {
    }

    // ------------------------------------------------------------------
    // Order lines
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A line built the pre-V19 way serialises with no variant keys; a variant line appends both")
    void lineSerialisation() throws Exception {
        assertThat(mapper.writeValueAsString(new OrderResponse.Line(SPEAKER,
                "Wireless Bluetooth Speaker", 2599, 1, 2599)))
                .isEqualTo("{\"listingId\":\"b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93\","
                        + "\"titleSnapshot\":\"Wireless Bluetooth Speaker\","
                        + "\"unitPriceCents\":2599,\"quantity\":1,\"lineTotalCents\":2599}");
        assertThat(mapper.writeValueAsString(new OrderResponse.Line(TEE, "Cotton Crew Tee",
                2299, 1, 2299, XL_BLACK, "XL - Black")))
                .isEqualTo("{\"listingId\":\"e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41\","
                        + "\"titleSnapshot\":\"Cotton Crew Tee\","
                        + "\"unitPriceCents\":2299,\"quantity\":1,\"lineTotalCents\":2299,"
                        + "\"variantId\":\"2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574\","
                        + "\"variantLabel\":\"XL - Black\"}");
    }

    @Test
    @DisplayName("Line.of carries the item's option snapshot; an item without one renders as the pre-V19 line")
    void lineOfCarriesTheLabel() {
        MarketOrderItem xl = MarketOrderItem.builder()
                .id(UUID.randomUUID()).orderId(UUID.randomUUID()).listingId(TEE)
                .merchantId(UUID.randomUUID()).titleSnapshot("Cotton Crew Tee")
                .unitPriceCents(2299).quantity(1).lineTotalCents(2299)
                .variantId(XL_BLACK).variantLabel("XL - Black")
                .build();
        MarketOrderItem speaker = MarketOrderItem.builder()
                .id(UUID.randomUUID()).orderId(UUID.randomUUID()).listingId(SPEAKER)
                .merchantId(UUID.randomUUID()).titleSnapshot("Wireless Bluetooth Speaker")
                .unitPriceCents(2599).quantity(2).lineTotalCents(5198)
                .build();

        assertThat(OrderResponse.Line.of(xl)).isEqualTo(new OrderResponse.Line(TEE,
                "Cotton Crew Tee", 2299, 1, 2299, XL_BLACK, "XL - Black"));
        assertThat(OrderResponse.Line.of(speaker)).isEqualTo(new OrderResponse.Line(SPEAKER,
                "Wireless Bluetooth Speaker", 2599, 2, 5198));
    }
}
