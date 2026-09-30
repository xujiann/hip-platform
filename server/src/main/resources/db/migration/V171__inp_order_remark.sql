-- v74 补 1006★ 唯一缺口：住院医嘱的备注 / 注意事项 / 加急。
-- 三方复核（docs/验收/v74复核-v44四条/）三票维持 1006 为"部分响应"，理由只有一条：
-- 门诊四类医嘱 V137 已有备注与加急，住院医嘱（inp_order）从界面到表全链路没有，
-- 而同章节 1004★/1005★ 与 1006 自己的现说明都写"门诊与住院"——投标方口径就是全院。
--
-- 列宽与 V137 的 outp_order 逐一对齐（remark/notice varchar(200)，urgent boolean default false），
-- 让门诊、住院两侧的前端 maxlength 与工作站显示可以共用同一套口径。
-- 【urgent 用 boolean default false 而非 not null default false】：同 V137——历史行不改写，
-- 读方一律按 coalesce(urgent,false) 或前端 truthy 判断，避免大表全量回填锁表。
-- 【只加列，不动写路径的既有语义】：临时/长期医嘱的计费、执行行生成一字未动。
alter table inp_order add column remark varchar(200);
alter table inp_order add column urgent boolean default false;
alter table inp_order add column notice varchar(200);
comment on column inp_order.remark is 'v74：医嘱备注（医生开立时填，护士站执行队列显示）';
comment on column inp_order.urgent is 'v74：加急标志（null 视同 false）';
comment on column inp_order.notice is 'v74：注意事项（医生开立时填，护士站执行队列显示）';
