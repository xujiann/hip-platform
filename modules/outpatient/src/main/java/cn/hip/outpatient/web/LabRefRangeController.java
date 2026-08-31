package cn.hip.outpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.service.ConfigReader;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** v33：检验参考区间/危急值阈值主数据维护（自动判危的数据基础，见 ReferenceRangeService）。 */
@RestController
@RequestMapping("/api/lab-ref-ranges")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN')")
@RequiredArgsConstructor
public class LabRefRangeController {

    private final JdbcTemplate jdbc;
    private final ConfigReader configReader;

    public record RefRangeReq(String itemCode, String itemName, String sex,
                              Integer ageLowDays, Integer ageHighDays,
                              BigDecimal refLow, BigDecimal refHigh,
                              BigDecimal critLow, BigDecimal critHigh, String unit, Boolean enabled) {}

    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String itemCode) {
        String base = "select * from lab_ref_range";
        return R.ok(itemCode == null || itemCode.isBlank()
                ? jdbc.queryForList(base + " order by item_code, sex nulls first, age_low_days nulls first")
                : jdbc.queryForList(base + " where item_code = ? order by sex nulls first, age_low_days nulls first", itemCode));
    }

    /** 当前生效的危急值确认时限（分钟），供维护页展示口径 */
    @GetMapping("/ack-deadline-minutes")
    public R<Integer> ackDeadlineMinutes() {
        return R.ok(configReader.getInt("critical_ack_deadline_minutes", 10));
    }

    @PostMapping
    public R<Void> create(@RequestBody RefRangeReq req) {
        if (req.itemCode() == null || req.itemCode().isBlank()) return R.fail(7104, "项目代码必填");
        jdbc.update("""
                insert into lab_ref_range(item_code, item_name, sex, age_low_days, age_high_days,
                        ref_low, ref_high, crit_low, crit_high, unit, enabled)
                values (?,?,?,?,?,?,?,?,?,?, coalesce(?, true))
                """, req.itemCode().trim(), req.itemName(), normSex(req.sex()),
                req.ageLowDays(), req.ageHighDays(), req.refLow(), req.refHigh(),
                req.critLow(), req.critHigh(), req.unit(), req.enabled());
        return R.ok();
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody RefRangeReq req) {
        int n = jdbc.update("""
                update lab_ref_range set item_name = ?, sex = ?, age_low_days = ?, age_high_days = ?,
                        ref_low = ?, ref_high = ?, crit_low = ?, crit_high = ?, unit = ?,
                        enabled = coalesce(?, enabled)
                where id = ?
                """, req.itemName(), normSex(req.sex()), req.ageLowDays(), req.ageHighDays(),
                req.refLow(), req.refHigh(), req.critLow(), req.critHigh(), req.unit(), req.enabled(), id);
        return n == 0 ? R.fail(7105, "参考区间不存在") : R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        return jdbc.update("delete from lab_ref_range where id = ?", id) == 0
                ? R.fail(7105, "参考区间不存在") : R.ok();
    }

    /** 性别归一：空串存 null（通用），M/F 大写 */
    private static String normSex(String sex) {
        if (sex == null || sex.isBlank()) return null;
        return sex.trim().toUpperCase();
    }
}
