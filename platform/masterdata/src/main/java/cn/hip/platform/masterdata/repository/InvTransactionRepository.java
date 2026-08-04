package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.InvTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvTransactionRepository extends JpaRepository<InvTransaction, Long> {

    List<InvTransaction> findTop100ByOrderByIdDesc();

    List<InvTransaction> findTop100ByDrugIdOrderByIdDesc(Long drugId);
}
