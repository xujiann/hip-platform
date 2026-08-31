package cn.hip.portal.web;

import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者端 H5 接口。登录后令牌主体为 portal:{patientId}，只授予 ROLE_PORTAL，
 * 所有查询强制以令牌内 patientId 为准（患者只能看自己的数据）。
 */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalController {

    private final PatientRepository patientRepository;
    private final JwtService jwtService;
    private final RegistrationService registrationService;
    private final SysDeptRepository deptRepository;
    private final cn.hip.outpatient.service.PayService payService;
    /** v40 分时段预约：与院内挂号台共用同一份两级占号实现，不在患者端另写一份 */
    private final cn.hip.outpatient.service.AppointmentService appointmentService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.core.env.Environment environment;

    /** 患者号是顺序号（P%08d），仅凭手机号即可枚举——按患者号计失败次数并锁定 */
    private static final int MAX_LOGIN_FAILED = 5;
    private static final long LOGIN_LOCK_MINUTES = 15;
    /**
     * 粗锁（1.2.3 五轮 P1-4）：细锁键含来源 IP，而 XFF 可伪造——每次换随机 IP 即绕过细锁
     * 无限猜手机号。粗锁按纯患者号跨 IP 聚合（60 分钟 50 次），把绕过成本抬高 10 倍；
     * 恶意锁定他人的代价同样抬高 10 倍，真实患者误锁 15 分钟可接受。
     */
    private static final int COARSE_MAX_FAILED = 50;
    private static final long COARSE_WINDOW_MINUTES = 60;

    public record PortalLoginRequest(String patientNo, String phone) {}

    /** MVP 以患者号+建档手机号认证；生产环境替换为电子健康卡/微信实名 */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody PortalLoginRequest req,
                                        jakarta.servlet.http.HttpServletRequest httpReq) {
        String patientNo = req.patientNo() == null ? "" : req.patientNo().trim();
        // 格式先校验再落表：patient_no 是 varchar(32)，超长字符串会让**未认证接口**抛 22001 → 500
        if (patientNo.isEmpty() || patientNo.length() > 32 || !patientNo.matches("[A-Za-z0-9_-]+")) {
            return R.fail(9501, "患者号或手机号不正确");
        }
        // 细锁键 = 患者号 + 来源 IP（只按患者号锁会让遍历号段锁全院）；粗锁键 = 患者号@ANY
        String attemptKey = patientNo + "@" + clientIp(httpReq);
        String coarseKey = patientNo + "@ANY";
        Integer locked = jdbc.queryForObject("""
                select count(*) from portal_login_attempt
                where patient_no in (?, ?) and locked_until > now()
                """, Integer.class, attemptKey, coarseKey);
        if (locked != null && locked > 0) {
            return R.fail(9503, "尝试过于频繁，请 " + LOGIN_LOCK_MINUTES + " 分钟后再试");
        }
        var patient = patientRepository.findByPatientNo(patientNo).orElse(null);
        if (patient == null || patient.getPhone() == null || !patient.getPhone().equals(req.phone())) {
            // 原子累加失败计数：并发猜测下"读-判-写"会让计数停在 1，锁定形同虚设。
            // 锁定窗口过后重新计数（否则锁一次即永久：过期后再错一次立刻续锁）。
            jdbc.update("""
                    insert into portal_login_attempt(patient_no, failed_count, last_failed_at)
                    values (?, 1, now())
                    on conflict (patient_no) do update set
                        failed_count = case
                            when portal_login_attempt.last_failed_at < now() - (? || ' minutes')::interval then 1
                            else portal_login_attempt.failed_count + 1 end,
                        last_failed_at = now(),
                        locked_until = case
                            when portal_login_attempt.last_failed_at >= now() - (? || ' minutes')::interval
                                 and portal_login_attempt.failed_count + 1 >= ?
                            then now() + (? || ' minutes')::interval else null end
                    """, attemptKey, String.valueOf(LOGIN_LOCK_MINUTES), String.valueOf(LOGIN_LOCK_MINUTES),
                    MAX_LOGIN_FAILED, String.valueOf(LOGIN_LOCK_MINUTES));
            // 粗锁同款 upsert：窗口 60 分钟、阈值 50，锁定时长与细锁一致
            jdbc.update("""
                    insert into portal_login_attempt(patient_no, failed_count, last_failed_at)
                    values (?, 1, now())
                    on conflict (patient_no) do update set
                        failed_count = case
                            when portal_login_attempt.last_failed_at < now() - (? || ' minutes')::interval then 1
                            else portal_login_attempt.failed_count + 1 end,
                        last_failed_at = now(),
                        locked_until = case
                            when portal_login_attempt.last_failed_at >= now() - (? || ' minutes')::interval
                                 and portal_login_attempt.failed_count + 1 >= ?
                            then now() + (? || ' minutes')::interval else null end
                    """, coarseKey, String.valueOf(COARSE_WINDOW_MINUTES), String.valueOf(COARSE_WINDOW_MINUTES),
                    COARSE_MAX_FAILED, String.valueOf(LOGIN_LOCK_MINUTES));
            return R.fail(9501, "患者号或手机号不正确");
        }
        // 登录成功只清细锁行：粗锁计数保留——攻击者穿插一次正确登录不应重置跨 IP 计数
        jdbc.update("delete from portal_login_attempt where patient_no = ?", attemptKey);
        String token = jwtService.issue("portal:" + patient.getId());
        return R.ok(Map.of("token", token, "patientName", patient.getName(), "patientNo", patient.getPatientNo()));
    }

    /**
     * 来源 IP 直接用 getRemoteAddr（1.2.3 五轮 P1-4）：`forward-headers-strategy: framework`
     * 已让 Spring 解析 XFF，应用内再手工读原始头首段等于信任任意自报值。
     * 经反代时 XFF 仍可能被伪造（细锁因此只是第一道），粗锁按纯患者号聚合兜底。
     * 截断到 90 字符防超长头制造过宽键（列宽 128 - 患者号 32 - '@'）。
     */
    private String clientIp(jakarta.servlet.http.HttpServletRequest req) {
        String addr = String.valueOf(req.getRemoteAddr());
        return addr.length() > 90 ? addr.substring(0, 90) : addr;
    }

    private Long patientId(Authentication auth) {
        String name = auth.getName();
        if (!name.startsWith("portal:")) {
            // 正常情况下院内令牌进不来（安全链已按 ROLE_PORTAL 隔离），显式拒绝防解析异常被兜底成 4000
            throw new org.springframework.security.access.AccessDeniedException("非患者端令牌");
        }
        return Long.valueOf(name.substring("portal:".length()));
    }

    /** 患者端并发高峰即门诊高峰：列表一律单条 SQL，不做逐行回查（1.1.4 B-13） */
    @GetMapping("/my/registrations")
    public R<List<Map<String, Object>>> myRegistrations(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(jdbc.queryForList("""
                select r.id, r.visit_date as "visitDate", r.reg_no as "regNo",
                       coalesce(d.name, '') as "deptName", r.fee, r.status
                from outp_registration r
                left join sys_dept d on d.id = r.dept_id
                where r.patient_id = ? order by r.id desc limit 50
                """, pid));
    }

    /** 检验报告：危急值不直接外显（HH/LL 结果替换为回院提示，避免患者误读延误） */
    @GetMapping("/my/lab-reports")
    public R<List<Map<String, Object>>> myLabReports(Authentication auth) {
        Long pid = patientId(auth);
        // 原实现 50 条挂号 × 每条医嘱 × 每条结果逐次查询——单患者一次刷新即数百条 SQL
        var flat = jdbc.queryForList("""
                select o.id as order_id, o.item_name as order_item, o.created_at,
                       x.item_name, x.result_value, x.unit, x.ref_range, x.abnormal_flag
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                left join outp_lab_result x on x.order_id = o.id
                where r.patient_id = ? and o.order_type = 'LAB' and o.status = 'EXECUTED'
                order by o.id desc, x.id asc
                """, pid);
        var out = new java.util.ArrayList<Map<String, Object>>();
        LinkedHashMap<String, Object> cur = null;
        Object curId = null;
        for (var row : flat) {
            if (!row.get("order_id").equals(curId)) {
                curId = row.get("order_id");
                cur = new LinkedHashMap<>();
                cur.put("orderId", row.get("order_id"));
                cur.put("itemName", row.get("order_item"));
                cur.put("reportDate", row.get("created_at"));
                cur.put("results", new java.util.ArrayList<Map<String, Object>>());
                out.add(cur);
            }
            if (row.get("item_name") == null) continue;   // left join 无结果行
            String flag = (String) row.get("abnormal_flag");
            boolean critical = "HH".equals(flag) || "LL".equals(flag);
            var rm = new LinkedHashMap<String, Object>();
            rm.put("itemName", row.get("item_name"));
            rm.put("resultValue", critical ? "结果异常，请尽快回院复诊" : row.get("result_value"));
            rm.put("unit", critical ? "" : row.get("unit"));
            rm.put("refRange", row.get("ref_range"));
            rm.put("abnormalFlag", critical ? "CRIT" : flag);
            @SuppressWarnings("unchecked")
            var results = (List<Map<String, Object>>) cur.get("results");
            results.add(rm);
        }
        return R.ok(out);
    }

    /** 检查/病理报告（已审核发布） */
    @GetMapping("/my/exam-reports")
    public R<List<Map<String, Object>>> myExamReports(Authentication auth) {
        Long pid = patientId(auth);
        var reports = jdbc.queryForList("""
                select 'EXAM' as report_type, o.item_name, e.impression as conclusion, e.findings as detail,
                       e.verified_at as report_date
                from ris_exam e
                join outp_order o on o.id = e.order_id
                join outp_registration r on r.id = o.registration_id
                where r.patient_id = ? and e.status = 'VERIFIED'
                union all
                select 'PATH', o.item_name, s.diagnosis, s.micro_finding, s.diagnosed_at
                from path_specimen s
                join outp_order o on o.id = s.order_id
                join outp_registration r on r.id = o.registration_id
                where r.patient_id = ? and s.status = 'DIAGNOSED'
                order by report_date desc
                """, pid, pid);
        return R.ok(reports);
    }

    /** 待缴费账单（按就诊分组） */
    @GetMapping("/my/pending-bill")
    public R<List<Map<String, Object>>> myPendingBill(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(jdbc.queryForList("""
                select r.id as registration_id, r.visit_date, sum(o.amount) as total,
                       string_agg(o.item_name, '、') as items
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                where r.patient_id = ? and o.status = 'CREATED'
                group by r.id, r.visit_date order by r.id desc
                """, pid));
    }

    public record PortalPayRequest(Long registrationId, String channel) {}

    /** 在线缴费：出码（校验挂号归属本人） */
    @PostMapping("/my/pay")
    public R<Object> pay(@RequestBody PortalPayRequest req, Authentication auth) {
        Long pid = patientId(auth);
        // 归属校验走 SQL（1.2.0）：跨模块直引 outpatient Repository 违反 ADR-0001 的模块边界
        Integer own = jdbc.queryForObject(
                "select count(*) from outp_registration where id = ? and patient_id = ?",
                Integer.class, req.registrationId(), pid);
        if (own == null || own == 0) return R.fail(9502, "挂号记录不存在或非本人");
        try {
            return R.ok(payService.createPayOrder(req.registrationId(), req.channel()));
        } catch (RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 支付确认——**仅限非生产环境的 Mock 钱包回调**。
     * 真实渠道下，确认必须由渠道带签名的异步通知触发（患者自调即可不付款白拿诊疗），
     * 故此处在 pilot/prod profile 直接拒绝；患者端只保留查询支付状态。
     */
    @PostMapping("/my/pay/{payNo}/confirm")
    public R<Object> confirmPay(@PathVariable String payNo, Authentication auth) {
        if (cn.hip.platform.core.config.HipProfiles.isProduction(environment)) {
            return R.fail(9504, "请在支付渠道完成付款，勿手动确认");
        }
        Long pid = patientId(auth);
        Integer own = jdbc.queryForObject("""
                select count(*) from pay_order po
                join outp_registration r on r.id = po.registration_id
                where po.pay_no = ? and r.patient_id = ?
                """, Integer.class, payNo, pid);
        if (own == null || own == 0) return R.fail(9502, "支付单不存在或非本人");
        try {
            return R.ok(payService.confirm(payNo, null));
        } catch (RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 智能导诊：症状关键词 → 科室推荐（keyword 作 LIKE 模式须转义：录入含 % 的关键词会匹配一切） */
    @GetMapping("/guide")
    public R<List<Map<String, Object>>> guide(@RequestParam String symptom) {
        return R.ok(jdbc.queryForList("""
                select keyword, dept_name, advice from triage_guide
                where ? like '%' || replace(replace(replace(keyword, '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%'
                order by id
                """, symptom));
    }

    @GetMapping("/my/charges")
    public R<List<Map<String, Object>>> myCharges(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(jdbc.queryForList("""
                select c.charge_no as "chargeNo", c.total_amount as "totalAmount",
                       c.pay_method as "payMethod", c.status, c.created_at as "createdAt"
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                where r.patient_id = ? order by c.id desc limit 100
                """, pid));
    }

    /** 可约号源（今日起） */
    @GetMapping("/schedules")
    public R<List<Map<String, Object>>> schedules(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(jdbc.queryForList("""
                select s.id, coalesce(d.name, '') as "deptName", s.reg_type as "regType",
                       s.shift, s.fee, s.capacity - s.booked as remaining
                from outp_schedule s
                left join sys_dept d on d.id = s.dept_id
                where s.schedule_date = ? and s.enabled
                order by s.dept_id, s.id
                """, date));
    }

    public record PortalRegisterRequest(Long scheduleId) {}

    /** 在线预约挂号（复用院内挂号服务，同一防超挂逻辑） */
    @PostMapping("/register")
    public R<Object> register(@RequestBody PortalRegisterRequest req, Authentication auth) {
        try {
            var reg = registrationService.register(patientId(auth), req.scheduleId());
            return R.ok(Map.of("regNo", reg.getRegNo(), "visitDate", reg.getVisitDate().toString(),
                    "fee", reg.getFee(), "deptName",
                    deptRepository.findById(reg.getDeptId()).map(d -> d.getName()).orElse("")));
        } catch (RegistrationService.BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ===== v40 ① 分时段预约（院内 v37 能力的患者端入口，占号逻辑同一份 AppointmentService） =====

    /** 某排班的可约时段 + 余号（患者端只看在用时段） */
    @GetMapping("/schedules/{scheduleId}/slots")
    public R<List<Map<String, Object>>> scheduleSlots(@PathVariable Long scheduleId) {
        return R.ok(appointmentService.slots(scheduleId, true));
    }

    public record PortalApptRequest(Long slotId) {}

    /**
     * 分时段预约：**patientId 只取自令牌**，请求体里不接受任何患者身份字段——
     * 否则任何患者可替他人占号。错误码沿用院内挂号段（3110/3111/3112），前端提示口径一致。
     */
    @PostMapping("/appointments")
    public R<Map<String, Object>> bookAppointment(@RequestBody PortalApptRequest req, Authentication auth) {
        var r = appointmentService.book(req.slotId(), patientId(auth), "PORTAL");
        if (!r.ok()) return R.fail(r.code(), r.message());
        return R.ok(Map.of("id", r.apptId(), "apptNo", r.apptNo()));
    }

    /** 我的预约（只列本人；单条 SQL 带出科室/时段） */
    @GetMapping("/my/appointments")
    public R<List<Map<String, Object>>> myAppointments(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(jdbc.queryForList("""
                select a.id, a.appt_no as "apptNo", a.status, a.source, a.created_at as "createdAt",
                       a.registration_id as "registrationId",
                       s.schedule_date as "scheduleDate", s.shift, s.reg_type as "regType", s.fee,
                       coalesce(d.name, '') as "deptName",
                       sl.time_begin as "timeBegin", sl.time_end as "timeEnd"
                from outp_appointment a
                join outp_schedule s on s.id = a.schedule_id
                join outp_schedule_slot sl on sl.id = a.slot_id
                left join sys_dept d on d.id = s.dept_id
                where a.patient_id = ? order by a.id desc limit 50
                """, pid));
    }

    /**
     * 自助取消预约：**先校验该预约属于令牌患者**（否则患者 A 可取消患者 B 的号），
     * 再走院内同一份两级释放。
     */
    @PostMapping("/my/appointments/{id}/cancel")
    public R<Void> cancelAppointment(@PathVariable Long id, Authentication auth) {
        Long pid = patientId(auth);
        Integer own = jdbc.queryForObject(
                "select count(*) from outp_appointment where id = ? and patient_id = ?", Integer.class, id, pid);
        if (own == null || own == 0) return R.fail(9506, "预约不存在或非本人");
        var r = appointmentService.cancel(id);
        return r.ok() ? R.ok() : R.fail(r.code(), r.message());
    }

    // ===== v40 ② 住院患者端：我的住院 / 每日费用清单 / 押金余额（口径与院内 InpatientController 一致） =====

    /** 我的住院记录（只列本人） */
    @GetMapping("/my/admissions")
    public R<List<Map<String, Object>>> myAdmissions(Authentication auth) {
        Long pid = patientId(auth);
        return R.ok(jdbc.queryForList("""
                select a.id, a.admission_no as "admissionNo", a.status,
                       a.admit_at as "admitAt", a.discharged_at as "dischargedAt",
                       a.admit_diag_name as "admitDiagName",
                       coalesce(cd.name, '') as "deptName", coalesce(wd.name, '') as "wardName",
                       coalesce(b.bed_no, '') as "bedNo"
                from inp_admission a
                left join sys_dept cd on cd.id = a.dept_id
                left join sys_dept wd on wd.id = a.ward_id
                left join inp_bed b on b.id = a.bed_id
                where a.patient_id = ? order by a.id desc limit 50
                """, pid));
    }

    /**
     * 住院记录归属校验（v40）：住院数据里有诊断与费用，**越权读取即隐私事故**。
     * 一律用「id + 令牌 patientId」两条件计数，不接受前端传入的患者身份。
     */
    private boolean ownsAdmission(Long admissionId, Long pid) {
        Integer own = jdbc.queryForObject(
                "select count(*) from inp_admission where id = ? and patient_id = ?", Integer.class, admissionId, pid);
        return own != null && own > 0;
    }

    /** 每日费用清单（口径同院内 /api/inpatient/admissions/{id}/daily-fees：按执行日期取已执行医嘱） */
    @GetMapping("/my/admissions/{id}/daily-fees")
    public R<Map<String, Object>> myDailyFees(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Authentication auth) {
        if (!ownsAdmission(id, patientId(auth))) return R.fail(9505, "住院记录不存在或非本人");
        // date 用 LocalDate 承接（非法日期由全局兜底成 4000），再以字符串入 ?::date，与院内同一 SQL
        String d = date.toString();
        var rows = jdbc.queryForList("""
                select item_name as "itemName", spec, qty, unit_price as "unitPrice", amount,
                       order_type as "orderType", executed_at as "executedAt"
                from inp_order
                where admission_id = ? and status = 'EXECUTED'
                  and executed_at >= ?::date and executed_at < ?::date + interval '1 day'
                order by executed_at
                """, id, d, d);
        var total = rows.stream()
                .map(r -> (java.math.BigDecimal) r.get("amount"))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return R.ok(Map.of("date", d, "rows", rows, "total", total));
    }

    /**
     * 押金/已发生费用/余额。口径与院内 account() 逐项一致：
     * 押金 = sum(inp_deposit.amount)；已发生 = 已执行医嘱金额合计；余额 = 押金 - 已发生（可为负=欠费）；
     * 未执行医嘱（CREATED）只作预判展示，不计入余额。
     */
    @GetMapping("/my/admissions/{id}/account")
    public R<Map<String, Object>> myAdmissionAccount(@PathVariable Long id, Authentication auth) {
        if (!ownsAdmission(id, patientId(auth))) return R.fail(9505, "住院记录不存在或非本人");
        var m = jdbc.queryForMap("""
                select
                  (select coalesce(sum(amount), 0) from inp_deposit where admission_id = ?) as "depositTotal",
                  (select coalesce(sum(amount), 0) from inp_order
                     where admission_id = ? and status = 'EXECUTED') as "executedAmount",
                  (select coalesce(sum(amount), 0) from inp_order
                     where admission_id = ? and status = 'CREATED') as "pendingAmount"
                """, id, id, id);
        var deposit = (java.math.BigDecimal) m.get("depositTotal");
        var executed = (java.math.BigDecimal) m.get("executedAmount");
        var balance = deposit.subtract(executed);
        var out = new LinkedHashMap<String, Object>(m);
        out.put("admissionId", id);
        out.put("balance", balance);
        out.put("owed", balance.signum() < 0);
        return R.ok(out);
    }
}
