package cn.hip.outpatient.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
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

    // ---- 输液执行 ----

    /** 待建输液单：已收费/已发药的静脉用药医嘱 */
    @GetMapping("/infusion-candidates")
    public R<List<Map<String, Object>>> candidates() {
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
    public R<Void> createInfusion(@RequestParam Long orderId) {
        int n = jdbc.update("""
                insert into outp_infusion(order_id)
                select ? where exists (select 1 from outp_order o where o.id = ?
                    and o.order_type = 'DRUG' and o.status in ('CHARGED','DISPENSED'))
                  and not exists (select 1 from outp_infusion i where i.order_id = ?)
                """, orderId, orderId, orderId);
        return n == 0 ? R.fail(4501, "医嘱不存在/未收费/已建输液单") : R.ok();
    }

    /** 开始输液：皮试类药品必须有本次就诊的阴性皮试 */
    @PutMapping("/infusions/{id}/start")
    @Transactional
    public R<Void> start(@PathVariable Long id, Authentication auth) {
        var rows = jdbc.queryForList("""
                select i.status, o.item_name, o.registration_id from outp_infusion i
                join outp_order o on o.id = i.order_id where i.id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(4501, "输液单不存在");
        var row = rows.get(0);
        if (!"PENDING".equals(row.get("status"))) return R.fail(4501, "输液单状态不允许开始");
        String itemName = (String) row.get("item_name");
        // 按皮试类别匹配：原实现只看"本次就诊有任一阴性皮试"，头孢皮试即放行青霉素（安全门失效）
        String category = skinTestCategory(itemName);
        if (category != null) {
            var negatives = jdbc.queryForList("""
                    select drug_name from outp_skin_test where registration_id = ? and result = 'NEG'
                    """, String.class, row.get("registration_id"));
            boolean covered = negatives.stream().anyMatch(n -> category.equals(skinTestCategory(n)));
            if (!covered) {
                return R.fail(4502, "皮试拦截：" + itemName + " 需" + category + "阴性皮试结果后方可输液");
            }
        }
        jdbc.update("update outp_infusion set status = 'RUNNING', nurse_id = ?, started_at = now() where id = ?",
                currentUserService.idOf(auth), id);
        return R.ok();
    }

    /** 巡视记录 */
    @PostMapping("/infusions/{id}/checks")
    public R<Void> addCheck(@PathVariable Long id, @RequestParam String note, Authentication auth) {
        Integer running = jdbc.queryForObject(
                "select count(*) from outp_infusion where id = ? and status = 'RUNNING'", Integer.class, id);
        if (running == null || running == 0) return R.fail(4501, "仅输液中可登记巡视");
        jdbc.update("insert into outp_infusion_check(infusion_id, note, nurse_id) values (?,?,?)",
                id, note, currentUserService.idOf(auth));
        return R.ok();
    }

    /** 结束输液 */
    @PutMapping("/infusions/{id}/finish")
    public R<Void> finish(@PathVariable Long id) {
        int n = jdbc.update(
                "update outp_infusion set status = 'DONE', finished_at = now() where id = ? and status = 'RUNNING'", id);
        return n == 0 ? R.fail(4501, "仅输液中可结束") : R.ok();
    }

    @GetMapping("/infusions")
    public R<List<Map<String, Object>>> infusions() {
        return R.ok(jdbc.queryForList("""
                select i.id, i.status, i.started_at, i.finished_at, o.item_name, o.usage_route,
                       p.name as patient_name,
                       (select count(*) from outp_infusion_check c where c.infusion_id = i.id) as checks
                from outp_infusion i
                join outp_order o on o.id = i.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where i.status <> 'DONE' or i.finished_at > now() - interval '1 day'
                order by i.id desc limit 100
                """));
    }
}
