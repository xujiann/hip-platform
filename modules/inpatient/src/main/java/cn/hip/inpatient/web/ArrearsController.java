package cn.hip.inpatient.web;

import cn.hip.inpatient.service.ArrearsService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * v41 住院欠费挂账台账（追缴闭环）。
 *
 * <p>类级限 ADMIN + CASHIER：追缴是收费职能。刻意不给 DOCTOR_OUTP/NURSE——
 * 临床角色不该看到患者欠费台账，更不该让欠费影响医疗决策（欠费出院是设计内放行，见
 * InpatientController#account 注释）。核销另加方法级 ADMIN 限制。
 *
 * <p>本控制器对住院资金主账（inp_deposit / inp_settlement / inp_order）严格只读，
 * 补缴只落 inp_arrears_payment——口径隔离说明见 {@link ArrearsService}。
 */
@RestController
@RequestMapping("/api/inpatient/arrears")
@PreAuthorize("hasAnyRole('ADMIN','CASHIER')")
@RequiredArgsConstructor
public class ArrearsController {

    private final ArrearsService arrearsService;
    private final CurrentUserService currentUserService;

    /** 欠费清单：status 可选 OPEN/PARTIAL/CLEARED/WRITTEN_OFF，缺省全量 */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return R.ok(arrearsService.list(status));
    }

    /** 单条欠费详情（含补缴流水与催缴记录） */
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable Long id) {
        try {
            return R.ok(arrearsService.detail(id));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record PayRequest(BigDecimal amount, String payMethod) {}

    /** 补缴：累计足额自动置 CLEARED；超额 9037、非正数 9036 */
    @PostMapping("/{id}/payments")
    public R<Map<String, Object>> pay(@PathVariable Long id, @RequestBody PayRequest req, Authentication auth) {
        try {
            return R.ok(arrearsService.pay(id, req.amount(), req.payMethod(), currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record DunRequest(String method, String note) {}

    /** 催缴登记：PHONE 电话 / SMS 短信 / VISIT 上门 / LETTER 书面 */
    @PostMapping("/{id}/dunnings")
    public R<Map<String, Object>> dun(@PathVariable Long id, @RequestBody DunRequest req, Authentication auth) {
        try {
            return R.ok(arrearsService.dun(id, req.method(), req.note(), currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record WriteOffRequest(String reason) {}

    /** 核销（坏账）：把应收变成损失，仅 ADMIN，原因必填（9040） */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/write-off")
    public R<Map<String, Object>> writeOff(@PathVariable Long id, @RequestBody WriteOffRequest req,
                                           Authentication auth) {
        try {
            return R.ok(arrearsService.writeOff(id, req.reason(), currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }
}
