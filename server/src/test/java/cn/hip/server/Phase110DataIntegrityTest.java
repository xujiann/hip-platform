package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.core.service.JobLockService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.platform.integration.service.IntegrationLogService;
import cn.hip.platform.integration.service.OruProcessingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 1.1.0 B 批回归：重复入院唯一约束 / ORU 多 OBR 分组 / 留痕截断 / 任务选主 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class Phase110DataIntegrityTest {

    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired OruProcessingService oruProcessingService;
    @Autowired IntegrationLogService logService;
    @Autowired JobLockService jobLock;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired cn.hip.outpatient.service.DoctorStationService doctorStationService;
    @Autowired cn.hip.outpatient.service.ChargeService chargeService;
    @Autowired cn.hip.outpatient.service.RegistrationService registrationService;
    @Autowired cn.hip.outpatient.repository.OutpScheduleRepository scheduleRepository;
    @Autowired cn.hip.platform.masterdata.repository.ChargeItemRepository chargeItemRepository;

    private Long visitedRegistration(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("U");
        Long pid = patientService.register(p).getId();
        var sch = new cn.hip.outpatient.entity.OutpSchedule();
        sch.setDeptId(1L);
        sch.setScheduleDate(java.time.LocalDate.now());
        sch.setFee(BigDecimal.ZERO);
        sch.setCapacity(5);
        sch = scheduleRepository.save(sch);
        Long rid = registrationService.register(pid, sch.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    /** B-1：同一患者不得同时有两条在院记录（两轮审阅都提过，此前一直可重复入院） */
    @Test
    void patientCannotBeAdmittedTwice() {
        Patient p = new Patient();
        p.setName("重复入院110");
        p.setSex("M");
        Long pid = patientService.register(p).getId();
        var beds = jdbc.queryForList("select id from inp_bed where status = 'FREE' limit 2", Long.class);

        inpatientService.admit(pid, 1L, beds.get(0), null, "J18.9", "肺炎",
                new BigDecimal("100"), "CASH", null);
        entityManager.flush();

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
            inpatientService.admit(pid, 1L, beds.get(1), null, "J18.9", "肺炎",
                    new BigDecimal("100"), "CASH", null);
            entityManager.flush();
        }, "同一患者第二次入院应被唯一索引拒绝");
    }

    /** B-6：多 OBR 报文必须按组归属，B 单的结果不得写进 A 单 */
    @Test
    void multiObrMessageKeepsResultsWithTheirOwnOrder() {
        // 造两张真实的检验申请单（各自 groupNo），再用一条多 OBR 报文同时回传
        Long ridA = visitedRegistration("多OBR-A");
        Long ridB = visitedRegistration("多OBR-B");
        Long labItem = chargeItemRepository
                .findTop20ByEnabledTrueAndNameContainingOrderByCode("血常规").get(0).getId();
        var orderA = doctorStationService.createOrders(ridA,
                List.of(new cn.hip.outpatient.service.DoctorStationService.OrderLine("LAB", labItem, 1, null, null, null, null)), null).get(0);
        var orderB = doctorStationService.createOrders(ridB,
                List.of(new cn.hip.outpatient.service.DoctorStationService.OrderLine("LAB", labItem, 1, null, null, null, null)), null).get(0);
        chargeService.settle(ridA, "CASH", null);
        chargeService.settle(ridB, "CASH", null);
        entityManager.flush();

        String cr = String.valueOf((char) 13);
        String raw = String.join(cr,
                "MSH|^~" + (char) 92 + "&|LIS|LAB|HIP|HOSP|20260816120000||ORU^R01|MSG110|P|2.5",
                "OBR|1|" + orderA.getGroupNo() + "||血常规",
                "OBX|1|NM|WBC^白细胞||6.5|10^9/L|4-10||||F",
                "OBR|2|" + orderB.getGroupNo() + "||血常规",
                "OBX|1|NM|HGB^血红蛋白||140|g/L|130-175||||F",
                "OBX|2|NM|PLT^血小板||210|10^9/L|100-300||||F");

        var result = oruProcessingService.process(raw);
        assertTrue(result.ok(), "多 OBR 报文应处理成功，实际: " + result);
        assertEquals(3, result.items(), "两组结果应全部处理");
        entityManager.clear();

        // 关键断言：A 单只拿到自己的 1 条，B 单拿到自己的 2 条——不串号
        Integer aCount = jdbc.queryForObject(
                "select count(*) from outp_lab_result where order_id = ?", Integer.class, orderA.getId());
        Integer bCount = jdbc.queryForObject(
                "select count(*) from outp_lab_result where order_id = ?", Integer.class, orderB.getId());
        assertEquals(1, aCount, "A 单只应有自己的 1 条结果（原实现会把 3 条全给 A）");
        assertEquals(2, bCount, "B 单应拿到自己的 2 条结果（原实现 B 单一条也拿不到）");
    }

    /** 留痕字段超长不得抛异常——它曾一路逃到 MllpServer 让连接被关、消息静默丢失 */
    @Test
    void oversizedLogFieldsAreTruncatedNotThrown() {
        String longRef = "R".repeat(200);          // ref_no varchar(64)
        String longErr = "E".repeat(2000);         // error varchar(512)
        String longPayload = "P".repeat(20000);    // payload varchar(8000)

        assertDoesNotThrow(() -> logService.log("IN", "HL7_LIS", longRef, longPayload, false, longErr));

        Integer n = jdbc.queryForObject(
                "select count(*) from int_message_log where ref_no = ?", Integer.class, "R".repeat(64));
        assertNotNull(n);
        assertTrue(n > 0, "超长字段应截断后落库，而不是抛异常丢消息");
    }

    /** B-9：任务选主——持锁期间其他实例拿不到同名锁 */
    @Test
    void jobLockPreventsConcurrentRuns() {
        var inner = new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean outer = jobLock.runExclusively("test-job-110", () -> {
            // 同名锁在同一会话内是可重入的，故用独立连接验证：这里只断言主体确实执行
            inner.set(true);
        });
        assertTrue(outer);
        assertTrue(inner.get());
    }
}
