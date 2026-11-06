package cn.hip.server;

import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** A real JWT request waits on a PostgreSQL foreign-key parent while employee state changes. */
@SpringBootTest
@AutoConfigureMockMvc
class V164EmrScopedCreateForeignKeyWaitTest {
    private static final String CREATE = "/api/emr-templates/scoped";
    private static final String CONTENT = "V164模拟模板正文";

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;

    private record Staff(long id, String username, String token) {}
    private record Reply(int status, JsonNode body) { int code() { return body.path("code").asInt(); } }
    private record Wait(int pid, String event, String query) {}

    private enum Change { REVOKE, DISABLE, TRANSFER, CLEAR_DEPT, ROLLBACK_DISABLE, SWITCH_DUTY, DOWNGRADE, NONE }

    private enum Case {
        DEPT_DUTY_REVOKED("DEPT", "QUALITY", true, Change.REVOKE, 403, 1005),
        DEPT_DISABLED("DEPT", "QUALITY", true, Change.DISABLE, 403, 1005),
        DEPT_EXPLICIT_TRANSFER("DEPT", "QUALITY", true, Change.TRANSFER, 200, 4066),
        DEPT_IMPLICIT_TRANSFER("DEPT", "QUALITY", false, Change.TRANSFER, 200, 4066),
        DEPT_IMPLICIT_CLEARED("DEPT", "QUALITY", false, Change.CLEAR_DEPT, 200, 4065),
        PERSONAL_DISABLED("PERSONAL", "QUALITY", false, Change.DISABLE, 403, 1005),
        HOSPITAL_ADMIN_DOWNGRADED("HOSPITAL", "ADMIN", false, Change.DOWNGRADE, 200, 4066),
        DEPT_ROLLED_BACK_DISABLE("DEPT", "QUALITY", true, Change.ROLLBACK_DISABLE, 200, 0),
        DEPT_SWITCH_DUTY("DEPT", "QUALITY", true, Change.SWITCH_DUTY, 200, 0),
        DEPT_ADMIN_EXPLICIT_TRANSFER("DEPT", "ADMIN", true, Change.TRANSFER, 200, 0),
        PERSONAL_STILL_AUTHORIZED("PERSONAL", "QUALITY", false, Change.NONE, 200, 0);

        final String scope, duty;
        final boolean explicitDepartment;
        final Change change;
        final int status, code;

        Case(String scope, String duty, boolean explicitDepartment, Change change, int status, int code) {
            this.scope = scope;
            this.duty = duty;
            this.explicitDepartment = explicitDepartment;
            this.change = change;
            this.status = status;
            this.code = code;
        }

        boolean departmentLock() { return scope.equals("DEPT"); }
    }

    private String tag;
    private Long oldDept, newDept;
    private Staff actor, admin;

    @BeforeEach
    void setup() {
        tag = "V164" + Long.toUnsignedString(System.nanoTime(), 36);
        oldDept = department("old");
        newDept = department("new");
        admin = employee("admin", "ADMIN");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Case.class)
    void checksCurrentAuthorizationAfterWaitingForForeignKey(Case scenario) throws Exception {
        actor = employee("actor", scenario.duty);
        var body = new LinkedHashMap<String, Object>();
        body.put("name", tag);
        body.put("content", CONTENT);
        body.put("templateType", "consent");
        body.put("scope", scenario.scope);
        body.put("deptId", scenario.explicitDepartment ? oldDept : null);
        body.put("recordType", "progress");
        var before = templateRows();
        Reply result;
        try (var workers = Executors.newFixedThreadPool(2); Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            Future<Reply> pending;
            try {
                int holderPid = lockParent(holder, scenario);
                pending = workers.submit(() -> call(post(CREATE), body, actor));
                Wait wait = awaitBlockedInsert(holderPid, pending);
                if (scenario.departmentLock()) {
                    workers.submit(() -> { changeThroughAdmin(scenario); return null; }).get(15, TimeUnit.SECONDS);
                    assertCurrentEmployee(scenario);
                } else {
                    changeInHolder(holder, scenario);
                }
                assertEquals(wait.pid(), awaitBlockedInsert(holderPid, pending).pid(),
                        "The same insert must still be waiting before the holder commits");
                assertEquals(before, templateRows(), "An uncommitted header or grant must not leak while waiting");
                System.out.printf("V164 %s FK_WAIT holder=%d waiter=%d event=%s sql=%s%n", scenario,
                        holderPid, wait.pid(), wait.event(), wait.query().replaceAll("\\s+", " ").trim());
                holder.commit();
            } finally {
                holder.rollback();
            }
            result = pending.get(20, TimeUnit.SECONDS);
        }
        assertCurrentEmployee(scenario);
        System.out.printf("V164 %s expected=%d/%d actual=%d/%d headerAndGrantDelta=%s%n", scenario,
                scenario.status, scenario.code, result.status(), result.code(), !before.equals(templateRows()));
        assertAll(
                () -> assertEquals(scenario.status, result.status(), result.body().toString()),
                () -> assertEquals(scenario.code, result.code(), result.body().toString()),
                () -> assertEquals(List.of(Map.of("method", "POST", "path", CREATE, "http_status", scenario.status)),
                        jdbc.queryForList("select method,path,http_status from sys_audit_log where username=?", actor.username())),
                () -> {
                    if (scenario.code == 0) assertCreated(scenario, result, before);
                    else {
                        assertTrue(result.body().path("data").isNull(), result.body().toString());
                        assertEquals(before, templateRows(), "Refusal must roll back the complete template and grant rows");
                    }
                });
    }

    private int lockParent(Connection holder, Case scenario) throws SQLException {
        String sql = scenario.departmentLock()
                ? "select id,pg_backend_pid() from sys_dept where id=? for update"
                : "select id,pg_backend_pid() from sys_user where id=? for update";
        try (var lock = holder.prepareStatement(sql)) {
            lock.setLong(1, scenario.departmentLock() ? oldDept : actor.id());
            lock.setQueryTimeout(10);
            try (var row = lock.executeQuery()) {
                assertTrue(row.next(), "The intended FK parent must exist");
                return row.getInt(2);
            }
        }
    }

    private Wait awaitBlockedInsert(int holderPid, Future<Reply> pending) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            if (pending.isDone()) fail("Request completed before a real FK wait: " + pending.get());
            var waits = jdbc.query("""
                    select pid,wait_event,query from pg_stat_activity
                    where datname=current_database() and wait_event_type='Lock'
                      and ? = any(pg_blocking_pids(pid))
                    """, (rs, i) -> new Wait(rs.getInt(1), rs.getString(2), rs.getString(3)), holderPid);
            if (!waits.isEmpty()) {
                assertEquals(1, waits.size());
                var wait = waits.getFirst();
                assertTrue(wait.query().stripLeading().toLowerCase(Locale.ROOT).startsWith("insert into emr_template("), wait.query());
                return wait;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("No template INSERT waiting on the held FK row, holder PID " + holderPid);
    }

    private void changeThroughAdmin(Case scenario) throws Exception {
        String path = "/api/system/users/" + actor.id();
        if (scenario.change == Change.ROLLBACK_DISABLE) {
            try (var rolledBack = dataSource.getConnection()) {
                rolledBack.setAutoCommit(false);
                try { disable(rolledBack); } finally { rolledBack.rollback(); }
            }
            return;
        }
        if (scenario.change == Change.NONE) return;
        Reply changed;
        if (scenario.change == Change.DISABLE) {
            changed = call(put(path + "/enabled").queryParam("enabled", "false"), null, admin);
        } else {
            var update = new LinkedHashMap<String, Object>();
            update.put("username", actor.username());
            update.put("realName", actor.username());
            update.put("deptId", currentDepartment(scenario));
            update.put("roleCodes", List.of(currentDuty(scenario)));
            changed = call(put(path), update, admin);
        }
        assertEquals(200, changed.status(), changed.body().toString());
        assertEquals(0, changed.code(), changed.body().toString());
    }

    private void changeInHolder(Connection holder, Case scenario) throws SQLException {
        if (scenario.change == Change.DISABLE) disable(holder);
        if (scenario.change == Change.DOWNGRADE) {
            assertEquals(1, execute(holder, "delete from sys_user_role where user_id=?", actor.id()));
            assertEquals(1, execute(holder, """
                    insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='QUALITY'
                    """, actor.id()));
        }
    }

    private void disable(Connection connection) throws SQLException {
        assertEquals(1, execute(connection, "update sys_user set enabled=false where id=?", actor.id()));
    }

    private int execute(Connection connection, String sql, long id) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.setQueryTimeout(10);
            return statement.executeUpdate();
        }
    }

    private void assertCurrentEmployee(Case scenario) {
        assertEquals(scenario.change != Change.DISABLE,
                jdbc.queryForObject("select enabled from sys_user where id=?", Boolean.class, actor.id()));
        assertEquals(currentDepartment(scenario),
                jdbc.queryForObject("select dept_id from sys_user where id=?", Long.class, actor.id()));
        assertEquals(List.of(currentDuty(scenario)), jdbc.queryForList("""
                select r.code from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=?
                """, String.class, actor.id()));
    }

    private Long currentDepartment(Case scenario) {
        return switch (scenario.change) {
            case TRANSFER -> newDept;
            case CLEAR_DEPT -> null;
            default -> oldDept;
        };
    }

    private String currentDuty(Case scenario) {
        return switch (scenario.change) {
            case REVOKE -> "CASHIER";
            case DOWNGRADE -> "QUALITY";
            case SWITCH_DUTY -> "TECHNICIAN";
            default -> scenario.duty;
        };
    }

    private Reply call(MockHttpServletRequestBuilder request, Object body, Staff who) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            if (body != null) request.contentType("application/json").content(mapper.writeValueAsBytes(body));
            var response = mvc.perform(request.header("Authorization", "Bearer " + who.token())).andReturn().getResponse();
            JsonNode envelope = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            var keys = new HashSet<String>();
            envelope.fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("code", "message", "data"), keys);
            return new Reply(response.getStatus(), envelope);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertCreated(Case scenario, Reply reply, Map<String, List<String>> before) {
        assertTrue(reply.body().path("data").isIntegralNumber(), "Scoped create keeps a numeric ID response");
        long id = reply.body().path("data").longValue();
        var header = jdbc.queryForMap("select * from emr_template where id=?", id);
        assertNotNull(header.remove("created_at"));
        var expected = new LinkedHashMap<String, Object>();
        expected.put("id", id);
        expected.put("dept_id", scenario.departmentLock() ? oldDept : null);
        expected.put("name", tag);
        expected.put("content", CONTENT);
        expected.put("template_type", "CONSENT");
        expected.put("scope", scenario.scope);
        expected.put("owner_id", actor.id());
        expected.put("record_type", "PROGRESS");
        expected.put("enabled", true);
        expected.put("is_default", false);
        expected.put("created_by", actor.id());
        assertEquals(expected, header);

        var grant = jdbc.queryForMap("select * from emr_template_grant where template_id=?", id);
        assertNotNull(grant.remove("id"));
        assertNotNull(grant.remove("granted_at"));
        assertEquals(Map.of("template_id", id, "grantee_type", scenario.departmentLock() ? "DEPT" : "USER",
                "grantee_id", scenario.departmentLock() ? oldDept : actor.id(), "granted_by", actor.id()), grant);
        assertEquals(before.get("headers"), jdbc.queryForList("select to_jsonb(t)::text from emr_template t where id<>? order by id", String.class, id));
        assertEquals(before.get("grants"), jdbc.queryForList("select to_jsonb(g)::text from emr_template_grant g where template_id<>? order by id", String.class, id));
    }

    private Map<String, List<String>> templateRows() {
        return Map.of("headers", jdbc.queryForList("select to_jsonb(t)::text from emr_template t order by id", String.class),
                "grants", jdbc.queryForList("select to_jsonb(g)::text from emr_template_grant g order by id", String.class));
    }

    private long department(String suffix) {
        return jdbc.queryForObject("insert into sys_dept(code,name,type) values (?,?,'CLINICAL') returning id",
                Long.class, tag + suffix, tag + suffix);
    }

    private Staff employee(String suffix, String duty) {
        String name = tag + suffix;
        long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,enabled) values (?,'x',?,?,true) returning id
                """, Long.class, name, name, oldDept);
        assertEquals(1, jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, duty));
        return new Staff(id, name, jwt.issue(name));
    }

    @AfterEach
    void cleanUp() {
        if (actor != null) {
            jdbc.update("delete from emr_template where owner_id=?", actor.id());
            remove(actor);
        }
        if (admin != null) remove(admin);
        jdbc.update("delete from sys_dept where id in (?,?)", oldDept, newDept);
        SecurityContextHolder.clearContext();
    }

    private void remove(Staff staff) {
        jdbc.update("delete from sys_audit_log where username=?", staff.username());
        jdbc.update("delete from sys_user_role where user_id=?", staff.id());
        jdbc.update("delete from sys_user where id=?", staff.id());
    }
}
