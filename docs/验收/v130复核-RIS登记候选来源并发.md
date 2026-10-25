# v130 复核：RIS登记候选来源并发

基线v1.8.48，目标本地v1.8.49；进行中，尚未验收定版。只治理自动登记候选在源写等待后失效却仍新增E的问题，原最终读取过滤与退款政策保持。不代负责人签认。

## 纯旧实证与审阅

独立树仅新增V130测试，生产相对v1.8.48为空。首跑9个真实源资格RED外，21个正向因Jackson LongNode/IntNode的测试期望边界不一致失败；数据库全列分配先已通过，这些夹具错误不计产品RED。首跑日志/XML单独保留v130-preflight.*；只修期望经相同JSON序列化/解析，不改字段值、顺序或数据库断言，重建专库完整重跑：39项/9业务红/30正向/0运行错误/0跳过、23组指定holder PG Lock/0监视失败。真实退款提交后旧响应200/code0且空列表，raw E却新增1，故本轮不声称原读取泄漏。

只读审阅排除NO KEY UPDATE方案：独立E holder持O FK KEY SHARE，B若先持NO KEY再等E唯一，holder随后真实退款升级O会成单O环。实际采用升序FOR UPDATE，使B在源O先等待，保留原组合事务断言及ON CONFLICT不覆盖；旧V123/V124/V128观察名称/注释诚实更新为registration-holder，不能继续声称新路径必在unique等锁。T1额外保留两个E胜出后真实退款提交/回滚正向用例，不通过更换fixture遮掩锁环。

审阅另发现旧V128的一个CSV末项仍unique而分支已改registration；仅同步参数名，业务断言不变。第一次全量构建在早期由核对本树路径与maven/surefire命令的自有PID终止，partial日志/XML/argv和Windows cmd强停后仍返回0的marker保留work/build-preflight（accepted=false），不计BUILD SUCCESS或通过。新junit2专库从clean package完整重跑，不拼接首轮。

私有浏览器协议要求同一快照验证A←真实退款B←实际UI登记C的完整阻塞链，不要求C直接列A；现金原回执先落盘，后续时钟/全列分配只能只读恢复，不重放未知结果。worker有界自有Popen/退出proof，七项必需UI观察及服务器侧两次已完成GET的原响应分别核空数组和14键默认E。金融JVM时间与辅助均native Linux按宿主窗口，O/R触发时间按PG窗口，所有旧审计和新三登录/两财务行精确分配。
