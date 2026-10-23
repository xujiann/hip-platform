# v119 复核：RIS 报告分享匿名读取一致性（验收中，未定版）

基线本地 v1.8.37 f4d9832。本轮仅在 codex/v119-report-share 开发，未合并 main、未创建 v1.8.38 tag、未推送。最终定版统计仍以规划顶节和 README 为准；本文件记录当前可验证证据，不代签负责人。

## 修复边界

ReportShareController 三入口统一按真实源医嘱 O→检查 E→短链加锁。分享及吊销必须有实际认证的 ADMIN/TECHNICIAN/DOCTOR_OUTP 岗位和存在启用员工，每次 O/E/share 等待后复核。保留全院协作，护士的既有审核职责不扩大为分享。分享/匿名读只认可源 EXAM + CHARGED/EXECUTED 且 E VERIFIED；历史已退款、撤回或错类报告不吐正文、不计成功访问。吊销无效报告可善后，缺失检查沿原 revoked=0。

匿名先无锁解析 token→E，继而 O→E→share，锁后核原 E→O 及 token/id/E 绑定，旧请求不追随等待中的改绑。有效期从锁后实际数据库 clock_timestamp 计算，读取/吊销同用实际时钟，不用事务开始 now 延长过期链接。拒绝与 SQL 写异常分开验证；写异常穿过事务边界真实回滚，可重试。已有合法72小时/30天、脱敏姓名和六个对外 JSON 正文键保持，LinkedHashMap 容纳历史空字段。原 verifiedAt 因 Map.of 改写首次被 put 扫描器识别，已登记真实外链 JSON/E2E 消费，零新增契约键。

RisView 对审核成功后分享4660回读真实列表，不展示失效 URL，不清掉新打开草稿；原审核成功不被误记失败。两项实际 SFC 测试在纯旧页面断言红，修后绿；既有测试保持。后端纯旧生产54项：33项断言红/21正向/0运行错误，全部失败为 AssertionFailedError；证据独立 v119-tests/work/v119-red.*。只读审阅候选未发现可确认剩余 P1/P2。

## 待完成

全新 hip_codex_v119_junit_final_20261023 的 UTC 全模块 clean package、前端完整测试及类型/Vite、五门禁、全新 hip_codex_v119_ci_20261023 的 workflow 原 prep/全部 E2E/demo 原顺序均通过。完整数量及实际 PG Lock 观察见 work/v119-facts.json，不提前回填定版统计。独立 hip_codex_v119_release_20261023 的实际包自报1.8.38，与源码及浏览器服务同 build.time 2026-10-23T05:34:09.080Z，包内本轮公共JWT从头通过，包括真实一分钟自然过期。已验证发布JAR与全量源码JAR逐条目仅 build-info.properties 的版本戳不同，JAR SHA256 1327b33c24b54059d385ab8336ce64e47ef4a76e8b8b436150c580f4baefa1f0。Windows computer-use 在首次捕获 Chrome 新标签页时，因无法可靠确认当前 URL 而自动安全停止；本轮没有完成实际浏览器操作，没有把脚本访问冒称为浏览器验收。需下轮由用户先手动打开可识别本地 URL 后继续。浏览器夹具/完整回账助手位于 work/v119-browser.py，使用独立模拟数据，不访问真实患者。首轮历史夹具对更新时间的预期遗漏V47/V50触发器，确认为三个源医嘱及相应挂号的六个updated_at列；该轮不算通过。重启调度期间一次提前seed连接被关闭，日志保留为work/v119-browser-seed-initial-failed.log（它不是首次断言失败完整日志）；首次断言错误仅工具回报留存，不虚称已有完整前态。修助手为写前保存合法全列与fixture、写后保存实际/预期，再用同事务返回时刻严格核这六列。已切全新hip_codex_v119_browser2_20261023从头造数成功，browser-legal-seed/history-observed/history-expected/initial及独立audit均保留。八个模拟病例与七链已准备，尚未进行浏览器访问，不把准备成功当实际读取或完整回账通过。

## 后续风险

病理及其他临床读/患者对象、患者门户和打印历史发布状态、病理已发报告不可变快照、ORU/worklist、CDR全局锁序、任意时点撤权和正式生产签认继续交接。只保护请求开始解析后的原绑定；没有新增持久快照，不承诺识别请求开始前发生的人工改绑或撤回已经送达内容。历史已退款 VERIFIED 不自动修账。
