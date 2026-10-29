package cn.hip.outpatient.service;

import cn.hip.platform.core.common.HipBizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared only by the existing structured clinical saves. The clinical object is
 * already locked before this parent lock; template/field maintenance takes an
 * incompatible parent UPDATE lock before changing its header, grants or fields.
 * Inpatient already depends on outpatient for clinical versioning.
 */
public final class StructuredEmrTemplateAccess {
    private StructuredEmrTemplateAccess() {}

    public record Actor(Long id, Long deptId, boolean admin) {}

    /** actorId is resolved by the existing entry point from Spring Authentication;
     * it is not an accepted request-body identity. Re-read that identity's live
     * database duties after the parent wait, rather than trusting earlier roles.
     * A null template remains the original renderer's 4024 validation boundary.
     */
    public static Actor lockUsable(JdbcTemplate jdbc, Long templateId, Long actorId) {
        if (templateId == null) return null;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Structured template access requires the clinical save transaction");
        }
        currentActor(jdbc, actorId);
        jdbc.queryForList("select id from emr_template where id = ? for share", Long.class, templateId);
        Actor actor = currentActor(jdbc, actorId);
        Integer usable = jdbc.queryForObject("""
                select count(*) from emr_template t
                where t.id = ? and t.enabled = true and (
                    t.scope in ('GLOBAL', 'HOSPITAL') or t.owner_id = ?
                    or (t.scope = 'DEPT' and (cast(? as boolean) or t.dept_id = ?))
                    or exists (select 1 from emr_template_grant g where g.template_id = t.id and (
                        (g.grantee_type = 'USER' and g.grantee_id = ?)
                        or (g.grantee_type = 'DEPT' and g.grantee_id = ?)))
                )
                """, Integer.class, templateId, actor.id(), actor.admin(), actor.deptId(), actor.id(), actor.deptId());
        if (usable == null || usable != 1) {
            throw new HipBizException(4066, "模板不存在、已停用或未授权使用");
        }
        return actor;
    }

    private static Actor currentActor(JdbcTemplate jdbc, Long actorId) {
        var rows = jdbc.queryForList("""
                select u.id, u.dept_id, u.enabled, r.code from sys_user u
                left join sys_user_role ur on ur.user_id = u.id
                left join sys_role r on r.id = ur.role_id
                where u.id = ?
                """, actorId);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled"))
                || rows.stream().noneMatch(row -> "ADMIN".equals(row.get("code")) || "DOCTOR_OUTP".equals(row.get("code")))) {
            throw new AccessDeniedException("结构化病历操作人不存在、已停用或岗位已变化");
        }
        var user = rows.getFirst();
        Object department = user.get("dept_id");
        return new Actor(((Number) user.get("id")).longValue(), department == null ? null : ((Number) department).longValue(),
                rows.stream().anyMatch(row -> "ADMIN".equals(row.get("code"))));
    }
}
