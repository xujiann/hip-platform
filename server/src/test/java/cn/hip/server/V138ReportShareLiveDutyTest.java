package cn.hip.server;

import cn.hip.platform.core.common.Masking;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real JWT security-chain requests own their transactions. Named PostgreSQL holders
 * establish each O/E/share wait before changing current duty. Committed historical SQL
 * fixtures are not a claim about clinical setup APIs or an outer-test rollback. */
@SpringBootTest
@AutoConfigureMockMvc
class V138ReportShareLiveDutyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;

    record Staff(Long id,String name,String token) {}
    record Outcome(int http,JsonNode body) {
        int code() { return body.get("code").asInt(); }
        JsonNode data() { return body.get("data"); }
    }
    record AuthFacts(List<Map<String,Object>> users,List<Map<String,Object>> roles) {}
    final List<Staff> employees=new ArrayList<>();
    String tag,link,blockedLink,expiredLink;
    Long dept,otherDept,patient,otherPatient,schedule,registration,otherRegistration,order,otherOrder,spareOrder,exam,otherExam;
    Staff actor;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        tag="V138"+Long.toUnsignedString(System.nanoTime(),36);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        otherDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"D",tag+"异科");
        actor=employee("actor","TECHNICIAN");
        assertEquals(1,jdbc.update("update sys_user set dept_id=? where id=?",otherDept,actor.id()));
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'张模拟患者','U') returning id",Long.class,tag);
        otherPatient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'李另一患者','U') returning id",Long.class,tag+"p2");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,2,2,0) returning id",Long.class,dept);
        registration=registration(patient,1);otherRegistration=registration(otherPatient,2);
        order=order(registration,"原检查");otherOrder=order(otherRegistration,"另一检查");spareOrder=order(otherRegistration,"未关联检查");
        paidBill(registration,order);paidBill(otherRegistration,otherOrder);
        exam=exam(order,"原所见","原结论");otherExam=exam(otherOrder,"另一患者所见","另一患者结论");
        link=tag+"first";blockedLink=tag+"second";expiredLink=tag+"expired";
        for(String token:List.of(link,blockedLink))jdbc.update("insert into report_share(token,exam_id,expire_at) values (?,?,clock_timestamp()+interval '2 hours')",token,exam);
        jdbc.update("insert into report_share(token,exam_id,expire_at,access_cnt) values (?,?,clock_timestamp()-interval '2 hours',7)",expiredLink,exam);
        jdbc.update("insert into report_share(token,exam_id,expire_at,access_cnt) values (?,?,clock_timestamp()+interval '2 hours',11)",tag+"other",otherExam);
    }
    Staff employee(String suffix,String role) {
        String name=tag+suffix;
        Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",Long.class,name,name,dept);
        Staff s=new Staff(id,name,jwt.issue(name));employees.add(s);roles(s,role);return s;
    }
    void roles(Staff who,String... duties) {
        jdbc.update("delete from sys_user_role where user_id=?",who.id());
        for(String duty:duties)assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",who.id(),duty));
    }
    Long registration(Long p,int no) {
        return jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (?,?,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,no,p,schedule,dept,actor.id());
    }
    Long order(Long reg,String item) {
        return jdbc.queryForObject("insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id,remark,clinical_summary) values (?,?,'EXAM',0,?,?,'次',1,7.50,7.50,'CHARGED',?,'原备注','原摘要') returning id",Long.class,reg,tag,tag+reg+item,item,actor.id());
    }
    Long exam(Long o,String findings,String impression) {
        return jdbc.queryForObject("insert into ris_exam(order_id,status,findings,impression,reporter_id,verifier_id,reported_at,verified_at,modality) values (?,'VERIFIED',?,?,?,?,now(),now(),'CT') returning id",Long.class,o,findings,impression,actor.id(),actor.id());
    }
    void paidBill(Long reg,Long o) {
        Long bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status,cashier_id) values (?,?,7.50,'CASH','PAID',?) returning id",Long.class,tag+reg,reg,actor.id());
        assertEquals(1,jdbc.update("update outp_order set charge_id=? where id=?",bill,o));
    }
    Outcome request(String operation,String token,Long target,Integer minutes,String viewToken)throws Exception {
        SecurityContextHolder.clearContext();
        try {
            MockHttpServletRequestBuilder req=switch(operation) {
                case "share"->post("/api/ris/exams/"+target+"/share");
                case "revoke"->delete("/api/ris/exams/"+target+"/share");
                case "view"->get("/api/share/"+viewToken);
                default->throw new IllegalArgumentException(operation);
            };
            if(minutes!=null)req.param("expireMinutes",minutes.toString());
            if(token!=null)req.header("Authorization","Bearer "+token);
            var result=mvc.perform(req).andReturn();
            return new Outcome(result.getResponse().getStatus(),mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
        } finally {SecurityContextHolder.clearContext();}
    }
    Outcome request(String op)throws Exception {return request(op,actor.token(),exam,null,null);}
    Outcome view(String token)throws Exception {return request("view",null,null,null,token);}
    Map<String,List<Map<String,Object>>> facts() {
        var result=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(var e:new String[][] {
            {"orders","outp_order","registration_id in("+registration+","+otherRegistration+")"},
            {"exams","ris_exam","id in("+exam+","+otherExam+")"},
            {"shares","report_share","exam_id in("+exam+","+otherExam+") or token like '"+tag+"%'"},
            {"charges","outp_charge","registration_id in("+registration+","+otherRegistration+")"},
            {"registrations","outp_registration","id in("+registration+","+otherRegistration+")"},
            {"schedule","outp_schedule","id="+schedule},{"patients","empi_patient","id in("+patient+","+otherPatient+")"},
            {"alerts","outp_critical_alert","registration_id in("+registration+","+otherRegistration+")"},
            {"cdr","cdr_document","patient_id in("+patient+","+otherPatient+")"},
            {"lab","outp_lab_result","order_id in("+order+","+otherOrder+","+spareOrder+")"},
            {"reports","outp_order_report","order_id in("+order+","+otherOrder+","+spareOrder+")"},
            {"stock","pharm_stock","true"},{"stockFlows","pharm_stock_flow","true"},{"batches","pharm_batch","true"}})
            result.put(e[0],jdbc.queryForList("select * from "+e[1]+" where "+e[2]+" order by id"));
        result.put("config",jdbc.queryForList("select * from sys_config order by cfg_key"));
        return result;
    }
    AuthFacts authFacts() {
        return new AuthFacts(jdbc.queryForList("select * from sys_user where username like ? order by id",tag+"%"),
            jdbc.queryForList("select ur.* from sys_user_role ur join sys_user u on u.id=ur.user_id where u.username like ? order by ur.user_id,ur.role_id",tag+"%"));
    }
    List<Map<String,Object>> copyRows(List<Map<String,Object>> rows) {var result=new ArrayList<Map<String,Object>>();rows.forEach(r->result.add(new LinkedHashMap<>(r)));return result;}
    Map<String,List<Map<String,Object>>> copy(Map<String,List<Map<String,Object>>> original) {var result=new LinkedHashMap<String,List<Map<String,Object>>>();original.forEach((k,v)->result.put(k,copyRows(v)));return result;}
    AuthFacts allocatedRoles(AuthFacts before,String... duties) {
        var result=copyRows(before.roles());result.removeIf(r->actor.id().equals(r.get("user_id")));
        for(String duty:duties)result.add(new LinkedHashMap<>(Map.of("user_id",actor.id(),"role_id",jdbc.queryForObject("select id from sys_role where code=?",Long.class,duty))));
        result.sort(Comparator.comparingLong((Map<String,Object> r)->((Number)r.get("user_id")).longValue()).thenComparingLong(r->((Number)r.get("role_id")).longValue()));
        return new AuthFacts(before.users(),result);
    }
    AuthFacts change(String mode,AuthFacts before,String targetRole) {
        if(mode.equals("disabled")) {
            var users=copyRows(before.users());users.stream().filter(r->actor.id().equals(r.get("id"))).findFirst().orElseThrow().put("enabled",false);
            assertEquals(1,jdbc.update("update sys_user set enabled=false where id=?",actor.id()));return new AuthFacts(users,before.roles());
        }
        String[] duties=switch(mode) {case "nurse"->new String[]{"NURSE"};case "quality","rollback"->new String[]{"QUALITY"};case "none"->new String[0];case "transfer","retain"->new String[]{targetRole};default->throw new IllegalArgumentException(mode);};
        roles(actor,duties);return allocatedRoles(before,duties);
    }
    TransactionTemplate tx() {var t=new TransactionTemplate(manager);t.setTimeout(25);return t;}
    void lock(String point) {
        assertEquals(1,jdbc.queryForList(switch(point) {
            case "O"->"select id from outp_order where id=? for update";
            case "E"->"select id from ris_exam where id=? for update";
            case "S"->"select id from report_share where token=? for update";
            default->throw new IllegalArgumentException(point);
        },point.equals("O")?order:point.equals("E")?exam:blockedLink).size());
    }
    void waitForHolder(int holder,String op,String point,Future<?> worker) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        String table=switch(point) {case "O"->"outp_order";case "E"->"ris_exam";case "S"->"report_share";default->throw new IllegalArgumentException(point);};
        while(System.nanoTime()<deadline) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            var rows=jdbc.queryForList("select pid,wait_event_type,pg_blocking_pids(pid) as blockers,query from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and lower(query) like ? and lower(query) like '%for update%'",holder,"%"+table+"%");
            if(!rows.isEmpty()){System.out.println("V138 PG_LOCK op="+op+" point="+point+" holder="+holder+" waiters="+rows);return;}
            if(worker.isDone())fail("Request completed before exact PostgreSQL holder wait: "+op+"/"+point);
            try {Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        }
        fail("No named-holder PostgreSQL Lock: "+op+"/"+point);
    }
    Outcome finish(Future<Outcome> worker)throws Exception {
        try{return worker.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Unexpected request/SQL/fixture exception",e.getCause());}
    }
    void code(Outcome response,int http,int code) {
        var keys=new HashSet<String>();response.body().fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("code","message","data"),keys);
        assertAll(()->assertEquals(http,response.http(),response.body().toString()),()->assertEquals(code,response.code(),response.body().toString()));
    }
    Set<String> keys(JsonNode value) {var keys=new HashSet<String>();value.fieldNames().forEachRemaining(keys::add);return keys;}
    Instant instant(Object raw) {return raw instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)raw).toInstant();}
    Instant clock() {return instant(jdbc.queryForObject("select clock_timestamp()",Object.class));}
    Object bounded(Map<String,Object> row,String key,Instant lower,Instant upper) {
        Object raw=row.get(key);assertNotNull(raw,key);assertFalse(instant(raw).isBefore(lower),key+" before lower bound");assertFalse(instant(raw).isAfter(upper),key+" after upper bound");return raw;
    }
    Map<String,Object> row(Map<String,List<Map<String,Object>>> data,String key,Long id) {return data.get(key).stream().filter(r->id.equals(((Number)r.get("id")).longValue())).findFirst().orElseThrow();}
    String accepted(String op,Outcome response,Map<String,List<Map<String,Object>>> before,Instant lower,int minutes) {
        code(response,200,0);var after=facts();var expected=copy(before);Instant upper=clock();String token=null;
        if(op.equals("share")) {
            assertEquals(Set.of("token","url","expireMinutes"),keys(response.data()));token=response.data().get("token").asText();assertTrue(token.matches("[a-f0-9]{32}"));assertEquals("/api/share/"+token,response.data().get("url").asText());assertEquals(minutes,response.data().get("expireMinutes").asInt());
            var ids=before.get("shares").stream().map(r->r.get("id")).toList();var added=after.get("shares").stream().filter(r->!ids.contains(r.get("id"))).toList();assertEquals(1,added.size());var actual=added.getFirst();assertTrue(((Number)actual.get("id")).longValue()>0);
            var allocated=new LinkedHashMap<String,Object>();actual.keySet().forEach(k->allocated.put(k,null));allocated.put("id",actual.get("id"));allocated.put("token",token);allocated.put("exam_id",exam);allocated.put("access_cnt",0);allocated.put("created_at",bounded(actual,"created_at",lower,upper));allocated.put("expire_at",bounded(actual,"expire_at",lower.plusSeconds(minutes*60L),upper.plusSeconds(minutes*60L)));expected.get("shares").add(allocated);
        } else {
            assertEquals(Set.of("revoked"),keys(response.data()));int changed=0;
            for(var target:expected.get("shares"))if(exam.equals(target.get("exam_id"))&&instant(target.get("expire_at")).isAfter(lower)) {
                var actual=row(after,"shares",((Number)target.get("id")).longValue());target.put("expire_at",bounded(actual,"expire_at",lower,upper));changed++;
            }
            assertEquals(changed,response.data().get("revoked").asInt());
        }
        expected.get("shares").sort(Comparator.comparingLong(r->((Number)r.get("id")).longValue()));assertEquals(expected,after,"Only exact share creation/active expiry may change; every clinical and unrelated column is frozen");return token;
    }
    void maskedRead(String token)throws Exception {
        var before=facts();Outcome response=view(token);code(response,200,0);assertEquals(Set.of("patientName","itemName","modality","findings","impression","verifiedAt"),keys(response.data()));
        assertEquals(Masking.name("张模拟患者"),response.data().get("patientName").asText());assertEquals("原检查",response.data().get("itemName").asText());assertEquals("CT",response.data().get("modality").asText());assertEquals("原所见",response.data().get("findings").asText());assertEquals("原结论",response.data().get("impression").asText());assertEquals(String.valueOf(row(before,"exams",exam).get("verified_at")),response.data().get("verifiedAt").asText());
        var expected=copy(before);var own=expected.get("shares").stream().filter(r->token.equals(r.get("token"))).findFirst().orElseThrow();own.put("access_cnt",((Number)own.get("access_cnt")).intValue()+1);assertEquals(expected,facts(),"Exactly one successful anonymous access is counted");
    }
    void race(String op,String point,String mode,String from,String to,boolean deny)throws Exception {
        roles(actor,mode.equals("retain")?new String[]{from,to}:new String[]{from});
        var before=facts();var authBefore=authFacts();var expectedAuth=new AtomicReference<AuthFacts>();var future=new AtomicReference<Future<Outcome>>();var pool=Executors.newSingleThreadExecutor();Instant lower=clock();
        try {
            tx().executeWithoutResult(t->{lock(point);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.set(pool.submit(()->request(op)));waitForHolder(holder,op,point,future.get());expectedAuth.set(change(mode,authBefore,to));assertEquals(expectedAuth.get(),authFacts(),"Holder changes only exact current employee facts");if(mode.equals("rollback"))t.setRollbackOnly();});
            assertEquals(mode.equals("rollback")?authBefore:expectedAuth.get(),authFacts());Outcome response=finish(future.get());
            if(deny) {
                System.out.println("V138 BUSINESS expected=403/1005 actual="+response.http()+"/"+response.code()+" op="+op+" point="+point+" mode="+mode);
                assertAll(()->code(response,403,1005),()->assertTrue(response.data().isNull()),()->assertEquals(before,facts(),"Withdrawn current duty cannot change share or clinical facts"));
                assertEquals(1,jdbc.update("update sys_user set enabled=true where id=?",actor.id()));roles(actor,from);assertEquals(authBefore,authFacts(),"Known restoration returns only the original employee facts");Instant restored=clock();accepted(op,request(op),before,restored,4320);assertEquals(authBefore,authFacts());
            } else accepted(op,response,before,lower,4320);
        } finally {pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS),"Worker finishes before cleanup");}
    }

    @ParameterizedTest @CsvSource({
        "share,O,nurse","share,E,nurse","revoke,O,nurse","revoke,E,nurse","revoke,S,nurse",
        "share,O,quality","share,E,quality","revoke,O,quality","revoke,E,quality","revoke,S,quality",
        "share,O,none","share,E,none","revoke,O,none","revoke,E,none","revoke,S,none",
        "share,O,disabled","share,E,disabled","revoke,O,disabled","revoke,E,disabled","revoke,S,disabled"})
    void waitingWritesRejectLossOfAllOriginalThreeDutiesAndPermitKnownRestoration(String op,String point,String mode)throws Exception {race(op,point,mode,"TECHNICIAN","ADMIN",true);}

    @ParameterizedTest @CsvSource({
        "share,O,transfer,TECHNICIAN,DOCTOR_OUTP","share,E,transfer,DOCTOR_OUTP,ADMIN","revoke,O,transfer,ADMIN,TECHNICIAN","revoke,E,transfer,TECHNICIAN,ADMIN","revoke,S,transfer,ADMIN,DOCTOR_OUTP",
        "share,O,retain,ADMIN,TECHNICIAN","share,E,retain,TECHNICIAN,DOCTOR_OUTP","revoke,O,retain,DOCTOR_OUTP,ADMIN","revoke,E,retain,ADMIN,DOCTOR_OUTP","revoke,S,retain,DOCTOR_OUTP,TECHNICIAN",
        "share,O,rollback,ADMIN,TECHNICIAN","share,E,rollback,TECHNICIAN,ADMIN","revoke,O,rollback,DOCTOR_OUTP,ADMIN","revoke,E,rollback,TECHNICIAN,DOCTOR_OUTP","revoke,S,rollback,ADMIN,DOCTOR_OUTP"})
    void originalThreeDutiesTransfersRemainingMultiRoleAndRolledBackWithdrawalStayUsable(String op,String point,String mode,String from,String to)throws Exception {race(op,point,mode,from,to,false);}

    @ParameterizedTest @CsvSource({"ADMIN,CHARGED","TECHNICIAN,CHARGED","DOCTOR_OUTP,CHARGED","ADMIN,EXECUTED","TECHNICIAN,EXECUTED","DOCTOR_OUTP,EXECUTED"})
    void originalThreeDutiesAndSourceStatesKeepExactShareAnonymousAndRevokeContract(String role,String status)throws Exception {
        roles(actor,role);jdbc.update("update outp_order set status=? where id=?",status,order);var before=facts();Instant lower=clock();String token=accepted("share",request("share"),before,lower,4320);maskedRead(token);
        before=facts();lower=clock();accepted("revoke",request("revoke"),before,lower,4320);before=facts();Outcome expired=view(token);code(expired,200,4662);assertTrue(expired.data().isNull());assertEquals(before,facts());
    }
    @ParameterizedTest @ValueSource(strings={"NURSE","QUALITY","CASHIER","INTERFACE","anonymous","disabled"})
    void initialWrongDutyAndUnauthenticatedRequestsKeepOriginalTransportDenial(String kind)throws Exception {
        String token=actor.token();int http=403,expected=1005;
        if(kind.equals("anonymous")){token=null;http=401;expected=1000;}else if(kind.equals("disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());http=401;expected=1000;}else roles(actor,kind);
        var before=facts();var auth=authFacts();for(String op:List.of("share","revoke")){Outcome response=request(op,token,exam,null,null);code(response,http,expected);assertTrue(response.data().isNull());assertEquals(before,facts());assertEquals(auth,authFacts());}
    }
    @ParameterizedTest @ValueSource(ints={-1,0,43201})
    void originalInvalidExpiryIs4663WithoutClinicalOrShareEffects(int minutes)throws Exception {var before=facts();Outcome response=request("share",actor.token(),exam,minutes,null);code(response,200,4663);assertTrue(response.data().isNull());assertEquals(before,facts());}
    @ParameterizedTest @ValueSource(ints={1,43200})
    void originalMinimumAndMaximumExpiryRemainAccepted(int minutes)throws Exception {var before=facts();Instant lower=clock();accepted("share",request("share",actor.token(),exam,minutes,null),before,lower,minutes);}

    @ParameterizedTest @ValueSource(strings={"CREATED","REFUNDED","LAB","REPORTED"})
    void unavailableOriginalSourcesRejectPublishingAndAnonymousReadButPermitRevokeCleanup(String changed)throws Exception {
        if(changed.equals("LAB"))jdbc.update("update outp_order set order_type='LAB' where id=?",order);else if(changed.equals("REPORTED"))jdbc.update("update ris_exam set status='REPORTED' where id=?",exam);else jdbc.update("update outp_order set status=? where id=?",changed,order);
        var before=facts();for(Outcome response:List.of(request("share"),view(link))){code(response,200,4660);assertTrue(response.data().isNull());assertEquals(before,facts());}Instant lower=clock();accepted("revoke",request("revoke"),before,lower,4320);
    }
    @Test void anInFlightShareCannotFollowExamReboundToAnotherPatientsSource()throws Exception {
        var before=facts();var expected=copy(before);row(expected,"exams",exam).put("order_id",spareOrder);var worker=new AtomicReference<Future<Outcome>>();var pool=Executors.newSingleThreadExecutor();
        try {
            tx().executeWithoutResult(t->{lock("E");int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);worker.set(pool.submit(()->request("share")));waitForHolder(holder,"share","E",worker.get());assertEquals(1,jdbc.update("update ris_exam set order_id=? where id=?",spareOrder,exam));});
            Outcome response=finish(worker.get());code(response,200,4660);assertTrue(response.data().isNull());assertEquals(expected,facts(),"Only holder's exact source association change is retained");
        } finally {pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    @Test void missingExamKeepsOriginalShare4660AndRevokeZeroWithoutAnyEffects()throws Exception {
        Long missing=jdbc.queryForObject("select coalesce(max(id),0)+1000000 from ris_exam",Long.class);var before=facts();Outcome response=request("share",actor.token(),missing,null,null);code(response,200,4660);assertTrue(response.data().isNull());assertEquals(before,facts());response=request("revoke",actor.token(),missing,null,null);code(response,200,0);assertEquals(mapper.valueToTree(Map.of("revoked",0)),response.data());assertEquals(before,facts());
    }
    @ParameterizedTest @ValueSource(strings={"NURSE","disabled"})
    void validAnonymousBearerLinkDoesNotAcquireACreatorCurrentDutyCondition(String change)throws Exception {
        var before=facts();Instant lower=clock();String token=accepted("share",request("share"),before,lower,4320);if(change.equals("disabled"))jdbc.update("update sys_user set enabled=false where id=?",actor.id());else roles(actor,change);var auth=authFacts();maskedRead(token);assertEquals(auth,authFacts());
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();if(registration==null)return;
        tx().executeWithoutResult(t->{
            jdbc.update("delete from report_share where token like ? or exam_id in (?,?)",tag+"%",exam,otherExam);jdbc.update("delete from ris_exam where id in (?,?)",exam,otherExam);
            jdbc.update("delete from outp_order where registration_id in (?,?)",registration,otherRegistration);jdbc.update("delete from outp_charge where registration_id in (?,?)",registration,otherRegistration);jdbc.update("delete from outp_registration where id in (?,?)",registration,otherRegistration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id in (?,?)",patient,otherPatient);
            for(var s:employees){jdbc.update("delete from sys_user_role where user_id=?",s.id());jdbc.update("delete from sys_user where id=?",s.id());}jdbc.update("delete from sys_dept where id in (?,?)",dept,otherDept);
        });employees.clear();
    }
}
