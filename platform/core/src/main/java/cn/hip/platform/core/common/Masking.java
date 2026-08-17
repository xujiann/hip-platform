package cn.hip.platform.core.common;

/**
 * 隐私脱敏统一口径（1.1.9）：此前同一规则在 PatientController / CdrSyncService /
 * ReportShareController 三处手写——脱敏属于等保合规口径，将来调规则必漏改一处；
 * 且"含掩码值拒绝回写"的防线只有 EMPI 一处知道要配。规则调整只改本类。
 */
public final class Masking {

    private Masking() {
    }

    /** 手机号：前3后4 */
    public static String phone(String s) {
        return mask(s, 3, 4);
    }

    /** 证件号：前4后3 */
    public static String idNo(String s) {
        return mask(s, 4, 3);
    }

    /** 姓名：首字 + ** */
    public static String name(String s) {
        return s == null || s.isEmpty() ? "" : s.charAt(0) + "**";
    }

    /** 值里是否含掩码字符（脱敏展示值被原样提交回来时必须拒绝入库） */
    public static boolean looksMasked(String s) {
        return s != null && s.contains("*");
    }

    public static String mask(String s, int head, int tail) {
        if (s == null || s.length() <= head + tail) {
            return s;
        }
        return s.substring(0, head) + "*".repeat(s.length() - head - tail) + s.substring(s.length() - tail);
    }
}
