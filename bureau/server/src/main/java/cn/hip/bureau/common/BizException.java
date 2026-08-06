package cn.hip.bureau.common;

/** 案件查办业务异常（错误码 2xxx），由 BureauExceptionAdvice 统一转 R.fail */
public class BizException extends RuntimeException {
    public final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
