package com.innbucks.marketplaceservice.catalog.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.innbucks.marketplaceservice.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

/**
 * Pins the refusal that stops a filter being silently dropped.
 *
 * <p>Spring ignores an unbound request parameter, so a client that misspells
 * {@code minPriceCents} as {@code minPrice} gets a confident 200 listing goods
 * outside its budget with nothing saying the bound was never applied.
 */
class QueryParamsTest {

    private static final Set<String> ALLOWED = Set.of("q", "page", "size", "minPriceCents");

    private static HttpServletRequest requestWith(String... namesAndValues) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            request.addParameter(namesAndValues[i], namesAndValues[i + 1]);
        }
        return request;
    }

    @Test
    void anAllowedParameterSetPasses() {
        assertThatCode(() -> QueryParams.rejectUnknown(
                requestWith("q", "lantern", "page", "2", "minPriceCents", "500"), ALLOWED))
                .doesNotThrowAnyException();
    }

    @Test
    void noParametersAtAllPasses() {
        assertThatCode(() -> QueryParams.rejectUnknown(requestWith(), ALLOWED))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnknownParameterIsRefusedRatherThanIgnored() {
        ApiException ex = (ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> QueryParams.rejectUnknown(requestWith("minPrice", "500"), ALLOWED));

        assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.code()).isEqualTo("unknown_parameter");
        // The message must name BOTH the offender and the supported set — the
        // fix has to be obvious from the response alone.
        assertThat(ex.getMessage()).contains("'minPrice'")
                .contains("minPriceCents").contains("page").contains("q").contains("size");
    }

    @Test
    void theNamedOffenderIsDeterministicWhenSeveralAreUnknown() {
        // Servlet parameter order is not guaranteed, and a flapping error
        // message is untestable and unreportable — so the alphabetically first
        // unknown is always the one named.
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> QueryParams.rejectUnknown(
                    requestWith("zebra", "1", "alpha", "2", "middle", "3"), ALLOWED))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("'alpha'");
        }
    }

    @Test
    void aRidiculouslyLongParameterNameIsTruncatedInTheEcho() {
        // The name is caller-controlled; a multi-kilobyte name must not become
        // a multi-kilobyte error body.
        String huge = "x".repeat(5000);

        ApiException ex = (ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> QueryParams.rejectUnknown(requestWith(huge, "1"), ALLOWED));

        assertThat(ex.getMessage()).hasSizeLessThan(300).contains("…");
    }

    @Test
    void aNullRequestIsANoOp() {
        // MockMvc standalone and direct unit calls can hand the controller no
        // servlet request; that must never become a spurious 400.
        assertThatCode(() -> QueryParams.rejectUnknown(null, ALLOWED))
                .doesNotThrowAnyException();
    }
}
