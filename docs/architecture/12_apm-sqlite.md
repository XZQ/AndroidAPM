# apm-sqlite 模块

> 同步日期：2026-07-11｜模块名：`sqlite`

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

事件：`slow_query`, `main_thread_db`, `large_db_operation`, `query_plan_issue`。QueryPlan 结构问题独立上报，包含问题类型、表名、截断 SQL 与 planner detail。

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

写操作在原数据库调用边界测量；查询 Cursor 具有惰性，因此 wrapper 在第一次 move/count 时测量真正的首次结果消费，而不是把 Cursor 构造时间误报为查询耗时。QueryPlan 可能额外执行一次 explain，因此只在阈值后触发；EXPLAIN 使用完整原 SQL 和绑定参数，只有事件字段会截断。所有 report 都隔离异常，数据库已完成的结果或原始异常不会被监控覆盖。事件落盘由 core 异步完成。

## 边界

- 没有 wrapper/手动 callback 就不会自动看到所有 SQLite 操作。
- Room/第三方 ORM 需要在其可扩展点接入。
- SQL 文本可能含隐私/参数，必须在接入和 PII 层控制。
- QueryPlan 只在 `ApmSQLiteDatabase.rawQuery` 具备真实数据库句柄、原 SQL 和绑定参数时执行；手动回调与 wrapper 概要 SQL 不伪造 EXPLAIN 能力。
- QueryPlan 只分析 SELECT/CTE 且达到阈值的语句；`SCAN CONSTANT ROW`、`SCAN 2 CONSTANT ROWS`、`SCAN SUBQUERY` 等合成实体不冒充 full-table scan，异常时降级并记录 internal error。

## 测试

Config、module 分类与 QueryPlan gate 有 JVM 测试；真实 SQLite planner、Room/并发/大数据库需 instrumentation/真机验证。

## 时间语义

SQL 执行与 EXPLAIN 开销使用 `ApmClock` 单调时间；事件 timestamp 保持 epoch。这里不改变 durable outbox 的 epoch lease/expiry 协议。
