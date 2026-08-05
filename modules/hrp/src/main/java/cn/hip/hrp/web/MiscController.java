package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 三十六期：服务窗口维护、科研台账（项目/知识产权转化+审核）、OA 我发起的 */
@RestController
@RequiredArgsConstructor
public class MiscController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    // ---- 服务窗口 ----
    public record WindowReq(String winNo, String name, String winType, String status) {}

    @PostMapping("/api/windows")
    public R<Void> saveWindow(@RequestBody WindowReq req) {
        if (!Set.of("SAMPLE", "PHARMACY", "CHARGE").contains(req.winType())) {
            return R.fail(4760, "窗口类型只能为 SAMPLE/PHARMACY/CHARGE");
        }
        // on conflict upsert：Postgres 事务内 insert 失败会中止事务，不能用 try/catch 重复键再 update
        jdbc.update("""
                insert into svc_window(win_no, name, win_type, status) values (?,?,?,?)
                on conflict (win_no) do update set name = excluded.name,
                    win_type = excluded.win_type, status = excluded.status
                """, req.winNo(), req.name(), req.winType(), req.status() == null ? "OPEN" : req.status());
        return R.ok();
    }

    @PutMapping("/api/windows/{winNo}/toggle")
    public R<Void> toggleWindow(@PathVariable String winNo) {
        int n = jdbc.update("""
                update svc_window set status = case status when 'OPEN' then 'CLOSED' else 'OPEN' end where win_no = ?
                """, winNo);
        return n == 0 ? R.fail(4761, "窗口不存在") : R.ok();
    }

    @GetMapping("/api/windows")
    public R<List<Map<String, Object>>> windows() {
        return R.ok(jdbc.queryForList("select * from svc_window order by win_type, win_no"));
    }

    // ---- 科研台账 ----
    public record SrReq(String itemType, String title, String leader, String content, BigDecimal amount) {}

    @PostMapping("/api/research")
    public R<Void> addResearch(@RequestBody SrReq req) {
        if (!"PROJECT".equals(req.itemType()) && !"IP".equals(req.itemType())) {
            return R.fail(4762, "类型只能为 PROJECT/IP");
        }
        jdbc.update("insert into sr_item(item_type, title, leader, content, amount) values (?,?,?,?,?)",
                req.itemType(), req.title(), req.leader(), req.content(), req.amount());
        return R.ok();
    }

    /** 编辑（仅草稿） */
    @PutMapping("/api/research/{id}")
    public R<Void> editResearch(@PathVariable Long id, @RequestBody SrReq req) {
        int n = jdbc.update("""
                update sr_item set title = ?, leader = ?, content = ?, amount = ? where id = ? and status = 'DRAFT'
                """, req.title(), req.leader(), req.content(), req.amount(), id);
        return n == 0 ? R.fail(4763, "仅草稿可编辑") : R.ok();
    }

    @PutMapping("/api/research/{id}/review")
    public R<Void> reviewResearch(@PathVariable Long id, @RequestParam boolean approve,
                                  @RequestParam(required = false) String note) {
        int n = jdbc.update("""
                update sr_item set status = ?, review_note = ? where id = ? and status = 'DRAFT'
                """, approve ? "APPROVED" : "REJECTED", note, id);
        return n == 0 ? R.fail(4764, "台账不存在或已审核") : R.ok();
    }

    @DeleteMapping("/api/research/{id}")
    public R<Void> deleteResearch(@PathVariable Long id) {
        int n = jdbc.update("delete from sr_item where id = ? and status = 'DRAFT'", id);
        return n == 0 ? R.fail(4763, "仅草稿可删除") : R.ok();
    }

    @GetMapping("/api/research")
    public R<List<Map<String, Object>>> research() {
        return R.ok(jdbc.queryForList("select * from sr_item order by id desc limit 100"));
    }

    // ---- OA 我发起的 ----
    @GetMapping("/api/oa/my-requests")
    public R<List<Map<String, Object>>> myRequests(Authentication auth) {
        return R.ok(jdbc.queryForList("""
                select * from oa_request where applicant_id = ? order by id desc limit 100
                """, currentUserService.idOf(auth)));
    }
}
