package com.innbucks.marketplaceservice.delivery;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.support.TestTowns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryTownCatalogTest {

    @Test
    @DisplayName("Towns resolve by code whatever the case or padding, in display order")
    void resolvesByCode() {
        DeliveryTownCatalog towns = TestTowns.zimbabwe();

        assertThat(towns.all()).extracting(DeliveryTown::getCode)
                .containsExactly("harare", "bulawayo", "mutare", "victoria-falls");
        assertThat(towns.require(" Victoria-Falls ", "townCode").getName())
                .isEqualTo("Victoria Falls");
        assertThat(towns.nameOf("nowhere")).isNull();
    }

    @Test
    @DisplayName("An unknown code is refused, naming the field and pointing at the list")
    void unknownCodeIsRefused() {
        assertThatThrownBy(() -> TestTowns.zimbabwe().require("johannesburg", "deliveryTowns.townCode"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).code()).isEqualTo("unknown_town");
                    assertThat(ex.getMessage()).startsWith("deliveryTowns.townCode 'johannesburg'");
                });
    }

    @Test
    @DisplayName("An address resolves by townCode first, then by its city name, and never guesses")
    void addressResolution() {
        DeliveryTownCatalog towns = TestTowns.zimbabwe();

        // townCode wins even when the city says something else.
        assertThat(towns.resolveForAddress("mutare", "Harare").getCode()).isEqualTo("mutare");
        // A client that has not learned about towns keeps working on the name.
        assertThat(towns.resolveForAddress(null, "  victoria falls ").getCode())
                .isEqualTo("victoria-falls");
        // Close is not a match.
        assertThatThrownBy(() -> towns.resolveForAddress(null, "Harare CBD"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code()).isEqualTo("unknown_town");
        assertThatThrownBy(() -> towns.resolveForAddress(null, null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("The list is read once per process and only for this cell's country")
    void loadedOnceForThisCountry() {
        DeliveryTownRepository repository = mock(DeliveryTownRepository.class);
        when(repository.findByCountryOrderBySortOrderAscNameAsc(anyString())).thenReturn(List.of());
        DeliveryTownCatalog towns = new DeliveryTownCatalog(repository, " zw ");

        towns.all();
        towns.find("harare");
        towns.all();

        verify(repository, times(1)).findByCountryOrderBySortOrderAscNameAsc("ZW");
    }
}
