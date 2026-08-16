package cn.hip.medtech.web;

import cn.hip.platform.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 二十三期：检查/检验时段预约——申请单收费后选时段，医技端按预约队列叫号 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/appointments")
@PreAuthorize("hasAnyRole('ADMIN','TECHNICIAN','NURSE')")   // 1.0.9：权限清点补齐
public class AppointmentController {

    private final JdbcTemplate jdbc;

    /** 可预约申请单：已收费的检查/检验、尚未预约 */
    @GetMapping("/pending")
    public R<List<Map<String, Object>>> pending() {
        return R.ok(jdbc.queryForList("""
                select o.id as order_id, o.order_type, o.item_name, o.group_no, p.name as patient_name
                from outp_order o
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where o.order_type in ('EXAM','LAB') and o.status = 'CHARGED'
                  and not exists (select 1 from med_appointment a where a.order_id = o.id)
                order by o.id desc limit 100
                """));
    }

    public record BookReq(Long orderId, String slotDate, String period) {}

    /** 预约：同时段顺序号自动生成 */
    @PostMapping
    @Transactional
    public R<Map<String, Object>> book(@RequestBody BookReq req) {
        if (!"AM".equals(req.period()) && !"PM".equals(req.period())) return R.fail(4510, "时段只能为 AM/PM");
        var orders = jdbc.queryForList(
                "select id from outp_order where id = ? and order_type in ('EXAM','LAB') and status = 'CHARGED'",
                req.orderId());
        if (orders.isEmpty()) return R.fail(4510, "申请单不存在或未收费");
        Integer booked = jdbc.queryForObject(
                "select count(*) from med_appointment where order_id = ?", Integer.class, req.orderId());
        if (booked != null && booked > 0) return R.fail(4511, "该申请单已预约");
        // 单语句取号：max+1 与 insert 在同一条语句内求值，唯一索引裁决并发。
        // **不能**用「循环 + catch DuplicateKey」——PG 在唯一冲突后会置事务为 aborted，
        // 同一事务里的下一条语句直接报 25P02，重试永远走不到（1.0.8 的写法就栽在这）。
        var seqRows = jdbc.queryForList("""
                insert into med_appointment(order_id, slot_date, period, seq_no)
                select ?, ?::date, ?,
                       coalesce((select max(seq_no) from med_appointment
                                 where slot_date = ?::date and period = ?), 0) + 1
                on conflict do nothing
                returning seq_no
                """, Integer.class, req.orderId(), req.slotDate(), req.period(), req.slotDate(), req.period());
        if (seqRows.isEmpty()) {
            return R.fail(4514, "该时段预约并发繁忙，请重试");   // 唯一索引裁决落败方
        }
        return R.ok(Map.of("seqNo", seqRows.get(0)));
    }

    /** 预约队列（按日期） */
    @GetMapping
    public R<List<Map<String, Object>>> list(@RequestParam String date) {
        return R.ok(jdbc.queryForList("""
                select a.id, a.slot_date, a.period, a.seq_no, a.status, o.order_type, o.item_name, p.name as patient_name
                from med_appointment a
                join outp_order o on o.id = a.order_id
                join outp_registration r on r.id = o.registration_id
                join empi_patient p on p.id = r.patient_id
                where a.slot_date = ?::date
                order by a.period, a.seq_no
                """, date));
    }

    /** 完成检查/检验（执行侧从队列消除） */
    @PutMapping("/{id}/done")
    public R<Void> done(@PathVariable Long id) {
        int n = jdbc.update(
                "update med_appointment set status = 'DONE' where id = ? and status in ('BOOKED','CALLED')", id);
        return n == 0 ? R.fail(4512, "预约不存在或已完成") : R.ok();
    }

    public record RescheduleReq(String slotDate, String period) {}

    /** 改约：直接选择新时段，重排顺序号 */
    @PutMapping("/{id}/reschedule")
    @Transactional
    public R<Map<String, Object>> reschedule(@PathVariable Long id, @RequestBody RescheduleReq req) {
        if (!"AM".equals(req.period()) && !"PM".equals(req.period())) return R.fail(4510, "时段只能为 AM/PM");
        Integer exists = jdbc.queryForObject(
                "select count(*) from med_appointment where id = ? and status = 'BOOKED'", Integer.class, id);
        if (exists == null || exists == 0) return R.fail(4513, "仅未叫号的预约可改约");
        // 同 book：单语句内取号并改约，避免事务 aborted 后重试失效
        List<Integer> seqRows;
        try {
            seqRows = jdbc.queryForList("""
                    update med_appointment set slot_date = ?::date, period = ?,
                           seq_no = coalesce((select max(m.seq_no) from med_appointment m
                                              where m.slot_date = ?::date and m.period = ?), 0) + 1
                    where id = ?
                    returning seq_no
                    """, Integer.class, req.slotDate(), req.period(), req.slotDate(), req.period(), id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return R.fail(4514, "该时段预约并发繁忙，请重试");
        }
        if (seqRows.isEmpty()) {
            return R.fail(4513, "仅未叫号的预约可改约");
        }
        return R.ok(Map.of("seqNo", seqRows.get(0)));
    }

    public record BatchCancelReq(List<Long> ids) {}

    /** 批量取消预约 */
    @PostMapping("/batch-cancel")
    @Transactional
    public R<Map<String, Object>> batchCancel(@RequestBody BatchCancelReq req) {
        int n = 0;
        for (Long id : req.ids()) {
            n += jdbc.update("update med_appointment set status = 'CANCELLED' where id = ? and status = 'BOOKED'", id);
        }
        return R.ok(Map.of("cancelled", n));
    }
}
