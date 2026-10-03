package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * v76 车道 A：诊断字典（md_icd10）独立浏览与在线维护（偏离表 993★ ①）。
 *
 * <p>与 {@link MasterDataController#icd10(String)} 的分工：那是**医生站检索下拉**（top20、只含启用行、
 * 返回 JPA 实体 code/name/pinyin，键名不变）；本控制器是**维护页**的读写入口（分页、可看停用行、
 * 增改启停与批量导入）。ICD 维护端点单独成类，不与别的车道同改 MasterDataController。
 *
 * <p>权限与既有写法一致：读登录即可（与 /icd10、/drugs 等检索端点同），写一律 ADMIN。
 *
 * <p><b>停用语义</b>：只影响检索下拉（{@code /icd10} 带 {@code and enabled}）；
 * <b>不</b>级联改 outp_diagnosis / inp_diagnosis 历史行，也<b>不</b>做"开单诊断码必须在字典内"校验
 * （DoctorStationService 4031 处纪律：中医/自定义诊断允许无码、V43PrintDocsTest 的 J00 不在种子里）。
 *
 * <p><b>拼音必须自带</b>：仓库无拼音库（pom 与全部 java 无 pinyin 依赖），本控制器不推断拼音，
 * 新增/编辑/CSV 均要求调用方给出拼音首字母；没有拼音的行无法按拼音检索，页面有提示。
 *
 * <p><b>编码、拼音一律转大写落库</b>：检索侧 {@code code like 'X%'} 走 text_pattern_ops 索引，
 * 要求落库值与查询值同为大写；拼音检索同理（{@code upper(pinyin)}）。
 */
@RestController
@RequestMapping("/api/masterdata/icd-dict")
@RequiredArgsConstructor
public class IcdDictController {

    private final JdbcTemplate jdbc;

    /** CSV 单批行数上限（全量国临版约 3 万行，留余量） */
    static final int IMPORT_MAX_LINES = 50_000;
    /** 5901 响应里最多回带多少条行级错误（总数在 errorCount） */
    static final int ERRORS_RETURNED_MAX = 200;

    /** ICD-10 编码：字母数字开头，允许 . * + † -（国临版有 M80.001+ / †星号码），≤16（列宽） */
    private static final Pattern CODE_RE = Pattern.compile("^[A-Z0-9][A-Z0-9.*+†\\-]{0,15}$");
    /** 拼音首字母：字母数字，≤32（列宽） */
    private static final Pattern PINYIN_RE = Pattern.compile("^[A-Z0-9]{1,32}$");

    /** 新增/编辑请求体。编辑时 code 以路径为准（编码建档后不可改），body 里的 code 被忽略。 */
    public record IcdReq(String code, String name, String pinyin) {}

    // ==================== 分页检索 ====================

    /**
     * 分页检索。keyword 为空 = 全部；否则 名称包含 / 编码前缀 / 拼音前缀（不分大小写）。
     * includeDisabled 默认 false（与医生站检索口径一致，维护页打开开关才看停用行）。
     * page 从 1 起；size 夹到 1..200。
     *
     * @return {records:[{code,name,pinyin,enabled,updatedAt}], total}
     */
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "false") boolean includeDisabled,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        String kw = keyword == null ? "" : keyword.strip();
        String where = " where (? or enabled)";
        List<Object> args = new ArrayList<>();
        args.add(includeDisabled);
        if (!kw.isEmpty()) {
            where += " and (name like ? escape '\\' or code like ? escape '\\'"
                    + " or upper(pinyin) like ? escape '\\')";
            String esc = escapeLike(kw);
            String escUpper = escapeLike(kw.toUpperCase());
            args.add("%" + esc + "%");
            args.add(escUpper + "%");
            args.add(escUpper + "%");
        }
        long total = jdbc.queryForObject("select count(*) from md_icd10" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) (p - 1) * s);
        List<Map<String, Object>> records = jdbc.queryForList("""
                select code, name, pinyin, enabled,
                       to_char(updated_at at time zone 'Asia/Shanghai', 'YYYY-MM-DD HH24:MI:SS') as "updatedAt"
                from md_icd10""" + where + " order by code limit ? offset ?", pageArgs.toArray());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", records);
        body.put("total", total);
        return R.ok(body);
    }

    // ==================== 新增 / 编辑 / 启停 ====================

    /** 新增。编码已存在（含大小写不同）→ 5900，不覆盖原行；其余字段不合法 → 4000。新增行默认启用。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> create(@RequestBody IcdReq req) {
        String code = normCode(req.code());
        String name = normName(req.name());
        String pinyin = normPinyin(req.pinyin());
        int n = jdbc.update("""
                insert into md_icd10(code, name, pinyin) values (?,?,?)
                on conflict (code) do nothing
                """, code, name, pinyin);
        if (n == 0) {
            throw new HipBizException(5900, "诊断编码「" + code + "」已存在；如需改名称/拼音请编辑，已停用的请在列表中启用");
        }
        return R.ok();
    }

    /** 编辑名称/拼音（编码与启停状态不动）。编码不存在 → 5902。 */
    @PutMapping("/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> update(@PathVariable String code, @RequestBody IcdReq req) {
        String c = code == null ? "" : code.strip().toUpperCase();
        String name = normName(req.name());
        String pinyin = normPinyin(req.pinyin());
        int n = jdbc.update("update md_icd10 set name = ?, pinyin = ?, updated_at = now() where code = ?",
                name, pinyin, c);
        if (n == 0) {
            throw new HipBizException(5902, "诊断编码「" + c + "」不存在");
        }
        return R.ok();
    }

    /** 启停。编码不存在 → 5902。幂等：已是目标状态也返回成功。 */
    @PutMapping("/{code}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> setEnabled(@PathVariable String code, @RequestParam boolean enabled) {
        String c = code == null ? "" : code.strip().toUpperCase();
        int n = jdbc.update("update md_icd10 set enabled = ?, updated_at = now() where code = ?", enabled, c);
        if (n == 0) {
            throw new HipBizException(5902, "诊断编码「" + c + "」不存在");
        }
        return R.ok();
    }

    // ==================== CSV 导入 ====================

    /**
     * CSV 批量导入。列 {@code code,name,pinyin}（三列，UTF-8，首行表头可选，容忍 BOM / CRLF / 空行 / 行首尾空白）。
     *
     * <p><b>先校验后写入、整批原子</b>：任何一行格式不合法（列数不是 3、编码或拼音不合规、名称空/超长、
     * 文件内编码重复）→ 返回 <b>5901</b> + {@code data.errors:[{line,reason}]}（行号为文件行号，含表头），
     * <b>整批一行都不落库</b>。校验阶段没有任何写操作，所以零落库不依赖事务回滚。
     *
     * <p><b>与既有药品/收费项目导入口径的差异（有意）</b>：那两个端点对短行是"跳过该行、其余照导、返回成功+errors"，
     * 软错误（未知费用类别等）也只记不拦。诊断字典是**其余模块的检索与病案编码的基准字典**，
     * 格式错行几乎总意味着拿错了文件/列序错位；非 UTF-8 文件按解码出的替换符整批拒绝——静默导入"对的那一半"，
     * 实施者看不出字典缺了哪些行；故格式硬错整批拒绝（5901 即为此登记）。
     * 而与既有口径<b>相同</b>的软口径照旧：upsert 命中<b>已停用</b>行时名称/拼音照更但
     * <b>不复活</b>（enabled 不动），并在返回里用 {@code disabledKept} 告知条数（v43 药品同款）。
     *
     * <p>合法时返回 {@code {imported, created, updated, disabledKept}}。
     */
    @PostMapping(value = "/import", consumes = "text/plain")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Map<String, Object>> importCsv(@RequestBody String csv) {
        String text = csv == null ? "" : csv;
        if (text.startsWith("﻿")) text = text.substring(1);
        // 第三轮反驳者二/三实测：Excel 中文系统另存的 GBK/ANSI 文件按 UTF-8 解码后名称成乱码、编码与拼音仍是纯 ASCII，
        // 此前能"成功"导入乱码名称——注释写"能挡 GBK"是假的。解码出替换符 U+FFFD 即非 UTF-8，整批拒绝。
        if (text.indexOf('\uFFFD') >= 0) {
            return fail5901(List.of(err(0, "文件不是 UTF-8 编码（疑为 GBK/ANSI），请在编辑器或 Excel 中另存为 UTF-8 后重试")), 1);
        }
        String[] lines = text.split("\\r?\\n", -1);

        List<Map<String, Object>> errors = new ArrayList<>();
        int errorCount = 0;
        Map<String, Integer> firstLineOf = new HashMap<>();
        List<String[]> rows = new ArrayList<>();   // [code, name, pinyin]
        boolean headerChecked = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            if (!headerChecked) {
                headerChecked = true;
                if (line.toLowerCase().startsWith("code")) continue;   // 表头（可选）
            }
            if (rows.size() + errorCount >= IMPORT_MAX_LINES) {
                return fail5901(List.of(err(i + 1, "超过单批上限 " + IMPORT_MAX_LINES + " 行，请拆分文件后分批导入")), 1);
            }
            String[] f = line.split(",", -1);
            String reason = null;
            if (f.length != 3) {
                reason = "列数应为 3（code,name,pinyin），实际 " + f.length + " 列";
            } else {
                String code = f[0].strip().toUpperCase();
                String name = f[1].strip();
                String pinyin = f[2].strip().toUpperCase();
                if (!CODE_RE.matcher(code).matches()) {
                    reason = "编码「" + f[0].strip() + "」不合规（字母数字开头，仅含字母数字及 . * + † -，最长 16 位）";
                } else if (name.isEmpty() || name.length() > 128) {
                    reason = "名称为空或超过 128 字";
                } else if (!PINYIN_RE.matcher(pinyin).matches()) {
                    reason = "拼音「" + f[2].strip() + "」不合规（必填，仅字母数字，最长 32 位；本系统不推断拼音）";
                } else if (firstLineOf.containsKey(code)) {
                    reason = "编码「" + code + "」与第 " + firstLineOf.get(code) + " 行重复";
                } else {
                    firstLineOf.put(code, i + 1);
                    rows.add(new String[]{code, name, pinyin});
                }
            }
            if (reason != null) {
                errorCount++;
                if (errors.size() < ERRORS_RETURNED_MAX) errors.add(err(i + 1, reason));
            }
        }
        if (errorCount > 0) {
            return fail5901(errors, errorCount);
        }
        if (rows.isEmpty()) {
            return fail5901(List.of(err(0, "文件里没有数据行")), 1);
        }

        // —— 到这里才开始写：校验已全部通过 ——
        Map<String, Boolean> existing = new HashMap<>();
        String[] codes = rows.stream().map(r -> r[0]).toArray(String[]::new);
        jdbc.query(con -> {
            var ps = con.prepareStatement("select code, enabled from md_icd10 where code = any (?)");
            ps.setArray(1, con.createArrayOf("varchar", codes));
            return ps;
        }, rs -> {
            existing.put(rs.getString(1), rs.getBoolean(2));
        });
        int created = 0;
        int updated = 0;
        int disabledKept = 0;
        List<Object[]> batch = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            Boolean en = existing.get(r[0]);
            if (en == null) created++;
            else {
                updated++;
                if (!en) disabledKept++;
            }
            batch.add(new Object[]{r[0], r[1], r[2]});
        }
        // do update 里不碰 enabled：已停用行名称/拼音照更、不复活
        jdbc.batchUpdate("""
                insert into md_icd10(code, name, pinyin) values (?,?,?)
                on conflict (code) do update set name = excluded.name, pinyin = excluded.pinyin,
                                                 updated_at = now()
                """, batch);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("imported", rows.size());
        body.put("created", created);
        body.put("updated", updated);
        body.put("disabledKept", disabledKept);
        // 与药品/收费项目导入同形（tools/init-hospital.py 对三个字典端点统一读 errorCount/errors）；
        // 本端点成功时恒为 0 / 空——有错就是 5901 整批拒绝，不会走到这里
        body.put("errorCount", 0);
        body.put("errors", List.of());
        return R.ok(body);
    }

    // ==================== 工具 ====================

    private static R<Map<String, Object>> fail5901(List<Map<String, Object>> errors, int errorCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCount", errorCount);
        body.put("errors", errors);
        return R.fail(5901, "CSV 有 " + errorCount + " 行格式不合法，整批未导入（请按行号修正后重新导入）", body);
    }

    private static Map<String, Object> err(int line, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", line);
        m.put("reason", reason);
        return m;
    }

    private static String normCode(String raw) {
        String c = raw == null ? "" : raw.strip().toUpperCase();
        if (!CODE_RE.matcher(c).matches()) {
            throw new HipBizException(4000, "诊断编码不合规（字母数字开头，仅含字母数字及 . * + † -，最长 16 位）");
        }
        return c;
    }

    private static String normName(String raw) {
        String n = raw == null ? "" : raw.strip();
        if (n.isEmpty() || n.length() > 128) {
            throw new HipBizException(4000, "诊断名称必填，且不超过 128 字");
        }
        return n;
    }

    private static String normPinyin(String raw) {
        String p = raw == null ? "" : raw.strip().toUpperCase();
        if (!PINYIN_RE.matcher(p).matches()) {
            throw new HipBizException(4000, "拼音首字母必填（仅字母数字，最长 32 位；本系统不推断拼音）");
        }
        return p;
    }

    /** LIKE 字面量转义：用户键入的 % _ \ 不当通配符（配合 SQL 里的 escape '\'） */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
