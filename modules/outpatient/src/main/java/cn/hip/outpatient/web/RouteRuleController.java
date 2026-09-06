package cn.hip.outpatient.web;

import cn.hip.outpatient.service.RouteRuleService;
import cn.hip.outpatient.service.RouteRuleService.OrderLine;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v51 车道 D ①：给药途径与溶媒配伍（错误码 5660–5679）。
 * 业务口径、数据源缺口的实测结论与「为什么不按 dose_form 推途径」全部写在
 * {@link RouteRuleService} 的类注释里，此处不复述。
 *
 * <h2>既有 CDSS 端点一字未动</h2>
 * 本控制器挂 <b>新前缀</b> {@code /api/cdss/route/**}。{@code CdssController} 的 4 个端点
 * （{@code /rules}、{@code /ddi-rules}、{@code /alerts}、{@code /suggestions}）与
 * {@code CdssService} 的三类规则逐字保留，错误码 4015 / 4017 / 4650 原样。
 * 前缀落在 {@code /api/cdss} 之下是有意的：{@code ModuleGate} 的 "cdss" 开关按
 * {@code /api/cdss} 前缀整段拦截，新端点自然随 CDSS 模块一起开关，
 * 不需要改 {@code ModuleGate} 注册表，也不会出现「关了 CDSS 模块、途径校验还在跑」。
 *
 * <h2>权限</h2>
 * 审查类端点开给 {@code ADMIN + DOCTOR_OUTP + PHARMACIST}：开单的是医生，
 * 复核与补别名的是药师，两边都要能看。
 * <b>维护类端点收窄到 {@code ADMIN + PHARMACIST}</b>——适用途径与配伍规则是药学结论，
 * 不该由开单医生在处方界面上顺手改掉自己被拦的那条规则。
 *
 * <h2>没有配套前端页面</h2>
 * {@code frontend/shell/src} 是共用目录，本车道按分工不改，V155 也<b>刻意没插 sys_menu</b>——
 * 插了菜单就是给药师一个点进去 404 的死链。页面需求已交主控（cross_lane）。
 */
@RestController
@RequestMapping("/api/cdss/route")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','PHARMACIST')")
@RequiredArgsConstructor
public class RouteRuleController {

    private final RouteRuleService routeRuleService;
    private final CurrentUserService currentUserService;

    private Long me(Authentication auth) {
        return auth == null ? null : currentUserService.idOf(auth);
    }

    // ================= 审查 =================

    /**
     * 开单前审查（<b>留痕；block 档以错误码返回</b>）。
     * body: {registrationId?, patientId?, lines:[{drugId, usageRoute, groupNo, orderId?}]}
     *
     * <p>block 档命中时返 5661（途径不符）/ 5662（溶媒禁配）。
     * <b>「用法文本未识别」在任何档位下都不会走到这里</b>——看不懂的文本上不做拦截。
     */
    @PostMapping("/check")
    public R<Object> check(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(routeRuleService.check(asLong(body.get("registrationId")),
                    asLong(body.get("patientId")), lines(body), me(auth)));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 只读预检：不留痕、不拦截，只回判定明细。前端可在医生敲完用法时实时调 */
    @PostMapping("/review")
    public R<Object> review(@RequestBody Map<String, Object> body) {
        try {
            return R.ok(routeRuleService.review(lines(body)));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 对某次挂号已开药品医嘱的<b>只读回顾核对</b>。
     *
     * <p>本车道不改 {@code DoctorStationService}（不在名下），若只留一个接缝，
     * 本条需求在本版就是「建了表、写了引擎、一次也没跑过」。此端点让引擎在不动既有开单链路的
     * 前提下跑在真实医嘱上——不写、不拦，只报。
     */
    @GetMapping("/review/registration/{registrationId}")
    public R<Object> reviewRegistration(@PathVariable Long registrationId) {
        try {
            return R.ok(routeRuleService.reviewRegistration(registrationId));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ================= 查询 =================

    /** 当前 gate 与匹配口径 */
    @GetMapping("/settings")
    public R<Map<String, Object>> settings() {
        return R.ok(routeRuleService.settings());
    }

    /** 规则库总览（含覆盖率与口径声明） */
    @GetMapping("/rules")
    public R<Map<String, Object>> rules() {
        return R.ok(routeRuleService.rules());
    }

    /** 维护覆盖率：drugsWithRouteConfigured = 0 时校验不产生任何判定，这一点必须能被看见 */
    @GetMapping("/coverage")
    public R<Map<String, Object>> coverage() {
        return R.ok(routeRuleService.coverage());
    }

    /** 提示留痕。kind 可选：ROUTE_MISMATCH / SOLVENT_FORBID / ROUTE_UNRECOGNIZED */
    @GetMapping("/alerts")
    public R<List<Map<String, Object>>> alerts(@RequestParam(required = false) String kind,
                                               @RequestParam(defaultValue = "200") int limit) {
        return R.ok(routeRuleService.alerts(kind, limit));
    }

    // ================= 规则维护（药剂科）=================

    /** 登记一条给药途径。body: {routeCode, routeName, intravenous, remark?} */
    @PostMapping("/routes")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addRoute(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(routeRuleService.addRoute(str(body.get("routeCode")), str(body.get("routeName")),
                    Boolean.TRUE.equals(body.get("intravenous")), str(body.get("remark")), me(auth)));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 登记一条用法文本别名。body: {aliasText, routeCode}
     *
     * <p>入库前与查询走同一个归一化函数，故「静脉滴注 」（带尾空格）与「静脉滴注」是同一行；
     * 但「静滴」与「静脉滴注」是<b>两条</b>别名，必须分别登记——引擎不做同义推断。
     */
    @PostMapping("/aliases")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addAlias(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(routeRuleService.addAlias(str(body.get("aliasText")), str(body.get("routeCode")), me(auth)));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 维护药品适用途径。body: {drugId, routeCode, basisSource}——依据必填 */
    @PostMapping("/drug-routes")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addDrugRoute(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(routeRuleService.addDrugRoute(asLong(body.get("drugId")), str(body.get("routeCode")),
                    str(body.get("basisSource")), me(auth)));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 登记溶媒品规。body: {drugId, solventCode, solventName, basisSource} */
    @PostMapping("/solvent-drugs")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addSolventDrug(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(routeRuleService.addSolventDrug(asLong(body.get("drugId")), str(body.get("solventCode")),
                    str(body.get("solventName")), str(body.get("basisSource")), me(auth)));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 维护溶媒配伍规则。body: {drugId, solventCode, verdict, basisSource, basisLevel, message} */
    @PostMapping("/solvent-rules")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    public R<Object> addSolventRule(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(routeRuleService.addSolventRule(asLong(body.get("drugId")), str(body.get("solventCode")),
                    str(body.get("verdict")), str(body.get("basisSource")), str(body.get("basisLevel")),
                    str(body.get("message")), me(auth)));
        } catch (HipBizException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ================= 入参解析 =================

    @SuppressWarnings("unchecked")
    private static List<OrderLine> lines(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("lines");
        if (!(raw instanceof List<?> list)) {
            throw new HipBizException(RouteRuleService.E_PARAM, "lines 必须为数组");
        }
        List<OrderLine> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) {
                throw new HipBizException(RouteRuleService.E_PARAM, "lines 元素必须为对象");
            }
            Map<String, Object> m = (Map<String, Object>) o;
            out.add(new OrderLine(asLong(m.get("drugId")), str(m.get("usageRoute")),
                    str(m.get("groupNo")), asLong(m.get("orderId"))));
        }
        return out;
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            String s = o.toString().trim();
            return s.isEmpty() ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new HipBizException(RouteRuleService.E_PARAM, "数字参数格式非法：" + o);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
