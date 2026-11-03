package cn.hip.platform.integration.mllp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MllpAllowListTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t ", ", ,"})
    void preservesEmptyAllowList(String spec) {
        assertTrue(MllpServer.parseAllowList(spec).isEmpty());
    }

    @Test
    void convertsLiteralAddressesAndCidrsToUnsignedNetworkAndMask() {
        var rules = MllpServer.parseAllowList(" 192.168.1.10, ,10.1.2.3/8, 255.255.255.255/32, 198.51.100.9/0, 010.002.000.001 ");
        assertEquals(5, rules.size());
        assertArrayEquals(new long[]{0xC0A8010AL, 0xFFFFFFFFL}, rules.get(0));
        assertArrayEquals(new long[]{0x0A000000L, 0xFF000000L}, rules.get(1));
        assertArrayEquals(new long[]{0xFFFFFFFFL, 0xFFFFFFFFL}, rules.get(2));
        assertArrayEquals(new long[]{0L, 0L}, rules.get(3));
        assertArrayEquals(new long[]{0x0A020001L, 0xFFFFFFFFL}, rules.get(4));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost", "localhost/0", "192.0.2.1, localhost/24",
            "::1", "2001:db8::1/32", "::ffff:192.0.2.1",
            "127.1", "2130706433", "0x7f.0.0.1", "192.0.2.1.5",
            "256.0.0.1", "192..2.1", "192.0.2.1.", "+192.0.2.1", "１９２.0.2.1",
            "/0", "192.0.2.1/", "192.0.2.1/24/", "192.0.2.1/24/8",
            "192.0.2.1/-1", "192.0.2.1/33", "192.0.2.1/+24", "192.0.2.1/２４",
            "192.0.2.1/ 24"
    })
    void rejectsAnythingOutsideLiteralIpv4AndOptionalPrefix(String spec) {
        var error = assertThrows(IllegalArgumentException.class, () -> MllpServer.parseAllowList(spec));
        assertTrue(error.getMessage().startsWith("hip.integration.mllp-allow 配置非法："));
    }
}
