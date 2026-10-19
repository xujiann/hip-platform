package cn.hip.outpatient.service;

import cn.hip.platform.core.common.HipBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Emergency ownership belongs to the actual triage, including unnamed patients. */
@Service @RequiredArgsConstructor
public class EmergencyAccess {
    private final JdbcTemplate jdbc;
    public record Actor(Long id, boolean admin, boolean nurse, boolean doctor) {
        public boolean coordinates() { return admin || nurse; }
    }
    public Actor actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw denied();
        var roles = auth.getAuthorities().stream().map(x -> x.getAuthority()).toList();
        var ids = jdbc.queryForList("select id from sys_user where username=? and enabled=true", Long.class, auth.getName());
        boolean admin=roles.contains("ROLE_ADMIN"), nurse=roles.contains("ROLE_NURSE"), doctor=roles.contains("ROLE_DOCTOR_OUTP");
        if (ids.isEmpty() || (!admin && !nurse && !doctor)) throw denied();
        return new Actor(ids.getFirst(), admin, nurse, doctor);
    }
    public AccessDeniedException denied() { return new AccessDeniedException("无权查阅或操作该急诊病例"); }
    public String scope(Actor me, List<Object> args) {
        if (me.coordinates()) return "1=1";
        args.add(me.id());
        return "(t.doctor_id is null or t.doctor_id=?)";
    }
    public Map<String,Object> triage(Long id, int missingCode, boolean physician) {
        return triage(id, missingCode, physician, false);
    }
    private Map<String,Object> triage(Long id, int missingCode, boolean physician, boolean lock) {
        var me=actor();
        var rows=jdbc.queryForList("select * from outp_triage where id=?"+(lock?" for update":""),id);
        if (rows.isEmpty()) throw new HipBizException(missingCode,"分诊记录不存在");
        var row=rows.getFirst();
        if (physician && !me.admin() && !me.doctor()) throw denied();
        boolean cross=me.admin() || (!physician && me.nurse());
        Object owner=row.get("doctor_id");
        if (!cross && owner!=null && !me.id().equals(((Number)owner).longValue())) throw denied();
        return row;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> lockTriage(Long id,int missingCode,boolean physician) { return triage(id,missingCode,physician,true); }
    public Map<String,Object> observation(Long id,int missingCode,boolean write,boolean physician) {
        actor();
        var before=find("er_observation",id,missingCode);
        if (write) lockTriage(number(before.get("triage_id")),4560,physician);
        else triage(number(before.get("triage_id")),4560,physician);
        var row=write?locked("er_observation",id,missingCode):before;
        if (!Objects.equals(before.get("triage_id"),row.get("triage_id"))) throw denied();
        return row;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> lockRescue(Long id) {
        var before=find("er_rescue_record",id,4566);
        if (before.get("observation_id")!=null) {
            var obs=observation(number(before.get("observation_id")),4565,true,true);
            if(!Objects.equals(obs.get("triage_id"),before.get("triage_id")))throw denied();
        } else lockTriage(number(before.get("triage_id")),4565,true);
        var row=locked("er_rescue_record",id,4566);
        if (!Objects.equals(before.get("triage_id"),row.get("triage_id")) || !Objects.equals(before.get("observation_id"),row.get("observation_id"))) throw denied();
        return row;
    }
    private Map<String,Object> find(String table,Long id,int code) {
        var rows=jdbc.queryForList("select * from "+table+" where id=?",id);
        if(rows.isEmpty())throw new HipBizException(code,"急诊记录不存在");
        return rows.getFirst();
    }
    private Map<String,Object> locked(String table,Long id,int code) {
        var rows=jdbc.queryForList("select * from "+table+" where id=? for update",id);
        if(rows.isEmpty())throw new HipBizException(code,"急诊记录不存在");
        return rows.getFirst();
    }
    public Long number(Object value) { return value==null?null:((Number)value).longValue(); }
    public void validateDoctor(Long id) {
        if (id==null) return;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sys_user u join sys_user_role ur on ur.user_id=u.id join sys_role r on r.id=ur.role_id where u.id=? and u.enabled=true and r.code='DOCTOR_OUTP')",Boolean.class,id)))
            throw new HipBizException(9706,"急诊责任医生须为启用的门诊医生");
    }
}
