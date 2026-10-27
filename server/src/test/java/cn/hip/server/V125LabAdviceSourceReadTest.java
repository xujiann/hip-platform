package cn.hip.server;

import cn.hip.medtech.web.MedTechController;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Signed JWT requests with committed PostgreSQL facts and a real controlled HTTP AI receiver.
 * Historical invalid source/results are explicit fixtures, not claims about the current write API.
 * No outer transaction, direct controller substitute or invented concurrency claim. The shared
 * controller's temporary endpoint is restored after every test, including failed assertions. */
@SpringBootTest
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class V125LabAdviceSourceReadTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired MedTechController controller;
    @Autowired PlatformTransactionManager manager;
    Object target;
    String originalAiUrl, tag, admin;
    Long dept, staffDept, patient, schedule, registration, order, bill;
    final List<Long> employees = new ArrayList<>();
    static final List<Received> received = new CopyOnWriteArrayList<>();
    static HttpServer sink;
    static volatile String responseBody;
    record Received(String method, String path, String contentType, String body) {}
    record Outcome(int http, JsonNode body) { int code() { return body.get("code").asInt(); } }

    // Keep one controlled listener for the class; database fixtures remain per-method.
    @BeforeAll static void startReceiver() throws Exception {
        sink=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        sink.createContext("/analyze/lab",exchange->{
            received.add(new Received(exchange.getRequestMethod(),exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] bytes=responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
            exchange.close();
        });
        sink.start();
    }
    @AfterAll static void stopReceiver() {
        if(sink!=null){sink.stop(0);sink=null;}
    }
    @BeforeEach void fixture() throws Exception {
        SecurityContextHolder.clearContext();
        target=AopTestUtils.getUltimateTargetObject(controller);
        originalAiUrl=(String)ReflectionTestUtils.getField(target,"aiUrl");
        received.clear();
        responseBody="{\"advice\":[\"受控解读\"],\"receiverMarker\":\"V125\",\"source\":\"must-be-overridden\"}";
        ReflectionTestUtils.setField(target,"aiUrl","http://127.0.0.1:"+sink.getAddress().getPort());
        tag="V125"+Long.toUnsignedString(System.nanoTime(),36);
        dept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",Long.class,tag,tag);
        staffDept=jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'MEDTECH') returning id",Long.class,tag+"S",tag+"异科");
        patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'U') returning id",Long.class,tag,tag+"不应传出的患者名");
        schedule=jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,1,1,0) returning id",Long.class,dept);
        registration=jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (1,?,?,?,current_date-1,0,'VISITED') returning id",Long.class,patient,schedule,dept);
        order=order("主申请","LAB","EXECUTED");
        bill=jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status) values (?,?,7.50,'CASH','PAID') returning id",Long.class,tag,registration);
        jdbc.update("update outp_order set charge_id=? where id=?",bill,order);
        result(order,"正常","15","N");result(order,"危急","999","HH");
        result(order,"危急","999","HH");result(order,"低值","-1","LL");
        admin=staff("ADMIN");
    }
    Long order(String label,String type,String state) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status)
                values (?,?,?,0,?,?,'次',1,7.50,7.50,?) returning id
                """,Long.class,registration,tag,type,tag+label,label,state);
    }
    void result(Long source,String name,String value,String flag) {
        jdbc.update("insert into outp_lab_result(order_id,item_code,item_name,result_value,unit,ref_range,abnormal_flag) values (?,'T',?,?,'不传单位','不传范围',?)",source,name,value,flag);
    }
    String staff(String role) {
        String name=tag+role;
        Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled,dept_id) values (?,'x',?,true,?) returning id",Long.class,name,name,staffDept);
        employees.add(id);jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role);
        return jwt.issue(name);
    }
    Outcome request(Long id,String token) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var req=get("/api/ai/lab-advice").param("orderId",id.toString());
            if(token!=null)req.header("Authorization","Bearer "+token);
            var res=mvc.perform(req).andReturn().getResponse();
            return new Outcome(res.getStatus(),mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8)));
        } finally {SecurityContextHolder.clearContext();}
    }
    Set<String> keys(JsonNode node) {var keys=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return keys;}
    JsonNode expectedPayload() {
        return mapper.valueToTree(Map.of("results",jdbc.queryForList("select item_name as name,result_value as value,abnormal_flag as flag from outp_lab_result where order_id=? order by id",order)));
    }
    JsonNode success(String token) throws Exception {
        int before=received.size();var facts=facts();var result=request(order,token);
        assertEquals(200,result.http());assertEquals(0,result.code(),result.body().toString());
        assertEquals(Set.of("source","advice","receiverMarker"),keys(result.body().get("data")));
        assertEquals("ai-service",result.body().at("/data/source").asText());
        assertEquals("受控解读",result.body().at("/data/advice/0").asText());
        assertEquals("V125",result.body().at("/data/receiverMarker").asText());
        assertEquals(before+1,received.size());var input=received.get(before);
        assertEquals("POST",input.method());assertEquals("/analyze/lab",input.path());assertEquals("application/json",input.contentType());
        var payload=mapper.readTree(input.body());assertEquals(expectedPayload(),payload);
        payload.get("results").forEach(row->assertEquals(Set.of("name","value","flag"),keys(row)));
        assertFalse(input.body().contains(tag));assertFalse(input.body().contains("不传"));
        assertEquals(facts,facts());return payload;
    }
    void refused(Long id,String token,int http,int code) throws Exception {
        var before=facts();int calls=received.size();var result=request(id,token);
        System.out.println("V125 REFUSAL expected="+code+" actual="+result.code()+" forwarded="+(received.size()-calls));
        assertAll(()->assertEquals(http,result.http()),()->assertEquals(code,result.code(),result.body().toString()),
                ()->assertTrue(result.body().get("data").isNull(),"No clinical/advice body on refusal"),
                ()->assertEquals(calls,received.size(),"Rejected source must not be forwarded to the AI receiver"),
                ()->assertEquals(before,facts(),"Full business columns stay unchanged"));
    }
    Object facts() {
        return List.of(jdbc.queryForList("select * from empi_patient where id=?",patient),
                jdbc.queryForList("select * from outp_registration where id=?",registration),
                jdbc.queryForList("select * from outp_schedule where id=?",schedule),
                jdbc.queryForList("select * from outp_order where registration_id=? order by id",registration),
                jdbc.queryForList("select * from outp_charge where registration_id=? order by id",registration),
                jdbc.queryForList("select * from outp_lab_result where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from lis_sample where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from outp_order_report where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from ris_exam where order_id in(select id from outp_order where registration_id=?) order by id",registration),
                jdbc.queryForList("select * from outp_critical_alert where registration_id=? order by id",registration),
                jdbc.queryForList("select * from cdr_document where patient_id=? order by id",patient));
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if(target!=null)ReflectionTestUtils.setField(target,"aiUrl",originalAiUrl);
        if(registration==null)return;
        new TransactionTemplate(manager).executeWithoutResult(t->{
            jdbc.update("delete from lis_sample where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_lab_result where order_id in(select id from outp_order where registration_id=?)",registration);
            jdbc.update("delete from outp_order where registration_id=?",registration);jdbc.update("delete from outp_charge where registration_id=?",registration);
            jdbc.update("delete from outp_registration where id=?",registration);jdbc.update("delete from outp_schedule where id=?",schedule);jdbc.update("delete from empi_patient where id=?",patient);
            for(Long id:employees){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}
            jdbc.update("delete from sys_dept where id in (?,?)",dept,staffDept);
        });employees.clear();
    }

    @ParameterizedTest @ValueSource(strings={"CREATED","CHARGED","DISPENSED","CANCELLED"})
    void unexecutedHistoricalResultsNeverReachAi(String state) throws Exception {
        jdbc.update("update outp_order set status=? where id=?",state,order);
        if(state.equals("CREATED")){jdbc.update("update outp_charge set status='REFUNDED' where id=?",bill);jdbc.update("update outp_order set charge_id=null where id=?",order);}
        refused(order,admin,200,9952);
    }
    @ParameterizedTest @ValueSource(strings={"EXAM","DRUG","TREAT"})
    void wrongTypeHistoricalResultsNeverReachAi(String type) throws Exception {jdbc.update("update outp_order set order_type=? where id=?",type,order);refused(order,admin,200,9952);}
    @Test void noOwnResultsNeverCallsAi() throws Exception {jdbc.update("delete from outp_lab_result where order_id=?",order);refused(order,admin,200,9952);}
    @Test void sameGroupSiblingsResultsCannotQualifyAnEmptySelectedOrder() throws Exception {
        jdbc.update("delete from outp_lab_result where order_id=?",order);result(order("同组","LAB","EXECUTED"),"兄弟独有","88","HH");refused(order,admin,200,9952);
    }
    @Test void nonexistentOrderKeeps9952() throws Exception {refused(-1L,admin,200,9952);}
    @ParameterizedTest @ValueSource(strings={"ADMIN","TECHNICIAN","DOCTOR_OUTP","NURSE"})
    void originalFourDutiesCanForwardAnUnsampledExecutedLab(String role) throws Exception {
        assertEquals(0,jdbc.queryForObject("select count(*) from lis_sample where order_id=?",Integer.class,order));success(role.equals("ADMIN")?admin:staff(role));
    }
    @ParameterizedTest @ValueSource(strings={"CASHIER","PHARMACIST","INTERFACE","PORTAL"})
    void wrongDutyNeverForwards(String role) throws Exception {refused(order,role.equals("PORTAL")?jwt.issue("portal:"+patient):staff(role),403,1005);}
    @Test void anonymousCannotInheritAValidRequest() throws Exception {success(admin);refused(order,null,401,1000);}
    @Test void disabledEmployeesExistingJwtNeverForwards() throws Exception {jdbc.update("update sys_user set enabled=false where username=?",tag+"ADMIN");refused(order,admin,401,1000);}
    @ParameterizedTest @ValueSource(strings={"COLLECTED","RECEIVED","PUBLISHED"})
    void sampleStageDoesNotReplaceLabSourceEligibility(String stage) throws Exception {jdbc.update("insert into lis_sample(order_id,barcode,status) values (?,?,?)",order,tag,stage);success(admin);}
    @Test void executedResultsDoNotGainAChargeRegistrationOrDateGate() throws Exception {
        jdbc.update("update outp_order set charge_id=null where id=?",order);jdbc.update("update outp_charge set status='REFUNDED' where id=?",bill);
        jdbc.update("update outp_registration set visit_date=current_date-365,status='CANCELLED' where id=?",registration);success(admin);
    }
    @Test void onlySelectedOrderRowsAreForwardedInOriginalIdOrder() throws Exception {
        result(order("同组","LAB","EXECUTED"),"兄弟独有","888","HH");
        var payload=success(admin);assertEquals(4,payload.get("results").size());assertFalse(payload.toString().contains("兄弟独有"));
        assertEquals(payload.at("/results/1"),payload.at("/results/2"));assertEquals("HH",payload.at("/results/1/flag").asText());assertEquals("LL",payload.at("/results/3/flag").asText());
    }
    @Test void appendedDuplicatesAndNullsRetainRawThreeKeyValues() throws Exception {
        result(order,"可空标记","原文本值",null);result(order,"","",null);
        var rows=success(admin).get("results");assertEquals(6,rows.size());assertEquals("原文本值",rows.get(4).get("value").asText());assertTrue(rows.get(4).get("flag").isNull());assertEquals("",rows.get(5).get("name").asText());
    }
    @Test void aCommittedSourceWithdrawalStopsTheNextForwarding() throws Exception {
        success(admin);new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order));refused(order,admin,200,9952);
    }
    @Test void committedResultRemovalStopsTheNextForwarding() throws Exception {
        success(admin);new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("delete from outp_lab_result where order_id=?",order));refused(order,admin,200,9952);
    }
    @Test void rolledBackWithdrawalDoesNotSuppressAValidSource() throws Exception {
        var before=facts();new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order);jdbc.update("delete from outp_lab_result where order_id=?",order);t.setRollbackOnly();});success(admin);assertEquals(before,facts());
    }
    @Test void malformedAiResponseKeepsReadableFallbackAndNoBusinessWrite() throws Exception {
        responseBody="not-json";var before=facts();var response=request(order,admin);
        assertEquals(200,response.http());assertEquals(0,response.code());var data=response.body().get("data");assertEquals(Set.of("source","advice","error"),keys(data));assertEquals("fallback",data.get("source").asText());
        assertEquals("AI 子服务不可用，请人工判读",data.at("/advice/0").asText());assertEquals("JsonParseException",data.get("error").asText());assertEquals(1,received.size());assertEquals(expectedPayload(),mapper.readTree(received.getFirst().body()));assertEquals(before,facts());
    }
    @Test void unavailableAiKeepsReadableFallbackWithoutWritingClinicalRows() throws Exception {
        // A released ephemeral port is not a controlled unavailable destination.
        ReflectionTestUtils.setField(target,"aiUrl","http://127.0.0.1:0");
        var before=facts();var response=request(order,admin);
        assertEquals(200,response.http());assertEquals(0,response.code());var data=response.body().get("data");assertEquals(Set.of("source","advice","error"),keys(data));assertEquals("fallback",data.get("source").asText());assertEquals("ConnectException",data.get("error").asText());assertEquals("AI 子服务不可用，请人工判读",data.at("/advice/0").asText());assertTrue(received.isEmpty());assertEquals(before,facts());
    }
}
