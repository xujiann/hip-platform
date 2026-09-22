package cn.hip.server;

import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v70 包 B：门诊接诊队列检索条件扩面（965★ 跨日期检索 / 2033★「我的患者」）。
 *
 * <p><b>本类的第一职责是「旧行为锁定」</b>：队列端点此前只有一个必填 {@code date}，
 * 前端至今这么调。本版要给它加日期区间与医生过滤，而改既有查询路径唯一的安全网，
 * 就是先把不传新参数时的行为钉死（测试方法论④的同类要求）。
 *
 * <p>照抄住院侧多维检索立下的那条纪律：<b>零条件 = 旧行为，不进检索分支、连查询都不换</b>。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V70WorklistSearchTest {

    @Autowired DoctorStationController doctorStationController;
    @Autowired RegistrationService registrationService;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private Long userId(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    private Authentication authOf(String username) {
        userId(username);
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    /** 建患者 + 指定日期的排班 + 挂号，返回 registrationId；doctorId 可空。 */
    private Long registrationOn(LocalDate day, Long doctorId) {
        Patient p = new Patient();
        p.setName("V70队列" + System.nanoTime());
        p.setSex("U");
        Long pid = patientService.register(p).getId();

        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(day);
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        if (doctorId != null) s.setDoctorId(doctorId);
        s = scheduleRepository.save(s);

        Long rid = registrationService.register(pid, s.getId()).getId();
        entityManager.flush();
        return rid;
    }

    private static List<Long> idsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(m -> ((Number) m.get("registrationId")).longValue()).toList();
    }

    // ==================== ① 旧行为锁定（改代码前先钉死） ====================

    /**
     * 只传 date 时：当日全部非作废挂号、按 id 倒序、返回体键集不变。
     *
     * <p>这条是本版的护栏——加了新参数之后它必须**逐字仍然成立**。
     */
    @Test
    void legacyDateOnlyQueryReturnsTodayRegistrationsIdDesc() {
        LocalDate today = BusinessDates.today();
        Long r1 = registrationOn(today, null);
        Long r2 = registrationOn(today, null);

        var rows = doctorStationController.worklist(today, null, null, null, null, null).getData();
        var ids = idsOf(rows);
        assertTrue(ids.contains(r1) && ids.contains(r2), "当日两条挂号都应在队列里：" + ids);
        assertTrue(ids.indexOf(r2) < ids.indexOf(r1), "按 id 倒序：后挂的排前面：" + ids);

        var one = rows.stream().filter(m -> r2.equals(((Number) m.get("registrationId")).longValue()))
                .findFirst().orElseThrow();
        // 键集是前端与 E2E 都在消费的契约，本版一个都不许少
        for (String k : List.of("registrationId", "regNo", "status", "deptId",
                "emrWritten", "emrSigned", "patientId", "patientNo", "patientName", "sex")) {
            assertTrue(one.containsKey(k), "队列返回体缺键 " + k + "：" + one.keySet());
        }
    }

    /** 作废挂号不进队列——既有过滤，改检索路径后不得丢。 */
    @Test
    void cancelledRegistrationStaysOutOfTheQueue() {
        LocalDate today = BusinessDates.today();
        Long rid = registrationOn(today, null);
        jdbc.update("update outp_registration set status = 'CANCELLED' where id = ?", rid);
        entityManager.flush();
        entityManager.clear();

        assertFalse(idsOf(doctorStationController.worklist(today, null, null, null, null, null).getData()).contains(rid),
                "作废挂号不得出现在接诊队列里");
    }

    /** 别的日期的挂号不串进来——日期条件本身的正确性。 */
    @Test
    void otherDaysDoNotLeakIntoTodayQueue() {
        LocalDate today = BusinessDates.today();
        Long yesterdayReg = registrationOn(today.minusDays(1), null);
        Long todayReg = registrationOn(today, null);

        var ids = idsOf(doctorStationController.worklist(today, null, null, null, null, null).getData());
        assertTrue(ids.contains(todayReg));
        assertFalse(ids.contains(yesterdayReg), "昨天的挂号不得出现在今天的队列里：" + ids);
    }

    // ==================== ② 新能力：日期区间（965★）与「我的患者」（2033★） ====================

    /** 跨日期检索：区间覆盖三天时三条都回；只传 date 时仍只回当天。 */
    @Test
    void dateRangeReturnsRegistrationsAcrossDays() {
        LocalDate today = BusinessDates.today();
        Long d2 = registrationOn(today.minusDays(2), null);
        Long d1 = registrationOn(today.minusDays(1), null);
        Long d0 = registrationOn(today, null);

        var ranged = idsOf(doctorStationController
                .worklist(null, today.minusDays(2), today, null, null, null).getData());
        assertTrue(ranged.containsAll(List.of(d2, d1, d0)), "区间应覆盖三天：" + ranged);

        var singleDay = idsOf(doctorStationController.worklist(today, null, null, null, null, null).getData());
        assertTrue(singleDay.contains(d0));
        assertFalse(singleDay.contains(d1), "只传 date 时不得把区间里的别天带出来：" + singleDay);
    }

    /** 「我的患者」只回当前登录医生接诊的那些。 */
    @Test
    void mineFiltersToTheLoggedInDoctorsPatients() {
        LocalDate today = BusinessDates.today();
        Long meId = userId("v70doc_me");
        Long otherId = userId("v70doc_other");
        Long mineReg = registrationOn(today, meId);
        Long othersReg = registrationOn(today, otherId);

        var ids = idsOf(doctorStationController
                .worklist(today, null, null, null, true, authOf("v70doc_me")).getData());
        assertTrue(ids.contains(mineReg), "我的患者应在：" + ids);
        assertFalse(ids.contains(othersReg), "别人的患者不得混进「我的」：" + ids);
    }

    /** 显式 doctorId 与 mine 等价（mine 只是「doctorId = 我」的糖）。 */
    @Test
    void explicitDoctorIdFiltersTheSameWayAsMine() {
        LocalDate today = BusinessDates.today();
        Long docId = userId("v70doc_explicit");
        Long his = registrationOn(today, docId);
        Long others = registrationOn(today, userId("v70doc_someone"));

        var ids = idsOf(doctorStationController
                .worklist(today, null, null, docId, null, null).getData());
        assertTrue(ids.contains(his));
        assertFalse(ids.contains(others));
    }

    /** 区间与旧路径口径一致：from=to=当天 应与只传 date 得到同一批。 */
    @Test
    void singleDayRangeMatchesLegacyDateOnlyResult() {
        LocalDate today = BusinessDates.today();
        registrationOn(today, null);
        registrationOn(today, null);

        var legacy = idsOf(doctorStationController.worklist(today, null, null, null, null, null).getData());
        var ranged = idsOf(doctorStationController.worklist(null, today, today, null, null, null).getData());
        assertEquals(legacy, ranged, "from=to=当天必须与只传 date 逐条同序一致");
    }

    // ==================== ③ 检索条件非法一律 4080 ====================

    @Test
    void invalidSearchConditionsAllReturn4080() {
        LocalDate today = BusinessDates.today();

        assertEquals(4080, doctorStationController
                .worklist(null, today, today.minusDays(3), null, null, null).getCode(), "起止倒置");
        assertEquals(4080, doctorStationController
                .worklist(null, today.minusDays(400), today, null, null, null).getCode(), "跨度超上限");
        assertEquals(4080, doctorStationController
                .worklist(today, null, null, 0L, null, null).getCode(), "医生条件非正");
        assertEquals(4080, doctorStationController
                .worklist(today, null, null, null, true, null).getCode(), "mine 但无登录上下文");
        assertEquals(4080, doctorStationController
                .worklist(today, null, null, userId("v70doc_conflict") + 9999, true, authOf("v70doc_conflict"))
                .getCode(), "mine 与 doctorId 冲突");
        assertEquals(4080, doctorStationController
                .worklist(null, null, null, null, null, null).getCode(), "既无 date 也无区间");
    }
}
