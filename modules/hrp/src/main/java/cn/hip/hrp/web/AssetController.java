package cn.hip.hrp.web;

import cn.hip.hrp.entity.HrpAsset;
import cn.hip.hrp.repository.AssetRepo;
import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import cn.hip.platform.core.config.BusinessDates;

@RestController
@RequestMapping("/api/hrp/assets")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AssetController {

    private final AssetRepo assetRepo;

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(assetRepo.findAll(Sort.by("id")).stream().map(a -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", a.getId());
            m.put("assetNo", a.getAssetNo());
            m.put("name", a.getName());
            m.put("category", a.getCategory());
            m.put("deptId", a.getDeptId());
            m.put("price", a.getPrice());
            m.put("purchaseDate", a.getPurchaseDate());
            m.put("usefulYears", a.getUsefulYears());
            m.put("netValue", a.getNetValue());
            m.put("status", a.getStatus());
            m.put("remark", a.getRemark());
            return (Map<String, Object>) m;
        }).toList());
    }

    @PostMapping
    public R<HrpAsset> create(@RequestBody HrpAsset asset) {
        if (asset.getName() == null || asset.getPrice() == null || asset.getPurchaseDate() == null) {
            return R.fail(9601, "名称、价格、购置日期为必填");
        }
        asset.setId(null);
        asset.setAssetNo("ZC" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + System.nanoTime() % 100000);
        return R.ok(assetRepo.save(asset));
    }

    @PutMapping("/{id}/status")
    public R<Void> setStatus(@PathVariable Long id, @RequestParam String status) {
        var a = assetRepo.findById(id).orElse(null);
        if (a == null) return R.fail(9602, "资产不存在");
        if (!List.of("IN_USE", "REPAIR", "SCRAPPED").contains(status)) return R.fail(9603, "非法状态");
        a.setStatus(status);
        assetRepo.save(a);
        return R.ok();
    }
}
