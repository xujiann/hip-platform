package cn.hip.inpatient.service;

import cn.hip.platform.core.common.HipBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** ICU observations use the current admission owner, with existing nursing coordination. */
@Service @RequiredArgsConstructor
public class IcuAccess {
    private final JdbcTemplate jdbc;
    private record Actor(Long id,boolean cross) {}
    private Actor actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null)throw denied();
        var roles=auth.getAuthorities().stream().map(x->x.getAuthority()).toList();
        boolean cross=roles.contains("ROLE_ADMIN")||roles.contains("ROLE_NURSE");
        if(!cross&&!roles.contains("ROLE_DOCTOR_OUTP"))throw denied();
        var ids=jdbc.queryForList("select id from sys_user where username=? and enabled=true",Long.class,auth.getName());
        if(ids.isEmpty())throw denied();
        return new Actor(ids.getFirst(),cross);
    }
    private AccessDeniedException denied(){return new AccessDeniedException("无权查阅该ICU床旁记录");}
    private Actor lock(Long id,boolean write) {
        actor();
        // Normal transactions (not readOnly): PostgreSQL disallows FOR SHARE in read-only transactions.
        var rows=jdbc.queryForList("select doctor_id,status from inp_admission where id=?"+(write?" for update":" for share"),id);
        var me=actor(); // Recheck enabled identity after any parent lock wait.
        if(rows.isEmpty()) {
            if(write)throw new HipBizException(4570,"住院记录不存在或已出院");
            return me; // Preserve legacy empty detail / zero aggregate for unknown admissions.
        }
        var row=rows.getFirst();Object owner=row.get("doctor_id");
        if(!me.cross()&&owner!=null&&!me.id().equals(((Number)owner).longValue())) {
            if(write)throw new HipBizException(9018,"无权操作该病案（非本人主管）");
            throw denied();
        }
        if(write&&!"IN_HOSPITAL".equals(row.get("status")))throw new HipBizException(4570,"住院记录不存在或已出院");
        return me;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long lockForWrite(Long id){return lock(id,true).id();}
    @Transactional(propagation=Propagation.MANDATORY)
    public void lockForRead(Long id){lock(id,false);}
    public List<Map<String,Object>> admissions(String keyword) {
        var me=actor();String kw=keyword==null?"":keyword.trim();
        if(kw.length()>64)throw new HipBizException(4880,"关键词过长（最多64字）");
        String like="%"+kw.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        var args=new ArrayList<Object>();args.add(kw);args.add(like);args.add(like);
        String scope="";
        if(!me.cross()){scope=" and (a.doctor_id is null or a.doctor_id=?)";args.add(me.id());}
        return jdbc.queryForList("select a.id,a.admission_no,a.status,p.name as patient_name from inp_admission a join empi_patient p on p.id=a.patient_id where (?='' or a.admission_no ilike ? escape '\\' or p.name ilike ? escape '\\')"+scope+" order by a.id desc limit 200",args.toArray());
    }
}
