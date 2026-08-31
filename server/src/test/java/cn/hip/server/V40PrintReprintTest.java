package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.server.web.PrintReportController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** v40：票据补打检索与打印留痕（收费台此前只能打刚结算那一单，重打无入口）。 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V40PrintReprintTest {

    @Autowired PrintReportController printController;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    private record Charged(Long chargeId, String chargeNo, String patientName) {}

    private Charged makeCharge() {
        String name = "补打" + System.nanoTime() % 100000;
        Patient p = new Patient();
        p.setName(name);
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(cn.hip.platform.core.config.BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(pid, s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        Long labItem = seeds.chargeItem("血常规", "LAB").getId();
        doctorStationService.createOrders(rid,
                List.of(new OrderLine("LAB", labItem, 1, null, null, null, null)), null);
        var charge = chargeService.settle(rid, "CASH", null);
        em.flush();
        return new Charged(charge.getId(), charge.getChargeNo(), name);
    }

    @Test
    void searchByChargeNoAndPatientName() {
        var c = makeCharge();
        // 按结算单号
        var byNo = printController.chargeSearch(c.chargeNo(), null).getData();
        assertTrue(byNo.stream().anyMatch(r -> c.chargeId().equals(((Number) r.get("id")).longValue())),
                "应能按结算单号检索到");
        // 按患者姓名
        var byName = printController.chargeSearch(c.patientName(), null).getData();
        assertTrue(byName.stream().anyMatch(r -> c.chargeId().equals(((Number) r.get("id")).longValue())),
                "应能按患者姓名检索到");
        // 按日期（今天）
        String today = cn.hip.platform.core.config.BusinessDates.today().toString();
        assertTrue(printController.chargeSearch(c.chargeNo(), today).getData().size() >= 1, "按日期应命中");
        // 不存在的关键词
        assertTrue(printController.chargeSearch("绝不存在的关键词ZZZ", null).getData().isEmpty());
    }

    @Test
    void printLogCountsReprints() {
        var c = makeCharge();
        var before = printController.chargeSearch(c.chargeNo(), null).getData().get(0);
        assertEquals(0, ((Number) before.get("print_count")).intValue(), "初始未打印");

        assertEquals(0, printController.logPrint("CHARGE", c.chargeId(), admin).getCode());
        assertEquals(0, printController.logPrint("CHARGE", c.chargeId(), admin).getCode());
        em.flush();
        var after = printController.chargeSearch(c.chargeNo(), null).getData().get(0);
        assertEquals(2, ((Number) after.get("print_count")).intValue(), "补打两次应计 2");

        // 非法单据类型
        assertEquals(4000, printController.logPrint("NOPE", c.chargeId(), admin).getCode());
        // 挂号凭条同表留痕
        assertEquals(0, printController.logPrint("REGISTRATION", 1L, admin).getCode());
    }

    @Test
    void chargeReceiptDataStillAvailableForReprint() {
        var c = makeCharge();
        var receipt = printController.chargeReceipt(c.chargeId()).getData();
        assertEquals(c.chargeNo(), receipt.get("charge_no"));
        assertNotNull(receipt.get("items"), "补打需要明细行");
    }
}
