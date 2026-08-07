package cn.hip.server;

import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class PatientServiceTest {

    @Autowired PatientService patientService;

    private Patient withIdCard(String name, String idNo) {
        Patient p = new Patient();
        p.setName(name);
        p.setIdType("ID_CARD");
        p.setIdNo(idNo);
        return p;
    }

    @Test
    void idCardDerivesBirthDateAndSex() {
        // 顺序码末位 1 为奇数 → 男（校验位 5 合法，1.0.3 起建档强校验）
        var p = patientService.register(withIdCard("单测甲", "510181198812150515"));
        assertEquals(LocalDate.of(1988, 12, 15), p.getBirthDate());
        assertEquals("M", p.getSex());
        assertTrue(p.getPatientNo().startsWith("P"));
    }

    @Test
    void sameIdCardIsIdempotent() {
        var first = patientService.register(withIdCard("单测乙", "510181199505054528"));
        var second = patientService.register(withIdCard("单测乙", "510181199505054528"));
        assertEquals(first.getId(), second.getId());
        assertEquals("F", first.getSex()); // 第 17 位 2 为偶数 → 女
    }

    @Test
    void ageIsComputedFromBirthDate() {
        assertNull(PatientService.ageOf(null));
        assertEquals(0, PatientService.ageOf(LocalDate.now()));
    }
}
