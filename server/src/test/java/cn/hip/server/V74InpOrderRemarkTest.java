package cn.hip.server;

import cn.hip.inpatient.entity.InpOrder;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.OrderLine;
import cn.hip.inpatient.web.InpatientController;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v74 追加（1006★ 唯一缺口）：住院医嘱的备注 / 加急 / 注意事项。
 *
 * <p>断言口径三条，与 V44OrderFieldsTest 的门诊侧同构：
 * <ol>
 *   <li><b>契约保护</b>——用既有 7 参 {@code OrderLine} 开单，三个新字段落 null、
 *       JSON 里多出且仅多出这三个键；既有键一个不少。</li>
 *   <li>三字段存取往返；空串按 null 落库（与门诊 V137 同口径）。</li>
 *   <li><b>到执行者屏幕上</b>——护士站待执行队列与长期医嘱执行行两条查询都带出三字段，
 *       历史行（urgent 为 null）在执行行查询里读作 false。</li>
 * </ol>
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V74InpOrderRemarkTest {

    @Autowired InpatientService inpatientService;
    @Autowired InpatientController inpatientController;
    @Autowired PatientService patientService;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired ObjectMapper objectMapper;

    private Long admit() {
        Patient p = new Patient();
        p.setName("嘱注" + System.nanoTime());
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("2000"), "CASH", null).getId();
    }

    private Long drugId() {
        return seeds.drug("嘱注药" + System.nanoTime() % 100000).getId();
    }

    @Test
    void 既有七参开单_新字段为空且序列化只多三个键() throws Exception {
        Long admId = admit();
        InpOrder o = inpatientService.createOrders(admId,
                List.of(new OrderLine("DRUG", drugId(), 1, "口服", "bid", "1粒", "TEMP")), null).get(0);
        assertNull(o.getRemark());
        assertNull(o.getUrgent(), "不传加急时落 null，不伪造 false（历史行同口径）");
        assertNull(o.getNotice());

        @SuppressWarnings("unchecked")
        Map<String, Object> json = objectMapper.readValue(objectMapper.writeValueAsString(o), Map.class);
        for (String k : List.of("id", "admissionId", "groupNo", "orderType", "itemId", "itemName", "qty",
                "amount", "usageRoute", "frequency", "dosePerTime", "status", "orderNature", "createdAt")) {
            assertTrue(json.containsKey(k), "既有键不得丢失：" + k);
        }
        assertTrue(json.containsKey("remark") && json.containsKey("urgent") && json.containsKey("notice"));
    }

    @Test
    void 三字段往返_空串落null_四类医嘱一视同仁() {
        Long admId = admit();
        Long labItem = jdbc.queryForObject(
                "select id from md_charge_item where category = 'LAB' and enabled limit 1", Long.class);
        List<InpOrder> saved = inpatientService.createOrders(admId, List.of(
                new OrderLine("DRUG", drugId(), 1, "口服", "bid", "1粒", "TEMP", "术前禁食", true, "青霉素过敏史"),
                new OrderLine("LAB", labItem, 1, null, null, null, "TEMP", "   ", false, "")), null);
        InpOrder drug = saved.get(0), lab = saved.get(1);
        assertEquals("术前禁食", drug.getRemark());
        assertEquals(Boolean.TRUE, drug.getUrgent());
        assertEquals("青霉素过敏史", drug.getNotice());
        assertNull(lab.getRemark(), "空白备注按 null 落库");
        assertEquals(Boolean.FALSE, lab.getUrgent());
        assertNull(lab.getNotice(), "空注意事项按 null 落库");

        em.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "select remark, urgent, notice from inp_order where id = ?", drug.getId());
        assertEquals("术前禁食", row.get("remark"));
        assertEquals(Boolean.TRUE, row.get("urgent"));
        assertEquals("青霉素过敏史", row.get("notice"));
    }

    @Test
    void 护士站待执行队列带出三字段() {
        Long admId = admit();
        InpOrder o = inpatientService.createOrders(admId, List.of(
                new OrderLine("DRUG", drugId(), 1, "口服", "bid", "1粒", "TEMP", "饭后服", true, null)), null).get(0);
        em.flush();
        Map<String, Object> mine = inpatientController.pendingOrders().getData().stream()
                .filter(m -> o.getId().equals(m.get("orderId"))).findFirst().orElseThrow();
        assertEquals("饭后服", mine.get("remark"));
        assertEquals(Boolean.TRUE, mine.get("urgent"));
        assertNull(mine.get("notice"));
    }

    /**
     * 技术债：护士站待执行队列不得混入长期医嘱（首次执行前 LONG 也是 CREATED，点「执行」只会得 9125）。
     * 长期医嘱只走执行行表；临时医嘱照常出现在队列里。
     */
    @Test
    void 待执行队列只含临时医嘱_长期医嘱走执行行() {
        Long admId = admit();
        InpOrder temp = inpatientService.createOrders(admId, List.of(
                new OrderLine("DRUG", drugId(), 1, "口服", "bid", "1粒", "TEMP")), null).get(0);
        InpOrder lng = inpatientService.createOrders(admId, List.of(
                new OrderLine("DRUG", drugId(), 1, "口服", "bid", "1粒", "LONG")), null).get(0);
        em.flush();

        List<Long> queue = inpatientController.pendingOrders().getData().stream()
                .map(m -> (Long) m.get("orderId")).toList();
        assertTrue(queue.contains(temp.getId()), "临时医嘱应在待执行队列");
        assertFalse(queue.contains(lng.getId()), "长期医嘱不得在待执行队列（点执行只会 9125）");

        // 长期医嘱的正路：执行行表里能看到它
        assertTrue(inpatientController.execLines(BusinessDates.today().toString()).getData().stream()
                .anyMatch(m -> lng.getId().equals(m.get("order_id"))), "长期医嘱应出现在执行行表");
    }

    @Test
    void 长期医嘱执行行带出三字段_历史行加急读作false() {
        Long admId = admit();
        InpOrder withNote = inpatientService.createOrders(admId, List.of(
                new OrderLine("DRUG", drugId(), 1, "口服", "bid", "1粒", "LONG", "监测血压", true, "低于90/60暂停")), null).get(0);
        InpOrder legacy = inpatientService.createOrders(admId, List.of(
                new OrderLine("DRUG", drugId(), 1, "口服", "qd", "1粒", "LONG")), null).get(0);
        em.flush();
        List<Map<String, Object>> lines = inpatientController.execLines(BusinessDates.today().toString()).getData();

        Map<String, Object> a = lines.stream().filter(m -> withNote.getId().equals(m.get("order_id"))).findFirst().orElseThrow();
        assertEquals("监测血压", a.get("remark"));
        assertEquals(Boolean.TRUE, a.get("urgent"));
        assertEquals("低于90/60暂停", a.get("notice"));

        Map<String, Object> b = lines.stream().filter(m -> legacy.getId().equals(m.get("order_id"))).findFirst().orElseThrow();
        assertNull(b.get("remark"));
        assertEquals(Boolean.FALSE, b.get("urgent"), "urgent 为 null 的行在执行行查询里按 coalesce 读作 false");
    }
}
