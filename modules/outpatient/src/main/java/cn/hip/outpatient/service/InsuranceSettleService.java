package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.OutpCharge;
import cn.hip.outpatient.entity.OutpOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/** 二十七期：医保费用分割（甲乙丙分类→统筹/个人）与智能审核规则雏形，医保结算时自动执行 */
@Service
@RequiredArgsConstructor
public class InsuranceSettleService {

    private final JdbcTemplate jdbc;

    /** 医保分割：按目录对照分类计费，统筹比例走 sys_config，未对照项目按丙类自费 */
    public void splitAndAudit(OutpCharge charge, List<OutpOrder> orders) {
        String insuranceType = jdbc.queryForObject("""
                select coalesce(p.insurance_type, 'SELF') from outp_registration r
                join empi_patient p on p.id = r.patient_id where r.id = ?
                """, String.class, charge.getRegistrationId());
        BigDecimal ratio = fundRatio(insuranceType);

        BigDecimal a = BigDecimal.ZERO, b = BigDecimal.ZERO, c = BigDecimal.ZERO, fund = BigDecimal.ZERO;
        StringJoiner detail = new StringJoiner(",", "[", "]");
        for (OutpOrder o : orders) {
            String itemType = "DRUG".equals(o.getOrderType()) ? "DRUG" : "ITEM";
            var maps = jdbc.queryForList(
                    "select charge_class, self_ratio, yb_code from yb_catalog_map where item_type = ? and item_code = ?",
                    itemType, o.getItemCode());
            String clazz = maps.isEmpty() ? "C" : (String) maps.get(0).get("charge_class");
            BigDecimal selfRatio = maps.isEmpty() ? BigDecimal.ZERO : (BigDecimal) maps.get(0).get("self_ratio");
            BigDecimal amount = o.getAmount();
            BigDecimal eligible = switch (clazz) {
                case "A" -> amount;
                case "B" -> amount.multiply(BigDecimal.ONE.subtract(selfRatio));
                default -> BigDecimal.ZERO;
            };
            BigDecimal lineFund = eligible.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            switch (clazz) {
                case "A" -> a = a.add(amount);
                case "B" -> b = b.add(amount);
                default -> c = c.add(amount);
            }
            fund = fund.add(lineFund);
            detail.add("{\"item\":\"%s\",\"class\":\"%s\",\"amount\":%s,\"fund\":%s}"
                    .formatted(o.getItemName(), clazz, amount, lineFund));
        }
        BigDecimal total = charge.getTotalAmount();
        BigDecimal self = total.subtract(fund);

        jdbc.update("""
                insert into yb_settle_split(charge_no, biz_type, insurance_type, total,
                                            class_a, class_b, class_c, fund_pay, self_pay, detail)
                values (?,'OUTP',?,?,?,?,?,?,?,?)
                on conflict (charge_no) do nothing
                """, charge.getChargeNo(), insuranceType, total, a, b, c, fund, self, detail.toString());

        audit(charge.getChargeNo(), orders, c, total);
    }

    private BigDecimal fundRatio(String insuranceType) {
        String key = switch (insuranceType) {
            case "YB_STAFF", "YB_EMPLOYEE" -> "yb_ratio_staff";
            case "YB_RESIDENT" -> "yb_ratio_resident";
            default -> null;
        };
        if (key == null) return BigDecimal.ZERO;
        var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = ?", String.class, key);
        return rows.isEmpty() ? BigDecimal.ZERO : new BigDecimal(rows.get(0));
    }

    /** 审核规则雏形（本地规则；真实智能审核规则库接入后替换数据源）：
     *  R001 单品种数量超限提醒；R002 同单重复项目提醒；R003 自费(丙类)占比过高提醒 */
    private void audit(String chargeNo, List<OutpOrder> orders, BigDecimal classC, BigDecimal total) {
        for (OutpOrder o : orders) {
            if ("DRUG".equals(o.getOrderType()) && o.getQty() != null && o.getQty() > 5) {
                warn(chargeNo, "R001", "%s 数量 %d 超过常规上限 5，请核实是否超量开药"
                        .formatted(o.getItemName(), o.getQty()));
            }
        }
        Map<String, Long> nameCount = orders.stream()
                .collect(Collectors.groupingBy(OutpOrder::getItemName, Collectors.counting()));
        nameCount.forEach((name, cnt) -> {
            if (cnt > 1) warn(chargeNo, "R002", "项目「%s」同单出现 %d 次，请核实重复收费".formatted(name, cnt));
        });
        if (total.compareTo(BigDecimal.ZERO) > 0
                && classC.divide(total, 4, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.5")) > 0) {
            warn(chargeNo, "R003", "自费(丙类)金额占比超 50%%（¥%s / ¥%s），请与参保人确认".formatted(classC, total));
        }
    }

    private void warn(String chargeNo, String rule, String message) {
        jdbc.update("insert into yb_audit_log(charge_no, rule_code, level, message) values (?,?,'WARN',?)",
                chargeNo, rule, message);
    }
}
