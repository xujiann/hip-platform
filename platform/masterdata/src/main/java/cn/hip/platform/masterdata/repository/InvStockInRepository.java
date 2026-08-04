package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.InvStockIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvStockInRepository extends JpaRepository<InvStockIn, Long> {

    List<InvStockIn> findTop50ByOrderByIdDesc();
}
