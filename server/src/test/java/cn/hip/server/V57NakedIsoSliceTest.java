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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>v57 2558：前端不许再裸切 UTC 线格式</h1>
 *
 * <h2>这个类为什么存在</h2>
 * 后端 timestamptz 经 JdbcTemplate/Jackson 出来的线格式是 {@code 2026-09-08T05:00:44.229+00:00}
 * （UTC 带偏移），实体 Instant 字段是 {@code …Z}。v57 之前全 shell 有 43 处
 * {@code String(x).slice(0, 16).replace('T', ' ')} 一类的写法——把 UTC 的前 16 位直接画到屏幕上，
 * 等于把 UTC 当北京时间：<b>病理、药房、护理、审计的每一个时刻都早 8 小时</b>；
 * 门户首页对报告日期 {@code slice(0, 10)}，北京 0–8 点签发的报告显示成前一天；
 * {@code new Date().toISOString().slice(0, 10)} 取「今天」，北京 0–8 点取到的是 UTC 的昨天。
 * <p>
 * v57 的地基是 {@code frontend/shell/src/utils/date.ts} 的 fmtDateTime / fmtDate
 * （字符串带 Z / ±hh:mm 才换算到 Asia/Shanghai，不带偏移原样截取）。裸切修一次容易，
 * <b>难的是不让它长回来</b>——这类写法一行就能写出来、构建不报错、本机（浏览器在 +08:00）肉眼也看不出。
 * 所以本类把「不许裸切」做成机械断言，每次 CI 都跑。
 *
 * <h2>抓什么</h2>
 * 剥掉 JS 行/块注释与模板 HTML 注释之后，按<b>语法形态</b>（不是裸 token）匹配：
 * <ol>
 *   <li>{@code .slice(0, 16)}——截到分钟；</li>
 *   <li>{@code .replace('T', ' ')}——把 ISO 直接画成时刻（连 {@code slice(5, 16)} 这种省年份的变体一起抓）；</li>
 *   <li>{@code toISOString().slice(0, 10)}——UTC 的「今天」；</li>
 *   <li>{@code .slice(0, 10)} 且接收表达式（点号左侧、同一表达式内）里有标识符以
 *       {@code _at / At / _date / Date / date / time / Time} 结尾——把时间戳当日期显示。
 *       {@code String(row.day).slice(0, 10)}、{@code title.slice(0, 10)} 这种截文本的不算。</li>
 * </ol>
 * 唯一合法实现是 {@code utils/date.ts}（它本来就该含这些形态，{@link #dateUtilsIsTheLivingControlGroup()}
 * 断言它<b>确实含</b>——扫描器读的是真文件、正则真的匹配得上，而不是「一处都没扫到所以全绿」）。
 *
 * <h2>每条断言都配一条探针</h2>
 * {@link #nakedSliceDetectorActuallyBites()} 在合成源码上证明：注释里的形态不计、活代码计、
 * 行号穿过多行块注释仍对得上、接收者不带日期词的 {@code slice(0, 10)} 不误报。
 */
class V57NakedIsoSliceTest {

    private static final String FRONTEND_SRC = "frontend/shell/src";

    /** 唯一合法实现：按字符串形态自判、带偏移才换算到业务时区。 */
    private static final String LEGAL_IMPL = FRONTEND_SRC + "/utils/date.ts";

    // ==================================================================================
    // 豁免清单（每条都要有理由；键 = 仓库相对路径；条目对不上任何命中时 waiverListItselfIsMaintained 会要求删掉）
    // ==================================================================================

    private record Waiver(String key, String why) {}

    /**
     * 理想为空。第一条是 v57 车道 A 同版在改的文件；后五条是车道 B 文件清单之外、主控未分派的文件——
     * 它们显示的<b>同样是</b> timestamptz，同样早 8 小时 / 早一天，不是「天然不该改」，只是本车道无权动别人的文件。
     * 修完（一行 fmtDateTime / fmtDate / fmtMonthDayTime 即可）请把对应条目删掉，本类会因「条目已对不上命中」逼你删。
     */
    private static final List<Waiver> WAIVERS = List.of(
            new Waiver(FRONTEND_SRC + "/views/medtech/SpecialtyView.vue",
                    "v57 车道 A 同版在改（2558 拆车道时 SpecialtyView 归 A）：rescue_start 用 slice(0,19).replace('T',' ') 早 8 小时。"
                            + "车道 A 合并后本条会对不上命中，届时直接删"),
            new Waiver(FRONTEND_SRC + "/components/VitalsChart.vue",
                    "v57 车道 B 清单外、主控未分派：measuredAt 是 timestamptz，slice(5, 16) 画 MM-DD HH:mm 早 8 小时；"
                            + "应改 fmtMonthDayTime(v.measuredAt, '')。修完删本条"),
            new Waiver(FRONTEND_SRC + "/views/PrintView.vue",
                    "v57 车道 B 清单外、主控未分派：护理记录 record_time 用 replace('T',' ').slice(0,16) 早 8 小时；"
                            + "同文件 fmtDate(v) 对 admit_at / discharged_at / created_at 裸切 slice(0,10)（扫描器按接收者 v 抓不到）。"
                            + "应改 fmtDateTime(r.record_time, '') 与 utils/date 的 fmtDate。修完删本条"),
            new Waiver(FRONTEND_SRC + "/views/cdr/Patient360View.vue",
                    "v57 车道 B 清单外、主控未分派：时间线 docTime 用 slice(0,16).replace('T',' ') 早 8 小时；"
                            + "应改 fmtDateTime(d.docTime)。修完删本条"),
            new Waiver(FRONTEND_SRC + "/views/inpatient/AdmissionView.vue",
                    "v57 车道 B 清单外、主控未分派：入院时间列 admitAt 裸切 slice(0,10)，北京 0–8 点入院显示前一天；"
                            + "应改 fmtDate(row.admitAt)。修完删本条"),
            new Waiver(FRONTEND_SRC + "/views/inpatient/DischargeView.vue",
                    "v57 车道 B 清单外、主控未分派：结算流水 created_at 用 slice(0,19).replace('T',' ') 早 8 小时；"
                            + "应改 fmtDateTimeSec(row.created_at)。修完删本条"));

    // ==================================================================================
    // 四种语法形态
    // ==================================================================================

    private static final Pattern SLICE_16 = Pattern.compile("\\.slice\\(0,\\s*16\\)");
    private static final Pattern REPLACE_T_SPACE = Pattern.compile("\\.replace\\('T',\\s*' '\\)");
    private static final Pattern TODAY_UTC = Pattern.compile("toISOString\\(\\)\\.slice\\(0,\\s*10\\)");
    private static final Pattern SLICE_10 = Pattern.compile("\\.slice\\(0,\\s*10\\)");
    /** 整个标识符以这些词结尾才算「时间戳/日期接收者」（等价于题面的 {@code (_at|At|_date|Date|date|time|Time)\b}）。 */
    private static final Pattern DATE_ISH_IDENT = Pattern.compile("(_at|At|_date|Date|date|time|Time)$");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$]*");

    static final String FORM_SLICE_16 = "slice(0,16) 裸切到分钟";
    static final String FORM_REPLACE_T = "replace('T',' ') 把 ISO 直接画成时刻";
    static final String FORM_TODAY_UTC = "toISOString().slice(0,10) 取的是 UTC 的今天";
    static final String FORM_DATE_SLICE_10 = "时间戳/日期接收者 slice(0,10) 当日期显示";

    /** 一处命中：文件、行号、命中的形态（同一行多种形态用「 + 」并起来）、该行内容。 */
    record Hit(String file, int line, String forms, String snippet) {
        @Override
        public String toString() {
            return file + ":" + line + "  [" + forms + "]  " + snippet;
        }
    }

    // ==================================================================================
    // 断言
    // ==================================================================================

    @Test
    void noNakedIsoSliceOutsideDateUtils() {
        Set<String> waived = waivedKeys(WAIVERS);
        Map<String, List<Hit>> byFile = new TreeMap<>();
        for (SrcFile f : shellSources()) {
            if (f.rel().equals(LEGAL_IMPL) || waived.contains(f.rel())) continue;
            List<Hit> hits = scan(f);
            if (!hits.isEmpty()) byFile.put(f.rel(), hits);
        }
        if (byFile.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("以下 ").append(byFile.size()).append(" 个前端文件在裸切 UTC 线格式——后端 timestamptz 出来的是 ")
                .append("2026-09-08T05:00:44.229+00:00，slice(0,16) 画出来的时刻早 8 小时、slice(0,10) 在北京 0–8 点早一天：\n");
        for (Map.Entry<String, List<Hit>> e : byFile.entrySet()) {
            for (Hit h : e.getValue()) sb.append("  ").append(h).append('\n');
        }
        sb.append("""
                怎么修（三选一）：
                  A. 显示时刻：用 utils/date 的 fmtDateTime(x) / fmtDateTimeSec(x)（原本显示到秒的）/ fmtMonthDayTime(x)；
                     显示日期：fmtDate(x)；取「今天」：todayLocal() / localDateOffset(n)。
                     旧代码空值显示空串的位置传 empty=''，不要把 '' 悄悄变成 '—'。
                  B. 接收者确实不是时间（截文本被 slice(0,10) 误抓）：往本类 WAIVERS 加一条并写清是哪个字段、为什么不是时间。
                  C. 新增合法实现：只能长在 utils/date.ts 里，别处一律委托它。
                """);
        fail(sb.toString());
    }

    /** 活的对照组：合法实现里必须真的含这些形态，否则「全绿」只说明扫描器没在读真文件。 */
    @Test
    void dateUtilsIsTheLivingControlGroup() {
        List<SrcFile> all = shellSources();
        SrcFile impl = all.stream().filter(f -> f.rel().equals(LEGAL_IMPL)).findFirst()
                .orElseGet(() -> fail("扫描器没走到 " + LEGAL_IMPL + "——先修扫描再看结果；扫到的文件数=" + all.size()));
        List<Hit> hits = scan(impl);
        Set<String> forms = new TreeSet<>();
        for (Hit h : hits) forms.addAll(List.of(h.forms().split(" \\+ ")));
        assertTrue(forms.contains(FORM_SLICE_16),
                "对照组：" + LEGAL_IMPL + " 的 fmtDateTime 无偏移分支本该含 .slice(0, 16)，扫描器却没抓到——"
                        + "要么正则坏了、要么剥注释把活代码剥掉了。实际命中：" + hits);
        assertTrue(forms.contains(FORM_REPLACE_T),
                "对照组：" + LEGAL_IMPL + " 本该含 .replace('T', ' ')，扫描器却没抓到。实际命中：" + hits);
    }

    /** 豁免清单自身要维护：理由要写清、不许重复、条目对不上任何命中就该删。 */
    @Test
    void waiverListItselfIsMaintained() {
        List<String> bad = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        Map<String, SrcFile> byRel = new LinkedHashMap<>();
        for (SrcFile f : shellSources()) byRel.put(f.rel(), f);
        for (Waiver w : WAIVERS) {
            if (!seen.add(w.key())) bad.add("WAIVERS 里 \"" + w.key() + "\" 重复登记——合并成一条");
            String why = w.why() == null ? "" : w.why().strip();
            if (why.length() < 20) {
                bad.add("WAIVERS 里 \"" + w.key() + "\" 的理由太短（" + why.length() + " 字）：写清是哪个字段、为什么不是时间");
            }
            SrcFile f = byRel.get(w.key());
            if (f == null) {
                bad.add("WAIVERS 里 \"" + w.key() + "\" 已对不上 " + FRONTEND_SRC + " 下的任何文件。烂条目请直接删掉。");
            } else if (scan(f).isEmpty()) {
                bad.add("WAIVERS 里 \"" + w.key() + "\" 已经没有任何裸切命中——它修好了，烂条目请直接删掉。");
            }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /**
     * 自证：注释里的形态不计、活代码计、行号穿过多行块注释仍准、接收者不带日期词的 slice(0,10) 不误报。
     * 探针在合成源码上做手术，与真仓无关——真仓全绿时它照样能证明扫描器会咬人。
     */
    @Test
    void nakedSliceDetectorActuallyBites() {
        String dead = """
                <template>
                  <!-- <span>{{ String(row.created_at).slice(0, 16).replace('T', ' ') }}</span> -->
                  <span>{{ row.title }}</span>
                </template>
                <script setup lang="ts">
                // const a = String(row.measuredAt).slice(0, 16).replace('T', ' ')
                /* const today = new Date().toISOString().slice(0, 10)
                   const day = String(row.admitAt).slice(0, 10) */
                const url = 'https://example.org/x'   // 字符串里的 // 不是注释
                </script>
                """;
        String live = """
                <template>
                  <span>{{ String(row.created_at).slice(0, 16).replace('T', ' ') }}</span>
                  <span>{{ String(row.admitAt).slice(0, 10) }}</span>
                  <span>{{ String(row.day).slice(0, 10) }}</span>
                </template>
                <script setup lang="ts">
                /* 多行块注释
                   占两行，后面的行号必须仍然对得上 */
                const today = new Date().toISOString().slice(0, 10)
                const label = String(item.report_date ?? '').slice(0, 10)
                const text = String(memo).slice(0, 10)
                const t2 = row.received_at?.slice(0,10)
                </script>
                """;

        List<Hit> deadHits = scan(new SrcFile(FRONTEND_SRC + "/views/probe/Dead.vue", dead));
        assertTrue(deadHits.isEmpty(), "注释（HTML / 行 / 块）里的形态不得计数，实际抓到：" + deadHits);

        List<Hit> liveHits = scan(new SrcFile(FRONTEND_SRC + "/views/probe/Live.vue", live));
        Map<Integer, String> byLine = new TreeMap<>();
        for (Hit h : liveHits) byLine.put(h.line(), h.forms());
        assertEquals(Set.of(2, 3, 9, 10, 12), byLine.keySet(),
                "活代码的四种形态都得抓到、且行号穿过块注释后仍准；row.day / String(memo) 不是时间不得误报。实际：" + liveHits);
        assertEquals(FORM_SLICE_16 + " + " + FORM_REPLACE_T, byLine.get(2), "同一行两种形态要并起来报");
        assertEquals(FORM_DATE_SLICE_10, byLine.get(3), "admitAt 以 At 结尾，是时间戳接收者");
        assertEquals(FORM_TODAY_UTC, byLine.get(9));
        assertEquals(FORM_DATE_SLICE_10, byLine.get(10), "report_date 以 _date 结尾，?? '' 括号里的引号不许干扰接收者提取");
        assertEquals(FORM_DATE_SLICE_10, byLine.get(12), "可选链 received_at?.slice(0,10) 也是时间戳接收者");
        assertFalse(byLine.containsKey(4), "String(row.day).slice(0, 10)：day 不带日期词，不得误报");
        assertFalse(byLine.containsKey(11), "String(memo).slice(0, 10)：截文本，不得误报");

        // 探针本身也得证明「不是裸 token 匹配」：slice(0, 100) / slice(0,1) 不算
        List<Hit> near = scan(new SrcFile(FRONTEND_SRC + "/views/probe/Near.ts",
                "export const a = String(row.created_at).slice(0, 100)\nexport const b = list.slice(0, 1)\n"));
        assertTrue(near.isEmpty(), "slice(0, 100) / slice(0, 1) 不是裸切形态，不得误报：" + near);
    }

    // ==================================================================================
    // 扫描器
    // ==================================================================================

    /** 一个文件里的全部命中（按行去重、同一行多形态并起来）。 */
    static List<Hit> scan(SrcFile f) {
        String code = f.rel().endsWith(".vue") ? stripHtmlComments(f.text()) : f.text();
        code = stripJsComments(code);
        Map<Integer, List<String>> formsByLine = new TreeMap<>();
        collect(code, SLICE_16, FORM_SLICE_16, formsByLine);
        collect(code, REPLACE_T_SPACE, FORM_REPLACE_T, formsByLine);
        collect(code, TODAY_UTC, FORM_TODAY_UTC, formsByLine);
        Matcher m = SLICE_10.matcher(code);
        while (m.find()) {
            String receiver = receiverBefore(code, m.start());
            if (receiver.endsWith("toISOString()")) continue;   // 形态 3 已经报过，别重复
            if (hasDateIshIdent(receiver)) {
                formsByLine.computeIfAbsent(lineOf(code, m.start()), k -> new ArrayList<>()).add(FORM_DATE_SLICE_10);
            }
        }
        String[] lines = code.split("\n", -1);
        List<Hit> out = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> e : formsByLine.entrySet()) {
            String snippet = lines[e.getKey() - 1].strip();
            if (snippet.length() > 160) snippet = snippet.substring(0, 160) + "…";
            out.add(new Hit(f.rel(), e.getKey(), String.join(" + ", e.getValue()), snippet));
        }
        return out;
    }

    private static void collect(String code, Pattern p, String form, Map<Integer, List<String>> formsByLine) {
        Matcher m = p.matcher(code);
        while (m.find()) {
            List<String> forms = formsByLine.computeIfAbsent(lineOf(code, m.start()), k -> new ArrayList<>());
            if (!forms.contains(form)) forms.add(form);
        }
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) if (code.charAt(i) == '\n') line++;
        return line;
    }

    private static boolean hasDateIshIdent(String receiver) {
        Matcher m = IDENT.matcher(receiver);
        while (m.find()) {
            if (DATE_ISH_IDENT.matcher(m.group()).find()) return true;
        }
        return false;
    }

    /**
     * {@code .slice(0, 10)} 左侧的接收表达式：从点号向左吞标识符、点号、可选链 {@code ?.}、成对的 () 与 []，
     * 碰到别的字符就停。{@code String(row.admitAt)} 整体、{@code a.b?.c}、{@code arr[0].x} 都取得完整。
     */
    static String receiverBefore(String s, int dot) {
        int i = dot - 1;
        while (i >= 0) {
            char c = s.charAt(i);
            if (c == ')' || c == ']') {
                int open = openerOf(s, i);
                if (open < 0) break;
                i = open - 1;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                i--;
            } else if (c == '?' && s.charAt(i + 1) == '.') {
                i--;
            } else {
                break;
            }
        }
        return s.substring(i + 1, dot);
    }

    /** 从闭括号向左找配对的开括号，跳过字符串字面量（{@code ?? ''} 里的引号不许干扰配对）。 */
    private static int openerOf(String s, int close) {
        char c = s.charAt(close);
        char o = c == ')' ? '(' : '[';
        int depth = 0;
        for (int i = close; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`') {
                int j = s.lastIndexOf(ch, i - 1);
                if (j < 0) return -1;
                i = j;
                continue;
            }
            if (ch == c) depth++;
            else if (ch == o && --depth == 0) return i;
        }
        return -1;
    }

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
     * 去掉 JS 的 {@code //} 行注释与 {@code /* *&#47;} 块注释，字符串里的内容原样保留——注释里的引号与括号不许干扰解析。
     * 私有复制自 ReachabilityTest.stripJsComments，唯一区别：块注释里的换行保留，命中行号才对得上。
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

    // ---------------- 清单与源码遍历（私有复制自 ReachabilityTest，不改它） ----------------

    private static Set<String> waivedKeys(List<Waiver> list) {
        Set<String> out = new TreeSet<>();
        for (Waiver w : list) out.add(w.key());
        return out;
    }

    private record SrcFile(String rel, String text) {}

    /** frontend/shell/src 下全部 .vue 与 .ts。少于 50 个就是扫描器没在读真仓。 */
    private static List<SrcFile> shellSources() {
        List<SrcFile> out = new ArrayList<>(sources(".vue", FRONTEND_SRC));
        out.addAll(sources(".ts", FRONTEND_SRC));
        if (out.size() < 50) {
            fail(FRONTEND_SRC + " 下只扫到 " + out.size() + " 个 .vue/.ts——先修扫描再看结果（user.dir="
                    + System.getProperty("user.dir") + "）");
        }
        return out;
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

    private static String read(Path f) {
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("读 " + f + " 失败", e);
        }
    }

    /**
     * 遍历真代码。跳过 target/ node_modules/ .claude/ worktrees/ dist/——构建产物与他人工作区不是本仓代码。
     * 与 ReachabilityTest 的区别：按<b>仓库相对路径</b>判跳过，不看绝对路径——
     * 仓库本身就可能被 checkout 在 …/.claude/worktrees/… 下（车道工作树），按绝对路径判会把整仓跳空。
     */
    private static List<SrcFile> sources(String suffix, String... relDirs) {
        Path root = repoRoot();
        List<SrcFile> out = new ArrayList<>();
        for (String rel : relDirs) {
            Path base = root.resolve(rel);
            if (!Files.isDirectory(base)) continue;
            try (var s = Files.walk(base)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String path = "/" + root.relativize(f).toString().replace('\\', '/');
                    if (path.contains("/target/") || path.contains("/node_modules/")
                            || path.contains("/.claude/") || path.contains("/worktrees/")
                            || path.contains("/dist/")) {
                        continue;
                    }
                    if (!path.endsWith(suffix)) continue;
                    out.add(new SrcFile(path.substring(1), read(f)));
                }
            } catch (Exception e) {
                throw new IllegalStateException("扫描 " + base + " 失败", e);
            }
        }
        return out;
    }
}
