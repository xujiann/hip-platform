package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.web.MasterDataController;
import cn.hip.server.web.StatsController;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v42 车道4 回归：费用类别字典 + 费别/费用类别金额汇总报表。
 *
 * <p>本类兑现的是**诚信补齐**：技术偏离表-v2.csv 的 1034★「拥有费别分类的金额汇总」、
 * 675「按费用类别统计门诊总费用/退费金额」、3684「按医保政策、费用类别维度统计医保患者住院费用」
 * 三条此前已答"平台已实现"而代码零实现。断言因此不止验证"能跑"，还把两条最容易走样的口径钉死：
 * <ol>
 *   <li><b>费别汇总必须覆盖自费单与"参保人付现金"的单</b>——这正是 yb_settle_split 方案会漏掉的两类
 *       （该表只在 pay_method='YB' 时写入）。用例显式断言这两张单在 yb_settle_split 里**一行都没有**，
 *       却仍出现在报表里；若有人日后把实现换成查 yb_settle_split，这两个断言必然失败。
 *   <li><b>未知/孤儿费别值归入「其他」行而非被丢弃</b>——join 写错会让这些患者的钱凭空消失。
 * </ol>
 *
 * <p>脏库对策（Phase113FinanceTest 方法论⑦）：费用类别报表用**当次唯一的费用类别码**隔离，
 * 该码在全表无任何历史行 → 绝对断言恒真；费别报表是全院按费别聚合、无法隔离，
 * 改用「同一费别行的前后差值」断言，同样与残留无关。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = {"ADMIN", "QUALITY", "CASHIER"})
class V42FeeCategoryTest {

    @Autowired MasterDataController masterDataController;
    @Autowired StatsController statsController;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired InpatientService inpatientService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private final String month = YearMonth.from(BusinessDates.today()).toString();

    // ---------------- 夹具 ----------------

    private static String uniq(String prefix) {
        return prefix + System.nanoTime() % 100000000L;
    }

    private static BigDecimal big(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }

    /** 当次唯一的费用类别：全表零历史挂靠，费用类别报表的绝对断言才成立 */
    private String dedicatedCategory() {
        String code = uniq("V42FC");
        var r = masterDataController.createFeeCategory(
                new MasterDataController.FeeCategoryReq(code, "V42测试类别" + code, 500, true, null, null));
        assertEquals(0, r.getCode(), "新建费用类别应成功");
        return code;
    }

    private Long categoryId(String code) {
        return jdbc.queryForObject("select id from md_fee_category where code = ?", Long.class, code);
    }

    /** 挂在指定费用类别上的收费项目（LAB 业务类别，价格自定，便于金额断言好算） */
    private Long dedicatedChargeItem(String feeCategoryCode, String price) {
        String code = uniq("V42CI");
        return jdbc.queryForObject("""
                insert into md_charge_item(code, name, category, unit, price, fee_category_code, enabled)
                values (?, ?, 'LAB', '次', ?::numeric, ?, true) returning id
                """, Long.class, code, "V42测试项目" + code, price, feeCategoryCode);
    }

    private Long dedicatedDept() {
        String code = uniq("V42D");
        return jdbc.queryForObject("""
                insert into sys_dept(parent_id, name, code, type, sort_no)
                values (null, ?, ?, 'CLINIC', 999) returning id
                """, Long.class, "V42费用科" + code, code);
    }

    /** 参保类型在建档时写入（同 InsuranceSplitTest）：建档后再 jdbc 改，JPA 侧仍持旧快照 */
    private Long newPatient(String name, String insuranceType) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        if (insuranceType != null) {
            p.setInsuranceType(insuranceType);
        }
        return patientService.register(p).getId();
    }

    /** 挂号（挂号费 fee 元）+ 一条指定收费项目的检验医嘱；返回挂号 id */
    private Long regWithItem(Long patientId, Long itemId, int qty, BigDecimal fee) {
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(dedicatedDept());
        sch.setDoctorId(null);
        sch.setScheduleDate(BusinessDates.today());
        sch.setFee(fee);
        sch.setCapacity(9);
        sch = scheduleRepository.save(sch);
        Long regId = registrationService.register(patientId, sch.getId()).getId();
        doctorStationService.startVisit(regId, null);
        doctorStationService.createOrders(regId,
                List.of(new DoctorStationService.OrderLine("LAB", itemId, qty, null, null, null, null)), null);
        entityManager.flush();
        return regId;
    }

    private Long freeBed() {
        return jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> insRows() {
        return (List<Map<String, Object>>) statsController.feeByInsurance(month).getData().get("rows");
    }

    /** 某费别行的 [门诊笔数, 门诊金额, 住院笔数, 住院金额]；无该行按 0（脏库下只做差值断言） */
    private BigDecimal[] insRow(String insuranceType) {
        return insRows().stream()
                .filter(r -> insuranceType.equals(r.get("insurance_type")))
                .findFirst()
                .map(r -> new BigDecimal[]{big(r.get("outp_bills")), big(r.get("outp_amount")),
                        big(r.get("inp_bills")), big(r.get("inp_amount"))})
                .orElse(new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> catRows(String insuranceType) {
        return (List<Map<String, Object>>) statsController.feeByCategory(month, insuranceType).getData().get("rows");
    }

    private Map<String, Object> catRow(String code, String insuranceType) {
        return catRows(insuranceType).stream()
                .filter(r -> code.equals(r.get("category_code")))
                .findFirst().orElse(null);
    }

    // ==================== ① 字典 CRUD 与 4860–4864 全部错误路径 ====================

    @Test
    void feeCategoryCrudCoversAllErrorCodes() {
        String code = dedicatedCategory();

        // 4860 码重复
        assertEquals(4860, masterDataController.createFeeCategory(
                new MasterDataController.FeeCategoryReq(code, "重名类别", 1, true, null, null)).getCode(),
                "同码再建应报 4860");

        // 4862 码/名称空（两个方向各测一次）
        assertEquals(4862, masterDataController.createFeeCategory(
                new MasterDataController.FeeCategoryReq("  ", "有名字没码", 1, true, null, null)).getCode());
        assertEquals(4862, masterDataController.createFeeCategory(
                new MasterDataController.FeeCategoryReq(uniq("V42X"), "   ", 1, true, null, null)).getCode());

        Long id = categoryId(code);
        assertEquals(4862, masterDataController.updateFeeCategory(id,
                new MasterDataController.FeeCategoryReq(code, "", 1, true, null, null)).getCode(),
                "改名成空应报 4862");

        // 4861 类别不存在
        assertEquals(4861, masterDataController.updateFeeCategory(-1L,
                new MasterDataController.FeeCategoryReq("X", "X", 1, true, null, null)).getCode());
        assertEquals(4861, masterDataController.deleteFeeCategory(-1L).getCode());

        // 正常改名 + 停用（无挂靠，允许）
        assertEquals(0, masterDataController.updateFeeCategory(id,
                new MasterDataController.FeeCategoryReq(code, "改名后的类别", 7, false, null, null)).getCode());
        var row = jdbc.queryForMap("select * from md_fee_category where id = ?", id);
        assertEquals("改名后的类别", row.get("name"));
        assertEquals(7, ((Number) row.get("sort_no")).intValue());
        assertEquals(Boolean.FALSE, row.get("enabled"));

        // 停用后不再出现在默认（挂类下拉用）列表里，但 all=true 仍可见
        assertTrue(masterDataController.feeCategories(false).getData().stream()
                .noneMatch(r -> code.equals(r.get("code"))), "停用类别不得出现在挂类下拉");
        assertTrue(masterDataController.feeCategories(true).getData().stream()
                .anyMatch(r -> code.equals(r.get("code"))), "all=true 应含停用类别");

        assertEquals(0, masterDataController.deleteFeeCategory(id).getCode(), "无挂靠时可删除");
    }

    /** 4863：仍有项目挂靠时既不许停用也不许删除——否则费用会在「未分类」与原类别之间漂移 */
    @Test
    void cannotDisableOrDeleteCategoryWithItemsAttached() {
        String code = dedicatedCategory();
        Long id = categoryId(code);
        Long itemId = dedicatedChargeItem(code, "30.00");

        assertEquals(1L, ((Number) masterDataController.feeCategories(true).getData().stream()
                .filter(r -> code.equals(r.get("code"))).findFirst().orElseThrow()
                .get("item_count")).longValue(), "字典行应带出挂靠数（维护页据此判断能否停用）");

        assertEquals(4863, masterDataController.updateFeeCategory(id,
                new MasterDataController.FeeCategoryReq(code, "仍在用", 1, false, null, null)).getCode(),
                "有挂靠时停用应报 4863");
        assertEquals(4863, masterDataController.deleteFeeCategory(id).getCode(),
                "有挂靠时删除应报 4863");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select enabled from md_fee_category where id = ?", Boolean.class, id),
                "被拒绝的停用不得留下副作用");

        // 改挂空后即可停用
        assertEquals(0, masterDataController.updateChargeItemAttrs(itemId,
                new MasterDataController.ItemAttrReq(null, null)).getCode());
        assertEquals(0, masterDataController.updateFeeCategory(id,
                new MasterDataController.FeeCategoryReq(code, "可以停了", 1, false, null, null)).getCode());
    }

    /** 挂类/自费维护：4861 未知类别、4864 项目不存在；self_pay 落库（补 V116 孤儿列） */
    @Test
    void itemAttrsValidateCategoryAndPersistSelfPayFlag() {
        String code = dedicatedCategory();
        Long itemId = dedicatedChargeItem(null, "30.00");

        assertEquals(4861, masterDataController.updateChargeItemAttrs(itemId,
                new MasterDataController.ItemAttrReq("NO_SUCH_CATEGORY", null)).getCode());
        assertEquals(4864, masterDataController.updateChargeItemAttrs(-1L,
                new MasterDataController.ItemAttrReq(code, null)).getCode());

        assertEquals(0, masterDataController.updateChargeItemAttrs(itemId,
                new MasterDataController.ItemAttrReq(code, true)).getCode());
        var row = jdbc.queryForMap("select fee_category_code, self_pay from md_charge_item where id = ?", itemId);
        assertEquals(code, row.get("fee_category_code"));
        assertEquals(Boolean.TRUE, row.get("self_pay"),
                "self_pay 必须真能写进去——V116 建了该列却无实体字段/无导入/无前端，"
                        + "使 InpatientService.isSelfPayItem 恒为 false、emr.gate.consent.selfpay 永久空转");

        // 药品侧同一条路径
        Long drugId = jdbc.queryForObject("""
                insert into md_drug(code, name, spec, unit, price, stock, antibiotic, enabled)
                values (?, ?, '测试规格', '盒', 10.00, 100, false, true) returning id
                """, Long.class, uniq("V42DG"), "V42测试药");
        assertEquals(0, masterDataController.updateDrugAttrs(drugId,
                new MasterDataController.ItemAttrReq(code, true)).getCode());
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select self_pay from md_drug where id = ?", Boolean.class, drugId));
    }

    // ==================== ② V132 回填 + 外部边界（不预置国标码） ====================

    /**
     * V132 的挂类回填必须真的落到了既有主数据上，且**绝不预置任何医保国标码值**。
     *
     * <p>后一条是硬约束：医保"15 大类"等码表随各地医保局版本走，本仓无权威来源，自造即为伪造，
     * 而其消费场景（医保结算清单、病案首页上报）是明确的诚信红线。若有人日后往种子里塞码值，
     * 这个断言会立刻失败。
     */
    @Test
    void migrationBackfilledCategoriesAndPresetNoNationalCodes() {
        assertTrue(jdbc.queryForObject(
                "select count(*) from md_charge_item where category = 'LAB' and fee_category_code = 'LAB'",
                Integer.class) > 0, "V132 应把既有 LAB 收费项目回填为化验费");
        assertTrue(jdbc.queryForObject(
                "select count(*) from md_charge_item where category = 'EXAM' and fee_category_code = 'EXAM'",
                Integer.class) > 0, "V132 应把既有 EXAM 收费项目回填为检查费");
        assertTrue(jdbc.queryForObject(
                "select count(*) from md_drug where drug_class = 'W' and fee_category_code = 'WM'",
                Integer.class) > 0, "V132 应把西药回填为西药费");

        assertEquals(0, (int) jdbc.queryForObject(
                "select count(*) from md_fee_category where std_code is not null or std_system is not null",
                Integer.class),
                "std_code/std_system 必须出厂留空：本仓无权威码表来源，预置国标码值即为伪造");
    }

    // ==================== ③ CSV 导入软校验：行级错误不阻断整批 ====================

    @Test
    void csvImportReportsUnknownCategoryPerRowWithoutAbortingBatch() {
        String good = dedicatedCategory();
        String c1 = uniq("V42I1"), c2 = uniq("V42I2"), c3 = uniq("V42I3");
        String csv = """
                code,name,category,unit,price,fee_category_code,self_pay
                %s,V42导入一,LAB,次,10.00,%s,0
                %s,V42导入二,LAB,次,20.00,NO_SUCH_CATEGORY,1
                %s,V42导入三,LAB,次,30.00,%s,1
                """.formatted(c1, good, c2, c3, good);

        var data = masterDataController.importChargeItems(csv).getData();
        assertEquals(3, data.get("imported"),
                "三行都必须落库——未知类别是行级提示，不能让整批导入失败（院方真实目录类别值远超字典）");
        assertEquals(1, data.get("errorCount"));
        @SuppressWarnings("unchecked")
        var errors = (List<String>) data.get("errors");
        assertTrue(errors.get(0).contains("NO_SUCH_CATEGORY") && errors.get(0).contains("第3行"),
                "行级错误须指明行号与未知码值，便于实施逐行订正：" + errors.get(0));

        assertEquals(good, jdbc.queryForObject(
                "select fee_category_code from md_charge_item where code = ?", String.class, c1));
        assertNull(jdbc.queryForObject(
                "select fee_category_code from md_charge_item where code = ?", String.class, c2),
                "未知类别的那一行照常导入，但不挂类（进报表「未分类」行，不凭空消失）");
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select self_pay from md_charge_item where code = ?", Boolean.class, c3),
                "CSV 的 self_pay 列必须写进去（V116 孤儿列补齐的三处之一）");
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select self_pay from md_charge_item where code = ?", Boolean.class, c1));
    }

    // ==================== ④ 费别汇总：自费单与"参保人付现金"的单都不能漏 ====================

    /**
     * 本类最关键的断言。yb_settle_split 只在 pay_method='YB' 时写入
     * （ChargeService:65 / InpatientService:434），故这两类单在那张表里**一行都没有**：
     * ①纯自费患者的单；②参保患者选择现金支付的单。
     * 用例先断言 split 表确实为空，再断言报表照样把它们统计进了对应费别行——
     * 若有人把实现换成查 yb_settle_split，这两个用例必然失败。
     */
    @Test
    void feeByInsuranceCountsSelfPayBillsAndInsuredPayingCash() {
        Long itemId = dedicatedChargeItem(dedicatedCategory(), "45.00");

        // ① 自费患者 + 现金
        BigDecimal[] beforeSelf = insRow("SELF");
        Long selfPid = newPatient("V42自费患者", "SELF");
        var selfCharge = chargeService.settle(regWithItem(selfPid, itemId, 1, BigDecimal.ZERO), "CASH", null);
        entityManager.flush();

        // ② 参保患者 + 现金（医保患者不走医保通道结算的单）
        BigDecimal[] beforeStaff = insRow("YB_STAFF");
        Long ybPid = newPatient("V42参保付现患者", "YB_STAFF");
        var ybCharge = chargeService.settle(regWithItem(ybPid, itemId, 2, BigDecimal.ZERO), "CASH", null);
        entityManager.flush();

        // 前提确认：这两张单在医保分割表里根本不存在
        assertEquals(0, (int) jdbc.queryForObject(
                "select count(*) from yb_settle_split where charge_no in (?, ?)", Integer.class,
                selfCharge.getChargeNo(), ybCharge.getChargeNo()),
                "自费单与参保人付现金的单都不写 yb_settle_split——用它出费别表会漏掉绝大多数业务");

        BigDecimal[] afterSelf = insRow("SELF");
        assertEquals(0, afterSelf[0].subtract(beforeSelf[0]).compareTo(BigDecimal.ONE), "自费行笔数 +1");
        assertEquals(0, afterSelf[1].subtract(beforeSelf[1]).compareTo(selfCharge.getTotalAmount()),
                "自费单金额必须计入自费行");

        BigDecimal[] afterStaff = insRow("YB_STAFF");
        assertEquals(0, afterStaff[0].subtract(beforeStaff[0]).compareTo(BigDecimal.ONE), "职工医保行笔数 +1");
        assertEquals(0, afterStaff[1].subtract(beforeStaff[1]).compareTo(ybCharge.getTotalAmount()),
                "参保人付现金的单按【患者费别】归集，与支付方式无关");

        // CSV 与 JSON 同口径同 SQL
        String csv = statsController.feeByInsuranceCsv(month);
        assertTrue(csv.contains("职工医保") && csv.contains("自费"), "CSV 应含费别行");
        assertTrue(csv.contains("月份,费别,门诊笔数"), "CSV 表头应为中文列名");
        assertTrue(csv.contains("当前值"), "CSV 末尾须带口径近似说明——导出件脱离页面后仍要能自证口径");
    }

    /** 未知/孤儿费别值显式进「其他」行而不是被 join 丢弃——丢弃会让这些患者的钱凭空消失 */
    @Test
    void feeByInsuranceBucketsOrphanInsuranceTypeIntoOtherRow() {
        Long itemId = dedicatedChargeItem(dedicatedCategory(), "12.00");
        BigDecimal[] before = insRow("OTHER");

        Long pid = newPatient("V42孤儿费别患者", "YB_EMPLOYEE");
        var charge = chargeService.settle(regWithItem(pid, itemId, 1, BigDecimal.ZERO), "CASH", null);
        entityManager.flush();

        BigDecimal[] after = insRow("OTHER");
        assertEquals(0, after[0].subtract(before[0]).compareTo(BigDecimal.ONE),
                "YB_EMPLOYEE 等历史遗留值必须落在「其他」行，不得被丢弃");
        assertEquals(0, after[1].subtract(before[1]).compareTo(charge.getTotalAmount()));

        var otherRow = insRows().stream().filter(r -> "OTHER".equals(r.get("insurance_type"))).findFirst();
        assertTrue(otherRow.isPresent() && String.valueOf(otherRow.get().get("insurance_name")).contains("其他"),
                "「其他」行须有可读名称，实施方据此发现还有多少患者费别没订正");
    }

    // ==================== ⑤ 按费用类别汇总：门诊 + 住院金额正确 ====================

    /**
     * 当次唯一的费用类别 → 该行只可能有本用例造的数据，故做**绝对**断言。
     * 门诊 1 次 × 45 元、住院 3 次 × 45 元，合计 180；行数各 1。
     */
    @Test
    void feeByCategorySumsOutpatientAndInpatientAmounts() {
        String cat = dedicatedCategory();
        BigDecimal price = new BigDecimal("45.00");
        Long itemId = dedicatedChargeItem(cat, price.toPlainString());

        assertNull(catRow(cat, null), "造数前该类别不应出现在报表里（证明断言有区分度）");

        // 门诊：1 次
        Long outpPid = newPatient("V42类别门诊", "SELF");
        var charge = chargeService.settle(regWithItem(outpPid, itemId, 1, BigDecimal.ZERO), "CASH", null);
        entityManager.flush();
        assertEquals(0, charge.getTotalAmount().compareTo(price), "挂号费为 0，收费单金额即项目金额");

        // 住院：3 次（执行后出院结算）
        Long inpPid = newPatient("V42类别住院", "SELF");
        Long admId = inpatientService.admit(inpPid, dedicatedDept(), freeBed(), null, "J18.9", "肺炎",
                new BigDecimal("5000"), "CASH", null).getId();
        var order = inpatientService.createOrders(admId,
                List.of(new InpatientService.OrderLine("LAB", itemId, 3, null, null, null)), null).get(0);
        inpatientService.execute(order.getId(), null);
        entityManager.flush();
        var settlement = inpatientService.discharge(admId, null, "CASH");
        entityManager.flush();

        var row = catRow(cat, null);
        assertNotNull(row, "报表应出现该费用类别行");
        assertEquals(1L, ((Number) row.get("outp_lines")).longValue());
        assertEquals(0, big(row.get("outp_amount")).compareTo(price), "门诊金额 = 1×45");
        assertEquals(1L, ((Number) row.get("inp_lines")).longValue());
        assertEquals(0, big(row.get("inp_amount")).compareTo(price.multiply(BigDecimal.valueOf(3))),
                "住院金额 = 3×45，且与出院结算总额同源（discharge 按已执行医嘱现算）");
        assertEquals(0, big(row.get("inp_amount")).compareTo(settlement.getTotalAmount()),
                "本次住院只有这一条医嘱，故该类别住院金额应恰等于出院结算总额");
        assertEquals(0, big(row.get("total_amount")).compareTo(price.multiply(BigDecimal.valueOf(4))));

        // 费别过滤（3684「按医保政策 + 费用类别」维度）：两位患者都是 SELF
        assertNotNull(catRow(cat, "SELF"), "按自费过滤应仍能看到该类别");
        assertNull(catRow(cat, "YB_STAFF"), "按职工医保过滤时不应出现自费患者的费用");

        String csv = statsController.feeByCategoryCsv(month, null);
        assertTrue(csv.contains(cat), "CSV 应含该费用类别行：" + cat);
    }

    /**
     * 门诊挂号费（order_type='REG'，item_id=0 哨兵值、不指向任何收费项目）单列为「挂号费」行：
     * 既不混进「未分类」稀释其含义，也不擅自并进字典里的「诊查费」（那是未经院方确认的口径假设）。
     */
    @Test
    void registrationFeeGoesToItsOwnRowInsteadOfUnclassified() {
        BigDecimal fee = new BigDecimal("9.00");
        Long itemId = dedicatedChargeItem(dedicatedCategory(), "5.00");
        var before = catRow("REG_FEE", null);
        BigDecimal beforeAmt = before == null ? BigDecimal.ZERO : big(before.get("total_amount"));

        Long pid = newPatient("V42挂号费", "SELF");
        chargeService.settle(regWithItem(pid, itemId, 1, fee), "CASH", null);
        entityManager.flush();

        var after = catRow("REG_FEE", null);
        assertNotNull(after, "挂号费应有独立行");
        assertEquals(0, big(after.get("total_amount")).subtract(beforeAmt).compareTo(fee),
                "挂号费金额应进「挂号费」行");
        assertTrue(String.valueOf(after.get("category_name")).contains("挂号费"),
                "行名须说明它是按医嘱类型识别、非主数据挂类：" + after.get("category_name"));
    }

    /** 口径近似说明必须随返回体下发——报表页 alert 直接渲染它，保证页面与端点口径逐字一致 */
    @Test
    void reportsCarryTheHonestCaveatText() {
        String insCaveat = String.valueOf(statsController.feeByInsurance(month).getData().get("caveat"));
        assertTrue(insCaveat.contains("当前值") && insCaveat.contains("其他"),
                "费别报表须标注 insurance_type 是当前值而非结算时快照、孤儿值归入其他");
        String catCaveat = String.valueOf(statsController.feeByCategory(month, null).getData().get("caveat"));
        assertTrue(catCaveat.contains("未分类") && catCaveat.contains("退费"),
                "费用类别报表须标注未分类行含义与门诊退费不可按类别拆分");
    }
}
