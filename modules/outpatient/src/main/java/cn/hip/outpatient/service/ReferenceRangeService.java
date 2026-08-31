package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * v33：检验结果按参考区间/危急值阈值自动判读（lab_ref_range 主数据）。
 *
 * <p>此前危急值判定硬编码 {@code Set.of("HH","LL")}，只信上游 HL7 或录入员主观填的 flag。
 * 本服务在结果落库时，对**数值型且 flag 缺失**的项，按项目+性别+年龄匹配区间自动判
 * N/H/L/HH/LL；上游已给 flag 的尊重上游（不覆盖），无匹配区间或非数值的返回 null（维持原样）。
 * 只读主数据，无副作用；判定口径集中一处，避免各调用点重复。
 */
@Service
@RequiredArgsConstructor
public class ReferenceRangeService {

    private final JdbcTemplate jdbc;

    /**
     * 按项目+性别+年龄判读一个结果值，返回 N/H/L/HH/LL；无法判定（非数值/无匹配区间）返回 null。
     *
     * @param itemCode 项目代码（与 lab_ref_range.item_code 对应）
     * @param sex      患者性别 M/F（可空）
     * @param ageDays  患者年龄（天，可空）
     * @param value    结果值文本
     */
    public String evaluate(String itemCode, String sex, Integer ageDays, String value) {
        if (itemCode == null || value == null) return null;
        BigDecimal v;
        try {
            v = new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return null;   // 非数值（如"阳性""未见"）不按区间判，留原 flag
        }
        var rows = jdbc.queryForList(
                "select sex, age_low_days, age_high_days, ref_low, ref_high, crit_low, crit_high "
                        + "from lab_ref_range where item_code = ? and enabled", itemCode);
        Map<String, Object> best = null;
        boolean bestSexSpecific = false;
        for (var r : rows) {
            String rSex = (String) r.get("sex");
            if (rSex != null && !rSex.equalsIgnoreCase(sex)) continue;               // 性别不符
            Integer lo = intOf(r.get("age_low_days")), hi = intOf(r.get("age_high_days"));
            if (ageDays != null) {
                if (lo != null && ageDays < lo) continue;                            // 年龄下界（含）
                if (hi != null && ageDays >= hi) continue;                           // 年龄上界（不含）
            } else if (lo != null || hi != null) {
                continue;   // 区间限定了年龄但患者年龄未知——不套用，避免误判
            }
            boolean sexSpecific = rSex != null;
            // 更具体（性别相关）的区间优先命中
            if (best == null || (sexSpecific && !bestSexSpecific)) {
                best = r;
                bestSexSpecific = sexSpecific;
            }
        }
        if (best == null) return null;
        BigDecimal critLow = decOf(best.get("crit_low")), critHigh = decOf(best.get("crit_high"));
        BigDecimal refLow = decOf(best.get("ref_low")), refHigh = decOf(best.get("ref_high"));
        if (critHigh != null && v.compareTo(critHigh) > 0) return "HH";
        if (critLow != null && v.compareTo(critLow) < 0) return "LL";
        if (refHigh != null && v.compareTo(refHigh) > 0) return "H";
        if (refLow != null && v.compareTo(refLow) < 0) return "L";
        return "N";
    }

    private static Integer intOf(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static BigDecimal decOf(Object o) {
        return o == null ? null : (o instanceof BigDecimal b ? b : new BigDecimal(o.toString()));
    }
}
