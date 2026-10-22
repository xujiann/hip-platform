package cn.hip.server;

import cn.hip.medtech.web.PathologyController;
import cn.hip.medtech.web.PathologyRegistryController;
import cn.hip.medtech.web.PathologyProcessController;
import cn.hip.medtech.web.PathologyProcessController.*;
import cn.hip.medtech.web.PathologyReportController;
import cn.hip.medtech.web.PathologyReportController.*;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.service.ConfigReader;
import cn.hip.outpatient.repository.OutpOrderRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

/** Committed real PostgreSQL transactions, no outer test transaction. Simulated controller contexts
 * do not claim JWT coverage. pg_stat_activity proves lock competition; NOWAIT distinguishes strong
 * source locks from FK KEY SHARE. Managed-entity flush cases are explicit service compositions.
 * No production source or prior test changes are needed for the old-code RED baseline. */
@SpringBootTest
class V118PathologyProcessingRaceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired PathologyProcessController process;
    @Autowired PathologyReportController report;
    @Autowired PathologyRegistryController registry;
    @Autowired PathologyController legacy;
    @Autowired ConfigReader config;
    @Autowired OutpOrderRepository orders;
    @Autowired EntityManager em;
    record Staff(Long id,String name,String role) {}
    List<Staff> staff=new ArrayList<>();
    List<Long> specimens=new ArrayList<>(),sourceOrders=new ArrayList<>();
    String tag,gate; Long dept,patient,schedule,registration,order,specimen,block,slide,techOrder;
    Long admission,bed,inpOrder; Staff tech,doctor;
    @BeforeEach void fixture(){
        SecurityContextHolder.clearContext();tag="P118"+Long.toUnsignedString(System.nanoTime(),36);
        gate=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='emr.gate.pathology.techdone'",String.class);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        tech=employee("t","TECHNICIAN");doctor=employee("d","DOCTOR_OUTP");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag);
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,doctor_id,visit_date,fee,status) values (1,?,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept,doctor.id());
        order=newOrder();specimen=newSpecimen(order,null);authenticate(tech);setGate("off");
    }
    Staff employee(String suffix,String role){String name=tag+suffix;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id",Long.class,name,name,dept);jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);var s=new Staff(id,name,role);staff.add(s);return s;}
    Long newOrder(){Long id=jdbc.queryForObject("insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id,remark) values (?,?,'TREAT',0,?,'病理实物','次',1,7.50,7.50,'CHARGED',?,'保留原备注') returning id",Long.class,registration,tag+sourceOrders.size(),tag,doctor.id());sourceOrders.add(id);return id;}
    Long newSpecimen(Long outp,Long inp){Long id=jdbc.queryForObject("insert into path_specimen(order_id,inp_order_id,part_no,barcode,specimen_desc,status,received_at,gross_finding,micro_finding) values (?,?,?,?,?,'RECEIVED',now(),'原大体','原镜下') returning id",Long.class,outp,inp,specimens.size()+1,tag+"s"+specimens.size(),"真实组织");specimens.add(id);return id;}
    Long newBlock(Long sid,boolean embedded){int n=jdbc.queryForObject("select coalesce(max(block_no),0)+1 from path_block where specimen_id=?",Integer.class,sid);return jdbc.queryForObject("insert into path_block(specimen_id,block_no,block_code,tissue_desc,created_by,embedded_at,embedded_by) values (?,?,?,'原组织',?,case when ? then now() else null end,case when ? then ? else null end) returning id",Long.class,sid,n,tag+"b"+sid+"n"+n,tech.id(),embedded,embedded,tech.id());}
    Long newSlide(Long bid,Long tid){int n=jdbc.queryForObject("select coalesce(max(slide_no),0)+1 from path_slide where block_id=?",Integer.class,bid);return jdbc.queryForObject("insert into path_slide(block_id,slide_no,slide_code,stain_type,tech_order_id) values (?,?,?,'HE',?) returning id",Long.class,bid,n,tag+"l"+bid+"n"+n,tid);}
    Long newTech(Long sid,Long bid,String type){return jdbc.queryForObject("insert into path_tech_order(specimen_id,block_id,tech_type,reason,ordered_by) values (?,?,?,'原下达原因',?) returning id",Long.class,sid,bid,type,doctor.id());}
    Authentication auth(){return SecurityContextHolder.getContext().getAuthentication();}
    Authentication token(Staff s){return new UsernamePasswordAuthenticationToken(s.name(),null,List.of(new SimpleGrantedAuthority("ROLE_"+s.role())));}
    void authenticate(Staff s){if(s==null)SecurityContextHolder.clearContext();else SecurityContextHolder.getContext().setAuthentication(token(s));}
    int attempt(IntSupplier s){try{return s.getAsInt();}catch(HipBizException e){return e.code;}catch(AccessDeniedException|AuthenticationCredentialsNotFoundException e){return 1005;}}
    TransactionTemplate tx(){var t=new TransactionTemplate(manager);t.setTimeout(20);return t;}
    void setGate(String value){assertEquals(1,jdbc.update("update sys_config set cfg_value=? where cfg_key='emr.gate.pathology.techdone'",value));config.evictAll();}
    void ready(String op){
        if(List.of("revise").contains(op))return;
        if(List.of("dehydrate","embed","slides","stain","batch","done","cancel").contains(op))block=newBlock(specimen,List.of("slides","stain","batch","done","cancel").contains(op));
        if(List.of("stain","batch").contains(op))slide=newSlide(block,null);
        if(List.of("done","cancel").contains(op))techOrder=newTech(specimen,block,"DEEP_CUT");
    }
    R<?> response(String op,Authentication supplied){return switch(op){
        case "gross"->process.grossing(new GrossingReq(specimen,null,null,null,false,"原取材",List.of(new BlockReq("原组织"))),supplied);
        case "revise"->process.reviseGrossFields(specimen,new GrossReviseReq(null,null,"修订大体"),supplied);
        case "dehydrate"->process.dehydrateBatch(new DehydrateBatchReq(tag+"篮",List.of(block),"原分篮"),supplied);
        case "embed"->process.embed(block,"原包埋",supplied);
        case "slides"->process.slides(new SlideReq(block,1,"HE",null,"原切片"),supplied);
        case "stain"->process.stain(slide,new StainReq("GOOD",null,"原染色"),supplied);
        case "batch"->process.batchComplete(new BatchCompleteReq(List.of(slide),"GOOD",null,"原批染"),supplied);
        case "create"->report.createTechOrder(new TechOrderReq(specimen,null,"DEEP_CUT",null,"原下达原因"),supplied);
        case "done"->report.doneTechOrder(techOrder,supplied);
        case "cancel"->report.cancelTechOrder(techOrder,new CancelTechOrderReq("不再需要"),supplied);
        default->throw new IllegalArgumentException(op);
    };}
    int op(String op){return response(op,auth()).getCode();}
    int changed(String op){return switch(op){case "gross","revise"->5221;case "dehydrate","embed","slides"->5241;case "stain","batch"->5245;case "create"->5261;default->5268;};}
    Object facts(){return List.of(jdbc.queryForList("select * from path_specimen where barcode like ? order by id",tag+"%"),jdbc.queryForList("select b.* from path_block b join path_specimen s on s.id=b.specimen_id where s.barcode like ? order by b.id",tag+"%"),jdbc.queryForList("select l.* from path_slide l join path_block b on b.id=l.block_id join path_specimen s on s.id=b.specimen_id where s.barcode like ? order by l.id",tag+"%"),jdbc.queryForList("select t.* from path_tech_order t join path_specimen s on s.id=t.specimen_id where s.barcode like ? order by t.id",tag+"%"),jdbc.queryForList("select p.* from path_process p join path_specimen s on s.id=p.specimen_id where s.barcode like ? order by p.id",tag+"%"),jdbc.queryForList("select g.* from path_gross_revision g join path_specimen s on s.id=g.specimen_id where s.barcode like ? order by g.id",tag+"%"),jdbc.queryForList("select g.* from path_gross_field g join path_specimen s on s.id=g.specimen_id where s.barcode like ? order by g.id",tag+"%"),jdbc.queryForList("select * from outp_order where registration_id=? order by id",registration));}
    void sourceLock(){jdbc.queryForList("select id from outp_order where id=? for update",order);}
    void specimenLock(){jdbc.queryForList("select id from path_specimen where id=? for update",specimen);}
    Future<Integer> worker(ExecutorService pool,AtomicInteger pid,IntSupplier fn){return worker(pool,pid,tech,fn);}
    Future<Integer> worker(ExecutorService pool,AtomicInteger pid,Staff actor,IntSupplier fn){return pool.submit(()->{authenticate(actor);try{return attempt(()->tx().execute(t->{pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));return fn.getAsInt();}));}finally{SecurityContextHolder.clearContext();}});}
    int result(Future<Integer> f)throws Exception{try{return f.get(20,TimeUnit.SECONDS);}catch(ExecutionException e){return fail("Expected readable business result, not SQL/runtime failure",e.getCause());}}
    void waiting(AtomicInteger pid,Future<?> f){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(pid.get()>0&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,pid.get()))){System.out.println("V118 PG_LOCK observed pid="+pid.get());return;}if(f.isDone())fail("Operation finished without required PostgreSQL Lock wait");try{Thread.sleep(15);}catch(InterruptedException e){throw new RuntimeException(e);}}fail("Expected actual PostgreSQL Lock wait");}
    void close(ExecutorService p)throws Exception{p.shutdown();assertTrue(p.awaitTermination(25,TimeUnit.SECONDS));}
    void strongSource(){tx().executeWithoutResult(t->jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{var sp=c.setSavepoint();try(var q=c.prepareStatement("select id from outp_order where id=? for no key update nowait")){q.setLong(1,order);try(var ignored=q.executeQuery()){fail("Writer must own strong O; specimen FK KEY SHARE alone is insufficient");}}catch(java.sql.SQLException e){assertEquals("55P03",e.getSQLState());}finally{c.rollback(sp);c.releaseSavepoint(sp);}return null;}));}
    void reject(){assertEquals(0,registry.reject(specimen,new PathologyRegistryController.RejectReq("模拟不符",null),auth()).getCode());}
    void diagnose(){assertEquals(0,legacy.diagnose(jdbc.queryForObject("select barcode from path_specimen where id=?",String.class,specimen),new PathologyController.DiagnoseReq(null,null,"模拟诊断"),auth()).getCode());}
    void inpatient(){bed=jdbc.queryForObject("insert into inp_bed(ward_id,bed_no) values (?,?) returning id",Long.class,dept,tag);admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,doctor_id) values (?,?,?,?,?,?) returning id",Long.class,tag,patient,dept,dept,bed,doctor.id());inpOrder=jdbc.queryForObject("insert into inp_order(admission_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id) values (?,?,'TREAT',0,?,'模拟病理','次',1,7.5,7.5,'CREATED',?) returning id",Long.class,admission,tag,tag,doctor.id());}
    @AfterEach void cleanup(){SecurityContextHolder.clearContext();tx().executeWithoutResult(t->{
        String ids="select id from path_specimen where barcode like ?";
        jdbc.update("delete from path_report where specimen_id in("+ids+")",tag+"%");
        jdbc.update("delete from path_process where specimen_id in("+ids+")",tag+"%");
        jdbc.update("delete from path_gross_field where specimen_id in("+ids+")",tag+"%");
        jdbc.update("delete from path_gross_revision where specimen_id in("+ids+")",tag+"%");
        jdbc.update("delete from path_slide where block_id in(select id from path_block where specimen_id in("+ids+"))",tag+"%");
        jdbc.update("update path_tech_order set block_id=null where specimen_id in("+ids+")",tag+"%");
        jdbc.update("delete from path_block where specimen_id in("+ids+")",tag+"%");
        jdbc.update("delete from path_tech_order where specimen_id in("+ids+")",tag+"%");
        jdbc.update("delete from path_specimen where barcode like ?",tag+"%");
        jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);
        if(admission!=null){jdbc.update("delete from inp_order where admission_id=?",admission);jdbc.update("delete from inp_admission where id=?",admission);}if(bed!=null)jdbc.update("delete from inp_bed where id=?",bed);
        jdbc.update("delete from empi_patient where id=?",patient);for(var s:staff){jdbc.update("delete from sys_user_role where user_id=?",s.id());jdbc.update("delete from sys_user where id=?",s.id());}jdbc.update("delete from sys_dept where id=?",dept);jdbc.update("update sys_config set cfg_value=? where cfg_key='emr.gate.pathology.techdone'",gate);
    });config.evictAll();}

    @ParameterizedTest @CsvSource({"gross,ADMIN","gross,TECHNICIAN","gross,DOCTOR_OUTP","revise,ADMIN","revise,TECHNICIAN","revise,DOCTOR_OUTP","dehydrate,ADMIN","dehydrate,TECHNICIAN","dehydrate,DOCTOR_OUTP","embed,ADMIN","embed,TECHNICIAN","embed,DOCTOR_OUTP","slides,ADMIN","slides,TECHNICIAN","slides,DOCTOR_OUTP","stain,ADMIN","stain,TECHNICIAN","stain,DOCTOR_OUTP","batch,ADMIN","batch,TECHNICIAN","batch,DOCTOR_OUTP","create,ADMIN","create,TECHNICIAN","create,DOCTOR_OUTP","done,ADMIN","done,TECHNICIAN","done,DOCTOR_OUTP","cancel,ADMIN","cancel,TECHNICIAN","cancel,DOCTOR_OUTP"})
    void originalThreeRolesRemainAllowedOnHistoricalSharedObjects(String op,String role){ready(op);var a=employee("allowed"+role,role);authenticate(a);assertEquals(0,op(op));assertEquals("CHARGED",jdbc.queryForObject("select status from outp_order where id=?",String.class,order));assertEquals("保留原备注",jdbc.queryForObject("select remark from outp_order where id=?",String.class,order));var nodes=jdbc.queryForList("select operator_id,occurred_at from path_process where specimen_id=?",specimen);if(op.equals("revise")){assertTrue(nodes.isEmpty());assertEquals(a.id(),jdbc.queryForObject("select changed_by from path_gross_revision where specimen_id=?",Long.class,specimen));return;}assertFalse(nodes.isEmpty());for(var n:nodes){assertEquals(a.id(),n.get("operator_id"));assertNotNull(n.get("occurred_at"));}}

    @ParameterizedTest @CsvSource({"gross,disabled","gross,missing","gross,wrong","gross,none","gross,forged","revise,disabled","revise,missing","revise,wrong","revise,none","revise,forged","dehydrate,disabled","dehydrate,missing","dehydrate,wrong","dehydrate,none","dehydrate,forged","embed,disabled","embed,missing","embed,wrong","embed,none","embed,forged","slides,disabled","slides,missing","slides,wrong","slides,none","slides,forged","stain,disabled","stain,missing","stain,wrong","stain,none","stain,forged","batch,disabled","batch,missing","batch,wrong","batch,none","batch,forged","create,disabled","create,missing","create,wrong","create,none","create,forged","done,disabled","done,missing","done,wrong","done,none","done,forged","cancel,disabled","cancel,missing","cancel,wrong","cancel,none","cancel,forged"})
    void everyWriteRequiresLiveActualRoleAndCannotForgeExplicitAuthentication(String op,String kind){ready(op);authenticate(tech);Authentication supplied=auth();switch(kind){case "disabled"->jdbc.update("update sys_user set enabled=false where id=?",tech.id());case "missing"->{authenticate(new Staff(-1L,tag+"absent","TECHNICIAN"));supplied=auth();}case "wrong"->{authenticate(employee("wrong","CASHIER"));supplied=auth();}case "none"->authenticate(null);case "forged"->supplied=token(doctor);default->fail();}var explicit=supplied;Object before=facts();assertEquals(1005,attempt(()->response(op,explicit).getCode()));assertEquals(before,facts());}

    @ParameterizedTest @ValueSource(strings={"gross","revise","dehydrate","embed","slides","stain","batch","create","done","cancel"})
    void everyWriteWaitsForStrongSourceBeforeOwningSpecimen(String op)throws Exception{ready(op);var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();try{tx().executeWithoutResult(t->{sourceLock();fs.add(worker(p,pid,()->op(op)));waiting(pid,fs.getFirst());assertDoesNotThrow(()->jdbc.queryForList("select id from path_specimen where id=? for no key update nowait",specimen),"O waiter cannot hold S");});assertEquals(0,result(fs.getFirst()));}finally{close(p);}}

    @ParameterizedTest @ValueSource(strings={"gross","revise","dehydrate","embed","slides","stain","batch","create","done","cancel"})
    void everyWriteOwnsStrongSourceWhileWaitingForSpecimenAndRechecksDisabledActor(String op)throws Exception{ready(op);var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object before=facts();try{tx().executeWithoutResult(t->{specimenLock();fs.add(worker(p,pid,()->op(op)));waiting(pid,fs.getFirst());strongSource();jdbc.update("update sys_user set enabled=false where id=?",tech.id());});assertEquals(1005,result(fs.getFirst()));assertEquals(before,facts());}finally{close(p);}}

    @ParameterizedTest @ValueSource(strings={"gross","revise","dehydrate","embed","slides","stain","batch","create","done","cancel"})
    void actorDisabledWhileWaitingSourceCannotWrite(String op)throws Exception{ready(op);var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object before=facts();try{tx().executeWithoutResult(t->{sourceLock();fs.add(worker(p,pid,()->op(op)));waiting(pid,fs.getFirst());jdbc.update("update sys_user set enabled=false where id=?",tech.id());});assertEquals(1005,result(fs.getFirst()));assertEquals(before,facts());}finally{close(p);}}

    @ParameterizedTest @ValueSource(strings={"gross","revise","dehydrate","embed","slides","stain","batch","create","done","cancel"})
    void sourceReboundWhileWaitingMustRefuseWithoutFollowingMovedSpecimen(String op)throws Exception{ready(op);Long other=newOrder();var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] before=new Object[1];try{tx().executeWithoutResult(t->{sourceLock();fs.add(worker(p,pid,()->op(op)));waiting(pid,fs.getFirst());jdbc.update("update path_specimen set order_id=? where id=?",other,specimen);before[0]=facts();});assertEquals(changed(op),result(fs.getFirst()));assertEquals(before[0],facts());}finally{close(p);}}

    @ParameterizedTest @ValueSource(strings={"gross","revise","create","dehydrate","embed","slides","stain","batch","done"})
    void rejectedObjectRefusesNewPhysicalWritesAndDoneWithNoPartialFacts(String op){ready(op);reject();Object before=facts();assertEquals(op.equals("done")?5261:changed(op),attempt(()->op(op)));assertEquals(before,facts());}

    @Test void cancellationAfterRejectionRemainsLegalAndPreservesOriginalReason(){ready("cancel");reject();assertEquals(0,op("cancel"));var row=jdbc.queryForMap("select * from path_tech_order where id=?",techOrder);assertEquals("CANCELLED",row.get("status"));assertEquals("原下达原因",row.get("reason"));assertEquals("不再需要",row.get("cancel_reason"));assertEquals(tech.id(),row.get("cancelled_by"));}

    @ParameterizedTest @ValueSource(strings={"DONE","CANCELLED"})
    void existingSlidesOfFinishedOrCancelledOrderStillAllowPhysicalStaining(String status){block=newBlock(specimen,true);techOrder=newTech(specimen,block,"DEEP_CUT");slide=newSlide(block,techOrder);Long second=newSlide(block,techOrder);jdbc.update("update path_tech_order set status=? where id=?",status,techOrder);assertEquals(0,op("stain"));assertEquals(0,process.batchComplete(new BatchCompleteReq(List.of(second),"GOOD",null,null),auth()).getCode());assertEquals(status,jdbc.queryForObject("select status from path_tech_order where id=?",String.class,techOrder));assertEquals(2,jdbc.queryForObject("select count(*) from path_slide where block_id=? and stained_at is not null",Integer.class,block));}

    @ParameterizedTest @ValueSource(strings={"CREATED","CANCELLED","CHARGED"})
    void processingDoesNotInventPaymentOrCancellationGate(String status){jdbc.update("update outp_order set status=? where id=?",status,order);ready("slides");assertEquals(0,op("slides"));assertEquals(status,jdbc.queryForObject("select status from outp_order where id=?",String.class,order));}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void appendAfterDiagnosisOrFormalIssueKeepsOriginalPhysicalResamplingCompatibility(boolean issued){diagnose();if(issued)jdbc.update("update path_specimen set report_issued_at=now() where id=?",specimen);String dx=jdbc.queryForObject("select diagnosis from path_specimen where id=?",String.class,specimen);var r=process.grossing(new GrossingReq(specimen,null,null,"追加组织描述",true,null,List.of(new BlockReq("追加组织"))),auth());assertEquals(0,r.getCode());assertTrue(jdbc.queryForObject("select gross_finding from path_specimen where id=?",String.class,specimen).contains("追加组织描述"));assertEquals(dx,jdbc.queryForObject("select diagnosis from path_specimen where id=?",String.class,specimen));assertEquals(issued,jdbc.queryForObject("select report_issued_at is not null from path_specimen where id=?",Boolean.class,specimen));}

    @Test void issuedCompatibilityRowCannotRevisePrimaryGrossText(){jdbc.update("update path_specimen set report_issued_at=now(),diagnosed_at=null where id=?",specimen);Object before=facts();assertEquals(5221,op("revise"));assertEquals(before,facts());}

    @ParameterizedTest @ValueSource(strings={"gross","revise","create"})
    void realRejectionWinsAgainstWaitingProducer(String op)throws Exception{ready(op);var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] after=new Object[1];try{tx().executeWithoutResult(t->{reject();fs.add(worker(p,pid,()->op(op)));waiting(pid,fs.getFirst());after[0]=facts();});assertEquals(changed(op),result(fs.getFirst()));assertEquals(after[0],facts());}finally{close(p);}}

    @Test void diagnosisWinsAgainstWaitingGrossRevision()throws Exception{var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] after=new Object[1];try{tx().executeWithoutResult(t->{diagnose();fs.add(worker(p,pid,()->op("revise")));waiting(pid,fs.getFirst());after[0]=facts();});assertEquals(5221,result(fs.getFirst()));assertEquals(after[0],facts());}finally{close(p);}}

    @Test void concurrentInitialGrossingHasExactlyOneSuccessAndOneReadable5223()throws Exception{var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();try{tx().executeWithoutResult(t->{assertEquals(0,op("gross"));fs.add(worker(p,pid,()->op("gross")));waiting(pid,fs.getFirst());});assertEquals(5223,result(fs.getFirst()));assertEquals(List.of(1),jdbc.queryForList("select block_no from path_block where specimen_id=? order by block_no",Integer.class,specimen));assertEquals(1,jdbc.queryForObject("select count(*) from path_process where specimen_id=? and node='GROSSING'",Integer.class,specimen));}finally{close(p);}}

    @Test void concurrentExplicitAppendsBothSucceedWithContiguousBlocksAndDescriptions()throws Exception{var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();try{tx().executeWithoutResult(t->{assertEquals(0,process.grossing(new GrossingReq(specimen,null,null,"先追加",true,null,List.of(new BlockReq("先块"))),auth()).getCode());fs.add(worker(p,pid,()->process.grossing(new GrossingReq(specimen,null,null,"后追加",true,null,List.of(new BlockReq("后块"))),auth()).getCode()));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertEquals(List.of(1,2),jdbc.queryForList("select block_no from path_block where specimen_id=? order by block_no",Integer.class,specimen));String gross=jdbc.queryForObject("select gross_finding from path_specimen where id=?",String.class,specimen);assertTrue(gross.contains("先追加"));assertTrue(gross.contains("后追加"));assertEquals(List.of(1,2),jdbc.queryForList("select seq from path_gross_revision where specimen_id=? order by seq",Integer.class,specimen));}finally{close(p);}}

    @Test void concurrentSectionsBothSucceedAndContinueActualSlideSequence()throws Exception{ready("slides");var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();try{tx().executeWithoutResult(t->{assertEquals(0,op("slides"));fs.add(worker(p,pid,()->op("slides")));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertEquals(List.of(1,2),jdbc.queryForList("select slide_no from path_slide where block_id=? order by slide_no",Integer.class,block));assertEquals(2,jdbc.queryForObject("select count(*) from path_process where specimen_id=? and node='SECTION'",Integer.class,specimen));}finally{close(p);}}

    @ParameterizedTest @ValueSource(strings={"singleFirst","batchFirst"})
    void overlappingStainIsOneWinnerAndWholeLosingBatchWritesNothing(String sequence)throws Exception{ready("stain");Long other=newSlide(block,null);var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] won=new Object[1];try{tx().executeWithoutResult(t->{if(sequence.equals("singleFirst")){assertEquals(0,op("stain"));fs.add(worker(p,pid,()->process.batchComplete(new BatchCompleteReq(List.of(other,slide),"GOOD",null,null),auth()).getCode()));}else{assertEquals(0,process.batchComplete(new BatchCompleteReq(List.of(other,slide),"GOOD",null,null),auth()).getCode());fs.add(worker(p,pid,()->op("stain")));}waiting(pid,fs.getFirst());won[0]=facts();});assertEquals(5245,result(fs.getFirst()));assertEquals(won[0],facts());assertEquals(sequence.equals("singleFirst")?1:2,jdbc.queryForObject("select count(*) from path_slide where block_id=? and stained_at is not null",Integer.class,block));}finally{close(p);}}

    @Test void embeddingFirstMakesWholeWaitingDehydrateBatchRefuse()throws Exception{ready("embed");Long other=newBlock(specimen,false);var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] won=new Object[1];try{tx().executeWithoutResult(t->{assertEquals(0,op("embed"));fs.add(worker(p,pid,()->process.dehydrateBatch(new DehydrateBatchReq(tag+"X",List.of(other,block),null),auth()).getCode()));waiting(pid,fs.getFirst());won[0]=facts();});assertEquals(5241,result(fs.getFirst()));assertEquals(won[0],facts());assertEquals(0,jdbc.queryForObject("select count(*) from path_block where specimen_id=? and dehydrate_batch is not null",Integer.class,specimen));}finally{close(p);}}

    @Test void dehydrateFirstThenEmbedKeepsNewBasketAndBothNodes()throws Exception{ready("dehydrate");var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();try{tx().executeWithoutResult(t->{assertEquals(0,op("dehydrate"));fs.add(worker(p,pid,()->op("embed")));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertEquals(tag+"篮",jdbc.queryForObject("select dehydrate_batch from path_block where id=?",String.class,block));assertEquals(2,jdbc.queryForObject("select count(*) from path_process where specimen_id=?",Integer.class,specimen));}finally{close(p);}}

    @ParameterizedTest @CsvSource({"off,stain","warn,stain","block,stain","off,resample","warn,resample","block,resample"})
    void waitingDoneUsesFreshStainedOrSampledEvidenceAndIdenticalNodeRemark(String tier,String producer)throws Exception{
        block=newBlock(specimen,true);techOrder=newTech(specimen,producer.equals("resample")?null:block,producer.equals("resample")?"RESAMPLE":"DEEP_CUT");
        if(producer.equals("stain"))slide=newSlide(block,techOrder);setGate(tier);
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();var returned=new AtomicReference<Map<String,Object>>();
        try{tx().executeWithoutResult(t->{
            // Explicit parent lock is a controlled committed-transaction barrier, not an HTTP pre-load claim.
            sourceLock();specimenLock();
            if(producer.equals("stain"))assertEquals(0,op("stain"));
            else assertEquals(0,process.grossing(new GrossingReq(specimen,null,null,null,true,null,List.of(new BlockReq("补取材")),techOrder),auth()).getCode());
            fs.add(worker(p,pid,()->{var r=report.doneTechOrder(techOrder,auth());returned.set(r.getData());return r.getCode();}));waiting(pid,fs.getFirst());
        });assertEquals(0,result(fs.getFirst()));var body=returned.get();assertNotNull(body);assertEquals(producer.equals("stain")?1L:0L,body.get("slideCount"));assertEquals(producer.equals("stain")?1L:0L,body.get("stainedCount"));assertEquals(producer.equals("resample")?1L:0L,body.get("sampledBlockCount"));assertNull(body.get("doneGap"));assertEquals(List.of(),body.get("warnings"));assertEquals(body.get("doneRemark"),jdbc.queryForObject("select remark from path_process where specimen_id=? and node='TECH_DONE'",String.class,specimen));
        }finally{close(p);}
    }

    @ParameterizedTest @CsvSource({"done,slides","cancel,slides","done,resample","cancel,resample"})
    void terminalTechFirstPreventsNewLinkedProductionWithReadableBusinessCode(String terminal,String producer)throws Exception{
        block=newBlock(specimen,true);techOrder=newTech(specimen,producer.equals("resample")?null:block,producer.equals("resample")?"RESAMPLE":"DEEP_CUT");var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] before=new Object[1];
        try{tx().executeWithoutResult(t->{assertEquals(0,op(terminal));before[0]=facts();fs.add(worker(p,pid,()->producer.equals("slides")?process.slides(new SlideReq(block,1,"HE",null,null,techOrder),auth()).getCode():process.grossing(new GrossingReq(specimen,null,null,null,true,null,List.of(new BlockReq("补块")),techOrder),auth()).getCode()));waiting(pid,fs.getFirst());});assertEquals(producer.equals("slides")?5272:5275,result(fs.getFirst()));assertEquals(before[0],facts());}finally{close(p);}
    }

    @ParameterizedTest @CsvSource({"slides,done","slides,cancel","resample,done","resample,cancel"})
    void linkedProducerFirstRetainsEvidenceWhenTerminalOperationFollows(String producer,String terminal)throws Exception{
        block=newBlock(specimen,true);techOrder=newTech(specimen,producer.equals("resample")?null:block,producer.equals("resample")?"RESAMPLE":"DEEP_CUT");var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{tx().executeWithoutResult(t->{sourceLock();specimenLock();assertEquals(0,producer.equals("slides")?process.slides(new SlideReq(block,1,"HE",null,null,techOrder),auth()).getCode():process.grossing(new GrossingReq(specimen,null,null,null,true,null,List.of(new BlockReq("补块")),techOrder),auth()).getCode());fs.add(worker(p,pid,()->op(terminal)));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));assertEquals(terminal.equals("done")?"DONE":"CANCELLED",jdbc.queryForObject("select status from path_tech_order where id=?",String.class,techOrder));assertEquals(1,jdbc.queryForObject(producer.equals("slides")?"select count(*) from path_slide where tech_order_id=?":"select count(*) from path_block where tech_order_id=?",Integer.class,techOrder));assertEquals("原下达原因",jdbc.queryForObject("select reason from path_tech_order where id=?",String.class,techOrder));}finally{close(p);}
    }

    @ParameterizedTest @CsvSource({"off,0,0","warn,0,1","block,5273,0","broken,0,1"})
    void originalDoneGateStillReportsRealMissingEvidence(String tier,int expected,int warnings){ready("done");setGate(tier);Object before=facts();var r=report.doneTechOrder(techOrder,auth());assertEquals(expected,r.getCode());if(expected!=0){assertEquals(before,facts());return;}assertEquals(0L,r.getData().get("slideCount"));assertEquals(0L,r.getData().get("stainedCount"));assertEquals(0L,r.getData().get("sampledBlockCount"));assertEquals(warnings,((List<?>)r.getData().get("warnings")).size());assertEquals(r.getData().get("doneRemark"),jdbc.queryForObject("select remark from path_process where specimen_id=? and node='TECH_DONE'",String.class,specimen));}

    @ParameterizedTest @ValueSource(strings={"dehydrate","batch"})
    void reverseInputBatchLocksAllOutpatientSourcesBeforeAnySpecimen(String op)throws Exception{
        Long otherOrder=newOrder(),otherSpecimen=newSpecimen(otherOrder,null);block=newBlock(specimen,false);Long otherBlock=newBlock(otherSpecimen,false);slide=newSlide(block,null);Long otherSlide=newSlide(otherBlock,null);
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{tx().executeWithoutResult(t->{jdbc.queryForList("select id from outp_order where id=? for update",otherOrder);fs.add(worker(p,pid,()->op.equals("dehydrate")?process.dehydrateBatch(new DehydrateBatchReq(tag+"整篮",List.of(otherBlock,block),null),auth()).getCode():process.batchComplete(new BatchCompleteReq(List.of(otherSlide,slide),"GOOD",null,null),auth()).getCode()));waiting(pid,fs.getFirst());strongSource();assertDoesNotThrow(()->jdbc.queryForList("select id from path_specimen where id in (?,?) for no key update nowait",specimen,otherSpecimen),"Batch must obtain all O before any S");});assertEquals(0,result(fs.getFirst()));assertEquals(2,jdbc.queryForObject(op.equals("dehydrate")?"select count(*) from path_block where dehydrate_batch=?":"select count(*) from path_slide where slide_code like ? and stained_at is not null",Integer.class,op.equals("dehydrate")?tag+"整篮":tag+"%"));}finally{close(p);}
    }

    @ParameterizedTest @ValueSource(strings={"dehydrate","batch"})
    void mixedInpOutpBatchUsesOutpatientSourcesThenInpatientThenSpecimens(String op)throws Exception{
        inpatient();Long otherSpecimen=newSpecimen(null,inpOrder);block=newBlock(specimen,false);Long otherBlock=newBlock(otherSpecimen,false);slide=newSlide(block,null);Long otherSlide=newSlide(otherBlock,null);
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{tx().executeWithoutResult(t->{jdbc.queryForList("select id from inp_order where id=? for update",inpOrder);fs.add(worker(p,pid,()->op.equals("dehydrate")?process.dehydrateBatch(new DehydrateBatchReq(tag+"混篮",List.of(otherBlock,block),null),auth()).getCode():process.batchComplete(new BatchCompleteReq(List.of(otherSlide,slide),"GOOD",null,null),auth()).getCode()));waiting(pid,fs.getFirst());strongSource();assertDoesNotThrow(()->jdbc.queryForList("select id from path_specimen where id in (?,?) for no key update nowait",specimen,otherSpecimen));});assertEquals(0,result(fs.getFirst()));assertEquals("CREATED",jdbc.queryForObject("select status from inp_order where id=?",String.class,inpOrder));}finally{close(p);}
    }

    @ParameterizedTest @ValueSource(strings={"embed","slides","stain","batch","done","cancel"})
    void childRelationshipReboundWhileWaitingRefusesOriginalOperation(String op)throws Exception{
        ready(op);Long another=newSpecimen(order,null),anotherBlock=newBlock(another,true);var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] before=new Object[1];
        try{tx().executeWithoutResult(t->{sourceLock();fs.add(worker(p,pid,()->op(op)));waiting(pid,fs.getFirst());if(List.of("stain","batch").contains(op))jdbc.update("update path_slide set block_id=? where id=?",anotherBlock,slide);else if(List.of("done","cancel").contains(op))jdbc.update("update path_tech_order set specimen_id=?,block_id=? where id=?",another,anotherBlock,techOrder);else jdbc.update("update path_block set specimen_id=?,block_no=2 where id=?",another,block);before[0]=facts();});assertEquals(changed(op),result(fs.getFirst()));assertEquals(before[0],facts());}finally{close(p);}
    }

    @ParameterizedTest @ValueSource(strings={"gross","revise","create","slides","done"})
    void pendingManagedMetadataFlushIsRetainedByProcessingComposition(String op){ready(op);tx().executeWithoutResult(t->{var original=orders.findById(order).orElseThrow();original.setRemark("组合中的新备注");assertEquals(0,op(op));assertEquals("组合中的新备注",jdbc.queryForObject("select remark from outp_order where id=?",String.class,order));});assertEquals("组合中的新备注",jdbc.queryForObject("select remark from outp_order where id=?",String.class,order));assertEquals("CHARGED",jdbc.queryForObject("select status from outp_order where id=?",String.class,order));}

    @ParameterizedTest @ValueSource(strings={"gross","slides","create","done","cancel","batch","dehydrate","embed","stain"})
    void actualProcessNodeFailureRollsBackEveryProducerFactAndRetrySucceeds(String op){
        ready(op);Object before=facts();String fn="v118_fail_"+tag.toLowerCase(),trigger=fn+"_trigger";
        jdbc.execute("create function "+fn+"() returns trigger language plpgsql as $$ begin if exists(select 1 from path_specimen where id=NEW.specimen_id and barcode like '"+tag+"%') then raise exception 'v118 deliberately fail node'; end if; return NEW; end $$");
        jdbc.execute("create trigger "+trigger+" before insert on path_process for each row execute function "+fn+"()");
        try{assertThrows(RuntimeException.class,()->op(op));assertEquals(before,facts());}finally{jdbc.execute("drop trigger "+trigger+" on path_process");jdbc.execute("drop function "+fn+"()");}
        assertEquals(0,op(op));assertNotEquals(before,facts());
    }
    @ParameterizedTest @ValueSource(strings={"dehydrate","embed","slides","stain","batch","done","cancel"})
    void actorDisabledWhileWaitingActualChildRowCannotLeavePartialProduction(String op)throws Exception{
        ready(op);String table=List.of("done","cancel").contains(op)?"path_tech_order":List.of("stain","batch").contains(op)?"path_slide":"path_block";Long id=table.equals("path_tech_order")?techOrder:table.equals("path_slide")?slide:block;
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object before=facts();
        try{tx().executeWithoutResult(t->{jdbc.queryForList("select id from "+table+" where id=? for update",id);fs.add(worker(p,pid,()->op(op)));waiting(pid,fs.getFirst());jdbc.update("update sys_user set enabled=false where id=?",tech.id());});assertEquals(1005,result(fs.getFirst()));assertEquals(before,facts());}finally{close(p);}
    }

    @Test void waitingAppendUsesGrossDescriptionCommittedByDiagnosisAndContinuesRevisionSequence()throws Exception{
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{tx().executeWithoutResult(t->{assertEquals(0,legacy.diagnose(jdbc.queryForObject("select barcode from path_specimen where id=?",String.class,specimen),new PathologyController.DiagnoseReq("诊断重写大体",null,"模拟诊断"),auth()).getCode());fs.add(worker(p,pid,()->process.grossing(new GrossingReq(specimen,null,null,"后追加描述",true,null,List.of(new BlockReq("后追加组织"))),auth()).getCode()));waiting(pid,fs.getFirst());});assertEquals(0,result(fs.getFirst()));String gross=jdbc.queryForObject("select gross_finding from path_specimen where id=?",String.class,specimen);assertTrue(gross.startsWith("诊断重写大体"));assertTrue(gross.contains("后追加描述"));assertEquals("模拟诊断",jdbc.queryForObject("select diagnosis from path_specimen where id=?",String.class,specimen));assertEquals(List.of(1,2),jdbc.queryForList("select seq from path_gross_revision where specimen_id=? order by seq",Integer.class,specimen));assertEquals(List.of("DIAGNOSE","GROSSING"),jdbc.queryForList("select source from path_gross_revision where specimen_id=? order by seq",String.class,specimen));}finally{close(p);}
    }

    @ParameterizedTest @ValueSource(strings={"dehydrate","batch"})
    void reverseInputBatchLocksAllInpatientSourcesByIdBeforeAnySpecimen(String op)throws Exception{
        inpatient();Long firstInp=inpOrder;Long secondInp=jdbc.queryForObject("insert into inp_order(admission_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,doctor_id) values (?,?,'TREAT',0,?,'模拟病理二','次',1,7.5,7.5,'CREATED',?) returning id",Long.class,admission,tag+"2",tag+"2",doctor.id());
        Long firstS=newSpecimen(null,firstInp),secondS=newSpecimen(null,secondInp);Long firstB=newBlock(firstS,false),secondB=newBlock(secondS,false),firstL=newSlide(firstB,null),secondL=newSlide(secondB,null);
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();
        try{tx().executeWithoutResult(t->{jdbc.queryForList("select id from inp_order where id=? for update",secondInp);fs.add(worker(p,pid,()->op.equals("dehydrate")?process.dehydrateBatch(new DehydrateBatchReq(tag+"住院篮",List.of(secondB,firstB),null),auth()).getCode():process.batchComplete(new BatchCompleteReq(List.of(secondL,firstL),"GOOD",null,null),auth()).getCode()));waiting(pid,fs.getFirst());jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)c->{var sp=c.setSavepoint();try(var q=c.prepareStatement("select id from inp_order where id=? for no key update nowait")){q.setLong(1,firstInp);try(var ignored=q.executeQuery()){fail("First INP source must already have strong lock");}}catch(java.sql.SQLException e){assertEquals("55P03",e.getSQLState());}finally{c.rollback(sp);c.releaseSavepoint(sp);}return null;});assertDoesNotThrow(()->jdbc.queryForList("select id from path_specimen where id in (?,?) for no key update nowait",firstS,secondS));});assertEquals(0,result(fs.getFirst()));assertEquals(2,jdbc.queryForObject("select count(*) from inp_order where admission_id=? and status='CREATED'",Integer.class,admission));}finally{close(p);}
    }

    @ParameterizedTest @ValueSource(strings={"dehydrate","batch"})
    void secondSpecimenRejectionDuringBatchWaitRefusesEntireBatch(String op)throws Exception{
        Long otherOrder=newOrder(),otherS=newSpecimen(otherOrder,null);block=newBlock(specimen,false);Long otherB=newBlock(otherS,false);slide=newSlide(block,null);Long otherL=newSlide(otherB,null);
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object[] before=new Object[1];
        try{tx().executeWithoutResult(t->{jdbc.queryForList("select id from outp_order where id=? for update",otherOrder);fs.add(worker(p,pid,()->op.equals("dehydrate")?process.dehydrateBatch(new DehydrateBatchReq(tag+"拒收篮",List.of(block,otherB),null),auth()).getCode():process.batchComplete(new BatchCompleteReq(List.of(slide,otherL),"GOOD",null,null),auth()).getCode()));waiting(pid,fs.getFirst());assertEquals(0,registry.reject(otherS,new PathologyRegistryController.RejectReq("模拟第二部位不符",null),auth()).getCode());before[0]=facts();});assertEquals(op.equals("dehydrate")?5241:5245,result(fs.getFirst()));assertEquals(before[0],facts());}finally{close(p);}
    }
    @ParameterizedTest @ValueSource(strings={"slides","resample"})
    void mismatchedForeignTechOrderMustRefuseWithoutTakingForeignChildLock(String producer)throws Exception{
        block=newBlock(specimen,true);Long ownTech=newTech(specimen,block,"RESAMPLE");jdbc.update("update path_block set tech_order_id=? where id=?",ownTech,block);
        Long foreignOrder=newOrder(),foreignS=newSpecimen(foreignOrder,null),foreignBlock=newBlock(foreignS,true);Long foreignTech=newTech(foreignS,foreignBlock,producer.equals("resample")?"RESAMPLE":"DEEP_CUT");jdbc.update("update path_block set tech_order_id=? where id=?",foreignTech,foreignBlock);
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object before=facts();
        try{tx().executeWithoutResult(t->{jdbc.queryForList("select id from path_tech_order where id=? for update",foreignTech);fs.add(worker(p,pid,()->producer.equals("slides")?process.slides(new SlideReq(block,1,"HE",null,null,foreignTech),auth()).getCode():process.grossing(new GrossingReq(specimen,null,null,null,true,null,List.of(new BlockReq("坏挂接")),foreignTech),auth()).getCode()));
            // Foreign T must not be acquired after the request has already locked its own S and T.
            // This is the deterministic half of the T1->T2 / T2->T1 malformed-input deadlock.
            try{assertEquals(producer.equals("slides")?5272:5275,fs.getFirst().get(3,TimeUnit.SECONDS));}catch(TimeoutException e){fail("Mismatched foreign tech order must be rejected before waiting for its row lock",e);}catch(Exception e){throw new RuntimeException(e);}
        });assertEquals(before,facts());}finally{close(p);}
    }
    @ParameterizedTest @ValueSource(strings={"slides","resample"})
    void actorDisabledWhileWaitingExplicitRequestedTechLockCannotCreatePhysicalChildren(String producer)throws Exception{
        block=newBlock(specimen,true);techOrder=newTech(specimen,producer.equals("resample")?null:block,producer.equals("resample")?"RESAMPLE":"DEEP_CUT");
        var p=Executors.newSingleThreadExecutor();var pid=new AtomicInteger();var fs=new ArrayList<Future<Integer>>();Object before=facts();
        try{tx().executeWithoutResult(t->{jdbc.queryForList("select id from path_tech_order where id=? for update",techOrder);fs.add(worker(p,pid,()->producer.equals("slides")?process.slides(new SlideReq(block,1,"HE",null,null,techOrder),auth()).getCode():process.grossing(new GrossingReq(specimen,null,null,null,true,null,List.of(new BlockReq("补块")),techOrder),auth()).getCode()));waiting(pid,fs.getFirst());jdbc.update("update sys_user set enabled=false where id=?",tech.id());});assertEquals(1005,result(fs.getFirst()));assertEquals(before,facts());}finally{close(p);}
    }
}

