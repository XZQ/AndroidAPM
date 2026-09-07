package com.apm.storage

import android.content.ContentValues
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import com.apm.model.ApmPriority
import com.apm.model.toLineProtocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [SQLiteEventStore] 真实 SQLite 行为测试（Robolectric）。
 *
 * 覆盖生产默认存储的关键路径：批量事务写入、缓存计数与水位线淘汰、
 * outbox 读取顺序、成功确认删除、重试计数、过期清理与坏行容忍。
 */
@RunWith(RobolectricTestRunner::class)
class SQLiteEventStoreTest {

    /** Permanent rejection uses the same exact ownership predicate and accounting as successful ACK. */
    @Test
    fun `discard claim cannot delete another owners rows`() {
        store.append(event("rejected"))
        val now = System.currentTimeMillis()
        val old = store.claimPending("old", 1, now, 10L)
        val current = store.claimPending("current", 1, now + 11L, 1_000L)
        assertEquals(old.map { it.id }, current.map { it.id })
        assertEquals(0, store.discardClaim("old", old.map { it.id }))
        assertEquals(1, store.pendingCount())
        assertEquals(1, store.discardClaim("current", current.map { it.id }))
        assertEquals(0, store.pendingCount())
    }

    /** 数据库助手。 */
    private lateinit var dbHelper: EventDbHelper

    /** 被测存储。 */
    private lateinit var store: SQLiteEventStore

    /** 每个用例使用独立的内存级数据库文件。 */
    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        dbHelper = EventDbHelper(context, name = "test-events-${System.nanoTime()}.db")
        store = SQLiteEventStore(dbHelper, maxEvents = TEST_MAX_EVENTS)
    }

    /**
     * 构造测试事件。
     *
     * @param name 事件名
     * @param priority 优先级
     * @param timestamp 时间戳
     * @return 测试事件
     */
    private fun event(name: String, priority: ApmPriority = ApmPriority.NORMAL, timestamp: Long = System.currentTimeMillis()): ApmEvent = ApmEvent(
        module = "test",
        name = name,
        priority = priority,
        timestamp = timestamp
    )

    /** readRecent 应从 payload BLOB 解码渲染出与直接序列化一致的 line protocol。 */
    @Test
    fun `readRecent renders line protocol from payload`() {
        val original = event("golden", timestamp = 1_700_000_000_000L)

        store.append(original)
        val rendered = store.readRecent(1)

        assertEquals(1, rendered.size)
        // golden 断言：读出内容与事件直接序列化完全一致
        assertEquals(original.toLineProtocol(), rendered.single())
    }

    /** appendBatch 应一次写入整批并全部可读。 */
    @Test
    fun `appendBatch persists all events atomically`() {
        val batch = (0 until BATCH_SIZE).map { event("batch_$it") }

        store.appendBatch(batch)

        assertEquals(BATCH_SIZE, store.pendingCount())
    }

    /** 单个超软上限 payload 被隔离，不会毒化同批正常事件。 */
    @Test
    fun `append result isolates oversized event from valid batch peers`() {
        val normal = event("normal")
        val oversized = event("oversized").copy(fields = mapOf("blob" to "x".repeat(LARGE_FIELD_CHARS)))
        val normalPayloadBytes = ApmEventCodec.encode(normal).size
        val budgetStore = SQLiteEventStore(
            EventDbHelper(RuntimeEnvironment.getApplication(), "event-budget-${System.nanoTime()}.db"),
            maxEvents = TEST_MAX_EVENTS,
            maxPayloadBytes = TOTAL_PAYLOAD_TEST_BUDGET_BYTES,
            maxEventPayloadBytes = normalPayloadBytes + EVENT_PAYLOAD_HEADROOM_BYTES
        )
        try {
            val result = budgetStore.appendBatchWithResult(listOf(oversized, normal))

            assertEquals(1, result.acceptedEventCount)
            assertEquals(listOf("oversized"), result.rejectedEvents.map(ApmEvent::name))
            assertEquals(listOf("normal"), budgetStore.readPending(10).map { it.event.name })
        } finally {
            budgetStore.close()
        }
    }

    /** payload 总量超限时按低优先级、旧事件优先淘汰，并返回可观测计数。 */
    @Test
    fun `payload byte budget evicts low priority oldest event`() {
        val lowOld = event("payload", ApmPriority.LOW, timestamp = 1L)
        val lowNew = event("payload", ApmPriority.LOW, timestamp = 2L)
        val critical = event("payload", ApmPriority.CRITICAL, timestamp = 3L)
        val retainedBudget = ApmEventCodec.encode(lowNew).size.toLong() +
            ApmEventCodec.encode(critical).size.toLong()
        val largestEventBytes = maxOf(
            ApmEventCodec.encode(lowOld).size,
            ApmEventCodec.encode(critical).size
        )
        val budgetStore = SQLiteEventStore(
            EventDbHelper(RuntimeEnvironment.getApplication(), "total-budget-${System.nanoTime()}.db"),
            maxEvents = TEST_MAX_EVENTS,
            maxPayloadBytes = retainedBudget,
            maxEventPayloadBytes = largestEventBytes
        )
        try {
            budgetStore.appendBatch(listOf(lowOld, lowNew))
            val result = budgetStore.appendWithResult(critical)

            assertEquals(1, result.capacityEvictedEventCount)
            assertEquals(mapOf(ApmPriority.LOW to 1), result.capacityEvictedPriorityCounts)
            val retained = budgetStore.readPending(10).map { it.event }
            assertEquals(listOf(critical, lowNew), retained)
        } finally {
            budgetStore.close()
        }
    }

    /** 大规模容量回收必须分块删除，不能超过 SQLite 绑定变量上限。 */
    @Test
    fun `capacity trim chunks large deletion sets`() {
        val capacityStore = SQLiteEventStore(
            EventDbHelper(RuntimeEnvironment.getApplication(), "chunked-trim-${System.nanoTime()}.db"),
            maxEvents = CHUNKED_TRIM_RETAINED_EVENTS
        )
        try {
            val result = capacityStore.appendBatchWithResult(
                (0 until CHUNKED_TRIM_INPUT_EVENTS).map { index ->
                    event("chunk-$index", ApmPriority.LOW, timestamp = index.toLong())
                }
            )

            assertEquals(
                CHUNKED_TRIM_INPUT_EVENTS - CHUNKED_TRIM_RETAINED_EVENTS,
                result.capacityEvictedEventCount
            )
            assertEquals(CHUNKED_TRIM_RETAINED_EVENTS, capacityStore.pendingCount())
        } finally {
            capacityStore.close()
        }
    }

    /** Duplicate event IDs are ignored without corrupting the capacity counter. */
    @Test
    fun `duplicate event identity does not trigger false trimming`() {
        val duplicate = event("duplicate", timestamp = 1L).copy(eventId = "same-event")
        val smallStore = SQLiteEventStore(
            EventDbHelper(RuntimeEnvironment.getApplication(), "dedupe-${System.nanoTime()}.db"),
            maxEvents = 2
        )
        try {
            smallStore.appendBatch(listOf(duplicate, duplicate))
            smallStore.append(event("unique", timestamp = 2L).copy(eventId = "unique-event"))

            assertEquals(2, smallStore.pendingCount())
            assertEquals(setOf("same-event", "unique-event"), smallStore.readPending(2).map { it.event.eventId }.toSet())
        } finally {
            smallStore.close()
        }
    }

    /** 超过容量时按 priority ASC + timestamp ASC 淘汰低优先级旧事件。 */
    @Test
    fun `trim evicts low priority old events at capacity`() {
        // 先塞满容量的 LOW 事件
        store.appendBatch(
            (0 until TEST_MAX_EVENTS).map {
                event("low_$it", priority = ApmPriority.LOW, timestamp = it.toLong())
            }
        )
        // 再写入一条 CRITICAL，触发淘汰
        store.append(event("critical", priority = ApmPriority.CRITICAL))

        // 总数回落到容量内
        assertTrue(store.pendingCount() <= TEST_MAX_EVENTS)
        // CRITICAL 事件应存活（淘汰的是低优先级旧行）
        val pending = store.readPending(1)
        assertEquals("critical", pending.single().event.name)
    }

    /** readPending 按优先级降序、时间升序返回。 */
    @Test
    fun `readPending orders by priority desc then timestamp asc`() {
        store.append(event("low", priority = ApmPriority.LOW, timestamp = 1L))
        store.append(event("critical_new", priority = ApmPriority.CRITICAL, timestamp = 2L))
        store.append(event("critical_old", priority = ApmPriority.CRITICAL, timestamp = 1L))

        val pending = store.readPending(3)

        assertEquals(listOf("critical_old", "critical_new", "low"), pending.map { it.event.name })
    }

    /** deletePending 确认删除后计数下降。 */
    @Test
    fun `deletePending acknowledges uploaded rows`() {
        store.appendBatch(listOf(event("a"), event("b")))
        val ids = store.readPending(2).map { it.id }

        val deleted = store.deletePending(ids)

        assertEquals(2, deleted)
        assertEquals(0, store.pendingCount())
    }

    /** markRetry 递增重试计数。 */
    @Test
    fun `markRetry increments retry counters`() {
        store.append(event("retry_me"))
        val id = store.readPending(1).single().id

        store.markRetry(listOf(id))
        store.markRetry(listOf(id))

        assertEquals(2, store.readPending(1).single().retryCount)
    }

    /** Active leases exclude other owners until expiry. */
    @Test
    fun `claim excludes another owner and expiry reclaims row`() {
        store.append(event("leased"))

        val first = store.claimPending("worker-a", 10, nowMs = 1_000L, leaseDurationMs = 500L)
        val blocked = store.claimPending("worker-b", 10, nowMs = 1_100L, leaseDurationMs = 500L)
        val reclaimed = store.claimPending("worker-b", 10, nowMs = 1_501L, leaseDurationMs = 500L)

        assertEquals(1, first.size)
        assertTrue(blocked.isEmpty())
        assertEquals(first.single().id, reclaimed.single().id)
    }

    /** Lease expiry arithmetic saturates instead of wrapping into an already-expired value. */
    @Test
    fun `lease expiry saturates at long max`() {
        store.append(event("overflow"))
        val claimed = store.claimPending(
            "worker-a",
            1,
            Long.MAX_VALUE - LEASE_OVERFLOW_DELTA_MS,
            LEASE_DURATION_MS
        )

        val reclaimed = store.claimPending("worker-b", 1, Long.MAX_VALUE, LEASE_DURATION_MS)

        assertEquals(claimed.single().id, reclaimed.single().id)
    }

    /** An owner cannot acknowledge rows leased by another worker. */
    @Test
    fun `owner mismatch cannot acknowledge claim`() {
        store.append(event("owned"))
        val id = store.claimPending("worker-a", 1, nowMs = 1_000L, leaseDurationMs = 500L).single().id

        assertEquals(0, store.acknowledgeClaim("worker-b", listOf(id)))
        assertEquals(1, store.pendingCount())
        assertEquals(1, store.acknowledgeClaim("worker-a", listOf(id)))
        assertEquals(0, store.pendingCount())
    }

    /** Failed claims increment retry count and become immediately available. */
    @Test
    fun `failed claim increments retry and releases ownership`() {
        store.append(event("failed"))
        val id = store.claimPending("worker-a", 1, nowMs = 1_000L, leaseDurationMs = 500L).single().id

        store.failClaim("worker-a", listOf(id))

        val retried = store.claimPending("worker-b", 1, nowMs = 1_001L, leaseDurationMs = 500L).single()
        assertEquals(1, retried.retryCount)
    }

    /** Explicit owner release returns every active claim to the available set. */
    @Test
    fun `release claims clears owner leases`() {
        store.appendBatch(listOf(event("one"), event("two")))
        assertEquals(2, store.claimPending("worker-a", 2, 1_000L, 500L).size)

        assertEquals(2, store.releaseClaims("worker-a"))
        assertEquals(2, store.claimPending("worker-b", 2, 1_001L, 500L).size)
    }

    /** Event identities stored in SQLite survive claim retries. */
    @Test
    fun `claim preserves event identity`() {
        store.append(event("identity").copy(eventId = "event-db"))

        val claimed = store.claimPending("worker-a", 1, 1_000L, 500L).single()

        assertEquals("event-db", claimed.event.eventId)
    }

    /** A critical durable row survives closing and reopening the process-local database. */
    @Test
    fun `critical event survives store restart`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "critical-restart-${System.nanoTime()}.db"
        val original = event("crash", priority = ApmPriority.CRITICAL)
            .copy(eventId = "critical-restart", fields = mapOf("exception" to "InjectedFailure"))
        val firstStore = SQLiteEventStore(
            EventDbHelper(context, databaseName),
            maxEvents = TEST_MAX_EVENTS
        )

        firstStore.append(original)
        firstStore.close()

        val reopenedStore = SQLiteEventStore(
            EventDbHelper(context, databaseName),
            maxEvents = TEST_MAX_EVENTS
        )
        try {
            val restored = reopenedStore.readPending(1).single().event
            assertEquals(original.eventId, restored.eventId)
            assertEquals(original.priority, restored.priority)
            assertEquals(original.fields, restored.fields)
        } finally {
            reopenedStore.close()
        }
    }

    /** Two store instances cannot claim the same durable row concurrently. */
    @Test
    fun `concurrent store instances receive disjoint claims`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "claim-race-${System.nanoTime()}.db"
        val firstStore = SQLiteEventStore(EventDbHelper(context, databaseName), maxEvents = TEST_MAX_EVENTS)
        val secondStore = SQLiteEventStore(EventDbHelper(context, databaseName), maxEvents = TEST_MAX_EVENTS)
        firstStore.appendBatch((0 until TEST_MAX_EVENTS).map { event("race-$it", timestamp = it.toLong()) })
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstFuture = executor.submit<List<PendingEvent>> {
                start.await()
                firstStore.claimPending("worker-a", CLAIM_BATCH_SIZE, 1_000L, 500L)
            }
            val secondFuture = executor.submit<List<PendingEvent>> {
                start.await()
                secondStore.claimPending("worker-b", CLAIM_BATCH_SIZE, 1_000L, 500L)
            }
            // Release both claimers only after they are ready to compete.
            start.countDown()
            val firstIds = firstFuture.get().map(PendingEvent::id).toSet()
            val secondIds = secondFuture.get().map(PendingEvent::id).toSet()

            assertTrue(firstIds.intersect(secondIds).isEmpty())
            assertEquals(TEST_MAX_EVENTS, firstIds.size + secondIds.size)
        } finally {
            executor.shutdownNow()
            firstStore.close()
            secondStore.close()
        }
    }

    /** pruneExpired 清除重试耗尽与超龄的行。 */
    @Test
    fun `pruneExpired removes exhausted and aged rows`() {
        // 一条超龄行（时间戳远在过去）
        store.append(event("aged", priority = ApmPriority.LOW, timestamp = 1L))
        // 一条重试耗尽行
        store.append(event("exhausted", priority = ApmPriority.CRITICAL))
        val exhaustedId = store.readPending(2).first { it.event.name == "exhausted" }.id
        repeat(MAX_RETRY_FOR_TEST) { store.markRetry(listOf(exhaustedId)) }
        // 一条健康行
        store.append(event("healthy"))

        val result = store.pruneExpiredWithResult(MAX_RETRY_FOR_TEST, ONE_DAY_MS)

        assertEquals(2, result.prunedEventCount)
        assertEquals(
            mapOf(ApmPriority.LOW to 1, ApmPriority.CRITICAL to 1),
            result.priorityCounts
        )
        assertEquals("healthy", store.readPending(1).single().event.name)
    }

    /** 坏 payload 行在 readPending 时被容忍并自动清除。 */
    @Test
    fun `corrupt payload rows are dropped during readPending`() {
        store.append(event("good"))
        // 直接向表内注入坏 payload 行
        val db = dbHelper.writableDatabase
        val corruptId = db.insert(
            "events",
            null,
            ContentValues().apply {
                put("priority", 1)
                put("module", "test")
                put("name", "corrupt")
                put("severity", "INFO")
                put("data", "")
                put("payload", byteArrayOf(1, 2, 3))
                put("event_id", "corrupt-event")
                put("timestamp", System.currentTimeMillis())
                put("retry_count", 0)
            }
        )
        assertTrue(corruptId > 0L)

        val pending = store.readPending(10)

        // 只有健康行返回，坏行被清除
        assertEquals(listOf("good"), pending.map { it.event.name })
        assertEquals(1, store.pendingCount())
    }

    /** clear 清空后缓存计数归零，后续写入正常。 */
    @Test
    fun `clear resets store and counter`() {
        store.appendBatch(listOf(event("a"), event("b")))
        store.clear()

        assertEquals(0, store.pendingCount())

        store.append(event("after_clear"))
        assertEquals(1, store.pendingCount())
    }

    /** Fatal decoder failures must propagate without classifying a valid durable row as corrupt. */
    @Test
    fun `fatal decoder error preserves durable row`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "fatal-decode-${System.nanoTime()}.db"
        val fatal = OutOfMemoryError("fatal decode")
        val fatalStore = SQLiteEventStore(
            EventDbHelper(context, databaseName),
            TEST_MAX_EVENTS
        ) { throw fatal }
        val verifyingStore = SQLiteEventStore(EventDbHelper(context, databaseName), TEST_MAX_EVENTS)
        try {
            fatalStore.append(event("durable"))

            val actual = assertThrows(OutOfMemoryError::class.java) { fatalStore.readPending(1) }

            assertSame(fatal, actual)
            assertEquals(1, verifyingStore.pendingCount())
        } finally {
            fatalStore.close()
            verifyingStore.close()
        }
    }

    /** A stale process-local cache must not become negative after another store adds rows. */
    @Test
    fun `cross store deletion cannot drive cached count negative`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "count-race-${System.nanoTime()}.db"
        val firstStore = SQLiteEventStore(EventDbHelper(context, databaseName), maxEvents = COUNT_TEST_MAX_EVENTS)
        val secondStore = SQLiteEventStore(EventDbHelper(context, databaseName), maxEvents = COUNT_TEST_MAX_EVENTS)
        try {
            firstStore.append(event("seed"))
            secondStore.appendBatch(listOf(event("external-1"), event("external-2")))
            firstStore.deletePending(firstStore.readPending(10).map(PendingEvent::id))

            firstStore.appendBatch((0 until COUNT_TEST_APPEND_SIZE).map { event("replacement-$it") })

            assertEquals(COUNT_TEST_MAX_EVENTS, firstStore.pendingCount())
        } finally {
            firstStore.close()
            secondStore.close()
        }
    }

    /**
     * 跨实例大 payload 插入后，第一实例的 pendingCount 必须通过行数漂移检测
     * 校准 payload 字节缓存，使下一次写入正确触发字节预算淘汰。
     */
    @Test
    fun `cross instance large inserts heal payload byte cache via pending count`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "byte-heal-${System.nanoTime()}.db"
        // 第一实例：行数宽裕但字节预算低，使字节维度成为唯一会触发的门禁
        val tightStore = SQLiteEventStore(
            EventDbHelper(context, databaseName),
            maxEvents = BYTE_HEAL_MAX_EVENTS,
            maxPayloadBytes = BYTE_HEAL_TIGHT_BYTE_BUDGET,
            maxEventPayloadBytes = BYTE_HEAL_EVENT_SOFT_LIMIT
        )
        // 第二实例：字节预算宽裕，插入后自身不触发淘汰
        val roomyStore = SQLiteEventStore(
            EventDbHelper(context, databaseName),
            maxEvents = BYTE_HEAL_MAX_EVENTS,
            maxPayloadBytes = BYTE_HEAL_ROOMY_BYTE_BUDGET
        )
        try {
            // 初始化第一实例缓存（此时行数与字节均正确）
            tightStore.append(event("seed"))
            val beforeExternal = tightStore.pendingCount()

            // 第二实例写入超过第一实例字节预算的大 payload，行数仍远低于上限
            roomyStore.appendBatch((0 until BYTE_HEAL_LARGE_EVENTS).map { index ->
                event("large-$index").copy(fields = mapOf("blob" to "x".repeat(LARGE_FIELD_CHARS)))
            })

            // 第一实例的 pendingCount 检测到行数漂移，必须顺带校准字节缓存
            val afterHeal = tightStore.pendingCount()
            assertEquals(beforeExternal + BYTE_HEAL_LARGE_EVENTS, afterHeal)

            // 下一次写入应看到真实字节水位并淘汰低优先级旧行
            val appendResult = tightStore.appendBatchWithResult(listOf(event("post-heal")))
            assertTrue(
                "byte budget trim must run after cache healing",
                appendResult.capacityEvictedEventCount > 0
            )
            assertTrue(tightStore.pendingCount() < afterHeal + 1)
        } finally {
            tightStore.close()
            roomyStore.close()
        }
    }

    /**
     * delete + insert can keep COUNT(*) unchanged; the AUTOINCREMENT watermark must still heal
     * the first instance's payload cache before its next capacity decision.
     */
    @Test
    fun `cross instance row replacement heals payload bytes when count is unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "byte-replace-heal-${System.nanoTime()}.db"
        val large = event("large", ApmPriority.LOW, timestamp = 2L).copy(
            fields = mapOf("blob" to "x".repeat(LARGE_FIELD_CHARS))
        )
        val postHeal = event("post-heal", ApmPriority.HIGH, timestamp = 3L)
        val tightBudget = ApmEventCodec.encode(large).size.toLong()
        val tightStore = SQLiteEventStore(
            EventDbHelper(context, databaseName),
            maxEvents = BYTE_HEAL_MAX_EVENTS,
            maxPayloadBytes = tightBudget,
            maxEventPayloadBytes = tightBudget.toInt()
        )
        val roomyStore = SQLiteEventStore(
            EventDbHelper(context, databaseName),
            maxEvents = BYTE_HEAL_MAX_EVENTS,
            maxPayloadBytes = BYTE_HEAL_ROOMY_BYTE_BUDGET
        )
        try {
            tightStore.append(event("small", ApmPriority.LOW, timestamp = 1L))
            assertEquals(1, tightStore.pendingCount())

            val smallId = roomyStore.readPending(1).single().id
            assertEquals(1, roomyStore.deletePending(listOf(smallId)))
            roomyStore.append(large)

            // Row count is still one, so only the persistent insertion watermark proves drift.
            assertEquals(1, tightStore.pendingCount())
            val result = tightStore.appendBatchWithResult(listOf(postHeal))

            assertEquals(1, result.capacityEvictedEventCount)
            assertEquals(mapOf(ApmPriority.LOW to 1), result.capacityEvictedPriorityCounts)
            assertEquals(listOf("post-heal"), tightStore.readPending(10).map { it.event.name })
        } finally {
            tightStore.close()
            roomyStore.close()
        }
    }

    /** Capacity trim must derive its final cache from transaction truth, not a stale baseline. */
    @Test
    fun `capacity trim replaces stale cache with transaction truth`() {
        val capacityStore = SQLiteEventStore(
            EventDbHelper(RuntimeEnvironment.getApplication(), "trim-truth-${System.nanoTime()}.db"),
            maxEvents = 2
        )
        try {
            capacityStore.appendBatch(
                listOf(
                    event("seed-1", timestamp = 1L),
                    event("seed-2", timestamp = 2L)
                )
            )
            val rowCacheField = SQLiteEventStore::class.java.getDeclaredField("cachedRowCount")
            rowCacheField.isAccessible = true
            (rowCacheField.get(capacityStore) as AtomicLong).set(1L)

            val firstTrim = capacityStore.appendBatchWithResult(
                listOf(
                    event("replacement-1", timestamp = 3L),
                    event("replacement-2", timestamp = 4L)
                )
            )
            assertEquals(2, firstTrim.capacityEvictedEventCount)

            // A stale-low result from the first trim would make this append incorrectly skip eviction.
            val secondTrim = capacityStore.appendBatchWithResult(
                listOf(event("replacement-3", timestamp = 5L))
            )
            assertEquals(1, secondTrim.capacityEvictedEventCount)
            assertEquals(2, capacityStore.pendingCount())
        } finally {
            capacityStore.close()
        }
    }

    companion object {
        /** 测试用容量上限。 */
        private const val TEST_MAX_EVENTS = 20

        /** Capacity used to expose stale negative cached counts. */
        private const val COUNT_TEST_MAX_EVENTS = 10

        /** Replacement rows that must trigger one capacity eviction. */
        private const val COUNT_TEST_APPEND_SIZE = 11

        /** Row cap for the byte-cache healing scenario; far above the rows used. */
        private const val BYTE_HEAL_MAX_EVENTS = 100

        /** Tight byte budget only the first instance enforces. */
        private const val BYTE_HEAL_TIGHT_BYTE_BUDGET = 16L * 1024L

        /** Roomy byte budget so the second instance never trims its own inserts. */
        private const val BYTE_HEAL_ROOMY_BYTE_BUDGET = 64L * 1024L * 1024L

        /** Large-payload rows the second instance inserts past the tight budget. */
        private const val BYTE_HEAL_LARGE_EVENTS = 8

        /** Per-event soft limit above one large payload but below the tight byte budget. */
        private const val BYTE_HEAL_EVENT_SOFT_LIMIT = 8 * 1024

        /** Distance from Long.MAX_VALUE used to force saturated lease addition. */
        private const val LEASE_OVERFLOW_DELTA_MS = 5L

        /** Positive lease duration shared by overflow assertions. */
        private const val LEASE_DURATION_MS = 10L

        /** Large field used to cross a deliberately small per-event soft limit. */
        private const val LARGE_FIELD_CHARS = 4_096

        /** Total budget kept comfortably above the small normal test event. */
        private const val TOTAL_PAYLOAD_TEST_BUDGET_BYTES = 1L * 1024L * 1024L

        /** Margin above the normal encoded event while remaining below the oversized event. */
        private const val EVENT_PAYLOAD_HEADROOM_BYTES = 32

        /** Input size forcing capacity deletion to span more than one bind batch. */
        private const val CHUNKED_TRIM_INPUT_EVENTS = 600

        /** Rows retained after the chunked trim regression scenario. */
        private const val CHUNKED_TRIM_RETAINED_EVENTS = 10

        /** 批量写入条数。 */
        private const val BATCH_SIZE = 5

        /** Per-worker claim size used to force overlapping demand. */
        private const val CLAIM_BATCH_SIZE = 12

        /** 测试用重试上限。 */
        private const val MAX_RETRY_FOR_TEST = 3

        /** 一天的毫秒数。 */
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    }
}
