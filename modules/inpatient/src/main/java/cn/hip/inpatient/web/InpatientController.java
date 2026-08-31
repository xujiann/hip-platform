package cn.hip.inpatient.web;

import cn.hip.inpatient.entity.InpAdmission;
import cn.hip.inpatient.entity.InpDeposit;
import cn.hip.inpatient.entity.InpOrder;
import cn.hip.inpatient.repository.*;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inpatient")
// 1.1.6 A-1：此前全类无注解，任何在职账号（含 INTERFACE 接口机）可入院/出院/开医嘱/冲销结算
// 1.2.14 P1 越权收口：类级去掉 CASHIER——收费员在住院线只该做预交金，
// 此前类级放行让其可 POST 医嘱篡改任意病区医嘱、PUT 出院诊断操纵 DRG 权重、POST 任意出院。
// 收费员该有的（缴预交金、看账户余额、打一日清单）改到方法级单独放行，不再靠类级放宽。
@org.springframework.security.access.prepost.PreAuthorize(
        "hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')")
@RequiredArgsConstructor
public class InpatientController {

    private final InpatientService inpatientService;
    private final BedRepo bedRepo;
    private final AdmissionRepo admissionRepo;
    private final DepositRepo depositRepo;
    private final OrderRepo orderRepo;
    private final PatientRepository patientRepository;
    private final SysDeptRepository deptRepository;
    private final CurrentUserService currentUserService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final cn.hip.inpatient.service.EmrIntegrityService emrIntegrityService;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    // ===== v39 长期医嘱：停嘱 / 执行行队列 / 按行执行 =====

    /** 停嘱（仅 LONG）：费用固化 + PENDING 行置 SKIPPED；从未执行过则作废 */
    @org.springframework.web.bind.annotation.PostMapping("/orders/{orderId}/stop")
    public R<Void> stopOrder(@PathVariable Long orderId,
                             org.springframework.security.core.Authentication auth) {
        try {
            inpatientService.stopOrder(orderId, currentUserService.idOf(auth));
            return R.ok();
        } catch (cn.hip.inpatient.service.InpatientService.InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 护士执行行队列：某日待执行长期医嘱执行行（含患者/床位/药品） */
    @org.springframework.web.bind.annotation.GetMapping("/exec-lines")
    public R<java.util.List<java.util.Map<String, Object>>> execLines(
            @org.springframework.web.bind.annotation.RequestParam String date) {
        return R.ok(jdbcTemplate.queryForList("""
                select e.id, e.exec_date, e.seq_no, e.status, e.amount,
                       o.id as order_id, o.item_name, o.spec, o.qty, o.usage_route, o.frequency, o.dose_per_time,
                       a.admission_no, p.name as patient_name, b.bed_no
                from inp_order_exec e
                join inp_order o on o.id = e.order_id
                join inp_admission a on a.id = o.admission_id
                join empi_patient p on p.id = a.patient_id
                left join inp_bed b on b.id = a.bed_id
                where e.exec_date = ?::date and e.status = 'PENDING'
                order by a.admission_no, o.id, e.seq_no
                """, date));
    }

    /** 按执行行执行（并发抢占，仅一方成功；扣库存 + 医嘱费用累加） */
    @org.springframework.web.bind.annotation.PutMapping("/exec-lines/{execId}/execute")
    public R<Void> executeExecLine(@PathVariable Long execId,
                                   org.springframework.security.core.Authentication auth) {
        try {
            inpatientService.executeExecLine(execId, currentUserService.idOf(auth));
            return R.ok();
        } catch (cn.hip.inpatient.service.InpatientService.InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 手动补生成某日执行行（运维/测试；每日 06:30 调度自动做） */
    @org.springframework.web.bind.annotation.PostMapping("/exec-lines/generate")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public R<java.util.Map<String, Object>> generateExecLines(
            @org.springframework.web.bind.annotation.RequestParam String date) {
        int n = inpatientService.generateDailyExecLines(java.time.LocalDate.parse(date));
        return R.ok(java.util.Map.of("activeLongOrders", n));
    }

    /** v35：出院/归档前病历完整性只读预检——前端据此弹缺项、按 gate 模式决定提示或禁用 */
    @org.springframework.web.bind.annotation.GetMapping("/admissions/{id}/emr-integrity")
    public R<java.util.Map<String, Object>> emrIntegrity(@PathVariable Long id) {
        var missing = emrIntegrityService.check(id);
        return R.ok(java.util.Map.of(
                "complete", missing.isEmpty(),
                "missing", missing,
                "dischargeGate", configReader.get("emr.gate.discharge", "warn"),
                "archiveGate", configReader.get("emr.gate.archive", "warn")));
    }

    /** 1.0.1（2067）：住院每日费用清单——按执行日期检索医嘱费用明细与合计 */
    @GetMapping("/admissions/{id}/daily-fees")
    public R<Map<String, Object>> dailyFees(@PathVariable Long id, @RequestParam String date) {
        var rows = jdbcTemplate.queryForList("""
                select item_name, spec, qty, unit_price, amount, order_type, executed_at
                from inp_order
                where admission_id = ? and status = 'EXECUTED'
                  and executed_at >= ?::date and executed_at < ?::date + interval '1 day'
                order by executed_at
                """, id, date, date);
        var total = rows.stream()
                .map(r -> (java.math.BigDecimal) r.get("amount"))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return R.ok(Map.of("date", date, "rows", rows, "total", total));
    }

    /**
     * 收尾环·打印1：住院费用一日清单打印数据集（患者可拿走的凭据）。
     * 参照门诊 PrintReportController 的既有数据集实现——只读 JdbcTemplate 组装，
     * 含患者姓名/住院号、当日按项费用与当日合计、以及账户级押金余额。
     */
    // 一日清单交患者：收费窗口也需打，CASHIER 方法级放行（出院小结属临床文书，不给 CASHIER）
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','CASHIER')")
    @GetMapping("/admissions/{id}/print/daily-fee")
    public R<Map<String, Object>> printDailyFee(@PathVariable Long id, @RequestParam String date) {
        var head = jdbcTemplate.queryForList("""
                select a.admission_no, p.name as patient_name, p.patient_no, p.sex,
                       cd.name as dept_name, wd.name as ward_name, b.bed_no
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                left join sys_dept cd on cd.id = a.dept_id
                left join sys_dept wd on wd.id = a.ward_id
                left join inp_bed  b  on b.id = a.bed_id
                where a.id = ?
                """, id);
        if (head.isEmpty()) return R.fail(9003, "住院记录不存在");
        var rows = jdbcTemplate.queryForList("""
                select item_name, spec, qty, unit_price, amount, order_type, executed_at
                from inp_order
                where admission_id = ? and status = 'EXECUTED'
                  and executed_at >= ?::date and executed_at < ?::date + interval '1 day'
                order by executed_at
                """, id, date, date);
        var dayTotal = rows.stream().map(r -> (BigDecimal) r.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 账户级押金/已发生费用/余额（与 /account 同口径）
        var depositTotal = jdbcTemplate.queryForObject(
                "select coalesce(sum(amount),0) from inp_deposit where admission_id = ?", BigDecimal.class, id);
        var executedTotal = jdbcTemplate.queryForObject(
                "select coalesce(sum(amount),0) from inp_order where admission_id = ? and status = 'EXECUTED'",
                BigDecimal.class, id);
        var balance = depositTotal.subtract(executedTotal);
        var m = new LinkedHashMap<String, Object>(head.get(0));
        m.put("date", date);
        m.put("rows", rows);
        m.put("dayTotal", dayTotal);
        m.put("depositTotal", depositTotal);
        m.put("executedTotal", executedTotal);
        m.put("balance", balance);
        m.put("owed", balance.signum() < 0);
        return R.ok(m);
    }

    /**
     * 收尾环·打印2：出院小结打印数据集。
     * 患者基本信息 + 入出院日期 + 诊断（入院/出院主诊断/其他诊断）+ 诊疗经过摘要 + 出院医嘱。
     * 数据源：inp_admission / inp_medical_record（DISCHARGE 记录为出院小结正文）/ inp_diagnosis。
     * 出院带药取已执行药嘱明细，作为出院医嘱的用药参考。
     */
    @GetMapping("/admissions/{id}/print/discharge-summary")
    public R<Map<String, Object>> printDischargeSummary(@PathVariable Long id) {
        var head = jdbcTemplate.queryForList("""
                select a.admission_no, a.admit_at, a.discharged_at, a.status,
                       a.admit_diag_icd, a.admit_diag_name,
                       a.discharge_diag_icd, a.discharge_diag_name,
                       p.name as patient_name, p.patient_no, p.sex, p.birth_date,
                       cd.name as dept_name, wd.name as ward_name, b.bed_no,
                       u.real_name as doctor_name
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                left join sys_dept cd on cd.id = a.dept_id
                left join sys_dept wd on wd.id = a.ward_id
                left join inp_bed  b  on b.id = a.bed_id
                left join sys_user u  on u.id = a.doctor_id
                where a.id = ?
                """, id);
        if (head.isEmpty()) return R.fail(9003, "住院记录不存在");
        var m = new LinkedHashMap<String, Object>(head.get(0));
        // 其他诊断（病案补录的并发症/合并症）
        m.put("otherDiagnoses", jdbcTemplate.queryForList(
                "select icd, name from inp_diagnosis where admission_id = ? order by id", id));
        // 诊疗经过：全部住院病历按时间正序（出院小结 DISCHARGE 记录为正文核心）
        m.put("records", jdbcTemplate.queryForList("""
                select record_type, title, content, created_at,
                       (signature is not null) as signed
                from inp_medical_record
                where admission_id = ?
                order by id
                """, id));
        // 出院带药：已执行药嘱，作为出院医嘱的用药参考
        m.put("meds", jdbcTemplate.queryForList("""
                select item_name, spec, qty, unit, usage_route, frequency, dose_per_time
                from inp_order
                where admission_id = ? and status = 'EXECUTED' and order_type = 'DRUG'
                order by id
                """, id));
        return R.ok(m);
    }

    /** 1.0.4：出院诊断补录（病案编码，出院前后均可；DRG 入组优先取出院诊断） */
    public record DischargeDiagReq(String icd, String name) {}

    @PutMapping("/admissions/{id}/discharge-diag")
    public R<Void> setDischargeDiag(@PathVariable Long id, @RequestBody DischargeDiagReq req) {
        if (req.icd() == null || req.icd().isBlank()) {
            return R.fail(9105, "出院诊断编码必填");
        }
        int n = jdbcTemplate.update(
                "update inp_admission set discharge_diag_icd = ?, discharge_diag_name = ? where id = ?",
                req.icd(), req.name(), id);
        return n == 0 ? R.fail(9106, "住院记录不存在") : R.ok();
    }

    public record CreateBedsRequest(Long wardId, Integer count) {}

    /** 产品化一期：病区床位批量创建（实施工具；bed_no 自增补位，重复号自动跳过） */
    @PostMapping("/beds")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> createBeds(@RequestBody CreateBedsRequest req) {
        if (req.wardId() == null || req.count() == null || req.count() < 1 || req.count() > 200) {
            return R.fail(9020, "wardId 必填，count 须为 1-200");
        }
        var existing = bedRepo.findByWardIdOrderByBedNo(req.wardId()).stream()
                .map(cn.hip.inpatient.entity.InpBed::getBedNo).collect(java.util.stream.Collectors.toSet());
        int created = 0;
        for (int no = 1; created < req.count() && no <= 999; no++) {
            String bedNo = String.format("%02d", no);
            if (existing.contains(bedNo)) continue;
            var b = new cn.hip.inpatient.entity.InpBed();
            b.setWardId(req.wardId());
            b.setBedNo(bedNo);
            bedRepo.save(b);
            created++;
        }
        return R.ok(Map.of("created", created));
    }

    @GetMapping("/beds")
    public R<List<Map<String, Object>>> beds(@RequestParam Long wardId) {
        return R.ok(bedRepo.findByWardIdOrderByBedNo(wardId).stream().map(b -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", b.getId());
            m.put("bedNo", b.getBedNo());
            m.put("status", b.getStatus());
            if (b.getAdmissionId() != null) {
                admissionRepo.findById(b.getAdmissionId()).ifPresent(a ->
                        patientRepository.findById(a.getPatientId()).ifPresent(p ->
                                m.put("patientName", p.getName())));
            }
            return (Map<String, Object>) m;
        }).toList());
    }

    public record AdmitRequest(Long patientId, Long deptId, Long bedId, Long doctorId,
                               String diagIcd, String diagName, BigDecimal deposit, String payMethod) {}

    @PostMapping("/admissions")
    public R<Object> admit(@RequestBody AdmitRequest req, Authentication auth) {
        try {
            return R.ok(toDto(inpatientService.admit(req.patientId(), req.deptId(), req.bedId(),
                    req.doctorId(), req.diagIcd(), req.diagName(),
                    req.deposit(), req.payMethod(), currentUserService.idOf(auth))));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/admissions")
    public R<List<Map<String, Object>>> inHospital() {
        return R.ok(admissionRepo.findByStatusOrderByIdDesc("IN_HOSPITAL").stream()
                .map(this::toDto).toList());
    }

    /** 住院工作区：医嘱 + 押金 + 费用汇总 */
    @GetMapping("/admissions/{id}/workspace")
    public R<Map<String, Object>> workspace(@PathVariable Long id) {
        var m = new LinkedHashMap<String, Object>();
        admissionRepo.findById(id).ifPresent(a -> m.put("admission", toDto(a)));
        List<InpOrder> orders = orderRepo.findByAdmissionIdOrderByIdAsc(id);
        List<InpDeposit> deposits = depositRepo.findByAdmissionIdOrderByIdAsc(id);
        m.put("orders", orders);
        m.put("deposits", deposits);
        m.put("totalAmount", orders.stream().filter(o -> !"CANCELLED".equals(o.getStatus()))
                .map(InpOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("depositAmount", deposits.stream().map(InpDeposit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return R.ok(m);
    }

    /**
     * 住院账户实时状态（收尾环·阻塞1，预交款不足提醒的数据源）。
     * 只读、复用现有查询，不改动任何医疗动作。口径：
     *   已交押金 = sum(inp_deposit.amount)；
     *   已发生费用 = 已执行医嘱金额合计（与出院结算 total 同口径）；
     *   余额 = 押金 - 已发生费用（可为负）；欠费 = 余额 < 0。
     * pendingAmount（未执行医嘱预计费用）不计入余额，仅供医生预判"再执行就欠费"。
     * <p>刻意不硬拦开单/执行：欠费属追缴范畴，医疗行为不因欠费停摆——本端点只让前端能"感知并提醒"。
     */
    // 只读账户余额：收费员催缴/退补差需看，CASHIER 方法级放行
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE','CASHIER')")
    @GetMapping("/admissions/{id}/account")
    public R<Map<String, Object>> account(@PathVariable Long id) {
        List<InpDeposit> deposits = depositRepo.findByAdmissionIdOrderByIdAsc(id);
        List<InpOrder> orders = orderRepo.findByAdmissionIdOrderByIdAsc(id);
        BigDecimal depositTotal = deposits.stream().map(InpDeposit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal executedAmount = orders.stream().filter(o -> "EXECUTED".equals(o.getStatus()))
                .map(InpOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pendingAmount = orders.stream().filter(o -> "CREATED".equals(o.getStatus()))
                .map(InpOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balance = depositTotal.subtract(executedAmount);
        var m = new LinkedHashMap<String, Object>();
        m.put("admissionId", id);
        m.put("depositTotal", depositTotal);
        m.put("executedAmount", executedAmount);
        m.put("pendingAmount", pendingAmount);
        m.put("balance", balance);
        m.put("owed", balance.signum() < 0);
        return R.ok(m);
    }

    public record DepositRequest(BigDecimal amount, String payMethod) {}

    // 预交金是收费职能：CASHIER 方法级单独放行（类级已不含 CASHIER）
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CASHIER','NURSE','DOCTOR_OUTP')")
    @PostMapping("/admissions/{id}/deposits")
    public R<Object> addDeposit(@PathVariable Long id, @RequestBody DepositRequest req, Authentication auth) {
        try {
            return R.ok(inpatientService.addDeposit(id, req.amount(), req.payMethod(), currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record TransferRequest(Long toDeptId, Long toBedId, String reason) {}

    @PostMapping("/admissions/{id}/transfer")
    public R<Object> transfer(@PathVariable Long id, @RequestBody TransferRequest req, Authentication auth) {
        try {
            var adm = inpatientService.transfer(id, req.toDeptId(), req.toBedId(), req.reason(),
                    currentUserService.idOf(auth));
            return R.ok(Map.of("admissionNo", adm.getAdmissionNo(),
                    "deptId", adm.getDeptId(), "wardId", adm.getWardId(), "bedId", adm.getBedId()));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 转科历史：某患者历次转科记录（带科室名/床号/原因），供转科对话框回看 */
    @GetMapping("/admissions/{id}/transfers")
    public R<List<Map<String, Object>>> transfers(@PathVariable Long id) {
        var rows = jdbcTemplate.queryForList("""
                select t.id, t.created_at, t.reason,
                       fd.name as from_dept_name, td.name as to_dept_name,
                       fb.bed_no as from_bed_no, tb.bed_no as to_bed_no
                from inp_transfer_log t
                left join sys_dept fd on fd.id = t.from_dept_id
                left join sys_dept td on td.id = t.to_dept_id
                left join inp_bed  fb on fb.id = t.from_bed_id
                left join inp_bed  tb on tb.id = t.to_bed_id
                where t.admission_id = ?
                order by t.id desc
                """, id);
        return R.ok(rows);
    }

    public record CreateOrdersRequest(List<InpatientService.OrderLine> lines) {}

    @PostMapping("/admissions/{id}/orders")
    public R<Object> createOrders(@PathVariable Long id, @RequestBody CreateOrdersRequest req, Authentication auth) {
        try {
            return R.ok(inpatientService.createOrders(id, req.lines(), currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 护士执行队列（全院未执行医嘱） */
    @GetMapping("/orders/pending")
    public R<List<Map<String, Object>>> pendingOrders() {
        return R.ok(orderRepo.findByStatusOrderByIdAsc("CREATED").stream().map(o -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("orderId", o.getId());
            m.put("groupNo", o.getGroupNo());
            m.put("orderType", o.getOrderType());
            m.put("itemName", o.getItemName());
            m.put("qty", o.getQty());
            m.put("usageRoute", o.getUsageRoute());
            m.put("frequency", o.getFrequency());
            m.put("dosePerTime", o.getDosePerTime());
            admissionRepo.findById(o.getAdmissionId()).ifPresent(a -> {
                m.put("admissionNo", a.getAdmissionNo());
                patientRepository.findById(a.getPatientId()).ifPresent(p -> m.put("patientName", p.getName()));
            });
            return (Map<String, Object>) m;
        }).toList());
    }

    /** 1.0.7：作废未执行医嘱（出院前清理误开医嘱；执行掉会多计费并白扣库存） */
    @PutMapping("/orders/{orderId}/cancel")
    public R<Void> cancelOrder(@PathVariable Long orderId) {
        try {
            inpatientService.cancelOrder(orderId);
            return R.ok();
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PutMapping("/orders/{orderId}/execute")
    public R<Object> execute(@PathVariable Long orderId, Authentication auth) {
        try {
            return R.ok(inpatientService.execute(orderId, currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping("/admissions/{id}/discharge")
    public R<Object> discharge(@PathVariable Long id,
                               @RequestParam(required = false, defaultValue = "CASH") String payMethod,
                               Authentication auth) {
        try {
            return R.ok(inpatientService.discharge(id, currentUserService.idOf(auth), payMethod));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 结算冲销（出院召回）：住院侧此前无任何退费/冲正路径，结算错误只能改库（1.1.3 B-5） */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    @PostMapping("/admissions/{id}/cancel-settlement")
    public R<Object> cancelSettlement(@PathVariable Long id,
                                      @RequestParam(required = false) Long bedId,
                                      Authentication auth) {
        try {
            return R.ok(toDto(inpatientService.cancelSettlement(id, currentUserService.idOf(auth), bedId)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 住院中间结算（V90）：住院期间对当前已发生费用出一张阶段性结算单，不出院、不释放床位。
     * 收费职能，CASHIER 方法级放行（类级已不含 CASHIER）。中间结算与出院结算口径不重复见 InpatientService。
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CASHIER','NURSE','DOCTOR_OUTP')")
    @PostMapping("/admissions/{id}/interim-settle")
    public R<Object> interimSettle(@PathVariable Long id,
                                   @RequestParam(required = false, defaultValue = "CASH") String payMethod,
                                   Authentication auth) {
        try {
            return R.ok(inpatientService.interimSettle(id, currentUserService.idOf(auth), payMethod));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 冲销一张误开的中间结算单（第七轮审阅 P3-B）：置 CANCELLED 并释放医嘱打标，限收费员/管理员 */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
    @PostMapping("/interim-settlements/{settleId}/cancel")
    public R<Object> cancelInterimSettle(@PathVariable Long settleId, Authentication auth) {
        try {
            inpatientService.cancelInterimSettle(settleId, currentUserService.idOf(auth));
            return R.ok(null);
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 某次入院的历次中间结算单（住院费用页回看，只读） */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CASHIER','NURSE','DOCTOR_OUTP')")
    @GetMapping("/admissions/{id}/interim-settlements")
    public R<List<Map<String, Object>>> interimSettlements(@PathVariable Long id) {
        return R.ok(jdbcTemplate.queryForList("""
                select id, settle_no, total_amount, deposit_amount, balance, pay_method, created_at
                from inp_settlement
                where admission_id = ? and settle_type = 'INTERIM'
                order by id
                """, id));
    }

    private Map<String, Object> toDto(InpAdmission a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", a.getId());
        m.put("admissionNo", a.getAdmissionNo());
        m.put("patientId", a.getPatientId());
        patientRepository.findById(a.getPatientId()).ifPresent(p -> {
            m.put("patientNo", p.getPatientNo());
            m.put("patientName", p.getName());
            m.put("sex", p.getSex());
        });
        m.put("deptName", deptRepository.findById(a.getDeptId()).map(d -> d.getName()).orElse(""));
        m.put("wardName", deptRepository.findById(a.getWardId()).map(d -> d.getName()).orElse(""));
        m.put("bedId", a.getBedId());
        bedRepo.findById(a.getBedId()).ifPresent(b -> m.put("bedNo", b.getBedNo()));
        m.put("admitDiagName", a.getAdmitDiagName());
        m.put("dischargeDiagIcd", a.getDischargeDiagIcd());
        m.put("dischargeDiagName", a.getDischargeDiagName());
        m.put("status", a.getStatus());
        m.put("admitAt", a.getAdmitAt());
        return m;
    }
}
