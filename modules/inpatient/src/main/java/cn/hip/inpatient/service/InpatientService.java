package cn.hip.inpatient.service;

import cn.hip.inpatient.entity.*;
import cn.hip.inpatient.repository.*;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InpatientService {

    private final BedRepo bedRepo;
    private final AdmissionRepo admissionRepo;
    private final DepositRepo depositRepo;
    private final OrderRepo orderRepo;
    private final SettlementRepo settlementRepo;
    private final TransferLogRepo transferLogRepo;
    private final DrugItemRepository drugRepository;
    private final ChargeItemRepository chargeItemRepository;
    private final InventoryService inventoryService;
    private final cn.hip.platform.integration.insurance.InsuranceAdapter insuranceAdapter;
    private final cn.hip.insurance.service.InsuranceSplitService insuranceSplitService;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    private final jakarta.persistence.EntityManager entityManager;

    /** 组号取数据库序列：内存 AtomicLong 的毫秒种子每 100 秒回绕，重启即与已发组号撞号 */
    private long nextGroupSeq() {
        return ((Number) entityManager
                .createNativeQuery("select nextval('inp_order_group_seq')")
                .getSingleResult()).longValue();
    }

    public static class InpException extends RuntimeException {
        public final int code;
        public InpException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 入院登记：占床 + 建住院档 + 收首笔押金 */
    @Transactional
    public InpAdmission admit(Long patientId, Long deptId, Long bedId, Long doctorId,
                              String diagIcd, String diagName,
                              BigDecimal deposit, String payMethod, Long operatorId) {
        InpBed bed = bedRepo.findById(bedId).orElseThrow(() -> new InpException(9001, "床位不存在"));

        InpAdmission adm = new InpAdmission();
        adm.setAdmissionNo("TEMP");
        adm.setPatientId(patientId);
        adm.setDeptId(deptId);
        adm.setWardId(bed.getWardId());
        adm.setBedId(bedId);
        adm.setDoctorId(doctorId);
        adm.setAdmitDiagIcd(diagIcd);
        adm.setAdmitDiagName(diagName);
        adm = admissionRepo.save(adm);
        adm.setAdmissionNo("%s%s-%06d".formatted(
                configReader.get("billno_prefix_admission", "ZY"),
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), adm.getId()));
        adm = admissionRepo.save(adm);

        if (bedRepo.occupy(bedId, adm.getId()) == 0) {
            throw new InpException(9002, "床位已被占用");
        }
        if (deposit != null && deposit.compareTo(BigDecimal.ZERO) > 0) {
            addDeposit(adm.getId(), deposit, payMethod, operatorId);
        }
        return adm;
    }

    @Transactional
    public InpDeposit addDeposit(Long admissionId, BigDecimal amount, String payMethod, Long operatorId) {
        InpAdmission adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        if (!"IN_HOSPITAL".equals(adm.getStatus())) {
            throw new InpException(9004, "已出院不能缴押金");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new InpException(9017, "押金金额须大于 0");
        }
        InpDeposit d = new InpDeposit();
        d.setAdmissionId(admissionId);
        d.setAmount(amount);
        d.setPayMethod(payMethod == null ? "CASH" : payMethod);
        d.setOperatorId(operatorId);
        return depositRepo.save(d);
    }

    public record OrderLine(String orderType, Long itemId, Integer qty,
                            String usageRoute, String frequency, String dosePerTime) {}

    /** 开住院医嘱（记账式：开立即计费） */
    @Transactional
    public List<InpOrder> createOrders(Long admissionId, List<OrderLine> lines, Long doctorId) {
        InpAdmission adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        if (!"IN_HOSPITAL".equals(adm.getStatus())) {
            throw new InpException(9005, "已出院不能开医嘱");
        }
        String stamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return lines.stream().map(line -> {
            InpOrder o = new InpOrder();
            o.setAdmissionId(admissionId);
            o.setOrderType(line.orderType());
            o.setQty(line.qty() == null || line.qty() <= 0 ? 1 : line.qty());
            o.setDoctorId(doctorId);
            o.setGroupNo("YZ" + stamp + "-" + nextGroupSeq());
            if ("DRUG".equals(line.orderType())) {
                DrugItem drug = drugRepository.findById(line.itemId())
                        .orElseThrow(() -> new InpException(9006, "药品不存在"));
                o.setItemId(drug.getId());
                o.setItemCode(drug.getCode());
                o.setItemName(drug.getName());
                o.setSpec(drug.getSpec());
                o.setUnit(drug.getUnit());
                o.setUnitPrice(drug.getPrice());
                o.setUsageRoute(line.usageRoute());
                o.setFrequency(line.frequency());
                o.setDosePerTime(line.dosePerTime());
            } else {
                ChargeItem item = chargeItemRepository.findById(line.itemId())
                        .orElseThrow(() -> new InpException(9007, "收费项目不存在"));
                o.setItemId(item.getId());
                o.setItemCode(item.getCode());
                o.setItemName(item.getName());
                o.setUnit(item.getUnit());
                o.setUnitPrice(item.getPrice());
            }
            o.setAmount(o.getUnitPrice().multiply(BigDecimal.valueOf(o.getQty())));
            return orderRepo.save(o);
        }).toList();
    }

    /** 护士执行：药品执行时原子扣库存并留痕 */
    @Transactional
    public InpOrder execute(Long orderId, Long executorId) {
        InpOrder o = orderRepo.findById(orderId).orElseThrow(() -> new InpException(9008, "医嘱不存在"));
        // 先抢占状态再扣库存：读-判-写会让两名护士各扣一次库存、各记一条出库流水
        if (orderRepo.claimExecute(orderId, executorId, Instant.now()) == 0) {
            throw new InpException(9009, "仅未执行医嘱可执行");
        }
        if ("DRUG".equals(o.getOrderType())) {
            if (drugRepository.deductStock(o.getItemId(), o.getQty()) == 0) {
                throw new InpException(9010, "库存不足: " + o.getItemName());
            }
            inventoryService.logOut(o.getItemId(), o.getQty(), o.getGroupNo(), executorId);
        }
        return orderRepo.findById(orderId).orElseThrow();
    }

    /** 作废未执行医嘱：出院前清理误开医嘱的唯一正确路径（执行掉会多计费并白扣库存） */
    @Transactional
    public void cancelOrder(Long orderId) {
        InpOrder o = orderRepo.findById(orderId).orElseThrow(() -> new InpException(9008, "医嘱不存在"));
        InpAdmission adm = admissionRepo.findById(o.getAdmissionId())
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        if (!"IN_HOSPITAL".equals(adm.getStatus())) {
            throw new InpException(9015, "已出院不能作废医嘱");
        }
        if (orderRepo.cancelIfCreated(orderId) == 0) {
            throw new InpException(9016, "仅未执行医嘱可作废");
        }
    }

    /** 转科转床：原子占新床成功后才释放旧床，全程留痕 */
    @Transactional
    public InpAdmission transfer(Long admissionId, Long toDeptId, Long toBedId, Long operatorId) {
        InpAdmission adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        if (!"IN_HOSPITAL".equals(adm.getStatus())) {
            throw new InpException(9013, "已出院不能转科");
        }
        if (adm.getBedId().equals(toBedId)) {
            throw new InpException(9014, "目标床位与当前床位相同");
        }
        InpBed toBed = bedRepo.findById(toBedId).orElseThrow(() -> new InpException(9001, "床位不存在"));
        if (bedRepo.occupy(toBedId, admissionId) == 0) {
            throw new InpException(9002, "目标床位已被占用");
        }
        Long fromBedId = adm.getBedId();
        Long fromDeptId = adm.getDeptId();
        bedRepo.release(fromBedId, admissionId);

        InpTransferLog log = new InpTransferLog();
        log.setAdmissionId(admissionId);
        log.setFromDeptId(fromDeptId);
        log.setToDeptId(toDeptId);
        log.setFromBedId(fromBedId);
        log.setToBedId(toBedId);
        log.setOperatorId(operatorId);
        transferLogRepo.save(log);

        adm.setDeptId(toDeptId);
        adm.setWardId(toBed.getWardId());
        adm.setBedId(toBedId);
        return admissionRepo.save(adm);
    }

    /** 出院结算：费用汇总、押金冲抵、释放床位 */
    @Transactional
    public InpSettlement discharge(Long admissionId, Long cashierId) {
        return discharge(admissionId, cashierId, "CASH");
    }

    /** 出院结算：payMethod=YB 时走医保通道上传结算（费用总额口径） */
    @Transactional
    public InpSettlement discharge(Long admissionId, Long cashierId, String payMethod) {
        InpAdmission adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        // 先抢占 DISCHARGED 再汇总：否则"读快照→置位"之间并发缴的押金既不进结算也不退还，
        // 并发开的医嘱则永不计费。抢占失败即他人已结算。
        if (admissionRepo.claimDischarge(admissionId, Instant.now()) == 0) {
            throw new InpException(9011, "该患者已出院结算");
        }
        List<InpOrder> orders = orderRepo.findByAdmissionIdOrderByIdAsc(admissionId);
        boolean hasPending = orders.stream().anyMatch(o -> "CREATED".equals(o.getStatus()));
        if (hasPending) {
            // 抛出即回滚，DISCHARGED 抢占一并撤销
            throw new InpException(9012, "存在未执行医嘱，请先执行或作废");
        }
        BigDecimal total = orders.stream()
                .filter(o -> "EXECUTED".equals(o.getStatus()))
                .map(InpOrder::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal deposit = depositRepo.findByAdmissionIdOrderByIdAsc(admissionId).stream()
                .map(InpDeposit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        InpSettlement s = new InpSettlement();
        s.setSettleNo("%s%s-%06d".formatted(
                configReader.get("billno_prefix_settle", "CY"),
                LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE), admissionId));
        s.setAdmissionId(admissionId);
        s.setTotalAmount(total);
        s.setDepositAmount(deposit);
        s.setBalance(deposit.subtract(total));
        s.setCashierId(cashierId);
        s.setPayMethod(payMethod == null ? "CASH" : payMethod);
        s = settlementRepo.save(s);
        if ("YB".equals(s.getPayMethod())) {
            // 住院行级分割（批次二）：与门诊共用分割引擎，biz_type=INP 走住院比例
            insuranceSplitService.splitAndAudit("INP", s.getSettleNo(), adm.getPatientId(), total,
                    orders.stream().filter(o -> "EXECUTED".equals(o.getStatus()))
                            .map(o -> new cn.hip.insurance.service.InsuranceSplitService.YbLine(
                                    o.getOrderType(), o.getItemCode(), o.getItemName(), o.getAmount(), o.getQty()))
                            .toList());
            var res = insuranceAdapter.uploadSettlement(s.getSettleNo(), total);
            if (!res.ok()) {
                throw new InpException(9013, "医保出院结算上传失败: " + res.message());
            }
            s.setYbSettleNo(res.settleNo());
            s = settlementRepo.save(s);
        }

        bedRepo.release(adm.getBedId(), admissionId);   // 状态已在入口抢占
        return s;
    }
}
