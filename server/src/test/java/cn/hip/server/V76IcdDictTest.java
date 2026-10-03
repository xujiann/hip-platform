package cn.hip.server;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.masterdata.entity.Icd10;
import cn.hip.platform.masterdata.web.IcdDictController;
import cn.hip.platform.masterdata.web.IcdDictController.IcdReq;
import cn.hip.platform.masterdata.web.MasterDataController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v76 车道 A：诊断字典（md_icd10）独立维护——分页检索 / 新增 / 编辑 / 启停 / CSV 导入（993★ ①）。
 *
 * <p>全部 {@code @Transactional}：本用例只断言落库值与返回码，不测回滚语义（方法论④）。
 * 唯一与回滚有关的断言是"CSV 格式错整批零落库"——它成立靠的是**先校验后写入**
 * （校验阶段没有任何写操作），而不是靠事务回滚；在事务测试里回滚本就不会发生，
 * 所以若实现是"边写边校验"，本用例照样会看到泄漏的行而变红，不是假绿。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V76IcdDictTest {

    @Autowired IcdDictController icdDict;
    @Autowired MasterDataController masterData;
    @Autowired JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(R<Map<String, Object>> r) {
        assertEquals(0, r.getCode());
        return (List<Map<String, Object>>) r.getData().get("records");
    }

    private List<String> codes(R<Map<String, Object>> r) {
        return records(r).stream().map(m -> String.valueOf(m.get("code"))).toList();
    }

    private int count(String code) {
        return jdbc.queryForObject("select count(*) from md_icd10 where code = ?", Integer.class, code);
    }

    private boolean enabledOf(String code) {
        return jdbc.queryForObject("select enabled from md_icd10 where code = ?", Boolean.class, code);
    }

    @Test
    void pageHidesDisabledByDefaultAndShowsThemWhenAsked() {
        icdDict.create(new IcdReq("T76A01", "用例诊断甲", "YLZDJ"));
        icdDict.create(new IcdReq("T76A02", "用例诊断乙", "YLZDY"));
        icdDict.setEnabled("T76A02", false);

        R<Map<String, Object>> def = icdDict.page("用例诊断", false, 1, 20);
        assertEquals(List.of("T76A01"), codes(def), "默认不含停用行");
        assertEquals(1L, ((Number) def.getData().get("total")).longValue());

        R<Map<String, Object>> all = icdDict.page("用例诊断", true, 1, 20);
        assertEquals(List.of("T76A01", "T76A02"), codes(all), "includeDisabled=true 含停用行");
        Map<String, Object> disabled = records(all).get(1);
        assertEquals(Boolean.FALSE, disabled.get("enabled"));
        assertEquals("YLZDY", disabled.get("pinyin"));
        assertNotNull(disabled.get("updatedAt"));
        assertEquals(Set.of("code", "name", "pinyin", "enabled", "updatedAt"), disabled.keySet(),
                "返回键固定：code,name,pinyin,enabled,updatedAt");
    }

    @Test
    void pageSearchesByCodePrefixNameAndPinyinAndPaginates() {
        for (int i = 1; i <= 5; i++) {
            icdDict.create(new IcdReq("T76B0" + i, "分页用例病" + i, "FYYLB"));
        }
        assertEquals(List.of("T76B01", "T76B02"), codes(icdDict.page("t76b", false, 1, 2)), "编码前缀不分大小写");
        assertEquals(List.of("T76B03", "T76B04"), codes(icdDict.page("t76b", false, 2, 2)));
        assertEquals(List.of("T76B05"), codes(icdDict.page("T76B", false, 3, 2)));
        assertEquals(5L, ((Number) icdDict.page("T76B", false, 1, 2).getData().get("total")).longValue(),
                "total 是命中总数，不是本页条数");
        assertEquals(5, codes(icdDict.page("分页用例", false, 1, 20)).size(), "名称包含匹配");
        assertEquals(5, codes(icdDict.page("fyylb", false, 1, 20)).size(), "拼音前缀不分大小写");
        assertEquals(List.of(), codes(icdDict.page("YYLB", false, 1, 20)), "拼音是前缀匹配，不是包含匹配");
    }

    @Test
    void pageKeywordWildcardsAreLiteral() {
        icdDict.create(new IcdReq("T76C01", "百分号用例", "BFHYL"));
        assertEquals(List.of(), codes(icdDict.page("%", false, 1, 20)), "% 不是通配符");
        assertEquals(List.of(), codes(icdDict.page("_", false, 1, 20)), "_ 不是通配符");
    }

    @Test
    void createDuplicateCodeIs5900() {
        icdDict.create(new IcdReq("T76D01", "重复码用例", "CFMYL"));
        HipBizException ex = assertThrows(HipBizException.class,
                () -> icdDict.create(new IcdReq("t76d01", "换个名字", "HGMZ")));
        assertEquals(5900, ex.code, "大小写不同也算同一个编码");
        assertEquals("重复码用例", jdbc.queryForObject("select name from md_icd10 where code = 'T76D01'", String.class),
                "重复新增不得覆盖原行");
        // 种子里的 J06.900 同样挡住
        assertEquals(5900, assertThrows(HipBizException.class,
                () -> icdDict.create(new IcdReq("J06.900", "x", "X"))).code);
    }

    @Test
    void editChangesNameAndPinyinButNotEnabledOrCode() {
        icdDict.create(new IcdReq("T76E01", "改前名", "GQM"));
        icdDict.setEnabled("T76E01", false);
        assertEquals(0, icdDict.update("T76E01", new IcdReq(null, "改后名", "GHM")).getCode());
        assertEquals("改后名", jdbc.queryForObject("select name from md_icd10 where code='T76E01'", String.class));
        assertEquals("GHM", jdbc.queryForObject("select pinyin from md_icd10 where code='T76E01'", String.class));
        assertFalse(enabledOf("T76E01"), "编辑不得顺手改启停状态");
        assertEquals(5902, assertThrows(HipBizException.class,
                () -> icdDict.update("T76NOPE", new IcdReq(null, "x", "X"))).code, "编辑不存在的码");
        assertEquals(4000, assertThrows(HipBizException.class,
                () -> icdDict.update("T76E01", new IcdReq(null, " ", "X"))).code, "名称不得为空");
    }

    @Test
    void setEnabledOnMissingCodeIs5902() {
        assertEquals(5902, assertThrows(HipBizException.class,
                () -> icdDict.setEnabled("T76NOPE", false)).code);
        icdDict.create(new IcdReq("T76F01", "启停用例", "QTYL"));
        assertEquals(0, icdDict.setEnabled("T76F01", false).getCode());
        assertFalse(enabledOf("T76F01"));
        assertEquals(0, icdDict.setEnabled("T76F01", true).getCode());
        assertTrue(enabledOf("T76F01"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void importWithBadRowsIs5901AndWritesNothing() {
        int before = jdbc.queryForObject("select count(*) from md_icd10", Integer.class);
        String csv = String.join("\n",
                "code,name,pinyin",
                "T76G01,合法行一,HFHY",
                "T76G02,列数不足",
                "T76G03,拼音含汉字,拼音",
                ",编码为空,BMWK",
                "T76G01,文件内重复,WJNCF",
                "T76G05,合法行二,HFHE");
        R<Map<String, Object>> r = icdDict.importCsv(csv);
        assertEquals(5901, r.getCode());
        List<Map<String, Object>> errors = (List<Map<String, Object>>) r.getData().get("errors");
        assertEquals(List.of(3, 4, 5, 6), errors.stream().map(e -> ((Number) e.get("line")).intValue()).toList(),
                "行号是文件行号（含表头），逐条汇总、不止报第一条");
        errors.forEach(e -> assertFalse(String.valueOf(e.get("reason")).isBlank()));
        assertEquals(0, count("T76G01"), "合法行也不落库：整批拒绝");
        assertEquals(0, count("T76G05"));
        assertEquals(before, (int) jdbc.queryForObject("select count(*) from md_icd10", Integer.class));
    }

    @Test
    void importUpsertsAndNeverResurrectsDisabledRows() {
        icdDict.create(new IcdReq("T76H01", "旧名", "JM"));
        icdDict.create(new IcdReq("T76H02", "停用旧名", "TYJM"));
        icdDict.setEnabled("T76H02", false);

        // 无表头、带 BOM、含 Windows 换行、行首尾空白、空行——实施期 CSV 的真实样子
        String csv = "﻿T76H01,新名,XM\r\nT76H02 ,停用新名,TYXM\r\n t76h03,全新行,QXH\r\n\r\n";
        R<Map<String, Object>> r = icdDict.importCsv(csv);
        assertEquals(0, r.getCode());
        assertEquals(3, ((Number) r.getData().get("imported")).intValue());
        assertEquals(1, ((Number) r.getData().get("created")).intValue());
        assertEquals(2, ((Number) r.getData().get("updated")).intValue());
        assertEquals(1, ((Number) r.getData().get("disabledKept")).intValue(), "被保持停用的行数要告诉导入者");

        assertEquals("新名", jdbc.queryForObject("select name from md_icd10 where code='T76H01'", String.class));
        assertTrue(enabledOf("T76H01"));
        assertEquals("停用新名", jdbc.queryForObject("select name from md_icd10 where code='T76H02'", String.class),
                "停用行的名称/拼音照更");
        assertFalse(enabledOf("T76H02"), "导入不得复活已停用行（与 v43 药品导入同口径）");
        assertEquals(1, count("T76H03"), "编码统一转大写落库");
        assertTrue(enabledOf("T76H03"), "新增行默认启用");
    }

    @Test
    void doctorStationSearchExcludesDisabledAndKeepsBodyKeys() {
        icdDict.create(new IcdReq("T76J01", "医生站检索用例甲", "YSZJS"));
        icdDict.create(new IcdReq("T76J02", "医生站检索用例乙", "YSZJS"));
        icdDict.setEnabled("T76J02", false);

        List<Icd10> hits = masterData.icd10("医生站检索用例").getData();
        assertEquals(List.of("T76J01"), hits.stream().map(Icd10::getCode).toList(), "停用行不进医生站下拉");
        // 种子行仍可检索（回归：加 enabled 过滤不得误伤既有 20 行）
        assertTrue(masterData.icd10("J06.900").getData().stream().anyMatch(i -> "J06.900".equals(i.getCode())));

        // 返回体键名不变：序列化后仍只有 code/name/pinyin（enabled 不外泄）
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(hits.get(0));
        var keys = new TreeSet<String>();
        json.fieldNames().forEachRemaining(keys::add);
        assertEquals(new TreeSet<>(List.of("code", "name", "pinyin")), keys);
    }

    @Test
    @WithMockUser(roles = "DOCTOR_OUTP")
    void writeEndpointsAreAdminOnlyButSearchIsOpenToLoggedIn() {
        assertThrows(AccessDeniedException.class, () -> icdDict.create(new IcdReq("T76K01", "x", "X")));
        assertThrows(AccessDeniedException.class, () -> icdDict.update("J06.900", new IcdReq(null, "x", "X")));
        assertThrows(AccessDeniedException.class, () -> icdDict.setEnabled("J06.900", false));
        assertThrows(AccessDeniedException.class, () -> icdDict.importCsv("T76K01,x,X"));
        assertEquals(0, icdDict.page("", false, 1, 5).getCode(), "检索与既有 /icd10 一样，登录即可");
    }

    /** 第三轮反驳者二/三：GBK/ANSI 文件按 UTF-8 解码出替换符，此前能把乱码名称"成功"导入；现整批 5901 */
    @Test
    void nonUtf8CsvRejectedAsWholeBatch() {
        String garbled = "code,name,pinyin\nR76GBK1,\uFFFD\uFFFD\uFFFD,CPHY\n";
        var r = icdDict.importCsv(garbled);
        assertEquals(5901, r.getCode());
        assertEquals(0, jdbc.queryForObject("select count(*) from md_icd10 where code = 'R76GBK1'", Integer.class));
    }

    /** 第三轮反驳者三：医生站检索小写编码搜不到（维护页已大写化、医生站没有） */
    @Test
    void doctorStationSearchIsCaseInsensitiveOnCode() {
        icdDict.importCsv("code,name,pinyin\nR76LC1,复核小写检索,FHXXJS\n");
        var hit = masterData.icd10("r76lc").getData();
        assertTrue(hit.stream().anyMatch(i -> "R76LC1".equals(i.getCode())), "小写编码关键字须命中");
        var hit2 = masterData.icd10("fhxx").getData();
        assertTrue(hit2.stream().anyMatch(i -> "R76LC1".equals(i.getCode())), "小写拼音关键字须命中");
    }
}
