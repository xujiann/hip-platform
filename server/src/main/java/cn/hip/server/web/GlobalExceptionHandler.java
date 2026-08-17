package cn.hip.server.web;

import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.NoSuchElementException;

/**
 * 全局异常处理。
 *
 * <p>此前全仓没有 ControllerAdvice：BizException/InpException 只在各自 Controller 里逐个
 * try/catch，漏掉的路径（以及 DataIntegrityViolation、NumberFormat、非法日期参数等）
 * 一律以 HTTP 500 + Spring 默认错误体返回，前端拿不到业务码只能显示"网络请求失败"。
 * 这是历次审阅中多条问题严重度的共同放大器。
 *
 * <p>注意：安全异常（401/403）由 SecurityConfig 的 entryPoint/accessDeniedHandler 处理，
 * 不走这里——它们发生在过滤器链，到不了 DispatcherServlet。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 门诊/挂号/收费/发药域的业务异常 */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.code, e.getMessage());
    }

    /** 住院域的业务异常 */
    @ExceptionHandler(InpException.class)
    public R<Void> handleInp(InpException e) {
        return R.fail(e.code, e.getMessage());
    }

    /** 唯一约束冲突：业务上多为"重复提交/已存在"，给可理解的提示而非 500 */
    @ExceptionHandler(DuplicateKeyException.class)
    public R<Void> handleDuplicate(DuplicateKeyException e) {
        log.warn("唯一约束冲突: {}", rootMessage(e));
        return R.fail(4090, "该记录已存在或正被并发处理，请刷新后重试");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public R<Void> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("数据完整性约束: {}", rootMessage(e));
        return R.fail(4091, "数据不符合约束要求，请检查输入（如金额须为正、编码长度超限）");
    }

    /** 请求体/参数格式问题：非法日期、类型不匹配、缺参 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MethodArgumentNotValidException.class,
            IllegalArgumentException.class, NumberFormatException.class})
    public R<Void> handleBadRequest(Exception e) {
        log.warn("请求参数错误: {}", e.getMessage());
        return R.fail(4000, "请求参数不正确：" + shortMessage(e));
    }

    /** findById().orElseThrow() 之类的空结果 */
    @ExceptionHandler(NoSuchElementException.class)
    public R<Void> handleNotFound(NoSuchElementException e) {
        return R.fail(4040, "记录不存在或已被删除");
    }

    /**
     * 安全异常必须**原样抛出**，交给 ExceptionTranslationFilter → accessDeniedHandler，
     * 否则会被下面的兜底吃成 500，401/403 的语义又丢了（这正是 1.1.0 修过的坑）。
     */
    @ExceptionHandler({org.springframework.security.access.AccessDeniedException.class,
            org.springframework.security.core.AuthenticationException.class})
    public void rethrowSecurity(RuntimeException e) {
        throw e;
    }

    /** 兜底：仍返回 500，但带业务信封，且服务端留完整堆栈 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return R.fail(5000, "系统内部错误，请联系管理员（可提供时间点便于排查）");
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e);
        return cause.getMessage() == null ? cause.toString() : cause.getMessage().split("\n")[0];
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.split("\n")[0];
    }
}
