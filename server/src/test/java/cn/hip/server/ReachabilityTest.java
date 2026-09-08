package cn.hip.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <h1>后端交付必须「可达」——把这条纪律做成机械断言</h1>
 *
 * <h2>这个类为什么存在</h2>
 * v52 花了一整版把 v48–v51 的前端欠账还清：12 个控制器一个前端都没有，
 * 其中病理 PIS 的两条菜单（V144 插的 168/169）**连 sys_role_menu 授权都没插**，
 * 于是菜单在库里、admin 的 {@code /auth/me} 里却一条都不下发，
 * 手输地址被 router 守卫踢回 {@code /dashboard}——**在、点不进去、也不报错**，躺了四个版本。
 * <p>
 * v52 补完，v53 立刻又欠上三笔：{@code EmrVersionController} /
 * {@code CountersignController} / {@code EmrTimelinessController} 零前端零菜单；
 * 外加 {@code emr.gate.timeliness} 建了开关没在 {@code sys_config} 登记，
 * 而 {@code PUT /api/config/{key}} 只 UPDATE 不 INSERT（影响 0 行时返回 1401「配置项不存在」），
 * 于是 block/off **两档根本调不动**。
 * <p>
 * 结论：<b>v52 治的是症状，欠账会每版重新积累</b>。人工检查已经证明不可靠——
 * 它只在有人专门去找的那一版有效。所以本类把「可达」拆成一条链，
 * <b>每次 CI 都跑</b>：
 * <pre>
 *   后端控制器 ──§1→ 前端有调用 ──§2→ 有菜单入口 ──§4→ 有角色授权
 *                                  └──§3→ 菜单 path 有对应路由（点进去不 404）
 *   后端控制器 ──§6→ 从某条菜单点进去的那一页（的组件树）真的在调它      ← v56 第二层
 *   三态 gate 键 ──§5→ 在 sys_config 登记（开关接上电）
 * </pre>
 * 链上断一环，功能就等于没交付；而这几种断法**全都不会让构建失败、也不会报错**，
 * 只会表现为「用户找不到 / 点不进去 / 调不动」。
 * <p>
 * §6 是 v55 核账（129 条 ★，0 可上调）逼出来的：挖出的缺陷全是同一类——<b>后端做了，前端够不着</b>，
 * 而 §1 抓不住它。§1 只保证「全前端<b>某个文件</b>里出现过这个控制器的路径字面量」，
 * 不保证「从菜单走到的那个页面」在调：页面在、API 被调用、§1 全绿，可医生从菜单点进去的那一页根本不调它。
 * §6 把「谁在调」按文件归属，再从菜单 → 路由 → 组件文件 → import 传递闭包一路走到调用点。
 *
 * <h2>每条断言都配一条探针</h2>
 * 「现在是绿的」与「永远不会红」在报告里长得一模一样，这正是人工检查失效的原因。
 * 所以 §2–§6 各带一条 {@code ...ActuallyBite()} / {@code ...IsBySegment...()} / {@code ...Distinguishes...()}：
 * 在事务里造出一条欠授权的菜单、一条断链的菜单、一个没人读的死开关、一个写错的档位值，
 * 再拿分段边界的反例喂给覆盖判定，<b>要求检测器当场点名</b>；方法结束整笔回滚，库里不留痕。
 * 探针红了说明断言本身坏了——那时候库里干净不干净根本不重要。
 * <p>
 * §1 的探针跑不进事务（要凭空长出/删掉前端文件），改由一次离线负向对照背书：
 * 把 v53 三个车道的前端目录从扫描面里摘掉，本节恰好点名那三个控制器、不多不少。
 * §6 的探针全在内存里做手术：真实树上剔掉唯一调用文件 / 拿掉盖住那一页的菜单，
 * 再合成孤儿文件、无菜单路由、父布局、import 环各喂一遍——不碰库、不碰文件。
 *
 * <h2>取件范围：全仓，无版本窗口（这一条是本类的硬约束）</h2>
 * v51 的 {@code V51CdssTest#newMigrations()} 曾写成「V152 及以后」而没有上界，
 * 结果 v53 的 V157–V159 一落盘就把它打红——<b>一个版本的测试去审后续版本的产物，
 * 红的不是被审者而是审者</b>，只好在 v53 回头给它补上界。
 * <p>
 * 本类是<b>全仓通用纪律</b>，不是某一版的验收单，所以：
 * <ul>
 *   <li>扫描面<b>永远是全仓全量</b>——所有 {@code @RestController}、所有 {@code sys_menu} 行、
 *       所有 gate 键。<b>代码里不许出现任何版本号区间</b>（{@link #thisTestItselfHasNoVersionWindow()}
 *       会机械地看住这一点）；</li>
 *   <li>随版本增长的只有<b>豁免清单</b>——新版本发现新的合理例外，就往清单里加一条<b>带理由</b>的；
 *       清单只增不改扫描面，所以本类永远不会因为「后来又出了新东西」而误红。</li>
 * </ul>
 *
 * <h2>豁免清单的三条规矩</h2>
 * <ol>
 *   <li><b>每条必须写为什么</b>。「天然没有前端」这种话不算理由，要写清是哪一类：
 *       机器对接、另有独立壳、样板骨架。{@link #waiverListItselfIsMaintained()} 会拒绝空理由与套话。</li>
 *   <li><b>不许有烂条目</b>。豁免键必须还能对上仓里真实存在的东西；控制器被改名或删掉之后，
 *       烂在清单里的那条会在将来悄悄豁免掉一个同名的新控制器。</li>
 *   <li><b>失败信息必须能照着修</b>。只说「不通过」的断言，下一个人只会把它注释掉。
 *       本类每条失败都点名「哪个控制器 / 哪条菜单 / 哪个 gate 键」缺什么、该补在哪、
 *       以及「确实是例外」时该往哪个清单里写。</li>
 * </ol>
 *
 * <h2>本类刻意<b>不</b>做的事</h2>
 * <ul>
 *   <li><b>不</b>断言菜单授权的角色码是否与控制器 {@code @PreAuthorize} 一致。
 *       授宽了是「菜单点得进、接口 403」，授窄了是「功能等于没交付」——两者都真实存在，
 *       但要机械判定得解析 SpEL 并跨类推导方法级收紧，误判率高于它能挡住的问题。
 *       这一条留在合版的人工核对（V156 的做法：逐条读 {@code @PreAuthorize} 再写注释）。</li>
 *   <li><b>不</b>要求每个控制器都有<b>自己的</b>菜单。很多控制器是页内组件的数据源（如开单页的 CDSS 预览），
 *       本来就不该有自己的导航入口。§6 要求的只是：它被<b>某个</b>菜单页的组件树调到——
 *       开单页有菜单、开单页 import 了 CDSS 预览组件、预览组件在调它，这就够了。</li>
 *   <li><b>不</b>断言运行时语义。§6 抓得住「从菜单走到的页面根本没调这个控制器」，<b>抓不住</b>
 *       「页面调了但用户手里没有它要的 id」（994 型：版本页要人手填 outp_emr.id）与
 *       「页面调了但列表 where 条件把要找的记录永久排除」（2553 型）。那两类靠 {@code tools/e2e-v55-reach.py}
 *       那样的端到端基线；静态断言与 E2E 各管一层，不互相替代。</li>
 * </ul>
 */
@SpringBootTest
class ReachabilityTest {

    // ==================================================================================
    // 豁免清单（每条都要有理由；理由要写清「哪一类例外」，不是「就是没有」）
    // ==================================================================================

    /** 一条豁免：{@code key} 是被豁免对象的标识，{@code why} 是<b>为什么它天然不该被要求</b>。 */
    private record Waiver(String key, String why) {}

    /**
     * §1 豁免：这些 {@code @RestController} 天然不该在 {@code frontend/shell} 里有调用。
     * 键 = 类级 {@code @RequestMapping} 路径（没有类级映射的控制器写方法级公共前缀）。
     */
    private static final List<Waiver> CONTROLLER_WAIVERS = List.of(
            new Waiver("/api/integration/hl7",
                    "机器对接：HL7 V2 的 ORU^R01 入站口，对端是 LIS/RIS 设备网关（HTTP 与 2575 端口的 MLLP 共用同一处理链），"
                            + "没有也不该有人工页面。真实覆盖在 tools/e2e-integration.py 与 tools/e2e-mllp.py。"),
            new Waiver("/api/portal",
                    "患者端另有独立壳：/portal 页面走 portalClient（baseURL='/api/portal'），"
                            + "页面里的调用字面量因此是 '/my/lab-reports' 这种**已经去掉 /portal 前缀**的相对路径，"
                            + "而本节的取词法是拿 @RequestMapping 全路径去前端找——机制上就看不见，不是真的没前端。"
                            + "真实覆盖在 PortalSecurityTest 与 tools/e2e-phase2931.py。"),
            new Waiver("/api/impl/template",
                    "实施样板骨架（ADR-0003）：impl/template-hospital 是给实施方按院复制改造的模板（ping/notes 两个示例端点），"
                            + "主壳**不应该**引用它；它哪天真在 frontend/shell 里有了调用，说明样板被写进了主干，那才是问题。"));

    /**
     * §2 豁免：这些前端路由天然不进 {@code sys_menu}。
     * 键 = 归一化后的路由绝对路径。
     */
    private static final List<Waiver> ROUTE_WAIVERS = List.of(
            new Waiver("/",
                    "根路径只做 redirect 到 /dashboard，不是一个页面；它在 router 的 ALWAYS_ALLOWED 里，不受菜单授权约束。"),
            new Waiver("/dashboard",
                    "驾驶舱是登录后的落地页，人人可见。放进 sys_menu 会被「模块停用清菜单」的逻辑波及，"
                            + "一旦被清掉，router 守卫的兜底跳转目标就没了。"),
            new Waiver("/login",
                    "登录页在拿到菜单之前就要能渲染——它若依赖 /auth/me 下发的菜单，就成了先有鸡还是先有蛋。"),
            new Waiver("/print",
                    "打印页由业务页 window.open 带参数打开，不是导航入口；它在 ALWAYS_ALLOWED 里。"),
            new Waiver("/portal",
                    "患者端登录页：独立会话（localStorage 的 hip_portal_token），根本不读 /auth/me 的菜单。"),
            new Waiver("/portal/home",
                    "患者端首页：导航由 PortalHomeView 自己画，院内 sys_menu 与它无关（且患者不该看见院内菜单树）。"));

    /** §3 豁免：菜单 path 允许没有精确路由的情形。目前一条都不需要——DIR 目录行本就按 type 排除。 */
    private static final List<Waiver> MENU_WAIVERS = List.of();

    /** §5 豁免：允许不在 {@code sys_config} 登记、或取值不是三态的 gate 键。目前一条都不需要。 */
    private static final List<Waiver> GATE_WAIVERS = List.of();

    /**
     * §6 豁免：前端<b>有</b>调用、但天然不从任何菜单页（也不从 router 的 {@code ALWAYS_ALLOWED} 页）走到的控制器。
     * 键 = 与 {@link #CONTROLLER_WAIVERS} 同口径的控制器基路径。
     *
     * <p>{@link #CONTROLLER_WAIVERS}（§1）自动带入 §6——前端一行都没有的，当然也从菜单走不到，不必重复登记；
     * {@link #menuReachWaiverListItselfIsMaintained()} 会拒绝重复条目，也会拒绝「此刻其实走得到」的死条目。
     * <p>共用布局里调的 API（{@code /api/auth} 由 MainLayout → stores/auth、ChangePasswordDialog 调）<b>不需要豁免</b>：
     * MainLayout 挂在 '/' 上，所有业务页都在它里面渲染，§6 把父路由的组件并进每条子路由的闭包——
     * 这是渲染树的事实，不是豁免。目前一条都不需要（v55 已把「后端做了前端够不着」的四类收口）。
     */
    private static final List<Waiver> MENU_REACH_WAIVERS = List.of();

    /** 三态 gate 的合法档位。坏值会被各服务静默回落到 warn——档位调不动且不报错。 */
    private static final Set<String> GATE_TIERS = Set.of("off", "warn", "block");

    @Autowired
    private JdbcTemplate jdbc;

    // ==================================================================================
    // §0 元断言：先看住清单本身，再让清单去豁免别人
    // ==================================================================================

    /**
     * 豁免清单必须：键不重复、理由不是套话、且键还能对上仓里真实存在的东西。
     *
     * <p>第三条（烂条目）是最要紧的：控制器改了名、路由删了、菜单换了 path 之后，
     * 留在清单里的旧键<b>什么都不再豁免</b>，但会一直躺着，直到某天一个新对象恰好叫同一个名字，
     * 被它悄悄放行。清单里的每一条都必须此刻仍然在管着一个真实存在的东西。
     */
    @Test
    void waiverListItselfIsMaintained() {
        List<String> bad = new ArrayList<>();
        checkWaiverShape("CONTROLLER_WAIVERS", CONTROLLER_WAIVERS, bad);
        checkWaiverShape("ROUTE_WAIVERS", ROUTE_WAIVERS, bad);
        checkWaiverShape("MENU_WAIVERS", MENU_WAIVERS, bad);
        checkWaiverShape("GATE_WAIVERS", GATE_WAIVERS, bad);

        Set<String> controllerKeys = new TreeSet<>(scanControllers().keySet());
        for (Waiver w : CONTROLLER_WAIVERS) {
            if (!controllerKeys.contains(w.key())) {
                bad.add("CONTROLLER_WAIVERS 里的 \"" + w.key() + "\" 已对不上任何 @RestController"
                        + "（控制器被改名或删除了？）。烂条目请直接删掉——留着只会在将来悄悄豁免一个同名的新控制器。");
            }
        }
        Set<String> routes = frontendRoutes();
        for (Waiver w : ROUTE_WAIVERS) {
            if (!routes.contains(w.key())) {
                bad.add("ROUTE_WAIVERS 里的 \"" + w.key() + "\" 已对不上 router/ 里的任何路由。烂条目请直接删掉。");
            }
        }
        Set<String> menuPaths = new TreeSet<>();
        for (Map<String, Object> row : jdbc.queryForList("select coalesce(path, '') as path from sys_menu")) {
            menuPaths.add(String.valueOf(row.get("path")));
        }
        for (Waiver w : MENU_WAIVERS) {
            if (!menuPaths.contains(w.key())) {
                bad.add("MENU_WAIVERS 里的 \"" + w.key() + "\" 已对不上任何 sys_menu.path。烂条目请直接删掉。");
            }
        }
        Set<String> gateKeys = new TreeSet<>(scanGateKeysInCode());
        gateKeys.addAll(registeredGateKeys().keySet());
        for (Waiver w : GATE_WAIVERS) {
            if (!gateKeys.contains(w.key())) {
                bad.add("GATE_WAIVERS 里的 \"" + w.key() + "\" 既不在代码里、也不在 sys_config 里。烂条目请直接删掉。");
            }
        }

        if (!bad.isEmpty()) {
            fail("""
                    【豁免清单自身不合格】共 %d 条：

                    %s

                    豁免清单是这套断言唯一允许随版本增长的东西，所以它自己必须先干净：
                    键唯一、理由说清是哪一类例外、条目还在管着真实存在的对象。
                    """.formatted(bad.size(), String.join("\n", bad)));
        }
    }

    /**
     * 本类自己不许写成「V152 及以后」那种带版本窗口的取件范围。
     *
     * <p>v51 就是这么翻的车：{@code newMigrations()} 写成 {@code >= 152} 无上界，
     * v53 的迁移一落盘就把 v51 的测试打红，红的不是被审者而是审者。
     * 本类是全仓通用纪律，扫描面必须恒为全量；随版本变的只能是豁免清单。
     * 这条断言只看<b>真代码行</b>——注释里复述这段历史（含 V144/V156/V161 这些版本号）是说明边界，恰恰要鼓励。
     */
    @Test
    void thisTestItselfHasNoVersionWindow() {
        Path self = repoRoot().resolve("server/src/test/java/cn/hip/server/ReachabilityTest.java");
        String text = read(self);
        // 迁移号/版本号都是三位数（V152、V161…）。二位数的比较是本类自己的长度/数量守卫，不是取件范围。
        Pattern window = Pattern.compile("(>=|<=|>|<)\\s*\\d{3,}");
        List<String> hits = new ArrayList<>();
        int lineNo = 0;
        for (String raw : text.split("\n")) {
            lineNo++;
            String line = raw.strip();
            if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) continue;
            if (window.matcher(line).find()) hits.add("第 " + lineNo + " 行：" + line);
        }
        if (!hits.isEmpty()) {
            fail("""
                    【本断言自己有取件范围】发现 %d 处版本号/序号区间比较：

                    %s

                    这个类是全仓通用纪律，不是某一版的验收单：扫描面必须恒为全量。
                    要放过某个对象，请往对应的豁免清单里加一条带理由的条目，而不是给扫描面加上下界——
                    加了下界，下一版的产物就会把这一版的测试打红（v51 的 newMigrations() 就是这么翻的车）。
                    """.formatted(hits.size(), String.join("\n", hits)));
        }
    }

    // ==================================================================================
    // §1 每个 @RestController 都要有前端在调它
    // ==================================================================================

    /**
     * 控制器 → 前端调用。断的是「后端交付了、前端一行都没有」这一环。
     *
     * <p>判据：把控制器的每个端点全路径去掉 {@code /api} 前缀、截到第一个 {@code {} 占位符之前，
     * 得到若干「前缀词」；前端只要有一处 <b>API 调用点</b>的字面量命中其中任意一个，就算可达。
     * <p>取词只认调用点（{@code client.get('/x')}）与 base 常量（{@code const BASE = '/x'}），
     * <b>不认满页面的任意字符串</b>——否则 {@code router.push('/inpatient/...')} 这种导航字面量
     * 会把一个零前端的控制器判成绿的，那比不查还糟。
     */
    @Test
    void everyRestControllerIsCalledFromTheFrontend() {
        Map<String, List<String>> controllers = scanControllers();
        Set<String> literals = frontendApiLiterals();
        Set<String> waived = waivedKeys(CONTROLLER_WAIVERS);

        List<String> unreachable = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : new TreeMap<>(controllers).entrySet()) {
            String base = e.getKey();
            if (waived.contains(base)) continue;
            if (!anyLiteralHits(e.getValue(), literals)) {
                unreachable.add("  · %s\n      源文件：%s\n      前端要找的字面量形如：client.get('%s/...')"
                        .formatted(base, controllerFiles.get(base), stripApi(base)));
            }
        }
        if (!unreachable.isEmpty()) {
            fail("""
                    【后端交付了、前端一行都没有】%d 个 @RestController 在 frontend/shell/src 里找不到任何调用：

                    %s

                    该怎么修（二选一）：
                      A. 补前端：在 frontend/shell/src/views/ 下建页面，用 client.get/post 调上面列出的路径
                         （client 的 baseURL 是 '/api'，所以调用时**不带** /api 前缀），
                         并在 frontend/shell/src/router/index.ts 注册路由；
                         菜单不要自己插 sys_menu（id 易撞），交合版统一登记。
                      B. 确实是例外：往本类的 CONTROLLER_WAIVERS 里加一条，**并写清是哪一类例外**
                         （机器对接 / 另有独立壳 / 样板骨架）。没有理由的豁免等于把断言注释掉。

                    本节只认「调用点字面量」（client.get('...')、const BASE = '...'、url: '...'）。
                    如果页面确实在调、但 URL 是本节看不见的方式拼出来的（例如整段路径都由变量拼装），
                    请把 base 前缀提成一个 const BASE = '/xxx' 常量——顺手也让人一眼看出这页在调谁。
                    """.formatted(unreachable.size(), String.join("\n", unreachable)));
        }
    }

    // ==================================================================================
    // §2 每条前端路由都要有菜单入口
    // ==================================================================================

    /**
     * 前端路由 → 菜单入口。断的是「页面写好了、导航里没有入口」这一环。
     *
     * <p>没有菜单不只是「不好找」：router 守卫按 {@code /auth/me} 下发的菜单放行
     * （{@code to.path === p || to.path.startsWith(p + '/')}），菜单树里没有任何一条能盖住这条 path，
     * <b>手输地址会被踢回 /dashboard</b>，而且不报错——表现与「功能没做」完全一样。
     *
     * <h3>判据为什么是「落在某条 MENU 之下」而不是「逐字有一条自己的菜单」</h3>
     * 两头都试过，两头都不对：
     * <ul>
     *   <li><b>逐字精确匹配</b>过严：钻取页、明细页（如审签工作台里点进去的 {@code /check}）
     *       本来就是从父页面点进去的，硬要它们各有一条导航菜单，只会逼后人往豁免清单里乱塞；</li>
     *   <li><b>照搬守卫的前缀匹配</b>过松：守卫认的菜单里<b>包含 DIR 目录行</b>，
     *       而 {@code /inpatient} 这种 DIR 会把 {@code /inpatient/**} 全部盖住——
     *       v53 三个新页面在这条判据下会全绿，等于没查。</li>
     * </ul>
     * 取中：只让 <b>{@code type='MENU'} 的行</b>参与覆盖判定，DIR 不算。于是
     * {@code /inpatient/countersign/check} 会在「审签工作台」有了菜单之后自动算通过，
     * 而 {@code /inpatient/timeliness} 这种上面一条 MENU 都没有的，必须自己拿一条。
     */
    @Test
    void everyFrontendRouteHasAMenuEntry() {
        Set<String> routes = frontendRoutes();
        List<String> menuPaths = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select path from sys_menu where type = 'MENU' and enabled and path is not null and path <> ''")) {
            menuPaths.add(String.valueOf(row.get("path")));
        }
        Set<String> waived = waivedKeys(ROUTE_WAIVERS);

        List<String> orphans = new ArrayList<>();
        for (String r : routes) {
            if (r.isEmpty() || waived.contains(r)) continue;
            if (!coveredByMenu(r, menuPaths)) orphans.add("  · " + r);
        }
        if (!orphans.isEmpty()) {
            fail("""
                    【页面在、导航里没有入口】%d 条前端路由上面没有任何一条 sys_menu 的 MENU 行盖住：

                    %s

                    这不只是「不好找」——router 守卫按 /auth/me 下发的菜单放行，
                    菜单树里没有能盖住这条 path 的行时，手输地址会被踢回 /dashboard，**且不报错**，
                    表现与「功能压根没做」一模一样（v52 挖出的病理 PIS 正是此形态，躺了四个版本）。

                    该怎么修（二选一）：
                      A. 补菜单：由**合版**在一份新迁移里 insert sys_menu，
                         并在同一份迁移里 insert sys_role_menu 授权（见 §4；只插菜单不插授权 = 白插）。
                         菜单 id 由合版统一分配，车道不要自己插——并行车道各自插必然撞主键。
                         注：只要**父页面**拿到了 MENU 行，它底下的钻取/明细子路由就自动算通过，
                         不必给每个子页面都开一条菜单。
                      B. 确实是例外：往本类的 ROUTE_WAIVERS 里加一条，并写清为什么这条路由不该进导航
                         （落地页 / 登录页 / 打印页 / 患者端独立壳 …）。

                    DIR 目录行不参与覆盖判定：/inpatient 这类目录会把 /inpatient/** 全盖住，
                    认它就等于这条断言永远不会红。
                    """.formatted(orphans.size(), String.join("\n", orphans)));
        }
    }

    /** 路由是否落在某条菜单之下：逐字相等，或者是它的<b>子路径</b>（必须带上那一杠，见探针）。 */
    private static boolean coveredByMenu(String route, List<String> menuPaths) {
        for (String p : menuPaths) {
            if (route.equals(p) || route.startsWith(p + "/")) return true;
        }
        return false;
    }

    /**
     * §2 的探针：覆盖判定的边界必须是<b>路径分段</b>，不是裸字符串前缀。
     *
     * <p>把 {@code startsWith(p + "/")} 写成 {@code startsWith(p)} 是这类检查的经典坑：
     * 菜单 {@code /cdss} 会顺手盖住毫不相干的 {@code /cdssomething}，
     * 于是一个真正没入口的页面被判成绿的——比不查更坏，因为它给出了「已经查过」的假象。
     */
    @Test
    void menuCoverageIsBySegmentNotByRawPrefix() {
        assertTrue(coveredByMenu("/cdss", List.of("/cdss")), "逐字相等必须算覆盖");
        assertTrue(coveredByMenu("/cdss/allergy-review", List.of("/cdss")), "子路径必须算覆盖");
        assertFalse(coveredByMenu("/cdssomething", List.of("/cdss")),
                "裸字符串前缀不算覆盖——差一杠就是另一个页面，判成绿的等于放走一个真缺口");
        assertFalse(coveredByMenu("/cdss", List.of("/cdss/allergy-review")), "子菜单盖不住父路径");
        assertFalse(coveredByMenu("/anything", List.of()), "一条菜单都没有时不许算覆盖");
    }

    // ==================================================================================
    // §3 每条菜单的 path 都要有对应前端路由
    // ==================================================================================

    /**
     * 菜单 path → 前端路由。断的是「菜单点得到、点进去 404」这一环。
     *
     * <p>只查 {@code type='MENU'} 的行：{@code DIR} 是目录（只承载子菜单，本就没有页面），
     * {@code BUTTON} 是按钮权限位（{@code path} 为空）。
     */
    @Test
    void everyMenuPathHasAFrontendRoute() {
        List<String> broken = brokenMenuPaths();
        if (!broken.isEmpty()) {
            fail("""
                    【菜单点得到、点进去 404】%d 条 sys_menu 的 path 在前端路由表里不存在：

                    %s

                    该怎么修：
                      A. 补路由：在 frontend/shell/src/router/index.ts 的 '/' 子路由里加一行
                         { path: '<去掉开头斜杠的菜单 path>', component: () => import('../views/...') }；
                      B. 或者改菜单 path 与已有路由逐字对齐（**逐字**——差一个横杠就是 404）；
                      C. 确实是例外：往 MENU_WAIVERS 里加一条并写明理由。

                    注：本节按「router 目前是两层（根 '/' + children）」解析路由；
                    若将来出现更深的嵌套，请同步改 frontendRoutes()，不要靠往豁免清单里塞条目绕过。
                    """.formatted(broken.size(), String.join("\n", broken)));
        }
    }

    /** path 在 router 里找不到（或 type=MENU 却没有 path）的菜单行。 */
    private List<String> brokenMenuPaths() {
        Set<String> routes = frontendRoutes();
        Set<String> waived = waivedKeys(MENU_WAIVERS);
        List<String> broken = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select id, name, coalesce(path, '') as path from sys_menu "
                        + "where type = 'MENU' and enabled order by id")) {
            String path = String.valueOf(row.get("path"));
            if (waived.contains(path)) continue;
            if (path.isEmpty()) {
                broken.add("  · 菜单 #%s「%s」：type=MENU 却没有 path".formatted(row.get("id"), row.get("name")));
            } else if (!routes.contains(path)) {
                broken.add("  · 菜单 #%s「%s」→ %s（router 里没有这条路由）"
                        .formatted(row.get("id"), row.get("name"), path));
            }
        }
        return broken;
    }

    /** §3 的探针：事务内造一条 path 指向不存在路由的菜单，检测器必须点名；随后整笔回滚。 */
    @Test
    @Transactional
    void brokenMenuPathDetectorActuallyBites() {
        long canaryId = 990003L;
        String nowhere = "/__canary_nowhere";
        assertFalse(frontendRoutes().contains(nowhere), "探针路径不该真的存在于 router 里，否则这条探针没意义");
        jdbc.update("insert into sys_menu(id, parent_id, name, type, path, perm, icon, sort_no) "
                + "values (?, null, '探针·断链菜单（事务内，将回滚）', 'MENU', ?, null, null, 0)", canaryId, nowhere);
        assertTrue(brokenMenuPaths().stream().anyMatch(s -> s.contains(nowhere)),
                "断链菜单检测器没抓到探针——那么「菜单点得到、点进去 404」这一类它同样抓不到");
    }

    // ==================================================================================
    // §4 每条菜单都要有 sys_role_menu 授权，且 ADMIN 必须看得见
    // ==================================================================================

    /**
     * 菜单 → 角色授权。断的是「菜单在库里、谁也看不见」这一环。
     *
     * <p>两半都要断：
     * <ol>
     *   <li><b>一条授权都没有</b>——菜单对所有人都不存在，等于没插；</li>
     *   <li><b>ADMIN 看不见</b>——V1 的种子是「ADMIN cross join sys_menu」，
     *       只覆盖它执行当时存在的 1–4 号菜单，<b>不会追认后来插的菜单</b>。
     *       后插的菜单如果漏了 ADMIN，连管理员都演示不了这个功能（v52 的病理菜单正是如此）。</li>
     * </ol>
     * DIR 目录行同样要查：目录没授权，它底下的子菜单在导航树里会被一起收掉。
     */
    @Test
    void everyMenuRowIsGrantedToSomeRoleAndToAdmin() {
        List<String> ungranted = ungrantedMenus();
        List<String> noAdmin = menusAdminCannotSee();
        if (!ungranted.isEmpty() || !noAdmin.isEmpty()) {
            fail("""
                    【菜单在、点不进去、也不报错】未授权 %d 条，ADMIN 看不见 %d 条：

                    %s%s

                    机制：/auth/me 的菜单来自「用户角色 → sys_role_menu → sys_menu」的连接，
                    而 V1 的种子只是「ADMIN cross join sys_menu」——它只覆盖执行当时存在的菜单，
                    **不会追认后来插的任何一条**。插了 sys_menu 不插 sys_role_menu，
                    菜单对所有人都不存在，手输地址还会被 router 守卫踢回 /dashboard，全程无报错。
                    v52 实测：V144 插的病理菜单 168/169 就这么躺了四个版本。

                    该怎么修：在插 sys_menu 的**同一份迁移**里补上授权，角色码逐条读控制器的
                    @PreAuthorize（授宽了是「菜单点得进、接口 403」，授窄了是功能等于没交付）：

                      insert into sys_role_menu (role_id, menu_id)
                      select r.id, <menu_id> from sys_role r where r.code in ('ADMIN', ...)
                      on conflict do nothing;

                    ADMIN 那一份不是可选项：管理员是验收与演示唯一保证能进所有页面的角色。
                    """.formatted(ungranted.size(), noAdmin.size(),
                    ungranted.isEmpty() ? "" : String.join("\n", ungranted) + "\n",
                    noAdmin.isEmpty() ? "" : String.join("\n", noAdmin)));
        }
    }

    /** 一条 sys_role_menu 都没有的启用菜单。 */
    private List<String> ungrantedMenus() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select m.id, m.type, m.name, coalesce(m.path, '') as path
                from sys_menu m
                where m.enabled
                  and not exists (select 1 from sys_role_menu rm where rm.menu_id = m.id)
                order by m.id
                """)) {
            out.add("  · 菜单 #%s [%s]「%s」%s —— 一条 sys_role_menu 都没有"
                    .formatted(row.get("id"), row.get("type"), row.get("name"), row.get("path")));
        }
        return out;
    }

    /** 有授权、但没给 ADMIN 的启用菜单。 */
    private List<String> menusAdminCannotSee() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select m.id, m.type, m.name, coalesce(m.path, '') as path
                from sys_menu m
                where m.enabled
                  and exists (select 1 from sys_role_menu rm where rm.menu_id = m.id)
                  and not exists (
                        select 1 from sys_role_menu rm
                        join sys_role r on r.id = rm.role_id
                        where rm.menu_id = m.id and r.code = 'ADMIN')
                order by m.id
                """)) {
            out.add("  · 菜单 #%s [%s]「%s」%s —— 有授权但没给 ADMIN"
                    .formatted(row.get("id"), row.get("type"), row.get("name"), row.get("path")));
        }
        return out;
    }

    /**
     * §4 的探针：证明这条断言现在是绿的，是因为库里真的没有欠授权的菜单，<b>不是因为断言是空的</b>。
     *
     * <p>做法：事务内造一条谁也没授权的菜单，再造一条只授给非 ADMIN 角色的菜单，
     * 两个检测器都必须点名它们；方法结束整笔回滚，库里不留痕。
     * 「绿的断言」与「不会红的断言」长得一模一样，只有探针能把两者分开。
     */
    @Test
    @Transactional
    void menuGrantDetectorsActuallyBite() {
        long noGrant = 990001L;
        long noAdminGrant = 990002L;
        jdbc.update("insert into sys_menu(id, parent_id, name, type, path, perm, icon, sort_no) "
                + "values (?, null, '探针·零授权（事务内，将回滚）', 'MENU', '/__canary_no_grant', null, null, 0)",
                noGrant);
        jdbc.update("insert into sys_menu(id, parent_id, name, type, path, perm, icon, sort_no) "
                + "values (?, null, '探针·无ADMIN（事务内，将回滚）', 'MENU', '/__canary_no_admin', null, null, 0)",
                noAdminGrant);
        jdbc.update("insert into sys_role_menu(role_id, menu_id) "
                + "select r.id, ? from sys_role r where r.code <> 'ADMIN'", noAdminGrant);

        assertTrue(ungrantedMenus().stream().anyMatch(s -> s.contains("#" + noGrant)),
                "零授权菜单检测器没抓到探针——那么它对真实的欠授权菜单同样抓不到，这条断言是空的");
        assertTrue(menusAdminCannotSee().stream().anyMatch(s -> s.contains("#" + noAdminGrant)),
                "「ADMIN 看不见」检测器没抓到探针——v52 的病理菜单正是这个形态，抓不到等于没查");
        assertFalse(ungrantedMenus().stream().anyMatch(s -> s.contains("#" + noAdminGrant)),
                "有授权的菜单不该被算成零授权——两个检测器的口径不能糊在一起");
    }

    // ==================================================================================
    // §5 每个三态 gate 键都要在 sys_config 里登记
    // ==================================================================================

    /**
     * gate 键 → {@code sys_config} 登记。断的是「建了开关、开关没接上电」这一环。
     *
     * <p>{@code PUT /api/config/{key}} 是 <b>update</b> 不是 upsert：键不在 {@code sys_config} 里时
     * 影响 0 行、返回 1401「配置项不存在」。院方拿着手册去调档位会调不动，
     * 而代码侧 {@code ConfigReader.get(key, "warn")} 读不到值就回落默认——
     * 结果是 <b>block 与 off 两档不可达</b>，而系统表现得一切正常。v53 的 {@code emr.gate.timeliness}
     * 就是这样，靠人工复核才发现（后来由 V161 补登记）。
     *
     * <p>三条一起断：
     * <ol>
     *   <li>代码里读的键，{@code sys_config} 里必须有——否则档位调不动；</li>
     *   <li>{@code sys_config} 里的 gate 键，代码里必须有人读——死开关会让人以为调了有用；</li>
     *   <li>出厂值必须是 off/warn/block 之一——种子里写错一个字，各服务会静默回落 warn。</li>
     * </ol>
     */
    @Test
    void everyGateKeyIsRegisteredInSysConfig() {
        GateProblems p = gateProblems();
        List<String> missing = p.missing();
        List<String> dead = p.dead();
        List<String> badTier = p.badTier();
        if (!missing.isEmpty() || !dead.isEmpty() || !badTier.isEmpty()) {
            fail("""
                    【建了开关，开关没接上电】未登记 %d 个，死开关 %d 个，出厂值非法 %d 个：

                    %s%s%s
                    机制：PUT /api/config/{key} 是 update 不是 upsert——键不在 sys_config 里时影响 0 行、
                    返回 1401「配置项不存在」，院方按手册调档位调不动；而代码侧 ConfigReader 读不到值就回落默认档，
                    于是 block 与 off **两档不可达，且系统表现得一切正常**。v53 的 emr.gate.timeliness 就是这样，
                    靠人工复核才发现（后由 V161 补登记）。

                    该怎么修：在建这个 gate 的**同一份迁移**里登记出厂档位，别留到下一版：

                      insert into sys_config (cfg_key, cfg_value, remark)
                      values ('<gate 键>', 'warn', '<这个 gate 管什么；block 档拦什么>')
                      on conflict (cfg_key) do nothing;

                    「死开关」那一类反过来：sys_config 里有、代码里没人读——院方调了以为生效，其实什么都没变。
                    要么把读取接上，要么把这行种子删掉（并说明为什么曾经有）。
                    """.formatted(missing.size(), dead.size(), badTier.size(),
                    missing.isEmpty() ? "" : String.join("\n", missing) + "\n",
                    dead.isEmpty() ? "" : String.join("\n", dead) + "\n",
                    badTier.isEmpty() ? "" : String.join("\n", badTier) + "\n"));
        }
    }

    private record GateProblems(List<String> missing, List<String> dead, List<String> badTier) {}

    /** 三个方向一次算完：代码有库里没有、库里有代码没人读、出厂值不是三态之一。 */
    private GateProblems gateProblems() {
        Set<String> inCode = scanGateKeysInCode();
        Map<String, String> inDb = registeredGateKeys();
        Set<String> waived = waivedKeys(GATE_WAIVERS);

        List<String> missing = new ArrayList<>();
        for (String k : new TreeSet<>(inCode)) {
            if (!waived.contains(k) && !inDb.containsKey(k)) {
                missing.add("  · " + k + " —— 代码里在读，sys_config 里没有这一行");
            }
        }
        List<String> dead = new ArrayList<>();
        for (String k : new TreeSet<>(inDb.keySet())) {
            if (!waived.contains(k) && !inCode.contains(k)) {
                dead.add("  · " + k + " —— sys_config 里登记了，但全仓没有一行代码读它");
            }
        }
        List<String> badTier = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(inDb).entrySet()) {
            if (!waived.contains(e.getKey()) && !GATE_TIERS.contains(e.getValue())) {
                badTier.add("  · %s = '%s' —— 不是 off/warn/block".formatted(e.getKey(), e.getValue()));
            }
        }
        return new GateProblems(missing, dead, badTier);
    }

    /**
     * §5 的探针：同样是把「绿的」与「不会红的」分开。
     *
     * <p>事务内往 {@code sys_config} 塞一个没人读的 gate 键、并把一个真键的档位改成非法值，
     * 「死开关」与「出厂值非法」两个检测器都必须点名；方法结束整笔回滚。
     * 「代码有库里没有」那一路没法在事务里造（要凭空长出一行读配置的 Java 代码），
     * 它由 {@code emr.gate.timeliness} 的真实历史背书——v53 复核实测过一次红。
     */
    @Test
    @Transactional
    void gateDetectorsActuallyBite() {
        String canary = "zzz.gate.canary";
        jdbc.update("insert into sys_config(cfg_key, cfg_value, remark) values (?, 'warn', '探针（事务内，将回滚）')",
                canary);
        jdbc.update("update sys_config set cfg_value = 'blocked' where cfg_key = 'emr.gate.discharge'");

        GateProblems p = gateProblems();
        assertTrue(p.dead().stream().anyMatch(s -> s.contains(canary)),
                "「死开关」检测器没抓到探针键——院方调了以为生效、其实什么都没变的那一类问题它查不出来");
        assertTrue(p.badTier().stream().anyMatch(s -> s.contains("emr.gate.discharge")),
                "「出厂值非法」检测器没抓到 'blocked' 这个笔误——各服务遇到坏值会静默回落 warn，"
                        + "档位从此调不动且不报错");
        assertTrue(p.missing().isEmpty(), "探针只该触发另外两路；missing 被连带打红说明三路口径糊在一起了");
    }

    // ==================================================================================
    // §6 每个控制器都要从某条菜单点进去的那一页走得到（v56：可达性第二层）
    // ==================================================================================

    /**
     * 菜单 → 路由 → 组件树 → 调用点。断的是「页面在、API 被调、可从菜单点进去的那一页根本不调它」这一环。
     *
     * <p>v55 核账挖出的全是这一类，而 §1 抓不住：§1 只保证「全前端<b>某个文件</b>里出现过这个控制器的
     * 路径字面量」。字面量在一个没被任何路由引用的孤儿组件里、或在一个没有菜单的页面里，§1 照样绿。
     *
     * <h3>判据</h3>
     * 对每个非豁免控制器，至少存在一条<b>入口路由</b>，其组件树的 import 传递闭包里有调用点字面量命中它
     * （命中判据与 §1 同一个 {@link #anyLiteralHits}，取词法同一组 {@link #API_LITERALS}）。其中：
     * <ul>
     *   <li><b>入口路由</b> = 落在某条 {@code sys_menu} MENU 行之下的路由（与 §2 的 {@link #coveredByMenu}
     *       同口径：子路由继承父菜单的覆盖）∪ router 自己放在菜单授权之外的 {@code ALWAYS_ALLOWED} 页
     *       （落地页 /dashboard、登录页、打印页——它们对每个登录用户都可达）。后者从 router 源码里取，
     *       是 router 的规则不是本类的豁免：谁把 /dashboard 从 ALWAYS_ALLOWED 里拿掉，
     *       驾驶舱独占的控制器会立刻在这里红。</li>
     *   <li><b>组件树</b> = 路由的 {@code component} 文件 + 它的父路由布局。MainLayout 挂在 '/' 上，
     *       所有业务页都在它里面渲染，它 import 的 stores/auth、ChangePasswordDialog 在任何菜单页都在跑——
     *       把父布局并进子路由闭包是渲染树的事实，不是豁免（所以 /api/auth 不需要豁免条目）。</li>
     *   <li><b>传递闭包</b>沿 {@code import … from}、{@code import '…'}、{@code export … from}、{@code import('…')}
     *       走；解析 {@code ./ ../} 相对路径与 {@code @/} 别名（→ frontend/shell/src）；无后缀依次试
     *       .ts / .vue / .tsx / .js / index.ts / index.vue（barrel）；只跟仓内文件，node_modules 一律不跟；带环检测。</li>
     *   <li><b>router/ 下的文件是闭包的根，不是节点</b>：api/client.ts 为了 401 跳转 import 了 router，
     *       router 又懒加载了全部页面——跟进去，每个页面的闭包都会等于整个应用，本节就退化成 §1 的复读机。
     *       {@link #importWalkerResolvesEveryInRepoImportAndStopsAtTheRouteTable()} 看住这一条。</li>
     * </ul>
     *
     * <h3>边界：本节抓不住什么</h3>
     * 它抓得住「从菜单走到的页面根本没调这个控制器」；<b>抓不住</b>两类运行时语义：
     * <b>994 型</b>（页面调了，但用户手里没有它要的 id——版本页要人手填 outp_emr.id）与
     * <b>2553 型</b>（页面调了，但列表 where 条件把要找的记录永久排除）。
     * 那两类靠 {@code tools/e2e-v55-reach.py} 那样的端到端基线。静态断言与 E2E 各管一层，不互相替代。
     */
    @Test
    void everyControllerIsReachableFromSomeMenu() {
        Map<String, List<String>> controllers = scanControllers();
        Set<String> waived = waivedKeys(CONTROLLER_WAIVERS);
        waived.addAll(waivedKeys(MENU_REACH_WAIVERS));
        Map<String, String> unreachable = menuUnreachable(controllers, frontendGraph(), enabledMenuPaths(), waived);
        if (!unreachable.isEmpty()) {
            fail("""
                    【页面在、API 被调、可从菜单点进去的那一页根本不调它】%d 个 @RestController 从任何一条菜单都走不到：

                    %s

                    判据：sys_menu 的 MENU 行（加上 router 自己放在菜单授权之外的 ALWAYS_ALLOWED 页）→ 路由 →
                    组件文件 → 沿 import 传递闭包（父布局并入子路由）→ 闭包里的调用点字面量。
                    §1 只保证「全前端某个文件里调过」，本节要求「从菜单走到的那一页（或它 import 的东西）在调」。

                    该怎么修（三选一）：
                      A. 调用点所在的页面没有菜单：由合版补 sys_menu + sys_role_menu（§2 / §4 的做法，id 由合版分配）；
                      B. 调用点在一个没被任何路由引用的孤儿文件里：把它 import 进对应页面，或给它注册路由并补菜单；
                      C. 确实是例外（有前端调用、但天然无菜单入口）：往 MENU_REACH_WAIVERS 里加一条并写清是哪一类。
                         §1 的 CONTROLLER_WAIVERS 自动带入，不必重复登记。

                    本节抓不住两类：页面调了但用户手里没有它要的 id（994 型）、页面调了但列表条件把记录永久排除（2553 型）——
                    那是运行时语义，由 tools/e2e-v55-reach.py 这类端到端基线看住，静态断言不替代它。
                    """.formatted(unreachable.size(), String.join("\n\n", unreachable.values())));
        }
    }

    /**
     * §6 的探针（真实树）：证明这条断言此刻是绿的，是因为每个控制器真的能从菜单走到，<b>不是因为它不会红</b>。
     *
     * <p>在当前树上挑一个<b>只有一处调用点</b>、且那处调用点只出现在 MENU 行盖住的路由里的控制器，做两台手术，
     * 每台都要求检测器当场点名它：
     * <ol>
     *   <li>把那个调用文件从闭包输入里剔除（文件还挂在路由的组件树上，但不再贡献字面量与 import 边）——
     *       模拟「页面在、可它根本不调这个接口」；</li>
     *   <li>把盖住那条路由的 MENU 行从菜单集合里拿掉（文件、路由、字面量原封不动）——
     *       模拟「页面调了、可菜单没指到这一页」。这正是 v55 挖出的形态：§1 全绿，用户够不着。</li>
     * </ol>
     * 全在内存里做，不碰库、不碰文件；候选按当前树动态挑，不写死名字，控制器改名不会把探针弄烂。
     */
    @Test
    void menuReachabilityDetectorActuallyBites() {
        Map<String, List<String>> controllers = scanControllers();
        FrontendGraph g = frontendGraph();
        List<String> menus = enabledMenuPaths();
        Set<String> waived = waivedKeys(CONTROLLER_WAIVERS);
        waived.addAll(waivedKeys(MENU_REACH_WAIVERS));
        Map<String, String> before = menuUnreachable(controllers, g, menus, waived);
        MenuReach reach = menuReach(g, menus);

        String target = null;
        String caller = null;
        List<String> owningRoutes = List.of();
        for (Map.Entry<String, List<String>> e : new TreeMap<>(controllers).entrySet()) {
            if (waived.contains(e.getKey()) || before.containsKey(e.getKey())) continue;
            List<String> callers = callersOf(e.getValue(), g);
            if (callers.size() != 1) continue;
            List<String> owning = routesWhoseTreeContains(callers.get(0), reach);
            if (owning.isEmpty()) continue;
            boolean onlyViaMenu = true;
            for (String r : owning) {
                if (coveredByMenu(r, g.alwaysAllowed())) onlyViaMenu = false;
            }
            if (!onlyViaMenu) continue;
            target = e.getKey();
            caller = callers.get(0);
            owningRoutes = owning;
            break;
        }
        assertTrue(target != null,
                "当前树上挑不出「只有一处调用点、且只经 MENU 行可达」的控制器，探针无从构造——换一种构造方式，别把这条测试删掉");

        // 手术一：剔除唯一的调用文件
        Map<String, String> after1 = menuUnreachable(controllers, g.withoutFile(caller), menus, waived);
        assertTrue(after1.containsKey(target),
                "剔除唯一调用文件 " + caller + " 后，检测器没有点名 " + target
                        + "——它并没有在看闭包里的字面量，这条断言是空的");
        assertTrue(after1.keySet().containsAll(before.keySet()),
                "剔除一个文件不该让原本就走不到的控制器变绿——两次结果的口径糊了");

        // 手术二：拿掉盖住那条路由的菜单（文件、路由、字面量原封不动）
        List<String> fewerMenus = new ArrayList<>();
        for (String m : menus) {
            boolean covers = false;
            for (String r : owningRoutes) {
                if (coveredByMenu(r, List.of(m))) covers = true;
            }
            if (!covers) fewerMenus.add(m);
        }
        assertTrue(fewerMenus.size() < menus.size(), "手术二没拿掉任何菜单——候选挑选与覆盖判定的口径对不上");
        Map<String, String> after2 = menuUnreachable(controllers, g, fewerMenus, waived);
        assertTrue(after2.containsKey(target),
                "拿掉盖住 " + owningRoutes + " 的菜单后，检测器没有点名 " + target
                        + "——「页面调了、菜单没指到这一页」正是 v55 挖出的形态，抓不到等于没查");
        String diagnosis = after2.get(target);
        assertTrue(diagnosis.contains(caller) && diagnosis.contains(owningRoutes.get(0)),
                "失败信息必须点名调用文件与它所在的路由，否则下一个人没法照着修。实际：\n" + diagnosis);
    }

    /**
     * §6 的探针（合成）：检测器必须分得清<b>调用点在哪条路由的组件树里</b>，而不是「全前端有没有」。
     *
     * <p>{@link #menuReachabilityDetectorActuallyBites()} 在真实树上做手术，但真实树上恰好每个页面都有菜单，
     * 证明不了「闭包没有退化成整个应用」。这里合成一组文件与路由，把机制的每一环单独喂一遍：
     * 孤儿文件 → 红；有路由没菜单 → 红；补上菜单 → 绿；菜单在但页面不再 import 调用文件 → 红
     * （此时字面量在全前端仍然存在，§1 会绿——这正是两层的分界）；调用挂在父布局上 → 绿；import 成环 → 不死循环且绿。
     */
    @Test
    void menuReachabilityDistinguishesWhichRouteImportsTheCaller() {
        FrontendGraph real = frontendGraph();
        List<String> menus = enabledMenuPaths();
        String orphan = FRONTEND_SRC + "/views/__canary/CanaryPanel.vue";
        String page = FRONTEND_SRC + "/views/__canary/CanaryView.vue";
        String layout = FRONTEND_SRC + "/views/__canary/CanaryLayout.vue";
        String route = "/__canary";
        assertFalse(real.literals().containsKey(orphan) || real.literals().containsKey(page),
                "探针文件不该真的存在于仓里，否则这条探针没意义");
        assertFalse(menus.contains(route) || frontendRoutes().contains(route),
                "探针路由/菜单不该真的存在，否则这条探针没意义");
        String key = "/api/__canary";
        Map<String, List<String>> canary = Map.of(key, List.of("/api/__canary/ping", "/api/__canary"));
        Set<String> literal = Set.of("/__canary/ping");
        List<String> menusPlus = new ArrayList<>(menus);
        menusPlus.add(route);

        // 1. 孤儿文件里有调用，没有任何路由引用它 → 红
        FrontendGraph g1 = real.withFile(orphan, literal, Set.of());
        assertTrue(menuUnreachable(canary, g1, menus, Set.of()).containsKey(key),
                "孤儿文件里的调用被判成可达——检测器没有按路由组件树取字面量，退化成了 §1");
        // 2. 页面 import 了它、路由也注册了，但没有菜单 → 红（v55 形态）
        FrontendGraph g2 = g1.withFile(page, Set.of(), Set.of(orphan)).withRoute(route, List.of(page));
        assertTrue(menuUnreachable(canary, g2, menus, Set.of()).containsKey(key),
                "没有菜单的路由被当成了入口——「页面在、菜单没指到」正是要抓的形态");
        // 3. 补上菜单 → 绿
        assertFalse(menuUnreachable(canary, g2, menusPlus, Set.of()).containsKey(key),
                "菜单 → 路由 → 页面 → import → 调用点这条链通了，检测器却仍然点名——判据过严，后人会往豁免清单里乱塞");
        // 4. 菜单在、路由在，但页面不再 import 调用文件 → 红（字面量在全前端仍在，§1 会绿）
        FrontendGraph g4 = g1.withFile(page, Set.of(), Set.of()).withRoute(route, List.of(page));
        assertTrue(menuUnreachable(canary, g4, menusPlus, Set.of()).containsKey(key),
                "页面不 import 调用文件却被判成可达——检测器把「全前端有」当成了「这一页在调」，这正是 §1 与 §6 的分界");
        // 5. 调用挂在父布局上、子路由有菜单 → 绿（MainLayout 的 /auth 就是这么可达的）
        FrontendGraph g5 = g1.withFile(layout, Set.of(), Set.of(orphan))
                .withFile(page, Set.of(), Set.of())
                .withRoute(route, List.of(layout, page));
        assertFalse(menuUnreachable(canary, g5, menusPlus, Set.of()).containsKey(key),
                "父布局的调用没并进子路由的闭包——那 /api/auth 这类布局级 API 会被误判成走不到");
        // 6. import 成环（页面 ⇄ 调用文件）→ 不死循环，且仍然绿
        FrontendGraph g6 = real.withFile(orphan, literal, Set.of(page))
                .withFile(page, Set.of(), Set.of(orphan))
                .withRoute(route, List.of(page));
        assertFalse(menuUnreachable(canary, g6, menusPlus, Set.of()).containsKey(key),
                "import 成环时闭包算错了——环检测坏了");
    }

    /**
     * §6 的扫描器自检：走不到的 import 边不许静默丢掉，路由表不许被当成节点跟进去。
     *
     * <p>三件事一起看：
     * <ol>
     *   <li>所有 {@code ./ ../ @/} 形态的仓内 import 都解析到了真实文件——解析不到的边会让闭包缺一块，
     *       缺的那块里若有调用点，§6 会把一个其实走得到的控制器判成走不到（那是扫描器缺口，修扫描器，不加豁免）；</li>
     *   <li>结构化解析出的路由集合与 §2/§3 用的 {@code path:} 字面量扫描逐字相等——两套取词法互相对账；</li>
     *   <li>任何路由的闭包都不含 router/ 下的文件——api/client.ts → router → 全部页面这条环一旦被跟进去，
     *       每个页面的闭包都等于整个应用，§6 就永远不会红；</li>
     *   <li>router 的 ALWAYS_ALLOWED 数组解析到了——它是入口路由的另一半，解析不到会把驾驶舱独占的控制器误判。</li>
     * </ol>
     */
    @Test
    void importWalkerResolvesEveryInRepoImportAndStopsAtTheRouteTable() {
        FrontendGraph g = frontendGraph();
        List<String> bad = new ArrayList<>();
        for (String u : g.unresolved()) {
            bad.add("  · 解析不了的仓内 import：" + u
                    + "（相对路径 / @/ 别名都试过 .ts .vue .tsx .js /index.ts /index.vue——若是新写法，改 resolveImport；"
                    + "若只是注释里的一句话，改注释）");
        }
        if (g.alwaysAllowed().isEmpty()) {
            bad.add("  · router 里没解析到 ALWAYS_ALLOWED 数组——它是 §6 入口路由的一半，解析不到会把驾驶舱独占的控制器误判成走不到");
        }
        Set<String> structural = new TreeSet<>();
        for (RouteDef r : g.routes()) structural.add(r.path());
        Set<String> flat = frontendRoutes();
        if (!structural.equals(flat)) {
            Set<String> onlyStructural = new TreeSet<>(structural);
            onlyStructural.removeAll(flat);
            Set<String> onlyFlat = new TreeSet<>(flat);
            onlyFlat.removeAll(structural);
            bad.add("  · 结构化路由解析（§6）与 path: 字面量扫描（§2/§3）对不上：只在结构化里 " + onlyStructural
                    + "，只在字面量里 " + onlyFlat + "——router 出现了新写法，两处解析要一起改");
        }
        for (RouteDef r : g.routes()) {
            for (String f : closure(g, r.components())) {
                if (f.startsWith(FRONTEND_SRC + "/router/")) {
                    bad.add("  · 路由 " + r.path() + " 的闭包跟进了路由表 " + f
                            + "——路由表是闭包的根不是节点，跟进去每个页面的闭包都等于整个应用");
                }
            }
        }
        if (!bad.isEmpty()) {
            fail("""
                    【§6 的扫描器自己有缺口】共 %d 处：

                    %s

                    扫描器有缺口时，§6 的结论（不管红绿）都不算数：闭包少一块会把走得到的判成走不到，
                    跟进路由表会把走不到的判成走得到。先修扫描器，再看 §6。
                    """.formatted(bad.size(), String.join("\n", bad)));
        }
    }

    /**
     * <b>注释掉的 import 与注释掉的调用都不算数</b>（v56 对抗复核实测的两条漏洞，同一根因）。
     *
     * <p>此前只有 router 文本过 {@link #stripJsComments}，.vue/.ts 吃原文。复核用对照组证了两种假绿：
     * ① {@code // import ReviewChild from './ReviewChild.vue'} 仍成为闭包的边——只在 ReviewChild 里调的
     * 控制器被判「从菜单走得到」；② {@code // await client.post('/pay/orders', …)} 仍算调用点——
     * 页面无任何活调用仍绿。两例都是删掉那行注释文本就立刻红，**绿的唯一原因就是注释**。
     * 现实形态：有人把某个 panel 的 import 临时注掉，该 panel 调的控制器在 §6 里永远绿。
     *
     * <p>本用例把合成源码喂进<b>与 {@link #frontendGraph()} 完全相同的两条抽取路径</b>
     * （{@link #collectLiterals} 与 IMPORT_SPECS 循环），断言注释版取不出、活版取得出——
     * 后一半是对照组：没有它，这条测试对「抽取函数整体坏掉、什么都取不出」同样会绿。
     */
    @Test
    void commentedOutImportAndCallAreNotExtractedDetectorActuallyBites() {
        String dead = """
                <script setup lang="ts">
                // import ReviewChild from './ReviewChild.vue'
                /* import Other from '../Other.vue' */
                // await client.post('/pay/orders', body)
                /* client.get('/pay/orders/1') */
                const note = 'https://example.org/not/an/api'   // 字符串里的 // 不是注释，也不是 API
                </script>
                """;
        String live = """
                <script setup lang="ts">
                import ReviewChild from './ReviewChild.vue'
                import { useX } from '@/composables/useX'
                const Lazy = defineAsyncComponent(() => import('./Lazy.vue'))
                await client.post('/pay/orders', body)
                </script>
                """;

        Set<String> deadLits = new TreeSet<>();
        collectLiterals(new SrcFile(FRONTEND_SRC + "/views/probe/Dead.vue", dead), deadLits);
        Set<String> liveLits = new TreeSet<>();
        collectLiterals(new SrcFile(FRONTEND_SRC + "/views/probe/Live.vue", live), liveLits);

        assertTrue(deadLits.isEmpty(),
                "被注释掉的调用不得算作调用点（漏洞 2），实际取出：" + deadLits);
        assertTrue(liveLits.contains("/pay/orders"),
                "对照组：活调用必须取得出，否则说明抽取函数整体坏了而不是注释被剥了：" + liveLits);

        Set<String> deadImports = importSpecsOf(dead);
        Set<String> liveImports = importSpecsOf(live);
        assertTrue(deadImports.isEmpty(),
                "被注释掉的 import 不得成为闭包的边（漏洞 1），实际取出：" + deadImports);
        assertTrue(liveImports.containsAll(Set.of("./ReviewChild.vue", "@/composables/useX", "./Lazy.vue")),
                "对照组：三种活 import（相对 / @ 别名 / 动态）都必须取得出：" + liveImports);
    }

    /** 与 {@link #frontendGraph()} 里 IMPORT_SPECS 循环<b>逐字同一条</b>抽取路径，供自证用例复用。 */
    private static Set<String> importSpecsOf(String text) {
        String script = stripJsComments(text);
        Set<String> out = new LinkedHashSet<>();
        for (Pattern p : IMPORT_SPECS) {
            Matcher m = p.matcher(script);
            while (m.find()) out.add(m.group(2));
        }
        return out;
    }

    /**
     * §6 豁免清单的守卫，与 {@link #waiverListItselfIsMaintained()} 同一套规矩再加两条：
     * 不许与 §1 的清单重复登记（§1 的自动带入，两条理由会互相打架），
     * 不许留「此刻其实走得到」的死条目——它什么都不再豁免，却会在这个控制器将来真的够不着时把红盖住。
     */
    @Test
    void menuReachWaiverListItselfIsMaintained() {
        List<String> bad = new ArrayList<>();
        checkWaiverShape("MENU_REACH_WAIVERS", MENU_REACH_WAIVERS, bad);
        Map<String, List<String>> controllers = scanControllers();
        Set<String> section1 = waivedKeys(CONTROLLER_WAIVERS);
        Map<String, String> unreachableIgnoringWaivers = MENU_REACH_WAIVERS.isEmpty()
                ? Map.of()
                : menuUnreachable(controllers, frontendGraph(), enabledMenuPaths(), Set.of());
        for (Waiver w : MENU_REACH_WAIVERS) {
            if (!controllers.containsKey(w.key())) {
                bad.add("MENU_REACH_WAIVERS 里的 \"" + w.key() + "\" 已对不上任何 @RestController（控制器被改名或删除了？）。"
                        + "烂条目请直接删掉——留着只会在将来悄悄豁免一个同名的新控制器。");
            } else if (section1.contains(w.key())) {
                bad.add("MENU_REACH_WAIVERS 里的 \"" + w.key() + "\" 已在 CONTROLLER_WAIVERS 里——§1 的豁免自动带入 §6，"
                        + "重复登记两条理由会互相打架，删掉这一条。");
            } else if (!unreachableIgnoringWaivers.containsKey(w.key())) {
                bad.add("MENU_REACH_WAIVERS 里的 \"" + w.key() + "\" 此刻从菜单走得到，这条豁免什么都不再豁免——"
                        + "删掉；留着会在它将来真的够不着时把红盖住。");
            }
        }
        if (!bad.isEmpty()) {
            fail("""
                    【§6 豁免清单自身不合格】共 %d 条：

                    %s

                    豁免清单是这套断言唯一允许随版本增长的东西，所以它自己必须先干净：
                    键唯一、理由说清是哪一类例外、条目还在管着一个真实存在且此刻确实走不到的控制器。
                    """.formatted(bad.size(), String.join("\n", bad)));
        }
    }

    // ==================================================================================
    // 扫描工具
    // ==================================================================================

    /** Java 源码根：全仓，无版本窗口。 */
    private static final String[] JAVA_DIRS = {
            "modules", "platform", "server/src/main/java", "datacenter", "bureau", "ai-service", "impl"};

    /** 前端源码根。frontend/bureau 目前只有 dist/ 构建产物，没有 src，不参与扫描。 */
    private static final String[] FRONTEND_DIRS = {"frontend/shell/src"};

    private static final Pattern CLASS_DECL =
            Pattern.compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*class\\s+\\w+");
    private static final Pattern CLASS_MAPPING =
            Pattern.compile("@RequestMapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"\\s*\\)");
    private static final Pattern ANY_MAPPING =
            Pattern.compile("@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");
    private static final Pattern GATE_KEY =
            Pattern.compile("\"([a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)*\\.gate\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*)\"");
    private static final Pattern ROUTE_PATH = Pattern.compile("path:\\s*(['\"])([^'\"]*)\\1");
    /** {@code @RestControllerAdvice} 不是控制器：{@code \b} 卡在 "…Controller" 与 "Advice" 之间为假，天然排除。 */
    private static final Pattern REST_CONTROLLER = Pattern.compile("@RestController\\b");

    /**
     * 前端「API 调用点」取词法。<b>只认这三种形态</b>，不认页面里的任意字符串——
     * 否则 router.push('/inpatient/xxx') 这种导航字面量会把零前端的控制器判成绿的。
     */
    private static final Pattern[] API_LITERALS = {
            Pattern.compile("\\.(?:get|post|put|delete|patch|request)\\s*(?:<[^>\\n]*>)?\\(\\s*(['\"`])([^'\"`\\n]*)\\1"),
            Pattern.compile("(?:const|let|var)\\s+\\w*(?:BASE|Base|API|Api|URL|Url|PATH|Path|PREFIX|Prefix)\\w*"
                    + "\\s*(?::[^=\\n]*)?=\\s*(['\"`])([^'\"`\\n]*)\\1"),
            Pattern.compile("(?:url|baseURL)\\s*:\\s*(['\"`])([^'\"`\\n]*)\\1")};

    /** 控制器基路径 → 源文件（失败信息里要点名文件，否则「照着修」无从下手）。 */
    private final Map<String, String> controllerFiles = new LinkedHashMap<>();

    /**
     * 全仓 {@code @RestController} → 它的全部端点全路径。
     *
     * <p>键取<b>类级 {@code @RequestMapping}</b>；没有类级映射的控制器（本仓有十几个把全路径写在方法上）
     * 取其所有方法级路径的最长公共前缀，保证键仍然是一个可读、可写进豁免清单的稳定标识。
     */
    private Map<String, List<String>> scanControllers() {
        if (!controllerFiles.isEmpty()) controllerFiles.clear();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            if (!REST_CONTROLLER.matcher(f.text()).find()) continue;
            Matcher cd = CLASS_DECL.matcher(f.text());
            int classAt = cd.find() ? cd.start() : f.text().length();

            String classPath = null;
            Matcher cm = CLASS_MAPPING.matcher(f.text());
            while (cm.find()) {
                if (cm.start() < classAt) { classPath = cm.group(1); break; }
            }
            Set<String> paths = new LinkedHashSet<>();
            Matcher am = ANY_MAPPING.matcher(f.text());
            while (am.find()) {
                String m = am.group(1);
                if (m.startsWith("/api")) {
                    paths.add(m);
                } else if (classPath != null) {
                    paths.add(join(classPath, m));
                }
            }
            if (classPath != null) paths.add(classPath);
            String key = classPath != null ? classPath : commonPrefix(paths);
            if (key == null || key.isBlank()) {
                // 既没有类级映射、方法级也拼不出公共前缀：这本身就该修，不该被静默跳过
                key = "(无法定位路径) " + f.rel();
            }
            out.computeIfAbsent(key, k -> new ArrayList<>()).addAll(paths);
            controllerFiles.merge(key, f.rel(), (a, b) -> a + " , " + b);
        }
        return out;
    }

    /** 前端所有 API 调用点上的路径字面量（模板串取 {@code ${} 之前的静态前缀）。 */
    private Set<String> frontendApiLiterals() {
        Set<String> out = new TreeSet<>();
        for (SrcFile f : sources(".vue", FRONTEND_DIRS)) collectLiterals(f, out);
        for (SrcFile f : sources(".ts", FRONTEND_DIRS)) collectLiterals(f, out);
        if (out.isEmpty()) {
            fail("在 " + String.join(", ", FRONTEND_DIRS) + " 里一个 API 调用字面量都没扫到——"
                    + "多半是取词法或目录变了，此时「全部控制器不可达」是假结论，先修扫描再看结果");
        }
        return out;
    }

    private static void collectLiterals(SrcFile f, Set<String> out) {
        // **先剥注释再取词**（v56 对抗复核实测的漏洞 2）：此前只有 router 文本过 stripJsComments，
        // .vue/.ts 吃的是原文——于是 `// await client.post('/pay/orders', …)` 这种**被注释掉的调用**
        // 仍算调用点，§1 与 §6 双双假绿。复核用对照组证的：删掉那行注释文本 → 两节同时红、点名 /api/pay。
        // stripJsComments 按引号跟踪，字符串里的 `//`（如 'https://…'）不会被吃；
        // 它不认正则字面量、也可能多剥 <template> 里裸露的 `http://` 后半行——
        // 那只会「少认」不会「多认」调用点，方向是安全的。
        String text = stripJsComments(f.text());
        for (Pattern p : API_LITERALS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                String s = m.group(2);
                if (!s.startsWith("/")) continue;
                int v = s.indexOf("${");
                out.add(v >= 0 ? s.substring(0, v) : s);
            }
        }
    }

    /** 控制器端点是否被前端命中：端点前缀词 == 字面量，或字面量在它下面继续延伸。 */
    private static boolean anyLiteralHits(List<String> endpoints, Set<String> literals) {
        for (String ep : endpoints) {
            String token = stripApi(ep);
            int brace = token.indexOf('{');
            if (brace >= 0) token = token.substring(0, brace);
            while (token.endsWith("/")) token = token.substring(0, token.length() - 1);
            if (token.isEmpty()) continue;
            for (String l : literals) {
                if (l.equals(token) || l.startsWith(token + "/") || l.startsWith(token + "?")
                        || l.startsWith(token + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * router 里注册的全部页面路径，归一化成绝对路径。
     *
     * <p>本仓 router 目前是两层：根 {@code '/'} 挂 MainLayout，业务页都是它的 children，
     * 子路由写成不带前导斜杠的相对路径。故「补一个前导斜杠」即得绝对路径。
     */
    private Set<String> frontendRoutes() {
        Set<String> out = new TreeSet<>();
        for (SrcFile f : sources(".ts", "frontend/shell/src/router")) {
            Matcher m = ROUTE_PATH.matcher(f.text());
            while (m.find()) {
                String p = m.group(2);
                if (p.isEmpty()) continue;
                out.add(p.startsWith("/") ? p : "/" + p);
            }
        }
        if (out.isEmpty()) {
            fail("frontend/shell/src/router 里一条路由都没扫到——先修扫描再看结果");
        }
        return out;
    }

    // ---------------- §6：前端源码图（字面量按文件归属 + 路由 → 组件 + import 传递闭包） ----------------

    /** 前端源码根（{@code @/} 别名指向这里）。 */
    private static final String FRONTEND_SRC = "frontend/shell/src";

    /** 无后缀 import 的补全顺序：原样、.ts、.vue、.tsx、.js、目录 barrel。 */
    private static final String[] RESOLVE_SUFFIXES = {"", ".ts", ".vue", ".tsx", ".js", "/index.ts", "/index.vue"};

    /**
     * import 边的取词法。三种形态：{@code … from '…'}（静态 import 与 export … from 的 barrel 重导出）、
     * 行首的副作用 import {@code import '…'}、动态 {@code import('…')}。
     * 不认 {@code require()}、{@code import.meta.glob}、vite 根绝对路径 {@code '/src/…'}——本仓没有这些写法；
     * 真出现了，{@link #importWalkerResolvesEveryInRepoImportAndStopsAtTheRouteTable()} 不会报，
     * 因为它们不以 ./ ../ @/ 开头——加写法时请一并把它们纳入取词。
     */
    private static final Pattern[] IMPORT_SPECS = {
            Pattern.compile("\\bfrom\\s*(['\"])([^'\"\\n]+)\\1"),
            Pattern.compile("(?m)^\\s*import\\s*(['\"])([^'\"\\n]+)\\1"),
            Pattern.compile("\\bimport\\s*\\(\\s*(['\"])([^'\"\\n]+)\\1")};

    private static final Pattern STATIC_IMPORT =
            Pattern.compile("\\bimport\\s+([A-Za-z_$][\\w$]*)\\s+from\\s*(['\"])([^'\"\\n]+)\\2");
    private static final Pattern CONST_LAZY = Pattern.compile(
            "\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*\\(\\)\\s*=>\\s*import\\(\\s*(['\"])([^'\"\\n]+)\\2");
    private static final Pattern COMPONENT_LAZY =
            Pattern.compile("\\bcomponent\\s*:\\s*\\(\\)\\s*=>\\s*import\\(\\s*(['\"])([^'\"\\n]+)\\1");
    private static final Pattern COMPONENT_NAMED = Pattern.compile("\\bcomponent\\s*:\\s*([A-Za-z_$][\\w$]*)");
    private static final Pattern CHILDREN_KEY = Pattern.compile("\\bchildren\\s*:");
    private static final Pattern ALWAYS_ALLOWED = Pattern.compile("\\bALWAYS_ALLOWED\\s*=\\s*\\[([^\\]]*)\\]");
    private static final Pattern QUOTED = Pattern.compile("(['\"])([^'\"\\n]*)\\1");

    /** 一条路由：归一化的绝对路径 + 渲染它时挂载的组件文件链（父布局在前，页面组件在后）。 */
    private record RouteDef(String path, List<String> components) {}

    /**
     * 前端源码图：文件 → 它 import 的仓内文件；文件 → 它的 API 调用字面量；路由表；router 的 ALWAYS_ALLOWED；
     * 以及解析不了的仓内 import（给扫描器自检用）。探针在副本上做手术，三个 with* 都是 copy-on-write。
     */
    private record FrontendGraph(Map<String, Set<String>> imports, Map<String, Set<String>> literals,
                                 List<RouteDef> routes, List<String> alwaysAllowed, List<String> unresolved) {
        FrontendGraph withFile(String rel, Set<String> lits, Set<String> imps) {
            Map<String, Set<String>> i = new LinkedHashMap<>(imports);
            i.put(rel, new LinkedHashSet<>(imps));
            Map<String, Set<String>> l = new LinkedHashMap<>(literals);
            l.put(rel, new TreeSet<>(lits));
            return new FrontendGraph(i, l, routes, alwaysAllowed, unresolved);
        }

        FrontendGraph withoutFile(String rel) {
            Map<String, Set<String>> i = new LinkedHashMap<>(imports);
            i.remove(rel);
            Map<String, Set<String>> l = new LinkedHashMap<>(literals);
            l.remove(rel);
            return new FrontendGraph(i, l, routes, alwaysAllowed, unresolved);
        }

        FrontendGraph withRoute(String path, List<String> chain) {
            List<RouteDef> r = new ArrayList<>(routes);
            r.add(new RouteDef(path, List.copyOf(chain)));
            return new FrontendGraph(imports, literals, r, alwaysAllowed, unresolved);
        }
    }

    /** 一次菜单可达性计算的中间结果：入口根集合、每条路由的组件树、入口路由组件树里的全部字面量。 */
    private record MenuReach(List<String> roots, Map<String, Set<String>> trees, Set<String> literals) {}

    /** 扫 frontend/shell/src 全量 .vue/.ts，建图。字面量取词与 §1 同一组 {@link #API_LITERALS}，只是按文件归属。 */
    private FrontendGraph frontendGraph() {
        Map<String, SrcFile> files = new LinkedHashMap<>();
        for (SrcFile f : sources(".vue", FRONTEND_DIRS)) files.put(f.rel(), f);
        for (SrcFile f : sources(".ts", FRONTEND_DIRS)) files.put(f.rel(), f);
        List<String> unresolved = new ArrayList<>();
        Map<String, Set<String>> imports = new LinkedHashMap<>();
        Map<String, Set<String>> literals = new LinkedHashMap<>();
        for (SrcFile f : files.values()) {
            Set<String> lits = new TreeSet<>();
            collectLiterals(f, lits);
            literals.put(f.rel(), lits);
            Set<String> edges = new LinkedHashSet<>();
            // **先剥注释再抽 import**（v56 对抗复核实测的漏洞 1）：此前 IMPORT_SPECS 跑在原文上，
            // `// import ReviewChild from './ReviewChild.vue'` 这种**被注释掉的 import** 仍成为闭包的边，
            // 于是只在 ReviewChild 里调的 /api/outpatient/review 被判「从菜单走得到」——假绿。
            // 现实形态：有人把某个 panel 的 import 临时注掉，该 panel 调的控制器在 §6 里永远绿。
            // 复核用对照组证的：删掉那行注释文本 → §6 红、诊断为「孤儿文件」。绿的唯一原因就是注释。
            String script = stripJsComments(f.text());
            for (Pattern p : IMPORT_SPECS) {
                Matcher m = p.matcher(script);
                while (m.find()) {
                    String to = resolveImport(m.group(2), f.rel(), files.keySet(), unresolved);
                    // 路由表是闭包的根，不是节点：client.ts → router → 全部懒加载页面这条边一跟，闭包就等于整个应用
                    if (to != null && !to.startsWith(FRONTEND_SRC + "/router/")) edges.add(to);
                }
            }
            imports.put(f.rel(), edges);
        }
        String routerRel = FRONTEND_SRC + "/router/index.ts";
        SrcFile router = files.get(routerRel);
        if (router == null) {
            return fail(routerRel + " 不存在——路由表搬家了？请同步改 frontendGraph()，不要靠豁免绕过");
        }
        String routerText = stripJsComments(router.text());
        List<RouteDef> routes = parseRouter(routerRel, routerText, files.keySet(), unresolved);
        if (routes.isEmpty()) {
            return fail(routerRel + " 里一条路由都没解析到——先修扫描再看结果");
        }
        List<String> alwaysAllowed = new ArrayList<>();
        Matcher aa = ALWAYS_ALLOWED.matcher(routerText);
        if (aa.find()) {
            Matcher q = QUOTED.matcher(aa.group(1));
            while (q.find()) alwaysAllowed.add(q.group(2));
        }
        return new FrontendGraph(imports, literals, routes, alwaysAllowed, unresolved);
    }

    /**
     * 把一个 import 说明符解析成仓内相对路径。bare 包名（vue / element-plus / axios …）返回 null 且不算未解析——
     * 那是 node_modules，一律不跟；{@code ./ ../ @/} 形态解析不到则记入 {@code unresolved}，由扫描器自检点名。
     */
    private static String resolveImport(String spec, String fromRel, Set<String> files, List<String> unresolved) {
        String base;
        if (spec.startsWith("@/")) {
            base = FRONTEND_SRC + "/" + spec.substring(2);
        } else if (spec.startsWith("./") || spec.startsWith("../")) {
            int slash = fromRel.lastIndexOf('/');
            String dir = slash < 0 ? "" : fromRel.substring(0, slash);
            base = Path.of(dir, spec).normalize().toString().replace('\\', '/');
        } else {
            return null;
        }
        for (String suf : RESOLVE_SUFFIXES) {
            if (files.contains(base + suf)) return base + suf;
        }
        unresolved.add(fromRel + " → '" + spec + "'");
        return null;
    }

    /**
     * 结构化解析 router：按 {@code routes: [ { path, component, children: [ … ] } ]} 的括号嵌套走，
     * 子路由的相对 path 拼到父路径上，父路由的组件排在子路由组件链的前面（布局并入）。
     * {@code component} 认两种写法：{@code () => import('…')}，以及 {@code component: X} 配
     * {@code import X from '…'} / {@code const X = () => import('…')}。
     */
    private static List<RouteDef> parseRouter(String routerRel, String text, Set<String> files, List<String> unresolved) {
        Map<String, String> named = new LinkedHashMap<>();
        Matcher si = STATIC_IMPORT.matcher(text);
        while (si.find()) named.put(si.group(1), si.group(3));
        Matcher cl = CONST_LAZY.matcher(text);
        while (cl.find()) named.put(cl.group(1), cl.group(3));
        int at = text.indexOf("routes:");
        if (at < 0) throw new IllegalStateException(routerRel + " 里找不到 routes: 数组——先修扫描再看结果");
        List<RouteDef> out = new ArrayList<>();
        parseRouteArray(text, text.indexOf('[', at), "", List.of(), named, routerRel, files, unresolved, out);
        return out;
    }

    private static void parseRouteArray(String text, int open, String parentPath, List<String> parentChain,
                                        Map<String, String> named, String routerRel, Set<String> files,
                                        List<String> unresolved, List<RouteDef> out) {
        int close = matchBracket(text, open, '[', ']');
        int i = open + 1;
        while (i < close) {
            if (text.charAt(i) != '{') {
                i++;
                continue;
            }
            int end = matchBracket(text, i, '{', '}');
            String obj = text.substring(i, end + 1);
            String own = obj;
            int childOpen = -1;
            Matcher ck = CHILDREN_KEY.matcher(obj);
            if (ck.find()) {
                childOpen = obj.indexOf('[', ck.end());
                int childClose = matchBracket(obj, childOpen, '[', ']');
                own = obj.substring(0, childOpen) + obj.substring(childClose + 1);
            }
            Matcher pm = ROUTE_PATH.matcher(own);
            if (pm.find()) {
                String p = pm.group(2);
                String abs = p.startsWith("/") ? p : parentPath.endsWith("/") ? parentPath + p : parentPath + "/" + p;
                if (abs.isEmpty()) abs = "/";
                List<String> chain = new ArrayList<>(parentChain);
                String spec = null;
                Matcher lazy = COMPONENT_LAZY.matcher(own);
                if (lazy.find()) {
                    spec = lazy.group(2);
                } else {
                    Matcher nm = COMPONENT_NAMED.matcher(own);
                    if (nm.find()) {
                        spec = named.get(nm.group(1));
                        if (spec == null) {
                            unresolved.add(routerRel + " → 路由 " + abs + " 的 component 标识符 " + nm.group(1)
                                    + " 既不是 import X from '…' 也不是 const X = () => import('…')");
                        }
                    }
                }
                if (spec != null) {
                    String r = resolveImport(spec, routerRel, files, unresolved);
                    if (r != null) chain.add(r);
                }
                out.add(new RouteDef(abs, List.copyOf(chain)));
                if (childOpen >= 0) {
                    parseRouteArray(text, i + childOpen, abs, chain, named, routerRel, files, unresolved, out);
                }
            }
            i = end + 1;
        }
    }

    /** 从 {@code open} 处的开括号找到配对的闭括号，跳过字符串字面量里的括号。 */
    private static int matchBracket(String s, int open, char o, char c) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == '\\') i++;
                else if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        throw new IllegalStateException("router 第 " + open + " 个字符起的 '" + o + "' 没有配对的 '" + c + "'");
    }

    /** 去掉 JS 的 {@code //} 行注释与 {@code /* *&#47;} 块注释，字符串里的内容原样保留——注释里的引号与括号不许干扰解析。 */
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
                i = e < 0 ? s.length() : e + 1;
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** 从一组组件文件出发的 import 传递闭包（含起点）。visited 集合就是环检测：到过的不再进。 */
    private static Set<String> closure(FrontendGraph g, List<String> seeds) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> stack = new ArrayDeque<>(seeds);
        while (!stack.isEmpty()) {
            String f = stack.pop();
            if (!seen.add(f)) continue;
            for (String to : g.imports().getOrDefault(f, Set.of())) {
                if (!seen.contains(to)) stack.push(to);
            }
        }
        return seen;
    }

    /** 入口根 = MENU 行 ∪ router 的 ALWAYS_ALLOWED；入口路由（落在某个根之下）的组件树里的字面量并起来。 */
    private static MenuReach menuReach(FrontendGraph g, List<String> menuPaths) {
        List<String> roots = new ArrayList<>(menuPaths);
        roots.addAll(g.alwaysAllowed());
        Map<String, Set<String>> trees = new LinkedHashMap<>();
        Set<String> lits = new TreeSet<>();
        for (RouteDef r : g.routes()) {
            Set<String> tree = closure(g, r.components());
            trees.merge(r.path(), tree, (a, b) -> {
                Set<String> u = new LinkedHashSet<>(a);
                u.addAll(b);
                return u;
            });
            if (coveredByMenu(r.path(), roots)) {
                for (String f : tree) lits.addAll(g.literals().getOrDefault(f, Set.of()));
            }
        }
        return new MenuReach(roots, trees, lits);
    }

    /** 从菜单走不到的控制器 → 能照着修的诊断（点名调用文件、它所在的路由、缺的是菜单还是引用）。 */
    private Map<String, String> menuUnreachable(Map<String, List<String>> controllers, FrontendGraph g,
                                                List<String> menuPaths, Set<String> waived) {
        MenuReach reach = menuReach(g, menuPaths);
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : controllers.entrySet()) {
            if (waived.contains(e.getKey()) || anyLiteralHits(e.getValue(), reach.literals())) continue;
            out.put(e.getKey(), diagnoseUnreachable(e.getKey(), e.getValue(), g, reach));
        }
        return out;
    }

    private String diagnoseUnreachable(String base, List<String> endpoints, FrontendGraph g, MenuReach reach) {
        StringBuilder sb = new StringBuilder();
        sb.append("  · ").append(base).append("\n      源文件：").append(controllerFiles.getOrDefault(base, "?"));
        List<String> callers = callersOf(endpoints, g);
        if (callers.isEmpty()) {
            sb.append("\n      全前端没有一处调用点（§1 也会红）——先按 §1 的提示补页面");
        }
        for (String c : callers) {
            List<String> owning = routesWhoseTreeContains(c, reach);
            if (owning.isEmpty()) {
                sb.append("\n      调用点 ").append(c)
                        .append("：没有任何路由的组件树包含它（孤儿文件——既不是路由组件，也没被任何路由组件 import）");
            } else {
                sb.append("\n      调用点 ").append(c).append("：在路由 ").append(owning)
                        .append(" 的组件树里，但这些路由上面没有任何 MENU 行、也不在 router 的 ALWAYS_ALLOWED 里");
            }
        }
        return sb.toString();
    }

    /** 全前端里字面量命中该控制器的文件（按路径排序，失败信息稳定）。 */
    private static List<String> callersOf(List<String> endpoints, FrontendGraph g) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : new TreeMap<>(g.literals()).entrySet()) {
            if (anyLiteralHits(endpoints, e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /** 组件树包含该文件的路由。 */
    private static List<String> routesWhoseTreeContains(String file, MenuReach reach) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : reach.trees().entrySet()) {
            if (e.getValue().contains(file)) out.add(e.getKey());
        }
        return out;
    }

    /** 启用的 MENU 行的 path（与 §2 同一条查询）。 */
    private List<String> enabledMenuPaths() {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select path from sys_menu where type = 'MENU' and enabled and path is not null and path <> '' order by id")) {
            out.add(String.valueOf(row.get("path")));
        }
        return out;
    }

    /** 全仓代码里出现的三态 gate 键（谁在读，谁就得被登记）。 */
    private Set<String> scanGateKeysInCode() {
        Set<String> out = new TreeSet<>();
        for (SrcFile f : sources(".java", JAVA_DIRS)) {
            Matcher m = GATE_KEY.matcher(f.text());
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    /** {@code sys_config} 里已登记的 gate 键 → 出厂值。 */
    private Map<String, String> registeredGateKeys() {
        Map<String, String> out = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "select cfg_key, cfg_value from sys_config where cfg_key like '%.gate.%'")) {
            out.put(String.valueOf(row.get("cfg_key")), String.valueOf(row.get("cfg_value")));
        }
        return out;
    }

    // ---------------- 清单与小工具 ----------------

    private static Set<String> waivedKeys(List<Waiver> list) {
        Set<String> out = new TreeSet<>();
        for (Waiver w : list) out.add(w.key());
        return out;
    }

    /** 套话黑名单：这些词写上去等于没写理由。 */
    private static final List<String> EMPTY_REASONS =
            List.of("todo", "tbd", "待补", "暂无", "无", "n/a", "na", "没有前端", "不需要");

    private static void checkWaiverShape(String listName, List<Waiver> list, List<String> bad) {
        Set<String> seen = new TreeSet<>();
        for (Waiver w : list) {
            if (!seen.add(w.key())) {
                bad.add(listName + " 里 \"" + w.key() + "\" 重复登记——两条理由会互相打架，合并成一条");
            }
            String why = w.why() == null ? "" : w.why().strip();
            if (why.length() < 20) {
                bad.add(listName + " 里 \"" + w.key() + "\" 的理由太短（" + why.length()
                        + " 字）：要写清是哪一类例外（机器对接 / 另有独立壳 / 样板骨架 …），"
                        + "以及这条功能真正的覆盖在哪里");
            }
            String low = why.toLowerCase();
            for (String t : EMPTY_REASONS) {
                if (low.equals(t) || low.startsWith(t + "，") || low.startsWith(t + ",")) {
                    bad.add(listName + " 里 \"" + w.key() + "\" 的理由是套话（\"" + why + "\"）——"
                            + "「就是没有前端」正是本断言要抓的东西，不是豁免的理由");
                }
            }
        }
    }

    private static String stripApi(String p) {
        return p.startsWith("/api") ? p.substring(4) : p;
    }

    private static String join(String base, String sub) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (sub.isEmpty() || "/".equals(sub)) return b;
        return b + (sub.startsWith("/") ? sub : "/" + sub);
    }

    /** 一组路径的最长「按段」公共前缀，给没有类级 @RequestMapping 的控制器当稳定标识。 */
    private static String commonPrefix(Set<String> paths) {
        if (paths.isEmpty()) return null;
        List<String> first = null;
        List<String> acc = null;
        for (String p : paths) {
            List<String> seg = new ArrayList<>(List.of(p.split("/")));
            if (first == null) { first = seg; acc = seg; continue; }
            List<String> next = new ArrayList<>();
            for (int i = 0; i < Math.min(acc.size(), seg.size()); i++) {
                if (!acc.get(i).equals(seg.get(i))) break;
                next.add(acc.get(i));
            }
            acc = next;
        }
        String s = String.join("/", acc);
        return s.isBlank() ? null : s;
    }

    // ---------------- 源码遍历（与 V51CdssTest 同口径） ----------------

    private record SrcFile(String rel, String text) {}

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

    /** 遍历真代码。跳过 target/ node_modules/ .claude/ worktrees/——构建产物与他人工作区不是本仓代码。 */
    private static List<SrcFile> sources(String suffix, String... relDirs) {
        Path root = repoRoot();
        List<SrcFile> out = new ArrayList<>();
        for (String rel : relDirs) {
            Path base = root.resolve(rel);
            if (!Files.isDirectory(base)) continue;
            try (var s = Files.walk(base)) {
                for (Path f : s.filter(Files::isRegularFile).toList()) {
                    String path = f.toString().replace('\\', '/');
                    if (path.contains("/target/") || path.contains("/node_modules/")
                            || path.contains("/.claude/") || path.contains("/worktrees/")
                            || path.contains("/dist/")) {
                        continue;
                    }
                    if (!path.endsWith(suffix)) continue;
                    out.add(new SrcFile(root.relativize(f).toString().replace('\\', '/'), read(f)));
                }
            } catch (Exception e) {
                throw new IllegalStateException("扫描 " + base + " 失败", e);
            }
        }
        return out;
    }
}
