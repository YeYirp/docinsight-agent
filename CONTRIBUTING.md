# 参与开发

## 本地跑起来

```bash
java -version          # 需要 JDK 21
./mvnw spring-boot:run # Windows: .\mvnw.cmd spring-boot:run
```

不配任何 Key 即可运行，此时走抽取式回答。需要真实生成时设置 `LLM_API_KEY`，需要向量召回时设置 `EMBEDDING_API_KEY`。
macOS / Linux 还需设置 `SQLITE_VEC_EXTENSION` 指向本机 vec0 动态库（仓库只带 Windows x86_64 的那份）。

## 测试

```bash
./mvnw test
node --test src/test/js/doc-utils.test.js
```

后端测试是真实的端到端链路：起临时 SQLite 库、解析示例文档、入库、检索、走一遍 `/api/ask` 的 SSE 事件。
新增或修改持久化逻辑时，请在 `DocumentStorePersistenceTest` 里补一条能覆盖旧库升级路径的用例——这个文件是本项目防回归的主要屏障。

## 代码约定

- Java 21，不引 Lombok；构造器注入，字段 `final`。
- 注释只写「为什么」：隐藏的约束、微妙的不变式、绕过某个具体缺陷的写法。不写代码本身已经表达的东西。
- SQL：静态查询留在 `DocumentStore` 用 JdbcTemplate；动态拼接的检索读查询放 `mapper/DocumentQueryMapper.xml`，参数一律绑定，禁止把值拼进 SQL 文本。
- 前端是 `static/index.html` 里的单文件 Vue 3 应用（无构建步骤）。`vendor/` 与 `js/` 必须与 HTML 同目录，否则离线预览模式失效。
- 改动界面时请实际启动应用在浏览器里点一遍，类型检查通过不等于功能正确。

## 目录职责

```text
config      数据源、schema 与迁移、vec0 扩展加载、AI 与视频配置
controller  HTTP 接口与统一异常处理
mapper      MyBatis 检索查询
model       领域对象与请求入参
service     解析 → 切片 → 存储 → 检索 → Agent 生成 → 视频转写
resources/python  faster-whisper worker（由主进程以子进程方式拉起）
```

## 提交与 PR

提交信息用约定式前缀（`fix:` / `feat:` / `refactor:` / `docs:`），正文说明动机而不是罗列改动。
一次 PR 只做一件事：行为修复与结构调整分开提交，便于单独回滚。
PR 检查清单见模板，其中「是否改变既有行为」一项必填。

## 数据库变更

`data/docinsight.db` 是用户本地文件，任何 schema 变更都要考虑已有库的升级：新增列走 `SqliteSchema` 里的幂等迁移，不要要求用户删库重建。
向量表 `chunk_vector` 的维度由 `EMBEDDING_DIMENSION` 决定，改维度需要重建索引，请在 PR 里写清楚。
