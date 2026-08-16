package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DispenseService {

    private final OutpOrderRepository orderRepository;
    private final DrugItemRepository drugRepository;
    private final InventoryService inventoryService;

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
        }
        // 只返回本次抢占的这批：整表回查会把上午已发的药也列进下午的发药凭条
        return orderRepository.findAllById(ids);
    }
}
