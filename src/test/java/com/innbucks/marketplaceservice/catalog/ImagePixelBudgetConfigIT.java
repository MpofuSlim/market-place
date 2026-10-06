package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CONFIGURED pixel budget reaches both halves of the guard, and on serve it
 * runs BEFORE the decode.
 *
 * <p>Deliberately a separate class with a tight budget (0.1 MP) and REAL,
 * decodable images. A hand-built bomb cannot prove ordering — the pre-guard
 * code also served one as the original, because the JDK refuses a >2 GB raster
 * with an exception it caught — and a default-budget test cannot tell the
 * injected {@link ImagePixelBudget} from the resizer's built-in default. Here
 * an 800 x 600 PNG that any unguarded resizer would happily shrink is served
 * as its original bytes, so only the configured header check can have stopped
 * it; and the upload refusal names the configured limit, not the default.
 */
@TestPropertySource(properties = "marketplace.listing.image-max-pixels=100000")
class ImagePixelBudgetConfigIT extends PostgresTestContainer {

    private static final String LISTING_BODY = """
            {
              "title": "Solar Lantern 20W",
              "description": "Portable solar lantern with 12h battery",
              "categoryCode": "electronics",
              "priceCents": 1550,
              "stockQty": 10
            }""";

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String merchantToken;

    @BeforeEach
    void mintToken() {
        merchantToken = TestJwts.merchantAdmin(UUID.randomUUID(), UUID.randomUUID(), jwtSecret);
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String createDraftListing() throws Exception {
        String body = mockMvc.perform(post("/marketplace/listings")
                        .header("Authorization", "Bearer " + merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LISTING_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private void storeDirectly(String listingId, byte[] bytes) {
        jdbc.update("""
                INSERT INTO listing_image (id, listing_id, image_bytes, content_type, is_primary,
                                           position, created_at)
                VALUES (?, ?, ?, 'image/png', TRUE, 0, now())""",
                UUID.randomUUID(), UUID.fromString(listingId), bytes);
    }

    @Test
    void aDecodableImageOverTheConfiguredBudgetIsServedOriginal_theCheckRunsBeforeTheDecode()
            throws Exception {
        // 480,000 px: inside the default 50 MP, over this cell's 0.1 MP. As if
        // stored before the cell lowered its budget.
        String listingId = createDraftListing();
        byte[] stored = png(800, 600);
        storeDirectly(listingId, stored);

        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "240"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Image-Resized", "false"))
                .andExpect(header().string("Cache-Control", "max-age=3600, must-revalidate, public"))
                .andExpect(content().bytes(stored));
    }

    @Test
    void anImageInsideTheConfiguredBudgetStillResizes() throws Exception {
        String listingId = createDraftListing();
        storeDirectly(listingId, png(300, 200)); // 60,000 px

        mockMvc.perform(get("/marketplace/catalog/{id}/image", listingId).param("w", "120"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Image-Resized", "true"));
    }

    @Test
    void uploadRefusesAgainstTheConfiguredBudget_andNamesIt() throws Exception {
        String listingId = createDraftListing();

        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", png(800, 600)))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("image_dimensions_too_large"))
                .andExpect(jsonPath("$.message").value("That image has too many pixels. Please use "
                        + "one of at most 0.1 megapixels and no more than 8,192 pixels on its "
                        + "longest side."));

        mockMvc.perform(multipart(HttpMethod.POST, "/marketplace/listings/{id}/image", listingId)
                        .file(new MockMultipartFile("image", "photo.png", "image/png", png(300, 200)))
                        .header("Authorization", "Bearer " + merchantToken))
                .andExpect(status().isOk());
    }
}
