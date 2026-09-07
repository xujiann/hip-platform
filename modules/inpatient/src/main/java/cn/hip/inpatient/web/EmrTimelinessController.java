package cn.hip.inpatient.web;

import cn.hip.inpatient.service.EmrTimelinessService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v53 车道 V3：病历书写时限质控端点。
 *
 * <p><b>路径另起 {@code /api/emr-timeliness}，不并进 {@code InpEmrController} 或
 * {@code NursingQualityController}</b>：那两个类是既有读写口，多套 E2E 与单测钉着返回体形状。
 * 本版纪律是「只加不改」，端点同理——加一组新路径，既有路径与既有返回体一个字节不动。
 *
 * <p><b>与 v34 既有时限统计（{@code NursingQualityController} 的
 * {@code emr.timeliness.first_progress_hours} 消费点）的关系</b>：本组端点<b>不取代它</b>，
 * 也不复制它的阈值——首程 8 小时这个数字<b>只有那一个 sys_config 键可改</b>，
 * 本服务读的就是它（{@code config_key} 一列指向）。改了那个键，两张报表一起动。
 * 若本服务另存一个 8，院方把键改成 12 之后会得到两个互相矛盾的超时率，
 * 这正是 v49 学过的「算得出但对不上账」。
 *
 * <h3>返回体上的三条诚实标注（前端必须显示，已写进 cross_lane）</h3>
 * <ol>
 *   <li>{@code available=false} 的规则<b>没有 rows、没有数字</b>，只有 {@code unavailableReason}
 *       与 {@code missingFields}。前端要显示成「本平台算不出这条指标」，
 *       <b>不许渲染成 0 或空表</b>——一张全 0 的表会被读成「本院无超时」。</li>
 *   <li>{@code sourceVerified=false} 的规则，{@code sourceArticle} 自带后缀
 *       {@code （阈值与条文号待病案科核对原文确认）}。前端<b>直接展示 sourceArticle 原文</b>，
 *       不要拿 {@code sourceArticleRaw} 去掉后缀显示。</li>
 *   <li>{@code overtimeRatePct} 可能为 {@code null}（分母为 0）。前端必须显示成「—」或
 *       「无可判定病例」，<b>不许当成 0%</b>。</li>
 * </ol>
 *
 * <p>错误码全部出自本车道子段 5740–5759，见 {@link EmrTimelinessService} 类注释。
 */
@RestController
@RequestMapping("/api/emr-timeliness")
@PreAuthorize("hasAnyRole('ADMIN','QUALITY','OPERATION','DOCTOR_OUTP','NURSE')")
@RequiredArgsConstructor
public class EmrTimelinessController {

    private final EmrTimelinessService timelinessService;
    private final CurrentUserService currentUserService;

    // ==================================================================
    // 一、目录与配置
    // ==================================================================

    /**
     * 规则目录：编码 → 名称 / 可用性 / 生效阈值 / 出处。
     *
     * <p>{@code unavailableCount} 与 {@code unverifiedCount} 是两个<b>刻意放在顶层</b>的数字：
     * 11 条规则里有几条算不出来、有几条阈值还没经病案科核过原文，一眼可见，
     * 不必逐条展开才发现「原来一半的指标是不能用的」。
     */
    @GetMapping("/catalog")
    public R<Map<String, Object>> catalog() {
        return wrap(timelinessService.catalog());
    }

    /** 当前生效配置（前端提示文案与表头按它渲染，避免把默认值在前端写死第二份）。 */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        return wrap(timelinessService.config());
    }

    /**
     * <b>锚点体检</b>：逐规则回答「这条规则的时间锚点在库里到底取哪个字段、那个字段存不存在、
     * 全表填充率多少」。
     *
     * <p>本端点是本车道最要紧的一个。选错锚点不会报错，只会让整张报表安静地失真：
     * 「首次病程 8 小时」的起点若取成「首条医嘱时刻」，病历写得越晚起点就越晚，
     * <b>超时率会恒为 0，而且看不出破绽</b>。
     *
     * <p>V159 种子里每条 {@code available=false} 都写了原因（具体到表名列名），但那是<b>写迁移的人的断言</b>；
     * 本端点用 {@code information_schema} 与 {@code count(col)} 把断言变成机器可复核的事实——
     * {@code inp_admission.death_at} 到底存不存在，一查便知。
     *
     * <p>另给 {@code executedStartAnchor / executedEndAnchor}：本服务<b>实际执行</b>的锚点。
     * 它与库里声明的锚点必须逐字一致，不一致时该规则会被降级为不可执行、不给任何数字——
     * 报表说的锚点必须就是它真正用的锚点。
     */
    @GetMapping("/anchors")
    @PreAuthorize("hasAnyRole('ADMIN','QUALITY','OPERATION')")
    public R<Map<String, Object>> anchors() {
        return wrap(timelinessService.anchors());
    }

    // ==================================================================
    // 二、超时率统计
    // ==================================================================

    /**
     * 超时率统计（按科室 / 责任医师 / 病区分组，或整体）。
     *
     * <p><b>务必先看 {@code coverage} 段再看指标值</b>：历史病历可能没有可靠的书写时刻，
     * 那样算出来的超时率是假的。{@code coverage.first_progress_typed} 为 0 而
     * {@code progress_records} 不为 0，意味着首次病程混写在 PROGRESS 里、在库里与普通病程无法区分。
     *
     * @param rule 规则码；缺省返回全部规则（含算不出来的那几条，带原因、不带数字）
     * @param by   分组维度 dept（默认）/ doctor / ward / none
     */
    @GetMapping("/indicators")
    public R<Map<String, Object>> indicators(@RequestParam(required = false) String rule,
                                             @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to,
                                             @RequestParam(required = false) String by,
                                             @RequestParam(required = false) Long deptId,
                                             @RequestParam(required = false) Long doctorId,
                                             @RequestParam(required = false) Long wardId) {
        return wrap(timelinessService.indicators(rule, from, to, by, deptId, doctorId, wardId));
    }

    // ==================================================================
    // 三、超时清单与即将超时提醒
    // ==================================================================

    /**
     * 超时清单（按科室 / 医师 / 病区 / 病历类型筛选）。
     *
     * <p>不传 {@code rule} 时跨全部「在用」规则合并；此时分页是<b>先各取上限、合并排序、再切页</b>，
     * 任一规则取满上限会在 {@code truncated / truncatedRules} 里点名——<b>不静默截断</b>。
     * 盘点存量请按 {@code rule=} 逐条取，那条路径不合并、不截断。
     *
     * @param overdueOnly 默认 true，只要已超时的（LATE + MISSING_OVERDUE）；
     *                    传 false 会连 PENDING（未到时限、结论未定）一起返回
     * @param status      只要某一种结论；合法值见 {@code /config} 的 {@code statusMeaning}
     */
    @GetMapping("/overdue")
    public R<Map<String, Object>> overdue(@RequestParam(required = false) String rule,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) Long deptId,
                                          @RequestParam(required = false) Long doctorId,
                                          @RequestParam(required = false) Long wardId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "true") boolean overdueOnly,
                                          @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) Integer offset) {
        return wrap(timelinessService.overdue(rule, from, to, deptId, doctorId, wardId,
                status, overdueOnly, limit, offset));
    }

    /**
     * 即将超时提醒：<b>尚未书写、尚未到时限、但已用时长已达该规则 {@code warn_ratio_pct}%</b> 的病历。
     *
     * <p>只提醒还来得及的。已经超时的属于 {@code /overdue}——提醒一件已经发生的事没有意义，
     * 混在一起只会让医生学会忽略这个列表。
     */
    @GetMapping("/upcoming")
    public R<Map<String, Object>> upcoming(@RequestParam(required = false) String rule,
                                           @RequestParam(required = false) Long deptId,
                                           @RequestParam(required = false) Long doctorId,
                                           @RequestParam(required = false) Long wardId,
                                           @RequestParam(required = false) Integer limit) {
        return wrap(timelinessService.upcoming(rule, deptId, doctorId, wardId, limit));
    }

    // ==================================================================
    // 四、gate 只读预检
    // ==================================================================

    /**
     * 某次住院的时限缺项与 gate 裁决（<b>纯只读预检</b>）。
     *
     * <p><b>本端点不拦任何东西</b>，只把事实与裁决摆出来：{@code findings} 是客观缺项
     * （off 档下照样是真的——gate 只决定拿事实怎么办，不决定事实是什么），
     * {@code blocked} 是「若把这条 gate 挂在出院/归档挡点上，此刻会不会被挡」。
     *
     * <p>真正挂到 {@code InpatientService.discharge} 或病案归档的哪一行，由主控裁决——
     * 那两处是核心写路径、多套 E2E 钉着，本车道按铁律不碰（已写进 cross_lane）。
     */
    @GetMapping("/check")
    public R<Map<String, Object>> check(@RequestParam Long admissionId) {
        var v = timelinessService.verdict(admissionId);
        var body = new LinkedHashMap<String, Object>();
        body.put("admissionId", admissionId);
        body.put("gate", v.gate());
        body.put("gateKey", EmrTimelinessService.GATE_KEY);
        body.put("blocked", v.blocked());
        body.put("blockCode", v.blocked() ? v.code() : 0);
        body.put("blockMessage", v.blocked() ? v.message() : null);
        body.put("findings", v.findings().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.code());
            m.put("ruleCode", f.ruleCode());
            m.put("subjectId", f.subjectId());
            m.put("text", f.text());
            return m;
        }).toList());
        body.put("warnings", v.warnings());
        return R.ok(body);
    }

    // ==================================================================
    // 五、规则维护
    // ==================================================================

    /** 单条规则 + 它的全部变更留痕（新到旧）。 */
    @GetMapping("/rules/{code}")
    public R<Map<String, Object>> rule(@PathVariable String code) {
        return wrap(timelinessService.ruleDetail(code));
    }

    /**
     * 修改一条时限规则。<b>每个变更字段落一行 {@code emr_timeliness_rule_log}，变更原因必填（5747）。</b>
     *
     * <p>写路径收窄到管理员与质控（病案科）：这里改的是<b>法定时限阈值</b>，
     * 阈值被改过而系统说不清改成了什么、谁改的、为什么改，历史超时率就永远对不上账
     * （「上个月超时率 3%，为什么现在重算是 11%？」）。
     *
     * <p>可改字段只有五个：{@code limitMinutes / warnRatioPct / enabled / sourceVerified / remark}。
     * <b>锚点、出处、可用性不开放修改</b>——锚点说明必须与代码里可执行的锚点同源（否则报表会声称按
     * A 判、实际按 B 判），换出处等于换了一条法定依据（应新增规则而不是就地改掉旧的），
     * 可用性由平台有没有那个字段决定、不由人声明。
     *
     * <p><b>把 {@code sourceVerified} 置 true 是一个有法律意味的动作</b>：它表示病案科已取
     * 《病历书写基本规范》等文件原文逐条核对过阈值与条文号。置 true 后返回体里的后缀消失、
     * 该规则即可用于对外考核——所以 {@code verified_by / verified_at} 一并落库。
     */
    @PutMapping("/rules/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','QUALITY')")
    public R<Map<String, Object>> updateRule(@PathVariable String code,
                                             @RequestBody EmrTimelinessService.UpdateRequest req,
                                             Authentication auth) {
        return wrap(timelinessService.update(code, req, currentUserService.idOf(auth)));
    }

    private static R<Map<String, Object>> wrap(EmrTimelinessService.Result r) {
        return r.ok() ? R.ok(r.body()) : R.fail(r.code(), r.message());
    }
}
