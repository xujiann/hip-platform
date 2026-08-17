package cn.hip.portal.web;

import cn.hip.outpatient.repository.OutpRegistrationRepository;
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
    private final OutpRegistrationRepository registrationRepository;
    private final SysDeptRepository deptRepository;
    private final cn.hip.outpatient.service.PayService payService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.core.env.Environment environment;

    /** 患者号是顺序号（P%08d），仅凭手机号即可枚举——按患者号计失败次数并锁定 */
    private static final int MAX_LOGIN_FAILED = 5;
    private static final long LOGIN_LOCK_MINUTES = 15;

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
        // 锁定键 = 患者号 + 来源 IP：只按患者号锁会让任何人遍历顺序号段把全院患者锁在门外
        String attemptKey = patientNo + "@" + clientIp(httpReq);
        Integer locked = jdbc.queryForObject("""
                select count(*) from portal_login_attempt where patient_no = ? and locked_until > now()
                """, Integer.class, attemptKey);
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
            return R.fail(9501, "患者号或手机号不正确");
        }
        jdbc.update("delete from portal_login_attempt where patient_no = ?", attemptKey);
        String token = jwtService.issue("portal:" + patient.getId());
        return R.ok(Map.of("token", token, "patientName", patient.getName(), "patientNo", patient.getPatientNo()));
    }

    /** 取真实来源 IP：容器化部署下 getRemoteAddr() 是网关地址 */
    private String clientIp(jakarta.servlet.http.HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String real = req.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : String.valueOf(req.getRemoteAddr());
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
        var reg = registrationRepository.findById(req.registrationId()).orElse(null);
        if (reg == null || !reg.getPatientId().equals(pid)) return R.fail(9502, "挂号记录不存在或非本人");
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
}
