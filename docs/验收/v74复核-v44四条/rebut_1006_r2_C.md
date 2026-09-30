# 反驳者三（数据口径）· 1006★ 第二轮

取证基线：HEAD `6440563` + 工作区未提交改动（`InpDoctorView.vue` +3 行，`open()` 换患者清空备注三值）。静态读码；另在草稿目录用 jackson-databind 2.19.2（与 spring-boot 3.5.5 管理版本一致）复刻 record 跑了反序列化实验（未跑 `V74InpOrderRemarkTest`，未启服务）。

## 落库回显链核对表

| 环节 | 核对结果 | 证据 |
|---|---|---|
| 前端 `orderExtras()` | `remark/notice` 空串转 null，`urgent` 恒为布尔（未勾选=false，不会送 null）。纯空白字符串前端不转 null，交后端 `blankToNull` 兜底 | `InpDoctorView.vue:420-422` |
| 请求体键名 | `remark/notice/urgent` 与 `OrderLine` 分量名逐字一致；`addDrug`(:799) 与 `addItem`(:823) 都展开 `...orderExtras()` | `InpDoctorView.vue:799,823`；`InpatientService.java:115-119` |
| Jackson 选构造器（关键） | **实测**：record 有 10 参规范构造器 + 6/7 参兼容构造器且无 `@JsonCreator` 时，Jackson 2.19.2 用规范构造器。旧前端不传三键时得 `remark=null, urgent=null, notice=null`、其余键照常；新前端传 `urgent:false` 得 false；`urgent:""` 得 null 不报错。既有前端调用路径不受影响 | 草稿目录 `jk/T.java` 三行输出；record 定义 `InpatientService.java:112-122` |
| `createOrders` 落库 | `blankToNull(remark/notice)`、`setUrgent(line.urgent())` 三行在药品/非药品分支之前，对四类与 TEMP/LONG 一视同仁；同一次 `save`。不传 urgent 落 **null**（Hibernate 显式插入 null，覆盖 DB `default false`，V171 注释写"default false"与实际写入行为不完全一致，但读方全部按 null=false 处理，不产生显示分歧） | `InpatientService.java:168-172,208-210` |
| `inp_order` 列 | `remark varchar(200)`、`notice varchar(200)`、`urgent boolean default false`，无 not null；实体 `@Column(length=200)` 对齐 | `V171__inp_order_remark.sql:11-13`；`InpOrder.java:80-87` |
| 医生站列表 | `open()` 取 `/workspace`，后者返回 `List<InpOrder>` 实体；键为 `remark/urgent/notice`（单词，无驼峰/下划线歧义）；未见 `@JsonIgnore` 或 NON_NULL 配置，null 键照常输出。列模板读 `row.urgent/row.remark/row.notice` | `InpatientController.java:558-564`；`InpDoctorView.vue:107-112` |
| 护士站待执行队列 | 手工 map，键 `remark/urgent/notice`，`Boolean.TRUE.equals(o.getUrgent())`→ null 读作 false；前端同键 | `InpatientController.java:675-677`；`InpNurseView.vue:77-85` |
| 长期执行行 | SQL 别名 `o.remark, coalesce(o.urgent,false) as urgent, o.notice`；这三个别名本身就是单词，与其他下划线列（`item_name` 等）并存但不冲突；前端 `row.urgent/remark/notice` 命中，**不存在"某张表永远显示 —"的键名错配** | `InpatientController.java:66`；`InpNurseView.vue:110-117` |
| 三处前端列模板 | 医生站、护士站队列、执行行三份模板键名、标签样式（红色 danger 小标签"加急"）、顺序（加急 → 备注 → 红字"注意：…"）完全一致 | 同上三处 |

## 口径问题

1. **长度**：前端 `maxlength=200`（`InpDoctorView.vue:90-91`）；后端不校验。API 直调 201 字：`orderRepo.save`（IDENTITY 主键，立即 insert）抛 `DataIntegrityViolationException`，`InpatientController` 仅 catch `InpException`，落到 `GlobalExceptionHandler.handleIntegrity`（`server/.../GlobalExceptionHandler.java:50-53`），返回 `R.fail(4091,"数据不符合约束要求…")`（HTTP 200，非 500），事务整体回滚（同批多行全部不落）。与门诊 V137 同口径（同为 200、同样无后端校验）。如实记：提示语泛化，未指明是备注超长；不构成数据存错。
2. **加急 null 与 false**：住院医生站列表 `v-if="row.urgent"`、护士队列 `Boolean.TRUE.equals`、执行行 `coalesce(...,false)`，三处对 null/false 处理一致（都不显示标签）。住院 urgent 只显示不排序，门诊亦然，无口径差。仅一处"数据层不一致"：老前端调用落 null、新前端落 false，两者显示等价，属保留记录而非缺陷。
3. **注意事项 vs 备注**：`grep getNotice/getRemark` 在住院模块仅见 `InpatientController.java:66,675-677` 的展示读取，**无任何业务分支、流转、校验、审核、打印差异**——住院侧两者只是两个文本框，仅显示样式不同（注意事项红字带"注意："前缀）。对外可以主张"医师可录入注意事项（药验查治四类均有独立录入框）"，不得主张任何"注意事项触发/提醒/拦截"类功能。
4. **一致性**：医生站列表空值不显示（无内容留白），护士站两表显示"—"。属于展示细节：医生站是"开立者回看自己刚填的"，留白不歧义；护士站是"执行者确认无嘱托"，"—"表示已核对。不影响任何数据事实，不算问题，记为保留观察。
5. **换患者串位（审计材料 #3 末段所述缺陷）**：`open()` 换患者清空三值的修复在**工作区未提交**（`InpDoctorView.vue:646-648`），HEAD `6440563` 不含。逻辑核对：`row?.id !== current.value?.id` 只在换人时重置，同患者开立/停嘱后刷新不清；开药/开申请成功后本来就 `resetExtras()`（:804,827）。修复正确，但上调结论必须以"该 3 行随上调一并提交"为前提；若只按 HEAD 上调，则存在"A 的备注落到 B 医嘱"的数据串位。同类残留（非本条参数）：`drugId/itemId/qty/orderNature` 等选择态同样不随患者重置，这是既有行为，不属备注口径。
6. **类型列**：`{DRUG,LAB,EXAM,TREAT}` 映射，MATERIAL 等其他类别类型列空白，备注仍显示（既有问题，审计材料已列）。
7. **说明措辞**：CSV 1006 末句"住院医嘱……尚未提供"须在上调时改写；住院无医嘱单打印、医嘱检索弹窗与 `/m` 手机端无备注列（审计 #20、#21），说明不得写"住院医嘱备注印出"或"全部界面显示"。属说明约束，不是数据错误。

用例 `V74InpOrderRemarkTest.java` 四条核对：
- 第 1 条（7 参开单三字段 null 且 JSON 有三键）：实断言，且因未配 NON_NULL，`containsKey` 不是恒绿。覆盖实体序列化回显。
- 第 2 条：往返 + 空串/纯空白落 null + `jdbc` 直查列，真覆盖 `blankToNull` 与落库；`lab.getUrgent()==FALSE` 只是把传入值读回，弱但不假。
- 第 3、4 条：调用真实 controller 方法（`pendingOrders()`、`execLines()`），第 4 条含历史行 null→false，有区分度。
- **未覆盖**：① JSON 反序列化到 `OrderLine`（构造器选择——本文已补做实验，用例本身不测）；② `/workspace` 端点（仅序列化实体，未走 controller）；③ 201 字超长；④ 前端换患者清空。无恒绿断言。

## 结论 + 一句理由

**不推翻但须标注的保留项。** 三字段从前端键名、Jackson 反序列化（实测兼容旧调用）、落库（空白→null、null 加急读作 false）到医生站/护士站两表的回显，键名、标签样式与空值语义全链一致，无存错/显错；保留项共三条：（1）换患者清空修复在工作区未提交，上调须连同提交；（2）后端无长度校验，超长回 4091 泛化提示、批次回滚，与门诊同口径；（3）注意事项在住院侧仅是显示样式不同的第二个文本框，说明中不得主张其有额外功能，且不得暗示住院单据印出或全界面显示。
