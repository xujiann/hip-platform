package cn.hip.server;

import cn.hip.medtech.service.EmrTemplateService.TemplateReq;
import cn.hip.medtech.web.MedTechController;
import cn.hip.medtech.web.MedTechController.EmrTemplateReq;
import cn.hip.platform.core.common.HipBizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1073★/1078★ 上调复核审计者点名的三处（老通道零范围判定 / 老通道列表泄露他人个人模板 / 改范围不清旧授权）。
 *
 * <p>老通道两个端点签名被 V38/V42/V45 按 1 参/2 参写死、只能从 SecurityContext 取登录人，
 * 所以本用例不用 {@code @WithMockUser}，逐个用例往 SecurityContextHolder 放真实落库的用户
 * （{@code @AfterEach} 清掉，不污染同 JVM 的其它用例）。
 * 全部 {@code @Transactional}：只断言落库值与返回码，不测回滚语义（方法论④）。
 */
@SpringBootTest
@Transactional
class V77TemplateLegacyGuardTest {

    @Autowired MedTechController medTech;
    @Autowired JdbcTemplate jdbc;

    private record TestUser(Long id, Authentication auth) {}

    private static String uniq(String prefix) {
        return prefix + System.nanoTime() % 100000000L;
    }

    private Long newDept() {
        String code = uniq("V77D");
        return jdbc.queryForObject("""
                insert into sys_dept(parent_id, name, code, type, sort_no)
                values (null, ?, ?, 'CLINICAL', 999) returning id
                """, Long.class, "V77模板科" + code, code);
    }

    private TestUser newUser(Long deptId, String roleCode) {
        String username = uniq("v77u");
        Long id = jdbc.queryForObject("""
                insert into sys_user(username, password, real_name, dept_id, enabled)
                values (?, 'x', ?, ?, true) returning id
                """, Long.class, username, username + "医生", deptId);
        jdbc.update("insert into sys_user_role(user_id, role_id) select ?, r.id from sys_role r where r.code = ?",
                id, roleCode);
        return new TestUser(id, new UsernamePasswordAuthenticationToken(username, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))));
    }

    private void actAs(Authentication auth) {
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void assertBiz(int code, org.junit.jupiter.api.function.Executable call) {
        HipBizException e = assertThrows(HipBizException.class, call);
        assertEquals(code, e.code, "错误码应为 " + code + "，实际 " + e.code + "：" + e.getMessage());
    }

    /** N1：老通道建模板——非管理员建全院模板 4066、给他科建科室模板 4066、建本科室的放行且自动授权 */
    @Test
    void legacyCreateIsScopeGuarded() {
        Long deptA = newDept();
        Long deptB = newDept();
        TestUser doc = newUser(deptA, "DOCTOR_OUTP");
        actAs(doc.auth());
        assertBiz(4066, () -> medTech.createTemplate(new EmrTemplateReq(null, "V77越权全院", "正文", "EMR")));
        assertBiz(4066, () -> medTech.createTemplate(new EmrTemplateReq(deptB, "V77越权他科", "正文", "EMR")));
        assertEquals(0, medTech.createTemplate(new EmrTemplateReq(deptA, "V77本科室", "正文", "EMR")).getCode());
        Long id = jdbc.queryForObject("select id from emr_template where name = 'V77本科室' order by id desc limit 1", Long.class);
        assertEquals(doc.id(), jdbc.queryForObject("select owner_id from emr_template where id = ?", Long.class, id));
        assertEquals(1, (int) jdbc.queryForObject(
                "select count(*) from emr_template_grant where template_id = ? and grantee_type = 'DEPT' and grantee_id = ?",
                Integer.class, id, deptA));
        assertEquals(0, (int) jdbc.queryForObject("select count(*) from emr_template where name like 'V77越权%'", Integer.class),
                "被拒的两次零落库");

        // 管理员照旧能建全院与任意科室（三个历史用例与两条 E2E 都是管理员调用）
        TestUser admin = newUser(deptA, "ADMIN");
        actAs(admin.auth());
        assertEquals(0, medTech.createTemplate(new EmrTemplateReq(null, "V77管理员全院", "正文", "EMR")).getCode());
        assertEquals(0, medTech.createTemplate(new EmrTemplateReq(deptB, "V77管理员他科", "正文", "EMR")).getCode());
    }

    /** N2：老通道列表——他人的个人模板不再出现（连正文一起泄露的那条路堵死）；本人与被授权者仍看得到 */
    @Test
    void legacyListHidesOthersPersonalTemplates() {
        Long deptA = newDept();
        TestUser owner = newUser(deptA, "DOCTOR_OUTP");
        TestUser peer = newUser(deptA, "DOCTOR_OUTP");
        TestUser tech = newUser(null, "TECHNICIAN");
        actAs(owner.auth());   // @PreAuthorize 要求 SecurityContext 里有人，形参 auth 只是取 id 用
        Long personal = medTech.createScopedTemplate(
                new TemplateReq("V77个人私藏", "只有我能看", "EMR", "PERSONAL", null, null), owner.auth()).getData();

        actAs(owner.auth());
        assertTrue(contains(medTech.templates(null, "EMR").getData(), personal), "本人可见");
        actAs(peer.auth());
        assertFalse(contains(medTech.templates(deptA, "EMR").getData(), personal), "同科室同事不可见");
        actAs(tech.auth());
        assertFalse(contains(medTech.templates(null, "EMR").getData(), personal), "他角色不可见");

        // 授权给同事后可见（授权只放大可见范围）
        actAs(owner.auth());
        medTech.grantTemplate(personal, new MedTechController.GrantReq("USER", peer.id()), owner.auth());
        actAs(peer.auth());
        assertTrue(contains(medTech.templates(deptA, "EMR").getData(), personal), "被授权后可见");

        // 返回体键集照旧（V45 §① 钉的五键仍在）
        actAs(owner.auth());
        Map<String, Object> row = medTech.templates(null, "EMR").getData().stream()
                .filter(r -> personal.equals(((Number) r.get("id")).longValue())).findFirst().orElseThrow();
        for (String k : List.of("id", "dept_id", "name", "content", "template_type")) {
            assertTrue(row.containsKey(k), "老通道返回体键 " + k + " 不得丢");
        }
    }

    /** N3：改作用范围清空旧授权，再按新范围自动授权——科室模板改成个人模板后整科不再看得见 */
    @Test
    void changingScopeClearsStaleGrants() {
        Long deptA = newDept();
        Long deptB = newDept();
        TestUser owner = newUser(deptA, "DOCTOR_OUTP");
        TestUser peer = newUser(deptA, "DOCTOR_OUTP");
        actAs(owner.auth());
        Long id = medTech.createScopedTemplate(
                new TemplateReq("V77改范围", "正文", "EMR", "DEPT", deptA, null), owner.auth()).getData();
        medTech.grantTemplate(id, new MedTechController.GrantReq("DEPT", deptB), owner.auth());
        assertEquals(2, medTech.templateGrants(id, owner.auth()).getData().size(), "自授权 + 手工授权");

        medTech.updateTemplate(id, new TemplateReq("V77改范围", "正文", "EMR", "PERSONAL", null, null), owner.auth());
        List<Map<String, Object>> grants = medTech.templateGrants(id, owner.auth()).getData();
        assertEquals(1, grants.size(), "改成个人模板后只剩本人的自授权");
        assertEquals("USER", grants.get(0).get("grantee_type"));
        actAs(peer.auth());
        assertFalse(contains(medTech.visibleTemplates(null, null, null, null, false, peer.auth()).getData(), id),
                "同科室同事不再看得见这张'个人'模板");
    }

    private static boolean contains(List<Map<String, Object>> rows, Long id) {
        return rows.stream().anyMatch(r -> id.equals(((Number) r.get("id")).longValue()));
    }
}
