-- =====================================================================================
-- v53 车道 V3：病历书写时限质控（法定时限阈值字典 + 阈值变更留痕）
--
-- 【本迁移零 update、零回填】只建两张新表 + 往新表插种子 + 四个 `if not exists` 索引。
--   历史病历没有时限判定所需的时间锚点——那就是事实，当时没采集。
--   **绝不拿当前内容伪造一条时间锚点**：那会让举证材料里出现一个从未真实存在过的时刻。
--
-- 【为什么阈值要进表而不是硬编码，也不全塞 sys_config】
--   1) 各地各院要求有差异，且法规会修订——阈值必须可配置（任务硬性要求）；
--   2) 但「一个数字」不够：法定时限的每一条都要能说清**出处（文件 + 条文号 + 生效日期）**，
--      否则考核被质疑时拿不出依据。sys_config 是 (key, value, remark) 三列，装不下出处、
--      生效日期、锚点说明、可用性与「病案科是否已核过原文」这五样，故单独建字典表。
--   3) **既有 sys_config 键不另起炉灶**：首程 8 小时在 v34 已有键
--      `emr.timeliness.first_progress_hours`，被 NursingQualityController:191 消费。
--      本表 config_key 一列指向它——**同一个阈值只有一处可改**，改了两张报表一起动。
--      若本表另存一个 8 而不读那个键，院方把键改成 12 之后，两张报表会给出两个超时率，
--      这正是 v49 学过的「算得出但对不上账」。
--
-- 【source_verified 默认 false 是刻意的】
--   种子里的条文号与阈值是**按现行规范填写的默认值，未经原文逐字核对**。
--   上线前须由病案科取《病历书写基本规范》（卫医政发〔2010〕11 号）等文件原文逐条核对，
--   并按本院制度确认，确认后经 PUT /api/emr-timeliness/rules/{code} 置 sourceVerified=true
--   （落 emr_timeliness_rule_log 留痕）。**未确认的规则不得用于对外考核或举证**——
--   接口在 catalog / indicators 两处都会把未核对的条文号加后缀标出来，想漏看都难。
--
-- 【与既有 V80 emr_amendment 的边界】
--   emr_amendment 是**已归档病历的补正追加**（不改原文、另起一条、带原因）。
--   本表与它无关：这里管的是「法定文书有没有在法定时限内写出来」，是**质控口径字典**，
--   不存任何病历正文，不参与任何写路径的拦截。
-- =====================================================================================


-- ===== 1) 时限规则字典 =====
create table emr_timeliness_rule (
    id                 bigserial    primary key,
    -- 机读码：稳定标识，**只准追加不准改名**（前端筛选、CSV 导出、院方考核表都按码绑定）
    rule_code          varchar(32)  not null unique,
    rule_name          varchar(64)  not null,
    -- 适用域 INP 住院 / OUTP 门诊 / ER 急诊
    scope              varchar(8)   not null,

    -- ---- 时间锚点（机读列名 + 中文说明必须同时给）----
    -- 只给中文说明的话，对账的人无法回到 SQL 去核实到底按哪一列判的；
    -- 只给列名的话，看报表的病案科人员读不懂。两者缺一都会让口径变成口头约定。
    start_anchor       varchar(64)  not null,
    start_anchor_cn    varchar(200) not null,
    -- 终点锚点不是一个列名，是一段**取值口径**（"min(表.列) where record_type=…"）：
    -- 本文件自己的种子里最长一条（主治查房：带 record_type 与 round_level 两个限定）就是 88 字符，
    -- 原定的 varchar(64) 装不下，整条迁移会在 insert 处 `value too long` 直接失败。
    -- 与 end_anchor_cn 取同宽 200。start_anchor 现存最长 27 字符（inp_transfer_log.created_at），
    -- 64 尚有余量，故不动——但将来若出现复合起点锚点，这里要一并放宽。
    end_anchor         varchar(200),
    end_anchor_cn      varchar(200),

    -- ---- 阈值 ----
    -- 分钟为唯一存储单位（8 小时 / 24 小时 / 10 分钟混在一张表里，用小时会存不下急会诊）
    limit_minutes      int          not null,
    -- 可选：指向既有 sys_config 键。非空时**以该键的值为准**，本列 limit_minutes 退化为回落值。
    config_key         varchar(64),
    -- 该 sys_config 键的单位（MINUTES / HOURS / DAYS），换算在服务层做
    config_unit        varchar(8),
    -- 「即将超时」提醒触发点：已用时长达到时限的百分之几即进提醒清单
    warn_ratio_pct     int          not null default 75,

    -- ---- 出处（法定依据）----
    source_doc         varchar(128) not null,
    source_article     varchar(64),
    source_effective_from date      not null,
    -- 出处与阈值是否已由病案科取原文逐条核对。**默认 false**，见文件头说明。
    source_verified    boolean      not null default false,
    verified_by        bigint       references sys_user (id),
    verified_at        timestamptz,

    -- ---- 可用性 ----
    -- available=false：本平台**没有可用的时间锚点**，该指标算不出来。
    -- 这类规则一律不进统计、不返回 rows、不显示成 0——返回一个看着像真的 0，
    -- 会让管理者把「我们没有能力统计」误读成「我们没有超时」，比不给更坏。
    available          boolean      not null default true,
    unavailable_reason varchar(2000),
    -- 缺哪些字段才能变成 available（补法说明，一行一个字段，换行分隔）
    missing_fields     varchar(2000),

    enabled            boolean      not null default true,
    remark             varchar(2000),
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now(),

    -- 新表零历史行，CHECK 不存在「脏数据挡住 Flyway」的风险
    -- （与 inp_medical_record.record_type 刻意不加 CHECK 的处置不同，见 V143:19）
    constraint chk_emr_tl_scope  check (scope in ('INP', 'OUTP', 'ER')),
    constraint chk_emr_tl_limit  check (limit_minutes >= 0),
    constraint chk_emr_tl_warn   check (warn_ratio_pct between 1 and 99),
    constraint chk_emr_tl_unit   check (config_unit is null or config_unit in ('MINUTES', 'HOURS', 'DAYS')),
    constraint chk_emr_tl_unitk  check ((config_key is null) = (config_unit is null)),
    -- 算不出来的规则不许启用：启用了也只会在清单里出现一条永远空的规则，纯误导
    constraint chk_emr_tl_avail  check (available or not enabled),
    -- available=false 必须写清楚为什么，否则「不可用」就成了不可核实的断言
    constraint chk_emr_tl_reason check (available or unavailable_reason is not null)
);

comment on table emr_timeliness_rule is
    'v53 病历书写时限质控规则字典。阈值可配置、出处可追溯。source_verified=false 表示条文号与阈值尚未经病案科核对原文，不得用于对外考核或举证。';


-- ===== 2) 规则变更留痕 =====
-- 法定时限阈值被改过而系统证明不了改成了什么、谁改的、为什么改，
-- 等于历史超时率永远对不上账（「上个月超时率 3%，为什么现在重算是 11%？」）。
-- 这与本版主题同源：**能证明**才算数。
create table emr_timeliness_rule_log (
    id          bigserial    primary key,
    rule_code   varchar(32)  not null,
    -- 变更字段名（limit_minutes / warn_ratio_pct / enabled / source_verified / remark）
    field       varchar(32)  not null,
    old_value   varchar(500),
    new_value   varchar(500),
    -- 变更原因：**必填**。改法定时限没有原因，事后就说不清当时为什么是 12 小时。
    reason      varchar(500) not null,
    changed_by  bigint       references sys_user (id),
    changed_at  timestamptz  not null default now()
);
create index idx_emr_tl_log_rule on emr_timeliness_rule_log (rule_code, id desc);


-- ===== 3) 统计所需索引（纯 DDL，不动一行数据）=====
-- 时限统计要按「起点锚点日期」落窗（见服务层 WINDOW_ANCHOR_NOTE），
-- 而 inp_admission 现有索引只有 status / patient / dept / ward / updated_at，
-- 按 admit_at 落窗会全表扫。
create index if not exists idx_inp_admission_admit_at on inp_admission (admit_at);
create index if not exists idx_inp_admission_discharged_at on inp_admission (discharged_at)
    where discharged_at is not null;
-- 终点锚点是 min(created_at) filter by record_type，现有 idx_inp_record_adm 只有 admission_id
create index if not exists idx_inp_record_type_adm on inp_medical_record (record_type, admission_id, created_at);
create index if not exists idx_inp_consult_created_at on inp_consult (created_at);


-- ===== 4) 种子：按现行规范的默认值（未经原文核对，source_verified 全部 false）=====
--
-- 可用性判定不是拍脑袋，每一条 available=false 都在 unavailable_reason 里给出**可复核的证据**
-- （具体到表名列名与代码行号），并在 missing_fields 里给出补法。
--
insert into emr_timeliness_rule
    (rule_code, rule_name, scope,
     start_anchor, start_anchor_cn, end_anchor, end_anchor_cn,
     limit_minutes, config_key, config_unit, warn_ratio_pct,
     source_doc, source_article, source_effective_from,
     available, unavailable_reason, missing_fields, enabled, remark)
values

-- ---------- 可用（5 条）----------

('INP_ADMISSION_24H', '入院记录', 'INP',
 'inp_admission.admit_at', '入院登记时刻（办入院手续、分配床位的时刻）',
 'min(inp_medical_record.created_at) where record_type=''ADMISSION''', '入院记录首次落库时刻',
 1440, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第十一条', date '2010-03-01',
 true, null, null, true,
 'admit_at 是入院登记时刻，不是患者到达病区时刻。夜间入院、急诊直入病房等场景登记可能晚于实际入院，'
 || '使起点偏晚、超时率偏乐观。本平台无「到达病区时刻」字段，不做推算。'),

('INP_FIRST_PROGRESS_8H', '首次病程记录', 'INP',
 'inp_admission.admit_at', '入院登记时刻',
 'min(inp_medical_record.created_at) where record_type=''FIRST_PROGRESS''', '首次病程记录首次落库时刻',
 480, 'emr.timeliness.first_progress_hours', 'HOURS', 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第二十二条', date '2010-03-01',
 true, null, null, true,
 '起点取入院时刻而非接诊时刻：条文表述为「入院后 8 小时内」，且本平台住院域**无接诊时刻字段**'
 || '（全仓 grep 接诊 / first_seen / receive_at / seen_at 在住院侧零命中）。'
 || '若拿「首条医嘱时刻」当接诊时刻，等于用结果定义起点——病历写得越晚起点就越晚，超时永远为 0。'
 || 'FIRST_PROGRESS 是 v34 才有的记录类型，此前首程混写在 PROGRESS 里无法区分，'
 || '故 v34 之前的住院这条判不准，请先看 coverage.firstProgressTyped。'),

('INP_ROUND_ATTENDING_48H', '主治医师首次查房记录', 'INP',
 'inp_admission.admit_at', '入院登记时刻',
 'min(inp_medical_record.created_at) where record_type=''ROUND'' and round_level=''ATTENDING''',
 '主治医师查房记录首次落库时刻',
 2880, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第二十四条', date '2010-03-01',
 true, null, null, false,
 '**默认停用**，与 v34 既有 emr.timeliness.round_check.enabled 默认 off 同口径：'
 || 'round_level 是 V115 才加的列，历史 ROUND 记录该列全空。'
 || '本规则**不把 round_level 为空的查房猜成主治查房**（猜错会把住院医查房算成主治查房，'
 || '让指标虚高），代价是历史查房整片判为「未写」——那是误报。'
 || '院方确认 round_level 录入已铺开后，再经 PUT /rules/INP_ROUND_ATTENDING_48H 启用。'),

('INP_DISCHARGE_24H', '出院记录（出院小结）', 'INP',
 'inp_admission.discharged_at', '出院时刻',
 'min(inp_medical_record.created_at) where record_type=''DISCHARGE''', '出院记录首次落库时刻',
 1440, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第三十三条', date '2010-03-01',
 true, null, null, true,
 '只统计 discharged_at 非空的住院。在院患者不进分母（不是「及时」也不是「超时」，是不适用）。'
 || 'status=''DISCHARGED'' 但 discharged_at 为空的行是数据质量问题，单列在 coverage 里，不硬凑起点。'
 -- 【口径警示：本规则的分母会被系统性掏空，看数前必读】
 || '【重要】起点 discharged_at 取的是**出院结算盖章时刻**（InpatientService.discharge 调 '
 || 'claimDischarge(id, now()) 写入），而临床上**出院小结通常在结算之前就写完了**。'
 || '于是「小结落库时刻 − 结算时刻」对正常流程恒为负，被判 ANOMALY_NEGATIVE 而不进分母；'
 || '真正能进分母的反倒是「结算之后才补写小结」那一类偏晚的记录，'
 || '**超时率因此系统性偏高，不可直接当作本院真实水平上报**。'
 || 'dev 库实测：唯一一条 DISCHARGE 记录的 created_at − discharged_at = −0.05 秒（先写后结算）。'
 || '正确做法是把起点改为「医嘱出院/离院时刻」，但平台当前没有采集该时刻的字段——'
 || '**缺数据源就如实说，不拿结算时刻冒充离院时刻**。补齐该字段前，本规则的数值仅供内部核对，'
 || 'anomalyNegative 计数即「因锚点方向相反而出局的例数」，该数越大说明口径越不适用。'),

('INP_CONSULT_ROUTINE_48H', '常规会诊意见记录', 'INP',
 'inp_consult.created_at', '会诊申请发出时刻',
 'inp_consult.done_at', '会诊意见完成时刻',
 2880, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第二十五条', date '2010-03-01',
 true, null, null, true,
 '责任科室取 to_dept_id（被邀科室）而非申请科室——会诊超时的责任方是被邀方。'
 || '本平台 inp_consult **无「急会诊 / 常规会诊」标识**，全部按常规 48 小时判：'
 || '急会诊的 10 分钟时限判不出来（见 INP_CONSULT_URGENT_10M），'
 || '故本指标的「及时」不能反过来说明急会诊达标。'),

-- ---------- 不可用（6 条）：算不出来就说算不出来，不给 0 ----------

('INP_SURGERY_NOTE_24H', '手术记录', 'INP',
 'inp_surgery.end_at', '手术结束时刻', null, null,
 1440, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第二十九条', date '2010-03-01',
 false,
 '缺终点锚点：手术记录正文存在 inp_surgery.op_note 一列上，'
 || '由 MedTechController:355 的 `update inp_surgery set status=''DONE'', op_note=?, anes_note=? ...` 覆盖写入，'
 || '**该语句不写任何时刻列**，全仓无「手术记录完成时刻」字段。'
 || 'inp_surgery.created_at 是手术**申请**建行时刻（常在术前数日），拿它当书写时刻会算出'
 || '「术前就写好了手术记录」的负数时长；而负数时长在及时率里会被当成「极其及时」，'
 || '把一处数据缺失伪装成一项优异指标。'
 || '另：inp_medical_record.record_type 白名单（InpEmrController:65）只有 6 类，无手术记录类型，'
 || '手术记录也不在病历表里。',
 'inp_surgery.op_note_at（手术记录书写完成时刻）' || chr(10)
 || '或：把手术记录改为 inp_medical_record 的一个 record_type（则复用 created_at 即可）',
 false, null),

('INP_DEATH_24H', '死亡记录', 'INP',
 'inp_admission.death_at', '死亡时刻（本平台不存在此列）', null, null,
 1440, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第三十四条', date '2010-03-01',
 false,
 '起点与终点两个锚点都没有。起点：inp_admission 无死亡时刻列，status 取值集合里也无死亡态'
 || '（V8 建表 default ''IN_HOSPITAL''，全仓 grep DEAD 只命中 er_rescue_record.outcome）；'
 || 'er_rescue_record.outcome=''DEATH'' 标的是**急诊抢救转归**，既不覆盖病房内死亡，'
 || '也不是死亡时刻。终点：record_type 白名单无死亡记录类型。'
 || '按「最后一条病程记录时刻」推死亡时刻是危险的假实现——死亡时刻要对得上死亡证明与'
 || '死因监测上报两条线的账，推错一例就是一条假账。',
 'inp_admission.death_at（死亡时刻）' || chr(10)
 || 'inp_admission.discharge_way（离院方式，含死亡）' || chr(10)
 || 'inp_medical_record.record_type 增加 DEATH 类型',
 false, null),

('ER_RESCUE_RECORD_6H', '抢救记录据实补记', 'ER',
 'er_rescue_record.rescue_end', '抢救结束时刻', null, null,
 360, 'emr.timeliness.rescue_record_hours', 'HOURS', 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第八条', date '2010-03-01',
 false,
 '缺终点锚点：er_rescue_record.created_at 是抢救台**建行**时刻（rescue_start 默认 now()，'
 || '通常在抢救开始时就建了行），而「据实补记」发生在其后对 measures / participants 的 UPDATE 里，'
 || '**该 UPDATE 不写任何时刻列**，补记完成时刻在库里不存在。'
 || '拿 created_at 当补记时刻会得到「抢救一开始就补记完了」的恒定及时率。'
 || '注：v34 既有的 emr.timeliness.rescue_record_hours 键判的是**抢救未闭合超时**'
 || '（rescue_end 为空超过阈值，NursingQualityController:249），那是另一件事，不是补记时限。',
 'er_rescue_record.record_completed_at（抢救记录补记完成时刻）' || chr(10)
 || '或：抢救记录改为版本留痕（末版时刻即补记完成时刻）',
 false, null),

('OUTP_EMR_TIMELY', '门诊病历就诊时完成', 'OUTP',
 'outp_registration.created_at', '挂号（接诊）时刻', null, null,
 0, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第十条', date '2010-03-01',
 false,
 '缺终点锚点，且成因正是本版要修的那件事：outp_emr **只有 updated_at 一列时间戳**，'
 || 'OutpEmr:73 在每次更新时把它置为 now()，DoctorStationService:137 的 emrRepository.save(emr) '
 || '是**整行覆盖**——门诊病历的**首次书写时刻在库里根本不存在**，第二次保存就把它抹掉了。'
 || '没有版本留痕，就没有首次书写时刻，也就没有门诊病历时限质控。'
 || '拿 updated_at 当书写时刻会算出「每份门诊病历都是在最后一次修改那一刻写的」，'
 || '越是反复修改的病历看起来越「不及时」，与事实相反。'
 || '另：本条时限是「就诊时及时完成」，无小时数，limit_minutes 存 0 表示不设时长，'
 || '即使锚点补齐也须先由病案科定义可判定的口径。',
 'outp_emr.created_at（首次书写时刻）' || chr(10)
 || '或：v53 车道 V1 的病历版本表（首版 created_at 即首次书写时刻）',
 false, null),

('INP_TRANSFER_IN_24H', '转入记录', 'INP',
 'inp_transfer_log.created_at', '转科操作时刻', null, null,
 1440, null, null, 75,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第二十四条', date '2010-03-01',
 false,
 '**有起点、无终点**：起点 inp_transfer_log.created_at 是现成的，'
 || '但 inp_medical_record.record_type 白名单（InpEmrController:65）只有 '
 || 'ADMISSION / FIRST_PROGRESS / PROGRESS / ROUND / DISCHARGE / PREOP 六类，无转入·转出记录类型。'
 || '转入记录若被混写进 PROGRESS，与普通病程记录在库里无法区分，'
 || '按「转科后第一条 PROGRESS」当转入记录会把恰好当天写的普通病程误判为转入记录。',
 'inp_medical_record.record_type 增加 TRANSFER_IN / TRANSFER_OUT 两类',
 false, null),

('INP_CONSULT_URGENT_10M', '急会诊到达', 'INP',
 'inp_consult.created_at', '急会诊请求发出时刻', null, null,
 10, null, null, 50,
 '《病历书写基本规范》（卫医政发〔2010〕11 号）', '第二十五条', date '2010-03-01',
 false,
 '缺两样：一是 inp_consult **无急 / 常规标识**，分不出哪些申请该按 10 分钟判；'
 || '二是条文要求的终点是会诊医师**到达**时刻，而本平台只有 done_at（会诊意见完成时刻），'
 || '两者相差整个会诊过程。用 done_at 判 10 分钟会把几乎全部急会诊判成超时——'
 || '一个几乎恒为 100% 的超时率不会被当真，只会让整张报表失去可信度。',
 'inp_consult.urgency（URGENT / ROUTINE）' || chr(10)
 || 'inp_consult.arrived_at（会诊医师到达时刻）',
 false, null);
