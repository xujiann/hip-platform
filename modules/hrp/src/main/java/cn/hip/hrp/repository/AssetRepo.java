package cn.hip.hrp.repository;

import cn.hip.hrp.entity.HrpAsset;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepo extends JpaRepository<HrpAsset, Long> {
}
