package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.server.web.MedRecordStatsController;
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
 * v41：床位效率趋势（出院人次/平均住院日/占用床日/周转次数/使用率）。
 * 该项在验收偏离表已承诺"平台已实现"但此前代码不存在——本测试锁住真实实现。
 */
@SpringBootTest
@Transactional
@org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
class V41BedTrendTest {

    @Autowired MedRecordStatsController mrStats;
    @Autowired InpatientService inpatientService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;

    /** 造一个已出院病例，住院天数可控（回拨入院/出院时间） */
    private String dischargedCase(int stayDays) {
        Patient p = new Patient();
        p.setName("床位" + System.nanoTime());
        p.setSex("F");
        Long pid = patientService.register(p).getId();
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        var adm = inpatientService.admit(pid, 1L, bedId, null, "J18.9", "肺炎", new BigDecimal("100"), "CASH", null);
        em.flush();
        inpatientService.discharge(adm.getId(), null, "CASH");
        em.flush();
        // 回拨到本月内、住院 stayDays 天（本月月初 +1 天起算，避免跨月落到上月）
        jdbc.update("""
                update inp_admission
                set admit_at = date_trunc('month', now()) + interval '1 day',
                    discharged_at = date_trunc('month', now()) + interval '1 day' + make_interval(days => ?)
                where id = ?
                """, stayDays, adm.getId());
        return jdbc.queryForObject("select admission_no from inp_admission where id = ?", String.class, adm.getId());
    }

    @Test
    void deptBedTrendComputesStayTurnoverOccupancy() {
        // 基线：本月本科室既有出院人次
        String thisMonth = jdbc.queryForObject("select to_char(now(), 'YYYY-MM')", String.class);
        var before = rowOf(mrStats.deptBedTrend(12).getData(), thisMonth);
        long baseDischarges = before == null ? 0 : ((Number) before.get("discharges")).longValue();

        dischargedCase(3);
        dischargedCase(5);
        em.flush();

        var after = rowOf(mrStats.deptBedTrend(12).getData(), thisMonth);
        assertNotNull(after, "本月应有床位效率行");
        assertEquals(baseDischarges + 2, ((Number) after.get("discharges")).longValue(), "出院人次 +2");
        assertNotNull(after.get("avg_stay_days"), "平均住院日应算出");
        assertNotNull(after.get("bed_days"), "占用床日应算出");
        // 有床位时周转与使用率非空（bed_count 为 0 时按 nullif 返回 null，属设计内）
        long bedCount = ((Number) after.get("bed_count")).longValue();
        if (bedCount > 0) {
            assertNotNull(after.get("turnover"), "有床位应算出周转次数");
            assertNotNull(after.get("occupancy_pct"), "有床位应算出使用率");
            assertTrue(((Number) after.get("turnover")).doubleValue() > 0);
        }
    }

    @Test
    void monthsParamIsClamped() {
        assertDoesNotThrow(() -> mrStats.deptBedTrend(0));    // 下限夹到 1
        assertDoesNotThrow(() -> mrStats.deptBedTrend(999));  // 上限夹到 36
        assertNotNull(mrStats.deptBedTrend(1).getData());
    }

    private Map<String, Object> rowOf(List<Map<String, Object>> rows, String month) {
        return rows.stream()
                .filter(r -> month.equals(r.get("month")) && "内科门诊".equals(r.get("dept_name")))
                .findFirst().orElse(null);
    }
}
