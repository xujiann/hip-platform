# 反驳者三（数据口径）· 1026★ 第二轮

> 基线：HEAD `0325fa2` + 工作区一处未提交改动（`frontend/shell/src/views/PrintView.vue`，导诊单医师栏）。只读源码核对，未起服务、未跑 mvn；未改仓库任何文件。
> 注意：审计材料声称基线是 `b9b4fe9` 且"git status 干净"，与现状不符——导诊单「挂号医师/接诊医师」这处修复**只在工作区，未进任何提交**；`0325fa2` 的 PrintView 里导诊单仍是单栏「接诊医师：{{ data.doctor_name }}」。上调前必须先提交，否则说明写的东西 HEAD 上不成立。

## 第一轮硬伤修复核对

| 硬伤 | 核对结果 | 证据 |
|---|---|---|
| ① 作废行上纸 | **真修**。四种单据 SQL 追加 `o.status <> 'CANCELLED'`；导诊单走 `status in ('CREATED','CHARGED')`，本就不含作废行；今天新增 `o.order_type <> 'REG'`，挂号费行（`RegistrationService` 挂号时按 fee>0 生成 `order_type='REG'`、`item_id=0`）不再冒充"环节"。退费后的行回到 CREATED（`OutpOrderRepository.claimRefund`），照常上纸，口径成立。作废只允许 CREATED（`DoctorStationService.cancelOrder` 4007），过滤覆盖全部作废场景 | `PrintReportController.java:257`（导诊单，含 REG 排除）、`:266`（四种单据）；`V43PrintDocsTest.java:157`、`:251` |
| ② 署名取排班医生 | **四种单据真修**。`docDoctor(g)` = 组内首个非空 `order_doctor_name`，取不到回落页眉 `doctor_name`；`docDoctorSuffix` 仅"无开单人或开单人=页眉医生"时印职称，不会把排班医生的职称安到开单人头上。 | `PrintView.vue:414-421` |
| ②' 多行一组、不同医生混一张单 | **不会发生**。药品 `drugGroupNo` 每次 `createOrders` 调用生成一个（含 `nextGroupSeq()`），一次调用的 `doctorId` 就是登录人（`DoctorStationController:220` `currentUserService.idOf(auth)`）；非药品每行各取一个 `group_no`（`DoctorStationService:805`）。全仓只有该处给门诊医嘱赋 group_no（另一处 `GH-` 是挂号费，被过滤），无追加行进旧组的路径。故 `firstOf` 在四种单据上取到的就是这张单唯一的开单人，无歧义 | `DoctorStationService.java:710,784,805` |
| ②'' 导诊单医师栏（工作区未提交） | 见「口径问题」M3：栏目名与取值已一致（不再说谎），但"接诊医师"取的是**首条待办行的开单人**，多医生时取决于 `order by order_type`（DRUG<EXAM<LAB<TREAT 字母序），不是"接诊人" | `PrintView.vue:296`，`PrintReportController.java:257` |

## 逐单据字段口径核对表

| 字段 | 来源 | 口径核对 | 判定 |
|---|---|---|---|
| 姓名/性别 | `empi_patient` | 性别非 M/F 印「—」 | 无 |
| 年龄 | `ageOf(birth_date)`，按 `BusinessDates.today()` 算 | 无出生日期 → 后端 null → 前端「—」（`PrintView.vue:422`，注释写明不印"—岁"），是诚实的"未知"。但按**打印当日**而非**就诊日**算：补打日与就诊日跨生日则年龄与就诊时不同；婴幼儿 `Period.getYears()` 恒印「0 岁」，儿科处方笺没有月龄。`V43PrintDocsTest.assertHeader` 只 `assertNotNull(age)`，未断言数值，无出生日期分支无用例 | 小项，标注 |
| 门诊号 | `empi_patient.patient_no` | 这是患者主索引号，不是本次门诊的编号；同一字段在住院单据上标「患者号」（`PrintView.vue:116`），在门诊单据上标「门诊号」（`:156`）。标签不统一，数据本身没错 | 小项，标注 |
| 临床诊断 | `outp_diagnosis` 取 `icd_code, icd_name, primary_diag`，主诊断排首，「；」连接（`PrintReportController.java:239`；`PrintView.vue:399`） | **丢字段，见 M1**。诊断整表替换（`saveEmr` 先 delete 再 insert），故印的永远是**当前值**；开单后改诊断，此前已开的处方补打会印新诊断，口径可接受但说明里不能写"开单时诊断" | **M1 推翻项** |
| 过敏史（仅处方笺） | `empi_patient.allergy_history`，空 → 印「无」（`PrintView.vue:169`） | 患者登记表单该栏 placeholder 为「无则留空」（`PatientRegistryView.vue:91`），即系统约定空=无；处方笺印「无」与该约定一致。但库里无法区分"确认无过敏"与"没问过"，「无」是断言。住院单据同一约定（`PrintView.vue:91`），非本轮新引入 | 保留项，标注 |
| 药品金额 | `g.total` = 组内非作废行 `amount` 之和 | 含 CREATED/CHARGED/DISPENSED，不含作废，口径成立。`BigDecimal` 经 JSON 到前端变 number，`¥12.50` 会印成 `¥12.5`、整数印 `¥10`；全 PrintView 的金额都如此（收费票据同），非本轮引入 | 小项，标注 |
| 医师/署名 | 见上 | 四种单据成立；药师/核对/发药/执行人等栏留空供手写，合理 | 无 |
| 检验申请单：标本条码/状态 | `lis_sample`（left join） | `lis_sample.status` 只有 COLLECTED/RECEIVED/PUBLISHED，映射表全覆盖，未采样回退「（未采样）/待采集」正确 | 无 |
| 检验/检查/治疗单：执行科室 | `md_charge_item.exec_dept_id` → `sys_dept.name`；空印「—」 | **数据是"空的"而不是"错的"，但空得很彻底，见 M2**。药品在导诊单上写死回落「药房」（`PrintView.vue:~305`），故只有药品有去处 | **M2 保留项（强）** |
| 状态列 | `orderStatusNames`（检查/治疗单）：CREATED 待缴费 / CHARGED 已缴费 / EXECUTED 已完成 | 与医生站同一状态的叫法不同（医生站：已开立/已收费/已执行，`DoctorStationView.vue:606`）。同一订单两屏两个词，不影响正确性 | 小项，标注 |
| 导诊单状态列 `guideStatusOf` | CREATED→待缴费；药品 CHARGED→待取药；非药 CHARGED→待执行 | 状态机确实只有这几档（`EXECUTED` 由 LIS 发布/RIS 出报告/执行站置位，`LabResultListener:88`、`MedTechController:270`、`ExecStationController:69`），映射与流程一致。**已采样未出报告的检验仍印「待执行」+ 前往科室**（订单仍 CHARGED），采了血的患者看到"待执行"略粗，但不是错——项目确实没做完。药品已发药/已作废不列，正确 | 保留项，标注 |
| 导诊单 REG 行 | 已排除 | 见上 | 已修 |

## 口径问题

**M1（推翻项）诊断的前缀/后缀/确诊·疑诊/自定义描述不上纸，疑诊会印成确诊。**
- 医生站诊断表有「前缀（placeholder 如 疑似）」「后缀（如 术后）」「确诊/疑诊」「自定义描述」四列（`DoctorStationView.vue:182-210`），后端落到 `outp_diagnosis.prefix/suffix/certainty/custom_name` 独立列，**`icd_name` 里不含这些**（V135 迁移注释明写：`icd_name` 仍是唯一展示名，custom_name 不顶掉它；`DoctorStationView.vue:189` 表格里也是 `icdName` 单独显示，前后缀是另外的输入框）。
- 打印取数只 `select icd_code, icd_name, primary_diag`（`PrintReportController.java:239`），前端只拼 `icd_name(icd_code)`（`PrintView.vue:399`）。全仓无任何一处把前后缀/certainty 拼进诊断文本（grep 仅 `DiagnosisVersionService` 留痕使用）。
- 复现：任一 VISITED 就诊，诊断填 J06.900 急性上呼吸道感染，前缀填「疑似」、确诊/疑诊选「疑诊」，后缀填「术后」→ 开一味药 → 打印处方笺/检验申请单/检查申请单/治疗单：临床诊断印「急性上呼吸道感染(J06.900)」，「疑似」「疑诊」「术后」全部消失。法定必印栏（处方笺临床诊断）把医生标注的疑诊印成了确诊，这是"纸上一格装了不对的数据"，与第一轮"作废行印成有效行"同性质。
- 用例：`V43PrintDocsTest.assertHeader` 只 `assertFalse(diagnoses.isEmpty())`，没有任何用例带前缀/certainty 打印；`V44DiagnosisTest` 只验落库读回，不验上纸。故恒绿。
- 修法极小（后端多取三列 + 前端拼 `前缀+名称+后缀`，疑诊补「（疑诊）」），但**现状下说明写"含临床诊断"即不成立**。

**M2（保留项，须标注）执行科室/前往科室在交付默认数据里全是「—」，且产品内无维护入口。**
- 基线种子 13 个收费项目（`V4__masterdata.sql:45-56`、`V21__phase24_specialty.sql:64-66`）的 `exec_dept_id` 全为 `null`。
- 产品内没有任何写入路径：`ChargeItemsView.vue` 无执行科室列/表单项；CSV 导入（`MasterDataController.java:143-150`）不写该列；`PUT /charge-items/{id}/attrs` 只更新 `fee_category_code/self_pay`；全仓 Java/Vue 无 `setExecDeptId`。仅有的写入是 `V43PrintDocsTest.java:81` 与 `V44OrderFieldsTest.java:258,329` 里的裸 SQL——**测试造数掩盖了这一点**，`assertNotNull(exec_dept_name)` 在真实默认库上必红。
- 后果：导诊单的核心信息"前往科室"和三种申请单/治疗单的"执行科室"，在未经 DBA 手工 SQL 的环境里对所有检验/检查/治疗项目都印「—」（本轮实测 肝功能全套 已见）。数据不是错的（诚实的空），所以不构成"印错"；但说明若写"导诊单指引患者前往执行科室"就不实。对外口径必须写"执行科室取自收费项目主数据，需实施期配置"。

**M3（保留项）导诊单「接诊医师」= 首条待办行的开单人。**
- 导诊单 `g.rows` 是整次就诊全部 CREATED/CHARGED 行，按 `order by o.order_type, o.id`。A 开了药、B 开了检验且都未完成时印 A（DRUG 字母序在前）；A 的药取走后再打印则印 B。两位确实都接触过患者，不算"印错人"，但栏目叫"接诊医师"而取值规则是"首条待办行的开单人"，口径说不清。
- 未开单、或所有项目都已完成/作废时印「—」：未开单时"还没有接诊人"合理（导诊单本就多在就诊前打）；但**已接诊且全部做完**时印「—」，同一张纸上「挂号医师」却有名字，略怪，可接受。
- 更合适的数据源是 `outp_emr.doctor_id`（`saveEmr` 写入），未用。
- 该修改**仍在工作区未提交**，且无用例（`V43PrintDocsTest` 的导诊单用例只断言医技行有 exec_dept_name，不涉及医师栏）。

**M4（保留项）条码。** 五张单"条码粘贴处"只印单据号/患者号文字（`PrintView.vue:147-150`，注释亦声明不画条码图形），无 canvas/JsBarcode/二维码。检验申请单的「标本条码」列印的是 `lis_sample.barcode` 文本，已采样才有值。对外说明可以写"打印功能"，不能写"条码打印/扫码"；1013 说明已写"申请单上印出"是指加急，不涉及条码，无冲突。

**M5（保留项）补打/重打留痕不覆盖这五张单。** `V40PrintReprintTest` 只测 CHARGE/REGISTRATION：`logPrint` 白名单仅这两类，其他类型返 4000（`PrintReportController.java:104-106`，用例 `V40PrintReprintTest:97`）；前端 `/print/log` 唯一调用点是 `ChargeView.vue:152`（`docType: 'CHARGE'`），`PrintView.vue` 对临床单据不记日志。决策注释（`:88-100`）明说不写，临床留痕另立 `clin_print_log`（尚无）。说明**不得**写"补打留痕/打印可追溯"。

**其他小项（不成条）：** 页脚「打印时间」取浏览器时钟（`new Date()`），与后端 `BusinessDates`（可冻结的演示业务日）可能不一致；状态词与医生站不统一；婴幼儿年龄。

## 用例覆盖（`V43PrintDocsTest` 9 条）

| 用例 | 覆盖的口径点 | 强度 |
|---|---|---|
| prescriptionCarriesRpLinesAndUsage | 过敏史值、用法用量、group_no 分组、金额非空、groupNo 过滤/4893 | 实断言（过敏史取值真比对） |
| cancelledOrderLineIsExcludedFromPrescription | 作废行不上纸、`order_doctor_name` 非空 | 实断言，**仅处方笺**；检验/检查/治疗靠共用 SQL |
| labRequest… / examRequest… / treatSheet… | 行字段、执行科室非空、病史/体格检查 | 执行科室断言依赖测试内裸 SQL 配置（见 M2），掩盖默认库为空 |
| guideSheetListsAllPendingItemsAcrossTypes | 四类待办、医技行有科室 | 同上 |
| guideSheetExcludesRegistrationFeeRow | REG 行排除 | 实断言，造数与 `RegistrationService` 写法一致 |
| missingDocDataReturns4893 / unsupportedDocTypeReturns4892 | 错误路径 | 实断言 |

**恒绿/空转点：** `assertHeader` 里 `age`/`doctor_name`/`diagnoses` 只验非空不验值；测试里开单医生 = 挂号医生 = `admin`，**没有任何用例让"开单人 ≠ 排班医生"**，故署名修复的核心场景（后端取到的名字、前端回落/职称后缀）零自动化；前端全部取值逻辑（`docDoctor/docDoctorSuffix/guideStatusOf/ageText/过敏史回落`）无测试目录；诊断前缀/疑诊零用例；lab-report 端点无用例。

## 结论 + 一句理由

**推翻**——医生在诊断表填的「疑似/术后/疑诊」不会上纸（`PrintReportController.java:239` 只取 `icd_code, icd_name`，`PrintView.vue:399` 只拼名称与编码），处方笺与三种申请单/治疗单的临床诊断栏会把疑诊印成确诊，属参数所指单据上的数据印错，复现条件见 M1。

附：第一轮两条硬伤（作废行、署名）确已修复且 M1 之外未发现新的"印错"；M2（执行科室默认全空且无维护入口）、M3（导诊单接诊医师口径 + 修复未提交）、M4（无条码图形）、M5（无补打留痕）、过敏史空印「无」，若 M1 修复后重审，均按**不推翻但须标注的保留项**处理，说明须写明：执行科室需实施期配置；条码仅留粘贴位；五类单据无补打留痕；导诊单"接诊医师"为开单医师口径。
