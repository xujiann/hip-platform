package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.qualitycare.service.PreventiveAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 预防保健：护理登记、本人记录；管理员及质控横向调阅。 */
@RestController @RequiredArgsConstructor
@RequestMapping("/api/phc")
@PreAuthorize("hasAnyRole('ADMIN','QUALITY','NURSE')")
public class PhcController {
    private final JdbcTemplate jdbc;
    private final PreventiveAccess access;
    private static final Set<String> TYPES = Set.of("VACCINATION", "PHYSICAL", "EDUCATION");
    public record PhcReq(Long patientId, String recordType, String content, String occurredDate) {}

    @PostMapping("/records")
    @PreAuthorize("hasAnyRole('ADMIN','NURSE')")
    @Transactional
    public R<Void> add(@RequestBody PhcReq req) {
        var actor = access.recorder();
        if (req.recordType() == null || !TYPES.contains(req.recordType())) return R.fail(4610, "类型只能为 VACCINATION/PHYSICAL/EDUCATION");
        if (req.content() == null || req.content().isBlank() || req.content().length() > 500)
            return R.fail(4612, "保健内容须为1–500字");
        LocalDate date;
        try { date = req.occurredDate() == null ? BusinessDates.today() : LocalDate.parse(req.occurredDate()); }
        catch (DateTimeParseException ex) { return R.fail(4612, "保健日期须为有效日期"); }
        if (date.isAfter(BusinessDates.today())) return R.fail(4612, "保健日期不能晚于今天");
        // Keep active-patient validation and insert in one transaction.
        if (jdbc.queryForList("select id from empi_patient where id=? and active for share", req.patientId()).isEmpty())
            return R.fail(4611, "患者不存在");
        actor = access.recorder();
        jdbc.update("insert into phc_record(patient_id,record_type,content,occurred_date,recorder_id) values (?,?,?,?,?)",
                req.patientId(), req.recordType(), req.content(), date, actor.id());
        return R.ok();
    }

    @GetMapping("/records")
    public R<List<Map<String,Object>>> list(@RequestParam(required=false) Long patientId) {
        var actor = access.actor();
        String sql = """
                select r.*, p.name as patient_name, u.real_name as recorder_name
                from phc_record r join empi_patient p on p.id=r.patient_id
                left join sys_user u on u.id=r.recorder_id
                where (? or r.recorder_id=?)
                """;
        return R.ok(patientId == null
                ? jdbc.queryForList(sql + " order by r.id desc limit 100", actor.crossRead(), actor.id())
                : jdbc.queryForList(sql + " and r.patient_id=? order by r.id desc", actor.crossRead(), actor.id(), patientId));
    }

    @GetMapping("/stats")
    public R<List<Map<String,Object>>> stats() {
        var actor = access.actor();
        return R.ok(jdbc.queryForList("select record_type,count(*) as cnt from phc_record where (? or recorder_id=?) group by record_type order by record_type",
                actor.crossRead(), actor.id()));
    }
}
