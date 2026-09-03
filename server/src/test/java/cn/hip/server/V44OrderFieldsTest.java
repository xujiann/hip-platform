package cn.hip.server;

import cn.hip.outpatient.entity.OutpOrder;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.web.MasterDataController;
import cn.hip.server.web.PrintReportController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v44 车道G：开单资料提示与医嘱字段
 * （偏离表 1001★ 费用资料提示 / 1002★ 药品资料提示 / 1003★ 缺药提醒 / 1006★ 医嘱备注 /
 *  1013★ 检验加急 / 1014★ 检查申请单四项 / 1016★ 标本类型·采样部位）。
 *
 * <p>断言口径四条：
 * <ol>
 *   <li><b>契约保护</b>——不传新字段时 createOrders 的返回体逐字段不变：
 *       既有键一个不少、值一个不改，新增的只有 V137 那七个键且全为空值。
 *       本车道是"零核心写路径改动"，这条是它的机械证明。</li>
 *   <li>七个新字段存取往返（含 500 字临床摘要）。</li>
 *   <li>资料提示端点带出规格/价格/费用类别/库存量/执行科室，
 *       且如实点名"参数要求但表里没有"的字段（不伪造）。</li>
 *   <li>打印数据集带出新字段；<b>无值时仍能打印</b>（历史医嘱行七列全 null，不得因此崩）。</li>
 *   <li>{@code outp.gate.stock.shortage} 默认 warn。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V44OrderFieldsTest {

    @Autowired MasterDataController masterDataController;
    @Autowired PrintReportController printController;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired cn.hip.outpatient.repository.OutpOrderRepository orderRepository;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.platform.core.service.ConfigReader configReader;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired ObjectMapper objectMapper;

    /**
     * v44 之前 OutpOrder 序列化出来的全部键。本清单是**契约基线**：
     * 少一个键就是打破了收费/发药/执行/前端四方的既有约定。
     */
    private static final Set<String> PRE_V44_KEYS = Set.of(
            "id", "registrationId", "groupNo", "orderType", "itemId", "itemCode", "itemName",
            "spec", "unit", "qty", "unitPrice", "amount", "usageRoute", "frequency",
            "dosePerTime", "days", "status", "chargeId", "reviewStatus", "reviewNote",
            "reviewerId", "doctorId", "createdAt", "stockWarnAvailable");

    private static final Set<String> V44_KEYS = Set.of(
            "remark", "urgent", "clinicalSummary", "examPurpose", "notice",
            "specimenType", "samplingSite");

    private Long doctorId() {
        return jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
    }

    /** 挂号 + 接诊，返回挂号 id（本测试不写病历——新字段与病历无耦合） */
    private Long visit() {
        Patient p = new Patient();
        p.setName("医嘱字段" + System.nanoTime() % 100000);
        p.setSex("F");
        p.setBirthDate(LocalDate.of(1990, 3, 12));
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, doctorId());
        return rid;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(Object o) {
        return objectMapper.convertValue(o, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    // ==================== ① 契约保护：不传新字段时开单行为逐字不变 ====================

    /**
     * 开单返回体：既有键一个不少、值一个不改；新增键恰好是 V137 那七个，且全为空值
     * （urgent 走 DB/实体默认 false，其余 null）。
     */
    @Test
    void createOrdersContractUnchangedWhenNewFieldsNotSupplied() {
        Long rid = visit();
        Long drugId = seeds.drug("契约保护测试药").getId();
        Long labId = seeds.chargeItem("契约保护测试检验", "LAB").getId();

        var created = doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", drugId, 2, "口服", "每日三次", "1粒", 3),
                new OrderLine("LAB", labId, 1, null, null, null, null)), doctorId());
        em.flush();

        assertEquals(2, created.size());
        for (OutpOrder o : created) {
            var keys = json(o).keySet();
            assertTrue(keys.containsAll(PRE_V44_KEYS),
                    "既有字段缺失（收费/发药/执行/前端四方共用的契约）："
                            + new java.util.TreeSet<>(PRE_V44_KEYS) + " 实际 " + new java.util.TreeSet<>(keys));
            var extra = new java.util.TreeSet<>(keys);
            extra.removeAll(PRE_V44_KEYS);
            assertEquals(new java.util.TreeSet<>(V44_KEYS), extra,
                    "本版只应新增 V137 七个字段，多出的字段属越界改动");

            // 既有值未被新字段污染
            assertEquals("CREATED", o.getStatus());
            assertNotNull(o.getGroupNo());
            assertNotNull(o.getItemName());
            assertNull(o.getChargeId());
            // 新字段在"不传"时全空——不擅自给临床数据填默认值
            assertNull(o.getRemark());
            assertNull(o.getClinicalSummary());
            assertNull(o.getExamPurpose());
            assertNull(o.getNotice());
            assertNull(o.getSpecimenType());
            assertNull(o.getSamplingSite());
            assertEquals(Boolean.FALSE, o.getUrgent(), "加急标志默认 false（未标加急，不是 null 语义）");
        }

        // 库存充足时既有的缺药提示字段仍为 null（1003 的既有放行行为一字未动）
        assertNull(created.get(0).getStockWarnAvailable());

        // 落库行的新列同样为空/false
        var row = jdbc.queryForMap(
                "select remark, urgent, clinical_summary, exam_purpose, notice, specimen_type, sampling_site"
                        + " from outp_order where id = ?", created.get(0).getId());
        assertNull(row.get("remark"));
        assertNull(row.get("clinical_summary"));
        assertEquals(Boolean.FALSE, row.get("urgent"));
    }

    // ==================== ② 新字段存取往返 ====================

    @Test
    void newOrderFieldsRoundTrip() {
        Long rid = visit();
        Long labId = seeds.chargeItem("往返测试检验", "LAB").getId();
        Long orderId = doctorStationService.createOrders(rid,
                List.of(new OrderLine("LAB", labId, 1, null, null, null, null)), doctorId())
                .get(0).getId();
        em.flush();

        String longSummary = "反复咳嗽咳痰三月余".repeat(50);   // 450 字，贴近 varchar(500) 上限
        assertTrue(longSummary.length() <= 500);

        OutpOrder o = orderRepository.findById(orderId).orElseThrow();
        o.setRemark("患者自备药已带，饭后半小时服");
        o.setUrgent(true);
        o.setClinicalSummary(longSummary);
        o.setExamPurpose("排除肺部占位");
        o.setNotice("空腹，检查前勿饮水");
        o.setSpecimenType("血液");
        o.setSamplingSite("肘正中静脉");
        orderRepository.save(o);
        em.flush();
        em.clear();

        OutpOrder back = orderRepository.findById(orderId).orElseThrow();
        assertEquals("患者自备药已带，饭后半小时服", back.getRemark());
        assertEquals(Boolean.TRUE, back.getUrgent());
        assertEquals(longSummary, back.getClinicalSummary());
        assertEquals("排除肺部占位", back.getExamPurpose());
        assertEquals("空腹，检查前勿饮水", back.getNotice());
        assertEquals("血液", back.getSpecimenType());
        assertEquals("肘正中静脉", back.getSamplingSite());
    }

    /** 1006★「所有医嘱均有备注」：备注不限医嘱类型，药品/检验/检查/治疗四类都能写 */
    @Test
    void remarkWorksForEveryOrderType() {
        Long rid = visit();
        var created = doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", seeds.drug("备注测试药").getId(), 1, "口服", "每日一次", "1粒", 1),
                new OrderLine("LAB", seeds.chargeItem("备注测试检验", "LAB").getId(), 1, null, null, null, null),
                new OrderLine("EXAM", seeds.chargeItem("备注测试检查", "EXAM").getId(), 1, null, null, null, null),
                new OrderLine("TREAT", seeds.chargeItem("备注测试治疗", "TREAT").getId(), 1, null, null, null, null)),
                doctorId());
        em.flush();
        for (OutpOrder o : created) {
            jdbc.update("update outp_order set remark = ? where id = ?", "备注-" + o.getOrderType(), o.getId());
        }
        em.clear();
        for (OutpOrder o : created) {
            assertEquals("备注-" + o.getOrderType(),
                    jdbc.queryForObject("select remark from outp_order where id = ?", String.class, o.getId()),
                    o.getOrderType() + " 类医嘱写不进备注");
        }
    }

    // ==================== ③ 资料提示端点（1001★/1002★/1003★ 的库存数据） ====================

    @Test
    void drugHintCarriesSpecPriceFeeCategoryAndStock() {
        var drug = seeds.drug("资料提示测试药");
        jdbc.update("update md_drug set fee_category_code = 'WM', stock = 42, price = 33.50,"
                + " spec = '0.5g*12片/盒' where id = ?", drug.getId());

        var r = masterDataController.orderHints("DRUG", "资料提示测试药", null, null, 20);
        assertEquals(0, r.getCode());
        var body = r.getData();
        assertEquals("DRUG", body.get("type"));

        var hit = listOf(body, "rows").stream()
                .filter(x -> drug.getId().equals(((Number) x.get("id")).longValue()))
                .findFirst().orElseThrow(() -> new AssertionError("药品资料提示未返回该药"));
        assertEquals("0.5g*12片/盒", hit.get("spec"), "1002 要求：规格");
        assertEquals(0, new BigDecimal("33.50").compareTo((BigDecimal) hit.get("price")), "1002 要求：价格");
        assertEquals(42, ((Number) hit.get("stock")).intValue(), "1002 要求：库存量（也是 1003 缺药提醒的数据源）");
        assertEquals("WM", hit.get("fee_category_code"));
        assertEquals("西药费", hit.get("fee_category_name"), "1002 要求：医保费用类别（须带类别名，不能只给码）");
        assertNotNull(hit.get("unit"));
        assertNotNull(hit.get("name"));

        // 不伪造：商品名/通用名本平台无对应列，如实点名而不是返回恒空的假字段
        @SuppressWarnings("unchecked")
        var unavailable = (Map<String, String>) body.get("unavailable");
        assertTrue(unavailable.containsKey("tradeName"));
        assertTrue(unavailable.containsKey("genericName"));
        assertFalse(hit.containsKey("trade_name"), "不得凭空返回商品名字段");
        assertFalse(hit.containsKey("generic_name"), "不得凭空返回通用名字段");
    }

    @Test
    void chargeItemHintCarriesPriceFeeCategoryAndExecDept() {
        var item = seeds.chargeItem("资料提示测试项目", "LAB");
        jdbc.update("update md_charge_item set fee_category_code = 'LAB', exec_dept_id = 1, price = 18.00"
                + " where id = ?", item.getId());

        var r = masterDataController.orderHints("ITEM", "资料提示测试项目", "LAB", null, 20);
        assertEquals(0, r.getCode());
        var body = r.getData();
        assertEquals("ITEM", body.get("type"));

        var hit = listOf(body, "rows").stream()
                .filter(x -> item.getId().equals(((Number) x.get("id")).longValue()))
                .findFirst().orElseThrow(() -> new AssertionError("项目资料提示未返回该项目"));
        assertEquals(0, new BigDecimal("18.00").compareTo((BigDecimal) hit.get("price")), "1001 要求：价格");
        assertEquals("LAB", hit.get("fee_category_code"));
        assertEquals("化验费", hit.get("fee_category_name"), "1001 要求：医保费用类别");
        assertEquals("LAB", hit.get("item_category"));
        assertNotNull(hit.get("exec_dept_name"), "1016 要求：按流向自动取执行科室");
        assertNotNull(hit.get("unit"), "1001 要求：单位（数量由开单界面输入，不属主数据）");

        // 不伪造：收费项目无规格列
        assertNull(hit.get("spec"));
        @SuppressWarnings("unchecked")
        var unavailable = (Map<String, String>) body.get("unavailable");
        assertTrue(unavailable.containsKey("spec"), "收费项目无规格列，须在返回体里如实点名");
    }

    /** 类别筛选生效：查 EXAM 不该把 LAB 项目带出来 */
    @Test
    void chargeItemHintFiltersByCategory() {
        seeds.chargeItem("类别筛选测试检验", "LAB");
        var r = masterDataController.orderHints("ITEM", "类别筛选测试", "EXAM", null, 20);
        assertEquals(0, r.getCode());
        assertTrue(listOf(r.getData(), "rows").stream()
                        .allMatch(x -> "EXAM".equals(x.get("item_category"))),
                "category=EXAM 不应返回其他类别项目");
    }

    /**
     * ids 形态不按 enabled 过滤：已在开单表里的行若药品被药师停用，医生必须当场看见
     * （检索形态则相反——停用药不进下拉框，见端点注释）。
     */
    @Test
    void idsHintReturnsDisabledDrugSoDoctorSeesIt() {
        var drug = seeds.drug("停用资料提示药");
        jdbc.update("update md_drug set enabled = false, disable_reason = '招标掉标' where id = ?", drug.getId());

        var byIds = masterDataController.orderHints("DRUG", "", null, String.valueOf(drug.getId()), 20);
        var rows = listOf(byIds.getData(), "rows");
        assertEquals(1, rows.size(), "按 id 取资料时不应按 enabled 过滤");
        assertEquals(Boolean.FALSE, rows.get(0).get("enabled"));
        assertEquals("招标掉标", rows.get(0).get("disable_reason"));

        var byKeyword = masterDataController.orderHints("DRUG", "停用资料提示药", null, null, 20);
        assertTrue(listOf(byKeyword.getData(), "rows").stream()
                        .noneMatch(x -> drug.getId().equals(((Number) x.get("id")).longValue())),
                "检索形态不得把停用药放回医生的下拉框（选中必撞 8016）");
    }

    /** 脏 ids 不该让只读提示接口整体失败 */
    @Test
    void hintToleratesGarbageIds() {
        var r = masterDataController.orderHints("DRUG", "", null, "abc,,  ,x", 20);
        assertEquals(0, r.getCode());
        assertFalse(listOf(r.getData(), "rows").isEmpty(), "全部 id 非法时应回落为检索形态而不是报错");
    }

    // ==================== ④ 打印数据集消费新字段 ====================

    @Test
    void labRequestPrintCarriesUrgentSpecimenAndSummary() {
        Long rid = visit();
        Long labId = seeds.chargeItem("打印检验项目", "LAB").getId();
        jdbc.update("update md_charge_item set exec_dept_id = 1 where id = ?", labId);
        Long orderId = doctorStationService.createOrders(rid,
                List.of(new OrderLine("LAB", labId, 1, null, null, null, null)), doctorId()).get(0).getId();
        em.flush();
        jdbc.update("""
                update outp_order set urgent = true, clinical_summary = '发热三天，血象升高',
                       specimen_type = '血液', sampling_site = '肘正中静脉', notice = '空腹',
                       remark = '与上次结果对比'
                where id = ?
                """, orderId);

        var r = printController.clinicalDoc("lab-request", rid, null);
        assertEquals(0, r.getCode());
        var row = listOf(r.getData(), "rows").get(0);
        assertEquals(Boolean.TRUE, row.get("urgent"), "1013 要求：加急标志");
        assertEquals("发热三天，血象升高", row.get("clinical_summary"), "1016 要求：病情摘要");
        assertEquals("血液", row.get("specimen_type"), "1016 要求：标本类型");
        assertEquals("肘正中静脉", row.get("sampling_site"), "1016 要求：采样部位");
        assertEquals("空腹", row.get("notice"));
        assertEquals("与上次结果对比", row.get("remark"), "1006 要求：医嘱备注要能印出来");
        assertNotNull(row.get("exec_dept_name"), "执行科室（申请单去哪儿做）");

        // 单头级字段：加急是印在单头的戳，摘要/标本要求也是整张单一条
        var group = listOf(r.getData(), "groups").get(0);
        assertEquals(Boolean.TRUE, group.get("urgent"));
        assertEquals("发热三天，血象升高", group.get("clinical_summary"));
        assertEquals("血液", group.get("specimen_type"));
        assertEquals("肘正中静脉", group.get("sampling_site"));
    }

    @Test
    void examRequestPrintCarriesPurposeSummaryAndNotice() {
        Long rid = visit();
        Long examId = seeds.chargeItem("打印检查项目", "EXAM").getId();
        Long orderId = doctorStationService.createOrders(rid,
                List.of(new OrderLine("EXAM", examId, 1, null, null, null, null)), doctorId()).get(0).getId();
        em.flush();
        jdbc.update("""
                update outp_order set clinical_summary = '咳嗽两周，抗感染无效',
                       exam_purpose = '排除肺部占位', notice = '检查前取下金属饰品'
                where id = ?
                """, orderId);

        var r = printController.clinicalDoc("exam-request", rid, null);
        assertEquals(0, r.getCode());
        var row = listOf(r.getData(), "rows").get(0);
        assertEquals("咳嗽两周，抗感染无效", row.get("clinical_summary"), "1014 要求：临床摘要");
        assertEquals("排除肺部占位", row.get("exam_purpose"), "1014 要求：检查目的");
        assertEquals("检查前取下金属饰品", row.get("notice"), "1014 要求：注意事项");
        // 1014 的"诊断资料"复用既有 outp_diagnosis，不另立列（见 V137 头注释）
        assertNotNull(r.getData().get("diagnoses"));

        var group = listOf(r.getData(), "groups").get(0);
        assertEquals("排除肺部占位", group.get("exam_purpose"));
        assertEquals("检查前取下金属饰品", group.get("notice"));
    }

    /**
     * 历史医嘱行七列全 null 时仍照常打印，单头级字段回 null——版式据此保留 v43 的手填栏。
     * 这条是本车道对既有单据打印的回归保护：加了字段不能让老单子打不出来。
     */
    @Test
    void printStillWorksWhenNewFieldsAreAllNull() {
        Long rid = visit();
        var created = doctorStationService.createOrders(rid, List.of(
                new OrderLine("DRUG", seeds.drug("空字段测试药").getId(), 1, "口服", "每日一次", "1粒", 1),
                new OrderLine("LAB", seeds.chargeItem("空字段测试检验", "LAB").getId(), 1, null, null, null, null),
                new OrderLine("EXAM", seeds.chargeItem("空字段测试检查", "EXAM").getId(), 1, null, null, null, null),
                new OrderLine("TREAT", seeds.chargeItem("空字段测试治疗", "TREAT").getId(), 1, null, null, null, null)),
                doctorId());
        em.flush();
        assertEquals(4, created.size());

        for (String docType : List.of("prescription", "lab-request", "exam-request", "treat-sheet", "guide-sheet")) {
            var r = printController.clinicalDoc(docType, rid, null);
            assertEquals(0, r.getCode(), docType + " 在新字段全空时打印失败");
            var rows = listOf(r.getData(), "rows");
            assertFalse(rows.isEmpty(), docType + " 无明细行");
            assertNull(rows.get(0).get("clinical_summary"));
            assertNull(rows.get(0).get("specimen_type"));
            assertEquals(Boolean.FALSE, rows.get(0).get("urgent"));
            if (!"guide-sheet".equals(docType)) {
                var group = listOf(r.getData(), "groups").get(0);
                assertEquals(Boolean.FALSE, group.get("urgent"), docType + " 单头加急应为 false");
                for (String col : List.of("clinical_summary", "exam_purpose", "notice",
                        "specimen_type", "sampling_site")) {
                    assertTrue(group.containsKey(col), docType + " 单头缺 " + col + " 键");
                    assertNull(group.get(col), docType + " 单头 " + col + " 无值时应为 null（版式留手填栏）");
                }
            }
        }
    }

    // ==================== ⑤ 1003 缺药提醒：院级开关 ====================

    /**
     * {@code outp.gate.stock.shortage} 默认 warn（同全仓 gate 纪律）。
     * <b>本版是"开单提示 + 院级开关"，不擅自改成硬拦</b>——参数要求硬拦，但开单与发药之间
     * 隔着缴费，硬拦会让已挂号的患者拿不到处方（详见 V137 头注释与 docs/配置手册.md）。
     */
    @Test
    void stockShortageGateDefaultsToWarn() {
        assertEquals("warn", jdbc.queryForObject(
                "select cfg_value from sys_config where cfg_key = 'outp.gate.stock.shortage'", String.class));
        configReader.evict("outp.gate.stock.shortage");
        assertEquals("warn", configReader.get("outp.gate.stock.shortage", "off"));
        // 开单界面从资料提示端点读该开关，无须另开配置接口
        assertEquals("warn", masterDataController.orderHints("DRUG", "", null, null, 1)
                .getData().get("stockGate"));
    }

    /**
     * 缺药时的既有放行行为一字未改：库存不足照样开得出单，只是返回体带出当前库存
     * （stockWarnAvailable，v1.2 既有契约）。本测试是"不擅自改成硬拦"的回归锁。
     */
    @Test
    void shortageStillPassesThroughAndReportsStock() {
        Long rid = visit();
        var drug = seeds.drug("缺药提醒测试药");
        jdbc.update("update md_drug set stock = 1 where id = ?", drug.getId());
        // 绕过 JPA 改的库存要清一级缓存，否则 createOrders 的 findById 读到的还是旧快照
        em.flush();
        em.clear();

        var created = assertDoesNotThrow(() -> doctorStationService.createOrders(rid,
                List.of(new OrderLine("DRUG", drug.getId(), 5, "口服", "每日三次", "1粒", 3)), doctorId()),
                "缺药不得拦截开单——参数虽要求硬拦，但硬拦会让已挂号患者拿不到处方");
        assertEquals(1, created.size());
        assertEquals(Integer.valueOf(1), created.get(0).getStockWarnAvailable(), "缺药时应回带当前库存供界面提示");
        assertEquals("CREATED", created.get(0).getStatus());

        // 提示所需的库存量同样能从资料提示端点拿到（开单界面一次取全）
        var hit = listOf(masterDataController.orderHints("DRUG", "", null,
                String.valueOf(drug.getId()), 1).getData(), "rows").get(0);
        assertEquals(1, ((Number) hit.get("stock")).intValue());
    }
}
