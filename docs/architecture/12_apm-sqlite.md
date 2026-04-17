# apm-sqlite 模块架构

> SQLite 监控：慢查询 + 主线程 DB + 大数据量 + QueryPlan 分析

---

## 类图

```
┌──────────────────────────────────────────┐
│           SqliteModule                    │
│       (implements ApmModule)              │
├──────────────────────────────────────────┤
│ - config: SqliteConfig                   │
│ - apmContext: ApmContext?                 │
│ - started: Boolean @Volatile             │
├──────────────────────────────────────────┤
│ + onInitialize(context)                  │
│ + onStart() / onStop()                   │
│ + onSqlExecuted(sql, durationMs,         │
│       affectedRows, databaseName)        │
└──────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│         QueryPlanAnalyzer                │
├──────────────────────────────────────────┤
│ - config: SqliteConfig                   │
├──────────────────────────────────────────┤
│ + isAnalyzable(sql): Boolean             │
│ + analyze(db, sql): List<QueryPlanIssue> │
└──────────────────────────────────────────┘

┌──────────────────────────────────┐
│  SqliteConfig (data class)       │
├──────────────────────────────────┤
│ enableSqliteMonitor: true        │
│ slowQueryThresholdMs: 100        │
│ detectMainThreadDb: true         │
│ largeAffectedRowsThreshold: 1000 │
│ maxSqlLength: 500                │
│ enableQueryPlanAnalysis: true    │
│ queryPlanThresholdMs: 50         │
│ enableFullScanDetection: true    │
│ enableTempBTreeDetection: true   │
└──────────────────────────────────┘

┌──────────────────────────────────┐
│  QueryPlanIssue (data class)     │
├──────────────────────────────────┤
│ type: String                     │
│   ├── ISSUE_TYPE_FULL_SCAN       │
│   ├── ISSUE_TYPE_TEMP_BTREE      │
│   └── ISSUE_TYPE_AUTO_INDEX      │
│ table: String                    │
│ detail: String                   │
│ severity: ApmSeverity            │
└──────────────────────────────────┘
```

## 检测流程

```
onSqlExecuted(sql, durationMs, rows, dbName)
       │
       ├── 主线程 DB 操作检测
       │   └── Looper.myLooper() == mainLooper
       │       → emit("main_thread_db", WARN)
       │
       ├── 慢查询检测
       │   └── durationMs >= slowQueryThreshold (100ms)
       │       → emit("slow_query", WARN/ERROR)
       │
       ├── 大数据量检测
       │   └── affectedRows >= largeThreshold (1000)
       │       → emit("large_operation", INFO)
       │
       └── QueryPlan 分析 (可选)
           └── if (enableQueryPlanAnalysis && duration >= 50ms)
               └── QueryPlanAnalyzer.analyze(db, sql)
                   → 检测全表扫描/临时BTree/自动索引
```

## QueryPlan 分析流程

```
QueryPlanAnalyzer.analyze(db, sql)
       │
       ├── if (!isAnalyzable(sql)) → return emptyList
       │   └── 仅分析 SELECT / WITH 语句
       │
       ├── cursor = db.rawQuery("EXPLAIN QUERY PLAN $sql")
       │
       ├── 遍历结果行:
       │   ├── 正则匹配 "SCAN TABLE (\w+)"
       │   │   → 全表扫描问题
       │   │   → QueryPlanIssue(FULL_SCAN, table, ...)
       │   │
       │   ├── 正则匹配 "USE TEMP B-TREE"
       │   │   → 临时 B-Tree 问题 (ORDER BY / GROUP BY 无索引)
       │   │   → QueryPlanIssue(TEMP_BTREE, ...)
       │   │
       │   └── 正则匹配 "AUTOINDEX"
       │       → 自动索引问题
       │       → QueryPlanIssue(AUTO_INDEX, ...)
       │
       └── return issues
```
