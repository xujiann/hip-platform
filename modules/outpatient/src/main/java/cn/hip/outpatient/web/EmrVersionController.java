package cn.hip.outpatient.web;

import cn.hip.outpatient.service.DiagnosisVersionService;
import cn.hip.outpatient.service.EmrVersionService;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v53 车道 V1：病历版本留痕查询端点（错误码 5700–5719）。
 *
 * <h2>本类<b>只有 GET</b>——这是设计，不是还没写完</h2>
 * 全类<b>一个 {@code @PostMapping} / {@code @PutMapping} / {@code @DeleteMapping} /
 * {@code @PatchMapping} 都没有</b>，且这一点由
 * {@code V53EmrVersionTest#controllerExposesNoMutationEndpoint} 用反射钉死——
 * 将来任何人往这里加一个写映射，测试当场变红。三条理由，每条都是本版的铁律：
 * <ol>
 *   <li><b>版本不可篡改。</b>能改的留痕在法庭上没有价值。既然如此，就<b>不要有</b>那个入口——
 *       靠「我们不会去调它」是靠不住的。</li>
 *   <li><b>不提供回滚。</b>「恢复到某一版」会让「当前正文的责任人是谁」变得不清楚：
 *       A 写了 v3、B 一键回滚到 v1，当前正文是 A 一小时前的字、责任却记在谁头上？
 *       法定病历不做这个。要改回去，医师本人重新书写保存，落成 v4，责任链完整。</li>
 *   <li><b>版本只能由接缝产生。</b>留痕必须是「保存病历这个动作的副产品」。
 *       开一个「手工新建版本」的端点，等于允许凭空造出一条没有对应保存动作的记录——那是造证。
 *       写入口只在服务层：{@link EmrVersionService#recordOutp}（主控接入，见 cross_lane）。</li>
 * </ol>
 *
 * <h2>路径与模块开关</h2>
 * 挂 {@code /api/emr/versions/**}。{@code /api/emr} 不在 {@code ModuleGate.MODULES} 的任何
 * apiPrefixes 里，故本组端点<b>不随任何模块开关消失</b>——这是有意的：
 * 病历留痕是法定材料的查阅口，不该因为「这家医院没买 CDSS」而 404。
 * 与 {@code EmrFieldController}（{@code /api/emr/templates|fields|field-search}）
 * 及 {@code ConsentController}（{@code /api/emr/consents}）无路径冲突。
 *
 * <h2>没有配套前端页面</h2>
 * {@code frontend/shell/src} 是共用目录，本车道按分工不改；V157 也<b>刻意没插 sys_menu</b>——
 * 插了菜单就是给医生一个点进去 404 的死链。页面需求已写进 cross_lane 交主控。
 */
@RestController
@RequestMapping("/api/emr/versions")
@PreAuthorize("hasAnyRole('ADMIN','DOCTOR_OUTP','QUALITY')")
@RequiredArgsConstructor
public class EmrVersionController {

    private final EmrVersionService emrVersionService;
    private final DiagnosisVersionService diagnosisVersionService;
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    /**
     * v80 对象级校验（v79 甲组审计 R3）：此前只有类级角色门槛，门诊医生能读任何患者的病历全文与诊断历史，
     * 版本列表还带回患者姓名；引用抽屉对同类数据是 4036。口径与 {@code EmrRefController#canRead} 相同：
     * ADMIN / QUALITY 全看；就诊归属（门诊接诊医生 / 住院主管医生）为空放行；其余须是本人。
     * 登录人从安全上下文取——五个端点的方法签名被既有用例直接调用，不加 Authentication 形参。
     * 病历 / 挂号不存在时不在这里判（交给原逻辑，返回体与 v79 一致），只在查到归属且非本人时拒。
     */
    private R<Map<String, Object>> denyIfNotOwner(String emrType, Long emrId, Long registrationId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_QUALITY".equals(a.getAuthority()))) {
            return null;
        }
        java.util.List<Long> owners;
        if (registrationId != null) {
            owners = jdbc.queryForList("select doctor_id from outp_registration where id = ?", Long.class, registrationId);
        } else if (EmrVersionService.OUTP.equalsIgnoreCase(emrType)) {
            owners = jdbc.queryForList(
                    "select r.doctor_id from outp_emr e join outp_registration r on r.id = e.registration_id where e.id = ?",
                    Long.class, emrId);
        } else if (EmrVersionService.INP.equalsIgnoreCase(emrType)) {
            owners = jdbc.queryForList(
                    "select a.doctor_id from inp_medical_record m join inp_admission a on a.id = m.admission_id where m.id = ?",
                    Long.class, emrId);
        } else {
            return null;   // 类型非法交给原逻辑报 5700
        }
        if (owners.isEmpty() || owners.get(0) == null) return null;
        Long me = auth == null ? null : currentUserService.idOf(auth);
        return owners.get(0).equals(me) ? null
                : R.fail(4036, "无权查阅该病历的版本留痕（非本人接诊/主管的就诊）");
    }

    /**
     * 当前生效的配置与运行计数。
     *
     * <p>前端提示文案按它渲染，避免把默认值写死在前端第二份；运维按 {@code counters.failed}
     * 判断 warn 档有没有在<b>静默</b>吞掉留痕失败——那种情况下医生的病历存进去了、
     * 那一版却没有痕迹，而界面上不会有任何异样。
     */
    @GetMapping("/settings")
    public R<Map<String, Object>> settings() {
        return R.ok(emrVersionService.settings());
    }

    /**
     * 版本列表（倒序，分页）。<b>不返回 content 全文</b>——一页 50 版 × 住院病历几千字，
     * 返了每次翻页都要从 TOAST 里拉几 MB。要看内容请调单版查看端点。
     *
     * <p>没有任何版本记录时返回空 {@code items} 并带一句 {@code notice}：只陈述「暂无版本记录」、
     * 哪些动作会写入版本、此刻 gate 是否 off——<b>不断言为什么没有</b>（v79 收口 v68 复核打回点：
     * 上线前书写、gate=off 时保存、留痕写入失败都会零版本，读路径分不出是哪一种）。
     * 系统不会拿当前内容伪造一条初版把列表填满。门诊病历另带 {@code registrationId}，供版本页接着读诊断变更。
     *
     * @param emrType           OUTP（{@code outp_emr.id}）/ INP（{@code inp_medical_record.id}）
     * @param withChangedFields 额外算出每一版相对上一版「改了哪几个字段」（<b>只回字段名，不回正文</b>）。
     *                          默认关：它要把本页 + 前一版的正文全读出来，正是上面要避免的开销
     */
    @GetMapping("/{emrType}/{emrId}")
    public R<Map<String, Object>> list(@PathVariable String emrType,
                                       @PathVariable Long emrId,
                                       @RequestParam(required = false) Integer limit,
                                       @RequestParam(required = false) Integer offset,
                                       @RequestParam(defaultValue = "false") boolean withChangedFields) {
        var deny = denyIfNotOwner(emrType, emrId, null);
        if (deny != null) return deny;
        var r = emrVersionService.list(emrType, emrId, limit, offset, withChangedFields);
        return r.ok() ? R.ok(r.body()) : R.fail(r.code(), r.message());
    }

    /**
     * v79（994①）：某次门诊就诊的<b>诊断</b>历次版本（{@code outp_diagnosis_version}，v74 起写入、此前无读出路径）。
     *
     * <p>按版本倒序；每版给版本序、操作人姓名、时间、该版诊断明细（照写入时的快照）与较上一版的增删。
     * 路径里的字面段 {@code diagnoses} 比 {@code /{emrType}/{emrId}} 更具体，Spring 优先匹配本映射。
     * 错误码复用：id 非法 4000、挂号不存在 4001。权限随类级注解，与正文版本端点一致。
     */
    @GetMapping("/diagnoses/{registrationId}")
    public R<Map<String, Object>> diagnoses(@PathVariable Long registrationId) {
        var deny = denyIfNotOwner(null, null, registrationId);
        if (deny != null) return deny;
        var r = diagnosisVersionService.list(registrationId);
        return r.ok() ? R.ok(r.body()) : R.fail(r.code(), r.message());
    }

    /**
     * 单版查看：返回<b>完整 content</b> 及拆好的字段。
     *
     * <p>「第 2 版当时到底写的是什么」——这个问题只有这里能回答，也正是 V157 选择存全文快照
     * 而不是存 diff 的理由：拿一串 {@code @@ -3,7 +3,9 @@} 出来是答不上来的。
     */
    @GetMapping("/{emrType}/{emrId}/versions/{versionNo}")
    public R<Map<String, Object>> one(@PathVariable String emrType,
                                      @PathVariable Long emrId,
                                      @PathVariable Integer versionNo) {
        var deny = denyIfNotOwner(emrType, emrId, null);
        if (deny != null) return deny;
        var r = emrVersionService.get(emrType, emrId, versionNo);
        return r.ok() ? R.ok(r.body()) : R.fail(r.code(), r.message());
    }

    /**
     * 任意两版的<b>结构化差异</b>：逐字段给出「哪个字段、从什么改成什么」，
     * 外加首个差异位置与差异片段——不是甩两段文本让人自己比。
     *
     * <p>未变化的字段只回长度、不回正文（{@code from}/{@code to} 为 null）。
     */
    @GetMapping("/{emrType}/{emrId}/compare")
    public R<Map<String, Object>> compare(@PathVariable String emrType,
                                          @PathVariable Long emrId,
                                          @RequestParam("from") Integer fromNo,
                                          @RequestParam("to") Integer toNo) {
        var deny = denyIfNotOwner(emrType, emrId, null);
        if (deny != null) return deny;
        var r = emrVersionService.compare(emrType, emrId, fromNo, toNo);
        return r.ok() ? R.ok(r.body()) : R.fail(r.code(), r.message());
    }

    /**
     * 某一版与<b>当前正文</b>的对比。
     *
     * <p>实务里问得最多的一句话是「现在这份病历和上级签字时那一版差在哪」，
     * 而它不是「两版对比」——当前正文不是一个版本行。右侧的 {@code versionNo} 为 null、
     * {@code label} 为 {@code CURRENT}，<b>本端点不写库、不生成版本行</b>：
     * 把当前内容记成一版，就是伪造了一次并不存在的保存动作。
     *
     * <p>当前正文与<b>最后一版</b>不一致时（只在此时），返回体的 {@code notice} 并列三种可能、不断言是哪一种：
     * 修改发生在留痕接缝之外、gate 曾经开在 off、或 warn 档下留痕写入失败（审阅修补（三））。
     */
    @GetMapping("/{emrType}/{emrId}/compare-current")
    public R<Map<String, Object>> compareCurrent(@PathVariable String emrType,
                                                 @PathVariable Long emrId,
                                                 @RequestParam("from") Integer fromNo) {
        var deny = denyIfNotOwner(emrType, emrId, null);
        if (deny != null) return deny;
        var r = emrVersionService.compareWithCurrent(emrType, emrId, fromNo);
        return r.ok() ? R.ok(r.body()) : R.fail(r.code(), r.message());
    }
}
