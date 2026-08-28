package cn.hip.platform.masterdata.repository;

import cn.hip.platform.masterdata.entity.InvTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InvTransactionRepository extends JpaRepository<InvTransaction, Long> {

    List<InvTransaction> findTop100ByOrderByIdDesc();

    List<InvTransaction> findTop100ByDrugIdOrderByIdDesc(Long drugId);

    /**
     * 某药品发药净出量的带符号合计（OUT 存负、RET 存正）。
     * 效期预警估算 FEFO 消耗量用：净消耗 = -本合计（发药 - 退药）。
     * 只取 OUT/RET，不含 ADJ/STOCKTAKE——盘点调整不代表实际发出，不参与批次分摊。
     */
    @Query("select coalesce(sum(t.qty), 0) from InvTransaction t where t.drugId = :drugId and t.type in ('OUT', 'RET')")
    long sumOutReturnQty(@Param("drugId") Long drugId);
}
