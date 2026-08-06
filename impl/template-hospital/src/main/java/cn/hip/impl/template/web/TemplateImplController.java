package cn.hip.impl.template.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 实施定制示例控制器（ADR-0003）。
 * 规约要点：/api/impl/<hospital>/** 前缀；错误码 20000+ 段；只写 impl_ 前缀自有表。
 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/impl/template")
public class TemplateImplController {

    private final JdbcTemplate jdbc;

    @GetMapping("/ping")
    public R<Map<String, Object>> ping() {
        return R.ok(Map.of("module", "impl-template", "ok", true));
    }

    public record NoteReq(String content) {}

    @PostMapping("/notes")
    public R<Void> addNote(@RequestBody NoteReq req) {
        if (req.content() == null || req.content().isBlank()) {
            return R.fail(20001, "内容必填");
        }
        jdbc.update("insert into impl_template_note(content) values (?)", req.content());
        return R.ok();
    }

    @GetMapping("/notes")
    public R<List<Map<String, Object>>> notes() {
        return R.ok(jdbc.queryForList("select * from impl_template_note order by id desc limit 50"));
    }
}
