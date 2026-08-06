-- 案件查办域模型（依据：医疗保障行政处罚程序暂行规定·国家医保局令第4号；案由依据：苏医保督〔2024〕1号）

-- 案由字典：4 类违法主体 × 9 种案由 × 35 项违法情形
create table case_cause (
    id           bigserial primary key,
    subject_type varchar(16)  not null,   -- AGENCY 经办机构 / PROVIDER 定点医药机构 / INDIVIDUAL 自然人 / OTHER 其他主体
    category     varchar(64)  not null,   -- 案由种类（案件办理适用的二级案由）
    item_no      int          not null unique,  -- 违法情形序号（1-35）
    description  varchar(512) not null,   -- 违法情形
    enabled      boolean      not null default true
);

-- 线索（第14条：15个工作日内核查，可延长15个工作日）
create table case_clue (
    id            bigserial primary key,
    clue_no       varchar(32)  not null unique,     -- XS20260001
    source        varchar(16)  not null,            -- INSPECTION 监督检查/COMPLAINT 投诉举报/TRANSFER 部门移送/ASSIGNED 上级交办/MONITOR 智能监控
    content       text         not null,
    suspect_name  varchar(128) not null,            -- 违法嫌疑人
    suspect_type  varchar(16)  not null,            -- 同案由主体类别
    received_at   date         not null,
    deadline_at   date         not null,            -- 核查期限（15个工作日）
    extended      boolean      not null default false,
    extend_reason varchar(255),
    status        varchar(16)  not null default 'PENDING',  -- PENDING/FILED 已立案/REJECTED 不予立案
    verify_result text,
    handler       varchar(64),
    created_by    varchar(64),
    created_at    timestamptz  not null default now()
);

-- 案件（状态机：INVESTIGATING⇄SUSPENDED；→TERMINATED；→REPORTED→NOTIFIED→DECIDED→DELIVERED→CLOSED）
create table case_file (
    id              bigserial primary key,
    case_no         varchar(64)  not null unique,   -- 示医保案〔2026〕1号
    name            varchar(255) not null,          -- 调查阶段：主体+涉嫌+案由+案；决定后去"涉嫌"
    clue_id         bigint references case_clue (id),
    cause_id        bigint       not null references case_cause (id),
    procedure_type  varchar(16)  not null default 'NORMAL',  -- NORMAL 普通程序 / SUMMARY 简易程序（第48条）
    party_name      varchar(128) not null,
    party_type      varchar(16)  not null,          -- AGENCY/PROVIDER/INDIVIDUAL/OTHER
    party_credit_no varchar(64),                    -- 统一社会信用代码/身份证号
    party_address   varchar(255),
    party_legal_rep varchar(64),
    party_contact   varchar(64),
    summary         text,                           -- 案情摘要
    amount_involved numeric(14,2) default 0,        -- 涉案基金金额
    status          varchar(16)  not null default 'INVESTIGATING',
    filed_at        date         not null,
    deadline_at     date         not null,          -- 办案期限（立案+90日，第45条）
    extension_days  int          not null default 0,-- 已批准延长天数（30 → 再+最长60）
    suspend_reason  varchar(255),                   -- 中止原因（第42条）
    suspended_at    date,
    terminate_reason varchar(255),                  -- 终止原因（第47条）
    terminated_at   date,
    reported_at     date,                           -- 调查终结报告日期
    decided_at      date,
    delivered_at    date,
    closed_at       date,
    close_reason    varchar(16),                    -- EXECUTED 执行完毕/COURT 法院受理强执/NO_NEED 无须执行/OTHER
    archive_no      varchar(64),                    -- 案卷号（一案一卷，第57条）
    defer_approved  boolean      not null default false,  -- 暂缓/分期缴纳已批准（第54条）
    court_enforce_applied boolean not null default false, -- 已申请法院强制执行（第55条）
    created_by      varchar(64),
    created_at      timestamptz  not null default now()
);
create index idx_case_status on case_file (status);
create index idx_case_filed on case_file (filed_at desc);

-- 办案人员（第16条：不少于两人；第5条：回避）
create table case_officer (
    id           bigserial primary key,
    case_id      bigint      not null references case_file (id),
    name         varchar(64) not null,
    cert_no      varchar(64) not null,   -- 执法证号
    duty         varchar(16) not null default 'MEMBER',  -- LEAD 主办 / MEMBER 协办
    avoided      boolean     not null default false,      -- 已回避
    avoid_reason varchar(255)
);

-- 证据（第19条 8 类；第26-28条先行登记保存；第29-33条封存）
create table case_evidence (
    id             bigserial primary key,
    case_id        bigint       not null references case_file (id),
    type           varchar(16)  not null,  -- DOCUMENT 书证/PHYSICAL 物证/AV 视听资料/EDATA 电子数据/TESTIMONY 证人证言/STATEMENT 当事人陈述/EXPERT 鉴定意见/RECORD 勘验笔录现场笔录
    name           varchar(255) not null,
    source         varchar(255),
    obtained_at    date         not null,
    keeper         varchar(64),
    note           varchar(512),
    register_hold  boolean      not null default false,  -- 先行登记保存
    hold_expire_at date,                                  -- 7个工作日处理期限
    hold_disposal  varchar(16),                           -- PRESERVE 证据保全/SEAL 封存/RELEASE 解除
    sealed         boolean      not null default false,   -- 封存中
    seal_expire_at date,                                   -- 30日，可延30日
    seal_extended  boolean      not null default false
);

-- 执法文书/笔录（第20/24/36/42/44条等）
create table case_document (
    id       bigserial primary key,
    case_id  bigint       not null references case_file (id),
    doc_type varchar(32)  not null,  -- SCENE_RECORD 现场笔录/INQUIRY_RECORD 询问笔录/ASSIST_LETTER 协查函/FINAL_REPORT 调查终结报告/SUSPEND_DECISION 中止决定书/RESUME_NOTICE 恢复通知/TERMINATE_DECISION 终止决定/NOTICE 告知书/HEARING_RECORD 听证笔录/MEETING_RECORD 集体讨论记录/DECISION 决定书/DELIVERY_RECEIPT 送达回证/CLOSE_REPORT 结案报告/OTHER
    title    varchar(255) not null,
    content  text         not null,
    made_at  date         not null,
    maker    varchar(64),
    signed   boolean      not null default false,  -- 当事人签名/盖章确认（拒签须注明）
    note     varchar(255)
);

-- 期限扣除（第45条：检测检验、鉴定、听证、公告、专家评审不计入办案期限）
create table case_period_exclusion (
    id       bigserial primary key,
    case_id  bigint      not null references case_file (id),
    reason   varchar(16) not null,  -- TEST 检测检验/APPRAISE 鉴定/HEARING 听证/ANNOUNCE 公告/EXPERT 专家评审
    start_at date        not null,
    end_at   date,
    note     varchar(255)
);

-- 法制审核（第37-40条）
create table case_review (
    id              bigserial primary key,
    case_id         bigint      not null references case_file (id),
    required_reason varchar(255) not null,  -- 触发情形
    submitted_at    date        not null,
    deadline_at     date        not null,   -- 10个工作日
    reviewer        varchar(64),
    opinion_type    varchar(24),            -- AGREE 同意/CONTINUE 继续调查/CHANGE 变更/CORRECT 纠正/OTHER
    opinion         text,
    reviewed_at     date,
    passed          boolean
);

-- 处罚告知/陈述申辩/听证（第41条）
create table case_notice (
    id                bigserial primary key,
    case_id           bigint        not null references case_file (id),
    notified_at       date          not null,
    content           text          not null,       -- 拟处罚的事实、理由及依据
    proposed_fine     numeric(14,2) not null default 0,
    proposed_recoup   numeric(14,2) not null default 0,  -- 拟责令退回基金
    hearing_entitled  boolean       not null default false,
    hearing_requested boolean       not null default false,
    hearing_held_at   date,
    statement         text,          -- 当事人陈述申辩
    statement_review  text           -- 复核意见（成立应采纳）
);

-- 负责人集体讨论（第44条）
create table case_meeting (
    id         bigserial primary key,
    case_id    bigint      not null references case_file (id),
    held_at    date        not null,
    attendees  varchar(255) not null,
    record     text        not null,   -- 不同意见如实记录
    conclusion varchar(255) not null
);

-- 处理决定（第43条 5 种）
create table case_decision (
    id                bigserial primary key,
    case_id           bigint        not null unique references case_file (id),
    decision_type     varchar(24)   not null,  -- PUNISH 行政处罚/NO_PUNISH 不予处罚/NOT_ESTABLISHED 违法事实不成立/TRANSFER_ADMIN 移送其他部门/TRANSFER_JUDICIAL 移送司法机关
    decision_no       varchar(64) unique,      -- 处罚决定书文号
    fine_amount       numeric(14,2) not null default 0,
    recoup_amount     numeric(14,2) not null default 0,  -- 责令退回基金（退回原基金财政专户，第53条）
    confiscate_amount numeric(14,2) not null default 0,  -- 没收违法所得（上缴国库）
    other_measures    varchar(512),            -- 约谈、建议经办机构处理等
    content           text          not null,
    decided_at        date          not null,
    published         boolean       not null default false  -- 处罚决定公开（第46条）
);

-- 送达（第59条：当场交付或7个工作日内送达，可电子送达）
create table case_delivery (
    id           bigserial primary key,
    case_id      bigint      not null references case_file (id),
    method       varchar(16) not null,  -- DIRECT 直接/MAIL 邮寄/LEFT 留置/ELECTRONIC 电子（须签确认书）/ANNOUNCE 公告
    delivered_at date        not null,
    receiver     varchar(64),
    note         varchar(255)
);

-- 执行记录（第52-55条）
create table case_execution (
    id          bigserial primary key,
    case_id     bigint        not null references case_file (id),
    kind        varchar(16)   not null,  -- FINE 罚款/RECOUP 退回基金/CONFISCATE 没收违法所得/LATE_FEE 加处罚款
    amount      numeric(14,2) not null,
    paid_at     date          not null,
    method      varchar(16)   not null,  -- ONSITE 当场收缴(≤100元)/BANK 银行缴付/INSTALLMENT 分期
    note        varchar(255)
);

-- 案由种子：苏医保督〔2024〕1号 附件全量 35 项
insert into case_cause (subject_type, category, item_no, description) values
    ('AGENCY',     '骗取医疗保障基金支出', 1,  '通过伪造、变造、隐匿、涂改、销毁医学文书、医学证明、会计凭证、电子信息等有关资料或者虚构医药服务项目等方式，骗取医疗保障基金支出'),
    ('AGENCY',     '骗取医疗保障基金支出', 2,  '以欺诈、伪造证明材料或者其他手段骗取医疗、生育保险基金支出'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 3,  '未建立医疗保障基金使用内部管理制度，或者没有专门机构或者人员负责医疗保障基金使用管理工作'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 4,  '未按照规定保管财务账目、会计凭证、处方、病历、治疗检查记录、费用明细、药品和医用耗材出入库记录等资料'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 5,  '未按照规定通过医疗保障信息系统传送医疗保障基金使用有关数据'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 6,  '未按照规定向医疗保障行政部门报告医疗保障基金使用监督管理所需信息'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 7,  '未按照规定向社会公开医药费用、费用结构等信息'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 8,  '除急诊、抢救等特殊情形外，未经参保人员或者其近亲属、监护人同意提供医疗保障基金支付范围以外的医药服务'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 9,  '拒绝医疗保障等行政部门监督检查或者提供虚假情况'),
    ('PROVIDER',   '违反医疗保障定点管理规定', 10, '定点医疗机构以医保支付政策为由拒收患者'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 11, '分解住院、挂床住院'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 12, '违反诊疗规范过度诊疗、过度检查、分解处方、超量开药、重复开药或者提供其他不必要的医药服务'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 13, '重复收费、超标准收费、分解项目收费'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 14, '串换药品、医用耗材、诊疗项目和服务设施'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 15, '为参保人员利用其享受医疗保障待遇的机会转卖药品，接受返还现金、实物或者获得其他非法利益提供便利'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 16, '将不属于医疗保障基金支付范围的医药费用纳入医疗保障基金结算'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 17, '为非定点医药机构提供医保费用结算服务'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 18, '高套病种编码，转嫁住院费用'),
    ('PROVIDER',   '违反医疗保障基金使用规定', 19, '造成医疗保障基金损失的其他违法行为'),
    ('PROVIDER',   '骗取医疗保障基金支出', 20, '诱导、协助他人冒名或者虚假就医、购药'),
    ('PROVIDER',   '骗取医疗保障基金支出', 21, '提供虚假证明材料'),
    ('PROVIDER',   '骗取医疗保障基金支出', 22, '串通他人虚开费用单据'),
    ('PROVIDER',   '骗取医疗保障基金支出', 23, '伪造、变造、隐匿、涂改、销毁医学文书、医学证明、会计凭证、电子信息等有关资料'),
    ('PROVIDER',   '骗取医疗保障基金支出', 24, '虚构医药服务项目'),
    ('PROVIDER',   '骗取医疗保障基金支出', 25, '以骗取医疗保障基金为目的，实施违反医疗保障基金使用规定造成医疗保障基金损失的行为之一'),
    ('PROVIDER',   '骗取医疗保障基金支出', 26, '其他骗取医疗保障基金支出的行为'),
    ('INDIVIDUAL', '违反医疗保障基金使用规定', 27, '将本人的医疗保障凭证交由他人冒名使用'),
    ('INDIVIDUAL', '违反医疗保障基金使用规定', 28, '重复享受医疗保障待遇'),
    ('INDIVIDUAL', '违反医疗保障基金使用规定', 29, '利用享受医疗保障待遇的机会转卖药品，接受返还现金、实物或者获得其他非法利益'),
    ('INDIVIDUAL', '骗取医疗保障基金支出', 30, '以骗取医疗保障基金为目的，实施违反医疗保障基金使用规定的行为之一'),
    ('INDIVIDUAL', '骗取医疗保障基金支出', 31, '使用他人医疗保障凭证冒名就医、购药'),
    ('INDIVIDUAL', '骗取医疗保障基金支出', 32, '通过伪造、变造、隐匿、涂改、销毁医学文书、医学证明、会计凭证、电子信息等有关资料或者虚构医药服务项目等方式，骗取医疗保障基金支出'),
    ('INDIVIDUAL', '骗取医疗保障待遇', 33, '以欺诈、伪造证明材料或者其他手段骗取医疗、生育保险待遇'),
    ('INDIVIDUAL', '骗取社会救助资金（物资或者服务）', 34, '采取虚报、隐瞒、伪造等手段，骗取社会救助资金、物资或者服务'),
    ('OTHER',      '骗取医疗保障基金支出', 35, '医药企业、用人单位等以欺诈、伪造证明材料或者其他手段骗取医疗保障基金支出');
