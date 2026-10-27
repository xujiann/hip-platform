package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

/** Real JWT through MockMvc's complete security chain, committed clinical fixtures, and
 * named-holder PostgreSQL waits. No outer test transaction or simulated Authentication.
 * Role changes belong only to the holder; every clinical column is separately allocated.
 * Historical fixture SQL does not pretend to be a clinical setup API. */
@SpringBootTest
@AutoConfigureMockMvc
class V137PathologyLiveDutyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;

    record Staff(Long id, String name, String token) {}
    record Outcome(int http, JsonNode body) {
        int code() { return body.get("code").asInt(); }
        JsonNode data() { return body.get("data"); }
    }
    record AuthFacts(List<Map<String,Object>> users, List<Map<String,Object>> roles) {}
    final List<Staff> staff = new ArrayList<>();
    String tag, source, operation, barcode;
    Long dept, otherDept, patient, schedule, registration, charge, order, bed, admission, inpOrder, specimen, otherSpecimen, block, techOrder;
    Staff actor, firstDoctor, secondDoctor;

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        tag = "V137" + Long.toUnsignedString(System.nanoTime(),36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        otherDept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"D",tag+"异科");
        firstDoctor = employee("first","DOCTOR_OUTP");
        secondDoctor = employee("second","DOCTOR_OUTP");
        actor = employee("actor","DOCTOR_OUTP");
        assertEquals(1,jdbc.update("update sys_user set dept_id=? where id=?",otherDept,actor.id()));
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"模拟患者");
        schedule = jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration = jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (1,?,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept,firstDoctor.id());
        charge = jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status,cashier_id) values (?,?,7.50,'CASH','PAID',?) returning id",Long.class,tag,registration,firstDoctor.id());
        order = jdbc.queryForObject("""
            insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,
              unit,qty,unit_price,amount,status,doctor_id,charge_id,remark,clinical_summary)
            values (?,?,'TREAT',0,?,'模拟病理','次',1,7.50,7.50,'CHARGED',?,?,'原备注','原摘要') returning id
            """,Long.class,registration,tag,tag,firstDoctor.id(),charge);
        bed = jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",Long.class,dept,tag);
        admission = jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id) values (?,?,?,?,?,?) returning id",Long.class,tag,patient,dept,dept,bed,firstDoctor.id());
        inpOrder = jdbc.queryForObject("insert into inp_order(admission_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id) values (?,?,'TREAT',0,?,'模拟病理','次',1,7.50,7.50,'CREATED',?) returning id",Long.class,admission,tag,tag,firstDoctor.id());
    }
    Staff employee(String suffix,String role) {
        String name=tag+suffix;
        Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",Long.class,name,name,dept);
        var s=new Staff(id,name,jwt.issue(name));staff.add(s);roles(s,role);return s;
    }
    void roles(Staff who,String... duties) {
        jdbc.update("delete from sys_user_role where user_id=?",who.id());
        for(String duty:duties) assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",who.id(),duty));
    }
    boolean signer(String op) { return Set.of("first","second","issue","supplement").contains(op); }
    void prepare(String op,String origin) {
        operation=op;source=origin;roles(actor,signer(op)?"DOCTOR_OUTP":"TECHNICIAN");
        barcode=tag+"s";
        specimen=newSpecimen(origin,barcode);
        otherSpecimen=newSpecimen(origin.equals("OUTP")?"INP":"OUTP",tag+"other");
        if(signer(op)) {
            jdbc.update("update path_specimen set status='DIAGNOSED',received_at=now(),diagnosis='原病理诊断',diagnosed_at=now(),pathologist_id=? where id=?",firstDoctor.id(),specimen);
            if(Set.of("second","issue").contains(op)) jdbc.update("update path_specimen set first_signer_id=?,first_signed_at=now() where id=?",firstDoctor.id(),specimen);
            if(op.equals("issue")) jdbc.update("update path_specimen set second_signer_id=?,second_signed_at=now() where id=?",secondDoctor.id(),specimen);
            if(op.equals("supplement")) jdbc.update("insert into path_report(specimen_id,seq_no,content,reason,signer_id,signed_at,created_by) values (?,1,'原补充正文','原原因',?,now(),?)",specimen,firstDoctor.id(),firstDoctor.id());
        }
        if(Set.of("embed","cancel").contains(op)) {
            jdbc.update("update path_specimen set status='RECEIVED',received_at=now() where id=?",specimen);
            block=jdbc.queryForObject("insert into path_block(specimen_id,block_no,block_code,tissue_desc,created_by) values (?,1,?,'原组织',?) returning id",Long.class,specimen,tag+"b",firstDoctor.id());
            if(op.equals("cancel")) techOrder=jdbc.queryForObject("insert into path_tech_order(specimen_id,block_id,tech_type,reason,ordered_by) values (?,?,'DEEP_CUT','原技术原因',?) returning id",Long.class,specimen,block,firstDoctor.id());
        }
        jdbc.update("insert into path_process(specimen_id,node,occurred_at,operator_id,remark) values (?,'READ',now(),?,'原历史流转')",otherSpecimen,firstDoctor.id());
    }
    Long newSpecimen(String origin,String code) {
        return jdbc.queryForObject("insert into path_specimen(order_id,inp_order_id,part_no,barcode,specimen_desc,status,gross_finding,micro_finding) values (?,?,1,?,'真实组织','COLLECTED','原大体','原镜下') returning id",Long.class,origin.equals("OUTP")?order:null,origin.equals("INP")?inpOrder:null,code);
    }
    Outcome request(String token) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            String prefix="/api/pathology/report/"+specimen;
            MockHttpServletRequestBuilder req=switch(operation) {
                case "first"->put(prefix+"/first-sign").param("remark","原核对");
                case "second"->put(prefix+"/second-sign").param("remark","原核对");
                case "issue"->put(prefix+"/issue");
                case "supplement"->post(prefix+"/supplement").content("{\"content\":\" 新补充正文 \",\"reason\":\" 新原因 \"}");
                case "receive"->put("/api/pathology/specimens/"+barcode+"/receive");
                case "embed"->put("/api/pathology/process/blocks/"+block+"/embed").param("remark","原核对");
                case "cancel"->put("/api/pathology/report/tech-orders/"+techOrder+"/cancel").content("{\"reason\":\"不再需要\"}");
                case "register"->post("/api/pathology/registry/specimens").content("{\""+(source.equals("OUTP")?"orderId":"inpOrderId")+"\":"+(source.equals("OUTP")?order:inpOrder)+",\"partNo\":2,\"specimenType\":\"ROUTINE\",\"specimenDesc\":\"新组织\"}");
                default->throw new IllegalArgumentException(operation);
            };
            req.contentType("application/json");
            if(token!=null)req.header("Authorization","Bearer "+token);
            var result=mvc.perform(req).andReturn();
            return new Outcome(result.getResponse().getStatus(),mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
        } finally { SecurityContextHolder.clearContext(); }
    }
    Map<String,List<Map<String,Object>>> facts() {
        var out=new LinkedHashMap<String,List<Map<String,Object>>>();
        String own="(order_id="+order+" or inp_order_id="+inpOrder+")";
        String specimens="select id from path_specimen where "+own;
        for(var e:new String[][]{
            {"orders","outp_order","registration_id="+registration},{"charges","outp_charge","registration_id="+registration},
            {"registrations","outp_registration","id="+registration},{"schedule","outp_schedule","id="+schedule},
            {"patients","empi_patient","id="+patient},{"admissions","inp_admission","id="+admission},
            {"beds","inp_bed","id="+bed},{"inpOrders","inp_order","admission_id="+admission},
            {"specimens","path_specimen",own},{"process","path_process","specimen_id in("+specimens+")"},
            {"reports","path_report","specimen_id in("+specimens+")"},{"blocks","path_block","specimen_id in("+specimens+")"},
            {"slides","path_slide","block_id in(select id from path_block where specimen_id in("+specimens+"))"},
            {"techOrders","path_tech_order","specimen_id in("+specimens+")"},{"gross","path_gross_revision","specimen_id in("+specimens+")"},
            {"fields","path_gross_field","specimen_id in("+specimens+")"},{"alerts","outp_critical_alert","registration_id="+registration},
            {"cdr","cdr_document","patient_id="+patient},{"lab","outp_lab_result","order_id="+order},
            {"orderReports","outp_order_report","order_id="+order},{"stock","pharm_stock","true"},
            {"stockFlows","pharm_stock_flow","true"},{"batches","pharm_batch","true"}})
            out.put(e[0],jdbc.queryForList("select * from "+e[1]+" where "+e[2]+" order by id"));
        out.put("config",jdbc.queryForList("select * from sys_config order by cfg_key"));
        return out;
    }
    AuthFacts authFacts() {
        return new AuthFacts(jdbc.queryForList("select * from sys_user where username like ? order by id",tag+"%"),
            jdbc.queryForList("select ur.* from sys_user_role ur join sys_user u on u.id=ur.user_id where u.username like ? order by ur.user_id,ur.role_id",tag+"%"));
    }
    List<Map<String,Object>> copyRows(List<Map<String,Object>> rows) { var copy=new ArrayList<Map<String,Object>>();rows.forEach(r->copy.add(new LinkedHashMap<>(r)));return copy; }
    AuthFacts allocateRoles(AuthFacts before,String... duties) {
        var expected=copyRows(before.roles());expected.removeIf(r->actor.id().equals(r.get("user_id")));
        for(String duty:duties) {
            Long id=jdbc.queryForObject("select id from sys_role where code=?",Long.class,duty);
            expected.add(new LinkedHashMap<>(Map.of("user_id",actor.id(),"role_id",id)));
        }
        expected.sort(Comparator.comparingLong((Map<String,Object> r)->((Number)r.get("user_id")).longValue()).thenComparingLong(r->((Number)r.get("role_id")).longValue()));
        return new AuthFacts(before.users(),expected);
    }
    AuthFacts change(String mode,AuthFacts before) {
        if(mode.equals("disable")) {
            var users=copyRows(before.users());users.stream().filter(r->actor.id().equals(r.get("id"))).findFirst().orElseThrow().put("enabled",false);
            assertEquals(1,jdbc.update("update sys_user set enabled=false where id=?",actor.id()));
            return new AuthFacts(users,before.roles());
        }
        String[] duties=switch(mode) {
            case "lose"->new String[]{signer(operation)?"TECHNICIAN":"QUALITY"};
            case "retain"->new String[]{signer(operation)?"ADMIN":"DOCTOR_OUTP"};
            case "transfer"->new String[]{signer(operation)?"ADMIN":"DOCTOR_OUTP"};
            case "rollback"->new String[]{"QUALITY"};
            default->throw new IllegalArgumentException(mode);
        };
        roles(actor,duties);return allocateRoles(before,duties);
    }
    TransactionTemplate tx() { var t=new TransactionTemplate(manager);t.setTimeout(25);return t; }
    void lock(String point) {
        jdbc.queryForList(switch(point) {
            case "O"->"select id from "+(source.equals("OUTP")?"outp_order":"inp_order")+" where id=? for update";
            case "S"->"select id from path_specimen where id=? for update";
            case "B"->"select id from path_block where id=? for update";
            case "T"->"select id from path_tech_order where id=? for update";
            default->throw new IllegalArgumentException(point);
        },switch(point) {case "O"->source.equals("OUTP")?order:inpOrder;case "S"->specimen;case "B"->block;default->techOrder;});
    }
    void waitForHolder(int holder,String point,Future<?> future) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        String table=switch(point) {case "O"->source.equals("OUTP")?"outp_order":"inp_order";case "S"->"path_specimen";case "B"->"path_block";default->"path_tech_order";};
        while(System.nanoTime()<end) {
            jdbc.execute("select pg_stat_clear_snapshot()");
            var rows=jdbc.queryForList("select pid,wait_event_type,pg_blocking_pids(pid) as blockers,query from pg_stat_activity where pid<>pg_backend_pid() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and lower(query) like ?",holder,"%"+table+"%");
            if(!rows.isEmpty()) { System.out.println("V137 PG_LOCK op="+operation+" source="+source+" point="+point+" holder="+holder+" waiters="+rows);return; }
            if(future.isDone())fail("Request completed before specified PostgreSQL holder wait: "+operation+"/"+point);
            try { Thread.sleep(20); } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new RuntimeException(e);}
        }
        fail("No exact-holder PostgreSQL Lock observed: "+operation+"/"+point);
    }
    void code(Outcome response,int http,int code) {
        var keys=new HashSet<String>();response.body().fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("code","message","data"),keys);
        assertAll(()->assertEquals(http,response.http(),response.body().toString()),()->assertEquals(code,response.code(),response.body().toString()));
    }
    Outcome finish(Future<Outcome> future)throws Exception {
        try {return future.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Unexpected request/SQL/fixture exception",e.getCause());}
    }
    Instant instant(Object raw) {return raw instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)raw).toInstant();}
    Instant clock() {return instant(jdbc.queryForObject("select clock_timestamp()",Object.class));}
    Object stamp(Map<String,Object> row,String key,Instant lower,Instant upper) {
        Object raw=row.get(key);assertNotNull(raw,key);Instant time=instant(raw);
        assertFalse(time.isBefore(lower),key+" before request window");assertFalse(time.isAfter(upper),key+" after request window");return raw;
    }
    Map<String,Object> row(Map<String,List<Map<String,Object>>> data,String key,Long id) {return data.get(key).stream().filter(r->id.equals(((Number)r.get("id")).longValue())).findFirst().orElseThrow();}
    Map<String,Object> added(Map<String,List<Map<String,Object>>> before,Map<String,List<Map<String,Object>>> after,String key) {
        var ids=before.get(key).stream().map(r->r.get("id")).toList();var rows=after.get(key).stream().filter(r->!ids.contains(r.get("id"))).toList();
        assertEquals(1,rows.size(),"One exact new "+key);assertTrue(((Number)rows.getFirst().get("id")).longValue()>0);return rows.getFirst();
    }
    Map<String,Object> emptyLike(Map<String,Object> actual) {var expected=new LinkedHashMap<String,Object>();actual.keySet().forEach(k->expected.put(k,null));return expected;}
    void accepted(Outcome response,Map<String,List<Map<String,Object>>> before,Instant lower) {
        code(response,200,0);var after=facts();var expected=new LinkedHashMap<String,List<Map<String,Object>>>();before.forEach((k,v)->expected.put(k,copyRows(v)));Instant end=clock();
        var s=row(expected,"specimens",specimen);var actual=row(after,"specimens",specimen);Object time=null;
        String node=null,remark=null;
        switch(operation) {
            case "first","second"->{String prefix=operation.equals("first")?"first":"second";time=stamp(actual,prefix+"_signed_at",lower,end);s.put(prefix+"_signed_at",time);s.put(prefix+"_signer_id",actor.id());node=operation.equals("first")?"FIRST_SIGN":"SECOND_SIGN";remark="原核对";assertEquals(actor.id().longValue(),response.data().get(prefix+"SignerId").asLong());}
            case "issue"->{time=stamp(actual,"report_issued_at",lower,end);s.put("report_issued_at",time);node="ISSUE";remark="双签完整";assertTrue(response.data().get("doubleSignComplete").asBoolean());assertTrue(response.data().get("warnings").isEmpty());assertEquals(0,response.data().get("partsPending").asInt());boolean execute=source.equals("OUTP")&&"CHARGED".equals(row(before,"orders",order).get("status"));assertEquals(execute,response.data().get("orderExecuted").asBoolean());if(execute){row(expected,"orders",order).put("status","EXECUTED");row(expected,"orders",order).put("updated_at",time);row(expected,"registrations",registration).put("updated_at",time);}}
            case "supplement"->{var r=added(before,after,"reports");time=stamp(r,"signed_at",lower,end);var n=emptyLike(r);n.put("id",r.get("id"));n.put("specimen_id",specimen);n.put("seq_no",2);n.put("content","新补充正文");n.put("reason","新原因");n.put("signer_id",actor.id());n.put("created_by",actor.id());n.put("signed_at",time);n.put("created_at",time);expected.get("reports").add(n);node="SUPPLEMENT";remark="补充报告 #2";assertEquals(((Number)r.get("id")).longValue(),response.data().get("id").asLong());}
            case "receive"->{time=stamp(actual,"received_at",lower,end);s.put("status","RECEIVED");s.put("received_at",time);assertTrue(response.data().isNull());}
            case "embed"->{var b=row(after,"blocks",block);time=stamp(b,"embedded_at",lower,end);var n=row(expected,"blocks",block);n.put("embedded_at",time);n.put("embedded_by",actor.id());node="EMBED";remark="包埋 "+tag+"b：原核对";assertEquals(actor.id().longValue(),response.data().get("embeddedBy").asLong());}
            case "cancel"->{var t=row(after,"techOrders",techOrder);time=stamp(t,"cancelled_at",lower,end);var n=row(expected,"techOrders",techOrder);n.put("status","CANCELLED");n.put("cancelled_at",time);n.put("cancelled_by",actor.id());n.put("cancel_reason","不再需要");node="TECH_CANCEL";remark="取消特检医嘱 #"+techOrder+" 深切：不再需要";assertEquals("CANCELLED",response.data().get("progress").asText());}
            case "register"->{var r=added(before,after,"specimens");time=stamp(r,"collected_at",lower,end);var n=emptyLike(r);n.put("id",r.get("id"));n.put("order_id",source.equals("OUTP")?order:null);n.put("inp_order_id",source.equals("INP")?inpOrder:null);n.put("part_no",2);n.put("barcode",response.data().get("barcode").asText());assertTrue(n.get("barcode").toString().matches("PB[0-9]+"));n.put("path_no",response.data().get("pathNo").asText());assertTrue(n.get("path_no").toString().matches("[0-9]{4}-C-[0-9]{6}"));n.put("specimen_type","ROUTINE");n.put("specimen_desc","新组织");n.put("status","COLLECTED");n.put("urgent",false);n.put("collected_at",time);expected.get("specimens").add(n);assertEquals(((Number)r.get("id")).longValue(),response.data().get("id").asLong());assertEquals(source,response.data().get("source").asText());}
            default->throw new IllegalArgumentException(operation);
        }
        if(node!=null) {
            var p=added(before,after,"process");var n=emptyLike(p);n.put("id",p.get("id"));n.put("specimen_id",specimen);n.put("node",node);n.put("operator_id",actor.id());n.put("remark",remark);n.put("occurred_at",time);n.put("created_at",time);expected.get("process").add(n);
        }
        expected.values().forEach(rows->{if(!rows.isEmpty()&&rows.getFirst().containsKey("id"))rows.sort(Comparator.comparingLong(r->((Number)r.get("id")).longValue()));});
        assertEquals(expected,after,"Only the allocated original business effects may change: "+operation+"/"+source);
    }
    void race(String op,String origin,String point,String mode,boolean deny)throws Exception {
        prepare(op,origin);
        if(mode.equals("retain"))roles(actor,signer(op)?new String[]{"DOCTOR_OUTP","ADMIN"}:new String[]{"TECHNICIAN","DOCTOR_OUTP"});
        var before=facts();var authBefore=authFacts();var expectedAuth=new AtomicReference<AuthFacts>();var future=new AtomicReference<Future<Outcome>>();var pool=Executors.newSingleThreadExecutor();Instant lower=clock();
        try {
            tx().executeWithoutResult(t->{lock(point);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.set(pool.submit(()->request(actor.token())));waitForHolder(holder,point,future.get());expectedAuth.set(change(mode,authBefore));assertEquals(expectedAuth.get(),authFacts(),"Holder may change only allocated staff fields");if(mode.equals("rollback"))t.setRollbackOnly();});
            assertEquals(mode.equals("rollback")?authBefore:expectedAuth.get(),authFacts(),"Committed or rolled-back staff fact is independent of clinical outcome");
            var response=finish(future.get());
            if(deny) {
                System.out.println("V137 BUSINESS expected=403/1005 actual="+response.http()+"/"+response.code()+" op="+op+" origin="+origin+" point="+point+" mode="+mode);
                assertAll(()->code(response,403,1005),()->assertTrue(response.data().isNull(),"No clinical receipt on lost duty"),()->assertEquals(before,facts(),"Withdrawal must leave all clinical columns unchanged"));
                jdbc.update("update sys_user set enabled=true where id=?",actor.id());roles(actor,signer(op)?"DOCTOR_OUTP":"TECHNICIAN");Instant restored=clock();accepted(request(actor.token()),before,restored);
            } else accepted(response,before,lower);
        } finally {pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS),"All workers finish before fixture cleanup");}
    }

    @ParameterizedTest @CsvSource({
        "first,OUTP,O","first,OUTP,S","first,INP,O","first,INP,S",
        "second,OUTP,O","second,OUTP,S","second,INP,O","second,INP,S",
        "issue,OUTP,O","issue,OUTP,S","issue,INP,O","issue,INP,S",
        "supplement,OUTP,O","supplement,OUTP,S","supplement,INP,O","supplement,INP,S"})
    void clinicalSignaturesRejectDoctorTransferredToTechnicalDutyAfterWaiting(String op,String source,String point)throws Exception {race(op,source,point,"lose",true);}

    @ParameterizedTest @CsvSource({
        "receive,OUTP,O","receive,INP,S","embed,OUTP,O","embed,INP,S","embed,OUTP,B",
        "cancel,OUTP,O","cancel,INP,S","cancel,OUTP,T","register,OUTP,O","register,INP,O"})
    void generalPathologyWritesRejectTechnicalDutyTransferredToQualityAfterWaiting(String op,String source,String point)throws Exception {race(op,source,point,"lose",true);}

    @ParameterizedTest @CsvSource({"first,OUTP,O","second,INP,S","issue,OUTP,S","supplement,INP,O","embed,OUTP,B","register,INP,O"})
    void disabledEmployeeAfterWaitingIsRejectedAndRestorationRemainsUsable(String op,String source,String point)throws Exception {race(op,source,point,"disable",true);}

    @ParameterizedTest @CsvSource({
        "first,OUTP,O,transfer","second,INP,S,retain","issue,OUTP,S,retain","supplement,INP,O,transfer",
        "receive,OUTP,S,transfer","embed,INP,B,retain","cancel,OUTP,T,transfer","register,INP,O,retain",
        "first,INP,S,rollback","issue,OUTP,O,rollback","embed,OUTP,B,rollback","register,INP,O,rollback"})
    void remainingOriginalDutiesTransfersAndRolledBackWithdrawalRemainLegitimate(String op,String source,String point,String mode)throws Exception {race(op,source,point,mode,false);}

    @ParameterizedTest @CsvSource({"first,OUTP,ADMIN","second,INP,ADMIN","issue,OUTP,DOCTOR_OUTP","supplement,INP,DOCTOR_OUTP","receive,OUTP,ADMIN","embed,INP,DOCTOR_OUTP","cancel,OUTP,TECHNICIAN","register,INP,ADMIN"})
    void originalDutiesRetainClinicalAndTechnicalEffects(String op,String source,String role)throws Exception {prepare(op,source);roles(actor,role);var before=facts();Instant lower=clock();accepted(request(actor.token()),before,lower);}

    @ParameterizedTest @CsvSource({"receive,OUTP","embed,OUTP","cancel,OUTP","first,OUTP","supplement,OUTP","issue,OUTP","issue,INP"})
    void physicalHistoryDoesNotGainANewPaymentRestriction(String op,String source)throws Exception {prepare(op,source);jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order);jdbc.update("update outp_charge set status='REFUNDED' where id=?",charge);var before=facts();Instant lower=clock();accepted(request(actor.token()),before,lower);}

    @Test void samePersonStillCannotSecondSignWithoutAnyBusinessWrite()throws Exception {
        prepare("second","OUTP");jdbc.update("update path_specimen set first_signer_id=? where id=?",actor.id(),specimen);var before=facts();var response=request(actor.token());code(response,200,5263);assertTrue(response.data().isNull());assertEquals(before,facts());
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();if(registration==null)return;
        tx().executeWithoutResult(t->{
            String ids="select id from path_specimen where order_id="+order+" or inp_order_id="+inpOrder;
            for(String table:List.of("path_report","path_process","path_gross_field","path_gross_revision"))jdbc.update("delete from "+table+" where specimen_id in("+ids+")");
            jdbc.update("delete from path_slide where block_id in(select id from path_block where specimen_id in("+ids+"))");
            jdbc.update("update path_tech_order set block_id=null where specimen_id in("+ids+")");
            jdbc.update("delete from path_block where specimen_id in("+ids+")");jdbc.update("delete from path_tech_order where specimen_id in("+ids+")");jdbc.update("delete from path_specimen where id in("+ids+")");
            jdbc.update("delete from cdr_document where patient_id=?",patient);jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);
            jdbc.update("delete from inp_order where admission_id=?",admission);jdbc.update("delete from inp_admission where id=?",admission);jdbc.update("delete from inp_bed where id=?",bed);jdbc.update("delete from empi_patient where id=?",patient);
            for(Staff s:staff){jdbc.update("delete from sys_user_role where user_id=?",s.id());jdbc.update("delete from sys_user where id=?",s.id());}jdbc.update("delete from sys_dept where id in (?,?)",dept,otherDept);
        });staff.clear();
    }
}
