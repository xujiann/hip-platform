# 1026★ 五张单据字段级自查

> 基线：HEAD `052dc6a`，只读源码（未起服务、未跑测试、未改仓库文件）。
> 源表列以 Flyway 迁移为准：outp_order（V5+V13+V47+V137）、outp_diagnosis（V5+V135）、outp_emr（V5+V12+V139）、
> empi_patient（V2）、outp_registration（V3+V47）、sys_user（V1）、md_drug（V4+V22+V32+V116+V132+V134+V149+V150+V153）、
> md_charge_item（V4+V116+V132）、lis_sample（V17+V37）、outp_skin_test（V20）、cdss_patient_allergy（V152）。
> 缩写：PV = frontend/shell/src/views/PrintView.vue；PC = server/src/main/java/cn/hip/server/web/PrintReportController.java；
> DSS = modules/outpatient/src/main/java/cn/hip/outpatient/service/DoctorStationService.java；DSV = frontend/shell/src/views/outpatient/DoctorStationView.vue。

## 0. 先说三个前提事实（都已核实）

1. 门诊医嘱只有两处写入 outp_order：`DSS.createOrders`（药品一次调用共用一个 group_no，DSS:710/784；非药品每行各取一个 group_no，DSS:805）与挂号费 `GH-<id>`（REG）。
   所以"一组多医生""一组多行时 firstOf 串行"在现有写路径上不会发生；`firstOf` 对检验/检查/治疗取到的就是该行自己的值。这是**隐含前提**，不是约束（库里无唯一/CHECK 保证），说明里不要写成"系统保证"。
2. 打印页的诊断、病历、过敏史、科室、医师**全部是打印时现取**，没有任何"开单时快照"。诊断在 `saveEmr` 里是"先删后插"（DSS:132-140），补打永远印当前值。
3. `docs/验收/技术偏离表-v3.csv` 第 1026 行的响应说明至今仍是"五类单据……当前版本尚未提供，列入后续开发计划"（结论"部分响应"），与代码事实（v43 起已落地）完全相反；
   上调候选措辞只存在于 `docs/验收/v74复核-1026三方/audit_1026_r2.md` 末尾"建议的说明改写要点"，尚未写进 CSV。第三轮要评的说明本身还没落盘，见 §4。

---

## 1. 逐格核对表

### 1.0 五张单共用页眉（PV:141-165，SQL 为 PC:151-161 `DOC_HEADER_SQL`，年龄 PC:197-203/236）

| 单据 | 格子 | 前端 | 后端列 | 源表 | 未取的修饰列 | 空值 | 判定 |
|---|---|---|---|---|---|---|---|
| 全部 | 医院名 | `hospitalName`（/config/public.hospital_name） | — | sys_config | — | 空串→标题空白 | 正确 |
| 全部 | 条码位可读号 | `g.groupNo \|\| data.patient_no` | o.group_no | outp_order | — | 导诊单印患者号 | 正确（不画条码图形，已如实声明） |
| 全部 | 姓名 | `data.patient_name` | p.name | empi_patient | — | — | 正确 |
| 全部 | 性别 | `sexNameOf(data.sex)`，M/F 以外→「—」 | p.sex | empi_patient | — | U→「—」 | 正确 |
| 全部 | 年龄 | `ageText`：null→「—」，否则「N 岁」 | `ageOf(p.birth_date)`=Period.getYears()（截至 `BusinessDates.today()`） | empi_patient.birth_date | 无月龄/日龄；基准是打印日不是就诊日；birth_date 晚于今天会得负数 | 无生日→「—」 | **会印错**（婴幼儿印「0 岁」，见 §2-③）；口径可疑（基准=打印日） |
| 全部 | 门诊号 | `data.patient_no` | p.patient_no | empi_patient | outp_registration.id（本次就诊）未取 | null→空 | 口径可疑：印的是**患者主索引号**不是就诊号；医生站同样叫「门诊号」（DSV:692），住院单据叫「患者号」（PV:116）——系统内自洽，对外别写成"门诊就诊号" |
| 全部 | 科室 | `data.dept_name` | d.name（r.dept_id） | outp_registration→sys_dept | 开单医师所属科室（sys_user.dept_id）未取 | — | 口径可疑：是**挂号科室**，代班/会诊医师不同科时"申请科室/开单科室"仍印挂号科室 |
| 全部 | 就诊日期 | `fmtDate(visit_date)` | r.visit_date（=排班日，RegistrationService:44/87 写入后不再改） | outp_registration | — | — | 正确 |
| 全部 | 号序 | `第 {{reg_no}} 号` | r.reg_no | outp_registration | — | — | 正确（叫号日志 outp_call_log 也用 reg_no） |
| 全部（导诊单除外） | 临床诊断 | `diagText`：`前缀 + (custom_name‖icd_name) + 后缀 + (icd_code) + [疑诊→（疑诊）]`，「；」连接，主诊断在前 | icd_code, icd_name, primary_diag, prefix, suffix, certainty, custom_name（PC:240-243） | outp_diagnosis | **diag_system（中医/西医）未取**；custom_name 被当作 icd_name 的替代 | 无诊断→「—」 | **会印错**：custom_name 顶替标准名（§2-②）；口径可疑：中西医诊断混排无标识、印当前值非开单时值 |
| 全部 | 打印时间 | `new Date().toLocaleString('zh-CN')`，页面挂载时取一次 | — | 浏览器时钟 | printedOn（后端 BusinessDates.today，已返回未使用） | — | 口径可疑：跟浏览器时区/时钟走，违背 date.ts 自己立的"不跟浏览器走"；取值时刻是打开页面而非点「打印」 |

### 1.1 处方笺 prescription（PV:168-193）

数据来源：`DOC_ORDER_SQL`（PC:176-194）+ `o.order_type='DRUG' and o.status<>'CANCELLED'`（PC:268）。药品行 `spec/unit/days/usage_route/frequency/dose_per_time` 都是**开单时快照**（DSS:786-796）；`antibiotic` 是**实时 join md_drug**。

| 单据 | 格子 | 前端 | 后端列 | 源表 | 未取的修饰列 | 空值 | 判定 |
|---|---|---|---|---|---|---|---|
| 处方笺 | 过敏史 | `data.allergy_history \|\| '无'`（PV:169） | p.allergy_history | empi_patient.allergy_history（自由文本 varchar(512)） | **cdss_patient_allergy（ACTIVE 结构化过敏记录：过敏原/严重度/表现）完全没取**；未 trim（纯空格串会印成空白而非「无」） | 空→「无」 | **会印错（高）**：见 §2-① |
| 处方笺 | 药品名 | `r.item_name` | o.item_name（=md_drug.name 快照） | outp_order | md_drug.generic_name（通用名，V153，可空）未取；md_drug 不区分商品名/通用名 | — | 口径可疑：不能说"印通用名" |
| 处方笺 | 规格 | `r.spec`（有值才印） | o.spec | outp_order（=md_drug.spec 快照） | dose_form（剂型）已取未印 | 空→不印 | 正确 |
| 处方笺 | 加急 / 备注 | `【加急】` `（remark）` | o.urgent, o.remark | outp_order V137 | — | 空→不印 | 正确（UI 药品行可填：DSV:311-317） |
| 处方笺 | 数量单位 | `× qty unit` | o.qty, o.unit（=drug.unit 销售单位：盒/瓶） | outp_order | md_drug.pack_size/min_unit（V150）未取 | — | 正确：qty 是**销售单位数**，与「每次 1粒」并列时读者需自己换算，无错 |
| 处方笺 | 抗菌药标 | `v-if r.antibiotic` → 红框「抗菌药」 | dr.antibiotic（md_drug.antibiotic，V4） | md_drug（实时） | **abx_level（0/1/2/3 分级，V22）未取**；drug_class、self_pay、high_alert（V149）未取 | false→不印 | 口径可疑：`antibiotic` 由 CSV 导入维护（MasterDataController:92-97 只写它），处方权闸（DSS:1015）与指标（MetricSnapshotService:43）用的是 **abx_level**——两列可背离；且不印「限制/特殊使用级」 |
| 处方笺 | 皮试标记 | **无** | — | md_drug 无皮试列；outp_skin_test（registration_id, drug_name 自由文本, result PENDING/NEG/POS） | 全部未取 | — | 口径可疑（缺项）：没有药品级皮试标志，皮试结果按 drug_name 文本也无法可靠对应到药品行——**没印，且现状下没法印对**；说明不得含"皮试/皮试结果"字样 |
| 处方笺 | 用法 | `用法：usage_route 每次 dose_per_time frequency 共 days 日` | o.usage_route, o.dose_per_time, o.frequency, o.days | outp_order（快照） | — | 各缺项→「—」；days 空/0→整段「共 N 日」不印 | 正确；口径可疑（小）：frequency 存的是 UI 下拉原码 `qd/bid/tid/qid/q8h/prn`（DSV:278），原样印在给患者的纸上（《处方管理办法》允许拉丁缩写，不算错） |
| 处方笺 | 药品金额 | `¥{{ g.total }}` | 后端按组内非作废行 `amount` 求和（PC:289-291），**前端不再累加** | outp_order.amount | — | — | 口径正确（含 CREATED/CHARGED/DISPENSED，不含 CANCELLED；退费退药后行状态回到 CREATED/CHARGED，仍计入）；**格式会印错（低）**：BigDecimal 经 JSON 成 number，`12.50`→`¥12.5`、`10.00`→`¥10`（§2-⑥） |
| 处方笺 | 医师签名栏 | `docDoctor(g)`=组内首个非空 `order_doctor_name`，缺则回落挂号医师 | du.real_name（o.doctor_id） | outp_order→sys_user | du.title 未取（职称后缀仅"开单人=挂号医师"时才印，PV:426-429） | — | 正确（代班时不带职称，是信息缺失不是印错） |
| 处方笺 | 药师（审核）栏 | 空手填 | — | outp_order.review_status / reviewer_id / review_note（V13） | 全部未取；`APPROVED` 与「未审」的处方印得一样 | 恒空 | 口径可疑：不能写"处方笺带药师审核结果" |
| 处方笺 | 开具日期 | `fmtDate(data.visit_date)` | r.visit_date | outp_registration | **o.created_at（真实开单时间）已取未用** | — | 口径可疑：印的是挂号排班日，不是开单日；正常流程同日无差，冻结业务日的演示库里 created_at=now() 会不同 |
| 处方笺 | 费别 | **无** | — | empi_patient.insurance_type；md_drug.self_pay | 未取 | — | 口径可疑（缺项）：《处方管理办法》处方前记含"费别"，五张单都没有；说明不得写"符合处方前记要求" |
| 处方笺 | 作废行 | 已过滤 | `status<>'CANCELLED'`（PC:268） | outp_order | — | 全作废→4893 | 正确 |

### 1.2 检验申请单 lab-request（PV:196-227）

一次 createOrders 里每个检验项目各取一个 group_no（DSS:805）→ 一张纸一行，`firstOf` 单头字段无串行风险。

| 单据 | 格子 | 前端 | 后端列 | 源表 | 未取的修饰列 | 空值 | 判定 |
|---|---|---|---|---|---|---|---|
| 检验申请单 | 申请科室 / 申请日期 | `data.dept_name` / `fmtDate(visit_date)` | 见 §1.0 | — | — | — | 口径可疑（同 §1.0：挂号科室、排班日） |
| 检验申请单 | 申请医师 | `docDoctor(g)` + `docDoctorSuffix(g)` | du.real_name | outp_order.doctor_id | du.title | 缺→回落挂号医师 | 正确 |
| 检验申请单 | 病史摘要 | `briefHistory`=主诉；现病史 | e.chief_complaint, e.present_illness（PC:245-248） | outp_emr | past_history（既往史）未取；**present_illness 内含结构化录入渲染进去的「【结构化记录】…【结构化记录结束】」块**（最长 2000 字），标记原样上纸 | 空→「—」 | 口径可疑：现病史整段 + 标记符号印在一行里；取当前病历值而非开单时值 |
| 检验申请单 | 临床摘要 | `firstOf(g,'clinical_summary')`，空→手填栏 | o.clinical_summary | outp_order V137 | — | 空→手填栏 | 正确；UI 的 LAB 行**没有**该输入（DSV:357-361），真实操作下恒为手填栏 |
| 检验申请单 | 项目 | `item_name` + 加急 + 备注 | o.item_name, urgent, remark | outp_order | — | — | 正确；加急仅字重加粗（`.urgent{font-weight:700}`，PV:577），**不是"醒目/红戳"** |
| 检验申请单 | 数量 | `qty unit` | o.qty, o.unit（=charge_item.unit，缺省「次」） | outp_order | — | — | 正确 |
| 检验申请单 | 标本类型 / 采样部位 | **逐行**：`[specimen_type, sampling_site].filter(Boolean).join(' / ') \|\| '—'`（PV:212） | o.specimen_type, o.sampling_site | outp_order V137 | — | 空→「—」 | 正确：**不是 firstOf**，逐行取自己的列，不会把 A 行标本印到 B 行；且现写路径一组只有一行 |
| 检验申请单 | 执行科室 | `r.exec_dept_name \|\| '—'` | ed.name（md_charge_item.exec_dept_id，**实时**） | md_charge_item→sys_dept | — | 空→「—」 | 口径可疑：交付默认库 13 个收费项目 exec_dept_id 全空且产品内无维护入口（rebut_1026_r2_C M2 已核）→ 默认全是「—」；实时取，主数据改了补打会变 |
| 检验申请单 | 标本条码 | `sample_barcode \|\| '（未采样）'` | s.barcode | lis_sample（left join，order_id 唯一，无重复行风险） | — | 无→「（未采样）」 | 正确 |
| 检验申请单 | 标本状态 | `sampleStatusNames[sample_status] ?? '待采集'` | s.status（COLLECTED/RECEIVED/PUBLISHED） | lis_sample | **substitute、substitute_name（替检标识，V37）未取**；collected_at 已取未印；订单缴费状态不体现 | 无样本→「待采集」 | 口径可疑：替检标本与正常标本印得一样；未缴费的项目也印「待采集」（本单无缴费状态列） |
| 检验申请单 | 注意事项 | `firstOf(g,'notice')`，空→手填栏 | o.notice | outp_order V137 | — | 空→手填栏 | 正确；UI 的 LAB 行无该输入（备注代之，1006 说明已如实写） |
| 检验申请单 | exam_purpose | 不印 | o.exam_purpose | outp_order | — | — | 正确（UI 不可能在检验行填该列） |
| 检验申请单 | 单据号 | `申请单号：groupNo` | o.group_no=`SQ+yyyymmdd-序号`（序号未补零） | outp_order | — | — | 正确；口径可疑（小）：`order by o.group_no` 字符串排序，`-10` 排在 `-9` 前，多张纸顺序不按开单先后 |

### 1.3 检查申请单 exam-request（PV:230-261）

| 单据 | 格子 | 前端 | 后端列 | 源表 | 未取的修饰列 | 空值 | 判定 |
|---|---|---|---|---|---|---|---|
| 检查申请单 | 申请科室/医师/日期 | 同检验单 | — | — | — | — | 同 §1.2 |
| 检查申请单 | 检查项目 / 数量 | `item_name`+加急+备注 / `qty unit` | o.* | outp_order | — | — | 正确 |
| 检查申请单 | 执行科室 | `exec_dept_name \|\| '—'` | ed.name | md_charge_item | — | 「—」 | 口径可疑（同 §1.2，默认库恒空） |
| 检查申请单 | 状态 | `orderStatusNames[status] ?? status` | o.status | outp_order | ris_exam.status（REGISTERED/REPORTED/VERIFIED）未取 | 未知码印原码 | 正确：现有状态集只有 CREATED/CHARGED/DISPENSED/EXECUTED/CANCELLED，映射全覆盖；EXAM 在 RIS 审核（MedTechController:270）前一直是 CHARGED→「已缴费」，即报告已写未审也印「已缴费」（口径粗，不算错） |
| 检查申请单 | 检查部位（补充说明） | `firstOf(g,'sampling_site')`，空→手填栏 | o.sampling_site | outp_order | — | 空→手填栏 | 口径可疑：栏目叫"检查部位"，取的是**采样部位**列；UI 的 EXAM 行无该输入（DSV:362-366）→ 恒为手填栏 |
| 检查申请单 | 检查目的 / 临床摘要 / 注意事项 | `firstOf(...)`，空→手填栏 | o.exam_purpose / clinical_summary / notice | outp_order V137 | — | 空→手填栏 | 正确（EXAM 行 UI 三项都可填） |
| 检查申请单 | 病史摘要 | `briefHistory` | 同 §1.2 | outp_emr | 同 §1.2 | — | 口径可疑（同 §1.2，结构化块标记） |
| 检查申请单 | 体格检查 | `emrInfo.physical_exam \|\| '—'` | e.physical_exam | outp_emr | — | 「—」 | 正确 |
| 检查申请单 | specimen_type | 不印 | o.specimen_type | outp_order | — | — | 正确（UI 不可填） |

### 1.4 治疗单 treat-sheet（PV:264-288）

| 单据 | 格子 | 前端 | 后端列 | 源表 | 未取的修饰列 | 空值 | 判定 |
|---|---|---|---|---|---|---|---|
| 治疗单 | 开单科室/医师/日期 | 同上 | — | — | — | — | 同 §1.2 |
| 治疗单 | 治疗项目 / 数量 / 单位 | `item_name`+加急+备注 / `qty` / `unit` | o.* | outp_order | — | — | 正确 |
| 治疗单 | 执行科室 | `exec_dept_name \|\| '—'` | ed.name（md_charge_item.exec_dept_id，实时） | md_charge_item | — | 「—」 | 口径可疑（同 §1.2，默认库恒空） |
| 治疗单 | 状态 | `orderStatusNames[status] ?? status` | o.status | outp_order | — | — | 正确（ExecStationController:69 执行后置 EXECUTED→「已完成」） |
| 治疗单 | 医嘱要求（部位/剂量/疗程） | 纯手填栏 | — | o.sampling_site / notice / clinical_summary（可承载但 UI 不可填） | 未取印 | 恒手填 | 正确（UI 的 TREAT 行只有加急+备注：DSV:355-366 无 TREAT 分支） |
| 治疗单 | 过敏史 | **无** | — | empi_patient / cdss_patient_allergy | — | — | 口径可疑（缺项）：输液/注射类治疗单不带过敏史 |

### 1.5 导诊单 guide-sheet（PV:290-318，后端分支 PC:252-262）

后端取 `o.status in ('CREATED','CHARGED') and o.order_type <> 'REG'`，`order by o.order_type, o.id`（字母序 DRUG<EXAM<LAB<TREAT），且**不过滤挂号状态**。

| 单据 | 格子 | 前端 | 后端列 | 源表 | 未取的修饰列 | 空值 | 判定 |
|---|---|---|---|---|---|---|---|
| 导诊单 | 就诊科室 | `data.dept_name` | r.dept_id→d.name | — | — | — | 正确 |
| 导诊单 | 诊室 | 固定「—」 | — | 无诊室主数据 | — | — | 正确（如实留空） |
| 导诊单 | 挂号医师 | `data.doctor_name \|\| '—'` | u.real_name（r.doctor_id） | outp_registration→sys_user | u.title 未取 | 「—」 | 正确：r.doctor_id=排班医生，仅在为 null 时接诊人回填（DSS:66-70）；栏目名与取值一致 |
| 导诊单 | 接诊医师 | `firstOf(g,'order_doctor_name') \|\| '—'` | du.real_name | outp_order.doctor_id | **outp_emr.doctor_id（病历保存人）未取** | 未开单→「—」 | 口径可疑：取的是"排序后第一条待办行的开单人"；多医生时取决于 order_type 字母序；已接诊但全部办完/作废也印「—」 |
| 导诊单 | 叫号序号 | `data.reg_no` | r.reg_no | — | — | — | 正确（叫号日志同口径）；**已退号（status=CANCELLED）也照印**（§2-⑤） |
| 导诊单 | 序 | `i+1` | — | — | — | — | 正确 |
| 导诊单 | 环节 | `guideStageNames[order_type] ?? r.order_type` | o.order_type | outp_order | — | 未知类型印英文原码 | **会印错（低）**：charge_item.category 不设白名单（V132:55-61 明说），UI 有 MATERIAL（DSV:603），导诊单会印「MATERIAL」等英文码（§2-④） |
| 导诊单 | 项目 / 数量 | `item_name` / `qty unit` | o.* | outp_order | urgent/remark 未印 | — | 正确 |
| 导诊单 | 前往科室 | `exec_dept_name \|\| (DRUG?'药房':'—')` | ed.name | md_charge_item | — | 药品→「药房」，其余「—」 | 口径可疑（默认库医技三类恒「—」，同 §1.2） |
| 导诊单 | 状态 | `guideStatusOf(r)`：CREATED→待缴费；DRUG→待取药；其余→待执行 | o.status | outp_order | **s.status（lis_sample，SQL 已取 sample_status 却没用）；ris_exam.status 未 join** | — | **会印错（中）**：检验已采样/已核收、订单仍是 CHARGED（直到 LIS 发布才 EXECUTED：LabResultListener:88），导诊单仍印「待执行」+前往科室，会让患者回头再采一次（§2-③b）；EXAM 报告已写未审同理（较轻） |
| 导诊单 | 提示语 | 固定文案 | — | — | — | — | 正确 |

---

## 2. 会印错的（必须修）——按严重度排序

**① 处方笺「过敏史」漏取结构化过敏记录，空值印「无」——高**
- 位置：PV:169（`data.allergy_history || '无'`）；SQL 无相应 join：PC:151-161（PC:153 只取 p.allergy_history）。
- 事实：`cdss_patient_allergy`（V152:174-213，status='ACTIVE'，含过敏原名/严重度 MILD..SEVERE/表现）可经 `AllergyRuleService.addPatientAllergy`（:653-667）**独立登记**，V152:171-172/218 明写"与 allergy_history 并存、不回写、永不写 allergy_history"。
  于是一个登记了"青霉素 SEVERE 过敏性休克"结构化记录、但自由文本栏为空的患者，处方笺印「过敏史：无」。反驳者三第二轮认为"空=无是系统约定（登记表单 placeholder『无则留空』）"——这条约定在结构化过敏上线后已被打破。
- 修法：`DOC_HEADER_SQL` 加子查询 `(select string_agg(a.name||'（'||ca.severity||coalesce('，'||ca.manifestation,'')||'）','；') from cdss_patient_allergy ca join cdss_allergen a on a.id=ca.allergen_id where ca.patient_id=p.id and ca.status='ACTIVE') as allergy_structured`；前端把文本与结构化两段拼起来，仅当**两者皆空**才印「无」；文本先 `trim()` 再判空。

**② 诊断用 custom_name 顶替标准名，会丢掉疾病名——中高**
- 位置：PV:404 `const name = s(d.custom_name) || s(d.icd_name)`。
- 事实：医生站「自定义描述」输入框占位符写的是"临床诊断名称描述（**与标准名并存**）"（DSV:208-209），标准名在另一列单独展示（DSV:190-193）；V135 迁移注释也明写 custom_name"并存不替代 icd_name"。第二轮修复处的注释称"与医生站诊断表同口径"，**与医生站实际相反**。
  若医生在自定义描述里只写"伴发热3天""右下肺"这类描述，纸上会印成「伴发热3天(J06.900)」，疾病名消失；若写"病毒性感冒"，则标准名"急性上呼吸道感染"消失。
- 附带：`diag_system`（中医/西医，DSV 诊断表有标签）未取，中西医诊断在纸上混成一串无标识。
- 修法：主名恒为 `icd_name`，`custom_name` 作括注：`前缀 + icd_name + 后缀 +（custom_name）+ (icd_code) + 疑诊标记`；SQL 追加 `diag_system`，出现 TCM 时按「中医：」「西医：」分组。

**③ 年龄：婴幼儿印「0 岁」，基准是打印日——中**
- 位置：PC:197-203（`Period.between(b, BusinessDates.today()).getYears()`）、PC:236、PV:431。
- 事实：《处方管理办法》第十六条要求"新生儿、婴幼儿写日、月龄"；现在 1 岁以内一律「0 岁」。基准用打印当天，补打跨生日则年龄与就诊时不同；生日晚于今天得负数（「-1 岁」）。
- 修法：以 `visit_date`（DOC_HEADER_SQL 已取）为基准，后端返 `age_text`：<28 天→「N 天」，<1 岁→「N 月」，否则「N 岁」；出生日期晚于基准日→null（印「—」）。

**③b 导诊单「状态」对已采样/已核收的检验仍印「待执行」——中**
- 位置：PV:433-436（`guideStatusOf` 只看 `r.status` 与 `r.order_type`）。
- 事实：检验医嘱在采样、核收后仍是 CHARGED，直到 LIS 发布才 EXECUTED（MedTechController.publish → LabResultListener:88）；SQL 已把 `s.status as sample_status` 带进每行（PC:186）却没用。患者采完血拿着导诊单看到「待执行 + 前往科室：检验科」。EXAM 同理（ris_exam 已写报告未审时仍 CHARGED，MedTechController:270，SQL 没 join ris_exam）。
  （反驳者三第二轮把这条判为"略粗不算错"，我判"会印错"：状态列的作用就是告诉患者下一步该做什么。）
- 修法：LAB 且 sample_status ∈ {COLLECTED,RECEIVED} → 「已采样，待出报告」；EXAM 增 `left join ris_exam` 取 status，REPORTED 起印「待出报告」。

**④ 导诊单「环节」列对未知类型印英文原码——低**
- 位置：PV:304（`?? r.order_type`）。
- 事实：charge_item.category 刻意无白名单（V132:55-61），UI 已有 MATERIAL（DSV:603）。
- 修法：`?? '其他'`。

**⑤ 已退号（CANCELLED）的挂号仍能打出「请到上述科室候诊，叫号序号：N」的导诊单——低**
- 位置：PC:152（`r.status as reg_status` 已取）、PC:252-262（导诊分支不校验）；PV:298 没用 reg_status。四种单据此时因订单已被作废而返 4893，只有导诊单会出一张"看起来正常"的空单。
- 事实：`RegistrationService.cancel`（:116-135）把未缴费订单置 CANCELLED、挂号置 CANCELLED。
- 修法：`reg_status='CANCELLED'` 时后端返 4893（"挂号已退"），或前端印「该号已退，不可就诊」。

**⑥ 处方笺「药品金额」丢小数——低（顺手修）**
- 位置：PV:185（`¥{{ g.total }}`）。BigDecimal→JSON number→`12.5`/`10`。
- 修法：`¥{{ Number(g.total).toFixed(2) }}`（全 PrintView 金额同病，收费票据 PV:19/22 也一样，可一并改）。

---

## 3. 口径可疑（不算错，但说明须限定）——共 20 条

1. **诊断/病史印当前值而非开单时值**：`saveEmr` 先删后插（DSS:132-140），补打取最新。1013★ 说明的"诊断与病史摘要的自动带出发生在申请单生成环节"、1016★ 的"申请单生成时自动带出病史摘要与临床诊断"**与实现不符**——是**打印时**现取，outp_order 上没有诊断/病史快照。
2. **"门诊号"= 患者主索引号**（p.patient_no），不是就诊号；住院单据同字段叫「患者号」。别写"门诊就诊号"。
3. **科室 = 挂号科室**，不随开单医师所属科室变。
4. **开具/申请/开单日期 = 挂号排班日 visit_date**，`o.created_at` 已取未用；冻结业务日的演示库里两者会不同。
5. **药品名 = md_drug.name 快照**，md_drug 不区分商品名/通用名，`generic_name`（V153）可空且未取；不得写"按通用名开具/印通用名"。
6. **抗菌药标**只看 `md_drug.antibiotic`（导入列），处方权闸与指标用 `abx_level`，两列可背离；不印分级。不得写"按抗菌药分级标注"。
7. **皮试**：没印；且无药品级皮试标志，outp_skin_test 只能按 drug_name 文本近似匹配。不得写"含皮试标注/皮试结果"。
8. **药师审核**栏永远手填，review_status/reviewer_id 已在 outp_order 上却未取；不得写"带审方结果"。
9. **费别**（insurance_type）、自费药标（md_drug.self_pay）未印；不得写"符合处方前记全部项目"。
10. **执行科室/前往科室**：交付默认库恒为「—」且产品内无维护入口（rebut_1026_r2_C M2、rebut_r2_B 保留项②）；且实时取自主数据。2092/2192/2252 三条说明"导诊单列出……前往科室与缴费、执行状态"需带限定，且"执行状态"在检验采样后不更新（§2-③b）。
11. **导诊单「接诊医师」**=按 order_type 字母序第一条待办行的开单人（M3）；更贴切的来源是 outp_emr.doctor_id。
12. **检查申请单「检查部位」栏读的是 sampling_site（采样部位）**，UI 的检查行无此输入，恒为手填栏。
13. **检验申请单**：替检标识（lis_sample.substitute/substitute_name，V37）未印；未缴费项目也印「待采集」。
14. **病史摘要**：整段现病史（≤2000 字）连同「【结构化记录】…【结构化记录结束】」标记原样印在一行里；既往史未取。
15. **1013★「加急……醒目印出」**：实现是字重加粗的文字标记（PV:577），黑白可见但不是"醒目/红戳"，措辞宜改"加粗标注"。
16. **打印时间**取浏览器本地钟、页面挂载时取值，与 date.ts 的业务时区约定不一致。
17. **多张纸顺序**：group_no 字符串排序（序号未补零）。
18. **导诊单不印加急/备注**；治疗单/导诊单/检验单/检查单无过敏史栏（仅处方笺有）。
19. **frequency 印 UI 原码**（tid/q8h/prn）。
20. **firstOf 的正确性依赖"非药品一行一个 group_no"**，无库表约束保证；说明里不要写成系统保证。

## 4. 对 1026 现说明（技术偏离表-v3.csv 第 1026 行）的逐词核对

- 现说明逐字：「本平台已提供挂号凭条、收费票据、检验报告单、住院费用清单与出院记录等打印能力；本条所述处方笺、检验申请单、检查单、治疗单、导诊单五类单据的打印，当前版本尚未提供，列入后续开发计划。」（结论：部分响应）
  → **整行失实低报**，须整体重写，不是局部修补。第三轮该评的措辞在 `audit_1026_r2.md` 的"建议的说明改写要点"，尚未落 CSV。
- 改写时与本自查口径冲突的词（避免出现）：
  - 「含临床诊断」——不限定"打印时取本次就诊当前诊断"则与 §3-1 冲突；修 §2-② 之前不要写"含前缀/后缀/自定义名称"。
  - 「过敏史」——修 §2-① 之前不要写"处方笺带患者过敏史"（只带自由文本栏）。
  - 「通用名」「抗菌药分级」「皮试」「药师审核结果」「费别」——本版均未印或不全。
  - 「导诊单指引前往科室 / 执行状态」——默认库为空且采样后不更新。
  - 「加急醒目印出」（1013★）——改"加粗标注"。
  - 「自动带出发生在申请单生成环节」（1013★/1016★）——改"打印时取本次就诊当前诊断与病历"。
  - 「年龄」——不要写"实足年龄/含月龄"，除非先修 §2-③。
  - 「打印中心完备 / 补打留痕 / 条码图形 / 检查报告单可打印」——沿用上两轮结论，仍不得出现。

## 结论：**先修再发**

理由：会印错 6 条里有 2 条落在法定必印栏（过敏史、临床诊断），且与第一轮"作废行印成有效行"、第二轮"疑诊印成确诊"同性质——纸上格子装了不对的数据；第三轮"数据口径"镜头几乎必然抓到 §2-①/②。
最低修复集：§2-①②③（后端 SQL 三处 + PV 三处，量都很小）；§2-③b④⑤⑥ 为顺手修，不修也须在说明里限定。修完补 `V43PrintDocsTest` 用例：结构化过敏+空文本、custom_name 与 icd_name 并存、1 岁内年龄文本、已采样检验的导诊状态、已退号导诊单。
