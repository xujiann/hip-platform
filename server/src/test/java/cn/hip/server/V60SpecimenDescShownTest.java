package cn.hip.server;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>v60 车道 A（2530 尾）：登记时录入的「标本描述」必须在病理工作台被<b>只读画出来</b></h1>
 *
 * <h2>这个类为什么存在</h2>
 * 反驳者原话（三票推翻，主控核过）：登记时强制录入的 {@code path_specimen.specimen_desc}（4554 必填）在前端
 * <b>没有任何一处只读展示</b>——四个读端点（登记检索 / 既往、取材工作列表与视图、报告全景与既往、轨迹）都 select 了它，
 * 但登记页标本检索表与既往抽屉、取材工作列表与「查看大体所见」头、诊断抽屉头与既往对比、轨迹抽屉头全都不画。
 * v60 地基 {@value #BASE_SHA} 上全 shell 唯一含 specimenDesc 的地方是登记表单的 {@code v-model}——只写不读。
 * <p>
 * 补六处展示是几行模板的事，难的是<b>让「画了」成为机械可查的事实</b>：本类剥掉模板 HTML 注释与 JS 注释后，
 * 按<b>渲染形态</b>（不是裸 token）数四个面板里 {@code specimen_desc} 出现的次数，并用地基版本的真文本做对照组——
 * 修复前四文件渲染形态计数都是 0、而登记表单的 v-model 形态存在，证明扫描器读的是真文件、且分得开「表单绑定」与「只读展示」。
 *
 * <h2>抓什么（三族渲染形态）</h2>
 * <ol>
 *   <li>{@code {{ …specimen_desc… }}}——插值（可跨行）；</li>
 *   <li>{@code prop="specimen_desc"}——表格列直绑；</li>
 *   <li>{@code :label="…specimen_desc…"}——下拉项 / 描述项标签里拼进去。</li>
 * </ol>
 * <b>不算</b>：{@code v-model="…specimen_desc / specimenDesc…"}（表单绑定是写、不是读）、字符串字面量、注释里的任何形态。
 * 字段名以各端点返回为准：四个读端点都是 JdbcTemplate 直出的蛇形 {@code specimen_desc}（登记的 SPECIMEN_SELECT、
 * 取材的 SPECIMEN_SELECT 与 grossingView 头、报告的 worklist / reports 头 / prior 行；轨迹头由车道 B 加，A 侧回落取材视图头）。
 *
 * <h2>被取代版本的字段可调阅 + 补取材挂接医嘱（同车道另两项）</h2>
 * 取材视图与轨迹抽屉必须消费车道 B 的新键 {@code fieldsByRevision}，取材登记必须把 {@code techOrderId} 发进 POST /grossing
 * ——地基上三处都不存在（对照组一并钉住）。
 *
 * <h2>对照组怎么取</h2>
 * {@code git show <地基 sha>:<路径>} 喂给同一个扫描器。CI 的浅克隆（fetch-depth=1）可能没有那个提交：此时对照组用
 * {@link Assumptions} 标「跳过」而不是假绿——没数据就说没数据。
 * 本类只读固定路径的四个文件，不做目录遍历，故无 worktree 排除逻辑可言（读的路径相对仓库根）。
 */
class V60SpecimenDescShownTest {

    /** v60 地基：规划节 + 错误码登记，四个面板尚未动过。 */
    static final String BASE_SHA = "96f098e";

    private static final String DIR = "frontend/shell/src/views/medtech/pathology/";
    static final String REGISTRY = DIR + "RegistryPanel.vue";
    static final String GROSSING = DIR + "GrossingPanel.vue";
    static final String DIAGNOSIS = DIR + "DiagnosisPanel.vue";
    static final String TRAIL = DIR + "TrailPanel.vue";

    /**
     * 六处展示落在四个文件里的最少渲染形态数：登记 2（检索表 + 既往抽屉表）、取材 2（工作列表 + 视图头）、
     * 诊断 3（抽屉头 + 对比框左右两栏）、轨迹 1（抽屉头）。用 {@code >=}：以后多画一处不该红。
     */
    static final Map<String, Integer> MIN_RENDER = Map.of(REGISTRY, 2, GROSSING, 2, DIAGNOSIS, 3, TRAIL, 1);

    // ==================================================================================
    // 形态
    // ==================================================================================

    /** 插值 {{ …specimen_desc… }}：可跨行，内容里不含 }} */
    private static final Pattern MUSTACHE = Pattern.compile(
            "\\{\\{(?:(?!\\}\\}).)*?\\bspecimen_desc\\b(?:(?!\\}\\}).)*?\\}\\}", Pattern.DOTALL);
    /** 表格列直绑 prop="specimen_desc" */
    private static final Pattern PROP = Pattern.compile("\\bprop=\"specimen_desc\"");
    /** :label="…specimen_desc…" */
    private static final Pattern LABEL_BIND = Pattern.compile(":label=\"[^\"]*\\bspecimen_desc\\b[^\"]*\"");
    /** 表单绑定（不算渲染）：v-model="…specimen_desc / specimenDesc…" */
    private static final Pattern V_MODEL = Pattern.compile("v-model=\"[^\"]*\\bspecimen(?:_d|D)esc\\b[^\"]*\"");

    private static final List<Pattern> RENDER_FORMS = List.of(MUSTACHE, PROP, LABEL_BIND);

    // ==================================================================================
    // 断言
    // ==================================================================================

    /** 主断言：四个面板剥注释后各自含足够的渲染形态；登记表单的 v-model 仍在（表单没被误改成只读）。 */
    @Test
    void sixPlacesRenderSpecimenDescReadOnly() {
        List<String> bad = new ArrayList<>();
        for (var e : MIN_RENDER.entrySet()) {
            String code = stripped(read(e.getKey()));
            int n = renderCount(code);
            if (n < e.getValue()) {
                bad.add(e.getKey() + " 渲染形态只有 " + n + " 处，至少要 " + e.getValue()
                        + "（{{ …specimen_desc… }} / prop=\"specimen_desc\" / :label=\"…specimen_desc…\"）");
            }
        }
        assertTrue(bad.isEmpty(), () -> "标本描述只读展示缺口：\n  " + String.join("\n  ", bad)
                + "\n怎么修：登记检索表与既往抽屉表加「标本描述」列、取材工作列表加列 + 视图头加行、诊断抽屉头 + 对比框两栏加行、轨迹抽屉头加行；"
                + "取值一律 fmt(row.specimen_desc)，空即「—」。");
        assertTrue(formCount(stripped(read(REGISTRY))) >= 1,
                REGISTRY + " 登记表单的 v-model=\"regForm.specimenDesc\" 必须还在——只读展示不是把录入口改掉");
    }

    /** 同车道另两项：取材视图与轨迹抽屉消费 fieldsByRevision，取材登记把 techOrderId 发进 POST /grossing（车道 B 契约）。 */
    @Test
    void grossingAndTrailConsumeFieldsByRevisionAndGrossingSendsTechOrderId() {
        String grossing = stripped(read(GROSSING));
        String trail = stripped(read(TRAIL));
        assertTrue(grossing.contains("fieldsByRevision"), GROSSING + " 必须消费 fieldsByRevision（被取代版本的字段可调阅：查看对话框按版切换）");
        assertTrue(trail.contains("fieldsByRevision"), TRAIL + " 必须消费 fieldsByRevision（修订表每行可展开该版字段）");
        // techOrderId 必须真的在 POST /pathology/process/grossing 的请求体里，不是随便哪里提一嘴
        Matcher post = Pattern.compile("client\\.post\\(\\s*'/pathology/process/grossing'\\s*,\\s*\\{").matcher(grossing);
        assertTrue(post.find(), GROSSING + " 找不到 client.post('/pathology/process/grossing', {");
        int open = grossing.indexOf('{', post.end() - 1);
        String body = grossing.substring(open + 1, closerOf(grossing, open));
        assertTrue(Pattern.compile("(?m)^\\s*techOrderId\\s*[,:]").matcher(body).find(),
                GROSSING + " 的 POST /grossing 请求体必须带 techOrderId（append=true 时挂接补取材医嘱）：\n" + body);
        // 医嘱来源：GET /pathology/report/tech-orders，只列 ORDERED 且 RESAMPLE；被拒 5275 要给人看后端消息
        assertTrue(grossing.contains("'/pathology/report/tech-orders'"), GROSSING + " 挂接下拉的来源必须是 GET /pathology/report/tech-orders");
        assertTrue(Pattern.compile("status\\s*:\\s*'ORDERED'").matcher(grossing).find(), GROSSING + " 取医嘱必须显式 status: 'ORDERED'（传 specimenId 时后端默认全状态）");
        assertTrue(grossing.contains("'RESAMPLE'"), GROSSING + " 只列 tech_type=RESAMPLE 的医嘱");
        assertTrue(grossing.contains("5275"), GROSSING + " 被拒 5275 时必须提示后端消息（错误码只用登记的 5275）");
    }

    /**
     * 对照组：v60 地基 {@value #BASE_SHA} 的真文本喂同一个扫描器——四文件渲染形态计数为 0、登记表单 v-model 形态存在、
     * 三个新键都不存在。证明扫描器读的是真文件、正则真的匹配得上、且分得开表单与渲染；对照组没了（浅克隆）就跳过而不是假绿。
     */
    @Test
    void baselineBeforeFixIsTheLivingControlGroup() {
        Map<String, String> base = new LinkedHashMap<>();
        for (String rel : MIN_RENDER.keySet()) {
            String text = gitShow(BASE_SHA, rel);
            Assumptions.assumeTrue(text != null,
                    "对照组不可用：git show " + BASE_SHA + ":" + rel + " 失败（浅克隆没有该提交，或没有 git）——本断言跳过，不算绿");
            base.put(rel, text);
        }
        for (var e : base.entrySet()) {
            String code = stripped(e.getValue());
            assertEquals(0, renderCount(code), "修复前 " + e.getKey() + " 不该有任何渲染形态（反驳者原话），扫描器却数到了");
        }
        assertTrue(formCount(stripped(base.get(REGISTRY))) >= 1,
                "修复前 " + REGISTRY + " 本该有 v-model=\"regForm.specimenDesc\"（只写不读的那一处）——对照组读到的不是真文件，或 v-model 形态没匹配上");
        assertFalse(stripped(base.get(GROSSING)).contains("techOrderId"), "修复前 " + GROSSING + " 不该有 techOrderId");
        assertFalse(stripped(base.get(GROSSING)).contains("fieldsByRevision"), "修复前 " + GROSSING + " 不该有 fieldsByRevision");
        assertFalse(stripped(base.get(TRAIL)).contains("fieldsByRevision"), "修复前 " + TRAIL + " 不该有 fieldsByRevision");
        // 地基文本与当前文本确实不是同一份：对照组不是把当前文件读了两遍。
        // 必须先统一换行再比：core.autocrlf=true 时 git show 出的是 LF、工作区是 CRLF，不统一则「不同」恒成立、断言形同虚设
        for (String rel : MIN_RENDER.keySet()) {
            assertFalse(lf(base.get(rel)).equals(lf(read(rel))),
                    rel + " 的地基文本与工作区文本（统一换行后）相同——本车道改动没落到这个文件，或 git show 读到的是工作区");
        }
    }

    /** 统一成 LF：对照组（git show，按 autocrlf 出 LF）与工作区文件（CRLF）只比内容不比换行。 */
    private static String lf(String s) {
        return s.replace("\r\n", "\n");
    }

    /**
     * 自证：合成源码上，注释掉的渲染（HTML 注释 / JS 行注释 / 块注释）不计，活的三族各计一次，跨行插值计，
     * v-model 只进表单计数、不进渲染计数，字符串字面量不计；不剥注释时同一段文本会多数出注释里的那几处。
     */
    @Test
    void detectorActuallyBites() {
        String synthetic = String.join("\n",
                "<template>",
                "  <!-- {{ fmt(row.specimen_desc) }} -->",
                "  <!-- 多行",
                "       <el-table-column prop=\"specimen_desc\" /> -->",
                "  <el-table-column label=\"标本描述\"><template #default=\"{ row }\">{{ fmt(row.specimen_desc) }}</template></el-table-column>",
                "  <el-table-column prop=\"specimen_desc\" label=\"描述\" />",
                "  <el-option :label=\"`${fmt(r.path_no)}　${fmt(r.specimen_desc)}`\" />",
                "  <el-input v-model=\"regForm.specimenDesc\" />",
                "  <el-input v-model=\"form.specimen_desc\" />",
                "  <el-descriptions-item label=\"x\">{{",
                "    fmt(a.specimen_desc ?? b.specimen_desc)",
                "  }}</el-descriptions-item>",
                "  <span>{{ fmt(row.specimen_type) }}</span>",
                "</template>",
                "<script setup lang=\"ts\">",
                "// {{ fmt(row.specimen_desc) }}",
                "/* prop=\"specimen_desc\" */",
                "const key = 'specimen_desc'",
                "</script>");
        String code = stripped(synthetic);
        assertEquals(4, renderCount(code), "活的渲染形态：插值 ×2（含跨行一处）+ prop ×1 + :label ×1；注释与字面量不计。剥注释后文本：\n" + code);
        assertEquals(2, formCount(code), "v-model 两种拼法（specimen_desc / specimenDesc）都进表单计数");
        assertEquals(4, renderCount(V_MODEL.matcher(code).replaceAll("")),
                "抠掉两处 v-model 后渲染计数不变——v-model 从未混进渲染计数");
        assertEquals(8, renderCount(synthetic), "不剥注释会把 HTML 注释里 2 处 + JS 注释里 2 处多数进来（4 + 4）——证明剥注释这一步是活的");
        // 单独验字面量与非渲染 token 不计
        assertEquals(0, renderCount(stripped("<script>const k = 'specimen_desc'; const s = \"prop=\\\"specimen_desc\\\"\"</script>")),
                "字符串字面量里的形态不计");
        assertEquals(0, renderCount("{{ fmt(row.specimenDesc) }}"), "驼峰 specimenDesc 不是读端点回的字段名，不计为渲染（画了也是 undefined）");
    }

    // ==================================================================================
    // 扫描器
    // ==================================================================================

    static int renderCount(String code) {
        int n = 0;
        for (Pattern p : RENDER_FORMS) n += count(p, code);
        return n;
    }

    static int formCount(String code) {
        return count(V_MODEL, code);
    }

    private static int count(Pattern p, String s) {
        int n = 0;
        Matcher m = p.matcher(s);
        while (m.find()) n++;
        return n;
    }

    /** 剥模板 HTML 注释再剥 JS 注释（顺序不能反：{@code <!-- // -->} 里的 // 不该先把 --> 吃掉）。 */
    static String stripped(String vueSource) {
        return stripJsComments(stripHtmlComments(vueSource));
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

    // ---------------- 剥注释与读文件（私有复制自 V59QcLabelsTest / V57NakedIsoSliceTest，不改它们） ----------------

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
     * 模板属性值用双引号、表达式里用单引号 / 反引号，成对出现，与 TS 同一套规则。
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

    /** 按仓库相对路径读真文件——四份源码都是固定路径，不必全仓遍历；文件不在就红，不许静默跳过。 */
    private static String read(String rel) {
        Path f = repoRoot().resolve(rel);
        if (!Files.isRegularFile(f)) fail("找不到 " + rel + "（仓库根 " + repoRoot() + "）");
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读 " + f + " 失败", e);
        }
    }

    /**
     * {@code git show <sha>:<rel>} 的文本；git 不在 / 提交不可达（浅克隆）/ 路径在该提交里不存在时返回 null，
     * 由调用方决定跳过——不在这里 fail，也不在这里假装读到了。仓库根就是本 worktree 的根，{@code -C} 指过去即可。
     */
    private static String gitShow(String sha, String rel) {
        try {
            Process p = new ProcessBuilder("git", "-C", repoRoot().toString(), "show", sha + ":" + rel)
                    .redirectErrorStream(true)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) {
                System.err.println("[V60SpecimenDescShownTest] git show 退出码 " + code + "：" + new String(out, StandardCharsets.UTF_8).strip());
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[V60SpecimenDescShownTest] git show 不可用：" + e);
            return null;
        }
    }
}
