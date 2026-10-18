package cn.hip.qualitycare.service;

import cn.hip.inpatient.service.InpatientWriteAccess;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.empi.service.PatientService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Live object ownership; transaction failures cross this boundary before HTTP envelopes are created. */
@Service @RequiredArgsConstructor
public class PatientCareService {
    private final JdbcTemplate jdbc;
    private final InpatientWriteAccess inpatient;
    private final PatientService patients;
    private record Actor(Long id,Long dept) {}
    private boolean role(String code) {
        var a=SecurityContextHolder.getContext().getAuthentication();
        return a!=null && a.getAuthorities().stream().anyMatch(x->("ROLE_"+code).equals(x.getAuthority()));
    }
    private Actor actor(boolean lock) {
        var a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null)throw denied();
        var rows=jdbc.queryForList("select id,dept_id from sys_user where username=? and enabled=true"+(lock?" for share":""),a.getName());
        if(rows.isEmpty())throw denied();
        var r=rows.getFirst();return new Actor(((Number)r.get("id")).longValue(),r.get("dept_id")==null?null:((Number)r.get("dept_id")).longValue());
    }
    private AccessDeniedException denied(){return new AccessDeniedException("无权操作或查阅该患者管理记录");}
    private Long number(Object value){return value==null?null:((Number)value).longValue();}
    private boolean crossRead(){return role("ADMIN")||role("QUALITY");}

    @Transactional
    public void createFollowup(Long patient,String topic,String channel,String dueDate){
        var me=actor(true);
        jdbc.update("insert into pc_followup(patient_id,topic,channel,due_date,creator_id) values (?,?,?,?::date,?)",patient,topic,channel==null?"PHONE":channel,dueDate,me.id());
    }
    public List<Map<String,Object>> followups(String status){
        var me=actor(false);String scope=crossRead()?"":" and f.creator_id=?";
        var args=new ArrayList<Object>();args.add(status);if(!crossRead())args.add(me.id());
        return jdbc.queryForList("""
            select f.id,f.topic,f.channel,f.due_date,f.status,f.result_note,f.creator_id,
                   p.name as patient_name,p.phone,p.patient_no
            from pc_followup f join empi_patient p on p.id=f.patient_id
            where f.status=?
            """+scope+" order by f.due_date,f.id limit 200",args.toArray());
    }
    @Transactional
    public void doneFollowup(Long id,String note){
        var rows=jdbc.queryForList("select status,creator_id from pc_followup where id=? for update",id);
        if(rows.isEmpty())throw new HipBizException(9901,"任务不存在或已完成");
        var me=actor(true);var row=rows.getFirst();
        if(!role("ADMIN")&&!me.id().equals(number(row.get("creator_id"))))throw denied();
        if(!"PENDING".equals(row.get("status")))throw new HipBizException(9901,"任务不存在或已完成");
        jdbc.update("update pc_followup set status='DONE',result_note=?,done_at=now() where id=?",note,id);
    }

    @Transactional
    public void requestConsult(Long admission,Long dept,String question){
        inpatient.assertCanWrite(admission);var me=actor(true);
        if(!"IN_HOSPITAL".equals(jdbc.queryForObject("select status from inp_admission where id=?",String.class,admission)))throw new HipBizException(9917,"已出院不能新增会诊");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sys_dept where id=? and type='CLINICAL')",Boolean.class,dept)))throw new HipBizException(9918,"受邀临床科室不存在");
        jdbc.update("insert into inp_consult(admission_id,from_doctor_id,to_dept_id,question) values (?,?,?,?)",admission,me.id(),dept,question);
    }
    public List<Map<String,Object>> consults(){
        var me=actor(false);var args=new ArrayList<Object>();String scope="";
        if(!crossRead()&&!role("NURSE")){
            scope=" and (a.doctor_id is null or a.doctor_id=? or c.to_dept_id=?)";args.add(me.id());args.add(me.dept());
        }
        return jdbc.queryForList("""
            select c.id,c.question,c.status,c.opinion,c.created_at,c.to_dept_id,
                   a.admission_no,p.name as patient_name,d.name as to_dept_name
            from inp_consult c join inp_admission a on a.id=c.admission_id
            join empi_patient p on p.id=a.patient_id join sys_dept d on d.id=c.to_dept_id
            where 1=1
            """+scope+" order by c.status desc,c.id desc limit 100",args.toArray());
    }
    @Transactional
    public void completeConsult(Long id,String opinion){
        var parent=jdbc.queryForList("select admission_id from inp_consult where id=?",id);
        if(parent.isEmpty())throw new HipBizException(9903,"会诊单不存在或已完成");
        Long admission=number(parent.getFirst().get("admission_id"));
        jdbc.queryForList("select id from inp_admission where id=? for update",admission);
        var rows=jdbc.queryForList("select admission_id,to_dept_id,status from inp_consult where id=? for update",id);
        if(rows.isEmpty())throw new HipBizException(9903,"会诊单不存在或已完成");
        var me=actor(true);var row=rows.getFirst();
        if(!admission.equals(number(row.get("admission_id"))))throw denied();
        if(!role("ADMIN")&&(!role("DOCTOR_OUTP")||me.dept()==null||!me.dept().equals(number(row.get("to_dept_id")))))throw denied();
        if(!"REQUESTED".equals(row.get("status")))throw new HipBizException(9903,"会诊单不存在或已完成");
        jdbc.update("update inp_consult set status='DONE',opinion=?,consultant_id=?,done_at=now() where id=?",opinion,me.id(),id);
    }

    private void eligibleExaminer(Long id){
        if(id==null)return;
        var rows=jdbc.queryForList("""
            select u.id from sys_user u where u.id=? and u.enabled=true and exists(
                select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                where ur.user_id=u.id and r.code in ('DOCTOR_OUTP','ADMIN')) for share of u
            """,id);
        if(rows.isEmpty())throw new HipBizException(9916,"总检医生不存在、已停用或无医生岗位");
    }
    private Long registrationExaminer(Long requested,Actor me){
        if(!role("ADMIN")&&!role("NURSE")){
            if(requested!=null&&!requested.equals(me.id()))throw denied();
            requested=me.id();
        }
        eligibleExaminer(requested);return requested;
    }
    private void enabledPackage(Long id){
        if(jdbc.queryForList("select id from pe_exam_package where id=? and enabled for share",id).isEmpty())throw new HipBizException(9912,"体检套餐不存在");
    }
    @Transactional
    public void registerExam(Long patient,Long pkg,Long requested){
        var me=actor(true);Long examiner=registrationExaminer(requested,me);enabledPackage(pkg);
        jdbc.update("insert into pe_exam_record(patient_id,package_id,registrar_id,examiner_id) values (?,?,?,?)",patient,pkg,me.id(),examiner);
    }
    @Transactional
    public Map<String,Object> createGroup(String unit,String contact,Long pkg,List<String> names,Long requested){
        if(names==null||names.isEmpty())throw new HipBizException(9909,"团检人员名单不能为空");
        if(names.size()>2000)throw new HipBizException(9915,"单批团检不超过 2000 人，请拆分名单分次提交");
        var me=actor(true);Long examiner=registrationExaminer(requested,me);enabledPackage(pkg);
        Long group=jdbc.queryForObject("insert into pe_group(unit_name,contact,package_id,creator_id) values (?,?,?,?) returning id",Long.class,unit,contact,pkg,me.id());
        for(String name:names){var p=new cn.hip.platform.empi.entity.Patient();p.setName(name);p.setSex("U");Long patient=patients.register(p).getId();
            jdbc.update("insert into pe_exam_record(patient_id,package_id,group_id,registrar_id,examiner_id) values (?,?,?,?,?)",patient,pkg,group,me.id(),examiner);}
        return Map.of("groupId",group,"members",names.size());
    }
    @Transactional
    public void completeExam(Long id,String summary,boolean abnormal){
        var rows=jdbc.queryForList("select status,examiner_id from pe_exam_record where id=? for update",id);
        if(rows.isEmpty())throw new HipBizException(9905,"体检记录不存在或已完成");
        var me=actor(true);var row=rows.getFirst();
        if(!role("ADMIN")&&(!role("DOCTOR_OUTP")||!me.id().equals(number(row.get("examiner_id")))))throw denied();
        if(!"REGISTERED".equals(row.get("status")))throw new HipBizException(9905,"体检记录不存在或已完成");
        jdbc.update("update pe_exam_record set status='DONE',summary=?,abnormal=? where id=?",summary,abnormal,id);
    }
    @Transactional
    public void assignExaminer(Long id,Long examiner){
        var rows=jdbc.queryForList("select status from pe_exam_record where id=? for update",id);
        if(rows.isEmpty())throw new HipBizException(9905,"体检记录不存在或已完成");
        actor(true);if(!role("ADMIN"))throw denied();
        if(!"REGISTERED".equals(rows.getFirst().get("status")))throw new HipBizException(9905,"体检记录不存在或已完成");
        if(examiner==null)throw new HipBizException(9916,"总检医生不存在、已停用或无医生岗位");
        eligibleExaminer(examiner);jdbc.update("update pe_exam_record set examiner_id=? where id=?",examiner,id);
    }
    private String examScope(String alias,Actor me,List<Object> args){
        if(crossRead())return "1=1";
        var parts=new ArrayList<String>();
        if(role("DOCTOR_OUTP")){parts.add(alias+".examiner_id=?");args.add(me.id());}
        if(role("NURSE")){parts.add(alias+".registrar_id=?");args.add(me.id());}
        return parts.isEmpty()?"1=0":"("+String.join(" or ",parts)+")";
    }
    public List<Map<String,Object>> exams(){
        var me=actor(false);var args=new ArrayList<Object>();String scope=examScope("r",me,args);
        return jdbc.queryForList("""
            select r.id,r.status,r.summary,r.created_at,r.registrar_id,r.examiner_id,u.real_name as examiner_name,
                   p.name as patient_name,k.name as package_name,k.price
            from pe_exam_record r join empi_patient p on p.id=r.patient_id
            join pe_exam_package k on k.id=r.package_id left join sys_user u on u.id=r.examiner_id where
            """+scope+" order by r.id desc limit 100",args.toArray());
    }
    public List<Map<String,Object>> groups(){
        var me=actor(false);var args=new ArrayList<Object>();String scope=examScope("r",me,args);
        return jdbc.queryForList("""
            select g.id,g.unit_name,g.contact,k.name as package_name,count(r.id) as members,
                   count(r.id) filter(where r.status='DONE') as done,
                   count(r.id) filter(where r.abnormal) as abnormal_cnt,
                   round(count(r.id) filter(where r.abnormal)::numeric
                     /nullif(count(r.id) filter(where r.status='DONE'),0)*100,1) as abnormal_rate
            from pe_group g join pe_exam_package k on k.id=g.package_id
            left join pe_exam_record r on r.group_id=g.id where
            """+scope+" group by g.id,g.unit_name,g.contact,k.name order by g.id desc",args.toArray());
    }
    public List<Map<String,Object>> groupRecords(Long id){
        var me=actor(false);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pe_group where id=?)",Boolean.class,id)))throw denied();
        var args=new ArrayList<Object>();args.add(id);String scope=examScope("r",me,args);
        var rows=jdbc.queryForList("""
            select r.id,r.status,r.summary,r.abnormal,r.registrar_id,r.examiner_id,u.real_name as examiner_name,p.name as patient_name
            from pe_exam_record r join empi_patient p on p.id=r.patient_id left join sys_user u on u.id=r.examiner_id where r.group_id=? and
            """+scope+" order by r.id",args.toArray());
        if(rows.isEmpty()&&!crossRead())throw denied();
        return rows;
    }
}
