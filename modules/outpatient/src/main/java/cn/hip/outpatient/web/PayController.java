package cn.hip.outpatient.web;

import cn.hip.outpatient.service.PayService;
import cn.hip.outpatient.service.RegistrationService.BizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十九期：扫码支付台（收费员出码 / 模拟扫码回调 / 流水） */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pay")
public class PayController {

    private final PayService payService;
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    public record CreateReq(Long registrationId, String channel) {}

    @PostMapping("/orders")
    public R<Object> create(@RequestBody CreateReq req) {
        try {
            return R.ok(payService.createPayOrder(req.registrationId(), req.channel()));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** Mock 扫码成功回调（真实环境为支付平台异步通知） */
    @PostMapping("/orders/{payNo}/mock-scan")
    public R<Object> mockScan(@PathVariable String payNo, Authentication auth) {
        try {
            return R.ok(payService.confirm(payNo, currentUserService.idOf(auth)));
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PutMapping("/orders/{payNo}/cancel")
    public R<Void> cancel(@PathVariable String payNo) {
        try {
            payService.cancel(payNo);
            return R.ok();
        } catch (BizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/orders")
    public R<List<Map<String, Object>>> orders() {
        return R.ok(jdbc.queryForList("""
                select o.*, p.name as patient_name
                from pay_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                order by o.id desc limit 100
                """));
    }
}
