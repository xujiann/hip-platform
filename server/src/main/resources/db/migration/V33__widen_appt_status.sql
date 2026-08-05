-- 三十四期修正：预约状态列拓宽（原 varchar(8) 放不下 CANCELLED）
alter table med_appointment alter column status type varchar(16);
