package cn.hip.server;

import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.web.InpEmrController;
import cn.hip.medtech.web.EmrFieldController;
import cn.hip.medtech.web.EmrFieldController.FieldReq;
import cn.hip.outpatient.entity.OutpEmr;
import cn.hip.outpatient.entity.OutpSchedule;
import cn.hip.outpatient.repository.OutpScheduleRepository;
import cn.hip.outpatient.service.DoctorStationService;
import cn.hip.outpatient.service.RegistrationService;
import cn.hip.outpatient.web.DoctorStationController;
import cn.hip.outpatient.web.DoctorStationController.SaveEmrRequest;
import cn.hip.platform.core.config.BusinessDates;
import cn.hip.platform.empi.entity.Patient;
import cn.hip.platform.empi.service.PatientService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v45 车道 I：结构化字段定义与病历侧车列（技术偏离表 989★ / 1075★ / 1098★）。
 *
 * <p>三条参数都已答"平台已实现"，而立项时全仓无字段定义表、病历正文是纯文本。本版补的是地基：
 * {@code emr_template_field}（六型元素定义）+ {@code content_json} 侧车列（门诊/住院各一）+
 * 既有保存端点上的一个可空 {@code fields} 参数 + 1098 的结构化元素检索通道。
 *
 * <p><b>本类的头两号价值，是两条互相制衡的护栏：</b>
 * <ol>
 *   <li>§① <b>契约保护</b>——{@link #outpLegacySaveWithoutFieldsIsUnchanged()} /
 *       {@link #inpLegacySaveWithoutFieldsIsUnchanged()} 钉死"不传 {@code fields} 时逐字节不变"：
 *       侧车列为 null、正文一个字符不多、既有下游读 SQL 原文照抄跑一遍结果不变。
 *       这是"只加不改"能否成立的唯一证据。</li>
 *   <li>§③ <b>正文必须渲染</b>——{@link #outpStructuredOnlySaveStillProducesSignableFullText()} /
 *       {@link #inpStructuredOnlySaveRendersReadableFullText()} 钉死反方向：结构化值必须落进
 *       <b>被 CA 签名的那份正文</b>。若谁把渲染那段删了、只写 content_json，门诊侧会在签名时
 *       撞上 4022「病历内容为空不可签名」、住院侧会撞上 9101，<b>用例当场红</b>——
 *       法定病历不能签一个空壳。</li>
 * </ol>
 *
 * <p>§② 六型往返（1075★ 明文的文本/数值/复选/单选/多选/日期，一个不少一个不多）；
 * §④ 校验四码 4024/4025/4026/4027 与字段定义 CRUD；
 * §⑤ 1098 检索：命中、限 200 + truncated、条件非法 4028；§⑥ 注入安全。
 *
 * <p>门诊四条校验用例各自独立成 @Test：控制器虽把 BizException 转成了 {@code R.fail}，
 * <b>服务层抛出的那一刻测试事务已被标记 rollback-only</b>，同一个 @Test 里后续任何"须成功提交"
 * 的步骤都会连坐（同 V44DiagnosisTest / V43EmrSignTest 的约定）。
 * 住院侧的校验落在控制器内、不穿透事务，故可合并成一条。
 */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class V45StructuredEmrTest {

    @Autowired DoctorStationController doctorStationController;
    @Autowired DoctorStationService doctorStationService;
    @Autowired RegistrationService registrationService;
    @Autowired InpEmrController inpEmrController;
    @Autowired InpatientService inpatientService;
    @Autowired EmrFieldController emrFieldController;
    @Autowired PatientService patientService;
    @Autowired OutpScheduleRepository scheduleRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired ObjectMapper objectMapper;
    @Autowired cn.hip.platform.integration.signature.SignatureAdapter signatureAdapter;

    private final Authentication admin = new UsernamePasswordAuthenticationToken("admin", null, List.of());

    /** 1075★ 明文六型 */
    private static final List<String> SIX = List.of("TEXT", "NUMBER", "CHECKBOX", "RADIO", "MULTI", "DATE");

    /** 字段定义返回体的键集合（前端动态表单契约，交车道 J） */
    private static final Set<String> FIELD_DTO_KEYS = Set.of(
            "id", "templateId", "fieldCode", "label", "datatype", "required",
            "sortNo", "valueSet", "placeholder", "unit", "enabled");

    // ==================== 造数据 ====================

    private Authentication userAuth(String username) {
        jdbc.update("insert into sys_user(username, password, real_name, enabled) "
                + "values (?, 'x', ?, true) on conflict (username) do nothing", username, username + "医生");
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private Long newPatient() {
        Patient p = new Patient();
        p.setName("v45结构化" + System.nanoTime());
        p.setSex("U");
        return patientService.register(p).getId();
    }

    /** 一次门诊就诊，返回 registrationId */
    private Long visit() {
        OutpSchedule s = new OutpSchedule();
        s.setDeptId(1L);
        s.setScheduleDate(BusinessDates.today());
        s.setFee(BigDecimal.ZERO);
        s.setCapacity(5);
        s = scheduleRepository.save(s);
        Long rid = registrationService.register(newPatient(), s.getId()).getId();
        doctorStationService.startVisit(rid, null);
        return rid;
    }

    /** 一次住院，返回 admissionId */
    private Long admit() {
        Long bedId = jdbc.queryForObject("select id from inp_bed where status = 'FREE' limit 1", Long.class);
        return inpatientService.admit(newPatient(), 1L, bedId, null, "J18.9", "肺炎",
                new BigDecimal("500"), "CASH", null).getId();
    }

    /** 一张病历模板（emr_template 属车道 H，本类只按既有列插一行，不碰它的新列） */
    private Long template(String name) {
        return jdbc.queryForObject(
                "insert into emr_template(dept_id, name, content, template_type) values (null, ?, '', 'EMR') "
                        + "returning id", Long.class, name + System.nanoTime());
    }

    /** 字段编码：只含字母数字下划线（写侧白名单 ^[A-Za-z0-9_]{1,64}$），加纳秒后缀保证跨用例不撞 */
    private static String code(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    private Long addField(Long templateId, String fieldCode, String label, String datatype,
                          boolean required, int sortNo, List<String> valueSet, String unit) {
        var r = emrFieldController.create(templateId, new FieldReq(
                fieldCode, label, datatype, required, sortNo, valueSet, null, unit, null));
        assertEquals(0, r.getCode(), r.getMessage());
        return ((Number) r.getData().get("id")).longValue();
    }

    private static OutpEmr emrOf(String chiefComplaint, String presentIllness) {
        OutpEmr e = new OutpEmr();
        e.setChiefComplaint(chiefComplaint);
        e.setPresentIllness(presentIllness);
        e.setAdvice("对症治疗");
        return e;
    }

    private Map<String, Object> outpRow(Long registrationId) {
        em.flush();
        em.clear();
        return jdbc.queryForList("select * from outp_emr where registration_id = ?", registrationId).get(0);
    }

    private Map<String, Object> inpRow(Long recordId) {
        em.flush();
        em.clear();
        return jdbc.queryForList("select * from inp_medical_record where id = ?", recordId).get(0);
    }

    private Map<String, Object> jsonOf(Object contentJson) {
        assertNotNull(contentJson, "content_json 不应为 null");
        try {
            return objectMapper.readValue((String) contentJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new AssertionError("content_json 不是合法 JSON：" + contentJson, e);
        }
    }

    // ==================== ① 契约保护：不传 fields 时逐字节不变 ====================

    /**
     * <b>本类最重要的两条之一。</b>门诊病历只传既有参数保存时：
     *
     * <ol>
     *   <li>五段正文<b>逐字符原样落库</b>——不追加任何结构化块、不做任何 strip；</li>
     *   <li>v45 新增的 {@code content_json} / {@code template_id} 两列<b>一律为 null</b>
     *       （历史病历同理，反解正文回填即伪造）；</li>
     *   <li>下游两条按列名点名的 SELECT（{@code CdrSyncService:126} 门诊病历抽取 /
     *       {@code PrintReportController:243} 申请单病史摘要）<b>原文照抄</b>跑一遍，取值不变；</li>
     *   <li>返回体键集合被完整钉死：既有键取值逐字不变，新增两键存在且为 null
     *       （同 v44 加列的口径——前端按可空读；将来谁再往实体上加字段，这条先红）。</li>
     * </ol>
     */
    @Test
    void outpLegacySaveWithoutFieldsIsUnchanged() {
        Authentication doc = userAuth("v45doc_contract");
        Long rid = visit();
        String pi = "受凉后起病 2 天，咳嗽咳痰，无发热。";

        var saved = doctorStationController.saveEmr(rid,
                new SaveEmrRequest(emrOf("咳嗽3天", pi), List.of()), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());

        // ① 正文逐字符不变，且**没有**任何结构化块痕迹
        var row = outpRow(rid);
        assertEquals(pi, row.get("present_illness"), "不传 fields 时现病史必须逐字符原样落库");
        assertEquals("咳嗽3天", row.get("chief_complaint"));
        assertEquals("对症治疗", row.get("advice"));
        assertFalse(String.valueOf(row.get("present_illness")).contains("【结构化记录】"),
                "旧路径绝不能被追加结构化块");

        // ② 侧车列为 null
        assertNull(row.get("content_json"), "不传 fields 时 content_json 必须为 null");
        assertNull(row.get("template_id"), "不传 fields 时 template_id 必须为 null");

        // ③ 下游读 SQL 原文照抄（改这两条 SQL 的行为等于改 CDR 同步与申请单病史摘要）
        var cdr = jdbc.queryForList(
                "select registration_id, chief_complaint, present_illness, advice from outp_emr"
                        + " where registration_id in (?)", rid);
        assertEquals(1, cdr.size(), "CdrSyncService 的门诊病历抽取行数不变");
        assertEquals(pi, cdr.get(0).get("present_illness"));
        assertEquals("对症治疗", cdr.get(0).get("advice"));
        var print = jdbc.queryForList("""
                select chief_complaint, present_illness, physical_exam, advice
                from outp_emr where registration_id = ?
                """, rid);
        assertEquals(1, print.size(), "PrintReportController 的病史摘要行数不变");
        assertEquals("咳嗽3天", print.get(0).get("chief_complaint"));
        assertNull(print.get(0).get("physical_exam"));

        // ④ 返回体键集合
        var body = objectMapper.convertValue(saved.getData(), new TypeReference<Map<String, Object>>() {});
        assertEquals(Set.of("id", "registrationId", "chiefComplaint", "presentIllness", "pastHistory",
                        "physicalExam", "advice", "doctorId", "contentJson", "templateId",
                        "signature", "signedAt", "updatedAt"),
                body.keySet(), "门诊病历返回体键集合被钉死：既有 11 键 + v45 新增 2 键，不得再多");
        assertEquals("咳嗽3天", body.get("chiefComplaint"));
        assertEquals(pi, body.get("presentIllness"));
        assertNull(body.get("contentJson"), "旧调用方不传 fields 时新键必须是 null");
        assertNull(body.get("templateId"));
    }

    /**
     * 住院侧同款契约保护：只传 recordType/title/content 时正文逐字符不变、侧车列为 null，
     * 下游 {@code EmrCopyController:163}（病案复印正文）与 {@code InpatientController:201}
     * （病案首页诊疗经过）两条 SELECT 原文照抄结果不变。
     */
    @Test
    void inpLegacySaveWithoutFieldsIsUnchanged() {
        Long admId = admit();
        String content = "患者一般情况可，无发热，继续观察。";

        var saved = inpEmrController.addRecord(admId,
                new InpEmrController.SaveRecordRequest("PROGRESS", "病程记录", content), admin);
        assertEquals(0, saved.getCode(), saved.getMessage());

        var row = inpRow(saved.getData().getId());
        assertEquals(content, row.get("content"), "不传 fields 时正文必须逐字符原样落库");
        assertFalse(String.valueOf(row.get("content")).contains("【结构化记录】"));
        assertNull(row.get("content_json"), "不传 fields 时 content_json 必须为 null");
        assertNull(row.get("template_id"));

        // 下游读 SQL 原文照抄
        var copy = jdbc.queryForList("""
                select record_type, title, content, created_at, (signature is not null) as signed
                from inp_medical_record where admission_id = ? order by id
                """, admId);
        assertEquals(1, copy.size(), "病案复印正文行数不变");
        assertEquals(content, copy.get(0).get("content"));
        assertEquals(Boolean.FALSE, copy.get(0).get("signed"));
        var front = jdbc.queryForList("""
                select record_type, title, content, created_at,
                       (signature is not null) as signed
                from inp_medical_record
                where admission_id = ?
                order by id
                """, admId);
        assertEquals(content, front.get(0).get("content"), "病案首页诊疗经过取值不变");

        // 返回体：既有键取值不变，新增两键为 null
        var body = objectMapper.convertValue(saved.getData(), new TypeReference<Map<String, Object>>() {});
        assertEquals("PROGRESS", body.get("recordType"));
        assertEquals(content, body.get("content"));
        assertTrue(body.containsKey("contentJson") && body.get("contentJson") == null);
        assertTrue(body.containsKey("templateId") && body.get("templateId") == null);
    }

    /**
     * 契约保护的第三条腿：<b>旧 JSON 请求体照常反序列化</b>。
     *
     * <p>两个请求体 record 各加了两个可空分量，同时保留了老形态的构造器（既有单测源码零改动）。
     * record 带第二个构造器时 Jackson 有"创建器歧义"的历史坑——这条用例直接拿 v44 的 JSON
     * 原文喂给 ObjectMapper，证明前端旧版本与 E2E 脚本发的报文一个字都不用改。
     */
    @Test
    void legacyJsonRequestBodiesStillDeserialize() throws Exception {
        var outp = objectMapper.readValue(
                "{\"emr\":{\"chiefComplaint\":\"咳嗽\",\"presentIllness\":\"两天\"},\"diagnoses\":[]}",
                SaveEmrRequest.class);
        assertEquals("咳嗽", outp.emr().getChiefComplaint());
        assertNotNull(outp.diagnoses());
        assertNull(outp.templateId(), "旧报文没有 templateId，反序列化后必须是 null");
        assertNull(outp.fields(), "旧报文没有 fields —— 为 null 才会走「逐字节不变」的旧路径");

        var inp = objectMapper.readValue(
                "{\"recordType\":\"PROGRESS\",\"title\":\"病程\",\"content\":\"正文\"}",
                InpEmrController.SaveRecordRequest.class);
        assertEquals("PROGRESS", inp.recordType());
        assertEquals("正文", inp.content());
        assertNull(inp.templateId());
        assertNull(inp.fields());
    }

    // ==================== ② 1075★ 六型往返 ====================

    /**
     * 六种 datatype 各自存取往返：TEXT 文本 / NUMBER 数值 / CHECKBOX 复选 / RADIO 单选 /
     * MULTI 多选 / DATE 日期——1075★ 明文这六型，一个不少一个不多。
     *
     * <p>侧车 {@code content_json} 里的 JSON 原生型也一并钉死：数值落数字（不是字符串）、
     * 复选落布尔、多选落数组——这是 1098 检索与前端回填共同依赖的形状。
     */
    @Test
    void allSixDatatypesRoundTrip() {
        Authentication doc = userAuth("v45doc_sixtypes");
        Long tpl = template("六型模板");
        String cText = code("t"), cNum = code("n"), cChk = code("c"),
                cRadio = code("r"), cMulti = code("m"), cDate = code("d");
        addField(tpl, cText, "查体所见", "TEXT", false, 1, null, null);
        addField(tpl, cNum, "体温", "NUMBER", false, 2, null, "℃");
        addField(tpl, cChk, "是否发热", "CHECKBOX", false, 3, null, null);
        addField(tpl, cRadio, "病情程度", "RADIO", false, 4, List.of("轻", "中", "重"), null);
        addField(tpl, cMulti, "伴随症状", "MULTI", false, 5, List.of("咳嗽", "咽痛", "流涕"), null);
        addField(tpl, cDate, "起病日期", "DATE", false, 6, null, null);

        Long rid = visit();
        var fields = new LinkedHashMap<String, Object>();
        fields.put(cText, "咽部充血，扁桃体 I 度肿大");
        fields.put(cNum, "38.5");
        fields.put(cChk, true);
        fields.put(cRadio, "中");
        fields.put(cMulti, List.of("咳嗽", "咽痛"));
        fields.put(cDate, "2026-09-01");

        var saved = doctorStationController.saveEmr(rid,
                new SaveEmrRequest(emrOf("发热3天", "起病急"), List.of(), tpl, fields), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());

        var row = outpRow(rid);
        assertEquals(tpl, ((Number) row.get("template_id")).longValue(), "本份病历用的模板须落库");
        var json = jsonOf(row.get("content_json"));
        assertEquals("咽部充血，扁桃体 I 度肿大", json.get(cText), "TEXT 原样");
        assertEquals(38.5, ((Number) json.get(cNum)).doubleValue(), 1e-9, "NUMBER 必须落 JSON 数字");
        assertEquals(Boolean.TRUE, json.get(cChk), "CHECKBOX 必须落 JSON 布尔");
        assertEquals("中", json.get(cRadio), "RADIO 落字符串");
        assertEquals(List.of("咳嗽", "咽痛"), json.get(cMulti), "MULTI 必须落 JSON 数组");
        assertEquals("2026-09-01", json.get(cDate), "DATE 落 ISO 字符串");
        assertEquals(List.of(cText, cNum, cChk, cRadio, cMulti, cDate), List.copyOf(json.keySet()),
                "侧车键序按 sort_no —— 前端表单顺序与 989★ 的快速跳转 Tab 序同源");
    }

    /** CHECKBOX / MULTI 的宽松回传形态：布尔字符串与逗号分隔串（前端老表单常见）也收得住 */
    @Test
    void checkboxAndMultiAcceptLenientWireForms() {
        Authentication doc = userAuth("v45doc_lenient");
        Long tpl = template("宽松形态");
        String cChk = code("c"), cMulti = code("m");
        addField(tpl, cChk, "吸烟史", "CHECKBOX", false, 1, null, null);
        addField(tpl, cMulti, "过敏原", "MULTI", false, 2, List.of("青霉素", "头孢", "花粉"), null);

        Long rid = visit();
        var saved = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("体检", "无不适"), List.of(), tpl,
                Map.of(cChk, "false", cMulti, "青霉素,花粉")), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());

        var json = jsonOf(outpRow(rid).get("content_json"));
        assertEquals(Boolean.FALSE, json.get(cChk));
        assertEquals(List.of("青霉素", "花粉"), json.get(cMulti));
    }

    // ==================== ③ 正文必须渲染：签名对象完整性 ====================

    /**
     * <b>本类最重要的两条之一。</b>门诊只填结构化字段、五段正文全空时保存，随后<b>必须签得下去</b>。
     *
     * <p>逻辑闭环：门诊签名摘要 = 五段以 '|' 拼接（{@code DoctorStationService.signEmr}），
     * 且 v43 起五段全空会被 <b>4022</b> 挡住。所以——
     * <b>若谁把"渲染进正文"那段删了、只写 content_json，这条用例会在 sign 处直接红</b>，
     * 正是我们要的：法定病历不允许 CA 签一个空壳。
     *
     * <p>同时正向断言正文<b>可读</b>：标签、单位、值、复选的"是/否"、多选的顿号连接都在里面，
     * 不是一坨 {"a":1} 的 JSON。
     */
    @Test
    void outpStructuredOnlySaveStillProducesSignableFullText() {
        Authentication doc = userAuth("v45doc_render");
        Long docId = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, "v45doc_render");
        Long tpl = template("渲染模板");
        String cNum = code("n"), cChk = code("c"), cMulti = code("m");
        addField(tpl, cNum, "体温", "NUMBER", false, 1, null, "℃");
        addField(tpl, cChk, "是否发热", "CHECKBOX", false, 2, null, null);
        addField(tpl, cMulti, "伴随症状", "MULTI", false, 3, List.of("咳嗽", "咽痛"), null);

        Long rid = visit();
        OutpEmr blank = new OutpEmr();   // 五段一个字都不填：正文的唯一来源就是结构化渲染
        var saved = doctorStationController.saveEmr(rid, new SaveEmrRequest(blank, List.of(), tpl,
                Map.of(cNum, "39.1", cChk, true, cMulti, List.of("咳嗽", "咽痛"))), doc);
        assertEquals(0, saved.getCode(), saved.getMessage());

        var row = outpRow(rid);
        String body = String.valueOf(row.get("present_illness"));
        assertTrue(body.contains("体温（℃）：39.1"), "正文须含带单位的可读全文，实际：" + body);
        assertTrue(body.contains("是否发热：是"), "复选须渲染成是/否，实际：" + body);
        assertTrue(body.contains("伴随症状：咳嗽、咽痛"), "多选须顿号连接，实际：" + body);
        assertFalse(body.contains("{"), "正文是给人读的全文，不是 JSON：" + body);

        // 签名对象完整性：签名摘要就是五段拼接，此处逐字重算一遍，确认结构化内容确实在被签的那份里
        String signedText = String.join("|",
                String.valueOf(row.get("chief_complaint")), String.valueOf(row.get("present_illness")),
                String.valueOf(row.get("past_history")), String.valueOf(row.get("physical_exam")),
                String.valueOf(row.get("advice")));
        assertTrue(signedText.contains("39.1") && signedText.contains("咽痛"),
                "结构化值必须落在被签名的那份全文里");

        // 真去签一次：只写侧车不渲染正文的实现会在这里撞 4022「病历内容为空，不可签名」
        var signed = doctorStationService.signEmr(rid, docId, signatureAdapter);
        assertNotNull(signed.getSignature(), "结构化病历必须签得下去（4022 一旦触发即说明正文没渲染）");
    }

    /** 住院同款：content 传 null、只给 fields，仍须渲染出可读正文（否则撞既有 9101） */
    @Test
    void inpStructuredOnlySaveRendersReadableFullText() {
        Long tpl = template("住院渲染");
        String cNum = code("n"), cDate = code("d");
        addField(tpl, cNum, "血压收缩压", "NUMBER", false, 1, null, "mmHg");
        addField(tpl, cDate, "查房日期", "DATE", false, 2, null, null);

        Long admId = admit();
        var saved = inpEmrController.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "PROGRESS", "结构化病程", null, tpl, Map.of(cNum, 138, cDate, "2026-09-02")), admin);
        assertEquals(0, saved.getCode(), saved.getMessage());

        var row = inpRow(saved.getData().getId());
        String body = String.valueOf(row.get("content"));
        assertTrue(body.contains("血压收缩压（mmHg）：138"), "住院正文须含可读全文，实际：" + body);
        assertTrue(body.contains("查房日期：2026-09-02"), body);
        assertEquals(tpl, ((Number) row.get("template_id")).longValue());
        var json = jsonOf(row.get("content_json"));
        assertEquals(138, ((Number) json.get(cNum)).intValue());

        // 住院签名直接签 content 原文——渲染缺席则 content 为空，addRecord 会先撞 9101
        var signed = inpEmrController.signRecord(admId, saved.getData().getId(), admin);
        assertEquals(0, signed.getCode(), signed.getMessage());
    }

    /**
     * 渲染幂等：前端把上一次保存的正文原样回传再存一次，结构化块<b>只能有一份</b>。
     * （表单 → 正文的往返若不幂等，改三次病历就会看到三份重复的结构化段落。）
     */
    @Test
    void renderIsIdempotentOnResave() {
        Authentication doc = userAuth("v45doc_idem");
        Long tpl = template("幂等模板");
        String cNum = code("n");
        addField(tpl, cNum, "体温", "NUMBER", false, 1, null, "℃");

        Long rid = visit();
        var first = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("发热", "起病 1 天"), List.of(), tpl, Map.of(cNum, "38.0")), doc);
        assertEquals(0, first.getCode(), first.getMessage());
        String afterFirst = String.valueOf(outpRow(rid).get("present_illness"));

        // 前端拿到的正文原样回传（含上一次的结构化块），并改了体温
        OutpEmr echo = emrOf("发热", afterFirst);
        var second = doctorStationController.saveEmr(rid,
                new SaveEmrRequest(echo, List.of(), tpl, Map.of(cNum, "39.0")), doc);
        assertEquals(0, second.getCode(), second.getMessage());

        String body = String.valueOf(outpRow(rid).get("present_illness"));
        assertEquals(1, countOf(body, "【结构化记录】"), "结构化块只能有一份，实际正文：" + body);
        assertTrue(body.contains("体温（℃）：39.0"), body);
        assertFalse(body.contains("38.0"), "旧值须被替换而不是叠加：" + body);
        assertTrue(body.startsWith("起病 1 天"), "医生手打的正文须保留在块之前：" + body);
    }

    private static int countOf(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
            n++;
        }
        return n;
    }

    // ==================== ④ 校验：4024 / 4025 / 4026 / 4027 ====================

    /** 4025 必填结构化字段未填 */
    @Test
    void missingRequiredFieldReturns4025() {
        Authentication doc = userAuth("v45doc_4025");
        Long tpl = template("必填模板");
        String cReq = code("r"), cOpt = code("o");
        addField(tpl, cReq, "主诉时长", "TEXT", true, 1, null, null);
        addField(tpl, cOpt, "备注", "TEXT", false, 2, null, null);
        Long rid = visit();

        var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("咳嗽", "两天"), List.of(), tpl, Map.of(cOpt, "无")), doc);
        assertEquals(4025, r.getCode(), r.getMessage());
        assertTrue(r.getMessage().contains("主诉时长"), r.getMessage());
    }

    /** 4026 取值不在值域内 */
    @Test
    void valueOutsideValueSetReturns4026() {
        Authentication doc = userAuth("v45doc_4026");
        Long tpl = template("值域模板");
        String cRadio = code("r");
        addField(tpl, cRadio, "病情程度", "RADIO", false, 1, List.of("轻", "中", "重"), null);
        Long rid = visit();

        var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("咳嗽", "两天"), List.of(), tpl, Map.of(cRadio, "危重")), doc);
        assertEquals(4026, r.getCode(), r.getMessage());
        assertTrue(r.getMessage().contains("危重"), r.getMessage());
    }

    /** 4027 数据类型不匹配 */
    @Test
    void datatypeMismatchReturns4027() {
        Authentication doc = userAuth("v45doc_4027");
        Long tpl = template("类型模板");
        String cNum = code("n");
        addField(tpl, cNum, "体温", "NUMBER", false, 1, null, "℃");
        Long rid = visit();

        var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("咳嗽", "两天"), List.of(), tpl, Map.of(cNum, "三十八度五")), doc);
        assertEquals(4027, r.getCode(), r.getMessage());
        assertTrue(r.getMessage().contains("NUMBER"), r.getMessage());
    }

    /** 4024 传了模板里没有的字段码 */
    @Test
    void unknownFieldCodeReturns4024() {
        Authentication doc = userAuth("v45doc_4024a");
        Long tpl = template("未知字段");
        addField(tpl, code("t"), "查体", "TEXT", false, 1, null, null);
        Long rid = visit();

        var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("咳嗽", "两天"), List.of(), tpl, Map.of("not_defined_code", "x")), doc);
        assertEquals(4024, r.getCode(), r.getMessage());
    }

    /** 4024 字段已停用后不可再录（停用是软开关：定义行还在，历史值仍解释得通） */
    @Test
    void disabledFieldReturns4024() {
        Authentication doc = userAuth("v45doc_4024b");
        Long tpl = template("停用字段");
        String c = code("t");
        Long fid = addField(tpl, c, "查体", "TEXT", false, 1, null, null);
        assertEquals(0, emrFieldController.disable(fid).getCode());
        assertEquals(4024, emrFieldController.disable(fid).getCode(), "重复停用返 4024");
        Long rid = visit();

        var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("咳嗽", "两天"), List.of(), tpl, Map.of(c, "咽红")), doc);
        assertEquals(4024, r.getCode(), r.getMessage());
    }

    /** 4024 传了 fields 却没指定 templateId：无从解析字段定义 */
    @Test
    void fieldsWithoutTemplateIdReturns4024() {
        Authentication doc = userAuth("v45doc_4024c");
        Long rid = visit();
        var r = doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("咳嗽", "两天"), List.of(), null, Map.of("x", "y")), doc);
        assertEquals(4024, r.getCode(), r.getMessage());
    }

    /**
     * 住院侧四码对称：走 {@code R.fail} 不抛异常，故可合并一条。
     * 两个模块各持一份同源的校验实现（模块依赖所限），这条与门诊四条一起构成对称护栏。
     */
    @Test
    void inpStructuredValidationMirrorsOutpatientCodes() {
        Long tpl = template("住院校验");
        String cReq = code("r"), cRadio = code("s"), cNum = code("n");
        addField(tpl, cReq, "入院诊断", "TEXT", true, 1, null, null);
        addField(tpl, cRadio, "护理级别", "RADIO", false, 2, List.of("一级", "二级", "三级"), null);
        addField(tpl, cNum, "体重", "NUMBER", false, 3, null, "kg");
        Long admId = admit();

        assertEquals(4025, inpEmrController.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "PROGRESS", "t", "正文", tpl, Map.of(cNum, 60)), admin).getCode());
        assertEquals(4026, inpEmrController.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "PROGRESS", "t", "正文", tpl, Map.of(cReq, "肺炎", cRadio, "特级")), admin).getCode());
        assertEquals(4027, inpEmrController.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "PROGRESS", "t", "正文", tpl, Map.of(cReq, "肺炎", cNum, "六十")), admin).getCode());
        assertEquals(4024, inpEmrController.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "PROGRESS", "t", "正文", tpl, Map.of(cReq, "肺炎", "ghost_code", "x")), admin).getCode());
        assertEquals(4024, inpEmrController.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "PROGRESS", "t", "正文", null, Map.of(cReq, "肺炎")), admin).getCode());
        // 一条都不该落库
        em.flush();
        assertEquals(0, jdbc.queryForObject(
                "select count(1) from inp_medical_record where admission_id = ?", Integer.class, admId));
    }

    /**
     * 字段定义 CRUD 与前端渲染契约：
     * datatype 非白名单 4027、单选多选未配候选值 4026、目标不存在 4024、
     * 返回体键集合钉死（车道 J 的动态表单按这组键渲染）。
     */
    @Test
    void fieldDefinitionCrudContract() {
        Long tpl = template("CRUD");
        String c = code("t");

        // 建：返回体形状即前端契约
        var created = emrFieldController.create(tpl, new FieldReq(
                c, "查体所见", "TEXT", true, 3, null, "请描述阳性体征", null, null));
        assertEquals(0, created.getCode(), created.getMessage());
        assertEquals(FIELD_DTO_KEYS, created.getData().keySet(), "字段定义返回体键集合被钉死（车道 J 契约）");
        assertEquals("TEXT", created.getData().get("datatype"));
        assertEquals(Boolean.TRUE, created.getData().get("required"));
        assertEquals(List.of(), created.getData().get("valueSet"), "非单选多选的 valueSet 恒为空数组，不是 null 也不是字符串");
        Long fid = ((Number) created.getData().get("id")).longValue();

        // 查：按 sort_no 升序，缺省只给启用中的
        Long fid2 = addField(tpl, code("r"), "程度", "RADIO", false, 1, List.of("轻", "重"), null);
        var list = emrFieldController.fields(tpl, null).getData();
        assertEquals(2, list.size());
        assertEquals(fid2, ((Number) list.get(0).get("id")).longValue(), "sort_no 小的在前（989★ 的跳转序）");
        assertEquals(List.of("轻", "重"), list.get(0).get("valueSet"), "候选值出接口时已解析成数组");

        // 改：fieldCode 不可改（历史 content_json 的键），其余可改
        var updated = emrFieldController.update(fid, new FieldReq(
                "IGNORED_CODE", "查体所见（修订）", "TEXT", false, 9, null, null, null, true));
        assertEquals(0, updated.getCode(), updated.getMessage());
        assertEquals(c, updated.getData().get("fieldCode"), "fieldCode 刻意不可改");
        assertEquals("查体所见（修订）", updated.getData().get("label"));

        // 错误码
        assertEquals(4027, emrFieldController.create(tpl, new FieldReq(
                code("x"), "富文本", "RICHTEXT", false, 1, null, null, null, null)).getCode(),
                "datatype 非白名单必须 4027（1075★ 只有六型）");
        assertEquals(4027, emrFieldController.create(tpl, new FieldReq(
                "bad-code!", "编码非法", "TEXT", false, 1, null, null, null, null)).getCode());
        assertEquals(4027, emrFieldController.create(tpl, new FieldReq(
                c, "重复编码", "TEXT", false, 1, null, null, null, null)).getCode());
        assertEquals(4026, emrFieldController.create(tpl, new FieldReq(
                code("r"), "没候选值的单选", "RADIO", false, 1, null, null, null, null)).getCode(),
                "单选/多选未配候选值必须 4026");
        assertEquals(4024, emrFieldController.update(-1L, new FieldReq(
                c, "x", "TEXT", false, 1, null, null, null, null)).getCode());
        assertEquals(4024, emrFieldController.disable(-1L).getCode());
        assertEquals(4024, emrFieldController.create(-1L, new FieldReq(
                code("t"), "模板不存在", "TEXT", false, 1, null, null, null, null)).getCode());
    }

    /** 1075★ 六型是硬边界：数据库 CHECK 与代码白名单必须同为这六个，多一个少一个都不行 */
    @Test
    void datatypeWhitelistIsExactlySixEverywhere() {
        assertEquals(SIX, EmrFieldController.DATATYPES, "代码白名单必须是 1075★ 明文的六型");
        String def = jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_emr_tpl_field_type'",
                String.class);
        assertNotNull(def, "V139 的 datatype CHECK 约束必须存在");
        for (String t : SIX) {
            assertTrue(def.contains("'" + t + "'"), "CHECK 缺少 " + t + "：" + def);
        }
        // 数据库层兜底：第七型写不进去（放在最后——本条会中止事务）
        Long tpl = template("六型边界");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("insert into emr_template_field(template_id, field_code, label, datatype) "
                        + "values (?, 'x', 'x', 'RICHTEXT')", tpl));
    }

    // ==================== ⑤ 1098★ 结构化元素检索 ====================

    /** 按结构化元素编码与取值检索，门诊住院同形返回；不填 value 则返回"填过这个元素"的全部病历 */
    @Test
    void fieldSearchFindsByElementValue() {
        Authentication doc = userAuth("v45doc_search");
        Long tpl = template("检索模板");
        String c = code("sym");
        addField(tpl, c, "伴随症状", "MULTI", false, 1, List.of("咳嗽", "咽痛", "腹泻"), null);

        Long rid = visit();
        assertEquals(0, doctorStationController.saveEmr(rid, new SaveEmrRequest(
                emrOf("发热", "起病急"), List.of(), tpl, Map.of(c, List.of("咳嗽", "咽痛"))), doc).getCode());
        Long admId = admit();
        assertEquals(0, inpEmrController.addRecord(admId, new InpEmrController.SaveRecordRequest(
                "PROGRESS", "住院结构化", "查房", tpl, Map.of(c, List.of("腹泻"))), admin).getCode());
        em.flush();

        var all = emrFieldController.fieldSearch(c, null, null, null, null).getData();
        assertEquals(2, all.get("total"), "两条（门诊 1 + 住院 1）都应命中");
        assertEquals(Boolean.FALSE, all.get("truncated"));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) all.get("items");
        assertEquals(Set.of("OUTP", "INP"), items.stream().map(m -> m.get("emrType"))
                .collect(java.util.stream.Collectors.toSet()), "门诊与住院同形返回，靠 emrType 区分");
        assertEquals(Set.of("emrType", "emrId", "visitId", "visitNo", "patientId", "patientName",
                        "deptName", "title", "templateId", "templateName", "fieldValue", "recordedAt"),
                items.get(0).keySet(), "检索命中行的键集合被钉死（车道 J 契约）");

        // 按取值收窄
        var cough = emrFieldController.fieldSearch(c, "咽痛", null, null, null).getData();
        assertEquals(1, cough.get("total"));
        var outpOnly = emrFieldController.fieldSearch(c, null, null, null, "OUTP").getData();
        assertEquals(1, outpOnly.get("total"));
        var inpOnly = emrFieldController.fieldSearch(c, null, null, null, "INP").getData();
        assertEquals(1, inpOnly.get("total"));

        // 时间区间：今天之后的窗口应为空
        var future = emrFieldController.fieldSearch(c, null,
                LocalDate.now().plusDays(3), LocalDate.now().plusDays(5), null).getData();
        assertEquals(0, future.get("total"));

        // 另一个元素码不该串味
        assertEquals(0, emrFieldController.fieldSearch(code("other"), null, null, null, null)
                .getData().get("total"));
    }

    /** 超限：照抄 mr-workqueue 的限 200 条 + truncated 标记纪律，不做翻页 */
    @Test
    void fieldSearchLimitsTo200WithTruncatedFlag() {
        Long admId = admit();
        String c = code("bulk");
        var batch = new java.util.ArrayList<Object[]>();
        for (int i = 0; i < 201; i++) {
            batch.add(new Object[]{admId, "结构化压测第 " + i + " 条", "{\"" + c + "\":\"批量" + i + "\"}"});
        }
        jdbc.batchUpdate("insert into inp_medical_record(admission_id, record_type, title, content, content_json) "
                + "values (?, 'PROGRESS', '压测', ?, ?)", batch);

        var r = emrFieldController.fieldSearch(c, null, null, null, "INP").getData();
        assertEquals(200, r.get("total"), "硬上限 200 条");
        assertEquals(200, r.get("limit"));
        assertEquals(Boolean.TRUE, r.get("truncated"), "超限须置 truncated 标记");
    }

    /** 4028 检索条件非法 */
    @Test
    void fieldSearchRejectsIllegalConditionsWith4028() {
        assertEquals(4028, emrFieldController.fieldSearch(null, null, null, null, null).getCode());
        assertEquals(4028, emrFieldController.fieldSearch("  ", null, null, null, null).getCode());
        assertEquals(4028, emrFieldController.fieldSearch("bad code", null, null, null, null).getCode());
        assertEquals(4028, emrFieldController.fieldSearch("a".repeat(65), null, null, null, null).getCode());
        assertEquals(4028, emrFieldController.fieldSearch("ok_code", "v".repeat(129), null, null, null).getCode());
        assertEquals(4028, emrFieldController.fieldSearch("ok_code", null,
                LocalDate.now(), LocalDate.now().minusDays(1), null).getCode(), "起始晚于截止");
        assertEquals(4028, emrFieldController.fieldSearch("ok_code", null, null, null, "EMERGENCY").getCode());
    }

    // ==================== ⑥ 注入安全 ====================

    /**
     * fieldCode 与 value 一律参数化，且 value 的 like 元字符先转义：
     * <ul>
     *   <li>带引号/分号的 fieldCode 过不了白名单（4028），根本进不到 SQL；</li>
     *   <li>value 传 {@code %} 只匹配<b>真的含 %</b> 的病历，<b>不退化成匹配全部</b>；</li>
     *   <li>value 传经典注入串返回 0 条而不是报错、更不是全表。</li>
     * </ul>
     */
    @Test
    void searchIsInjectionSafe() {
        Authentication doc = userAuth("v45doc_inject");
        Long tpl = template("注入");
        String c = code("pct");
        addField(tpl, c, "纯度描述", "TEXT", false, 1, null, null);

        Long ridPct = visit();
        assertEquals(0, doctorStationController.saveEmr(ridPct, new SaveEmrRequest(
                emrOf("A", "a"), List.of(), tpl, Map.of(c, "100%纯度")), doc).getCode());
        Long ridPlain = visit();
        assertEquals(0, doctorStationController.saveEmr(ridPlain, new SaveEmrRequest(
                emrOf("B", "b"), List.of(), tpl, Map.of(c, "无异常")), doc).getCode());
        em.flush();

        assertEquals(2, emrFieldController.fieldSearch(c, null, null, null, "OUTP").getData().get("total"),
                "不带 value 时两条都在");

        // like 元字符：% 必须当字面量
        var pct = emrFieldController.fieldSearch(c, "%", null, null, "OUTP").getData();
        assertEquals(1, pct.get("total"), "搜 % 只能命中真的含 % 的那条，不得退化成匹配全部");
        @SuppressWarnings("unchecked")
        var hits = (List<Map<String, Object>>) pct.get("items");
        assertEquals("100%纯度", hits.get(0).get("fieldValue"));
        assertEquals(0, emrFieldController.fieldSearch(c, "_", null, null, "OUTP").getData().get("total"),
                "下划线同样被转义：两条取值都不含 _，命中 0（未转义时 ilike '%_%' 会命中全部）");

        // 经典注入串：参数化后只是普通字符串
        assertEquals(0, emrFieldController.fieldSearch(c, "' or 1=1 --", null, null, "OUTP")
                .getData().get("total"));
        assertEquals(0, emrFieldController.fieldSearch(c, "'; drop table outp_emr; --", null, null, "OUTP")
                .getData().get("total"));
        // 表还在（上一条若真被执行，这里会炸）
        assertNotNull(jdbc.queryForObject("select count(1) from outp_emr", Integer.class));

        // fieldCode 侧：引号/分号根本进不到 SQL
        assertEquals(4028, emrFieldController.fieldSearch("a' or '1'='1", null, null, null, null).getCode());
        assertEquals(4028, emrFieldController.fieldSearch("x; drop table outp_emr", null, null, null, null).getCode());
    }
}
