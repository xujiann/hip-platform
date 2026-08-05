package cn.hip.server;

import cn.hip.hrp.web.PurchaseDocController;
import cn.hip.hrp.web.PurchaseDocController.DocReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** 三十五期：采购单据链状态机（草稿→审核→验收补发票；作废↔还原） */
@SpringBootTest
@Transactional
class PurchaseDocFlowTest {

    @Autowired PurchaseDocController controller;
    @Autowired JdbcTemplate jdbc;

    private Long supplierId;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into hrp_supplier(name, contact, phone, scope) values ('单元测试供应商','t','1','测试')");
        supplierId = jdbc.queryForObject("select max(id) from hrp_supplier", Long.class);
    }

    @Test
    void stockDocLifecycleWithInvoiceGuard() {
        var docNo = (String) controller.create(
                new DocReq("STOCK", supplierId, "测试品目", new BigDecimal("100"), null)).getData().get("docNo");
        // 未审核不能验收
        assertEquals(4693, controller.receive(docNo, "FP1").getCode());
        assertEquals(0, controller.approve(docNo).getCode());
        assertEquals(0, controller.receive(docNo, "FP1").getCode());
        // 已验收仍可调整发票
        assertEquals(0, controller.receive(docNo, "FP2").getCode());
        String invoice = jdbc.queryForObject("select invoice_no from pur_doc where doc_no = ?", String.class, docNo);
        assertEquals("FP2", invoice);
        // 已验收不可作废
        assertEquals(4694, controller.cancel(docNo).getCode());
    }

    @Test
    void cancelAndRestoreOnlyFromDraft() {
        var docNo = (String) controller.create(
                new DocReq("RETURN", supplierId, "退货", new BigDecimal("10"), null)).getData().get("docNo");
        assertEquals(0, controller.cancel(docNo).getCode());
        // 已作废不能再验收
        assertEquals(4693, controller.receive(docNo, "x").getCode());
        assertEquals(0, controller.restore(docNo).getCode());
        String status = jdbc.queryForObject("select status from pur_doc where doc_no = ?", String.class, docNo);
        assertEquals("DRAFT", status);
    }

    @Test
    void invalidTypeAndSupplierRejected() {
        assertEquals(4690, controller.create(new DocReq("X", supplierId, "a", BigDecimal.ONE, null)).getCode());
        assertEquals(4691, controller.create(new DocReq("STOCK", -1L, "a", BigDecimal.ONE, null)).getCode());
    }
}
