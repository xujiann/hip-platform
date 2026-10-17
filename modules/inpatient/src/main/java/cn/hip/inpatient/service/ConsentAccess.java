package cn.hip.inpatient.service;

import cn.hip.platform.core.common.HipBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

/** Consent ownership always follows the admission, including historical dual-scope rows. */
@Service @RequiredArgsConstructor
public class ConsentAccess {
    private final JdbcTemplate jdbc;
    private final InpatientReadAccess readAccess;
    private final InpatientWriteAccess writeAccess;

    public Map<String,Object> read(Long id) {
        var row=find(id);
        if(row.get("admission_id") instanceof Number n) readAccess.assertCanRead(n.longValue());
        return row;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> lockForWrite(Long id,boolean physicianSignature) {
        var row=find(id);
        if(row.get("admission_id") instanceof Number n) {
            if(physicianSignature) writeAccess.assertCanWrite(n.longValue());
            else writeAccess.assertCanCoordinateCare(n.longValue());
        }
        // Parent first; hold both through CA invocation and the final state update.
        var rows=jdbc.queryForList("select * from emr_consent where id=? for update",id);
        if(rows.isEmpty())throw new HipBizException(9112,"同意书不存在");
        return rows.getFirst();
    }

    private Map<String,Object> find(Long id) {
        var rows=jdbc.queryForList("select * from emr_consent where id=?",id);
        if(rows.isEmpty())throw new HipBizException(9112,"同意书不存在");
        return rows.getFirst();
    }
}
