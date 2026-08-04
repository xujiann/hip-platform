package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十五期：消毒供应追溯——包-灭菌-发放-使用条码链，全程留痕可回溯 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/cssd")
public class CssdController {

    private final JdbcTemplate jdbc;

    public record PackReq(String name) {}

    /** 打包（生成包条码 CS-） */
    @PostMapping("/packages")
    @Transactional
    public R<Map<String, Object>> pack(@RequestBody PackReq req, Authentication auth) {
        String pkgNo = "CS" + System.currentTimeMillis() % 1000000000L;
        jdbc.update("insert into cssd_package(pkg_no, name) values (?,?)", pkgNo, req.name());
        trace(pkgNo, "PACKED", auth);
        return R.ok(Map.of("pkgNo", pkgNo));
    }

    /** 灭菌（登记灭菌批次） */
    @PutMapping("/packages/{pkgNo}/sterilize")
    @Transactional
    public R<Void> sterilize(@PathVariable String pkgNo, @RequestParam String batch, Authentication auth) {
        int n = jdbc.update("""
                update cssd_package set status = 'STERILIZED', sterile_batch = ?, sterilized_at = now()
                where pkg_no = ? and status = 'PACKED'
                """, batch, pkgNo);
        if (n == 0) return R.fail(4600, "包不存在或状态不符（须为已打包）");
        trace(pkgNo, "STERILIZED", auth);
        return R.ok();
    }

    /** 发放到科室 */
    @PutMapping("/packages/{pkgNo}/issue")
    @Transactional
    public R<Void> issue(@PathVariable String pkgNo, @RequestParam Long deptId, Authentication auth) {
        int n = jdbc.update("""
                update cssd_package set status = 'ISSUED', dest_dept_id = ?, issued_at = now()
                where pkg_no = ? and status = 'STERILIZED'
                """, deptId, pkgNo);
        if (n == 0) return R.fail(4601, "须先灭菌后方可发放");
        trace(pkgNo, "ISSUED", auth);
        return R.ok();
    }

    /** 使用登记 */
    @PutMapping("/packages/{pkgNo}/use")
    @Transactional
    public R<Void> use(@PathVariable String pkgNo, Authentication auth) {
        int n = jdbc.update(
                "update cssd_package set status = 'USED', used_at = now() where pkg_no = ? and status = 'ISSUED'",
                pkgNo);
        if (n == 0) return R.fail(4602, "须先发放后方可使用");
        trace(pkgNo, "USED", auth);
        return R.ok();
    }

    @GetMapping("/packages")
    public R<List<Map<String, Object>>> packages() {
        return R.ok(jdbc.queryForList("""
                select p.*, d.name as dest_dept_name from cssd_package p
                left join sys_dept d on d.id = p.dest_dept_id
                order by p.id desc limit 100
                """));
    }

    /** 追溯链 */
    @GetMapping("/packages/{pkgNo}/trace")
    public R<List<Map<String, Object>>> traceChain(@PathVariable String pkgNo) {
        return R.ok(jdbc.queryForList("""
                select t.action, t.operator, t.at from cssd_trace_log t
                join cssd_package p on p.id = t.pkg_id
                where p.pkg_no = ? order by t.id
                """, pkgNo));
    }

    private void trace(String pkgNo, String action, Authentication auth) {
        jdbc.update("""
                insert into cssd_trace_log(pkg_id, action, operator)
                select id, ?, ? from cssd_package where pkg_no = ?
                """, action, auth == null ? null : auth.getName(), pkgNo);
    }
}
