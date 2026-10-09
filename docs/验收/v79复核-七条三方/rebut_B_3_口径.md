# 反驳者三（数据口径）· 乙组 988★ / 1101★ / 2457★ / 1082★ / 2458★

> 时点 2026-10-09，HEAD `2dd4d86`。审计材料是 `audit_B_988_2457_1082.md`，它基于 `a5872fc`。审计者点名的 D1/D2/D3/D4/D5/D6/D8 已经在 `ad4b0f1`、`6130c59`、`6770519`、`2dd4d86` 修掉，本文核的是**修后**的口径，重点看这几处修法有没有带进新的口径问题。仓库零改动，本文件是唯一新建的仓库文件，没有 commit。
> 手段：读源码；开发库 `hip` 直查（`wsl -d Ubuntu -u postgres -- psql -d hip`，只用 select 和 `\d`）；对本机 8080 做 HTTP 实测。`/actuator/info` 显示 build `2026-10-09T04:18:51Z`，即 12:18:51 +08，晚于 HEAD 提交时间 12:16:03，所以运行中的服务就是 HEAD 代码。用 `java GateParse.java` 单文件推演取值校验（逻辑逐字抄自源码）。没有起前端、没有开浏览器、没有跑 mvn 或 vitest。脚本和输出在 `scratchpad/rbB3/`：`probe.py`/`probe.out`、`probe2.py`/`probe2.out`、`GateParse.java`/`gateparse.out`、`ids.json`。
> 写操作只做了协议允许的两类，见文末。没有改任何系统配置。

## 逐链路核对表

| # | 链路 | 结果 | 证据 / 复现 |
|---|---|---|---|
| 1a | 988 取数 SQL | 与审计者一致：`where dept_id=? and record_type=upper(trim(?)) and is_default and enabled limit 1`，**不看 template_type**，取到后用 `canSee` 判可见，不可见返回 4066 | `EmrTemplateService.java:706-722`。写侧 `normRecordType`（`:961-965`）把 record_type 统一 trim 加大写，读写同口径 |
| 1b | 唯一性 | 同科室同 record_type 不可能有两张启用的默认：部分唯一索引 `uq_emr_tpl_default (dept_id, record_type) WHERE is_default AND enabled`（`\d emr_template` 实测）。record_type 写侧统一大写，不会出现 `OUTP` 与 `outp` 各占一格 | `setDefault :644-672` 先 4067 友好拦截，撞索引兜底 `defaultConflict` |
| 1c | 返回体键名与前端解析 | **对得上**。doctor01 和 admin 取 `deptId=1&recordType=OUTP` 以及 `' outp '` 都返回 #71，snake_case 12 键：`content, created_at, created_by, dept_id, enabled, id, is_default, name, owner_id, record_type, scope, template_type`。`pickDefaultTemplate` 读 `enabled / template_type / name / id / content`（`default-template.ts:51-65`），五个键都在；`content` 在返回体里，所以不会走「再按 id 取一次」那条分支 | `probe.out` [988] 行 |
| 1d | 可见性 | doctor02（外科）取内科默认返回 4066，前端 `__silentCodes` 静默按无默认处理（审计者 N2 成立）。admin 也可见 | `probe.out` |
| 1e | 新病历 / 正文为空 / 过敏史合并 / 撤销 | 判据与审计者 §A1 一致：新病历按 `!ws.emr`（实测新挂号 workspace.emr=null），正文为空按五段 trim 后全空，过敏史合并为「既有值 + 换行 + 模板值」，撤销恢复 `before`（`default-template.ts:37-45,82-110`）。D8：bootstrap 现在建 DEPT、内科、OUTP 模板并设为默认（`6130c59`），开发库已有 #71 `is_default=t enabled=t`；模板正文第 3 行是非空的「既往史：否认高血压…」，与过敏史预填会按「追加在其后」合并 | 库查 `emr_template id=71` |
| **1f** | **988 × 2457 修法交叉（新）** | **须标注**。默认模板自动套用后，医生一个字没动就点「提交（签名）」：前端 `emrHasContent()`（`DoctorStationView.vue:1433`）为真，因为模板骨架非空；随后 `saveEmr()` 把骨架落库，签名通过（4022 只拦五段全空）。修前这条路会被后端 4009「病历不存在，请先书写保存」拦住，因为从未暂存过。修后**一次点击就能签下一份纯模板骨架**，比如 #71 的「体格检查：T  ℃  P  次/分…」。页面其实知道医生有没有动过（`emrEquals(emr, autoTpl.after)`，`:1481` 离页提示正是用这个判断），提交路径却没用它 | 静态推演；HTTP 侧已实测「PUT 后 sign 必过」（下行 2b） |
| 2a | 门诊提交 = 先暂存再签（D1 修法） | **数据链路成立**：屏上内容 → PUT 落 `outp_emr` → sign 读库里同一行 → SUBMIT 版。实测「暂存 A → 同内容再暂存 A → 提交前自动暂存 B → sign」，结果版本 3 条 `(1 MANUAL A)(2 MANUAL B)(3 SUBMIT B)`，**同内容那次被 dedup 吞掉**；`outp_emr.chief_complaint` 等于 B，与 SUBMIT 版一致 | `probe.out` [2457]；库查 `emr_version emr_id=104` |
| 2b | 提交会不会多一版 | `emr.version.dedup=on`（库态）时，屏上与上次暂存相同则 MANUAL 不加版，只多一版 SUBMIT（`EmrVersionService.java:405` 中 `dedup() && !SUBMIT.equals(source)`，SUBMIT 永不去重）；屏上有改动则多一版 MANUAL 再加 SUBMIT。dedup 切 off 时每次提交固定多一版与上一版逐字相同的 MANUAL。诊断版本无论开关如何都去重（`DiagnosisVersionService.java:79-87`） | 实测 + 源码 |
| 2c | saveEmr 失败的路径 | 修后 `saveEmr` 返回 `false` 的只有 `!current.value` 一条。`resp.data.code !== 0 → return false`（`:1313-1316`）**实际是死代码**：拦截器对非 0 码一律 reject（`api/client.ts:98-106`），所以 4008、4000（列宽）、4024–4029（结构化）、4033/4034（诊断）以及网络错误全都以**抛异常**的方式让 `signEmr` 在 `await saveEmr()` 处中断，签名照样不发。结论「暂存失败就不签」成立，只是走的是异常路径（未捕获的 rejection，红字由拦截器弹出）。另有一个旁支：`saveEmr` 成功后还 `await loadCdssTips()`，它自带 try/catch，不会让「已暂存却不签」发生 | 源码 |
| 2d | 屏上与库里必然一致吗 | 不必然，但窗口从「上次暂存以来的全部改动」缩小到「两次请求的往返时间」。确认框之后到签名返回之前，五段输入框**不禁用**（`:disabled="emrSigned"`，`:250`），「暂存」按钮只有 loading 态。这段时间里敲进去的字不在已签版本里，签完 `openPatient` 重载时会被冲掉。「提交（签名）」的 loading 要等 `saveEmr` 结束才置位（`:1457-1458`），再点一次会再弹确认框 | 静态 |
| **2e** | **4023「仅书写医师本人可签名」被修法架空（新）** | **须标注 / 另开**。`saveEmr` 无条件 `emr.setDoctorId(当前用户)`（`DoctorStationService.java:134`），签名用 `emr.getDoctorId()` 比对签名人（`:1205-1206`）。修前：doctor02 打开 doctor01 未签的草稿，直接点提交，返回 4023。修后：提交先暂存，doctor_id 改写成 doctor02，再签就通过。屏上内容哪怕一字未改（MANUAL 被 dedup 吞掉、版本表不记 doctor02 存过），`outp_emr.doctor_id` 也已改成 doctor02，SUBMIT 版 saved_by 也是 doctor02。worklist 按日期列出全部挂号，不按接诊人过滤（`6130c59` 提交说明），所以这条路就在正常界面上。修前点「暂存」再点「提交」也能绕过，修法把绕过变成了默认路径 | 静态推演（协议不许 doctor02 写 doctor01 的号，未实测） |
| 3a | 住院 PUT：条件更新 / 两列同步 / MANUAL / 保存人 | 都成立。admin 对演示住院患者（adm 626）做：新建 #81，PUT 改正文 → v2 MANUAL；同内容 PUT → 被去重；`title='   '` 的 PUT → 标题不动（`coalesce`）→ v3；签名 → v4 SUBMIT；签名后 PUT → 9103。查房 #82：PUT 带 `superiorCorrection:''` → `content = round_opinion` 同步、上级意见清成 null；`saved_by=1` 是修改人，`doctor_id` 不动 | `probe.out` [2457-INP]；库查 `inp_medical_record 81/82`、`emr_version INP 81/82` |
| **3b** | **签名端点整实体 save 与 PUT 的竞态（实现者自述未修）** | **须标注**。`signRecord`（`InpEmrController.java:162-176`）不在事务里，`open-in-view: false`（`application.yml:23`）。`findById` 读到的实体在 save 时已脱管，`recordRepo.save(r)` 走 merge，会把整行状态（包括旧 content）写回。若 PUT 在签名的 `findById` 与 `save` 之间提交，结果是：PUT 返回成功并落了新正文的 MANUAL 版，签名随即把**旧正文写回**，签名摘要和 SUBMIT 版也都基于旧正文。PUT 的 `where signature is null` 只防反方向。窗口等于 `signatureAdapter.sign` 的耗时，概率低；版本表留得下「改了又被改回」的痕迹，链路没有断 | 静态 |
| 3c | PUT 的角色与归属 | 类级 `hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')`（`:19`）同样作用于 PUT。**实测 nurse01 对已签名的 #81 做 PUT 返回 9103**，即鉴权放行，只是被签名拦住。PUT 不比对书写人：护士或任一医生经接口都能改他人未签名的病程，`doctor_id` 仍是原书写人，`saved_by` 记的是改的人。界面只有 ADMIN 能进住院医生站，所以这只在接口层成立。POST 和 sign 早就如此，不是本轮引入；与 2410（正偏离「病历限定医生书写」）有张力，不在本组 | `probe2.out` |
| 3d | 结构化侧车 | PUT 保留建档时的 `content_json`、不反解正文（`:206` javadoc），而 1098 结构化检索读的正是 `inp_medical_record.content_json`（`EmrFieldController.java:358-365`）。改过结构化块的住院病程，检索会返回旧值。门诊无 fields 的暂存同口径（`DoctorStationService.java:135`），属已知口径，记录 | 静态 |
| 4a | 粘贴来源存在哪 | `localStorage['hip_emr_copy_src']`，单槽，后写覆盖前写，同源**跨标签页共享**（版本页是新标签打开的，照样能识别），**登出不清**（全前端只有 `removeItem('hip_token')`）。存的是 patientId、patientName 和最多 20000 字的原文。D4 之后来源页多了患者 360（整份 CDR JSON），浏览器里滞留的他人病历文本也更多。这是隐私面，不是 1082 的口径问题，记录 | `EmrRefDrawer.vue:76-104`；grep |
| 4b | 判定 | 只有 `src.patientId`、当前 patientId 都非空且不相等时才拦；任一侧为 null 就放行（`:176`）。内容比对先把空白归一，再看整段相等或互相包含（双方都至少 8 字）（`:109-115`） | 源码 |
| 4c | `loadPolicy` 读口 | `GET /outpatient/emr-ref/copy-policy`，只接受 `off/warn/block` 三个小写值（`:148`）。后端读 `emr.copy.cross_patient` 后 `trim().toLowerCase(Locale.ROOT)`，非法值回落 warn（`EmrRefController.java:137-147`）。实测 doctor01/admin/doctor02 返回 warn，nurse01 返回 1005（无权，前端停在默认 warn；护士没有门诊或住院医生站菜单，不影响） | `probe.out`、`probe2.out` |
| 4d | 患者 360 登记的 patientId 是谁的 | **正是被复制正文的所属患者**。`/cdr/documents/{id}` 整实体直出 `patientId`（实测 #41621 → 1001232），它来自 `cdr_document.patient_id`，由 `CdrSyncService` 从 `outp_registration.patient_id` / `inp_admission.patient_id` 写入（`:145,172,212`），与医生站使用的 EMPI 主索引是同一 id 空间。全文检索结果也是整实体（实测键相同），点开走同一个 `viewDoc`。姓名只在左侧选中的就是同一患者时才带出，否则提示「其他患者」，不会张冠李戴 | `probe2.out`；`Patient360View.vue:191-197` |
| 4e | 版本页登记的 patientId | **正是该份病历的所属患者**：门诊按 outp_emr→registration→empi，住院按 inp_medical_record→admission→empi（`EmrVersionService.java:617-633,737-739`）。实测门诊 #104 → 1001253「反驳B3门诊」，住院 #81 → 1001236「演示住院患者」，quality01 看到的相同；不存在的 emr → 两键都为 null，管控放行，不乱猜 | `probe.out`、`probe2.out` |
| **4f** | **本系统内仍未登记来源的他人病历页（审计者 N13 漏列）** | **须标注**。「病案复印」`/emr-copy`（菜单 90，ADMIN/QUALITY；`EmrCopyView.vue:112` 展示病历 content），以及打印页 `inp-discharge-summary`（`PrintView.vue:56-72`，逐条展示该次住院全部病历正文）都没有挂 `@copy`。全前端 `@copy` 只出现在 DoctorStation、InpDoctor、Patient360、EmrVersion 四个文件。**ADMIN 恰好是唯一能进住院医生站的角色**：从病案复印页复制 A 的病程，粘进演示住院患者的记录，block 档也放行。说明「只能识别在本系统内从其他患者病历复制的片段」仍比实际宽，只是缺口比审计时小 | grep；`sys_role_menu` 库查 |
| 5a | D6 校验与 copy 键读取端是否同口径 | **基本同口径**：校验用 `strip().toLowerCase()`，读取端用 `trim().toLowerCase(ROOT)`，所以 ` BLOCK `、`Warn` 两侧都认（block、warn）。**差别在 Unicode 空白**：`strip` 会去掉全角空格 U+3000 和 U+2003，`trim` 不会。所以 `　block`（中文输入法下容易打出）能通过校验并原样写库，读取端却回落 **warn**——正是 D6 自己写的「管理员以为改成了拒绝，实际仍是确认」。U+00A0 两侧都不认，被拒 | `gateparse.out` |
| **5b** | **D6 对 emr.gate.* 的「读取端大小写不敏感，这里同口径」不成立（新）** | **修法缺陷，须补**。12 个 gate 键里有 6 个由**直比型**读取端消费，`ConfigReader.get` 不做 trim：`consent.selfpay`（`InpatientService.java:155` `"block".equals`）、`discharge`（`:408`）、`consent.transfusion`（`BloodController.java:40-46`）、`consent.surgery`（`MedTechController.java:349-352`）、`archive`（`NursingQualityController.java:510-513`，`InpatientController.java:106-107` 原值下发前端）、`nursing.record`（`NursingRecordController.java:209` 原值回显）。新校验放行 `BLOCK`、`Block`、` block`、`block `、`OFF`，这些值在直比型读取端**全部读成 warn**：出院、归档、手术、输血、自费五道硬拦截被管理员「设成 BLOCK」后仍是放行。修前写 `xyz` 是静默回落，修后写 `Block` 同样静默回落，而且多了一个「校验通过」的假信号。归一型读取端（countersign / timeliness / vital.range / pathology×3 / copy）没有这个问题。**没有任何 gate 键历史上接受过三档以外的值**（逐个读取端都只认 off/warn/block；种子、迁移、E2E、文档全是小写 warn/off/block），所以新校验**不会挡掉合法配置**；现存 13 个键（12 gate + 1 copy）的值也都是精确小写 | `gateparse.out`；grep 读取端 |
| 5c | D3、D5 | D3：`loadCopyPolicy` 放到 `onMounted` 首行并单独 try（`:1636-1638`），成立。D5：放行档显示 info「放行」，档位初值仍是 warn，接口读不到时显示「需确认」，与实际档位可能不一致，但这正是「读不到维持 warn」的设计口径 | `ad4b0f1` diff |

## 口径问题

1 和 2 须在落表前修掉或标注，3 到 5 须标注，其余记录在案：

1. **D6 校验比 6 个直比型 gate 读取端宽**（5b）。最小修法是一行：把**归一后的值**写库（`update … set cfg_value = ?` 改传 `value.strip().toLowerCase(Locale.ROOT)`），或者校验改为精确小写、不 trim。同时把 `strip` 和 `trim` 统一，否则 U+3000 的口子（5a）对 copy 键依然存在。现在没有任何用例钉住这一格（全仓测试 grep 不到「取值只能」或 emr.gate 的 PUT 用例）。这一条不在本组五行的说明字面内（说明没写取值校验），所以不构成对五行的推翻，但修法本身宣称修掉的失败模式，换了个输入照样能复现。
2. **门诊「提交」的先暂存会改写书写医师**（2e）。修法让 4023 在界面上不再可达，并把 `outp_emr.doctor_id` 改成「最后提交人」。建议：提交路径的 `saveEmr` 在 `emr.doctorId` 非空且不等于当前用户时，前端先拦下提示「非本人书写」；或者让后端 `saveEmr` 对已有书写人的草稿不改写 doctor_id。本组说明没有提到书写人本人签名，不推翻，但 2457 的「提交表示病历已书写完成」由谁完成，这里改了口径。
3. **自动套用的未改动模板可一键提交**（1f）。修前有 4009 兜底，修后没有了。建议提交前用 `autoTpl && emrEquals(emr, autoTpl.after)` 判断，命中就拦下，提示「科室默认模板尚未修改」。
4. **1082/2458 的识别范围句仍偏宽**（4f）。二选一：病案复印页和出院小结打印页也挂 `onCopy`（来源患者分别取复印对象和 `data.patient_id`），或者把句子窄化为「能识别在门诊、住院医生站病历编辑区、患者360视图与病历版本页复制的片段」。
5. **住院 PUT 与签名的竞态**（3b，实现者自述未修）。说明没有声称并发安全，不推翻；修法是给 `signRecord` 也改成 `update … set signature=?, signed_at=? where id=? and signature is null`，不再整实体 save。
6. 记录：PUT 继承类级 NURSE（3c）；PUT 不更新 `content_json`（3d）；`saveEmr` 里 `code !== 0` 分支是死代码（2c）；提交往返期间输入框不禁用（2d）；`localStorage` 来源登出不清、D4 让滞留文本变多（4a）；`emr.version.gate`、`emr.version.dedup` 不在 D6 前缀内，仍可写任意值（`emr.version.gate` 读取端同样回落 warn）。

## 审计者错漏

| # | 审计材料位置 | 问题 |
|---|---|---|
| E1 | §六 N18、§七 D6「可加三态校验」 | 只看了 copy 键的归一型读取端（实测 ` BLOCK `→block），没有提醒 emr.gate.* 的读取端口径不一（6 个直比型）。实现者照 copy 键的口径给整个前缀加了校验，产生了 5b |
| E2 | §七 D1 修法「提交前先 `saveEmr()` 再签」 | 没考虑 `saveEmr` 会改写 doctor_id（→ 4023 被架空，2e），也没考虑默认模板未改动时 4009 兜底随之消失（1f） |
| E3 | §六 N13「患者360视图、病历版本留痕等」 | 漏了病案复印（ADMIN/QUALITY）和出院小结打印页。ADMIN 正是住院医生站的唯一使用者，所以对住院侧 1082 来说，这两页才是主要缺口 |
| E4 | §三 B「住院……记录不可改，签的就是屏上那条，无此问题」 | 审计时为真；修后有了 PUT，住院同样出现「签的不是刚改的那版」的竞态（3b，概率低）。审计材料先于修法，不算错，但上调前须重核 |
| E5 | §六 N10 | 没指出 InpEmrController 类级放行 NURSE，新 PUT 随之对护士开放（3c） |
| E6 | §二 B T3（D1 描述） | 「`signEmr` 只检查页面表单非空」是对的；补一句：`saveEmr` 的 `return`（修后为 `return false`）分支在拦截器下不可达，失败实际以异常形式中断，不影响结论 |

## 说明逐句核

对象：CSV `docs/验收/技术偏离表-v3.csv` 中 988/1101/2457/1082/2458 五行（wave39），与审计材料引文逐字一致（`rows.txt`）。下表只列数据口径上**修后有变化**或**本镜头有新发现**的句子，其余同审计者 §三 的逐句核。

### 988 / 1101

| 句 | 判定 |
|---|---|
| 若本科室（挂号科室）设有启用的门诊默认模板且病历正文为空，进页自动套用该模板并提示，可一键撤销 | 真（1a–1e）。须标注审计者 N1/N2/N3；补「自动套用的内容未经修改时不可直接提交」或修掉 1f，否则评委点一下就能签出纯模板骨架 |
| 已有正文、已签名或本科室未设默认模板时不自动套用 | 真 |
| 1101「急诊留观的观察记录可从可见模板中选择后整段插入」 | 本轮修法未触及；N8 的「替换」措辞照审计者 |

### 2457

| 句 | 判定 |
|---|---|
| 病历未签名即为暂存，签名即为提交 | 真；门诊与住院现在都可反复暂存（2a、3a） |
| 住院医生站病历时间线逐条显示「暂存」或「已提交」，按钮分别为「暂存」与「提交（签名）」 | 真。住院按钮实为「暂存记录」「修改 / 保存修改」「提交（签名）」，宜补「住院未签名记录可点『修改』续写，每次保存留一版」，现句没有写出 N10 修后的能力 |
| 提交后病历冻结、不可直接修改 | 真（门诊 4008，住院 9103 实测） |
| 形成完整修改痕迹并可回放 | 同审计者 N12（偏宽） |
| （未写，建议补）门诊「提交（签名）」先暂存屏上内容再签名 | 修后为真（2a）；按审计者 §八 2457 第 1 句取「提交时自动暂存当前内容后签名」分支 |
| 1081★「由门诊医生站与住院医生站提供：病历签名前可反复保存」 | **修后两侧均为真**（住院 PUT 实测可反复修改，每次留版，同内容去重）；1316 的「尚未提供：临时存储（草稿）…未书写完成的病历无法标记为草稿」与之互斥，须同批改 |

### 1082 / 2458

| 句 | 判定 |
|---|---|
| 门诊医生站与住院医生站病历编辑区均生效 | 真（D3 修后门诊进页首先加载档位） |
| 参数分三档：放行、粘贴前确认、拒绝粘贴 | 真；取值校验只在 U+3000 这类全角空白上与读取端不同口（5a），须标注或修掉 |
| 编辑区显示当前管控档位 | 修后为真（D5 放行档也显示） |
| 只能识别在本系统内从其他患者病历复制的片段 | **仍偏宽**：患者 360 和版本页已补登记（4d/4e 链路正确），病案复印和出院小结打印页未登记（4f）。修掉或窄化，见口径问题 4 |
| 参数由系统管理员通过系统配置接口设置 | 真；补一句「取值只能是 off/warn/block」可以，但须先修 5a/5b，否则写「BLOCK」被接受这件事与该句的暗示相反 |

## 结论 + 一句理由

- **988★：不推翻，但须标注。** 取数 SQL、唯一索引、snake_case 返回体、前端判定、bootstrap 默认模板这条链路逐格对得上，HTTP 实测通过。须标注的是 2457 修法带来的交叉副作用（1f：未改动的默认模板一键可签），以及审计者 N1–N3。
- **1101★：不推翻（同意审计者维持）。** 门诊部分口径同 988；急诊部分本轮没有任何修法触及，审计者 §三 A4 的判断不变。
- **2457★：不推翻，但须标注。** D1 修后门诊「签的就是屏上的」链路成立（实测 3 版：MANUAL A → MANUAL B → SUBMIT B，同内容去重）；D2 修后住院可反复修改（实测条件更新、两列同步、MANUAL、保存人、9103）。须标注：4023 被架空、书写医师被改写（2e）；住院签名整实体 save 与 PUT 的竞态（3b，实现者自述未修）；提交往返期间输入未禁用（2d）。1081 的住院句修后成立，1316 须同批改。
- **1082★：不推翻，但须标注。** 患者 360 与版本页登记的 patientId 都是被复制正文的真实所属患者（EMPI 同一 id 空间，实测），档位读口与 D3/D5 修法成立。但「本系统内」仍漏病案复印和出院小结打印两页（4f）；D6 校验与 copy 读取端在全角空白上不同口（5a）。
- **2458★：同 1082。**
- **D6 本身（镜头 4）：不会挡掉合法配置，但修法缺陷须补。** 6 个直比型 gate 读取端把校验放行的 `BLOCK`、`Block`、` block` 读成 warn，与提交说明「读取端大小写不敏感，这里同口径」相反（5b）。

无推翻级发现：五行说明没有一句与修后代码或库态相反，数据链路也没有断裂。5b 是修法本身的口径反例，但不在五行的说明字面内。

## 写操作与清理

| 写操作 | 对象 | 状态 |
|---|---|---|
| admin 建患者 | `empi_patient` 1001253「反驳B3门诊」 | 保留（无删除端点） |
| admin 挂号 | `outp_registration` 1069（排班 534，内科，doctor01） | 保留；排班 534 的 booked 加 1 |
| doctor01 接诊、暂存 ×3、签名 | `outp_emr` 104（已签名），`emr_version` OUTP 104 v1–v3 | 保留（已签名病历按法定不可删） |
| admin 住院记录 | 演示住院患者 adm 626：`inp_medical_record` #81「反驳B3病程」、#82「反驳B3查房」，PUT ×4，均已签名；`emr_version` INP 81 v1–v4、82 v1–v3 | 保留。**会出现在演示住院患者的时间线上**（标题以「反驳B3」开头，可辨识） |
| 必然失败的写尝试 | nurse01 对已签名的 #81 PUT → 9103 | 无状态变化 |
| 系统配置 | 无 | `emr.copy.cross_patient` 仍为 warn；本轮未发任何 `PUT /api/config` |
| 仓库 | 只新建本文件 | 未 commit |
