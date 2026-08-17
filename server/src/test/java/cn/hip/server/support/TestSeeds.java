package cn.hip.server.support;

import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 测试字典自给（1.2.1）：单测曾按中文名硬取 B 类种子药品/项目——实施照
 * docs/种子数据分层清单.md 替换 B 类种子后，`mvn test` 以 `IndexOutOfBoundsException`
 * 之类完全不指向根因的方式全线崩。本类"查无则建"，使通用流程类测试与种子解耦。
 *
 * <p>仍依赖种子**属性**的测试（过敏交叉/抗菌分级/CDSS 规则/医保对照）无法用本类解耦——
 * 它们依赖的是规则表与药品的关联，清单「测试契约」节已标注，替换种子前须同步改规则种子。
 */
@Component
@RequiredArgsConstructor
public class TestSeeds {

    private final DrugItemRepository drugRepository;
    private final ChargeItemRepository chargeItemRepository;
    private final JdbcTemplate jdbc;

    /** 按名称取药品，不存在则建一条测试药（充足库存、普通药、10 元） */
    public DrugItem drug(String nameKeyword) {
        var hit = drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(nameKeyword);
        if (!hit.isEmpty()) {
            return hit.get(0);
        }
        jdbc.update("""
                insert into md_drug(code, name, spec, unit, price, stock, antibiotic, enabled)
                values (?, ?, '测试规格', '盒', 10.00, 100000, false, true)
                on conflict (code) do nothing
                """, "TST-" + Integer.toHexString(nameKeyword.hashCode()), nameKeyword + "(测试自建)");
        return drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(nameKeyword).get(0);
    }

    /** 按名称取收费项目，不存在则建一条（默认 LAB 类、30 元） */
    public ChargeItem chargeItem(String nameKeyword, String category) {
        var hit = chargeItemRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(nameKeyword);
        if (!hit.isEmpty()) {
            return hit.get(0);
        }
        jdbc.update("""
                insert into md_charge_item(code, name, category, unit, price, enabled)
                values (?, ?, ?, '次', 30.00, true)
                on conflict (code) do nothing
                """, "TSC-" + Integer.toHexString(nameKeyword.hashCode()), nameKeyword + "(测试自建)", category);
        return chargeItemRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(nameKeyword).get(0);
    }

    /** 任取一条可用药品（旧写法 `select id from md_drug where enabled limit 1` 的解耦替身） */
    public DrugItem anyDrug() {
        return drug("通用测试药");
    }

    /** 药品当前库存（测试断言扣减用） */
    public int stockOf(Long drugId) {
        Integer n = jdbc.queryForObject("select stock from md_drug where id = ?", Integer.class, drugId);
        return n == null ? 0 : n;
    }

    public static BigDecimal price(DrugItem d) {
        return d.getPrice();
    }
}
