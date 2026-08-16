package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十五期：护理管理——排班（同人同日同班次唯一）与质控评分（科室横向对比） */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/nursing")
@PreAuthorize("hasAnyRole('ADMIN','NURSE','QUALITY')")   // 1.0.9：权限清点补齐
public class NursingMgmtController {

    private final JdbcTemplate jdbc;

    private static final Set<String> SHIFTS = Set.of("DAY", "MID", "NIGHT");

    public record ShiftReq(Long deptId, String nurseName, String shiftDate, String shiftType) {}

    @PostMapping("/shifts")
    public R<Void> addShift(@RequestBody ShiftReq req) {
        if (!SHIFTS.contains(req.shiftType())) return R.fail(4580, "班次只能为 DAY/MID/NIGHT");
        try {
            jdbc.update("insert into nur_shift(dept_id, nurse_name, shift_date, shift_type) values (?,?,?::date,?)",
                    req.deptId(), req.nurseName(), req.shiftDate(), req.shiftType());
        } catch (DuplicateKeyException e) {
            return R.fail(4581, "排班冲突：" + req.nurseName() + " 当日该班次已排");
        }
        return R.ok();
    }

    @GetMapping("/shifts")
    public R<List<Map<String, Object>>> shifts(@RequestParam String date) {
        return R.ok(jdbc.queryForList("""
                select s.*, d.name as dept_name from nur_shift s
                join sys_dept d on d.id = s.dept_id
                where s.shift_date = ?::date order by s.dept_id, s.shift_type
                """, date));
    }

    public record ScoreReq(Long deptId, String item, Double score, String note) {}

    @PostMapping("/qc-scores")
    public R<Void> addScore(@RequestBody ScoreReq req, Authentication auth) {
        if (req.score() == null || req.score() < 0 || req.score() > 100) return R.fail(4582, "评分范围 0-100");
        jdbc.update("insert into nur_qc_score(dept_id, item, score, checker, note) values (?,?,?,?,?)",
                req.deptId(), req.item(), req.score(), auth.getName(), req.note());
        return R.ok();
    }

    @GetMapping("/qc-scores")
    public R<List<Map<String, Object>>> scores() {
        return R.ok(jdbc.queryForList("""
                select s.*, d.name as dept_name from nur_qc_score s
                join sys_dept d on d.id = s.dept_id order by s.id desc limit 200
                """));
    }

    /** 科室质控平均分横向对比 */
    @GetMapping("/qc-scores/summary")
    public R<List<Map<String, Object>>> summary() {
        return R.ok(jdbc.queryForList("""
                select d.name as dept_name, round(avg(s.score), 1) as avg_score, count(*) as checks
                from nur_qc_score s join sys_dept d on d.id = s.dept_id
                group by d.name order by avg_score desc
                """));
    }
}
