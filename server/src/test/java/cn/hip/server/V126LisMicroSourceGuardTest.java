package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.outpatient.service.ChargeService;
import cn.hip.platform.core.security.JwtService;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Committed PostgreSQL facts, real JWT requests and named-holder Lock waits.
 * No outer test transaction. Explicit historical SQL is not a current write API claim.
 * Refund/publication holders deliberately compose the real services inside a controlled
 * transaction; only the competing micro request is an ordinary independently committing HTTP path.
 * Both old FK INSERT waits and the fixed O/S eligibility waits count only after pg_blocking_pids proof. */
@SpringBootTest
@AutoConfigureMockMvc
class V126LisMicroSourceGuardTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired ChargeService charges;
    @Autowired MedTechController medtech;
    @Autowired jakarta.persistence.EntityManager entityManager;
    String tag, barcode, admin, cashier;
    Long dept, staffDept, patient, schedule, registration, order, sample, bill, adminId, cashierId;
    final List<Long> employees=new ArrayList<>();
    static final String BODY="""
            {"specimen":"痰 原样 ","organism":"原始  菌 ","gram":"NEG","colonyCount":" ++ ",
             "ast":[{"antibiotic":"头孢 原样 ","method":"MIC","micValue":"1","sir":"S"},
                    {"antibiotic":"另一药","method":null,"micValue":null,"sir":"I"}]}
            """;
    record Outcome(int http,JsonNode body) {int code(){return body.get("code").asInt();}}
    record MicroFacts(List<Map<String,Object>> micros,List<Map<String,Object>> ast) {}

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();tag="V126"+Long.toUnsignedString(System.nanoTime(),36);barcode=tag+"BC";
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        staffDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"S",tag+"跨科");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"患者");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (1,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept);
        order=order("主申请","LAB","CHARGED");
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status) values (?,?,7.50,'CASH','PAID') returning id",Long.class,tag,registration);
        jdbc.update("update outp_order set charge_id=? where id=?",bill,order);
        sample=jdbc.queryForObject("insert into lis_sample(order_id,barcode,status,received_at) values (?,?,'RECEIVED',now()) returning id",Long.class,order,barcode);
        admin=staff("ADMIN");cashier=staff("CASHIER");adminId=staffId("ADMIN");cashierId=staffId("CASHIER");
        Long old=jdbc.queryForObject("insert into lab_micro_result(sample_id,order_id,specimen,organism,colony_count,gram,reporter_id) values (?,?,'原标本','原菌','原计数','POS',?) returning id",Long.class,sample,order,adminId);
        jdbc.update("insert into lab_micro_ast(micro_id,antibiotic,method,mic_value,sir) values (?,'原药','KB','21','R')",old);
    }
    Long order(String suffix,String type,String state) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status)
                values (?,?,?,0,?,?,'次',1,7.50,7.50,?) returning id
                """,Long.class,registration,tag+suffix,type,tag+suffix,suffix,state);
    }
    String staff(String role) {
        String name=tag+role;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,name,name,staffDept);
        employees.add(id);jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);return jwt.issue(name);
    }
    Long staffId(String role){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,tag+role);}
    Outcome request(String bc,String token,String body) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=post("/api/lis/micro/"+bc).contentType("application/json").content(body);
            if(token!=null)req.header("Authorization","Bearer "+token);
            var res=mvc.perform(req).andReturn().getResponse();return new Outcome(res.getStatus(),mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8)));
        } finally {SecurityContextHolder.clearContext();}
    }
    Outcome request(String token) throws Exception{return request(barcode,token,BODY);}
    void code(Outcome result,int http,int expected) {
        System.out.println("V126 RESPONSE expected="+expected+" actual="+result.code()+" HTTP="+result.http());
        assertAll(()->assertEquals(http,result.http()),()->assertEquals(expected,result.code(),result.body().toString()),()->assertTrue(result.body().get("data").isNull()));
    }
    MicroFacts microFacts() {
        return new MicroFacts(jdbc.queryForList("select * from lab_micro_result where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from lab_micro_ast where micro_id in(select id from lab_micro_result where order_id in(select id from outp_order where registration_id=?)) order by id",registration));
    }
    Object otherFacts() {
        return List.of(jdbc.queryForList("select * from empi_patient where id=?",patient),
                jdbc.queryForList("select * from outp_registration where id=?",registration),
                jdbc.queryForList("select * from outp_schedule where id=?",schedule),
                jdbc.queryForList("select * from outp_order where registration_id=? order by id",registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id",registration),
                jdbc.queryForList("select * from lis_sample where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from outp_lab_result where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from outp_order_report where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from outp_critical_alert where registration_id=? order by id",registration),
                jdbc.queryForList("select * from cdr_document where patient_id=? order by id",patient));
    }
    void refused(String bc,String token,String body,int http,int expected) throws Exception {
        var before=microFacts();var other=otherFacts();Outcome response=request(bc,token,body);
        assertAll(()->code(response,http,expected),()->assertEquals(before,microFacts(),"Rejected request must preserve every M/AST column"),()->assertEquals(other,otherFacts(),"No unrelated business changes"));
    }
    Instant instant(Object value){return value instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)value).toInstant();}
    Instant clock(){return instant(jdbc.queryForObject("select clock_timestamp()",Object.class));}
    void appended(MicroFacts before,int count,Long actor,Instant lower) {
        var after=microFacts();assertEquals(before.micros().size()+count,after.micros().size());assertEquals(before.ast().size()+2*count,after.ast().size());
        assertEquals(before.micros(),after.micros().subList(0,before.micros().size()));assertEquals(before.ast(),after.ast().subList(0,before.ast().size()));
        Instant upper=clock();var oldIds=before.micros().stream().map(x->((Number)x.get("id")).longValue()).toList();
        for(var row:after.micros())if(!oldIds.contains(((Number)row.get("id")).longValue())) {
            assertEquals(Set.of("id","sample_id","order_id","specimen","organism","colony_count","gram","reporter_id","reported_at"),row.keySet());
            assertEquals(sample,((Number)row.get("sample_id")).longValue());assertEquals(order,((Number)row.get("order_id")).longValue());assertEquals(actor,((Number)row.get("reporter_id")).longValue());
            assertEquals("痰 原样 ",row.get("specimen"));assertEquals("原始  菌 ",row.get("organism"));assertEquals(" ++ ",row.get("colony_count"));assertEquals("NEG",row.get("gram"));
            Instant stamp=instant(row.get("reported_at"));assertFalse(stamp.isBefore(lower));assertFalse(stamp.isAfter(upper));
            var lines=jdbc.queryForList("select antibiotic,method,mic_value,sir from lab_micro_ast where micro_id=? order by id",row.get("id"));
            Map<String,Object> nullable=new LinkedHashMap<>();nullable.put("antibiotic","另一药");nullable.put("method",null);nullable.put("mic_value",null);nullable.put("sir","I");
            assertEquals(List.of(Map.of("antibiotic","头孢 原样 ","method","MIC","mic_value","1","sir","S"),nullable),lines);
        }
    }
    TransactionTemplate tx(){var tx=new TransactionTemplate(manager);tx.setTimeout(25);return tx;}
    void actor(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(tag+role,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    void serviceRefund(){actor("CASHIER");try{assertEquals("REFUNDED",charges.refund(bill,cashierId).getStatus());}finally{SecurityContextHolder.clearContext();}}
    void servicePublish(){actor("ADMIN");try{assertEquals(0,medtech.publish(barcode,new MedTechController.PublishReq(List.of(new MedTechController.ManualResult("V126","普通结果","1",null,null,"N"))),SecurityContextHolder.getContext().getAuthentication()).getCode());}finally{SecurityContextHolder.clearContext();}}
    JsonNode financeHttp(String path,String body) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=post(path).header("Authorization","Bearer "+cashier);if(body!=null)req.contentType("application/json").content(body);
            var res=mvc.perform(req).andReturn().getResponse();assertEquals(200,res.getStatus());var json=mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8));assertEquals(0,json.get("code").asInt(),json.toString());return json.get("data");
        } finally{SecurityContextHolder.clearContext();}
    }
    void waitForHolder(int holder,Future<?> request,String stage) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            var waits=jdbc.queryForList("""
                    select pid,wait_event_type,pg_blocking_pids(pid) as blockers,query from pg_stat_activity
                    where pid<>pg_backend_pid() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))
                      and (lower(query) like '%insert into lab_micro_result%' or lower(query) like '%from outp_order%for update%'
                           or lower(query) like '%from lis_sample%for update%')
                    """,holder);
            if(!waits.isEmpty()){System.out.println("V126 PG_LOCK stage="+stage+" holder="+holder+" waiters="+waits);return;}
            if(request.isDone())fail("Micro request completed before required named-holder Lock wait at "+stage);
            try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        }
        fail("Actual named-holder PostgreSQL Lock not observed at "+stage);
    }
    Outcome finish(Future<Outcome> future) throws Exception {
        try{return future.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Expected an HTTP business outcome",e.getCause());}
    }
    void close(ExecutorService workers) throws Exception {workers.shutdown();assertTrue(workers.awaitTermination(25,TimeUnit.SECONDS));}
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();if(registration==null)return;
        tx().executeWithoutResult(t->{
            jdbc.update("delete from lab_micro_result where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_critical_alert where registration_id=?",registration);jdbc.update("delete from cdr_document where patient_id=?",patient);
            jdbc.update("delete from outp_lab_result where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_order_report where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from lis_sample where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);
            jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id=?",patient);
            for(Long id:employees){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
            jdbc.update("delete from sys_dept where id in (?,?)",dept,staffDept);
        });employees.clear();
    }

    @ParameterizedTest @ValueSource(strings={"CREATED","CANCELLED","DISPENSED","REFUNDED"})
    void inactiveHistoricalSourcesCannotAppend(String status) throws Exception {jdbc.update("update outp_order set status=? where id=?",status,order);refused(barcode,admin,BODY,200,7106);}
    @ParameterizedTest @ValueSource(strings={"EXAM","DRUG","TREAT"})
    void wrongHistoricalTypesCannotAppend(String type) throws Exception {jdbc.update("update outp_order set order_type=? where id=?",type,order);refused(barcode,admin,BODY,200,7106);}
    @ParameterizedTest @ValueSource(strings={"COLLECTED","PUBLISHED","CANCELLED"})
    void onlyReceivedSampleCanAppend(String state) throws Exception {jdbc.update("update lis_sample set status=? where id=?",state,sample);refused(barcode,admin,BODY,200,7106);}
    @Test void missingBarcodeKeeps7106() throws Exception {refused("V126_MISSING",admin,BODY,200,7106);}
    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE"})
    void originalFourCrossDepartmentDutiesAppendExactRawValues(String role) throws Exception {
        String token=role.equals("ADMIN")?admin:staff(role);var before=microFacts();var other=otherFacts();Instant lower=clock();code(request(token),200,0);appended(before,1,staffId(role),lower);assertEquals(other,otherFacts());
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","PHARMACIST","INTERFACE","PORTAL"})
    void wrongDomainsDoNotAppend(String role) throws Exception {refused(barcode,role.equals("PORTAL")?jwt.issue("portal:"+patient):role.equals("CASHIER")?cashier:staff(role),BODY,403,1005);}
    @Test void anonymousDoesNotInheritPriorStaffAuthentication() throws Exception {code(request(admin),200,0);refused(barcode,null,BODY,401,1000);}
    @Test void initiallyDisabledEmployeeIs401() throws Exception {jdbc.update("update sys_user set enabled=false where id=?",adminId);refused(barcode,admin,BODY,401,1000);}
    @ParameterizedTest @ValueSource(strings={"CHARGED","EXECUTED"})
    void bothOriginalActiveSourceStatesRemainEligibleWithoutExtraFinancialGate(String status) throws Exception {
        jdbc.update("update outp_order set status=?,charge_id=null where id=?",status,order);jdbc.update("update outp_charge set status='REFUNDED' where id=?",bill);
        jdbc.update("update outp_registration set visit_date=current_date-365,status='CANCELLED' where id=?",registration);
        var before=microFacts();var other=otherFacts();Instant lower=clock();code(request(admin),200,0);appended(before,1,adminId,lower);assertEquals(other,otherFacts());
    }
    @ParameterizedTest @ValueSource(strings={"null","[]"})
    void nullAndEmptyAstKeepOriginalAppendSemantics(String ast) throws Exception {
        var before=microFacts();var other=otherFacts();code(request(barcode,admin,"{\"organism\":\"无药敏菌\",\"ast\":"+ast+"}"),200,0);
        var after=microFacts();assertEquals(before.micros().size()+1,after.micros().size());assertEquals(before.ast(),after.ast());var row=after.micros().getLast();assertEquals("无药敏菌",row.get("organism"));assertNull(row.get("specimen"));assertNull(row.get("gram"));assertNull(row.get("colony_count"));assertEquals(other,otherFacts());
    }
    @ParameterizedTest @ValueSource(strings={"null","\"   \""})
    void blankOrganismKeeps7107WithoutAnyWrite(String organism) throws Exception {refused(barcode,admin,"{\"organism\":"+organism+"}",200,7107);}
    @ParameterizedTest @ValueSource(strings={"{\"antibiotic\":\"\",\"sir\":\"S\"}","{\"antibiotic\":\"药\",\"sir\":\"X\"}","{\"antibiotic\":\"药\",\"sir\":null}"})
    void invalidAstKeeps7108WithoutAnyWrite(String ast) throws Exception {refused(barcode,admin,"{\"organism\":\"菌\",\"ast\":["+ast+"]}",200,7108);}
    @Test void repeatedLegalCallsAppendInsteadOfOverwriteOrDeduplication() throws Exception {
        var before=microFacts();var other=otherFacts();Instant lower=clock();code(request(admin),200,0);code(request(admin),200,0);appended(before,2,adminId,lower);assertEquals(other,otherFacts());
    }
    @Test void realCommittedRefundRefusesAndRetainsOriginalCultureAndAst() throws Exception {
        var before=microFacts();financeHttp("/api/outpatient/charges/"+bill+"/refund",null);assertEquals(before,microFacts());
        assertEquals("CREATED",jdbc.queryForObject("select status from outp_order where id=?",String.class,order));refused(barcode,admin,BODY,200,7106);
    }
    @Test void realRechargingRestoresTheSameReceivedSampleAndOriginalHistory() throws Exception {
        var before=microFacts();var samples=jdbc.queryForList("select * from lis_sample where id=?",sample);
        financeHttp("/api/outpatient/charges/"+bill+"/refund",null);var paid=financeHttp("/api/outpatient/charges/settle","{\"registrationId\":"+registration+",\"payMethod\":\"CASH\"}");assertNotEquals(bill.longValue(),paid.get("id").asLong());
        assertEquals(samples,jdbc.queryForList("select * from lis_sample where id=?",sample));var other=otherFacts();Instant lower=clock();code(request(admin),200,0);appended(before,1,adminId,lower);assertEquals(other,otherFacts());
    }
    @Test void completingMicroDoesNotAddARefundBlockAndRetainsItsHistory() throws Exception {
        code(request(admin),200,0);var saved=microFacts();financeHttp("/api/outpatient/charges/"+bill+"/refund",null);assertEquals(saved,microFacts());assertEquals("RECEIVED",jdbc.queryForObject("select status from lis_sample where id=?",String.class,sample));
    }
    @Test void actualPublicationClosesFurtherMicroAppend() throws Exception {servicePublish();refused(barcode,admin,BODY,200,7106);}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualRefundHolderCommitRejectsOrRollbackKeepsEligible(boolean rollback) throws Exception {
        var before=microFacts();var originalOther=otherFacts();Instant lower=clock();var workers=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();final Object[] expectedOther=new Object[1];
        try {
            tx().executeWithoutResult(t->{serviceRefund();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(workers.submit(()->request(admin)));waitForHolder(holder,future.getFirst(),rollback?"refund-rollback":"refund-commit");if(rollback)t.setRollbackOnly();else expectedOther[0]=otherFacts();});
            Outcome response=finish(future.getFirst());
            if(rollback){code(response,200,0);appended(before,1,adminId,lower);assertEquals(originalOther,otherFacts());}else assertAll(()->code(response,200,7106),()->assertEquals(before,microFacts()),()->assertEquals(expectedOther[0],otherFacts()));
        } finally{close(workers);}
    }
    @Test void actualPublicationHolderPreventsStaleReceivedAppendAfterItsCommit() throws Exception {
        var before=microFacts();var workers=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();final Object[] expectedOther=new Object[1];
        try {
            tx().executeWithoutResult(t->{servicePublish();entityManager.flush();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);expectedOther[0]=otherFacts();future.add(workers.submit(()->request(admin)));waitForHolder(holder,future.getFirst(),"actual-publish-commit");});
            Outcome response=finish(future.getFirst());assertAll(()->code(response,200,7106),()->assertEquals(before,microFacts()),()->assertEquals(expectedOther[0],otherFacts()));
        } finally{close(workers);}
    }
    @ParameterizedTest @ValueSource(strings={"PUBLISHED","COLLECTED"})
    void sampleLockWaitRechecksItsCommittedState(String state) throws Exception {
        var before=microFacts();var workers=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();final Object[] expectedOther=new Object[1];
        try {
            tx().executeWithoutResult(t->{jdbc.queryForList("select id from lis_sample where id=? for update",sample);jdbc.update("update lis_sample set status=? where id=?",state,sample);expectedOther[0]=otherFacts();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(workers.submit(()->request(admin)));waitForHolder(holder,future.getFirst(),"sample-state-"+state);});
            Outcome response=finish(future.getFirst());assertAll(()->code(response,200,7106),()->assertEquals(before,microFacts()),()->assertEquals(expectedOther[0],otherFacts()));
        } finally{close(workers);}
    }
    @Test void sampleLockWaitRechecksItsOrderRelationInsteadOfSplittingSampleAndOrder() throws Exception {
        Long other=order("新关系","LAB","CHARGED");var before=microFacts();var workers=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();final Object[] expectedOther=new Object[1];
        try {
            tx().executeWithoutResult(t->{jdbc.queryForList("select id from lis_sample where id=? for update",sample);jdbc.update("update lis_sample set order_id=? where id=?",other,sample);expectedOther[0]=otherFacts();int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(workers.submit(()->request(admin)));waitForHolder(holder,future.getFirst(),"sample-relation-change");});
            Outcome response=finish(future.getFirst());assertAll(()->code(response,200,7106),()->assertEquals(before,microFacts()),()->assertEquals(expectedOther[0],otherFacts()));
        } finally{close(workers);}
    }
    @ParameterizedTest @ValueSource(strings={"O:disable","S:disable","O:revoke","S:revoke"})
    void employeesChangedDuringActualWaitCannotAppendWithTheirOldRequestAuthority(String scenario) throws Exception {
        var before=microFacts();var other=otherFacts();var workers=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();
        try {
            tx().executeWithoutResult(t->{
                if(scenario.startsWith("O:"))jdbc.queryForList("select id from outp_order where id=? for update",order);else jdbc.queryForList("select id from lis_sample where id=? for update",sample);
                int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(workers.submit(()->request(admin)));waitForHolder(holder,future.getFirst(),scenario);
                if(scenario.endsWith("disable"))jdbc.update("update sys_user set enabled=false where id=?",adminId);else jdbc.update("delete from sys_user_role where user_id=?",adminId);
            });
            Outcome response=finish(future.getFirst());assertAll(()->code(response,403,1005),()->assertEquals(before,microFacts()),()->assertEquals(other,otherFacts()));
        } finally{close(workers);}
    }
    @Test void twoValidMicroRequestsStillAppendTwoCulturesWithAProvenLockWait() throws Exception {
        var before=microFacts();var other=otherFacts();Instant lower=clock();var workers=Executors.newFixedThreadPool(2);var future=new ArrayList<Future<Outcome>>();
        try {
            tx().executeWithoutResult(t->{jdbc.queryForList("select id from outp_order where id=? for update",order);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(workers.submit(()->request(admin)));future.add(workers.submit(()->request(admin)));waitForHolder(holder,future.getFirst(),"two-valid-appends");});
            code(finish(future.get(0)),200,0);code(finish(future.get(1)),200,0);appended(before,2,adminId,lower);assertEquals(other,otherFacts());
        } finally{close(workers);}
    }
}
