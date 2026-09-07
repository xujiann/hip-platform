-- v53 车道 V2：上级医师审签（住院医师书写 → 上级医师审核签名）
--
-- ============================================================
-- 【先看现状：全仓「三级查房」11 处命中里已经有什么】
-- ============================================================
-- V115（v34）在 inp_medical_record 上扩了 4 列，其中一列正是本域最接近的既有物：
--   round_level          查房级别 CHIEF 主任 / ATTENDING 主治 / RESIDENT 住院医
--   round_doctor_id      查房医师
--   round_opinion        查房意见
--   superior_correction  **上级修正意见**   ← 就是它
-- 配套有 InpEmrController.addRound / rounds 两个端点、InpWardRoundTest、
-- NursingQualityController 的查房时限统计、前端 InpDoctorView/MobileWorkView/PrintView。
--
-- 所以本迁移**不重建查房**，一列都不碰 V115 那四列。
--
-- 但 `superior_correction` 解决不了本版的问题，因为它是**写在被审记录自己身上的一段自由文本**：
--   * 它不记「是谁写的这段修正意见」——列上没有 superior_id，回看时只能看到一段无主的话；
--   * 它不记「什么时候写的」——只有整行的 created_at，那是住院医写查房记录的时刻；
--   * 它不记「审的是哪一版正文」——住院医之后再改 content，这段修正意见原地不动，
--     事后完全看不出「上级看过的到底是不是现在这份」；
--   * 它只长在 record_type='ROUND' 的行上——入院记录、首次病程、出院小结这三类
--     真正法定需要上级审阅签名的文书，根本没有承载审签的地方。
-- 一句话：**既有的是「上级说了什么」，缺的是「审签这个动作本身」——谁签、何时签、签的哪一版。**
-- 而《病历书写基本规范》要求的恰恰是后者：审阅、修改并**签名**。
--
-- ============================================================
-- 【零回填纪律：本文件零条 update、零条派生 insert】
-- ============================================================
-- 最诱人的一步是在这里写一句
--   insert into emr_countersign(record_id, countersigner_id, ...)
--   select id, round_doctor_id, ... from inp_medical_record
--   where superior_correction is not null;
-- **绝对不可以**。superior_correction 里有一段话，不等于有人审签过：
-- 那段话是住院医自己敲进去的（addRound 的入参就在住院医的请求体里，见 InpEmrController:112），
-- round_doctor_id 是**查房人**不是审签人。照这样回填，会在举证材料里造出
-- 一条「某年某月某日某主任审签了本份病历」的记录——而这件事从未发生。
-- 历史病历没有审签记录，那就是事实：当时没采集。宁可空着，不可假算。
--
-- ============================================================
-- 【为什么审签人资格不由系统判定】
-- ============================================================
-- 本仓角色只有 ADMIN/DOCTOR_OUTP/NURSE/PHARMACIST/TECHNICIAN/CASHIER/QUALITY 七个，
-- **没有住院医/主治/主任的分级**；`sys_user.title` 是 V1 建表起就有的 varchar(32) **自由文本**，
-- 全仓零处按它做过判定。从 title 里正则出「主治」来认定审签资格，与 v51 从自由文本过敏史
-- 解析过敏原是同一类错误：「主治医师」「主治医师（待聘）」「住院医师（代主治）」字面高度相似而资格相反，
-- 判错的两个方向都致命——该拦的不拦，或把有资格的上级挡在门外。
--
-- 故本版**只强制一条能证明的硬规则：审签人不得与书写人为同一人**（DB 层 CHECK 兜底，见下）。
-- 「谁有资格当上级」由院方按账号授权与排班管理，系统不猜；但**审签当时的职称原文照抄进快照列**，
-- 事后举证时拿得出「签字的这个人当时挂的是什么职称」。快照为空就是空——历史账号本就没填 title。
--
-- ============================================================
-- 【审签必须绑定到具体版本，否则「签完又改了」无从发现】
-- ============================================================
-- 这是本表最核心的一列：content_sha256——**审签当时那份正文的摘要**。
-- 有了它，「这份病历在上级签字之后被动过」变成一句可当场验算的话：
-- 重算当前 content 的 sha256，与签字时的摘要不等即为失效审签（stale）。
-- 没有它，审签只是一个时间戳，证明不了签的是什么。
--
-- version_id / version_no 是对 **v53 车道 V1 版本表**的**软引用，刻意不加外键**：
-- 两条车道并行开发，硬外键意味着本迁移的成败取决于另一条车道的表名与落地顺序，
-- 且 V1 若延期，本表就整个建不起来。表名与列名走 sys_config 可改（见文末三个 key），
-- 取不到版本时落 version_source='DIGEST_ONLY' 并照常审签——
-- **摘要在任何情况下都成立，版本号是锦上添花**。已写进 cross_lane 与 V1 协调。

-- ============================================================
-- 一、审签记录
-- ============================================================
-- 只增不改不删：审签是法定签字动作，撤销审签＝把签过的字擦掉。
-- 需要否定前一次审签的场景（上级看错了病人），走「病历改了 → 旧审签自动 stale → 重新审签」，
-- 而不是删行。同 v48「拒收不删记录」、v51「原文永远保留不覆盖」。
create table emr_countersign (
    id                  bigserial    primary key,

    -- 被审签的病历行。**加外键**：一条指向不存在病历的审签记录，在举证材料里是负资产。
    record_id           bigint       not null references inp_medical_record (id),

    -- 书写人快照（审签当时的 inp_medical_record.doctor_id）。
    -- **not null**：书写人未知就无从核验「审签人与书写人是不是同一个人」，
    -- 那样的审签形同虚设，服务层直接拒（5722），DB 层用 not null 兜住直连改库。
    author_id           bigint       not null references sys_user (id),

    countersigner_id    bigint       not null references sys_user (id),

    -- 审签当时 sys_user.title 的**原文快照**，可空（历史账号本就没填，空就是空，不猜）。
    -- 只作举证展示，不参与任何判定——理由见文件头「审签人资格」一节。
    countersigner_title varchar(32),

    countersigned_at    timestamptz  not null,
    opinion             varchar(500),

    -- 审签的是哪一版正文
    content_sha256      varchar(64)  not null,
    content_len         integer      not null,

    -- 对 v53 车道 V1 版本表的软引用（无外键，见文件头）
    version_id          bigint,
    version_no          integer,
    -- VERSION_TABLE 取到了版本行 / NO_VERSION_ROW 版本表在但这份病历没有版本记录
    -- （V1 上线之前写的病历必然如此）/ DIGEST_ONLY 版本表尚未就位
    version_source      varchar(16)  not null,

    constraint chk_emr_countersign_not_self
        check (author_id <> countersigner_id),
    constraint chk_emr_countersign_sha
        check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint chk_emr_countersign_vsrc
        check (version_source in ('VERSION_TABLE', 'NO_VERSION_ROW', 'DIGEST_ONLY'))
);

-- 唯一键刻意**带上摘要**，不是 (record_id, countersigner_id)。
-- 只按人去重会造成一个致命后果：住院医在上级签完之后改了正文，
-- 同一位上级**再也签不了第二次**，于是这份被改过的病历永远停在「有审签但已 stale」，
-- 而系统还拿 unique 冲突告诉上级「您已经签过了」。
-- 带上摘要后语义准确：同一个人不能把**同一份正文**签两遍（那没有意义），
-- 但正文一变就是新的一版，该重签就能重签。
create unique index uq_emr_countersign_once
    on emr_countersign (record_id, countersigner_id, content_sha256);

create index idx_emr_countersign_record on emr_countersign (record_id, id);
create index idx_emr_countersign_signer on emr_countersign (countersigner_id, countersigned_at desc);

comment on table emr_countersign is
    'v53：上级医师审签动作本身（谁签、何时签、签的哪一版）。与 V115 的 superior_correction '
    '（上级说了什么，写在被审记录自己身上的自由文本）是两回事，不互相替代';
comment on column emr_countersign.content_sha256 is
    'v53：审签当时正文的 sha256。重算当前 content 与之比对即可判定「签完又改了」——'
    '没有这一列，审签就只是个时间戳，证明不了签的是什么';
comment on column emr_countersign.author_id is
    'v53：书写人快照。not null 是硬要求——书写人未知则无从核验审签人是否与其为同一人';
comment on column emr_countersign.version_source is
    'v53：version_id/version_no 的来源。DIGEST_ONLY 表示 V1 版本表尚未就位，本次审签只绑定摘要';

-- ============================================================
-- 二、配置
-- ============================================================
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.gate.countersign', 'warn',
     '上级审签 gate：off 不校验 / warn 提示但放行（默认）/ block 未审签不得出院归档（5725）。'
     '默认 warn——存量病历一条审签记录都没有（本版零回填），直接 block 会让在院患者全部出不了院。'
     '坏配置回落 warn 不回落 off：笔误不该静默关掉一条法定校验'),
    ('emr.countersign.required_types', 'ADMISSION,FIRST_PROGRESS,DISCHARGE',
     '哪些病历类型进「待审签」工作列表与 gate 判定。默认三类是法定需上级审阅签名的核心文书。'
     '**刻意不含 ROUND**：主任/主治查房记录本身就是上级写的，再要一次上级审签是自签自审；'
     '住院医查房记录的上级意见走 V115 既有 superior_correction，两条路不合并'),
    ('emr.countersign.due_hours', '24',
     '审签时限（小时，自病历创建时刻起算），仅用于待审签工作列表的超期标记与排序，**不拦截任何写入**。'
     '法定时限质控是 v53 车道 V3（5740–5759）的范围，本 key 不参与其判定')
on conflict (cfg_key) do nothing;

-- V1 版本表的软引用寻址（三个 key 全部可改；任一项对不上即回落 DIGEST_ONLY，不报错）。
-- 服务层对这三个值做 ^[a-z_][a-z0-9_]{0,62}$ 白名单校验后才拼进 SQL——
-- 它们是被拼接的标识符，不是绑定参数，不校验就是一个注入口。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.countersign.version_table', 'emr_version',
     'v53 车道 V1 版本表的表名。查不到该表时审签照常进行，落 version_source=DIGEST_ONLY'),
    -- **必须是 emr_id，不是 record_id**（v53 复核实测 D2）：emr_version 是**多态表**，
    -- 列是 (emr_type, emr_id, version_no)，压根没有 record_id 这一列。
    -- 配成不存在的列名时 resolveVersion 的列体检不过，**恒返 DIGEST_ONLY**，
    -- 且自陈「版本表尚未就位」——而 V157 早已落库，这句话是假的，
    -- 三态里的 NO_VERSION_ROW 永远不会出现。
    -- 注意：只改这个配置**还不够**，查询必须同时带 emr_type='INP'（见 CountersignService
    -- resolveVersion 的注释），否则住院病历会绑走同 id 的门诊版本行——那比绑不上更坏。
    ('emr.countersign.version_fk_column', 'emr_id',
     '版本表中指向 inp_medical_record.id 的列名（emr_version 多态表按 emr_type=INP 过滤后取此列）'),
    ('emr.countersign.version_no_column', 'version_no',
     '版本表中的版本序号列名，取该病历下最大值作为「本次审签绑定的版本」')
on conflict (cfg_key) do nothing;
