package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DispenseService {

    private final OutpOrderRepository orderRepository;
    private final DrugItemRepository drugRepository;
    private final InventoryService inventoryService;
    private final PharmStockService pharmStockService;
    private final JdbcTemplate jdbc;

    /**
     * <b>v50 收敛：把发药接进批次账</b>，同时不动这个类的既有并发模型。
     *
     * <p>车道 A 建好了批次账并备好了 {@code consumeForDispense}/{@code restoreToBatch} 两个接缝，
     * 但发药链路没接上，实测漂移是真的：发 9 盒后总账 91、批次子账 100，<b>差 9 盒</b>。
     * 更危险的是盘点——V81 的盘点盘的是 {@code md_drug.stock}，
     * 会把这个差额<b>永久抹平</b>，而批次层的错账原地不动、无人知晓。
     *
     * <p><b>只对已纳入批次管理的药品走批次扣减</b>（口径与 {@code PharmStockService.coverage()}
     * 一致：有 {@code pharm_batch} 记录即为已纳管）。未纳管的药品保持 v49 行为逐字不变——
     * 否则存量药品会在启用批次管理的当天全部发不出去。这是迁移期两口径并存的必然，
     * 覆盖率由 {@code /balance} 的 coverage 段如实报数，不粉饰。
     */
    private boolean batchManaged(Long drugId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from pharm_batch where drug_id = ?", Integer.class, drugId);
        return n != null && n > 0;
    }

    /** 退药：已发药订单退回药房，库存回补并留痕，状态回到已收费（此后可整单退费） */
    @Transactional
    public OutpOrder returnDrug(Long orderId, Long operatorId) {
        OutpOrder o = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException(6006, "订单不存在"));
        if (!"DRUG".equals(o.getOrderType())) {
            throw new BizException(6004, "仅已发药的药品可退药");
        }
        // 先抢占 DISPENSED→CHARGED 再回补库存：读-判-写会让两次并发退药各回补一次（库存凭空多出）
        if (orderRepository.claimReturn(orderId) == 0) {
            throw new BizException(6004, "仅已发药的药品可退药");
        }
        if (drugRepository.restoreStock(o.getItemId(), o.getQty()) == 0) {
            throw new BizException(6005, "药品不存在");
        }
        // 按发药时落下的明细回补**原批次**，不猜。历史订单（批次账启用前发的）没有明细行，
        // 自然回落旧口径只回补汇总——那是事实，不是缺陷。
        for (var row : jdbc.queryForList("""
                select id, batch_id, location_code, qty - returned_qty as remain
                from pharm_dispense_batch where order_id = ? and qty > returned_qty
                """, orderId)) {
            int remain = ((Number) row.get("remain")).intValue();
            pharmStockService.restoreToBatch(((Number) row.get("batch_id")).longValue(),
                    (String) row.get("location_code"), remain, o.getGroupNo(), operatorId);
            jdbc.update("update pharm_dispense_batch set returned_qty = returned_qty + ? where id = ?",
                    remain, ((Number) row.get("id")).longValue());
        }
        inventoryService.logReturn(o.getItemId(), o.getQty(), o.getGroupNo(), operatorId);
        return orderRepository.findById(orderId).orElseThrow();
    }

    /** 发药：该挂号下全部已收费药品订单，逐一原子扣库存后标记已发药 */
    @Transactional
    public List<OutpOrder> dispense(Long registrationId) {
        List<OutpOrder> charged = orderRepository
                .findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(registrationId, "DRUG", "CHARGED");
        if (charged.isEmpty()) {
            throw new BizException(6001, "没有待发药的处方");
        }
        // 先抢占状态再扣库存：并发双发药时读-判-写会让每条医嘱被扣两次库存（药只发一次）
        var ids = charged.stream().map(OutpOrder::getId).toList();
        if (orderRepository.claimDispense(ids) != ids.size()) {
            throw new BizException(6003, "处方状态已变化（可能正在退费或已发药），请刷新后重试");
        }
        for (OutpOrder o : charged) {
            if (drugRepository.deductStock(o.getItemId(), o.getQty()) == 0) {
                throw new BizException(6002, "库存不足: " + o.getItemName() + " x" + o.getQty());
            }
            inventoryService.logOut(o.getItemId(), o.getQty(), o.getGroupNo(), null);
            // 已纳管药品同时扣批次账，并把「拆给了哪几个批次各多少」当场落库——
            // 退药要回补原批次，事后按 FEFO 猜会让批次追溯变成错的且看不出来。
            if (batchManaged(o.getItemId())) {
                var plan = pharmStockService.consumeForDispense(
                        o.getItemId(), null, o.getQty(), o.getGroupNo(), null);
                for (var line : plan.lines()) {
                    jdbc.update("""
                            insert into pharm_dispense_batch(order_id, drug_id, batch_id, location_code, qty)
                            values (?,?,?,?,?)
                            """, o.getId(), o.getItemId(), line.batchId(), line.locationCode(), line.picked());
                }
            }
        }
        // 只返回本次抢占的这批：整表回查会把上午已发的药也列进下午的发药凭条
        return orderRepository.findAllById(ids);
    }
}
