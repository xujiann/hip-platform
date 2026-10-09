# 反驳者二（演示可行性）· 乙组 988★ / 1101★ / 2457★ / 1082★ / 2458★ 上调复核

> 镜头：评委以 `docs/培训脚本.md` 的「⚠ 新 v1.7.6」各处（:61-64 门诊、:96 住院）与五行现说明（审计材料 §一逐字，CSV 自 a5872fc 未改）为脚本，从登录后菜单出发逐步点，哪一步走不到、哪一步看到的与说的相反。
> HEAD `2dd4d86`（审计者点名的 D1/D2(N10)/D3/D4/D5/D6/D8 均有修补提交，见 `git log a5872fc..HEAD`）。运行实例 `/actuator/info` build `2026-10-09T04:18:51Z`（12:18 +08，晚于 HEAD 提交 12:16），即本组 HEAD 代码。
> 手段：读 .vue/.java/.ts 推演 + 对本机 8080 做 HTTP 实测（admin / doctor01 / doctor02 / cashier01 / quality01 五账号）+ 开发库 `hip` 直查 select。**未起前端、未开浏览器、未跑 mvn**；尝试用 vite-node 跑 `default-template.ts` 被 Vite 的 fs 白名单拒（脚本在仓库外），改为读源码推演，`git status` 未变。
> 写操作仅协议允许两类（§清理）：建患者「反驳B2门诊甲」挂内科号 → doctor01 接诊 → 暂存两次 → 签名；admin 给「演示住院患者」建「反驳B2病程记录」→ PUT 修改 → 签名。另有三次**被拒的**写尝试（doctor01 挂号 403、签名后 PUT 门诊病历 4008、签名后 PUT 住院记录 9103、未签名补正 9108），均无状态变化。**未改系统配置、未动默认模板**（"如何切档"只推演）。
> 脚本与完整 HTTP 记录：scratchpad `rbB2/`（`h.py` 调用封装、`p1`–`p9*.py` 探针、`http_log.txt` 186 行逐请求记录、`ids.txt` 产物 id）。

## 演示库现状（推演起点，库态实查）

| 项 | 库态 / 实测 | 对演示的含义 |
|---|---|---|
| 菜单 | `/auth/me` 实测：doctor01 有 门诊医生站 `/outpatient/doctor`、患者建档 `/patient/registry`、患者360 `/cdr/patient360`、病历模板 `/emr-template`、病历版本留痕 `/emr-version`、专科流程 `/specialty`；**无** 门诊挂号 `/outpatient/register`、急诊预检分诊 `/outpatient/triage`、住院医生站 `/inpatient/doctor`。cashier01 有门诊挂号，**无患者建档**。admin 全有。`sys_role_menu`：挂号 = ADMIN/CASHIER；分诊 = ADMIN/NURSE；住院医生站 = 仅 ADMIN；患者建档 = ADMIN/DOCTOR_OUTP/NURSE | 988 另挂新号要换 cashier01 或 admin；新患者建档要 doctor01/admin（cashier01 建不了档）；住院一节只能 admin |
| 科室默认模板 | #71「内科门诊默认病历模板」DEPT·内科门诊·OUTP·`is_default`·启用，owner=admin，创建于 12:22（HEAD bootstrap 今日已跑）。doctor01 `GET /emr-templates/default?deptId=1&recordType=OUTP` → #71；doctor02（外科）→ 4066；admin → #71 | 988 自动套用的前置已由 bootstrap 办好（D8 已修） |
| 今日 doctor01 队列 | 开发库：张三 ×2（#1062 doctor01 接诊、病历未签；#1063 admin 接诊的旧引用演示就诊——HEAD bootstrap 已改挂前一天，**但开发库保留了上午那条**）、审计A/其他反驳者的测试患者、本人「反驳B2门诊甲」。全新库 + HEAD bootstrap：只有张三一行 | 开发库演示前须认准行；全新库要演跨患者粘贴，必须先有第二个患者（见 1082） |
| 张三病历 | `outp_emr` #99 未签名（bootstrap 只暂存不签） | 可直接在编辑区选中复制——1082 的复制源现成 |
| 跨患者粘贴档位 | `sys_config emr.copy.cross_patient = warn`；copy-policy doctor01/admin 皆 `warn`；全前端零调用 `PUT /config/*`，`sys_menu` 无任何参数/配置菜单 | 切档只能接口调用 |
| 急诊 | `sys_dept` 1–14 无急诊临床科室（10「急诊检验室」MEDTECH）；`er_observation` 36 行全 OUT、0 行 IN；`outp_triage` 72 行；bootstrap 不造分诊/留观 | 1101 急诊部分见下 |
| 住院 | 「演示住院患者」admission 626 在院，病历记录 0 条（本轮写前） | admin 可直接写第一条 |

## 逐条逐路径推演表

### 988★（门诊：自动套科室默认模板 / 自选任一模板 / 按既往病历新建）

| # | 路径 / 步骤 | 预期（说明 / 培训脚本 :62） | 代码实际（file:line / HTTP 实测） | 通不通 |
|---|---|---|---|---|
| 1a | 「另挂一个新号」谁来挂 | 脚本只写"另挂一个新号（新患者或张三再挂一次内科）"，没写谁、在哪 | doctor01 无挂号菜单；实测 doctor01 `POST /outpatient/registrations` → **403 / 1005「无该功能权限」**（类级 `hasAnyRole('ADMIN','CASHIER')` `OutpRegistrationController.java:22`）。cashier01 → 门诊业务 → 门诊挂号：实测挂到 #534（doctor01 内科全天号）→ #1070 `REGISTERED` | 通但须标注（脚本须写"cashier01 或 admin → 门诊挂号"） |
| 1b | 「新患者」从哪来 | — | 挂号页只能检索已有患者（`RegisterView.vue:1-20`，无建档入口）；建档菜单 `/patient/registry` 只授 ADMIN/DOCTOR_OUTP/NURSE，**cashier01 没有**。即：doctor01（或 admin）患者服务 → 患者建档 → cashier01 门诊挂号 → doctor01 回医生站点「刷新」（`DoctorStationView.vue:6`）。admin 一个账号可走完前两步 | 通但须标注 |
| 1c | 「张三再挂一次内科」 | 可行 | 查重只拦同号源 `status='REGISTERED'`（`RegistrationService.java:73-75`；部分唯一索引 `uq_outp_reg_active … WHERE status='REGISTERED'` 库查）；张三今日那条已 `VISITED` → 可再挂（按代码与索引推演，协议不允许对张三写，未实挂）。代价：队列出现两个张三，且同一患者无法用来演 1082 跨患者 | 通（建议优先用新患者） |
| 1d | doctor01 打开新号 → 黄条 | 进页自动套用、黄条「已自动套用……可撤销」 | 实测 #1070 队列行 `deptId=1`、`emrWritten=false`；workspace `emr=null` → `eligibleForDefaultTemplate` 真（`DoctorStationView.vue:1215-1217` → `default-template.ts:37-45`）→ 取 #71 → `autoApplyDefaultTemplate`（`:849-867`）→ 黄条 `:128-134`。**点队列行即触发，不必先点「接诊」**（`openPatient` 不判挂号状态）。#71 正文按标签拆段（`emr-template.ts:82-116`）只有「既往史」「体格检查」两段有值，**主诉/现病史/处理意见三段仍空**——评委看到的是两段被填 | 通（须向评委说明只填两段，因模板如此） |
| 1e | 「撤销」 | 恢复为套用前 | `undoAutoTemplate` `:869-885` → `revertDefaultTemplate` 只回写模板写过的段（`default-template.ts:116-121`）；套用后改过则先确认；记入 `declinedAutoTpl`，同页再进不复套 | 通 |
| 1f | 张三那次（已写病历）不套 | 不动 | workspace `emr` 非空 → `hasEmr=true` → 不取模板（`default-template.ts:44`） | 通 |
| 1g | 设默认的入口与权限 | — | 数据中心 → 病历模板 → 行内「设为科室默认 / 取消默认」（`EmrTemplateView.vue:77-80`，`:disabled="!row.editable"`）。后端 `setDefault` 先 `requireEditable`（`EmrTemplateService.java:644-646`），DEPT 模板仅**创建者本人或 ADMIN**（`canEdit :249-256`）。实测 doctor01 `/visible` 里 #71 `editable=false` → 按钮置灰。doctor01 可另建「科室·内科门诊·门诊病历」模板 →「设为科室默认」→ 4067 冲突框「替换为默认」（`EmrTemplateView.vue:399-415`）→ replace=true 直接把 admin 的 #71 让位（`:656-659`，不要求对 #71 有维护权）。QUALITY 无科室，事实上设不了科室默认（审计 N3 属实） | 通但须标注（"谁能设"须写明；评委若用 doctor01 演"替换为默认"，演完须由 admin 把 #71 设回） |
| 1h | 跨科医生接诊 | — | doctor02（外科）`GET …/default?deptId=1` → 4066，前端 `__silentCodes` 静默（`:836-837`）→ 外科医生接内科号**什么都不发生、无提示** | 通但须标注（审计 N2） |
| 2 | 自行选择任一模板 | 「医生也可自行选择任一可见的门诊模板套用正文」 | 病历页签「结构化模板（按模板定义的元素录入）」下拉（`:136-143`，标签已按 `scopeName` 标「科室/全院/个人」）+「套用正文」（`:145`）。实测 doctor01 `/visible?type=EMR` 38 张（全新库约 #34 全院 + #71 内科）。占位符写"结构化模板"，评委不一定想到它也是"套整段正文"的入口；培训脚本 :24 已写「套用正文」 | 通 |
| 3 | 选择患者历史文书创建新病历 | 「可选择患者既往病历取正文插入当前病历」 | 「按既往病历新建」`:118` → 对话框 `:612-634` →「取正文」→ `insertText` 插入「插入到」所选一段（审计 N5）。实测 `/emr-templates/prior-records?patientId=1001254`（**新患者**）只回它自己本次刚暂存的 #105——新患者在暂存前列表为空「该患者暂无既往病历」；张三（patientId=2）回 50 条。**要演这一项须用有往次就诊的患者**（张三再挂一次即可）；另：列表会把本次就诊自己的病历也列进来（只按 patientId 取） | 通但须标注（演法要选对患者） |

### 1101★（门**急**诊：同上三项，急诊部分）

| # | 路径 / 步骤 | 预期 | 代码实际 | 通不通 |
|---|---|---|---|---|
| 1 | 评委问"急诊病历在哪写" | 1101 现说明：急诊落在「急诊留观的观察记录」；2454★ 说「急诊就诊在门诊医生站书写」 | 全库无急诊临床科室（`/system/depts` 实测 1–14），bootstrap 不建；分诊 `outp_triage` 与挂号无关联。按 2454 口径须 admin 现场：建急诊科（CLINICAL）→ 排班 → 建科室模板（门诊病历）→ 设默认 → cashier01 挂急诊号 → doctor01 接诊，五六步、全是实施期配置；**培训脚本全篇无一处急诊/留观**（grep「留观」0 命中） | **不通**（按现说明：急诊部分说明自认"不自动套用、不支持按既往病历取正文"，三缺二；按 2454 口径：演示库无急诊科室，培训脚本无路径） |
| 2 | 走现说明那条：留观「记观察」选模板 | 「急诊留观的观察记录可从可见模板中选择后整段插入」 | 入口：门诊业务 → 专科流程 →「急诊留观」页签（`SpecialtyView.vue:56-86`）。开发库 36 条留观全部 OUT → 无任何「记观察」按钮（`:76` `v-if="row.status === 'IN'"`）。须先「入留观」：表单要手填**「分诊ID」数字**（`:58`、`:351-354`）。而分诊 ID **在界面上任何地方都不显示**：预检分诊页队列无 ID 列（`TriageView.vue:37-60`），分诊成功只提示「分诊完成」（`:48`）；全前端 grep `triageId/triage_id/分诊ID` 只有 SpecialtyView 这三处。后端 `GET /outpatient/triage` 返回体带 `id`（实测 72 条，键含 `id`），只是页面不显示。全新库第一条分诊恰为 id=1，只能"猜"；开发库下一条是 73 | **不通**（复现见下「不通项」） |
| 3 | 若进得去：记观察对话框 | 选模板整段插入 | 对话框 `:219-236`，模板下拉 `v-if="erTemplates.length"`（`/visible?type=EMR` 剔 INP `:375-384`）；选中后**整段替换**观察情况（`:409-416`，改写过先确认「覆盖」）——"插入"实为替换（审计 N8） | 通但须标注（措辞） |

### 2457★（暂存与提交）

| # | 路径 / 步骤 | 预期（说明 / 脚本 :63、:96） | 代码实际 / HTTP 实测 | 通不通 |
|---|---|---|---|---|
| 1a | 门诊状态三档 | 「新病历（尚未暂存）」「暂存（未签名）」「已提交（签名人 · 时间）」 | `DoctorStationView.vue:99-103`：无 id → 灰「新病历（尚未暂存）」（自动套用后、暂存前仍是这一档，与黄条「点「暂存」后生效」一致）；实测暂存后 `emr.id=105`、`signature=null` → 橙「暂存（未签名）」，队列「未签」；签名后 → 绿「已提交（已签名 · 演示门诊医生 · 时间）」（`emrStatusText :908-909`）+ 页首绿条签名人/时间 | 通（队列小标签仍是「已签/未签」，措辞不统一，不计） |
| 1b | 改完不暂存直接「提交（签名）」 | 签的应是屏上内容（D1 已修） | `signEmr :1442-1470`：确认框写明「将先暂存屏上当前内容，再以此签名」（`:1450`），确认后 `if (!(await saveEmr())) return`（`:1457`）→ 再 `POST /emr/sign`。HTTP 按同一顺序复现：暂存 v1 → 改「处理意见」后暂存 v2 → 签名 → workspace `advice` = v2 那句「…三天后复诊（提交前最后改的一句）」；版本 v1 MANUAL / v2 MANUAL / v3 SUBMIT | 通 |
| 1c | 提交后编辑区冻结、补正入口 | 冻结；补正 | 签名后 `openPatient` 重载 → `emrSigned` → 五段 `:disabled`（`:158-161`、`:250`）、「暂存」置灰（`:252`）、「提交（签名）」消失（`:254 v-if`）、补正区出现（`:267-287`，补正内容/原因/提交补正 + 补正时间线）。实测签名后 `PUT /emr` → **4008「病历已签名冻结，不可修改」**。补正端点按协议未实写，读码 `DoctorStationService.java:1240-1260` | 通 |
| 2a | 住院（admin）写一条「暂存记录」 | 时间线「暂存（未签名）」 | 实测 `POST /records` → #83，`signature=null`，版本 v1 MANUAL；时间线 `InpDoctorView.vue:203-217`：「暂存（未签名）」+「修改」（`:206`，`canEditInpRecord`=未签名）+「提交（签名）」+「版本留痕」 | 通 |
| 2b | 「修改」→「保存修改」→ 版本 +1 | 可续写（N10 已修） | `startEdit` 回填编辑区（编辑区已有未存内容先确认 `:790`），按钮换成「保存修改 / 取消修改」+ 提示「保存后仍为暂存（未签名），并多一版留痕」（`:191-195`）；记录类型下拉锁定。实测 `PUT /records/83` → 0，正文更新、`signature` 仍空，版本 v2 MANUAL。未签名点补正 → **9108「病历尚未签名，无需补正：请在病历时间线点该记录的「修改」直接修改正文」**（文案已指向真实入口） | 通 |
| 2c | 签名后「修改」消失、走补正 | — | 实测签名 → v3 SUBMIT；再 `PUT` → **9103「病历已签名（已提交），原文冻结不可直接修改；如需更正请点「补正」追加补正记录」**；时间线「修改」随 `!r.signature` 消失，「补正」出现（`:211-212`）；正在修改的那条直接点签名会被拦「请先保存修改或取消修改」（`signRecord :859-863`） | 通 |

### 1082★ / 2458★（参数控制跨患者复制粘贴）

| # | 路径 / 步骤 | 预期（说明 / 脚本 :96） | 代码实际 / HTTP 实测 | 通不通 |
|---|---|---|---|---|
| 1a | 门诊档位标签常驻 | 「编辑区显示当前管控档位」 | `DoctorStationView.vue:120-123` 已去掉 `v-if`，三档「禁止/需确认/放行」都显示（D5 门诊侧已修）；档位进页最先加载并单独兜底（`:1638`，D3 已修） | 通 |
| 1b | **住院档位标签** | 脚本 :96「编辑区上方显示当前档位」 | `InpDoctorView.vue:180-183` **仍是 `v-if="copyMode !== 'off'"`，文案仍只有「禁止/需确认」两档**——修补提交 ad4b0f1 自述「D5：放行档也显示「放行」」，但只改了 DoctorStationView（`git show ad4b0f1 --stat` 只含 DoctorStationView.vue 与 SysConfigController.java）。审计 D5 点名的两处只修了一处 | 通但须标注（切到 off 后评委在住院看不到档位；一行修复：照抄门诊 :120-123） |
| 2 | 怎么切档 | 「参数由系统管理员通过系统配置接口设置，当前无独立参数维护界面」 | 无菜单、无页面：`sys_menu` 无配置类菜单，全前端零调用 `PUT /config/*`（只有 `/config/public` 读）。唯一途径：先 `POST /api/auth/login`（admin/admin123）取 `data.token`，再 `PUT /api/config/emr.copy.cross_patient?value=block`（`SysConfigController.java` `@PutMapping("/{key}") @PreAuthorize("hasRole('ADMIN')")`；HEAD 起只认 off/warn/block，非法值 1402，D6 已修）。改后后端即时生效（`configReader.evict`），但**医生站只在进页读一次档位** → 必须刷新页面（审计 N17）。按协议未实切 | 通但须标注（说明已如实；培训脚本须给出两条命令与"刷新页面"） |
| 3a | 从患者 A 编辑区复制 → 到患者 B 编辑区粘贴（warn） | 弹确认 | 复制：A=张三（#1062 未签名，五段可编辑）→ 选中现病史 → Ctrl+C → 容器 `@copy`（`:156`）→ `onCopy` 走 textarea 的 `selectionStart/End` 取文（`EmrRefDrawer.vue:117-123`）→ 记 `{patientId:2, 张三}` 进 `localStorage hip_emr_copy_src`。点队列里另一患者（如本轮的「反驳B2门诊甲」）→ 粘贴 → `onPaste`：内容命中、患者不同 → `preventDefault` → 确认框「这段内容来自患者「张三」，不是当前患者「…」的病历…确认粘贴？」（`:186-210`）→ 确认后代写入光标处 | 通 |
| 3b | 同上（block） | 拒绝 | `ElMessage.error('这段内容来自患者「张三」，按院内参数设置（emr.copy.cross_patient=block）禁止跨患者粘贴病历内容')`（`:180-183`），不写入 | 通（须先按 #2 切档并刷新） |
| 3c | 门诊复制 → 住院粘贴（admin 单账号） | 两处均生效 | 来源存 `localStorage`，同浏览器同源共享；住院 `patient()` 取在院患者主索引（`InpDoctorView.vue:838-841`）；实测住院版本列表 `patientId=1001236` ≠ 张三 2 → 判为跨患者。**必须同一浏览器**（评委若 doctor01、admin 开两个浏览器，来源互不可见 → 视为外部来源放行） | 通但须标注 |
| 4a | 从患者360复制（HEAD 起登记来源） | 识别 | `Patient360View.vue:73` `<pre @copy>`，来源取所查看文档自身的 `patientId`（`emr-copy-source.ts:24-32`）；实测 `/cdr/documents/41607` 带 `patientId=2`。**但**：360 文档是 CDR 快照，夜间 02:10 定时同步（`CdrSyncScheduler.java:19`），库内最近一次 `synced_at` 2026-10-01；今天写的病历要 admin 点页首「立即同步」才进 360（`/cdr/sync` 仅 ADMIN，doctor01 点会 403 而按钮对其照常显示）；门诊快照里只含主诉/现病史/处理意见三段、以 JSON 文本展示 | 通但须标注（演前 admin 先同步；只能复制到 JSON 片段） |
| 4b | 从病历版本页复制（HEAD 起登记来源） | 识别 | `EmrVersionView.vue:177/239/248` 三处容器挂 `@copy`，来源取版本列表随体带回的 `patientId/patientName`；实测 `/emr/versions/OUTP/105` → `1001254 反驳B2门诊甲`、`/emr/versions/INP/83` → `1001236 演示住院患者`。入口：医生站「版本留痕」新标签打开（同浏览器） | 通 |
| 4c | **从医生站「历史就诊」抽屉 /「引用资料」抽屉复制** | 说明：「只能识别在本系统内从其他患者病历复制的片段」 | 两处都**不在**粘贴管控容器内：容器 `:156-264` 只包表单；「历史就诊」抽屉在 `:490-505`（显示历次主诉、处理意见），「引用资料」抽屉组件在 `:636`（「历史病历」页签以 `<p class="ref-text">` 显示往次病历全文，`EmrRefDrawer.vue:64`）。全前端 `@copy` 仅 4 个文件（DoctorStationView / InpDoctorView / Patient360View / EmrVersionView，grep）。Element Plus 2.14.3 的 el-drawer 默认**不 teleport**（`appendTo="body"` 且 `appendToBody=false` → `Teleport disabled`，`drawer.vue_vue_type_script_setup_true_lang.mjs:48-50`），但其 DOM 位置在容器之外，copy 事件冒不到 `:156` → 来源不登记 → 粘到另一患者时按"外部来源"放行，**block 档也放行** | 通但须标注（N13 同类残留，见下「不通项」第 2 条与保留项 R3） |
| 5 | 审计 N14：门诊**已签名**病历五段能否选中复制 | — | 见下专节：推演为**多半能**（Chromium 系），且即便不能也已不构成缺口 | 不影响裁定 |

## 审计 N14 专节：已签名门诊病历五段 disabled 能否选中复制

依据（读源码，未开浏览器）：
1. **Element Plus 怎么禁用**：`el-input` 把 `disabled` 作为原生属性直接落到 `<input>` / `<textarea>` 上（`node_modules/element-plus/es/components/input/src/input.vue_vue_type_script_setup_true_lang.mjs:409`（input）、`:505`（textarea），版本 2.14.3）；样式 `theme-chalk/el-input.css` 对 `.is-disabled` 的 `__inner` 只设颜色、`-webkit-text-fill-color` 与 `cursor:not-allowed`，**没有 `user-select:none`，也没有 `pointer-events:none`**（`pointer-events:none` 只作用于前后缀图标容器）。即：能不能选中完全取决于浏览器对原生 disabled 文本控件的默认行为。
2. **页面写法**：主诉是 `el-input`（单行 input），其余四段是 `type="textarea"`，都只 `:disabled="emrSigned"`（`DoctorStationView.vue:158-161`、`:250`），**不是 `readonly`**；整块处于粘贴管控容器 `:156` 之内。
3. **浏览器行为（推演，按引擎分）**：Chromium 系（Chrome/Edge，演示最常用）对 disabled 的文本控件只屏蔽鼠标点击类事件与聚焦，**允许拖选其中文字并经右键「复制」/Ctrl+C 复制**；Firefox 长期不允许在 disabled 文本框内选字（老缺陷，新版本是否已放开我不能确认）。这一条是我对引擎行为的认知，**未经本机浏览器实测，可信度中等，须在浏览器里点一次核实**。
4. **若能复制，来源能否登记**：clipboard 事件的目标是选区所在的那个文本控件（shadow 内的编辑区重定向到宿主 textarea），冒泡到 `:156` 容器 → `onCopy` → `selectedTextOf` 命中 `HTMLTextAreaElement`/`HTMLInputElement` 分支、按 `selectionStart/End` 取文（disabled 不影响这两个属性）→ 登记为当前患者。故 Chromium 下**已签名病历在编辑区内复制会被登记**，粘到别的患者照常 warn/block。
5. **为什么不影响裁定**：① 演示所需的复制源（张三的病历）本来就是未签名、可编辑的；② 即便某浏览器下选不中，HEAD 的 D4 已让「病历版本留痕」页（含已签名病历的单版全文与对比，实测随体带 patientId）登记来源，签名病历另有一条被识别的复制路径；审计所担心的"已签名门诊病历只能经不识别的页面复制"已不成立（但见 4c：医生站两个抽屉仍不识别）。

## 不通项（可复现）

1. **1101：急诊留观「记观察」选模板，从界面走不进去（分诊 ID 不可见）**
   - 复现：admin（或 nurse01）→ 门诊业务 → 急诊预检分诊 → 填姓名/分级/主诉 →「分 诊」→ 提示仅「分诊完成」（`TriageView.vue:48`），右侧队列各列为 级/姓名/主诉/体征/状态/操作，**无 ID 列**（`:37-60`）→ doctor01 → 门诊业务 → 专科流程 →「急诊留观」页签：开发库 36 条全 OUT，无「记观察」按钮（`SpecialtyView.vue:76`）→「入留观」要求手填「分诊ID」（`:58`）——界面上无处可查该数字；填错 4560「分诊记录不存在」（`ErObservationController.java:33-35`）。
   - 只有知道 `GET /api/outpatient/triage` 返回体里的 `id`（实测带 `id`）或在全新库里猜 `1`，才能进到 1101 现说明所称的模板对话框。
   - 叠加：按现说明急诊部分本就三缺二（说明自认不自动套用、不支持既往病历），按 2454 口径则演示库无急诊科室、培训脚本无一字。→ **1101 上调推翻**。
2. **1082/2458：医生站两个抽屉里复制的他人病历不识别，block 档放行**（N13 同类残留，量级不及 1101，归"须标注"，复现供主控权衡）
   - 复现：admin 先按「如何切档」切到 block → doctor01 刷新医生站 → 点张三 →「历史就诊」（或「引用资料」→「历史病历」页签）→ 选中一段往次主诉/正文 → Ctrl+C → 点另一患者 → 粘贴进现病史 → **无提示直接写入**（来源未登记，`onPaste` 在 `!src || !sameContent` 处放行，`EmrRefDrawer.vue:172-174`）。对照：同一段文字从张三编辑区复制 → 粘到另一患者 → 拒绝。
   - 修法（两行级，与 D4 同款）：给两个抽屉的正文区包一层 `<div @copy="onCopy" @cut="onCopy">`（来源即当前患者，DoctorStationView 的 `onCopy` 已按 `current` 取患者）；或把说明句窄化。

## 演示前置清单（bootstrap / 培训脚本该补什么）

| 条 | bootstrap 已造 | 培训脚本 / bootstrap 该补 |
|---|---|---|
| 988 自动套用 | 内科门诊默认模板 #71（D8 已修） | 脚本 :62 补：**谁挂号**——cashier01（或 admin）→ 门诊业务 → 门诊挂号，doctor01 无挂号菜单；**新患者先建档**——doctor01/admin → 患者服务 → 患者建档（cashier01 无建档菜单）；医生站点「刷新」看到新行（待诊、病历「—」）；**点行即套**，不必先接诊；#71 只填「既往史」「体格检查」两段。建议脚本优先"新患者"（张三再挂会出两个张三，且演不了 1082） |
| 988 设默认 | — | 脚本补入口：数据中心 → 病历模板 → 行内「设为科室默认」；能设的人 = 系统管理员或该科室模板创建者，doctor01 对 #71 按钮置灰；若演 doctor01 自建模板「替换为默认」，演完 admin 把 #71 设回 |
| 988 按既往病历新建 | 张三有往次病历（今日 + 前一天） | 脚本补：要用**有往次就诊的患者**（如张三再挂一次内科），新患者列表为空 |
| 1101 急诊 | 无（无急诊科、无分诊、无在观留观） | 若坚持留观演法：分诊页加 ID 列或留观页改下拉选分诊（代码），bootstrap 造一条分诊 + 一条 IN 留观，脚本补「专科流程 → 急诊留观 → 记观察 → 选模板」；若改 2454 口径：bootstrap 建急诊科（CLINICAL）+ 排班 + 科室模板（门诊病历）+ 默认，脚本补急诊挂号演法 |
| 2457 门诊 | 无需 | 脚本 :63 补一句「提交会先暂存屏上当前内容再签名」（D1 修后的新行为，确认框已写） |
| 2457 住院 | 演示住院患者在院 | 脚本 :96 补：「暂存记录」→ 时间线「修改」→「保存修改」（版本 +1，仍为暂存）→「提交（签名）」→「修改」消失、「补正」出现。**注意**：审计 §八为 2457 拟的限定句 2「住院病历…暂存后不可续写」在 HEAD 已不实，**不得写入说明** |
| 1082/2458 切档 | 档位 warn | 脚本补两条命令：`curl -s -X POST http://<主机>/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"<口令>\"}"` 取 `data.token`；`curl -s -X PUT "http://<主机>/api/config/emr.copy.cross_patient?value=block" -H "Authorization: Bearer <token>"`（off/warn/block）；**改完刷新医生站页面**；演完改回 `warn` |
| 1082/2458 演法 | 全新库队列只有张三 | 脚本门诊节补粘贴管控演法（现只在住院节 :96 提及）：先按 988 挂一个新患者作 B；张三编辑区复制 → B 粘贴；或 admin 单账号「门诊张三 → 住院演示住院患者」；**同一浏览器内操作**；从 360 复制须先由 admin 点「立即同步」 |

## 保留项（须标注，均不构成参数字面走不通）

- **R1（988 演示路径口径）**：另挂新号须 cashier01/admin，新患者建档须 doctor01/admin；自动套用按挂号科室取默认、按接诊医生判可见，跨科医生接诊静默不套；只有 DEPT + 门诊病历 的模板能当默认，设默认 = 系统管理员或该模板创建者（另一创建者可用「替换为默认」顶掉现默认）；"按既往病历新建"列表含本次就诊自身。
- **R2（2457 措辞）**：暂存与提交的屏上表现、提交先暂存再签、冻结、4008/9103、住院「修改」续写与版本 +1、签名后改走补正，全部实测成立。须标注的只有：队列小标签「已签/未签」与编辑区「已提交/暂存」不统一；「可回放」宜写「按时间线查看」（审计 N12）；急诊留观观察记录无暂存/提交之分（审计 N11）；`signEmr` 空正文提示仍写「请先书写并暂存后再提交」（D1 修后"提交会自动暂存"，此句略显多余，不误导）。
- **R3（1082/2458 识别范围）**：HEAD 补了患者360与版本页，但医生站「历史就诊」「引用资料」两个抽屉复制的他人病历仍不识别（不通项 2）。说明「只能识别在本系统内从其他患者病历复制的片段」仍偏宽：要么两行修补，要么窄化为「识别门诊/住院医生站病历编辑区、患者360视图与病历版本页中复制的片段；医生站历史就诊与引用资料抽屉中复制的内容，以及外部来源，识别不到」。
- **R4（1082/2458 住院放行档无标签）**：`InpDoctorView.vue:180-183` 仍 `v-if="copyMode !== 'off'"`，D5 只修了门诊，修补提交说明与代码不符。说明「编辑区显示当前管控档位」与培训脚本 :96 在住院 off 档不成立；一行修复。
- **R5（1082/2458 切档）**：无界面，须 curl + 刷新页面；复制来源存在本浏览器 localStorage，跨浏览器不识别；360 文档要先同步。说明已写"无独立参数维护界面"，培训脚本须给出命令。
- **R6（1101 措辞）**：留观对话框是整段**替换**不是插入（审计 N8）。

## 写操作清理确认（库态 select 核验）

| 写操作 | 产物 | 现状 / 能否清 |
|---|---|---|
| cashier01 `POST /patients` | `empi_patient` #1001254「反驳B2门诊甲」 | 保留（患者无删除端点） |
| cashier01 `POST /outpatient/registrations` | `outp_registration` #1070（#534 号源，现 `VISITED`）；附带挂号费订单 `outp_order` #1890 REG ¥10 `CREATED`（**未收费，会出现在收费台待收列表**） | 已接诊不可退号（`RegistrationService` 3005），按协议也不做退号；**提请主控知悉收费台多一笔 ¥10 待收** |
| doctor01 接诊 / 暂存×2 / 签名 | `outp_emr` #105（已签名）、诊断 1 行、`emr_version` #37/#38/#39（MANUAL/MANUAL/SUBMIT） | 保留（签名病历不可删，合规） |
| admin 住院 `POST records` / `PUT` / `sign` | `inp_medical_record` #83「反驳B2病程记录」（已签名），`emr_version` #40/#41/#42（MANUAL/MANUAL/SUBMIT），挂在**演示住院患者** admission 626 | 保留（无删除端点）。**影响演示**：演示住院患者时间线多一条已签名「反驳B2病程记录」，病历完整性预检的缺项数随之变化；演示前如需干净，须全新库重跑 bootstrap |
| 被拒写尝试 | doctor01 挂号 403/1005；签名后 PUT 门诊 4008；未签名住院补正 9108；签名后 PUT 住院 9103 | 无状态变化 |
| 系统配置 / 模板 | 未写 | `emr.copy.cross_patient` 仍 `warn`（`updated_at` 仍为审计者那次 11:34:34）；#71 仍为内科门诊默认、启用 |
| 仓库 | 只新建本文件 | 未 commit |

## 结论 + 一句理由

- **988★：不推翻但须标注**（R1）。三项在门诊医生站逐步走得到：bootstrap 已设好内科默认模板，cashier01 挂新号 → doctor01 点行即见黄条（两段被填）→ 撤销回空，张三那次不套；手选模板与按既往病历取正文都有入口。唯一的坑是培训脚本没写"谁挂号、新患者谁建档、既往病历要选有往次的患者"，属演示前置文案。
- **1101★：推翻（上调）**。按现说明急诊部分三项缺两项（说明自认）；仅剩的"留观记观察选模板"从菜单出发也进不去——入留观要手填一个界面上任何地方都不显示的分诊 ID，开发库无在观留观、bootstrap 不造；按 2454 口径则演示库无急诊科室、培训脚本无一字。评委问"急诊病历怎么写"，没有一条可点的路。
- **2457★：不推翻但须标注**（R2）。门诊三档状态、提交先暂存再签（D1 实测修复）、冻结与 4008、补正入口；住院暂存可「修改」续写、版本 +1、签名后「修改」消失改走补正（N10 实测修复，9103/9108 文案指向真实入口），全部实测成立。
- **1082★ / 2458★：不推翻但须标注**（R3、R4、R5）。参数三档在门诊、住院两处编辑区都能演出 warn 确认与 block 拒绝，患者360与版本页复制已登记来源；切档无界面须 curl + 刷新（说明已如实）。须标注且建议上调前顺手修掉的两处：住院放行档仍不显示档位（D5 只修一半）、医生站「历史就诊」「引用资料」抽屉复制不识别（N13 同类残留，block 档放行，可复现）——若主控对 N13 类缺口沿用审计"说明句为假即维持"的尺度，这一条应先修或先窄化说明再上调。
