package cn.hip.server;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.masterdata.service.LabRouteService;
import cn.hip.platform.masterdata.service.LabRouteService.RuleReq;
import cn.hip.platform.masterdata.web.LabRouteRuleController;
import cn.hip.platform.masterdata.web.SpecimenTypeController;
import cn.hip.platform.masterdata.web.SpecimenTypeController.SpecimenTypeReq;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v78 车道 A：标本类型字典（V176 {@code md_specimen_type} + {@link SpecimenTypeController}）、
 * 流向规则的字典校验（5916/5917）、启用行同键唯一索引 {@code uq_lab_route_rule_key} 的 5913 第二道腿、
 * {@code emr_template_grant} 历史行回填的幂等。
 *
 * <p>除 {@link #uniqueIndexIsTheSecondLegOf5913} 外全部 {@code @Transactional}：只断言落库值与返回码，
 * 不测回滚语义（方法论④）。那一个用例必须真提交：PG 撞唯一索引后整个事务 aborted、同连接上任何后续语句都报
 * "current transaction is aborted"，在事务用例里既做不了后续断言、也演不出"读-判-写没看见对方、索引兜住"的竞态。
 * 它自己造数、finally 里自己清（方法论⑨：并发用例要证明竞争真的发生——用 pg_stat_activity 等到产品路径的 insert
 * 确实阻塞在锁上才提交对方事务，并按文案断言走的是索引那条腿，而不是读-判-写那条）。
 *
 * <p>被测写路径只走 {@link SpecimenTypeController} / {@link LabRouteRuleController}；造前置数据（科室、收费项目、
 * 模板行）走 jdbc。回填幂等用例执行的是 <b>V176 文件里原样截取</b>的 SQL（@backfill-begin/@backfill-end 之间），不另抄一份。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V78SpecimenDictTest {

    @Autowired SpecimenTypeController dict;
    @Autowired LabRouteRuleController ruleController;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate txTemplate;

    static final List<String> SEED_CODES = List.of("WB", "SER", "PLA", "UR", "ST", "SP", "TS", "SEC", "CSF", "EFF", "BM", "TIS", "OTH");

    // ==================== 造数 ====================

    private Long newDept(String code) {
        return jdbc.queryForObject("""
                insert into sys_dept(name, code, type, sort_no, enabled) values (?, ?, 'MEDTECH', 99, true) returning id
                """, Long.class, "标本字典用例科室" + code, code);
    }

    private Long newLabItem(String code) {
        return jdbc.queryForObject("""
                insert into md_charge_item(code, name, category, unit, price, enabled)
                values (?, ?, 'LAB', '次', 30.00, true) returning id
                """, Long.class, code, "标本字典用例项目-" + code);
    }

    private Long create(String code, String name) {
        return (Long) dict.create(new SpecimenTypeReq(code, name, null)).getData().get("id");
    }

    private Long rule(String name, Long itemId, String spec, Long execDept) {
        return (Long) ruleController.create(new RuleReq(name, itemId, spec, null, execDept, null, null)).getData().get("id");
    }

    private int codeOf(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(HipBizException.class, e).code;
    }

    private Map<String, Object> row(Long id, boolean all) {
        return dict.list(all).getData().stream()
                .filter(m -> ((Number) m.get("id")).longValue() == id).findFirst().orElse(null);
    }

    private Map<String, Object> dbRow(Long id) {
        return jdbc.queryForMap("select code, name, sort_no, enabled from md_specimen_type where id = ?", id);
    }

    // ==================== 种子 + 列表 ====================

    @Test
    void seedHasThirteenRowsAndListHidesDisabledByDefault() {
        List<String> seeded = jdbc.queryForList(
                "select code from md_specimen_type where code = any(?) order by sort_no, code", String.class,
                (Object) SEED_CODES.toArray(String[]::new));
        assertEquals(SEED_CODES, seeded, "V176 种子 13 行按 sort_no 顺序");
        assertEquals("全血", jdbc.queryForObject("select name from md_specimen_type where code = 'WB'", String.class));

        var all = dict.list(true).getData();
        var first = all.get(0);
        assertEquals(Set.of("id", "code", "name", "sortNo", "enabled", "ruleCount"), first.keySet(), "返回键固定");

        Long id = create("t78l1", "列表用例标本");
        assertNotNull(row(id, false), "新建默认启用、默认列表可见");
        dict.setEnabled(id, false);
        assertNull(row(id, false), "停用后默认列表不可见");
        Map<String, Object> hidden = row(id, true);
        assertNotNull(hidden, "all=true 可见");
        assertEquals(Boolean.FALSE, hidden.get("enabled"));
        assertEquals("T78L1", hidden.get("code"), "编码大写落库");
        assertEquals(0L, ((Number) hidden.get("ruleCount")).longValue());
        // 排序：sort_no 升序再 code
        dict.update(id, new SpecimenTypeReq(null, "列表用例标本", 5));
        var ids = dict.list(true).getData().stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        assertEquals(id, ids.get(0), "sort_no=5 排在种子（10 起）之前");
    }

    // ==================== CRUD 与 5915 / 5916 / 4000 ====================

    @Test
    void crudWithErrorCodes5915And5916AndGeneric4000() {
        Long id = create(" t78c1 ", "  CRUD用例标本  ");
        Map<String, Object> db = dbRow(id);
        assertEquals("T78C1", db.get("code"), "编码 strip + 大写");
        assertEquals("CRUD用例标本", db.get("name"), "名称 strip");
        assertEquals(0, db.get("sort_no"), "sortNo 缺省 0");
        assertEquals(Boolean.TRUE, db.get("enabled"));

        // 5915：编码重复（含大小写不同）/ 名称重复 / 名称只差空白（规范化相同）；种子码同样挡
        assertEquals(5915, codeOf(() -> create("t78C1", "另一个名字")));
        assertEquals(5915, codeOf(() -> create("T78C2", "CRUD用例标本")));
        assertEquals(5915, codeOf(() -> create("T78C2", "CRUD 用例 标本")), "名称只差空白 → 规范化相同 → 5915");
        assertEquals(5915, codeOf(() -> create("wb", "随便")), "种子编码 WB 大小写不同也挡");
        assertEquals(5915, codeOf(() -> create("T78C2", "全血")), "种子名称也挡");
        assertEquals(1, (int) jdbc.queryForObject("select count(*) from md_specimen_type where code like 'T78C%'", Integer.class),
                "被拒的全部零落库");

        // 4000：编码缺失/不合规/超长，名称缺失/超长
        assertEquals(4000, codeOf(() -> create("", "x")));
        assertEquals(4000, codeOf(() -> create("含 空格", "x")));
        assertEquals(4000, codeOf(() -> create("X".repeat(17), "x")));
        assertEquals(4000, codeOf(() -> create("T78C3", "   ")));
        assertEquals(4000, codeOf(() -> create("T78C3", "x".repeat(33))));
        assertEquals(4000, codeOf(() -> dict.update(id, new SpecimenTypeReq(null, "", 1))));

        // 编辑：改名/排序；code 不可改（body 里给了也忽略）
        assertEquals(0, dict.update(id, new SpecimenTypeReq("WANT_CHANGE", "改名后", 7)).getCode());
        db = dbRow(id);
        assertEquals("T78C1", db.get("code"), "编码建档后不可改");
        assertEquals("改名后", db.get("name"));
        assertEquals(7, db.get("sort_no"));
        // 改名撞其他行 → 5915；改成自己（只变排序）不撞
        assertEquals(5915, codeOf(() -> dict.update(id, new SpecimenTypeReq(null, "血清", 7))));
        assertEquals(0, dict.update(id, new SpecimenTypeReq(null, "改名后", 8)).getCode());

        // 5916：不存在
        assertEquals(5916, codeOf(() -> dict.update(987654321L, new SpecimenTypeReq(null, "x", null))));
        assertEquals(5916, codeOf(() -> dict.setEnabled(987654321L, false)));
        assertEquals(5916, codeOf(() -> dict.delete(987654321L)));

        // 启停幂等；删除真删
        assertEquals(0, dict.setEnabled(id, false).getCode());
        assertEquals(0, dict.setEnabled(id, false).getCode(), "已是目标状态也成功");
        assertEquals(0, dict.setEnabled(id, true).getCode());
        assertEquals(0, dict.delete(id).getCode());
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from md_specimen_type where id = ?", Integer.class, id));
    }

    // ==================== 引用守卫 5917 + ruleCount ====================

    @Test
    void disableDeleteRenameOfReferencedItemIs5917AndRuleCountFollowsEnabledRules() {
        Long dept = newDept("T78RD");
        Long lab = newLabItem("T78R1");
        Long id = create("T78R", "守卫用例标本");
        // 规则填的是带空白的变体：规范化后等于字典名 → 放行、落库规范化值
        Long r1 = rule("引用字典项", lab, " 守卫 用例标本 ", dept);
        assertEquals("守卫用例标本", jdbc.queryForObject("select specimen_type from lab_route_rule where id = ?", String.class, r1));
        assertEquals(1L, ((Number) row(id, true).get("ruleCount")).longValue(), "ruleCount 按规范化值等值计");

        assertEquals(5917, codeOf(() -> dict.setEnabled(id, false)), "被启用规则引用 → 不能停用");
        assertEquals(5917, codeOf(() -> dict.delete(id)), "被启用规则引用 → 不能删除");
        assertEquals(5917, codeOf(() -> dict.update(id, new SpecimenTypeReq(null, "改成别的名", null))),
                "被启用规则引用 → 不能改名（规则存的是规范化名称，改名会让它们永远不再命中）");
        assertEquals(0, dict.update(id, new SpecimenTypeReq(null, "守卫 用例标本", 3)).getCode(),
                "只变空白/排序（规范化值不变）不算改名，放行");
        assertEquals(Boolean.TRUE, dbRow(id).get("enabled"));
        assertEquals("守卫 用例标本", dbRow(id).get("name"));
        assertEquals(1, (int) jdbc.queryForObject("select count(*) from md_specimen_type where code = 'T78R'", Integer.class));

        // 停用规则不算引用：停掉规则后 ruleCount 归零，字典项可停用、可删
        ruleController.setEnabled(r1, false);
        assertEquals(0L, ((Number) row(id, true).get("ruleCount")).longValue());
        assertEquals(0, dict.setEnabled(id, false).getCode());
        assertEquals(0, dict.setEnabled(id, true).getCode());
        assertEquals(0, dict.update(id, new SpecimenTypeReq(null, "改成别的名", null)).getCode());
        assertEquals(0, dict.delete(id).getCode());
        // 已停用的规则仍在（删字典项不级联规则；它再启用时会撞 5916）
        assertEquals(1, (int) jdbc.queryForObject("select count(*) from lab_route_rule where id = ?", Integer.class, r1));
        assertEquals(5916, codeOf(() -> ruleController.update(r1, new RuleReq("再编辑", lab, "守卫用例标本", null, dept, null, null))));
    }

    // ==================== 规则侧字典校验 5916 / 5917 ====================

    @Test
    void ruleSpecimenMustBeAnEnabledDictionaryEntry() {
        Long dept = newDept("T78VD");
        Long lab = newLabItem("T78V1");

        // 字典外值 → 5916（新增 / 编辑同码）
        assertEquals(5916, codeOf(() -> rule("字典外", lab, "T78字典外标本", dept)));
        // 已停用字典项 → 5917
        Long off = create("T78V", "停用用例标本");
        dict.setEnabled(off, false);
        assertEquals(5917, codeOf(() -> rule("停用项", lab, "停用用例标本", dept)));
        // 字段 4000（超长）先于字典校验
        assertEquals(4000, codeOf(() -> rule("超长", lab, "x".repeat(33), dept)));
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from lab_route_rule where exec_dept_id = ?", Integer.class, dept),
                "被拒零落库");

        // 种子项放行；空白变体、大小写变体按规范化匹配
        Long r1 = rule("种子全血", lab, "全血", dept);
        Long r2 = rule("种子尿液带空白", lab, " 尿　液 ", dept);
        assertEquals("尿液", jdbc.queryForObject("select specimen_type from lab_route_rule where id = ?", String.class, r2));
        // 不填标本（通配）不受字典约束
        Long r3 = rule("通配", lab, null, dept);
        assertNull(jdbc.queryForObject("select specimen_type from lab_route_rule where id = ?", String.class, r3));
        // 编辑改成字典外值 → 5916，原行不动
        assertEquals(5916, codeOf(() -> ruleController.update(r1, new RuleReq("种子全血", lab, "静脉血", null, dept, null, null))));
        assertEquals("全血", jdbc.queryForObject("select specimen_type from lab_route_rule where id = ?", String.class, r1));
        // 启用一条引用了"事后被停用的字典项"的规则：规则侧 setEnabled 不重校字典（字典停用守卫已保证"被启用规则引用的项停不掉"，
        // 这里的规则是停用态、不算引用；再启用它时 5913 判同键，不判字典——规则落库值已是规范化名，匹配照旧）
        Long wb = jdbc.queryForObject("select id from md_specimen_type where code = 'WB'", Long.class);
        ruleController.setEnabled(r1, false);
        assertEquals(0L, ((Number) row(wb, true).get("ruleCount")).longValue(), "本用例里全血只被 r1 引用，停用 r1 后归零");
        assertEquals(0, ruleController.setEnabled(r1, true).getCode());
        assertEquals(1L, ((Number) row(wb, true).get("ruleCount")).longValue());
        // 列表里 ruleCount 与 dict 规范化口径一致：尿液被 r2 引用
        Long ur = jdbc.queryForObject("select id from md_specimen_type where code = 'UR'", Long.class);
        assertEquals(1L, ((Number) row(ur, false).get("ruleCount")).longValue());
    }

    // ==================== 权限 ====================

    @Test
    @WithMockUser(roles = "TECHNICIAN")
    void technicianCanWrite() {
        Long id = create("T78T1", "技师建的标本");
        assertEquals(0, dict.update(id, new SpecimenTypeReq(null, "技师改的标本", 1)).getCode());
        assertEquals(0, dict.setEnabled(id, false).getCode());
        assertEquals(0, dict.delete(id).getCode());
    }

    @Test
    @WithMockUser(roles = "DOCTOR_OUTP")
    void doctorCanReadButNotWrite() {
        assertFalse(dict.list(false).getData().isEmpty(), "读登录即可");
        assertThrows(AccessDeniedException.class, () -> dict.create(new SpecimenTypeReq("T78K1", "x", null)));
        assertThrows(AccessDeniedException.class, () -> dict.update(1L, new SpecimenTypeReq(null, "x", null)));
        assertThrows(AccessDeniedException.class, () -> dict.setEnabled(1L, false));
        assertThrows(AccessDeniedException.class, () -> dict.delete(1L));
    }

    // ==================== 回填幂等 ====================

    /** 从 V176 文件原样截取 @backfill-begin/@backfill-end 之间的语句 */
    private List<String> backfillStatements() throws Exception {
        String sql = new ClassPathResource("db/migration/V176__v78_specimen_dict_rule_index_grant_backfill.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int b = sql.indexOf("-- @backfill-begin");
        int e = sql.indexOf("-- @backfill-end");
        assertTrue(b > 0 && e > b, "V176 须保留 @backfill-begin/@backfill-end 标记");
        String section = sql.substring(b + "-- @backfill-begin".length(), e);
        return java.util.Arrays.stream(section.split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private int grantsOf(Long templateId) {
        return jdbc.queryForObject("select count(*) from emr_template_grant where template_id = ?", Integer.class, templateId);
    }

    @Test
    void grantBackfillOnlyAddsMissingRowsAndIsIdempotent() throws Exception {
        Long dept = newDept("T78GD");
        Long admin = jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
        // ① 历史科室模板：无授权行（V138 之前建的那 32 张的形态），created_by 有值
        Long legacyDept = jdbc.queryForObject("""
                insert into emr_template(dept_id, name, content, template_type, scope, enabled, created_by)
                values (?, 'T78历史科室', '正文', 'EMR', 'DEPT', true, ?) returning id
                """, Long.class, dept, admin);
        // ② 科室模板已有自动授权行：不得重复补
        Long grantedDept = jdbc.queryForObject("""
                insert into emr_template(dept_id, name, content, template_type, scope, enabled)
                values (?, 'T78已授权科室', '正文', 'EMR', 'DEPT', true) returning id
                """, Long.class, dept);
        jdbc.update("insert into emr_template_grant(template_id, grantee_type, grantee_id) values (?, 'DEPT', ?)", grantedDept, dept);
        // ③ 个人模板无授权行，created_by 为空 → 补 (USER, owner)，granted_by 为 null
        Long legacyPersonal = jdbc.queryForObject("""
                insert into emr_template(name, content, template_type, scope, enabled, owner_id)
                values ('T78历史个人', '正文', 'EMR', 'PERSONAL', true, ?) returning id
                """, Long.class, admin);
        // ④ owner 为空的个人行、全院行：不补
        Long orphanPersonal = jdbc.queryForObject("""
                insert into emr_template(name, content, template_type, scope, enabled)
                values ('T78孤儿个人', '正文', 'EMR', 'PERSONAL', true) returning id
                """, Long.class);
        Long hospital = jdbc.queryForObject("""
                insert into emr_template(name, content, template_type, scope, enabled)
                values ('T78全院', '正文', 'EMR', 'HOSPITAL', true) returning id
                """, Long.class);

        int before = jdbc.queryForObject("select count(*) from emr_template_grant", Integer.class);
        List<String> stmts = backfillStatements();
        assertEquals(2, stmts.size(), "两条 insert（DEPT / PERSONAL）");
        for (String s : stmts) jdbc.execute(s);
        int afterFirst = jdbc.queryForObject("select count(*) from emr_template_grant", Integer.class);
        assertEquals(before + 2, afterFirst, "第一遍：只给 ① 与 ③ 各补一行");
        for (String s : stmts) jdbc.execute(s);
        assertEquals(afterFirst, (int) jdbc.queryForObject("select count(*) from emr_template_grant", Integer.class),
                "第二遍：零新增（幂等）");

        assertEquals(1, grantsOf(legacyDept));
        Map<String, Object> g1 = jdbc.queryForMap(
                "select grantee_type, grantee_id, granted_by from emr_template_grant where template_id = ?", legacyDept);
        assertEquals("DEPT", g1.get("grantee_type"));
        assertEquals(dept, ((Number) g1.get("grantee_id")).longValue());
        assertEquals(admin, ((Number) g1.get("granted_by")).longValue(), "granted_by 取 created_by");
        assertEquals(1, grantsOf(grantedDept), "已有授权行的不重复补");
        assertEquals(1, grantsOf(legacyPersonal));
        Map<String, Object> g3 = jdbc.queryForMap(
                "select grantee_type, grantee_id, granted_by from emr_template_grant where template_id = ?", legacyPersonal);
        assertEquals("USER", g3.get("grantee_type"));
        assertEquals(admin, ((Number) g3.get("grantee_id")).longValue());
        assertNull(g3.get("granted_by"), "created_by 为空 → granted_by 为空，不编造");
        assertEquals(0, grantsOf(orphanPersonal));
        assertEquals(0, grantsOf(hospital));
    }

    // ==================== 唯一索引：5913 的第二道腿（真提交） ====================

    /**
     * 非事务（见类注释）。(a) 直写两条同键启用规则被索引拒绝（含 null 键：nulls not distinct），同键停用行放行；
     * (b) 竞态：对方事务先插入同键规则但不提交 → 产品路径 create 的读-判-写看不见它（READ COMMITTED）→ insert 阻塞在索引锁上
     * → 对方提交 → 产品路径撞索引 → 控制器翻成 5913；库里该键只剩对方那一条。
     * 用 pg_stat_activity 等到产品路径的 insert 真的阻塞了才让对方提交——否则"5913"可能来自读-判-写那条腿，不是在测索引。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void uniqueIndexIsTheSecondLegOf5913() throws Exception {
        String tag = "T78X" + (System.nanoTime() % 100000);
        Long dept = null;
        Long lab = null;
        try {
            dept = newDept(tag);
            lab = newLabItem(tag);
            final Long d = dept, item = lab;

            // (a) 索引存在且口径 = checkDuplicate：null 键彼此相等；停用行不占键
            jdbc.update("insert into lab_route_rule(name, charge_item_id, exec_dept_id) values (?, ?, ?)", tag + " 直写1", item, d);
            assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                    "insert into lab_route_rule(name, charge_item_id, exec_dept_id) values (?, ?, ?)", tag + " 直写2", item, d),
                    "同键（标本/开单科室皆 null）第二条启用行被 uq_lab_route_rule_key 拒绝");
            jdbc.update("insert into lab_route_rule(name, charge_item_id, exec_dept_id, enabled) values (?, ?, ?, false)",
                    tag + " 直写停用", item, d);
            assertEquals(2, (int) jdbc.queryForObject("select count(*) from lab_route_rule where exec_dept_id = ?", Integer.class, d),
                    "停用的同键行放行");

            // (b) 竞态
            CountDownLatch inserted = new CountDownLatch(1);
            AtomicBoolean sawBlocked = new AtomicBoolean(false);
            Thread rival = new Thread(() -> txTemplate.execute(status -> {
                jdbc.update("insert into lab_route_rule(name, charge_item_id, specimen_type, exec_dept_id) values (?, ?, '全血', ?)",
                        tag + " 对方未提交", item, d);
                inserted.countDown();
                // 等产品路径的 insert 阻塞在锁上（最多 15 秒），再提交
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (System.nanoTime() < deadline) {
                    // pg_stat_activity 在一个事务内是冻结快照（stats_fetch_consistency=cache），不清就永远看不到对方阻塞
                    jdbc.execute("select pg_stat_clear_snapshot()");
                    Integer blocked = jdbc.queryForObject("""
                            select count(*) from pg_stat_activity
                            where datname = current_database() and state = 'active'
                              and wait_event_type = 'Lock' and query ilike 'insert into lab_route_rule%'
                            """, Integer.class);
                    if (blocked != null && blocked > 0) {
                        sawBlocked.set(true);
                        break;
                    }
                    try { Thread.sleep(30); } catch (InterruptedException ignored) { break; }
                }
                return null;
            }), "v78-rival-writer");
            rival.start();
            assertTrue(inserted.await(15, TimeUnit.SECONDS), "对方事务应已插入未提交");

            HipBizException ex = assertThrows(HipBizException.class,
                    () -> ruleController.create(new RuleReq(tag + " 产品路径", item, "全血", null, d, null, null)));
            rival.join(TimeUnit.SECONDS.toMillis(20));
            assertTrue(sawBlocked.get(), "产品路径的 insert 必须真的阻塞在索引锁上（否则没形成竞争，测的不是索引）");
            assertEquals(5913, ex.code, "撞索引翻成 5913：" + ex.getMessage());
            assertTrue(ex.getMessage().contains("同时写入"), "须是索引那条腿的文案：" + ex.getMessage());
            assertEquals(1, (int) jdbc.queryForObject(
                    "select count(*) from lab_route_rule where enabled and charge_item_id = ? and specimen_type = '全血'", Integer.class, item),
                    "该键只剩对方那一条，产品路径那条已回滚");
            assertEquals(tag + " 对方未提交", jdbc.queryForObject(
                    "select name from lab_route_rule where enabled and charge_item_id = ? and specimen_type = '全血'", String.class, item));
        } finally {
            if (dept != null) jdbc.update("delete from lab_route_rule where exec_dept_id = ?", dept);
            if (lab != null) jdbc.update("delete from md_charge_item where id = ?", lab);
            if (dept != null) jdbc.update("delete from sys_dept where id = ?", dept);
        }
    }
}
