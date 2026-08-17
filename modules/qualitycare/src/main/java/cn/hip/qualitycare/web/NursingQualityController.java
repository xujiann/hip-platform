package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 九期：护理白板、护理级别、病历时限质控、病案首页/归档、不良事件、院感登记 */
@RestController
@PreAuthorize("hasAnyRole('ADMIN','NURSE','QUALITY')")   // 1.0.9：权限清点补齐
@RequiredArgsConstructor
public class NursingQualityController {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUserService;

    /** 护理白板：在院一览（床位/护理级别/过敏/未执行医嘱数） */
    @GetMapping("/api/inpatient/nursing/board")
    public R<List<Map<String, Object>>> board() {
        return R.ok(jdbc.queryForList("""
                select a.id as admission_id, a.admission_no, a.care_level,
                       w.name as ward_name, b.bed_no, d.name as dept_name,
                       p.name as patient_name, p.sex, p.allergy_history,
                       (select count(*) from inp_order o where o.admission_id = a.id and o.status = 'CREATED') as pending_orders
                from inp_admission a
                join inp_bed b on b.id = a.bed_id
                join sys_dept w on w.id = a.ward_id
                join sys_dept d on d.id = a.dept_id
                join empi_patient p on p.id = a.patient_id
                where a.status = 'IN_HOSPITAL'
                order by w.name, b.bed_no
                """));
    }

    @PutMapping("/api/inpatient/admissions/{id}/care-level")
    public R<Void> setCareLevel(@PathVariable Long id, @RequestParam String level) {
        if (!List.of("特级", "一级", "二级", "三级").contains(level)) return R.fail(9801, "非法护理级别");
        jdbc.update("update inp_admission set care_level = ? where id = ?", level, id);
        return R.ok();
    }

    /** 病历时限质控：入院>24h 无入院记录 / 出院无出院小结 / 门诊病历未签名 */
    @GetMapping("/api/quality/emr-timeliness")
    public R<Map<String, Object>> emrTimeliness() {
        var missingAdmission = jdbc.queryForList("""
                select a.admission_no, p.name as patient_name, d.name as dept_name,
                       round(extract(epoch from (now() - a.admit_at)) / 3600) as hours
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                where a.admit_at < now() - interval '24 hours'
                  and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'ADMISSION')
                """);
        var missingDischargeSummary = jdbc.queryForList("""
                select a.admission_no, p.name as patient_name
                from inp_admission a join empi_patient p on p.id = a.patient_id
                where a.status = 'DISCHARGED'
                  and not exists (select 1 from inp_medical_record r
                                  where r.admission_id = a.id and r.record_type = 'DISCHARGE')
                """);
        Long unsignedEmr = jdbc.queryForObject(
                "select count(*) from outp_emr where signature is null", Long.class);
        return R.ok(Map.of(
                "missingAdmissionRecord", missingAdmission,
                "missingDischargeSummary", missingDischargeSummary,
                "unsignedOutpEmrCount", unsignedEmr,
                "defectTotal", missingAdmission.size() + missingDischargeSummary.size()));
    }

    /** 病案首页数据集（出院后自动汇编） */
    @GetMapping("/api/inpatient/admissions/{id}/front-page")
    public R<Map<String, Object>> frontPage(@PathVariable Long id) {
        var rows = jdbc.queryForList("""
                select a.admission_no, a.care_level, a.admit_at, a.discharged_at, a.status, a.archived,
                       a.admit_diag_icd, a.admit_diag_name,
                       p.name as patient_name, p.sex, p.birth_date, p.id_no,
                       d.name as dept_name,
                       s.total_amount, s.deposit_amount, s.balance
                from inp_admission a
                join empi_patient p on p.id = a.patient_id
                join sys_dept d on d.id = a.dept_id
                left join inp_settlement s on s.admission_id = a.id and s.status = 'PAID'
                where a.id = ?
                """, id);
        if (rows.isEmpty()) return R.fail(9802, "住院记录不存在");
        var page = rows.get(0);
        page.put("drugAmount", jdbc.queryForObject(
                "select coalesce(sum(amount),0) from inp_order where admission_id = ? and order_type = 'DRUG' and status = 'EXECUTED'",
                Double.class, id));
        page.put("recordCount", jdbc.queryForObject(
                "select count(*) from inp_medical_record where admission_id = ?", Long.class, id));
        return R.ok(page);
    }

    /** 病案归档（须已出院） */
    @PutMapping("/api/inpatient/admissions/{id}/archive")
    public R<Void> archive(@PathVariable Long id) {
        int n = jdbc.update(
                "update inp_admission set archived = true where id = ? and status = 'DISCHARGED'", id);
        return n == 0 ? R.fail(9803, "仅出院病历可归档") : R.ok();
    }

    public record AdverseEventReq(String type, Integer level, String occurredOn, Long deptId,
                                  String description, Boolean anonymous) {}

    @PostMapping("/api/quality/adverse-events")
    public R<Void> reportAdverse(@RequestBody AdverseEventReq req, Authentication auth) {
        if (req.level() == null || req.level() < 1 || req.level() > 4) return R.fail(9804, "事件分级须为 1-4");
        boolean anon = Boolean.TRUE.equals(req.anonymous());
        jdbc.update("""
                insert into qc_adverse_event(type, level, occurred_on, dept_id, description, anonymous, reporter_id)
                values (?,?,?::date,?,?,?,?)
                """, req.type(), req.level(), req.occurredOn(), req.deptId(), req.description(), anon,
                anon ? null : currentUserService.idOf(auth));
        return R.ok();
    }

    @GetMapping("/api/quality/adverse-events")
    public R<List<Map<String, Object>>> adverseEvents() {
        return R.ok(jdbc.queryForList("""
                select e.id, e.type, e.level, e.occurred_on, e.description, e.anonymous, e.status, e.handle_note,
                       d.name as dept_name
                from qc_adverse_event e left join sys_dept d on d.id = e.dept_id
                order by case e.status when 'NEW' then 0 else 1 end, e.level, e.id desc limit 100
                """));
    }

    @PutMapping("/api/quality/adverse-events/{id}/handle")
    public R<Void> handleAdverse(@PathVariable Long id, @RequestParam String note, Authentication auth) {
        int n = jdbc.update("""
                update qc_adverse_event set status = 'HANDLED', handle_note = ?, handler_id = ?
                where id = ? and status = 'NEW'
                """, note, currentUserService.idOf(auth), id);
        return n == 0 ? R.fail(9805, "事件不存在或已处理") : R.ok();
    }

    public record InfectionReq(Long admissionId, String site, String pathogen, String confirmedOn, String note) {}

    @PostMapping("/api/quality/infections")
    public R<Void> reportInfection(@RequestBody InfectionReq req) {
        jdbc.update("insert into qc_infection_case(admission_id, site, pathogen, confirmed_on, note) values (?,?,?,?::date,?)",
                req.admissionId(), req.site(), req.pathogen(), req.confirmedOn(), req.note());
        return R.ok();
    }

    /** 院感统计：登记病例 + 抗菌药物使用监测 */
    @GetMapping("/api/quality/infections")
    public R<Map<String, Object>> infections() {
        return R.ok(Map.of(
                "cases", jdbc.queryForList("""
                        select c.id, c.site, c.pathogen, c.confirmed_on, c.note,
                               a.admission_no, p.name as patient_name
                        from qc_infection_case c
                        join inp_admission a on a.id = c.admission_id
                        join empi_patient p on p.id = a.patient_id
                        order by c.id desc limit 100
                        """),
                "antibioticOrderCount", jdbc.queryForObject("""
                        select count(*) from inp_order o join md_drug d on d.id = o.item_id
                        where o.order_type = 'DRUG' and d.antibiotic and o.status = 'EXECUTED'
                        """, Long.class)));
    }
}
