package cn.hip.bureau.common;

import cn.hip.platform.core.common.R;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "cn.hip.bureau")
public class BureauExceptionAdvice {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        return R.fail(e.code, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegal(IllegalArgumentException e) {
        return R.fail(2000, e.getMessage());
    }
}
