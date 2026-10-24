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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real JWT requests, committed PostgreSQL fixtures and independently committing transactions.
 * The unique-key races prove the actual INSERT waits on the named holder backend before release.
 * No outer test transaction, fake controller or assumption that two started threads raced.
 * Historical row fields/source mismatches are explicit fixtures, not current write-API claims. */
@SpringBootTest
@AutoConfigureMockMvc
class V123RisWorklistRegisterTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    String tag, admin, independentIndex;
    Long dept, staffDept, patient, schedule, registration, order, bill;
    final List<Long> employees = new ArrayList<>();
    static final Set<String> KEYS = Set.of("id","status","modality","findings","impression","item_name","group_no",
            "order_status","clinical_summary","exam_purpose","notice","urgent","patient_name","diagnosis");
    record Outcome(int status, JsonNode body, String sqlState) { int code() {return body.get("code").asInt();} JsonNode data() {return body.get("data");} }

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();tag="V123"+Long.toUnsignedString(System.nanoTime(),36);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        staffDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"S",tag+"异科");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"患者");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (1,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept);
        order=order("首单超声","EXAM","CHARGED");
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status) values (?,?,7.50,'CASH','PAID') returning id",Long.class,tag,registration);
        jdbc.update("update outp_order set charge_id=? where id=?",bill,order);
        admin=staff("ADMIN");
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
        return List.of(jdbc.queryForList("select * from empi_patient where id=?",patient),
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
            if(!waiters.isEmpty()) {System.out.println("V123 PG_LOCK unique-holder="+holder+" waiters="+waiters);return;}
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
        SecurityContextHolder.clearContext();if(independentIndex!=null)jdbc.execute("drop index if exists "+independentIndex);
        tx().executeWithoutResult(t->{
            jdbc.update("delete from ris_exam where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_diagnosis where registration_id=?",registration);
            jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);
            jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id=?",patient);
            for(Long id:employees){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
            jdbc.update("delete from sys_dept where id in (?,?)",dept,staffDept);
        });employees.clear();
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE"})
    void originalFourDutiesRegisterOtherDepartmentVisitsAndKeepFourteenKeys(String role) throws Exception {
        String token=role.equals("ADMIN")?admin:staff(role);
        jdbc.update("insert into outp_diagnosis(registration_id,icd_code,icd_name,primary_diag) values (?,'A','次诊一',false),(?,'B','主诊',true),(?,'C','次诊二',false)",registration,registration,registration);
        var before=otherFacts();var rows=own(ok(request(token,null)));assertEquals(1,rows.size());var row=rows.getFirst();
        var keys=new java.util.HashSet<String>();row.fieldNames().forEachRemaining(keys::add);assertEquals(KEYS,keys);
        assertEquals("REGISTERED",row.get("status").asText());assertEquals("US",row.get("modality").asText());assertEquals("CHARGED",row.get("order_status").asText());
        assertEquals(tag+"患者",row.get("patient_name").asText());assertEquals("原摘要",row.get("clinical_summary").asText());assertEquals("原目的",row.get("exam_purpose").asText());assertEquals("原注意",row.get("notice").asText());assertTrue(row.get("urgent").asBoolean());
        assertEquals("主诊；次诊一；次诊二",row.get("diagnosis").asText());assertTrue(row.get("findings").isNull());assertTrue(row.get("impression").isNull());
        assertEquals(before,otherFacts());assertEquals(1,exams().size());
    }
    @ParameterizedTest @CsvSource({"ADMIN,REGISTERED","TECHNICIAN,ARRIVED","DOCTOR_OUTP,REPORTED","NURSE,VERIFIED"})
    void committingUniqueWinnerAllowsSuccessfulJwtReadWithoutOverwritingAnyWinnerField(String role,String state) throws Exception {
        String token=role.equals("ADMIN")?admin:staff(role);Object before=otherFacts();var executor=Executors.newSingleThreadExecutor();var futures=new ArrayList<Future<Outcome>>();
        final List<Map<String,Object>> expected=new ArrayList<>();
        try {
            tx().executeWithoutResult(t->{
                richExam(order,state);expected.addAll(exams());int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                futures.add(executor.submit(()->request(token,null)));waitForUniqueHolder(holder,futures.getFirst());
            });
            Outcome response=finish(futures.getFirst());
            assertEquals(expected,exams());assertEquals(before,otherFacts());
            assertEquals(200,response.status());
            System.out.println("V123 RACE role="+role+" winner="+state+" HTTP="+response.status()+" code="+response.code()+" SQLState="+response.sqlState());
            if(response.code()!=0)assertEquals("23505",response.sqlState(),"Only the observed unique-key race explains the old refusal");
            // The old implementation gives mapped code4090 (underlying SQLState23505), not HTTP500.
            var rows=own(ok(response));assertEquals(state.equals("VERIFIED")?0:1,rows.size());
            if(!rows.isEmpty()){assertEquals(expected.getFirst().get("id"),rows.getFirst().get("id").asLong());assertEquals("ORIGINAL",rows.getFirst().get("modality").asText());assertEquals("原所见",rows.getFirst().get("findings").asText());}
        } finally {close(executor);}
    }
    @Test void rolledBackUniqueHolderLetsTheWaitingJwtRequestCreateItsOwnDefaultRow() throws Exception {
        Object before=otherFacts();var executor=Executors.newSingleThreadExecutor();var futures=new ArrayList<Future<Outcome>>();
        try {
            tx().executeWithoutResult(t->{richExam(order,"REPORTED");int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);futures.add(executor.submit(()->request(admin,null)));waitForUniqueHolder(holder,futures.getFirst());t.setRollbackOnly();});
            var rows=own(ok(finish(futures.getFirst())));assertEquals(1,rows.size());var e=exams().getFirst();assertEquals(1,exams().size());
            assertEquals("REGISTERED",e.get("status"));assertEquals("US",e.get("modality"));assertNull(e.get("findings"));assertNull(e.get("impression"));assertNull(e.get("reporter_id"));assertNull(e.get("verified_at"));assertEquals(false,e.get("critical_flag"));assertEquals(before,otherFacts());
        } finally {close(executor);}
    }
    @ParameterizedTest @ValueSource(strings={"REGISTERED","ARRIVED","REPORTED","VERIFIED"})
    void existingRowsKeepEveryStoredColumnAndVerifiedRowsRemainOffTheQueue(String state) throws Exception {
        Long id=richExam(order,state);var before=exams();var clinical=otherFacts();var rows=own(ok(request(admin,null)));
        assertEquals(state.equals("VERIFIED")?0:1,rows.size());if(!rows.isEmpty())assertEquals(id.longValue(),rows.getFirst().get("id").asLong());
        assertEquals(before,exams());assertEquals(clinical,otherFacts());
    }
    @ParameterizedTest @CsvSource({"心电胃镜彩超,ECG","胃镜超声,ENDO","彩超,US","超声,US","心电,ECG","CT,GENERAL","普通项目,GENERAL"})
    void originalModalityPrecedenceAndFallbackAreRetained(String name,String modality) throws Exception {
        jdbc.update("update outp_order set item_name=? where id=?",name,order);var before=otherFacts();var rows=own(ok(request(admin,null)));
        assertEquals(modality,rows.getFirst().get("modality").asText());assertEquals(before,otherFacts());
    }
    @ParameterizedTest @CsvSource({"EXAM,CREATED","EXAM,EXECUTED","EXAM,CANCELLED","EXAM,DISPENSED","LAB,CHARGED","DRUG,CHARGED","TREAT,CHARGED"})
    void automaticRegistrationDoesNotExpandItsOriginalSourceEligibility(String type,String state) throws Exception {
        jdbc.update("update outp_order set order_type=?,status=? where id=?",type,state,order);var before=otherFacts();assertEquals(0,own(ok(request(admin,null))).size());assertTrue(exams().isEmpty());assertEquals(before,otherFacts());
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","PHARMACIST","INTERFACE","PORTAL"})
    void wrongDomainsCannotPerformTheAutomaticRegistrationWrite(String role) throws Exception {
        String token=role.equals("PORTAL")?jwt.issue("portal:"+patient):staff(role);var before=otherFacts();var response=request(token,null);
        assertEquals(403,response.status());assertEquals(1005,response.code());assertTrue(response.data().isNull());assertTrue(exams().isEmpty());assertEquals(before,otherFacts());
    }
    @Test void anonymousRequestDoesNotInheritAStaffJwtOrCreateRows() throws Exception {
        var response=request(null,null);assertEquals(401,response.status());assertEquals(1000,response.code());assertTrue(exams().isEmpty());
    }
    @Test void disabledEmployeesExistingJwtStillCannotCreateRows() throws Exception {
        jdbc.update("update sys_user set enabled=false where username=?",tag+"ADMIN");var response=request(admin,null);assertEquals(401,response.status());assertEquals(1000,response.code());assertTrue(exams().isEmpty());
    }
    @Test void modalityFilterIsExactAndDoesNotRestrictWhichEligibleOrdersAreRegistered() throws Exception {
        order("心电", "EXAM","CHARGED");order("普通", "EXAM","CHARGED");Object before=otherFacts();
        assertTrue(own(ok(request(admin,""))).isEmpty());assertEquals(3,exams().size());var stored=exams();
        assertEquals(1,own(ok(request(admin,"US"))).size());assertTrue(own(ok(request(admin,"us"))).isEmpty());assertTrue(own(ok(request(admin," US"))).isEmpty());assertEquals(3,own(ok(request(admin,null))).size());assertEquals(stored,exams());assertEquals(before,otherFacts());
    }
    @Test void queueRetainsIdOrderingAndDoesNotDedupeSameNamedOrders() throws Exception {
        Long second=order("首单超声","EXAM","CHARGED");richExam(second,"REPORTED");richExam(order,"ARRIVED");
        var stored=exams();var rows=own(ok(request(admin,null)));assertEquals(2,rows.size());assertEquals(stored.stream().map(x->((Number)x.get("id")).longValue()).toList(),rows.stream().map(x->x.get("id").asLong()).toList());assertEquals(stored,exams());
    }
    @Test void existingHistoricalSourceDisplayPolicyIsNotSilentlyTightenedByRegistrationFix() throws Exception {
        richExam(order,"REPORTED");jdbc.update("update outp_order set status='CREATED',order_type='LAB' where id=?",order);Object before=otherFacts();var stored=exams();var rows=own(ok(request(admin,null)));
        assertEquals(1,rows.size());assertEquals("CREATED",rows.getFirst().get("order_status").asText());assertEquals(stored,exams());assertEquals(before,otherFacts());
    }
    @Test void unrelatedUniqueConstraintStillFailsAtomicallyAndRetryRegistersTheWholeBatch() throws Exception {
        Long second=order("另一单","EXAM","CHARGED");Object before=otherFacts();independentIndex="v123_independent_"+tag.toLowerCase();
        // A disposable independent uniqueness rule catches an overly broad ON CONFLICT DO NOTHING.
        jdbc.execute("create unique index "+independentIndex+" on ris_exam ((1)) where order_id in ("+order+","+second+")");
        try {var response=request(admin,null);assertEquals(200,response.status());assertEquals(4090,response.code());assertEquals("23505",response.sqlState());assertTrue(exams().isEmpty());assertEquals(before,otherFacts());}
        finally {jdbc.execute("drop index "+independentIndex);independentIndex=null;}
        assertEquals(2,own(ok(request(admin,null))).size());assertEquals(2,exams().size());assertEquals(before,otherFacts());
    }
    @Test void repeatedReadsAreIdempotentAndDoNotRequireAPaidChargeObject() throws Exception {
        jdbc.update("update outp_order set charge_id=null where id=?",order);Object before=otherFacts();var first=own(ok(request(admin,null)));var stored=exams();
        assertEquals(1,first.size());for(int i=0;i<3;i++)assertEquals(first,own(ok(request(admin,null))));assertEquals(stored,exams());assertEquals(before,otherFacts());
    }
}
