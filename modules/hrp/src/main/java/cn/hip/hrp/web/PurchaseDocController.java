package cn.hip.hrp.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 三十二期：采购单据链（备货/退货，审核→验收补发票→作废还原）+ 供应商证照 + 制度文件库 */
@RestController
@RequiredArgsConstructor
public class PurchaseDocController {

    private final JdbcTemplate jdbc;

    // ---- 采购单据 ----
    public record DocReq(String docType, Long supplierId, String items, BigDecimal amount, String remark) {}

    @PostMapping("/api/purchase/docs")
    public R<Map<String, Object>> create(@RequestBody DocReq req) {
        if (!"STOCK".equals(req.docType()) && !"RETURN".equals(req.docType())) return R.fail(4690, "单据类型只能为 STOCK/RETURN");
        Integer sup = jdbc.queryForObject("select count(*) from hrp_supplier where id = ?", Integer.class, req.supplierId());
        if (sup == null || sup == 0) return R.fail(4691, "供应商不存在");
        String docNo = ("STOCK".equals(req.docType()) ? "BH" : "TH") + System.currentTimeMillis() % 1000000000L;
        jdbc.update("""
                insert into pur_doc(doc_no, doc_type, supplier_id, items, amount, remark) values (?,?,?,?,?,?)
                """, docNo, req.docType(), req.supplierId(), req.items(), req.amount(), req.remark());
        return R.ok(Map.of("docNo", docNo));
    }

    @PutMapping("/api/purchase/docs/{docNo}/approve")
    public R<Void> approve(@PathVariable String docNo) {
        int n = jdbc.update("update pur_doc set status = 'APPROVED' where doc_no = ? and status = 'DRAFT'", docNo);
        return n == 0 ? R.fail(4692, "单据不存在或状态不符") : R.ok();
    }

    /** 财务验收：补录/调整发票信息 */
    @PutMapping("/api/purchase/docs/{docNo}/receive")
    public R<Void> receive(@PathVariable String docNo, @RequestParam String invoiceNo) {
        int n = jdbc.update("""
                update pur_doc set status = 'RECEIVED', invoice_no = ?
                where doc_no = ? and status in ('APPROVED', 'RECEIVED')
                """, invoiceNo, docNo);
        return n == 0 ? R.fail(4693, "须先审核后验收") : R.ok();
    }

    @PutMapping("/api/purchase/docs/{docNo}/cancel")
    public R<Void> cancel(@PathVariable String docNo) {
        int n = jdbc.update("update pur_doc set status = 'CANCELLED' where doc_no = ? and status = 'DRAFT'", docNo);
        return n == 0 ? R.fail(4694, "仅草稿可作废") : R.ok();
    }

    /** 误作废还原为草稿 */
    @PutMapping("/api/purchase/docs/{docNo}/restore")
    public R<Void> restore(@PathVariable String docNo) {
        int n = jdbc.update("update pur_doc set status = 'DRAFT' where doc_no = ? and status = 'CANCELLED'", docNo);
        return n == 0 ? R.fail(4695, "仅已作废可还原") : R.ok();
    }

    @GetMapping("/api/purchase/docs")
    public R<List<Map<String, Object>>> docs() {
        return R.ok(jdbc.queryForList("""
                select d.*, s.name as supplier_name from pur_doc d
                join hrp_supplier s on s.id = d.supplier_id order by d.id desc limit 100
                """));
    }

    // ---- 供应商证照 ----
    public record CertReq(Long supplierId, String certType, String certNo, String attachmentName, String expireDate) {}

    @PostMapping("/api/purchase/supplier-certs")
    public R<Void> addCert(@RequestBody CertReq req) {
        jdbc.update("""
                insert into hrp_supplier_cert(supplier_id, cert_type, cert_no, attachment_name, expire_date)
                values (?,?,?,?,?::date)
                """, req.supplierId(), req.certType(), req.certNo(), req.attachmentName(), req.expireDate());
        return R.ok();
    }

    @GetMapping("/api/purchase/supplier-certs")
    public R<List<Map<String, Object>>> certs() {
        return R.ok(jdbc.queryForList("""
                select c.*, s.name as supplier_name,
                       case when c.expire_date < current_date then 'EXPIRED'
                            when c.expire_date < current_date + 90 then 'EXPIRING' else 'VALID' end as validity
                from hrp_supplier_cert c join hrp_supplier s on s.id = c.supplier_id
                order by c.expire_date
                """));
    }

    // ---- 制度文件库 ----
    public record DocFileReq(String category, String title, String content, String version) {}

    @PostMapping("/api/doclib")
    public R<Void> upload(@RequestBody DocFileReq req) {
        if (req.title() == null || req.content() == null) return R.fail(4696, "标题与内容必填");
        jdbc.update("insert into oa_doc_file(category, title, content, version) values (?,?,?,?)",
                req.category(), req.title(), req.content(), req.version() == null ? "V1.0" : req.version());
        return R.ok();
    }

    @GetMapping("/api/doclib")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String keyword) {
        String base = "select id, category, title, version, created_at from oa_doc_file ";
        return R.ok(keyword == null
                ? jdbc.queryForList(base + " order by category, id desc limit 200")
                : jdbc.queryForList(base + " where title ilike ? or category ilike ? order by id desc limit 200",
                        "%" + keyword + "%", "%" + keyword + "%"));
    }

    /** 在线预览 */
    @GetMapping("/api/doclib/{id}")
    public R<Map<String, Object>> view(@PathVariable Long id) {
        var rows = jdbc.queryForList("select * from oa_doc_file where id = ?", id);
        return rows.isEmpty() ? R.fail(4697, "文件不存在") : R.ok(rows.get(0));
    }
}
