# v123 复核：RIS工作队列自动登记唯一争用

v1.8.39/v1.8.40/v1.8.41与main已原子推送并回读核验。基线v1.8.41 9c2f52f，本轮独立codex/v123-ris-worklist-register，完整验收后本地定版v1.8.42，main快进及annotated标签随定版提交核验；未推送本轮、部署生产或代负责人签认。

## 实现及原契约

risWorklist原INSERT SELECT NOT EXISTS后仅增加on conflict(order_id) do nothing。目标明确，保留原事务中的两条SQL：登记等待结束后，下一条SELECT取得当前READ COMMITTED的新快照，不合并成CTE或在aborted事务捕获后继续。已有E从不DO UPDATE，原id、模态、状态、到检、正文、签名/时间及危急字段保持；序列允许跳号。

原ADMIN/TECHNICIAN/DOCTOR_OUTP/NURSE四岗跨科、真实员工JWT边界、EXAM/CHARGED候选、四模态优先序、先全部登记再精确modality筛选及空字符串筛空保持。原14键、患者R归属、主诊优先/诊断id拼接、临床摘要/目的/注意/加急、E.id升序、非VERIFIED可见与旧源状态回显不改。不新增迁移/菜单/配置/响应键/错误码，不把其他队列读取政策或退款交错顺手混入。

## 纯旧实现与独立审阅

独立纯测试8764f75，在hip_codex_v123_red2_20261024的UTC全模块旧实现38项：4个AssertionFailedError、34正向、0运行错误/跳过。四岗竞争先真实事务插入未提交E，实际JWT请求阻塞于INSERT，观测wait_event_type=Lock且pg_blocking_pids包含指定持有者后才提交。旧响应是HTTP200/code4090，底层23505，非裸HTTP500；先核胜出E全列及其余临床账保持，再断言应code0。五次实际等待涵盖四种提交winner状态及持有者回滚后的接管。独立部分唯一索引故障仍4090/23505、整批零E、移除后原样再试，防止广义DO NOTHING吞其他唯一错误。

首跑五个监测失败来自持有者事务内pg_stat_activity统计快照缓存，不计产品缺陷；work/attempt1在独立测试树保留。每轮监测调用pg_stat_clear_snapshot后另建red2纯旧重跑，红/正向与SQLState分类证据保留work/v123-red.*。没有外层测试事务包住HTTP，也未用仅启动多线程冒充真实等待。

终审生产43d3383无本轮范围内已确认P1/P2或原契约退化。公共脚本只宣称八个客户端HTTP生命周期实际重叠、四岗同队列及一O一E；不借此冒称PG锁竞争。私有浏览器首次GET只准新增fresh O的一条精确14列默认E，动态分配仅正生成id和有界created_at，不能任意放宽exams整表变化。

## 验收状态

全新hip_codex_v123_junit_final_20261024的UTC全模块clean package、完整前端/类型/Vite及五门禁通过；全新hip_codex_v123_ci_20261024按workflow原prep及全部E2E/demo原序完整通过，无跳过失败或拼接续跑。新增38项修后全绿，五次PG_LOCK、四次真实JWT提交竞争HTTP200/code0；回滚接管、目标外唯一故障整批回滚和重试保持。统计以规划顶节/README及work/v123-final-verification.json为准。

独立hip_codex_v123_release_20261024实际JAR自报1.8.42，与源码及浏览器同build.time 2026-10-24T11:47:00.666Z，包内本轮公共真实JWT完整通过。JAR SHA256 0af4530e6a61f52e0ce798541b50cd4d65561d84366939dfd0f73c77836f9cb9；源码与发布JAR逐条目仅build-info版本戳不同。公共八读者实际记录生命周期重叠，所有响应相同且一O一E、重复读取原E/费用源保持，模态及身份拒绝矩阵通过，不将其冒称确定性PG锁实证。

## 实际浏览器与新增E精确回账

最终dist5395→8647、专用新hip_codex_v123_browser_20261024。公共API合法准备两个模拟患者、同一原R的两笔PAID/两条CHARGED EXAM、已写但未审核的原E，以及另一条已收费但无E的fresh源；最后造源后不再提前GET worklist。正常技师登录并首次进入/ris，实际GET产生一条REGISTERED新E，id为2，队列为原草稿加新登记两行。

首次读取后独立allocate-first逐列构造精确默认14列E，只分配正生成id和readyAt至观察时刻之间的数据库created_at；原order_id、US模态、REGISTERED、空报告/签名/时间、critical_flag=false及其余默认均匹配，所有旧E和其他业务全列完全不变。观察/预期及时间分配保存，非任意允许exams变化，不假定序列无缝。

初次私有allocate-first因Python3.10的fromisoformat不能解析PG五位小数时间而失败，属于辅助解析错误，不计产品缺陷。改用支持0至6位小数的精确strptime后，在同一真实首次登记现场继续只读逐列核验，原readyAt<=created_at<=观察时刻和14列默认断言未放宽，生产/公共E2E及业务夹具未变；独立Python3.10微秒排序检查通过。

再实际打开原“修改报告”核原所见/结论并取消、刷新仍原两行/同一E，退出至登录页并关闭自建标签；没有保存、到检、审核或标危急。38项完整回账通过：患者、R/O/C、E、CDR、检验/病理/执行/告警、库存、配置及金融打印等与已精确接受的首次登记后基线全列相等，原审计保留，仅新增GET和正常登录。截图/AX、browser-registration-allocation.json及work/v123-browser-observations.json保存；实际浏览器没有承担确定性PG竞争证明。

## 后续边界

队列其他来源/读取政策、退款/源写交错、多O候选反序（未复现，不能称死锁缺陷已修）、其他登记入口、报告快照、历史坏账、ORU整报文、CDR全局锁序、跨标签及正式部署/签认保持专项。
