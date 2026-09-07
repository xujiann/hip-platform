-- v53 合版补：登记 emr.gate.timeliness。
--
-- ============ 为什么必须补 ============
-- V159 建了时限规则表，服务里也写了三态 gate（EmrTimelinessService.GATE_KEY），
-- 但**这个键从没被 seed 进 sys_config**。而 `PUT /api/config/{key}` 只 UPDATE 不 INSERT——
-- 于是 `update sys_config set cfg_value='block' where cfg_key='emr.gate.timeliness'`
-- **影响 0 行**，改不动。三态里 block 与 off **两档不可达**，实际恒为 warn。
--
-- 服务自己在 GATE_KEY_NOTE 里认了这件事（诚实），但「认了」不等于「能用」：
-- 院方按配置手册去调档位会发现调不动，且**没有任何报错**——
-- PUT 返回成功、值没变，比报错更难发现。
--
-- 同类教训见 v52：V144 插了菜单却没插 sys_role_menu，菜单存在但谁也点不进去，
-- 也是「东西在、但不可用、且不报错」。**建了开关就要把开关接上电**。

-- 注意 remark 列是 varchar(255)（第一版写超了直接把 Flyway 打红）——
-- 详细口径写在上面的注释与 docs/配置手册.md 里，不硬塞进这一列。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.gate.timeliness', 'warn',
     '病历书写时限质控 gate（v53）：off 旁路 / warn 只提示不拦截（默认）/ block 以 5749 拦出院归档。'
     || '默认 warn：历史病历普遍缺可靠书写时刻锚点，直接 block 会让存量出院大面积失败。'
     || '收紧前先看 indicators 的 coverage；坏配置回落 warn。')
on conflict (cfg_key) do nothing;

-- 零回填：不改任何既有配置行，on conflict do nothing 保证重复执行安全。
