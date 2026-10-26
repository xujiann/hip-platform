# v130 复核：RIS登记候选来源并发

基线v1.8.48，目标本地v1.8.49；全部验收通过，本地定版v1.8.49，未推送。只治理自动登记候选在源写等待后失效却仍新增E的问题，原最终读取过滤与退款政策保持。不代负责人签认。

## 纯旧实证与审阅

独立树仅新增V130测试，生产相对v1.8.48为空。首跑9个真实源资格RED外，21个正向因Jackson LongNode/IntNode的测试期望边界不一致失败；数据库全列分配先已通过，这些夹具错误不计产品RED。首跑日志/XML单独保留v130-preflight.*；只修期望经相同JSON序列化/解析，不改字段值、顺序或数据库断言，重建专库完整重跑：39项/9业务红/30正向/0运行错误/0跳过、23组指定holder PG Lock/0监视失败。真实退款提交后旧响应200/code0且空列表，raw E却新增1，故本轮不声称原读取泄漏。

只读审阅排除NO KEY UPDATE方案：独立E holder持O FK KEY SHARE，B若先持NO KEY再等E唯一，holder随后真实退款升级O会成单O环。实际采用升序FOR UPDATE，使B在源O先等待，保留原组合事务断言及ON CONFLICT不覆盖；旧V123/V124/V128观察名称/注释诚实更新为registration-holder，不能继续声称新路径必在unique等锁。T1额外保留两个E胜出后真实退款提交/回滚正向用例，不通过更换fixture遮掩锁环。

审阅另发现旧V128的一个CSV末项仍unique而分支已改registration；仅同步参数名，业务断言不变。第一次全量构建在早期由核对本树路径与maven/surefire命令的自有PID终止，partial日志/XML/argv和Windows cmd强停后仍返回0的marker保留work/build-preflight（accepted=false），不计BUILD SUCCESS或通过。新junit2专库从clean package完整重跑，不拼接首轮。

私有浏览器协议要求同一快照验证A←真实退款B←实际UI登记C的完整阻塞链，不要求C直接列A；现金原回执先落盘，后续时钟/全列分配只能只读恢复，不重放未知结果。worker有界自有Popen/退出proof，七项必需UI观察及服务器侧两次已完成GET的原响应分别核空数组和14键默认E。金融JVM时间与辅助均native Linux按宿主窗口，O/R触发时间按PG窗口，所有旧审计和新三登录/两财务行精确分配。

## 浏览器超时预检与完整重验

首轮browser专库演练手动放锁耗时超过既有Axios 15秒，UI实际超时；虽真实退款已成功且后端随后200空数组/raw E0，不能将后端结果替代成功UI验收。已正常退出/关闭首轮标签，按PID、完整argv及start_ticks核对后仅停止该浏览器后端；原退款回执与数据库保留，未重放已知或未知结果的财务请求。首轮全部证据单独存work/browser-timeout-preflight，reason.json标明accepted_ui=false。

另建browser2空库及独立模拟账号，从正常登录开始完整重验。只读自动监视器先在同一PG快照确认实际UI登记C→真实退款B→O-only holder A的完整阻塞链，随后立即请求持锁者提交；客户端在原15秒内成功显示空态。真实重新收费一次后实际点击刷新，成功显示恰一条默认REGISTERED E。两次实际UI GET原响应、两笔金融原回执、空态/已登记像素与AX、无错误console、正常退出/关闭及最终45项完整回账均为browser2本次证据，不拼接超时轮或改动客户端超时。

## 运行环境重启后的收尾核验

2026-10-26继续收尾时，原工具host会话已不存在，未取得host-exited文件或退出码；清理脚本在发现首个原PID缺失时立即中止，未发终止信号。当前WSL uptime短于所有已登记服务start_ticks所对应的启动时刻，表明已换运行epoch。后验逐一确认登记的AI、source、release、browser2、静态代理五个PID均不存在，原8795/26124/8796/8797/8803/5403端口全部关闭，无完整argv匹配的残留进程，共享PostgreSQL仍可连接，证据work/v130-runtime-reset-proof.json。此处只记录重启后服务不存在，不补造SIGTERM成功、正常退出或退出码；验收结论来自重启前已保存的完整日志、原响应、全列回账及产物哈希。

## 最终验收

2026-10-26本地定版：junit2专用新库UTC全模块clean package，服务端2758项0失败/0错误/2既有跳过，集成6项全绿；本轮39项全绿，纯旧9业务红/30正向/0errors，旧/新各23组指定holder PG Lock。前端580项/类型/Vite及五门禁通过；WSL原生Linux全新CI原prep/原序99套E2E加两演示101/101，独立实际1.8.49同build.time与本轮JWT通过。最终dist实际UI登记与真实退款形成A←B←C阻塞链，退款先完成后空队列/raw E0，真实重收费后UI刷新仅新增一个默认E，45项完整回账通过。无新迁移/菜单/配置/响应键/错误码，仍V184/100键/574码。v1.8.47/v1.8.48及本轮均未推送，见验收/v130复核-RIS登记候选来源并发.md。

实际source/browser/release自报同build.time 2026-10-25T14:08:41.659Z；发布JAR版本1.8.49、SHA256 178a414f6c46eac1c8ecf75ab6d0152ae107ccbe9212451ab2653edd41a2191a，逐entry与源码JAR比对仅build-info.properties版本不同。Windows UTC构建及前端580/最终dist证据保留；HTTP与浏览器后端、私有只读psql及静态代理全部native Linux，从全新ci/release/browser2库开始，原prep/原101命令逐项匹配，无pacing、未知写重试或片段拼接。

浏览器专用模拟已收费EXAM事先无E。只读O-only holderA，真实收费员退款B先在A源锁等待，实际技师点击RIS后的候选INSERT C等待B；释放前在同一PG快照保存B→A、C→B及完整query/PID/Lock。退款原HTTP回执持久化后，代理保存实际UI已完成200/code0/data空数组，UI成功空态且raw E0；所有完整业务列只分配真实退款C字段和O/R状态关联与触发戳。真实重新收费一次持原回执，全量核新C十一列/单号/实际cashier及JVM字段，实际UI刷新一次返回原14键并新增恰一个默认E，完整正文/签名/时间/危急/到检字段均按默认值分配，其余完整临床/财务/员工/配置列保持。

全库原审计七列冻结，新增只有三次已知正常登录及两次真实现金POST，各自actor/method/path/status/IP/PG窗口精确分配；无GET或登录整类豁免。金融JVM时刻等原回执并按native host窗口，O/R同事务触发戳按PG窗口，未混Chrome时钟。holder及refund worker均有界并持原进程身份/退出proof，未知intent不自动重发；回执已存后的分配可只读恢复。真实空态/已登记截图与AX、七项必需UI观察、正常退出/关标签、完整回账保留work/v130-browser-*及browser-*。

强源锁会比原路径更早等待同O的FK-only写入；旧V123/V124/V128保持原业务断言，仅将观察口径改为登记holder等待，不再将所有当前争用称唯一键锁。本轮升序只约束自身候选，不宣称任意组合事务多O或既有CDR隐式O→R全域死锁清零。已有E历史不会删除或覆盖，登记本身不执行O、不新增退款禁令；技术历史读取、报告快照、ORU整报文、历史坏账、其他岗位政策及正式生产签认仍专项。
