package com.innbucks.marketplaceservice.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20 added {@code merchantId} to {@link OrderLineRejection} for the two
 * delivery-method reasons only. Every older reason must serialise to exactly
 * the bytes it did before - a client that diffed or cached a rejection, and the
 * published examples, must see nothing new - and the two method reasons must
 * carry the seller, appended last.
 *
 * <p>Checked through both Jackson lines: Jackson 2 (the service's own
 * {@code ObjectMapper}) and Jackson 3 (what Spring MVC renders responses with).
 */
class OrderLineRejectionTest {

    private static final UUID LANTERN = UUID.fromString("9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d");
    private static final UUID TEE = UUID.fromString("e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41");
    private static final UUID XL_BLACK = UUID.fromString("2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574");
    private static final UUID SELLER = UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");

    private static final ObjectMapper JACKSON2 = JsonMapper.builder().build();
    private static final tools.jackson.databind.ObjectMapper JACKSON3 =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static List<String> render(OrderLineRejection rejection) throws Exception {
        return List.of(JACKSON2.writeValueAsString(rejection), JACKSON3.writeValueAsString(rejection));
    }

    @Test
    @DisplayName("Every pre-V20 reason serialises with NO merchantId key - the pre-V20 bytes")
    void olderReasonsCarryNoMerchantId() throws Exception {
        List<OrderLineRejection> older = List.of(
                OrderLineRejection.unavailable(LANTERN, 2),
                OrderLineRejection.unavailable(TEE, 1, XL_BLACK, "XL - Black"),
                OrderLineRejection.insufficientStock(LANTERN, "Solar Lantern 20W", 5, 3, 1550),
                OrderLineRejection.insufficientStock(TEE, "Cotton Crew Tee", 7, 6, 2299, XL_BLACK,
                        "XL - Black"),
                OrderLineRejection.variantRequired(TEE, "Cotton Crew Tee", "Size and Colour", 1, 1999),
                OrderLineRejection.variantUnavailable(TEE, "Cotton Crew Tee", XL_BLACK, 1, 1999));
        for (OrderLineRejection rejection : older) {
            assertThat(rejection.merchantId()).isNull();
            for (String json : render(rejection)) {
                assertThat(json).doesNotContain("merchantId");
            }
        }
        // One pinned literal, byte for byte: what the published 409 example shows.
        assertThat(render(OrderLineRejection.insufficientStock(LANTERN, "Solar Lantern 20W", 5, 3, 1550)))
                .containsOnly("{\"listingId\":\"9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d\","
                        + "\"reason\":\"INSUFFICIENT_STOCK\",\"message\":\"Only 3 left of Solar Lantern 20W\","
                        + "\"requestedQty\":5,\"availableQty\":3,\"unitPriceCents\":1550}");
    }

    @Test
    @DisplayName("NOT_DELIVERED_TO_TOWN keeps its pre-V20 keys and message, and appends the seller")
    void notDeliveredToTownAppendsTheSeller() throws Exception {
        OrderLineRejection r = OrderLineRejection.notDeliveredToTown(LANTERN, "Solar Lantern 20W", 1,
                1550, "Mutare", null, null, SELLER);

        assertThat(render(r)).containsOnly("{\"listingId\":\"9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d\","
                + "\"reason\":\"NOT_DELIVERED_TO_TOWN\",\"message\":\"Solar Lantern 20W is not delivered to Mutare\","
                + "\"requestedQty\":1,\"unitPriceCents\":1550,"
                + "\"merchantId\":\"7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54\"}");
    }

    @Test
    @DisplayName("COLLECTION_NOT_OFFERED names the item, prices the option, echoes it and names the seller")
    void collectionNotOfferedShape() throws Exception {
        OrderLineRejection r = OrderLineRejection.collectionNotOffered(TEE, "Cotton Crew Tee", 2, 2299,
                XL_BLACK, "XL - Black", SELLER);

        assertThat(r.reason()).isEqualTo(OrderLineRejection.REASON_COLLECTION_NOT_OFFERED);
        assertThat(render(r)).containsOnly("{\"listingId\":\"e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41\","
                + "\"reason\":\"COLLECTION_NOT_OFFERED\",\"message\":\"Cotton Crew Tee is delivery only\","
                + "\"requestedQty\":2,\"unitPriceCents\":2299,"
                + "\"variantId\":\"2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574\",\"variantLabel\":\"XL - Black\","
                + "\"merchantId\":\"7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54\"}");
    }
}
