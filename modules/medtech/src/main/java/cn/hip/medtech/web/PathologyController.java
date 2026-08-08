package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十四期：病理——复用 LIS 标本流转模式（取材→核收→诊断报告），报告发布联动医嘱执行 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pathology")
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

    @PutMapping("/specimens/{barcode}/receive")
    public R<Void> receive(@PathVariable String barcode) {
        int n = jdbc.update(
                "update path_specimen set status = 'RECEIVED', received_at = now() where barcode = ? and status = 'COLLECTED'",
                barcode);
        return n == 0 ? R.fail(4551, "标本不存在或状态不符") : R.ok();
    }

    public record DiagnoseReq(String grossFinding, String microFinding, String diagnosis) {}

    /** 病理诊断报告：发布后联动医嘱执行 */
    @PutMapping("/specimens/{barcode}/diagnose")
    @Transactional
    public R<Void> diagnose(@PathVariable String barcode, @RequestBody DiagnoseReq req, Authentication auth) {
        if (req.diagnosis() == null || req.diagnosis().isBlank()) return R.fail(4552, "诊断不能为空");
        int n = jdbc.update("""
                update path_specimen set status = 'DIAGNOSED', gross_finding = ?, micro_finding = ?,
                       diagnosis = ?, pathologist_id = ?, diagnosed_at = now()
                where barcode = ? and status = 'RECEIVED'
                """, req.grossFinding(), req.microFinding(), req.diagnosis(),
                currentUserService.idOf(auth), barcode);
        if (n == 0) return R.fail(4553, "标本不存在或未核收");
        jdbc.update("""
                update outp_order set status = 'EXECUTED'
                where id = (select order_id from path_specimen where barcode = ?) and status = 'CHARGED'
                """, barcode);
        return R.ok();
    }

    @GetMapping("/specimens")
    public R<List<Map<String, Object>>> specimens() {
        return R.ok(jdbc.queryForList("""
                select s.*, o.item_name, p.name as patient_name
                from path_specimen s
                join outp_order o on o.id = s.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                order by s.id desc limit 100
                """));
    }
}
