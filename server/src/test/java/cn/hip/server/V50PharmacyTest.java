package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpOrderRepository;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DispenseService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.service.InventoryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * v50 车道 E：<b>门诊药房调剂的机械对账保障</b>（技术偏离表 46★）。
 *
 * <h2>这个类为什么先于（并独立于）实现车道存在</h2>
 * v49 的教训写在 {@code V49CaliberTest} 的类注释里：v46 的一批口径缺陷活了三个版本，
 * <b>不是因为没写测试，而是因为 {@code V46AnesQcTest} 的断言只覆盖了一条指标</b>。
 * 药房比质控更不容出错——质控算错是报表难看，<b>药房账错是药品去向不明</b>。
 * 所以本版<b>先把账本立起来</b>：判据先于实现，且判据<b>逐行普查全表</b>而不是只验夹具那一条。
 *
 * <h2>总纲：流水即账，库存即余额</h2>
 * 对每一个 (药品, 批次)：<pre>期初 + Σ入库 - Σ出库 ± Σ盘点调整 == 当前库存余额</pre>
 * 本仓今天有<b>三本账</b>，本类对三本各立一条恒等式，并<b>额外立一条跨账核对</b>：
 * <ol>
 *   <li><b>总账</b> {@code md_drug.stock} ↔ {@code inv_transaction}（药品级，销售单位「盒」）
 *       —— {@link #ledgerIdentityHoldsForEveryLegacyPath()}
 *   <li><b>批次子账</b> {@code pharm_stock} ↔ {@code pharm_stock_flow}（(批次,地点) 级，销售单位）
 *       —— {@link #batchStockEqualsFlowSumForEveryBatchAndLocation()}
 *   <li><b>拆零子账</b> {@code pharm_split_stock} ↔ {@code pharm_split_txn}（药品级，<b>最小单位「片」</b>）
 *       —— {@link #splitRemainEqualsSplitTxnSumForEveryDrug()}
 *   <li><b>跨账</b>：开包那一盒在总账里是 {@code OUT -1 盒}，在拆零账里是 {@code OPEN +24 片}，
 *       两者必须按 {@code pack_size} 换算后严丝合缝 —— {@link #splitOpeningConservesQuantityAcrossLedgers()}。
 *       <b>三本账各自自洽而合起来对不上，是药房系统最经典的坏法</b>：单独看每本都是平的，
 *       没有任何单本账的用例会发现，所以跨账那条必须单独钉。
 * </ol>
 *
 * <h2>本类三条工程口径</h2>
 * <ol>
 *   <li><b>每条不变式都有「今天就能跑的腿」</b>。跑在既有链路上的那条腿必须<b>现在就绿</b>，
 *       绿了才证明这张网本身通电；一张只会在未来变绿的网，和没有网没区别。
 *   <li><b>静默跳过 = fail</b>（承自 V49CaliberTest）。未交付一律以 {@code [V50-未交付]} 前缀
 *       显式 fail、可被 grep；主控宣告不做的边界登记进 {@link #DECLARED_OUT_OF_SCOPE}，
 *       <b>登记了才允许不做</b>。
 *   <li><b>不焊死在别人的方法签名上</b>。跨车道一律走 HTTP（MockMvc）或反射，
 *       实现车道改签名不会让本类编译不过——否则「改签名」就会变成「顺手把测试改绿」。
 * </ol>
 *
 * <h2>本类同时是既有链路的防改坏网</h2>
 * 既有 4 个发药端点、错误码 6001–6006、{@code claimDispense}/{@code claimReturn} 的抢占模式
 * 逐条钉死。新流程走新端点，一旦有人图省事改既有端点，这里第一个红。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(roles = {"ADMIN", "PHARMACIST"})
class V50PharmacyTest {

    private static final String UNDELIVERED = "[V50-未交付] ";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired ApplicationContext ctx;
    @Autowired PlatformTransactionManager txManager;

    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired DispenseService dispenseService;
    @Autowired InventoryService inventoryService;
    @Autowired DrugItemRepository drugRepository;
    @Autowired OutpOrderRepository orderRepository;
    @Autowired OutpScheduleRepository scheduleRepository;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    // ==================================================================================
    // 【登记表一】药品级流水（总账）的类型域与方向。
    //
    // 与 V81__pharmacy_stock_take.sql 给 inv_transaction.type 写的列注释同源。
    // everyDrugLedgerTypeIsRegisteredWithADirection() 对**全表** distinct type 普查：
    // 出现没登记的类型 = 当场红。这不是洁癖——**新增一条流水路径而没人说它对余额是加是减，
    // 恒等式就悄悄失效了**，而失效的差额会被下一次盘点调整抹平、届时连证据都不剩。
    // ==================================================================================
    private static final Map<String, String> DRUG_LEDGER_TYPES = new LinkedHashMap<>();

    static {
        DRUG_LEDGER_TYPES.put("IN", "入库验收（InventoryService.acceptStockIn）");
        DRUG_LEDGER_TYPES.put("OUT", "出库：既有发药 + v50 拆零开包（qty 带负号）");
        DRUG_LEDGER_TYPES.put("RET", "退药回补");
        DRUG_LEDGER_TYPES.put("ADJ", "单药直调盘点，qty 为带符号差额");
        DRUG_LEDGER_TYPES.put("STOCKTAKE", "盘点单确认，qty 为带符号差额");
    }

    /** 批次级流水（子账）的类型域。与 {@code pharm_stock_flow.flow_type} 的 CHECK 约束同源。 */
    private static final Map<String, String> BATCH_FLOW_TYPES = new LinkedHashMap<>();

    static {
        BATCH_FLOW_TYPES.put("IN", "入库（POST /api/pharm/stock/stock-in）");
        BATCH_FLOW_TYPES.put("SCRAP", "报损（POST /api/pharm/stock/scrap）");
        BATCH_FLOW_TYPES.put("OUT", "发药出库");
        BATCH_FLOW_TYPES.put("RET", "退药回补");
        BATCH_FLOW_TYPES.put("ADJ", "盘点调整");
    }

    /** 拆零子账的类型域。与 {@code pharm_split_txn.type} 的列注释同源。 */
    private static final Map<String, String> SPLIT_TXN_TYPES = new LinkedHashMap<>();

    static {
        SPLIT_TXN_TYPES.put("OPEN", "开包入池（+，最小单位）");
        SPLIT_TXN_TYPES.put("OUT", "拆零发出（−）");
        SPLIT_TXN_TYPES.put("RET", "拆零退回（+）");
        SPLIT_TXN_TYPES.put("TAKEADJ", "拆零盘点差异调整（带符号）");
    }

    // ==================================================================================
    // 【登记表二】主控/实现车道已宣告本版不做的边界。**登记了才允许不做。**
    // 这张表的存在是为了让"不做"是一个有署名的决定，而不是一次悄无声息的遗漏。
    // ==================================================================================
    private static final Map<String, String> DECLARED_OUT_OF_SCOPE = new LinkedHashMap<>();

    static {
        DECLARED_OUT_OF_SCOPE.put("包药机/发药机设备直连驱动",
                "硬边界，同 v48 玻片打码机：平台无设备驱动层，硬做只能做出假的。"
                        + "由 tools/e2e-v50-pharmacy.py 断言 package.json / pom.xml 无相关依赖。");
        DECLARED_OUT_OF_SCOPE.put("药品追溯码上传国家平台", "外部接口规范，属外部边界。");
        DECLARED_OUT_OF_SCOPE.put("ADJ 盘点调整",
                "【主控署名登记，v50 不做，v51 必做】盘点仍只盘 md_drug.stock（V81 的 inv_stock_take），"
                        + "不盘批次余额。**这是本版留下的最危险的一处**：发药/退药已收敛进批次账后，"
                        + "两本账日常是一致的；但一旦出现任何来源的漂移，盘点会把汇总拉到实存、"
                        + "把差额**永久抹平**，而批次层的错账原地不动、无人知晓，"
                        + "表现为「看起来一直对得上、实际每次都靠盘点擦屁股」。"
                        + "不在本版做的理由：confirmStockTake 在 platform/masterdata 的 InventoryService，"
                        + "盘点要盘到批次层意味着盘点单本身要加批次维度（盘的是「这个批号还剩几盒」"
                        + "而不是「这个药还剩几盒」），那是盘点业务的重新设计，不是接一个调用能了事的，"
                        + "须单独评估、单独回归。**短期缓解**：盘点确认后提示药师同步做一次 "
                        + "GET /api/pharm/stock/reconcile 批次层核对；该端点已可用且能真的抓出不一致"
                        + "（V50PharmStockTest.reconcileActuallyDetectsMismatch 人为破坏后必须被抓到）。");
        DECLARED_OUT_OF_SCOPE.put("麻精药品专项流程（5520–5539）",
                "md_drug 无「精麻毒放」属性位（v46 的 1444★/1445★ 已因此标 available:false）。"
                        + "先补主数据属性位才谈得上做，**不得按药名猜**——见 "
                        + "narcoticFlowRequiresAMasterDataAttributeNotADrugNameGuess()。");
    }

    /**
     * 【登记表三】<b>批次子账今天缺失的写入路径</b>——本类最要紧的一份清单。
     *
     * <p>V147 迁移注释第三节已诚实自陈：入库、报损两条路径双写；<b>发药出库仍只扣
     * {@code md_drug.stock}、不扣批次余额</b>，因为 {@code DispenseService} 是既有链路、本版不许改。
     * 退药与盘点同理。
     *
     * <p>本类<b>不把它当作可以豁免的既定事实</b>：主控给车道 E 的判据原文是
     * 「这条要<b>对每一条流水路径都成立</b>：发药、退药、盘点、报损、拆零」。
     * 三条路径不进批次账，恒等式在批次层就<b>只对入库和报损成立</b>——
     * 那不叫账，那叫两条路径的流水账。故 {@link #everyStockMutatingPathWritesTheBatchLedger()}
     * 就是要<b>红</b>，红的位置就是本版对账保障的真实缺口，交主控裁决。
     */
    private static final Map<String, String> BATCH_LEDGER_MISSING_WRITE_PATHS = new LinkedHashMap<>();

    static {
        // 【已收敛 v50 主控】OUT 发药出库 与 RET 退药回补 原登记在此。
        // 主控接了车道 A 备好的两个接缝（consumeForDispense / restoreToBatch），
        // 并新建 V151 pharm_dispense_batch 存住「这一笔发药拆给了哪几个批次各多少」——
        // 没有这个存储位，退药只能事后按 FEFO 猜一个批次回补，总数仍对得上但
        // **批次追溯从此是错的且看不出来**，召回时会照着错批次去下架。
        // 收敛后实测：发 9 盒，总账 91、批次子账 91，**漂移 0**（原为 9）。
        // 只对已纳管药品（有 pharm_batch 记录）走批次扣减，未纳管的保持 v49 行为逐字不变——
        // 否则存量药品会在启用批次管理当天全部发不出去。覆盖率由 /balance 的 coverage 如实报数。
        BATCH_LEDGER_MISSING_WRITE_PATHS.put("ADJ 盘点调整",
                "V81 的 inv_stock_take 盘的是 md_drug.stock（整包），不盘批次余额。"
                        + "**这一条最危险**：盘点会把汇总拉到实存，于是上面两条漏账造成的差额被永久抹平，"
                        + "而批次层的错账原地不动、无人知晓。");
    }

    // ==================================================================================
    // §0 通用工具
    // ==================================================================================

    private static int seq = 0;

    private static String uniq(String prefix) {
        return prefix + (System.nanoTime() % 100000000L) + (seq++);
    }

    private void flushIfInTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            em.flush();
            em.clear();
        }
    }

    /** 现取现认的端点集合，形如 {@code "POST /api/outpatient/dispense/{registrationId}"}。 */
    private Set<String> mappedEndpoints() {
        Set<String> out = new TreeSet<>();
        handlerMapping.getHandlerMethods().keySet().forEach(info -> {
            Set<String> patterns = new LinkedHashSet<>();
            if (info.getPathPatternsCondition() != null) {
                patterns.addAll(info.getPathPatternsCondition().getPatternValues());
            }
            if (info.getPatternsCondition() != null) {
                patterns.addAll(info.getPatternsCondition().getPatterns());
            }
            var methods = info.getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (methods.isEmpty()) out.add("* " + p);
                else methods.forEach(m -> out.add(m.name() + " " + p));
            }
        });
        return out;
    }

    private Set<String> endpointsMatching(String... fragments) {
        Set<String> out = new TreeSet<>();
        for (String e : mappedEndpoints()) {
            String low = e.toLowerCase(Locale.ROOT);
            for (String f : fragments) {
                if (low.contains(f)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    private boolean tableExists(String table) {
        Long n = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name=?",
                Long.class, table);
        return n != null && n > 0;
    }

    private void requireTable(String table, String owner, String why) {
        assertTrue(tableExists(table),
                UNDELIVERED + "缺表 " + table + "（" + owner + "）：" + why);
    }

    private Map<String, Object> api(String method, String path, Object body) {
        try {
            var req = "POST".equals(method)
                    ? post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(om.writeValueAsString(body == null ? Map.of() : body))
                    : get(path);
            String json = mvc.perform(req).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            return om.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("调用 " + method + " " + path + " 失败", e);
        }
    }

    private static int code(Map<String, Object> body) {
        return ((Number) body.get("code")).intValue();
    }

    private static Object data(Map<String, Object> body) {
        assertEquals(0, code(body), "接口应成功：" + body);
        return body.get("data");
    }

    // ==================================================================================
    // §0.5 夹具
    // ==================================================================================

    /**
     * 建一味<b>本类专用</b>的药，初始库存 0。
     *
     * <p>为什么必须自建而不用种子药：种子药的 stock 是 {@code V4__masterdata.sql} 直接 insert 的，
     * <b>没有对应的 IN 流水</b>，恒等式在它身上从一开始就不成立（期初不为 0 且不可考）。
     * 自建一味<b>期初为 0</b> 的药，恒等式才是<b>绝对形式</b>而非差分形式——
     * 差分形式抓不到"两笔互相抵消的漏写"。
     */
    private Long newDrugWithZeroStock(String name) {
        return jdbc.queryForObject("""
                insert into md_drug(code, name, spec, unit, price, stock, antibiotic, enabled)
                values (?, ?, '0.25g*24粒/盒', '盒', 10.00, 0, false, true)
                returning id
                """, Long.class, uniq("V50D"), name + uniq("-"));
    }

    private record Rx(Long registrationId, Long orderId, String groupNo, int qty) {}

    /** 走平台自己的链路造一张已收费待发药处方（排班→挂号→接诊→开单→结算）。 */
    private Rx chargedPrescription(Long drugId, int qty) {
        Patient p = new Patient();
        p.setName("药房对账" + uniq(""));
        p.setSex("U");
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());   // 禁用裸 LocalDate.now()：本仓已因时区炸过四次
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(99);
        s = scheduleRepository.save(s);

        Long regId = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(regId, null);
        doctorStationService.createOrders(regId,
                List.of(new OrderLine("DRUG", drugId, qty, "口服", "tid", "1粒", 3)), null);
        chargeService.settle(regId, "CASH", null);
        flushIfInTransaction();
        var order = orderRepository
                .findByRegistrationIdAndOrderTypeAndStatusOrderByIdAsc(regId, "DRUG", "CHARGED").get(0);
        return new Rx(regId, order.getId(), order.getGroupNo(), qty);
    }

    private int stockOf(Long drugId) {
        flushIfInTransaction();
        Integer n = jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
        return n == null ? 0 : n;
    }

    private long drugLedgerSum(Long drugId) {
        Long n = jdbc.queryForObject(
                "select coalesce(sum(qty), 0) from inv_transaction where drug_id = ?", Long.class, drugId);
        return n == null ? 0L : n;
    }

    /** 恒等式的绝对形式：期初 0 + Σ流水 == 当前库存。 */
    private void assertDrugLedgerBalances(Long drugId, String step) {
        assertEquals((long) stockOf(drugId), drugLedgerSum(drugId),
                "【流水即账，库存即余额】" + step + " 后总账对不上：期初 0 + Σinv_transaction.qty 应 == md_drug.stock。"
                        + "差额说明这一步有库存变动没写流水（或写反了方向），"
                        + "而这种差额会被下一次盘点抹平、届时连证据都不剩。");
    }

    // ==================================================================================
    // §1 既有链路：必须**现在就绿**，且永远不许被改红
    // ==================================================================================

    /** 既有 4 个发药端点契约逐字不动。新流程走新端点，不许图省事改这四条。 */
    @Test
    void legacyDispenseEndpointsAreUnchanged() {
        Set<String> all = mappedEndpoints();
        for (String e : List.of(
                "GET /api/outpatient/dispense/worklist",
                "POST /api/outpatient/dispense/{registrationId}",
                "GET /api/outpatient/dispense/dispensed",
                "POST /api/outpatient/dispense/orders/{orderId}/return")) {
            assertTrue(all.contains(e),
                    "既有发药端点 [" + e + "] 不见了或被改了签名——**既有 4 个端点契约逐字不动**是本版铁律。"
                            + "当前 /api/outpatient/dispense 下的端点："
                            + endpointsMatching("/api/outpatient/dispense"));
        }
    }

    /** 既有错误码 6001/6002/6004 原样保留（6003 由并发用例覆盖）。 */
    @Test
    void legacyDispenseErrorCodesArePreserved() {
        Patient p = new Patient();
        p.setName("空处方" + uniq(""));
        p.setSex("U");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(9);
        s = scheduleRepository.save(s);
        Long emptyReg = registrationService.register(pid, s.getId()).getId();
        assertEquals(6001, assertThrows(BizException.class, () -> dispenseService.dispense(emptyReg)).code,
                "6001「没有待发药的处方」是既有码，不许改");

        Long drugId = newDrugWithZeroStock("码位保留");
        Rx rx = chargedPrescription(drugId, 2);
        // **6004 必须先测**：库存不足那次 dispense 抛的是业务异常，而测试事务是一整个物理事务，
        // claimDispense 已把订单置成 DISPENSED 且不会因为抛异常而回滚（同一物理事务里没有回滚点）。
        // 顺序反过来的话，这一条会在一个「已发药」的订单上退药成功，从而**测不出 6004**。
        assertEquals(6004, assertThrows(BizException.class,
                () -> dispenseService.returnDrug(rx.orderId(), null)).code,
                "6004「仅已发药的药品可退药」是既有码，不许改");
        assertEquals(6002, assertThrows(BizException.class,
                () -> dispenseService.dispense(rx.registrationId())).code, "6002「库存不足」是既有码，不许改");
    }

    /**
     * <b>总账恒等式</b>：入库验收 → 发药 → 退药 → 盘点直调，逐步核对
     * 「期初 0 + Σ流水 == md_drug.stock」，并核对每条流水自带的 {@code stock_after}
     * 与逐笔累加值一致（{@code stock_after} 对不上 = 这笔流水记的是别人的余额，台账打出来就是错的）。
     */
    @Test
    void ledgerIdentityHoldsForEveryLegacyPath() {
        Long drugId = newDrugWithZeroStock("总账主链");
        assertEquals(0, stockOf(drugId));
        assertDrugLedgerBalances(drugId, "建药（期初 0）");

        var in = inventoryService.stockIn(drugId, 100, "V50BATCH-A",
                BusinessDates.today().plusDays(365), "对账供应商", null, null);
        assertEquals(0, stockOf(drugId), "入库登记不入账（待验收）——这是 InventoryService 的既有口径");
        assertDrugLedgerBalances(drugId, "入库登记（不入账）");
        inventoryService.acceptStockIn(in.getId(), null);
        assertEquals(100, stockOf(drugId));
        assertDrugLedgerBalances(drugId, "入库验收");

        Rx rx = chargedPrescription(drugId, 7);
        dispenseService.dispense(rx.registrationId());
        assertEquals(93, stockOf(drugId));
        assertDrugLedgerBalances(drugId, "发药");

        dispenseService.returnDrug(rx.orderId(), null);
        assertEquals(100, stockOf(drugId));
        assertDrugLedgerBalances(drugId, "退药");

        inventoryService.adjust(drugId, 88, "V50 对账盘点", null);
        assertEquals(88, stockOf(drugId));
        assertDrugLedgerBalances(drugId, "盘点调整");

        var rows = jdbc.queryForList(
                "select id, type, qty, stock_after from inv_transaction where drug_id = ? order by id", drugId);
        long running = 0;
        for (Map<String, Object> r : rows) {
            running += ((Number) r.get("qty")).longValue();
            assertEquals(running, ((Number) r.get("stock_after")).longValue(),
                    "流水 id=" + r.get("id") + " type=" + r.get("type")
                            + " 的 stock_after 与逐笔累加值不符——这笔流水记的是别人的余额");
        }
        assertEquals(4, rows.size(), "入库验收/发药/退药/盘点四条路径应各留一笔流水，实际：" + rows);
    }

    /** 不变式②「发药不得凭空造药」：出库总量 == 处方数量，且不得给处方之外的药开流水。 */
    @Test
    void dispenseOutQuantityEqualsPrescriptionQuantity() {
        Long drugId = newDrugWithZeroStock("出量核对");
        Long otherDrug = newDrugWithZeroStock("无关药品");
        inventoryService.acceptStockIn(inventoryService.stockIn(drugId, 50, "V50BATCH-B",
                BusinessDates.today().plusDays(200), "对账供应商", null, null).getId(), null);
        long otherBefore = drugLedgerSum(otherDrug);

        Rx rx = chargedPrescription(drugId, 6);
        dispenseService.dispense(rx.registrationId());
        flushIfInTransaction();

        Long out = jdbc.queryForObject("""
                select coalesce(sum(-qty), 0) from inv_transaction
                where drug_id = ? and type = 'OUT' and ref_no = ?
                """, Long.class, drugId, rx.groupNo());
        assertEquals((long) rx.qty(), out == null ? 0L : out,
                "【发药不得凭空造药】本次发药的出库总量必须等于处方数量（groupNo=" + rx.groupNo() + "）。"
                        + "多出来的量是凭空造药，少掉的量是药出去了账没记。");
        assertEquals(otherBefore, drugLedgerSum(otherDrug), "处方里没有的药不得产生任何流水");
    }

    /** 总账类型域普查：全表 distinct type 必须条条登记。静默新增类型 = 当场红。 */
    @Test
    void everyDrugLedgerTypeIsRegisteredWithADirection() {
        List<String> types = jdbc.queryForList(
                "select distinct type from inv_transaction order by 1", String.class);
        List<String> unknown = types.stream().filter(t -> !DRUG_LEDGER_TYPES.containsKey(t)).toList();
        assertTrue(unknown.isEmpty(),
                "inv_transaction 出现未登记的流水类型 " + unknown
                        + "。要加类型，先来 V50PharmacyTest.DRUG_LEDGER_TYPES 写一行说明它对余额的方向——"
                        + "**不说方向的流水等于没有流水**。已登记：" + DRUG_LEDGER_TYPES.keySet());
    }

    /**
     * 不变式⑥：既有 {@code claimDispense} 的防双扣模式在真并发下只让一方成功。
     *
     * <p><b>本用例不吃测试事务</b>（{@code NOT_SUPPORTED}）：各线程要各自提交才形成真并发写，
     * 单线程 {@code @Transactional} 测不出抢占（docs/测试方法论.md 第 ④ 条）。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDispenseOfOnePrescriptionSucceedsExactlyOnce() throws Exception {
        AtomicReference<Long> drugRef = new AtomicReference<>();
        AtomicReference<Rx> rxRef = new AtomicReference<>();
        inTx(() -> {
            Long d = newDrugWithZeroStock("并发发药");
            inventoryService.acceptStockIn(inventoryService.stockIn(d, 100, "V50BATCH-C",
                    BusinessDates.today().plusDays(300), "对账供应商", null, null).getId(), null);
            drugRef.set(d);
            rxRef.set(chargedPrescription(d, 3));
        });
        Long drugId = drugRef.get();
        Rx rx = rxRef.get();
        try {
            int[] r = race(() -> dispenseService.dispense(rx.registrationId()));
            assertEquals(1, r[0], "同一张处方并发发药**只能成功一次**——"
                    + "成功多次即库存双扣、药只发一次，claimDispense 的防线失效了");
            assertEquals(97, stockOf(drugId), "库存只应被扣一次");
            assertEquals(1L, count("select count(*) from inv_transaction where drug_id = ? and type = 'OUT'",
                    drugId), "出库流水只应有一笔——多一笔就是账上多发了一次药");
            assertEquals((long) stockOf(drugId), drugLedgerSum(drugId), "并发之后账仍须对得上");
        } finally {
            cleanup(drugId, rx.registrationId());
        }
    }

    /** 不变式⑥（退药侧）：{@code claimReturn} 并发下只回补一次，否则库存凭空多出。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReturnOfOneOrderRestoresExactlyOnce() throws Exception {
        AtomicReference<Long> drugRef = new AtomicReference<>();
        AtomicReference<Rx> rxRef = new AtomicReference<>();
        inTx(() -> {
            Long d = newDrugWithZeroStock("并发退药");
            inventoryService.acceptStockIn(inventoryService.stockIn(d, 100, "V50BATCH-D",
                    BusinessDates.today().plusDays(300), "对账供应商", null, null).getId(), null);
            Rx r = chargedPrescription(d, 4);
            dispenseService.dispense(r.registrationId());
            drugRef.set(d);
            rxRef.set(r);
        });
        Long drugId = drugRef.get();
        Rx rx = rxRef.get();
        try {
            assertEquals(96, stockOf(drugId));
            int[] r = race(() -> dispenseService.returnDrug(rx.orderId(), null));
            assertEquals(1, r[0], "同一条医嘱并发退药**只能成功一次**——多成功一次即库存凭空多出");
            assertEquals(100, stockOf(drugId), "库存只应回补一次");
            assertEquals(1L, count("select count(*) from inv_transaction where drug_id = ? and type = 'RET'",
                    drugId), "退药流水只应有一笔");
            assertEquals((long) stockOf(drugId), drugLedgerSum(drugId), "并发之后账仍须对得上");
        } finally {
            cleanup(drugId, rx.registrationId());
        }
    }

    // ==================================================================================
    // §2 批次子账（车道 A：pharm_batch / pharm_stock / pharm_stock_flow）
    // ==================================================================================

    /**
     * <b>批次子账恒等式</b>：对任意 {@code (batch_id, location_code)}，
     * {@code pharm_stock.qty == sum(pharm_stock_flow.qty_delta)}。
     *
     * <p>逐行普查<b>整张表</b>而不是只查夹具那一条——只查自己造的数据，等于只证明
     * "我造的那条对得上"，而真正会出事的是别人造的那些。
     */
    @Test
    void batchStockEqualsFlowSumForEveryBatchAndLocation() {
        requireTable("pharm_stock", "车道A", "批次余额表，5400–5419 库存地基");
        requireTable("pharm_stock_flow", "车道A", "批次流水表");

        var bad = jdbc.queryForList("""
                select s.batch_id, s.location_code, s.qty as balance,
                       coalesce(f.total, 0) as flow_sum
                from pharm_stock s
                left join (select batch_id, location_code, sum(qty_delta) as total
                           from pharm_stock_flow group by 1, 2) f
                  on f.batch_id = s.batch_id and f.location_code = s.location_code
                where s.qty <> coalesce(f.total, 0)
                limit 20
                """);
        assertTrue(bad.isEmpty(),
                "【流水即账，库存即余额】以下 (批次, 地点) 的余额与流水合计对不上：" + bad
                        + "。V147 迁移注释把这条写成硬不变量，且余额与流水永远同事务写入。");

        // 反向：有流水却没有余额行 —— 账在余额之外飘着，同样是账实分离
        var orphan = jdbc.queryForList("""
                select f.batch_id, f.location_code, sum(f.qty_delta) as flow_sum
                from pharm_stock_flow f
                where not exists (select 1 from pharm_stock s
                                  where s.batch_id = f.batch_id and s.location_code = f.location_code)
                group by 1, 2 limit 20
                """);
        assertTrue(orphan.isEmpty(), "以下 (批次, 地点) 有流水却没有余额行：" + orphan);
    }

    /**
     * 批次流水的 {@code qty_after} 链必须自洽：按 (批次,地点) 分组、按 id 升序逐笔累加，
     * 每一行的 {@code qty_after} 都要等于累加值。
     *
     * <p>V147 注释写明 {@code qty_after} 由带 {@code RETURNING} 的原子更新回读、不是应用层算的。
     * 应用层「读余额→加减→写回」在并发下会记出一串错误的 {@code qty_after} 而余额本身还是对的
     * ——<b>账面对、流水错</b>，比两个都错更难查。这条就是查它。
     */
    @Test
    void batchFlowQtyAfterChainIsConsistent() {
        requireTable("pharm_stock_flow", "车道A", "批次流水表");
        var bad = jdbc.queryForList("""
                select id, batch_id, location_code, qty_delta, qty_after, running
                from (select id, batch_id, location_code, qty_delta, qty_after,
                             sum(qty_delta) over (partition by batch_id, location_code order by id
                                                  rows between unbounded preceding and current row) as running
                      from pharm_stock_flow) t
                where qty_after <> running
                limit 20
                """);
        assertTrue(bad.isEmpty(),
                "以下批次流水的 qty_after 与逐笔累加值不符：" + bad
                        + "。qty_after 必须由带 RETURNING 的原子更新回读；应用层自行计算在并发下会记出"
                        + "一串错误的结存，而余额本身还是对的——账面对、流水错，比两个都错更难查。");
    }

    /** 批次流水类型域普查：全表 distinct flow_type 必须条条登记。 */
    @Test
    void everyBatchFlowTypeIsRegistered() {
        requireTable("pharm_stock_flow", "车道A", "批次流水表");
        List<String> types = jdbc.queryForList(
                "select distinct flow_type from pharm_stock_flow order by 1", String.class);
        List<String> unknown = types.stream().filter(t -> !BATCH_FLOW_TYPES.containsKey(t)).toList();
        assertTrue(unknown.isEmpty(),
                "pharm_stock_flow 出现未登记的类型 " + unknown + "。已登记：" + BATCH_FLOW_TYPES.keySet());
    }

    /**
     * 入库必须<b>双写两本账</b>并且合得上：批次子账 +N、总账 +N、既有入库单一张、IN 流水一条。
     *
     * <p>两本账各自自洽而合起来对不上，是药房系统最经典的坏法——单独看每本都是平的，
     * 没有任何单本账的用例会发现。所以跨账核对必须单独钉。
     */
    @Test
    void stockInDoubleWritesBothLedgersConsistently() {
        requireTable("pharm_stock", "车道A", "批次余额表");
        Long drugId = newDrugWithZeroStock("双写入库");
        long invBefore = drugLedgerSum(drugId);

        var resp = api("POST", "/api/pharm/stock/stock-in", Map.of(
                "drugId", drugId, "qty", 120, "batchNo", uniq("B"),
                "producedOn", BusinessDates.today().minusDays(30).toString(),
                "expireOn", BusinessDates.today().plusDays(400).toString(),
                "supplier", "对账供应商", "purchasePrice", "3.2500"));
        assertEquals(0, code(resp), "入库应成功：" + resp);
        flushIfInTransaction();

        assertEquals(120, stockOf(drugId), "总账 md_drug.stock 应 +120");
        assertEquals(invBefore + 120, drugLedgerSum(drugId), "总账流水 inv_transaction 应 +120");
        assertEquals(1L, count("""
                select count(*) from inv_transaction where drug_id = ? and type = 'IN'
                """, drugId), "既有口径的 IN 流水必须有且只有一条（不许另起炉灶写第二套加库存的 SQL）");

        Long batchQty = jdbc.queryForObject("""
                select coalesce(sum(s.qty), 0) from pharm_stock s
                join pharm_batch b on b.id = s.batch_id where b.drug_id = ?
                """, Long.class, drugId);
        assertEquals(120L, batchQty, "批次子账 pharm_stock 应 +120");

        Long flowSum = jdbc.queryForObject("""
                select coalesce(sum(f.qty_delta), 0) from pharm_stock_flow f
                join pharm_batch b on b.id = f.batch_id where b.drug_id = ?
                """, Long.class, drugId);
        assertEquals(120L, flowSum, "批次流水合计应 +120");

        assertEquals(1L, count("""
                select count(*) from pharm_stock_flow f join pharm_batch b on b.id = f.batch_id
                where b.drug_id = ? and f.src_stock_in_id is not null
                """, drugId),
                "IN 流水必须带 src_stock_in_id 勾稽键——没有勾稽键的两本账就是两个各说各话的数");
    }

    /** 报损同样双写：批次子账 −N、总账 −N；原因必填（5409）。 */
    @Test
    void scrapDecrementsBothLedgersAndRequiresAReason() {
        requireTable("pharm_stock", "车道A", "批次余额表");
        Long drugId = newDrugWithZeroStock("报损双写");
        api("POST", "/api/pharm/stock/stock-in", Map.of(
                "drugId", drugId, "qty", 60, "batchNo", uniq("B"),
                "expireOn", BusinessDates.today().plusDays(400).toString()));
        flushIfInTransaction();
        Long batchId = jdbc.queryForObject(
                "select id from pharm_batch where drug_id = ? order by id desc limit 1", Long.class, drugId);

        var noReason = api("POST", "/api/pharm/stock/scrap",
                Map.of("batchId", batchId, "qty", 5, "reason", ""));
        assertTrue(code(noReason) != 0,
                "报损原因必填——减少资产且不可逆的动作，原因是审计与药监检查的唯一线索：" + noReason);

        var ok = api("POST", "/api/pharm/stock/scrap",
                Map.of("batchId", batchId, "qty", 5, "reason", "V50 对账报损"));
        assertEquals(0, code(ok), "报损应成功：" + ok);
        flushIfInTransaction();

        assertEquals(55, stockOf(drugId), "总账应 −5");
        Long batchQty = jdbc.queryForObject("""
                select coalesce(sum(s.qty), 0) from pharm_stock s
                join pharm_batch b on b.id = s.batch_id where b.drug_id = ?
                """, Long.class, drugId);
        assertEquals(55L, batchQty, "批次子账应 −5");
        assertEquals(1L, count("""
                select count(*) from pharm_stock_flow f join pharm_batch b on b.id = f.batch_id
                where b.drug_id = ? and f.flow_type = 'SCRAP'
                """, drugId), "报损应留一条 SCRAP 流水");
    }

    /**
     * <b>本类最要紧的一条判据</b>：库存的<b>每一条</b>变动路径都必须写批次流水。
     *
     * <p>主控给车道 E 的判据原文是「这条要<b>对每一条流水路径都成立</b>：发药、退药、盘点、报损、拆零」。
     * 实测：{@code pharm_stock_flow} 今天只有 IN 与 SCRAP 两条写入路径；发药、退药、盘点<b>不进批次账</b>
     * （V147 迁移注释第三节已诚实自陈，称之为「已知漂移」）。
     *
     * <p><b>本类不接受把它当作可豁免的既定事实</b>。理由不是教条，是后果：
     * <ol>
     *   <li>批次层账面<b>单向偏高</b>——近效期预警报的仍然是估算，召回时算不出"这批还剩多少在架"；
     *   <li>退药回补<b>无从落到原批次</b>，不变式③（退药可追溯到原发药批次）直接不成立；
     *   <li>最危险的是<b>盘点</b>：盘点把汇总拉到实存，上面两条漏账造成的差额被<b>永久抹平</b>，
     *       批次层的错账原地不动、无人知晓。这正是"看起来一直对得上、实际每次都靠盘点擦屁股"。
     * </ol>
     * 本用例<b>就是要红</b>，红的位置即本版对账保障的真实缺口，交主控裁决（收敛或登记为不做）。
     */
    @Test
    void everyStockMutatingPathWritesTheBatchLedger() {
        requireTable("pharm_stock_flow", "车道A", "批次流水表");

        // 先用真实链路把漂移量化，不空口说白话
        Long drugId = newDrugWithZeroStock("漂移实证");
        api("POST", "/api/pharm/stock/stock-in", Map.of(
                "drugId", drugId, "qty", 100, "batchNo", uniq("B"),
                "expireOn", BusinessDates.today().plusDays(400).toString()));
        flushIfInTransaction();
        Rx rx = chargedPrescription(drugId, 9);
        dispenseService.dispense(rx.registrationId());
        flushIfInTransaction();

        int aggregate = stockOf(drugId);
        Long batchStock = jdbc.queryForObject("""
                select coalesce(sum(s.qty), 0) from pharm_stock s
                join pharm_batch b on b.id = s.batch_id where b.drug_id = ?
                """, Long.class, drugId);
        long drift = batchStock - aggregate;

        // 关闭本条有且只有两条路：**把账做全**（删掉对应条目），
        // 或**把它登记进 DECLARED_OUT_OF_SCOPE 并署名**（那样它就不再是无人认领的缺口）。
        // 刻意不提供第三条「改断言」的路——那正是 v49 明令要杜绝的「顺手把测试改绿」。
        var undeclared = BATCH_LEDGER_MISSING_WRITE_PATHS.entrySet().stream()
                .filter(e -> !DECLARED_OUT_OF_SCOPE.containsKey(e.getKey()))
                .toList();
        assertTrue(undeclared.isEmpty(),
                "【恒等式在批次层只对两条路径成立】发药 9 盒后：总账 md_drug.stock=" + aggregate
                        + "，批次子账合计=" + batchStock + "，**漂移 " + drift + " 盒**。\n"
                        + "缺失且**无人认领**的批次账写入路径：" + undeclared + "\n"
                        + "主控判据原文是「对每一条流水路径都成立：发药、退药、盘点、报损、拆零」，"
                        + "今天只有入库与报损两条进批次账。后果不是理论上的："
                        + "① 近效期预警仍是估算，召回时算不出这批还剩多少在架；"
                        + "② 退药回补落不到原批次，不变式③直接不成立；"
                        + "③ 盘点会把汇总拉到实存、把漏账差额**永久抹平**，批次层的错账原地不动无人知晓。\n"
                        + "收敛路径（V147 注释已给）：DispenseService 改为先按 pick_rule 取批、扣批次余额并写 OUT 流水，"
                        + "再扣 md_drug.stock。那要动既有发药链路与并发抢占模式，须单独评估、单独回归——"
                        + "**故本条交主控裁决：要么收敛，要么把它登记进 DECLARED_OUT_OF_SCOPE 并署名。**"
                        + "本类拒绝让它安静地留在注释里。");
    }

    /**
     * 漂移必须<b>随返回体下发</b>，不能只写在迁移注释里。
     *
     * <p>承 v46/v48 的诚实标注做法：口径与覆盖率不随体下发，管理者会把"批次库存 100% 准确"
     * 当成全院全历史口径——而实情可能只是"全院 3 个药启用了批次管理"。
     */
    @Test
    void batchLedgerDeclaresItsDriftAndCoverageInTheResponseBody() {
        requireTable("pharm_stock", "车道A", "批次余额表");
        String balance = String.valueOf(data(api("GET", "/api/pharm/stock/balance", null)));
        for (String k : List.of("drift", "aggregateStock", "coverage")) {
            assertTrue(balance.contains(k),
                    "GET /api/pharm/stock/balance 的返回体必须带 " + k
                            + "——漂移与覆盖率只写在迁移注释里，管理者看不到，"
                            + "会把批次库存当成全院全历史的准确口径。实际返回体：" + trunc(balance));
        }
        String cov = String.valueOf(data(api("GET", "/api/pharm/stock/coverage", null)));
        String covLow = cov.toLowerCase(Locale.ROOT);
        assertTrue(covLow.contains("coverage") || covLow.contains("rate") || covLow.contains("pct"),
                "GET /api/pharm/stock/coverage 应给出批次管理覆盖率——"
                        + "「批次库存 100% 准确」很可能只是「全院 3 个药启用了批次管理」，"
                        + "覆盖率是这句话的分母：" + trunc(cov));
        String rec = String.valueOf(data(api("GET", "/api/pharm/stock/reconcile", null)));
        assertTrue(rec != null && !rec.isBlank() && !"null".equals(rec),
                "GET /api/pharm/stock/reconcile 必须给出逐行结论——它是把恒等式验一遍的端点，"
                        + "返回空等于没有对账：" + rec);
    }

    /**
     * 拆零<b>换算系数必须有维护入口</b>——否则 5460–5479 整段在生产上一次也走不通。
     *
     * <p>{@link #splitRefusesDrugsWithoutAMaintainedConversionFactor()} 证明了「拒绝猜 spec」是对的；
     * 本条证明的是另一半：<b>既然不许猜，就必须给药剂科一个能录进去的地方</b>。
     * 只堵不疏的结果是 {@code POST /pharm-ops/split/dispense} 对<b>每一味药</b>都返 5460。
     *
     * <p>判据走源码扫描而不是「库里有没有一行 pack_size 非空」：后者被谁手工 update 一行就会变绿，
     * 而那恰恰是最坏的情况——功能看着能用，实施期却发现只能靠直连改库维护主数据。
     */
    @Test
    void splitConversionFactorHasAMaintenanceWritePath() throws Exception {
        java.nio.file.Path root = repoRoot();
        List<String> writers = new ArrayList<>();
        for (java.nio.file.Path dir : List.of(root.resolve("modules"), root.resolve("platform"),
                root.resolve("server/src/main/java"))) {
            if (!java.nio.file.Files.isDirectory(dir)) continue;
            try (var s = java.nio.file.Files.walk(dir)) {
                for (java.nio.file.Path f : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (f.toString().contains("target")) continue;
                    for (String line : java.nio.file.Files.readAllLines(f)) {
                        String x = line.strip();
                        if (x.startsWith("*") || x.startsWith("//") || x.startsWith("/*")) continue;
                        boolean names = x.contains("pack_size") || x.contains("packSize")
                                || x.contains("min_unit") || x.contains("minUnit");
                        boolean writes = x.contains("update md_drug") || x.contains("insert into md_drug")
                                || x.contains("setPackSize") || x.contains("set pack_size");
                        if (names && writes) writers.add(root.relativize(f) + ": " + x);
                    }
                }
            }
        }
        assertTrue(!writers.isEmpty(), UNDELIVERED + """
                md_drug.pack_size / min_unit **没有任何写入路径**，拆零（5460–5479）在生产上不可达：
                  · V150 只加了两列，全仓 grep 这两个名字只出现在 SQL 注释与读侧；
                  · MasterDataController 的 CSV 导入只认 8 列（code..antibiotic + 费用类别/自费）；
                  · PUT /masterdata/drugs/{id}/attrs 的 ItemAttrReq 只有 feeCategoryCode 与 selfPay。
                于是 POST /api/outpatient/pharm-ops/split/dispense 对**每一味药**都返 5460。
                注意「拒绝从 spec 文本正则出系数」是对的、不要改；错在**只堵不疏**——
                既然不许猜，就必须给药剂科一个能录进去的地方（同 v50 车道C 给高危与 LASA 补维护端点的做法）。""");
    }

    /** 从测试工作目录（server 模块）向上找仓库根：monorepo 里必然找得到，找不到即环境异常。 */
    private static java.nio.file.Path repoRoot() {
        java.nio.file.Path p = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && p != null; i++, p = p.getParent()) {
            if (java.nio.file.Files.isDirectory(p.resolve("modules"))
                    && java.nio.file.Files.isDirectory(p.resolve("platform"))) {
                return p;
            }
        }
        return fail("定位不到仓库根（从 " + System.getProperty("user.dir") + " 向上找 modules/ 与 platform/）");
    }

    // ==================================================================================
    // §3 拆零子账（车道 D：pharm_split_stock / pharm_split_txn / pharm_split_dispense）
    // ==================================================================================

    /** <b>拆零子账恒等式</b>：对每味药，{@code remain_qty == sum(pharm_split_txn.qty)}（最小单位）。 */
    @Test
    void splitRemainEqualsSplitTxnSumForEveryDrug() {
        requireTable("pharm_split_stock", "车道D", "拆零余量池，5460–5479");
        requireTable("pharm_split_txn", "车道D", "拆零余量流水");
        var bad = jdbc.queryForList("""
                select s.drug_id, s.remain_qty, coalesce(t.total, 0) as txn_sum
                from pharm_split_stock s
                left join (select drug_id, sum(qty) as total from pharm_split_txn group by 1) t
                  on t.drug_id = s.drug_id
                where s.remain_qty <> coalesce(t.total, 0)
                limit 20
                """);
        assertTrue(bad.isEmpty(),
                "【拆零子账】以下药品的散装余量与拆零流水合计对不上：" + bad
                        + "。拆零余量是本版新造出来的一种库存形态，V81 的整包盘点盘不到它，"
                        + "它一旦对不上账就没有任何其它机制会发现。");

        var orphan = jdbc.queryForList("""
                select t.drug_id, sum(t.qty) as txn_sum from pharm_split_txn t
                where not exists (select 1 from pharm_split_stock s where s.drug_id = t.drug_id)
                group by 1 limit 20
                """);
        assertTrue(orphan.isEmpty(), "以下药品有拆零流水却没有余量行：" + orphan);
    }

    /** 拆零流水的 {@code remain_after} 链必须自洽（同批次流水 qty_after 的道理）。 */
    @Test
    void splitTxnRemainAfterChainIsConsistent() {
        requireTable("pharm_split_txn", "车道D", "拆零余量流水");
        var bad = jdbc.queryForList("""
                select id, drug_id, qty, remain_after, running from (
                  select id, drug_id, qty, remain_after,
                         sum(qty) over (partition by drug_id order by id
                                        rows between unbounded preceding and current row) as running
                  from pharm_split_txn) t
                where remain_after <> running limit 20
                """);
        assertTrue(bad.isEmpty(), "以下拆零流水的 remain_after 与逐笔累加值不符：" + bad);
    }

    /** 拆零流水类型域普查。 */
    @Test
    void everySplitTxnTypeIsRegistered() {
        requireTable("pharm_split_txn", "车道D", "拆零余量流水");
        List<String> types = jdbc.queryForList(
                "select distinct type from pharm_split_txn order by 1", String.class);
        List<String> unknown = types.stream().filter(t -> !SPLIT_TXN_TYPES.containsKey(t)).toList();
        assertTrue(unknown.isEmpty(),
                "pharm_split_txn 出现未登记的类型 " + unknown + "。已登记：" + SPLIT_TXN_TYPES.keySet());
    }

    /**
     * 不变式②的<b>拆零腿</b>：开包必须按 {@code pack_size} 在两本账之间严丝合缝地换算。
     *
     * <p>开包那一盒在总账里是 {@code inv_transaction OUT −1 盒}，在拆零账里是
     * {@code pharm_split_txn OPEN +24 片}，同一个 {@code ref_no}。二者必须满足
     * <pre>|Σ总账OUT(盒)| × pack_size == Σ拆零账OPEN(片)</pre>
     * 差一点就是「按盒扣、按片发」——账上扣 1 盒、实际发 3 片，剩下 21 片去哪了没人知道，
     * 而且这个差额<b>会被下一次盘点抹平</b>。
     */
    @Test
    void splitOpeningConservesQuantityAcrossLedgers() {
        requireTable("pharm_split_txn", "车道D", "拆零余量流水");
        Long drugId = newDrugWithZeroStock("拆零换算");
        jdbc.update("update md_drug set pack_size = 24, min_unit = '片' where id = ?", drugId);
        inventoryService.acceptStockIn(inventoryService.stockIn(drugId, 10, "V50BATCH-S",
                BusinessDates.today().plusDays(400), "对账供应商", null, null).getId(), null);
        flushIfInTransaction();

        // 要 30 片：余量 0 → 需开 2 盒（向上取整），发 30 片，余 18 片
        var resp = api("POST", "/api/outpatient/pharm-ops/split/dispense",
                Map.of("drugId", drugId, "qtyMinUnit", 30));
        assertEquals(0, code(resp), "拆零发药应成功：" + resp);
        flushIfInTransaction();

        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) resp.get("data");
        String dispenseNo = String.valueOf(d.get("dispenseNo"));

        long packsOut = -nz(jdbc.queryForObject(
                "select coalesce(sum(qty),0) from inv_transaction where drug_id = ? and ref_no = ? and type='OUT'",
                Long.class, drugId, dispenseNo));
        long openedMinUnits = nz(jdbc.queryForObject(
                "select coalesce(sum(qty),0) from pharm_split_txn where drug_id = ? and ref_no = ? and type='OPEN'",
                Long.class, drugId, dispenseNo));
        assertEquals(2L, packsOut, "凑 30 片需开 2 盒（24 片/盒，向上取整）");
        assertEquals(packsOut * 24, openedMinUnits,
                "【跨账换算】总账出库 " + packsOut + " 盒 × pack_size 24 必须等于拆零账入池 "
                        + openedMinUnits + " 片。差一点就是「按盒扣、按片发」，"
                        + "而这个差额会被下一次盘点抹平。");
        assertEquals(8, stockOf(drugId), "整包库存应 −2 盒");
        assertEquals(18L, nz(jdbc.queryForObject(
                "select remain_qty from pharm_split_stock where drug_id = ?", Long.class, drugId)),
                "散装余量应为 2×24−30 = 18 片");
        // 单位口径不得混：inv_transaction 永远是盒，pharm_split_txn 永远是片
        assertEquals(0L, count("""
                select count(*) from inv_transaction where drug_id = ? and ref_no = ? and abs(qty) > 24
                """, drugId, dispenseNo),
                "总账里出现了最小单位量级的数字——单位口径混了，"
                        + "InventoryService.expiryWarnings 的 sumOutReturnQty 会当场算错");
    }

    /**
     * 拆零必须有<b>维护过的</b>换算系数，不许猜。
     *
     * <p>最典型的假实现是拿 {@code spec} 文本（「0.25g*24粒/盒」）正则出 24。规格是自由录入，
     * 「10ml:0.1g*5支/盒」「24片×2板」「1g/瓶」各家写法都不同，猜错一位就是把「1 盒」当「1 片」发出去。
     */
    @Test
    void splitRefusesDrugsWithoutAMaintainedConversionFactor() {
        requireTable("pharm_split_stock", "车道D", "拆零余量池");
        Long drugId = newDrugWithZeroStock("未维护系数");   // spec 里明明写着 24 粒，但 pack_size 为 NULL
        inventoryService.acceptStockIn(inventoryService.stockIn(drugId, 10, "V50BATCH-N",
                BusinessDates.today().plusDays(400), "对账供应商", null, null).getId(), null);
        flushIfInTransaction();

        var resp = api("POST", "/api/outpatient/pharm-ops/split/dispense",
                Map.of("drugId", drugId, "qtyMinUnit", 5));
        assertTrue(code(resp) != 0,
                "未维护 pack_size/min_unit 的药必须拒绝拆零，**尤其不许从 spec 文本正则出系数**："
                        + "规格是自由录入，猜错一位就是把「1 盒」当「1 片」发出去。实际：" + resp);
        assertEquals(0L, count("select count(*) from pharm_split_txn where drug_id = ?", drugId),
                "被拒的拆零不得留下半条流水");
        assertEquals(10, stockOf(drugId), "被拒的拆零不得动整包库存");
    }

    // ==================================================================================
    // §4 双人核对与新流程（车道 B / C）
    // ==================================================================================

    /**
     * 不变式⑤：摆药人 ≠ 核对人，且这条<b>由数据库兜底</b>。
     *
     * <p>与 gate 无关（承 v48 病理双签的口径）：gate 管的是「<b>未核对能否发出</b>」，
     * 不管「<b>能否一个人把两个位都占了</b>」。一个人签两次不叫双人核对。
     * 只在服务层判是不够的——直连改库、以及将来新写的代码路径都绕得过去。
     */
    @Test
    void pickerAndCheckerAreTwoDifferentPeopleEnforcedByTheDatabase() {
        requireTable("pharm_dispense_check", "车道C", "发药核对单，5440–5459");
        Long constraints = count("""
                select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid
                where t.relname = 'pharm_dispense_check' and c.contype = 'c'
                  and pg_get_constraintdef(c.oid) ilike '%checker_id%' and pg_get_constraintdef(c.oid) ilike '%picker_id%'
                """);
        assertTrue(constraints > 0,
                "pharm_dispense_check 缺少「核对人 <> 摆药人」的 CHECK 约束。"
                        + "只在服务层判不够——直连改库与将来新写的代码路径都绕得过去，"
                        + "而这一条与 gate 无关、永远生效。");

        var same = jdbc.queryForList("""
                select id, registration_id, picker_id, checker_id from pharm_dispense_check
                where checker_id is not null and checker_id = picker_id limit 20
                """);
        assertTrue(same.isEmpty(), "以下核对单一个人占了双人核对的两个位：" + same);
    }

    /**
     * 车道 C 的核对流程必须<b>有写入路径</b>。
     *
     * <p>{@code DispenseCheckService} 已存在，但全仓<b>没有任何 Controller 引用它</b>——
     * 一个没有入口的服务等于没有交付：核对单永远建不出来，
     * {@code pharm_dispense_check} 永远是空表，于是
     * {@link #pickerAndCheckerAreTwoDifferentPeopleEnforcedByTheDatabase()} 的行级断言
     * <b>恒真而毫无意义</b>（空表当然没有违规行）。<b>一条永远不会红的断言等于没有断言</b>，
     * 所以入口的有无必须单独钉。
     */
    @Test
    void dispenseCheckFlowHasAWritePath() {
        // 只认 dispense-check 前缀：泛匹配 "/check" 会撞上病历完整性预检之类的既有端点，
        // 让这条断言在核对流程根本不存在时也变绿——那正是本类要杜绝的「恒真断言」。
        Set<String> eps = endpointsMatching("dispense-check", "dispense_check", "dispensecheck");
        eps.removeIf(e -> e.startsWith("GET "));
        assertTrue(!eps.isEmpty(), UNDELIVERED + """
                发药核对（5440–5459）没有任何写入端点：DispenseCheckService 存在，但没有 Controller 引用它。
                没有入口 = 核对单永远建不出来 = pharm_dispense_check 永远是空表 =
                「摆药人≠核对人」「未核对不得发药」两条断言恒真而毫无意义。
                V149 迁移注释里已经写了端点路径 POST /api/outpatient/dispense-check/registrations/{id}/dispense，
                但它今天不存在。""" + "\n当前 /api/outpatient 下与核对相关的端点："
                + endpointsMatching("dispense-check", "/check"));
    }

    /**
     * 不变式⑥（新流程腿）：摆药单建单的防重也必须在<b>真并发</b>下只让一方成功。
     *
     * <p>走反射调 {@code PharmPickService.create}，<b>不 import 车道 B 的类</b>：
     * 焊死在别人的方法签名上，别人一改签名本类连编译都过不去，"改签名"就会变成"顺手改测试"。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void newPickingFlowKeepsTheAntiDoubleClaimPattern() throws Exception {
        Object svc;
        Method create;
        try {
            svc = ctx.getBean(Class.forName("cn.hip.outpatient.service.PharmPickService"));
            create = svc.getClass().getMethod("create", Long.class, String.class, Long.class);
        } catch (ClassNotFoundException | NoSuchMethodException | org.springframework.beans.BeansException e) {
            fail(UNDELIVERED + "取不到 PharmPickService.create(Long,String,Long)："
                    + e + "。摆药单建单的防重必须在真并发下验过——"
                    + "既有 claimDispense 的抢占模式是本仓 P1-9 付过学费换来的，新流程不许倒退。");
            return;
        }

        AtomicReference<Long> drugRef = new AtomicReference<>();
        AtomicReference<Rx> rxRef = new AtomicReference<>();
        inTx(() -> {
            Long d = newDrugWithZeroStock("并发摆药");
            inventoryService.acceptStockIn(inventoryService.stockIn(d, 100, "V50BATCH-P",
                    BusinessDates.today().plusDays(300), "对账供应商", null, null).getId(), null);
            drugRef.set(d);
            rxRef.set(chargedPrescription(d, 5));
        });
        Long drugId = drugRef.get();
        Rx rx = rxRef.get();
        // 摆药是作业动作，识别不出人就不落单（5430）。并发实证必须带一个真人 id，
        // 否则 6 个线程会全部倒在「无法识别当前登录用户」上，测出来的是**参数没传**而不是抢占
        Long operator = jdbc.queryForObject("select id from sys_user order by id limit 1", Long.class);
        assertNotNull(operator, "测试库须有用户种子");
        try {
            int[] r = race(() -> {
                try {
                    create.invoke(svc, rx.registrationId(), null, operator);
                } catch (InvocationTargetException ite) {
                    Throwable t = ite.getTargetException();
                    if (t instanceof RuntimeException re) throw re;
                    throw new IllegalStateException(t);
                } catch (IllegalAccessException iae) {
                    throw new IllegalStateException(iae);
                }
            });
            assertEquals(1, r[0],
                    "同一次挂号并发建摆药单**只能成功一次**——成功多次就是同一批药被配两遍，"
                            + "而且两张单会各自走到「已配好」，发药台看到两份");
            assertEquals(1L, count(
                    "select count(*) from pharm_picking_order where registration_id = ?", rx.registrationId()),
                    "摆药单只应有一张");
        } finally {
            inTx(() -> jdbc.update("delete from pharm_picking_line where picking_id in "
                    + "(select id from pharm_picking_order where registration_id = ?)", rx.registrationId()));
            inTx(() -> jdbc.update("delete from pharm_picking_order where registration_id = ?",
                    rx.registrationId()));
            cleanup(drugId, rx.registrationId());
        }
    }

    // ==================================================================================
    // §5 gate 与边界
    // ==================================================================================

    /**
     * 铁律 4：新增拦截一律 {@code pharm.gate.<点位>} 三态、默认 <b>warn</b>。
     *
     * <p>此前从无这些校验（双人核对/效期/批次必填全仓零命中），直接 block 会让存量流程
     * 瞬间大面积失败——v47 体征 gate 已经付过学费的同一条口径。
     * 坏配置回落 warn 不回落 off：宁可多提示，不可静默失效。
     */
    @Test
    void newPharmacyGatesAreThreeStateAndDefaultToWarn() {
        var rows = jdbc.queryForList(
                "select cfg_key, cfg_value, coalesce(remark,'') as remark from sys_config "
                        + "where cfg_key like 'pharm.gate.%' order by cfg_key");
        assertTrue(!rows.isEmpty(), UNDELIVERED
                + "sys_config 里一个 pharm.gate.* 键都没有。本版新增的拦截一律须挂三态 gate、默认 warn（铁律 4）。");

        for (Map<String, Object> r : rows) {
            String key = (String) r.get("cfg_key");
            String val = String.valueOf(r.get("cfg_value"));
            String remark = String.valueOf(r.get("remark"));
            assertEquals("warn", val,
                    "gate [" + key + "] 的**出厂值必须是 warn**，实际 " + val
                            + "。此前从无这些校验，直接 block 会让存量流程瞬间大面积失败；"
                            + "收紧到 block 的时机由院方按自家数据质量决定（v47 已定此口径）。");
            for (String mode : List.of("off", "warn", "block")) {
                assertTrue(remark.contains(mode),
                        "gate [" + key + "] 的 remark 必须写明三态 off/warn/block（缺 " + mode + "）——"
                                + "配置手册「新键必须补一行」的同一纪律。当前 remark：" + remark);
            }
        }
    }

    /**
     * 麻精药品：不许按药名猜。
     *
     * <p>{@code md_drug} 没有「精麻毒放」属性位（v46 的 1444★/1445★ 正因此标 available:false）。
     * 「含吗啡即毒麻」是危险假实现——管制药品台账要同时对得上药监与卫健两条线，
     * 猜错一味药出的是<b>法律问题</b>不是数据问题。
     *
     * <p>判据是<b>交叉</b>的：有麻精端点却没有主数据属性位 = 当场红。
     * 今天两者都没有，故本条应绿；哪天有人只补了端点没补属性位，它立刻变红。
     */
    @Test
    void narcoticFlowRequiresAMasterDataAttributeNotADrugNameGuess() {
        Set<String> eps = endpointsMatching("narcotic", "psychotropic", "controlled", "majing");
        Long attrCols = count("""
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='md_drug'
                  and (column_name like '%control%' or column_name in
                       ('narcotic_class','special_class','drug_control_level'))
                """);
        if (!eps.isEmpty()) {
            assertTrue(attrCols > 0,
                    "出现了麻精类端点 " + eps + "，但 md_drug 仍无「精麻毒放」属性位。"
                            + "没有属性位就只能按药名猜，而「含吗啡即毒麻」是危险假实现："
                            + "管制药品台账要同时对得上药监与卫健两条线，猜错一味出的是法律问题。"
                            + "要做麻精流程，**先补主数据属性位**。");
        } else {
            assertTrue(DECLARED_OUT_OF_SCOPE.containsKey("麻精药品专项流程（5520–5539）"),
                    "本版不做麻精流程，必须在 DECLARED_OUT_OF_SCOPE 登记——登记了才允许不做");
        }
    }

    /**
     * 高危药品与看似听似<b>只能靠维护</b>，不许自动生成。
     *
     * <p>{@code md_drug.high_alert} 三态（true/false/<b>null 未维护</b>）不得有 default：
     * 给 default false 等于替药剂科宣称「全库没有高危药」，而且此后再也分不清
     * 「已判定非高危」与「根本没人看过」，高危目录维护完成度这个管理指标永远算不出来。
     */
    @Test
    void highAlertAndLasaAreMaintainedNotGuessed() {
        Long defaults = count("""
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='md_drug'
                  and column_name='high_alert' and column_default is not null
                """);
        assertEquals(0L, defaults,
                "md_drug.high_alert 不得有 default——三态语义是 true 是 / false 否 / **null 未维护**。"
                        + "给 default false 等于替药剂科宣称全库无高危药，"
                        + "且此后分不清「已判定非高危」与「没人看过」。");

        requireTable("pharm_lasa_pair", "车道C", "看似听似对照表");
        // 无序对只存一行：存两行迟早只删一半，变成「从 A 查得到、从 B 查不到」的静默漏提示
        assertTrue(count("""
                select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid
                where t.relname = 'pharm_lasa_pair' and c.contype = 'c'
                  and pg_get_constraintdef(c.oid) like '%drug_id_lo%<%drug_id_hi%'
                """) > 0, "pharm_lasa_pair 须有 lo < hi 的 CHECK：存两行迟早只删一半，成为静默漏提示");
        assertEquals(0L, count("select count(*) from pharm_lasa_pair where drug_id_lo >= drug_id_hi"),
                "存在 lo >= hi 的 LASA 行");
    }

    // ==================================================================================
    // §6 并发与清理工具
    // ==================================================================================

    private static final int RACE_THREADS = 6;

    private void inTx(Runnable r) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> r.run());
    }

    /**
     * 并发跑同一动作，返回 {成功数, 抢占失败数}；失败必须是业务异常或锁冲突，不收约束冲突。
     *
     * <p>失败的业务码会打印出来。这不是装饰：抢占用例最常见的假红是
     * <b>「6 个线程全倒在同一个参数校验上」</b>（例如必填的操作人没传），
     * 此时"成功 0 次"看起来像抢占失效，实际测的是参数。把码打出来，一眼能分辨。
     */
    private int[] race(Runnable action) throws Exception {
        var codes = java.util.Collections.synchronizedList(new ArrayList<Integer>());
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(RACE_THREADS);
        try {
            CountDownLatch ready = new CountDownLatch(RACE_THREADS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < RACE_THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        tx.executeWithoutResult(s -> action.run());
                        return true;
                    } catch (Exception e) {
                        Throwable root = e;
                        while (root.getCause() != null) root = root.getCause();
                        // **不收 DuplicateKey**：靠唯一索引兜底照样"1 成功 5 失败"，
                        // 恰好掩盖抢占防线失效（第六轮审阅 P3 的同一条教训）。
                        // 例外：摆药单建单的防重本就写明"硬保证在索引上"，服务层已把
                        // DataIntegrityViolationException 转成 5421 业务异常，故仍走业务异常这一支。
                        boolean biz = e instanceof HipBizException || root instanceof HipBizException
                                || e.getClass().getName().contains("CannotAcquireLock")
                                || root.getClass().getName().contains("CannotAcquireLock")
                                || e.getClass().getName().contains("Deadlock")
                                || root.getClass().getName().contains("Deadlock");
                        assertTrue(biz, "抢占失败应为业务异常或锁冲突（裸约束冲突=防线失效），实际：" + root);
                        if (root instanceof HipBizException hbe) codes.add(hbe.code);
                        else if (e instanceof HipBizException hbe2) codes.add(hbe2.code);
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            go.countDown();
            int ok = 0;
            int failed = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(60, TimeUnit.SECONDS)) ok++;
                else failed++;
            }
            System.out.println("[V50 并发实证] 成功=" + ok + " 抢占失败=" + failed + " 失败业务码=" + codes);
            return new int[]{ok, failed};
        } finally {
            pool.shutdownNow();
        }
    }

    /** 非事务用例的收尾：本类自建的数据不留在库里，免得污染别人的对账窗口。 */
    private void cleanup(Long drugId, Long registrationId) {
        inTx(() -> {
            try {
                if (registrationId != null) {
                    jdbc.update("delete from outp_order where registration_id = ?", registrationId);
                    jdbc.update("delete from outp_charge where registration_id = ?", registrationId);
                    jdbc.update("delete from outp_registration where id = ?", registrationId);
                }
                jdbc.update("delete from inv_transaction where drug_id = ?", drugId);
                jdbc.update("delete from inv_stock_in where drug_id = ?", drugId);
                jdbc.update("delete from md_drug where id = ?", drugId);
            } catch (RuntimeException e) {
                // 收尾失败不该让用例结论翻转：留一味孤儿测试药远比掩盖真实断言结果好
                System.out.println("[V50] 收尾清理未完全成功（不影响判据）：" + e.getMessage());
            }
        });
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String trunc(String s) {
        return s == null || s.length() <= 600 ? s : s.substring(0, 600) + "…";
    }
}
