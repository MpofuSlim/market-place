package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.notify.SmsTextSanitizer;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V19 text rules for option names and values: how an option is printed
 * (label), what makes two options "the same" (key), and which seller text is
 * refused (normalize). The resolver-level refusals, naming the request field,
 * are pinned in {@link VariantSetResolverTest}.
 */
class VariantLabelsTest {

    private static void refused(Runnable call, String message) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(api.code()).isEqualTo("invalid_variant_option");
                    assertThat(api.getMessage()).isEqualTo(message);
                });
    }

    // ---- label ------------------------------------------------------------

    @Test
    @DisplayName("A one-axis option is labelled by its value alone; a two-axis option joins them with \" - \"")
    void labels() {
        assertThat(VariantLabels.label("M", null)).isEqualTo("M");
        assertThat(VariantLabels.label("M", "Black")).isEqualTo("M - Black");
        assertThat(VariantLabels.JOINER).isEqualTo(" - ");
    }

    @Test
    @DisplayName("The entity prints the same label and values the helpers do, so no surface formats its own")
    void entityUsesTheSameLabel() {
        ListingVariant twoAxes = new ListingVariant();
        twoAxes.setValues("XL", "Black");
        ListingVariant oneAxis = new ListingVariant();
        oneAxis.setValues("M", null);

        assertThat(twoAxes.label()).isEqualTo("XL - Black");
        assertThat(twoAxes.values()).containsExactly("XL", "Black");
        assertThat(twoAxes.getOptionKey()).isEqualTo(VariantLabels.key("XL", "Black"));
        assertThat(oneAxis.label()).isEqualTo("M");
        assertThat(oneAxis.values()).containsExactly("M");
        assertThat(oneAxis.getOptionKey()).isEqualTo("m,");
    }

    // ---- key --------------------------------------------------------------

    @Test
    @DisplayName("The option key lower-cases both values and separates them with ','; a missing second value is empty")
    void keyLowerCasesAndSeparatesWithAComma() {
        assertThat(VariantLabels.key("M", "Black")).isEqualTo("m,black");
        assertThat(VariantLabels.key("XL", "Navy/White")).isEqualTo("xl,navy/white");
        assertThat(VariantLabels.key("M", null)).isEqualTo("m,");
    }

    @Test
    @DisplayName("Two options that differ only in case share one key - the one definition of \"the same option\"")
    void keyIgnoresCase() {
        assertThat(VariantLabels.key("m", "BLACK")).isEqualTo(VariantLabels.key("M", "Black"));
        assertThat(VariantLabels.key("M", "Black")).isNotEqualTo(VariantLabels.key("M", "Blue"));
    }

    @Test
    @DisplayName("The ',' separator keeps keys unambiguous: values that would run together stay distinct options")
    void separatorKeepsKeysApart() {
        // Values can never contain ',' (normalize refuses it), so the comma in
        // a key always marks where the first value ends.
        assertThat(VariantLabels.key("Ma", "b")).isEqualTo("ma,b");
        assertThat(VariantLabels.key("M", "ab")).isEqualTo("m,ab");
        assertThat(VariantLabels.key("Ma", "b")).isNotEqualTo(VariantLabels.key("M", "ab"));
        assertThat(VariantLabels.key("M", null)).isNotEqualTo(VariantLabels.key("M", "Black"));
    }

    // ---- normalize --------------------------------------------------------

    @Test
    @DisplayName("Normalising strips HTML, collapses every run of whitespace to one space and trims")
    void normalizeCleansTheText() {
        assertThat(VariantLabels.normalize("  Navy \t\n  Blue  ", "variants[0].values[1]", 40))
                .isEqualTo("Navy Blue");
        assertThat(VariantLabels.normalize("<b>XL</b>", "variants[0].values[0]", 40))
                .isEqualTo("XL");
        assertThat(VariantLabels.normalize("Black &amp; White", "variants[0].values[1]", 40))
                .isEqualTo("Black & White");
    }

    @Test
    @DisplayName("A non-breaking space is whitespace too - \"M \" is \"M\", the same option key, never a look-alike duplicate")
    void nonBreakingSpaceIsCollapsed() {
        String pasted = VariantLabels.normalize("M&nbsp;", "variants[1].values[0]", 40);
        assertThat(pasted).isEqualTo("M");
        assertThat(VariantLabels.normalize("Navy Blue", "variants[0].values[1]", 40))
                .isEqualTo("Navy Blue");
        assertThat(VariantLabels.key(pasted, "Black")).isEqualTo(VariantLabels.key("M", "Black"));
        refused(() -> VariantLabels.normalize("  ", "options[0]", 30),
                "options[0] must not be blank");
    }

    @Test
    @DisplayName("'/' and other punctuation are allowed - \"Navy/White\" and \"6/128GB\" are real option values")
    void slashIsAllowed() {
        assertThat(VariantLabels.normalize("Navy/White", "variants[0].values[1]", 40))
                .isEqualTo("Navy/White");
        assertThat(VariantLabels.normalize("6/128GB", "variants[0].values[0]", 40))
                .isEqualTo("6/128GB");
        assertThat(VariantLabels.normalize("Size (UK)", "options[0]", 30)).isEqualTo("Size (UK)");
    }

    @Test
    @DisplayName("A blank value is refused naming the field, including null and markup that strips to nothing")
    void blankIsRefused() {
        refused(() -> VariantLabels.normalize("   ", "options[1]", 30),
                "options[1] must not be blank");
        refused(() -> VariantLabels.normalize(null, "options[0]", 30),
                "options[0] must not be blank");
        refused(() -> VariantLabels.normalize("<b></b>", "variants[0].values[0]", 40),
                "variants[0].values[0] must not be blank");
    }

    @Test
    @DisplayName("A comma is refused naming the field - it would break the option key and every item summary")
    void commaIsRefused() {
        refused(() -> VariantLabels.normalize("Black, White", "variants[0].values[1]", 40),
                "variants[0].values[1] must not contain a comma");
    }

    @Test
    @DisplayName("The length limit is measured after the whitespace collapse, at exactly the limit it passes")
    void lengthIsMeasuredAfterCollapse() {
        assertThat(VariantLabels.normalize("x".repeat(30), "options[0]", 30)).hasSize(30);
        // 44 characters as typed, 20 once collapsed.
        assertThat(VariantLabels.normalize("Extra" + " ".repeat(25) + "Large Tall Fit",
                "variants[0].values[0]", 40)).isEqualTo("Extra Large Tall Fit");
        refused(() -> VariantLabels.normalize("x".repeat(31), "options[0]", 30),
                "options[0] must be at most 30 characters");
    }

    // ---- SMS safety of the fixed joiners ----------------------------------

    @Test
    @DisplayName("The fixed joiners \" (\", \" - \" and \")\" around ASCII values survive the SMS sanitizer unchanged")
    void joinersRoundTripTheSmsSanitizer() {
        List<String> samples = List.of(
                "Cotton Crew Tee (" + VariantLabels.label("M", "Black") + ")",
                "Cotton Crew Tee (" + VariantLabels.label("XL", "Black") + ")",
                "Leather Boots (" + VariantLabels.label("42", "Brown") + ")",
                "Solar Lantern 20W (" + VariantLabels.label("Warm White", null) + ")",
                "1 x Cotton Crew Tee (L - Black), 2 x Solar Lantern 20W");

        assertThat(samples).allSatisfy(text ->
                assertThat(SmsTextSanitizer.toGsmSafe(text)).isEqualTo(text));
    }

    @Test
    @DisplayName("The title an order line prints - title (label) - is built from the same joiners and is SMS-safe")
    void orderLineDisplayTitleIsSmsSafe() {
        MarketOrderItem withOption = MarketOrderItem.builder()
                .titleSnapshot("Cotton Crew Tee")
                .variantLabel(VariantLabels.label("XL", "Black"))
                .build();
        MarketOrderItem plain = MarketOrderItem.builder()
                .titleSnapshot("Wireless Bluetooth Speaker")
                .build();

        assertThat(withOption.displayTitle()).isEqualTo("Cotton Crew Tee (XL - Black)");
        assertThat(SmsTextSanitizer.toGsmSafe(withOption.displayTitle()))
                .isEqualTo(withOption.displayTitle());
        assertThat(plain.displayTitle()).isEqualTo("Wireless Bluetooth Speaker");
    }

    @Test
    @DisplayName("Seller text is the sanitizer's job, not the label's: an allowed '/' is softened only on the way into an SMS")
    void sellerTextIsSanitisedOnSendNotRefused() {
        String label = VariantLabels.label(
                VariantLabels.normalize("Navy/White", "variants[0].values[0]", 40), null);

        assertThat(label).isEqualTo("Navy/White");
        assertThat(SmsTextSanitizer.toGsmSafe("Tee (" + label + ")")).isEqualTo("Tee (Navy White)");
    }
}
