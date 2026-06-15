package com.self.multi_currency_household_ledger.ledger.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, Long> {

    Optional<Asset> findByIdAndOwnerMemberIdIn(Long id, List<Long> ownerMemberIds);

    List<Asset> findByOwnerMemberIdInAndIsActiveTrueOrderBySortOrder(List<Long> ownerMemberIds);

    boolean existsByOwnerMemberIdAndCode(Long ownerMemberId, String code);
}
