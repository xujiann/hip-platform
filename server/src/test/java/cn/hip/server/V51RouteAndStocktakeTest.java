package cn.hip.server;

import cn.hip.outpatient.service.RouteRuleService;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.service.InventoryService;
import cn.hip.platform.masterdata.service.InventoryService.InventoryException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v51 车道 D 回归：① 给药途径与溶媒配伍（5660–5679）② 盘点批次层收敛（5680–5699）。
 *
 * <p>编排把「没有伪造任何药学知识」放在最前——它锁的是本车道最要紧的一条纪律。
 * 一旦 {@link #migrationSeededNoPharmacologyKnowledge()} 变红，说明有人往规则表里
 * 塞了配伍禁忌或药品适用途径，那正是「看起来很专业、医生会信、然后出事」的那类东西。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V51RouteAndStocktakeTest {

    @Autowired RouteRuleService routeRules;
    @Autowired InventoryService inventoryService;
    @Autowired DrugItemRepository drugRepository;
    @Autowired ConfigReader configReader;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired cn.hip.server.support.TestSeeds seeds;

    /** ConfigReader 是 30 秒进程内缓存，事务回滚清不掉——必须显式失效，否则串味到别的用例 */
    @AfterEach
    void evict() {
        configReader.evictAll();
    }

    // ===================== 工具 =====================

    private static int seq = 0;

    private static String uniq(String p) {
        return p + (System.nanoTime() % 100000000L) + (seq++);
    }

    private long drug(String name) {
        return seeds.drug(name).getId();
    }

    private void setStock(long drugId, int stock) {
        jdbc.update("update md_drug set stock = ? where id = ?", stock, drugId);
        em.clear();
    }

    private int stockOf(long drugId) {
        em.clear();
        return drugRepository.findById(drugId).orElseThrow().getStock();
    }

    /**
     * 拒收类断言专用：走裸 JDBC 读库存。
     *
     * <p>{@code confirmStockTake} 是 {@code @Transactional} 的，它抛异常会把测试所在的外层事务
     * 标记 rollback-only；此后再走 JPA 查询（会先 flush）可能踩在被标记的事务上而炸出
     * 与被测行为无关的错。裸 JDBC 不受该标记影响，读到的仍是本事务内的真实值。
     */
    private int stockViaJdbc(long drugId) {
        Integer n = jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
        return n == null ? 0 : n;
    }

    private void cfg(String key, String value) {
        jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", value, key);
        configReader.evictAll();
    }

    /** 直插一条批次 + 一行批次余额（不走 /stock-in，避免顺带改汇总库存干扰断言） */
    private long batchWithStock(long drugId, int qty) {
        Long batchId = jdbc.queryForObject("""
                insert into pharm_batch(drug_id, batch_no, expire_on, created_at)
                values (?, ?, current_date + 400, now()) returning id
                """, Long.class, drugId, uniq("B"));
        jdbc.update("insert into pharm_stock(batch_id, location_code, qty) values (?, 'OUTP_PHARM', ?)",
                batchId, qty);
        return batchId;
    }

    private int batchQtyOf(long drugId) {
        Integer n = jdbc.queryForObject("""
                select coalesce(sum(s.qty), 0) from pharm_stock s
                  join pharm_batch b on b.id = s.batch_id where b.drug_id = ?
                """, Integer.class, drugId);
        return n == null ? 0 : n;
    }

    private List<Map<String, Object>> checkRows(long takeId) {
        return jdbc.queryForList(
                "select * from inv_stock_take_batch_check where take_id = ? order by id", takeId);
    }

    private int routeCode(Runnable r) {
        return assertThrows(HipBizException.class, r::run).code;
    }

    /**
     * 一次挂号 + 一条药品医嘱的最小夹具（裸 JDBC 自建科室/排班/患者，不依赖任何种子内容）。
     * 只读回顾端点若没有夹具就永远跑不到真实 {@code outp_order}，那就是「建了引擎一次没跑过」。
     */
    private long registrationWithDrugOrder(long drugId, String usageRoute) {
        Long deptId = jdbc.queryForObject(
                "insert into sys_dept(name, code, type) values (?, ?, 'DEPT') returning id",
                Long.class, "途径回顾科室", uniq("DPT"));
        Long scheduleId = jdbc.queryForObject(
                "insert into outp_schedule(dept_id, schedule_date, capacity) values (?, current_date, 10) returning id",
                Long.class, deptId);
        Long patientId = jdbc.queryForObject("""
                insert into empi_patient(patient_no, name, sex, birth_date)
                values (?, '途径回顾患者', 'F', current_date - 10000) returning id
                """, Long.class, uniq("P"));
        Long regId = jdbc.queryForObject("""
                insert into outp_registration(reg_no, patient_id, schedule_id, dept_id, visit_date, fee, status)
                values (1, ?, ?, ?, current_date, 0, 'VISITED') returning id
                """, Long.class, patientId, scheduleId, deptId);
        jdbc.update("""
                insert into outp_order(registration_id, group_no, order_type, item_id, item_code, item_name,
                                       unit, qty, unit_price, amount, usage_route, status)
                select ?, 'G1', 'DRUG', id, code, name, unit, 1, price, price, ?, 'CREATED'
                  from md_drug where id = ?
                """, regId, usageRoute, drugId);
        return regId;
    }

    // =====================================================================================
    // ① 没有伪造任何药学知识（本车道第一条纪律）
    // =====================================================================================

    /**
     * V155 <b>一条药学结论都不许内置</b>：药品适用途径、溶媒目录、配伍规则三张表必须建完即空；
     * 两张词表只许各有 1 条 {@code is_example} 示例行，且不许有非示例行。
     *
     * <p>这一条红了，意味着有人把自己"知道"的配伍禁忌写进了迁移。编出来的禁忌看起来很专业，
     * 医生会信，然后照着它改处方——那比没有这个功能危险得多。
     */
    @Test
    void migrationSeededNoPharmacologyKnowledge() {
        assertEquals(0, count("select count(*) from cdss_drug_route"),
                "cdss_drug_route 必须建完即空：药品适用途径是药学结论，不得按 dose_form 推断");
        assertEquals(0, count("select count(*) from cdss_solvent_dict"),
                "cdss_solvent_dict 必须建完即空：不得按药名判定「这是不是溶媒」");
        assertEquals(0, count("select count(*) from cdss_solvent_rule"),
                "cdss_solvent_rule 必须建完即空：配伍禁忌只能由药剂科按院内目录维护");
        assertEquals(0, count("select count(*) from cdss_route_dict where not is_example"),
                "途径词表只许有示例行，非示例行须由药剂科自己录");
        assertEquals(0, count("select count(*) from cdss_route_alias where not is_example"),
                "别名表只许有示例行");
        assertEquals(1, count("select count(*) from cdss_route_dict where is_example"),
                "示例行恰好 1 条（下发口径：空表 + 一条示例行）");
        assertEquals(1, count("select count(*) from cdss_route_alias where is_example"),
                "示例行恰好 1 条");
    }

    /**
     * 规则表空 → 覆盖率必须<b>明说</b>「校验当前不会产生任何判定」。
     * 没有这一段，「途径校验已上线」会被读成「开错途径系统会拦」。
     */
    @Test
    void coverageTellsTheTruthWhenNothingIsMaintained() {
        var c = routeRules.coverage();
        assertEquals(0, ((Number) c.get("drugsWithRouteConfigured")).intValue());
        assertEquals(Boolean.FALSE, c.get("routeCheckActive"),
                "一个药都没维护适用途径时，routeCheckActive 必须是 false，不能让人以为校验在跑");
        assertEquals(Boolean.FALSE, c.get("solventCheckActive"));
        assertTrue(String.valueOf(c.get("note")).contains("不会产生任何判定"));
    }

    // =====================================================================================
    // ② 自由文本不做脚本解析
    // =====================================================================================

    /** 归一化只做字符级：去全部空白（含全角）+ ASCII 小写；不做同义替换、不做全角折叠 */
    @Test
    void normalizationIsCharacterLevelOnly() {
        assertEquals("静脉滴注", RouteRuleService.normalizeRouteText("  静脉滴注 "));
        assertEquals("静脉滴注", RouteRuleService.normalizeRouteText("静脉　滴注"));
        assertEquals("ivgtt", RouteRuleService.normalizeRouteText("IV Gtt"));
        assertNull(RouteRuleService.normalizeRouteText("   "));
        assertNull(RouteRuleService.normalizeRouteText(null));
        // 形近而义反的两个词**必须**归一化成不同结果——否则等值查找会把它们判成同一条途径
        assertNotEquals(RouteRuleService.normalizeRouteText("静滴"),
                RouteRuleService.normalizeRouteText("静推"),
                "「静滴」(静脉滴注) 与「静推」(静脉注射) 是两条途径，归一化不得让它们相等");
    }

    /**
     * <b>本车道最要紧的一条判据</b>：未登记的用法文本，<b>即使 gate=block 也绝不拦截</b>。
     *
     * <p>「静滴」与「静推」文本相近而语义相反。任何 contains/正则都会在这组词上出错，
     * 而出错的方向要么放行该拦的、要么拦住不该拦的。故引擎只做等值查找，
     * 查不到就是查不到——看不懂的文本上做拦截，比不做更危险。
     */
    @Test
    void unrecognizedRouteTextIsNeverBlockedEvenInBlockGate() {
        long d = drug(uniq("途径未识别药"));
        routeRules.addRoute("ORALX" + seq, "口服(测试)", false, null, null);
        routeRules.addDrugRoute(d, "ORALX" + seq, "测试依据：院内用药目录 v1", null);
        cfg(RouteRuleService.GATE_ROUTE, "block");

        // 「静滴」未登记别名 → 不判定、不拦
        var r = routeRules.check(null, null,
                List.of(new RouteRuleService.OrderLine(d, "静滴", "G1")), null);
        assertFalse(r.blocked(), "未识别的用法文本在 block 档也不得拦截");
        assertTrue(r.violations().stream().anyMatch(
                        f -> RouteRuleService.K_ROUTE_UNRECOGNIZED.equals(f.kind())),
                "应回一条 ROUTE_UNRECOGNIZED 提示，让药剂科去补别名");
        // 未识别也要留痕：哪些写法没登记，只能靠真实流量发现
        assertTrue(count("select count(*) from cdss_route_alert where kind = 'ROUTE_UNRECOGNIZED'"
                + " and drug_id = " + d) > 0, "未识别须留痕，否则药剂科无从得知要补哪些别名");
        assertEquals(0, count("select count(*) from cdss_route_alert where blocked and drug_id = " + d),
                "未识别留痕的 blocked 必须恒为 false");
    }

    // =====================================================================================
    // ③ 途径判定：未维护 ≠ 禁止；warn 真的把提示给到人；block 才拦
    // =====================================================================================

    /** 药品未维护适用途径 → 不判定（不是「任何途径都不许」） */
    @Test
    void drugWithoutConfiguredRouteIsNeverJudged() {
        long d = drug(uniq("未配途径药"));
        String code = "IVD" + seq;
        routeRules.addRoute(code, "静脉滴注(测试)", true, null, null);
        routeRules.addAlias("静脉滴注", code, null);
        cfg(RouteRuleService.GATE_ROUTE, "block");

        var r = routeRules.review(List.of(new RouteRuleService.OrderLine(d, "静脉滴注", "G1")));
        assertFalse(r.blocked(), "未维护适用途径的药，任何途径都不得判成不符");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains(RouteRuleService.K_ROUTE_UNCONFIGURED)));
    }

    /** 途径不符：warn 档放行但**返回体带 warnings 且留痕**；block 档返 5661 */
    @Test
    void routeMismatchWarnsByDefaultAndBlocksWhenConfigured() {
        long d = drug(uniq("途径不符药"));
        String oral = "OR" + seq;
        String iv = "IV" + seq;
        routeRules.addRoute(oral, "口服(测试)", false, null, null);
        routeRules.addRoute(iv, "静脉滴注(测试)", true, null, null);
        routeRules.addAlias("静脉滴注", iv, null);
        routeRules.addDrugRoute(d, oral, "测试依据：说明书 2026 版", null);

        var line = List.of(new RouteRuleService.OrderLine(d, "静脉滴注", "G1"));

        // warn（默认档）：不抛、不拦，但提示必须真的到手
        cfg(RouteRuleService.GATE_ROUTE, "warn");
        var warn = routeRules.check(null, null, line, null);
        assertFalse(warn.blocked());
        assertFalse(warn.warnings().isEmpty(), "warn 档必须把提示随返回体下发，不能静默");
        assertTrue(warn.warnings().get(0).contains("途径不符"));
        assertEquals(1, count("select count(*) from cdss_route_alert"
                + " where kind = 'ROUTE_MISMATCH' and drug_id = " + d), "warn 档也要留痕");

        // block：返 5661
        cfg(RouteRuleService.GATE_ROUTE, "block");
        assertEquals(RouteRuleService.E_ROUTE,
                routeCode(() -> routeRules.check(null, null, line, null)));

        // off：完全旁路
        cfg(RouteRuleService.GATE_ROUTE, "off");
        var off = routeRules.review(line);
        assertTrue(off.violations().isEmpty(), "off 档应完全旁路");
    }

    /**
     * 只读回顾端点必须<b>真的跑在 outp_order 上</b>：读 item_id 与 usage_route、回带 orderId、
     * 且不留痕不拦截。
     *
     * <p>本车道不改 {@code DoctorStationService}（不在名下），若只留一个等别人来接的接缝，
     * 本条需求在本版就是「建了表、写了引擎、一次也没跑过」。这条用例就是那条腿。
     */
    @Test
    void reviewRegistrationRunsTheEngineOnRealOrders() {
        long d = drug(uniq("途径回顾药"));
        String oral = "ORR" + seq;
        String iv = "IVR" + seq;
        routeRules.addRoute(oral, "口服(测试)", false, null, null);
        routeRules.addRoute(iv, "静脉滴注(测试)", true, null, null);
        routeRules.addAlias("静脉滴注", iv, null);
        routeRules.addDrugRoute(d, oral, "测试依据：说明书 2026 版", null);
        long regId = registrationWithDrugOrder(d, "静脉滴注");

        var r = routeRules.reviewRegistration(regId);
        assertEquals(1, r.lineCount(), "应读到该挂号下唯一一条未取消的药品医嘱");
        assertTrue(r.violations().stream().anyMatch(f ->
                        RouteRuleService.K_ROUTE_MISMATCH.equals(f.kind()) && f.orderId() != null),
                "回顾核对必须真的读到 outp_order 的 item_id/usage_route 并回带 orderId：" + r.violations());
        assertEquals(0, count("select count(*) from cdss_route_alert where drug_id = " + d),
                "只读回顾不得留痕");
        assertFalse(r.blocked(), "只读回顾不得拦截");
    }

    /** 坏配置回落 warn，不是 off——宁可多提示，不可静默失效 */
    @Test
    void badGateValueFallsBackToWarnNotOff() {
        cfg(RouteRuleService.GATE_ROUTE, "BLOCKK");
        assertEquals("warn", routeRules.gate(RouteRuleService.GATE_ROUTE));
        cfg(RouteRuleService.GATE_SOLVENT, "");
        assertEquals("warn", routeRules.gate(RouteRuleService.GATE_SOLVENT));
    }

    // =====================================================================================
    // ④ 溶媒：未登记 ≠ 禁配
    // =====================================================================================

    /**
     * 溶媒判定三态：无规则不判定 / 有规则但该溶媒未登记不判定 / 命中 FORBID 才判违规。
     *
     * <p>中间那一条是本表最要紧的决定：把「不在白名单」当禁配，等于让一张录到一半的表
     * 去拦大批正常医嘱——用维护缺口伪装成临床问题。
     */
    @Test
    void solventUnlistedIsNotForbidden() {
        long main = drug(uniq("溶媒主药"));
        long ns = drug(uniq("溶媒生理盐水"));
        long gs = drug(uniq("溶媒葡萄糖"));
        String iv = "IVS" + seq;
        routeRules.addRoute(iv, "静脉滴注(测试)", true, null, null);
        routeRules.addAlias("静脉滴注", iv, null);
        routeRules.addDrugRoute(main, iv, "测试依据：院内用药目录", null);
        routeRules.addSolventDrug(ns, "NS" + seq, "生理盐水(测试)", "测试依据", null);
        routeRules.addSolventDrug(gs, "GS" + seq, "葡萄糖(测试)", "测试依据", null);
        cfg(RouteRuleService.GATE_SOLVENT, "block");

        var lines = List.of(new RouteRuleService.OrderLine(main, "静脉滴注", "G1"),
                new RouteRuleService.OrderLine(ns, "静脉滴注", "G1"));

        // 该药一条溶媒规则都没有 → 不判定
        var noRule = routeRules.review(lines);
        assertFalse(noRule.blocked());
        assertTrue(noRule.notes().stream().anyMatch(n -> n.contains(RouteRuleService.K_SOLVENT_UNCONFIGURED)));

        // 只配了 GS 的规则，本次用的是 NS → **未登记不等于禁配**，仍不判定
        routeRules.addSolventRule(main, "GS" + seq, "ALLOW", "测试依据", "院内目录", "可用葡萄糖", null);
        var unlisted = routeRules.review(lines);
        assertFalse(unlisted.blocked(), "不在白名单不等于禁配——一张录到一半的表不得用来拦医嘱");
        assertTrue(unlisted.notes().stream().anyMatch(n -> n.contains(RouteRuleService.K_SOLVENT_UNLISTED)));

        // 明确登记 NS 为 FORBID → 才判违规，block 档返 5662
        routeRules.addSolventRule(main, "NS" + seq, "FORBID", "测试依据：说明书 2026 版", "禁配",
                "本品遇氯化钠溶液可析出沉淀（测试用例虚构规则，非真实药学结论）", null);
        assertEquals(RouteRuleService.E_SOLVENT,
                routeCode(() -> routeRules.check(null, null, lines, null)));
    }

    /** 溶媒校验只对静脉途径生效：口服药同组即使挂着溶媒也不判 */
    @Test
    void solventCheckOnlyAppliesToIntravenousRoutes() {
        long main = drug(uniq("口服主药"));
        long ns = drug(uniq("口服组溶媒"));
        String oral = "ORS" + seq;
        routeRules.addRoute(oral, "口服(测试)", false, null, null);
        routeRules.addAlias("口服(测试写法)", oral, null);
        routeRules.addDrugRoute(main, oral, "测试依据", null);
        routeRules.addSolventDrug(ns, "NSO" + seq, "生理盐水(测试)", "测试依据", null);
        routeRules.addSolventRule(main, "NSO" + seq, "FORBID", "测试依据", "禁配", "不应被触发", null);
        cfg(RouteRuleService.GATE_SOLVENT, "block");

        var r = routeRules.review(List.of(
                new RouteRuleService.OrderLine(main, "口服(测试写法)", "G1"),
                new RouteRuleService.OrderLine(ns, "口服(测试写法)", "G1")));
        assertFalse(r.blocked(), "口服途径不跑溶媒校验——把溶媒检查铺到所有途径上只会制造噪音");
    }

    /** 审查类参数校验：空行、药品不存在、挂号不存在一律 5660（三个都不是 @Transactional 方法） */
    @Test
    void checkParameterErrorsUseCode5660() {
        assertEquals(RouteRuleService.E_PARAM, routeCode(() -> routeRules.review(List.of())));
        assertEquals(RouteRuleService.E_PARAM, routeCode(() -> routeRules.review(
                List.of(new RouteRuleService.OrderLine(-999L, "口服", "G1")))));
        assertEquals(RouteRuleService.E_PARAM, routeCode(() -> routeRules.reviewRegistration(-999L)));
    }

    /** 别名指向不存在的途径编码 → 5663。维护端与查询端走同一归一化，写错编码当场顶回去 */
    @Test
    void aliasWithDanglingRouteCodeIsRejected() {
        assertEquals(RouteRuleService.E_RULE_INPUT,
                routeCode(() -> routeRules.addAlias("口服(未登记编码)", "NO_SUCH_CODE", null)));
    }

    /**
     * 溶媒规则引用未登记的溶媒编码 → 5663。
     * 不挡的话，写错一个字母的规则会安静地永远匹配不上，而维护页上它看起来是"已经配好了"。
     */
    @Test
    void solventRuleWithUnregisteredSolventCodeIsRejected() {
        long d = drug(uniq("溶媒参数药"));
        assertEquals(RouteRuleService.E_RULE_INPUT, routeCode(() -> routeRules.addSolventRule(
                d, "NO_SUCH_SOLVENT", "FORBID", "依据", "级别", "提示", null)));
    }

    // =====================================================================================
    // ⑤ 盘点批次层收敛（v50 署名欠下的账）
    // =====================================================================================

    /**
     * 未纳入批次管理的药品：行为<b>逐字不变</b>，连一行核对留痕都不建。
     *
     * <p>这一条是既有回归的护栏：V147 零回填，绝大多数药品在批次层一粒都没有。
     * 若对未纳管药品也报差异，全院盘点会在本版上线当天全部失败。
     */
    @Test
    void stockTakeOnUnmanagedDrugBehavesExactlyAsBefore() {
        long d = drug(uniq("未纳管盘点药"));
        setStock(d, 100);
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "block");

        var view = inventoryService.createStockTake(List.of(d), null, null);
        inventoryService.enterCounts(view.id(), List.of(new InventoryService.CountEntry(d, 95)));
        var confirmed = inventoryService.confirmStockTake(view.id(), null);

        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(95, stockOf(d), "未纳管药品的盘点行为一字不变");
        assertTrue(confirmed.batchCheckWarnings().isEmpty());
        assertEquals(0, checkRows(view.id()).size(),
                "未纳管药品不建核对行——「无行」= 未纳管，与「核对通过」用 verdict=OK 的行区分");
    }

    /**
     * <b>本车道欠账的核心判据</b>：已纳管药品存在事前漂移时，block 档<b>拒绝确认</b>（5680），
     * 汇总不被拉到实存，差额不被抹平。
     */
    @Test
    void stockTakeRefusesToFlattenBatchDriftInBlockGate() {
        long d = drug(uniq("漂移盘点药"));
        setStock(d, 91);
        batchWithStock(d, 100);          // 批次层 100 vs 汇总 91：漂移 +9（拆零/旧口径入库的典型形态）
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "block");

        var view = inventoryService.createStockTake(List.of(d), null, null);
        inventoryService.enterCounts(view.id(), List.of(new InventoryService.CountEntry(d, 85)));

        assertEquals(InventoryService.E_BATCH_DRIFT,
                assertThrows(InventoryException.class,
                        () -> inventoryService.confirmStockTake(view.id(), null)).code,
                "已纳管药品有事前漂移时必须拒绝确认，而不是把差额抹平");
        assertEquals(91, stockViaJdbc(d), "拒绝确认后汇总库存不得被改动");
        assertEquals(100, batchQtyOf(d), "任何档位下盘点都不得改动批次层");
    }

    /**
     * warn 档（默认）：确认照常，但<b>不静默</b>——留痕落库 + 提示随返回体下发，
     * 且批次层的数字一个都没被改。
     */
    @Test
    void warnGateConfirmsButRecordsAndReportsTheDrift() {
        long d = drug(uniq("漂移留痕药"));
        setStock(d, 91);
        batchWithStock(d, 100);
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "warn");

        var view = inventoryService.createStockTake(List.of(d), null, null);
        inventoryService.enterCounts(view.id(), List.of(new InventoryService.CountEntry(d, 85)));
        var confirmed = inventoryService.confirmStockTake(view.id(), null);

        assertEquals(85, stockOf(d), "warn 档照常确认");
        assertEquals(100, batchQtyOf(d), "**任何档位下盘点都不改动批次层**");
        assertFalse(confirmed.batchCheckWarnings().isEmpty(),
                "warn 档必须把差异随返回体下发——只落审计表等于换个地方静默");
        assertTrue(confirmed.batchCheckWarnings().get(0).contains("批次层合计"));

        var rows = checkRows(view.id());
        assertEquals(1, rows.size());
        assertEquals("DRIFT_AND_DIFF", rows.get(0).get("verdict"));
        assertEquals(9, ((Number) rows.get(0).get("pre_drift")).intValue(),
                "pre_drift = 批次层 - 汇总，与 /balance 的 drift 同号同义");
        assertEquals(-6, ((Number) rows.get(0).get("count_delta")).intValue());
        assertEquals(false, rows.get(0).get("blocked"));
    }

    /**
     * 批次层与汇总一致、但盘出盈亏：差额<b>只进了汇总</b>，批次层没动——
     * 这是盘点当场制造的一笔新漂移，必须如实记成 UNAPPLIED_DIFF 而不是当作"盘完就对了"。
     * block 档下返 5681。
     */
    @Test
    void countDifferenceOnManagedDrugIsRecordedAsUnappliedNotSmoothed() {
        long d = drug(uniq("盘盈未落批次药"));
        setStock(d, 100);
        batchWithStock(d, 100);          // 事前无漂移
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "warn");

        var view = inventoryService.createStockTake(List.of(d), null, null);
        inventoryService.enterCounts(view.id(), List.of(new InventoryService.CountEntry(d, 104)));
        var confirmed = inventoryService.confirmStockTake(view.id(), null);

        var rows = checkRows(view.id());
        assertEquals(1, rows.size());
        assertEquals("UNAPPLIED_DIFF", rows.get(0).get("verdict"));
        assertEquals(0, ((Number) rows.get(0).get("pre_drift")).intValue());
        assertEquals(4, ((Number) rows.get(0).get("count_delta")).intValue());
        assertTrue(String.valueOf(rows.get(0).get("note")).contains("未落批次层"));
        assertFalse(confirmed.batchCheckWarnings().isEmpty());
        assertEquals(100, batchQtyOf(d), "盘点不猜「多的是哪一批」——差额按批号走 /stock-in 补");

        // block 档下同样形态必须被拒（5681：盈亏无法落到批次层）
        long d2 = drug(uniq("盘亏未落批次药"));
        setStock(d2, 50);
        batchWithStock(d2, 50);
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "block");
        var v2 = inventoryService.createStockTake(List.of(d2), null, null);
        inventoryService.enterCounts(v2.id(), List.of(new InventoryService.CountEntry(d2, 47)));
        assertEquals(InventoryService.E_BATCH_UNAPPLIED,
                assertThrows(InventoryException.class,
                        () -> inventoryService.confirmStockTake(v2.id(), null)).code);
    }

    /** 账实相符且无漂移的已纳管行，仍要落一行 verdict=OK——「无行」才等于「未纳管」 */
    @Test
    void cleanManagedLineStillWritesAnOkRowSoAbsenceMeansUnmanaged() {
        long d = drug(uniq("账实相符纳管药"));
        setStock(d, 60);
        batchWithStock(d, 60);
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "warn");

        var view = inventoryService.createStockTake(List.of(d), null, null);
        inventoryService.enterCounts(view.id(), List.of(new InventoryService.CountEntry(d, 60)));
        var confirmed = inventoryService.confirmStockTake(view.id(), null);

        var rows = checkRows(view.id());
        assertEquals(1, rows.size(), "核对通过也要留行，否则「没有行」会同时意味着未纳管与没问题");
        assertEquals("OK", rows.get(0).get("verdict"));
        assertTrue(confirmed.batchCheckWarnings().isEmpty(), "无差异不产生噪音提示");
    }

    /** off 档完全旁路：不核对、不留痕、不提示。必须是显式选择，不是默认 */
    @Test
    void offGateBypassesCompletely() {
        long d = drug(uniq("旁路盘点药"));
        setStock(d, 91);
        batchWithStock(d, 100);
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "off");

        var view = inventoryService.createStockTake(List.of(d), null, null);
        inventoryService.enterCounts(view.id(), List.of(new InventoryService.CountEntry(d, 85)));
        var confirmed = inventoryService.confirmStockTake(view.id(), null);

        assertEquals(85, stockOf(d));
        assertEquals(0, checkRows(view.id()).size());
        assertTrue(confirmed.batchCheckWarnings().isEmpty());
        assertEquals(100, batchQtyOf(d));
    }

    /** 默认档位是 warn，坏值也回落 warn（不回落 off） */
    @Test
    void stockTakeGateDefaultsToWarnAndFallsBackToWarn() {
        assertEquals("warn", jdbc.queryForObject(
                "select cfg_value from sys_config where cfg_key = ?", String.class,
                InventoryService.GATE_STOCKTAKE_BATCH), "V155 seed 必须是 warn");
        cfg(InventoryService.GATE_STOCKTAKE_BATCH, "nonsense");
        assertEquals("warn", inventoryService.batchCheckGate());
    }

    // ===================== 内部 =====================

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }
}
