# 反驳者三（数据真实性与口径）

审查对象：`audit_1026.md`（1026★，现结论「部分响应」，审计者拟上调为「符合参数字面」的上调候选）。
镜头：纸上印出来的每个字段，取的是不是**对的数据、对的口径**——不看"有没有这个按钮"，只看"印出来的东西对不对"。

审查基线：**工作区现状**（`git status` 复核，非只审计材料所称的两处未提交）。实际未提交改动共四个 vue 文件 + 一个 CSV：
`PrintView.vue`、`LisView.vue`、`RisView.vue`、`DoctorStationView.vue`、`RegisterView.vue`。审计材料开篇只列了 `LisView.vue`/`DoctorStationView.vue` 两处，**遗漏了 `PrintView.vue` 本身也在工作区被改动**——而 `PrintView.vue` 正是审计材料判定 #15（v44 七字段）"不符合"的核心文件，工作区版本已经就地改写了这条判定所评的对象。本反驳全程按工作区现状核，与审计材料的"14/15 条按 HEAD"不完全同基线，特此标注。

## 一、逐单据取数口径表

| 单据 | 字段 | 来源列 | 口径判断 | 风险 |
|---|---|---|---|---|
| 处方笺 | 用法/剂量/频次/天数 | `outp_order.usage_route/dose_per_time/frequency/days` | 医生开单时自由文本录入，逐字段透传打印，无换算无拼接 | 无——自由文本口径，取值即所见 |
| 处方笺 | 数量/单位 | `outp_order.qty` + `md_drug.unit`（药品主数据） | 单位取自药品字典而非医嘱自填，口径一致 | 无 |
| 处方笺 | **医嘱是否有效** | 无——`PrintReportController.java:260-266` 的非导诊单查询**不带任何 status 过滤**，只按 `order_type`（+ 可选 `groupNo`）取行 | **口径错**：已作废（`CANCELLED`）的药品行与在效行同组同印，见「二、一致性核对」 | **高——见推翻点①** |
| 检验申请单 | 执行科室/标本条码/标本状态 | `md_charge_item.exec_dept_id` → `sys_dept.name`；`lis_sample.barcode/status`（left join，未采样为 null） | 与审计材料 #2a 核对一致，未采样正确回退「（未采样）/待采集」 | 无 |
| 检验申请单 | **医嘱是否有效** | 同上，`DOC_ORDER_SQL` 无 status 过滤，且**该单据版式无任何状态列**（`PrintView.vue:199-219`，"标本状态"列取的是 `lis_sample.status`，不是 `outp_order.status`） | **口径错**：作废的检验医嘱照印，且比处方笺更隐蔽——检验申请单连"已作废"三个字都印不出来（处方笺、检验申请单是唯二完全没有 `outp_order.status` 展示位的两张单） | **高——见推翻点①** |
| 检查申请单/治疗单 | 状态列 | `orderStatusNames[r.status]`（`PrintView.vue:369` 含 `CANCELLED: '已作废'`） | 这两张单**有**状态列，作废行会印但标「已作废」，不构成误导——技师/护士看得到真实状态 | 低（属"该不该印"的完整性问题，非"印错"） |
| 全部五单 | 申请医师/开单医师/接诊医师 | `DOC_HEADER_SQL`（`PrintReportController.java:150-161`）取 `r.doctor_id`→`sys_user.real_name`，即 `outp_registration.doctor_id` | **口径错**：`doctor_id` 在挂号建档时就写死为**排班医生**（`RegistrationService.java:43,86` `reg.setDoctorId(schedule.getDoctorId())`），`startVisit` 只在其为 null 时才回填当前接诊人（`DoctorStationService.java:60-69`），而挂号建档时该列必已非空——正常路径下永不回填。真正"这行医嘱是谁开的"存在 `outp_order.doctor_id`，`DOC_ORDER_SQL` 也确实取了它（别名 `order_doctor_name`，`PrintReportController.java:177`），但**全前端 `PrintView.vue` 从未引用 `order_doctor_name`**（grep 零命中）——取了不用 | **高——见推翻点②** |
| 导诊单 | 待办项目判据 | `o.status in ('CREATED','CHARGED')`（`PrintReportController.java:250-258`） | 退费医嘱回退到 `CREATED`（`OutpOrderRepository.claimRefund` 把 `CHARGED`→`CREATED`），会重新出现在导诊单——**这是对的**：退费后患者确实还没缴费，理应再引导一次 | 无 |
| 导诊单 | 诊断/病史 | 不涉及（导诊单不印诊断） | — | — |
| 诊断（除导诊单外四单） | 主诊断/全部诊断 | `outp_diagnosis` 全表按 `primary_diag desc, id` 取出，前端 `diagText` **把全部诊断用「；」拼接打印**，非只取主诊断 | 符合门诊处方笺"临床诊断"惯例（可列多条），主诊断排首，口径合理 | 无 |

## 二、一致性核对

**核心矛盾点：作废医嘱在"已开医嘱"页面、打印页两处的表现不一致，且打印页内部两种单据的表现也不一致。**

- 医生站「已开医嘱」表：作废按钮 `v-if="row.status === 'CREATED'"`（可作废），作废后 `cancelOrder` 把 `outp_order.status` 置 `CANCELLED`（`DoctorStationService.java:1130-1138`，仅未收费可作废）。该页面本身的状态列会如实显示"已作废"。
- 打印页（处方笺/检验申请单）：**同一行**在作废后仍会被 `clinicalDoc` 端点原样查出并打印，且两张单据的版式压根没有状态列可显示"已作废"——纸面上这行和旁边未作废的行**长得一模一样**。
- 复现条件：门诊医生站开一张含两种药品的处方（同一次「开立处方」提交，共享同一 `group_no`）；缴费前对其中一种药品点「作废」；随即点「打印单据▾」→「处方笺」——两行药品原样打印，无删除线、无「已作废」字样、无任何区别标记。检验申请单同理（先提交一次含多项目的申请，再作废其中一项，再打印）。
- 影响：处方笺是《处方管理办法》定义的法定医疗文书，纸面内容须与医嘱当前状态一致；一张已被医生撤回的用药医嘱，印出来和有效医嘱没有区别，药房/患者单看纸面无法判断该行是否仍然有效。

**第二处矛盾：打印页的"开单医师"与"实际开单医师"可能不是同一人，且系统内部对此心知肚明却没接上。**

- 挂号建档（`RegisterView.vue` → `RegistrationService.register/registerFromAppointment`）把 `outp_registration.doctor_id` 写死为**排班表的医生**（患者预约/挂的那个号所属医生）。
- 医生站「接诊队列」按 `DoctorStationController.java:42-81` 的查询逻辑看，**任何 `DOCTOR_OUTP` 角色用户均可对队列中任一挂号点「接诊」**（`mine`/`doctorId` 只是查询过滤参数，不是接诊权限门槛；`startVisit` 内部也没有校验"当前用户是否等于 `reg.doctor_id`"）。
- 换人接诊场景下（代班、会诊转接、原医生临时不在等门诊常见情形），实际开单的医生是 `outp_order.doctor_id`，但打印页头部"申请医师/开单医师/接诊医师"字段全部来自 `outp_registration.doctor_id`（排班医生），**与实际开方人不符**。
- 后端其实已经把正确的值取出来了（`order_doctor_name`），只是前端打印页从未消费——这正是本项目"有函数存在但口径错"的同一种模式（时间窗锚点错的先例）：数据链路存在，接的却是错的那一列。
- 复现条件：A 医生的排班号下，B 医生（同为 `DOCTOR_OUTP`）登录后在接诊队列点该患者「接 诊」并开具处方/申请单；打印任一单据，页头"申请医师"印的是 A（排班医生），而实际下医嘱、该对处方负责的是 B。

## 三、v44 七字段接线核对（remark/urgent/clinical_summary/exam_purpose/notice/specimen_type/sampling_site）

1. **`firstOf` 组内取首个非空值是否安全**：安全。后端注释与代码均确认非药品医嘱（LAB/EXAM/TREAT）在 `createOrders` 里逐行各分配一个独立 `group_no`（`DoctorStationService.java:802-811`，`else` 分支对每一行单独 `nextGroupSeq()`），即"一组=一行"对这三类恒成立；`PrintReportController.java:287` 注释也如实写明这一点。因此"同一张申请单内两行检查目的不同"这一审计材料自己提出的隐患**在当前代码结构下不可能发生**——不是接得巧，是数据模型本身保证了无歧义。此点**不成立**，不支持推翻。
2. **加急是行级还是单级**：行级。`PrintView.vue:209,236` 都是 `r.urgent`（逐行判断），后端虽然额外计算了组级 `g.urgent`（`anyMatch`，`PrintReportController.java:293`）但**前端从未使用这个组级字段**（grep `g.urgent`/`data.urgent` 零命中）。由于非药品单据一组恒一行，行级判断与组级判断在当前数据形态下等价，不构成印错；只是组级字段成了死数据，不算口径问题。
3. **实际接线范围只覆盖了检验申请单、检查申请单两种，处方笺、治疗单完全没接**：
   - 处方笺（`PrintView.vue:171-183`）：完全没有 remark/urgent 的展示位，即便医生在开药时标了「加急」或写了备注（V137 迁移说明书原话举的例子就是"饭后半小时服"这种**药品备注**），处方笺照样一个字不印。
   - 治疗单（`PrintView.vue:262-282`）：同样零接线，四个可选字段（remark/urgent/clinical_summary/notice）全部没有展示位，`fill-line` 手填栏原样保留、无 `v-if` 分支。
   - `CHANGELOG.md:1127` 写的是「**五种**日常单据打印消费新字段：有值印值、无值仍保留手填栏」——按工作区现状核实，**这句话不成立，实际只有 2/5（检验申请单、检查申请单）**。这不是"待完善"的表述模糊，是**对外文档描述与实际实现不一致**，且恰好落在本条参数（1026★）自己范围内的五种单据上。
4. **更深一层：即便印了，医生站界面目前也没有任何输入控件能让医生真正填写这七个字段。** 逐一核对「处方」页签（`DoctorStationView.vue:265-329`，`addRxLine` 只收 药品/单次量/频次/途径/天数/数量）与「检查检验」页签（`:332-356`，`addLabLine` 只收 项目）的表单与提交函数，均**没有**加急勾选框、备注输入框、检查目的/标本类型/采样部位/注意事项/临床摘要的任何输入项；`submitOrders` 提交的 `lines` 对象里同样不带这些键。全仓搜索「加急」「标本类型」「采样部位」「检查目的」「临床摘要」「注意事项」在 `DoctorStationView.vue` 里零命中（唯一"备注"命中是无关的"特殊病种停药"表单）。后端 `OrderLine` record 虽已扩到 14 分量并保留兼容构造器（`DoctorStationService.java:676-687`），但没有任何调用点会真正传入这 7 个新分量——**当前系统里，这 7 列在真实业务流程下恒为 null/false，只有直接打 API（如单测）才能造出非空值**。也就是说，"检验申请单已经在印检查目的" 这件事，在门诊医生站现有 UI 上永远不会被触发；打印页那几行 `v-if="firstOf(...)"` 分支在生产环境下是**死分支**。

## 结论

**推翻。**

理由：本条参数字面要求"拥有处方、检验单、检查单、治疗单、导诊单打印功能"，但字面之下最基本的前提是"打印出来的是对的数据"——本次核查发现两处独立、可复现、落在这五种单据本身范围内的**印错数据**：
① 处方笺与检验申请单对已作废（`CANCELLED`）的医嘱不做任何状态过滤或标记，作废行与有效行同版打印、肉眼无法区分（`PrintReportController.java:260-266` 无 status 过滤，`PrintView.vue:171-183/199-219` 无状态列）；
② 全部五种单据的"申请医师/开单医师/接诊医师"字段取自挂号排班医生（`outp_registration.doctor_id`），而非实际开具该医嘱的医生（`outp_order.doctor_id`，后端已取出 `order_doctor_name` 却从未在打印页消费），换人接诊时打印页会署错医生。
两处都不是审计材料已经如实标注的"设计取舍"（如条码只留位、导诊单诊室为空都有注释说明为何不编造数据），而是**没被意识到的口径错误**——恰好符合本项目"有函数存在但口径错，被反驳者抓出"的既往模式。此外，工作区里作为上调依据的 v44 七字段接线本身也只完成 2/5 单据、且开单界面尚无对应输入控件，CHANGELOG 关于"五种单据消费新字段"的表述在当前工作区下不成立。综上，本条不满足"零推翻才上调"的门槛，应维持现结论或至少不得上调为"正偏离/无偏离"。
