package cn.hip.insurance.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import cn.hip.platform.core.config.BusinessDates;

/**
 * 医保费用分割与审核（门诊/住院共用，自 outpatient 泛化下沉）。
 * 分类：甲类全额纳入、乙类扣先行自付、丙类自费，未对照项目按丙类（保守口径）。
 * 待遇模型：年度起付线/封顶线（sys_config，0=不启用）；不启用时统筹按逐行舍入求和，
 * 与二十七期算法逐分一致；启用后统筹按账单级「(可报销基数-起付线扣除)×比例，封顶截断」。
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class InsuranceSplitService {

    private final JdbcTemplate jdbc;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    /** 订单行的医保视角：类型（DRUG 走药品目录，其余走诊疗目录）、编码、金额、数量 */
    public record YbLine(String orderType, String itemCode, String itemName, BigDecimal amount, Integer qty) {}

    @Transactional
    public void splitAndAudit(String bizType, String billNo, Long patientId,
                              BigDecimal totalAmount, List<YbLine> lines) {
        String insuranceType = jdbc.queryForObject(
                "select coalesce(insurance_type, 'SELF') from empi_patient where id = ?",
                String.class, patientId);
        BigDecimal ratio = fundRatio(insuranceType, "INP".equals(bizType));

        BigDecimal a = BigDecimal.ZERO, b = BigDecimal.ZERO, c = BigDecimal.ZERO;
        BigDecimal eligibleTotal = BigDecimal.ZERO, lineFundSum = BigDecimal.ZERO;
        StringJoiner detail = new StringJoiner(",", "[", "]");
        for (YbLine o : lines) {
            String itemType = "DRUG".equals(o.orderType()) ? "DRUG" : "ITEM";
            var maps = jdbc.queryForList(
                    "select charge_class, self_ratio from yb_catalog_map where item_type = ? and item_code = ?",
                    itemType, o.itemCode());
            String clazz = maps.isEmpty() ? "C" : (String) maps.get(0).get("charge_class");
            BigDecimal selfRatio = maps.isEmpty() ? BigDecimal.ZERO : (BigDecimal) maps.get(0).get("self_ratio");
            BigDecimal amount = o.amount();
            BigDecimal eligible = switch (clazz) {
                case "A" -> amount;
                case "B" -> amount.multiply(BigDecimal.ONE.subtract(selfRatio));
                default -> BigDecimal.ZERO;
            };
            switch (clazz) {
                case "A" -> a = a.add(amount);
                case "B" -> b = b.add(amount);
                default -> c = c.add(amount);
            }
            eligibleTotal = eligibleTotal.add(eligible);
            lineFundSum = lineFundSum.add(eligible.multiply(ratio).setScale(2, RoundingMode.HALF_UP));
            detail.add("{\"item\":\"%s\",\"class\":\"%s\",\"amount\":%s,\"eligible\":%s}"
                    .formatted(o.itemName(), clazz, amount, eligible.setScale(2, RoundingMode.HALF_UP)));
        }

        // 年度待遇：起付线/封顶线（行级锁保证同患者并发结算下累计正确）
        int year = BusinessDates.today().getYear();
        jdbc.update("insert into yb_patient_annual(patient_id, year) values (?,?) on conflict (patient_id, year) do nothing",
                patientId, year);
        Map<String, Object> annual = jdbc.queryForMap(
                "select deductible_used, fund_used from yb_patient_annual where patient_id = ? and year = ? for update",
                patientId, year);
        BigDecimal deductible = cfg(deductibleKey(insuranceType));
        BigDecimal cap = cfg(capKey(insuranceType));

        BigDecimal dedTake = BigDecimal.ZERO;
        BigDecimal fund;
        if (deductible.signum() == 0 && cap.signum() == 0) {
            fund = lineFundSum; // 待遇模型未启用：与既有逐行舍入算法逐分一致
        } else {
            BigDecimal dedRemain = deductible.subtract((BigDecimal) annual.get("deductible_used")).max(BigDecimal.ZERO);
            dedTake = eligibleTotal.min(dedRemain).setScale(2, RoundingMode.HALF_UP);
            fund = eligibleTotal.subtract(dedTake).max(BigDecimal.ZERO)
                    .multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            if (cap.signum() > 0) {
                BigDecimal capRemain = cap.subtract((BigDecimal) annual.get("fund_used")).max(BigDecimal.ZERO);
                fund = fund.min(capRemain);
            }
        }
        BigDecimal self = totalAmount.subtract(fund);
        // 先插分割留痕，**仅当本次确实新插入**才累加年度额度：
        // 分割行是 on conflict do nothing（预期会被重复调用），而年度累加无条件执行的话，
        // 重试/补跑会重复吃掉起付线与统筹额度，而 reverse 只退一次 → 患者年度额度永久错账。
        int inserted = jdbc.update("""
                insert into yb_settle_split(charge_no, biz_type, insurance_type, patient_id, total,
                                            class_a, class_b, class_c, fund_pay, self_pay, deductible_pay, detail)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (charge_no) do nothing
                """, billNo, bizType, insuranceType, patientId, totalAmount, a, b, c, fund, self,
                dedTake, detail.toString());
        if (inserted > 0) {
            jdbc.update("""
                    update yb_patient_annual set deductible_used = deductible_used + ?,
                           fund_used = fund_used + ?, updated_at = now()
                    where patient_id = ? and year = ?
                    """, dedTake, fund, patientId, year);
        }

        audit(billNo, lines, c, totalAmount);
    }

    /** 退费冲销：回退该结算单占用的年度起付线/统筹额度（分割留痕保留，汇总口径按单据状态排除） */
    @Transactional
    public void reverse(String billNo) {
        // 抢占 reversed 标记：并发退费（重复点击/重试）会各冲销一次，患者凭空多出年度统筹额度
        var rows = jdbc.queryForList("""
                update yb_settle_split set reversed = true
                where charge_no = ? and not reversed
                returning patient_id, fund_pay, deductible_pay, extract(year from created_at)::int as y
                """, billNo);
        if (rows.isEmpty() || rows.get(0).get("patient_id") == null) return;
        var r = rows.get(0);
        jdbc.update("""
                update yb_patient_annual
                set fund_used = greatest(fund_used - ?, 0),
                    deductible_used = greatest(deductible_used - ?, 0), updated_at = now()
                where patient_id = ? and year = ?
                """, r.get("fund_pay"), r.get("deductible_pay"), r.get("patient_id"), r.get("y"));
    }

    private BigDecimal fundRatio(String insuranceType, boolean inpatient) {
        String key = switch (insuranceType) {
            case "YB_STAFF", "YB_EMPLOYEE" -> inpatient ? "yb_ratio_staff_inp" : "yb_ratio_staff";
            case "YB_RESIDENT" -> inpatient ? "yb_ratio_resident_inp" : "yb_ratio_resident";
            default -> null;
        };
        return key == null ? BigDecimal.ZERO : cfg(key);
    }

    private String deductibleKey(String t) {
        return switch (t) {
            case "YB_STAFF", "YB_EMPLOYEE" -> "yb_deductible_staff";
            case "YB_RESIDENT" -> "yb_deductible_resident";
            default -> null;
        };
    }

    private String capKey(String t) {
        return switch (t) {
            case "YB_STAFF", "YB_EMPLOYEE" -> "yb_cap_staff";
            case "YB_RESIDENT" -> "yb_cap_resident";
            default -> null;
        };
    }

    /**
     * 配置读取走 ConfigReader（1.1.6 B-1）：每笔 YB 结算读 3–5 键，直查等于放弃 1.1.4 的缓存；
     * 坏值（历史遗留/绕过校验直改库）安全解析——比例键回落 0 会让患者被静默全额自费，
     * 故坏值一律 error 级告警，让问题在日志里可见而不是在患者账单里。
     */
    private BigDecimal cfg(String key) {
        if (key == null) return BigDecimal.ZERO;
        String v = configReader.get(key, null);
        if (v == null) return BigDecimal.ZERO;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            log.error("sys_config 键 {} 的值 [{}] 不是数字，按 0 处理——请立即修正（医保分割正在受影响）", key, v);
            return BigDecimal.ZERO;
        }
    }

    /** 审核规则雏形（阈值走 sys_config；真实智能审核规则库接入后替换数据源）：
     *  R001 单品种数量超限；R002 同单重复项目；R003 自费(丙类)占比过高 */
    private void audit(String billNo, List<YbLine> lines, BigDecimal classC, BigDecimal total) {
        int qtyLimit = cfg("yb_audit_qty_limit").max(BigDecimal.ONE).intValue();
        BigDecimal selfLimit = cfg("yb_audit_self_ratio_limit");
        if (selfLimit.signum() == 0) selfLimit = new BigDecimal("0.5");
        for (YbLine o : lines) {
            if ("DRUG".equals(o.orderType()) && o.qty() != null && o.qty() > qtyLimit) {
                warn(billNo, "R001", "%s 数量 %d 超过常规上限 %d，请核实是否超量开药"
                        .formatted(o.itemName(), o.qty(), qtyLimit));
            }
        }
        Map<String, Long> nameCount = lines.stream()
                .collect(Collectors.groupingBy(YbLine::itemName, Collectors.counting()));
        nameCount.forEach((name, cnt) -> {
            if (cnt > 1) warn(billNo, "R002", "项目「%s」同单出现 %d 次，请核实重复收费".formatted(name, cnt));
        });
        if (total.compareTo(BigDecimal.ZERO) > 0
                && classC.divide(total, 4, RoundingMode.HALF_UP).compareTo(selfLimit) > 0) {
            warn(billNo, "R003", "自费(丙类)金额占比超 %s%%（¥%s / ¥%s），请与参保人确认"
                    .formatted(selfLimit.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString(),
                            classC, total));
        }
    }

    private void warn(String billNo, String rule, String message) {
        jdbc.update("insert into yb_audit_log(charge_no, rule_code, level, message) values (?,?,'WARN',?)",
                billNo, rule, message);
    }
}
