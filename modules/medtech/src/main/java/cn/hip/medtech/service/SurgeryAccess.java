package cn.hip.medtech.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;

/** Surgery-specific duties; no grant to unrelated inpatient or blood endpoints. */
@Service @RequiredArgsConstructor
public class SurgeryAccess {
    private final JdbcTemplate jdbc;
    private final CurrentUserService users;
    private static final Set<String> OPERATORS=Set.of("ROLE_ADMIN","ROLE_NURSE","ROLE_TECHNICIAN");
    public record Scope(boolean all,Long doctorId) {
        boolean includes(Object owner){return all||(doctorId!=null&&(owner==null||doctorId.equals(((Number)owner).longValue())));}
        public void appendSql(StringBuilder sql,List<Object> args){
            if(all)return;
            if(doctorId==null){sql.append(" and 1=0");return;}
            sql.append(" and (a.doctor_id is null or a.doctor_id=?)");args.add(doctorId);
        }
    }
    public Scope scope(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        // Existing trusted non-HTTP service callers; every HTTP entry has a role gate.
        if(auth==null)return new Scope(true,null);
        if(auth.getAuthorities().stream().anyMatch(a->OPERATORS.contains(a.getAuthority())))return new Scope(true,null);
        boolean doctor=auth.getAuthorities().stream().anyMatch(a->"ROLE_DOCTOR_OUTP".equals(a.getAuthority()));
        return new Scope(false,doctor?users.idOf(auth):null);
    }
    public void assertMedicalRole(){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null&&auth.getAuthorities().stream().noneMatch(a->Set.of("ROLE_ADMIN","ROLE_DOCTOR_OUTP").contains(a.getAuthority())))throw new AccessDeniedException("须医师岗位操作");
    }
    public boolean read(Long surgeryId){
        var rows=jdbc.queryForList("select a.doctor_id from inp_surgery s join inp_admission a on a.id=s.admission_id where s.id=?",surgeryId);
        if(rows.isEmpty())return false;
        if(!scope().includes(rows.getFirst().get("doctor_id")))throw new AccessDeniedException("无权查阅该手术（非本人主管）");
        return true;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockAdmission(Long admissionId,boolean medicalDecision){
        if(medicalDecision)assertMedicalRole();
        var rows=jdbc.queryForList("select doctor_id from inp_admission where id=? for update",admissionId);
        if(rows.isEmpty())throw new HipBizException(9003,"住院记录不存在");
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null)return;
        var cross=medicalDecision?Set.of("ROLE_ADMIN"):OPERATORS;
        if(auth.getAuthorities().stream().anyMatch(a->cross.contains(a.getAuthority())))return;
        boolean doctor=auth.getAuthorities().stream().anyMatch(a->"ROLE_DOCTOR_OUTP".equals(a.getAuthority()));
        Long me=doctor?users.idOf(auth):null;Object owner=rows.getFirst().get("doctor_id");
        if(me==null||(owner!=null&&!me.equals(((Number)owner).longValue())))throw new HipBizException(9018,"无权操作该病案（非本人主管）");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockSurgery(Long id,boolean medicalDecision){
        if(medicalDecision)assertMedicalRole();
        var rows=jdbc.queryForList("select admission_id from inp_surgery where id=?",id);
        if(rows.isEmpty())return false;
        lockAdmission(((Number)rows.getFirst().get("admission_id")).longValue(),medicalDecision);
        return !jdbc.queryForList("select id from inp_surgery where id=? for update",id).isEmpty();
    }
    public enum Child {TUBE("surg_tube"),TRANSFUSION("surg_transfusion"),EVENT("surg_event");final String table;Child(String table){this.table=table;}}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean lockChild(Child kind,Long id){
        // Table identifiers are closed enum constants, never request text.
        var rows=jdbc.queryForList("select surgery_id from "+kind.table+" where id=?",id);
        if(rows.isEmpty())return false;
        if(!lockSurgery(((Number)rows.getFirst().get("surgery_id")).longValue(),false))return false;
        return !jdbc.queryForList("select id from "+kind.table+" where id=? for update",id).isEmpty();
    }
}
