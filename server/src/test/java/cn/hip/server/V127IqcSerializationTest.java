package cn.hip.server;

import cn.hip.medtech.web.LisQcController;
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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Independently committing JWT requests and explicit PostgreSQL holders; no outer test transaction.
 * Holders may compose the real qc service in a controlled transaction. The competitor is an ordinary
 * JWT request. Old INSERT/operator FK waits and new advisory waits are distinguished by actual query.
 * A second probe proves the request owns its series lock across an unrelated INSERT FK wait, so an
 * advisory call without @Transactional cannot pass merely by briefly acquiring a lock. */
@SpringBootTest
@AutoConfigureMockMvc
class V127IqcSerializationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired LisQcController controller;
    String tag,item,admin,tech;
    Long dept,otherDept,adminId,techId;
    final List<Long> employees=new ArrayList<>();
    static final Set<String> COLUMNS=Set.of("id","item_code","level","lot_no","target_value","sd","measured_value","z_score","rule_broken","in_control","operator_id","measured_at");
    record Outcome(int http,JsonNode body) {int code(){return body.get("code").asInt();} JsonNode data(){return body.get("data");}}

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();tag="V127"+Long.toUnsignedString(System.nanoTime(),36);item=tag+"Q";
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag,tag);
        otherDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag+"D",tag+"跨科");
        admin=staff("ADMIN");tech=staff("TECHNICIAN");adminId=id("ADMIN");techId=id("TECHNICIAN");
        jdbc.update("insert into lab_qc_record(item_code,level,lot_no,target_value,sd,measured_value,z_score,rule_broken,in_control,operator_id) values (?,'H','历史原批号',0,1,1,1,null,true,?)",tag+"H",adminId);
    }
    String staff(String role) {
        String name=tag+role;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,name,name,role.equals("ADMIN")?dept:otherDept);
        employees.add(id);jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);return jwt.issue(name);
    }
    Long id(String role){return jdbc.queryForObject("select id from sys_user where username=?",Long.class,tag+role);}
    String body(String code,String level,String lot,String value) throws Exception {
        return mapper.writeValueAsString(Map.of("itemCode",code,"level",level,"lotNo",lot,"targetValue",BigDecimal.ZERO,"sd",BigDecimal.ONE,"measuredValue",new BigDecimal(value)));
    }
    Outcome request(String token,String body) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=post("/api/lis/qc").contentType("application/json").content(body);if(token!=null)req.header("Authorization","Bearer "+token);
            var res=mvc.perform(req).andReturn().getResponse();return new Outcome(res.getStatus(),mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8)));
        } finally{SecurityContextHolder.clearContext();}
    }
    Outcome point(String token,String code,String level,String lot,String value) throws Exception{return request(token,body(code,level,lot,value));}
    Set<String> keys(JsonNode node){var keys=new HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    void result(Outcome response,int http,int code,String rule,boolean controlled,double z) {
        System.out.println("V127 RESPONSE expected="+code+" actual="+response.code()+" data="+response.data());
        assertAll(()->assertEquals(http,response.http()),()->assertEquals(code,response.code(),response.body().toString()));
        if(code==0){assertEquals(Set.of("zScore","inControl","rule"),keys(response.data()));assertEquals(rule,response.data().get("rule").asText());assertEquals(controlled,response.data().get("inControl").asBoolean());assertEquals(z,response.data().get("zScore").asDouble());}
        else assertTrue(response.data().isNull());
    }
    List<Map<String,Object>> rows(){return jdbc.queryForList("select * from lab_qc_record where item_code like ? order by id",tag+"%");}
    List<Map<String,Object>> own(String code,String level){return jdbc.queryForList("select * from lab_qc_record where item_code=? and level=? order by id",code,level);}
    Object otherFacts(){return List.of(jdbc.queryForList("select * from empi_patient order by id"),jdbc.queryForList("select * from outp_registration order by id"),jdbc.queryForList("select * from outp_order order by id"),jdbc.queryForList("select * from outp_charge order by id"),jdbc.queryForList("select * from lis_sample order by id"),jdbc.queryForList("select * from outp_lab_result order by id"),jdbc.queryForList("select * from lab_micro_result order by id"),jdbc.queryForList("select * from lab_micro_ast order by id"),jdbc.queryForList("select * from cdr_document order by id"),jdbc.queryForList("select * from outp_critical_alert order by id"));}
    Instant instant(Object o){return o instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)o).toInstant();}
    Instant clock(){return instant(jdbc.queryForObject("select clock_timestamp()",Object.class));}
    void decimal(Object actual,String value){assertEquals(0,((BigDecimal)actual).compareTo(new BigDecimal(value)));}
    void stored(Map<String,Object> row,String code,String level,String lot,String value,String rule,boolean controlled,Long actor,Instant lower) {
        assertEquals(COLUMNS,row.keySet());assertTrue(((Number)row.get("id")).longValue()>0);assertEquals(code,row.get("item_code"));assertEquals(level,row.get("level"));assertEquals(lot,row.get("lot_no"));
        decimal(row.get("target_value"),"0");decimal(row.get("sd"),"1");decimal(row.get("measured_value"),value);decimal(row.get("z_score"),value);
        assertEquals(rule,row.get("rule_broken"));assertEquals(controlled,row.get("in_control"));assertEquals(actor,((Number)row.get("operator_id")).longValue());
        Instant time=instant(row.get("measured_at"));assertFalse(time.isBefore(lower));assertFalse(time.isAfter(clock()));
    }
    void unchangedRefusal(String token,String body,int http,int expected) throws Exception {
        var before=rows();var other=otherFacts();var out=request(token,body);result(out,http,expected,null,false,0);assertEquals(before,rows());assertEquals(other,otherFacts());
    }
    TransactionTemplate tx(){var tx=new TransactionTemplate(manager);tx.setTimeout(25);return tx;}
    void lockSeries(String code,String level){jdbc.query("select pg_advisory_xact_lock(hashtextextended(jsonb_build_array('hip:medtech:iqc', ?::text, ?::text)::text, 0))",(ResultSetExtractor<Void>)rs->null,code,level);}
    void actor(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(tag+role,null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));}
    void servicePoint(String code,String level,String lot,String value) {
        actor("ADMIN");try{assertEquals(0,controller.qc(new LisQcController.QcReq(code,level,lot,BigDecimal.ZERO,BigDecimal.ONE,new BigDecimal(value)),SecurityContextHolder.getContext().getAuthentication()).getCode());}finally{SecurityContextHolder.clearContext();}
    }
    int waitForHolder(int holder,Future<?> request,String stage) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline){
            jdbc.execute("select pg_stat_clear_snapshot()");var waits=jdbc.queryForList("""
                    select pid,wait_event_type,pg_blocking_pids(pid) as blockers,query from pg_stat_activity
                    where pid<>pg_backend_pid() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid))
                      and (lower(query) like '%insert into lab_qc_record%' or lower(query) like '%pg_advisory_xact_lock%')
                    """,holder);
            if(!waits.isEmpty()){System.out.println("V127 PG_LOCK stage="+stage+" holder="+holder+" waiters="+waits);return ((Number)waits.getFirst().get("pid")).intValue();}
            if(request.isDone())fail("JWT QC completed before required named-holder Lock at "+stage);
            try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        }return fail("Named-holder Lock was not observed at "+stage);
    }
    boolean completedOrPeerWait(int holder,Future<?> request,String stage) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline){
            jdbc.execute("select pg_stat_clear_snapshot()");var waits=jdbc.queryForList("select pid,pg_blocking_pids(pid) as blockers,query from pg_stat_activity where wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and lower(query) like '%pg_advisory_xact_lock%'",holder);
            if(!waits.isEmpty()){System.out.println("V127 PG_LOCK stage="+stage+" holder="+holder+" waiters="+waits);return true;}
            if(request.isDone()){System.out.println("V127 PEER_COMPLETED_WITHOUT_LOCK stage="+stage);return false;}
            try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        }return fail("Peer neither completed nor waited on the named request backend");
    }
    Outcome finish(Future<Outcome> f) throws Exception {try{return f.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Expected a real HTTP outcome",e.getCause());}}
    void close(ExecutorService pool) throws Exception{pool.shutdown();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();if(tag==null)return;tx().executeWithoutResult(t->{jdbc.update("delete from lab_qc_record where item_code like ?",tag+"%");for(Long id:employees){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}jdbc.update("delete from sys_dept where id in (?,?)",dept,otherDept);});employees.clear();}

    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE"})
    void originalFourDutiesKeepExactRawTwelveColumnAppend(String role) throws Exception {
        String token=role.equals("ADMIN")?admin:role.equals("TECHNICIAN")?tech:staff(role);var before=rows();var other=otherFacts();Instant lower=clock();
        String code=item+" ",level=" L1 ",lot=" 原批号 ";result(point(token,code,level,lot,"1.125"),200,0,"",true,1.125);
        var after=rows();assertEquals(before,after.subList(0,before.size()));assertEquals(before.size()+1,after.size());stored(after.getLast(),code,level,lot,"1.125",null,true,id(role),lower);assertEquals(other,otherFacts());
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","PHARMACIST","INTERFACE","PORTAL"})
    void wrongDomainsCannotWriteQc(String role) throws Exception {unchangedRefusal(role.equals("PORTAL")?jwt.issue("portal:1"):staff(role),body(item,"L1","B","2.5"),403,1005);}
    @Test void anonymousCannotInheritPriorAuthentication() throws Exception {result(point(admin,item,"L1","A","1"),200,0,"",true,1);unchangedRefusal(null,body(item,"L1","B","2.5"),401,1000);}
    @Test void initiallyDisabledJwtRemains401() throws Exception{jdbc.update("update sys_user set enabled=false where id=?",techId);unchangedRefusal(tech,body(item,"L1","B","2.5"),401,1000);}
    @ParameterizedTest @ValueSource(strings={"itemCode","level","lotNo","targetValue","measuredValue"})
    void missingFieldsKeep7109(String field) throws Exception {var data=mapper.readTree(body(item,"L1","B","2.5"));((com.fasterxml.jackson.databind.node.ObjectNode)data).putNull(field);unchangedRefusal(admin,mapper.writeValueAsString(data),200,7109);}
    @ParameterizedTest @ValueSource(strings={"itemCode","level","lotNo"})
    void blankTextFieldsKeep7109(String field) throws Exception{var data=mapper.readTree(body(item,"L1","B","2.5"));((com.fasterxml.jackson.databind.node.ObjectNode)data).put(field,"  ");unchangedRefusal(admin,mapper.writeValueAsString(data),200,7109);}
    @Test void nullSdKeeps7110() throws Exception{var data=mapper.readTree(body(item,"L1","B","2.5"));((com.fasterxml.jackson.databind.node.ObjectNode)data).putNull("sd");unchangedRefusal(admin,mapper.writeValueAsString(data),200,7110);}
    @ParameterizedTest @ValueSource(strings={"0","-1"})
    void nonpositiveSdKeeps7110(String sd) throws Exception{var data=mapper.readTree(body(item,"L1","B","2.5"));((com.fasterxml.jackson.databind.node.ObjectNode)data).put("sd",new BigDecimal(sd));unchangedRefusal(admin,mapper.writeValueAsString(data),200,7110);}
    @ParameterizedTest @CsvSource({"0,'',true","2,'',true","-2,'',true","3,1-2s(警告),true","-3,1-2s(警告),true","3.001,1-3s,false","-3.001,1-3s,false"})
    void strictOriginalThresholdsAndResponseKeysRemain(String value,String rule,boolean control) throws Exception {
        var before=rows();Instant lower=clock();result(point(admin,item,"L1","A",value),200,0,rule,control,Double.parseDouble(value));var after=rows();assertEquals(before,after.subList(0,before.size()));stored(after.getLast(),item,"L1","A",value,rule.isEmpty()?null:rule,control,adminId,lower);
    }
    @ParameterizedTest @CsvSource({"2.5,2.5,2-2s,false","-2.5,-2.5,2-2s,false","-2.5,2.5,1-2s(警告),true","2.5,-2.5,1-2s(警告),true","4,2.5,2-2s,false","2.5,4,1-3s,false"})
    void originalSameSideLatestIdAndCrossLotSemanticsRemain(String prior,String current,String rule,boolean control) throws Exception{
        result(point(admin,item,"L1","原批号",prior),200,0,Math.abs(Double.parseDouble(prior))>3?"1-3s":"1-2s(警告)",Math.abs(Double.parseDouble(prior))<=3,Double.parseDouble(prior));
        var first=own(item,"L1").getFirst();result(point(tech,item,"L1","另一批号",current),200,0,rule,control,Double.parseDouble(current));assertEquals(first,own(item,"L1").getFirst());assertEquals(2,own(item,"L1").size());
    }
    @Test void roundedStorageStillControlsTheOriginalNextThreshold() throws Exception {
        result(point(admin,item,"L1","A","2.0004"),200,0,"1-2s(警告)",true,2);decimal(own(item,"L1").getFirst().get("z_score"),"2.000");result(point(admin,item,"L1","B","2.5"),200,0,"1-2s(警告)",true,2.5);
    }
    @Test void repeatedCallsAppendRatherThanDeduplicate() throws Exception{result(point(admin,item,"L1","A","1"),200,0,"",true,1);var first=own(item,"L1").getFirst();result(point(admin,item,"L1","A","1"),200,0,"",true,1);assertEquals(2,own(item,"L1").size());assertEquals(first,own(item,"L1").getFirst());}

    @ParameterizedTest @ValueSource(strings={"2.5","-2.5"})
    void committedRealQcPredecessorMustBeReadAfterNamedHolderWait(String value) throws Exception {
        var before=rows();var other=otherFacts();Instant lower=clock();var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();String requestBody=body(item,"L1","B",value);
        try{
            tx().executeWithoutResult(t->{lockSeries(item,"L1");jdbc.queryForList("select id from sys_user where id=? for update",techId);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(tech,requestBody)));waitForHolder(holder,future.getFirst(),"predecessor-commit-"+value);servicePoint(item,"L1","A",value);});
            var response=finish(future.getFirst());assertAll(()->result(response,200,0,"2-2s",false,Double.parseDouble(value)),()->assertEquals(other,otherFacts()));
            assertEquals(before.size()+2,rows().size());var row=own(item,"L1").stream().filter(x->x.get("lot_no").equals("B")).findFirst().orElseThrow();stored(row,item,"L1","B",value,"2-2s",false,techId,lower);
        }finally{close(pool);}
    }
    @Test void rolledBackRealPredecessorLeavesNoQcAndReleasesItsLock() throws Exception {
        var before=rows();var other=otherFacts();Instant lower=clock();var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();String b=body(item,"L1","B","2.5");
        try{tx().executeWithoutResult(t->{lockSeries(item,"L1");jdbc.queryForList("select id from sys_user where id=? for update",techId);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(tech,b)));waitForHolder(holder,future.getFirst(),"predecessor-rollback");servicePoint(item,"L1","ROLLED_BACK","2.5");t.setRollbackOnly();});
            result(finish(future.getFirst()),200,0,"1-2s(警告)",true,2.5);assertEquals(before.size()+1,rows().size());stored(own(item,"L1").getFirst(),item,"L1","B","2.5","1-2s(警告)",true,techId,lower);assertEquals(other,otherFacts());
            result(point(admin,item,"L1","RETRY","2.5"),200,0,"2-2s",false,2.5);
        }finally{close(pool);}
    }
    @ParameterizedTest @ValueSource(strings={"0","-2.5"})
    void committedNormalOrOppositePointResetsThePendingDecision(String first) throws Exception {
        result(point(admin,item,"L1","SEED","2.5"),200,0,"1-2s(警告)",true,2.5);var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();String b=body(item,"L1","B","2.5");
        try{tx().executeWithoutResult(t->{lockSeries(item,"L1");jdbc.queryForList("select id from sys_user where id=? for update",techId);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(tech,b)));waitForHolder(holder,future.getFirst(),"predecessor-reset-"+first);servicePoint(item,"L1","RESET",first);});result(finish(future.getFirst()),200,0,"1-2s(警告)",true,2.5);
        }finally{close(pool);}
    }
    @ParameterizedTest @ValueSource(strings={"disable","revoke"})
    void employeeChangedDuringNamedWaitCannotUseOldAuthority(String kind) throws Exception {
        var before=rows();var other=otherFacts();var pool=Executors.newSingleThreadExecutor();var future=new ArrayList<Future<Outcome>>();String b=body(item,"L1","B","0");
        try{tx().executeWithoutResult(t->{lockSeries(item,"L1");jdbc.queryForList("select id from sys_user where id=? for update",techId);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(tech,b)));waitForHolder(holder,future.getFirst(),"employee-"+kind);if(kind.equals("disable"))jdbc.update("update sys_user set enabled=false where id=?",techId);else jdbc.update("delete from sys_user_role where user_id=?",techId);});
            var response=finish(future.getFirst());assertAll(()->result(response,403,1005,null,false,0),()->assertEquals(before,rows()),()->assertEquals(other,otherFacts()));
        }finally{close(pool);}
    }
    @ParameterizedTest @CsvSource({"2.5,2-2s","0,1-2s(警告)","4,2-2s"})
    void seriesLockMustCoverReadAndInsertEvenAcrossForeignKeyWait(String first,String expectedSecond) throws Exception {
        if(first.equals("0"))result(point(admin,item,"L1","SEED","2.5"),200,0,"1-2s(警告)",true,2.5);
        var other=otherFacts();var pool=Executors.newFixedThreadPool(2);var future=new ArrayList<Future<Outcome>>();String b=body(item,"L1","B",first),c=body(item,"L1","C","2.5");final boolean[] peerWait={false};
        try{
            tx().executeWithoutResult(t->{jdbc.queryForList("select id from sys_user where id=? for update",techId);int holder=jdbc.queryForObject("select pg_backend_pid()",Integer.class);future.add(pool.submit(()->request(tech,b)));int bPid=waitForHolder(holder,future.getFirst(),"lifetime-first-"+first);future.add(pool.submit(()->request(admin,c)));peerWait[0]=completedOrPeerWait(bPid,future.get(1),"lifetime-peer-"+first);});
            var firstResponse=finish(future.get(0));var secondResponse=finish(future.get(1));String firstRule=first.equals("0")?"":first.equals("4")?"1-3s":"1-2s(警告)";
            result(firstResponse,200,0,firstRule,!first.equals("4"),Double.parseDouble(first));
            assertAll(()->result(secondResponse,200,0,expectedSecond,expectedSecond.startsWith("1-2s"),2.5),()->assertTrue(peerWait[0],"A passing classification must also prove C actually waited on B's held transaction lock"),()->assertEquals(other,otherFacts()));
            var row=own(item,"L1").stream().filter(x->x.get("lot_no").equals("C")).findFirst().orElseThrow();assertEquals(expectedSecond,row.get("rule_broken"));
        }finally{close(pool);}
    }
    @ParameterizedTest @CsvSource({"OTHER,L1","SAME,L2","DELIMITER,A:B"})
    void aDifferentExactItemLevelDoesNotWaitOnTheHeldSeries(String variant,String level) throws Exception {
        String heldCode=variant.equals("DELIMITER")?item+":A":item;
        String heldLevel=variant.equals("DELIMITER")?"B":"L1";
        String code=variant.equals("OTHER")?item+"X":item;String b=body(code,level,"B","2.5");var pool=Executors.newSingleThreadExecutor();
        try{tx().executeWithoutResult(t->{lockSeries(heldCode,heldLevel);try{result(pool.submit(()->request(tech,b)).get(8,TimeUnit.SECONDS),200,0,"1-2s(警告)",true,2.5);}catch(Exception e){fail("Independent tuple must complete while the other key is held",e);}});}finally{close(pool);}
    }
    @Test void insertFailureRollsBackAndSameSeriesCanRetry() throws Exception {
        var before=rows();var other=otherFacts();var failed=point(admin,item,"L1","X".repeat(33),"2.5");result(failed,200,4091,null,false,0);assertEquals(before,rows());assertEquals(other,otherFacts());
        result(point(tech,item,"L1","RETRY","2.5"),200,0,"1-2s(警告)",true,2.5);
    }
}
