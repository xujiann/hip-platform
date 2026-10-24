package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.outpatient.service.ChargeService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.MediaType;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real JWT requests, committed PostgreSQL fixtures and independently committing transactions.
 * The refund race proves the actual INSERT waits on the named uncommitted E holder before release.
 * The holder uses the real refund service with a live cashier, then commits/rolls back its actual writes.
 * No outer test transaction, fake controller or assumption that two started threads raced.
 * Historical row fields/source mismatches are explicit fixtures, not current write-API claims. */
@SpringBootTest
@AutoConfigureMockMvc
class V124RisWorklistSourceReadTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChargeService chargeService;
    String tag, admin, cashier;
    Long dept, staffDept, patient, otherPatient, schedule, registration, order, bill, cashierId;
    final List<Long> employees = new ArrayList<>();
    static final Set<String> KEYS = Set.of("id","status","modality","findings","impression","item_name","group_no",
            "order_status","clinical_summary","exam_purpose","notice","urgent","patient_name","diagnosis");
    record Outcome(int status, JsonNode body, String sqlState) { int code() {return body.get("code").asInt();} JsonNode data() {return body.get("data");} }

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();tag="V124"+Long.toUnsignedString(System.nanoTime(),36);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        staffDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"S",tag+"异科");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"患者");
        otherPatient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,? ,'F') returning id",Long.class,tag+"Q",tag+"另一患者");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (1,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept);
        order=order("首单超声","EXAM","CHARGED");
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status) values (?,?,7.50,'CASH','PAID') returning id",Long.class,tag,registration);
        jdbc.update("update outp_order set charge_id=? where id=?",bill,order);
        admin=staff("ADMIN");cashier=staff("CASHIER");cashierId=jdbc.queryForObject("select id from sys_user where username=?",Long.class,tag+"CASHIER");
    }
    Long order(String label,String type,String state) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,
                    clinical_summary,exam_purpose,notice,urgent)
                values (?,?,?,0,?,?,'次',1,7.50,7.50,?,'原摘要','原目的','原注意',true) returning id
                """,Long.class,registration,tag+label,type,tag+label,label,state);
    }
    String staff(String role) {
        String name=tag+role;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,name,name,staffDept);
        employees.add(id);jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);return jwt.issue(name);
    }
    Outcome request(String token,String modality) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=get("/api/ris/worklist");if(token!=null)req.header("Authorization","Bearer "+token);if(modality!=null)req.param("modality",modality);
            var result=mvc.perform(req).andReturn();var res=result.getResponse();String sqlState=null;
            for(Throwable cause=result.getResolvedException();cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql)sqlState=sql.getSQLState();
            return new Outcome(res.getStatus(),mapper.readTree(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)),sqlState);
        } finally {SecurityContextHolder.clearContext();}
    }
    JsonNode ok(Outcome response) {assertEquals(200,response.status());assertEquals(0,response.code(),response.body().toString());assertTrue(response.data().isArray());return response.data();}
    List<JsonNode> own(JsonNode rows) {var found=new ArrayList<JsonNode>();rows.forEach(x->{if(x.get("group_no").asText().startsWith(tag))found.add(x);});return found;}
    List<Map<String,Object>> exams() {return jdbc.queryForList("select * from ris_exam where order_id in(select id from outp_order where registration_id=?) order by id",registration);}
    Object otherFacts() {
        return List.of(jdbc.queryForList("select * from empi_patient where id in (?,?) order by id",patient,otherPatient),
                jdbc.queryForList("select * from outp_registration where id=?",registration),
                jdbc.queryForList("select * from outp_schedule where id=?",schedule),
                jdbc.queryForList("select * from outp_order where registration_id=? order by id",registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id",registration),
                jdbc.queryForList("select * from cdr_document where patient_id=? order by id",patient),
                jdbc.queryForList("select * from outp_order_report where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from outp_critical_alert where registration_id=? order by id",registration),
                jdbc.queryForList("select * from outp_lab_result where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from lis_sample where order_id in(select id from outp_order where registration_id=?) order by id",registration));
    }
    Long richExam(Long source,String state) {
        return jdbc.queryForObject("""
                insert into ris_exam(order_id,status,modality,findings,impression,reporter_id,verifier_id,reported_at,verified_at,arrived_at,critical_flag,critical_note)
                values (?,?,'ORIGINAL','原所见','原结论',?,?,date_trunc('second',now())+interval '0.123456 second',now(),now(),true,'原危急') returning id
                """,Long.class,source,state,employees.getFirst(),employees.getFirst());
    }
    TransactionTemplate tx() {var transaction=new TransactionTemplate(manager);transaction.setTimeout(20);return transaction;}
    void waitForUniqueHolder(int holder,Future<?> request) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline) {
            // This monitor runs inside the holder transaction: refresh its cached statistics view.
            jdbc.execute("select pg_stat_clear_snapshot()");
            var waiters=jdbc.queryForList("""
                    select pid,pg_blocking_pids(pid) as blockers from pg_stat_activity
                    where pid<>pg_backend_pid() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))
                      and query like '%insert into ris_exam(order_id, modality)%'
                    """,holder);
            if(!waiters.isEmpty()) {System.out.println("V124 PG_LOCK unique-holder="+holder+" waiters="+waiters);return;}
            if(request.isDone())fail("JWT worklist request finished before its required unique-key Lock wait");
            try {Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        }
        fail("Must observe actual INSERT Lock wait blocked by the known uncommitted holder");
    }
    Outcome finish(Future<Outcome> future) throws Exception {
        try{return future.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Unexpected runtime rather than an HTTP worklist outcome",e.getCause());}
    }
    void close(ExecutorService executor) throws Exception {executor.shutdown();assertTrue(executor.awaitTermination(25,TimeUnit.SECONDS),"Workers finish before fixture cleanup");}
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        tx().executeWithoutResult(t->{
            jdbc.update("delete from ris_exam where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_diagnosis where registration_id=?",registration);
            jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);
            jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id in (?,?)",patient,otherPatient);
            for(Long id:employees){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
            jdbc.update("delete from sys_dept where id in (?,?)",dept,staffDept);
        });employees.clear();
    }

    JsonNode postOk(String path,String body) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=post(path).header("Authorization","Bearer "+cashier);
            if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(body);
            var result=mvc.perform(req).andReturn().getResponse();assertEquals(200,result.getStatus());
            var json=mapper.readTree(result.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));assertEquals(0,json.get("code").asInt(),json.toString());return json.get("data");
        } finally {SecurityContextHolder.clearContext();}
    }
    void cashierAuth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(tag+"CASHIER",null,List.of(new SimpleGrantedAuthority("ROLE_CASHIER"))));}
    void actualServiceRefund(){cashierAuth();try{assertEquals("REFUNDED",chargeService.refund(bill,cashierId).getStatus());}finally{SecurityContextHolder.clearContext();}}
    void emptyAndUnchanged() throws Exception {var e=exams();var other=otherFacts();assertTrue(own(ok(request(admin,null))).isEmpty());assertEquals(e,exams());assertEquals(other,otherFacts());}
    void oneAndUnchanged() throws Exception {var e=exams();var other=otherFacts();assertEquals(1,own(ok(request(admin,null))).size());assertEquals(e,exams());assertEquals(other,otherFacts());}

    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE"})
    void originalFourDutiesReadAllHospitalActiveSourcesAndFourteenKeys(String role) throws Exception {
        Long id=richExam(order,"REPORTED");String token=role.equals("ADMIN")?admin:staff(role);
        jdbc.update("insert into outp_diagnosis(registration_id,icd_code,icd_name,primary_diag) values (?,'A','次诊一',false),(?,'B','主诊',true),(?,'C','次诊二',false)",registration,registration,registration);
        var stored=exams();var before=otherFacts();var rows=own(ok(request(token,null)));assertEquals(1,rows.size());var row=rows.getFirst();
        var keys=new java.util.HashSet<String>();row.fieldNames().forEachRemaining(keys::add);assertEquals(KEYS,keys);
        assertEquals(id.longValue(),row.get("id").asLong());assertEquals("REPORTED",row.get("status").asText());assertEquals("CHARGED",row.get("order_status").asText());
        assertEquals("原所见",row.get("findings").asText());assertEquals("原结论",row.get("impression").asText());assertEquals("ORIGINAL",row.get("modality").asText());
        assertEquals(tag+"患者",row.get("patient_name").asText());assertEquals("主诊；次诊一；次诊二",row.get("diagnosis").asText());
        assertEquals("原摘要",row.get("clinical_summary").asText());assertEquals("原目的",row.get("exam_purpose").asText());assertEquals("原注意",row.get("notice").asText());assertTrue(row.get("urgent").asBoolean());
        assertEquals(stored,exams());assertEquals(before,otherFacts());
    }
    @ParameterizedTest @ValueSource(strings={"CREATED","CANCELLED","DISPENSED","REFUNDED"})
    void historicalInactiveSourceStatusesDoNotExposeExistingReport(String state) throws Exception {richExam(order,"REPORTED");jdbc.update("update outp_order set status=? where id=?",state,order);emptyAndUnchanged();}
    @ParameterizedTest @ValueSource(strings={"LAB","DRUG","TREAT"})
    void historicalNonExamSourcesDoNotExposeExistingReport(String type) throws Exception {richExam(order,"REPORTED");jdbc.update("update outp_order set order_type=? where id=?",type,order);emptyAndUnchanged();}
    @ParameterizedTest @CsvSource({"REGISTERED,CREATED","ARRIVED,CANCELLED","REPORTED,CREATED"})
    void sourceQualificationAppliesToEveryPendingExamStage(String examState,String sourceState) throws Exception {richExam(order,examState);jdbc.update("update outp_order set status=? where id=?",sourceState,order);emptyAndUnchanged();}
    @ParameterizedTest @CsvSource({"REGISTERED,CHARGED","ARRIVED,CHARGED","REPORTED,CHARGED","REGISTERED,EXECUTED","ARRIVED,EXECUTED","REPORTED,EXECUTED"})
    void chargedAndExecutedUnverifiedReportsKeepAllOriginalStoredFields(String examState,String sourceState) throws Exception {richExam(order,examState);jdbc.update("update outp_order set status=? where id=?",sourceState,order);oneAndUnchanged();}
    @ParameterizedTest @ValueSource(strings={"CHARGED","EXECUTED"})
    void verifiedReportsStayOffTheWorkQueue(String state) throws Exception {richExam(order,"VERIFIED");jdbc.update("update outp_order set status=? where id=?",state,order);emptyAndUnchanged();}
    @ParameterizedTest @ValueSource(strings={"CASHIER","PHARMACIST","INTERFACE","PORTAL"})
    void originalWrongDomainsCannotReadPendingReport(String role) throws Exception {
        richExam(order,"REPORTED");String token=role.equals("PORTAL")?jwt.issue("portal:"+patient):role.equals("CASHIER")?cashier:staff(role);
        var e=exams();var before=otherFacts();var out=request(token,null);assertEquals(403,out.status());assertEquals(1005,out.code());assertTrue(out.data().isNull());assertEquals(e,exams());assertEquals(before,otherFacts());
    }
    @Test void anonymousDoesNotInheritAStaffRead() throws Exception {richExam(order,"REPORTED");assertEquals(1,own(ok(request(admin,null))).size());var e=exams();var before=otherFacts();var out=request(null,null);assertEquals(401,out.status());assertEquals(1000,out.code());assertEquals(e,exams());assertEquals(before,otherFacts());}
    @Test void disabledStaffExistingJwtStillCannotRead() throws Exception {richExam(order,"REPORTED");jdbc.update("update sys_user set enabled=false where username=?",tag+"ADMIN");var e=exams();var before=otherFacts();var out=request(admin,null);assertEquals(401,out.status());assertEquals(1000,out.code());assertEquals(e,exams());assertEquals(before,otherFacts());}
    @Test void exactAndBlankModalitySemanticsRemainUnchanged() throws Exception {
        richExam(order,"REPORTED");var e=exams();var before=otherFacts();assertEquals(1,own(ok(request(admin,"ORIGINAL"))).size());
        for(String m:List.of("","original"," ORIGINAL","US"))assertTrue(own(ok(request(admin,m))).isEmpty());assertEquals(1,own(ok(request(admin,null))).size());assertEquals(e,exams());assertEquals(before,otherFacts());
    }
    @Test void currentRegistrationPatientBindingDeterminesTheName() throws Exception {richExam(order,"REPORTED");jdbc.update("update outp_registration set patient_id=? where id=?",otherPatient,registration);var before=otherFacts();var e=exams();var rows=own(ok(request(admin,null)));assertEquals(tag+"另一患者",rows.getFirst().get("patient_name").asText());assertEquals(before,otherFacts());assertEquals(e,exams());}
    @Test void sourceQualificationDoesNotAddAPaidChargeRequirement() throws Exception {richExam(order,"REPORTED");jdbc.update("update outp_order set charge_id=null where id=?",order);oneAndUnchanged();}
    @ParameterizedTest @ValueSource(strings={"REGISTERED","ARRIVED","REPORTED"})
    void committedRealHttpRefundHidesExistingExamWithoutDeletingIt(String state) throws Exception {
        richExam(order,state);var e=exams();postOk("/api/outpatient/charges/"+bill+"/refund",null);
        assertEquals("CREATED",jdbc.queryForObject("select status from outp_order where id=?",String.class,order));assertNull(jdbc.queryForObject("select charge_id from outp_order where id=?",Long.class,order));assertEquals("REFUNDED",jdbc.queryForObject("select status from outp_charge where id=?",String.class,bill));
        assertEquals(e,exams());emptyAndUnchanged();
    }
    @Test void realRechargingRestoresTheSameOriginalReportAndSignatureFacts() throws Exception {
        Long id=richExam(order,"REPORTED");var e=exams();postOk("/api/outpatient/charges/"+bill+"/refund",null);
        var newBill=postOk("/api/outpatient/charges/settle","{\"registrationId\":"+registration+",\"payMethod\":\"CASH\"}");
        assertNotEquals(bill.longValue(),newBill.get("id").asLong());assertEquals("PAID",newBill.get("status").asText());assertEquals(e,exams());
        var before=otherFacts();var rows=own(ok(request(admin,null)));assertEquals(id.longValue(),rows.getFirst().get("id").asLong());assertEquals("原所见",rows.getFirst().get("findings").asText());assertEquals(e,exams());assertEquals(before,otherFacts());
    }
    @Test void nextReadHonoursCommittedHistoricalSourceInvalidation() throws Exception {richExam(order,"REPORTED");assertEquals(1,own(ok(request(admin,null))).size());tx().executeWithoutResult(t->jdbc.update("update outp_order set status='CREATED' where id=?",order));emptyAndUnchanged();}
    @Test void rolledBackHistoricalInvalidationKeepsOriginalActiveReport() throws Exception {richExam(order,"REPORTED");var e=exams();var before=otherFacts();tx().executeWithoutResult(t->{jdbc.update("update outp_order set status='CREATED' where id=?",order);t.setRollbackOnly();});assertEquals(1,own(ok(request(admin,null))).size());assertEquals(e,exams());assertEquals(before,otherFacts());}
    @Test void unknownNonVerifiedStageAndNullContentAreNotNewlyExcluded() throws Exception {richExam(order,"LEGACY");jdbc.update("update ris_exam set findings=null,impression=null where order_id=?",order);oneAndUnchanged();}
    @Test void mixedQueueFiltersOnlyInactiveSourcesAndKeepsIdOrderAndSameNamedRows() throws Exception {
        Long first=richExam(order,"REPORTED"), invalid=order("首单超声","LAB","CHARGED"), second=order("首单超声","EXAM","EXECUTED");richExam(invalid,"ARRIVED");Long secondE=richExam(second,"ARRIVED");
        var e=exams();var before=otherFacts();var rows=own(ok(request(admin,null)));assertEquals(List.of(first,secondE),rows.stream().map(x->x.get("id").asLong()).toList());assertEquals(e,exams());assertEquals(before,otherFacts());
    }
    @Test void committedRealRefundDuringUniqueWaitIsAppliedByTheFollowingSelect() throws Exception {
        var executor=Executors.newSingleThreadExecutor();var futures=new ArrayList<Future<Outcome>>();var expected=new ArrayList<Map<String,Object>>();final Object[] afterRefund=new Object[1];
        try {
            tx().executeWithoutResult(t->{
                richExam(order,"REPORTED");expected.addAll(exams());int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                futures.add(executor.submit(()->request(admin,null)));waitForUniqueHolder(holder,futures.getFirst());actualServiceRefund();afterRefund[0]=otherFacts();
            });
            var response=finish(futures.getFirst());assertEquals(expected,exams());assertEquals(afterRefund[0],otherFacts());assertEquals(200,response.status());assertEquals(0,response.code());
            System.out.println("V124 REFUND_WAIT HTTP="+response.status()+" code="+response.code()+" rows="+own(response.data()).size());assertTrue(own(response.data()).isEmpty());
        } finally {close(executor);}
    }
    @Test void rolledBackRealRefundDuringUniqueWaitLeavesTheOriginalChargeAndAllowsDefaultRegistration() throws Exception {
        var before=otherFacts();var executor=Executors.newSingleThreadExecutor();var futures=new ArrayList<Future<Outcome>>();
        try {
            tx().executeWithoutResult(t->{
                richExam(order,"REPORTED");int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);futures.add(executor.submit(()->request(admin,null)));waitForUniqueHolder(holder,futures.getFirst());actualServiceRefund();t.setRollbackOnly();
            });
            var rows=own(ok(finish(futures.getFirst())));assertEquals(1,rows.size());var e=exams();assertEquals(1,e.size());assertEquals("REGISTERED",e.getFirst().get("status"));assertEquals("US",e.getFirst().get("modality"));assertNull(e.getFirst().get("findings"));assertEquals(before,otherFacts());
        } finally {close(executor);}
    }
}
