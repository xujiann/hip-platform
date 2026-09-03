package cn.hip.server.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.hip.platform.core.config.BusinessDates;

/** 十三期：打印数据集与日结报表 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','CASHIER','DOCTOR_OUTP','NURSE','TECHNICIAN')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class PrintReportController {

    private final JdbcTemplate jdbc;
    private final cn.hip.platform.core.security.CurrentUserService currentUserService;

    /** 挂号凭条数据 */
    @GetMapping("/api/print/registration/{id}")
    public R<Map<String, Object>> registrationSlip(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select r.id, r.reg_no, r.visit_date, r.fee, r.created_at,
                       p.name as patient_name, p.patient_no, d.name as dept_name
                from outp_registration r
                join empi_patient p on p.id = r.patient_id
                join sys_dept d on d.id = r.dept_id where r.id = ?
                """, id);
        return rows.isEmpty() ? R.fail(9930, "挂号不存在") : R.ok(rows.get(0));
    }

    /** 收费票据数据（含明细行） */
    @GetMapping("/api/print/charge/{id}")
    public R<Map<String, Object>> chargeReceipt(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select c.charge_no, c.total_amount, c.pay_method, c.created_at,
                       p.name as patient_name, p.patient_no
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id where c.id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(9931, "结算单不存在");
        var receipt = rows.get(0);
        receipt.put("items", jdbc.queryForList(
                "select item_name, spec, qty, unit_price, amount from outp_order where charge_id = ?", id));
        return R.ok(receipt);
    }

    /**
     * v40 票据补打检索：收费员按结算单号/患者号/姓名找回历史单据补打。
     * 此前打印数据集与版式都在，但收费台只能打"刚结算的那一单"——重打无入口。
     */
    @GetMapping("/api/print/charge-search")
    public R<List<Map<String, Object>>> chargeSearch(@RequestParam(required = false) String keyword,
                                                     @RequestParam(required = false) String date) {
        var where = new StringBuilder(" where 1=1 ");
        var args = new java.util.ArrayList<Object>();
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (c.charge_no ilike ? or p.patient_no ilike ? or p.name ilike ?) ");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        // 半开区间走索引（1.1.3 起全库口径；?::date 显式定型避免 PG 推断不出参数类型）
        if (date != null && !date.isBlank()) {
            where.append(" and c.created_at >= ?::date and c.created_at < (?::date + 1) ");
            args.add(date);
            args.add(date);
        }
        return R.ok(jdbc.queryForList("""
                select c.id, c.charge_no, c.total_amount, c.pay_method, c.status, c.created_at,
                       p.name as patient_name, p.patient_no,
                       (select count(*) from fin_print_log l where l.doc_type = 'CHARGE' and l.doc_id = c.id) as print_count
                from outp_charge c
                join outp_registration r on r.id = c.registration_id
                join empi_patient p on p.id = r.patient_id
                """ + where + " order by c.id desc limit 100", args.toArray()));
    }

    /**
     * v40 打印留痕：前端每次打开打印页记一次（补打次数可追溯，财务/审计关注）。
     *
     * <p><b>v43 车道B 决策：五种日常单据（处方笺/检验申请单/检查申请单/治疗单/导诊单）
     * 一律不写本表，白名单保持 CHARGE/REGISTRATION 两项不变。</b>
     * fin_print_log 是 <code>fin_</code> 前缀的财务票据留痕表，V125 建表注释把语义定死为
     * "CHARGE 收费票据 / REGISTRATION 挂号凭条"，其唯一消费口径是 chargeSearch 的
     * <code>print_count</code>（收费员看"这张票补打过几次"）。临床单据是诊疗过程文书、
     * 一次就诊里天然要打好几张、且和票据号无关——混进来会让"补打次数"从财务口径退化成
     * "打印动作计数"，与 v42 CHANGELOG 记的纪律（护理文书不得混进财务票据留痕表污染补打
     * 次数口径）是同一条。临床单据要留痕应另立 <code>clin_print_log</code> 表并单独定义口径，
     * 属独立议题，本车道不擅自扩大 fin_print_log 语义。
     */
    @PostMapping("/api/print/log")
    public R<Void> logPrint(@RequestParam String docType, @RequestParam Long docId,
                            org.springframework.security.core.Authentication auth) {
        if (!List.of("CHARGE", "REGISTRATION").contains(docType)) return R.fail(4000, "单据类型不正确");
        jdbc.update("insert into fin_print_log(doc_type, doc_id, operator_id) values (?,?,?)",
                docType, docId, currentUserService.idOf(auth));
        return R.ok();
    }

    /** 检验报告单数据 */
    @GetMapping("/api/print/lab-report/{orderId}")
    public R<Map<String, Object>> labReport(@PathVariable Long orderId) {
        var rows = jdbc.queryForList("""
                select o.id, o.item_name, o.group_no, o.created_at,
                       p.name as patient_name, p.sex, p.patient_no
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where o.id = ? and o.order_type = 'LAB'
                """, orderId);
        if (rows.isEmpty()) return R.fail(9932, "检验申请不存在");
        var report = rows.get(0);
        report.put("results", jdbc.queryForList(
                "select item_name, result_value, unit, ref_range, abnormal_flag from outp_lab_result where order_id = ? order by id",
                orderId));
        return R.ok(report);
    }

    // ==================== v43 车道B：五种日常单据打印数据集（偏离表 1026★） ====================
    // 处方笺 / 检验申请单 / 检查申请单 / 治疗单 / 导诊单——全仓此前零命中，而这五张纸医院每天要用。
    // 设计取舍：**一个端点带 docType 路径段**而不是五个端点——PrintView 的 `?type=&id=` 契约天然
    // 一一对应，非法 type 才有唯一收口点返 4892；五个端点的话"类型不支持"根本无处可返。
    // 全部只读：不改开单/收费/发药任何逻辑，不写任何表（含不写 fin_print_log，理由见 logPrint 注释）。

    /** 单据类型 → 医嘱类型。guide-sheet 不按单一医嘱类型取数（它要的是"还没做完的都列出来"），单独走分支。 */
    private static final Map<String, String> DOC_ORDER_TYPE = Map.of(
            "prescription", "DRUG",
            "lab-request", "LAB",
            "exam-request", "EXAM",
            "treat-sheet", "TREAT");

    /** 单据类型 → 中文名（同时充当受支持类型白名单，前端 titles 映射与之逐字对应） */
    private static final Map<String, String> DOC_TITLE = Map.of(
            "prescription", "处方笺",
            "lab-request", "检验申请单",
            "exam-request", "检查申请单",
            "treat-sheet", "治疗单",
            "guide-sheet", "导诊单");

    /** 五种单据共用页眉：患者 + 就诊科室/号序 + 接诊医师（含职称，签名栏要用） */
    private static final String DOC_HEADER_SQL = """
            select r.id as registration_id, r.reg_no, r.visit_date, r.status as reg_status, r.created_at,
                   p.name as patient_name, p.patient_no, p.sex, p.birth_date, p.allergy_history,
                   d.name as dept_name, d.code as dept_code,
                   u.real_name as doctor_name, u.title as doctor_title
            from outp_registration r
            join empi_patient p on p.id = r.patient_id
            join sys_dept d on d.id = r.dept_id
            left join sys_user u on u.id = r.doctor_id
            where r.id = ?
            """;

    /**
     * 医嘱行取数。item_id 对 DRUG 指向 md_drug、对其余指向 md_charge_item，
     * 两张字典表主键会撞号，故 join 条件里必须带 order_type 判别，否则药品会错挂上收费项目的执行科室。
     * exec_dept 即"这张单去哪儿做"（md_charge_item.exec_dept_id），是导诊单/申请单的核心信息。
     * lis_sample 只在检验采样后才有行，left join 后为 null 属正常（未采样），版式上留手填位。
     */
    private static final String DOC_ORDER_SQL = """
            select o.id, o.group_no, o.order_type, o.item_code, o.item_name, o.spec, o.unit, o.qty,
                   o.unit_price, o.amount, o.usage_route, o.frequency, o.dose_per_time, o.days,
                   o.status, o.created_at,
                   du.real_name as order_doctor_name,
                   dr.dose_form, dr.antibiotic,
                   ci.category as item_category,
                   ed.name as exec_dept_name,
                   s.barcode as sample_barcode, s.status as sample_status, s.collected_at as sample_collected_at
            from outp_order o
            left join sys_user du on du.id = o.doctor_id
            left join md_drug dr on dr.id = o.item_id and o.order_type = 'DRUG'
            left join md_charge_item ci on ci.id = o.item_id and o.order_type <> 'DRUG'
            left join sys_dept ed on ed.id = ci.exec_dept_id
            left join lis_sample s on s.order_id = o.id
            where o.registration_id = ?
            """;

    /** 出生日期 → 周岁。业务日期走 BusinessDates（演示库冻结日期时年龄不能按真实今天算）。 */
    private static Integer ageOf(Object birthDate) {
        if (birthDate == null) return null;
        LocalDate b = birthDate instanceof java.sql.Date d
                ? d.toLocalDate()
                : LocalDate.parse(String.valueOf(birthDate).substring(0, 10));
        return java.time.Period.between(b, BusinessDates.today()).getYears();
    }

    /**
     * 五种日常单据打印数据集：{@code GET /api/print/doc/{docType}/{registrationId}}。
     *
     * <p>docType ∈ prescription | lab-request | exam-request | treat-sheet | guide-sheet，
     * 与 PrintView 的 `?type=` 逐字一致。可选 {@code groupNo} 只打其中一张（一次就诊可能开了
     * 两张处方、三张检查申请单——处方号/申请单号就是 outp_order.group_no）。
     *
     * <p>返回体：页眉字段平铺（患者/科室/号序/医师）+ diagnoses 诊断 + emr 病历摘要
     * + groups 按单据号分组的明细（每组一张纸）+ rows 明细行平铺（便于断言与合计）。
     * 导诊单没有"单据号"概念，只回 rows（本次就诊尚未完成的项目清单）。
     *
     * <p>错误码：4892 类型不支持 / 4893 单据数据不存在（挂号不存在，或本次就诊无该类内容）。
     */
    @GetMapping("/api/print/doc/{docType}/{registrationId}")
    public R<Map<String, Object>> clinicalDoc(@PathVariable String docType,
                                              @PathVariable Long registrationId,
                                              @RequestParam(required = false) String groupNo) {
        if (!DOC_TITLE.containsKey(docType)) {
            return R.fail(4892, "单据类型不支持: " + docType);
        }
        var head = jdbc.queryForList(DOC_HEADER_SQL, registrationId);
        if (head.isEmpty()) return R.fail(4893, "单据数据不存在：挂号记录不存在");

        var m = new LinkedHashMap<String, Object>(head.get(0));
        m.put("docType", docType);
        m.put("docTitle", DOC_TITLE.get(docType));
        m.put("age", ageOf(head.get(0).get("birth_date")));
        // 临床诊断：主诊断排首位（申请单法定必填项）
        m.put("diagnoses", jdbc.queryForList("""
                select icd_code, icd_name, primary_diag from outp_diagnosis
                where registration_id = ? order by primary_diag desc, id
                """, registrationId));
        // 病史摘要：申请单要给医技科室看"为什么做这个检查"，取自本次门诊病历
        var emr = jdbc.queryForList("""
                select chief_complaint, present_illness, physical_exam, advice
                from outp_emr where registration_id = ?
                """, registrationId);
        m.put("emr", emr.isEmpty() ? Map.of() : emr.get(0));
        m.put("printedOn", BusinessDates.today().toString());

        if ("guide-sheet".equals(docType)) {
            // 导诊单：本次就诊"还没做完"的项目——CREATED 待缴费 / CHARGED 已缴费待执行。
            // 已 EXECUTED / DISPENSED / CANCELLED 的不再引导患者去跑。空清单也是合法导诊单
            // （只挂号未开单的患者照样要拿着单子找诊室），故此处不返 4893。
            m.put("rows", jdbc.queryForList(
                    DOC_ORDER_SQL + " and o.status in ('CREATED','CHARGED') order by o.order_type, o.id",
                    registrationId));
            return R.ok(m);
        }

        var args = new ArrayList<Object>(List.of(registrationId, DOC_ORDER_TYPE.get(docType)));
        var sql = new StringBuilder(DOC_ORDER_SQL).append(" and o.order_type = ? ");
        if (groupNo != null && !groupNo.isBlank()) {
            sql.append(" and o.group_no = ? ");
            args.add(groupNo.trim());
        }
        sql.append(" order by o.group_no, o.id");
        var rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            return R.fail(4893, "单据数据不存在：本次就诊无可打印的" + DOC_TITLE.get(docType) + "内容");
        }
        m.put("rows", rows);

        // 按单据号分组：一次就诊可开多张处方/多张申请单，每组各出一张纸（版式上分页）
        var grouped = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var r : rows) {
            grouped.computeIfAbsent(String.valueOf(r.get("group_no")), k -> new ArrayList<>()).add(r);
        }
        m.put("groups", grouped.entrySet().stream().map(e -> {
            var g = new LinkedHashMap<String, Object>();
            g.put("groupNo", e.getKey());
            g.put("rows", e.getValue());
            g.put("total", e.getValue().stream()
                    .map(x -> x.get("amount") instanceof BigDecimal b ? b : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            return (Map<String, Object>) g;
        }).toList());
        return R.ok(m);
    }

    /** 1.0.1（1028）：死亡登记卡打印数据集 */
    @GetMapping("/api/print/death-card/{id}")
    public R<Map<String, Object>> deathCard(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select d.*, p.name as patient_name, p.patient_no, p.sex, p.birth_date, a.admission_no
                from mr_death_card d
                join empi_patient p on p.id = d.patient_id
                left join inp_admission a on a.id = d.admission_id
                where d.id = ?
                """, id);
        return rows.isEmpty() ? R.fail(9951, "死亡登记卡不存在") : R.ok(rows.get(0));
    }

    /**
     * 日结报表：收款按收款日、退款按退费日各自归集（1.1.3 B-1）。
     * 原口径把 D2 的退费算进 D1 的报表——D1 历史日结事后变脸，D2 当天的实际出账看不到。
     * 日期一律半开区间：`created_at::date = ?` 会废掉索引（B-4）。
     */
    @GetMapping("/api/reports/daily-settlement")
    public R<Map<String, Object>> dailySettlement(@RequestParam(required = false) String date) {
        // 预校验（1.2.0）：非法日期直接 ?::date 会 500 + ERROR 堆栈污染告警；
        // DateTimeParseException 由全局处理器转 4000
        String d = date == null ? BusinessDates.today().toString() : LocalDate.parse(date).toString();
        List<Map<String, Object>> byMethod = jdbc.queryForList("""
                select pay_method, side, count(*) as cnt, sum(total_amount) as amount
                from (
                    select pay_method, 'COLLECTED' as side, total_amount from outp_charge
                    where created_at >= ?::date and created_at < ?::date + interval '1 day'
                    union all
                    select pay_method, 'REFUNDED', total_amount from outp_charge
                    where status = 'REFUNDED'
                      and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day'
                ) t
                group by pay_method, side order by pay_method, side
                """, d, d, d, d);
        var total = jdbc.queryForList("""
                select (select coalesce(sum(total_amount), 0) from outp_charge
                        where created_at >= ?::date and created_at < ?::date + interval '1 day') as paid,
                       (select coalesce(sum(total_amount), 0) from outp_charge
                        where status = 'REFUNDED'
                          and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day') as refunded,
                       (select count(*) from outp_charge
                        where created_at >= ?::date and created_at < ?::date + interval '1 day') as bills
                """, d, d, d, d, d, d).get(0);
        return R.ok(Map.of("date", d, "byMethod", byMethod, "total", total));
    }

    /**
     * CSV 字段转义：姓名含逗号会串列；以 = + - @ 开头的值在 Excel 里会被当公式执行（CSV 注入），
     * 而姓名是建档端可写入的自由文本。
     */
    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        // 数值不做公式守卫（1.2.3 五轮 P1-3）：退款行金额 -15.00 被加 ' 前缀后在 Excel 里
        // 是文本，SUM 跳过——"金额列求和=当日净额"的口径被守卫自己击穿。数字无公式注入风险
        if (!(v instanceof Number) && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return s.contains(",") || s.contains("\"") || s.contains("\n")
                ? "\"" + s.replace("\"", "\"\"") + "\""
                : s;
    }

    /**
     * 日结报表 CSV 导出（1.2.0 口径可复算）：收款/退款各成一行、退款金额取负——
     * 原先一张"当日新建且当日退费"的单只出一行且无符号，按金额列求和既不等于收款也不等于净额；
     * 历史日期重导时后来才退费的单 status 显示 REFUNDED，快照"事后变脸"。
     * 现在：金额列求和 = 当日净额；按口径列分组求和 = 日结报表两侧合计；重导历史日期结果稳定。
     */
    @GetMapping(value = "/api/reports/daily-settlement.csv", produces = "text/csv;charset=UTF-8")
    public String dailySettlementCsv(@RequestParam(required = false) String date) {
        String d = date == null ? BusinessDates.today().toString() : LocalDate.parse(date).toString();
        var rows = jdbc.queryForList("""
                select t.side, t.charge_no, p.name, t.amount, t.pay_method, t.occurred_at
                from (
                    select '收款' as side, charge_no, registration_id, total_amount as amount,
                           pay_method, created_at as occurred_at, id
                    from outp_charge
                    where created_at >= ?::date and created_at < ?::date + interval '1 day'
                    union all
                    select '退款', charge_no, registration_id, -total_amount,
                           pay_method, refunded_at, id
                    from outp_charge
                    where status = 'REFUNDED'
                      and refunded_at >= ?::date and refunded_at < ?::date + interval '1 day'
                ) t
                join outp_registration r on r.id = t.registration_id
                join empi_patient p on p.id = r.patient_id
                order by t.occurred_at, t.id
                """, d, d, d, d);
        StringBuilder sb = new StringBuilder("﻿口径,结算单号,患者,金额,方式,发生时间\n");
        for (var r : rows) {
            sb.append("%s,%s,%s,%s,%s,%s\n".formatted(csv(r.get("side")), csv(r.get("charge_no")),
                    csv(r.get("name")), csv(r.get("amount")), csv(r.get("pay_method")),
                    csv(r.get("occurred_at"))));
        }
        return sb.toString();
    }
}
