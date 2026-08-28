package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.InvStockTakeLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InvStockTakeLineRepository extends JpaRepository<InvStockTakeLine, Long> {

    List<InvStockTakeLine> findByTakeIdOrderById(Long takeId);

    Optional<InvStockTakeLine> findByTakeIdAndDrugId(Long takeId, Long drugId);
}
