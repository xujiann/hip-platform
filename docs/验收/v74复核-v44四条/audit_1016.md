# 1016★ 审计材料

## 参数原文（逐字，来自 `docs/验收/技术偏离表-v3.csv` 序号 1016）

> 拥有检验申请自动获取病情摘要、诊断，能填写标本类型、采样部位等信息，并根据流向自动获取执行科室。

## 现结论/现说明（逐字）

- 响应结论：**部分响应**
- 响应说明：
  > 本平台门诊医生站可开具检验申请单并生成申请单号，检验标本可按条码流转至检验环节；本条所述申请单自动获取病情摘要与诊断、标本类型与采样部位填写、按流向自动确定执行科室等功能，当前版本尚未提供，列入后续开发计划。

现说明明确写"尚未提供"，但仓库证据显示至少两项（标本类型/采样部位）已在 2026-09-29 提交 `7b64dd4` 落地为可填控件，另两项（病情摘要、诊断）的自动带出在打印页面自 `c8f42386`（2026-09-03）起就已存在——现说明整体已经滞后于代码事实，需要核实滞后到什么程度。

## 章节语境（CSV 1011–1021，同段落主题：检验/检查/手术申请单缺项系列）

| 序号 | 参数摘要（节选） | 响应结论 |
|---|---|---|
| 1011 | 草药倍数药规格校验 | 配套产品响应 |
| 1012 | 草药煎法/炮制/治法 | 部分响应 |
| 1013★ | 检验申请单诊断、加急标志录入 | 部分响应 |
| 1014★ | 检查申请单临床摘要/诊断/检查目的/注意事项录入 | 部分响应 |
| 1015★ | 手术申请单及急诊/日间手术标志 | 部分响应 |
| **1016★** | **检验申请自动获取病情摘要/诊断、填标本类型/采样部位、按流向自动获取执行科室** | **部分响应** |
| 1017★ | 医技检查报告调阅、影像资料浏览、系统集成 | 部分响应 |
| 1018★ | 医技检验报告调阅、系统集成 | 正偏离/无偏离 |
| 1019★ | 电子病历互通（检验/检查/病理/EMR 集中查询） | 部分响应 |
| 1020★ | 图文报告+内嵌影像浏览器 | 部分响应 |

同段落是"门诊申请单开具"系列条款，1013/1014/1016 都在指同一处（门诊医生站"检查检验"页签）的字段缺失问题；V137 迁移注释里 1013/1014/1016 是同一批（v44 车道G）一次性补列，2026-09-29 的 `7b64dd4` 是把这批列第一次接上界面控件。没有发现前后行被拆分重复计分的情况，语境正常。

## 子要求清单与判定

拆分依据：参数句含 5 个不可再拆的动作——①自动获取病情摘要 ②自动获取诊断 ③填写标本类型 ④填写采样部位 ⑤按流向自动获取执行科室。

| # | 子要求 | 判定 | 演示路径 | 证据 file:line |
|---|---|---|---|---|
| 1 | 检验申请**自动获取病情摘要** | 部分符合 | 门诊医生站 → 患者接诊后进入"检查检验"页签开检验项目并"提交申请" → 切到"已开医嘱(N)"页签 → 点"打印单据"下拉按钮 → 选"检验申请单" → 新开的打印预览页面自动显示"病史摘要：{主诉；现病史}"，全程无需医生手动填写。**但在"检查检验"页签本身开单时，界面上完全看不到这个摘要**——医生提交申请那一刻是"盲提"，只有事后打印才能看见系统已经带出的内容。 | 自动取值与展示：`frontend/shell/src/views/PrintView.vue:201`（`病史摘要：{{ briefHistory }}`，`type==='lab-request'` 分支）、`frontend/shell/src/views/PrintView.vue:396-400`（`briefHistory` computed，取 `chief_complaint`+`present_illness`）；后端取数：`server/src/main/java/cn/hip/server/web/PrintReportController.java:242-247`（`select chief_complaint, present_illness ... from outp_emr`）。开单页签本身无展示：`frontend/shell/src/views/outpatient/DoctorStationView.vue:339-385`（"检查检验"页签全部列定义，无病史/摘要列）。此能力自 `c8f42386`（2026-09-03）即存在，非 `7b64dd4` 新增。 |
| 2 | 检验申请**自动获取诊断** | 部分符合 | 同上路径，打印预览页面顶部自动显示"临床诊断：{诊断名(ICD码)；...}"，同样无需手动填写，同样只在打印页可见、开单页签看不到。 | `frontend/shell/src/views/PrintView.vue:164`（`临床诊断：{{ diagText }}`，`type !== 'guide-sheet'` 时全单据类型通用，含 `lab-request`）、`:393-394`（`diagText` computed）；后端：`PrintReportController.java:237-241`（`select icd_code, icd_name, primary_diag from outp_diagnosis`）。同样自 `c8f42386` 起已存在。 |
| 3 | **填写标本类型** | 符合 | 门诊医生站 → 接诊患者 → "检查检验"页签 → 搜索框（占位符"搜索检验/检查/治疗项目"）选中检验类项目 → 点"加入"按钮 → 该行"申请信息"列出现"标本类型"输入框（占位符原文"标本类型"），可手动录入并随行保存；提交后打印页"标本类型 / 采样部位"列同步显示。 | UI 输入框：`frontend/shell/src/views/outpatient/DoctorStationView.vue:358-359`（`row.category === 'LAB'` 分支，`<el-input v-model="row.specimenType" ... placeholder="标本类型" maxlength="32">`）；列宽 32 与迁移一致：`server/src/main/resources/db/migration/V137__outp_order_fields.sql:45`（`specimen_type varchar(32)`）；打印回显：`frontend/shell/src/views/PrintView.vue:211`（`[r.specimen_type, r.sampling_site].filter(Boolean).join(' / ')`）。这是 `7b64dd4` 当次新增的控件，此前该列后端一直能收但医生站无入口（同 commit message 所述）。 |
| 4 | **填写采样部位** | 符合 | 同上路径，同一行紧邻"标本类型"的第二个输入框（占位符原文"采样部位"）。 | `frontend/shell/src/views/outpatient/DoctorStationView.vue:360`（`<el-input v-model="row.samplingSite" ... placeholder="采样部位" maxlength="32">`）；列宽：`V137__outp_order_fields.sql:46`（`sampling_site varchar(32)`）；打印回显同上 `PrintView.vue:211`。 |
| 5 | **按流向自动获取执行科室** | 部分符合 | 门诊医生站开检验申请提交后，打印预览页面"检验申请单"表格自动列出"执行科室"一列（如"检验科"），全程无需医生选择或填写；但该"科室"并非按患者/标本条件动态计算的"流向"，而是收费项目字典里给每个检验项目**固定配置**的一个部门外键（`md_charge_item.exec_dept_id`），且这个字段**在医生站开单界面完全不可见**（连资料提示都没接进开单搜索框），只在打印页被动带出。 | 打印页展示：`frontend/shell/src/views/PrintView.vue:212`（`{{ r.exec_dept_name \|\| '—' }}`）；数据来源与开发者自述性质：`platform/masterdata/src/main/java/cn/hip/platform/masterdata/web/MasterDataController.java:240`（注释"exec_dept 即 1016★「按流向自动取执行科室」的数据来源"）、`:247-251`（`ci.exec_dept_id`, `left join sys_dept ed on ed.id = ci.exec_dept_id`，纯字典外键 join，无路由/条件逻辑）；测试佐证：`server/src/test/java/cn/hip/server/V44OrderFieldsTest.java:273`（`assertNotNull(hit.get("exec_dept_name"), "1016 要求：按流向自动取执行科室")`）；医生站开单搜索未接该资料提示端点：`frontend/shell/src/views/outpatient/DoctorStationView.vue:1324-1326`（`searchChargeItems` 调用的是 `/masterdata/charge-items`，不是带 `exec_dept_name` 的 `orderHints` 端点）。 |

### 附加发现（供三方复核参考，非独立子要求）

`V137__outp_order_fields.sql:50` 的列注释写"v44 临床/病情摘要（**1014/1016**），可由病历自动带入后医生改写"——把 `clinical_summary` 列同时记在 1014 和 1016 名下，暗示该列本应服务 1016 的"病情摘要"。但 `7b64dd4` 实际接线时，`clinicalSummary` 输入框只接给了 `category === 'EXAM'` 分支（`DoctorStationView.vue:364`），`LAB` 分支完全没有 `clinical_summary` 字段；且无论 EXAM 还是 LAB，代码里都找不到"由病历自动带入"的逻辑——`addLabLine()`（`DoctorStationView.vue:1329-1334`）新增行时只塞 `{orderType, itemId, itemName, category, qty}`，`clinical_summary` 全靠医生手敲，并非自动带入。也就是说：子要求 1（病情摘要）真正"自动获取"的实现路径，走的是 `outp_emr.chief_complaint/present_illness` 到打印页 `briefHistory` 这条线，跟 `clinical_summary` 列（名字上更像"摘要"的那一列）无关——命名与注释容易让人误判两者是同一实现，需要在复核材料里讲清楚，避免复核者顺着列注释去找一个根本不存在的"自动带入 clinical_summary"逻辑。

## 审计者总判定

**部分符合**——不建议将 1016★ 整体上调为"符合/正偏离"。

5 个子要求中：
- 子要求③④（标本类型、采样部位）：**符合**，`7b64dd4` 已把可用输入框接入开单界面并落表打印，可以由"尚未提供"改判为"已提供"。
- 子要求①②⑤（病情摘要、诊断、执行科室的自动获取）：**部分符合**——数据确实是自动取的、确实零手动录入，但只在**提交之后的打印预览页**才可见，医生在"检查检验"页签实际填写检验申请的那一步看不到系统已经带出了什么；子要求⑤还额外存在"固定字典外键"与参数原文"根据流向"之间的语义落差。

若仍要整体上调为"符合"，反驳者最可能打回的三点：
1. **"自动获取"发生在打印页而非申请单开具界面**：参数原文是"检验申请自动获取……"，字面指向开具检验申请这个动作本身；而当前实现是医生提交时看不到任何摘要/诊断回显，只有点了"打印单据→检验申请单"之后才在另一张预览页看到，等于"申请单这份文件最终带了"而不是"开申请单这个操作带出了"，两者能否等价存在争议。
2. **"根据流向"被字典外键偷换**：`exec_dept_id` 是收费项目主数据里逐项目手工配置的固定部门，不随患者情况、标本条件、当日排班等因素变化，不构成"流向"意义上的动态路由；`MasterDataController.java:240` 的注释是开发者自我认定，不构成第三方视角下"流向自动获取"的证据。
3. **`clinical_summary` 列注释制造的混淆**：`V137__outp_order_fields.sql:50` 把该列同时记给 1014/1016，且写"可由病历自动带入"，但代码里既没有对 LAB 行开放该字段，也没有任何自动带入逻辑（纯手敲）——如果复核方按列注释去验收，会验出一个不存在的功能点，需要在提交复核材料时主动澄清，防止对方先入为主后又反过来因"发现注释造假"而牵连打回已经站得住的③④两项。
