-- v62（2530 复核）：登记 gate emr.gate.pathology.grossfield。
--
-- ============ 这个 gate 管什么 ============
-- PathologyProcessController.reviseGrossFields（PUT /api/pathology/process/grossing/{id}/fields）
-- 此前只要求「字段与自由描述至少填一项」，于是「本来有 N 项结构化字段、修订后一项不填」被视为合法：
-- 新版字段行落 0 行、结构化记录静默退化成一段扁平文本——正是「确保描述内容的结构化存储」要消灭的形态。
-- 本 gate 三态管这条退化：off 不判 / warn 照常落库但返回体 warnings 回带（默认）/ block 以 5277 拦、零写入。
--
-- ============ 为什么默认 warn ============
-- 存量标本本就可能一行字段都没有（V164 之前的历史标本、以及只写自由文本的标本），
-- 而这条守卫看的是「修订前有、修订后没有」，仍有正当场景（把录错的字段整体改写成自由描述再重填）。
-- 直接 block 会拦住正常修订。坏配置（'blocked' / 'true' / '1'）在代码侧回落 warn 而非 off——
-- 把笔误变成静默关闭校验是更坏的默认。
--
-- ============ 为什么这一行非插不可 ============
-- PUT /api/config/{key} 是 update 不是 upsert：键不在 sys_config 里时影响 0 行、返回 1401，
-- 院方按手册调档位调不动，而代码侧 ConfigReader 读不到就回落默认档——block 与 off 两档不可达，
-- 且系统表现得一切正常。v53 的 emr.gate.timeliness 就是这样，靠人工复核才发现（后由 V161 补登记）。
-- ReachabilityTest.everyGateKeyIsRegisteredInSysConfig 现在把这条纪律钉成断言。
--
-- 注意 remark 列是 varchar(255)（V161 第一版写超了直接把 Flyway 打红），详细口径写在上面的注释里。
insert into sys_config (cfg_key, cfg_value, remark) values
    ('emr.gate.pathology.grossfield', 'warn',
     '取材修订结构化退化 gate（v62）：off 旁路 / warn 只提示不拦截（默认）/ block 以 5277 拦「修订后一行字段都不剩」。'
     || '默认 warn：存量标本本就可能无字段行，直接 block 会拦住正常修订；坏配置回落 warn。')
on conflict (cfg_key) do nothing;

-- 零回填：不建表、不加列、不改任何既有配置行，on conflict do nothing 保证重复执行安全。
