package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real JWT through original controller paths. No outer test transaction.
 * A test-only JDBC boundary pauses the actual payload statement, never its
 * result. Public role/attending changes commit before a public owner publishes
 * NEW clinical text. BEFORE and AFTER statement snapshots are distinct cases.
 * No PostgreSQL lock-wait or arbitrary-time revocation guarantee is claimed. */
@SpringBootTest @AutoConfigureMockMvc
@Import(V152EmrHistoryAccessTest.JdbcConfiguration.class)
class V152EmrHistoryAccessTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired EmrTemplateService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    static final String ROOT = "/api/emr-templates/";
    static final List<String> ROUTES = List.of("list", "contentOUTP", "contentINP", "copyOUTP", "copyINP");
    static final Set<String> LIST_KEYS = Set.of("source","record_id","record_type","title","created_at","visit_no","dept_name","doctor_name","content_length");
    static final Set<String> CONTENT_KEYS = Set.of("source","recordId","recordType","title","content","createdAt");
    static final Set<String> COPY_KEYS = Set.of("id","scope","recordType","contentLength","sourceLength","truncated","maxContent");
    static final List<String> TABLES = List.of("empi_patient","outp_schedule","outp_registration","outp_emr","outp_diagnosis","outp_diagnosis_favorite","outp_diagnosis_version","emr_version","inp_admission","inp_medical_record","outp_order","outp_charge","inp_order","inp_order_exec","inp_deposit","inp_settlement","emr_template","emr_template_field","emr_template_grant","cdr_document","emr_consent","fin_print_log","lis_sample","ris_exam","path_specimen");
    record Staff(Long id,String name,String token) {}
    record Reply(int http,JsonNode body,String path) { int code(){return body.path("code").asInt();} }
    record Wanted(int http,int code,JsonNode data) {}
    String tag, oldNonce, newNonce, route="list", sourceOverride, limit;
    Long dept, bed, patient, otherPatient, registration, emr, admission, record, qualification;
    Staff actor, owner, admin;
    final List<Staff> people = new ArrayList<>();
    final List<Long> schedules = new ArrayList<>(), registrations = new ArrayList<>(), ownTemplates = new ArrayList<>();
    final List<Map<String,Object>> maintenance = new ArrayList<>();
    int artifact;

    @BeforeEach void setup(){
        SecurityContextHolder.clearContext(); tag="V152"+Long.toUnsignedString(System.nanoTime(),36);
        oldNonce=tag+"_OLD"; newNonce=tag+"_NEW_AFTER_CHANGE";
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        actor=employee("actor","DOCTOR_OUTP"); owner=employee("owner","DOCTOR_OUTP"); admin=employee("admin","ADMIN");
        patient=newPatient("patient"); otherPatient=newPatient("other");
        registration=registration(BusinessDates.today().minusDays(4),owner.id(),"VISITED");
        emr=jdbc.queryForObject("insert into outp_emr(registration_id,doctor_id,chief_complaint,present_illness,past_history,physical_exam,advice) values (?,?,?,'现病史',null,'','处理') returning id",Long.class,registration,owner.id(),oldNonce);
        bed=jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",Long.class,dept,tag);
        admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id,status) values (?,?,?,?,?,?,'DISCHARGED') returning id",Long.class,tag,patient,dept,dept,bed,owner.id());
        record=jdbc.queryForObject("insert into inp_medical_record(admission_id,record_type,title,content,doctor_id) values (?,'PROGRESS',?,?,?) returning id",Long.class,admission,tag+"标题",oldNonce+"住院正文",owner.id());
    }
    Long newPatient(String suffix){return jdbc.queryForObject("insert into empi_patient(name,sex) values (?,'U') returning id",Long.class,tag+suffix);}
    Long registration(LocalDate date,Long doctor,String status){
        Long schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,doctor_id,schedule_date,capacity,booked) values (?,?,?,10,1) returning id",Long.class,dept,doctor,date); schedules.add(schedule);
        Long id=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,status) values (1,?,?,?,?,?,?) returning id",Long.class,patient,schedule,dept,doctor,date,status); registrations.add(id);return id;
    }
    Staff employee(String suffix,String role){String name=tag+suffix;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",Long.class,name,name,dept);Staff who=new Staff(id,name,jwt.issue(name));people.add(who);roles(who,role);return who;}
    void roles(Staff who,String... duties){jdbc.update("delete from sys_user_role where user_id=?",who.id());for(String role:duties)if(!role.isBlank())assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",who.id(),role));}
    void relation(String kind){
        switch(kind){
            case "ownedOutp" -> qualification=registration(BusinessDates.today().minusDays(3),actor.id(),"REGISTERED");
            case "today" -> qualification=registration(BusinessDates.today(),null,"REGISTERED");
            case "outpAuthor" -> jdbc.update("update outp_emr set doctor_id=? where id=?",actor.id(),emr);
            case "futureOwned" -> qualification=registration(BusinessDates.today().plusDays(3),actor.id(),"REGISTERED");
            case "ownedInp" -> jdbc.update("update inp_admission set doctor_id=? where id=?",actor.id(),admission);
            case "nullInp" -> jdbc.update("update inp_admission set doctor_id=null where id=?",admission);
            case "inpAuthorOnly" -> jdbc.update("update inp_medical_record set doctor_id=? where id=?",actor.id(),record);
            case "nullOutp" -> jdbc.update("update outp_registration set doctor_id=null where id=?",registration);
            case "cancelledOwned" -> qualification=registration(BusinessDates.today(),actor.id(),"CANCELLED");
            case "cancelledPayload" -> {jdbc.update("update outp_registration set status='CANCELLED' where id=?",registration);relation("ownedInp");}
            case "none" -> {}
            default -> throw new AssertionError(kind);
        }
    }
    String source(){return sourceOverride!=null?sourceOverride:route.endsWith("OUTP")?"OUTP":"INP";}
    Long id(){return route.endsWith("OUTP")?emr:record;}
    String endpoint(){return route.equals("list")?ROOT+"prior-records?patientId="+patient+(limit==null?"":"&limit="+limit):route.startsWith("copy")?ROOT+"from-record":ROOT+"prior-records/content?patientId="+patient+"&source="+URLEncoder.encode(source(),StandardCharsets.UTF_8)+"&recordId="+id();}
    Reply read()throws Exception{return read(actor.token());}
    Reply read(String token)throws Exception{var body=new LinkedHashMap<String,Object>();body.put("source",source());body.put("recordId",id());body.put("patientId",patient);body.put("name",tag+"copy");body.put("scope","PERSONAL");var reply=call(route.startsWith("copy")?"POST":"GET",endpoint(),route.startsWith("copy")?body:null,token);if(route.startsWith("copy")&&reply.code()==0)ownTemplates.add(reply.body().path("data").path("id").asLong());return reply;}
    Wanted expected(){
        var current=jdbc.queryForList("select enabled from sys_user where id=?",actor.id());var duties=jdbc.queryForList("select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?",String.class,actor.id());
        if(current.isEmpty()||!Boolean.TRUE.equals(current.getFirst().get("enabled"))||duties.stream().noneMatch(Set.of("ADMIN","QUALITY","DOCTOR_OUTP","NURSE")::contains))return new Wanted(403,1005,null);
        boolean relationship=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from outp_registration r where r.patient_id=? and r.status<>'CANCELLED' and (r.visit_date=? or r.doctor_id=? or exists(select 1 from outp_emr e where e.registration_id=r.id and e.doctor_id=?))) or exists(select 1 from inp_admission a where a.patient_id=? and a.doctor_id=?)",Boolean.class,patient,BusinessDates.today(),actor.id(),actor.id(),patient,actor.id()));
        boolean outp=duties.contains("ADMIN")||duties.contains("QUALITY")||duties.contains("DOCTOR_OUTP")&&relationship;
        boolean inp=outp||duties.contains("NURSE");
        if(route.equals("list")){if(!inp&&!outp)return new Wanted(200,4036,null);return new Wanted(200,0,wire(rawList(outp,inp)));}
        if(!(route.endsWith("OUTP")?outp:inp))return new Wanted(200,4036,null);
        var rows=jdbc.queryForList(route.endsWith("OUTP")?"select e.id,'OUTP' as record_type,'门诊病历' as title,concat_ws(chr(10),case when coalesce(e.chief_complaint,'')='' then null else '主诉：'||e.chief_complaint end,case when coalesce(e.present_illness,'')='' then null else '现病史：'||e.present_illness end,case when coalesce(e.past_history,'')='' then null else '既往史：'||e.past_history end,case when coalesce(e.physical_exam,'')='' then null else '体格检查：'||e.physical_exam end,case when coalesce(e.advice,'')='' then null else '处理意见：'||e.advice end) as content,e.updated_at as created_at from outp_emr e join outp_registration g on g.id=e.registration_id where e.id=? and g.patient_id=?":"select r.id,r.record_type,r.title,r.content,r.created_at from inp_medical_record r join inp_admission a on a.id=r.admission_id where r.id=? and a.patient_id=?",id(),patient);
        if(rows.isEmpty())return new Wanted(200,4068,null);var r=rows.getFirst();var data=new LinkedHashMap<String,Object>();data.put("source",route.endsWith("OUTP")?"OUTP":"INP");data.put("recordId",r.get("id"));data.put("recordType",r.get("record_type"));data.put("title",r.get("title"));data.put("content",r.get("content"));data.put("createdAt",r.get("created_at"));return new Wanted(200,0,wire(data));
    }
    List<Map<String,Object>> rawList(boolean outp,boolean inp){int n=limit==null?50:Integer.parseInt(limit);if(n<1||n>200)n=50;return jdbc.queryForList("select 'INP' as source,r.id as record_id,r.record_type,r.title,r.created_at,a.admission_no as visit_no,d.name as dept_name,u.real_name as doctor_name,length(r.content) as content_length from inp_medical_record r join inp_admission a on a.id=r.admission_id left join sys_dept d on d.id=a.dept_id left join sys_user u on u.id=r.doctor_id where a.patient_id=? and cast(? as boolean) union all select 'OUTP',e.id,'OUTP','门诊病历',e.updated_at,cast(g.reg_no as varchar),d2.name,u2.real_name,coalesce(length(e.chief_complaint),0)+coalesce(length(e.present_illness),0)+coalesce(length(e.past_history),0)+coalesce(length(e.physical_exam),0)+coalesce(length(e.advice),0) from outp_emr e join outp_registration g on g.id=e.registration_id left join sys_dept d2 on d2.id=g.dept_id left join sys_user u2 on u2.id=e.doctor_id where g.patient_id=? and cast(? as boolean) order by created_at desc limit ?",patient,inp,patient,outp,n);}
    static Stream<Arguments> matrix(){return Stream.of("ADMIN","QUALITY","DOCTOR_OUTP","NURSE","TECHNICIAN","DOCTOR_OUTP+NURSE","DOCTOR_OUTP+TECHNICIAN","NURSE+TECHNICIAN","QUALITY+TECHNICIAN").flatMap(role->Stream.of("none","ownedOutp","ownedInp").flatMap(relation->ROUTES.stream().map(route->Arguments.of(role,relation,route))));}
    @ParameterizedTest @MethodSource("matrix") void realJwtRoleUnionPatientAndSourceMatrix(String duties,String relation,String entry)throws Exception{roles(actor,duties.split("\\+"));relation(relation);route=entry;check("matrix-"+duties+"-"+relation+"-"+entry);}
    static Stream<Arguments> relations(){return Stream.of("today","outpAuthor","futureOwned","nullInp","inpAuthorOnly","nullOutp","cancelledOwned","cancelledPayload").flatMap(kind->ROUTES.stream().map(route->Arguments.of(kind,route)));}
    @ParameterizedTest @MethodSource("relations") void originalPatientRelationRulesAndHistoricalPayloadAreDistinct(String kind,String entry)throws Exception{relation(kind);route=entry;check("relation-"+kind+"-"+entry);}
    @ParameterizedTest @ValueSource(strings={"omitted","1","0","-1","201","200"}) void listLimitKeepsOriginalNormalizationAndNineKeys(String value)throws Exception{roles(actor,"ADMIN");limit=value.equals("omitted")?null:value;check("limit-"+value);}
    @ParameterizedTest @ValueSource(strings={"ADMIN","QUALITY","NURSE"}) void aLegallyVisiblePatientWithoutAnyHistoricalRowsIsAnEmptyArray(String role)throws Exception{roles(actor,role);jdbc.update("delete from outp_emr where id=?",emr);jdbc.update("delete from inp_medical_record where id=?",record);check("empty-"+role);}
    @ParameterizedTest @ValueSource(strings={"contentOUTP","contentINP","copyOUTP","copyINP"}) void sourceCaseAndWhitespaceKeepTheOriginalSuccessfulContract(String entry)throws Exception{roles(actor,"ADMIN");route=entry;sourceOverride=" "+(route.endsWith("OUTP")?"outp":"inp")+" ";check("source-case-"+entry);}
    @ParameterizedTest @ValueSource(strings={"contentOUTP","contentINP","copyOUTP","copyINP"}) void authorizedWrongPatientAndMissingRecordKeep4068(String entry)throws Exception{roles(actor,"ADMIN");route=entry;patient=otherPatient;check("wrong-patient-"+entry);}
    @ParameterizedTest @ValueSource(strings={"contentOUTP","contentINP","copyOUTP","copyINP"}) void unauthorizedValidSourceDoesNotProbeTheRecordExistence(String entry)throws Exception{route=entry;emr=89999999999991L;record=89999999999992L;check("hidden-missing-"+entry);}
    @Test void successfulCopyKeepsExplicit4000TruncationAndAutomaticPersonalGrant()throws Exception{roles(actor,"QUALITY");route="copyINP";jdbc.update("update inp_medical_record set content=? where id=?","模拟超长病历".repeat(1000),record);check("long-copy");}
    static Stream<Arguments> entryGates(){return Stream.of("anonymous","disabled","CASHIER").flatMap(kind->ROUTES.stream().map(route->Arguments.of(kind,route)));}
    @ParameterizedTest @MethodSource("entryGates") void originalJwtAuthenticationAndClinicalDutyGatesApplyToAllEntries(String kind,String entry)throws Exception{route=entry;String token=actor.token();int status=403,code=1005;if(kind.equals("anonymous")){token=null;status=401;code=1000;}else if(kind.equals("disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());status=401;code=1000;}else roles(actor,kind);var before=facts();var old=audit();verify("entry-"+kind+"-"+entry,read(token),new Wanted(status,code,null),before,old,null,false);}
    @ParameterizedTest @ValueSource(strings={"contentINP","copyINP"}) void malformedSourceKeepsOriginal4068BeforeObjectQualification(String entry)throws Exception{route=entry;sourceOverride="PACS";var before=facts();var old=audit();verify("bad-source-"+entry,read(),new Wanted(200,4068,null),before,old,null,false);}
    @ParameterizedTest @ValueSource(strings={"null-context","missing-db-admin","missing-db-quality","existing-db-no-role"}) void noTrustedMissingEmployeeFallbackIsIntroduced(String kind){
        if(!kind.equals("null-context")){String name=kind.startsWith("missing")?tag+"missing":actor.name();if(kind.equals("existing-db-no-role"))roles(actor,"TECHNICIAN");SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(name,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(kind.endsWith("quality")?"ROLE_QUALITY":"ROLE_ADMIN"))));}
        var before=facts();try{assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.priorRecords(patient,null));}finally{SecurityContextHolder.clearContext();}assertEquals(before,facts());
    }
    void mutation(String method,String path,Object body,Staff who)throws Exception{Reply reply=call(method,path,body,who.token());var event=new LinkedHashMap<String,Object>();event.put("method",method);event.put("path",path);event.put("actor",who.name());event.put("body",body);event.put("response",reply.body());event.put("http",reply.http());maintenance.add(event);response(reply,200,0);if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())entityManager.flush();}
    void publicRoles(String... values)throws Exception{mutation("PUT","/api/system/users/"+actor.id(),Map.of("username",actor.name(),"realName",actor.name(),"deptId",dept,"roleCodes",List.of(values)),admin);}
    void publicAttending(Staff who)throws Exception{mutation("PUT","/api/inpatient/admissions/"+admission+"/attending-doctor",Map.of("doctorId",who.id()),admin);}
    void publishNew()throws Exception{
        mutation("PUT","/api/inpatient/admissions/"+admission+"/records/"+record,Map.of("content",newNonce+"住院正文","title",tag+"新标题"),owner);
        mutation("PUT","/api/outpatient/doctor/"+registration+"/emr",Map.of("emr",Map.of("chiefComplaint",newNonce,"presentIllness","现病史","advice","处理"),"diagnoses",List.of()),owner);
    }
    void change(String kind)throws Exception{switch(kind){case "attendingLoss"->publicAttending(owner);case "attendingGain"->publicAttending(actor);case "adminLoss"->publicRoles("DOCTOR_OUTP");case "nurseOnly"->publicRoles("NURSE");case "dutyLoss"->publicRoles("TECHNICIAN");case "disabled"->mutation("PUT","/api/system/users/"+actor.id()+"/enabled?enabled=false",null,admin);default->throw new AssertionError(kind);}publishNew();}
    static Stream<Arguments> beforeWindows(){return Stream.of("attendingLoss","adminLoss","nurseOnly","dutyLoss","disabled","attendingGain").flatMap(kind->ROUTES.stream().map(route->Arguments.of(kind,route)));}
    @ParameterizedTest @MethodSource("beforeWindows") void currentIdentityPatientQualificationAndNewPayloadShareOneStatement(String kind,String entry)throws Exception{route=entry;if(kind.equals("adminLoss"))roles(actor,"ADMIN");else if(!kind.equals("attendingGain"))relation("ownedInp");window("before-"+kind+"-"+entry,kind,false,false);}
    static Stream<Arguments> afterWindows(){return Stream.of("attendingLoss","nurseOnly","disabled").flatMap(kind->ROUTES.stream().map(route->Arguments.of(kind,route)));}
    @ParameterizedTest @MethodSource("afterWindows") void anAlreadyExecutedAuthorizedSnapshotMayReturnOrCopyItsCompleteOldBody(String kind,String entry)throws Exception{route=entry;relation("ownedInp");window("after-"+kind+"-"+entry,kind,true,false);}
    @ParameterizedTest @ValueSource(strings={"list","contentOUTP","contentINP","copyOUTP","copyINP"}) void rolledBackPublicRelationshipAndPublicationNeverChangeReadEligibility(String entry)throws Exception{route=entry;relation("ownedInp");window("rollback-"+entry,"attendingLoss",false,true);}
    @Test void anAlreadyExecutedLegalEmptyArrayIsNotReclassifiedByLaterRevocation()throws Exception{roles(actor,"ADMIN");jdbc.update("delete from outp_emr where id=?",emr);jdbc.update("delete from inp_medical_record where id=?",record);window("empty-after-role-loss","emptyRoleLoss",true,false);}
    void window(String label,String kind,boolean after,boolean rollback)throws Exception{
        Wanted wanted=expected();var auditBefore=audit();var gap=new Gap(after);var pool=Executors.newSingleThreadExecutor();
        Future<Reply> pending=pool.submit(()->{Gap.TRACE.set(gap);try{return read();}finally{Gap.TRACE.remove();SecurityContextHolder.clearContext();}});
        try{gap.await();if(rollback){var tx=new TransactionTemplate(transactions);tx.setTimeout(20);var original=facts();tx.executeWithoutResult(status->{try{change(kind);entityManager.flush();entityManager.clear();status.setRollbackOnly();}catch(Exception e){throw new RuntimeException(e);}});assertEquals(original,facts());}else{if(kind.equals("emptyRoleLoss"))publicRoles("DOCTOR_OUTP");else change(kind);if(!after)wanted=expected();}
            var before=facts();gap.release();Reply reply=pending.get(20,TimeUnit.SECONDS);verify(label,reply,wanted,before,auditBefore,gap,rollback);
        }finally{gap.release();pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    void check(String label)throws Exception{var wanted=expected();var before=facts();var old=audit();verify(label,read(),wanted,before,old,null,false);}
    void verify(String label,Reply reply,Wanted wanted,Map<String,List<String>> before,List<Map<String,Object>> old,Gap gap,boolean rolledBack){
        var after=facts();evidence(label,reply,wanted,before,after,old,gap,rolledBack);
        assertAll(()->{response(reply,wanted.http(),wanted.code());if(wanted.code()==0&&!route.startsWith("copy")){assertEquals(wanted.data(),reply.body().path("data"));if(route.equals("list"))for(var r:reply.body().path("data"))assertEquals(LIST_KEYS,keys(r));else assertEquals(CONTENT_KEYS,keys(reply.body().path("data")));}},()->{
            if(route.startsWith("copy")&&wanted.code()==0&&reply.code()==0)successfulCopy(reply,wanted,before,after);else assertEquals(before,after,"Denied/read-only history request cannot change any monitored business/auth/config column");
        },()->{var events=new ArrayList<Map<String,Object>>();if(!rolledBack)events.addAll(maintenance);var entry=new LinkedHashMap<String,Object>();entry.put("method",route.startsWith("copy")?"POST":"GET");entry.put("path",endpoint());entry.put("actor",wanted.http()==401?null:actor.name());entry.put("http",wanted.http());if(route.startsWith("copy")||wanted.http()==403||wanted.http()==401)events.add(entry);audited(old,events);});
        System.out.println("V152 CASE "+label+" actual="+reply.http()+"/"+reply.code()+" expected="+wanted.http()+"/"+wanted.code()+" snapshot="+(gap==null?"sequential":gap.after?"AFTER":"BEFORE"));
    }
    void successfulCopy(Reply reply,Wanted wanted,Map<String,List<String>> before,Map<String,List<String>> after){
        for(String key:before.keySet())if(!Set.of("emr_template","emr_template_grant").contains(key))assertEquals(before.get(key),after.get(key),key);
        assertEquals(COPY_KEYS,keys(reply.body().path("data")));String full=wanted.data().path("content").asText();String truncated=full.substring(0,Math.min(4000,full.length()));Long id=reply.body().path("data").path("id").asLong();
        assertEquals(before.get("emr_template").size()+1,after.get("emr_template").size());assertTrue(after.get("emr_template").containsAll(before.get("emr_template")));assertEquals(before.get("emr_template_grant").size()+1,after.get("emr_template_grant").size());assertTrue(after.get("emr_template_grant").containsAll(before.get("emr_template_grant")));
        var t=jdbc.queryForMap("select * from emr_template where id=?",id);assertEquals(truncated,t.get("content"));assertEquals(actor.id(),((Number)t.get("owner_id")).longValue());assertEquals(actor.id(),((Number)t.get("created_by")).longValue());assertEquals("PERSONAL",t.get("scope"));assertEquals("EMR",t.get("template_type"));assertEquals(wanted.data().path("recordType").asText(),t.get("record_type"));assertNull(t.get("dept_id"));assertEquals(tag+"copy",t.get("name"));assertEquals(false,t.get("is_default"));assertEquals(true,t.get("enabled"));
        assertEquals(wire(Map.of("id",id,"scope","PERSONAL","recordType",wanted.data().path("recordType").asText(),"contentLength",truncated.length(),"sourceLength",full.length(),"truncated",full.length()>4000,"maxContent",4000)),reply.body().path("data"));
        var g=jdbc.queryForList("select * from emr_template_grant where template_id=?",id);assertEquals(1,g.size());assertEquals("USER",g.getFirst().get("grantee_type"));assertEquals(actor.id(),((Number)g.getFirst().get("grantee_id")).longValue());assertEquals(actor.id(),((Number)g.getFirst().get("granted_by")).longValue());
    }
    Set<String> keys(JsonNode node){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    Reply call(String method,String path,Object body,String token)throws Exception{
        SecurityContextHolder.clearContext();try{MockHttpServletRequestBuilder req=method.equals("POST")?post(path):method.equals("PUT")?put(path):get(path);if(method.equals("GET")&&path.contains("?")){req=get(path.substring(0,path.indexOf('?')));for(String p:path.substring(path.indexOf('?')+1).split("&")){String[] kv=p.split("=",2);req.queryParam(URLDecoder.decode(kv[0],StandardCharsets.UTF_8),URLDecoder.decode(kv.length==2?kv[1]:"",StandardCharsets.UTF_8));}}if(body!=null)req.contentType("application/json").content(mapper.writeValueAsBytes(body));if(token!=null)req.header("Authorization","Bearer "+token);var r=mvc.perform(req).andReturn().getResponse();return new Reply(r.getStatus(),mapper.readTree(r.getContentAsString(StandardCharsets.UTF_8)),path);}finally{SecurityContextHolder.clearContext();}
    }
    JsonNode wire(Object value){return mapper.valueToTree(value);}
    void response(Reply r,int status,int code){assertEquals(Set.of("code","message","data"),keys(r.body()));assertEquals(status,r.http(),r.body().toString());assertEquals(code,r.code(),r.body().toString());if(code!=0)assertTrue(r.body().path("data").isNull());if(code==4036)assertEquals("无权查阅该患者的既往病历",r.body().path("message").asText());}
    Map<String,List<String>> facts(){var out=new LinkedHashMap<String,List<String>>();for(String t:TABLES)out.put(t,jdbc.queryForList("select to_jsonb(t)::text from "+t+" t order by id",String.class));out.put("config",jdbc.queryForList("select to_jsonb(t)::text from sys_config t order by cfg_key",String.class));out.put("roles",jdbc.queryForList("select to_jsonb(t)::text from sys_user_role t order by user_id,role_id",String.class));out.put("users",jdbc.queryForList("select ((to_jsonb(t)-'password')||jsonb_build_object('passwordDigest',md5(t.password)))::text from sys_user t order by id",String.class));return out;}
    List<Map<String,Object>> audit(){return jdbc.queryForList("select id,username,method,path,http_status,client_ip,created_at::text as created_at from sys_audit_log order by id");}
    void audited(List<Map<String,Object>> old,List<Map<String,Object>> events){var all=audit();assertTrue(all.containsAll(old));var added=new ArrayList<>(all);added.removeAll(old);assertEquals(events.size(),added.size(),added.toString());for(var e:events){String path=(String)e.get("path");var matches=added.stream().filter(a->Objects.equals(a.get("method"),e.get("method"))&&Objects.equals(a.get("path"),path)&&Objects.equals(a.get("username"),e.get("actor"))&&((Number)a.get("http_status")).intValue()==((Number)e.get("http")).intValue()).toList();assertFalse(matches.isEmpty(),e.toString());var a=matches.getFirst();assertEquals(Set.of("id","username","method","path","http_status","client_ip","created_at"),a.keySet());assertEquals("127.0.0.1",a.get("client_ip"));added.remove(a);}assertTrue(added.isEmpty());}
    void evidence(String label,Reply reply,Wanted wanted,Map<String,List<String>> before,Map<String,List<String>> after,List<Map<String,Object>> old,Gap gap,boolean rollback){String folder=System.getProperty("hip.v152.evidenceDir");if(folder==null)return;try{Path dir=Path.of(folder);Files.createDirectories(dir);var out=new LinkedHashMap<String,Object>();out.put("label",label);out.put("tag",tag);out.put("route",route);out.put("http",reply.http());out.put("response",reply.body());out.put("expectedHttp",wanted.http());out.put("expectedCode",wanted.code());out.put("expectedData",wanted.data());out.put("before",before);out.put("after",after);out.put("oldAudit",old);out.put("currentAudit",audit());out.put("publicChanges",maintenance);out.put("changesRolledBack",rollback);out.put("oldNonce",oldNonce);out.put("newNonce",newNonce);if(gap!=null)out.put("jdbcBoundary",gap.proof());Files.writeString(dir.resolve(tag+"-"+(++artifact)+".json"),mapper.writeValueAsString(out),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new AssertionError(e);}}
    static final class Gap{
        static final ThreadLocal<Gap> TRACE=new ThreadLocal<>();final boolean after;final CountDownLatch reached=new CountDownLatch(1),resume=new CountDownLatch(1);final AtomicInteger hits=new AtomicInteger();final List<Map<String,Object>> statements=new ArrayList<>();Gap(boolean after){this.after=after;}
        void observe(String sql,Map<Integer,Object> params,boolean done,int fetchSize){String n=sql.replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);if(!(n.contains("from inp_medical_record r")||n.contains("from outp_emr e"))||n.contains("for update"))return;var e=new LinkedHashMap<String,Object>();e.put("sql",sql);e.put("parameters",new TreeMap<>(params));e.put("position",done?"AFTER":"BEFORE");e.put("thread",Thread.currentThread().getName());e.put("time",Instant.now().toString());e.put("fetchSize",fetchSize);statements.add(e);if(done==after){assertEquals(1,hits.incrementAndGet());assertEquals(0,fetchSize);reached.countDown();try{assertTrue(resume.await(20,TimeUnit.SECONDS));}catch(InterruptedException x){Thread.currentThread().interrupt();throw new AssertionError(x);}}}
        void await()throws Exception{assertTrue(reached.await(15,TimeUnit.SECONDS),"Actual history payload execute boundary missing");assertEquals(1,hits.get());}void release(){resume.countDown();}Map<String,Object> proof(){return Map.of("kind","test-only JDBC executeQuery", "postgresLockWaitClaim",false,"after",after,"hits",hits.get(),"statements",statements);}
    }
    @TestConfiguration(proxyBeanMethods=false) static class JdbcConfiguration{@Bean @Primary JdbcTemplate historyBoundaryJdbcTemplate(DataSource actual){return new JdbcTemplate(new BoundaryDataSource(actual));}}
    static final class BoundaryDataSource extends DelegatingDataSource{
        BoundaryDataSource(DataSource actual){super(new TransactionAwareDataSourceProxy(actual));}
        @Override public Connection getConnection()throws SQLException{return connection(super.getConnection());}
        @Override public Connection getConnection(String u,String p)throws SQLException{return connection(super.getConnection(u,p));}
        Connection connection(Connection actual){return(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{Object v=invoke(actual,m,a);return m.getName().equals("prepareStatement")&&a!=null&&a[0] instanceof String sql&&v instanceof PreparedStatement ps?statement(ps,sql):v;});}
        PreparedStatement statement(PreparedStatement actual,String sql){var params=new TreeMap<Integer,Object>();return(PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)->{if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer i)params.put(i,m.getName().equals("setNull")?null:a[1]);if(m.getName().equals("clearParameters"))params.clear();Gap g=Gap.TRACE.get();boolean q=m.getName().equals("executeQuery")&&(a==null||a.length==0);if(g!=null&&q)g.observe(sql,params,false,actual.getFetchSize());Object v=invoke(actual,m,a);if(g!=null&&q)g.observe(sql,params,true,actual.getFetchSize());return v;});}
        static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    }
    @AfterEach void cleanup(){
        Gap.TRACE.remove();SecurityContextHolder.clearContext();
        for(Long id:ownTemplates){jdbc.update("delete from emr_template_grant where template_id=?",id);jdbc.update("delete from emr_template where id=?",id);}
        for(Long reg:registrations){jdbc.update("delete from emr_version where emr_type='OUTP' and emr_id in(select id from outp_emr where registration_id=?)",reg);jdbc.update("delete from outp_diagnosis_version where registration_id=?",reg);jdbc.update("delete from outp_diagnosis where registration_id=?",reg);jdbc.update("delete from outp_emr where registration_id=?",reg);jdbc.update("delete from outp_registration where id=?",reg);}
        jdbc.update("delete from emr_version where emr_type='INP' and emr_id in(select id from inp_medical_record where admission_id=?)",admission);jdbc.update("delete from inp_medical_record where admission_id=?",admission);jdbc.update("delete from inp_admission where id=?",admission);jdbc.update("delete from inp_bed where id=?",bed);
        for(Long id:schedules)jdbc.update("delete from outp_schedule where id=?",id);
        jdbc.update("delete from empi_patient where name in (?,?)",tag+"patient",tag+"other");
        for(Staff who:people){jdbc.update("delete from sys_user_role where user_id=?",who.id());jdbc.update("delete from sys_user where id=?",who.id());}jdbc.update("delete from sys_dept where id=?",dept);
    }
}
