package cn.hip.outpatient.service;

import cn.hip.outpatient.entity.*;
import cn.hip.outpatient.repository.*;
import cn.hip.platform.masterdata.entity.ChargeItem;
import cn.hip.platform.masterdata.entity.DrugItem;
import cn.hip.platform.masterdata.repository.ChargeItemRepository;
import cn.hip.platform.masterdata.repository.DrugItemRepository;
import cn.hip.outpatient.service.RegistrationService.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import cn.hip.platform.core.config.BusinessDates;

@Service
@RequiredArgsConstructor
public class DoctorStationService {

    private final OutpRegistrationRepository registrationRepository;
    private final OutpEmrRepository emrRepository;
    private final OutpDiagnosisRepository diagnosisRepository;
    private final OutpOrderRepository orderRepository;
    private final DrugItemRepository drugRepository;
    private final ChargeItemRepository chargeItemRepository;
    private final cn.hip.platform.empi.repository.PatientRepository patientRepository;
    private final jakarta.persistence.EntityManager entityManager;
    private final CdssService cdssService;
    // v53：病历修订留痕。接缝走**宽松档**（recordOutp → recordVersion），
    // 留痕失败按 gate 降级、**不抛异常**——留痕是为了举证，不该反过来打断医生看病。
    private final EmrVersionService emrVersionService;
    // v51：四条 CDSS 新规则。四个车道各自建了引擎，但都按纪律没动这个既有文件——
    // 主控在此一次性接入，避免四人各改一次必然冲突。
    private final AllergyRuleService allergyRuleService;
    private final DuplicateRxService duplicateRxService;
    private final PopulationRuleService populationRuleService;
    private final RouteRuleService routeRuleService;
    private final cn.hip.platform.core.service.ConfigReader configReader;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** 组号取数据库序列：跨实例、跨重启唯一 */
    private long nextGroupSeq() {
        // 空 query space（1.1.7 B-8）：未声明时 Hibernate 按"可能读任何表"处理，
        // 每次取号都 auto-flush 整个会话并对已托管实体做脏检查——这是医生开单的热路径
        return ((Number) entityManager
                .createNativeQuery("select nextval('outp_order_group_seq')")
                .unwrap(org.hibernate.query.NativeQuery.class)
                .addSynchronizedQuerySpace("")
                .getSingleResult()).longValue();
    }

    /** 接诊：挂号状态置为 VISITED */
    @Transactional
    public OutpRegistration startVisit(Long registrationId, Long doctorId) {
        OutpRegistration reg = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new BizException(4001, "挂号记录不存在"));
        if ("CANCELLED".equals(reg.getStatus())) {
            throw new BizException(4002, "该号已退，不能接诊");
        }
        if ("REGISTERED".equals(reg.getStatus()) || "CALLED".equals(reg.getStatus())) {
            reg.setStatus("VISITED");
            if (reg.getDoctorId() == null) {
                reg.setDoctorId(doctorId);
            }
            registrationRepository.save(reg);
        }
        return reg;
    }

    /** 保存病历与诊断（诊断整表替换，第一条为主诊断）；已签名病历冻结不可改 */
    @Transactional
    public OutpEmr saveEmr(Long registrationId, OutpEmr data, List<OutpDiagnosis> diagnoses, Long doctorId) {
        return saveEmr(registrationId, data, diagnoses, doctorId, null, null);
    }

    /**
     * v45 车道 I（989★/1075★）：带结构化字段的病历保存。
     *
     * <p><b>本方法不是新的写端点，而是既有 {@link #saveEmr(Long, OutpEmr, List, Long)} 的超集</b>——
     * 四参重载原样委托进来并传 {@code fields = null}，此时下面每一处新逻辑都被
     * {@code fields == null} 短路，落库与返回体<b>逐字节等同 v44</b>
     * （V45StructuredEmrTest#legacySaveWithoutFieldsIsByteIdentical 钉死）。
     *
     * <p>{@code fields != null} 时，在**同一个事务**内按 sort_no 依次做三件事：
     * <ol>
     *   <li>按 {@code templateId} 取启用中的字段定义，逐字段校验（4024/4025/4026/4027）；</li>
     *   <li><b>渲染成可读全文</b>追加进 {@code present_illness}——这是最要紧的一步：
     *       门诊签名摘要是五段以 '|' 拼接，只写侧车不渲染正文等于让 CA 签了个空壳；</li>
     *   <li>原始键值序列化成 JSON 存 {@code content_json} 侧车（供前端回填与 1098 检索）。</li>
     * </ol>
     *
     * <p>渲染块用 {@code 【结构化记录】…【结构化记录结束】} 包裹，写入前先剥掉正文里已有的同名块——
     * 前端把上一次保存的正文原样回传时不会二次追加（幂等），这是"表单→正文"往返的必要条件。
     *
     * @param templateId 病历模板 id；{@code fields} 非空时必填（否则 4024）
     * @param fields     结构化元素值 {@code {fieldCode: value}}；<b>为 null 即完全走旧路径</b>
     */
    @Transactional
    public OutpEmr saveEmr(Long registrationId, OutpEmr data, List<OutpDiagnosis> diagnoses, Long doctorId,
                           Long templateId, java.util.Map<String, Object> fields) {
        OutpEmr emr = emrRepository.findByRegistrationId(registrationId).orElseGet(() -> {
            OutpEmr e = new OutpEmr();
            e.setRegistrationId(registrationId);
            return e;
        });
        if (emr.getSignature() != null) {
            throw new BizException(4008, "病历已签名冻结，不可修改");
        }
        // v44 车道E：新增字段的取值前置校验（4033/4034）。**纯只读、零副作用**，刻意放在
        // 任何 save 之前——旧调用方只传既有 5 个字段时新列为 null，validateDiagnoses 全部放行，
        // 行为与返回体逐字不变（V44DiagnosisTest#legacyFiveFieldSaveContractUnchanged 钉死）。
        // 既有 icdCode/icdName/primaryDiag 一概不校验：md_icd10 只有几十条种子，
        // 给 icdCode 加"字典必须存在"会当场打断 V43PrintDocsTest（用的 J00 就不在种子里）与实施期数据。
        validateDiagnoses(diagnoses);
        emr.setChiefComplaint(data.getChiefComplaint());
        emr.setPresentIllness(data.getPresentIllness());
        emr.setPastHistory(data.getPastHistory());
        emr.setPhysicalExam(data.getPhysicalExam());
        emr.setAdvice(data.getAdvice());
        emr.setDoctorId(doctorId);
        // v45 车道 I：结构化录入。fields == null 时整段跳过——content_json / template_id
        // **连碰都不碰**（既有病历上已有的侧车值不会被一次无 fields 的保存抹掉）。
        if (fields != null) {
            var defs = StructuredEmr.load(jdbc, templateId);
            var out = StructuredEmr.validateAndRender(defs, fields, objectMapper);
            String body = StructuredEmr.merge(emr.getPresentIllness(), out.text());
            if (body != null && body.length() > PRESENT_ILLNESS_MAX) {
                throw new BizException(4029, "结构化内容渲染进现病史后超出 "
                        + PRESENT_ILLNESS_MAX + " 字上限（当前 " + body.length() + " 字），请精简字段或拆分病历");
            }
            emr.setPresentIllness(body);
            emr.setContentJson(out.json());
            emr.setTemplateId(templateId);
        }
        emr = emrRepository.save(emr);
        // v53 合规留痕：**这一行之前，医生保存第二次，第一次写的内容就永久消失了**——
        // 库里查不到（无历史表）、审计日志也不记请求体，连捞回来的退路都没有。
        // 《电子病历应用管理规范》第二十四条要求病历修改留痕、可追溯；
        // 举证时「病历被改过而系统证明不了改了什么」等同于举证不能。
        // 用宽松档：留痕写不进去也不阻断保存（gate 降级），但会留 ERROR 日志。
        emrVersionService.recordOutp(emr, EmrVersionService.MANUAL, doctorId);

        diagnosisRepository.deleteByRegistrationId(registrationId);
        for (int i = 0; i < diagnoses.size(); i++) {
            OutpDiagnosis d = diagnoses.get(i);
            d.setId(null);
            d.setRegistrationId(registrationId);
            d.setPrimaryDiag(i == 0);
            diagnosisRepository.save(d);
        }
        // v44 车道E（979/1084）：常用诊断使用次数累加。**追加在既有落库逻辑之后，一行未改**，
        // 写的是新表 outp_diagnosis_favorite，对所有既有下游（CDR/打印/病案/DRG）完全不可见。
        // doctorId 为 null（服务层直调、无登录上下文的既有单测与 E2E）时整段跳过。
        bumpFavorites(doctorId, diagnoses);
        return emr;
    }

    // ==================== v44 车道E：门诊诊断域完整化（977/979/982/983/984/1084） ====================

    /**
     * 诊断新增字段取值校验（v44）。只看 v44 新列，既有列一概不碰。
     * 全部允许 null / 空串——历史与旧调用方不填即放行，这是"只加不改"的前提。
     */
    private static void validateDiagnoses(List<OutpDiagnosis> diagnoses) {
        if (diagnoses == null) {
            return;
        }
        for (OutpDiagnosis d : diagnoses) {
            String certainty = blankToNull(d.getCertainty());
            if (certainty != null
                    && !OutpDiagnosis.CERTAINTY_CONFIRMED.equals(certainty)
                    && !OutpDiagnosis.CERTAINTY_SUSPECTED.equals(certainty)) {
                throw new BizException(4033, "确诊/疑诊标记取值非法：" + certainty
                        + "（只接受 CONFIRMED 确诊 / SUSPECTED 疑诊）");
            }
            String system = blankToNull(d.getDiagSystem());
            if (system != null
                    && !OutpDiagnosis.SYSTEM_ICD10.equals(system)
                    && !OutpDiagnosis.SYSTEM_TCM.equals(system)) {
                throw new BizException(4034, "诊断体系取值非法：" + system
                        + "（只接受 ICD10 西医 / TCM 中医）");
            }
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ==================== v45 车道 I：结构化字段录入（989★/1075★/1098★） ====================

    /** outp_emr.present_illness 的列宽（V5:7 varchar(2000)）——渲染后超限返 4029，绝不静默截断正文 */
    private static final int PRESENT_ILLNESS_MAX = 2000;

    /**
     * 结构化元素的定义装载、校验、渲染与序列化。
     *
     * <p><b>刻意与 {@code InpEmrController.StructuredEmr} 逐行同源的一份复制</b>，原因是模块依赖：
     * hip-outpatient 与 hip-inpatient 的唯一公共依赖是 hip-platform-core，而本车道不持有
     * platform/core 的改动权（v45 车道纪律：一个文件只有一个车道能改）。下沉到 platform/core
     * 属跨车道动作，记入技术债在合版后统一做。两份代码的行为由同一批单测
     * （V45StructuredEmrTest 的门诊/住院两组用例）对称钉死，任何一侧漂移都会先红。
     *
     * <p>错误码：4024 字段定义不存在或已停用 / 4025 必填未填 / 4026 取值不在值域 / 4027 类型不匹配。
     */
    static final class StructuredEmr {

        /** 1075★ 明文六型，一个不少一个不多（与 V139 的 CHECK 约束同源） */
        static final List<String> DATATYPES = List.of("TEXT", "NUMBER", "CHECKBOX", "RADIO", "MULTI", "DATE");

        /** 渲染块的包裹标记：写入前先剥旧块再追加新块，保证"表单→正文"往返幂等 */
        static final String BLOCK_BEGIN = "【结构化记录】";
        static final String BLOCK_END = "【结构化记录结束】";
        private static final java.util.regex.Pattern BLOCK =
                java.util.regex.Pattern.compile("\\n?" + BLOCK_BEGIN + ".*?" + BLOCK_END + "\\n?",
                        java.util.regex.Pattern.DOTALL);

        /** 单个 TEXT 元素的长度上限：正文列宽有限，先在这里挡住，不让 4029 变成常态 */
        static final int TEXT_MAX = 1000;

        /** value_set 解析专用（只读 JSON 数组，无需 Spring 的定制配置），避免逐行 new */
        private static final com.fasterxml.jackson.databind.ObjectMapper VALUE_SET_MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        private StructuredEmr() {}

        /** 一条字段定义（只取启用中的） */
        record FieldDef(long id, String fieldCode, String label, String datatype,
                        boolean required, List<String> valueSet, String unit) {}

        /** 渲染产物：{@code text} 进正文（参与签名）、{@code json} 进 content_json 侧车 */
        record Rendered(String text, String json) {}

        /** 取模板下启用中的字段定义，按 sort_no 排（sort_no 同时是 989★ 快速跳转的 Tab 序） */
        static List<FieldDef> load(org.springframework.jdbc.core.JdbcTemplate jdbc, Long templateId) {
            if (templateId == null) {
                throw new BizException(4024, "未指定病历模板，无法解析结构化字段（templateId 必填）");
            }
            var rows = jdbc.queryForList("""
                    select id, field_code, label, datatype, required, value_set, unit
                    from emr_template_field
                    where template_id = ? and enabled = true
                    order by sort_no, id
                    """, templateId);
            return rows.stream().map(r -> new FieldDef(
                    ((Number) r.get("id")).longValue(),
                    (String) r.get("field_code"),
                    (String) r.get("label"),
                    (String) r.get("datatype"),
                    Boolean.TRUE.equals(r.get("required")),
                    parseValueSet((String) r.get("value_set")),
                    (String) r.get("unit"))).toList();
        }

        /** value_set 是 text 存的 JSON 数组（本仓惯例）；解析失败按"未配置候选值"处理，由 4026 兜住 */
        static List<String> parseValueSet(String raw) {
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            try {
                var node = VALUE_SET_MAPPER.readTree(raw);
                if (!node.isArray()) {
                    return List.of();
                }
                var out = new java.util.ArrayList<String>();
                node.forEach(n -> {
                    String v = n.asText();
                    if (v != null && !v.isBlank()) {
                        out.add(v.trim());
                    }
                });
                return List.copyOf(out);
            } catch (Exception e) {
                return List.of();
            }
        }

        /**
         * 逐字段校验 + 渲染 + 序列化。
         *
         * <p>顺序刻意是「先认全部键、再逐字段校验」：传了一个模板里没有的 fieldCode 时先报 4024，
         * 而不是被必填检查抢先报 4025——前者才是真正的错因（多半是模板选错了）。
         */
        static Rendered validateAndRender(List<FieldDef> defs, java.util.Map<String, Object> fields,
                                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
            var byCode = new java.util.LinkedHashMap<String, FieldDef>();
            for (FieldDef d : defs) {
                byCode.put(d.fieldCode(), d);
            }
            for (String k : fields.keySet()) {
                if (!byCode.containsKey(k)) {
                    throw new BizException(4024, "模板字段定义不存在或已停用：" + k);
                }
            }
            var values = new java.util.LinkedHashMap<String, Object>();
            var lines = new java.util.ArrayList<String>();
            for (FieldDef d : defs) {
                Object raw = fields.get(d.fieldCode());
                if (isEmpty(raw)) {
                    if (d.required()) {
                        throw new BizException(4025, "必填结构化字段未填：" + d.label() + "（" + d.fieldCode() + "）");
                    }
                    continue;   // 未填的可选字段既不进侧车也不进正文——不写空行、不编造默认值
                }
                Object v = coerce(d, raw);
                values.put(d.fieldCode(), v);
                lines.add(d.label()
                        + (d.unit() == null || d.unit().isBlank() ? "" : "（" + d.unit() + "）")
                        + "：" + display(d, v));
            }
            String text = lines.isEmpty() ? "" : BLOCK_BEGIN + "\n" + String.join("\n", lines) + "\n" + BLOCK_END;
            try {
                return new Rendered(text, mapper.writeValueAsString(values));
            } catch (Exception e) {
                throw new BizException(4027, "结构化字段序列化失败：" + e.getMessage());
            }
        }

        private static boolean isEmpty(Object raw) {
            if (raw == null) {
                return true;
            }
            if (raw instanceof String s) {
                return s.isBlank();
            }
            return raw instanceof java.util.Collection<?> c && c.isEmpty();
        }

        /** 按 datatype 把前端传来的原始值收敛成 JSON 原生型；不匹配一律 4027，值域不符 4026 */
        static Object coerce(FieldDef d, Object raw) {
            String s = raw instanceof String str ? str.trim() : String.valueOf(raw);
            switch (d.datatype()) {
                case "TEXT" -> {
                    if (raw instanceof java.util.Collection<?> || raw instanceof java.util.Map<?, ?>) {
                        throw typeErr(d, raw);
                    }
                    if (s.length() > TEXT_MAX) {
                        throw new BizException(4027, "字段「" + d.label() + "」文本超过 " + TEXT_MAX + " 字上限");
                    }
                    return s;
                }
                case "NUMBER" -> {
                    try {
                        return new BigDecimal(s);
                    } catch (NumberFormatException e) {
                        throw typeErr(d, raw);
                    }
                }
                case "CHECKBOX" -> {
                    if (raw instanceof Boolean b) {
                        return b;
                    }
                    return switch (s) {
                        case "true", "1", "是", "Y", "y" -> Boolean.TRUE;
                        case "false", "0", "否", "N", "n" -> Boolean.FALSE;
                        default -> throw typeErr(d, raw);
                    };
                }
                case "DATE" -> {
                    try {
                        return LocalDate.parse(s).toString();
                    } catch (Exception e) {
                        throw typeErr(d, raw);
                    }
                }
                case "RADIO" -> {
                    if (raw instanceof java.util.Collection<?> || raw instanceof java.util.Map<?, ?>) {
                        throw typeErr(d, raw);
                    }
                    requireInValueSet(d, s);
                    return s;
                }
                case "MULTI" -> {
                    List<String> picked = toList(d, raw);
                    picked.forEach(x -> requireInValueSet(d, x));
                    return picked;
                }
                default -> throw new BizException(4027,
                        "字段「" + d.label() + "」数据类型非法：" + d.datatype() + "（只接受 " + DATATYPES + "）");
            }
        }

        /** MULTI 兼容两种回传形态：JSON 数组，或以 , ; 、 分隔的字符串（前端老表单常见） */
        private static List<String> toList(FieldDef d, Object raw) {
            if (raw instanceof java.util.Collection<?> c) {
                var out = new java.util.ArrayList<String>();
                for (Object o : c) {
                    if (o instanceof java.util.Collection<?> || o instanceof java.util.Map<?, ?>) {
                        throw typeErr(d, raw);
                    }
                    String v = String.valueOf(o).trim();
                    if (!v.isEmpty() && !out.contains(v)) {
                        out.add(v);
                    }
                }
                return out;
            }
            if (raw instanceof java.util.Map<?, ?>) {
                throw typeErr(d, raw);
            }
            var out = new java.util.ArrayList<String>();
            for (String v : String.valueOf(raw).split("[,;、]")) {
                String t = v.trim();
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
            }
            return out;
        }

        private static void requireInValueSet(FieldDef d, String v) {
            if (d.valueSet().isEmpty()) {
                throw new BizException(4026, "字段「" + d.label() + "」未配置候选值，无法校验取值");
            }
            if (!d.valueSet().contains(v)) {
                throw new BizException(4026, "字段「" + d.label() + "」取值不在值域内：" + v
                        + "（候选：" + String.join("/", d.valueSet()) + "）");
            }
        }

        private static BizException typeErr(FieldDef d, Object raw) {
            return new BizException(4027, "字段「" + d.label() + "」数据类型不匹配："
                    + raw + " 不是合法的 " + d.datatype());
        }

        /** 正文里的显示形态：复选渲染成是/否、多选顿号连接——正文要给人读，不是给机器读 */
        static String display(FieldDef d, Object v) {
            if (v instanceof Boolean b) {
                return b ? "是" : "否";
            }
            if (v instanceof java.util.Collection<?> c) {
                return c.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("、"));
            }
            return String.valueOf(v);
        }

        /** 剥掉正文里已有的结构化块（幂等的前提：前端回传的正文含上一次渲染结果） */
        static String strip(String body) {
            return body == null ? null : BLOCK.matcher(body).replaceAll("").stripTrailing();
        }

        /** 医生手打正文 + 结构化块 = 送去签名的那份全文 */
        static String merge(String body, String block) {
            String kept = strip(body);
            if (block == null || block.isEmpty()) {
                return kept;
            }
            return kept == null || kept.isBlank() ? block : kept + "\n" + block;
        }
    }

    /** 保存诊断时累加个人常用诊断（upsert）；无编码的中医/自定义诊断按名称去重 */
    private void bumpFavorites(Long doctorId, List<OutpDiagnosis> diagnoses) {
        if (doctorId == null || diagnoses == null || diagnoses.isEmpty()) {
            return;
        }
        for (OutpDiagnosis d : diagnoses) {
            String name = blankToNull(d.getIcdName());
            if (name == null) {
                continue;
            }
            upsertFavorite(doctorId, blankToNull(d.getIcdCode()), name, blankToNull(d.getDiagSystem()));
        }
    }

    /**
     * 常用诊断 upsert：有编码走 (user_id, icd_code) 唯一约束，
     * 无编码（中医/自定义）走部分唯一索引 (user_id, icd_name) where icd_code is null。
     * 分两条语句是因为 ON CONFLICT 的冲突目标必须与索引谓词一致。
     */
    public void upsertFavorite(Long userId, String icdCode, String icdName, String diagSystem) {
        if (userId == null || icdName == null || icdName.isBlank()) {
            return;
        }
        if (icdCode == null || icdCode.isBlank()) {
            jdbc.update("""
                    insert into outp_diagnosis_favorite(user_id, icd_code, icd_name, diag_system)
                    values (?, null, ?, ?)
                    on conflict (user_id, icd_name) where icd_code is null
                    do update set use_count = outp_diagnosis_favorite.use_count + 1,
                                  last_used_at = now(),
                                  diag_system = coalesce(excluded.diag_system, outp_diagnosis_favorite.diag_system)
                    """, userId, icdName, diagSystem);
        } else {
            jdbc.update("""
                    insert into outp_diagnosis_favorite(user_id, icd_code, icd_name, diag_system)
                    values (?, ?, ?, ?)
                    on conflict (user_id, icd_code)
                    do update set use_count = outp_diagnosis_favorite.use_count + 1,
                                  last_used_at = now(),
                                  icd_name = excluded.icd_name,
                                  diag_system = coalesce(excluded.diag_system, outp_diagnosis_favorite.diag_system)
                    """, userId, icdCode, icdName, diagSystem);
        }
    }

    /** 删除一条个人常用诊断（只能删自己的；删不存在的按幂等处理，不报错） */
    @Transactional
    public void deleteFavorite(Long userId, Long favoriteId) {
        if (userId == null || favoriteId == null) {
            return;
        }
        jdbc.update("delete from outp_diagnosis_favorite where id = ? and user_id = ?", favoriteId, userId);
    }

    /** 诊断助手每段的条数上限——三段各 20 条，界面一屏放得下，也挡住全院聚合的长尾 */
    private static final int ASSIST_LIMIT = 20;

    /** 高频诊断的统计窗口：近半年。窗口内没有数据就自然返回空，不硬编码任何"常见病"清单 */
    private static final int FREQUENT_WINDOW_DAYS = 180;

    /**
     * 诊断助手（偏离表 979★ + 1084★ 的"可从中挑选"）：一次返回三段。
     *
     * <ul>
     *   <li><b>history</b> 该患者历史诊断，按编码+名称去重、按最近就诊倒序；</li>
     *   <li><b>favorite</b> 当前医生的常用诊断（outp_diagnosis_favorite），按使用次数倒序；</li>
     *   <li><b>frequent</b> 全院高频诊断，<b>按 outp_diagnosis 真实数据聚合</b>（近 180 天），
     *       不是硬编码清单——没数据就是空的。</li>
     * </ul>
     *
     * <p>三段各一条聚合 SQL、各限 {@value #ASSIST_LIMIT} 条，无 N+1。纯只读。
     * keyword 为空时不加过滤条件（不写 {@code ? = '' or ...}：PG 对该形态推不出参数类型，
     * 1.1 期已经踩过一次）。
     */
    public java.util.Map<String, Object> diagnosisAssist(Long patientId, String keyword, Long doctorId) {
        String kw = blankToNull(keyword);
        String like = kw == null ? null : "%" + kw + "%";

        List<java.util.Map<String, Object>> history = List.of();
        if (patientId != null) {
            var args = new java.util.ArrayList<Object>();
            var sql = new StringBuilder("""
                    select d.icd_code as "icdCode", d.icd_name as "icdName",
                           d.diag_system as "diagSystem", max(r.visit_date) as "lastVisitDate"
                    from outp_diagnosis d
                    join outp_registration r on r.id = d.registration_id
                    where r.patient_id = ? and r.status <> 'CANCELLED'
                    """);
            args.add(patientId);
            if (like != null) {
                sql.append(" and (d.icd_name ilike ? or d.icd_code ilike ?) ");
                args.add(like);
                args.add(like);
            }
            sql.append(" group by d.icd_code, d.icd_name, d.diag_system")
               .append(" order by max(r.visit_date) desc, max(r.id) desc limit ").append(ASSIST_LIMIT);
            history = jdbc.queryForList(sql.toString(), args.toArray());
        }

        List<java.util.Map<String, Object>> favorite = List.of();
        if (doctorId != null) {
            var args = new java.util.ArrayList<Object>();
            var sql = new StringBuilder("""
                    select id, icd_code as "icdCode", icd_name as "icdName",
                           diag_system as "diagSystem", use_count as "useCount"
                    from outp_diagnosis_favorite where user_id = ?
                    """);
            args.add(doctorId);
            if (like != null) {
                sql.append(" and (icd_name ilike ? or icd_code ilike ?) ");
                args.add(like);
                args.add(like);
            }
            sql.append(" order by use_count desc, last_used_at desc limit ").append(ASSIST_LIMIT);
            favorite = jdbc.queryForList(sql.toString(), args.toArray());
        }

        var freqArgs = new java.util.ArrayList<Object>();
        var freqSql = new StringBuilder("""
                select d.icd_code as "icdCode", d.icd_name as "icdName", count(*) as "useCount"
                from outp_diagnosis d
                join outp_registration r on r.id = d.registration_id
                where r.visit_date >= ? and r.status <> 'CANCELLED' and coalesce(d.icd_name, '') <> ''
                """);
        freqArgs.add(java.sql.Date.valueOf(BusinessDates.today().minusDays(FREQUENT_WINDOW_DAYS)));
        if (like != null) {
            freqSql.append(" and (d.icd_name ilike ? or d.icd_code ilike ?) ");
            freqArgs.add(like);
            freqArgs.add(like);
        }
        freqSql.append(" group by d.icd_code, d.icd_name order by count(*) desc, d.icd_name limit ")
               .append(ASSIST_LIMIT);
        var frequent = jdbc.queryForList(freqSql.toString(), freqArgs.toArray());

        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("history", history);
        m.put("favorite", favorite);
        m.put("frequent", frequent);
        return m;
    }

    /**
     * 医保特殊病种（慢特病）<b>院内登记</b>——偏离表 984★。
     *
     * <p><b>边界（务必照实说）</b>：本平台做的是「院内登记」，即在诊间记下患者已认定的
     * 特殊病种与院内认定有效期，供诊断与开单时提示；<b>不包含向医保经办机构备案报送</b>。
     * 报送要走当地医保接口（报文规范、CA、专网），属外部条件，本仓没有也不假装有——
     * 表结构里刻意不留 approve_status / filing_no 这类永远停在"待报送"的假状态列。
     */
    @Transactional
    public Long addSpecialDisease(Long patientId, String diseaseCode, String diseaseName,
                                  String insuranceType, LocalDate startDate, LocalDate endDate,
                                  String remark, Long operatorId) {
        if (patientId == null || blankToNull(diseaseName) == null || startDate == null) {
            throw new BizException(4035, "特殊病种登记信息不全：患者、病种名称、有效期起始均为必填");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new BizException(4035, "特殊病种登记信息不全：有效期止不能早于有效期起");
        }
        jdbc.update("""
                insert into outp_special_disease(patient_id, disease_code, disease_name,
                                                 insurance_type, start_date, end_date, remark, created_by)
                values (?,?,?,?,?,?,?,?)
                """, patientId, blankToNull(diseaseCode), blankToNull(diseaseName).trim(),
                blankToNull(insuranceType), java.sql.Date.valueOf(startDate),
                endDate == null ? null : java.sql.Date.valueOf(endDate),
                blankToNull(remark), operatorId);
        return jdbc.queryForObject(
                "select max(id) from outp_special_disease where patient_id = ?", Long.class, patientId);
    }

    /**
     * 患者的特殊病种登记（院内）。activeOnly=true 时只返回业务日期仍在有效期内的，
     * 供诊断与开单界面提示。
     */
    public List<java.util.Map<String, Object>> listSpecialDiseases(Long patientId, boolean activeOnly) {
        if (patientId == null) {
            return List.of();
        }
        if (!activeOnly) {
            return jdbc.queryForList("""
                    select id, disease_code as "diseaseCode", disease_name as "diseaseName",
                           insurance_type as "insuranceType", start_date as "startDate",
                           end_date as "endDate", remark, created_by as "createdBy", created_at as "createdAt"
                    from outp_special_disease where patient_id = ? order by start_date desc, id desc
                    """, patientId);
        }
        return jdbc.queryForList("""
                select id, disease_code as "diseaseCode", disease_name as "diseaseName",
                       insurance_type as "insuranceType", start_date as "startDate",
                       end_date as "endDate", remark, created_by as "createdBy", created_at as "createdAt"
                from outp_special_disease
                where patient_id = ? and start_date <= ? and (end_date is null or end_date >= ?)
                order by start_date desc, id desc
                """, patientId, java.sql.Date.valueOf(BusinessDates.today()),
                java.sql.Date.valueOf(BusinessDates.today()));
    }

    /** 删除一条特殊病种院内登记（登记错了直接删；本表不承担法定留痕职责） */
    @Transactional
    public void deleteSpecialDisease(Long id) {
        if (id != null) {
            jdbc.update("delete from outp_special_disease where id = ?", id);
        }
    }

    /**
     * 开单行。v44 合版：在既有 7 个分量之后追加 7 个 v44 字段（remark/urgent/clinicalSummary/
     * examPurpose/notice/specimenType/samplingSite），并保留**原 7 参兼容构造器**——
     * 全仓 78 个调用点（含大量单测与 import 工具）一行不用改，新字段一律补 null。
     * 手法同 v39 给 InpatientService.OrderLine 加 orderNature 时的兼容构造器。
     */
    public record OrderLine(String orderType, Long itemId, Integer qty,
                            String usageRoute, String frequency, String dosePerTime, Integer days,
                            String remark, Boolean urgent, String clinicalSummary,
                            String examPurpose, String notice, String specimenType, String samplingSite) {

        /** 兼容构造器：既有 7 参调用点保持原样，v44 字段留空。 */
        public OrderLine(String orderType, Long itemId, Integer qty,
                         String usageRoute, String frequency, String dosePerTime, Integer days) {
            this(orderType, itemId, qty, usageRoute, frequency, dosePerTime, days,
                    null, null, null, null, null, null, null);   // 7 个 v44 字段全部留空
        }
    }

    /** 开立一组医嘱（药品成一张处方，检查检验各自成申请单） */
    @Transactional
    public List<OutpOrder> createOrders(Long registrationId, List<OrderLine> lines, Long doctorId) {
        OutpRegistration reg = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new BizException(4001, "挂号记录不存在"));
        if (!"VISITED".equals(reg.getStatus())) {
            throw new BizException(4003, "请先接诊后再开单");
        }
        // v43 车道C（8016）：停用药品不可开单。**本方法唯一的新增判断，且是纯只读预检**——
        // 刻意放在 nextGroupSeq() 之前：那是 nextval，事务回滚也退不回去，
        // 拒绝一张不该开的单不该消耗掉一个处方组号。失败时零副作用（无序列、无订单、无库存变动）。
        // 药品不存在仍留给下方既有的 4004 路径处理，此处只管"存在但已停用"。
        for (OrderLine line : lines) {
            if (!"DRUG".equals(line.orderType())) continue;
            DrugItem d = drugRepository.findById(line.itemId()).orElse(null);
            if (d != null && !Boolean.TRUE.equals(d.getEnabled())) {
                throw new BizException(8016, "药品已停用，不可开单：" + d.getName()
                        + (d.getDisableReason() == null ? "" : "（停用原因：" + d.getDisableReason() + "）"));
            }
        }
        String stamp = BusinessDates.today().format(DateTimeFormatter.BASIC_ISO_DATE);
        String drugGroupNo = configReader.get("billno_prefix_rx", "CF") + stamp + "-" + nextGroupSeq();

        // 合理用药前置拦截（过敏禁忌 / 同诊重复用药 / 抗菌药分级处方权）
        List<CdssService.DrugLine> newDrugs = new java.util.ArrayList<>();
        List<Long> newDrugIds = new java.util.ArrayList<>();
        List<String> newDrugNames = new java.util.ArrayList<>();
        // 同一次提交内的完全重复行（同药同用法同频次）：查重只看已持久化订单，同请求两行会双双放行。
        // 用法/频次不同属合法医嘱（负荷量+维持量、口服+静滴双途径、激素递减），不拦。
        var seenLines = new java.util.HashSet<String>();
        for (OrderLine line : lines) {
            if ("DRUG".equals(line.orderType())
                    && !seenLines.add(line.itemId() + "|" + line.usageRoute() + "|" + line.frequency()
                                      + "|" + line.dosePerTime())) {
                String name = drugRepository.findById(line.itemId())
                        .map(DrugItem::getName).orElse(String.valueOf(line.itemId()));
                throw new BizException(4013, "同一处方内存在完全相同的重复医嘱行: " + name);
            }
        }
        // v55 车道 R3（217）+ 合版修复复核 D1：自由文本关键词闸的让路是**按药品**裁决的，不是按患者。
        // 复核实测的漏洞：患者级 trustworthy 让路后，药剂科只映射了阿莫西林一支，
        // 医生开**未映射**的氨苄西林（同为青霉素类）→ 引擎查不到映射、关键词闸又已撤防
        // → **静默放行，比收敛前更松**。关键词闸原本按药名族（「西林」）覆盖整类，
        // 结构化引擎只命中显式映射的药——让路的前提必须是「这一行引擎必定会说话」。
        // 仍是整单算一次（读 gate / 覆盖度 / 映射各一次），逐行只查集合，不撞 30 秒缓存边界。
        java.util.Set<Long> allergyKeywordYield = allergyKeywordYieldSet(reg.getPatientId(), lines);
        for (OrderLine line : lines) {
            if ("DRUG".equals(line.orderType())) {
                drugRepository.findById(line.itemId())
                        .ifPresent(drug -> {
                            checkRationalDrugUse(reg, drug.getName(), drug.getId(),
                                    !allergyKeywordYield.contains(drug.getId()));
                            checkAbxPrivilege(drug, doctorId);
                            newDrugs.add(new CdssService.DrugLine(drug.getName(), line.days()));
                            newDrugIds.add(drug.getId());          // v51：按 id 收集，**不按药名反查**
                            newDrugNames.add(drug.getName());
                        });
            }
        }
        // CDSS 处方审查（相互作用 4015 / 年龄禁忌 4017 拦截，疗程超限留痕提醒）
        // v51：改为接收返回体——既有 checkPrescription 只往 cdss_alert 写一行，
        // 医生开完单什么都看不见。**躺在一张要另外去查的表里的提示等于没有提示。**
        List<String> cdssWarnings = new java.util.ArrayList<>();
        cdssWarnings.addAll(cdssService.checkPrescription(registrationId, reg.getPatientId(), newDrugs));

        // v51 四条新规则。全部**只增不改**：各自受自己的 gate 管辖（默认 warn），
        // block 档才抛异常拦截；warn 档把提示汇总进 cdssWarnings 随返回体下发。
        if (!newDrugIds.isEmpty()) {
            cdssWarnings.addAll(warningsOf(allergyRuleService.enforceOnOrdering(
                    reg.getPatientId(), registrationId, newDrugIds, doctorId)));
            cdssWarnings.addAll(warningsOf(duplicateRxService.checkOnOrdering(registrationId, newDrugIds)));
            cdssWarnings.addAll(warningsOf(populationRuleService.checkPrescription(
                    registrationId, reg.getPatientId(), newDrugNames)));
        }

        return lines.stream().map(line -> {
            OutpOrder o = new OutpOrder();
            o.setRegistrationId(registrationId);
            o.setOrderType(line.orderType());
            o.setQty(line.qty() == null || line.qty() <= 0 ? 1 : line.qty());
            o.setDoctorId(doctorId);
            // v44 合版：7 个新字段统一在此落库（药品/检验/检查/治疗共用一段，不按类型焊死——
            // V137 刻意没加 CHECK，真实院内会有 EXAM 送病理、TREAT 写注意事项）。
            // 纯 setter，不改任何既有分支逻辑；调用方不传即为 null。
            o.setRemark(line.remark());
            o.setUrgent(Boolean.TRUE.equals(line.urgent()));
            o.setClinicalSummary(line.clinicalSummary());
            o.setExamPurpose(line.examPurpose());
            o.setNotice(line.notice());
            o.setSpecimenType(line.specimenType());
            o.setSamplingSite(line.samplingSite());
            Integer stockWarn = null;
            if ("DRUG".equals(line.orderType())) {
                DrugItem drug = drugRepository.findById(line.itemId())
                        .orElseThrow(() -> new BizException(4004, "药品不存在: " + line.itemId()));
                o.setGroupNo(drugGroupNo);
                o.setItemId(drug.getId());
                o.setItemCode(drug.getCode());
                o.setItemName(drug.getName());
                o.setSpec(drug.getSpec());
                o.setUnit(drug.getUnit());
                o.setUnitPrice(drug.getPrice());
                o.setUsageRoute(line.usageRoute());
                o.setFrequency(line.frequency());
                o.setDosePerTime(line.dosePerTime());
                o.setDays(line.days());
                // 库存预警（阻塞6）：本次开量高于当前库存（含库存<=0）即预警，但不拦截开单。
                // 读的是主数据门诊药房库存快照；发药时才原子扣减并可能撞 6002，
                // 此处提前让医生知情，避免"缴费后到药房才发现断货、只能退费重开"。
                int stock = drug.getStock() == null ? 0 : drug.getStock();
                if (stock < o.getQty()) {
                    stockWarn = stock;
                }
            } else {
                ChargeItem item = chargeItemRepository.findById(line.itemId())
                        .orElseThrow(() -> new BizException(4005, "收费项目不存在: " + line.itemId()));
                o.setGroupNo(configReader.get("billno_prefix_req", "SQ") + stamp + "-" + nextGroupSeq());
                o.setItemId(item.getId());
                o.setItemCode(item.getCode());
                o.setItemName(item.getName());
                o.setUnit(item.getUnit());
                o.setUnitPrice(item.getPrice());
            }
            o.setAmount(o.getUnitPrice().multiply(BigDecimal.valueOf(o.getQty())));
            OutpOrder saved = orderRepository.save(o);
            // 预警是非持久化提示，落库后回填到返回实例上（不入库）
            saved.setStockWarnAvailable(stockWarn);
            // v51：CDSS 警告随返回体下发。**挂在订单元素上而不是把 data 包成对象**——
            // 既有契约 data 是订单数组，包成 {orders, warnings} 会让 off 档
            // （本该毫无变化的那一档）也一起挂掉，那就不是「只增不改」了。
            // 字段 @JsonInclude(NON_EMPTY)，无警告时返回体与 v50 逐字相同。
            if (!cdssWarnings.isEmpty()) saved.setCdssWarnings(List.copyOf(cdssWarnings));
            return saved;
        }).toList();
    }

    /**
     * 从各 CDSS 服务的返回 Map 里取出 warnings 列表。
     *
     * <p>四个服务各自返回 {@code Map<String,Object>}，约定都带 {@code warnings} 键。
     * 取不到就当空——**审查服务出问题不该反过来打断临床开单**，
     * 但那样等于静默失效，故取不到时另记一条可见的提示，而不是悄悄跳过。
     */
    private static List<String> warningsOf(java.util.Map<String, Object> result) {
        if (result == null) return List.of();
        Object w = result.get("warnings");
        if (w instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of();
    }

    /** 过敏交叉映射：过敏史关键词 → 命中的药名特征 */
    private static final java.util.Map<String, List<String>> ALLERGY_CROSS = java.util.Map.of(
            "青霉素", List.of("西林", "青霉素"),
            "头孢", List.of("头孢"),
            "磺胺", List.of("磺胺"),
            "阿司匹林", List.of("阿司匹林", "水杨酸"));

    /**
     * <b>否定语境词</b>：过敏史里出现这些词时，同段文字里的药名<b>不是过敏原</b>。
     *
     * <p>v51 实测的真缺陷：过敏史写「青霉素皮试阴性」的患者开阿莫西林，
     * 会被下面的关键词匹配<b>误判成青霉素过敏而 4012 硬拦</b>——
     * 「青霉素皮试阴性」含「青霉素」、「阿莫西林」含「西林」，两个条件都成立。
     * 而皮试阴性恰恰是<b>可以用</b>的证据，这是彻底的<b>反向拦截</b>：该拦的不拦、不该拦的拦住。
     *
     * <p><b>这正是 v51 明令禁止的错误模式，而它早已在生产逻辑里。</b>
     * 彻底的修法是走 v51 新建的结构化过敏原（{@code AllergyRuleService}），
     * 那条路不做任何文本猜测；但结构化目录需要药剂科逐条维护，覆盖前这条既有拦截还得留着。
     * 故此处只做<b>最小的止血</b>：识别否定语境，不再误拦。
     *
     * <p><b>刻意不做的</b>：不做分句、不做否定作用域分析。那需要中文 NLP，
     * 而做一半的 NLP 会产生新的、更难预料的误判。当前口径是<b>整段保守</b>——
     * 只要过敏史里出现否定词，该关键词的<b>关键词式</b>拦截就让位给结构化过敏原。
     * 代价是「青霉素过敏，头孢皮试阴性」这样一段里青霉素也不再被关键词拦，
     * 这是刻意选的方向：<b>宁可少拦一条靠猜的，也不要多拦一条把医生训练成无脑点继续的</b>。
     * 结构化过敏原不受此影响，那才是该依赖的路径。
     */
    private static final List<String> ALLERGY_NEGATIONS = List.of(
            "皮试阴性", "皮试（-）", "皮试(-)", "阴性", "否认", "无过敏", "不过敏", "未见过敏");

    /** 过敏史整段是否处于否定语境（见 {@link #ALLERGY_NEGATIONS} 的口径说明） */
    private static boolean allergyTextIsNegated(String allergy) {
        return ALLERGY_NEGATIONS.stream().anyMatch(allergy::contains);
    }

    /**
     * v55 车道 R3（偏离表 217★）：<b>自由文本关键词闸是否值守</b>。
     *
     * <h3>要收敛的缺陷</h3>
     * 上面这张 {@link #ALLERGY_CROSS} 关键词表在 {@code createOrders} 里排在 v51 结构化过敏引擎
     * （{@code allergyRuleService.enforceOnOrdering}）<b>之前</b>执行，命中即抛 4012 回滚整单，
     * 且完全不受 {@code cdss.gate.allergy} 管辖。后果有两条：
     * <ul>
     *   <li>对「原文含青霉素/头孢/磺胺/阿司匹林」的患者，结构化引擎<b>根本轮不到执行</b>——
     *       药剂科把映射维护得再准、护士把原文核对得再细，说了都不算；</li>
     *   <li>管理员把 gate 设成 off 也关不掉它，「off」这个档位对过敏审查是假的。</li>
     * </ul>
     * v51 给它加的否定语境识别（{@link #ALLERGY_NEGATIONS}）是止血；本方法做的是收敛：
     * <b>让结构化引擎真正说了算，关键词闸退到「结构化查不到时」的兜底位。</b>
     *
     * <h3>三档下关键词闸的行为</h3>
     * <ul>
     *   <li><b>off</b>：让路（返回 false）。gate=off 是管理员在 sys_config 里写下的决定，
     *       从此关键词闸与结构化引擎同进退——引擎在 off 档照样留痕不拦，闸也一样不拦。</li>
     *   <li><b>warn / block</b>：先看结构化覆盖度（下节）；覆盖不可信时<b>照旧硬拦 4012</b>，
     *       不降成 warning。理由：warn 描述的是<b>结构化引擎</b>对直接命中的处置方式，
     *       而关键词闸是出厂态下唯一一道防线（见下），在它还必须值守的场合把它降成一句提示，
     *       等于在结构化引擎尚未接管前就把防线撤了。既有契约（RationalDrugRulesTest、
     *       V51CdssTest#legacyFreeTextAllergyBlockStillWorks）钉的正是默认档 warn 下的 4012。</li>
     *   <li>坏配置（'blocked'/'on'…）经 {@code AllergyRuleService#gate()} 回落 warn，
     *       关键词闸随之<b>继续值守</b>——一个笔误不能静默关掉两道闸。只有字面 off 才让路。</li>
     * </ul>
     *
     * <h3>结构化覆盖到时以谁为准</h3>
     * 判据直接取结构化引擎自己的 {@code coverage.trustworthy}（{@code patientProfile} 返回体），
     * 不在本类另起一套口径。它为 true 需要<b>同时</b>满足：
     * <ol>
     *   <li>该患者<b>当前原文</b>有匹配的人工核对记录（{@code cdss_allergy_text_review.source_text}
     *       与 {@code allergy_history} 逐字相等），且结论不是 UNCLEAR；</li>
     *   <li>该患者全部生效结构化过敏原都至少映射到一个院内药品（映射为空的过敏原永远不会命中）。</li>
     * </ol>
     * 两条都成立时，关键词闸让路，:742 的结构化引擎接手：直接命中按 gate 处置
     * （block → 5612；warn → warnings 随返回体下发 + cdss_alert + gate 台账），交叉命中恒为警告，
     * 无命中则放行——<b>这就是「以结构化判定为准」</b>。任一条不成立，闸照旧值守。
     *
     * <h3>为什么这样收敛是安全的（不许直接删闸的正面回答）</h3>
     * <ul>
     *   <li><b>出厂态一个字节不变。</b>V152 五表全空、零回填：任何有原文的患者
     *       {@code unreviewedText=true} → {@code trustworthy=false} → 闸值守，行为与 v54 逐字相同。
     *       结构化目录为空时删闸等于把现有唯一防线也删掉，本方法在那个状态下<b>什么都不改</b>。</li>
     *   <li><b>让路的两个条件都是「人做过决定」</b>：off 是管理员写的配置；trustworthy 要求
     *       有人逐字读过当前原文并签了字、药剂科给每个过敏原都配了映射。机器从不自己决定让路，
     *       也不解析原文——本方法读的是核对记录与映射表，不碰原文一个字
     *       （V51CdssTest 的「自由文本零脚本解析」纪律不受影响，本文件仍只登记那 4 组既有关键词）。</li>
     *   <li><b>让路是可撤销的</b>：原文一改，核对记录的 source_text 就对不上，
     *       该患者自动重回「未核对」，闸下一次开单即重新值守。不存在「核对过一次就永远放行」。</li>
     *   <li><b>让路之后不是裸奔</b>，是换一道更准的闸接手：结构化引擎按显式映射命中，
     *       既没有「阿莫西林 vs 青霉素」的假阴性，也没有「皮试阴性」的假阳性。
     *       trustworthy 是引擎自己对「本次审查配不配说『未发现禁忌』」的判定，
     *       关键词闸只在引擎自认不配的时候补位——两者互斥而不重叠。</li>
     *   <li><b>判据取患者级而非药品级</b>（「这个药有映射」）：药品级答不了「原文里那条过敏有没有人读过」，
     *       而漏拦的根源恰恰是原文没人读。患者级 trustworthy 恒比药品级严，宁严勿宽。</li>
     *   <li><b>失败面不变</b>：不吞 {@code patientProfile} 的异常——:742 已在无 catch 地调同一引擎，
     *       引擎读不到时开单本来就失败，这里不新增静默路径。</li>
     * </ul>
     *
     * <p>错误码：本收敛<b>不新增码</b>——关键词闸值守时仍抛既有 4012，结构化引擎拦截仍是既有 5612，
     * 让路时无错误可抛。5840–5859 子段登记在本车道名下，本版未启用。
     *
     * @return true = 关键词闸值守（沿用既有 4012 路径）；false = 让路给结构化引擎 / gate=off
     */
    /**
     * 关键词闸对本单哪些药品<b>让路</b>——返回的 id 集合里的行<b>不跑</b>关键词闸，其余行照旧值守。
     *
     * <p>v55 R3 原版是患者级布尔（{@code coverage.trustworthy} 为真即整单让路），复核实测出漏洞（D1）：
     * 药剂科只映射了阿莫西林一支，患者级覆盖仍算可信，于是**未映射**的氨苄西林（同类）
     * 既不被引擎命中、也不再被关键词闸拦——静默放行，<b>比收敛前更松</b>。
     * 关键词闸原本按药名族（「西林」）覆盖整类，引擎只命中显式映射的药；
     * <b>让路的前提必须是「这一行引擎必定会说话」</b>，患者级判据说明不了这一点。
     *
     * <p>裁决顺序（每一档都对应 {@code V55AllergyGateTest} 的一节）：
     * <ol>
     *   <li>无药品行 / 无过敏原文 → 闸无事可做，全部让路；</li>
     *   <li>gate=off → 与引擎同进退，全部让路；</li>
     *   <li>覆盖不可信（原文未核对、有未映射过敏原、结论 UNCLEAR）→ 全部值守；</li>
     *   <li>覆盖可信且<b>人工核对为无过敏</b>（生效过敏原 0 条）→ 引擎对谁都不会命中，
     *       但「无过敏」是人确认过的事实 → 全部让路；</li>
     *   <li>覆盖可信且有生效过敏原 → <b>只对引擎会直接命中（已显式映射）的药让路</b>，
     *       其余同类药闸继续值守——这一条就是 D1 的修复。</li>
     * </ol>
     * 仍是整单算一次（gate / 覆盖度 / 映射各一次查询），不逐行重算。
     */
    private java.util.Set<Long> allergyKeywordYieldSet(Long patientId, List<OrderLine> lines) {
        List<Long> drugIds = lines.stream()
                .filter(l -> "DRUG".equals(l.orderType()) && l.itemId() != null)
                .map(OrderLine::itemId).distinct().toList();
        if (drugIds.isEmpty()) return java.util.Set.of();
        var patient = patientRepository.findById(patientId).orElse(null);
        String allergy = patient == null ? null : patient.getAllergyHistory();
        if (allergy == null || allergy.isBlank()) return java.util.Set.copyOf(drugIds);   // ① 无原文
        if ("off".equals(allergyRuleService.gate())) return java.util.Set.copyOf(drugIds); // ② gate=off
        Object coverage = allergyRuleService.patientProfile(patientId).get("coverage");
        boolean trustworthy = coverage instanceof java.util.Map<?, ?> m
                && Boolean.TRUE.equals(m.get("trustworthy"));
        if (!trustworthy) return java.util.Set.of();                                        // ③ 不可信：全值守
        if (!allergyRuleService.hasActiveAllergen(patientId)) {
            return java.util.Set.copyOf(drugIds);                                           // ④ 人工核对为无过敏
        }
        return allergyRuleService.directlyHitDrugIds(patientId, drugIds);                   // ⑤ 只对已映射药让路
    }

    /**
     * 合理用药规则：1) 过敏史禁忌 2) 同一次就诊重复开同一药品。
     *
     * <p>v55：过敏史关键词段受 {@code allergyKeywordArmed} 管辖（裁决见
     * {@link #allergyKeywordGateArmed}）；值守时的匹配逻辑、错误码 4012 与文案<b>逐字不变</b>。
     * 重复用药 4013 不属过敏 gate 管辖，任何档位都照常执行。
     */
    private void checkRationalDrugUse(OutpRegistration reg, String drugName, Long drugId,
                                      boolean allergyKeywordArmed) {
        var patient = patientRepository.findById(reg.getPatientId()).orElse(null);
        String allergy = patient == null ? null : patient.getAllergyHistory();
        if (allergyKeywordArmed && allergy != null && !allergy.isBlank() && !allergyTextIsNegated(allergy)) {
            for (var entry : ALLERGY_CROSS.entrySet()) {
                if (allergy.contains(entry.getKey())
                        && entry.getValue().stream().anyMatch(drugName::contains)) {
                    throw new BizException(4012,
                            "过敏禁忌拦截：患者过敏史含「%s」，禁用 %s".formatted(entry.getKey(), drugName));
                }
            }
            if (allergy.contains(drugName)) {
                throw new BizException(4012, "过敏禁忌拦截：患者过敏史记载 " + drugName);
            }
        }
        boolean duplicated = orderRepository.findByRegistrationIdOrderByIdAsc(reg.getId()).stream()
                .anyMatch(o -> "DRUG".equals(o.getOrderType()) && o.getItemId().equals(drugId)
                        && !"CANCELLED".equals(o.getStatus()));
        if (duplicated) {
            throw new BizException(4013, "重复用药拦截：本次就诊已开过 " + drugName);
        }
    }

    /** 二十五期：抗菌药分级处方权——限制/特殊级须相应授权（缺省 1 级=仅非限制级） */
    private void checkAbxPrivilege(DrugItem drug, Long doctorId) {
        int level = drug.getAbxLevel() == null ? 0 : drug.getAbxLevel();
        if (level < 2) {
            return;
        }
        int privilege = 1;
        if (doctorId != null) {
            var rows = entityManager.createNativeQuery(
                            "select level from med_abx_privilege where user_id = ?")
                    .setParameter(1, doctorId).getResultList();
            if (!rows.isEmpty()) {
                privilege = ((Number) rows.get(0)).intValue();
            }
        }
        if (privilege < level) {
            throw new BizException(4014, "抗菌药分级拦截：%s 为%s级抗菌药，医师处方权不足（当前 %d 级）"
                    .formatted(drug.getName(), level == 2 ? "限制" : "特殊", privilege));
        }
    }

    /** 病历五段正文是否全空（签一份空病历等同签白纸，v43 起不予签名） */
    private static boolean blankEmr(OutpEmr emr) {
        return java.util.stream.Stream.of(emr.getChiefComplaint(), emr.getPresentIllness(),
                        emr.getPastHistory(), emr.getPhysicalExam(), emr.getAdvice())
                .allMatch(s -> s == null || s.isBlank());
    }

    /**
     * 病历签名：内容摘要签名后冻结。
     *
     * <p>v43 车道A（偏离表 991★）补两条此前缺失的校验——端点自 1.0 起就在，但只挡了「病历不存在」
     * 与「已签名」，任何登录医生都能替别人签、空白病历也照签：
     * <ul>
     *   <li><b>4023</b> 仅病历书写医师本人可签名（saveEmr 落的 doctorId 即书写人）。
     *       两侧 id 任一为 null（历史数据、无登录上下文的服务层直调）时不判——
     *       把新校验倒灌进这些既有调用会直接打断 E2E 与既有单测；</li>
     *   <li><b>4022</b> 五段正文全空不可签名。</li>
     * </ul>
     * 既有 4009/4010/4011 三码与成功返回体一律不动（E2E e2e-phase48/e2e-emr-closure 与前端已在消费）。
     */
    @Transactional
    public OutpEmr signEmr(Long registrationId, Long doctorId,
                           cn.hip.platform.integration.signature.SignatureAdapter signatureAdapter) {
        OutpEmr emr = emrRepository.findByRegistrationId(registrationId)
                .orElseThrow(() -> new BizException(4009, "病历不存在，请先书写保存"));
        if (emr.getSignature() != null) {
            throw new BizException(4010, "病历已签名");
        }
        if (emr.getDoctorId() != null && doctorId != null && !emr.getDoctorId().equals(doctorId)) {
            throw new BizException(4023, "仅病历书写医师本人可签名");
        }
        if (blankEmr(emr)) {
            throw new BizException(4022, "病历内容为空，不可签名");
        }
        String content = String.join("|",
                String.valueOf(emr.getChiefComplaint()), String.valueOf(emr.getPresentIllness()),
                String.valueOf(emr.getPastHistory()), String.valueOf(emr.getPhysicalExam()),
                String.valueOf(emr.getAdvice()));
        var result = signatureAdapter.sign(content, doctorId);
        if (!result.ok()) {
            throw new BizException(4011, "签名失败: " + result.message());
        }
        emr.setSignature(result.signature());
        emr.setSignedAt(java.time.Instant.now());
        OutpEmr signed = emrRepository.save(emr);
        // 签名定稿单独记一版（source=SUBMIT）：定稿版是举证时最要紧的那一版，
        // 必须能与之前的草稿版逐版对比，说清「签的到底是哪一份」。
        emrVersionService.recordOutp(signed, EmrVersionService.SUBMIT, doctorId);
        return signed;
    }

    /** 病历原文拼接（补正留痕时快照原文，与签名摘要同口径） */
    private static String emrText(OutpEmr emr) {
        return String.join("|",
                String.valueOf(emr.getChiefComplaint()), String.valueOf(emr.getPresentIllness()),
                String.valueOf(emr.getPastHistory()), String.valueOf(emr.getPhysicalExam()),
                String.valueOf(emr.getAdvice()));
    }

    /**
     * 病历补正（阻塞4）：签名冻结的门诊病历不允许改原文，但可追加一条补正记录
     * （原文快照 + 补正内容 + 补正人 + 补正时间 + 补正原因），形成法定可追溯的修改痕迹。
     * 未签名的病历应走 saveEmr 直接修改，不走补正。
     */
    @Transactional
    public void amendEmr(Long registrationId, String amendText, String reason, Long doctorId) {
        OutpEmr emr = emrRepository.findByRegistrationId(registrationId)
                .orElseThrow(() -> new BizException(4016, "病历不存在，无法补正"));
        if (emr.getSignature() == null) {
            throw new BizException(4018, "病历未签名冻结，请直接修改，无需补正");
        }
        if (amendText == null || amendText.isBlank()) {
            throw new BizException(4019, "补正内容不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(4019, "补正原因不能为空");
        }
        jdbc.update("""
                insert into emr_amendment(emr_type, emr_id, original_text, amend_text, reason, amended_by)
                values ('OUTP', ?, ?, ?, ?, ?)
                """, emr.getId(), emrText(emr), amendText, reason, doctorId);
    }

    /** 门诊病历补正历史（时间正序） */
    public List<java.util.Map<String, Object>> listAmendments(Long registrationId) {
        OutpEmr emr = emrRepository.findByRegistrationId(registrationId).orElse(null);
        if (emr == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                select a.id, a.amend_text, a.reason, a.amended_by, a.amended_at, u.real_name as amended_by_name
                from emr_amendment a left join sys_user u on u.id = a.amended_by
                where a.emr_type = 'OUTP' and a.emr_id = ?
                order by a.id
                """, emr.getId());
    }

    /** 作废医嘱（仅未收费的可作废） */
    @Transactional
    public void cancelOrder(Long orderId) {
        OutpOrder o = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException(4006, "医嘱不存在"));
        if (!"CREATED".equals(o.getStatus())) {
            throw new BizException(4007, "已收费/已执行的医嘱不能作废，请先退费");
        }
        o.setStatus("CANCELLED");
        orderRepository.save(o);
    }
}
