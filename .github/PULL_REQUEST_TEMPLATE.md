## 改了什么

一句话说清动机与影响面，涉及哪个环节（解析 / 切片 / 存储 / 检索 / 生成 / 前端 / 配置）。

## 是否改变既有行为

- [ ] 不改变，纯修复或内部重构
- [ ] 改变，影响面与迁移方式已在下方说明

## 验证

```text
./mvnw test
node --test src/test/js/doc-utils.test.js
```

- [ ] 上面两条通过（贴关键输出）
- [ ] 实机跑过受影响的功能：______________________
- [ ] 未验证的部分与原因：________________________

## 数据库与配置

改动触及 `SqliteSchema`、表结构、FTS / vec0 或环境变量时：

- [ ] 已考虑旧库升级路径，并补了 `DocumentStorePersistenceTest` 用例
- [ ] 已同步 `.env.example` 与 README 的配置表
- [ ] 向量维度、`chunk_vector` 与 `EMBEDDING_DIMENSION` 保持一致

## 备注

回滚方式、已知遗留、需要 reviewer 特别看的地方。
