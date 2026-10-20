package cn.hip.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @AutoConfigureMockMvc @Transactional
class V102EmpiReadTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;
    static final Set<String> KEYS=Set.of("id","patientNo","name","sex","birthDate","age","idType","idNo","phone","address","insuranceType","bloodType","allergyHistory");
    String actor(){String n="v102"+System.nanoTime();jdbc.update("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true)",n,n);return n;}
    long patient(String name){return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex,birth_date,id_type,id_no,phone,address,insurance_type,blood_type,allergy_history) values (?,?,'U','1995-01-01','PASSPORT',?,'13800001111','V102住址','YB_STAFF','A','V102过敏') returning id",Long.class,"V102"+System.nanoTime(),name,"V102"+System.nanoTime());}
    JsonNode call(String method,String path,Object body,String name,String...roles)throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req="POST".equals(method)?post(path):"PUT".equals(method)?put(path):get(path);
            req.with(user(name).roles(roles));
            if(body!=null)req.contentType("application/json").content(om.writeValueAsString(body));
            var res=mvc.perform(req).andReturn().getResponse();
            assertTrue(res.getStatus()==200||res.getStatus()==403,"HTTP "+res.getStatus());
            em.flush();return om.readTree(res.getContentAsString(StandardCharsets.UTF_8));
        } finally { SecurityContextHolder.clearContext(); }
    }
    JsonNode ok(String method,String path,Object body,String who,String...roles)throws Exception {
        var res=call(method,path,body,who,roles);assertEquals(0,res.path("code").asInt(),res.toString());return res.path("data");
    }
    JsonNode lookup(long id,String who,String...roles)throws Exception {
        String no=jdbc.queryForObject("select patient_no from empi_patient where id=?",String.class,id);
        var data=ok("GET","/api/patients?keyword="+no,null,who,roles);assertEquals(1,data.path("total").asInt());return data.path("records").get(0);
    }
    void projection(JsonNode row,boolean admin,boolean clinical,boolean billing) {
        var keys=new HashSet<String>();row.fieldNames().forEachRemaining(keys::add);assertEquals(KEYS,keys);
        assertEquals(admin?"13800001111":"138****1111",row.path("phone").asText());
        assertEquals("PASSPORT",row.path("idType").asText());assertFalse(row.path("name").asText().isEmpty());
        if(clinical){assertEquals("A",row.path("bloodType").asText());assertEquals("V102过敏",row.path("allergyHistory").asText());}
        else {assertTrue(row.path("bloodType").isNull(),row.toString());assertTrue(row.path("allergyHistory").isNull(),row.toString());}
        if(billing){assertEquals("V102住址",row.path("address").asText());assertEquals("YB_STAFF",row.path("insuranceType").asText());}
        else {assertTrue(row.path("address").isNull(),row.toString());assertTrue(row.path("insuranceType").isNull(),row.toString());}
    }
    @Test void cashierSearchDoesNotExposeClinicalRiskFields()throws Exception {projection(lookup(patient("V102收费"),actor(),"CASHIER"),false,false,true);}
    @Test void technicianAndQualitySearchOnlyReturnSharedIndexFields()throws Exception {
        long id=patient("V102只读");String who=actor();for(String role:List.of("TECHNICIAN","QUALITY"))projection(lookup(id,who,role),false,false,false);
    }
    @Test void clinicalAndAdminKeepOriginalDataAndIdentityMasking()throws Exception {
        long id=patient("V102临床");String who=actor();for(String role:List.of("ADMIN","DOCTOR_OUTP","NURSE"))projection(lookup(id,who,role),role.equals("ADMIN"),true,true);
    }
    @Test void cashierIdempotentRegistrationDoesNotBecomeAClinicalReadBypass()throws Exception {
        long id=patient("V102幂等");var before=jdbc.queryForMap("select * from empi_patient where id=?",id);
        String card=(String)before.get("id_no");
        var row=ok("POST","/api/patients",Map.of("name","不得覆盖","idType","PASSPORT","idNo",card),actor(),"CASHIER");
        assertEquals(id,row.path("id").asLong());projection(row,false,false,true);assertEquals(before,jdbc.queryForMap("select * from empi_patient where id=?",id));
    }
    @Test void cashierEmptyUpdateDoesNotBecomeAClinicalReadBypassOrEraseData()throws Exception {
        long id=patient("V102空更新");var before=jdbc.queryForMap("select * from empi_patient where id=?",id);
        projection(ok("PUT","/api/patients/"+id,Map.of(),actor(),"CASHIER"),false,false,true);
        assertEquals(before,jdbc.queryForMap("select * from empi_patient where id=?",id));
    }
    @Test void mixedDutiesUseTheirUnionWithoutUnmaskingIdentity()throws Exception {
        long id=patient("V102兼岗");String who=actor();
        projection(lookup(id,who,"QUALITY","TECHNICIAN"),false,false,false);
        projection(lookup(id,who,"QUALITY","CASHIER"),false,false,true);
        projection(lookup(id,who,"TECHNICIAN","DOCTOR_OUTP"),false,true,true);
        projection(lookup(id,who,"QUALITY","NURSE"),false,true,true);
        projection(lookup(id,who,"QUALITY","ADMIN"),true,true,true);
    }
    @Test void clinicalWriteResponsesKeepClinicalFieldsAndMaskedIdentity()throws Exception {
        long id=patient("V102写返回");String who=actor();
        for(String role:List.of("NURSE","DOCTOR_OUTP","ADMIN"))projection(ok("PUT","/api/patients/"+id,Map.of(),who,role),role.equals("ADMIN"),true,true);
        String card=jdbc.queryForObject("select id_no from empi_patient where id=?",String.class,id);
        projection(ok("POST","/api/patients",Map.of("name","不改","idType","PASSPORT","idNo",card),who,"NURSE"),false,true,true);
    }
    @Test void reducedProjectionDoesNotChangePaginationOrTotals()throws Exception {
        String prefix="V102PAGE"+System.nanoTime();for(int i=0;i<3;i++)patient(prefix+i);
        var data=ok("GET","/api/patients?keyword="+prefix+"&page=1&size=2",null,actor(),"QUALITY");
        assertEquals(3,data.path("total").asInt());assertEquals(1,data.path("records").size());projection(data.path("records").get(0),false,false,false);
    }
    @Test void clientProjectionFlagsCannotGrantBroaderFields()throws Exception {
        long id=patient("V102伪造");String no=jdbc.queryForObject("select patient_no from empi_patient where id=?",String.class,id);
        var row=ok("GET","/api/patients?keyword="+no+"&role=ADMIN&clinical=true&fields=bloodType,address",null,actor(),"QUALITY").path("records").get(0);
        projection(row,false,false,false);
    }
    @Test void disabledUnknownAndUnrelatedRolesAreStillRejected()throws Exception {
        String disabled=actor();jdbc.update("update sys_user set enabled=false where username=?",disabled);
        for(String who:List.of(disabled,"v102ghost"))assertEquals(1005,call("GET","/api/patients",null,who,"ADMIN").path("code").asInt());
        String enabled=actor();for(String role:List.of("PHARMACIST","OPERATION","INTERFACE"))assertEquals(1005,call("GET","/api/patients",null,enabled,role).path("code").asInt());
    }
}
