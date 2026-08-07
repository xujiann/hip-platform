# CHANGELOG

版本纪律：语义化版本；平台迁移段 V1–V999，实施段 V10000+；升级 = 停服→备份→换产物→自动前滚→回归抽查（多医院部署操作指南 §三）。

## 1.0.2（2026-08-07）

安全与并发修复版（两条独立修复分支合并，均为教材撰写抽查发现的缺陷）：

**安全（platform/core）**
- 停用账号的未过期令牌即时失效：JwtAuthenticationFilter 校验 `isEnabled()`，停用即 401
- `GET /api/config/public` 由返回 sys_config 全表改为白名单四键（院名/简称/票据抬头/咨询电话），
  医保比例、审核阈值、模块开关等敏感配置不再出库

**并发（modules/outpatient，V45）**
- 处方/申请单组号弃用内存 AtomicLong（重启种子回绕可撞号），改数据库序列 `outp_order_group_seq`，
  跨实例、跨重启唯一；前缀仍走 `billno_prefix_rx/req` 配置（与 1.0.1 合并）
- 退药库存回补由读-改-写改为原子 `update stock = stock + ?`，并发不丢更新
- 急诊留观占床加部分唯一索引（status='IN' 的 triage_id/bed_no），占床冲突映射 4561/4562

测试 67（新增 DisabledAccountToken/PublicConfigWhitelist/ConcurrencyFix×6/StockRestoreConcurrency）；
e2e-1821 消除套件间数据顺序依赖（挑带结构化结果的检验文档）。

## 1.0.1（2026-08-07）

快赢包：部分响应 152 条中筛出的 8 条一句话增强（docs/验收/部分响应筛选.md），升为正偏离：

- **2067** 住院每日费用清单：`GET /inpatient/admissions/{id}/daily-fees?date=` 按执行日期聚合，出院结算页内嵌页签
- **1227** 结账明细按项目类型检索：`/finance/charge-search` 加 `orderType` 即切明细行模式
- **893** 排班上一周/下一周快捷切换（前端）
- **505** 药师单次待审列表上限：`sys_config.review_pending_limit`（0=不限）
- **1749** 单据编号前缀配置化：`billno_prefix_charge/admission/settle/rx/req` 五键，默认与历史一致（SJ/ZY/CY/CF/SQ）
- **1814** 输血不良反应处置方案字典：`blood_reaction_plan` 表 + 输血记录留痕（`blood_apply.reaction_plan`）
- **1028** 死亡登记卡：`mr_death_card`（死因链 a/b/c/d + ICD）、病案统计页登记页签、打印数据集 `/print/death-card/{id}`
- **1938** 体检套餐"不进入总检结果"项目配置：`pe_exam_package.hidden_items`

新增 `platform/core ConfigReader`（sys_config 轻量读取）；数据库 V44；测试 58（新增 QuickWin101Test×3）；
20 套 E2E（新增 e2e-101 进 CI）。1938 为接口层达成，套餐维护 UI 随体检深化。

## 1.0.0（2026-08-07）

首个定版。范围即采购需求 107 模块的软件可推进全集 + 产品化能力：

- **业务全集**：门诊/住院/医技/护理/院感/运营/数据中心/集成平台八域 55 功能页 + 患者端 H5（功能清单.md）
- **医保域**：目录对照（CSV 导入+对照率）、行级费用分割（门诊+住院）、年度起付线/封顶线待遇模型、
  智能审核雏形、结算/冲正通道（失败回滚）、每日自动对账（差异开工单）、DRG 支付模拟
- **产品化**：机构参数化、模块级功能开关（6 域双路生效）、init-hospital 一键装机、
  impl 实施定制层规约与模板（V10000 段）、五类 SPI 适配点（含 Mock）
- **安全**：JWT 密钥环境变量注入、密码策略/防爆破/90 天提醒、装机强改默认口令、写操作全量审计、敏感操作过滤
- **质量**：55 单元/集成测试、19 套 E2E 回归（CI 三任务）、装机演练与全量彩排通过
- **数据库**：Flyway V1–V43
- **已知边界**：外部条件项（省医保 SDK/CA/微信/设备直连/官方目录）接入点就绪待资质；配套产品项见技术偏离表
