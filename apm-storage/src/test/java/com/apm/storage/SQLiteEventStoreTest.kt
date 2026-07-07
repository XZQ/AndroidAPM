package com.apm.storage

import android.content.ContentValues
import com.apm.model.ApmEvent
import com.apm.model.ApmPriority
import com.apm.model.toLineProtocol
import org.junit.Assert.assertEquals
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
    private fun event(
        name: String,
        priority: ApmPriority = ApmPriority.NORMAL,
        timestamp: Long = System.currentTimeMillis()
    ): ApmEvent = ApmEvent(
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

    /** pruneExpired 清除重试耗尽与超龄的行。 */
    @Test
    fun `pruneExpired removes exhausted and aged rows`() {
        // 一条超龄行（时间戳远在过去）
        store.append(event("aged", timestamp = 1L))
        // 一条重试耗尽行
        store.append(event("exhausted"))
        val exhaustedId = store.readPending(2).first { it.event.name == "exhausted" }.id
        repeat(MAX_RETRY_FOR_TEST) { store.markRetry(listOf(exhaustedId)) }
        // 一条健康行
        store.append(event("healthy"))

        val pruned = store.pruneExpired(MAX_RETRY_FOR_TEST, ONE_DAY_MS)

        assertEquals(2, pruned)
        assertEquals("healthy", store.readPending(1).single().event.name)
    }

    /** 坏 payload 行在 readPending 时被容忍并自动清除。 */
    @Test
    fun `corrupt payload rows are dropped during readPending`() {
        store.append(event("good"))
        // 直接向表内注入坏 payload 行
        val db = dbHelper.writableDatabase
        db.insert(
            "events",
            null,
            ContentValues().apply {
                put("priority", 1)
                put("module", "test")
                put("name", "corrupt")
                put("severity", "INFO")
                put("data", "")
                put("payload", byteArrayOf(1, 2, 3))
                put("timestamp", System.currentTimeMillis())
                put("retry_count", 0)
            }
        )

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

    companion object {
        /** 测试用容量上限。 */
        private const val TEST_MAX_EVENTS = 20

        /** 批量写入条数。 */
        private const val BATCH_SIZE = 5

        /** 测试用重试上限。 */
        private const val MAX_RETRY_FOR_TEST = 3

        /** 一天的毫秒数。 */
        private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    }
}
