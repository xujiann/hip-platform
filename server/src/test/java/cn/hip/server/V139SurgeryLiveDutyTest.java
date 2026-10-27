package cn.hip.server;

import cn.hip.medtech.service.SurgeryService;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Committed historical fixtures are deliberately not a clinical API setup claim.
 * Every HTTP writer uses the real JWT filter chain and its own transaction. A
 * named A/S/child holder must be observed in pg_blocking_pids before it changes
 * current employee facts. There is no outer test transaction hiding rollback. */
@SpringBootTest @AutoConfigureMockMvc
class V139SurgeryLiveDutyTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired SurgeryService service;
    static final String AT="2026-09-04T01:00:00Z";
    static final List<String> OPS=List.of("request","complete","cancel","timepoint","room","medical","tubeAdd","tubeUpdate","tubeDelete","transfusionAdd","transfusionUpdate","transfusionDelete","eventAdd","eventUpdate","eventDelete","anes");
    record Point(String op,String lock) {}
    record Staff(Long id,String name,String token) {}
    record Outcome(int http,JsonNode body) {int code(){return body.path("code").asInt();} JsonNode data(){return body.path("data");}}
    record AuthFacts(List<Map<String,Object>> users,List<Map<String,Object>> roles) {}
    String tag;Long dept,ward,otherDept,patient,otherPatient,bed,otherBed,admission,otherAdmission,surgery,otherSurgery,tube,transfusion,event;
    Staff actor,owner;final List<Staff> employees=new ArrayList<>();

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();tag="V139"+Long.toUnsignedString(System.nanoTime(),36);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        ward=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'NURSING') returning id",Long.class,tag+"W",tag+"病区");
        otherDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"D",tag+"异科");
        actor=employee("actor","TECHNICIAN");owner=employee("owner","DOCTOR_OUTP");
        jdbc.update("update sys_user set dept_id=? where id=?",otherDept,actor.id());
        patient=patient("P1");otherPatient=patient("P2");bed=bed("1");otherBed=bed("2");
        admission=admission(patient,bed,actor.id(),"A1");otherAdmission=admission(otherPatient,otherBed,owner.id(),"A2");
        surgery=surgery(admission,"原手术");otherSurgery=surgery(otherAdmission,"另一患者手术");
        for(Long a:List.of(admission,otherAdmission))jdbc.update("insert into inp_deposit(admission_id,amount,pay_method,operator_id) values (?,23.50,'CASH',?)",a,owner.id());
        for(Long s:List.of(surgery,otherSurgery)) {
            Long t=jdbc.queryForObject("insert into surg_tube(surgery_id,tube_type,position,depth_cm,inserted_at,operator_id,remark) values (?,'尿管','原位置',10.0,?::timestamptz,?,'原备注') returning id",Long.class,s,AT,owner.id());
            Long f=jdbc.queryForObject("insert into surg_transfusion(surgery_id,product_type,volume_ml,is_auto,transfused_at,operator_id) values (?,'RBC',200,false,?::timestamptz,?) returning id",Long.class,s,AT,owner.id());
            Long e=jdbc.queryForObject("insert into surg_event(surgery_id,event_type,event_time,detail,planned,operator_id,remark) values (?,'RESCUE',?::timestamptz,'原明细',true,?,'原备注') returning id",Long.class,s,AT,owner.id());
            jdbc.update("insert into anes_record(surgery_id,phase,hr,note) values (?,'INTRA',70,'原麻醉')",s);
            if(s.equals(surgery)){tube=t;transfusion=f;event=e;}
        }
    }
    Staff employee(String suffix,String duty) {String name=tag+suffix;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",Long.class,name,name,dept);var s=new Staff(id,name,jwt.issue(name));employees.add(s);roles(s,duty);return s;}
    void roles(Staff who,String... duties) {jdbc.update("delete from sys_user_role where user_id=?",who.id());for(String duty:duties)assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",who.id(),duty));}
    Long patient(String suffix){return jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag+suffix,tag+suffix);}
    Long bed(String no){return jdbc.queryForObject("insert into inp_bed(ward_id,bed_no,status) values (?,?,'OCCUPIED') returning id",Long.class,ward,no);}
    Long admission(Long p,Long b,Long doctor,String suffix){return jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status,admit_diag_name) values (?,?,?,?,?,?,'IN_HOSPITAL','原模拟诊断') returning id",Long.class,tag+suffix,p,dept,ward,b,doctor);}
    Long surgery(Long a,String name){return jdbc.queryForObject("insert into inp_surgery(admission_id,procedure_name,anesthesia_type,status,surgeon_id,op_icd) values (?,?,'原麻醉','REQUESTED',?,'47.0') returning id",Long.class,a,name,owner.id());}
    static boolean medical(String op){return Set.of("request","complete","cancel","medical").contains(op);}
    static List<Point> points(){var result=new ArrayList<Point>();for(String op:OPS){result.add(new Point(op,"A"));if(!op.equals("request"))result.add(new Point(op,"S"));if(op.endsWith("Update")||op.endsWith("Delete"))result.add(new Point(op,"C"));}assertEquals(37,result.size());return result;}
    static List<Point> representative(){return List.of(new Point("request","A"),new Point("complete","A"),new Point("complete","S"),new Point("eventUpdate","A"),new Point("eventUpdate","S"),new Point("eventUpdate","C"),new Point("anes","A"),new Point("anes","S"));}
    static Stream<Arguments> withdrawals(){var result=new ArrayList<Arguments>();for(Point point:points()){result.add(Arguments.of(point,"quality"));result.add(Arguments.of(point,"disabled"));if(medical(point.op()))result.add(Arguments.of(point,"technical"));if(point.op().equals("anes"))result.add(Arguments.of(point,"nurse"));}assertEquals(83,result.size());return result.stream();}
    static Stream<Arguments> emptyRoleWithdrawals(){return representative().stream().map(p->Arguments.of(p,"none"));}
    static Stream<Arguments> compatibleChanges(){var result=new ArrayList<Arguments>();for(Point p:representative())for(String mode:List.of("transfer","retain","rollback"))result.add(Arguments.of(p,mode));return result.stream();}
    static Stream<Arguments> currentDoctorScopes(){var result=new ArrayList<Arguments>();for(Point p:representative())for(String scope:List.of("own","unassigned","foreign"))result.add(Arguments.of(p,scope));return result.stream();}
    String endpoint(String op,Long sid,Long child){return switch(op){case "request"->"/api/inpatient/surgeries";case "room","medical"->"/api/inpatient/surgeries/"+sid+"/op-info";case "complete","cancel","timepoint"->"/api/inpatient/surgeries/"+sid+"/"+op;case "anes"->"/api/anes/records";default->"/api/surgery/intraop/"+(op.startsWith("tube")?"tubes":op.startsWith("transfusion")?"transfusions":"events")+(op.endsWith("Add")?"":"/"+child);};}
    Long child(String op){return op.startsWith("tube")?tube:op.startsWith("transfusion")?transfusion:event;}
    Map<String,Object> body(String op,Long sid,Long aid){return switch(op){
        case "request"->Map.of("admissionId",aid,"procedureName","新手术","anesthesiaType","全麻","opIcd","49.0");
        case "complete"->Map.of("opNote","新手术记录","anesNote","新麻醉记录");
        case "cancel"->Map.of("stage","APPLY","reason","新取消原因","at",AT);
        case "timepoint"->Map.of("stage","IN_ROOM","at",AT);
        case "room"->Map.of("roomNo","OR139");
        case "medical"->Map.of("roomNo","OR139","asaGrade","II");
        case "anes"->Map.of("surgeryId",sid,"phase","PACU","hr",88,"sbp",120,"dbp",80,"spo2",98,"stewardScore",5,"note","新麻醉");
        default->{var m=new LinkedHashMap<String,Object>();m.put("surgeryId",op.endsWith("Add")?sid:otherSurgery);if(op.startsWith("tube")){m.put("tubeType","尿管");m.put("position","新位置");m.put("depthCm",new BigDecimal("12.3"));m.put("insertedAt",AT);m.put("remark","新备注");}
            else if(op.startsWith("transfusion")){m.put("productType",op.endsWith("Add")?"AUTO":"RBC");m.put("volumeMl",op.endsWith("Add")?450:350);m.put("isAuto",op.endsWith("Update"));m.put("transfusedAt",AT);}
            else {m.put("eventType",op.endsWith("Add")?"TO_ICU":"RESCUE");m.put("eventTime",AT);m.put("detail","新明细");m.put("planned",op.endsWith("Add")?false:null);m.put("remark","新备注");}yield m;}
    };}
    Outcome call(String method,String target,Object body,String token)throws Exception {SecurityContextHolder.clearContext();try{MockHttpServletRequestBuilder req=switch(method){case "POST"->post(target);case "PUT"->put(target);case "DELETE"->delete(target);default->get(target);};if(body!=null)req.contentType("application/json").content(mapper.writeValueAsString(body));if(token!=null)req.header("Authorization","Bearer "+token);var r=mvc.perform(req).andReturn().getResponse();return new Outcome(r.getStatus(),mapper.readTree(r.getContentAsString(StandardCharsets.UTF_8)));}finally{SecurityContextHolder.clearContext();}}
    Outcome request(String op)throws Exception{return request(op,surgery,admission,child(op),actor.token(),null);}
    Outcome request(String op,Long sid,Long aid,Long child,String token,Map<String,Object> override)throws Exception {String method=op.endsWith("Delete")?"DELETE":op.endsWith("Add")||Set.of("request","anes").contains(op)?"POST":"PUT";return call(method,endpoint(op,sid,child),method.equals("DELETE")?null:override==null?body(op,sid,aid):override,token);}
    Map<String,List<Map<String,Object>>> facts(){var result=new LinkedHashMap<String,List<Map<String,Object>>>();for(var e:new String[][]{
        {"admissions","inp_admission","id in("+admission+","+otherAdmission+")"},{"surgeries","inp_surgery","admission_id in("+admission+","+otherAdmission+")"},
        {"tubes","surg_tube","surgery_id in("+surgery+","+otherSurgery+")"},{"transfusions","surg_transfusion","surgery_id in("+surgery+","+otherSurgery+")"},
        {"events","surg_event","surgery_id in("+surgery+","+otherSurgery+")"},{"anes","anes_record","surgery_id in("+surgery+","+otherSurgery+")"},
        {"patients","empi_patient","id in("+patient+","+otherPatient+")"},{"beds","inp_bed","id in("+bed+","+otherBed+")"},
        {"deposits","inp_deposit","admission_id in("+admission+","+otherAdmission+")"},{"settlements","inp_settlement","admission_id in("+admission+","+otherAdmission+")"},
        {"orders","inp_order","admission_id in("+admission+","+otherAdmission+")"},{"consents","emr_consent","admission_id in("+admission+","+otherAdmission+")"},
        {"blood","blood_apply","admission_id in("+admission+","+otherAdmission+")"},{"cdr","cdr_document","patient_id in("+patient+","+otherPatient+")"},
        {"stock","pharm_stock","true"},{"stockFlows","pharm_stock_flow","true"},{"batches","pharm_batch","true"}})result.put(e[0],jdbc.queryForList("select * from "+e[1]+" where "+e[2]+" order by id"));result.put("config",jdbc.queryForList("select * from sys_config order by cfg_key"));return result;}
    AuthFacts authFacts(){return new AuthFacts(jdbc.queryForList("select * from sys_user where username like ? order by id",tag+"%"),jdbc.queryForList("select ur.* from sys_user_role ur join sys_user u on u.id=ur.user_id where u.username like ? order by ur.user_id,ur.role_id",tag+"%"));}
    List<Map<String,Object>> copyRows(List<Map<String,Object>> rows){var result=new ArrayList<Map<String,Object>>();rows.forEach(row->result.add(new LinkedHashMap<>(row)));return result;}
    Map<String,List<Map<String,Object>>> copy(Map<String,List<Map<String,Object>>> before){var result=new LinkedHashMap<String,List<Map<String,Object>>>();before.forEach((k,v)->result.put(k,copyRows(v)));return result;}
    Map<String,Object> row(Map<String,List<Map<String,Object>>> facts,String table,Long id){return facts.get(table).stream().filter(r->id.equals(((Number)r.get("id")).longValue())).findFirst().orElseThrow();}
    Instant instant(Object value){if(value instanceof Timestamp t)return t.toInstant();if(value instanceof java.time.OffsetDateTime t)return t.toInstant();return (Instant)value;}
    Instant clock(){return instant(jdbc.queryForObject("select clock_timestamp()",Object.class));}
    Object bounded(Map<String,Object> actual,String key,Instant lower,Instant upper){Object value=actual.get(key);assertNotNull(value,key);assertFalse(instant(value).isBefore(lower),key);assertFalse(instant(value).isAfter(upper),key);return value;}
    void code(Outcome response,int status,int code){var keys=new HashSet<String>();response.body().fieldNames().forEachRemaining(keys::add);assertEquals(Set.of("code","message","data"),keys);assertAll(()->assertEquals(status,response.http(),response.body().toString()),()->assertEquals(code,response.code(),response.body().toString()));}
    Set<String> keys(JsonNode json){var result=new HashSet<String>();json.fieldNames().forEachRemaining(result::add);return result;}
    Map<String,Object> allocateNew(Map<String,List<Map<String,Object>>> expected,Map<String,List<Map<String,Object>>> actual,String table){var ids=expected.get(table).stream().map(r->r.get("id")).toList();var additions=actual.get(table).stream().filter(r->!ids.contains(r.get("id"))).toList();assertEquals(1,additions.size());var actualRow=additions.getFirst();assertTrue(((Number)actualRow.get("id")).longValue()>0);var allocated=new LinkedHashMap<String,Object>();actualRow.keySet().forEach(k->allocated.put(k,null));allocated.put("id",actualRow.get("id"));expected.get(table).add(allocated);return allocated;}
    void accepted(String op,Outcome response,Map<String,List<Map<String,Object>>> before,Instant lower){code(response,200,0);var actual=facts();var expected=copy(before);Instant upper=clock();Map<String,Object> target;
        if(op.equals("request")){assertEquals(Set.of("warning"),keys(response.data()));assertEquals("未查到有效手术知情同意书，请及时补录并医患双签",response.data().get("warning").asText());target=allocateNew(expected,actual,"surgeries");target.putAll(Map.of("admission_id",admission,"procedure_name","新手术","anesthesia_type","全麻","status","REQUESTED","surgeon_id",actor.id(),"op_icd","49.0","is_unplanned_reop",false));target.put("created_at",bounded(row(actual,"surgeries",((Number)target.get("id")).longValue()),"created_at",lower,upper));}
        else if(Set.of("complete","cancel","timepoint","room","medical").contains(op)){target=row(expected,"surgeries",surgery);switch(op){case "complete"->target.putAll(Map.of("status","DONE","op_note","新手术记录","anes_note","新麻醉记录"));case "cancel"->target.putAll(Map.of("status","CANCELLED","cancel_stage","APPLY","cancel_reason","新取消原因","cancelled_at",Timestamp.from(Instant.parse(AT))));case "timepoint"->{target.put("in_room_at",Timestamp.from(Instant.parse(AT)));assertEquals(mapper.valueToTree(Map.of("stage","IN_ROOM","stageName","入室","at",AT,"phase","IN_ROOM")),response.data());}default->{target.put("room_no","OR139");if(op.equals("medical"))target.put("asa_grade","II");}}if(!op.equals("timepoint"))assertTrue(response.data().isNull());}
        else if(op.endsWith("Delete")){String table=op.startsWith("tube")?"tubes":op.startsWith("transfusion")?"transfusions":"events";assertTrue(expected.get(table).removeIf(r->child(op).equals(r.get("id"))));assertTrue(response.data().isNull());}
        else {String table=op.equals("anes")?"anes":op.startsWith("tube")?"tubes":op.startsWith("transfusion")?"transfusions":"events";boolean added=op.endsWith("Add")||op.equals("anes");target=added?allocateNew(expected,actual,table):row(expected,table,child(op));if(added){target.put("surgery_id",surgery);if(!op.equals("anes"))target.put("operator_id",actor.id());String stamp=op.equals("anes")?"recorded_at":"created_at";target.put(stamp,bounded(row(actual,table,((Number)target.get("id")).longValue()),stamp,lower,upper));if(!op.equals("anes")){assertEquals(Set.of("id"),keys(response.data()));assertEquals(((Number)target.get("id")).longValue(),response.data().get("id").asLong());}}else assertTrue(response.data().isNull());
            if(op.startsWith("tube")){target.putAll(Map.of("tube_type","尿管","position","新位置","depth_cm",new BigDecimal("12.3"),"inserted_at",Timestamp.from(Instant.parse(AT)),"remark","新备注"));target.put("removed_at",null);}
            else if(op.startsWith("transfusion"))target.putAll(Map.of("product_type",added?"AUTO":"RBC","volume_ml",added?450:350,"is_auto",true,"transfused_at",Timestamp.from(Instant.parse(AT))));
            else if(op.startsWith("event")){target.putAll(Map.of("event_type",added?"TO_ICU":"RESCUE","event_time",Timestamp.from(Instant.parse(AT)),"detail","新明细","remark","新备注"));target.put("planned",added?false:null);}
            else {target.putAll(Map.of("phase","PACU","hr",88,"sbp",120,"dbp",80,"spo2",98,"steward_score",5,"note","新麻醉"));assertTrue(response.data().isNull());}}
        expected.values().forEach(rows->{if(!rows.isEmpty()&&rows.getFirst().containsKey("id"))rows.sort(Comparator.comparingLong(r->((Number)r.get("id")).longValue()));});assertEquals(expected,actual,"Only exactly allocated surgery/child/anes fields may change; both patients, admission/billing/config and unrelated raw columns freeze");}
    AuthFacts allocatedRoles(AuthFacts before,String... duties){var roles=copyRows(before.roles());roles.removeIf(r->actor.id().equals(r.get("user_id")));for(String duty:duties)roles.add(new LinkedHashMap<>(Map.of("user_id",actor.id(),"role_id",jdbc.queryForObject("select id from sys_role where code=?",Long.class,duty))));roles.sort(Comparator.comparingLong((Map<String,Object> r)->((Number)r.get("user_id")).longValue()).thenComparingLong(r->((Number)r.get("role_id")).longValue()));return new AuthFacts(before.users(),roles);}
    AuthFacts change(String mode,AuthFacts before,String targetRole){if(mode.equals("disabled")){var users=copyRows(before.users());users.stream().filter(r->actor.id().equals(r.get("id"))).findFirst().orElseThrow().put("enabled",false);assertEquals(1,jdbc.update("update sys_user set enabled=false where id=?",actor.id()));return new AuthFacts(users,before.roles());}String[] roles=switch(mode){case "quality","rollback"->new String[]{"QUALITY"};case "technical"->new String[]{"TECHNICIAN"};case "nurse"->new String[]{"NURSE"};case "none"->new String[0];case "transfer","retain","scope"->new String[]{targetRole};default->throw new IllegalArgumentException(mode);};roles(actor,roles);return allocatedRoles(before,roles);}
    TransactionTemplate tx(){var t=new TransactionTemplate(manager);t.setTimeout(30);return t;}
    String table(Point point){return point.lock().equals("A")?"inp_admission":point.lock().equals("S")?"inp_surgery":point.op().startsWith("tube")?"surg_tube":point.op().startsWith("transfusion")?"surg_transfusion":"surg_event";}
    void lock(Point p){Long id=p.lock().equals("A")?admission:p.lock().equals("S")?surgery:child(p.op());assertEquals(1,jdbc.queryForList("select id from "+table(p)+" where id=? for update",id).size());}
    void waitForHolder(int holder,Point p,Future<?> worker){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);while(System.nanoTime()<end){jdbc.execute("select pg_stat_clear_snapshot()");var rows=jdbc.queryForList("select pid,wait_event_type,pg_blocking_pids(pid) as blockers,query from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and lower(query) like ? and lower(query) like '%for update%'",holder,"%"+table(p)+"%");if(!rows.isEmpty()){System.out.println("V139 PG_LOCK op="+p.op()+" point="+p.lock()+" holder="+holder+" waiters="+rows);return;}if(worker.isDone())fail("Request finished before named PostgreSQL holder wait: "+p);try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}fail("No named-holder PostgreSQL Lock: "+p);}
    Outcome finish(Future<Outcome> worker)throws Exception{try{return worker.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Unexpected request/SQL/fixture error",e.getCause());}}
    void race(Point point,String mode,boolean denial,int expectedCode)throws Exception{String from=medical(point.op())?"DOCTOR_OUTP":"TECHNICIAN",to=medical(point.op())?"ADMIN":point.op().equals("anes")?"DOCTOR_OUTP":"NURSE";if(mode.equals("scope")){from="ADMIN";to="DOCTOR_OUTP";}roles(actor,mode.equals("retain")?new String[]{from,to}:new String[]{from});var before=facts();var authBefore=authFacts();var changed=new AtomicReference<AuthFacts>();var future=new AtomicReference<Future<Outcome>>();var pool=Executors.newSingleThreadExecutor();Instant lower=clock();String restore=from;String target=to;
        try {tx().executeWithoutResult(t->{lock(point);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.set(pool.submit(()->request(point.op())));waitForHolder(holder,point,future.get());changed.set(change(mode,authBefore,target));assertEquals(changed.get(),authFacts(),"Holder changes only exact current employee rows");if(mode.equals("rollback"))t.setRollbackOnly();});assertEquals(mode.equals("rollback")?authBefore:changed.get(),authFacts());Outcome response=finish(future.get());
            if(denial){System.out.println("V139 BUSINESS expected="+(expectedCode==1005?"403/":"200/")+expectedCode+" actual="+response.http()+"/"+response.code()+" op="+point.op()+" point="+point.lock()+" mode="+mode);assertAll(()->code(response,expectedCode==1005?403:200,expectedCode),()->assertTrue(response.data().isNull()),()->assertEquals(before,facts(),"Rejected current employee has no clinical/financial side effects"));assertEquals(1,jdbc.update("update sys_user set enabled=true where id=?",actor.id()));roles(actor,restore);assertEquals(authBefore,authFacts());Instant restored=clock();accepted(point.op(),request(point.op()),before,restored);assertEquals(authBefore,authFacts());}
            else {accepted(point.op(),response,before,lower);assertEquals(mode.equals("rollback")?authBefore:changed.get(),authFacts());}
        }finally{pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS),"Writer terminates before fixture cleanup");}}

    @ParameterizedTest @MethodSource("withdrawals") void allOriginalWriteWaitsRejectWithdrawnDutiesOrDisabledEmployeeAndRecoverOnlyAfterKnownRestoration(Point point,String mode)throws Exception{race(point,mode,true,1005);}
    @ParameterizedTest @MethodSource("emptyRoleWithdrawals") void lossOfAllRolesAtRepresentativeAdmissionSurgeryAndChildWaitsIsDenied(Point point,String mode)throws Exception{race(point,mode,true,1005);}
    @ParameterizedTest @MethodSource("compatibleChanges") void originalModeDutyTransfersRemainingMultiRoleAndRolledBackWithdrawalRemainUsable(Point point,String mode)throws Exception{race(point,mode,false,0);}
    @ParameterizedTest @MethodSource("currentDoctorScopes") void conversionToCurrentDoctorKeepsOriginalOwnUnassignedAndForeignOwnerPolicies(Point point,String scope)throws Exception{jdbc.update("update inp_admission set doctor_id=? where id=?",scope.equals("unassigned")?null:scope.equals("foreign")?owner.id():actor.id(),admission);race(point,"scope",scope.equals("foreign"),scope.equals("foreign")?9018:0);}
    @ParameterizedTest @CsvSource({"request,ADMIN","request,DOCTOR_OUTP","eventAdd,ADMIN","eventAdd,DOCTOR_OUTP","eventAdd,NURSE","eventAdd,TECHNICIAN","anes,ADMIN","anes,DOCTOR_OUTP","anes,TECHNICIAN"}) void originalTwoFourAndThreeDutiesKeepLawfulCrossDepartmentWrites(String op,String role)throws Exception{roles(actor,role);var before=facts();var auth=authFacts();Instant lower=clock();accepted(op,request(op),before,lower);assertEquals(auth,authFacts());}
    @ParameterizedTest @ValueSource(strings={"QUALITY","CASHIER","INTERFACE","anonymous","disabled"}) void initialWrongDutyAnonymousOrDisabledRequestsKeepOriginalTransportDenials(String kind)throws Exception{String token=actor.token();int status=403,code=1005;if(kind.equals("anonymous")){token=null;status=401;code=1000;}else if(kind.equals("disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());status=401;code=1000;}else roles(actor,kind);var before=facts();var auth=authFacts();for(String op:List.of("request","eventAdd","anes")){Outcome response=request(op,surgery,admission,child(op),token,null);code(response,status,code);assertTrue(response.data().isNull());assertEquals(before,facts());assertEquals(auth,authFacts());}}
    @ParameterizedTest @CsvSource({"request,NURSE","medical,TECHNICIAN","anes,NURSE"}) void narrowerMedicalAndAnesthesiaDutiesDoNotAcquireGeneralOperationalGrants(String op,String role)throws Exception{roles(actor,role);var before=facts();Outcome response=request(op);code(response,403,1005);assertTrue(response.data().isNull());assertEquals(before,facts());}
    @Test void originalDoctorCannotWriteForeignOwnerThroughAnyOriginalEntryOrForgedChildBody()throws Exception{roles(actor,"DOCTOR_OUTP");jdbc.update("update inp_admission set doctor_id=? where id=?",owner.id(),admission);var before=facts();for(String op:OPS){Outcome response=request(op);code(response,200,9018);assertTrue(response.data().isNull());assertEquals(before,facts());}}
    @ParameterizedTest @CsvSource({"request,9003","complete,9946","timepoint,4900","eventAdd,4926","eventDelete,4927","anes,4671"}) void originalMissingObjectCodesRemainUnchanged(String op,int expected)throws Exception{roles(actor,"ADMIN");var before=facts();Long missing=9000000000000000L;Outcome response=request(op,missing,missing,missing,actor.token(),null);code(response,200,expected);assertTrue(response.data().isNull());assertEquals(before,facts());}
    static Stream<Arguments> invalidBodies(){return Stream.of(Arguments.of("timepoint",Map.of("stage","BAD"),4908),Arguments.of("timepoint",Map.of("stage","IN_ROOM","at","BAD"),4909),Arguments.of("room",Map.of("roomNo"," "),4901),Arguments.of("medical",Map.of("asaGrade","BAD"),4904),Arguments.of("cancel",Map.of("stage","APPLY","reason"," "),4907),Arguments.of("cancel",Map.of("stage","APPLY","reason","x","at","2099-01-01T00:00:00Z"),4911),Arguments.of("tubeAdd",Map.of("tubeType","BAD"),4920),Arguments.of("tubeUpdate",Map.of("tubeType","尿管","insertedAt",AT,"removedAt","2026-09-03T00:00:00Z"),4921),Arguments.of("transfusionAdd",Map.of("productType","RBC","volumeMl",0),4923),Arguments.of("eventAdd",Map.of("eventType","BAD"),4924),Arguments.of("eventUpdate",Map.of("eventType","RESCUE","eventTime","BAD"),4928),Arguments.of("anes",Map.of("phase","BAD"),4670),Arguments.of("anes",Map.of("phase","PACU","stewardScore",9),4672));}
    @ParameterizedTest @MethodSource("invalidBodies") void originalFieldBoundaryBusinessErrorsHaveNoPartialWrites(String op,Map<String,Object> invalid,int expected)throws Exception{roles(actor,"ADMIN");var body=new LinkedHashMap<>(body(op,surgery,admission));body.putAll(invalid);var before=facts();Outcome response=request(op,surgery,admission,child(op),actor.token(),body);code(response,200,expected);assertTrue(response.data().isNull());assertEquals(before,facts());}
    @Test void originalDoctorReadScopeRemainsOwnAndUnassignedButNotForeign()throws Exception{roles(actor,"DOCTOR_OUTP");var before=facts();code(call("GET","/api/inpatient/surgeries/"+surgery+"/detail",null,actor.token()),200,0);code(call("GET","/api/inpatient/surgeries/"+otherSurgery+"/detail",null,actor.token()),403,1005);assertEquals(before,facts());jdbc.update("update inp_admission set doctor_id=null where id=?",admission);before=facts();code(call("GET","/api/inpatient/surgeries/"+surgery+"/detail",null,actor.token()),200,0);assertEquals(before,facts());}
    @Test void trustedNullAuthenticationMedicalServiceCallerKeepsExistingBehavior(){SecurityContextHolder.clearContext();var before=facts();Instant lower=clock();var response=service.updateOpInfo(surgery,new SurgeryService.OpInfoReq("OR139",null,"II",null,null,null));assertEquals(0,response.getCode());accepted("medical",new Outcome(200,mapper.valueToTree(response)),before,lower);}
    @Test void trustedNullAuthenticationOperationalServiceCallerKeepsExistingBehavior(){SecurityContextHolder.clearContext();var before=facts();Instant lower=clock();var response=service.registerTimepoint(surgery,new SurgeryService.TimepointReq("IN_ROOM",AT));assertEquals(0,response.getCode());accepted("timepoint",new Outcome(200,mapper.valueToTree(response)),before,lower);}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(admission==null)return;tx().executeWithoutResult(t->{for(String table:List.of("surg_tube","surg_transfusion","surg_event","anes_record"))jdbc.update("delete from "+table+" where surgery_id in (select id from inp_surgery where admission_id in (?,?))",admission,otherAdmission);jdbc.update("delete from inp_surgery where admission_id in (?,?)",admission,otherAdmission);jdbc.update("delete from inp_deposit where admission_id in (?,?)",admission,otherAdmission);jdbc.update("delete from inp_admission where id in (?,?)",admission,otherAdmission);jdbc.update("delete from inp_bed where ward_id=?",ward);jdbc.update("delete from empi_patient where id in (?,?)",patient,otherPatient);for(Staff s:employees){jdbc.update("delete from sys_user_role where user_id=?",s.id());jdbc.update("delete from sys_user where id=?",s.id());}jdbc.update("delete from sys_dept where id in (?,?,?)",dept,ward,otherDept);});employees.clear();}
}
