-- v53 合版补：审签「书写人」必须与病历的实际书写人一致（数据库层兜底）。
--
-- ============ 为什么 CHECK 不够 ============
-- V158 已有 `chk_emr_countersign_not_self` 挡 `author_id <> countersigner_id`，
-- 但 CHECK **只能比同一行的两列**，比不到 `inp_medical_record.doctor_id`。
-- v53 复核实测：绕过服务层直连 psql，**谎报 author_id** 就能把自签伪装成他签——
--     真实书写人 doctor_id = 甲
--     insert 时 author_id 填乙、countersigner_id 填甲
--   → CHECK 通过（甲≠乙），落库后 join 一看就是甲自签自审。
--
-- 服务层本身是对的（`CountersignService:193` 的 authorId 取自病历的 doctor_id，
-- 不接受调用方传入），所以这条路径只有**直连数据库**才走得通。
-- 那更像是 DBA 权限层面的事，但审签记录是**举证材料**——
-- 「这份病历有上级签过」这句话要经得起对方律师逐行查库。
-- 账目类约束值得写两遍：应用挡一道，数据库再挡一道。

create or replace function chk_countersign_author() returns trigger as $$
declare real_author bigint;
begin
    select doctor_id into real_author from inp_medical_record where id = new.record_id;
    -- 病历没有 doctor_id（历史数据）时不阻断：那是既有数据质量问题，
    -- 不该让一条本来合规的审签写不进去。此时退化为只有 CHECK 那一道。
    if real_author is null then
        return new;
    end if;
    if new.author_id is distinct from real_author then
        raise exception '审签记录的书写人(%) 与病历实际书写人(%) 不一致，拒绝写入',
            new.author_id, real_author
            using errcode = 'check_violation';
    end if;
    if new.countersigner_id = real_author then
        raise exception '审签人不得是该病历的书写人本人（书写人=%），拒绝写入', real_author
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_emr_countersign_author
    before insert or update on emr_countersign
    for each row execute function chk_countersign_author();

comment on function chk_countersign_author() is
    'v53：审签书写人对账。CHECK 只能比同行两列，比不到 inp_medical_record.doctor_id；'
    '谎报 author_id 可把自签伪装成他签，本触发器把 author_id 与病历实际书写人对账后兜底。';

-- 零回填：不校验、不清理任何既有行。历史审签记录（若有）保持原样——
-- 事后按新规则去删既有举证记录，本身就是在动证据。
