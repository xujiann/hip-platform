package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService;
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


/** Real JWT, original public maintenance, exact JDBC executeQuery boundaries.
 * Initial fieldActor remains real database policy. NEW field text is published
 * only after lost eligibility commits; no legal old snapshot could contain it.
 * AFTER empty-result and authorized snapshots are separately asserted. No outer
 * test transaction, fake result, production hook or PostgreSQL lock-wait claim. */
@SpringBootTest @AutoConfigureMockMvc
@Import(V150EmrFieldReadSnapshotTest.JdbcConfiguration.class)
class V150EmrFieldReadSnapshotTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired EmrTemplateService service;
    static final String PATH="/api/emr/templates/";
    static final List<String> DUTIES=List.of("ADMIN","DOCTOR_OUTP","NURSE","QUALITY");
    static final Set<String> RAW=Set.of("id","template_id","field_code","label","datatype","required","sort_no","value_set","placeholder","unit","enabled","created_at");
    static final Set<String> DTO=Set.of("id","templateId","fieldCode","label","datatype","required","sortNo","valueSet","placeholder","unit","enabled");
    static final List<String> TABLES = List.of("empi_patient", "outp_schedule", "outp_registration",
            "outp_emr", "outp_diagnosis", "outp_diagnosis_favorite", "outp_diagnosis_version", "emr_version",
            "inp_admission", "inp_medical_record", "outp_order", "outp_charge", "inp_order", "inp_order_exec",
            "inp_deposit", "inp_settlement", "emr_template", "emr_template_field", "emr_template_grant",
            "cdr_document", "emr_consent", "fin_print_log", "lis_sample", "ris_exam", "path_specimen");

    record Staff(Long id,String name,String token) {}
    record Reply(int http,JsonNode body,String path) {int code(){return body.path("code").asInt();}}
    record Audited(String method,String path,int status,String username) {}
    record Wanted(int http,int code,JsonNode data) {}
    String tag,oldNonce,newNonce,includeQuery;
    Long dept,otherDept,template,otherTemplate,field,grantId;
    Staff actor,owner,admin;
    final List<Staff> people=new ArrayList<>();
    final List<Long> ownTemplates=new ArrayList<>();
    final List<Map<String,Object>> maintenance=new ArrayList<>(),internalInvocations=new ArrayList<>();
    int artifact;
    @BeforeEach void setup(){
        SecurityContextHolder.clearContext();tag="V150T"+Long.toUnsignedString(System.nanoTime(),36);
        oldNonce=tag+"_OLD";newNonce=tag+"_NEW_AFTER_LOSS";
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag+"A",tag+"A");
        otherDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag+"B",tag+"B");
        actor=employee("actor","DOCTOR_OUTP",otherDept);owner=employee("owner","DOCTOR_OUTP",dept);admin=employee("admin","ADMIN",otherDept);
        template=parent("target","DEPT",dept);otherTemplate=parent("other","PERSONAL",null);
        int i=0;for(String type:List.of("TEXT","NUMBER","CHECKBOX","RADIO","MULTI","DATE")){
            Long id=jdbc.queryForObject("insert into emr_template_field(template_id,field_code,label,datatype,required,sort_no,value_set,placeholder,unit,enabled) values (?,?,?,?,?,?,?,?,?,true) returning id",Long.class,
                    template,"f"+i,oldNonce+"_"+type,type,i==0,i%3,Set.of("RADIO","MULTI").contains(type)?"[\"低\",\"高\"]":null,"提示"+i,"mm");
            if(i++==0)field=id;
        }
        jdbc.update("insert into emr_template_field(template_id,field_code,label,datatype,sort_no,enabled) values (?,'off','停用字段','TEXT',-1,false)",template);
        jdbc.update("insert into emr_template_field(template_id,field_code,label,datatype,sort_no) values (?,'other','其他父字段','TEXT',0)",otherTemplate);
    }
    Long parent(String name,String scope,Long department){Long id=jdbc.queryForObject("insert into emr_template(dept_id,name,content,template_type,scope,owner_id,record_type,enabled,created_by) values (?,?,?,'EMR',?,?, 'OUTP',true,?) returning id",Long.class,department,tag+name,"模拟父正文",scope,owner.id(),owner.id());ownTemplates.add(id);return id;}
    void configure(String scope,boolean mine){jdbc.update("delete from emr_template_grant where template_id=?",template);jdbc.update("update emr_template set scope=?,dept_id=?,owner_id=? where id=?",scope,scope.equals("DEPT")?dept:null,mine?actor.id():owner.id(),template);}
    Long grant(String kind,Long grantee){return jdbc.queryForObject("insert into emr_template_grant(template_id,grantee_type,grantee_id,granted_by) values (?,?,?,?) returning id",Long.class,template,kind,grantee,owner.id());}
    String endpoint(){return PATH+template+"/fields"+(includeQuery==null?"":"?includeDisabled="+includeQuery);}
    boolean include(){return includeQuery!=null&&Set.of("true","on","yes","1").contains(includeQuery.toLowerCase(Locale.ROOT));}
    Reply read()throws Exception{return call("GET",endpoint(),null,actor.token());}
    List<Map<String,Object>> rawRows(){return jdbc.queryForList("select id,template_id,field_code,label,datatype,required,sort_no,value_set,placeholder,unit,enabled,created_at from emr_template_field where template_id=?"+(include()?"":" and enabled=true")+" order by sort_no,id",template);}
    Wanted expected(){
        var users=jdbc.queryForList("select id,dept_id,enabled from sys_user where id=?",actor.id());
        var roles=jdbc.queryForList("select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?",String.class,actor.id());
        if(users.isEmpty()||!Boolean.TRUE.equals(users.getFirst().get("enabled"))||roles.stream().noneMatch(DUTIES::contains))return new Wanted(403,1005,null);
        var parents=jdbc.queryForList("select scope,owner_id,dept_id from emr_template where id=?",template);
        if(parents.isEmpty())return new Wanted(200,4066,null);
        var p=parents.getFirst();Long d=(Long)users.getFirst().get("dept_id");String scope=(String)p.get("scope");
        boolean granted=jdbc.queryForList("select grantee_type,grantee_id from emr_template_grant where template_id=?",template).stream().anyMatch(g->"USER".equals(g.get("grantee_type"))&&actor.id().equals(g.get("grantee_id"))||"DEPT".equals(g.get("grantee_type"))&&d!=null&&d.equals(g.get("grantee_id")));
        boolean visible=Set.of("GLOBAL","HOSPITAL").contains(scope)||actor.id().equals(p.get("owner_id"))||"DEPT".equals(scope)&&(roles.contains("ADMIN")||d!=null&&d.equals(p.get("dept_id")))||granted;
        if(!visible)return new Wanted(200,4066,null);
        return new Wanted(200,0,wire(rawRows().stream().map(this::dto).toList()));
    }
    Map<String,Object> dto(Map<String,Object> row){
        assertEquals(RAW,row.keySet());var out=new LinkedHashMap<String,Object>();
        for(String key:List.of("id","label","datatype","required","placeholder","unit","enabled"))out.put(key,row.get(key));
        out.put("templateId",row.get("template_id"));out.put("fieldCode",row.get("field_code"));out.put("sortNo",row.get("sort_no"));out.put("valueSet",valueSet((String)row.get("value_set")));return out;
    }
    List<String> valueSet(String value){if(value==null||value.isBlank())return List.of();try{var node=mapper.readTree(value);if(!node.isArray())return List.of();var out=new ArrayList<String>();node.forEach(n->{String v=n.asText();if(v!=null&&!v.isBlank())out.add(v.trim());});return List.copyOf(out);}catch(Exception e){return List.of();}}
    void mutation(String verb,String path,Object body,Staff who)throws Exception{var reply=call(verb,path,body,who.token());var event=new LinkedHashMap<String,Object>();event.put("method",verb);event.put("path",path);event.put("body",body);event.put("actor",who.name());event.put("http",reply.http());event.put("response",reply.body());maintenance.add(event);response(reply,200,0);if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())entityManager.flush();}
    void publicActor(String duty,Long department)throws Exception{var body=new LinkedHashMap<String,Object>();body.put("username",actor.name());body.put("realName",actor.name());body.put("deptId",department);body.put("roleCodes",duty.equals("NO_ROLES")?List.of():List.of(duty));mutation("PUT","/api/system/users/"+actor.id(),body,admin);}
    void publicField(boolean enabled)throws Exception{var parent=jdbc.queryForMap("select scope from emr_template where id=?",template);var body=new LinkedHashMap<String,Object>();body.put("fieldCode","IGNORED_UPDATE_CODE");body.put("label",newNonce+"_label");body.put("datatype","TEXT");body.put("required",false);body.put("sortNo",7);body.put("valueSet",List.of(newNonce+"_choice"));body.put("placeholder",newNonce+"_hint");body.put("unit","mm");body.put("enabled",enabled);mutation("PUT","/api/emr/fields/"+field,body,Set.of("GLOBAL","HOSPITAL").contains(parent.get("scope"))?admin:owner);}
    Audited[] committedEvents(){return maintenance.stream().map(e->new Audited((String)e.get("method"),(String)e.get("path"),200,(String)e.get("actor"))).toArray(Audited[]::new);}
    void positive(String label)throws Exception{var wanted=expected();var before=facts();var old=audit();frozen(label,read(),wanted,before,old,null);}
    static Stream<Arguments> matrix(){return Stream.of("GLOBAL","HOSPITAL","DEPT","PERSONAL").flatMap(s->DUTIES.stream().map(d->Arguments.of(s,d)));}
    @ParameterizedTest @MethodSource("matrix") void originalFourScopeAndFourRealDutyReadMatrix(String scope,String duty)throws Exception{configure(scope,false);roles(actor,duty);positive("matrix-"+scope+"-"+duty);}
    @ParameterizedTest @MethodSource("matrix") void originalOwnerRemainsVisibleForEveryFieldDutyAndScope(String scope,String duty)throws Exception{configure(scope,true);roles(actor,duty);positive("owner-"+scope+"-"+duty);}
    static Stream<Arguments> grants(){return Stream.of("DEPT","PERSONAL").flatMap(s->Stream.of("USER","DEPT").flatMap(g->DUTIES.stream().map(d->Arguments.of(s,g,d))));}
    @ParameterizedTest @MethodSource("grants") void userAndDepartmentGrantsAreAdditiveIncludingPersonalWithoutAdminBackdoor(String scope,String kind,String duty)throws Exception{configure(scope,false);roles(actor,duty);grant(kind,kind.equals("USER")?actor.id():otherDept);positive("grant-"+scope+"-"+kind+"-"+duty);}
    @ParameterizedTest @ValueSource(strings={"ADMIN","DOCTOR_OUTP","NURSE","QUALITY"}) void currentSameDepartmentStillReadsWithoutGrant(String duty)throws Exception{roles(actor,duty);jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());positive("same-dept-"+duty);}
    @ParameterizedTest @MethodSource("matrix") void disabledParentDoesNotRestrictHistoricalFieldDefinitionRead(String scope,String duty)throws Exception{configure(scope,true);roles(actor,duty);jdbc.update("update emr_template set enabled=false,template_type='RIS',record_type='PROGRESS' where id=?",template);positive("disabled-parent-"+scope+"-"+duty);}
    @ParameterizedTest @ValueSource(strings={"omitted","false","true","blank","yes","on","1"}) void includeDisabledOnlyControlsFieldsAndKeepsSortThenId(String flag)throws Exception{configure("PERSONAL",true);includeQuery=flag.equals("omitted")?null:flag.equals("blank")?"":flag;positive("include-"+flag);}
    @ParameterizedTest @ValueSource(strings={"none","all-disabled"}) void visibleNoFieldsAndAllInactiveFieldsRemainEmptyArrays(String kind)throws Exception{configure("PERSONAL",true);if(kind.equals("none"))jdbc.update("delete from emr_template_field where template_id=?",template);else jdbc.update("update emr_template_field set enabled=false where template_id=?",template);positive("empty-"+kind);}
    @ParameterizedTest @ValueSource(strings={"null","blank","malformed","object","array","scalar"}) void originalValueSetParserAndPrivateSentinelRemovalRemainCompatible(String kind)throws Exception{configure("PERSONAL",true);String value=switch(kind){case "null"->null;case "blank"->" ";case "malformed"->"[broken";case "object"->"{\"a\":1}";case "scalar"->"\"x\"";default->"[\" 甲 \",\" \",\"甲\",1,true,null,{}]";};jdbc.update("update emr_template_field set value_set=? where id=?",value,field);positive("value-set-"+kind);}
    @Test void missingParentUsesOriginalMerged4066()throws Exception{template=899999999999999L;positive("missing-parent");}
    @ParameterizedTest @CsvSource({"bad,omitted","valid,bad","valid,maybe"}) void malformedLongOrBooleanKeepsOriginal4000(String id,String flag)throws Exception{String path=PATH+(id.equals("bad")?"bad":template)+"/fields"+(flag.equals("omitted")?"":"?includeDisabled="+flag);var before=facts();var old=audit();frozen("bad-parameter-"+id+"-"+flag,call("GET",path,null,actor.token()),new Wanted(200,4000,null),before,old,null);}
    @Test void requestHintsCannotReplaceTheRealJwtDatabaseActor()throws Exception{configure("PERSONAL",false);String path=endpoint()+"?username="+admin.name()+"&userId="+admin.id()+"&admin=true&deptId="+dept;var before=facts();var old=audit();frozen("jwt-not-query-hints",call("GET",path,null,actor.token()),new Wanted(200,4066,null),before,old,null);}
    @ParameterizedTest @ValueSource(strings={"TECHNICIAN","CASHIER","INTERFACE","NO_ROLES","anonymous","disabled"}) void originalJwtAndFourDutyEntryGatesRemainUnchanged(String who)throws Exception{String token=actor.token();int http=403,code=1005;if(who.equals("anonymous")){token=null;http=401;code=1000;}else if(who.equals("disabled")){jdbc.update("update sys_user set enabled=false where id=?",actor.id());http=401;code=1000;}else roles(actor,who.equals("NO_ROLES")?new String[]{}:new String[]{who});var before=facts();var old=audit();frozen("entry-"+who,call("GET",endpoint(),null,token),new Wanted(http,code,null),before,old,null);}
    @ParameterizedTest @ValueSource(strings={"null-context","missing-db-admin","wrong-duty-context","valid-raw"}) void explicitInternalCallsKeepRealDatabaseAndContextRequirements(String kind){
        configure("PERSONAL",true);var before=facts();var old=audit();Object value=null;Throwable failure=null;
        if(!kind.equals("null-context")){String name=kind.equals("missing-db-admin")?tag+"missing":actor.name();String role=kind.equals("wrong-duty-context")?"TECHNICIAN":"ADMIN";SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(name,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))));}
        try{value=service.visibleFieldRows(template,false);}catch(Throwable e){failure=e;}finally{SecurityContextHolder.clearContext();}
        var result=new LinkedHashMap<String,Object>();result.put("case",kind);result.put("actual",wire(value));result.put("exception",failure==null?null:failure.getClass().getName());internalInvocations.add(result);evidence("internal-"+kind,before,facts(),old,null,null,null);
        if(kind.equals("valid-raw")){assertNull(failure);assertEquals(wire(rawRows()),wire(value));for(var row:(List<Map<String,Object>>)value){assertEquals(RAW,row.keySet());assertNotNull(row.get("created_at"));}}else assertInstanceOf(org.springframework.security.access.AccessDeniedException.class,failure);
        assertEquals(before,facts());assertEquals(old,audit());
    }
    @Test void publicTemplateFieldGrantAndRealJwtConsumeTheOriginalDto()throws Exception{var old=audit();mutation("POST","/api/emr-templates/scoped",Map.of("name",tag+"public","content","模拟正文","templateType","EMR","scope","DEPT","deptId",dept,"recordType","OUTP"),owner);Long id=((JsonNode)maintenance.getLast().get("response")).path("data").asLong();ownTemplates.add(id);template=id;mutation("POST",PATH+id+"/fields",Map.of("fieldCode","public_f","label","公开模拟字段","datatype","TEXT","required",false,"sortNo",1),owner);mutation("POST","/api/emr-templates/"+id+"/grants",Map.of("granteeType","USER","granteeId",actor.id()),owner);var before=facts();frozen("public-created",read(),expected(),before,old,null,committedEvents());}
    void prepare(String kind,String role){
        configure("DEPT",false);
        switch(kind){
            case "deptLoss","nullDept","emptyDeptLoss"->{roles(actor,role);jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());}
            case "deptGrantLoss"->{configure("PERSONAL",false);roles(actor,role);jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());grantId=grant("DEPT",dept);}
            case "adminLoss"->{roles(actor,"ADMIN");}
            case "entryDisabled","entryTech","entryNoRoles","emptyDutyLoss","emptyDisabled"->{configure("HOSPITAL",false);roles(actor,role);}
            case "gainDept","gainAdmin","emptyGrantGain"->{roles(actor,"DOCTOR_OUTP");}
            case "revokeUser","addUser","emptyRevokeUser"->{configure("PERSONAL",false);if(!kind.equals("addUser"))grantId=grant("USER",actor.id());}
            case "revokeDept"->{configure("PERSONAL",false);grantId=grant("DEPT",otherDept);}
            default->{jdbc.update("update sys_user set dept_id=? where id=?",dept,actor.id());}
        }
        if(kind.equals("enableField"))jdbc.update("update emr_template_field set enabled=false where id=?",field);
        if(kind.equals("enableParent"))jdbc.update("update emr_template set enabled=false where id=?",template);
        if(kind.startsWith("empty")&&!kind.equals("emptyGrantGain"))jdbc.update("delete from emr_template_field where template_id=?",template);
    }
    void change(String kind,String role)throws Exception{
        switch(kind){
            case "deptLoss","deptGrantLoss","adminLoss","nullDept"->{publicActor(role,kind.equals("nullDept")?null:otherDept);assertNotEquals(0,expected().code(),"Permission loss must precede first NEW publication");assertEquals(0,jdbc.queryForObject("select count(*) from emr_template_field where template_id=? and (label like ? or placeholder like ? or value_set like ?)",Integer.class,template,"%"+newNonce+"%","%"+newNonce+"%","%"+newNonce+"%"));publicField(true);}
            case "entryDisabled"->{mutation("PUT","/api/system/users/"+actor.id()+"/enabled?enabled=false",null,admin);assertEquals(1005,expected().code());publicField(true);}
            case "entryTech","entryNoRoles"->{publicActor(kind.equals("entryTech")?"TECHNICIAN":"NO_ROLES",otherDept);assertEquals(1005,expected().code());publicField(true);}
            case "gainDept"->{publicActor("DOCTOR_OUTP",dept);publicField(true);}
            case "gainAdmin"->{publicActor("ADMIN",otherDept);publicField(true);}
            case "revokeUser","revokeDept","emptyRevokeUser"->mutation("DELETE","/api/emr-templates/"+template+"/grants/"+grantId,null,owner);
            case "addUser","emptyGrantGain"->mutation("POST","/api/emr-templates/"+template+"/grants",Map.of("granteeType","USER","granteeId",actor.id()),owner);
            case "scopePersonal"->mutation("PUT","/api/emr-templates/"+template,Map.of("name",tag+"target","content","模拟父正文","templateType","EMR","scope","PERSONAL","recordType","OUTP"),owner);
            case "disableParent","enableParent"->mutation("PUT","/api/emr-templates/"+template+(kind.equals("disableParent")?"/disable":"/enable"),null,owner);
            case "disableField","enableField","fieldBody"->publicField(!kind.equals("disableField"));
            case "emptyDeptLoss"->publicActor("DOCTOR_OUTP",otherDept);
            case "emptyDutyLoss"->publicActor("TECHNICIAN",otherDept);
            case "emptyDisabled"->mutation("PUT","/api/system/users/"+actor.id()+"/enabled?enabled=false",null,admin);
            default->fail(kind);
        }
    }
    static Stream<Arguments> privateLosses(){return Stream.concat(Stream.of("DOCTOR_OUTP","NURSE","QUALITY").flatMap(r->Stream.of(Arguments.of("deptLoss",r),Arguments.of("adminLoss",r))),DUTIES.stream().map(r->Arguments.of("deptGrantLoss",r)));}
    @ParameterizedTest @MethodSource("privateLosses") void newPrivateFieldDefinitionCannotUseThePreviouslyReadDepartmentOrAdmin(String kind,String role)throws Exception{prepare(kind,role);window("private-loss-"+kind+"-"+role,kind,role,false,false);}
    @Test void currentNullDepartmentDoesNotFallBackToOldDepartment()throws Exception{prepare("nullDept","DOCTOR_OUTP");window("private-loss-null-dept","nullDept","DOCTOR_OUTP",false,false);}
    static Stream<Arguments> dutyLosses(){return DUTIES.stream().flatMap(r->Stream.of("entryDisabled","entryTech","entryNoRoles").map(k->Arguments.of(k,r)));}
    @ParameterizedTest @MethodSource("dutyLosses") void disabledOrNoLongerFourDutyEmployeeCannotReadNewFieldsAfterEntry(String kind,String role)throws Exception{prepare(kind,role);window("duty-loss-"+kind+"-"+role,kind,role,false,false);}
    @ParameterizedTest @ValueSource(strings={"gainDept","gainAdmin"}) void newlyGainedProfileUsesTheNewNonemptyDefinitionSnapshot(String kind)throws Exception{prepare(kind,"DOCTOR_OUTP");window("profile-gain-"+kind,kind,"DOCTOR_OUTP",false,false);}
    @ParameterizedTest @ValueSource(strings={"revokeUser","revokeDept","addUser","scopePersonal","disableParent","enableParent","disableField","enableField","fieldBody"}) void parentGrantAndDefinitionChangesAlreadyShareThePayloadStatement(String kind)throws Exception{prepare(kind,"DOCTOR_OUTP");window("compatible-before-"+kind,kind,"DOCTOR_OUTP",false,false);}
    @ParameterizedTest @ValueSource(strings={"deptLoss","adminLoss","entryDisabled","gainDept","revokeUser","scopePersonal","disableField"}) void realPublicRollbackLeavesOriginalFieldsAndAllOriginalFacts(String kind)throws Exception{prepare(kind,"DOCTOR_OUTP");window("rollback-"+kind,kind,"DOCTOR_OUTP",true,false);}
    @ParameterizedTest @ValueSource(strings={"deptLoss","adminLoss","entryDisabled","entryTech","revokeUser","scopePersonal","fieldBody"}) void alreadyExecutedAuthorizedStatementMayReturnItsCompleteOldDefinition(String kind)throws Exception{prepare(kind,"DOCTOR_OUTP");window("authorized-after-"+kind,kind,"DOCTOR_OUTP",false,true);}
    @ParameterizedTest @ValueSource(strings={"emptyRevokeUser","emptyDeptLoss","emptyDutyLoss","emptyDisabled","emptyGrantGain"}) void emptyOutcomeAndParentOrActorStatusBelongToTheSameExecutedSnapshot(String kind)throws Exception{prepare(kind,"DOCTOR_OUTP");window("empty-after-"+kind,kind,"DOCTOR_OUTP",false,true);}
    void window(String label,String kind,String role,boolean rollback,boolean after)throws Exception{
        Wanted wanted=expected();var initial=facts();var old=audit();var gap=new Gap(template,after);var pool=Executors.newSingleThreadExecutor();
        try{var worker=pool.submit(()->tracedRead(gap));gap.awaitBoundary();
            if(rollback){var tx=new TransactionTemplate(transactions);tx.setTimeout(20);tx.executeWithoutResult(status->{try{change(kind,role);entityManager.flush();entityManager.clear();status.setRollbackOnly();}catch(Exception e){throw new RuntimeException(e);}});assertEquals(initial,facts());}
            else{change(kind,role);if(!after)wanted=expected();}
            var committed=facts();gap.release();Reply reply=finish(worker);frozen(label,reply,wanted,committed,old,gap,rollback?new Audited[]{}:committedEvents());
            if(label.startsWith("private-loss-")||label.startsWith("duty-loss-"))assertFalse(reply.body().toString().contains(newNonce),"NEW did not exist until this reader had lost eligibility; not a lawful old snapshot/cache");
        }finally{gap.release();close(pool);}
    }
    Reply tracedRead(Gap gap)throws Exception{Gap.TRACE.set(gap);try{return read();}finally{Gap.TRACE.remove();}}
    Reply finish(Future<Reply> worker)throws Exception{try{return worker.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Unexpected fixture/request exception",e.getCause());}}
    void close(ExecutorService pool)throws Exception{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
    static final class Gap{
        static final ThreadLocal<Gap> TRACE=new ThreadLocal<>();final Long id;final boolean after;final CountDownLatch reached=new CountDownLatch(1),resume=new CountDownLatch(1);final AtomicInteger hits=new AtomicInteger();final List<Map<String,Object>> statements=new ArrayList<>();volatile Map<String,Object> boundary;
        Gap(Long id,boolean after){this.id=id;this.after=after;}
        boolean payload(String sql,Map<Integer,Object> params){String n=sql.replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);return params.containsValue(id)&&(n.contains("from emr_template_field f join emr_template t")||n.contains("__field_actor_allowed")&&n.contains("field_read_parent"));}
        void observe(String sql,Map<Integer,Object> params,boolean done,int fetchSize){String n=sql.replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);if(!n.contains("emr_template")&&!n.contains("select u.id, u.dept_id, u.enabled, r.code from sys_user u"))return;var event=new LinkedHashMap<String,Object>();event.put("sql",sql);event.put("parameters",new TreeMap<>(params));event.put("position",done?"AFTER":"BEFORE");event.put("time",Instant.now().toString());event.put("threadName",Thread.currentThread().getName());event.put("threadId",Thread.currentThread().threadId());event.put("fetchSize",fetchSize);event.put("statementOrdinal",statements.size()+1);statements.add(event);if(payload(sql,params)&&done==after){assertEquals(1,hits.incrementAndGet());assertEquals(0,fetchSize);assertEquals(1,statements.stream().filter(v->v.get("position").equals("AFTER")&&((String)v.get("sql")).replaceAll("\\s+"," ").toLowerCase(Locale.ROOT).contains("select u.id, u.dept_id, u.enabled, r.code from sys_user u")).count(),"Original fieldActor must complete before this payload boundary");boundary=new LinkedHashMap<>(event);reached.countDown();try{assertTrue(resume.await(20,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}}
        void awaitBoundary()throws InterruptedException{assertTrue(reached.await(15,TimeUnit.SECONDS),"Exact field payload boundary missing");assertEquals(1,hits.get());System.out.println("V150 JDBC_BOUNDARY after="+after+" proof="+boundary+" pgLockWaitClaim=false");}
        void release(){resume.countDown();}
        Map<String,Object> proof(){return Map.of("kind","test-only exact field JDBC executeQuery boundary","postgresLockWaitClaim",false,"after",after,"hits",hits.get(),"boundary",boundary,"statements",List.copyOf(statements));}
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
            if (verb.equals("GET") && path.contains("?")) {
                request = get(path.substring(0,path.indexOf('?')));
                for (String part : path.substring(path.indexOf('?') + 1).split("&")) {
                    String[] pair = part.split("=", 2);
                    request.queryParam(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8));
                }
            }
            if (payload != null) request.contentType("application/json").content(mapper.writeValueAsString(payload));
            if (token != null) request.header("Authorization", "Bearer " + token);
            var response = mvc.perform(request).andReturn().getResponse();
            return new Reply(response.getStatus(), mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8)), path);
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

    String auditPath(String path){String[] parts=path.split("\\?",2);if(parts.length==1)return path;String qs=parts[1].replaceAll("(\\d{4})\\d{7,10}(\\d{3,4})","$1****$2").replaceAll("(?<!\\d)(1\\d{2})\\d{4}(\\d{4})(?!\\d)","$1****$2");String value=parts[0]+"?"+qs;return value.length()>255?value.substring(0,255):value;}
    void evidence(String label,Map<String,List<String>> before,Map<String,List<String>> after,List<Map<String,Object>> old,Reply reply,Wanted wanted,Gap gap){String folder=System.getProperty("hip.v150.evidenceDir");if(folder==null)return;try{Path directory=Path.of(folder).toAbsolutePath().normalize();assertFalse(directory.toString().toLowerCase(Locale.ROOT).contains("onedrive"));Files.createDirectories(directory);var value=new LinkedHashMap<String,Object>();value.put("label",label);value.put("tag",tag);value.put("kind",reply==null?"explicit-internal-service":"real-JWT-HTTP");value.put("http",reply==null?null:reply.http());value.put("path",reply==null?null:reply.path());value.put("response",reply==null?null:reply.body());value.put("expectedHttp",wanted==null?null:wanted.http());value.put("expectedCode",wanted==null?null:wanted.code());value.put("expectedData",wanted==null?null:wanted.data());value.put("before",before);value.put("after",after);value.put("oldAudit",old);value.put("currentAudit",audit());value.put("publicMaintenance",maintenance);value.put("internalInvocations",internalInvocations);value.put("oldNonce",oldNonce);value.put("newNonce",newNonce);if(gap!=null)value.put("jdbcBoundary",gap.proof());Files.writeString(directory.resolve(tag+"-"+(++artifact)+".json"),mapper.writeValueAsString(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new AssertionError("Cannot preserve complete original case",e);}}
    void frozen(String label,Reply reply,Wanted wanted,Map<String,List<String>> before,List<Map<String,Object>> old,Gap gap,Audited... extra){var after=facts();evidence(label,before,after,old,reply,wanted,gap);var events=new ArrayList<>(Arrays.asList(extra));events.add(new Audited("GET",auditPath(reply.path()),wanted.http(),wanted.http()==401?null:actor.name()));System.out.println("V150 CASE "+label+" actual="+reply.http()+"/"+reply.code()+" expected="+wanted.http()+"/"+wanted.code()+" allFactsFrozen="+before.equals(after));assertAll(()->{response(reply,wanted.http(),wanted.code());if(wanted.code()==0){assertEquals(wanted.data(),reply.body().path("data"),"Field DTO and empty/status outcome must use one actor/parent/definition snapshot");for(JsonNode row:reply.body().path("data")){var keys=new HashSet<String>();row.fieldNames().forEachRemaining(keys::add);assertEquals(DTO,keys);}}},()->assertEquals(before,after,"Read cannot mutate any monitored business/auth/config column"),()->audited(old,events.toArray(Audited[]::new)));}
    @TestConfiguration(proxyBeanMethods = false) static class JdbcConfiguration {
        @Bean @Primary JdbcTemplate v150BoundaryJdbcTemplate(DataSource actual) { return new JdbcTemplate(new BoundaryDataSource(actual)); }
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
                if (query && trace != null) { trace.observe(sql, parameters, true, actual.getFetchSize());  } return value;
            });
        }
        static Object invoke(Object actual, Method method, Object[] args) throws Throwable { try { return method.invoke(actual, args); } catch (InvocationTargetException e) { throw e.getCause(); } }
    }
    @AfterEach void cleanup() {
        Gap.TRACE.remove(); SecurityContextHolder.clearContext();
        for (Long id : ownTemplates) { jdbc.update("delete from emr_template_field where template_id=?", id); jdbc.update("delete from emr_template_grant where template_id=?", id); jdbc.update("delete from emr_template where id=?", id); }
        for (var who : people) { jdbc.update("delete from sys_user_role where user_id=?", who.id()); jdbc.update("delete from sys_user where id=?", who.id()); }
        if (dept != null) jdbc.update("delete from sys_dept where id in (?,?)", dept, otherDept);
    }
}
