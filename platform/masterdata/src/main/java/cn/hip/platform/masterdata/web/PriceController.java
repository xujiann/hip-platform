package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 三十五期：价格规则维护（调价留痕）——价格属主数据域，自 server 下沉 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class PriceController {

    private final JdbcTemplate jdbc;

    public record PriceReq(BigDecimal newPrice, String reason) {}

    /** 诊疗项目调价：留痕生效 */
    @PutMapping("/api/price/charge-items/{id}")
    @Transactional
    public R<Void> changePrice(@PathVariable Long id, @RequestBody PriceReq req, Authentication auth) {
        if (req.newPrice() == null || req.newPrice().compareTo(BigDecimal.ZERO) < 0) return R.fail(4740, "价格不合法");
        if (req.reason() == null || req.reason().isBlank()) return R.fail(4741, "调价原因必填");
        var rows = jdbc.queryForList("select price from md_charge_item where id = ?", id);
        if (rows.isEmpty()) return R.fail(4742, "项目不存在");
        BigDecimal old = (BigDecimal) rows.get(0).get("price");
        jdbc.update("insert into price_change_log(item_id, old_price, new_price, reason, changed_by) values (?,?,?,?,?)",
                id, old, req.newPrice(), req.reason(), auth.getName());
        jdbc.update("update md_charge_item set price = ? where id = ?", req.newPrice(), id);
        return R.ok();
    }

    @GetMapping("/api/price/change-logs")
    public R<List<Map<String, Object>>> changeLogs() {
        return R.ok(jdbc.queryForList("""
                select l.*, c.code, c.name from price_change_log l
                join md_charge_item c on c.id = l.item_id order by l.id desc limit 100
                """));
    }
}
