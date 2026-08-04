package cn.hip.qualitycare.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 二十五期：预防保健——疫苗接种/健康体检/健康宣教记录 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/phc")
public class PhcController {

    private final JdbcTemplate jdbc;

    private static final Set<String> TYPES = Set.of("VACCINATION", "PHYSICAL", "EDUCATION");

    public record PhcReq(Long patientId, String recordType, String content, String occurredDate) {}

    @PostMapping("/records")
    public R<Void> add(@RequestBody PhcReq req) {
        if (!TYPES.contains(req.recordType())) return R.fail(4610, "类型只能为 VACCINATION/PHYSICAL/EDUCATION");
        Integer exists = jdbc.queryForObject("select count(*) from empi_patient where id = ? and active",
                Integer.class, req.patientId());
        if (exists == null || exists == 0) return R.fail(4611, "患者不存在");
        jdbc.update("""
                insert into phc_record(patient_id, record_type, content, occurred_date)
                values (?,?,?,coalesce(?::date, current_date))
                """, req.patientId(), req.recordType(), req.content(), req.occurredDate());
        return R.ok();
    }

    @GetMapping("/records")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long patientId) {
        String base = """
                select r.*, p.name as patient_name from phc_record r
                join empi_patient p on p.id = r.patient_id
                """;
        return R.ok(patientId == null
                ? jdbc.queryForList(base + " order by r.id desc limit 100")
                : jdbc.queryForList(base + " where r.patient_id = ? order by r.id desc", patientId));
    }

    /** 按类型统计 */
    @GetMapping("/stats")
    public R<List<Map<String, Object>>> stats() {
        return R.ok(jdbc.queryForList(
                "select record_type, count(*) as cnt from phc_record group by record_type order by record_type"));
    }
}
