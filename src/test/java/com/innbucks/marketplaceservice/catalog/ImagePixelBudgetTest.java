package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImagePixelBudgetTest {

    private final ImagePixelBudget budget = ImagePixelBudget.defaults();

    @Test
    void admitsEveryPhoneCameraOutput_upToA50MpFullResolutionMode() {
        assertThat(budget.admits(new ImageDimensions.Size(4032, 3024))).isTrue();  // 12 MP default shot
        assertThat(budget.admits(new ImageDimensions.Size(8160, 6120))).isTrue();  // 50 MP full-res
        assertThat(budget.admits(new ImageDimensions.Size(6120, 8160))).isTrue();  // portrait
        assertThat(budget.admits(new ImageDimensions.Size(8192, 6000))).isTrue();  // exactly the side
    }

    @Test
    void refusesOverEitherLimit() {
        assertThat(budget.admits(new ImageDimensions.Size(8193, 100))).isFalse();  // side
        assertThat(budget.admits(new ImageDimensions.Size(100, 8193))).isFalse();  // side, tall
        assertThat(budget.admits(new ImageDimensions.Size(8192, 6200))).isFalse(); // 50.8 MP
        assertThat(budget.admits(new ImageDimensions.Size(30_000, 30_000))).isFalse();
    }

    @Test
    void uploadGuard_refusesABombWithACustomerSafeMessageNamingTheLimit() {
        assertThatThrownBy(() -> budget.requireUploadable(HeaderOnlyImages.png(30_000, 30_000)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("image_dimensions_too_large");
                    assertThat(ex.status().value()).isEqualTo(400);
                    // Pinned verbatim: ListingController's Swagger example
                    // quotes it, and the portal shows it to the seller.
                    assertThat(ex.getMessage()).isEqualTo("That image has too many pixels. Please use "
                            + "one of at most 50 megapixels and no more than 8,192 pixels on its "
                            + "longest side.");
                });
        assertThatThrownBy(() -> budget.requireUploadable(HeaderOnlyImages.jpeg(9000, 100)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> budget.requireUploadable(HeaderOnlyImages.webpExtended(20_000, 20_000)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void uploadGuard_letsThroughWhatItCannotMeasure_andWhatFits() {
        // Magic bytes only: the header is unreadable, so the image is never
        // decoded server-side either — accepting it is what upload always did.
        assertThatCode(() -> budget.requireUploadable(
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 8, 7}))
                .doesNotThrowAnyException();
        assertThatCode(() -> budget.requireUploadable(HeaderOnlyImages.png(8160, 6120)))
                .doesNotThrowAnyException();
    }

    @Test
    void theMessageFollowsTheConfiguredLimit() {
        ImagePixelBudget custom = new ImagePixelBudget(12_500_000L, 4000);

        assertThat(custom.refusalMessage()).isEqualTo("That image has too many pixels. Please use one "
                + "of at most 12.5 megapixels and no more than 4,000 pixels on its longest side.");
    }

    @Test
    void aNonPositiveLimitFailsBoot() {
        assertThatThrownBy(() -> new ImagePixelBudget(0, 8192)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImagePixelBudget(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
