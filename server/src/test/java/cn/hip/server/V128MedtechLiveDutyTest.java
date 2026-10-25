package cn.hip.server;

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

/** Real JWT requests, committed fixtures and named PostgreSQL holder observations.
 * No enclosing test transaction. Role changes are separately allocated holder facts;
 * they are not clinical mutations. Historical fixtures do not claim a clinical setup API.
 * This suite covers only existing manual duties and the INSERT-bearing RIS worklist.
 * Since v130 the existing-E holder may block source FOR UPDATE before the unique check. */
@SpringBootTest
@AutoConfigureMockMvc
class V128MedtechLiveDutyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    static final List<String> OPS=List.of("collect","receive","publish","arrive","report","verify","critical","worklist");
    static final Set<String> QUEUE_KEYS=Set.of("id","status","modality","findings","impression","item_name","group_no","order_status","clinical_summary","exam_purpose","notice","urgent","patient_name","diagnosis");
    String tag,barcode; Long dept,otherDept,patient,schedule,registration,bill,order,sample,exam; int serial;
    Staff actor,reporter; final List<Staff> staff=new ArrayList<>();
    record Staff(Long id,String name,String token) {}
    record Outcome(int http,JsonNode body) {int code(){return body.get("code").asInt();} JsonNode data(){return body.get("data");}}
    record Window(Instant pg,Instant jvm) {}
    record AuthFacts(List<Map<String,Object>> users,List<Map<String,Object>> roles) {}

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();tag="V128"+Long.toUnsignedString(System.nanoTime(),36);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        otherDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"S",tag+"异科");
        reporter=employee("reporter","DOCTOR_OUTP");actor=employee("actor","TECHNICIAN");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"患者");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (1,?,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept,reporter.id());
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status,cashier_id) values (?,?,7.50,'CASH','PAID',?) returning id",Long.class,tag,registration,reporter.id());
    }
    Staff employee(String suffix,String role) {
        String name=tag+suffix;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,name,name,otherDept);
        var s=new Staff(id,name,jwt.issue(name));staff.add(s);grant(s,role);return s;
    }
    void grant(Staff s,String role){assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=? on conflict do nothing",s.id(),role));}
    void roles(Staff s,String... roles){jdbc.update("delete from sys_user_role where user_id=?",s.id());for(String role:roles)grant(s,role);}
    void prepare(String op) {
        String suffix="_"+(++serial);boolean lab=List.of("collect","receive","publish").contains(op);
        order=jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id,charge_id,
                    clinical_summary,exam_purpose,notice,urgent,remark,review_status,review_note,reviewer_id)
                values (?,?,?,0,?,'专用超声','次',1,7.50,7.50,'CHARGED',?,?,'原摘要','原目的','原注意',true,'原备注','APPROVED','原审方',?) returning id
                """,Long.class,registration,tag+suffix,lab?"LAB":"EXAM",tag+suffix,reporter.id(),bill,reporter.id());
        sample=null;exam=null;barcode=tag+suffix;
        if(List.of("receive","publish").contains(op))sample=jdbc.queryForObject("insert into lis_sample(order_id,barcode,status,received_at) values (?,?,?,?) returning id",Long.class,order,barcode,op.equals("publish")?"RECEIVED":"COLLECTED",op.equals("publish")?java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")):null);
        if(!lab&&!op.equals("worklist")) {
            boolean reported=List.of("verify","critical").contains(op);
            exam=jdbc.queryForObject("insert into ris_exam(order_id,status,modality,findings,impression,reporter_id,reported_at) values (?,?,'US','原所见','原结论',?,?) returning id",Long.class,order,reported?"REPORTED":"REGISTERED",reporter.id(),java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        }
    }
    Outcome request(String op,String token) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            MockHttpServletRequestBuilder req=switch(op) {
                case "collect"->post("/api/lis/samples").param("orderId",order.toString());
                case "receive"->put("/api/lis/samples/"+barcode+"/receive");
                case "publish"->post("/api/lis/samples/"+barcode+"/publish").contentType("application/json").content("{\"results\":[{\"code\":\"V128_N\",\"name\":\"原检验\",\"value\":\"1.25\",\"unit\":\"u\",\"refRange\":\"0-2\",\"flag\":\"N\"}]}");
                case "arrive"->put("/api/ris/exams/"+exam+"/arrive");
                case "report"->put("/api/ris/exams/"+exam+"/report").contentType("application/json").content("{\"findings\":\" 原始 所见 \",\"impression\":\" 原始 结论 \"}");
                case "verify"->put("/api/ris/exams/"+exam+"/verify");
                case "critical"->put("/api/ris/exams/"+exam+"/critical").contentType("application/json").content("{\"note\":\"原危急\"}");
                case "worklist"->get("/api/ris/worklist");
                default->throw new IllegalArgumentException(op);
            };
            if(token!=null)req.header("Authorization","Bearer "+token);
            var response=mvc.perform(req).andReturn().getResponse();return new Outcome(response.getStatus(),mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally {SecurityContextHolder.clearContext();}
    }
    Map<String,List<Map<String,Object>>> facts() {
        var out=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String[] entry:new String[][]{{"patients","empi_patient","id="+patient},{"registrations","outp_registration","id="+registration},{"schedules","outp_schedule","id="+schedule},{"orders","outp_order","registration_id="+registration},{"charges","outp_charge","registration_id="+registration},{"samples","lis_sample","order_id in(select id from outp_order where registration_id="+registration+")"},{"exams","ris_exam","order_id in(select id from outp_order where registration_id="+registration+")"},{"results","outp_lab_result","order_id in(select id from outp_order where registration_id="+registration+")"},{"alerts","outp_critical_alert","registration_id="+registration},{"cdr","cdr_document","patient_id="+patient},{"reports","outp_order_report","order_id in(select id from outp_order where registration_id="+registration+")"},{"diagnoses","outp_diagnosis","registration_id="+registration},{"micro","lab_micro_result","order_id in(select id from outp_order where registration_id="+registration+")"},{"stock","pharm_stock","true"},{"stockFlows","pharm_stock_flow","true"}})
            out.put(entry[0],jdbc.queryForList("select * from "+entry[1]+" where "+entry[2]+" order by id"));
        out.put("config",jdbc.queryForList("select * from sys_config order by cfg_key"));return out;
    }
    AuthFacts authFacts(){return new AuthFacts(jdbc.queryForList("select * from sys_user where username like ? order by id",tag+"%"),jdbc.queryForList("select ur.* from sys_user_role ur join sys_user u on u.id=ur.user_id where u.username like ? order by ur.user_id,ur.role_id",tag+"%"));}
    Map<String,List<Map<String,Object>>> copy(Map<String,List<Map<String,Object>>> before){var out=new LinkedHashMap<String,List<Map<String,Object>>>();before.forEach((k,v)->{var rows=new ArrayList<Map<String,Object>>();v.forEach(row->rows.add(new LinkedHashMap<>(row)));out.put(k,rows);});return out;}
    Map<String,Object> row(Map<String,List<Map<String,Object>>> data,String key,Long id){return data.get(key).stream().filter(r->id.equals(((Number)r.get("id")).longValue())).findFirst().orElseThrow();}
    Instant instant(Object v){return v instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)v).toInstant();}
    Instant pgClock(){return instant(jdbc.queryForObject("select clock_timestamp()",Object.class));}
    Window window(){return new Window(pgClock(),Instant.now());}
    Object stamp(Map<String,Object> row,String name,Instant lower,Instant upper){Object value=row.get(name);assertNotNull(value,name);Instant time=instant(value);assertFalse(time.isBefore(lower),name+" before its clock window");assertFalse(time.isAfter(upper),name+" after its clock window");return value;}
    Map<String,Object> added(Map<String,List<Map<String,Object>>> before,Map<String,List<Map<String,Object>>> after,String key){var ids=before.get(key).stream().map(r->r.get("id")).toList();var added=after.get(key).stream().filter(r->!ids.contains(r.get("id"))).toList();assertEquals(1,added.size(),"One exact new "+key);assertTrue(((Number)added.getFirst().get("id")).longValue()>0);return added.getFirst();}
    void code(Outcome response,int http,int code){var keys=new HashSet<String>();response.body().fieldNames().forEachRemaining(keys::add);assertEquals(Set.of("code","message","data"),keys);assertAll(()->assertEquals(http,response.http(),response.body().toString()),()->assertEquals(code,response.code(),response.body().toString()));}
    void denied(Outcome response,Map<String,List<Map<String,Object>>> before){System.out.println("V128 BUSINESS expected=403/1005 actual="+response.http()+"/"+response.code());assertAll(()->code(response,403,1005),()->assertTrue(response.data().isNull(),"No clinical body after losing duty"),()->assertEquals(before,facts(),"All business columns must remain unchanged"));}
    void accepted(String op,Outcome response,Staff who,Map<String,List<Map<String,Object>>> before,Window lower) {
        code(response,200,0);var after=facts();var expected=copy(before);Instant pgEnd=pgClock(),jvmEnd=Instant.now();
        if(!List.of("collect","worklist").contains(op))assertTrue(response.data().isNull());
        switch(op) {
            case "collect"->{var actual=added(before,after,"samples");String bc=response.data().get("barcode").asText();assertTrue(bc.matches("BC[0-9]+"));assertEquals(1,response.data().size());var n=new LinkedHashMap<String,Object>();n.put("id",actual.get("id"));n.put("order_id",order);n.put("barcode",bc);n.put("status","COLLECTED");n.put("collected_at",stamp(actual,"collected_at",lower.pg(),pgEnd));n.put("received_at",null);n.put("published_at",null);n.put("verifier_id",null);n.put("substitute",false);n.put("substitute_name",null);expected.get("samples").add(n);}
            case "receive"->{var n=row(expected,"samples",sample);n.put("status","RECEIVED");n.put("received_at",stamp(row(after,"samples",sample),"received_at",lower.pg(),pgEnd));}
            case "publish"->{var n=row(expected,"samples",sample);n.put("status","PUBLISHED");n.put("verifier_id",who.id());n.put("published_at",stamp(row(after,"samples",sample),"published_at",lower.pg(),pgEnd));executed(expected,n.get("published_at"));var actual=added(before,after,"results");var result=new LinkedHashMap<String,Object>();result.put("id",actual.get("id"));result.put("order_id",order);result.put("item_code","V128_N");result.put("item_name","原检验");result.put("result_value","1.25");result.put("unit","u");result.put("ref_range","0-2");result.put("abnormal_flag","N");result.put("created_at",stamp(actual,"created_at",lower.jvm(),jvmEnd));expected.get("results").add(result);}
            case "arrive"->{var n=row(expected,"exams",exam);n.put("status","ARRIVED");n.put("arrived_at",stamp(row(after,"exams",exam),"arrived_at",lower.pg(),pgEnd));}
            case "report"->{var n=row(expected,"exams",exam);n.put("status","REPORTED");n.put("findings"," 原始 所见 ");n.put("impression"," 原始 结论 ");n.put("reporter_id",who.id());n.put("reported_at",stamp(row(after,"exams",exam),"reported_at",lower.pg(),pgEnd));}
            case "verify"->{var n=row(expected,"exams",exam);n.put("status","VERIFIED");n.put("verifier_id",who.id());n.put("verified_at",stamp(row(after,"exams",exam),"verified_at",lower.pg(),pgEnd));executed(expected,n.get("verified_at"));}
            case "critical"->{var n=row(expected,"exams",exam);n.put("critical_flag",true);n.put("critical_note","原危急");var actual=added(before,after,"alerts");var alert=new LinkedHashMap<String,Object>();alert.put("id",actual.get("id"));alert.put("order_id",order);alert.put("registration_id",registration);alert.put("content","【影像危急值】专用超声：原危急");alert.put("status","NEW");alert.put("handler_id",null);alert.put("handled_at",null);Object now=stamp(actual,"created_at",lower.pg(),pgEnd);alert.put("created_at",now);alert.put("source","RIS");alert.put("notify_to_user_id",reporter.id());alert.put("notified_at",now);int minutes=Integer.parseInt(jdbc.queryForObject("select cfg_value from sys_config where cfg_key='critical_ack_deadline_minutes'",String.class));assertEquals(instant(now).plusSeconds(minutes*60L),instant(actual.get("deadline_at")));alert.put("deadline_at",actual.get("deadline_at"));alert.put("ack_by",null);alert.put("ack_at",null);alert.put("disposition",null);expected.get("alerts").add(alert);}
            case "worklist"->{assertTrue(response.data().isArray());for(var q:response.data()){var keys=new HashSet<String>();q.fieldNames().forEachRemaining(keys::add);assertEquals(QUEUE_KEYS,keys);}var actual=added(before,after,"exams");var n=defaultExam(actual,lower.pg(),pgEnd);expected.get("exams").add(n);assertTrue(response.data().findValues("id").stream().anyMatch(id->id.asLong()==((Number)actual.get("id")).longValue()));}
            default->throw new IllegalArgumentException(op);
        }
        expected.values().forEach(rows->{if(!rows.isEmpty()&&rows.getFirst().containsKey("id"))rows.sort(Comparator.comparingLong(r->((Number)r.get("id")).longValue()));});assertEquals(expected,after,"Only exactly allocated original effects are allowed for "+op);
    }
    Map<String,Object> defaultExam(Map<String,Object> actual,Instant lower,Instant upper){var n=new LinkedHashMap<String,Object>();n.put("id",actual.get("id"));n.put("order_id",order);n.put("status","REGISTERED");n.put("findings",null);n.put("impression",null);n.put("reporter_id",null);n.put("verifier_id",null);n.put("reported_at",null);n.put("verified_at",null);n.put("created_at",stamp(actual,"created_at",lower,upper));n.put("modality","US");n.put("critical_flag",false);n.put("critical_note",null);n.put("arrived_at",null);return n;}
    // V47/V50 triggers touch O and R with the same PostgreSQL transaction now().
    // Allocate only these two known timestamp fields rather than ignoring whole rows.
    void executed(Map<String,List<Map<String,Object>>> expected,Object transactionNow){var o=row(expected,"orders",order);o.put("status","EXECUTED");o.put("updated_at",transactionNow);row(expected,"registrations",registration).put("updated_at",transactionNow);}
    Map<String,List<Map<String,Object>>> winner(Map<String,List<Map<String,Object>>> before,Instant lower){
        jdbc.update("insert into ris_exam(order_id,status,modality,findings,impression,reporter_id,reported_at) values (?,'REPORTED','ORIGINAL','胜出所见','胜出结论',?,now())",order,reporter.id());
        var after=facts();var actual=added(before,after,"exams");var n=defaultExam(actual,lower,pgClock());n.put("status","REPORTED");n.put("modality","ORIGINAL");n.put("findings","胜出所见");n.put("impression","胜出结论");n.put("reporter_id",reporter.id());n.put("reported_at",n.get("created_at"));var expected=copy(before);expected.get("exams").add(n);assertEquals(expected,after,"Holder commits only one completely allocated winning E");return expected;
    }
    TransactionTemplate tx(){var tx=new TransactionTemplate(manager);tx.setTimeout(25);return tx;}
    void lock(String point){jdbc.queryForList(switch(point){case "O"->"select id from outp_order where id=? for update";case "S"->"select id from lis_sample where id=? for update";case "E"->"select id from ris_exam where id=? for update";default->throw new IllegalArgumentException(point);},point.equals("O")?order:point.equals("S")?sample:exam);}
    void waitForHolder(int holder,Future<?> future,String stage){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){jdbc.execute("select pg_stat_clear_snapshot()");var rows=jdbc.queryForList("select pid,wait_event_type,pg_blocking_pids(pid) as blockers,query from pg_stat_activity where pid<>pg_backend_pid() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and (lower(query) like '%outp_order%' or lower(query) like '%lis_sample%' or lower(query) like '%ris_exam%')",holder);if(!rows.isEmpty()){System.out.println("V128 PG_LOCK stage="+stage+" holder="+holder+" waiters="+rows);return;}if(future.isDone())fail("MONITOR: request completed before named-holder PostgreSQL Lock at "+stage);try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}fail("MONITOR: no actual named-holder PostgreSQL Lock at "+stage);}
    Outcome finish(Future<Outcome> f)throws Exception{try{return f.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("HTTP request failed at runtime instead of returning a business response",e.getCause());}}
    void close(ExecutorService pool)throws Exception{pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}

    @ParameterizedTest @CsvSource({"collect,O,ADMIN,revoke","receive,O,TECHNICIAN,replace","receive,S,DOCTOR_OUTP,revoke","publish,O,NURSE,replace","publish,S,ADMIN,revoke","arrive,O,TECHNICIAN,replace","arrive,E,DOCTOR_OUTP,revoke","report,O,NURSE,replace","report,E,ADMIN,revoke","verify,O,TECHNICIAN,replace","verify,E,DOCTOR_OUTP,revoke","critical,O,NURSE,replace","critical,E,ADMIN,revoke"})
    void allManualLockPointsRejectTheDutyLostDuringAnActualJwtRequest(String op,String point,String role,String mode)throws Exception{
        roles(actor,role);prepare(op);var before=facts();var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();var expectedAuth=new AtomicReference<AuthFacts>();
        try{tx().executeWithoutResult(t->{lock(point);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(op,actor.token())));waitForHolder(holder,future.getFirst(),op+":"+point+":"+mode);roles(actor,mode.equals("replace")?new String[]{"QUALITY"}:new String[]{});expectedAuth.set(authFacts());});assertEquals(expectedAuth.get(),authFacts());denied(finish(future.getFirst()),before);roles(actor,role);Window w=window();accepted(op,request(op,actor.token()),actor,before,w);}finally{close(pool);}
    }

    @ParameterizedTest @ValueSource(strings={"disable","revoke"})
    void worklistRejectsAfterItsRegistrationWaitAndRollsBackOnlyItsOwnRegistrations(String mode)throws Exception{
        prepare("worklist");Long first=order;prepare("worklist");Long second=order;order=first;var original=facts();Instant lower=pgClock();var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();var expected=new AtomicReference<Map<String,List<Map<String,Object>>>>();var expectedAuth=new AtomicReference<AuthFacts>();
        try{tx().executeWithoutResult(t->{expected.set(winner(original,lower));int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request("worklist",actor.token())));waitForHolder(holder,future.getFirst(),"worklist:registration:"+mode);if(mode.equals("disable"))jdbc.update("update sys_user set enabled=false where id=?",actor.id());else roles(actor,"QUALITY");expectedAuth.set(authFacts());});assertEquals(expectedAuth.get(),authFacts());denied(finish(future.getFirst()),expected.get());assertEquals(0,jdbc.queryForObject("select count(*) from ris_exam where order_id=?",Integer.class,second));jdbc.update("update sys_user set enabled=true where id=?",actor.id());roles(actor,"NURSE");order=second;Window w=window();accepted("worklist",request("worklist",actor.token()),actor,expected.get(),w);}finally{close(pool);}
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE"})
    void originalFourDutiesRetainEveryEndpointAndExactBusinessEffects(String role)throws Exception{roles(actor,role);for(String op:OPS){prepare(op);var before=facts();Window w=window();accepted(op,request(op,actor.token()),actor,before,w);}}

    @ParameterizedTest @CsvSource({"publish,S,retain","publish,O,transfer","verify,E,retain","report,O,transfer","worklist,registration,retain","worklist,registration,transfer"})
    void anotherRemainingOrTransferredOriginalDutyStillWorksAfterWaiting(String op,String point,String mode)throws Exception{
        if(mode.equals("retain"))grant(actor,"NURSE");prepare(op);var original=facts();var before=new AtomicReference<Map<String,List<Map<String,Object>>>>();var expectedAuth=new AtomicReference<AuthFacts>();var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();Window w=window();
        try{tx().executeWithoutResult(t->{if(point.equals("registration"))before.set(winner(original,w.pg()));else{lock(point);before.set(original);}int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(op,actor.token())));waitForHolder(holder,future.getFirst(),op+":"+point+":"+mode);roles(actor,"NURSE");expectedAuth.set(authFacts());});assertEquals(expectedAuth.get(),authFacts());if(op.equals("worklist")){code(finish(future.getFirst()),200,0);assertEquals(before.get(),facts());}else accepted(op,finish(future.getFirst()),actor,before.get(),w);}finally{close(pool);}
    }

    @ParameterizedTest @CsvSource({"publish,S","report,E","worklist,unique"})
    void rolledBackRoleWithdrawalLeavesTheWaitingRequestLegitimate(String op,String point)throws Exception{
        prepare(op);var before=facts();var authBefore=authFacts();var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();Window w=window();
        try{tx().executeWithoutResult(t->{if(point.equals("registration"))winner(before,w.pg());else lock(point);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(op,actor.token())));waitForHolder(holder,future.getFirst(),op+":"+point+":role-rollback");roles(actor,"QUALITY");t.setRollbackOnly();});assertEquals(authBefore,authFacts());accepted(op,finish(future.getFirst()),actor,before,w);}finally{close(pool);}
    }

    @ParameterizedTest @ValueSource(strings={"CASHIER","PORTAL","anonymous","disabled"})
    void wrongDomainsAndInvalidEmployeesNeverEnterAnyOfTheEightEndpoints(String identity)throws Exception{
        String token=identity.equals("anonymous")?null:identity.equals("PORTAL")?jwt.issue("portal:"+patient):actor.token();if(identity.equals("CASHIER"))roles(actor,"CASHIER");if(identity.equals("disabled"))jdbc.update("update sys_user set enabled=false where id=?",actor.id());
        for(String op:OPS){prepare(op);var before=facts();var response=request(op,token);code(response,List.of("anonymous","disabled").contains(identity)?401:403,List.of("anonymous","disabled").contains(identity)?1000:1005);assertTrue(response.data().isNull());assertEquals(before,facts());}
    }
    @Test void originalStateErrorsAndSelfVerifyRulePreserveEveryColumn()throws Exception{
        for(String op:OPS.subList(0,7)){prepare(op);int expected=switch(op){case "collect"->{jdbc.update("update outp_order set status='CREATED' where id=?",order);yield 9940;}case "receive"->{jdbc.update("update lis_sample set status='RECEIVED' where id=?",sample);yield 9941;}case "publish"->{jdbc.update("update lis_sample set status='COLLECTED' where id=?",sample);yield 9942;}case "arrive"->{jdbc.update("update ris_exam set status='ARRIVED' where id=?",exam);yield 9944;}case "report"->{jdbc.update("update ris_exam set status='VERIFIED' where id=?",exam);yield 9944;}case "verify"->{jdbc.update("update ris_exam set status='REGISTERED' where id=?",exam);yield 9945;}default->{jdbc.update("update ris_exam set status='REGISTERED' where id=?",exam);yield 9945;}};var before=facts();var response=request(op,actor.token());code(response,200,expected);assertTrue(response.data().isNull());assertEquals(before,facts());}
        prepare("verify");var before=facts();code(request("verify",reporter.token()),200,9947);assertEquals(before,facts());
    }
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(registration==null)return;tx().executeWithoutResult(t->{for(String table:List.of("outp_critical_alert","outp_lab_result","outp_order_report","lis_sample","ris_exam","lab_micro_result"))jdbc.update("delete from "+table+" where order_id in(select id from outp_order where registration_id=?)",registration);jdbc.update("delete from cdr_document where patient_id=?",patient);jdbc.update("delete from outp_diagnosis where registration_id=?",registration);jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id=?",patient);for(Staff s:staff){jdbc.update("delete from sys_user_role where user_id=?",s.id());jdbc.update("delete from sys_user where id=?",s.id());}jdbc.update("delete from sys_dept where id in (?,?)",dept,otherDept);});staff.clear();}
}
