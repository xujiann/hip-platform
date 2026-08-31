package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * v40：分时段预约的**两级原子占号唯一实现**。
 *
 * <p>v37 时该逻辑只写在院内挂号台 controller 里；v40 患者端也要预约，若照抄一份，
 * 两份实现必然随时间漂移（号源两处各写一份是超挂事故最常见的成因）。故抽到本 service，
 * 院内 {@code OutpAppointmentController} 与患者端 {@code PortalController} 共用同一份：
 * 占号规则、防重规则、错误码（3110–3114）在两个入口天然一致。
 *
 * <p><b>失败刻意分两类表达</b>（不是风格问题，是事务语义）：
 * <ul>
 *   <li>无副作用的早退（3110 时段不存在 / 3112 重复预约预检 / 3111 时段已满）用<b>返回值</b>给出——
 *       若改抛异常，调用方在同一事务内 catch 会把事务标记为 rollback-only，与 v37 既有行为不等价；</li>
 *   <li>已占号后才发现的失败（排班池满、撞 uq_appt_active）仍<b>抛 BizException</b>，
 *       靠回滚把已占的 slot 号退回去——这是 v37 两级占号的关键，不能改成返回值。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AppointmentService {

    private final JdbcTemplate jdbc;

    /** code==0 为成功；否则为业务码（3110/3111/3112），由调用方转成各自入口的响应体 */
    public record BookResult(int code, String message, Long apptId, Integer apptNo) {
        public boolean ok() {
            return code == 0;
        }

        static BookResult fail(int code, String message) {
            return new BookResult(code, message, null, null);
        }
    }

    /** 取消等无返回数据的操作结果（0=成功，3113/3114=业务码） */
    public record OpResult(int code, String message) {
        public boolean ok() {
            return code == 0;
        }
    }

    private static final OpResult OK = new OpResult(0, null);

    /**
     * 时段列表 + 余号。
     *
     * @param onlyEnabled 患者端只应看到在用时段；院内排班管理需要连停用时段一并看到
     */
    public List<Map<String, Object>> slots(Long scheduleId, boolean onlyEnabled) {
        return jdbc.queryForList("""
                select id, time_begin, time_end, capacity, booked, (capacity - booked) as remaining, enabled
                from outp_schedule_slot where schedule_id = ?
                """ + (onlyEnabled ? " and enabled" : "") + " order by time_begin", scheduleId);
    }

    /**
     * 预约：两级原子占号（slot 满 3111；schedule 池满同样 3111 但回滚 slot 占号；同患者同排班重复 3112）。
     *
     * <p>patientId 由调用方决定来源——患者端必须取自令牌，绝不可用请求体里的值。
     */
    @Transactional
    public BookResult book(Long slotId, Long patientId, String source) {
        var slot = jdbc.queryForList(
                "select schedule_id from outp_schedule_slot where id = ? and enabled", slotId);
        if (slot.isEmpty()) return BookResult.fail(3110, "时段不存在或已停用");
        Long scheduleId = ((Number) slot.get(0).get("schedule_id")).longValue();
        // 查重仅为友好提示（占号前早退零副作用）；真正防线是 uq_appt_active 部分唯一索引
        // （并发下 find-then-insert 会双约——撞索引时 throw 回滚两级占号）。与 register() 同范式。
        Integer dup = jdbc.queryForObject(
                "select count(*) from outp_appointment where schedule_id = ? and patient_id = ? and status = 'BOOKED'",
                Integer.class, scheduleId, patientId);
        if (dup != null && dup > 0) return BookResult.fail(3112, "该患者此排班已有有效预约");
        // 两级占号：先 slot 后 schedule，任一失败整体回滚（同一事务）
        if (jdbc.update("update outp_schedule_slot set booked = booked + 1 where id = ? and booked < capacity",
                slotId) == 0) {
            return BookResult.fail(3111, "该时段号源已满");
        }
        if (jdbc.update("update outp_schedule set booked = booked + 1 where id = ? and booked < capacity and enabled",
                scheduleId) == 0) {
            throw new RegistrationService.BizException(3111, "排班号源已满");   // throw 回滚 slot 占号
        }
        Integer booked = jdbc.queryForObject("select booked from outp_schedule where id = ?", Integer.class, scheduleId);
        int apptNo = booked == null ? 0 : booked;
        try {
            var kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        insert into outp_appointment(slot_id, schedule_id, patient_id, appt_no, source)
                        values (?,?,?,?,?)
                        """, new String[]{"id"});
                ps.setLong(1, slotId);
                ps.setLong(2, scheduleId);
                ps.setLong(3, patientId);
                ps.setInt(4, apptNo);
                ps.setString(5, source);
                return ps;
            }, kh);
            return new BookResult(0, null, kh.getKey().longValue(), apptNo);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new RegistrationService.BizException(3112, "该患者此排班已有有效预约");   // uq_appt_active，回滚两级占号
        }
    }

    /** 取消预约：抢占 BOOKED→CANCELLED，两级释放号源。归属校验由各入口负责（患者端必须校验）。 */
    @Transactional
    public OpResult cancel(Long apptId) {
        var rows = jdbc.queryForList("select slot_id, schedule_id from outp_appointment where id = ?", apptId);
        if (rows.isEmpty()) return new OpResult(3113, "预约不存在");
        if (jdbc.update("update outp_appointment set status = 'CANCELLED', cancelled_at = now() "
                + "where id = ? and status = 'BOOKED'", apptId) == 0) {
            return new OpResult(3114, "预约状态不允许取消（已签到或已取消）");
        }
        jdbc.update("update outp_schedule_slot set booked = booked - 1 where id = ? and booked > 0",
                ((Number) rows.get(0).get("slot_id")).longValue());
        jdbc.update("update outp_schedule set booked = booked - 1 where id = ? and booked > 0",
                ((Number) rows.get(0).get("schedule_id")).longValue());
        return OK;
    }
}
