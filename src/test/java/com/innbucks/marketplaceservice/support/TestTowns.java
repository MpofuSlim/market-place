package com.innbucks.marketplaceservice.support;

import com.innbucks.marketplaceservice.delivery.DeliveryTown;
import com.innbucks.marketplaceservice.delivery.DeliveryTownCatalog;
import com.innbucks.marketplaceservice.delivery.DeliveryTownRepository;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link DeliveryTownCatalog} over a few of V14's seeded Zimbabwe towns, for
 * unit tests that need real town resolution without a database. The codes and
 * names match the migration exactly, so a test written against them reads the
 * same as the production data.
 */
public final class TestTowns {

    public static final DeliveryTown HARARE =
            new DeliveryTown("harare", "Harare", "Harare", "ZW", 10);
    public static final DeliveryTown BULAWAYO =
            new DeliveryTown("bulawayo", "Bulawayo", "Bulawayo", "ZW", 40);
    public static final DeliveryTown MUTARE =
            new DeliveryTown("mutare", "Mutare", "Manicaland", "ZW", 50);
    public static final DeliveryTown VICTORIA_FALLS =
            new DeliveryTown("victoria-falls", "Victoria Falls", "Matabeleland North", "ZW", 270);

    private TestTowns() {
    }

    public static DeliveryTownCatalog zimbabwe() {
        DeliveryTownRepository repository = mock(DeliveryTownRepository.class);
        when(repository.findByCountryOrderBySortOrderAscNameAsc("ZW"))
                .thenReturn(List.of(HARARE, BULAWAYO, MUTARE, VICTORIA_FALLS));
        return new DeliveryTownCatalog(repository, "ZW");
    }
}
