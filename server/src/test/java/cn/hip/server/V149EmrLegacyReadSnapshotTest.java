package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService;
import cn.hip.medtech.web.MedTechController;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import cn.hip.platform.core.common.HipBizException;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * v149 legacy GET: current existing employee profile, ACL and complete raw row
 * share one real SQL snapshot. Test-only JDBC boundaries schedule actual JWT
 * reads and public commits; they are never PostgreSQL row-lock wait claims.
 * Trusted Actor/null/missing/mock-context calls are explicitly non-HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(V149EmrLegacyReadSnapshotTest.JdbcConfiguration.class)
class V149EmrLegacyReadSnapshotTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired EmrTemplateService service;
    @Autowired MedTechController controller;

    static final String PATH = "/api/emr-templates";
    static final List<String> DUTIES = List.of("ADMIN", "DOCTOR_OUTP", "NURSE", "QUALITY", "TECHNICIAN");
    static final List<String> TABLES = List.of("empi_patient", "outp_schedule", "outp_registration",
            "outp_emr", "outp_diagnosis", "outp_diagnosis_favorite", "outp_diagnosis_version", "emr_version",
            "inp_admission", "inp_medical_record", "outp_order", "outp_charge", "inp_order", "inp_order_exec",
            "inp_deposit", "inp_settlement", "emr_template", "emr_template_field", "emr_template_grant",
            "cdr_document", "emr_consent", "fin_print_log", "lis_sample", "ris_exam", "path_specimen");
    record Staff(Long id, String name, String token) {}
    record Reply(int http, JsonNode body) { int code() { return body.path("code").asInt(); } }
    record Audited(String method, String path, int status, String username) {}

    String tag, oldNonce, newNonce;
    Long dept, otherDept, global, hospital, department, personal, extra, ris, template, grantId;
    Staff actor, owner, admin;
    final List<Staff> people = new ArrayList<>();
    final List<Long> ownTemplates = new ArrayList<>();
    final List<Map<String, Object>> maintenance = new ArrayList<>(), internalInvocations = new ArrayList<>();
    final Map<String, String> filters = new LinkedHashMap<>();
    static final Set<String> ROW_KEYS=Set.of("id","dept_id","name","content","template_type","enabled","scope","owner_id","record_type","is_default","created_by","created_at");
    int artifact;

    @BeforeEach void setup() {
        SecurityContextHolder.clearContext(); tag="V149T"+Long.toUnsignedString(System.nanoTime(),36);
        oldNonce=tag+"_OLD_ORIGINAL_BODY_";newNonce=tag+"_NEW_PUBLISHED_ONLY_AFTER_PROFILE_COMMIT_";
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag+"A",tag+"A");
        otherDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag+"B",tag+"B");
        actor=employee("actor","DOCTOR_OUTP",otherDept);owner=employee("owner","DOCTOR_OUTP",dept);admin=employee("admin","ADMIN",otherDept);
        global=create("A_global","GLOBAL",null,"EMR","OUTP",false);
        hospital=create("B_hospital","HOSPITAL",null,"EMR","OUTP",false);
        department=create("C_dept","DEPT",dept,"EMR","OUTP",true);
        personal=create("D_personal","PERSONAL",null,"EMR","OUTP",false);
        extra=create("E_dept","DEPT",dept,"EMR","ABCDEFGHIJKLMNOP",false);
        ris=create("F_ris","HOSPITAL",null,"RIS",null,false);template=department;
        jdbc.update("insert into emr_template_field(template_id,field_code,label,datatype,required,sort_no) values (?,'f','原字段','TEXT',false,1)",department);
        filters.put("type","EMR");
    }
    Long create(String suffix,String scope,Long department,String type,String record,boolean isDefault) {
        Long id=jdbc.queryForObject("insert into emr_template(dept_id,name,content,template_type,scope,owner_id,record_type,enabled,is_default,created_by) values (?,?,?,?,?,?,?,true,?,?) returning id",
                Long.class,department,tag+"_"+suffix,oldNonce,type,scope,owner.id(),record,isDefault,owner.id());ownTemplates.add(id);return id;
    }
    Long grant(Long target,String kind,Long grantee) {return jdbc.queryForObject("insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by) values (?,?,?,?) returning id",Long.class,target,kind,grantee,owner.id());}
    String endpoint(){var query=new ArrayList<String>();for(var e:filters.entrySet())if(e.getValue()!=null)query.add(e.getKey()+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8));return PATH+(query.isEmpty()?"":"?"+String.join("&",query));}
    Reply read()throws Exception{return call("GET",endpoint(),null,actor.token());}
    boolean has(String value){return value!=null&&!value.isBlank();}
    JsonNode expected(EmrTemplateService.Actor supplied) {
        Long uid=supplied.userId(),readerDept=supplied.deptId();boolean isAdmin=supplied.admin();
        var employee=uid==null?List.<Map<String,Object>>of():jdbc.queryForList("select id,dept_id from sys_user where id=?",uid);
        if(!employee.isEmpty()){
            readerDept=(Long)employee.getFirst().get("dept_id");
            isAdmin=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=? and r.code='ADMIN')",Boolean.class,uid));
        }
        final Long actualDept=readerDept;var grants=jdbc.queryForList("select template_id,grantee_type,grantee_id from emr_template_grant");
        var all=jdbc.queryForList("select * from emr_template order by id");var result=new ArrayList<Map<String,Object>>();
        for(var row:all){
            assertEquals(ROW_KEYS,row.keySet());
            if(!Boolean.TRUE.equals(row.get("enabled")))continue;
            if(has(filters.get("type"))&&!filters.get("type").equals(row.get("template_type")))continue;
            if(filters.get("deptId")!=null&&row.get("dept_id")!=null&&!Long.valueOf(filters.get("deptId")).equals(row.get("dept_id")))continue;
            String scope=(String)row.get("scope");boolean mine=uid!=null&&uid.equals(row.get("owner_id"));
            boolean granted=grants.stream().anyMatch(g->Objects.equals(g.get("template_id"),row.get("id"))&&(("USER".equals(g.get("grantee_type"))&&uid!=null&&uid.equals(g.get("grantee_id")))||("DEPT".equals(g.get("grantee_type"))&&actualDept!=null&&actualDept.equals(g.get("grantee_id")))));
            boolean visible=Set.of("GLOBAL","HOSPITAL").contains(scope)||mine||("DEPT".equals(scope)&&(isAdmin||actualDept!=null&&actualDept.equals(row.get("dept_id"))))||granted;
            if(visible)result.add(row);
        }
        return wire(result);
    }
    JsonNode expected(){return expected(new EmrTemplateService.Actor(actor.id(),null,false));}
    Long target(String scope){return switch(scope){case "GLOBAL"->global;case "HOSPITAL"->hospital;case "DEPT"->department;case "PERSONAL"->personal;default->throw new AssertionError(scope);};}
    void mutation(String verb,String path,Object payload,Staff who)throws Exception{var reply=call(verb,path,payload,who.token());var event=new LinkedHashMap<String,Object>();event.put("method",verb);event.put("path",path);event.put("body",payload);event.put("actor",who.name());event.put("http",reply.http());event.put("response",reply.body());maintenance.add(event);response(reply,200,0);}
    void publicActor(String role,Long department)throws Exception{mutation("PUT","/api/system/users/"+actor.id(),Map.of("username",actor.name(),"realName",actor.name(),"deptId",department,"roleCodes",List.of(role)),admin);}
    void publicBody()throws Exception{var old=jdbc.queryForMap("select * from emr_template where id=?",template);var value=new LinkedHashMap<String,Object>();value.put("name",old.get("name"));value.put("content",newNonce);value.put("templateType",old.get("template_type"));value.put("scope",old.get("scope"));value.put("deptId",old.get("dept_id"));value.put("recordType",old.get("record_type"));mutation("PUT","/api/emr-templates/"+template,value,Set.of("GLOBAL","HOSPITAL").contains(old.get("scope"))?admin:owner);}
    Audited[] committedEvents(){return maintenance.stream().map(e->new Audited((String)e.get("method"),(String)e.get("path"),200,(String)e.get("actor"))).toArray(Audited[]::new);}
    void positive(String label)throws Exception{var wanted=expected();var before=facts();var audits=audit();frozen(label,read(),200,0,wanted,before,audits,null);}
    static Stream<Arguments> matrix(){return Stream.of("GLOBAL","HOSPITAL","DEPT","PERSONAL").flatMap(scope->DUTIES.stream().map(role->Arguments.of(scope,role)));}
    @ParameterizedTest @MethodSource("matrix") void fourScopeFiveDutyLegacyVisibilityReturnsOnlyOriginalRawColumns(String scope,String role)throws Exception{roles(actor,role);template=target(scope);positive("matrix-"+scope+"-"+role);}
    @ParameterizedTest @MethodSource("matrix") void ownerIdentityStillSeesEveryOwnedScopeForAllOriginalDuties(String scope,String role)throws Exception{roles(actor,role);jdbc.update("update emr_template set owner_id=? where id=?",actor.id(),target(scope));positive("owner-"+scope+"-"+role);}
    @ParameterizedTest @ValueSource(strings={"ADMIN","DOCTOR_OUTP","NURSE","QUALITY","TECHNICIAN"}) void sameDepartmentStillReadsWithoutGrant(String role)throws Exception{roles(actor,role);jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());positive("same-dept-"+role);}
    static Stream<Arguments> grantCases(){return Stream.of("DEPT","PERSONAL").flatMap(scope->Stream.of("USER","DEPT").map(kind->Arguments.of(scope,kind)));}
    @ParameterizedTest @MethodSource("grantCases") void additiveUserAndDepartmentGrantsIncludePersonalScope(String scope,String kind)throws Exception{grant(target(scope),kind,kind.equals("USER")?actor.id():otherDept);positive("grant-"+scope+"-"+kind);}
    @ParameterizedTest @ValueSource(strings={"USER","DEPT"}) void adminReadsPersonalOnlyWithTheOriginalExplicitGrant(String kind)throws Exception{roles(actor,"ADMIN");grant(personal,kind,kind.equals("USER")?actor.id():otherDept);positive("personal-admin-granted-"+kind);}
    @ParameterizedTest @CsvSource({"PERSONAL,DOCTOR_OUTP","PERSONAL,ADMIN","DEPT,DOCTOR_OUTP","DEPT,ADMIN"}) void historicalNullOwnersKeepTheirOriginalVisibility(String scope,String role)throws Exception{roles(actor,role);jdbc.update("update emr_template set owner_id=null where id=?",target(scope));grant(target(scope),"USER",actor.id());positive("null-owner-"+scope+"-"+role);}
    @ParameterizedTest @ValueSource(strings={"null-none","null-admin","null-dept","null-admin-dept","missing-none","missing-admin","missing-dept","missing-admin-dept","missing-user-grant","existing-spoof-dept","existing-spoof-admin","existing-null-dept","existing-db-admin","existing-owner-grant","existing-disabled"})
    void trustedActorFallbackIsPreservedOnlyWithoutAnExistingEmployee(String kind){
        Long uid=kind.startsWith("null-")?null:kind.startsWith("missing-")?899999999999999L:actor.id();
        Long suppliedDept=kind.contains("dept")?dept:null;boolean suppliedAdmin=kind.contains("admin")&&!kind.equals("existing-db-admin");
        if(kind.equals("missing-user-grant"))grant(personal,"USER",uid);
        if(kind.equals("existing-null-dept"))jdbc.update("update sys_user set dept_id=null where id=?",actor.id());
        if(kind.equals("existing-db-admin"))roles(actor,"ADMIN");
        if(kind.equals("existing-owner-grant")){jdbc.update("update emr_template set owner_id=? where id=?",actor.id(),personal);grant(department,"USER",actor.id());}
        if(kind.equals("existing-disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());grant(personal,"USER",actor.id());}
        var supplied=new EmrTemplateService.Actor(uid,suppliedDept,suppliedAdmin);var wanted=expected(supplied);var before=facts();var audits=audit();
        Object value=service.legacyList(supplied,null,filters.get("type"));var call=new LinkedHashMap<String,Object>();call.put("userId",uid);call.put("suppliedDept",suppliedDept);call.put("suppliedAdmin",suppliedAdmin);call.put("actualValue",wire(value));internalInvocations.add(call);
        var after=facts();evidence("trusted-"+kind,before,after,audits,null,null);assertEquals(wanted,wire(value));assertEquals(before,after);assertEquals(audits,audit());
    }
    @Test void noDatabaseMockAdminControllerFallbackRemainsAnExplicitTrustedNonHttpCall(){
        var wanted=expected(new EmrTemplateService.Actor(null,null,true));var before=facts();var audits=audit();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(tag+"_mock_no_employee",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try{var value=controller.templates(null,"EMR");internalInvocations.add(Map.of("kind","trusted controller mock context, not HTTP/JWT","actualValue",wire(value.getData())));var after=facts();evidence("trusted-no-db-mock-admin",before,after,audits,null,null);assertEquals(0,value.getCode());assertEquals(wanted,wire(value.getData()));assertEquals(before,after);assertEquals(audits,audit());}finally{SecurityContextHolder.clearContext();}
    }
    @ParameterizedTest @ValueSource(strings={"EMR","RIS","CONSENT","emr"," EMR ","UNKNOWN","  ","OMIT"}) void typeFilterRetainsItsOriginalLiteralAndBlankRules(String value)throws Exception{roles(actor,"ADMIN");grant(personal,"USER",actor.id());if(value.equals("OMIT"))filters.remove("type");else filters.put("type",value);positive("literal-type-"+value);}
    @ParameterizedTest @ValueSource(strings={"A","B","MISSING","OMIT"}) void requestedDepartmentIncludesThatDepartmentOrNullAndRemainsIndependentOfActor(String value)throws Exception{roles(actor,"ADMIN");grant(personal,"USER",actor.id());if(!value.equals("OMIT"))filters.put("deptId",value.equals("A")?dept.toString():value.equals("B")?otherDept.toString():"899999999999998");positive("requested-dept-"+value);}
    @ParameterizedTest @ValueSource(strings={"ADMIN","DOCTOR_OUTP","NURSE","QUALITY","TECHNICIAN"}) void disabledTemplatesStayHiddenForEveryOriginalDutyEvenTheirOwner(String role)throws Exception{roles(actor,role);jdbc.update("update emr_template set enabled=false,owner_id=? where id=?",actor.id(),personal);positive("disabled-owner-"+role);}
    @Test void sortingRemainsIdAscendingEvenWhenDefaultAndNamesSuggestAnotherOrder()throws Exception{roles(actor,"ADMIN");grant(personal,"USER",actor.id());var wanted=expected();var before=facts();var audits=audit();var reply=read();frozen("literal-id-sorting",reply,200,0,wanted,before,audits,null);var ours=new ArrayList<Long>();reply.body().path("data").forEach(n->{long id=n.path("id").asLong();if(ownTemplates.contains(id))ours.add(id);});assertEquals(List.of(global,hospital,department,personal,extra),ours);}
    @Test void invalidPublicLongParameterRetainsTheOriginal4000Envelope()throws Exception{var before=facts();var audits=audit();frozen("invalid-dept-param",call("GET",PATH+"?deptId=not-a-long&type=EMR",null,actor.token()),200,4000,null,before,audits,null);}
    @ParameterizedTest @ValueSource(strings={"CASHIER","INTERFACE","NO_ROLES","anonymous","disabled"}) void originalJwtAndFiveDutyEntryGatesRemainReadable(String who)throws Exception{String token=actor.token();int http=403,code=1005;if(who.equals("anonymous")){token=null;http=401;code=1000;}else if(who.equals("disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());http=401;code=1000;}else if(who.equals("NO_ROLES"))roles(actor);else roles(actor,who);var before=facts();var audits=audit();frozen("entry-"+who,call("GET",endpoint(),null,token),http,code,null,before,audits,null);}
    @Test void publicDepartmentTemplateAndGrantAreConsumedAsTheOriginalFullLegacyRow()throws Exception{var audits=audit();mutation("POST","/api/emr-templates/scoped",Map.of("name",tag+"_public","content","公共模拟正文","templateType","EMR","scope","DEPT","deptId",dept,"recordType","OUTP"),owner);Long id=((JsonNode)maintenance.getLast().get("response")).path("data").asLong();ownTemplates.add(id);mutation("POST","/api/emr-templates/"+id+"/grants",Map.of("granteeType","USER","granteeId",actor.id()),owner);var before=facts();frozen("public-create-grant-legacy",read(),200,0,expected(),before,audits,null,committedEvents());}
    void prepare(String kind){
        if(kind.equals("deptLoss")||kind.equals("deptLossPersonal")){jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());if(kind.equals("deptLossPersonal")){template=personal;grant(personal,"DEPT",dept);}}
        else if(kind.equals("adminLoss")||kind.equals("contextAdminLoss")){roles(actor,"ADMIN");}
        else if(kind.equals("revokeUser")){template=personal;grantId=grant(personal,"USER",actor.id());}
        else if(kind.equals("revokeDept")){template=personal;jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());grantId=grant(personal,"DEPT",dept);}
        else if(kind.equals("addUser")){template=personal;}
        else if(kind.equals("gainDept")||kind.equals("gainAdmin")){}
        else {jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());if(kind.equals("enable"))jdbc.update("update emr_template set enabled=false where id=?",department);}
    }
    void change(String kind,String role)throws Exception{
        switch(kind){
            case "deptLoss","deptLossPersonal","adminLoss","contextAdminLoss"->{publicActor(role,otherDept);publicBody();}
            case "gainDept"->{publicActor("DOCTOR_OUTP",dept);publicBody();}
            case "gainAdmin"->{publicActor("ADMIN",otherDept);publicBody();}
            case "disable"->mutation("PUT","/api/emr-templates/"+template+"/disable",null,owner);
            case "enable"->mutation("PUT","/api/emr-templates/"+template+"/enable",null,owner);
            case "unsetDefault"->mutation("DELETE","/api/emr-templates/"+template+"/default",null,owner);
            case "scope"->mutation("PUT","/api/emr-templates/"+template,Map.of("name",tag+"_C_dept","content",newNonce,"templateType","EMR","scope","PERSONAL","recordType","OUTP"),owner);
            case "revokeUser","revokeDept"->{mutation("DELETE","/api/emr-templates/"+template+"/grants/"+grantId,null,owner);publicBody();}
            case "addUser"->{publicBody();mutation("POST","/api/emr-templates/"+template+"/grants",Map.of("granteeType","USER","granteeId",actor.id()),owner);}
            case "roleSwap"->{publicActor("NURSE",dept);publicBody();}
            case "entryDisabled"->{mutation("PUT","/api/system/users/"+actor.id()+"/enabled?enabled=false",null,admin);publicBody();}
            case "entryDutyLost"->{publicActor("CASHIER",dept);publicBody();}
            case "body"->publicBody();
            default->fail(kind);
        }
    }
    static Stream<Arguments> losses(){return Stream.of("deptLoss","deptLossPersonal","adminLoss").flatMap(kind->Stream.of("DOCTOR_OUTP","NURSE","QUALITY","TECHNICIAN").map(role->Arguments.of(kind,role)));}
    @ParameterizedTest @MethodSource("losses") void newPrivateBodyPublishedAfterProfileLossCannotUseTheCompletedOlderActor(String kind,String role)throws Exception{if(!kind.equals("adminLoss"))roles(actor,role);prepare(kind);window("private-loss-"+kind+"-"+role,kind,role,false,"PAYLOAD_BEFORE");}
    @ParameterizedTest @ValueSource(strings={"DOCTOR_OUTP","NURSE","QUALITY","TECHNICIAN"}) void staleContextAdminCannotOverrideRolesActuallyReadAfterThePublicDemotion(String role)throws Exception{prepare("contextAdminLoss");window("context-or-"+role,"contextAdminLoss",role,false,"ROLE_BEFORE");}
    @ParameterizedTest @ValueSource(strings={"gainDept","gainAdmin"}) void newDepartmentOrAdminEligibilityIsAvailableToTheStatement(String kind)throws Exception{prepare(kind);window("profile-gain-"+kind,kind,"DOCTOR_OUTP",false,"PAYLOAD_BEFORE");}
    @ParameterizedTest @ValueSource(strings={"disable","enable","unsetDefault","scope","revokeUser","revokeDept","addUser","roleSwap","entryDisabled","entryDutyLost","body"}) void parentGrantEnabledAndOriginalEntrySnapshotPoliciesRemainCompatible(String kind)throws Exception{prepare(kind);window("compatible-before-"+kind,kind,"DOCTOR_OUTP",false,"PAYLOAD_BEFORE");}
    @ParameterizedTest @ValueSource(strings={"deptLoss","deptLossPersonal","adminLoss","contextAdminLoss","gainDept","revokeUser","disable"}) void realRolledBackPublicChangesPreserveTheOriginalCompleteLegacyList(String kind)throws Exception{prepare(kind);window("rollback-"+kind,kind,"DOCTOR_OUTP",true,kind.equals("contextAdminLoss")?"ROLE_BEFORE":"PAYLOAD_BEFORE");}
    @ParameterizedTest @ValueSource(strings={"deptLoss","adminLoss","revokeUser","scope","disable","unsetDefault"}) void alreadyExecutedAuthorizedStatementMayReturnItsCompleteOlderSnapshot(String kind)throws Exception{prepare(kind);window("authorized-after-"+kind,kind,"DOCTOR_OUTP",false,"PAYLOAD_AFTER");}
    void window(String label,String kind,String role,boolean rollback,String mode)throws Exception{
        var wanted=expected();var initial=facts();var audits=audit();var gap=new Gap(actor.id(),mode);var pool=Executors.newSingleThreadExecutor();
        try{var worker=pool.submit(()->tracedRead(gap));gap.awaitBoundary();
            if(rollback){var tx=new TransactionTemplate(transactions);tx.setTimeout(20);tx.executeWithoutResult(status->{try{change(kind,role);entityManager.flush();entityManager.clear();status.setRollbackOnly();}catch(Exception e){throw new RuntimeException(e);}});assertEquals(initial,facts());}
            else{change(kind,role);if(!mode.equals("PAYLOAD_AFTER"))wanted=expected();}
            var committed=facts();gap.release();var reply=finish(worker);frozen(label,reply,200,0,wanted,committed,audits,gap,rollback?new Audited[]{}:committedEvents());
            if(label.startsWith("private-loss-")||label.startsWith("context-or-"))assertFalse(reply.body().toString().contains(newNonce),"NEW first appeared only after this existing employee lost rights; no lawful earlier snapshot or cache could contain it");
        }finally{gap.release();close(pool);}
    }
    Reply tracedRead(Gap gap)throws Exception{Gap.TRACE.set(gap);try{return read();}finally{Gap.TRACE.remove();}}
    Reply finish(Future<Reply> worker)throws Exception{try{return worker.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Unexpected fixture/request exception",e.getCause());}}
    void close(ExecutorService pool)throws Exception{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    static final class Gap{
        static final ThreadLocal<Gap> TRACE=new ThreadLocal<>();final Long actorId;final String mode;final CountDownLatch reached=new CountDownLatch(1),resume=new CountDownLatch(1);final AtomicInteger hits=new AtomicInteger();final List<Map<String,Object>> statements=new ArrayList<>();final Set<String> observedRoleCodes=new LinkedHashSet<>();volatile Map<String,Object> boundary;volatile boolean boundaryContextAdmin;
        Gap(Long actorId,String mode){this.actorId=actorId;this.mode=mode;}
        boolean roleQuery(String sql,Map<Integer,Object> params){return sql.strip().toLowerCase(Locale.ROOT).startsWith("select r.code from sys_user_role")&&params.containsValue(actorId);}
        boolean payload(String sql){String n=sql.replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);return n.contains("select t.* from emr_template t")&&n.contains("where t.enabled")&&n.contains("order by t.id");}
        void observe(String sql,Map<Integer,Object> params,boolean done,int fetchSize){String n=sql.toLowerCase(Locale.ROOT);if(!n.contains("emr_template")&&!n.contains("select dept_id from sys_user")&&!n.contains("select r.code from sys_user_role"))return;var event=new LinkedHashMap<String,Object>();event.put("sql",sql);event.put("parameters",new TreeMap<>(params));event.put("position",done?"AFTER":"BEFORE");event.put("time",Instant.now().toString());event.put("threadName",Thread.currentThread().getName());event.put("threadId",Thread.currentThread().threadId());event.put("fetchSize",fetchSize);event.put("statementOrdinal",statements.size()+1);statements.add(event);
            boolean target=mode.equals("ROLE_BEFORE")?roleQuery(sql,params)&&!done:payload(sql)&&done==mode.equals("PAYLOAD_AFTER");
            if(target){assertEquals(1,hits.incrementAndGet());assertEquals(0,fetchSize);long completed=statements.stream().filter(v->v.get("position").equals("AFTER")&&(((String)v.get("sql")).contains("select dept_id from sys_user")||((String)v.get("sql")).contains("select r.code from sys_user_role"))).count();assertEquals(mode.equals("ROLE_BEFORE")?1:2,completed,"Controller actorOf is frozen; exact boundary follows real profile queries");var auth=SecurityContextHolder.getContext().getAuthentication();boundaryContextAdmin=auth!=null&&auth.getAuthorities().stream().anyMatch(v->v.getAuthority().equals("ROLE_ADMIN"));if(mode.equals("ROLE_BEFORE"))assertTrue(boundaryContextAdmin);boundary=new LinkedHashMap<>(event);reached.countDown();try{assertTrue(resume.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
        }
        void roleValue(Object value){if(value instanceof String role)observedRoleCodes.add(role);}
        void awaitBoundary()throws InterruptedException{assertTrue(reached.await(15,TimeUnit.SECONDS),"Exact legacy/role SQL boundary missing");assertEquals(1,hits.get());System.out.println("V149 JDBC_BOUNDARY mode="+mode+" proof="+boundary+" pgLockWaitClaim=false");}
        void release(){resume.countDown();}
        Map<String,Object> proof(){var out=new LinkedHashMap<String,Object>();out.put("kind","test-only exact legacy JDBC executeQuery boundary");out.put("postgresLockWaitClaim",false);out.put("mode",mode);out.put("hits",hits.get());out.put("boundary",boundary);out.put("contextAdminAtBoundary",boundaryContextAdmin);out.put("actualStandaloneRoleValues",List.copyOf(observedRoleCodes));out.put("statements",List.copyOf(statements));return out;}
    }
    Staff employee(String suffix, String role, Long department) {
        String name = tag + suffix;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id", Long.class, name, name, department);
        Staff who = new Staff(id, name, jwt.issue(name)); people.add(who); roles(who, role); return who;
    }
    void roles(Staff who, String... values) {
        jdbc.update("delete from sys_user_role where user_id=?", who.id());
        for (String role : values) assertEquals(1, jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", who.id(), role));
    }
    Reply call(String verb, String path, Object payload, String token) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            MockHttpServletRequestBuilder request = switch (verb) { case "PUT" -> put(path); case "POST" -> post(path); case "DELETE" -> delete(path); default -> get(path); };
            // MockMvc's String URI builder re-encodes an already URLEncoder'd
            // plus as a literal plus. Supply decoded servlet query values, as a
            // real HTTP form-style query parser does, without changing inputs.
            if (verb.equals("GET") && path.startsWith(PATH + "?")) {
                request = get(PATH);
                for (String part : path.substring(PATH.length() + 1).split("&")) {
                    String[] pair = part.split("=", 2);
                    request.queryParam(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
                }
            }
            if (payload != null) request.contentType("application/json").content(mapper.writeValueAsString(payload));
            if (token != null) request.header("Authorization", "Bearer " + token);
            var response = mvc.perform(request).andReturn().getResponse();
            return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)));
        } finally { SecurityContextHolder.clearContext(); }
    }
    List<String> rows(String table, String order) { return jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by " + order, String.class); }
    Map<String, List<String>> facts() {
        var result = new LinkedHashMap<String, List<String>>(); for (String table : TABLES) result.put(table, rows(table, "id"));
        result.put("config", rows("sys_config", "cfg_key")); result.put("roles", rows("sys_user_role", "user_id,role_id"));
        result.put("users", jdbc.queryForList("select ((to_jsonb(t)-'password') || jsonb_build_object('passwordDigest',md5(t.password)))::text from sys_user t order by id", String.class)); return result;
    }
    List<Map<String, Object>> audit() {
        // Preserve the exact PostgreSQL timestamp text, including microseconds,
        // in the seven-column export; no application Date truncation here.
        return jdbc.queryForList("select id,username,method,path,http_status,client_ip,created_at::text as created_at from sys_audit_log order by id");
    }
    void audited(List<Map<String, Object>> old, Audited... events) {
        var all = audit(); var original = new HashMap<Object, Map<String, Object>>(); old.forEach(r -> original.put(r.get("id"), r)); var added = new ArrayList<Map<String, Object>>();
        for (var r : all) { if (original.containsKey(r.get("id"))) assertEquals(original.remove(r.get("id")), r); else added.add(r); }
        assertTrue(original.isEmpty()); assertEquals(events.length, added.size(), added.toString()); var remaining = new ArrayList<>(added);
        for (var e : events) {
            var matching = remaining.stream().filter(r -> Objects.equals(r.get("username"), e.username()) && Objects.equals(r.get("method"), e.method())
                    && Objects.equals(r.get("path"), e.path()) && ((Number) r.get("http_status")).intValue() == e.status()).toList();
            assertFalse(matching.isEmpty(), "Missing actual audit: " + e); var row = matching.getFirst();
            assertEquals(Set.of("id", "username", "method", "path", "http_status", "client_ip", "created_at"), row.keySet()); assertEquals("127.0.0.1", row.get("client_ip")); assertNotNull(row.get("created_at")); remaining.remove(row);
        }
        assertTrue(remaining.isEmpty());
    }
    JsonNode wire(Object value) { try { return mapper.readTree(mapper.writeValueAsBytes(value)); } catch (Exception e) { throw new AssertionError(e); } }
    void response(Reply reply, int http, int code) {
        var keys = new HashSet<String>(); reply.body().fieldNames().forEachRemaining(keys::add); assertEquals(Set.of("code", "message", "data"), keys);
        assertAll(() -> assertEquals(http, reply.http(), reply.body().toString()), () -> assertEquals(code, reply.code(), reply.body().toString()));
        if (code != 0) assertTrue(reply.body().path("data").isNull());
    }
    void evidence(String label, Map<String, List<String>> before, Map<String, List<String>> after, List<Map<String, Object>> oldAudit, Reply reply, Gap gap) {
        String folder = System.getProperty("hip.v149.evidenceDir"); if (folder == null) return;
        try {
            Path directory = Path.of(folder).toAbsolutePath().normalize(); assertFalse(directory.toString().toLowerCase(Locale.ROOT).contains("onedrive")); Files.createDirectories(directory);
            var value = new LinkedHashMap<String, Object>(); value.put("label", label); value.put("tag", tag); value.put("kind", reply == null ? "trusted-internal-service" : "real-JWT-HTTP");
            value.put("http", reply == null ? null : reply.http()); value.put("response", reply == null ? null : reply.body()); value.put("before", before); value.put("after", after);
            value.put("oldAudit", oldAudit); value.put("currentAudit", audit()); value.put("publicMaintenance", maintenance); value.put("internalInvocations", internalInvocations);
            value.put("oldNonce", oldNonce); value.put("newNonce", newNonce); if (gap != null) value.put("jdbcBoundary", gap.proof());
            Files.writeString(directory.resolve(tag + "-" + (++artifact) + ".json"), mapper.writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Exception e) { throw new AssertionError("Cannot preserve original complete case", e); }
    }
    void frozen(String label, Reply reply, int http, int code, JsonNode wanted, Map<String, List<String>> before, List<Map<String, Object>> oldAudit, Gap gap, Audited... extra) {
        var after = facts(); evidence(label, before, after, oldAudit, reply, gap);
        if(label.startsWith("context-or-")){assertTrue(gap.boundaryContextAdmin);assertEquals(1,gap.observedRoleCodes.size());assertFalse(gap.observedRoleCodes.contains("ADMIN"),"Exact JDBC ResultSet exposed fresh non-ADMIN before old actorOf ORed the already authenticated context ADMIN");}
        var events = new ArrayList<>(Arrays.asList(extra)); if (http != 200) events.add(new Audited("GET", PATH, http, http == 401 ? null : actor.name()));
        System.out.println("V149 CASE " + label + " actual=" + reply.http() + "/" + reply.code() + " expected=" + http + "/" + code + " allFactsFrozen=" + before.equals(after));
        assertAll(() -> {
            response(reply, http, code); if (code == 0) {
                assertEquals(wanted, reply.body().path("data"), "Legacy raw row/ordering must use one current existing-employee and body snapshot; only absent employee may use trusted supplied Actor");
                for (JsonNode row : reply.body().path("data")) { var keys=new HashSet<String>(); row.fieldNames().forEachRemaining(keys::add); assertEquals(ROW_KEYS,keys); }
            }
        }, () -> assertEquals(before, after, "Read cannot mutate any monitored business/auth/config fact"), () -> audited(oldAudit, events.toArray(Audited[]::new)));
    }
    @TestConfiguration(proxyBeanMethods = false) static class JdbcConfiguration {
        @Bean @Primary JdbcTemplate v149BoundaryJdbcTemplate(DataSource actual) { return new JdbcTemplate(new BoundaryDataSource(actual)); }
    }
    static final class BoundaryDataSource extends DelegatingDataSource {
        BoundaryDataSource(DataSource actual) { super(new TransactionAwareDataSourceProxy(actual)); }
        @Override public Connection getConnection() throws SQLException { return connection(super.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return connection(super.getConnection(user, password)); }
        Connection connection(Connection actual) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                Object value = invoke(actual, method, args); if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql && value instanceof PreparedStatement ps) return statement(ps, sql); return value;
            });
        }
        PreparedStatement statement(PreparedStatement actual, String sql) {
            var parameters = new TreeMap<Integer, Object>();
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                if (method.getName().startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) parameters.put(index, method.getName().equals("setNull") ? null : args[1]); if (method.getName().equals("clearParameters")) parameters.clear();
                var trace = Gap.TRACE.get(); boolean query = method.getName().equals("executeQuery") && (args == null || args.length == 0);
                if (query && trace != null) trace.observe(sql, parameters, false, actual.getFetchSize()); Object value = invoke(actual, method, args);
                if (query && trace != null) { trace.observe(sql, parameters, true, actual.getFetchSize()); if(trace.roleQuery(sql,parameters)&&value instanceof ResultSet rows)return roleRows(rows,trace); } return value;
            });
        }
        ResultSet roleRows(ResultSet actual,Gap trace){return (ResultSet)Proxy.newProxyInstance(ResultSet.class.getClassLoader(),new Class<?>[]{ResultSet.class},(proxy,method,args)->{Object value=invoke(actual,method,args);if(method.getName().equals("getString")||method.getName().equals("getObject"))trace.roleValue(value);return value;});}
        static Object invoke(Object actual, Method method, Object[] args) throws Throwable { try { return method.invoke(actual, args); } catch (InvocationTargetException e) { throw e.getCause(); } }
    }
    @AfterEach void cleanup() {
        Gap.TRACE.remove(); SecurityContextHolder.clearContext();
        for (Long id : ownTemplates) { jdbc.update("delete from emr_template_field where template_id=?", id); jdbc.update("delete from emr_template_grant where template_id=?", id); jdbc.update("delete from emr_template where id=?", id); }
        for (var who : people) { jdbc.update("delete from sys_user_role where user_id=?", who.id()); jdbc.update("delete from sys_user where id=?", who.id()); }
        if (dept != null) jdbc.update("delete from sys_dept where id in (?,?)", dept, otherDept);
    }
}
