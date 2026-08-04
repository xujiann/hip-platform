package cn.hip.outpatient.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十五期：抗菌药物分级处方权管控——授权矩阵维护（开单拦截见 DoctorStationService 4014） */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/outpatient/abx-privileges")
@PreAuthorize("hasRole('ADMIN')")
public class AbxPrivilegeController {

    private final JdbcTemplate jdbc;

    /** 用户处方权列表（缺省 1 级） */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(jdbc.queryForList("""
                select u.id as user_id, u.username, u.real_name, coalesce(p.level, 1) as level, p.granted_at
                from sys_user u
                left join med_abx_privilege p on p.user_id = u.id
                where u.enabled order by u.id
                """));
    }

    /** 授予/调整处方权级别 */
    @PutMapping("/{userId}")
    public R<Void> grant(@PathVariable Long userId, @RequestParam int level) {
        if (level < 1 || level > 3) return R.fail(4620, "级别范围 1-3");
        jdbc.update("""
                insert into med_abx_privilege(user_id, level) values (?,?)
                on conflict (user_id) do update set level = excluded.level, granted_at = now()
                """, userId, level);
        return R.ok();
    }

    /** 分级药品目录 */
    @GetMapping("/drugs")
    public R<List<Map<String, Object>>> drugs() {
        return R.ok(jdbc.queryForList(
                "select id, code, name, abx_level from md_drug where abx_level >= 1 order by abx_level desc, code"));
    }
}
