package cn.hip.platform.integration.hl7;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Hl7V2MessageTest {

    private static final String ORU = """
            MSH|^~\\&|LIS|EMSH|HIP|EMSH|20260804120000||ORU^R01|MSG0001|P|2.5\r\
            PID|1||P00000002||张三\r\
            OBR|1|SQ20260804-123||C0001^血常规\r\
            OBX|1|NM|WBC^白细胞计数|1|15.2|10^9/L|3.5-9.5|H\r\
            OBX|2|NM|HGB^血红蛋白|2|45|g/L|130-175|LL\r""";

    @Test
    void parsesMessageTypeFromMsh9() {
        assertEquals("ORU_R01", Hl7V2Message.parse(ORU).messageType());
    }

    @Test
    void mshFieldIndexesFollowHl7Numbering() {
        var msh = Hl7V2Message.parse(ORU).first("MSH");
        assertEquals("|", msh.field(1));
        assertEquals("^~\\&", msh.field(2));
        assertEquals("LIS", msh.field(3));
        assertEquals("MSG0001", msh.field(10));
    }

    @Test
    void obrCarriesOrderGroupNo() {
        assertEquals("SQ20260804-123", Hl7V2Message.parse(ORU).first("OBR").field(2));
    }

    @Test
    void allObxSegmentsAreReturnedInOrder() {
        var obx = Hl7V2Message.parse(ORU).all("OBX");
        assertEquals(2, obx.size());
        assertEquals("WBC^白细胞计数", obx.get(0).field(3));
        assertEquals("LL", obx.get(1).field(8));
    }

    @Test
    void outOfRangeFieldIsEmptyNotException() {
        assertEquals("", Hl7V2Message.parse(ORU).first("PID").field(99));
    }

    @Test
    void handlesLfAndCrLfLineEndings() {
        String lf = ORU.replace("\r", "\n");
        assertEquals("ORU_R01", Hl7V2Message.parse(lf).messageType());
        assertEquals(2, Hl7V2Message.parse(lf).all("OBX").size());
    }
}
