# HIP 医院信息化平台 — 会话开工必读

任何会话（含并行车道会话）动手前，先走 [docs/开发流程.md](docs/开发流程.md) §〇「迷路恢复协议」：
`git log` 前缀定位所处步骤 → 规划文档顶节看当前轮 → `docs/验收/` 最新复核看打回清单 → tag 对照 CHANGELOG 查定版欠账。

## 最小纪律集（详细规则在开发流程与各专门文档）

- **三套编号**：vNN=规划轮、vX.Y.Z=发布版本、VNNN=Flyway 迁移，含义与事实源见开发流程 §一，别混用。
- **提交前缀即状态机**：`vNN 前置` → `vNN 车道X` → `vNN 合并后补齐` → `vNN 审阅修补` → `X.Y.Z vNN`（定版）→ 复核。新工作沿用此前缀体系。
- **定版必须过检查单**（开发流程 §三）：全新库 CI 顺序全量 E2E、`/actuator/info` 验产物、CHANGELOG 与 tag 同落、文档联动。
- 写测试/做验证前读 [docs/测试方法论.md](docs/测试方法论.md)（九条）；发错误码前查 [docs/错误码分段.md](docs/错误码分段.md)。
- 编号（错误码/迁移号/菜单 id/配置键）只用规划节预分配的，车道内不自造。
- 多会话并行是常态：开工前确认无他会话占用同一车道文件与 8080 端口；车道用独立 worktree。

## 构建与环境坑（高频翻车）

- 改多模块必须全量 `mvn clean package`（不带 `-am` 会打进本地仓库旧模块）；旧进程占 jar 时 clean 静默失败，先杀进程。
- 数据库在 WSL Ubuntu 的 PostgreSQL 16：JDBC 用 `127.0.0.1` 不能用 `localhost`；WSL 需保活进程（`wsl -d Ubuntu sleep infinity`）防空闲关机——测试中途连库失败先查这里。
- 本机 Windows HTTP 全序验收在 v128 连续遇到 `WinError 10048`，35ms 限速后仍中断；不要计作业务失败或复用部分结果。可把同一已构建 JAR、原 AI 服务和全部 Python E2E 放在 WSL 原生 Linux 回环，用全新库按原 CI prep/原序完整跑，避开 Windows TCP 客户端；Windows UTC 全量构建、前端和真实浏览器证据各自保留。Linux Java21 已有，AI 依赖与临时文件放项目 `work` 内；服务必须记录 PID/完整 argv/启动 ticks/端口后只清理自己的进程，共享 PG 不动，详见 v128 复核。
- 源码扫描类测试的排除路径一律用仓库相对路径（绝对路径在 worktree 下扫空恒绿）。
- 起后端：`mvn -pl server spring-boot:run`（**不能带 `-am`**，会把 run 跑到父模块上报"找不到 main class"；先 `-pl server -am -DskipTests install` 刷新模块）。本机开发库 `hip` 的 Flyway 152–154 校验和曾是草稿版，2026-10-05 负责人确认后已 `flyway repair`（走 flyway-maven-plugin 11.7.2，未手改 `flyway_schema_history`），起服务不再需要关校验。
