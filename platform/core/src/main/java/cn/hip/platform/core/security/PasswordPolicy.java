package cn.hip.platform.core.security;

/**
 * 等保口令策略：至少 8 位且同时含字母与数字。
 * v27-A 自助改密与管理员设密共用同一把尺子——策略若散落两处，
 * 迟早出现"管理员设得进、本人改不了"（或反之）的口径分裂。
 */
public final class PasswordPolicy {

    private PasswordPolicy() {
    }

    /** 合规返回 null，否则返回给用户看的原因（文案被 1102 测试锁定，勿随意改动） */
    public static String error(String pwd) {
        if (pwd == null || pwd.length() < 8) return "密码不能少于 8 位";
        if (!pwd.matches(".*[A-Za-z].*") || !pwd.matches(".*\\d.*")) return "密码须同时包含字母和数字";
        return null;
    }
}
