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
    private final EmrIntegrityService emrIntegrityService;
    private final ArrearsService arrearsService;

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
                            String usageRoute, String frequency, String dosePerTime,
                            String orderNature) {
        /** 兼容构造器：既有 50+ 调用点缺省 TEMP（行为与历史一致） */
        public OrderLine(String orderType, Long itemId, Integer qty,
                         String usageRoute, String frequency, String dosePerTime) {
            this(orderType, itemId, qty, usageRoute, frequency, dosePerTime, null);
        }
    }

    /** 开住院医嘱（记账式：TEMP 开立即计费；v39 LONG 长期医嘱按执行行累计计费） */
    @Transactional
    public List<InpOrder> createOrders(Long admissionId, List<OrderLine> lines, Long doctorId) {
        InpAdmission adm = admissionRepo.findById(admissionId)
                .orElseThrow(() -> new InpException(9003, "住院记录不存在"));
        if (!"IN_HOSPITAL".equals(adm.getStatus())) {
            throw new InpException(9005, "已出院不能开医嘱");
        }
        // v43 车道C（8016）：停用药品不可开医嘱。**本方法唯一的新增判断，且是纯只读预检**——
        // 放在 nextGroupSeq()／orderRepo.save()／generateExecLines() 之前，
        // 失败时零副作用（无序列、无 inp_order、无 inp_order_exec、无库存变动）。
        // 药品不存在仍留给下方既有的 9006 路径处理，此处只管"存在但已停用"。
        for (OrderLine line : lines) {
            if (!"DRUG".equals(line.orderType())) continue;
            DrugItem d = drugRepository.findById(line.itemId()).orElse(null);
            if (d != null && !Boolean.TRUE.equals(d.getEnabled())) {
                throw new InpException(8016, "药品已停用，不可开单：" + d.getName()
                        + (d.getDisableReason() == null ? "" : "（停用原因：" + d.getDisableReason() + "）"));
            }
        }
        // v34 自费医嘱知情同意 gate（试点期可配 emr.gate.consent.selfpay，默认 warn）。
        // 仅 block 模式才查（warn/off 只读一次缓存配置、零额外开销、零打断）；含自费项而无有效自费
        // 同意书时硬拦。self_pay 标记由主数据维护补齐，未标记则天然无自费项、gate 空转。
        if ("block".equals(configReader.get("emr.gate.consent.selfpay", "warn"))
                && lines.stream().anyMatch(this::isSelfPayItem)
                && !hasSignedConsent(admissionId, "SELF_PAY")) {
            throw new InpException(9118, "含自费医嘱但无有效自费知情同意书");
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
            // v39：LONG 长期医嘱 amount=累计执行金额（开立为 0，随执行行执行累加）；TEMP 维持开立即计费
            boolean isLong = "LONG".equals(line.orderNature());
            o.setOrderNature(isLong ? "LONG" : "TEMP");
            o.setAmount(isLong ? BigDecimal.ZERO
                    : o.getUnitPrice().multiply(BigDecimal.valueOf(o.getQty())));
            InpOrder saved = orderRepo.save(o);
            if (isLong) {
                entityManager.flush();   // 原生 insert 的 FK 需先见到该行
                generateExecLines(saved, BusinessDates.today());
            }
            return saved;
        }).toList();
    }

    /** v39：按频次为长期医嘱生成某日执行行（qd=1/bid=2/tid=3/qid=4 行/日；幂等 on conflict skip） */
    private void generateExecLines(InpOrder o, java.time.LocalDate date) {
        int perDay = switch (o.getFrequency() == null ? "" : o.getFrequency().toLowerCase()) {
            case "bid" -> 2;
            case "tid" -> 3;
            case "qid" -> 4;
            default -> 1;
        };
        BigDecimal lineAmount = o.getUnitPrice().multiply(BigDecimal.valueOf(o.getQty()));
        for (int seq = 1; seq <= perDay; seq++) {
            entityManager.createNativeQuery("""
                    insert into inp_order_exec(order_id, exec_date, seq_no, amount)
                    values (:oid, :d, :seq, :amt) on conflict (order_id, exec_date, seq_no) do nothing
                    """)
                    .setParameter("oid", o.getId()).setParameter("d", date)
                    .setParameter("seq", seq).setParameter("amt", lineAmount)
                    .executeUpdate();
        }
    }

    /** v39：为全部活跃长期医嘱（在院+未停嘱+未作废）生成某日执行行。每日调度调用，幂等可重跑。 */
    @Transactional
    public int generateDailyExecLines(java.time.LocalDate date) {
        int gen = 0;
        for (InpOrder o : orderRepo.findActiveLongOrders()) {
            generateExecLines(o, date);
            gen++;
        }
        return gen;
    }

    /**
     * v39：执行一条长期医嘱执行行。条件更新抢占（并发双执行仅一方成功）→ 药品扣库存 →
     * 原子累加医嘱 amount 并置 EXECUTED——下游读 "EXECUTED 的 amount" 口径自动含累计费用。
     */
    @Transactional
    public void executeExecLine(Long execId, Long executorId) {
        var rows = entityManager.createNativeQuery("""
                select e.order_id, e.amount, o.order_type, o.item_id, o.qty, o.group_no, o.item_name, o.stop_at
                from inp_order_exec e join inp_order o on o.id = e.order_id where e.id = :id
                """).setParameter("id", execId).getResultList();
        if (rows.isEmpty()) throw new InpException(9126, "执行行不存在");
        Object[] r = (Object[]) rows.get(0);
        if (r[7] != null) throw new InpException(9127, "该长期医嘱已停嘱，不能再执行");
        int claimed = entityManager.createNativeQuery("""
                update inp_order_exec set status = 'DONE', executor_id = :uid, executed_at = now()
                where id = :id and status = 'PENDING'
                """).setParameter("uid", executorId).setParameter("id", execId).executeUpdate();
        if (claimed == 0) throw new InpException(9126, "执行行不存在或已执行/已跳过");
        Long orderId = ((Number) r[0]).longValue();
        BigDecimal lineAmount = (BigDecimal) r[1];
        if ("DRUG".equals(r[2])) {
            Long drugId = ((Number) r[3]).longValue();
            int qty = ((Number) r[4]).intValue();
            if (drugRepository.deductStock(drugId, qty) == 0) {
                throw new InpException(9010, "库存不足: " + r[6]);
            }
            inventoryService.logOut(drugId, qty, (String) r[5], executorId);
        }
        // 原子累加 + 置 EXECUTED；executed_at 只记首次（LONG 逐日费用看执行行，避免多日归到末日）
        entityManager.createNativeQuery("""
                update inp_order set amount = amount + :amt, status = 'EXECUTED',
                       executor_id = :uid, executed_at = coalesce(executed_at, now())
                where id = :oid
                """).setParameter("amt", lineAmount).setParameter("uid", executorId)
                .setParameter("oid", orderId).executeUpdate();
        // 原生累加绕过 JPA 一级缓存——不清缓存则同事务后续 findById/discharge 汇总读到陈旧 amount
        entityManager.clear();
    }

    /**
     * v39：停嘱（仅 LONG）。今日起 PENDING 执行行置 SKIPPED、不再生成新行——费用固化，
     * 停嘱后可参与中间结算认领。从未执行过的长期医嘱停嘱即作废（否则 9012 拦出院）。
     */
    @Transactional
    public void stopOrder(Long orderId, Long doctorId) {
        InpOrder o = orderRepo.findById(orderId).orElseThrow(() -> new InpException(9008, "医嘱不存在"));
        if (!"LONG".equals(o.getOrderNature())) throw new InpException(9128, "停嘱仅适用于长期医嘱");
        int n = entityManager.createNativeQuery(
                "update inp_order set stop_at = now(), stop_doctor_id = :uid where id = :oid and stop_at is null")
                .setParameter("uid", doctorId).setParameter("oid", orderId).executeUpdate();
        if (n == 0) throw new InpException(9127, "该长期医嘱已停嘱");
        entityManager.createNativeQuery(
                "update inp_order_exec set status = 'SKIPPED' where order_id = :oid and status = 'PENDING'")
                .setParameter("oid", orderId).executeUpdate();
        orderRepo.cancelIfCreated(orderId);   // 从未执行过：CREATED→CANCELLED
        entityManager.clear();   // 原生 update 后清一级缓存，防调用方读到陈旧 status/stopAt
    }

    /** v34：该行医嘱对应的项目/药品是否标记为自费（self_pay），供自费同意 gate 判定 */
    private boolean isSelfPayItem(OrderLine line) {
        String table = "DRUG".equals(line.orderType()) ? "md_drug" : "md_charge_item";
        var rs = entityManager.createNativeQuery("select self_pay from " + table + " where id = :id")
                .setParameter("id", line.itemId()).getResultList();
        return !rs.isEmpty() && Boolean.TRUE.equals(rs.get(0));
    }

    /** v34：该住院是否已有指定类型的有效（SIGNED、未作废）知情同意书 */
    private boolean hasSignedConsent(Long admissionId, String consentType) {
        Number c = (Number) entityManager.createNativeQuery(
                "select count(*) from emr_consent where admission_id = :aid and consent_type = :t "
                        + "and status = 'SIGNED' and revoked_at is null")
                .setParameter("aid", admissionId).setParameter("t", consentType).getSingleResult();
        return c.longValue() > 0;
    }

    /** 护士执行：药品执行时原子扣库存并留痕 */
    @Transactional
    public InpOrder execute(Long orderId, Long executorId) {
        InpOrder o = orderRepo.findById(orderId).orElseThrow(() -> new InpException(9008, "医嘱不存在"));
        // v39：长期医嘱按执行行逐次执行计费，不走单次执行路径（否则只计一次费且状态机错乱）
        if ("LONG".equals(o.getOrderNature())) {
            throw new InpException(9125, "长期医嘱请按每日执行行执行");
        }
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
        // v35 出院病历完整性 gate（试点期可配 emr.gate.discharge，默认 warn 静默放行不硬拦）。
        // 放在 claimDischarge 抢占之前——只读、throw 前零副作用，不搅动既有抢占/回滚/并发时序。
        // warn 缺项由只读预检端点 emr-integrity 暴露给前端提示，不在此拦截（返回类型无法挂 warning）。
        if ("block".equals(configReader.get("emr.gate.discharge", "warn"))) {
            var missing = emrIntegrityService.check(admissionId);
            if (!missing.isEmpty()) {
                throw new InpException(9124, "病历不完整，不能出院结算：" + String.join("、", missing));
            }
        }
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
        // v41 欠费挂账（纯追加，不改上文任何逻辑与时序）：balance<0 即欠费出院——
        // 医疗行为不因欠费停摆（见 InpatientController#account），但追缴必须留下台账。
        // 位置刻意排在医保上传**之前**：渠道成功后不允许再有可失败的本地步骤（1.1.6 B-3 同款纪律）。
        // flush 先落 settlement 的 INSERT/UPDATE，下游原生 SQL 的 FK(settle_id) 才引用得到该行。
        // 幂等由 admission_id 唯一 + on conflict 保证：冲销召回后重结算是更新而非重复挂账。
        entityManager.flush();
        arrearsService.syncOnDischarge(admissionId, s.getId(), s.getBalance());
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

        // ① 原子认领：仅未被认领的已执行医嘱，打上本次结算 id（并发安全，靠行锁与 is null 条件）。
        // 认领同时校验住院仍在院（第七轮审阅 P3-A）：与并发 discharge/claimDischarge 抢跑时，
        // 出院已提交则子查询为空、认领 0 行——避免为已出院的 admission 留一张时序矛盾的 INTERIM 幽灵单。
        // v39：未停嘱的 LONG 不认领——其 amount 随执行行持续累加，认领后已结算单金额会漂移；
        // 停嘱后费用固化方可认领（中间结算只结"已固化"费用）。
        entityManager.createNativeQuery(
                        "update inp_order set interim_settle_id = :sid "
                                + "where admission_id = :aid and status = 'EXECUTED' and interim_settle_id is null "
                                + "and (order_nature = 'TEMP' or stop_at is not null) "
                                + "and exists (select 1 from inp_admission a where a.id = :aid and a.status = 'IN_HOSPITAL')")
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

    /**
     * 冲销一张 INTERIM 中间结算单（第七轮审阅 P3-B）：误开的中间结算此前无系统内纠正路径，
     * 只能改库。置 CANCELLED 并释放其认领的医嘱打标（interim_settle_id=null），让这些医嘱
     * 可被后续中间结算或出院结算重新纳入。资金上本无副作用（出院按台账现算、忽略打标），
     * 但法定留痕需要一条可作废的路径而非改库。
     */
    @Transactional
    public void cancelInterimSettle(Long settleId, Long operatorId) {
        InpSettlement s = settlementRepo.findById(settleId)
                .orElseThrow(() -> new InpException(9033, "结算单不存在"));
        if (!"INTERIM".equals(s.getSettleType())) {
            throw new InpException(9033, "仅中间结算单可冲销，出院结算请走结算冲销");
        }
        // 条件更新抢占：并发双击/两窗口同时冲销只一方成功（同全仓抢占纪律）
        int n = entityManager.createNativeQuery(
                        "update inp_settlement set status = 'CANCELLED', refunded_at = now(), refund_by = :op "
                                + "where id = :id and status = 'PAID' and settle_type = 'INTERIM'")
                .setParameter("op", operatorId)
                .setParameter("id", settleId)
                .executeUpdate();
        if (n == 0) {
            throw new InpException(9034, "该中间结算单已冲销或状态异常");
        }
        // 释放打标：被本单认领的医嘱回到未结算，可被后续结算重新纳入
        entityManager.createNativeQuery(
                        "update inp_order set interim_settle_id = null where interim_settle_id = :id")
                .setParameter("id", settleId)
                .executeUpdate();
    }

    /** 原生查询数值结果统一转 BigDecimal（coalesce(sum) 在 PG numeric 上回 BigDecimal，兜底其它数值型） */
    private static BigDecimal toBig(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        return new BigDecimal(v.toString());
    }
}
