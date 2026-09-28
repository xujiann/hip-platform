# 反驳者二（演示可行性）

> 复核方式：不跑浏览器，逐条按审计材料给的「菜单→页面→按钮→接口→版式」链路读源码推演；对工作区未提交改动全部用 `git diff` 单独核验，不采信审计材料的转述。

## 逐路径推演表

| # | 演示路径（审计者给的步骤） | 预期看到什么 | 代码实际（file:line） | 通/不通 |
|---|---|---|---|---|
| 前置 | 登录（DOCTOR_OUTP）→「门诊业务」→「门诊医生站」 | 菜单可见、路由可进 | 菜单 `V5__doctor_charge_dispense.sql:64`（id=10）+ 角色绑定 `V5__doctor_charge_dispense.sql:74`（`DOCTOR_OUTP` 授权 menu 7,10）；路由 `router/index.ts:70`（`outpatient/doctor`）；路由守卫按 `auth.user.menus` 命中放行（`router/index.ts:163-170`） | **通**——审计者说 DOCTOR_OUTP 能看到该菜单，核实成立 |
| 1 | 已开医嘱 →「打印单据▾」→「处方笺」 | 新标签处方笺版式 | 按钮 `DoctorStationView.vue:363-367`；接口 `PrintReportController.java:262-269`（`prescription`→`DOC_ORDER_TYPE.DRUG`）；版式 `PrintView.vue:171-197`；测试 `V43PrintDocsTest.java:116-147` | **通**（HEAD 上即可，不依赖工作区未提交改动） |
| 2a | 同上 →「检验申请单」 | 检验申请单版式（含标本条码/未采样提示） | `DoctorStationView.vue:368`；`PrintReportController.java:176-194`（`o.remark, o.urgent, o.clinical_summary...` 这些 v44 列 SQL 里确实在选，但 HEAD 版打印页不消费，见下方 #15）；`PrintView.vue:199-219` | **通**（字段完整度另计，见 #15） |
| 2b | 「已开医嘱」检验行「报告」按钮 / LIS「录结果并发布」自动开报告页 | 检验报告单打印页 | `DoctorStationView.vue:402-404,1006-1009` 与 `LisView.vue:120-132` 均在 **`git diff` 里核实为未提交**（见下方核验记录）；HEAD 上两处入口确实不存在 | **HEAD 上不通**，工作区（当前未提交代码）**通** |
| 3a | 「检查申请单」 | 检查申请单版式（手填栏「检查部位」「检查目的」） | `PrintReportController.java:138`；`PrintView.vue:222-247`；测试 `V43PrintDocsTest.java:169-183` | **通** |
| 3b | RIS 页找检查报告打印按钮 | — | `RisView.vue` 全文 grep「print/打印」**HEAD 与工作区均为零命中**（工作区改动是新增「影像浏览器」PACS 跳转按钮，与打印无关，`git diff` 已核实）；`PrintView.vue` type 全集无 `exam-report` | **不通**，且**与是否提交无关**——HEAD、工作区都没有 |
| 4 | 「治疗单」 | 治疗单版式 | `DoctorStationView.vue:370`；`PrintReportController.java:139`；`PrintView.vue:250-273`；测试同上 `:185-197` | **通** |
| 5 | 「导诊单」 | 导诊单，含待办列表 | `DoctorStationView.vue:371`；`PrintReportController.java:250-258`；`PrintView.vue:276-305`；测试 `:199-216,239-240` | **通** |
| 6 | 打印入口可达性（医生站） | 「打印单据▾」在「已开医嘱」页签工具条 | `DoctorStationView.vue:358-375`，`v-if="current"`（`:47`）套住整个工作区卡片，`printDoc` 内 `if (!current.value) return`（`:1013`）为双保险，理论上不可达但按钮本就在已选患者的卡片内 | **通** |
| 7 | 收费台缴费前/后两处入口 | 「打印单据▾」下拉五项，缴费前也能出 | `ChargeView.vue:28-40`（缴费前，无状态限制）、`:75-92`（结算后） | **通**，且核实**开单后立即可打，不需先收费**（`clinicalDoc` 的 SQL 只按 `registration_id + order_type` 过滤，无收费状态过滤） |
| 9 | 重打/补打 | 五单可重打；无留痕/检索 | `PrintReportController.java:91-108`（白名单只 `CHARGE/REGISTRATION`，决策注释在 `:91-99`）；对照 `ChargeView.vue:99-121`「票据补打」卡确有留痕 | **通**（功能层面可重打），审计者标注的「无留痕」缺口属实 |
| 10 | `groupNo` 单张打印 | 手敲 URL 带 `groupNo` 可单张；前端两处入口未接 | `PrintReportController.java:262-265`（`groupNo` 参数存在）；`DoctorStationView.vue:1014`、`ChargeView.vue:173` 的 `printDoc` 均未拼 `groupNo` | **接口通、UI 不通**——审计者标「部分符合」属实 |
| 15 | 单据内容完整（v44 七字段上纸） | 检查申请单「检查目的/临床要求」应有值印值 | HEAD：`git show HEAD:frontend/shell/src/views/PrintView.vue \| grep clinical_summary/notice/exam_purpose/specimen_type/sampling_site/urgent` **零命中**——**HEAD 上确实不通**，CHANGELOG `:1127`「有值印值」在 HEAD 上失实；工作区 `PrintView.vue` 的未提交改动（`firstOf(g,'clinical_summary')` 等，`git diff` 已核）已经把这七个字段接上，**工作区可通** | HEAD **不通**，工作区**通** |
| 17 | 无数据时的行为 | 未开药点「处方笺」→ 红字 toast + 4893 | `PrintReportController.java:227-231,268-270`；错误码登记 `docs/错误码分段.md:43`（4892/4893 二码确认存在，语义与材料描述一致） | **通** |
| 19 | 自动化测试覆盖 | 控制器级 8 用例 | `server/src/test/java/cn/hip/server/V43PrintDocsTest.java` 存在，`@Test` 用例与 4892/4893/groupNo 断言均核实存在（`:116,139,145,149,169,185,199,217-221,242-244,250-259`） | **通**（非 E2E，材料如实标注） |

## 数据前置条件核验

- `tools/bootstrap-demo.py` 只建基础患者/演示账号/体检套餐，**不预建任何挂号+医嘱数据**（`grep` 全文无 `/outpatient/`、`prescription`、`registration` 相关调用）。
- 但核心五单（#1/2a/3a/4/5）的演示链路很短：挂号（`RegisterView`）→ 医生站接诊→开药或提申请→提交→立即可打印，**且打印不要求先收费**（见路径 7 的核验）。整条链路走一遍在两三分钟量级，**不构成「评委现场要先造 20 分钟数据」的打回点**。
- 若要走 2b（检验报告单）的完整链路，需额外经过收费→LIS 标本流转→采样→核收→录结果发布，跨三个角色，耗时明显更长——但这只在「检验单=报告单」这一解读下才需要，不影响审计者采用的「检验单=申请单」解读。

## 对审计材料本身的核验发现（新增，非审计者已列的三点）

审计材料开头声明基线「**含两处未提交改动**：`LisView.vue`、`DoctorStationView.vue`」。实际 `git status` 核验：

```
M frontend/shell/src/views/PrintView.vue
M frontend/shell/src/views/medtech/LisView.vue
M frontend/shell/src/views/medtech/RisView.vue
M frontend/shell/src/views/outpatient/DoctorStationView.vue
M frontend/shell/src/views/outpatient/RegisterView.vue
```

**是四个业务文件未提交，不是两个**（另有一份 CSV 修改与本条无关）。其中：
- `PrintView.vue` 的未提交改动**直接是材料自己第 15 行判定「不符合（CHANGELOG 失实）」所指技术债的修复代码**——但材料没有把它列进「工作区未提交」清单，容易让复核人误以为第 15 行的评估和当前工作区改动无关（实际上工作区已经把这个技术债修掉了，只是还没提交）。
- `RegisterView.vue` 的未提交改动（挂号列表加「凭条」按钮）与本条 1026 无关（1026 谈的是处方/检验/检查/治疗/导诊五单，不含挂号凭条），材料没提它，这点不算漏项。
- `RisView.vue` 的未提交改动是新增「影像浏览器」PACS 跳转按钮，**与打印无关**，不影响材料第 3b 行「RIS 无打印按钮」的结论（我已用 `git diff` 确认该文件改动确实不含任何打印相关代码）。

这个「两处」写成「四处」的疏漏不改变任何一条子判定的对错（我逐条核验后，材料所有 file:line 引用与实际代码一致，包括故意标注为工作区未提交的 2b），但基线自证的完整性有瑕疵，上调说明如果要引用这份材料的基线声明，应先更正为准确的文件清单。

## 结论：不推翻，但须标注保留项

**理由**：参数字面「处方、检验单、检查单、治疗单、导诊单打印」在审计者采用的解读（检验单/检查单＝医生站开出去的申请单，与 1022/1025/1027/1029 同章节的门诊医生站语境一致）下，五条演示路径（处方笺、检验申请单、检查申请单、治疗单、导诊单）**全部在 HEAD（当前 tag v1.6.5 对应的已提交代码）上可走通**，不依赖任何工作区未提交改动：菜单可达、按钮存在、接口返回、版式渲染、控制器级测试覆盖，逐条 file:line 核对无出入；且打印不需要先收费，前置数据搭建成本很低，不构成"评委现场要先造 20 分钟数据"的打回点。故从演示可行性这一镜头看，不构成推翻上调的理由。

保留项（应写入上调说明，供负责人取舍）：
1. **若把「检验单/检查单」读作报告单**：检验报告单的两处前端入口（医生站「报告」按钮、LIS 发布后自动开打印页）**只存在于工作区未提交代码，HEAD 上确认不存在**；检查报告单（RIS）在 HEAD 与工作区都**没有任何打印入口**，是硬缺。这两点审计材料已如实标注（#2b、#3b），我逐字核验属实，不是新发现，但既然要写进上调说明，就必须继续保留这句免责声明，不能因为"总判定符合"而在对外文案里连带说成"检验/检查报告单也能打印"。
2. **v44 七字段上纸（第15行）**：HEAD 上确认打印页零消费这七个字段，CHANGELOG「有值印值」在 HEAD 上失实；工作区已有修复但未提交。上调说明若要引用"字段已上纸"，必须等这处改动实际提交，否则对外文案会立刻被下一轮复核抓到「说明与 HEAD 不符」。
3. **审计材料基线自述的「两处未提交改动」实际是四处**（多了 `PrintView.vue`、`RisView.vue`），其中 `PrintView.vue` 恰是第15行技术债的修复文件。建议上调说明引用基线时改为准确列出全部相关未提交文件，避免复核链路上出现"材料自己都没数清工作区改了什么"的可打回点。
