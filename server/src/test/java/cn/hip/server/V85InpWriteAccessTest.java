package cn.hip.server;

import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import cn.hip.inpatient.service.InpatientService;
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
class V85InpWriteAccessTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper om; @Autowired JdbcTemplate jdbc;
    @Autowired PatientService patients; @Autowired InpatientService service; @Autowired EntityManager em;
    private String doctor() {
        String name="v85"+System.nanoTime();
        jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",name,name);return name;
    }
    private Long uid(String who) {return jdbc.queryForObject("select id from sys_user where username=?",Long.class,who);}
    private Long admission(String owner) {
        Patient p=new Patient();p.setName("V85"+System.nanoTime());p.setSex("U");
        Long pid=patients.register(p).getId();
        Long bed=jdbc.queryForObject("select id from inp_bed where status='FREE' order by id limit 1",Long.class);
        Long id=service.admit(pid,1L,bed,owner==null?null:uid(owner),null,null,BigDecimal.ZERO,"CASH",null).getId();
        em.flush();return id;
    }
    private Long order(Long adm,boolean longer) {
        return jdbc.queryForObject("""
            insert into inp_order(admission_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,order_nature)
            values (?,'V85','TREAT',1,'V85','权限测试','次',1,0,0,'CREATED',?) returning id
            """,Long.class,adm,longer?"LONG":"TEMP");
    }
    private int req(String method,String path,Object body,String who,String role) throws Exception {
        var request=switch(method){case "POST"->post(path);case "PUT"->put(path);default->get(path);};
        request.with(user(who).roles(role));if(body!=null)request.contentType("application/json").content(om.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();
        if(response.getStatus()==403)return 1005;
        assertEquals(200,response.getStatus());return om.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("code").asInt();
    }
    private Map<String,Object> state(Long id) {return jdbc.queryForMap("select doctor_id,discharge_diag_icd,discharge_diag_name from inp_admission where id=?",id);}
    @Test void otherDoctorCannotTakeOwnershipOrChangeDischargeDiagnosis() throws Exception {
        String a=doctor(),b=doctor();Long id=admission(a);Map<String,Object> before=state(id);
        assertEquals(9018,req("PUT","/api/inpatient/admissions/"+id+"/attending-doctor",Map.of("doctorId",uid(b)),b,"DOCTOR_OUTP"));
        assertEquals(9018,req("PUT","/api/inpatient/admissions/"+id+"/discharge-diag",Map.of("icd","Z00","name","越权"),b,"DOCTOR_OUTP"));
        assertEquals(before,state(id));
    }
    @Test void foreignOrderDecisionsRejectWithoutChangingOrdersOrExecutionLines() throws Exception {
        String a=doctor(),b=doctor();Long adm=admission(a),temp=order(adm,false),longer=order(adm,true);
        jdbc.update("insert into inp_order_exec(order_id,exec_date,seq_no,amount) values (?,current_date,1,0)",longer);
        var before=jdbc.queryForList("select * from inp_order where admission_id=? order by id",adm);
        var execBefore=jdbc.queryForList("select * from inp_order_exec where order_id=?",longer);
        assertEquals(9018,req("POST","/api/inpatient/admissions/"+adm+"/orders",Map.of("lines",List.of()),b,"DOCTOR_OUTP"));
        assertEquals(9018,req("PUT","/api/inpatient/orders/"+temp+"/cancel",null,b,"DOCTOR_OUTP"));
        assertEquals(9018,req("POST","/api/inpatient/orders/"+longer+"/stop",Map.of(),b,"DOCTOR_OUTP"));
        assertEquals(before,jdbc.queryForList("select * from inp_order where admission_id=? order by id",adm));
        assertEquals(execBefore,jdbc.queryForList("select * from inp_order_exec where order_id=?",longer));
    }
    @Test void nurseKeepsExecutionAndVitalsButCannotMakePhysicianDecisions() throws Exception {
        String a=doctor(),n=doctor();Long adm=admission(a),temp=order(adm,false),longer=order(adm,true);
        String base="/api/inpatient/admissions/"+adm;
        assertEquals(1005,req("PUT",base+"/attending-doctor",Map.of("doctorId",uid(n)),n,"NURSE"));
        assertEquals(1005,req("PUT",base+"/discharge-diag",Map.of("icd","Z00","name","护士改诊断"),n,"NURSE"));
        assertEquals(1005,req("POST",base+"/orders",Map.of("lines",List.of()),n,"NURSE"));
        assertEquals(1005,req("PUT","/api/inpatient/orders/"+temp+"/cancel",null,n,"NURSE"));
        assertEquals(1005,req("POST","/api/inpatient/orders/"+longer+"/stop",Map.of(),n,"NURSE"));
        assertEquals(0,req("PUT","/api/inpatient/orders/"+temp+"/execute",null,n,"NURSE"));
        assertEquals(0,req("POST",base+"/vitals",Map.of("temperature",36.5,"pulse",80),n,"NURSE"));
    }
    @Test void ownerAdminUnassignedAndHandoffRemainUsable() throws Exception {
        String a=doctor(),b=doctor();Long adm=admission(a);String base="/api/inpatient/admissions/"+adm;
        assertEquals(0,req("POST",base+"/orders",Map.of("lines",List.of()),a,"DOCTOR_OUTP"));
        assertEquals(0,req("PUT",base+"/discharge-diag",Map.of("icd","Z00","name","本人"),a,"DOCTOR_OUTP"));
        assertEquals(0,req("PUT",base+"/attending-doctor",Map.of("doctorId",uid(b)),a,"DOCTOR_OUTP"));
        assertEquals(9018,req("POST",base+"/orders",Map.of("lines",List.of()),a,"DOCTOR_OUTP"));
        assertEquals(0,req("POST",base+"/orders",Map.of("lines",List.of()),b,"DOCTOR_OUTP"));
        assertEquals(0,req("PUT",base+"/attending-doctor",Map.of("doctorId",uid(a)),"admin","ADMIN"));
        Long unassigned=admission(null);
        assertEquals(0,req("PUT","/api/inpatient/admissions/"+unassigned+"/attending-doctor",Map.of("doctorId",uid(a)),a,"DOCTOR_OUTP"));
    }
}
