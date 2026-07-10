# apm-sqlite 模块

> 同步日期：2026-07-10｜模块名：`sqlite`

## 目的与接入

该模块监控宿主业务 SQLite，不是 core 自己的 outbox database。

推荐 wrapper：

```kotlin
val module = SqliteModule()
Apm.register(module)
val db = ApmSQLiteDatabase(helper.writableDatabase, module, "app.db")
```

wrapper 覆盖常用 `rawQuery/query/insert/update/delete/execSQL` 并自动计时。未覆盖操作可访问 `delegate`；也可手动调用 `onSqlExecuted(sql, durationMs, affectedRows, databaseName)`。

## 检测

- slow query
- main-thread database operation
- large affected rows
- 对超过 query-plan threshold 的查询执行 `EXPLAIN QUERY PLAN`
- full table scan
- temporary B-tree

事件：`slow_query`, `main_thread_db`, `large_db_operation`。QueryPlan 分析结果作为字段附加到对应事件。

## 默认配置

| 配置 | 默认 |
|---|---:|
| monitor | 开 |
| slow query | 100ms |
| main-thread DB | 检测 |
| affected rows | 1000 |
| SQL max length | 500 chars |
| stack max | 4000 chars |
| QueryPlan | 开，threshold 50ms |
| full scan/temp B-tree | 检测 |

## 线程与开销

wrapper 在原数据库调用线程测量。QueryPlan 可能额外执行一次 explain，因此只在阈值后触发。事件落盘由 core 异步完成。

## 边界

- 没有 wrapper/手动 callback 就不会自动看到所有 SQLite 操作。
- Room/第三方 ORM 需要在其可扩展点接入。
- SQL 文本可能含隐私/参数，必须在接入和 PII 层控制。
- QueryPlan 只分析可解释语句，异常时降级并记录 internal error。

## 测试

Config、module 分类、QueryPlan 和 wrapper 常用操作有 JVM/Robolectric 测试；真实 Room/并发/大数据库需集成验证。
