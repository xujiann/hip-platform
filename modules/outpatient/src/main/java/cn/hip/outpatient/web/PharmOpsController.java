package cn.hip.outpatient.web;

import cn.hip.outpatient.service.PharmOpsService;
import cn.hip.outpatient.service.PharmOpsService.PharmOpsException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v50 车道 D：门诊药房日常——拆零发药 / 近效期预警 / 拆零余量盘点。
 * 业务口径、现状复核与「为什么不重建盘点单」全部写在 {@link PharmOpsService} 的类注释里。
 *
 * <h2>既有发药链路一字未动</h2>
 * 本控制器是<b>新路径</b>：{@code /api/outpatient/pharm-ops/**}。
 * {@code DispenseController} 的 4 个端点（worklist / dispense / dispensed / return）
 * 与 {@code DispenseService} 的 {@code claimDispense}/{@code claimReturn} 抢占模式逐字保留，
 * 错误码 6001–6006 原样不动。整包发药仍走那条路，本控制器只处理「一盒发不完」的那部分。
 *
 * <h2>权限</h2>
 * 与 {@code DispenseController} 逐字对齐 {@code ADMIN + PHARMACIST}：拆零就是发药动作，
 * 权限面不该比整包发药宽。<b>刻意不放给 NURSE</b>——病区备用药补充虽是拆零的真实场景，
 * 但那要走病区领药流程审批，不是在药房端点上开个口子。
 *
 * <h2>没有配套前端页面</h2>
 * {@code frontend/shell/src} 是共用目录，本车道按分工不改，V150 也<b>刻意没插 sys_menu</b>——
 * 插了菜单就是给药师一个点进去 404 的死链。页面需求已交主控（cross_lane）。
 */
@RestController
@RequestMapping("/api/outpatient/pharm-ops")
@PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
@RequiredArgsConstructor
public class PharmOpsController {

    private final PharmOpsService pharmOpsService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    private Long me(Authentication auth) {
        return auth == null ? null : currentUserService.idOf(auth);
    }

    // ================= 拆零（5460–5479）=================

    /** 某药品的拆零可行性、换算系数与当前散装余量 */
    @GetMapping("/split/drugs/{drugId}")
    public R<Object> splitInfo(@PathVariable Long drugId) {
        try {
            return R.ok(pharmOpsService.splitInfo(drugId));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /**
     * 拆零发药。body: {drugId, qtyMinUnit, registrationId?, orderId?}
     *
     * <p>未维护换算系数的药品在这里返 5460 而<b>不是</b>默认按 1 放行——理由见
     * {@link PharmOpsService} 类注释「为什么未维护换算系数是硬错误而不是 warn 档 gate」。
     */
    @PostMapping("/split/dispense")
    public R<Object> dispenseSplit(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(pharmOpsService.dispenseSplit(
                    asLong(body.get("drugId")), asInt(body.get("qtyMinUnit")),
                    asLong(body.get("registrationId")), asLong(body.get("orderId")), me(auth)));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 拆零退回（支持部分退）。body: {qtyMinUnit} */
    @PostMapping("/split/dispenses/{dispenseId}/return")
    public R<Object> returnSplit(@PathVariable Long dispenseId,
                                 @RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(pharmOpsService.returnSplit(dispenseId, asInt(body.get("qtyMinUnit")), me(auth)));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 拆零发药记录（退回入口）：按挂号或按药品过滤，皆空则最近 100 条 */
    @GetMapping("/split/dispenses")
    public R<Object> splitDispenses(@RequestParam(required = false) Long registrationId,
                                    @RequestParam(required = false) Long drugId) {
        return R.ok(pharmOpsService.splitDispenses(registrationId, drugId));
    }

    /** 拆零余量流水（最小单位）：账实不符时的回溯入口 */
    @GetMapping("/split/drugs/{drugId}/txns")
    public R<Object> splitTxns(@PathVariable Long drugId,
                               @RequestParam(defaultValue = "100") int limit) {
        return R.ok(pharmOpsService.splitTxns(drugId, limit));
    }

    // ================= 近效期（5480–5499）=================

    /**
     * 近效期批次列表。{@code days} 不传则按 {@code pharm.expiry.warn_days} →
     * {@code inv_expiry_warn_days} → 90 解析（返回体带 warnDaysSource 与 caveats 说明取自哪一处）。
     */
    @GetMapping("/expiry/warnings")
    public R<Object> expiryWarnings(@RequestParam(required = false) Integer days) {
        try {
            return R.ok(pharmOpsService.expiryWarnings(days));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 生效的预警天数与来源（配置页排查用） */
    @GetMapping("/expiry/warn-days")
    public R<Object> warnDays() {
        return R.ok(pharmOpsService.resolveWarnDays());
    }

    /**
     * 单药发药前的过期批次校验。
     * <b>发药主链路（整包发药）也可以调这个端点</b>——它不依赖拆零，Lane A/B 无需复制判定逻辑。
     */
    @GetMapping("/expiry/check/{drugId}")
    public R<Object> expiryCheck(@PathVariable Long drugId) {
        try {
            return R.ok(pharmOpsService.expiryCheck(drugId));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    // ================= 拆零余量盘点（5500–5519）=================
    //
    // 只盘散装余量。整包库存盘点走既有 /api/masterdata/inventory/stock-take*（V81），
    // 本车道**不重建、不代理**——同一概念两套盘点单就是两套账。

    /** 建拆零盘点单。body: {drugIds: [..], remark?} */
    @PostMapping("/stock-take/split")
    public R<Object> createSplitTake(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            return R.ok(pharmOpsService.createSplitTake(asLongList(body.get("drugIds")),
                    (String) body.get("remark"), me(auth)));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 批量录实盘数。body: {entries: [{drugId, actualQty}]} */
    @SuppressWarnings("unchecked")
    @PostMapping("/stock-take/split/{takeId}/counts")
    public R<Object> enterCounts(@PathVariable Long takeId,
                                 @RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Object raw = body.get("entries");
            List<Map<String, Object>> entries = raw instanceof List<?> l
                    ? l.stream().map(x -> (Map<String, Object>) x).toList() : List.of();
            return R.ok(pharmOpsService.enterSplitCounts(takeId, entries, me(auth)));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping("/stock-take/split/{takeId}/confirm")
    public R<Object> confirmSplitTake(@PathVariable Long takeId, Authentication auth) {
        try {
            return R.ok(pharmOpsService.confirmSplitTake(takeId, me(auth)));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping("/stock-take/split/{takeId}/cancel")
    public R<Object> cancelSplitTake(@PathVariable Long takeId) {
        try {
            return R.ok(pharmOpsService.cancelSplitTake(takeId));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/stock-take/split/{takeId}")
    public R<Object> splitTake(@PathVariable Long takeId) {
        try {
            return R.ok(pharmOpsService.splitTakeView(takeId));
        } catch (PharmOpsException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/stock-take/split")
    public R<Object> recentSplitTakes() {
        return R.ok(pharmOpsService.recentSplitTakes());
    }

    // ================= 入参转换 =================
    // 前端 JSON 的数字既可能是 Integer 也可能是 String（表单直传），两种都收；
    // 非数字一律转成 null，交由 service 按业务码（5462/5468/5502）报错，不要在这里抛 500。

    /**
     * <b>拆零换算系数维护</b>（v50 主控补：车道 D 只加了列，没有写入路径，
     * 于是拆零在生产上<b>不可达</b>——{@code V50PharmacyTest.splitConversionFactorHasAMaintenanceWritePath}
     * 抓到了这个缺口）。
     *
     * <p><b>为什么必须是人工维护而不是脚本生成</b>：一个销售单位含多少最小单位，
     * 同一通用名不同厂家、不同规格都不一样（同是「阿莫西林胶囊」，甲厂 24 粒/盒、乙厂 12 粒/盒），
     * 从药名或规格串里解析必然出错。而这个数错了的后果是<b>发错药量</b>：
     * 系数填错一倍，患者拿到的药就差一倍。故本端点只接受逐条录入，不提供批量导入猜值。
     *
     * <p>未维护系数的药品由 {@code PharmOpsService} 返 5460 拒绝拆零，
     * <b>而不是默认按 1 处理</b>——默认 1 会让「1 盒」当「1 片」发出去。
     */
    @PutMapping("/drugs/{drugId}/pack-spec")
    public R<Map<String, Object>> setPackSpec(@PathVariable Long drugId,
                                              @RequestBody Map<String, Object> body) {
        Integer packSize = asInt(body.get("packSize"));
        Object mu = body.get("minUnit");
        String minUnit = mu == null ? null : mu.toString().trim();

        Integer exists = jdbc.queryForObject(
                "select count(*) from md_drug where id = ?", Integer.class, drugId);
        if (exists == null || exists == 0) return R.fail(5400, "药品不存在");

        // 允许清空（两个都传 null）——录错了要能撤回，清空后该药回到「不允许拆零」
        boolean clearing = packSize == null && (minUnit == null || minUnit.isBlank());
        if (!clearing) {
            if (packSize == null || packSize < 2) {
                return R.fail(5465, "换算系数必须是不小于 2 的整数（等于 1 说明该药本就不需要拆零）");
            }
            if (minUnit == null || minUnit.isBlank() || minUnit.length() > 8) {
                return R.fail(5465, "最小单位必填且不超过 8 字（如「片」「粒」「支」）");
            }
        }
        jdbc.update("update md_drug set pack_size = ?, min_unit = ? where id = ?",
                clearing ? null : packSize, clearing ? null : minUnit, drugId);

        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("drugId", drugId);
        out.put("packSize", clearing ? null : packSize);
        out.put("minUnit", clearing ? null : minUnit);
        out.put("splitEnabled", !clearing);
        out.put("note", clearing
                ? "已清空换算系数，该药品不再允许拆零（拆零请求将返 5460）"
                : "已维护：1 个销售单位 = " + packSize + " " + minUnit);
        return R.ok(out);
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            String s = String.valueOf(o).trim();
            return s.isEmpty() ? null : Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer asInt(Object o) {
        Long v = asLong(o);
        return v == null || v > Integer.MAX_VALUE || v < Integer.MIN_VALUE ? null : v.intValue();
    }

    private static List<Long> asLongList(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        List<Long> out = new ArrayList<>();
        for (Object x : l) {
            Long v = asLong(x);
            if (v != null) out.add(v);
        }
        return out;
    }
}
