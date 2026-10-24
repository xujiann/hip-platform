package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import cn.hip.server.web.PrintReportController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Real signed JWT reads against committed PostgreSQL fixtures; no enclosing test transaction.
 * Historical invalid rows are allocated SQL fixtures, not claims about current write APIs.
 * The raw controller timestamp probe is explicitly separate from the HTTP millisecond contract.
 * Commit/rollback checks describe reads after those boundaries, not a coordinated lock race. */
@SpringBootTest
@AutoConfigureMockMvc
class V121LabPrintReadTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired PrintReportController print;
    String tag;
    Long dept, patient, otherPatient, schedule, registration, otherRegistration, order, bill;
    String admin, adminName;
    final List<Long> employees = new ArrayList<>();

    @BeforeEach void fixture() {
        SecurityContextHolder.clearContext();
        tag = "V121" + Long.toUnsignedString(System.nanoTime(), 36);
        dept = jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id", Long.class, tag, tag);
        patient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'F') returning id", Long.class, tag, tag + "本人");
        otherPatient = jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,?,'M') returning id", Long.class, tag + "Q", tag + "旁人");
        schedule = jdbc.queryForObject("insert into outp_schedule(dept_id,schedule_date,capacity,booked,last_issued_no,fee) values (?,current_date-1,5,2,2,0) returning id", Long.class, dept);
        registration = registration(patient, 1); otherRegistration = registration(otherPatient, 2);
        order = order(registration, "L", "LAB", "EXECUTED");
        bill = jdbc.queryForObject("insert into outp_charge(charge_no,registration_id,total_amount,pay_method,status) values (?,?,7.50,'CASH','PAID') returning id", Long.class, tag, registration);
        jdbc.update("update outp_order set charge_id=? where id=?", bill, order);
        result(order, "正常", "15", "g/L", "1-2", "N");
        result(order, "危急", "999", null, null, "HH");
        result(order, "危急", "999", null, null, "HH");
        result(order, "低值", "-1", "mmol/L", "4-8", "LL");
        admin = staff("ADMIN"); adminName = tag + "ADMIN";
    }
    Long registration(Long p, int no) {
        return jdbc.queryForObject("insert into outp_registration(reg_no,patient_id,schedule_id,dept_id,visit_date,fee,status) values (?,?,?,?,current_date-1,0,'VISITED') returning id", Long.class, no, p, schedule, dept);
    }
    Long order(Long reg, String suffix, String type, String status) {
        return jdbc.queryForObject("""
                insert into outp_order(registration_id,group_no,order_type,item_id,item_code,item_name,unit,qty,unit_price,amount,status,created_at)
                values (?,?,?,0,?,?,'次',1,7.50,7.50,?,date_trunc('second',now())+interval '0.123456 second') returning id
                """, Long.class, reg, tag, type, tag + suffix, tag + suffix + "申请", status);
    }
    Long result(Long source, String name, String value, String unit, String range, String flag) {
        return jdbc.queryForObject("insert into outp_lab_result(order_id,item_code,item_name,result_value,unit,ref_range,abnormal_flag) values (?,?,?,?,?,?,?) returning id",
                Long.class, source, "TEST", name, value, unit, range, flag);
    }
    String staff(String role) {
        String username = tag + role;
        Long id = jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id", Long.class, username, username);
        employees.add(id); jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        return jwt.issue(username);
    }
    JsonNode request(Long id, String token, int status, int code) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            var request = get("/api/print/lab-report/" + id);
            if (token != null) request.header("Authorization", "Bearer " + token);
            var response = mvc.perform(request).andReturn().getResponse();
            assertEquals(status, response.getStatus());
            var body = mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(code, body.get("code").asInt());
            return body.get("data");
        } finally { SecurityContextHolder.clearContext(); }
    }
    JsonNode report() throws Exception { return request(order, admin, 200, 0); }
    Set<String> keys(JsonNode node) { var keys = new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(keys::add); return keys; }
    JsonNode expectedResults(Long source) {
        return mapper.valueToTree(jdbc.queryForList("select item_name,result_value,unit,ref_range,abnormal_flag from outp_lab_result where order_id=? order by id", source));
    }
    Object facts() {
        return List.of(jdbc.queryForList("select * from empi_patient where id in (?,?) order by id", patient, otherPatient),
                jdbc.queryForList("select * from outp_registration where id in (?,?) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from outp_order where registration_id in (?,?) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from outp_charge where registration_id in (?,?) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from outp_lab_result where order_id in (select id from outp_order where registration_id in (?,?)) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from lis_sample where order_id in (select id from outp_order where registration_id in (?,?)) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from outp_critical_alert where registration_id in (?,?) order by id", registration, otherRegistration),
                jdbc.queryForList("select * from outp_schedule where id=?", schedule),
                jdbc.queryForList("select * from cdr_document where patient_id in (?,?) order by id", patient, otherPatient));
    }
    void refusedAndReadOnly() throws Exception { var before = facts(); assertTrue(request(order, admin, 200, 9932).isNull()); assertEquals(before, facts()); }
    Instant instant(Object o) { return o instanceof java.sql.Timestamp t ? t.toInstant() : ((java.time.OffsetDateTime) o).toInstant(); }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        new TransactionTemplate(manager).executeWithoutResult(t -> {
            jdbc.update("delete from outp_critical_alert where registration_id in (?,?)", registration, otherRegistration);
            jdbc.update("delete from lis_sample where order_id in (select id from outp_order where registration_id in (?,?))", registration, otherRegistration);
            jdbc.update("delete from outp_lab_result where order_id in (select id from outp_order where registration_id in (?,?))", registration, otherRegistration);
            jdbc.update("delete from outp_order where registration_id in (?,?)", registration, otherRegistration);
            jdbc.update("delete from outp_charge where registration_id in (?,?)", registration, otherRegistration);
            jdbc.update("delete from outp_registration where id in (?,?)", registration, otherRegistration);
            jdbc.update("delete from outp_schedule where id=?", schedule);
            jdbc.update("delete from empi_patient where id in (?,?)", patient, otherPatient);
            for (Long id : employees) { jdbc.update("delete from sys_user_role where user_id=?", id); jdbc.update("delete from sys_user where id=?", id); }
            jdbc.update("delete from sys_dept where id=?", dept);
        }); employees.clear();
    }

    @ParameterizedTest @ValueSource(strings={"ADMIN","CASHIER","DOCTOR_OUTP","NURSE","TECHNICIAN"})
    void fiveOriginalDutiesKeepExactMachineWithoutSampleContract(String role) throws Exception {
        var token = role.equals("ADMIN") ? admin : staff(role); var before = facts();
        var data = request(order, token, 200, 0);
        assertEquals(Set.of("id","item_name","group_no","created_at","patient_name","sex","patient_no","results"), keys(data));
        assertEquals(order.longValue(), data.get("id").asLong()); assertEquals(tag + "L申请", data.get("item_name").asText());
        assertEquals(tag, data.get("group_no").asText()); assertEquals(tag + "本人", data.get("patient_name").asText());
        assertEquals(tag, data.get("patient_no").asText()); assertEquals("F", data.get("sex").asText());
        Object created = jdbc.queryForObject("select created_at from outp_order where id=?", Object.class, order);
        assertEquals(mapper.valueToTree(created), data.get("created_at"), "Keep the pre-existing HTTP Timestamp serialization");
        assertEquals(expectedResults(order), data.get("results"));
        data.get("results").forEach(row -> assertEquals(Set.of("item_name","result_value","unit","ref_range","abnormal_flag"), keys(row)));
        assertEquals(0, jdbc.queryForObject("select count(*) from lis_sample where order_id=?", Integer.class, order));
        assertEquals(before, facts());
    }
    @ParameterizedTest @ValueSource(strings={"CREATED","CHARGED","DISPENSED","CANCELLED"})
    void aHistoricalResultCannotPublishAnUnexecutedSource(String status) throws Exception {
        jdbc.update("update outp_order set status=? where id=?", status, order);
        if (status.equals("CREATED")) { jdbc.update("update outp_charge set status='REFUNDED' where id=?", bill); jdbc.update("update outp_order set charge_id=null where id=?", order); }
        refusedAndReadOnly();
    }
    @ParameterizedTest @ValueSource(strings={"EXAM","DRUG","TREAT"})
    void otherOrderTypesDoNotBecomeLabReports(String type) throws Exception { jdbc.update("update outp_order set order_type=? where id=?", type, order); refusedAndReadOnly(); }
    @Test void executedButNoResultIsNotAnEmptyReport() throws Exception { jdbc.update("delete from outp_lab_result where order_id=?", order); refusedAndReadOnly(); }
    @Test void aSiblingsResultsCannotMakeTheSelectedEmptyOrderPrintable() throws Exception {
        jdbc.update("delete from outp_lab_result where order_id=?", order); Long sibling=order(otherRegistration,"Q","LAB","EXECUTED"); result(sibling,"旁人结果","77",null,null,"N"); refusedAndReadOnly();
    }
    @Test void missingOrderKeeps9932() throws Exception { var before=facts(); assertTrue(request(-1L,admin,200,9932).isNull()); assertEquals(before,facts()); }
    @ParameterizedTest @ValueSource(strings={"PHARMACIST","INTERFACE","PORTAL"})
    void wrongDomainsCannotEnterThePrintDataset(String role) throws Exception {
        String token=role.equals("PORTAL")?jwt.issue("portal:"+patient):staff(role);var before=facts();assertTrue(request(order,token,403,1005).isNull());assertEquals(before,facts());
    }
    @Test void anonymousDoesNotInheritThePreviousValidJwt() throws Exception { assertEquals(4,report().get("results").size()); var before=facts();assertTrue(request(order,null,401,1000).isNull());assertEquals(before,facts()); }
    @Test void aDisabledEmployeesExistingJwtCannotRead() throws Exception { jdbc.update("update sys_user set enabled=false where username=?",adminName);var before=facts();assertTrue(request(order,admin,401,1000).isNull());assertEquals(before,facts()); }
    @ParameterizedTest @ValueSource(strings={"COLLECTED","RECEIVED","PUBLISHED"})
    void existingSampleStateDoesNotReplaceExecutedResultEligibility(String state) throws Exception {
        jdbc.update("insert into lis_sample(order_id,barcode,status) values (?,?,?)",order,tag+"B",state);var before=facts();assertEquals(expectedResults(order),report().get("results"));assertEquals(before,facts());
    }
    @Test void onlySelectedOrderResultsArePrintedEvenWhenAGroupSpansPatients() throws Exception {
        Long sibling=order(otherRegistration,"Q","LAB","EXECUTED");result(sibling,"另一患者独有","888",null,null,"HH");
        var before=facts();var data=report();assertEquals(expectedResults(order),data.get("results"));assertFalse(data.toString().contains("另一患者独有"));assertEquals(before,facts());
    }
    @Test void duplicateAndLaterMachineAppendsRetainInsertionOrderAndRawCriticalValues() throws Exception {
        var original=report().get("results");assertEquals(original.get(1),original.get(2));assertEquals("999",original.get(1).get("result_value").asText());assertEquals("HH",original.get(1).get("abnormal_flag").asText());
        result(order,"后来追加","-999",null,null,"LL");var before=facts();var data=report();assertEquals(expectedResults(order),data.get("results"));assertEquals(5,data.get("results").size());assertEquals("后来追加",data.get("results").get(4).get("item_name").asText());assertEquals(before,facts());
    }
    @Test void nullableFieldsDoNotCauseMapOfFailuresOrGetInventedValues() throws Exception {
        result(order,"可空字段","文本值",null,null,null);var before=facts();var row=report().get("results").get(4);
        assertTrue(row.get("unit").isNull());assertTrue(row.get("ref_range").isNull());assertTrue(row.get("abnormal_flag").isNull());assertEquals("文本值",row.get("result_value").asText());assertEquals(before,facts());
    }
    @Test void committedInvalidationIsAppliedByTheNextRead() throws Exception {
        assertEquals(4,report().get("results").size());new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order));refusedAndReadOnly();
    }
    @Test void committedRemovalOfAllResultsInvalidatesTheNextRead() throws Exception {
        assertEquals(4,report().get("results").size());new TransactionTemplate(manager).executeWithoutResult(t->jdbc.update("delete from outp_lab_result where order_id=?",order));refusedAndReadOnly();
    }
    @Test void rolledBackInvalidationLeavesTheOriginalValidFactVisible() throws Exception {
        var before=facts();new TransactionTemplate(manager).executeWithoutResult(t->{jdbc.update("update outp_order set status='CREATED',charge_id=null where id=?",order);jdbc.update("delete from outp_lab_result where order_id=?",order);t.setRollbackOnly();});assertEquals(expectedResults(order),report().get("results"));assertEquals(before,facts());
    }
    @Test void patientHeaderComesFromTheSelectedOrdersActualRegistration() throws Exception {
        jdbc.update("update outp_order set registration_id=? where id=?",otherRegistration,order);var before=facts();var data=report();assertEquals(tag+"旁人",data.get("patient_name").asText());assertEquals(tag+"Q",data.get("patient_no").asText());assertEquals("M",data.get("sex").asText());assertEquals(before,facts());
    }
    @Test void validOldReportsDoNotGainADateOrRegistrationStatusGate() throws Exception {
        jdbc.update("update outp_registration set visit_date=current_date-365,status='CANCELLED' where id=?",registration);var before=facts();assertEquals(4,report().get("results").size());assertEquals(before,facts());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(adminName,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {assertEquals(instant(jdbc.queryForObject("select created_at from outp_order where id=?",Object.class,order)),instant(print.labReport(order).getData().get("created_at")),"Direct SQL dataset preserves original microseconds independently of HTTP");}
        finally {SecurityContextHolder.clearContext();}
    }
    @Test void existingBlankTextIsNotSilentlyTurnedIntoANewClinicalValidationRule() throws Exception {
        result(order,"","",null,null,null);var before=facts();assertEquals(expectedResults(order),report().get("results"));assertEquals(before,facts());
    }
}
