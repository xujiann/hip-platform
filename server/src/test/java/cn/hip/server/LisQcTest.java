package cn.hip.server;

import cn.hip.medtech.web.LisQcController;
import cn.hip.medtech.web.LisQcController.AstLine;
import cn.hip.medtech.web.LisQcController.MicroReq;
import cn.hip.medtech.web.LisQcController.QcReq;
import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.DoctorStationService.OrderLine;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** v36 LIS 质控轮：微生物药敏 / 室内质控 IQC(Westgard) / TAT。 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class LisQcTest {

    @Autowired LisQcController lisQc;
    @Autowired MedTechController medTech;
    @Autowired PatientService patientService;
    @Autowired RegistrationService registrationService;
    @Autowired DoctorStationService doctorStationService;
    @Autowired ChargeService chargeService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.server.support.TestSeeds seeds;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    /** 建一个已核收(RECEIVED)标本，返回 barcode */
    private String receivedSample() {
        Patient p = new Patient();
        p.setName("质控" + System.nanoTime());
        p.setSex("M");
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
        Long orderId = doctorStationService.createOrders(rid,
                List.of(new OrderLine("LAB", labItem, 1, null, null, null, null)), null).get(0).getId();
        chargeService.settle(rid, "CASH", null);
        em.flush();
        String bc = (String) medTech.collect(orderId, null).getData().get("barcode");
        medTech.receive(bc);
        return bc;
    }

    @Test
    void microRecordAndRetrieve() {
        String bc = receivedSample();
        Long orderId = jdbc.queryForObject("select order_id from lis_sample where barcode=?", Long.class, bc);
        // 错误码
        assertEquals(7106, lisQc.micro("NOPE", new MicroReq("痰", "大肠埃希菌", "NEG", "+++", List.of()), admin).getCode());
        assertEquals(7107, lisQc.micro(bc, new MicroReq("痰", "  ", "NEG", null, List.of()), admin).getCode());
        assertEquals(7108, lisQc.micro(bc, new MicroReq("痰", "大肠埃希菌", "NEG", null,
                List.of(new AstLine("头孢", "KB", "20", "X"))), admin).getCode());
        // 正常录入
        assertEquals(0, lisQc.micro(bc, new MicroReq("痰", "大肠埃希菌", "NEG", "+++",
                List.of(new AstLine("头孢曲松", "MIC", "1", "S"), new AstLine("氨苄西林", "MIC", "32", "R"))), admin).getCode());
        var results = lisQc.microResults(orderId).getData();
        assertEquals(1, results.size());
        assertEquals("大肠埃希菌", results.get(0).get("organism"));
        @SuppressWarnings("unchecked")
        var ast = (List<Map<String, Object>>) results.get(0).get("ast");
        assertEquals(2, ast.size());
    }

    @Test
    void iqcWestgardRules() {
        assertEquals(7109, lisQc.qc(new QcReq("", "L1", "L001", new BigDecimal("5"), new BigDecimal("0.2"), new BigDecimal("5")), admin).getCode());
        assertEquals(7110, lisQc.qc(new QcReq("GLU", "L1", "L001", new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("5")), admin).getCode());

        String item = "GLU" + System.nanoTime() % 100000;
        // 在控：z=1
        var r1 = lisQc.qc(new QcReq(item, "L1", "L001", new BigDecimal("5.0"), new BigDecimal("0.2"), new BigDecimal("5.2")), admin).getData();
        assertEquals(Boolean.TRUE, r1.get("inControl"));
        // 1-3s 失控：z=4
        var r2 = lisQc.qc(new QcReq(item, "L2", "L001", new BigDecimal("5.0"), new BigDecimal("0.2"), new BigDecimal("5.8")), admin).getData();
        assertEquals(Boolean.FALSE, r2.get("inControl"));
        assertEquals("1-3s", r2.get("rule"));
        // 2-2s：连续两点同侧 >2SD（z=2.5 两次）
        lisQc.qc(new QcReq(item, "L3", "L001", new BigDecimal("5.0"), new BigDecimal("0.2"), new BigDecimal("5.5")), admin);
        var r4 = lisQc.qc(new QcReq(item, "L3", "L001", new BigDecimal("5.0"), new BigDecimal("0.2"), new BigDecimal("5.5")), admin).getData();
        assertEquals(Boolean.FALSE, r4.get("inControl"));
        assertEquals("2-2s", r4.get("rule"));
    }

    @Test
    void tatAggregateReturns() {
        var tat = lisQc.tat(null, null).getData();
        assertTrue(tat.containsKey("total") && tat.containsKey("limitMinutes"));
    }
}
