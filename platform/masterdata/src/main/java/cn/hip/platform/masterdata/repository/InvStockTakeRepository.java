package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.InvStockTake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvStockTakeRepository extends JpaRepository<InvStockTake, Long> {

    List<InvStockTake> findTop50ByOrderByIdDesc();
}
