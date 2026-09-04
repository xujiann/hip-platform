-- v45 车道 I：结构化字段定义与病历侧车列（技术偏离表 989★ / 1075★ / 1098★）。
--
-- 三条参数都已答「平台已实现」，而全仓**无任何字段定义表**、病历正文是纯文本：
--   1075★ 模板兼容最小结构化元素：文本·数值·复选·单选·多选·日期
--   989★  结构化书写、所见即所得、元素间快速跳转
--   1098★ 检索门急诊病历中**某个结构化元素内容**的接口通道
-- 本迁移是这三条的数据地基：一张字段定义表 + 两条侧车列。
--
-- ============ 纪律一（最要紧的一条）：签名对象不变，侧车只是侧车 ============
-- **`inp_medical_record.content`（住院）与 `outp_emr` 五段定长列（门诊）仍是被 CA 签名的那份正文。**
-- 门诊签名摘要 = 五段以 '|' 拼接（DoctorStationService.signEmr）；住院签名摘要 = content 原文
-- （InpEmrController.signRecord）。本版**一个字都不改这两处摘要口径**。
--
-- 因此结构化录入的落库顺序是「先渲染成正文、再存侧车」：字段值按 sort_no 渲染成可读全文写进
-- 上述正文列，原始键值另存 `content_json`。**绝不允许只写 content_json 而不渲染正文**——
-- 那样签名签的是一个空壳，法定病历的完整性当场崩塌（V45StructuredEmrTest 有专门用例钉死）。
-- `content_json` 只服务两件事：前端回填表单、1098 的结构化元素检索。它**不参与签名、不参与补正
-- 快照、不参与 CDR 抽取、不参与病案首页**——所有既有读方一个都不用改。
--
-- ============ 纪律二：旧文本病历一字节不动 ============
-- 新增列**全部 nullable、无默认值、无回填**。历史病历的 content_json 与 template_id 永远是 null，
-- 严禁拿正文去反解结构化值回填——那是伪造。既有写路径（DoctorStationService.saveEmr /
-- InpEmrController.addRecord）在**不传新参数 `fields` 时行为逐字节不变**，这条由单测钉死。
--
-- ============ 纪律三：text 存 JSON，不开 jsonb ============
-- 本仓既有 JSON 落库一律用 text 列（PatientCareController:121 的 care_pathway.items 即是），
-- 沿用惯例不首开 jsonb：jsonb 会带来列类型迁移、GIN 索引与运维口径三件新事，
-- 而 1098 的检索量级（结构化病历本就是新数据）用 `content_json::json ->> ?` 完全够。
-- 读侧一律**参数化**（`->> cast(? as text)`），绝不拼接字段码进 SQL。
--
-- ============ 纪律四：datatype 六型，一个不少一个不多 ============
-- 1075★ 明文列举「文本、数值、复选、单选、多选、日期」六种，本表 CHECK 就锁这六个。
-- 不自作主张加 SELECT/TABLE/RICHTEXT——多出来的型没有参数依据，前端也没有对应控件，
-- 只会变成第二个「PREOP 幽灵类型」（v42 踩过：代码硬要一个下拉里根本没有的类型）。
-- 新表加 CHECK 与 v42「不给 record_type 加 CHECK」不矛盾：那条针对的是**既有列上的历史脏数据**
-- 会挡住 Flyway，本表是新表、无历史行、无脏值风险（同 V136 rx_template 的做法）。

-- ===== 1) 模板字段定义 =====
create table emr_template_field (
    id          bigserial   primary key,
    -- 归属模板。emr_template 属 v45 车道 H（模板体系），本迁移**只引用不改动**它。
    -- on delete cascade：模板真被删时字段定义没有独立存在的意义（模板的正常下线是停用不是删行）。
    template_id bigint      not null references emr_template (id) on delete cascade,
    -- 结构化元素编码：content_json 的键，也是 1098 检索接口的 fieldCode 入参。
    -- 写侧限 [A-Za-z0-9_]，既是编码规范也让检索入参有一条可校验的白名单（非法返 4028）。
    field_code  varchar(64) not null,
    -- 元素标签：渲染进正文的那个中文名（"体温"→"体温（℃）：38.5"）
    label       varchar(64) not null,
    -- 1075★ 六型：TEXT 文本 / NUMBER 数值 / CHECKBOX 复选 / RADIO 单选 / MULTI 多选 / DATE 日期
    datatype    varchar(16) not null,
    required    boolean     not null default false,
    -- 渲染与录入顺序，也是 989★「元素快速跳转」的 Tab 序（前端按 sort_no 排 tabindex）
    sort_no     int         not null default 0,
    -- RADIO/MULTI 的候选值：**JSON 数组存 text**（["轻","中","重"]），沿用纪律三
    value_set   text,
    placeholder varchar(128),
    -- 计量单位（℃/mmHg/次·分⁻¹），只参与显示与正文渲染，不参与任何换算
    unit        varchar(16),
    -- **建表即带 enabled**（V136:8-13 写下的纪律：emr_template 当年没带，导致"停用模板"三版做不了）。
    -- 停用是软开关：字段停用后历史病历的 content_json 仍解释得通"当时录的是什么"。
    enabled     boolean     not null default true,
    created_at  timestamptz not null default now(),
    constraint uk_emr_tpl_field      unique (template_id, field_code),
    constraint ck_emr_tpl_field_type check (datatype in ('TEXT', 'NUMBER', 'CHECKBOX', 'RADIO', 'MULTI', 'DATE'))
);
-- 取某模板的字段定义（渲染动态表单 / 保存时校验）恒按 (template_id, sort_no) 取全量
create index idx_emr_tpl_field on emr_template_field (template_id, sort_no, id);

-- ===== 2) 病历侧车列（门诊 + 住院各两列，全部 nullable） =====
-- content_json：{"fieldCode": 值} 的扁平对象。值的 JSON 型按 datatype 落：
--   TEXT/DATE/RADIO → 字符串；NUMBER → 数值；CHECKBOX → 布尔；MULTI → 字符串数组。
--   扁平不嵌套是为 1098：`content_json::json ->> 'temp'` 一步取到，不必先 -> 'fields'。
-- template_id：本份病历是照哪张模板写的（回显表单、检索时带出模板名）。
alter table outp_emr add column content_json text;
alter table outp_emr add column template_id  bigint references emr_template (id);

alter table inp_medical_record add column content_json text;
alter table inp_medical_record add column template_id  bigint references emr_template (id);

-- 1098 检索的支撑索引：**部分索引**只收结构化病历。结构化病历是本版起才有的新数据，
-- 占全量病历的比例长期很小，部分索引让检索不必扫历史全表；排序列与端点的
-- `order by 时间 desc` 一致，limit 201 可直接走索引。
-- 刻意**不建 GIN**：text 列上的 GIN 需先建表达式索引 `(content_json::json)`，而 json（非 jsonb）
-- 没有默认 GIN opclass，硬上要么改列型要么建函数索引——两者都超出"侧车"的分寸。
create index idx_outp_emr_structured   on outp_emr           (updated_at desc) where content_json is not null;
create index idx_inp_record_structured on inp_medical_record (created_at desc) where content_json is not null;

-- ===== 3) 刻意不做的三件事 =====
-- a) **不建菜单**：字段定义是模板维护页里的一块（车道 H 的菜单 111），单独开菜单会出现两个入口。
-- b) **不动 emr_template 一列**：enabled/scope/授权属车道 H 的 V138，本迁移只加外键引用。
-- c) **不预置任何字段种子**：字段定义是院方按科室业务定的，预置一套"示范模板字段"会被当成
--    国标数据元用（与 v42「std_code 留空由实施期填」同一条纪律）。

-- ===== 1082★ 跨患者复制粘贴管控开关（合版补：车道J 因用量上限中断，代码已完成但未 seed）=====
--
-- 三态 off|warn|block，**默认 warn**——不能默认 block：
--   * 本平台此前从无此限制，上来就硬拦是运行时打扰（全仓 gate 纪律：默认 warn 运行时零打扰）；
--   * block 会连带挡住合理的模板套用与同患者续写。
-- 诚实边界（已随 /emr-ref/copy-policy 一起下发给前端显示）：**只能识别本系统内复制的片段**
-- （复制时记下来源患者）；从外部编辑器/浏览器/纸质材料粘贴进来的内容识别不到，也不拦截。
-- 该管控**纯前端行为**：block 档也只拒绝这一次粘贴动作，**不在任何写路径上加拦截**。
insert into sys_config (cfg_key, cfg_value, remark)
values ('emr.copy.cross_patient', 'warn',
        '跨患者病历复制粘贴管控 off/warn/block（默认 warn）；仅识别系统内复制，外部来源识别不到')
on conflict (cfg_key) do nothing;
