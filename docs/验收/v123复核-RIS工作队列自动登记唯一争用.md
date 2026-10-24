# v123 复核：RIS工作队列自动登记唯一争用（验收中）

v1.8.39/v1.8.40/v1.8.41与main已原子推送并回读核验。基线v1.8.41 9c2f52f，本轮独立codex/v123-ris-worklist-register，完整验收后只本地定版，不自动推送新轮或代负责人签认。

## 实现及原契约

risWorklist原INSERT SELECT NOT EXISTS后仅增加on conflict(order_id) do nothing。目标明确，保留原事务中的两条SQL：登记等待结束后，下一条SELECT取得当前READ COMMITTED的新快照，不合并成CTE或在aborted事务捕获后继续。已有E从不DO UPDATE，原id、模态、状态、到检、正文、签名/时间及危急字段保持；序列允许跳号。

原ADMIN/TECHNICIAN/DOCTOR_OUTP/NURSE四岗跨科、真实员工JWT边界、EXAM/CHARGED候选、四模态优先序、先全部登记再精确modality筛选及空字符串筛空保持。原14键、患者R归属、主诊优先/诊断id拼接、临床摘要/目的/注意/加急、E.id升序、非VERIFIED可见与旧源状态回显不改。不新增迁移/菜单/配置/响应键/错误码，不把其他队列读取政策或退款交错顺手混入。

## 纯旧实现与独立审阅

独立纯测试8764f75，在hip_codex_v123_red2_20261024的UTC全模块旧实现38项：4个AssertionFailedError、34正向、0运行错误/跳过。四岗竞争先真实事务插入未提交E，实际JWT请求阻塞于INSERT，观测wait_event_type=Lock且pg_blocking_pids包含指定持有者后才提交。旧响应是HTTP200/code4090，底层23505，非裸HTTP500；先核胜出E全列及其余临床账保持，再断言应code0。五次实际等待涵盖四种提交winner状态及持有者回滚后的接管。独立部分唯一索引故障仍4090/23505、整批零E、移除后原样再试，防止广义DO NOTHING吞其他唯一错误。

首跑五个监测失败来自持有者事务内pg_stat_activity统计快照缓存，不计产品缺陷；work/attempt1在独立测试树保留。每轮监测调用pg_stat_clear_snapshot后另建red2纯旧重跑，红/正向与SQLState分类证据保留work/v123-red.*。没有外层测试事务包住HTTP，也未用仅启动多线程冒充真实等待。

终审生产43d3383无本轮范围内已确认P1/P2或原契约退化。公共脚本只宣称八个客户端HTTP生命周期实际重叠、四岗同队列及一O一E；不借此冒称PG锁竞争。私有浏览器首次GET只准新增fresh O的一条精确14列默认E，动态分配仅正生成id和有界created_at，不能任意放宽exams整表变化。

## 验收状态

全量、新库完整原序CI、独立包、实际浏览器与完整回账待全部通过后回填。启动和脚本造数不计浏览器验收。

## 后续边界

队列其他来源/读取政策、退款/源写交错、多O候选反序（未复现，不能称死锁缺陷已修）、其他登记入口、报告快照、历史坏账、ORU整报文、CDR全局锁序、跨标签及正式部署/签认保持专项。
