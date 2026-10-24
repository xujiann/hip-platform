package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real signed JWT/HTTP reads and committed PG fixtures, no test-level transaction.
 * Historical invalid rows are explicit SQL fixtures, not claims that current write APIs can create them.
 * No locks are needed by the single UNION read; commit/rollback visibility is tested separately. */
@SpringBootTest
@AutoConfigureMockMvc
class V120PortalReportReadTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired cn.hip.portal.web.PortalController portal;
    String tag;
    Long dept, schedule, patient, otherPatient, registration, otherRegistration, order, exam, bill;
    final List<Long> staff = new ArrayList<>();

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        tag="V120"+Long.toUnsignedString(System.nanoTime(),36);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"本人");
        otherPatient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag+"Q",tag+"旁人");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,2,2,0) returning id",Long.class,dept);
        registration=registration(patient,1);otherRegistration=registration(otherPatient,2);
        order=order(registration,"E","EXAM","EXECUTED");
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status) values (?,?,7.50,'CASH','PAID') returning id",Long.class,tag,registration);
        jdbc.update("update outp_order set charge_id=? where id=?",bill,order);
        exam=jdbc.queryForObject("""
                insert into ris_exam(order_id,status,findings,impression,reported_at,verified_at,modality)
                values (?,'VERIFIED',?,?,now()-interval '2 day',date_trunc('second',now())-interval '1 day'+interval '0.123456 second','CT') returning id
                """,Long.class,order,tag+"所见",tag+"结论");
    }
    Long registration(Long p,int no) {
        return jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (?,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,no,p,schedule,dept);
    }
    Long order(Long reg,String suffix,String type,String state) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,remark)
                values (?,?,?,0,?,?,'次',1,7.50,7.50,?,'原备注') returning id
                """,Long.class,reg,tag+suffix,type,tag+suffix,tag+suffix+"项目",state);
    }
    Long specimen(String state,boolean issued,boolean rejected) {
        Long source=order(registration,"P","EXAM",state);
        return jdbc.queryForObject("""
                insert into path_specimen(order_id,barcode,path_no,specimen_type,specimen_desc,status,diagnosis,micro_finding,
                    collected_at,received_at,diagnosed_at,report_issued_at,rejected_at)
                values (?,?,?,'ROUTINE','模拟实物','DIAGNOSED',?,?,now()-interval '3 day',now()-interval '2 day',now()-interval '1 day',
                    case when ? then date_trunc('second',now())+interval '0.654321 second' else null end,
                    case when ? then now() else null end) returning id
                """,Long.class,source,tag+"B",tag+"P",tag+"病理诊断",tag+"镜下",issued,rejected);
    }
    String patientToken(Long p) {return jwt.issue("portal:"+p);}
    String staffToken(String role) {
        String username=tag+role;
        Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,username,username);
        staff.add(id);jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);
        return jwt.issue(username);
    }
    JsonNode request(String token,String query,int status,int code) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=get("/api/portal/my/exam-reports"+query);
            if(token!=null)req.header("Authorization","Bearer "+token);
            var response=mvc.perform(req).andReturn().getResponse();
            assertEquals(status,response.getStatus());
            var json=mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(code,json.get("code").asInt());return json.get("data");
        } finally {SecurityContextHolder.clearContext();}
    }
    JsonNode reports(Long p) throws Exception {return request(patientToken(p),"",200,0);}
    Object facts() {
        return List.of(jdbc.queryForList("select * from empi_patient where id in (?,?) order by id",patient,otherPatient),
                jdbc.queryForList("select * from outp_registration where id in (?,?) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_order where registration_id in (?,?) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_charge where registration_id in (?,?) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from ris_exam where order_id in (select id from outp_order where registration_id in (?,?)) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from path_specimen where order_id in (select id from outp_order where registration_id in (?,?)) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_schedule where id=?",schedule));
    }
    void assertEmptyAndReadOnly() throws Exception {var before=facts();assertTrue(reports(patient).isEmpty());assertEquals(before,facts());}
    Instant instant(Object value) {return value instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)value).toInstant();}
    void assertPublicationTime(JsonNode wire, String column, String table, Long id) {
        Object original=jdbc.queryForObject("select "+column+" from "+table+" where id=?",Object.class,id);
        // Existing HTTP Timestamp serialization is millisecond precision; this round must not
        // silently change that contract or mislabel its pre-existing precision as a new defect.
        assertEquals(mapper.valueToTree(original),wire.get("report_date"));
        var identity=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("portal:"+patient,null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PORTAL")));
        var sql=portal.myExamReports(identity).getData().stream()
                .filter(row->wire.get("conclusion").asText().equals(row.get("conclusion"))).findFirst().orElseThrow();
        assertEquals(instant(original),instant(sql.get("report_date")),"The selected SQL publication timestamp retains its microseconds");
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        new TransactionTemplate(manager).executeWithoutResult(t->{
            jdbc.update("delete from path_specimen where order_id in (select id from outp_order where registration_id in (?,?))",registration,otherRegistration);
            jdbc.update("delete from ris_exam where order_id in (select id from outp_order where registration_id in (?,?))",registration,otherRegistration);
            jdbc.update("delete from outp_order where registration_id in (?,?)",registration,otherRegistration);
            jdbc.update("delete from outp_charge where registration_id in (?,?)",registration,otherRegistration);
            jdbc.update("delete from outp_registration where id in (?,?)",registration,otherRegistration);
            jdbc.update("delete from outp_schedule where id=?",schedule);
            jdbc.update("delete from empi_patient where id in (?,?)",patient,otherPatient);
            for(Long id:staff){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
            jdbc.update("delete from sys_dept where id=?",dept);
        });staff.clear();
    }

    @ParameterizedTest @ValueSource(strings={"CHARGED","EXECUTED"})
    void validExamKeepsFiveKeysOriginalWireTimeAndSqlMicroseconds(String state) throws Exception {
        jdbc.update("update outp_order set status=? where id=?",state,order);var before=facts();
        var rows=reports(patient);assertEquals(1,rows.size());var row=rows.get(0);
        var keys=new java.util.HashSet<String>();row.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("report_type","item_name","conclusion","detail","report_date"),keys);
        assertEquals("EXAM",row.get("report_type").asText());assertEquals(tag+"E项目",row.get("item_name").asText());
        assertEquals(tag+"所见",row.get("detail").asText());assertEquals(tag+"结论",row.get("conclusion").asText());
        assertPublicationTime(row,"verified_at","ris_exam",exam);
        assertEquals(before,facts());
    }

    @ParameterizedTest @CsvSource({"EXAM,CREATED","EXAM,CANCELLED","EXAM,DISPENSED","LAB,CHARGED","LAB,EXECUTED","DRUG,CHARGED","DRUG,EXECUTED","TREAT,CHARGED","TREAT,EXECUTED"})
    void historicalVerifiedWrongSourceIsFilteredWithoutClinicalOrBillingWrites(String type,String status) throws Exception {
        jdbc.update("update outp_order set order_type=?,status=? where id=?",type,status,order);
        if("CREATED".equals(status)){jdbc.update("update outp_charge set status='REFUNDED' where id=?",bill);jdbc.update("update outp_order set charge_id=null where id=?",order);}
        assertEmptyAndReadOnly();
    }

    @ParameterizedTest @ValueSource(strings={"REGISTERED","ARRIVED","REPORTED"})
    void unverifiedExamsStayPrivate(String state) throws Exception {jdbc.update("update ris_exam set status=? where id=?",state,exam);assertEmptyAndReadOnly();}

    @Test void historicalVerifiedNullTimestampDoesNotInventANewPublicationGate() throws Exception {
        jdbc.update("update ris_exam set verified_at=null where id=?",exam);var before=facts();var rows=reports(patient);
        assertEquals(1,rows.size());assertTrue(rows.get(0).get("report_date").isNull());assertEquals(before,facts());
    }

    @Test void signedPatientSubjectWinsOverEveryClientPatientIdAndOtherPatientCannotReadIt() throws Exception {
        var before=facts();assertTrue(request(patientToken(otherPatient),"?patientId="+patient+"&registrationId="+registration+"&orderId="+order,200,0).isEmpty());
        assertEquals(1,request(patientToken(patient),"?patientId="+otherPatient,200,0).size());assertEquals(before,facts());
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE","CASHIER","PHARMACIST","INTERFACE"})
    void RealEmployeeJwtCannotEnterPatientReportDomain(String role) throws Exception {var before=facts();assertTrue(request(staffToken(role),"",403,1005).isNull());assertEquals(before,facts());}

    @Test void anonymousRequestDoesNotInheritThePreviousSuccessfulPortalContext() throws Exception {
        assertEquals(1,reports(patient).size());var before=facts();assertTrue(request(null,"",401,1000).isNull());assertEquals(before,facts());
    }

    @ParameterizedTest @ValueSource(strings={"CREATED","CHARGED","EXECUTED","CANCELLED"})
    void signedPhysicalPathologyKeepsOriginalUnpaidRefundAndSourceStateCompatibility(String state) throws Exception {
        jdbc.update("update ris_exam set status='REPORTED' where id=?",exam);Long s=specimen(state,true,false);var before=facts();
        var rows=reports(patient);assertEquals(1,rows.size());var row=rows.get(0);assertEquals("PATH",row.get("report_type").asText());
        assertEquals(tag+"病理诊断",row.get("conclusion").asText());assertEquals(tag+"镜下",row.get("detail").asText());
        assertPublicationTime(row,"report_issued_at","path_specimen",s);
        assertTrue(reports(otherPatient).isEmpty());assertEquals(before,facts());
    }

    @ParameterizedTest @CsvSource({"false,false","true,true"})
    void unsentOrRejectedPathologyStillDoesNotReachPatient(boolean issued,boolean rejected) throws Exception {
        jdbc.update("update ris_exam set status='REPORTED' where id=?",exam);specimen("CREATED",issued,rejected);assertEmptyAndReadOnly();
    }

    @Test void pathologyAndExamStillMixByOriginalPublicationDates() throws Exception {
        specimen("CREATED",true,false);var before=facts();var rows=reports(patient);assertEquals(2,rows.size());
        assertEquals("PATH",rows.get(0).get("report_type").asText());assertEquals("EXAM",rows.get(1).get("report_type").asText());assertEquals(before,facts());
    }

    @Test void committedWithdrawalChangesTheNextReadAndRollbackRestoresTheOriginalVisibleFact() throws Exception {
        assertEquals(1,reports(patient).size());
        new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order));
        assertEmptyAndReadOnly();
        new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.update("update outp_order set status='EXECUTED',charge_id=? where id=?",bill,order);t.setRollbackOnly();});
        assertEmptyAndReadOnly();
        jdbc.update("update outp_order set status='EXECUTED',charge_id=? where id=?",bill,order);assertEquals(1,reports(patient).size());
    }
}
