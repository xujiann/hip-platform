package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.entity.Icd10;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.repository.Icd10Repository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/masterdata")
@RequiredArgsConstructor
public class MasterDataController {

    private final DrugItemRepository drugRepository;
    private final ChargeItemRepository chargeItemRepository;
    private final Icd10Repository icd10Repository;
    private final JdbcTemplate jdbc;

    /** 产品化一期：药品目录 CSV 批量导入（实施工具，按 code upsert）
     *  列：code,name,spec,unit,dose_form,price,stock[,antibiotic 0/1] */
    @PostMapping(value = "/drugs/import", consumes = "text/plain")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Map<String, Object>> importDrugs(@RequestBody String csv) {
        int ok = 0;
        List<String> errors = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || (i == 0 && line.toLowerCase().startsWith("code"))) continue;
            String[] f = line.split(",", -1);
            if (f.length < 6) {
                errors.add("第" + (i + 1) + "行：列数不足 6");
                continue;
            }
            try {
                jdbc.update("""
                        insert into md_drug(code, name, spec, unit, dose_form, price, stock, antibiotic, enabled)
                        values (?,?,?,?,?,?::numeric,?::int,?,true)
                        on conflict (code) do update set name = excluded.name, spec = excluded.spec,
                            unit = excluded.unit, dose_form = excluded.dose_form, price = excluded.price,
                            antibiotic = excluded.antibiotic, enabled = true
                        """, f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(), f[4].strip(),
                        f[5].strip(), f.length > 6 && !f[6].strip().isEmpty() ? f[6].strip() : "0",
                        f.length > 7 && "1".equals(f[7].strip()));
                ok++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行：" + e.getMessage().split("\n")[0]);
            }
        }
        return R.ok(Map.of("imported", ok, "errorCount", errors.size(), "errors", errors));
    }

    /** 产品化一期：收费项目 CSV 批量导入。列：code,name,category,unit,price */
    @PostMapping(value = "/charge-items/import", consumes = "text/plain")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Map<String, Object>> importChargeItems(@RequestBody String csv) {
        int ok = 0;
        List<String> errors = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || (i == 0 && line.toLowerCase().startsWith("code"))) continue;
            String[] f = line.split(",", -1);
            if (f.length < 5) {
                errors.add("第" + (i + 1) + "行：列数不足 5");
                continue;
            }
            try {
                jdbc.update("""
                        insert into md_charge_item(code, name, category, unit, price, enabled)
                        values (?,?,?,?,?::numeric,true)
                        on conflict (code) do update set name = excluded.name, category = excluded.category,
                            unit = excluded.unit, price = excluded.price, enabled = true
                        """, f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(), f[4].strip());
                ok++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行：" + e.getMessage().split("\n")[0]);
            }
        }
        return R.ok(Map.of("imported", ok, "errorCount", errors.size(), "errors", errors));
    }

    @GetMapping("/drugs")
    public R<List<DrugItem>> drugs(@RequestParam(defaultValue = "") String keyword) {
        return R.ok(drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(keyword));
    }

    @GetMapping("/charge-items")
    public R<List<ChargeItem>> chargeItems(@RequestParam(defaultValue = "") String keyword,
                                           @RequestParam(required = false) String category) {
        return R.ok(category == null
                ? chargeItemRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(keyword)
                : chargeItemRepository.findTop20ByEnabledTrueAndCategoryAndNameContainingOrderByCode(category, keyword));
    }

    @GetMapping("/icd10")
    public R<List<Icd10>> icd10(@RequestParam(defaultValue = "") String keyword) {
        return R.ok(icd10Repository.search(keyword, PageRequest.of(0, 20)));
    }
}
