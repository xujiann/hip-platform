package cn.hip.platform.integration.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.integration.service.IntegrationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 三十四期：集成适配器与内容路由雏形——文件/DB 两类适配器注册 + 关键字路由（重型 ESB 属配套产品） */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/integration/adapters")
public class AdapterController {

    private final JdbcTemplate jdbc;
    private final IntegrationLogService logService;

    public record AdapterReq(String name, String type, String config) {}

    @PostMapping
    public R<Void> create(@RequestBody AdapterReq req) {
        if (!"FILE".equals(req.type()) && !"DB".equals(req.type())) return R.fail(4710, "类型只能为 FILE/DB");
        jdbc.update("""
                insert into int_adapter(name, type, config) values (?,?,?)
                on conflict (name) do update set type = excluded.type, config = excluded.config
                """, req.name(), req.type(), req.config());
        return R.ok();
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(jdbc.queryForList("""
                select a.*, (select count(*) from int_route_rule r where r.adapter_id = a.id) as rules
                from int_adapter a order by a.id
                """));
    }

    @PutMapping("/{id}/toggle")
    public R<Void> toggle(@PathVariable Long id) {
        int n = jdbc.update("update int_adapter set enabled = not enabled where id = ?", id);
        return n == 0 ? R.fail(4711, "适配器不存在") : R.ok();
    }

    // ---- 路由规则 ----
    public record RuleReq(String keyword, Long adapterId, String remark) {}

    @PostMapping("/rules")
    public R<Void> addRule(@RequestBody RuleReq req) {
        Integer a = jdbc.queryForObject("select count(*) from int_adapter where id = ?", Integer.class, req.adapterId());
        if (a == null || a == 0) return R.fail(4711, "适配器不存在");
        jdbc.update("insert into int_route_rule(keyword, adapter_id, remark) values (?,?,?)",
                req.keyword(), req.adapterId(), req.remark());
        return R.ok();
    }

    @GetMapping("/rules")
    public R<List<Map<String, Object>>> rules() {
        return R.ok(jdbc.queryForList("""
                select r.*, a.name as adapter_name, a.type as adapter_type
                from int_route_rule r join int_adapter a on a.id = r.adapter_id order by r.id
                """));
    }

    /** 路由测试：报文按关键字命中适配器并模拟投递（留痕 channel=ADAPTER） */
    @PostMapping("/route-test")
    public R<Map<String, Object>> routeTest(@RequestParam String content) {
        var rules = jdbc.queryForList("""
                select r.keyword, a.id, a.name, a.type, a.config, a.enabled
                from int_route_rule r join int_adapter a on a.id = r.adapter_id order by r.id
                """);
        var m = new LinkedHashMap<String, Object>();
        for (var r : rules) {
            if (content.contains((String) r.get("keyword"))) {
                if (!Boolean.TRUE.equals(r.get("enabled"))) {
                    return R.fail(4712, "命中适配器已停用：" + r.get("name"));
                }
                logService.log("OUT", "ADAPTER", (String) r.get("name"),
                        "{\"route\":\"%s\",\"type\":\"%s\",\"target\":\"%s\",\"payloadLen\":%d}"
                                .formatted(r.get("keyword"), r.get("type"), r.get("config"), content.length()),
                        true, null);
                m.put("matched", true);
                m.put("adapter", r.get("name"));
                m.put("type", r.get("type"));
                m.put("target", r.get("config"));
                return R.ok(m);
            }
        }
        m.put("matched", false);
        return R.ok(m);
    }
}
