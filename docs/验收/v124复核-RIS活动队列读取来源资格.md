# v124 复核：RIS活动队列读取来源资格

v1.8.42/main已原子推送并回读核验，基线48085e6。本轮codex/v124-ris-worklist-source-read，完整验收后本地定版v1.8.43，main快进及annotated标签随定版提交核验；未推送新轮、部署生产或代负责人签认。

## 实现与明确的行为调整

只在risWorklist最后读取SELECT增加源O.EXAM及CHARGED/EXECUTED，保留E非VERIFIED、四岗跨科协作、14键、真实R患者、诊断顺序/原临床值、E.id升序、模态优先序及精确筛选/空字符串筛空。原自动登记候选、ON CONFLICT(order_id)、两SQL事务完全保持，无新锁/迁移/菜单/配置/响应键/错误码。

v114曾明确保留退款后CREATED行回显，v123也锁定该原行为；本轮有意识改为活动队列过滤，不假称原来就不显示。持久E/正文/作者/时间不删改，实际重新收费可恢复同一E；EXECUTED但未审核的合法报告、历史CHARGED、未知但非VERIFIED的旧E状态保持，不增加C.status、日期或个人/科室门槛。未命中仍code0空列表，旧写守卫和已执行退款5005不变。

旧V114公共退款helper、草稿及四岗三种退款case的[0]期望均改为活动队列空；真实重收后的REGISTERED及后续审核/5005保留。V123旧失效源正向改空但仍核原E全部列与其他账相等。新T1/公共/私有回账承担原草稿保留及重收同E恢复实证。当前操作/培训中的旧回显指引同时更新，历史规划与旧验收不重写。

## 纯旧实现与审阅

独立纯测试aa2cbd6，专用新hip_codex_v124_red_20261024的UTC全模块旧实现41项中16个AssertionFailedError/25正向/0运行错误/跳过，无夹具误报。16红为四无效源状态、三错类、三E阶段的失效源、三次真实HTTP退款后读泄露、提交后失效、混合列表未过滤、实际退款服务提交的唯一等待后第二SELECT仍泄露。

两次指定holder PG_LOCK覆盖实际ChargeService退款组合事务的提交与回滚，监测清统计快照并核实际blocking PID，队列B用真实JWT。提交后旧HTTP200/code0仍带失效E；先核原E全列与真实退款账保持再断言应空。另有真实HTTP退款及重新收费恢复同E，不把组合事务的服务调用冒称HTTP并发。原四岗、14键/模态/真实患者、合法EXECUTED与退款回滚均正向，证据独立树work/v124-red.*保存。

终审6e5a216/a0ebddb/99043fa无本轮范围内已确认P1/P2或有效源兼容退化。公共脚本实际通用执行后未审核E、隔离未付R、真退款/重收与原写守卫；私有辅助先记录真实金融receipt，再精确分配财务与O/R触发列，辅助分配失败只读重验，不能再次发退款/收费。

## 验收状态

全新hip_codex_v124_junit_final_20261024的UTC全模块clean package、完整前端/类型/Vite和五门禁通过。全新hip_codex_v124_ci2_20261024按workflow原prep及全部E2E/demo原序完整通过，没有失败后跳过或拼接续跑。新增41项修后全绿，两次指定holder PG_LOCK通过；统计以规划顶节/README及work/v124-final-verification.json为准。

首跑CI在V119分享脚本仍期望退款后返回旧草稿时失败，未计完整通过；89fa9d5明确同步新活动队列约定，并复查全部RIS worklist工具消费者。日志/结果保留work/ci-attempt1；另建hip_codex_v124_ci2_20261024，从原prep及全部脚本完整重跑，未续跑或拼接首跑成功记录。生产JAR未改，源码全量和独立本轮公共包验收仍为同一产物。

独立hip_codex_v124_release_20261024实际发布JAR自报1.8.43，与源码及浏览器同build.time 2026-10-24T12:35:02.520Z，包内本轮公共真实JWT完整通过。JAR SHA256 f81a703526e0adde67f64ce3a12d62548048b97b94d41114430d335280b1964b，源码与发布JAR逐条目仅build-info版本戳不同。真实通用执行后未审核兼容、四岗/精确模态、合法退款/原写拒绝/重收同E恢复及5005保持。

## 实际浏览器与精确财务回账

最终dist5396→8667，专用新hip_codex_v124_browser_20261024。公共API合法建立原PAID收费、CHARGED EXAM及REPORTED草稿；正常技师登录看一行，初次UI读取与initial完整业务列精确相等。收费员实际API退款，精确核原C只变REFUNDED/refund_by/有界refunded_at，O变CREATED/charge_id=null及O/R同一事务updated_at，其余全列相等，原E全部列不变；实际页面刷新为空，读后全列等于refunded-observed。

再通过原settle API重新收费，旧单仍REFUNDED，新单为PAID且新id 2，旧id 1；新C严格按11列构造（配置前缀+北京日期+id的单号、同R/原O金额、CASH/实际收费员、JVM创建时刻与真实API回执精确相等、空医保与退款字段），O恢复CHARGED/关联新单及O/R事务时间，其余全表完整列不变。实际刷新恢复原检查id 1及原正文，打开“修改报告”核原所见/结论、取消/刷新/退出至登录页并关闭自建标签。没有额外到检、保存、审核或标危急写。

首个重收辅助分配将JVM新C创建时间放入PG时钟区间而误拒：回执createdAt为12:48:53.445683Z，PG操作后时刻为12:48:53.418547+00:00。OutpCharge.createdAt来自JVM Instant.now截微秒，与PG的O/R事务时钟不同。原receipt未改、金融请求未重发；改为新C.created_at与原认证API回执精确微秒相等，O/R仍受原PG窗口约束，其余字段及金额、单号、员工和全账约束不放宽，再只读allocate-recharge通过。独立复核确认，最终还核旧C退款时刻与原API回执精确相等。不把该辅助时钟错误计产品缺陷。

41项完整回账通过：原E与患者、R/O/C、CDR、检验/病理/执行/告警、库存、配置/退款审批/金融打印等与接受的真实重收后基线全列一致，原审计保持，只允许恰好各一次原收费员refund/settle POST及GET/正常登录。每项金融API先保存receipt，再只读精确allocate，不因辅助断言失败而重复提交付款。观察/预期、交易时窗、截图/AX和work/v124-browser-observations.json保存；无SQL历史变更，不把辅助脚本或服务组合事务冒称浏览器PG并发。

## 后续边界

候选INSERT和退款/源写交错的潜在残留E写入、其他临床读取/岗位、多O反序（未复现）、LIS/病理实物不同政策、不可变报告、ORU整报文、历史坏账、CDR全局锁序及正式签认继续专项。只裁决本条读取SQL的MVCC快照，不承诺任意时点撤权或收回已送达正文。
