package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.ChargeItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChargeItemRepository extends JpaRepository<ChargeItem, Long> {

    List<ChargeItem> findTop20ByEnabledTrueAndNameContainingOrderByCode(String keyword);

    List<ChargeItem> findTop20ByEnabledTrueAndCategoryAndNameContainingOrderByCode(String category, String keyword);
}
