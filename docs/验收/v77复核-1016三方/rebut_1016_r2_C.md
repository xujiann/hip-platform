# 反驳者三（数据口径）· 1016★ 第二轮

> 时点 2026-10-04，HEAD `b689e37`（tag v1.7.3），与审计材料 `audit_1016_r2.md` 同一提交。仓库零改动，本文件是唯一新建仓库文件（未 commit）。
> 手段：读源码；开发库 `hip` 直查（`wsl -d Ubuntu -u postgres -- psql -d hip`，只 select / `\d`）；对本机 8080 的 1.7.3 后端做 HTTP 实测（脚本 `scratchpad/rebut1016C/http1.py` `http2.py` `http3.py`）。
> 写操作仅协议允许的两类，已全部清理：建「反驳C-英文Serum」(#8)、「反驳C-并发0/1」（并发只成功一条 #9）两条规则，用后删除，库中只剩他人建的 #1；doctor01 给张三（挂号 1050）开 2 LAB + 1 EXAM（1845–1847），验完立刻 `PUT /outpatient/doctor/orders/{id}/cancel`，库态 `status=CANCELLED`。未起前端、未开浏览器、未跑 mvn / JUnit。
> `lab.route.enabled` 的 `updated_at`=14:41:50 与 `flyway_schema_history` V174 `installed_on` 同秒，是迁移写入而非有人改过开关。

## 逐链路核对表

| # | 链路 | 结果 | 证据 / 复现 |
|---|---|---|---|
| 1a | 候选筛选 SQL 与排序 | **与说明一致** | `LabRouteService.java:79-91`：`enabled and item_category='LAB'` + 三键「空即通配、非空等值」；排序 `case` 加权 4/2/1 降序 → `priority` → `id`。停用科室：`:93-97` `continue` 取下一候选，不是整体回落（审计者 Q2 写对）。无命中 `fallback()` 取 `md_charge_item.exec_dept_id`，不校验科室启停（`:114-122`） |
| 1b | 规范化是否两侧同函数 | **同一函数 `normalizeSpecimen`** | 写规则 `validate()` `:218`；匹配 `resolve()` `:77`；两处都是 `:125-129` 的 `strip()` + `replaceAll("\\s+","")` + `toUpperCase()`。实测建规则传 `"  Serum  Blood "` 落库 `SERUMBLOOD`，`serum blood ` / `SERUM BLOOD` 均命中 |
| 1c | **「忽略空格」只对半角空白成立** | **须标注（见口径问题 1）** | Java `\s` 不含全角空格 U+3000 与 NBSP U+00A0；`strip()` 只去首尾。HTTP `resolve?chargeItemId=3&orderDeptId=1&specimenType=…` 对规则 #1「血清」：`血 清`(半角)→RULE、`血\t清`→RULE、`　血清　`(首尾全角)→RULE，但 **`血　清`(内含全角) → ITEM**、`血 清` → ITEM、`血清 `(尾 NBSP) → ITEM。英文同理：`Serum　Blood` → ITEM。中文输入法全角状态下敲出的空格正是 U+3000 |
| 1d | `createOrders` 只对 LAB 落值；原值保留 | **成立** | `DoctorStationService.java:835-839`，`resolve(item, line.specimenType(), reg.getDeptId())`；`o.setSpecimenType(line.specimenType())` 落**原文**（`:799`），匹配时才规范化。实测 1845 库态 `specimen_type='血　清'`（含全角）、1846 `'血 清'` 原样；EXAM 1847 `exec_dept_id` null、返回体无 `execDeptName` 键 |
| 1e | 「开单科室」= 挂号科室 | **成立** | `reg` 是 `OutpRegistration`（`:703`），`reg.getDeptId()`；申请单页眉「申请科室」同取 `r.dept_id`（`PrintReportController.java` DOC_HEADER_SQL），两处口径一致。审计者 N12 对 |
| 1f | 三条读路径 coalesce 一致；LIS `deptId` 过滤也用 coalesce | **成立** | 打印 `PrintReportController.java:196` `left join sys_dept ed on ed.id = coalesce(o.exec_dept_id, ci.exec_dept_id)`；`/lis/pending` `MedTechController.java:101,105,108`；`/lis/samples` `:170,173,176`——select 列、join、where 过滤三处都是同一个 `coalesce`。HTTP：历史行 1829（`exec_dept_id` null、字典 8）在 `/lis/pending?deptId=8` 出现、`exec_dept_name=检验科`；`?deptId=9` 零行；检验申请单与导诊单 1829 皆印「检验科」 |
| 1g | 历史行输出逐字不变 | **成立** | 列名 `exec_dept_name` 未改，只换 join 条件；`exec_dept_id` null 时 `coalesce` 退化为原 `ci.exec_dept_id`。LIS 两队列不传 `deptId` 时 where 不变、只多两列 |
| 1h | `lab.route.enabled` 生效时延 | **审计者 N6 的「缓存 30 秒」要补一句** | `ConfigReader.java:20` TTL 30 s；但 `SysConfigController.java:82-91` `PUT /api/config/{key}` 后立刻 `configReader.evict(key)`——走接口改**立即生效**，只有直改库才最多等 30 s |
| 1i | `resolve` 返回体 source 三档与开关关闭 | **成立** | RULE / ITEM / NONE（`:61`）；开关非 "1" 直接 `fallback(item)` → ITEM 或 NONE（`:74-76`），返回体不区分「未查规则」与「查了没命中」，前端 `LabRouteRuleView.vue:213` 对两者印同一句「无规则命中，按收费项目字典」。审计者 N6 对 |
| 2a | 摘要链路：预填 → 落库 | **成立** | `DoctorStationView.vue:1413-1428` 读**本页表单** `emr.chiefComplaint/presentIllness`（`:1046-1058` 由 workspace 装入已保存值，之后随医生编辑）→ `row.clinicalSummary` → `OrderLine.clinicalSummary` → `o.setClinicalSummary` `:796` → `outp_order.clinical_summary varchar(500)`。库态 1832 与 `outp_emr` 1050 逐字同源（审计者已核） |
| 2b | 打印页两行摘要 | **成立（审计者 N1 对）** | 「病史摘要」= 打印时现查 `outp_emr.chief_complaint/present_illness`（`PrintReportController.java:285-289`；`print-format.ts:96-100` 各自 `stripBlockMarks` 后以「；」连）；「临床摘要」= 组内首个非空 `clinical_summary`（`:353` SHEET_LEVEL_COLS；`PrintView.vue:206-207`）。`physical_exam/advice` 也随 `emr` 下发，但检验申请单不印 |
| 2c | **`【结构化记录】` 标记会被预填进临床摘要** | **须标注 / 登记缺陷（见口径问题 2）** | `saveEmr` 用过结构化元素时把 `【结构化记录】…【结构化记录结束】` 渲染块**追加进 `present_illness`**（`DoctorStationService.java:226-229,325`）；workspace 把 `OutpEmr` 实体原样返回（`DoctorStationController.java:137-138`）；`DoctorStationView.vue` 全文零处 `结构化记录`——文本框与预填都不剥标记；预填 `:1418` 直接 `emr.presentIllness.trim()`。于是「临床摘要」= `主诉：…；现病史：…【结构化记录】\n…【结构化记录结束】`，而纸面「病史摘要」是剥过的、「临床摘要」`firstOf` 不剥（`print-format.ts:13-19`）——同一张纸一行干净一行带内部标记。开发库 `outp_emr` 含该标记的行为 0，未能库态复现，属静态推断；1075★/989★ 一旦启用结构化录入即会出现 |
| 2d | 500 字截断 | **前端截、后端不截** | 前端 `slice(0,500)` + 一次 warning；`el-input maxlength=500`。API 直传 501 字 → 后端无字段校验，PG 列宽报错落到通用 **4091「数据不符合约束要求…」**（实测）；33 字标本类型同为 4091。JS `length` 按 UTF-16 单元计，恒 ≥ PG 字符数，前端截完不会再撞列宽 |
| 3 | 诊断链路 | **成立（审计者 N3 对）** | 录入行标签 `:365-373` 逐条 `d.icdName`（`diagList` 含未保存行；`pushDiag` `:1211-1213` 中医诊断 icdName=中医名，故也显示）；不显示 prefix/suffix/certainty/customName/[中医]。申请单 `formatDiagnoses`（`print-format.ts:26-40`）印已保存 `outp_diagnosis` 的前缀/后缀/［自定义］/(编码)/（疑诊）/[中医]。实测 1050：`[J06.900 急性上呼吸道感染 primary]`，标签与纸面同名 |
| 4 | 标本类型 / 采样部位 | **成立** | 列 `varchar(32)`；LIS 两队列与打印都取 `o.specimen_type, o.sampling_site` 原文；申请单 `[specimen_type, sampling_site].join(' / ')`，单头级 `specimen_type` 也带（组内首个非空）。实测 1845 印 `血　清 / 肘正中静脉` |
| 5 | `/auth/me deptId` → LisView 默认值 | **成立** | `AuthController.java:151-153` `user.getDeptId()` + join 名；`stores/auth.ts:63-64` 以 `/auth/me` 回填 `user`；`LisView.vue:103` `auth.user?.deptId ?? null` → null 即「全部」。实测 admin/tech01 `deptId=null`、doctor01 `1`。审计者 N7 对 |
| 6a | 同键重复 5913 | **成立** | `checkDuplicate` `is not distinct from` 三键（`:266-275`）；实测顺序重建 → 5913 |
| 6b | **并发同键** | **无唯一索引，有窗口；本次未复现** | `\d lab_route_rule` 只有 pkey + `(item_category,enabled,priority)` + `(charge_item_id)` 三个索引，无键三元组唯一约束；判重是 READ COMMITTED 下的 select-then-insert。两线程同时 POST 同键：本次一条成功 (#9) 一条 5913，但靠的是时序而非约束。若双双成功，匹配按 `id` 取先建者，页面能看到两条、编辑/启用时才会被 5913 挡住。不在说明字面内，登记为缺口 |
| 6c | 删规则对已开医嘱 | **成立** | `delete()` 仅删规则行；`outp_order.exec_dept_id` 无外键（`\d outp_order`）；实测删 #8/#9 后 1845/1846 `exec_dept_id=8` 不动 |
| 7 | workspace 回显 | 审计者 N4 对 | `/workspace` 的 `orders` 带 `execDeptId`（1845→8、1847→null），无 `execDeptName`（瞬态只随 createOrders 返回体）；已开医嘱表不显示 |

## 口径问题

全部不构成「说明与代码相反」或「链路断裂」，但 1 必须改说明措辞，2 须登记缺陷并在说明里限定：

1. **「忽略大小写与空格」须收窄为「忽略半角空白」**。说明原文与规则页文案（`LabRouteRuleView.vue:19`）、审计者建议稿「忽略大小写与空白」三处同一口径，都把 U+3000 全角空格包含进「空格」语义里，而 `normalizeSpecimen` 的 `\s+` 不吃全角空格与 NBSP（见 1c 实测）。中文全角输入下「血　清」不命中「血清」会回落字典，评委随手一敲就能撞上。不算推翻：半角空格确实忽略，说的是「不全」而非「相反」。修法一行（`replaceAll("[\\s\\p{Z}]+","")` 或 `Pattern.UNICODE_CHARACTER_CLASS`），两侧同函数，改后旧规则无需迁移（库里规则值若含全角空格，是在改前就没被去掉的，需扫一遍）。
2. **结构化病历的渲染标记会原样进入预填的临床摘要并上纸**（2c）。「自动带入门诊病历主诉与现病史」字面为真，但带入的是含内部标记的正文；1013/1026 两行说「病史摘要于打印时取病历」的那一行是剥过标记的，1016 新加的「临床摘要」这一行没剥。建议说明加「预填取病历正文原文」或直接修前端：`prefillClinicalSummary` 对 `presentIllness` 先 `stripBlockMarks`（函数已在 `utils/print-format.ts:91` 现成）。
3. **500 字限制只有前端守**（2d）：API 直调超长落到通用 4091，不说哪个字段；说明写「超长截断提示」是前端口径，够用，不必改。
4. **规则键无唯一约束**（6b）：登记为技术债，建议加 `unique (charge_item_id, specimen_type, order_dept_id) where enabled`——注意 PG 唯一索引对 null 不判重，三键全可空，需 `coalesce` 表达式索引或 `nulls not distinct`（PG15+，本库 16 可用）。
5. 审计者 N6 的「ConfigReader 缓存 30 秒」要补「经 `PUT /api/config/{key}` 改立即生效（evict），直改库才等 TTL」（1h）。
6. 同意审计者 N1/N2/N3/N5/N8/N12 的限定；N8 的「仅完全相同才命中」实测再添三例：内含全角空格、NBSP、尾 NBSP 均不命中。

## 说明全文逐句核

对象：审计者「建议落表的说明全文」（`audit_1016_r2.md` §七）。CSV 事实源是 `docs/验收/技术偏离表-v3.csv` 第 1004 行（审计者引的 wave35 原文与之逐字一致；`-v2.csv` 是「平台已实现」旧版，别拿错）。

| 句 | 判定 |
|---|---|
| 开具检验申请时可填写标本类型与采样部位，随申请单保存、印出并在检验科待采样与标本流转列表显示 | 真（原文落库、三处读同列） |
| 检验、检查医嘱录入行即时展示病历区当前录入的诊断名称 | 真（只名称、含未保存、含中医名） |
| 在加入行时按本次门诊病历的主诉与现病史自动预填临床摘要（医生可改，超长截断提示） | 真；**须补**「取病历正文原文」或先修掉标记泄漏（口径问题 2） |
| 临床摘要随申请单保存并印出，申请单另印本次就诊已保存的临床诊断与打印时取自病历的病史摘要 | 真（两行两时点，与 1013「诊断与病史摘要于打印时取当前记录」不互斥） |
| 执行科室按「检验流向规则」在开单时自动确定并随医嘱保存 | 真（只 LAB；下句已限定） |
| 规则以检验项目、标本类型、开单科室（即挂号科室）三个条件匹配，按项目、标本类型、开单科室的先后权重取最具体者，再按优先级 | 真（4/2/1 加权；建议补「再按建立先后」——`id` 升序是第三序，页面文案已写） |
| 无规则命中时按收费项目字典配置的执行科室带出 | 真（含规则指向科室停用时跳过该条；字典科室不校验启停） |
| 规则由系统管理员或医技技师角色在基础数据下维护，可停用、可试算 | 真（`hasAnyRole('ADMIN','TECHNICIAN')`） |
| 开单成功提示中显示本批检验申请的执行科室，检验申请单与导诊单逐行印出，检验科待采样与标本流转队列可按执行科室筛选（默认登录人所在科室） | 真（toast 去重合并；LIS 默认 `deptId` 为空即全部） |
| 说明：临床摘要预填后修改病历不自动同步 | 真 |
| 执行科室在已开医嘱列表不显示 | 真（workspace 有 `execDeptId`，前端不用） |
| 规则按医生实际填写的标本类型匹配，仅与规则配置值完全相同（忽略大小写与空白）时命中，标本类型为自由文本、未字典化 | **半真**：「忽略…空白」须改「忽略大小写与半角空白」，否则全角空格一例即可当场证伪（口径问题 1） |
| 流向规则仅对门诊检验类医嘱生效，检查、治疗类按收费项目字典带出，住院检验申请暂无执行科室分流 | 真（`inp_order` 27 列无任何 dept 列，库查） |
| 执行科室在开单时落值，此后修改规则不改已开出的申请 | 真（删规则亦然，无外键） |
| 流向规则总开关由系统管理员通过配置接口设置，默认开启 | 真（接口改即时生效） |
| 执行科室与检验技师所属科室由院方在实施期配置 | 真（演示库 tech01/admin 无科室，仅检验科一个检验类执行科室） |

## 结论 + 一句理由

**不推翻但须标注。**

理由：每一格数据的来源、落点与再读方都接得上——标本/采样部位原文落 `outp_order` 两列、三条读路径同取；执行科室由 `resolve` 落医嘱级快照、打印/LIS 列与过滤四处同一个 `coalesce`、历史行退化为字典值逐字不变；临床摘要由本页病历预填落 `clinical_summary` 并在申请单印出；规范化在写规则与匹配两侧是同一函数——没有一句说明与代码相反，没有「写了没人读、读了不是写的那份」。但「忽略大小写与空格」实测只对半角空白成立（全角空格 U+3000、NBSP 内含即不命中），说明、规则页文案与审计者建议稿三处须同改为「半角空白」；结构化病历渲染标记会随预填进入临床摘要并上纸，须限定或修补；按审计者建议稿原文落表，本镜头对那一句维持「半真」。

另建议单独提修补（不在 1016 字面内）：`normalizeSpecimen` 吃全角空格/NBSP；`prefillClinicalSummary` 复用 `stripBlockMarks`；`lab_route_rule` 键三元组加 `nulls not distinct` 部分唯一索引；`LabRouteRuleView.vue:213` 开关关闭态文案（需 `resolve` 返回体加 `OFF` 档或前端读开关）。
