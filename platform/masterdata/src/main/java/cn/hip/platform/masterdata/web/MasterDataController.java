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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/masterdata")
@RequiredArgsConstructor
public class MasterDataController {

    private final DrugItemRepository drugRepository;
    private final ChargeItemRepository chargeItemRepository;
    private final Icd10Repository icd10Repository;

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
