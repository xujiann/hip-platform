package cn.hip.outpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.outpatient.service.OutpatientExecutionAccess;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十三期：门诊护士站——皮试登记与结果、输液单执行（含巡视记录） */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/outpatient/nurse")
@PreAuthorize("hasAnyRole('ADMIN','NURSE')")   // 1.0.9：权限清点补齐
public class OutpNurseStationController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;
    private final OutpatientExecutionAccess executionAccess;
    private final EntityManager entityManager;

    /**
     * 皮试类别：输液前必须有**同类别**阴性皮试结果。
     * 按类别而非单一药名匹配——阿莫西林属青霉素类，临床做的就是青霉素皮试；
     * 但头孢类皮试不能覆盖青霉素类（原实现只看"本次就诊有任一阴性皮试"，两类互相放行）。
     */
    private static final Map<String, List<String>> SKIN_TEST_CATEGORIES = Map.of(
            "青霉素类", List.of("青霉素", "西林"),
            "头孢类", List.of("头孢"));

    /** 药名 → 皮试类别；不需皮试返回 null */
    private static String skinTestCategory(String drugName) {
        if (drugName == null) return null;
        for (var e : SKIN_TEST_CATEGORIES.entrySet()) {
            if (e.getValue().stream().anyMatch(drugName::contains)) return e.getKey();
        }
        return null;
    }

    // ---- 皮试 ----
    public record SkinTestReq(Long registrationId, String drugName) {}

    @PostMapping("/skin-tests")
    public R<Void> createSkinTest(@RequestBody SkinTestReq req, Authentication auth) {
        jdbc.update("insert into outp_skin_test(registration_id, drug_name, nurse_id) values (?,?,?)",
                req.registrationId(), req.drugName(), currentUserService.idOf(auth));
        return R.ok();
    }

    @PutMapping("/skin-tests/{id}/result")
    public R<Void> recordResult(@PathVariable Long id, @RequestParam String result, Authentication auth) {
        if (!"NEG".equals(result) && !"POS".equals(result)) return R.fail(4500, "结果只能为 NEG/POS");
        int n = jdbc.update("""
                update outp_skin_test set result = ?, nurse_id = ?, tested_at = now()
                where id = ? and result = 'PENDING'
                """, result, currentUserService.idOf(auth), id);
        return n == 0 ? R.fail(4500, "皮试不存在或已出结果") : R.ok();
    }

    @GetMapping("/skin-tests")
    public R<List<Map<String, Object>>> skinTests(@RequestParam(required = false) Long registrationId) {
        String base = """
                select t.*, p.name as patient_name from outp_skin_test t
                join outp_registration r on r.id = t.registration_id
                join empi_patient p on p.id = r.patient_id
                """;
        return R.ok(registrationId == null
                ? jdbc.queryForList(base + " order by t.id desc limit 100")
                : jdbc.queryForList(base + " where t.registration_id = ? order by t.id desc", registrationId));
    }

    /**
     * 1050 皮试结果回诊室：<b>医生可读</b>的皮试结果查询。
     *
     * <p>问题不在"没有端点"，而在<b>角色被挡</b>：本类类级 {@code @PreAuthorize} 是
     * {@code hasAnyRole('ADMIN','NURSE')}，医生调 {@code GET /skin-tests} 一律 1005 无权限，
     * 于是"护士做完皮试、医生在诊室看结果"这条最基本的闭环走不通——开药的人看不到皮试结论。
     *
     * <p>方法级注解覆盖类级，<b>只放开读、不放开写</b>：登记（POST /skin-tests）与
     * 出结果（PUT /skin-tests/{id}/result）仍是护士职能，医生角色进不去。
     *
     * <p>与 {@code GET /skin-tests} 的差别：本端点<b>强制</b>按 registrationId 取本次就诊，
     * 不提供"最近 100 条全院皮试"的宽表——诊室场景只需要眼前这位患者，
     * 给医生角色开一个全院列表是没必要的越权面。
     *
     * @return 皮试项目（drugName/category）、结果（result/resultText）、时间（testedAt/createdAt）、
     *         执行人（nurseName），外加患者姓名，供门诊医生站直接渲染
     */
    @GetMapping("/skin-tests/for-doctor")
    @PreAuthorize("hasAnyRole('ADMIN','NURSE','DOCTOR_OUTP')")
    public R<List<Map<String, Object>>> skinTestsForDoctor(@RequestParam Long registrationId) {
        if (registrationId == null || registrationId <= 0) return R.fail(4500, "registrationId 必填");
        var rows = jdbc.queryForList("""
                select t.id, t.registration_id, t.drug_name, t.result, t.tested_at, t.created_at,
                       u.real_name as nurse_name, p.name as patient_name
                from outp_skin_test t
                join outp_registration r on r.id = t.registration_id
                join empi_patient p on p.id = r.patient_id
                left join sys_user u on u.id = t.nurse_id
                where t.registration_id = ?
                order by t.id desc
                """, registrationId);
        return R.ok(rows.stream().map(r -> {
            var m = new java.util.LinkedHashMap<String, Object>(r);
            String result = (String) r.get("result");
            m.put("category", skinTestCategory((String) r.get("drug_name")));
            m.put("result_text", "NEG".equals(result) ? "阴性"
                    : "POS".equals(result) ? "阳性" : "待出结果");
            m.put("positive", "POS".equals(result));
            return (Map<String, Object>) m;
        }).toList());
    }

    // ---- 输液执行 ----

    /** 待建输液单：已收费/已发药的静脉用药医嘱 */
    @GetMapping("/infusion-candidates")
    public R<List<Map<String, Object>>> candidates() {
        executionAccess.nurse(null);
        return R.ok(jdbc.queryForList("""
                select o.id as order_id, o.item_name, o.usage_route, o.group_no, p.name as patient_name, o.registration_id
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where o.order_type = 'DRUG' and o.status in ('CHARGED', 'DISPENSED')
                  and coalesce(o.usage_route, '') like '%静%'
                  and not exists (select 1 from outp_infusion i where i.order_id = o.id)
                order by o.id desc limit 100
                """));
    }

    /** 生成输液单 */
    @PostMapping("/infusions")
    @Transactional
    public R<Void> createInfusion(@RequestParam Long orderId) {
        executionAccess.nurse(null);
        entityManager.flush();
        var order = lockOrder(orderId, null);
        if (order == null || !paidDrug(order)) return R.fail(4501, "医嘱不存在/未收费/已建输液单");
        // O 锁串行化同一医嘱建单；不能把唯一约束冲突当作第二人的业务响应。
        var existing = jdbc.queryForList("select id from outp_infusion where order_id = ? for update", orderId);
        executionAccess.nurse(null);
        if (!existing.isEmpty()) return R.fail(4501, "医嘱不存在/未收费/已建输液单");
        jdbc.update("insert into outp_infusion(order_id) values (?)", orderId);
        return R.ok();
    }

    /** 开始输液：皮试类药品必须有本次就诊的阴性皮试 */
    @PutMapping("/infusions/{id}/start")
    @Transactional
    public R<Void> start(@PathVariable Long id, Authentication auth) {
        executionAccess.nurse(auth);
        entityManager.flush();
        var care = lockCare(id, auth);
        if (care == null) return R.fail(4501, "输液单不存在");
        if (!"PENDING".equals(care.infusion().get("status")) || !paidDrug(care.order())) {
            return R.fail(4501, "输液单状态或医嘱收费状态不允许开始");
        }
        String itemName = (String) care.order().get("item_name");
        // 按皮试类别匹配：原实现只看"本次就诊有任一阴性皮试"，头孢皮试即放行青霉素（安全门失效）
        String category = skinTestCategory(itemName);
        if (category != null) {
            var negatives = jdbc.queryForList("""
                    select drug_name from outp_skin_test where registration_id = ? and result = 'NEG'
                    """, String.class, care.order().get("registration_id"));
            boolean covered = negatives.stream().anyMatch(n -> category.equals(skinTestCategory(n)));
            if (!covered) {
                return R.fail(4502, "皮试拦截：" + itemName + " 需" + category + "阴性皮试结果后方可输液");
            }
        }
        Long nurseId = executionAccess.nurse(auth);
        int n = jdbc.update("""
                update outp_infusion set status = 'RUNNING', nurse_id = ?, started_at = now()
                where id = ? and status = 'PENDING'
                """, nurseId, id);
        return n == 0 ? R.fail(4501, "输液单状态不允许开始") : R.ok();
    }

    /** 巡视记录 */
    @PostMapping("/infusions/{id}/checks")
    @Transactional
    public R<Void> addCheck(@PathVariable Long id, @RequestParam String note, Authentication auth) {
        executionAccess.nurse(auth);
        if (note == null || note.isBlank() || note.length() > 500) return R.fail(4000, "巡视记录须为1至500字真实内容");
        entityManager.flush();
        var care = lockCare(id, auth);
        if (care == null || !"RUNNING".equals(care.infusion().get("status"))) {
            return R.fail(4501, "仅输液中可登记巡视");
        }
        jdbc.update("insert into outp_infusion_check(infusion_id, note, nurse_id) values (?,?,?)",
                id, note, executionAccess.nurse(auth));
        return R.ok();
    }

    /** 结束输液 */
    @PutMapping("/infusions/{id}/finish")
    @Transactional
    public R<Void> finish(@PathVariable Long id) {
        executionAccess.nurse(null);
        entityManager.flush();
        var care = lockCare(id, null);
        if (care == null || !"RUNNING".equals(care.infusion().get("status"))) {
            return R.fail(4501, "仅输液中可结束");
        }
        int n = jdbc.update(
                "update outp_infusion set status = 'DONE', finished_at = now() where id = ? and status = 'RUNNING'", id);
        return n == 0 ? R.fail(4501, "仅输液中可结束") : R.ok();
    }

    @GetMapping("/infusions")
    public R<List<Map<String, Object>>> infusions() {
        executionAccess.nurse(null);
        return R.ok(jdbc.queryForList("""
                select i.id, i.status, i.started_at, i.finished_at, o.item_name, o.usage_route,
                       p.name as patient_name, o.status as order_status,
                       (select count(*) from outp_infusion_check c where c.infusion_id = i.id) as checks
                from outp_infusion i
                join outp_order o on o.id = i.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where i.status <> 'DONE' or i.finished_at > now() - interval '1 day'
                order by i.id desc limit 100
                """));
    }

    /** 每条输液写路径都按 O→I 锁序；先读关联 ID 不持有 I 锁。 */
    private LockedCare lockCare(Long infusionId, Authentication auth) {
        var links = jdbc.queryForList("select order_id from outp_infusion where id = ?", Long.class, infusionId);
        if (links.isEmpty()) return null;
        Long orderId = links.getFirst();
        var order = lockOrder(orderId, auth);
        if (order == null) return null;
        var rows = jdbc.queryForList("select id, status from outp_infusion where id = ? and order_id = ? for update",
                infusionId, orderId);
        executionAccess.nurse(auth);
        return rows.isEmpty() ? null : new LockedCare(order, rows.getFirst());
    }

    private Map<String, Object> lockOrder(Long orderId, Authentication auth) {
        var rows = jdbc.queryForList("""
                select id, order_type, status, item_name, registration_id from outp_order where id = ? for update
                """, orderId);
        executionAccess.nurse(auth);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static boolean paidDrug(Map<String, Object> order) {
        return "DRUG".equals(order.get("order_type"))
                && ("CHARGED".equals(order.get("status")) || "DISPENSED".equals(order.get("status")));
    }

    private record LockedCare(Map<String, Object> order, Map<String, Object> infusion) {}
}
