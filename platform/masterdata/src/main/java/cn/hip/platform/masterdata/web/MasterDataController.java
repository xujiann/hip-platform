package cn.hip.platform.masterdata.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.entity.Icd10;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.platform.masterdata.repository.Icd10Repository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/masterdata")
@RequiredArgsConstructor
public class MasterDataController {

    private final DrugItemRepository drugRepository;
    private final ChargeItemRepository chargeItemRepository;
    private final Icd10Repository icd10Repository;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final CurrentUserService currentUserService;

    /** 产品化一期：药品目录 CSV 批量导入（实施工具，按 code upsert）
     *  列：code,name,spec,unit,dose_form,price,stock[,antibiotic 0/1][,fee_category_code][,self_pay 0/1]
     *
     *  <p>v42 起可带费用类别与自费标记两列。费用类别走**软校验**（见 importChargeItems 注释）。
     *
     *  <p><b>v43 停用药品的导入口径（同一套行级 errors 纪律）</b>：upsert 命中一条**已停用**的
     *  药品时——
     *  <ol>
     *    <li><b>该行照常导入</b>（名称/规格/价格等照更），<b>但不复活它</b>：
     *        原先 do update 里写死的 {@code enabled = true} 已去掉，改为不动 enabled 列。
     *        批量导入是实施期每周都在跑的动作，让它静默把药师刚停掉的药一次次重新上架，
     *        比不导入危险得多——而且没有任何日志能说明药是怎么活过来的。</li>
     *    <li><b>记一条行级错误</b>进 errors 数组（与"列数不足"、v42"未知费用类别"同一模式），
     *        告诉实施方这行的状态没被覆盖，要复活请走「基础数据 → 药品目录 → 启用」。</li>
     *    <li><b>整批不阻断</b>（本仓既定纪律，v42 费用类别同款）。</li>
     *  </ol>
     *  新增行仍按 enabled=true 落库（insert 分支的字面量未动）。 */
    @PostMapping(value = "/drugs/import", consumes = "text/plain")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Map<String, Object>> importDrugs(@RequestBody String csv) {
        int ok = 0;
        List<String> errors = new ArrayList<>();
        Set<String> categories = enabledCategoryCodes();
        // 一次查全量停用码（同 enabledCategoryCodes 的取舍：逐行查会把批量导入变成 N 次往返）
        Set<String> disabledCodes = new HashSet<>(jdbc.queryForList(
                "select code from md_drug where not enabled", String.class));
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || (i == 0 && line.toLowerCase().startsWith("code"))) continue;
            String[] f = line.split(",", -1);
            if (f.length < 6) {
                errors.add("第" + (i + 1) + "行：列数不足 6");
                continue;
            }
            String cat = softCategory(field(f, 8), categories, i + 1, errors);
            Boolean selfPay = flag(field(f, 9));
            if (disabledCodes.contains(f[0].strip())) {
                errors.add("第" + (i + 1) + "行：药品「" + f[0].strip() + "」当前为停用状态，"
                        + "本次导入已更新其名称/规格/价格等资料，但**未改变停用状态**"
                        + "（如需恢复，请在「基础数据 → 药品目录」中显式启用）");
            }
            try {
                jdbc.update("""
                        insert into md_drug(code, name, spec, unit, dose_form, price, stock, antibiotic,
                                            fee_category_code, self_pay, enabled)
                        values (?,?,?,?,?,?::numeric,?::int,?,?::varchar,coalesce(?::boolean, false),true)
                        on conflict (code) do update set name = excluded.name, spec = excluded.spec,
                            unit = excluded.unit, dose_form = excluded.dose_form, price = excluded.price,
                            antibiotic = excluded.antibiotic,
                            fee_category_code = coalesce(?::varchar, md_drug.fee_category_code),
                            self_pay = coalesce(?::boolean, md_drug.self_pay)
                        """, f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(), f[4].strip(),
                        f[5].strip(), f.length > 6 && !f[6].strip().isEmpty() ? f[6].strip() : "0",
                        f.length > 7 && "1".equals(f[7].strip()),
                        cat, selfPay, cat, selfPay);
                ok++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行：" + e.getMessage().split("\n")[0]);
            }
        }
        return R.ok(Map.of("imported", ok, "errorCount", errors.size(), "errors", errors));
    }

    /**
     * 产品化一期：收费项目 CSV 批量导入。
     * 列：code,name,category,unit,price[,fee_category_code][,self_pay 0/1]
     *
     * <p><b>v42 费用类别软校验（刻意不做成硬失败）</b>：CSV 批量导入是实施期落数主手段，
     * 院方真实收费目录的类别值远超现有 4 种。若给 category 加数据库 CHECK 白名单、
     * 或给 fee_category_code 加外键，一条脏值就会让**整批**导入在数据库层失败，
     * 且报错是约束名而非可操作的行号。故：未知/已停用的费用类别码 → 记一条**行级**错误进
     * errors 数组（与"列数不足"同一模式，MasterDataController 自产品化一期起即如此），
     * 该行照常导入、只是不挂类（报表里进"未分类"行，不会凭空消失），整批不阻断。
     */
    @PostMapping(value = "/charge-items/import", consumes = "text/plain")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Map<String, Object>> importChargeItems(@RequestBody String csv) {
        int ok = 0;
        List<String> errors = new ArrayList<>();
        Set<String> categories = enabledCategoryCodes();
        String[] lines = csv.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || (i == 0 && line.toLowerCase().startsWith("code"))) continue;
            String[] f = line.split(",", -1);
            if (f.length < 5) {
                errors.add("第" + (i + 1) + "行：列数不足 5");
                continue;
            }
            String cat = softCategory(field(f, 5), categories, i + 1, errors);
            Boolean selfPay = flag(field(f, 6));
            try {
                jdbc.update("""
                        insert into md_charge_item(code, name, category, unit, price,
                                                   fee_category_code, self_pay, enabled)
                        values (?,?,?,?,?::numeric,?::varchar,coalesce(?::boolean, false),true)
                        on conflict (code) do update set name = excluded.name, category = excluded.category,
                            unit = excluded.unit, price = excluded.price,
                            fee_category_code = coalesce(?::varchar, md_charge_item.fee_category_code),
                            self_pay = coalesce(?::boolean, md_charge_item.self_pay),
                            enabled = true
                        """, f[0].strip(), f[1].strip(), f[2].strip(), f[3].strip(), f[4].strip(),
                        cat, selfPay, cat, selfPay);
                ok++;
            } catch (Exception e) {
                errors.add("第" + (i + 1) + "行：" + e.getMessage().split("\n")[0]);
            }
        }
        return R.ok(Map.of("imported", ok, "errorCount", errors.size(), "errors", errors));
    }

    /**
     * 药品检索（top 20）。
     *
     * <p><b>v43：默认行为逐字节不变——不带参数时仍只返回启用中的药品。</b>
     * 这不是保守，是必需：本端点是**开单侧药品选择器**的唯一数据源
     * （DoctorStationView:367 门诊医生站、InpDoctorView:476 住院医生站，另有 InventoryView /
     * StockTakeView 两处药库页与 15 个 E2E 脚本在用）。若把默认改成"返回全部"，
     * 停用药品会立刻回到医生的下拉框里，医生选中→开单→撞 8016，
     * 等于用一个"能查到但开不了"的选项去惩罚使用者，与本车道的目的正相反。
     *
     * <p>维护页（DrugsView）需要看到停用药品，走显式参数——与本类
     * {@link #feeCategories(boolean)} 的 {@code all=true} 同一套既定约定：
     * <ul>
     *   <li>不带参数：仅启用（历史默认，走原仓储方法，一行未改）</li>
     *   <li>{@code all=true}：全部（维护页默认视图）</li>
     *   <li>{@code enabled=true|false}：仅启用 / <b>仅停用</b>（维护页状态筛选）</li>
     * </ul>
     *
     * @param all     true 时返回全部状态（enabled 未指定时生效）
     * @param enabled 显式状态筛选，优先于 all；null 表示不按状态筛
     */
    @GetMapping("/drugs")
    public R<List<DrugItem>> drugs(@RequestParam(defaultValue = "") String keyword,
                                   @RequestParam(defaultValue = "false") boolean all,
                                   @RequestParam(required = false) Boolean enabled) {
        if (enabled == null && !all) {
            return R.ok(drugRepository.findTop20ByEnabledTrueAndNameContainingOrderByCode(keyword));
        }
        String jpql = "select d from DrugItem d where d.name like :kw"
                + (enabled == null ? "" : " and d.enabled = :en") + " order by d.code";
        var q = entityManager.createQuery(jpql, DrugItem.class)
                .setParameter("kw", "%" + keyword + "%")
                .setMaxResults(20);
        if (enabled != null) {
            q.setParameter("en", enabled);
        }
        return R.ok(q.getResultList());
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

    // ==================== v43 药品启用/停用（V134，错误码 8013–8016） ====================

    /** 停用入参：reason 必填（8015） */
    public record DrugDisableReq(String reason) {}

    /**
     * 停用药品（偏离表 1162★）。enabled=false + 落停用原因/时间/人三列留痕。
     *
     * <p><b>并发口径</b>：走**条件更新 + 受影响行数**判定"是否已停用"，不做读-判-写——
     * 两个药师同时点停用时，读-判-写会双双通过、后写者覆盖前写者的原因与时间
     * （同 {@link #createFeeCategory} 的判定方式，本仓既定纪律）。
     *
     * <p><b>停用是可逆的软状态</b>：既不删行、不动 stock、也不碰任何在途医嘱/处方——
     * 已开出但未发药的处方照常执行（药已经在架上，拒发只会把患者堵在药房窗口）；
     * 停用只作用于**新开单**（8016）。
     *
     * <p>权限：ADMIN + PHARMACIST。药品目录菜单 /masterdata/drugs 自 V36:29-30 起本就
     * 授权给药师，停用/启用是药事管理动作，把它锁死在 ADMIN 会让真正的责任人做不了。
     */
    @PutMapping("/drugs/{id}/disable")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    @Transactional
    public R<Void> disableDrug(@PathVariable Long id,
                               @RequestBody(required = false) DrugDisableReq req,
                               Authentication auth) {
        var rows = jdbc.queryForList("select name from md_drug where id = ?", id);
        if (rows.isEmpty()) {
            return R.fail(8013, "药品不存在");
        }
        String name = String.valueOf(rows.get(0).get("name"));
        String reason = nullIfBlank(req == null ? null : req.reason());
        if (reason == null) {
            // 停用原因是本条诚信补齐的核心：没有原因的停用等于「有列没功能」换个地方复现——
            // 事后没人说得清这药是招标掉标、是效期召回、还是临床暂停使用。
            return R.fail(8015, "停用原因必填");
        }
        if (reason.length() > 200) {
            reason = reason.substring(0, 200);   // 列宽 200，截断而非报错（原因是备注不是主键）
        }
        int n = jdbc.update("""
                update md_drug set enabled = false, disable_reason = ?, disabled_at = now(), disabled_by = ?
                where id = ? and enabled
                """, reason, uidOf(auth), id);
        return n == 0 ? R.fail(8014, "药品「" + name + "」已是停用状态，无需重复停用") : R.ok();
    }

    /** 取消停用。留痕三列一并清空——留着旧原因会让下次停用前的展示自相矛盾（已启用却显示停用原因）。 */
    @PutMapping("/drugs/{id}/enable")
    @PreAuthorize("hasAnyRole('ADMIN','PHARMACIST')")
    @Transactional
    public R<Void> enableDrug(@PathVariable Long id) {
        int n = jdbc.update("""
                update md_drug set enabled = true, disable_reason = null, disabled_at = null, disabled_by = null
                where id = ? and not enabled
                """, id);
        if (n > 0) {
            return R.ok();
        }
        var rows = jdbc.queryForList("select name from md_drug where id = ?", id);
        return rows.isEmpty()
                ? R.fail(8013, "药品不存在")
                : R.fail(8014, "药品「" + rows.get(0).get("name") + "」已是启用状态，无需重复启用");
    }

    /** 登录态用户 id；测试/内部调用可能无 Authentication，此时留痕人为 null（列可空） */
    private Long uidOf(Authentication auth) {
        return auth == null ? null : currentUserService.idOf(auth);
    }

    // ==================== v42 费用类别字典（md_fee_category，错误码 4860–4864） ====================

    /**
     * @param stdCode   国标/医保费用类别码——**本仓不预置任何码值**（医保"15 大类"等码表随各地
     *                  医保局版本走，本仓无权威来源，自造即为伪造；唯一消费场景是医保结算清单与
     *                  病案首页国标上报两条诚信红线）。本字段只是承载列，由实施期院方填入。
     * @param stdSystem 码表来源标识（如省医保局版本号），同上留空。
     */
    public record FeeCategoryReq(String code, String name, Integer sortNo, Boolean enabled,
                                 String stdCode, String stdSystem) {}

    /** 项目/药品挂类与自费标记维护入参 */
    public record ItemAttrReq(String feeCategoryCode, Boolean selfPay) {}

    /**
     * 费用类别字典。默认只返回启用项（挂类下拉直接用）；all=true 返回全部（维护页用）。
     * 读侧不限角色：医生站/收费/报表都要拿它翻译类别名，限权会让普通角色满屏 403。
     */
    @GetMapping("/fee-categories")
    public R<List<Map<String, Object>>> feeCategories(@RequestParam(defaultValue = "false") boolean all) {
        // item_count 随行返回：维护页要据此判断能否停用（4863 守卫的同一口径），
        // 前端若改用 /charge-items + /drugs 自己数会数错——那两个端点是 top20 检索接口。
        String sql = """
                select fc.*,
                       (select count(*) from md_charge_item ci where ci.fee_category_code = fc.code)
                     + (select count(*) from md_drug        d  where d.fee_category_code  = fc.code) as item_count
                from md_fee_category fc
                %s
                order by fc.sort_no, fc.code
                """.formatted(all ? "" : "where fc.enabled");
        return R.ok(jdbc.queryForList(sql));
    }

    /** 新建费用类别。码重复走**数据库唯一约束 + 受影响行数**判定，不做读-判-写（并发下会双插） */
    @PostMapping("/fee-categories")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> createFeeCategory(@RequestBody FeeCategoryReq req) {
        String code = trim(req.code());
        String name = trim(req.name());
        if (code.isEmpty() || name.isEmpty()) {
            return R.fail(4862, "费用类别码与名称必填");
        }
        int n = jdbc.update("""
                insert into md_fee_category(code, name, sort_no, enabled, std_code, std_system)
                values (?,?,?,?,?,?) on conflict (code) do nothing
                """, code, name, req.sortNo() == null ? 0 : req.sortNo(),
                req.enabled() == null || req.enabled(), nullIfBlank(req.stdCode()), nullIfBlank(req.stdSystem()));
        return n == 0 ? R.fail(4860, "费用类别码已存在：" + code) : R.ok();
    }

    /**
     * 修改费用类别。code 不可改（已被 md_charge_item/md_drug 按码挂靠，改码等于批量断链）。
     * <p><b>停用守卫（4863）</b>：仍有项目/药品挂靠时不允许停用——否则这些项目的费用会在
     * 报表的"未分类"与原类别之间漂移，且挂靠关系还在库里、肉眼查不出原因。
     */
    @PutMapping("/fee-categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> updateFeeCategory(@PathVariable Long id, @RequestBody FeeCategoryReq req) {
        String name = trim(req.name());
        if (name.isEmpty()) {
            return R.fail(4862, "费用类别码与名称必填");
        }
        var rows = jdbc.queryForList("select code, enabled from md_fee_category where id = ?", id);
        if (rows.isEmpty()) {
            return R.fail(4861, "费用类别不存在或已停用");
        }
        String code = String.valueOf(rows.get(0).get("code"));
        boolean wasEnabled = Boolean.TRUE.equals(rows.get(0).get("enabled"));
        boolean toEnabled = req.enabled() == null || req.enabled();
        if (wasEnabled && !toEnabled) {
            long refs = refCount(code);
            if (refs > 0) {
                return R.fail(4863, "费用类别「" + code + "」下仍有 " + refs + " 个项目/药品挂靠，请先改挂其他类别");
            }
        }
        jdbc.update("""
                update md_fee_category set name = ?, sort_no = ?, enabled = ?, std_code = ?, std_system = ?
                where id = ?
                """, name, req.sortNo() == null ? 0 : req.sortNo(), toEnabled,
                nullIfBlank(req.stdCode()), nullIfBlank(req.stdSystem()), id);
        return R.ok();
    }

    /** 删除费用类别：同样受挂靠守卫（4863）——删掉比停用更彻底，挂靠列会变成查无此码的孤儿值 */
    @DeleteMapping("/fee-categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> deleteFeeCategory(@PathVariable Long id) {
        var rows = jdbc.queryForList("select code from md_fee_category where id = ?", id);
        if (rows.isEmpty()) {
            return R.fail(4861, "费用类别不存在或已停用");
        }
        String code = String.valueOf(rows.get(0).get("code"));
        long refs = refCount(code);
        if (refs > 0) {
            return R.fail(4863, "费用类别「" + code + "」下仍有 " + refs + " 个项目/药品挂靠，请先改挂其他类别");
        }
        jdbc.update("delete from md_fee_category where id = ?", id);
        return R.ok();
    }

    /** 收费项目挂类 + 自费标记维护 */
    @PutMapping("/charge-items/{id}/attrs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> updateChargeItemAttrs(@PathVariable Long id, @RequestBody ItemAttrReq req) {
        return updateItemAttrs("md_charge_item", id, req);
    }

    /** 药品挂类 + 自费标记维护 */
    @PutMapping("/drugs/{id}/attrs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> updateDrugAttrs(@PathVariable Long id, @RequestBody ItemAttrReq req) {
        return updateItemAttrs("md_drug", id, req);
    }

    /**
     * 挂类/自费标记落库。
     * <p>语义：feeCategoryCode 为**整体替换**（传空 = 取消挂类）；selfPay 为 null 时保持原值，
     * 便于只改其一的调用方。table 只来自本类两个固定字面量，无注入面。
     *
     * <p>selfPay 补的是 V116 的孤儿列：该列建了一年，实体无字段、CSV 不写、前端无入口，
     * 真实数据恒为 false，使 InpatientService.isSelfPayItem 恒返回 false、
     * gate `emr.gate.consent.selfpay` 即使调成 block 也拦不住任何东西。
     */
    private R<Void> updateItemAttrs(String table, Long id, ItemAttrReq req) {
        String cat = nullIfBlank(req.feeCategoryCode());
        if (cat != null && !enabledCategoryCodes().contains(cat)) {
            return R.fail(4861, "费用类别不存在或已停用：" + cat);
        }
        int n = jdbc.update("update " + table
                + " set fee_category_code = ?::varchar, self_pay = coalesce(?::boolean, self_pay) where id = ?",
                cat, req.selfPay(), id);
        return n == 0 ? R.fail(4864, "项目或药品不存在") : R.ok();
    }

    /** 当前启用的费用类别码集合（一次导入只查一次，逐行查会把批量导入变成 N 次往返） */
    private Set<String> enabledCategoryCodes() {
        return new HashSet<>(jdbc.queryForList(
                "select code from md_fee_category where enabled", String.class));
    }

    /** 该费用类别下挂靠的项目 + 药品数（停用/删除守卫用） */
    private long refCount(String code) {
        Long n = jdbc.queryForObject("""
                select (select count(*) from md_charge_item where fee_category_code = ?)
                     + (select count(*) from md_drug        where fee_category_code = ?)
                """, Long.class, code, code);
        return n == null ? 0 : n;
    }

    /** CSV 软校验：未知/已停用类别 → 行级错误 + 该行不挂类，**不抛异常、不阻断整批** */
    private static String softCategory(String raw, Set<String> known, int lineNo, List<String> errors) {
        String v = nullIfBlank(raw);
        if (v == null) return null;
        if (known.contains(v)) return v;
        errors.add("第" + lineNo + "行：费用类别码「" + v + "」不在字典中或已停用，该行已导入但未挂类"
                + "（请先在「基础数据 → 费用类别」登记后重新导入本行）");
        return null;
    }

    /** CSV 第 idx 列（越界/空 → null）；0/1 标志列解析（空 = 未提供 = 保持原值） */
    private static String field(String[] f, int idx) {
        return idx < f.length ? f[idx] : null;
    }

    private static Boolean flag(String raw) {
        String v = nullIfBlank(raw);
        return v == null ? null : "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
