package cn.hip.server;

import cn.hip.cdr.service.CdrLegacyService;
import cn.hip.cdr.service.CdrLegacyService.LegacyDoc;
import cn.hip.cdr.service.CdrLegacyService.LegacyException;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** 1.1.5 存量文书导入回归：幂等覆盖、患者定位失败、类别/日期校验 */
@SpringBootTest
@Transactional
class Phase115LegacyImportTest {

    @Autowired CdrLegacyService legacyService;
    @Autowired PatientService patientService;
    @Autowired JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager entityManager;

    private String newPatientNo(String name) {
        Patient p = new Patient();
        p.setName(name);
        p.setSex("M");
        String no = patientService.register(p).getPatientNo();
        entityManager.flush();   // 服务内用 JdbcTemplate 定位患者，JPA 侧须先刷库
        return no;
    }

    /** 同一老单号导两次 = 一行覆盖更新（断点续跑/修正重导不产生重复文书） */
    @Test
    void reimportIsIdempotentUpdate() {
        String patientNo = newPatientNo("文书迁移115");
        var first = legacyService.importDocument(new LegacyDoc(null, patientNo, "OUTP",
                "MZ115-001", "门诊病历", "2024-05-12", "主诉：咳嗽 3 天。"));
        assertEquals(false, first.get("updated"));

        var second = legacyService.importDocument(new LegacyDoc(null, patientNo, "OUTP",
                "MZ115-001", "门诊病历（修正）", "2024-05-12", "主诉：咳嗽 3 天，修正版。"));
        assertEquals(true, second.get("updated"));
        assertEquals(first.get("refId"), second.get("refId"), "同老单号必须命中同一 ref_id");
        entityManager.flush();

        Integer rows = jdbc.queryForObject("""
                select count(*) from cdr_document where doc_type = 'LEGACY_OUTP' and ref_id = ?
                """, Integer.class, first.get("refId"));
        assertEquals(1, rows, "重复导入不得产生第二行");
        String content = jdbc.queryForObject(
                "select content from cdr_document where doc_type = 'LEGACY_OUTP' and ref_id = ?",
                String.class, first.get("refId"));
        assertTrue(content.contains("修正版"), "重导应覆盖为最新正文");
    }

    /** 失败路径：患者未建档、类别非法、日期格式错——都必须拦住并给出可行动的提示 */
    @Test
    void rejectsBadInput() {
        String patientNo = newPatientNo("文书校验115");
        var noPatient = assertThrows(LegacyException.class, () ->
                legacyService.importDocument(new LegacyDoc("999999999999999999", null, "OUTP",
                        "MZ115-X1", "t", "2024-01-01", "c")));
        assertEquals(4686, noPatient.code);

        var badCategory = assertThrows(LegacyException.class, () ->
                legacyService.importDocument(new LegacyDoc(null, patientNo, "EMR",
                        "MZ115-X2", "t", "2024-01-01", "c")));
        assertEquals(4682, badCategory.code);

        var badDate = assertThrows(LegacyException.class, () ->
                legacyService.importDocument(new LegacyDoc(null, patientNo, "OUTP",
                        "MZ115-X3", "t", "2024/01/01", "c")));
        assertEquals(4687, badDate.code);

        var noKey = assertThrows(LegacyException.class, () ->
                legacyService.importDocument(new LegacyDoc(null, patientNo, "OUTP",
                        " ", "t", "2024-01-01", "c")));
        assertEquals(4681, noKey.code);
    }

    /** 导入的文书出现在患者 360 文档列表与全文检索里（否则迁移了也看不见） */
    @Test
    void importedDocIsVisibleInCdr() {
        String patientNo = newPatientNo("文书可见115");
        Long pid = jdbc.queryForObject("select id from empi_patient where patient_no = ?",
                Long.class, patientNo);
        legacyService.importDocument(new LegacyDoc(null, patientNo, "INP",
                "ZY115-001", "出院小结-骨科", "2023-11-15", "右桡骨远端骨折术后恢复良好。"));
        entityManager.flush();

        Integer visible = jdbc.queryForObject("""
                select count(*) from cdr_document where patient_id = ? and doc_type = 'LEGACY_INP'
                """, Integer.class, pid);
        assertEquals(1, visible);
        Integer hit = jdbc.queryForObject(
                "select count(*) from cdr_document where content ilike '%桡骨远端%' and patient_id = ?",
                Integer.class, pid);
        assertEquals(1, hit, "全文检索必须能命中迁移文书");
    }
}
