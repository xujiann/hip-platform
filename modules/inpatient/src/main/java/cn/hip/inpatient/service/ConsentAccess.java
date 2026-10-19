package cn.hip.inpatient.service;

import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.config.BusinessDates;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** Real parent encounter scope; historical dual-scope consents remain admission-scoped. */
@Service @RequiredArgsConstructor
public class ConsentAccess {
    private final JdbcTemplate jdbc;
    private final InpatientReadAccess readAccess;
    private final InpatientWriteAccess writeAccess;
    private record Actor(Long id,boolean admin,boolean nurse,boolean doctor) {
        boolean crossRead(){return admin||nurse;}
    }
    private Actor actor(boolean lock){
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null)throw denied();
        var roles=auth.getAuthorities().stream().map(x->x.getAuthority()).toList();
        boolean admin=roles.contains("ROLE_ADMIN"),nurse=roles.contains("ROLE_NURSE"),doctor=roles.contains("ROLE_DOCTOR_OUTP");
        if(!admin&&!nurse&&!doctor)throw denied();
        var users=jdbc.queryForList("select id from sys_user where username=? and enabled=true"+(lock?" for share":""),Long.class,auth.getName());
        if(users.isEmpty())throw denied();
        return new Actor(users.getFirst(),admin,nurse,doctor);
    }
    private AccessDeniedException denied(){return new AccessDeniedException("无权查阅或操作该知情同意书");}
    private Long number(Object x){return x==null?null:((Number)x).longValue();}
    private String outpScope(Actor me,List<Object> args){
        if(me.crossRead())return "1=1";
        args.add(BusinessDates.today().toString());args.add(me.id());args.add(me.id());
        return "(r.visit_date=?::date or r.doctor_id is null or r.doctor_id=? or exists(select 1 from outp_emr e where e.registration_id=r.id and e.doctor_id=?))";
    }
    private Actor registration(Long id,boolean write,boolean physician){
        if(write)jdbc.queryForList("select id from outp_registration where id=? for update",id);
        var me=actor(write);
        // A separate statement after acquiring the parent lock also refreshes the EMR author.
        var rows=jdbc.queryForList("select r.doctor_id,r.visit_date,exists(select 1 from outp_emr e where e.registration_id=r.id and e.doctor_id=?) as author from outp_registration r where r.id=?",me.id(),id);
        if(rows.isEmpty())throw new HipBizException(4001,"挂号记录不存在");
        if(physician&&!me.admin()&&!me.doctor())throw denied();
        var r=rows.getFirst();
        boolean cross=me.admin()||(!physician&&me.nurse());
        boolean allowed=cross||BusinessDates.today().toString().equals(String.valueOf(r.get("visit_date")))||r.get("doctor_id")==null||me.id().equals(number(r.get("doctor_id")))||Boolean.TRUE.equals(r.get("author"));
        if(!allowed){if(write)throw new HipBizException(4036,"无权操作该门诊同意书（非当日就诊且非本人接诊或书写）");throw denied();}
        return me;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Long assertCanCreate(Long admission,Long reg){
        if(admission!=null){writeAccess.assertCanCoordinateCare(admission);return actor(true).id();}
        return registration(reg,true,false).id();
    }
    public List<Map<String,Object>> registrations(LocalDate date){
        var me=actor(false);var args=new ArrayList<Object>();args.add((date==null?BusinessDates.today():date).toString());
        String scope=outpScope(me,args);
        return jdbc.queryForList("select r.id,r.reg_no,r.visit_date,r.doctor_id,e.doctor_id as emr_doctor_id,p.name as patient_name,p.patient_no from outp_registration r join empi_patient p on p.id=r.patient_id left join outp_emr e on e.registration_id=r.id where r.visit_date=?::date and "+scope+" order by r.id desc limit 100",args.toArray());
    }
    public List<Map<String,Object>> list(Long admission,Long reg){
        if(admission!=null)readAccess.assertCanRead(admission);
        else if(reg!=null&&jdbc.queryForList("select id from outp_registration where id=?",Long.class,reg).isEmpty())throw new HipBizException(4001,"挂号记录不存在");
        var me=actor(false);var args=new ArrayList<Object>();
        var sql=new StringBuilder("select c.* from emr_consent c left join inp_admission a on a.id=c.admission_id left join outp_registration r on r.id=c.registration_id where 1=1");
        if(admission!=null){sql.append(" and c.admission_id=?");args.add(admission);}
        else if(reg!=null){sql.append(" and c.registration_id=?");args.add(reg);}
        sql.append(" and ((c.admission_id is not null and a.id is not null");
        readAccess.clinicalScope().appendSql(sql,args);
        sql.append(") or (c.admission_id is null and r.id is not null and ").append(outpScope(me,args)).append(")) order by c.id desc");
        if(admission==null&&reg==null)sql.append(" limit 100");
        return jdbc.queryForList(sql.toString(),args.toArray());
    }
    public Map<String,Object> read(Long id){
        var row=find(id);
        if(row.get("admission_id") instanceof Number n){readAccess.assertCanRead(n.longValue());actor(false);}
        else registration(number(row.get("registration_id")),false,false);
        return row;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Map<String,Object> lockForWrite(Long id,boolean physicianSignature){
        var before=find(id);
        if(before.get("admission_id") instanceof Number n){
            if(physicianSignature)writeAccess.assertCanWrite(n.longValue());else writeAccess.assertCanCoordinateCare(n.longValue());
            var me=actor(true);if(physicianSignature&&!me.admin()&&!me.doctor())throw denied();
        }else registration(number(before.get("registration_id")),true,physicianSignature);
        var rows=jdbc.queryForList("select * from emr_consent where id=? for update",id);
        if(rows.isEmpty())throw new HipBizException(9112,"同意书不存在");
        var row=rows.getFirst();
        if(!Objects.equals(before.get("admission_id"),row.get("admission_id"))||!Objects.equals(before.get("registration_id"),row.get("registration_id")))throw denied();
        return row;
    }
    private Map<String,Object> find(Long id){
        var rows=jdbc.queryForList("select * from emr_consent where id=?",id);
        if(rows.isEmpty())throw new HipBizException(9112,"同意书不存在");
        return rows.getFirst();
    }
}
