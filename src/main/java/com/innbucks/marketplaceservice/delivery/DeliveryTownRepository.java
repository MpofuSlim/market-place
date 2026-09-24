package com.innbucks.marketplaceservice.delivery;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryTownRepository extends JpaRepository<DeliveryTown, String> {

    /** The cell's towns in display order — the whole (small) list in one read. */
    List<DeliveryTown> findByCountryOrderBySortOrderAscNameAsc(String country);
}
