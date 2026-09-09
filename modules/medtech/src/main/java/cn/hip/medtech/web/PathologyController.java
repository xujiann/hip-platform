package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 二十四期：病理——复用 LIS 标本流转模式（取材→核收→诊断报告），报告发布联动医嘱执行 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pathology")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','DOCTOR_OUTP')")   // 1.0.9：权限清点补齐
public class PathologyController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    /** 待取材：已收费、项目名含「病理/活检」的申请 */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending() {
        return R.ok(jdbc.queryForList("""
                select o.id as order_id, o.item_name, o.group_no, p.name as patient_name
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where o.status = 'CHARGED' and (o.item_name like '%病理%' or o.item_name like '%活检%')
                  and not exists (select 1 from path_specimen s where s.order_id = o.id)
                order by o.id desc limit 100
                """));
    }

    /** 取材登记（打码 PB-） */
    @PostMapping("/specimens")
    @Transactional
    public R<Map<String, Object>> collect(@RequestParam Long orderId,
                                          @RequestParam(required = false) String specimenDesc) {
        String barcode = "PB" + jdbc.queryForObject("select nextval('path_specimen_seq')", Long.class);
        int n = jdbc.update("""
                insert into path_specimen(order_id, barcode, specimen_desc)
                select ?, ?, ? where exists (select 1 from outp_order o where o.id = ? and o.status = 'CHARGED')
                  and not exists (select 1 from path_specimen s where s.order_id = ?)
                """, orderId, barcode, specimenDesc, orderId, orderId);
        return n == 0 ? R.fail(4550, "申请不存在/未收费/已取材") : R.ok(Map.of("barcode", barcode));
    }

    /**
     * 核收。
     *
     * <p><b>v48 加了 {@code rejected_at is null}</b>：v48 的拒收<b>刻意不改 status</b>
     * （拒收不删记录、也不占用状态值域，下游按 {@code rejected_at} 判断），
     * 于是被拒收的标本仍是 {@code COLLECTED}，这条 update 原样能命中——
     * 实测可走通「拒收 → 核收 → 诊断」，台账上出现<b>「已拒收却已发报告」</b>的标本。
     * 这个洞是 v48 引入的（拒收是本版新功能，本端点不知道它存在），故本版必须堵。
     *
     * <p><b>返回键与错误码值域未变</b>：仍是 {@code R<Void>} 与 4551，
     * 改的只是 4551 的触发时机（多了一种「已拒收」的情形）。
     */
    @PutMapping("/specimens/{barcode}/receive")
    public R<Void> receive(@PathVariable String barcode) {
        int n = jdbc.update(
                "update path_specimen set status = 'RECEIVED', received_at = now() "
                        + "where barcode = ? and status = 'COLLECTED' and rejected_at is null",
                barcode);
        return n == 0 ? R.fail(4551, "标本不存在、状态不符或已拒收") : R.ok();
    }

    public record DiagnoseReq(String grossFinding, String microFinding, String diagnosis) {}

    /**
     * 病理诊断报告：发布后联动医嘱执行。
     *
     * <p><b>v48 加了 {@code rejected_at is null}</b>——理由同 {@link #receive(String)}：
     * 已拒收的标本不得出报告。4553 值域未变，只是多了一种触发情形。
     *
     * <p><b>v57（2530）：大体 / 镜下所见「空白即保留原值」</b>。此前这条 update 对
     * {@code gross_finding} / {@code micro_finding} 是<b>无条件覆盖</b>：取材工位
     * （{@code POST /api/pathology/process/grossing}，GROSSING 节点）写进 {@code gross_finding}
     * 的大体所见，只要出报告时该字段传空就被静默抹掉，且无历史可回——旧页「专科流程」
     * （SpecialtyView.vue，菜单 47）正是这样调的，而且传的还是写死的伪造临床文本。
     * 现在两列改为 {@code coalesce(nullif(btrim(?::text), ''), <原列>)}：入参空白
     * （null / 空串 / 纯空白）时保留原值；非空白时按 trim 后的内容覆盖——工作台
     * DiagnosisPanel 预填既有大体所见再编辑提交的合法路径<b>契约不变</b>（显式传值仍能改）。
     *
     * <p><b>返回体从 {@code R<Void>} 改为三个事实</b>：{@code specimenId}、{@code grossKept}、
     * {@code microKept}（Kept=true 表示本次入参空白且原值非空而被保留）。全仓调用方
     * （DiagnosisPanel.vue、tools/e2e-*.py）此前只看 {@code code}，无人依赖 {@code data} 为空。
     * 4552、状态守卫（RECEIVED 且未拒收）、{@code outp_order} 置 EXECUTED 一律不动。
     *
     * <p><b>v58（2530）：覆盖大体所见必须留痕</b>。v57 之前该列被覆盖时原文没有任何地方留着——
     * 「与首次报告共用、可被整体覆盖且无任何历史留存的单列」。现在入参非空且与原值不同（trim 后比较）时，
     * 同一事务往 {@code path_gross_revision}（V164）写一行：{@code old_text} = 覆盖前的原值、
     * {@code new_text} = 新值、{@code source='DIAGNOSE'}、{@code changed_by} = 当前用户，
     * {@code seq} 接在取材首写之后续排。<b>空白保留时不写、与原值相同时不写</b>——没有变化就没有版本。
     * 返回体<b>只加</b>一个键 {@code grossRevised}（本次是否写了修订行），三个既有键不动。
     * 原值在 update 之前<b>锁行读出</b>：覆盖后再读就没了，而修订留痕要的正是「覆盖前是什么」。
     */
    @PutMapping("/specimens/{barcode}/diagnose")
    @Transactional
    public R<Map<String, Object>> diagnose(@PathVariable String barcode, @RequestBody DiagnoseReq req, Authentication auth) {
        if (req.diagnosis() == null || req.diagnosis().isBlank()) return R.fail(4552, "诊断不能为空");
        String gross = blankToNull(req.grossFinding());
        String micro = blankToNull(req.microFinding());
        // v58：覆盖前锁行读原值。只读不判——状态守卫仍由下面那条 update 的 where 决定，4553 触发情形一个不变。
        var before = jdbc.queryForList(
                "select gross_finding from path_specimen where barcode = ? for update", barcode);
        String oldGrossRaw = before.isEmpty() ? null : (String) before.get(0).get("gross_finding");
        String oldGross = blankToNull(oldGrossRaw);
        Long uid = currentUserService.idOf(auth);
        int n = jdbc.update("""
                update path_specimen set status = 'DIAGNOSED',
                       gross_finding = coalesce(nullif(btrim(?::text), ''), gross_finding),
                       micro_finding = coalesce(nullif(btrim(?::text), ''), micro_finding),
                       diagnosis = ?, pathologist_id = ?, diagnosed_at = now()
                where barcode = ? and status = 'RECEIVED' and rejected_at is null
                """, gross, micro, req.diagnosis(),
                uid, barcode);
        if (n == 0) return R.fail(4553, "标本不存在、未核收或已拒收");
        jdbc.update("""
                update outp_order set status = 'EXECUTED'
                where id = (select order_id from path_specimen where barcode = ?) and status = 'CHARGED'
                """, barcode);
        // 事实回读：入参空白时列值就是原值，故「入参空白 且 结果非空」即「原值非空而被保留」
        var row = jdbc.queryForMap("""
                select id,
                       (gross_finding is not null and btrim(gross_finding) <> '') as gross_present,
                       (micro_finding is not null and btrim(micro_finding) <> '') as micro_present
                from path_specimen where barcode = ?
                """, barcode);
        // v58：入参非空且与原值不同（trim 后比较）→ 写修订行；空白保留、与原值相同都不写。
        // old_text 存覆盖前的原文（非空白时原样，空白视同「无原值」存 NULL）；seq 由单条 insert 内的子查询取 max+1。
        boolean grossRevised = gross != null && !gross.equals(oldGross);
        if (grossRevised) {
            PathologyProcessController.insertGrossRevision(jdbc, ((Number) row.get("id")).longValue(),
                    oldGross == null ? null : oldGrossRaw, gross, "DIAGNOSE", uid);
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("specimenId", row.get("id"));
        body.put("grossKept", gross == null && Boolean.TRUE.equals(row.get("gross_present")));
        body.put("microKept", micro == null && Boolean.TRUE.equals(row.get("micro_present")));
        body.put("grossRevised", grossRevised);
        return R.ok(body);
    }

    /** 空白（null / 空串 / 纯空白）归一为 null，非空白 trim 后入库 */
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /**
     * 标本流转列表。
     *
     * <p><b>v48 唯一动过的既有方法，动的是漏行缺陷不是契约</b>：原实现是
     * {@code join outp_order o on o.id = s.order_id}（inner join）。V144 之后
     * {@code path_specimen} 是<b>双来源</b>——住院开的病理申请挂 {@code inp_order_id}、
     * {@code order_id} 为 null，inner join 会把这些行<b>静默漏掉</b>：标本在流转，
     * 工作台上却一条也看不见，而且不报任何错。这是本版必须修的真缺陷。
     *
     * <p><b>本次改动没有增删任何键</b>：仍是 {@code s.*} 加 {@code item_name}、
     * {@code patient_name} 三部分，键名、键序、条数上限（100）、排序（id 降序）全部照旧；
     * 前端 SpecialtyView.vue 消费的 {@code barcode/patient_name/item_name/status/diagnosis/id}
     * 一个不少。改的只是这两个键的<b>取值来源</b>：住院来源的行从
     * {@code inp_order}/{@code inp_admission} 取，门诊来源仍从 {@code outp_order}/挂号取。
     * 三处 join 一律改 left join 再 coalesce——任何一侧缺失都不该把行整条吞掉，
     * 那正是原缺陷的成因。
     *
     * <p><b>但要如实说清一件不是本次改动造成的事</b>：这里 select 的是 {@code s.*}，
     * 所以 V144 给 {@code path_specimen} 加的 18 个新列（{@code path_no}、{@code part_no}、
     * {@code specimen_type}、双签四列……）<b>从迁移落地那一刻起就已经出现在返回体里了</b>，
     * 与这个 join 改不改无关。要让键集合停在 V144 之前的 12 列，唯一办法是把 {@code s.*}
     * 展开成显式列清单——那才是一次<b>真正的契约改动</b>（且会让新字段对工作台永久不可见），
     * 故不做。判断口径：<b>加键是加法、漏行是缺陷</b>，本方法只修后者。
     *
     * <p>新增的检索能力（病理号/日期区间/状态/类别/来源 + truncated 标记）在
     * {@code GET /api/pathology/registry/specimens/search}，不往这个老端点上叠参数。
     */
    @GetMapping("/specimens")
    public R<List<Map<String, Object>>> specimens() {
        return R.ok(jdbc.queryForList("""
                select s.*, coalesce(o.item_name, io.item_name) as item_name,
                       coalesce(p.name, ip.name) as patient_name
                from path_specimen s
                left join outp_order o on o.id = s.order_id
                left join outp_registration r on r.id = o.registration_id
                left join empi_patient p on p.id = r.patient_id
                left join inp_order io on io.id = s.inp_order_id
                left join inp_admission a on a.id = io.admission_id
                left join empi_patient ip on ip.id = a.patient_id
                order by s.id desc limit 100
                """));
    }
}
