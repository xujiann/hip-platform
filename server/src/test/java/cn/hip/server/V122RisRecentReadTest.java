package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.platform.core.security.JwtService;
import cn.hip.platform.core.service.ConfigReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real signed JWT/HTTP reads against committed PostgreSQL fixtures; no enclosing test transaction.
 * Historical invalid sources are explicit SQL fixtures, not claims about current write APIs.
 * The exact window-edge and raw timestamp probes use direct JDBC/controller data separately from
 * the original HTTP millisecond serialization. Commit/rollback checks are not lock-race claims. */
@SpringBootTest
@AutoConfigureMockMvc
class V122RisRecentReadTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired ConfigReader config;
    @Autowired MedTechController med;
    String tag, admin, priorDays;
    Long dept, staffDept, patient, otherPatient, schedule, registration, otherRegistration, order, exam, bill;
    final List<Long> employees = new ArrayList<>();

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        tag="V122"+Long.toUnsignedString(System.nanoTime(),36);
        priorDays=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='ris.mutual.days'",String.class);
        setDays("30");
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        staffDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag+"S",tag+"异科");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'F') returning id",Long.class,tag,tag+"本人");
        otherPatient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'M') returning id",Long.class,tag+"Q",tag+"旁人");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,2,2,0) returning id",Long.class,dept);
        registration=registration(patient,1);otherRegistration=registration(otherPatient,2);
        order=order(registration,"E","EXAM","EXECUTED");
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status) values (?,?,7.50,'CASH','PAID') returning id",Long.class,tag,registration);
        jdbc.update("update outp_order set charge_id=? where id=?",bill,order);
        exam=exam(order,"VERIFIED",1);
        admin=staff("ADMIN");
    }
    void setDays(String value) {jdbc.update("update sys_config set cfg_value=? where cfg_key='ris.mutual.days'",value);config.evictAll();}
    Long registration(Long p,int no) {
        return jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (?,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,no,p,schedule,dept);
    }
    Long order(Long reg,String suffix,String type,String status) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status)
                values (?,?,?,0,?,?,'次',1,7.50,7.50,?) returning id
                """,Long.class,reg,tag+suffix,type,tag+suffix,tag+suffix+"项目",status);
    }
    Long exam(Long source,String status,int daysAgo) {
        return jdbc.queryForObject("""
                insert into ris_exam(order_id,status,findings,impression,modality,reported_at,verified_at)
                values (?,?,?,?,'CT',now()-interval '2 day',date_trunc('second',now())-make_interval(days=>?)+interval '0.123456 second') returning id
                """,Long.class,source,status,tag+"所见",tag+"结论",daysAgo);
    }
    String staff(String role) {
        String username=tag+role;
        Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,username,username,staffDept);
        employees.add(id);jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);
        return jwt.issue(username);
    }
    JsonNode request(Long p,String name,String token,int status,int code) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=get("/api/ris/recent-exams").param("patientId",p.toString());
            if(name!=null)req.param("itemName",name);
            if(token!=null)req.header("Authorization","Bearer "+token);
            var res=mvc.perform(req).andReturn().getResponse();
            assertEquals(status,res.getStatus());var body=mapper.readTree(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(code,body.get("code").asInt());return body.get("data");
        } finally {SecurityContextHolder.clearContext();}
    }
    JsonNode recent() throws Exception {return request(patient,null,admin,200,0);}
    Set<String> keys(JsonNode node) {var out=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(out::add);return out;}
    Object facts() {
        return List.of(jdbc.queryForList("select * from empi_patient where id in (?,?) order by id",patient,otherPatient),
                jdbc.queryForList("select * from outp_registration where id in (?,?) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_order where registration_id in (?,?) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_charge where registration_id in (?,?) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from ris_exam where order_id in (select id from outp_order where registration_id in (?,?)) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_lab_result where order_id in (select id from outp_order where registration_id in (?,?)) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from lis_sample where order_id in (select id from outp_order where registration_id in (?,?)) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_critical_alert where registration_id in (?,?) order by id",registration,otherRegistration),
                jdbc.queryForList("select * from outp_schedule where id=?",schedule),
                jdbc.queryForList("select * from cdr_document where patient_id in (?,?) order by id",patient,otherPatient),
                jdbc.queryForList("select * from sys_config where cfg_key='ris.mutual.days'"));
    }
    void emptyAndReadOnly() throws Exception {var before=facts();assertEquals(0,recent().size());assertEquals(before,facts());}
    void oneAndReadOnly() throws Exception {var before=facts();assertEquals(1,recent().size());assertEquals(before,facts());}
    Instant instant(Object value) {return value instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)value).toInstant();}
    void asAdmin() {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(tag+"ADMIN",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));}

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();config.evictAll();
        new TransactionTemplate(manager).executeWithoutResult(t->{
            jdbc.update("delete from ris_exam where order_id in (select id from outp_order where registration_id in (?,?))",registration,otherRegistration);
            jdbc.update("delete from outp_order where registration_id in (?,?)",registration,otherRegistration);
            jdbc.update("delete from outp_charge where registration_id in (?,?)",registration,otherRegistration);
            jdbc.update("delete from outp_registration where id in (?,?)",registration,otherRegistration);
            jdbc.update("delete from outp_schedule where id=?",schedule);
            jdbc.update("delete from empi_patient where id in (?,?)",patient,otherPatient);
            for(Long id:employees){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
            jdbc.update("delete from sys_dept where id in (?,?)",dept,staffDept);
            jdbc.update("update sys_config set cfg_value=? where cfg_key='ris.mutual.days'",priorDays);
        });employees.clear();config.evictAll();
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE"})
    void originalFourDutiesKeepFiveKeysAcrossDepartmentsAndPatients(String role) throws Exception {
        String token=role.equals("ADMIN")?admin:staff(role);Long other=exam(order(otherRegistration,"Q","EXAM","EXECUTED"),"VERIFIED",2);
        var before=facts();var rows=request(patient,null,token,200,0);assertEquals(1,rows.size());var row=rows.get(0);
        assertEquals(Set.of("id","item_name","modality","impression","verified_at"),keys(row));
        assertEquals(exam.longValue(),row.get("id").asLong());assertEquals(tag+"E项目",row.get("item_name").asText());
        assertEquals("CT",row.get("modality").asText());assertEquals(tag+"结论",row.get("impression").asText());
        assertEquals(mapper.valueToTree(jdbc.queryForObject("select verified_at from ris_exam where id=?",Object.class,exam)),row.get("verified_at"));
        var second=request(otherPatient,null,token,200,0);assertEquals(1,second.size());assertEquals(other.longValue(),second.get(0).get("id").asLong());assertEquals(before,facts());
    }
    @ParameterizedTest @ValueSource(strings={"CREATED","DISPENSED","CANCELLED","REFUNDED"})
    void historicalVerifiedReportsCannotExposeInvalidSourceStates(String state) throws Exception {
        jdbc.update("update outp_order set status=? where id=?",state,order);emptyAndReadOnly();
    }
    @ParameterizedTest @ValueSource(strings={"LAB","DRUG","TREAT"})
    void historicalVerifiedReportsCannotExposeNonExamSources(String type) throws Exception {
        jdbc.update("update outp_order set order_type=? where id=?",type,order);emptyAndReadOnly();
    }
    @ParameterizedTest @ValueSource(strings={"REGISTERED","ARRIVED","REPORTED"})
    void unpublishedReportsRemainExcludedEvenWithAVerificationTime(String state) throws Exception {jdbc.update("update ris_exam set status=? where id=?",state,exam);emptyAndReadOnly();}
    @ParameterizedTest @ValueSource(strings={"CASHIER","PHARMACIST","INTERFACE","PORTAL"})
    void wrongDomainsRemainForbiddenWithoutAClinicalPayload(String role) throws Exception {
        String token=role.equals("PORTAL")?jwt.issue("portal:"+patient):staff(role);var before=facts();assertTrue(request(patient,null,token,403,1005).isNull());assertEquals(before,facts());
    }
    @Test void anonymousDoesNotInheritThePreviousJwt() throws Exception {assertEquals(1,recent().size());var before=facts();assertTrue(request(patient,null,null,401,1000).isNull());assertEquals(before,facts());}
    @Test void disabledEmployeesExistingJwtRemainsUnauthenticated() throws Exception {jdbc.update("update sys_user set enabled=false where username=?",tag+"ADMIN");var before=facts();assertTrue(request(patient,null,admin,401,1000).isNull());assertEquals(before,facts());}
    @Test void legacyChargedVerifiedSourceIsStillPublished() throws Exception {jdbc.update("update outp_order set status='CHARGED' where id=?",order);oneAndReadOnly();}
    @Test void registrationStatusAndVisitAgeAreNotAdditionalReleaseConditions() throws Exception {jdbc.update("update outp_registration set status='CANCELLED',visit_date=current_date-365 where id=?",registration);oneAndReadOnly();}
    @Test void oldAndNullVerificationTimesAreExcluded() throws Exception {
        jdbc.update("update ris_exam set verified_at=now()-interval '31 day' where id=?",exam);emptyAndReadOnly();
        jdbc.update("update ris_exam set verified_at=null where id=?",exam);emptyAndReadOnly();
    }
    @Test void configuredWindowIsAppliedAfterCacheEviction() throws Exception {
        jdbc.update("update ris_exam set verified_at=now()-interval '8 day' where id=?",exam);oneAndReadOnly();setDays("7");emptyAndReadOnly();setDays("9");oneAndReadOnly();
    }
    @ParameterizedTest @ValueSource(strings={"","not-a-number"})
    void blankOrInvalidConfigurationKeepsDefaultThirtyDays(String value) throws Exception {
        setDays(value);jdbc.update("update ris_exam set verified_at=now()-interval '29 day' where id=?",exam);oneAndReadOnly();jdbc.update("update ris_exam set verified_at=now()-interval '31 day' where id=?",exam);emptyAndReadOnly();
    }
    @Test void zeroAndNegativeWindowsRetainOriginalFutureTimeSemantics() throws Exception {
        setDays("0");emptyAndReadOnly();jdbc.update("update ris_exam set verified_at=now()+interval '2 day' where id=?",exam);oneAndReadOnly();setDays("-1");oneAndReadOnly();
    }
    @Test void exactWindowBoundaryRemainsStrictlyGreaterAtTheSameDatabaseTransactionTime() {
        new TransactionTemplate(manager).executeWithoutResult(t->{
            asAdmin();
            try {
                jdbc.update("update ris_exam set verified_at=now()-interval '30 day' where id=?",exam);
                assertTrue(med.recentExams(patient,null).getData().isEmpty());
                jdbc.update("update ris_exam set verified_at=verified_at+interval '0.000001 second' where id=?",exam);
                assertEquals(1,med.recentExams(patient,null).getData().size());
            } finally {SecurityContextHolder.clearContext();}
            t.setRollbackOnly();
        });
    }
    @Test void itemNameIsExactWhileMissingAndBlankNamesDoNotFilter() throws Exception {
        var before=facts();assertEquals(1,request(patient,tag+"E项目",admin,200,0).size());
        for(String value:List.of("", "  ", "\t"))assertEquals(1,request(patient,value,admin,200,0).size());
        for(String value:List.of(tag+"E",tag+"E项目 ",tag+"e项目","%"))assertEquals(0,request(patient,value,admin,200,0).size());assertEquals(before,facts());
    }
    @Test void missingAndUnknownPatientReturnSuccessfulEmptyLists() throws Exception {var before=facts();assertEquals(0,request(-1L,null,admin,200,0).size());assertEquals(0,request(otherPatient,null,admin,200,0).size());assertEquals(before,facts());}
    @Test void currentRegistrationBindingDeterminesPatientOwnership() throws Exception {
        jdbc.update("update outp_order set registration_id=? where id=?",otherRegistration,order);var before=facts();assertEquals(0,recent().size());var rows=request(otherPatient,null,admin,200,0);assertEquals(1,rows.size());assertEquals(exam.longValue(),rows.get(0).get("id").asLong());assertEquals(before,facts());
    }
    @Test void nullableImpressionAndEmptyContentKeepOriginalKeysAndValues() throws Exception {
        jdbc.update("update ris_exam set impression=null where id=?",exam);var before=facts();var row=recent().get(0);assertTrue(row.get("impression").isNull());assertEquals(5,row.size());assertEquals(before,facts());
        jdbc.update("update ris_exam set impression='' where id=?",exam);before=facts();assertEquals("",recent().get(0).get("impression").asText());assertEquals(before,facts());
    }
    @Test void orderedLimitIsTwentyAndDoesNotDedupeRepeatedItems() throws Exception {
        for(int i=2;i<=25;i++)exam(order(registration,"T"+i,"EXAM","EXECUTED"),"VERIFIED",i);
        jdbc.update("update outp_order set item_name=? where registration_id=?",tag+"同名检查",registration);
        var before=facts();var rows=recent();assertEquals(20,rows.size());
        var expected=jdbc.queryForList("select e.id from ris_exam e join outp_order o on o.id=e.order_id where o.registration_id=? order by e.verified_at desc limit 20",Long.class,registration);
        for(int i=0;i<20;i++){assertEquals(expected.get(i).longValue(),rows.get(i).get("id").asLong());assertEquals(tag+"同名检查",rows.get(i).get("item_name").asText());}assertEquals(before,facts());
    }
    @Test void invalidNewerRowsDoNotConsumeTheTwentyEligibleSlots() throws Exception {
        for(int i=2;i<=23;i++)exam(order(registration,"T"+i,"EXAM","EXECUTED"),"VERIFIED",i);
        for(int i=0;i<4;i++)exam(order(registration,"X"+i,"LAB","EXECUTED"),"VERIFIED",0);
        var before=facts();var rows=recent();var expected=jdbc.queryForList("select e.id from ris_exam e join outp_order o on o.id=e.order_id where o.registration_id=? and o.order_type='EXAM' order by e.verified_at desc limit 20",Long.class,registration);
        assertEquals(20,rows.size());for(int i=0;i<20;i++)assertEquals(expected.get(i).longValue(),rows.get(i).get("id").asLong());assertEquals(before,facts());
    }
    @Test void directDatasetRetainsJdbcMicrosecondsSeparatelyFromTheHttpSerialization() throws Exception {
        Object original=jdbc.queryForObject("select verified_at from ris_exam where id=?",Object.class,exam);var before=facts();asAdmin();
        try {assertEquals(instant(original),instant(med.recentExams(patient,null).getData().getFirst().get("verified_at")));}
        finally {SecurityContextHolder.clearContext();}
        assertEquals(mapper.valueToTree(original),recent().get(0).get("verified_at"));assertEquals(before,facts());
    }
    @Test void committedSourceInvalidationIsAppliedByTheNextRead() throws Exception {
        assertEquals(1,recent().size());new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order));emptyAndReadOnly();
    }
    @Test void committedReportWithdrawalIsAppliedByTheNextRead() throws Exception {
        assertEquals(1,recent().size());new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("update ris_exam set status='REPORTED' where id=?",exam));emptyAndReadOnly();
    }
    @Test void rolledBackInvalidationKeepsTheOriginalPublishedFacts() throws Exception {
        var before=facts();new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order);jdbc.update("update ris_exam set status='REPORTED' where id=?",exam);t.setRollbackOnly();});assertEquals(1,recent().size());assertEquals(before,facts());
    }
}
