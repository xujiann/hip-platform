package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional @WithMockUser(roles="ADMIN")
class V87InpCareAccessTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired PatientService patients;
    @Autowired InpatientService service;
    @Autowired EntityManager em;
    private String doctor() {
        String name="v87"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",name,name);
        jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code='DOCTOR_OUTP' on conflict do nothing",name);
        return name;
    }
    private Long uid(String who) {return jdbc.queryForObject("select id from sys_user where username=?",Long.class,who);}
    private Long admission(String owner) {
        Patient p=new Patient();p.setName("V87虚构"+System.nanoTime());p.setSex("U");
        Long id=service.admit(patients.register(p).getId(),1L,freeBed(),owner==null?null:uid(owner),null,null,BigDecimal.ZERO,"CASH",null).getId();
        em.flush();
        jdbc.update("""
            insert into inp_order(admission_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status)
            values (?,'V87','TREAT',1,'V87','已执行权限夹具','次',1,1,1,'EXECUTED')
            """,id);
        return id;
    }
    private Long freeBed() {return jdbc.queryForObject("select id from inp_bed where status='FREE' order by id limit 1",Long.class);}
    private int req(String action,Long adm,Object body,String who,String... roles) throws Exception {
        var request=post("/api/inpatient/admissions/"+adm+"/"+action).with(user(who).roles(roles));
        if(body!=null)request.contentType("application/json").content(om.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();
        assertTrue(response.getStatus()==200 || response.getStatus()==403);
        return om.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asInt();
    }
    private Map<String,Object> transferBody() {return Map.of("toDeptId",1,"toBedId",freeBed(),"reason","权限验收");}
    private Map<String,Object> state(Long adm) {
        em.flush();
        return Map.of("admission",jdbc.queryForMap("select * from inp_admission where id=?",adm),
            "beds",jdbc.queryForList("select * from inp_bed order by id"),
            "transfers",jdbc.queryForList("select * from inp_transfer_log where admission_id=? order by id",adm),
            "orders",jdbc.queryForList("select * from inp_order where admission_id=? order by id",adm),
            "settlements",jdbc.queryForList("select * from inp_settlement where admission_id=? order by id",adm));
    }
    private void foreignDenied(String action) throws Exception {
        String a=doctor(),b=doctor();Long adm=admission(a);var before=state(adm);
        assertEquals(9018,req(action,adm,action.equals("transfer")?transferBody():Map.of(),b,"DOCTOR_OUTP"));
        assertEquals(before,state(adm),"拒绝不能改床位、归属、状态、留痕、金额或医嘱标记");
    }
    @Test void foreignDoctorCannotTransfer() throws Exception {foreignDenied("transfer");}
    @Test void foreignDoctorCannotDischarge() throws Exception {foreignDenied("discharge");}
    @Test void foreignDoctorCannotInterimSettle() throws Exception {foreignDenied("interim-settle");}
    private void normalFlow(String owner,String who,String... roles) throws Exception {
        Long adm=admission(owner);Long target=freeBed();
        assertEquals(0,req("transfer",adm,Map.of("toDeptId",1,"toBedId",target,"reason","正常转床"),who,roles));
        em.flush();
        assertEquals(target,jdbc.queryForObject("select bed_id from inp_admission where id=?",Long.class,adm));
        assertEquals(0,req("interim-settle",adm,Map.of(),who,roles));
        assertEquals(0,req("discharge",adm,Map.of(),who,roles));
        assertEquals("DISCHARGED",jdbc.queryForObject("select status from inp_admission where id=?",String.class,adm));
    }
    @Test void ownerAdminNurseAndUnassignedFlowsRemainAvailable() throws Exception {
        String a=doctor(),n=doctor();
        normalFlow(a,a,"DOCTOR_OUTP");
        normalFlow(a,"admin","ADMIN");
        normalFlow(a,n,"NURSE");
        normalFlow(null,a,"DOCTOR_OUTP");
    }
    @Test void cashierKeepsInterimButCannotTransferOrDischarge() throws Exception {
        String a=doctor(),c=doctor();Long adm=admission(a);
        assertEquals(0,req("interim-settle",adm,Map.of(),c,"CASHIER"));
        var before=state(adm);
        assertEquals(1005,req("transfer",adm,transferBody(),c,"CASHIER"));
        assertEquals(1005,req("discharge",adm,Map.of(),c,"CASHIER"));
        assertEquals(before,state(adm));
        // An account with actual nursing responsibilities retains that workflow.
        assertEquals(0,req("transfer",adm,transferBody(),c,"DOCTOR_OUTP","NURSE"));
    }
    @Test void unknownDoctorIsDeniedEvenWhenUnassignedAndFormerOwnerLosesAccess() throws Exception {
        String a=doctor(),b=doctor();Long adm=admission(null);
        for(String action:List.of("transfer","discharge","interim-settle")) {
            var before=state(adm);
            assertEquals(9018,req(action,adm,action.equals("transfer")?transferBody():Map.of(),"v87-unknown","DOCTOR_OUTP"));
            assertEquals(before,state(adm));
        }
        jdbc.update("update inp_admission set doctor_id=? where id=?",uid(b),adm);
        for(String action:List.of("transfer","discharge","interim-settle"))
            assertEquals(9018,req(action,adm,action.equals("transfer")?transferBody():Map.of(),a,"DOCTOR_OUTP"));
        assertEquals(0,req("interim-settle",adm,Map.of(),b,"DOCTOR_OUTP"));
    }
}
