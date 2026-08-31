-- v40 病案室待编码/待归档工作队列：超期阈值配置键（纯只读队列，无新表）。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('mr.archive.overdue_days', '3', '病案归档超期阈值（天）：出院超过该天数仍未归档的病案在病案室工作队列标红（只提示不阻断）')
on conflict (cfg_key) do nothing;

-- 加速工作队列扫描：只索引「已出院且未收尾」的病案，队列量级为几十条
create index if not exists idx_inp_admission_mr_workqueue
    on inp_admission (discharged_at desc)
    where status = 'DISCHARGED' and (archived = false or discharge_diag_icd is null);
