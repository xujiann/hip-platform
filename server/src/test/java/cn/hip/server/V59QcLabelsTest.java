package cn.hip.server;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>v59 2576 复核：质控页表头字典必须盖住后端 CSV 表头字典</h1>
 *
 * <h2>这个类为什么存在</h2>
 * 病理质控页（{@code PathQcView.vue}）的表头走前端 {@code format.ts} 的 {@code ZH} 映射，
 * 同一张表导出 CSV 时表头走后端 {@code PathQcController.zh(String col)}（复核清单里叫 columnLabel，
 * 同一个方法）。两份字典各长各的：v59 地基 1ca9773 上后端已登记 161 个键，前端 220 个键却<b>漏了
 * stat_day / issue_day / stained_count / progress_name</b>——四张按日工作量表第一列在页面上显示英文
 * {@code stat_day}、导出的 CSV 却写「日期」，同一列两套表头（复核者原话，主控实测坐实）。
 * <p>
 * 补四个键是一分钟的事，难的是<b>不让它再漏</b>：后端每加一个 case、前端就得跟一个键，没有人会记得。
 * 所以本类把「ZH ⊇ 后端 case 键」做成机械断言：剥注释、按语法形态解析两侧源码、逐键比对，缺了就红并把缺的键列出来。
 *
 * <h2>怎么解析</h2>
 * <ul>
 *   <li>Java 侧：剥 {@code //} 与块注释后，定位 {@code static String zh(String col)}（或 columnLabel）的
 *       {@code switch} 体（按括号配对截取，字符串里的花括号不算），取每个 {@code case "xxx" ->} 的引号键。
 *       注释掉的 case 不计。</li>
 *   <li>TS 侧：剥注释后定位 {@code const ZH: Record<string, string> = {}，按括号配对截取对象体，
 *       取每行 {@code key:} / {@code 'key':} 形态的键。注释掉的键不计。</li>
 * </ul>
 * 每条断言配活的对照组（两侧键数都要 > 50、后端必须真解析出 stat_day / issue_day）与探针
 * （往合成的后端文本塞一个 ZH 没有的键必须红；把 stat_day 从合成的 ZH 里抠掉必须报 stat_day）——
 * 「全绿」必须是因为真的对上了，不是因为解析器没读到东西。
 */
class V59QcLabelsTest {

    private static final String CONTROLLER = "modules/medtech/src/main/java/cn/hip/medtech/web/PathQcController.java";
    private static final String REPORT_CONTROLLER =
            "modules/medtech/src/main/java/cn/hip/medtech/web/PathologyReportController.java";
    private static final String FORMAT_TS = "frontend/shell/src/views/medtech/pathology/format.ts";
    private static final String QC_VIEW = "frontend/shell/src/views/medtech/pathology/PathQcView.vue";

    /** 反向事实：v59 地基 1ca9773 上，后端已登记而前端 ZH 没有的四个键（复核者点名的是前两个）。 */
    static final List<String> MISSING_BEFORE_V59 = List.of("stat_day", "issue_day", "stained_count", "progress_name");

    /** 后端方法名：复核清单叫 columnLabel，源码里叫 zh；改名也认，找不到就红。 */
    private static final Pattern LABEL_METHOD = Pattern.compile(
            "static\\s+String\\s+(?:zh|columnLabel)\\s*\\(\\s*String\\s+\\w+\\s*\\)\\s*\\{");
    private static final Pattern CASE_ARM = Pattern.compile("\\bcase\\s+((?:\"[^\"]*\"\\s*,?\\s*)+)->");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern ZH_DECL = Pattern.compile("const\\s+ZH\\s*:\\s*Record<\\s*string\\s*,\\s*string\\s*>\\s*=\\s*\\{");
    private static final Pattern TS_KEY = Pattern.compile("(?m)^\\s*(?:'([^']+)'|\"([^\"]+)\"|([A-Za-z_$][\\w$]*))\\s*:");

    // ==================================================================================
    // 断言
    // ==================================================================================

    /** 主断言：后端 CSV 表头字典的每个键，前端 ZH 都得有；缺的逐个列出。 */
    @Test
    void zhCoversEveryBackendColumnLabelKey() {
        Set<String> backend = backendLabelKeys(read(CONTROLLER));
        Set<String> zh = zhKeys(read(FORMAT_TS));
        List<String> missing = missing(backend, zh);
        assertTrue(missing.isEmpty(), () -> "前端 " + FORMAT_TS + " 的 ZH 映射缺以下 " + missing.size()
                + " 个后端 " + CONTROLLER + " zh() 已登记的列名键，页面表头会显示英文而 CSV 表头是中文（同一列两套表头）：\n  "
                + String.join("\n  ", missing)
                + "\n怎么修：往 ZH 补这些键，中文照后端同名 case 的右值抄，不要另起一个叫法。"
                + "\n（v59 地基 1ca9773 上缺的是 " + MISSING_BEFORE_V59 + "）");
    }

    /** 把反向事实钉正：那四个键现在必须在 ZH 里，且中文与后端同名 case 逐字一致。 */
    @Test
    void theFourKeysMissingBeforeV59AreRegisteredWithBackendWording() {
        Map<String, String> backend = backendLabels(read(CONTROLLER));
        Map<String, String> zh = zhEntries(read(FORMAT_TS));
        List<String> bad = new ArrayList<>();
        for (String k : MISSING_BEFORE_V59) {
            if (!backend.containsKey(k)) bad.add("后端 zh() 已没有 case \"" + k + "\"——反向事实失效，请核对本类 MISSING_BEFORE_V59");
            else if (!zh.containsKey(k)) bad.add("ZH 仍缺 " + k + "（后端叫「" + backend.get(k) + "」）");
            else if (!backend.get(k).equals(zh.get(k))) {
                bad.add("ZH." + k + " = 「" + zh.get(k) + "」，后端同名 case 是「" + backend.get(k) + "」——中文要照后端抄");
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** 活的对照组：两侧都得真解析出几十个键，且后端必须含复核者点名的 stat_day / issue_day。 */
    @Test
    void bothDictionariesAreLivingControlGroups() {
        Set<String> backend = backendLabelKeys(read(CONTROLLER));
        Set<String> zh = zhKeys(read(FORMAT_TS));
        assertTrue(backend.size() > 50, "后端 zh() 只解析出 " + backend.size() + " 个 case 键——解析器没读到 switch 体：" + backend);
        assertTrue(zh.size() > 50, "前端 ZH 只解析出 " + zh.size() + " 个键——解析器没读到对象体：" + zh);
        assertTrue(backend.containsAll(List.of("stat_day", "issue_day")),
                "后端 zh() 本该含 stat_day / issue_day（PathQcController 通用段前两行），解析器却没抓到：" + backend);
        assertTrue(zh.containsAll(List.of("path_no", "patient_name", "specimen_id")),
                "前端 ZH 本该含 path_no / patient_name / specimen_id，解析器却没抓到：" + zh);
    }

    /**
     * 自证：往合成的后端文本塞一个 ZH 没有的键必须红并点名；把 stat_day 从合成的 ZH 里抠掉必须报 stat_day
     * （这一步就是在真后端文本上重演 1ca9773 的缺陷）；注释掉的 case / 键两侧都不计。
     */
    @Test
    void detectorActuallyBites() {
        String java = read(CONTROLLER);
        String ts = read(FORMAT_TS);
        Set<String> zh = zhKeys(ts);

        // (1) 后端多一个键 → 只报这一个
        String probeJava = insertAfterSwitchOpen(java,
                "\n            case \"zz_probe_only_backend\" -> \"探针\";"
                + "\n            // case \"zz_dead_case\" -> \"注释里的不计\";");
        Set<String> probeKeys = backendLabelKeys(probeJava);
        assertTrue(probeKeys.contains("zz_probe_only_backend"), "合成 case 没被解析到：" + probeKeys);
        assertFalse(probeKeys.contains("zz_dead_case"), "注释掉的 case 不得计数：" + probeKeys);
        assertEquals(List.of("zz_probe_only_backend"), missing(probeKeys, zh),
                "往后端塞一个 ZH 没有的键，必须恰好报这一个");

        // (2) 前端抠掉 stat_day → 报 stat_day（重演修复前的缺陷）
        Pattern statDayLine = Pattern.compile("(?m)^\\s*stat_day\\s*:\\s*'[^']*'\\s*,\\s*\\r?\\n");
        Matcher m = statDayLine.matcher(ts);
        assertTrue(m.find(), "ZH 里本该有一行 stat_day: '…',——探针无从抠起");
        String probeTs = m.replaceFirst("");
        Set<String> probeZh = zhKeys(probeTs);
        assertFalse(probeZh.contains("stat_day"));
        assertEquals(List.of("stat_day"), missing(backendLabelKeys(java), probeZh),
                "抠掉 stat_day 后必须恰好报 stat_day（修复前的反向事实）");

        // (3) 前端注释里的键不计、别的对象里的 key: 不计
        String probeTs2 = ts.replaceFirst("(?m)^(\\s*stat_day\\s*:)",
                "  // zz_only_in_comment: '假',\n  /* zz_block_comment: '假', */\n$1");
        Set<String> probeZh2 = zhKeys(probeTs2);
        assertFalse(probeZh2.contains("zz_only_in_comment"), "行注释里的键不得计数：" + probeZh2);
        assertFalse(probeZh2.contains("zz_block_comment"), "块注释里的键不得计数：" + probeZh2);
        assertFalse(probeZh2.contains("value"), "SPECIMEN_TYPES 等别的对象字面量的 value:/label: 不得混进 ZH：" + probeZh2);
        assertTrue(probeZh2.contains("stat_day"));
    }

    /**
     * 穿透枚举中文化的落点：PathQcView 两张表的单元格必须走 format.ts 的 cellText（修复前是 {@code {{ fmt(row[c]) }}}，
     * 来源列显示 OUTP、染色列显示 IHC、环节列显示 GROSSING），明细列序必须走 idColumnsLast（内部主键挪到最后）。
     */
    @Test
    void qcViewCellsGoThroughCellTextAndIdColumnsLast() {
        String view = stripJsComments(stripHtmlComments(read(QC_VIEW)));
        String ts = stripJsComments(read(FORMAT_TS));
        Pattern nakedFmt = Pattern.compile("\\{\\{\\s*fmt\\(\\s*row\\[\\s*c\\s*\\]\\s*\\)\\s*\\}\\}");
        Pattern viaCellText = Pattern.compile("cellText\\(\\s*c\\s*,\\s*row\\[\\s*c\\s*\\]\\s*,\\s*row\\s*\\)");
        assertFalse(nakedFmt.matcher(view).find(),
                QC_VIEW + " 仍有 {{ fmt(row[c]) }} 形态的单元格——编码值不经字典就上屏（修复前两处）");
        assertEquals(2, count(viaCellText, view),
                QC_VIEW + " 汇总行表与穿透明细表两处单元格都得是 cellText(c, row[c], row)");
        assertTrue(view.contains("idColumnsLast(") && view.contains("isIdColumn("),
                QC_VIEW + " 明细列序必须走 idColumnsLast，并用 isIdColumn 给主键列置灰");
        // 对照组：format.ts 真的导出了这几个函数，view 引用的不是幻影
        for (String fn : List.of("cellText", "idColumnsLast", "isIdColumn", "techTypeName")) {
            assertTrue(ts.contains("export function " + fn + "("), FORMAT_TS + " 没有导出 " + fn);
        }
        // 探针：合成的修复前视图必须红
        String before = "<template><el-table-column v-for=\"c in columnsOf(detailItems)\">"
                + "<template #default=\"{ row }\">{{ fmt(row[c]) }}</template></el-table-column></template>";
        assertTrue(nakedFmt.matcher(stripJsComments(stripHtmlComments(before))).find(), "修复前形态必须被抓到");
        String dead = "<template><!-- {{ fmt(row[c]) }} --></template>";
        assertFalse(nakedFmt.matcher(stripJsComments(stripHtmlComments(dead))).find(), "注释里的形态不计");
    }

    /**
     * 前端 TECH_TYPES（v59 新增）与后端 {@code PathologyReportController.TECH_TYPE_NAMES} 逐键逐字同源：
     * 这张表是照抄来的，抄错一个字就是「同一个 IHC 两个名字」。
     */
    @Test
    void frontendTechTypesMirrorBackendTechTypeNames() {
        Map<String, String> backend = javaMapOf(stripJsComments(read(REPORT_CONTROLLER)), "TECH_TYPE_NAMES");
        Map<String, String> front = tsValueLabelList(stripJsComments(read(FORMAT_TS)), "TECH_TYPES");
        assertTrue(backend.size() >= 6, "后端 TECH_TYPE_NAMES 只解析出 " + backend);
        assertEquals(backend, front, "前端 TECH_TYPES 必须与后端 TECH_TYPE_NAMES 逐键逐字一致（值域 chk_path_tech_type 六档）");
    }

    // ==================================================================================
    // 解析器
    // ==================================================================================

    /** 后端 zh()/columnLabel() switch 体里全部 case 键（源码顺序，去重）。 */
    static Set<String> backendLabelKeys(String javaSource) {
        return backendLabels(javaSource).keySet();
    }

    /** 后端 case 键 → 右值中文（只认 {@code case "k" -> "v";} 形态；多标签 case 各键共用右值）。 */
    static Map<String, String> backendLabels(String javaSource) {
        String code = stripJsComments(javaSource);
        Matcher m = LABEL_METHOD.matcher(code);
        if (!m.find()) fail("在 " + CONTROLLER + " 里找不到 static String zh(String col) / columnLabel(String col)");
        int sw = code.indexOf("switch", m.end());
        int open = code.indexOf('{', sw);
        if (sw < 0 || open < 0) fail("zh() 里找不到 switch {");
        String body = code.substring(open + 1, closerOf(code, open));
        Map<String, String> out = new LinkedHashMap<>();
        Matcher arm = CASE_ARM.matcher(body);
        while (arm.find()) {
            int arrow = arm.end();
            Matcher rhs = QUOTED.matcher(body);
            String label = rhs.find(arrow) ? rhs.group(1) : null;
            Matcher q = QUOTED.matcher(arm.group(1));
            while (q.find()) out.putIfAbsent(q.group(1), label);
        }
        return out;
    }

    static Set<String> zhKeys(String tsSource) {
        return zhEntries(tsSource).keySet();
    }

    /** 前端 ZH 对象体里的键 → 单引号中文右值（源码顺序；重复键取首次）。 */
    static Map<String, String> zhEntries(String tsSource) {
        String code = stripJsComments(tsSource);
        Matcher m = ZH_DECL.matcher(code);
        if (!m.find()) fail("在 " + FORMAT_TS + " 里找不到 const ZH: Record<string, string> = {");
        int open = m.end() - 1;
        String body = code.substring(open + 1, closerOf(code, open));
        Map<String, String> out = new LinkedHashMap<>();
        Matcher k = TS_KEY.matcher(body);
        Pattern value = Pattern.compile("\\s*'((?:[^'\\\\]|\\\\.)*)'");
        while (k.find()) {
            String key = k.group(1) != null ? k.group(1) : k.group(2) != null ? k.group(2) : k.group(3);
            Matcher v = value.matcher(body.substring(k.end()));
            String label = v.lookingAt() ? v.group(1) : null;
            out.putIfAbsent(key, label);
        }
        return out;
    }

    static List<String> missing(Set<String> backend, Set<String> zh) {
        List<String> out = new ArrayList<>();
        for (String k : backend) if (!zh.contains(k)) out.add(k);
        return out;
    }

    /** 在 zh() 的 {@code switch (col) {} 开括号后插入文本（探针用）。 */
    private static String insertAfterSwitchOpen(String javaSource, String insert) {
        Matcher m = LABEL_METHOD.matcher(javaSource);
        assertTrue(m.find());
        int open = javaSource.indexOf('{', javaSource.indexOf("switch", m.end()));
        return javaSource.substring(0, open + 1) + insert + javaSource.substring(open + 1);
    }

    /** Java {@code Map.of("k", "v", …)} 常量 → 有序 map（探 TECH_TYPE_NAMES 这类六对的小表）。 */
    private static Map<String, String> javaMapOf(String code, String constName) {
        int at = code.indexOf(constName + " = Map.of(");
        if (at < 0) fail("找不到 " + constName + " = Map.of(");
        int open = code.indexOf('(', at);
        String body = code.substring(open + 1, closerOf(code, open));
        List<String> parts = new ArrayList<>();
        Matcher q = QUOTED.matcher(body);
        while (q.find()) parts.add(q.group(1));
        assertEquals(0, parts.size() % 2, constName + " 的引号串不成对：" + parts);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < parts.size(); i += 2) out.put(parts.get(i), parts.get(i + 1));
        return out;
    }

    /** TS {@code const X = [ { value: 'A', label: 'B' }, … ]} → 有序 map。 */
    private static Map<String, String> tsValueLabelList(String code, String constName) {
        Matcher m = Pattern.compile("const\\s+" + constName + "\\s*(?::[^=]*)?=\\s*\\[").matcher(code);
        if (!m.find()) fail("找不到 const " + constName + " = [");
        int open = m.end() - 1;
        String body = code.substring(open + 1, closerOf(code, open));
        Map<String, String> out = new LinkedHashMap<>();
        Matcher e = Pattern.compile("value\\s*:\\s*'([^']*)'\\s*,\\s*label\\s*:\\s*'([^']*)'").matcher(body);
        while (e.find()) out.put(e.group(1), e.group(2));
        return out;
    }

    private static int count(Pattern p, String s) {
        int n = 0;
        Matcher m = p.matcher(s);
        while (m.find()) n++;
        return n;
    }

    /** 从开括号（( [ {）向右找配对的闭括号，跳过字符串字面量（先剥过注释）。 */
    static int closerOf(String s, int open) {
        char o = s.charAt(open);
        char c = o == '(' ? ')' : o == '[' ? ']' : '}';
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == '\\') i++;
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') quote = ch;
            else if (ch == o) depth++;
            else if (ch == c && --depth == 0) return i;
        }
        return fail("从 " + open + " 起找不到配对的 " + c);
    }

    // ---------------- 剥注释与读文件（私有复制自 V57NakedIsoSliceTest，不改它） ----------------

    /** 去掉模板里的 {@code <!-- -->}，换行保留以便行号不漂。 */
    private static String stripHtmlComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("<!--", i);
            if (start < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, start);
            int end = s.indexOf("-->", start + 4);
            int stop = end < 0 ? s.length() : end + 3;
            for (int k = start; k < stop; k++) if (s.charAt(k) == '\n') out.append('\n');
            i = stop;
        }
        return out.toString();
    }

    /**
     * 去掉 {@code //} 行注释与 {@code /* *&#47;} 块注释，字符串里的内容原样保留——注释里的引号与括号不许干扰解析。
     * Java 与 TS 注释语法相同，两侧共用；Java 文本块 {@code """} 按三个普通引号处理，块内容仍算字符串。
     */
    private static String stripJsComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                out.append(ch);
                if (ch == '\\' && i + 1 < s.length()) out.append(s.charAt(++i));
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                out.append(ch);
            } else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                int end = e < 0 ? s.length() : e + 2;
                for (int k = i; k < end; k++) if (s.charAt(k) == '\n') out.append('\n');
                i = end - 1;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** 从测试工作目录（server 模块）向上找仓库根。 */
    private static Path repoRoot() {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("modules")) && Files.isDirectory(p.resolve("platform"))) {
                return p;
            }
        }
        return fail("定位不到仓库根（从 " + System.getProperty("user.dir") + " 向上找 modules/ 与 platform/）");
    }

    /** 按仓库相对路径读真文件——三份源码都是固定路径，不必全仓遍历；文件不在就红，不许静默跳过。 */
    private static String read(String rel) {
        Path f = repoRoot().resolve(rel);
        if (!Files.isRegularFile(f)) fail("找不到 " + rel + "（仓库根 " + repoRoot() + "）");
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读 " + f + " 失败", e);
        }
    }
}
