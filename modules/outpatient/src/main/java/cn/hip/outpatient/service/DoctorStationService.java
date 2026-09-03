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
    private final cn.hip.platform.core.service.ConfigReader configReader;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        emr = emrRepository.save(emr);

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
        for (OrderLine line : lines) {
            if ("DRUG".equals(line.orderType())) {
                drugRepository.findById(line.itemId())
                        .ifPresent(drug -> {
                            checkRationalDrugUse(reg, drug.getName(), drug.getId());
                            checkAbxPrivilege(drug, doctorId);
                            newDrugs.add(new CdssService.DrugLine(drug.getName(), line.days()));
                        });
            }
        }
        // CDSS 处方审查（相互作用 4015 / 年龄禁忌 4017 拦截，疗程超限留痕提醒）
        cdssService.checkPrescription(registrationId, reg.getPatientId(), newDrugs);

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
            return saved;
        }).toList();
    }

    /** 过敏交叉映射：过敏史关键词 → 命中的药名特征 */
    private static final java.util.Map<String, List<String>> ALLERGY_CROSS = java.util.Map.of(
            "青霉素", List.of("西林", "青霉素"),
            "头孢", List.of("头孢"),
            "磺胺", List.of("磺胺"),
            "阿司匹林", List.of("阿司匹林", "水杨酸"));

    /** 合理用药规则：1) 过敏史禁忌 2) 同一次就诊重复开同一药品 */
    private void checkRationalDrugUse(OutpRegistration reg, String drugName, Long drugId) {
        var patient = patientRepository.findById(reg.getPatientId()).orElse(null);
        String allergy = patient == null ? null : patient.getAllergyHistory();
        if (allergy != null && !allergy.isBlank()) {
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
        return emrRepository.save(emr);
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
