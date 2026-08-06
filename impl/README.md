# impl/ 实施定制层（L4）

每家医院的真定制代码放这里，规约详见 **docs/adr/ADR-0003-实施定制层规约.md**，
四层模型见 docs/产品化方案.md。速记：

- 复制 `template-hospital/` 为 `impl/<hospital>`，改 artifactId 与包名 `cn.hip.impl.<hospital>`
- 数据库只用 **Flyway V10000+**、只建 `impl_` 前缀表、不动平台表结构
- API 走 `/api/impl/<hospital>/**`，错误码 20000+；前端定制页走 `/impl/*` 路由
- 启用 = 实施仓库根 pom `<modules>` 加入 + server pom 加依赖；**标准仓库根 pom 永不包含 impl 模块**
- 平台升级后先重编译 impl 模块验证兼容，再上线
