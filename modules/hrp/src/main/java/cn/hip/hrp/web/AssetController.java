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
        // v64 合并后补齐（超出本轮范围，但不修就得靠重跑碰运气，见下）：原为 `System.nanoTime() % 100000`。
        //
        // 那个取模是**真正的撞号机制**：Windows 上 System.nanoTime() 走 QueryPerformanceCounter，
        // 常见 10 MHz 频率使返回值恒为 100 的整数倍，于是 `% 100000` 的可能取值只有 **1000 个**（不是十万）。
        // 一次 E2E 建十来个资产，生日碰撞概率约 4–5%——2026-09-17 的全新库全量 E2E 就是这么红的：
        // hrp_asset_asset_no_key 唯一约束冲突 → 全局兜底 4091「数据不符合约束要求」，
        // 而那句话既不说是哪个字段、也不说是撞号，运维只能一个个删着试。
        //
        // nanoTime() 本身在同一 JVM 内单调递增，**不截断就不会撞**；跨重启同日撞上的概率可忽略。
        // 长度：2 + 8 + 1 + 19 = 30，列是 varchar(32)，放得下。
        // 正解仍是按日序列号（ZC20260917-0001 这种人能读的号），但那要新迁移，不在本轮范围——已记入技术债交接单。
        asset.setAssetNo("ZC" + BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + System.nanoTime());
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
