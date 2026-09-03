package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.web.MasterDataController;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v43 车道C 回归：药品启用/停用（技术偏离表 1162★）。
 *
 * <p>本类兑现的是**诚信补齐**：1162 此前答"平台已实现"，而实测 md_drug.enabled 是典型的
 * 「有列没功能」——无启停端点、无前端入口、无停用原因、无取消停用，
 * 且**停用药品照样能开单**（两处 createOrders 只 findById、从不看 enabled），
 * 即"停用"这个状态在业务上根本不生效。
 *
 * <p>断言重点不在"能停用"，而在三条最容易做成花架子的地方：
 * <ol>
 *   <li><b>停用原因真的落库</b>（8015 守住必填），否则事后没人说得清药是为什么停的——
 *       这正是本条被判"假实现"的原因之一，做成一个不留原因的布尔开关等于换个地方复现；</li>
 *   <li><b>停用真的能拦住开单</b>，门诊与住院<b>两条</b>写路径都拦（8016）；</li>
 *   <li><b>被拦时零副作用</b>——订单一行未落、库存一分未扣、处方组号序列未被消耗。
 *       这是本车道唯一碰写路径的地方，只加了一个只读预检；若日后有人把这个判断挪到
 *       save() 之后（或改成"先落库再回滚"），下面的绝对断言会立刻失败。</li>
 * </ol>
 *
 * <p><b>范围外·按批次停用不做</b>：md_drug.stock 是单一聚合值，批次级在库量不落库
 * （inv_stock_in 只记入库时的批次与效期，不维护批次余量）。没有"某批次还剩多少"这一数据，
 * 按批次停用既拦不住发药（发药扣的是聚合 stock）、也答不出该批次在架量，故不做、也不留假入口。
 *
 * <p><b>测试期 JPA 一级缓存注意</b>：启停端点走 JdbcTemplate 条件更新（并发口径，见端点注释），
 * 而开单侧走 JPA findById。生产上每个请求各自一个 PersistenceContext 不受影响；
 * 本类整个用例共用一个事务/会话，故每次改状态后必须 {@code flushAndClear()}，
 * 否则开单侧读到的是停用前的实体快照——同 V42FeeCategoryTest:101 记录的那类陷阱。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "PHARMACIST"})
class V43DrugDisableTest {

    @Autowired MasterDataController masterDataController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    // ---------------- 夹具 ----------------

    private static String uniq(String prefix) {
        return prefix + System.nanoTime() % 100000000L;
    }

    /** jdbc 改状态后必须清一级缓存，否则 JPA 侧仍持旧快照（见类注释） */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * 当次唯一的测试药：abx_level=0、antibiotic=false、无过敏交叉词的药名——
     * 保证 8016 之外的既有拦截（4012 过敏 / 4013 重复 / 4014 抗菌药分级）全都不会先命中，
     * 否则用例断言的就不是本车道的东西了。
     */
    private Long newDrug() {
        String code = uniq("V43DG");
        return jdbc.queryForObject("""
                insert into md_drug(code, name, spec, unit, dose_form, price, stock,
                                    antibiotic, abx_level, drug_class, enabled)
                values (?, ?, '10mg*12片/盒', '盒', '片剂', 20.00, 100, false, 0, 'W', true)
                returning id
                """, Long.class, code, "V43测试药" + code);
    }

    private Long dedicatedDept() {
        String code = uniq("V43D");
        return jdbc.queryForObject("""
                insert into sys_dept(parent_id, name, code, type, sort_no)
                values (null, ?, ?, 'CLINIC', 999) returning id
                """, Long.class, "V43启停科" + code, code);
    }

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("V43启停患者");
        p.setSex("U");
        return patientService.register(p).getId();   // 无过敏史：不会触发 4012
    }

    /** 已接诊的门诊挂号（挂号费 0，金额断言好算） */
    private Long visitedRegistration() {
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(dedicatedDept());
        sch.setScheduleDate(BusinessDates.today());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(newPatient(), sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        return regId;
    }

    private Long inHospitalAdmission() {
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(newPatient(), dedicatedDept(), bedId, null, "J18.9", "肺炎",
                new BigDecimal("5000"), "CASH", null).getId();
    }

    private DoctorStationService.OrderLine outpLine(Long drugId) {
        return new DoctorStationService.OrderLine("DRUG", drugId, 2, "口服", "bid", "1片", 3);
    }

    private InpatientService.OrderLine inpLine(Long drugId) {
        return new InpatientService.OrderLine("DRUG", drugId, 2, "口服", "bid", "1片");
    }

    private int outpOrderCount(Long regId) {
        return jdbc.queryForObject(
                "select count(*) from outp_order where registration_id = ?", Integer.class, regId);
    }

    private int inpOrderCount(Long admId) {
        return jdbc.queryForObject(
                "select count(*) from inp_order where admission_id = ?", Integer.class, admId);
    }

    private int stockOf(Long drugId) {
        return jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
    }

    private void disableOk(Long drugId, String reason) {
        assertEquals(0, masterDataController.disableDrug(
                drugId, new MasterDataController.DrugDisableReq(reason), null).getCode());
        flushAndClear();
    }

    // ==================== ① 停用 → 原因落库 → 再停用 8014 ====================

    @Test
    void disablePersistsReasonAndRejectsRepeatedDisable() {
        Long drugId = newDrug();
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select enabled from md_drug where id = ?", Boolean.class, drugId));

        disableOk(drugId, "厂家召回，暂停使用");

        var row = jdbc.queryForMap(
                "select enabled, disable_reason, disabled_at from md_drug where id = ?", drugId);
        assertEquals(Boolean.FALSE, row.get("enabled"));
        assertEquals("厂家召回，暂停使用", row.get("disable_reason"),
                "停用原因必须真的落库——只翻一个布尔位就是把「有列没功能」换个地方复现");
        assertNotNull(row.get("disabled_at"), "停用时间必须留痕");

        // 8014：状态无需重复操作（条件更新 + 受影响行数判定，并发下也只有一方成功）
        assertEquals(8014, masterDataController.disableDrug(
                drugId, new MasterDataController.DrugDisableReq("再停一次"), null).getCode());
        assertEquals("厂家召回，暂停使用", jdbc.queryForObject(
                "select disable_reason from md_drug where id = ?", String.class, drugId),
                "被拒绝的重复停用不得覆盖首次留痕");

        // 库存不受停用影响（停用是软状态，不删数据不清库存）
        assertEquals(100, stockOf(drugId));
    }

    // ==================== ② 8016：门诊开单被拒，且零副作用 ====================

    @Test
    void disabledDrugRejectedInOutpatientOrdersWithZeroSideEffect() {
        Long drugId = newDrug();
        Long regId = visitedRegistration();

        // 停用前能开——证明后面的拒绝确实来自停用状态，而不是夹具本身开不出单
        assertDoesNotThrow(() -> doctorStationService.createOrders(regId, List.of(outpLine(drugId)), null));
        flushAndClear();
        assertEquals(1, outpOrderCount(regId));

        disableOk(drugId, "招标掉标");

        // 换一张挂号：同一张挂号再开同药会先撞既有的 4013 重复用药，测不到 8016
        Long regId2 = visitedRegistration();
        int stockBefore = stockOf(drugId);
        long seqBefore = currentGroupSeq();

        var e = assertThrows(BizException.class,
                () -> doctorStationService.createOrders(regId2, List.of(outpLine(drugId)), null));
        assertEquals(8016, e.code);
        assertTrue(e.getMessage().contains("V43测试药"), "错误信息须带药品名，否则医生不知道是哪一行被拦：" + e.getMessage());
        assertTrue(e.getMessage().contains("招标掉标"), "顺带带出停用原因，避免医生反复试同一种药：" + e.getMessage());

        // 零副作用三连
        assertEquals(0, outpOrderCount(regId2), "被 8016 拒绝时订单一行都不许落库");
        assertEquals(stockBefore, stockOf(drugId), "被 8016 拒绝时库存不得变动");
        assertEquals(seqBefore, currentGroupSeq(),
                "预检须在 nextGroupSeq() 之前：nextval 事务回滚也退不回去，"
                        + "一张不该开的单不该消耗掉一个处方组号");
    }

    /** 处方组号序列当前值（nextval 不受事务回滚影响，故可用来验证"预检真的在写之前"） */
    private long currentGroupSeq() {
        return jdbc.queryForObject("select last_value from outp_order_group_seq", Long.class);
    }

    // ==================== ③ 8016：住院开医嘱被拒，且零副作用 ====================

    @Test
    void disabledDrugRejectedInInpatientOrdersWithZeroSideEffect() {
        Long drugId = newDrug();
        Long admId = inHospitalAdmission();

        assertDoesNotThrow(() -> inpatientService.createOrders(admId, List.of(inpLine(drugId)), null));
        flushAndClear();
        assertEquals(1, inpOrderCount(admId));

        disableOk(drugId, "临床暂停使用");

        int stockBefore = stockOf(drugId);
        var e = assertThrows(InpException.class,
                () -> inpatientService.createOrders(admId, List.of(inpLine(drugId)), null));
        assertEquals(8016, e.code);
        assertTrue(e.getMessage().contains("V43测试药"), e.getMessage());

        assertEquals(1, inpOrderCount(admId), "被 8016 拒绝时不得新增任何 inp_order");
        assertEquals(stockBefore, stockOf(drugId), "被 8016 拒绝时库存不得变动");
        assertEquals(0, (int) jdbc.queryForObject("""
                select count(*) from inp_order_exec e join inp_order o on o.id = e.order_id
                where o.admission_id = ? and o.item_id = ? and o.order_nature = 'LONG'
                """, Integer.class, admId, drugId),
                "被拒绝的开单不得留下长期医嘱执行行");
    }

    /** 一批医嘱里只要有一行是停用药，整批都不落库——预检在 stream 落库之前，不是逐行放行 */
    @Test
    void oneDisabledLineRejectsTheWholeBatch() {
        Long okDrug = newDrug();
        Long badDrug = newDrug();
        disableOk(badDrug, "过期未补");
        Long admId = inHospitalAdmission();

        var e = assertThrows(InpException.class, () -> inpatientService.createOrders(
                admId, List.of(inpLine(okDrug), inpLine(badDrug)), null));
        assertEquals(8016, e.code);
        assertEquals(0, inpOrderCount(admId),
                "同批的正常药也不得落库——否则医生看到「部分成功」却不知道成了哪几行");
    }

    // ==================== ④ 启用 → 留痕清空 → 开单恢复 ====================

    @Test
    void enableClearsTraceAndRestoresOrdering() {
        Long drugId = newDrug();
        disableOk(drugId, "暂停");

        assertEquals(0, masterDataController.enableDrug(drugId).getCode());
        flushAndClear();

        var row = jdbc.queryForMap("""
                select enabled, disable_reason, disabled_at, disabled_by from md_drug where id = ?
                """, drugId);
        assertEquals(Boolean.TRUE, row.get("enabled"));
        assertNull(row.get("disable_reason"),
                "启用后须清空留痕：留着旧原因会让页面自相矛盾（已启用却显示停用原因）");
        assertNull(row.get("disabled_at"));
        assertNull(row.get("disabled_by"));

        // 8014：重复启用
        assertEquals(8014, masterDataController.enableDrug(drugId).getCode());

        // 开单恢复正常（门诊 + 住院两条路径都要恢复）
        Long regId = visitedRegistration();
        assertDoesNotThrow(() -> doctorStationService.createOrders(regId, List.of(outpLine(drugId)), null));
        Long admId = inHospitalAdmission();
        assertDoesNotThrow(() -> inpatientService.createOrders(admId, List.of(inpLine(drugId)), null));
        flushAndClear();
        assertEquals(1, outpOrderCount(regId));
        assertEquals(1, inpOrderCount(admId));
    }

    // ==================== ⑤ 8015 / 8013 错误路径 ====================

    @Test
    void blankReasonAnd404DrugReportTheirOwnCodes() {
        Long drugId = newDrug();

        // 8015 停用原因必填：null body / null 字段 / 纯空白三种写法都要拦住
        assertEquals(8015, masterDataController.disableDrug(drugId, null, null).getCode());
        assertEquals(8015, masterDataController.disableDrug(
                drugId, new MasterDataController.DrugDisableReq(null), null).getCode());
        assertEquals(8015, masterDataController.disableDrug(
                drugId, new MasterDataController.DrugDisableReq("   "), null).getCode());
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select enabled from md_drug where id = ?", Boolean.class, drugId),
                "被 8015 拒绝的停用不得留下副作用");

        // 8013 药品不存在（停用与启用两个端点都要报同一个码）
        assertEquals(8013, masterDataController.disableDrug(
                -1L, new MasterDataController.DrugDisableReq("理由充分"), null).getCode());
        assertEquals(8013, masterDataController.enableDrug(-1L).getCode());
    }

    // ==================== ⑥ 列表端点：默认口径不变 + 状态筛选 ====================

    /**
     * 本车道最容易踩坏别人的地方：{@code GET /masterdata/drugs} 是**开单侧药品选择器**的唯一
     * 数据源（门诊/住院医生站、药库两页、15 个 E2E）。加状态筛选**不得改变默认口径**——
     * 否则停用药会回到医生的下拉框里，选中即撞 8016。
     */
    @Test
    void drugListKeepsEnabledOnlyDefaultAndSupportsStatusFilter() {
        Long drugId = newDrug();
        String name = jdbc.queryForObject("select name from md_drug where id = ?", String.class, drugId);

        assertTrue(masterDataController.drugs(name, false, null).getData().stream()
                .anyMatch(d -> d.getId().equals(drugId)), "启用中的药默认可检索到");

        disableOk(drugId, "停用后应从默认列表消失");

        assertTrue(masterDataController.drugs(name, false, null).getData().stream()
                .noneMatch(d -> d.getId().equals(drugId)),
                "默认（不带参数）必须仍是「仅启用」——开单侧选择器直接消费本端点");
        assertTrue(masterDataController.drugs(name, false, true).getData().stream()
                .noneMatch(d -> d.getId().equals(drugId)), "enabled=true 同样不含停用药");
        assertTrue(masterDataController.drugs(name, true, null).getData().stream()
                .anyMatch(d -> d.getId().equals(drugId)), "all=true（维护页）应看得到停用药");

        var offOnly = masterDataController.drugs(name, false, false).getData();
        assertTrue(offOnly.stream().anyMatch(d -> d.getId().equals(drugId)), "enabled=false 应筛出停用药");
        assertTrue(offOnly.stream().allMatch(d -> !Boolean.TRUE.equals(d.getEnabled())),
                "「仅停用」筛选不得混入启用中的药");
        assertEquals("停用后应从默认列表消失",
                offOnly.stream().filter(d -> d.getId().equals(drugId)).findFirst().orElseThrow()
                        .getDisableReason(),
                "维护页要直接读实体上的停用原因，实体字段必须随 V134 补齐");
    }

    // ==================== ⑦ CSV 批量导入：行级提示、不复活、不阻断整批 ====================

    /**
     * 批量导入是实施期每周都在跑的动作。命中已停用药品时：该行照常更新资料、
     * <b>但不把它重新上架</b>，并按本仓既定的行级 errors 模式提示（v42 费用类别同款），
     * <b>整批不阻断</b>。
     */
    @Test
    void csvImportDoesNotResurrectDisabledDrugButReportsPerRow() {
        Long disabledId = newDrug();
        String disabledCode = jdbc.queryForObject(
                "select code from md_drug where id = ?", String.class, disabledId);
        disableOk(disabledId, "招标掉标");

        String newCode = uniq("V43IMP");
        String csv = """
                code,name,spec,unit,dose_form,price,stock
                %s,导入改名后的停用药,20mg*10片/盒,盒,片剂,33.00,50
                %s,V43导入新药,5mg*30片/盒,盒,片剂,44.00,60
                """.formatted(disabledCode, newCode);

        var data = masterDataController.importDrugs(csv).getData();
        assertEquals(2, data.get("imported"), "两行都必须落库——停用是行级提示，不能阻断整批");
        assertEquals(1, data.get("errorCount"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) data.get("errors");
        assertTrue(errors.get(0).contains(disabledCode) && errors.get(0).contains("第2行"),
                "行级错误须指明行号与药品编码，便于实施逐行订正：" + errors.get(0));

        var row = jdbc.queryForMap(
                "select name, price, enabled, disable_reason from md_drug where code = ?", disabledCode);
        assertEquals("导入改名后的停用药", row.get("name"), "资料照常更新");
        assertEquals(0, new BigDecimal(String.valueOf(row.get("price"))).compareTo(new BigDecimal("33.00")));
        assertEquals(Boolean.FALSE, row.get("enabled"),
                "批量导入不得静默复活药师刚停掉的药——那是没有任何日志能解释的上架");
        assertEquals("招标掉标", row.get("disable_reason"), "停用留痕不得被导入抹掉");

        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select enabled from md_drug where code = ?", Boolean.class, newCode),
                "新增行仍按启用落库（insert 分支未动）");
    }
}
