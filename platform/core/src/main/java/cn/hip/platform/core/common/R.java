package cn.hip.platform.core.common;

import lombok.Getter;

/**
 * 统一接口返回结构：{code, message, data}
 * code=0 表示成功，非 0 为业务错误码。
 */
@Getter
public class R<T> {

    private final int code;
    private final String message;
    private final T data;

    private R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> ok(T data) {
        return new R<>(0, "success", data);
    }

    public static <T> R<T> ok() {
        return new R<>(0, "success", null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null);
    }

    /** 失败但需随码带回结构化明细（如 v76 诊断字典 CSV 导入 5901 的行级错误汇总） */
    public static <T> R<T> fail(int code, String message, T data) {
        return new R<>(code, message, data);
    }
}
