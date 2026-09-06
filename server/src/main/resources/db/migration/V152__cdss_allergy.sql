-- v51 车道 A：CDSS 过敏规则——结构化过敏原 / 交叉过敏族 / 开单拦截 / 自由文本人工迁移工作台
--
-- ============================================================
-- 【本迁移要补的缺口】
-- ============================================================
-- 实测现状：`empi_patient.allergy_history` 是 varchar(512) **自由文本**（V2 建表至今未变），
-- 全仓只有 6 处把它 select 出来显示（InpEmrController:302 / DoctorStationController:69 /
-- EmrRefController:181,195,249,261 / ReviewController:52 / NursingQualityController:31），
-- **从没进过任何一条 CDSS 规则**。CdssService 仅 94 行、三类规则（DDI / 疗程 / 年龄），
-- 「过敏」两个字在整个规则引擎里零命中。
--
-- 也就是说：**给青霉素过敏的患者开阿莫西林，系统一声不吭地放行**。
-- 这是临床安全缺口，不是功能缺口——DDI 少一条规则是漏一次提醒，
-- 过敏漏拦是过敏性休克。
--
-- ============================================================
-- 【零回填纪律】本文件 **零条 update 语句**，且 **零条从 empi_patient 派生的 insert**
-- ============================================================
-- 最诱人也最危险的一步，是在这里写一句
--   insert into cdss_patient_allergy(patient_id, allergen_id)
--   select id, (select id from cdss_allergen where name='青霉素')
--   from empi_patient where allergy_history like '%青霉素%';
-- **绝对不可以**。理由不是洁癖，是语义：
--   * 「青霉素过敏」        → 该拦
--   * 「青霉素皮试阴性」    → 不该拦（恰恰相反，是可以用）
--   * 「否认药物过敏史」    → 不该拦
--   * 「青霉素过敏（家族史）」→ 患者本人未必过敏
--   * 「无」「/」「未询问」  → 不是「无过敏」，是「没问过」，两者临床含义天差地别
-- 这四类文本的**字符高度相似而语义相反**。关键词/正则解析必然同时制造两种错误：
--   ① 假阴性——「对青霉素类抗生素过敏」写成「PCN 过敏」，关键词表漏掉，该拦的不拦；
--   ② 假阳性——把「皮试阴性」判成过敏，不该拦的拦住。
-- 而假阳性泛滥的后果**不止是麻烦**：医生一旦发现过敏提示经常是错的，
-- 就会养成无脑点「继续」的肌肉记忆，于是**真警告也一起被点掉**——
-- 自动解析因此比不做更危险，它把唯一一道防线的可信度提前消耗掉了。
--
-- 故本迁移只建**空表**（外加两条 enabled=false 的示例行，见文末），
-- 存量自由文本的转换一律走**人工确认工作台**：
-- 列出原文 → 人逐条判断 → 选定结构化过敏原 → 落库，且**原文永远保留不覆盖**
-- （`empi_patient.allergy_history` 本迁移一个字节不改，工作台也只读它）。
--
-- ============================================================
-- 【三级映射：为什么必须显式维护，不能按药名子串匹配】
-- ============================================================
-- 既有 DDI 规则用的是 `drugName.contains(drug_a)` 子串匹配。那套办法搬到过敏上会立刻出事：
--   * 患者「青霉素」过敏 → 子串匹配**匹配不到「阿莫西林」**（同为青霉素类，一个字不重合）
--     ——这正是本版要解决的那个漏拦；
--   * 患者「碘」过敏 → 子串匹配会命中「碘伏消毒液」「碘化钾」，也会命中任何名字里带碘的药，
--     其中一部分与碘对比剂过敏毫无关系。
-- 所以过敏原到药品的对应关系**必须逐条显式维护**，并标注这条对应是在哪一级断言的：
--   INGREDIENT 成分级（阿莫西林含青霉素母核）/ PRODUCT 药品级（就是这个药）/
--   CLASS 药理类别级（同属青霉素类）。
-- 级别不影响是否命中，只影响**提示怎么说**——医生看到「因为同为青霉素类」
-- 与看到一句光秃秃的「过敏」，判断成本差一个量级。
--
-- 映射内容（哪个药含哪个成分、哪些药同类）**属院内用药目录范畴，由药剂科维护**，
-- 本迁移不预置任何一条。编出来的对应关系会直接误导处方。
--
-- ============================================================
-- 【维护完成度必须可查，因为「查不到过敏原」不等于「没有过敏」】
-- ============================================================
-- 本版所有查询与返回体都能回答两个问题：
--   ① 这个患者有多少条自由文本过敏史**还没人工核对**（分母之一）；
--   ② 有多少条结构化过敏原**还没映射到任何院内药品**（映射为空 = 永远不会命中）。
-- 不把这两件事说出来，「本次审查未发现过敏禁忌」就是一句谎话。

-- ============================================================
-- 一、过敏原字典
-- ============================================================
-- 过敏原是**独立实体**，不是 md_drug 上的一个属性位：
--   * 食物（鸡蛋）、非药物（乳胶、造影剂）也要能登记，它们在 md_drug 里根本没有行；
--   * 「青霉素」作为过敏原是一个**成分/类别**概念，院内可能一个叫「青霉素」的药都没有，
--     却有七个青霉素类制剂。把它塞进 md_drug 会逼出一堆假药品。
create table cdss_allergen (
    id            bigserial   primary key,
    code          varchar(32) not null unique,
    name          varchar(64) not null,
    -- DRUG 药物 / FOOD 食物 / OTHER 其他（乳胶、花粉、造影剂……）
    allergen_type varchar(8)  not null,
    -- 该过敏原**在药物侧的粒度**：INGREDIENT 成分 / PRODUCT 具体药品 / CLASS 药理类别。
    -- 非药物过敏原可空。它只是字典上的自述粒度，真正决定命中的是下面的映射表。
    drug_level    varchar(12),
    remark        varchar(255),
    -- 停用只挡**新登记**，不挡既有患者记录参与审查——见 cdss_patient_allergy 处的长注释
    enabled       boolean     not null default true,
    created_at    timestamptz not null default now(),
    created_by    bigint      references sys_user (id),
    constraint chk_cdss_allergen_type  check (allergen_type in ('DRUG', 'FOOD', 'OTHER')),
    constraint chk_cdss_allergen_level check (drug_level is null
                                              or drug_level in ('INGREDIENT', 'PRODUCT', 'CLASS'))
);
create index idx_cdss_allergen_name on cdss_allergen (name);

comment on table cdss_allergen is
    'v51 过敏原字典。内容由药剂科按院内用药目录维护，本平台不预置任何药学判断';
comment on column cdss_allergen.enabled is
    'v51 停用仅阻止新登记；已确认的患者过敏记录**照常参与开单审查**。停用字典条目就让存量过敏失效，等于用一次字典整理静默关掉一批过敏拦截';

-- ============================================================
-- 二、过敏原 → 院内药品映射（三级断言，逐条维护）
-- ============================================================
-- 这张表是整个过敏审查唯一的命中依据。空表 = 一条也拦不住，这是**已知且被返回体明说**的状态，
-- 不是「系统认为安全」。
create table cdss_allergen_drug (
    id           bigserial   primary key,
    allergen_id  bigint      not null references cdss_allergen (id),
    drug_id      bigint      not null references md_drug (id),
    -- 这条对应是在哪一级断言的：影响提示文案（「同为青霉素类」vs「即该药本身」），不影响是否命中
    mapped_level varchar(12) not null,
    note         varchar(200),
    created_at   timestamptz not null default now(),
    created_by   bigint      references sys_user (id),
    constraint chk_cdss_allergen_drug_level check (mapped_level in ('INGREDIENT', 'PRODUCT', 'CLASS')),
    constraint uq_cdss_allergen_drug unique (allergen_id, drug_id)
);
-- 审查按「本次开的这几个药 id」反查过敏原，drug_id 是热路径上的检索列
create index idx_cdss_allergen_drug_drug on cdss_allergen_drug (drug_id);

comment on table cdss_allergen_drug is
    'v51 过敏原→院内药品显式映射。**禁止用药名子串/相似度自动生成**：子串匹配既拦不住「青霉素过敏 vs 阿莫西林」（假阴性），又会把「碘过敏 vs 碘伏」这类不相干的一并拦下（假阳性）';

-- ============================================================
-- 三、交叉过敏族
-- ============================================================
-- 族 = 一组过敏原（青霉素类的各成分）；交叉风险 = 两个族之间的关系。
-- 与 v50 看似听似同一个道理：**交叉是「A 族与 B 族之间」的关系，不是「A 族」的属性**，
-- 做成族上的一个 boolean 只能说「本族有交叉风险」，说不出跟谁交叉，提示就没法写。
create table cdss_allergen_group (
    id         bigserial   primary key,
    code       varchar(32) not null unique,
    name       varchar(64) not null,
    remark     varchar(255),
    enabled    boolean     not null default true,
    created_at timestamptz not null default now(),
    created_by bigint      references sys_user (id)
);

create table cdss_allergen_group_member (
    id          bigserial   primary key,
    group_id    bigint      not null references cdss_allergen_group (id) on delete cascade,
    allergen_id bigint      not null references cdss_allergen (id),
    created_at  timestamptz not null default now(),
    created_by  bigint      references sys_user (id),
    constraint uq_cdss_allergen_group_member unique (group_id, allergen_id)
);
create index idx_cdss_allergen_gm_allergen on cdss_allergen_group_member (allergen_id);

-- 族间交叉风险：无序对只存一行（lo < hi），照抄 V149 pharm_lasa_pair 的口径。
-- 存两行（A→B、B→A）迟早只删掉一半，变成「从 A 查得到、从 B 查不到」的静默漏提示。
create table cdss_allergen_cross (
    id          bigserial   primary key,
    group_id_lo bigint      not null references cdss_allergen_group (id),
    group_id_hi bigint      not null references cdss_allergen_group (id),
    -- 交叉风险高低由药剂科按院内共识填写。本平台不预置任何一个百分比——
    -- 「青霉素与头孢交叉率 X%」这种数字随文献与头孢代际大幅变动，编一个进去就是假证据。
    risk_level  varchar(8)  not null,
    note        varchar(255),
    created_at  timestamptz not null default now(),
    created_by  bigint      references sys_user (id),
    constraint chk_cdss_cross_level check (risk_level in ('HIGH', 'MEDIUM', 'LOW')),
    constraint chk_cdss_cross_order check (group_id_lo < group_id_hi),
    constraint uq_cdss_allergen_cross unique (group_id_lo, group_id_hi)
);
create index idx_cdss_cross_hi on cdss_allergen_cross (group_id_hi);

comment on table cdss_allergen_cross is
    'v51 交叉过敏族间风险（无序对，lo<hi 只存一行）。族的成员与交叉关系均由药剂科维护；交叉命中在开单侧**恒为警告、永不拦截**——理由见 AllergyRuleService 类注释';

-- ============================================================
-- 四、患者结构化过敏记录
-- ============================================================
-- 与 allergy_history 的关系：**并存，不替代，不回写**。
-- 自由文本是病史书写的一部分（有它自己的表述、时间、上下文），结构化记录是给机器用的判定依据。
-- 用结构化结果去覆盖原文，等于把「医生当时到底写了什么」抹掉——事后追责看的正是原文。
create table cdss_patient_allergy (
    id            bigserial    primary key,
    patient_id    bigint       not null references empi_patient (id) on delete cascade,
    allergen_id   bigint       not null references cdss_allergen (id),
    -- MILD 轻 / MODERATE 中 / SEVERE 重 / UNKNOWN 不详。
    -- **无 default**：默认 UNKNOWN 尚可，默认任何一个具体档位都是替确认人做临床判断。
    severity      varchar(12)  not null,
    manifestation varchar(200),                         -- 表现：皮疹 / 喉头水肿 / 过敏性休克……
    -- 来源：SELF_REPORT 患者自述 / CLINICAL 临床观察或既往病历 / TEST 皮试或激发试验 /
    --       TEXT_REVIEW 由自由文本过敏史人工核对而来 / OTHER
    -- 来源必填且不可空：自述与皮试证实的可信度差一个量级，医生看提示时需要这个信息才判断得动。
    source        varchar(16)  not null,
    -- 确认人与确认时刻必填：过敏记录会拦处方，**必须有人为它签字**。
    -- 允许匿名落库的过敏记录，等于允许任何一条脏数据无声地拦住临床。
    confirmed_by  bigint       not null references sys_user (id),
    confirmed_at  timestamptz  not null,
    -- ACTIVE 生效 / REVOKED 已撤销（例如后续皮试证实并非过敏）
    status        varchar(8)   not null default 'ACTIVE',
    revoked_by    bigint       references sys_user (id),
    revoked_at    timestamptz,
    revoke_reason varchar(255),
    -- 若来自迁移工作台：当时所核对的**原文快照**（只是快照，原文仍在 empi_patient 且不被改动）
    source_text   varchar(512),
    note          varchar(255),
    created_at    timestamptz  not null default now(),
    constraint chk_cdss_pa_severity check (severity in ('MILD', 'MODERATE', 'SEVERE', 'UNKNOWN')),
    constraint chk_cdss_pa_source   check (source in ('SELF_REPORT', 'CLINICAL', 'TEST',
                                                      'TEXT_REVIEW', 'OTHER')),
    constraint chk_cdss_pa_status   check (status in ('ACTIVE', 'REVOKED')),
    -- 撤销必须留下人、时刻、原因三样。少任何一样，事后都无法回答
    -- 「这条过敏是谁在什么依据下撤掉的」——而撤销过敏记录直接放开了一次拦截。
    constraint chk_cdss_pa_revoke check (
        status = 'ACTIVE'
        or (revoked_by is not null and revoked_at is not null and revoke_reason is not null))
);

-- 同一患者同一过敏原只能有一条生效记录（撤销后可重新登记）
create unique index uq_cdss_patient_allergy_active
    on cdss_patient_allergy (patient_id, allergen_id) where status = 'ACTIVE';
create index idx_cdss_patient_allergy_pat on cdss_patient_allergy (patient_id, status);

comment on column cdss_patient_allergy.status is
    'v51 ACTIVE/REVOKED。撤销是唯一的失效途径且必须留人/时刻/原因——不提供物理删除，删掉一条过敏记录不该是无痕的';
comment on column cdss_patient_allergy.source_text is
    'v51 迁移工作台核对时的原文快照。empi_patient.allergy_history 本身**永不被本模块写入**';

-- ============================================================
-- 五、自由文本过敏史人工核对工作台（处置记录）
-- ============================================================
-- 这张表存的是**人做过的判断**，不是待办本身。
-- 待办 = `empi_patient` 里 allergy_history 非空、且没有匹配当前原文的处置记录的患者，
-- 由工作台端点**实时 join 算出来**，不预生成任务行：
--   * 预生成需要一条 insert...select 把存量患者刷进来（本迁移禁止派生写入）；
--   * 更要命的是原文会变（医生随时修订过敏史），预生成的任务行立刻过期,
--     而「已处置」的标记会挂在**旧原文**上，让新写进去的过敏史看起来已经核对过了。
-- 故处置记录里存**当时核对的原文快照**，原文变了 = 这条处置对不上，患者重新回到待办。
create table cdss_allergy_text_review (
    id           bigserial    primary key,
    patient_id   bigint       not null references empi_patient (id) on delete cascade,
    -- 核对时看到的原文快照。与 empi_patient.allergy_history 当前值不一致 → 该患者重回待办
    source_text  varchar(512) not null,
    -- STRUCTURED  已确认并登记了结构化过敏原
    -- NO_ALLERGY  原文不构成过敏记录（「皮试阴性」「否认过敏史」「无」）——**这是一个真结论，要留痕**
    -- UNCLEAR     无法判定，需进一步询问患者（**不等于无过敏**，患者仍留在提示口径里）
    resolution   varchar(12)  not null,
    created_count int         not null default 0,       -- 本次由该原文登记了几条结构化过敏原
    note         varchar(500),
    reviewed_by  bigint       not null references sys_user (id),
    reviewed_at  timestamptz  not null,
    constraint chk_cdss_text_review_res check (resolution in ('STRUCTURED', 'NO_ALLERGY', 'UNCLEAR'))
);
create index idx_cdss_text_review_pat on cdss_allergy_text_review (patient_id, reviewed_at desc);

comment on table cdss_allergy_text_review is
    'v51 自由文本过敏史**人工**核对处置记录。全仓不存在任何把 allergy_history 自动转结构化的代码路径——「青霉素过敏」与「青霉素皮试阴性」文本相近而语义相反，解析错即反向拦截';
comment on column cdss_allergy_text_review.resolution is
    'v51 NO_ALLERGY 与 UNCLEAR 必须分开：前者是「看过了，确实没有」，后者是「看过了，还说不准」。混成一个「已处理」，UNCLEAR 的患者就会被当成已排除过敏';

-- ============================================================
-- 六、gate：开药命中过敏原怎么办（三态，默认 warn）
-- ============================================================
-- **默认 warn 而不是 block**，尽管过敏是本版最高危的一条：
--   本平台此前**从无任何过敏校验**，全部存量患者的结构化过敏原数为 0、映射表为空。
--   直接 block 的后果不是「更安全」，而是：
--     ① 映射表填到一半时，覆盖到的那部分药突然大面积拦截，没覆盖到的照旧放行——
--        医生感受到的是「系统随机拦人」，第一反应是找人把 gate 关掉，于是连 warn 都没了；
--     ② 交叉过敏族一旦配得宽（青霉素过敏拦下全部头孢），会拦掉大量临床上完全合理的处方。
--   收紧到 block 的前提是**映射覆盖率与人工核对完成度都能拿数说话**，
--   本版把这两个数做成了可查指标（见 GET /api/cdss/allergy/rules 的 coverage 段）。
--
-- 但 **warn 档必须真的把提示给到医生**，不能静默放行：
--   * 返回体带 warnings 数组（前端必须显示，已写进 cross_lane）;
--   * 每次命中写一行 cdss_alert（rule_type='ALLERGY'），既有 GET /api/cdss/alerts 就能看到；
--   * 每次审查写一行下面的 gate 台账（**命中与否都写**，否则「过敏拦截命中率」的分母是假的）。
-- 坏配置回落 warn 而非 off：宁可多提示，不可让一个笔误把过敏审查静默关掉。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('cdss.gate.allergy', 'warn',
     '开药过敏审查 gate：off 旁路 / warn 命中照常开单但回带 warnings 并留痕（默认）/ block 直接命中禁止开具（5612）。交叉过敏命中在三档下**一律只警告不拦截**')
on conflict (cfg_key) do nothing;

-- ============================================================
-- 七、过敏审查台账
-- ============================================================
-- 三档都记一行。只记拦下来的那部分，就永远算不出
-- 「一共审了多少次 / 命中多少次 / warn 档放行了多少次」这三个数，
-- 也就永远没有依据决定何时收紧到 block（v48 病理双签、v50 发药核对两次学到的同一课）。
create table cdss_allergy_gate_log (
    id              bigserial   primary key,
    patient_id      bigint      not null references empi_patient (id) on delete cascade,
    -- 可空：本审查按「患者 + 一组药品」成立，不强依赖挂号。住院医嘱侧接入时不一定有挂号号。
    -- **on delete set null**：这是一张**台账**，不是业务从表。
    -- 台账的意义是「有人差点给过敏患者开了药」这件事要留下来，
    -- 但它**不该反过来阻止业务数据的正常清理**——挂号被撤销/清理时，
    -- 外键若是默认的 NO ACTION 会直接挡住删除（v51 整合时实测撞到：
    -- V50PharmPickingConcurrencyTest 的清理删挂号被这条外键拒绝）。
    -- set null 后台账行仍在（谁、什么时候、开了什么药、命中了什么），
    -- 只是不再指向一个已不存在的挂号——这正是台账该有的语义。
    registration_id bigint      references outp_registration (id) on delete set null,
    gate            varchar(8)  not null,          -- 当次生效档位 off/warn/block
    drug_count      int         not null,          -- 本次送审药品数
    direct_hits     int         not null default 0,
    cross_hits      int         not null default 0,
    blocked         boolean     not null default false,
    -- 覆盖度快照：审查当时该患者「未人工核对的自由文本原文」「无药品映射的过敏原数」。
    -- 事后复盘「为什么这次没拦住」时，这两个数比任何日志都直接。
    unreviewed_text boolean     not null default false,
    unmapped_count  int         not null default 0,
    detail          varchar(500),                  -- 命中摘要，仅供人看（超长截断）
    operator_id     bigint      references sys_user (id),
    occurred_at     timestamptz not null
);
create index idx_cdss_allergy_log_time on cdss_allergy_gate_log (occurred_at);
create index idx_cdss_allergy_log_pat on cdss_allergy_gate_log (patient_id);

comment on column cdss_allergy_gate_log.unreviewed_text is
    'v51 审查当时该患者仍有未人工核对的自由文本过敏史。为 true 时「未发现过敏禁忌」这句话不成立，返回体也会明说';

-- ============================================================
-- 八、种子：**仅两条 enabled=false 的示例行，零药学知识**
-- ============================================================
-- 商用 CDSS 知识库内容属硬边界。这里不写「头孢与青霉素交叉」「孕妇禁用某药」之类的判断——
-- 那些内容看起来很专业，医生会信，一旦编错就是直接误导处方。
-- 两条示例行 **enabled=false 且无任何映射/成员**，因此**不可能命中任何处方**，
-- 只用来示范字段怎么填。上线前须由药剂科替换或删除。
--
-- 映射表 cdss_allergen_drug、族成员、交叉关系、患者过敏记录、核对记录、台账：**全部空表**。
insert into cdss_allergen (code, name, allergen_type, drug_level, enabled, remark) values
    ('SAMPLE-ALLERGEN', '示例过敏原（上线前须由药剂科替换或删除）', 'OTHER', null, false,
     '示例行：仅示范字段填法。enabled=false 且无药品映射，不会命中任何处方。真实过敏原目录由药剂科按院内用药目录逐条维护')
on conflict (code) do nothing;

insert into cdss_allergen_group (code, name, enabled, remark) values
    ('SAMPLE-GROUP', '示例交叉过敏族（上线前须由药剂科替换或删除）', false,
     '示例行：族的成员与族间交叉风险均由药剂科维护，本平台不预置任何交叉过敏判断')
on conflict (code) do nothing;
