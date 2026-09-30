# 反驳者二（演示可行性）· 1006★ 第二轮

基线：HEAD `6440563` + 工作区未提交改动（`InpDoctorView.vue` `open()` 换患者清空）。全部读码推演，未起服务、未跑浏览器。

## 逐路径推演表（步骤/预期/代码实际/通不通）

| # | 步骤 | 预期 | 代码实际 | 通不通 |
|---|---|---|---|---|
| 1 | 用 **admin** 登录，左侧「住院业务」→「住院医生站」 | 进入页面 | 菜单 V8:88 授给 ADMIN（V8:94）；路由 `router/index.ts:73`；路由守卫按 `auth.user.menus` 命中放行（`index.ts:169-171`） | 通 |
| 1' | 用 **doctor01**（演示库唯一医生账号，`bootstrap-demo.py:65`）登录，找「住院医生站」 | 看得到菜单 | V36 只给 DOCTOR_OUTP 六条门诊类菜单（V36:8-12），全仓无任何迁移把菜单 id 20 / `/inpatient/doctor` 授给 DOCTOR_OUTP（grep sys_role_menu 全部迁移，仅 V8 授 ADMIN、V36 授 NURSE 的 `/inpatient/nurse`）；无「住院医生」角色。手输 URL 被守卫拦：`ElMessage.error('无该功能权限…')` 跳 `/dashboard`（`index.ts:171-176`）。后端类级 `hasAnyRole('ADMIN','DOCTOR_OUTP','NURSE')`（`InpatientController.java:28-29`）其实放行，卡的是菜单 | **不通**（保留项，见结论） |
| 2 | 住院医生站左列选患者 | 在院患者表有人 | `GET /admissions` 零条件返回 `IN_HOSPITAL` 全部（`InpatientController.java:338-340`）。**演示库无在院患者**：`bootstrap-demo.py` 不入院（grep 无 admissions 调用）；V8 只种床位不种入院。需先「住院登记」→「办理入院」选患者/科室/床位（`AdmissionView.vue:138-150`，`admit()` 无其他 gate，`InpatientService.java:64-91`，押金可不填） | 前置一步后通 |
| 3 | 选中患者→默认页签「医嘱」（`tab=ref('orders')` `:428`） | 见开药行与备注行 | 第一行药品/单次量/频次/途径/数量/临时长期/开药/项目/开申请；第二行备注(300px)、注意事项(300px)、加急（`InpDoctorView.vue:49-93`），`flex-wrap` 换行；切换选中时 `open()` 触发 | 通 |
| 4 | 药品下拉输入关键词选药，备注填「术前禁食」，注意事项填「青霉素过敏史」，勾加急，点「开药」 | 提示「医嘱已开立」，列表新行 | `searchDrugs` 是 remote，须键入关键词才有选项（`:790-793`，无预加载）；`addDrug` 发 `...orderExtras()`（`:802`）→ `createOrders` 赋 remark/urgent/notice（`InpatientService.java:168-172`）；成功后 `resetExtras()` + 重新 `open()`（`:806-808`）；列表列显示红色「加急」、备注、红字「注意：…」（`:107-112`） | 通 |
| 5 | 「临时/长期」选「长期」重做 4 | 「长期医嘱已开立」，备注列显示，标签「长期」 | 同 4，`orderNature=LONG`；三字段在 isLong 分支之前设入同一实体（`:168-172` 早于 `:194`） | 通 |
| 6 | 检验/检查/治疗：检索框键入项目名选项，填备注/注意事项，点「开申请」 | 「申请已开立」，类型列验/查/治 | `searchItems` 走 `/masterdata/charge-items`，`orderType: item.category`（`:826`），V132:63 注明种子值仅 LAB/EXAM/TREAT；后端非药品分支同样吃三字段（`:168-172` 在分支前） | 通（注：「开申请」不传 orderNature，只能开临时；长期只能药品） |
| 7 | 换患者不清空 | A 填未开立，切 B，输入框应空 | 工作区未提交改动 `:648` `if (row?.id !== current.value?.id) resetExtras()`；`resetExtras` 声明于 `:423`（先于 `open` 定义，无 TDZ 问题）；同患者刷新（`open(current.value)`）id 相同不清 | 工作区通；**HEAD 不通**（已提交版仍有串位，必须提交并重打包） |
| 8 | 切「护士执行」（admin 或 nurse01） | 页面打开 | nurse01 菜单：V36:19（含 `/inpatient/nurse`，父目录 V36:75 补齐）；路由 `index.ts:74`；`onMounted(load(); loadExecLines())`（`InpNurseView.vue:233`） | 通 |
| 9 | 「护士执行队列（全院未执行医嘱）」看 4/6 的临时医嘱 | 备注/加急/注意事项列 | `GET /orders/pending` 取 status=CREATED，带三字段（`InpatientController.java:663-677`）；列渲染 `InpNurseView.vue:77-85`，无内容显示「—」 | 通 |
| 10 | 「长期医嘱执行行（今日）」看 5 的长期医嘱，**开完立刻切过来** | 见执行行 | `createOrders` 长期分支同事务 `flush` 后立即 `generateExecLines(saved, BusinessDates.today())`（`InpatientService.java:194-197`），频次 bid 默认 2 行/日（`:215`；qd 及 q8h/st 落 default=1 行），无须等 06:30 调度；前端 `todayStr()` 用浏览器本地日期，与服务端 Asia/Shanghai 同日（评委机在中国时区）；`execLines` SQL 带 remark/urgent/notice（`InpatientController.java:66`），列 `InpNurseView.vue:110-117`；点「刷新」或重新进页面即可见 | 通 |
| 11 | 上一步之外：护士队列里同一条长期医嘱 | 审计称队列为"临时医嘱" | 队列查询只按 status=CREATED，**不过滤 orderNature**（`:664`），长期医嘱在首次执行行被执行前也在队列，备注同样显示，但点该行「执行」→ `execute()` 抛 9125「长期医嘱请按每日执行行执行」（`InpatientService.java:323-325`，前端 `execute` `:225-229` 只靠全局拦截器弹错）。属存量行为，非 v74 引入，不影响备注可见 | 通（但评委点错按钮会看到报错；审计 #18 措辞"临时医嘱"不准） |
| 12 | 门诊侧抽核入口 | 入口仍在 | `DoctorStationView.vue:314-315`（药品备注）、`:372-373`（检验/检查/治疗备注列）、`:365`（检查注意事项）均在；`git diff` 无门诊文件改动 | 通 |

## 数据与账号前置

- **账号**：演示脚本建的 doctor01 无法进住院医生站（行 1'，菜单缺失），评委须用 **admin**，或先在「系统管理→角色」给 DOCTOR_OUTP 勾选「住院医生站」（`RolesView.vue` 有菜单树授权，是标准配置动作，非改代码）。护士侧 nurse01 直接可用。培训脚本 doctor01 段（`docs/培训脚本.md:20-25`）只讲门诊，没有给评委住院医生账号。
- **数据**：演示库无在院患者，须先「住院登记」办理入院 1 人（选患者 P00000002 张三或 Test、科室、空床，约 1 分钟，非 20 分钟）。药品/收费项目主数据有种子（V4；LAB/EXAM/TREAT 种子）。无需造其他数据；长期医嘱开完即有当日执行行。
- **已提交版本 vs 工作区**：换患者清空只在工作区，不在 `6440563`；演示包须含该改动。

## 结论 + 一句理由

**不推翻但须标注的保留项。** 参数字面要求的路径（住院医师录入备注/注意事项、护士站可见）在 admin 账号下逐步走通，长期医嘱执行行开立即生成、无需等待；保留项三条：(1) 演示库唯一医生账号 doctor01 没有「住院医生站」菜单，须用 admin 或先授权，且演示前须先办理一名入院（响应说明/演示脚本应写明，不得让评委用 doctor01 硬点）；(2) 换患者清空改动尚在工作区未提交，须提交并重新全量打包后才算数；(3) 护士执行队列同时列出长期医嘱且点「执行」会报 9125，演示时只走执行行表，审计 #18 "临时医嘱"表述应改为"未执行医嘱队列"。
