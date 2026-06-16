package com.self.multi_currency_household_ledger.ledger.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    List<Asset> findByIsActiveTrueOrderBySortOrder();
}
