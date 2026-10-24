# v122 复核：RIS近期检查读取来源资格

基线本地v1.8.40 486f0fd，本轮codex/v122-ris-recent-read；v1.8.39/v1.8.40与本轮均未推送；完整验收通过后本地定版v1.8.41，main快进及annotated标签随定版提交核验，不代负责人签认或部署生产。

## 实现与原契约

recentExams原单条SELECT补源O.order_type=EXAM及O.status为CHARGED/EXECUTED，与门户EXAM和报告外链发布资格一致。来源过滤先于原verified_at降序及20条限额，防旧坏行占满合格名额；不增加锁或业务写。

原ADMIN/TECHNICIAN/DOCTOR_OUTP/NURSE四岗、跨科全院协作与真实R患者归属保持，不追加个人、收费单状态或样本门槛。原id/item_name/modality/impression/verified_at五字段、精确项目名和缺省/空白不筛、ris.mutual.days默认及strict >窗口、未来时间原语义、空值、重复项目和JDBC/HTTP各自时间精度保持。未命中依旧code0空列表，匿名/停用401/1000，错域403/1005不变。

前端搜索recent-exams/recentExams零命中，纠正了原javadoc称已在开单/登记弹出提示的描述。公共HTTP脚本为消费方；未新增前端产品入口，不把浏览器原RIS流程兼容走查冒称为近期互认UI。

## 纯旧实现与审阅

独立v122-tests纯测试提交0dc310f，专用新库hip_codex_v122_red_20261024的UTC全模块旧生产38项中9个AssertionFailedError/29正向/0运行错误/0跳过。9红为四个无效来源状态、三个非EXAM类型、真实提交后来源失效及失效新行挤占20条限额；REFUNDED是显式历史任意字符串，不冒称当前合法状态流。原四岗跨科/跨患者、键及时间、空值/名称/窗口/排序、回滚和认证链均正向；配置每例恢复/清缓存，无外层事务。

独立终审生产add32f0未发现本轮范围内已确认P1/P2或兼容退化。精确窗口边界只在局部事务固定now后回滚，HTTP真实JWT和原毫秒口径分开验证；提交/回滚读取不冒称锁竞争。公共E2E真实两员工报告审核，未付单最后创建避免后续结算并账，已执行退款仍5005。历史错类/失效只由私有夹具分配，公共脚本不SQL造数。

## 验收状态

全新hip_codex_v122_junit_final_20261024的UTC全模块clean package、完整前端/类型/Vite及五门禁通过；全新hip_codex_v122_ci2_20261024按workflow原prep和全部E2E/demo原顺序完整通过，无失败后跳过或续跑。新增38项修后全绿。总数以规划顶节/README及work/v122-final-verification.json为准。

首跑浏览器及包内造数因INTERFACE变岗后越权GET /auth/me而403，属于测试身份取证假设错误，不计产品RED。052f9a6改用管理员PUT返回的真实机器DTO取证，保留重新登录与wrong-domain拒绝；生产权限未改。首跑CI在v116停止、未计完整，原日志保留work/attempt1；随后三个全新CI2/browser2/release2库重新准备并完整验收，未续跑旧数据或拼接通过记录。独立复核确认此修补没有放宽断言。

独立hip_codex_v122_release2_20261024实际发布JAR自报1.8.41，与源码及浏览器同build.time 2026-10-24T10:38:14.682Z，包内本轮公共E2E真实JWT完整通过。JAR SHA256 92c7b0f1e1029e5149f1c0e750181460d93d8f4fa1c003c27dee8ab81c9fa529；源码与发布JAR逐条目仅build-info版本戳不同，dist/脚本及ZIP完整核验另存最终证明。

## 实际浏览器兼容、独立HTTP与完整回账

最终dist5394→8627、专用全新hip_codex_v122_browser2_20261024。公共API合法建两个模拟患者、八个原医嘱/PAID收费及RIS，六份由两员工真实报告审核。登记GET的旧自动写先在造数阶段排空，再分配四个历史源变更：CHARGED兼容、CREATED失效、CANCELLED及非EXAM；只允许四个指定O字段、四个O.updated_at及共享R.updated_at改变，写前合法全列、写后观察/预期及同一事务时刻均留证，原charge_id/PAID、签名、时间、正文及CDR保留。不是合法退款已执行报告或报告撤回接口。

与浏览器分开的实际四岗JWT HTTP检查：指定患者仅返回原EXECUTED及历史CHARGED两份，另一患者仅自己一份；三个失效/错类VERIFIED、未审核及未报告不返回。五键/原时间、两个精确项目、空白不筛及未知患者均核验，全部业务列无变化。

实际浏览器正常登录合成技师，RIS原队列仅一份REPORTED草稿及一份REGISTERED申请；打开“修改报告”核原所见/结论，取消、刷新后仍为原两行，最后退出回登录页并关闭自建标签。没有保存、审核、到检或标危急操作。由于前端没有recent-exams调用，此处只证明原RIS交互兼容，不把它等同于近期互认弹窗验收。

44项完整回账通过：患者、R/O/C、RIS完整签名/正文/时间、CDR、库存及执行/检验/病理/告警/配置/金融打印等全列与接受的历史夹具后基线一致，原审计行保持，只新增GET和正常员工登录。截图/AX、work/v122-browser-http-check.json及work/v122-browser-observations.json保存。没有把脚本造数或接口通过当浏览器通过，也不声称报告不可变或真实网络/PG锁竞争。

## 后续边界

worklist自动登记唯一争用须先真实PG复现；队列其他读取、其余对象/岗位政策、报告快照、历史坏账、ORU整报文、CDR全局锁序、跨标签及正式部署/签认保持专项。单条SQL不承诺跨请求实时推送或任意时点撤权。
