package cn.hip.platform.core.common;

/**
 * 业务异常统一基类（1.1.9）：此前三套同构异常家族（outpatient.BizException /
 * inpatient.InpException / cdr.LegacyException）并存、9 个模块直接 R.fail，
 * 新模块作者不知道抄哪个；GlobalExceptionHandler 每认识一个模块就多一条具体类 import。
 * 统一后：新模块抛本类（或建子类保留域内类型语义），server 层按基类一条处理。
 *
 * <p>错误码分配见 docs/错误码分段.md——新增码先登记再写代码。
 */
public class HipBizException extends RuntimeException {

    public final int code;

    public HipBizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
