-- 二十九期：患者服务与支付闭环（聚合支付 / 智能导诊 / 报告分享）

-- 聚合支付单（扫码支付：微信/支付宝，Mock 通道）
create table pay_order (
    id              bigserial primary key,
    pay_no          varchar(32)   not null unique,
    registration_id bigint        not null references outp_registration (id),
    amount          numeric(12,2) not null,
    channel         varchar(16)   not null,               -- WECHAT/ALIPAY
    status          varchar(16)   not null default 'PENDING', -- PENDING/SUCCESS/CANCELLED
    qr_content      varchar(255),
    charge_id       bigint,                               -- 支付成功后关联的结算单
    created_at      timestamptz   not null default now(),
    paid_at         timestamptz
);

-- 智能导诊规则（症状关键词 → 科室推荐）
create table triage_guide (
    id       bigserial primary key,
    keyword  varchar(64)  not null,
    dept_name varchar(64) not null,
    advice   varchar(255)
);

insert into triage_guide (keyword, dept_name, advice) values
    ('胸痛',   '急诊科',   '胸痛请立即就诊，勿自行驾车；持续不缓解拨打 120'),
    ('胸闷',   '心血管内科', '建议心电图检查'),
    ('心悸',   '心血管内科', '建议心电图与电解质检查'),
    ('咳嗽',   '呼吸内科', '持续两周以上建议胸部影像检查'),
    ('发热',   '发热门诊', '请佩戴口罩前往发热门诊'),
    ('腹痛',   '消化内科', '急性剧烈腹痛请挂急诊'),
    ('腹泻',   '消化内科', '注意补液，留取大便标本可加快诊断'),
    ('头痛',   '神经内科', '伴呕吐/意识改变请立即急诊'),
    ('头晕',   '神经内科', '监测血压后就诊'),
    ('皮疹',   '皮肤科',   '勿抓挠，拍照记录变化过程'),
    ('血糖',   '内分泌科', '空腹 8 小时后就诊便于检测'),
    ('外伤',   '急诊科',   '开放性伤口请勿自行涂抹药物');

-- 报告对外分享（云胶片/报告短链，匿名可访、限时失效）
create table report_share (
    id         bigserial primary key,
    token      varchar(64) not null unique,
    exam_id    bigint      not null references ris_exam (id),
    expire_at  timestamptz not null,
    access_cnt int         not null default 0,
    created_at timestamptz not null default now()
);

-- ===== 菜单 =====
insert into sys_menu (id, parent_id, name, type, path, perm, icon, sort_no) values
    (53, 7, '扫码支付台', 'MENU', '/pay', 'pay:center', 'Money', 14);

insert into sys_role_menu (role_id, menu_id)
select r.id, m.id from sys_role r, sys_menu m
where r.code = 'ADMIN' and m.id in (53);
