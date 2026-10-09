package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 机构参数：公开配置供登录页/单据读取，修改仅管理员 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final JdbcTemplate jdbc;
    private final cn.hip.platform.core.config.ModuleGate moduleGate;
    private final cn.hip.platform.core.service.ConfigReader configReader;

    /** 公开键白名单：仅机构展示信息；医保比例(yb_*)、模块开关等业务参数不得经此外泄 */
    private static final List<String> PUBLIC_KEYS =
            List.of("hospital_name", "hospital_short", "receipt_title", "contact_phone");

    /** 公开配置（未登录可读：院名/电话等非敏感信息） */
    @GetMapping("/public")
    public R<Map<String, String>> publicConfig() {
        var m = new LinkedHashMap<String, String>();
        String placeholders = String.join(",", Collections.nCopies(PUBLIC_KEYS.size(), "?"));
        jdbc.queryForList("select cfg_key, cfg_value from sys_config where cfg_key in (" + placeholders + ")",
                PUBLIC_KEYS.toArray()).forEach(row ->
                m.put((String) row.get("cfg_key"), (String) row.get("cfg_value")));
        return R.ok(m);
    }

    /**
     * 按键类型校验（1.1.3 B-6）：改配置是管理员日常操作，此前零校验——
     * `yb_ratio_staff` 填 1.5 医院倒贴；填 "abc" 则全院医保结算 500。保存即拦截并说明取值域。
     */
    private static String validate(String key, String value) {
        if (value == null || value.length() > 512) {
            return "配置值不能为空且长度不超过 512";
        }
        if (key.startsWith("yb_ratio_") || key.startsWith("yb_audit_self_ratio")) {
            return numericIn(value, java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE)
                    ? null : "比例须为 0–1 之间的数字（如 0.7）";
        }
        if (key.endsWith("_enabled") || (key.startsWith("module.") && key.endsWith(".enabled"))
                || key.equals("empi_idcard_checksum")) {
            return "0".equals(value) || "1".equals(value) ? null : "开关值只能是 0 或 1";
        }
        if (key.equals("lis_allow_substitute")) {
            // 历史种子与 E2E 用 true/false，全库其余开关用 0/1——两种都认，读取端同双语义
            return Set.of("0", "1", "true", "false").contains(value) ? null : "开关值须为 0/1 或 true/false";
        }
        // 1.1.6 B-1：原规则校验的 yb_annual_limit 是不存在的幽灵键，真实封顶线键是 yb_cap_*——
        // 校验规则必须对着真实键集写（InsuranceSplitService.capKey/deductibleKey）
        if (key.equals("drg_rate") || key.startsWith("yb_deductible") || key.startsWith("yb_cap_")
                || key.startsWith("yb_audit_qty") || key.equals("review_pending_limit")) {
            return numericIn(value, java.math.BigDecimal.ZERO, new java.math.BigDecimal("100000000"))
                    ? null : "须为非负数字";
        }
        // v79 复核（乙组审计者 D6）：三态 gate 键此前不校验，空串与 "xyz" 都能写库、读出时静默回落 warn——
        // 管理员以为改成了"拒绝"，实际仍是"确认"。emr.copy.* / emr.gate.* 只认 off / warn / block。
        // v79 审阅修补（三）（乙组反驳 B3-5b / 甲组 A3-2e）：此前先 strip 再转小写后比对、却把原值写库——
        // 而 12 个 gate 键里一半的读取端是直比型（"block".equals(cfg)，ConfigReader 不 trim），
        // 「BLOCK」「 block」过了校验、读出来全是 warn；全角空格「　block」连归一型读取端（trim 不去 U+3000）也读成 warn。
        // 现改为**原值精确匹配小写三档**，不 strip、不转小写：凡是能写进库的，所有读取端都读得出同一档。
        // emr.version.gate（正文留痕 trim+小写、诊断留痕 equalsIgnoreCase 不 trim，两块读取端不同口）一并纳入。
        // 历史上没有任何三态键接受过三档以外的值（种子、迁移、E2E、文档全是小写），收紧不挡合法配置。
        if (key.startsWith("emr.copy.") || key.startsWith("emr.gate.") || key.equals("emr.version.gate")) {
            return Set.of("off", "warn", "block").contains(value)
                    ? null : "取值只能是小写 off（放行）/ warn（提示）/ block（拦截），前后不能带空格";
        }
        if (key.startsWith("billno_prefix_")) {
            return value.matches("[A-Za-z0-9]{1,8}") ? null : "单号前缀须为 1–8 位字母数字";
        }
        return null;
    }

    private static boolean numericIn(String v, java.math.BigDecimal min, java.math.BigDecimal max) {
        try {
            var d = new java.math.BigDecimal(v);
            return d.compareTo(min) >= 0 && d.compareTo(max) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> update(@PathVariable String key, @RequestParam String value) {
        String err = validate(key, value);
        if (err != null) {
            return R.fail(1402, err);
        }
        int n = jdbc.update("update sys_config set cfg_value = ?, updated_at = now() where cfg_key = ?", value, key);
        if (n > 0) {
            configReader.evict(key);   // 配置须立即生效，不能等 30 秒缓存 TTL
            if (key.startsWith("module.")) {
                moduleGate.evictCache();
            }
        }
        return n == 0 ? R.fail(1401, "配置项不存在") : R.ok();
    }
}
