package cn.hip.server;

import cn.hip.medtech.web.PathQcController;
import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyController.DiagnoseReq;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.BlockReq;
import cn.hip.medtech.web.PathologyProcessController.GrossingReq;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>v61 车道 C（2576 复核两条）：签发份数两口径正名 + 按科室导出从菜单走得到</h1>
 *
 * <h2>修复前的反向事实（v61 地基 78a3110，反驳者原话经主控实测坐实）</h2>
 * <ol>
 *   <li><b>同一块看板、同一个中文表头「签发份数」，在两组指标里是两个口径</b>：{@code WORKLOAD_DEPT} 的时间窗是
 *       {@code s.collected_at}（登记时刻），{@code issued} 只是 {@code count(*) filter (where s.report_issued_at is not null)}
 *       ——「本期<b>登记</b>的标本里、<b>截至查询那一刻</b>已签发的条数」（存量）；而 {@code REPORT_*} 系列锚
 *       {@code report_issued_at}，那里的「签发份数」才是本期签发量（流量）。后果可实测：同一个<b>已结束</b>的历史区间，
 *       今天查与下周查，科室那一行的「签发份数」会变大、「在办数」会变小——上期登记、本期才签发的标本被追加进上期那一行，
 *       一张已经发给科室做工作量考核的表因此不可复现。本类 {@link #stockColumnIsNotAFlowAndIsNamedAccordingly} 用一条
 *       「上期登记、本期签发」的标本把这件事钉死：它<b>不</b>计入上期的流量指标、却<b>计入</b>上期的存量列。</li>
 *   <li><b>按科室导出只有 E2E 调得到，用户从菜单永远走不到</b>：后端 {@code detailCsv(indicator, from, to, dept)} 早有
 *       {@code dept} 形参、{@code toCsv} 也会写「科室过滤：X」页脚，但全前端没有任何调用方传 dept——两个导出按钮都在
 *       指标头部、穿透抽屉里根本没有导出按钮。评委看完某科室明细、关掉抽屉点「导出明细」，拿到的是全科室的另一份，
 *       而抽屉顶部的 alert 恰恰写着「明细与汇总同时间窗、同锚点、同过滤条件——对不上账即为缺陷」。
 *       {@link #drawerExportCarriesTheDeptFilter} 从源码上钉住抽屉里有导出按钮且把 dept 传下去。</li>
 * </ol>
 *
 * <h2>时间纪律</h2>
 * 不写任何时间字面量：夹具时刻由端点按库端 {@code now()} 落；「上期」用 {@link BusinessDates#today()} 派生的相对日期窗表达，
 * 「上期登记」由一条 {@code update … collected_at = now() - interval} 的相对表达式造出（不是墙钟字面量）。事务回滚，库里不留痕。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V61QcCaliberTest {

    private static final String QC_VIEW = "frontend/shell/src/views/medtech/pathology/PathQcView.vue";
    private static final String CONTROLLER = "modules/medtech/src/main/java/cn/hip/medtech/web/PathQcController.java";

    private static final String CODE = "WORKLOAD_DEPT";
    /** 存量列：本期登记的标本里、截至查询那一刻已签发的条数——名字必须自带「本期登记中」，与流量列分开 */
    private static final String STOCK_COL = "issued_of_registered";

    @Autowired PathologyController pathology;
    @Autowired PathologyProcessController process;
    @Autowired PathologyReportController report;
    @Autowired PathQcController pathQc;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private Authentication doc;
    private long deptId;
    private long regId;
    private String deptName;

    @BeforeEach
    void setUp() {
        tag = "V61Q" + Long.toHexString(System.nanoTime());
        doc = userAuth(tag + "d1");
        deptName = "口径测试科" + tag;
        deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, deptName, tag + "D");
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, ?, 'F', current_date - 15000) returning id
                """, Long.class, tag + "P", "口径患者" + tag);
        regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
    }

    // =====================================================================================
    // (a) 存量列已正名，且确实是存量：上期登记、本期签发的标本不计上期流量、却计上期存量
    // =====================================================================================

    @Test
    void stockColumnIsNotAFlowAndIsNamedAccordingly() {
        // 一条走到签发的标本，随后把「登记时刻」改到 10 天前——签发时刻仍是此刻（库端 now()）。
        // 这正是「上期登记、本期才签发」的现实形态：月末结账时它还在办，下个月才签发。
        long sid = issuedSpecimen("s1");
        jdbc.update("update path_specimen set collected_at = now() - interval '10 days' where id = ?", sid);

        // 上期窗口：[今天-12, 今天-8]——登记落在窗内，签发（此刻）落在窗外
        String from = BusinessDates.today().minusDays(12).toString();
        String to = BusinessDates.today().minusDays(8).toString();

        // ① 存量：本期登记的这一条，截至查询此刻已签发 → 计入
        var ind = rows(ok(pathQc.indicators(from, to, CODE)), "indicators").get(0);
        var deptRows = rows(ind, "rows");
        var row = deptRows.stream().filter(r -> deptName.equals(r.get("dept_name"))).findFirst()
                .orElseGet(() -> fail("上期窗口里应有本科室一行（登记时刻落在窗内）：" + deptRows));
        assertEquals(1L, n(row.get("registered")), "登记时刻落在上期窗内");
        assertEquals(1L, n(row.get(STOCK_COL)),
                "存量列：本期登记的标本里、截至查询此刻已签发的条数——这一条算进来了");
        assertEquals(0L, n(row.get("in_progress")), "已签发就不再是在办");

        // ② 流量：REPORT_* 系列锚 report_issued_at，签发时刻在上期窗外 → 不计入
        //    这两个数在同一块看板上，此前同名同中文「签发份数」，评委无从分辨
        var flowInd = rows(ok(pathQc.indicators(from, to, "REPORT_ROUTINE")), "indicators").get(0);
        long flowIssued = totalIssued(rows(flowInd, "rows"));
        assertEquals(0L, flowIssued,
                "流量指标锚签发时刻：签发发生在此刻、不在上期窗内，上期的『签发份数』里不该有它——"
                        + "与存量列 1 并列，正是两个口径（修复前两列同名同中文，这个差异在屏幕上看不出来）");
        // 活的对照组：同一条标本，把窗口挪到「含此刻」，流量列就数得到它——证明上面那个 0 不是恒真
        String nowFrom = BusinessDates.today().minusDays(1).toString();
        String nowTo = BusinessDates.today().plusDays(1).toString();
        var flowNow = rows(ok(pathQc.indicators(nowFrom, nowTo, "REPORT_ROUTINE")), "indicators").get(0);
        assertTrue(totalIssued(rows(flowNow, "rows")) >= 1L,
                "对照组：窗口含签发时刻时，流量指标必须数得到这一条（否则上面的 0 只是空表恒真）");

        // ③ 名字与中文都已分开：存量列不叫 issued、中文自带「本期登记中」
        assertFalse(row.containsKey("issued"),
                "存量列不得再叫 issued——那是 REPORT_* 流量列的名字（修复前同名）：" + row.keySet());
        var backend = V59QcLabelsTest.backendLabels(read(CONTROLLER));
        assertEquals("本期登记中已签发", backend.get(STOCK_COL));
        assertEquals("本期登记中在办", backend.get("in_progress"));
        assertEquals("签发份数", backend.get("issued"), "流量列的中文不动——两列在表头上就能分开");

        // ④ 口径说明随体下发：caveat 里要能读到「存量 / 随查询时刻变化」这层意思
        String caveats = String.valueOf(ind.get("caveat")) + ind.get("note") + ind.get("anchorNote");
        assertTrue(caveats.contains("截至查询") || caveats.contains("存量"),
                "返回体要点明本列是存量、随查询时刻变化（否则导出的历史区间表不可复现而无人知晓）：" + caveats);
    }

    // =====================================================================================
    // (b) 穿透抽屉里能按当前科室导出——此前 dept 过滤只有 E2E 调得到
    // =====================================================================================

    @Test
    void drawerExportCarriesTheDeptFilter() {
        String src = stripComments(read(QC_VIEW));

        // 抽屉里有导出按钮，且它走的函数会带上当前 dept
        assertTrue(src.contains("exportDetailInDrawer"),
                "穿透抽屉里必须有「导出本次明细」——修复前抽屉里没有任何导出按钮，"
                        + "关掉抽屉去点指标头部那个拿到的是全科室的另一份");
        Matcher fn = Pattern.compile("function\\s+exportDetailInDrawer\\s*\\([^)]*\\)\\s*\\{(.{0,900}?)\\n\\}", Pattern.DOTALL)
                .matcher(src);
        assertTrue(fn.find(), "找不到 exportDetailInDrawer 的函数体");
        String bodyText = fn.group(1);
        assertTrue(bodyText.contains("detailDept"),
                "抽屉导出必须把当前科室过滤传下去（后端 detailCsv 的 dept 形参与「科室过滤：」页脚早就就绪）：" + bodyText);

        // 活的对照组：指标头部那个导出**不**传 dept（它导的就是全科室），两者不能混为一谈
        Matcher head = Pattern.compile("async function exportCsv\\([^)]*\\)\\s*\\{(.{0,900}?)\\n\\}", Pattern.DOTALL)
                .matcher(src);
        assertTrue(head.find(), "找不到 exportCsv 的函数体");
        assertFalse(head.group(1).contains("detailDept"),
                "指标头部的「导出明细（全科室）」不传 dept——这是对照组：若两个函数都传，说明扫描器没在分辨");

        // 后端两条路径的实际行为（E2E 已覆盖 HTTP 层，这里从控制器直调补单测）
        long sid = issuedSpecimen("d1");
        assertNotNull(sid);
        String from = BusinessDates.today().minusDays(1).toString();
        String to = BusinessDates.today().plusDays(1).toString();
        String filtered = pathQc.detailCsv(CODE, from, to, deptName);
        assertTrue(filtered.contains("科室过滤：" + deptName), "带 dept 的导出要在页脚写明过滤条件：" + filtered);
        assertFalse(pathQc.detailCsv(CODE, from, to, null).contains("科室过滤："), "不带 dept 时不写过滤页脚");
    }

    // =====================================================================================
    // 夹具（照抄 V60DeptDimensionTest：走真实端点，不直插业务行）
    // =====================================================================================

    /** collect → receive → grossing → diagnose → issue，全走真实端点，时刻由库端 now() 落 */
    private long issuedSpecimen(String suffix) {
        Long orderId = jdbc.queryForObject("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, status)
                values (?, ?, 'EXAM', 1, 'PATH', '病理检查', '次', 1, 100.00, 100.00, 'CHARGED') returning id
                """, Long.class, regId, "G" + tag + suffix);
        var c = pathology.collect(orderId, "标本" + suffix + " " + tag);
        assertEquals(0, c.getCode(), c.getMessage());
        String barcode = (String) c.getData().get("barcode");
        long sid = jdbc.queryForObject("select id from path_specimen where barcode = ?", Long.class, barcode);
        // REPORT_* 系列只统计 specimen_type='ROUTINE'（类别未填的历史标本不默认算常规，见指标 caveat），
        // 本类要拿流量指标做对照组，夹具必须显式落类别——否则「流量=0」是空表恒真而不是口径差异
        jdbc.update("update path_specimen set specimen_type = 'ROUTINE' where id = ?", sid);
        assertEquals(0, pathology.receive(barcode).getCode());
        var gr = process.grossing(new GrossingReq(sid, null, null, "灰白组织一块 " + tag, false, null,
                List.of(new BlockReq("肿物中心"))), doc);
        assertEquals(0, gr.getCode(), gr.getMessage());
        var dx = pathology.diagnose(barcode, new DiagnoseReq(null, "镜下见慢性炎细胞浸润 " + tag, "慢性炎症 " + tag), doc);
        assertEquals(0, dx.getCode(), dx.getMessage());
        var is = report.issue(sid, doc);
        assertEquals(0, is.getCode(), is.getMessage());
        return sid;
    }

    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR_OUTP")));
    }

    private static long totalIssued(List<Map<String, Object>> rows) {
        return rows.stream().mapToLong(r -> n(r.get("issued"))).sum();
    }

    private static long n(Object v) {
        return v instanceof Number num ? num.longValue() : 0L;
    }

    private static Map<String, Object> ok(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode(), r.getMessage());
        return r.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> body, String key) {
        Object v = body.get(key);
        assertNotNull(v, "返回体缺键 " + key + "：" + body.keySet());
        return (List<Map<String, Object>>) v;
    }

    private static String read(String rel) {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !(Files.isDirectory(root.resolve("frontend")) && Files.isDirectory(root.resolve("modules")))) {
            root = root.getParent();
        }
        assertNotNull(root, "找不到仓库根");
        try {
            return Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读不到 " + rel, e);
        }
    }

    /** 剥 HTML / 块 / 行注释——注释里的形态不算数（本仓三次源码扫描误判的统一处置） */
    private static String stripComments(String s) {
        s = s.replaceAll("(?s)<!--.*?-->", "");
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        return s.replaceAll("(?m)(^|\\s)//[^\\n]*", "$1");
    }
}
