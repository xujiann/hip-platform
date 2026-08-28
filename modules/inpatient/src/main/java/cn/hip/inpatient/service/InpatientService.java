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
import cn.hip.platform.core.config.BusinessDates;

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
    private final cn.hip.platform.core.config.ModuleGate moduleGate;

    private final jakarta.persistence.EntityManager entityManager;

    /** 组号取数据库序列：内存 AtomicLong 的毫秒种子每 100 秒回绕，重启即与已发组号撞号 */
    private long nextGroupSeq() {
        // 空 query space（1.1.7 B-8）：未声明时 Hibernate 按"可能读任何表"处理，
        // 每次取号都 auto-flush 整个会话并对已托管实体做脏检查——这是医生开单的热路径
        return ((Number) entityManager
                .createNativeQuery("select nextval('inp_order_group_seq')")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addSynchronizedQuerySpace("")
                .getSingleResult()).longValue();
    }

    /** 域内保留类型（现有 catch/测试不动），实质语义在基类（1.1.9） */
    public static class InpException extends cn.hip.platform.core.common.HipBizException {
        public InpException(int code, String message) {
            super(code, message);
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
                BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE), adm.getId()));
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
        String stamp = BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE);
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

    /** 转科转床（无原因重载，保留既有调用点签名不变） */
    @Transactional
    public InpAdmission transfer(Long admissionId, Long toDeptId, Long toBedId, Long operatorId) {
        return transfer(admissionId, toDeptId, toBedId, null, operatorId);
    }

    /** 转科转床：原子占新床成功后才释放旧床，全程留痕（reason 为收尾环补采的转科原因，可空） */
    @Transactional
    public InpAdmission transfer(Long admissionId, Long toDeptId, Long toBedId, String reason, Long operatorId) {
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
        log.setReason(reason);
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
        // 先存后编号（与门诊 ChargeService 同法）：单号含 admissionId 会在「冲销后当日重结」时
        // 与已作废单撞唯一约束；TEMP 占位含 admissionId 保证并发下不互撞
        s.setSettleNo("TEMP-" + admissionId);
        s.setAdmissionId(admissionId);
        s.setTotalAmount(total);
        s.setDepositAmount(deposit);
        s.setBalance(deposit.subtract(total));
        s.setCashierId(cashierId);
        s.setPayMethod(payMethod == null ? "CASH" : payMethod);
        s = settlementRepo.save(s);
        // 后缀带方案区分符 S（1.1.8 实测回归）：V54 前的旧号含 admissionId、纯数字后缀，
        // 同日升级后新结算 id 追上旧行的 admissionId 值即撞 settle_no 唯一约束——
        // 旧号永远是纯数字，"S"+id 与其永不相等
        s.setSettleNo("%s%s-S%06d".formatted(
                configReader.get("billno_prefix_settle", "CY"),
                BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE), s.getId()));
        s = settlementRepo.save(s);
        // 必须用抢占**之后**重读的床位：adm 是 claim 前的快照且已被 clearAutomatically detach，
        // 期间若发生转床，用旧 bedId 释放会影响 0 行 → 新床永久 OCCUPIED 无法再收治。
        // 床位释放在医保上传**之前**（1.1.6 B-3）：9018 是本方法自定义的失败路径，
        // 若排在上传之后，触发即"医保已扣、本地整体回滚无结算单"。
        Long currentBedId = admissionRepo.findById(admissionId).orElseThrow().getBedId();
        if (bedRepo.release(currentBedId, admissionId) == 0) {
            throw new InpException(9018, "床位释放失败（床位已被改动），请联系管理员核对床位状态");
        }

        if ("YB".equals(s.getPayMethod())) {
            // 模块开关须挡横向调用（1.2.0，与门诊 5010 同理）
            if (!moduleGate.isEnabled("insurance")) {
                throw new InpException(9027, "医保模块未启用，不能以医保方式结算");
            }
            // 住院行级分割（批次二）：与门诊共用分割引擎，biz_type=INP 走住院比例
            insuranceSplitService.splitAndAudit("INP", s.getSettleNo(), adm.getPatientId(), total,
                    orders.stream().filter(o -> "EXECUTED".equals(o.getStatus()))
                            .map(o -> new cn.hip.insurance.service.InsuranceSplitService.YbLine(
                                    o.getOrderType(), o.getItemCode(), o.getItemName(), o.getAmount(), o.getQty()))
                            .toList());
            var res = insuranceAdapter.uploadSettlement(s.getSettleNo(), total);
            if (!res.ok()) {
                throw new InpException(9026, "医保出院结算上传失败: " + res.message());   // 原 9013 与"已出院不能转科"撞码
            }
            // 渠道成功后仅剩回填渠道结算号这一个本地写（无业务校验、无抢占），失败面可忽略
            s.setYbSettleNo(res.settleNo());
            s = settlementRepo.save(s);
        }
        return s;
    }

    /**
     * 结算冲销（出院召回）：结算错误的唯一系统内纠正路径——此前只能改库，
     * 且住院医保年度起付线/统筹额度永不回退（reverse 全仓仅门诊一个调用点，1.1.3 审阅 B-5）。
     *
     * <p>动作序：抢占冲销 → 占床 → 恢复在院 → **最后**才医保冲正+额度回退。
     * 渠道冲正一旦成功就不允许再有任何可失败的本地步骤——否则渠道已冲、本地回滚，
     * 悬空的是医保基金账（C-2 支付悬空同款时序，教训复用）。任何本地步骤失败则整体回滚，
     * 冲正报文根本不会发出。
     *
     * @param bedId 召回后的床位；null 则回原床。原床已被新患者占用时须显式指定空床
     */
    @Transactional
    public InpAdmission cancelSettlement(Long admissionId, Long operatorId, Long bedId) {
        // V90：按 settle_type='FINAL' 定位——中间结算引入后同一入院可有多张 PAID 行，
        // 裸按 (admissionId,'PAID') 取会命中中间结算行、返回多行报错。冲销永远针对出院结算。
        InpSettlement s = settlementRepo.findByAdmissionIdAndStatusAndSettleType(admissionId, "PAID", "FINAL")
                .orElseThrow(() -> new InpException(9021, "该患者无有效出院结算单"));
        // 条件更新抢占：并发双击/两窗口同时冲销，只有一方成功
        if (settlementRepo.claimCancel(s.getId(), Instant.now(), operatorId) == 0) {
            throw new InpException(9022, "该结算单已被冲销");
        }
        InpAdmission adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        Long targetBed = bedId != null ? bedId : adm.getBedId();
        if (bedRepo.occupy(targetBed, admissionId) == 0) {
            throw new InpException(9024, "召回床位已被占用，请指定一张空床后重试");
        }
        if (admissionRepo.claimReadmit(admissionId, targetBed) == 0) {
            throw new InpException(9025, "该患者不在已出院状态（可能已被召回）");
        }
        // 冲销侧有意不设模块开关（1.2.0）：既存 YB 结算必须始终可纠正，
        // 否则中途关闭医保模块的医院会有永远无法冲销的历史单据
        if ("YB".equals(s.getPayMethod())) {
            // 本地额度回退在前、渠道冲正在后（1.1.6 B-3）：渠道成功后不允许再有可失败的本地步骤。
            // reverse 可能因年度累计行锁超时失败——若排在冲正之后，本地回滚而医保已冲，
            // 操作员重试会对同一 settleNo 二次发冲正报文
            insuranceSplitService.reverse(s.getSettleNo());
            var res = insuranceAdapter.uploadRefund(s.getSettleNo());
            if (!res.ok()) {
                throw new InpException(9023, "医保冲正失败，结算冲销终止: " + res.message());
            }
        }
        return admissionRepo.findById(admissionId).orElseThrow();
    }

    /**
     * 住院中间结算（V90，长住/医保患者刚需）：住院期间就【当前已发生费用】出一张阶段性结算单，
     * 不出院、不释放床位、不冲抵全部押金。与出院结算(discharge)口径不重复的机理：
     *
     * <p>① 只认领尚未被任何中间结算认领的【已执行】医嘱(interim_settle_id is null)并原子打标——
     *    两张中间结算永不重叠认领同一笔费用（并发下靠行锁，每行只被一方 update 命中）。
     * <p>② 本次中间结算金额 = 实际打标那批医嘱的金额之和（先打标后按 settle_id 求和，而非"先算再打标"，
     *    杜绝并发下金额与打标行不一致）。
     * <p>③ 出院结算 discharge() 的费用总额永远按医嘱台账现算(sum 全部已执行医嘱)、从不读中间结算行——
     *    故中间结算结构上不可能抬高出院总额；中间结算金额恒为出院总额的子集。收入确认只认 FINAL。
     * <p>④ balance 与 /account 同口径：押金合计 - 已发生费用合计（含已被中间结算认领部分），
     *    即中间结算单上的 balance 就是当刻真实账户余额，负数=需补押金。
     */
    @Transactional
    public InpSettlement interimSettle(Long admissionId, Long cashierId, String payMethod) {
        InpAdmission adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        if (!"IN_HOSPITAL".equals(adm.getStatus())) {
            throw new InpException(9030, "已出院不能做中间结算");
        }
        // 中间结算暂不发医保通道报文：避免与出院 YB 结算重复上传、污染不可逆的年度累计（起付线/统筹额度）。
        // 仅本地记账留痕；医保分段上传留待按当地医保接口规范实现（见汇报·遗留风险）。
        if ("YB".equals(payMethod)) {
            throw new InpException(9032, "中间结算暂不支持医保通道结算，请用现金/自费方式");
        }

        // 先建结算单占号（拿到 id 才能给医嘱打标）；TEMP 占位含 admissionId 防并发互撞
        InpSettlement s = new InpSettlement();
        s.setSettleNo("TEMP-INT-" + admissionId);
        s.setAdmissionId(admissionId);
        s.setSettleType("INTERIM");
        s.setTotalAmount(BigDecimal.ZERO);   // 打标后回填真实金额
        s.setDepositAmount(BigDecimal.ZERO);
        s.setBalance(BigDecimal.ZERO);
        s.setCashierId(cashierId);
        s.setPayMethod(payMethod == null ? "CASH" : payMethod);
        s = settlementRepo.save(s);
        // 必须 flush 出 INSERT，后续原生 update 的 FK(interim_settle_id) 才引用得到该行
        entityManager.flush();
        final Long settleId = s.getId();

        // ① 原子认领：仅未被认领的已执行医嘱，打上本次结算 id（并发安全，靠行锁与 is null 条件）
        entityManager.createNativeQuery(
                        "update inp_order set interim_settle_id = :sid "
                                + "where admission_id = :aid and status = 'EXECUTED' and interim_settle_id is null")
                .setParameter("sid", settleId)
                .setParameter("aid", admissionId)
                .executeUpdate();
        // ② 本次金额 = 实际被本单认领的医嘱金额之和（对打标结果求和，杜绝与打标行不一致）
        BigDecimal interimAmount = toBig(entityManager.createNativeQuery(
                        "select coalesce(sum(amount), 0) from inp_order where interim_settle_id = :sid")
                .setParameter("sid", settleId).getSingleResult());
        if (interimAmount.signum() <= 0) {
            // 无新增已发生费用可结算——抛出即回滚，打标与占号一并撤销
            throw new InpException(9031, "无新增已发生费用可结算");
        }

        // ④ 账户级快照：与 /account 同口径（押金合计 - 全部已发生费用合计）
        BigDecimal depositTotal = toBig(entityManager.createNativeQuery(
                        "select coalesce(sum(amount), 0) from inp_deposit where admission_id = :aid")
                .setParameter("aid", admissionId).getSingleResult());
        BigDecimal executedTotal = toBig(entityManager.createNativeQuery(
                        "select coalesce(sum(amount), 0) from inp_order where admission_id = :aid and status = 'EXECUTED'")
                .setParameter("aid", admissionId).getSingleResult());

        s.setTotalAmount(interimAmount);
        s.setDepositAmount(depositTotal);
        s.setBalance(depositTotal.subtract(executedTotal));   // 负数=需补押金；即当刻真实账户余额
        // 结算号后缀带 I 与出院结算 S 区分，且与旧纯数字号永不相等
        s.setSettleNo("%s%s-I%06d".formatted(
                configReader.get("billno_prefix_interim", "ZJ"),
                BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE), settleId));
        return settlementRepo.save(s);
    }

    /** 原生查询数值结果统一转 BigDecimal（coalesce(sum) 在 PG numeric 上回 BigDecimal，兜底其它数值型） */
    private static BigDecimal toBig(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }
}
