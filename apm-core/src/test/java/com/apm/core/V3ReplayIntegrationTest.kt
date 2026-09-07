package com.apm.core

import com.apm.core.aggregation.EventAggregator
import com.apm.core.privacy.PiiSanitizer
import com.apm.core.selfmonitor.SdkDropReason
import com.apm.core.selfmonitor.SdkSelfMonitor
import com.apm.model.ApmEvent
import com.apm.model.ApmEventCodec
import com.apm.model.ApmOccurrenceContext
import com.apm.model.SerializationFormat
import com.apm.storage.DiscardablePendingEventStore
import com.apm.storage.EventDbHelper
import com.apm.storage.PendingEventStore
import com.apm.storage.SQLiteEventStore
import com.apm.uploader.HttpApmUploader
import com.apm.uploader.RetryPolicy
import com.apm.uploader.UploaderLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/** Real SQLite/HTTP replay across legacy durable data, V3 aggregation and privacy processing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class V3ReplayIntegrationTest {
    /** A legacy row is explicitly discarded while valid raw and aggregated V3 rows receive exact ACK. */
    @Test
    fun `legacy outbox row cannot poison sanitized aggregate and current v3 batch`() {
        val database = SQLiteEventStore(EventDbHelper(RuntimeEnvironment.getApplication(),
            name = "v3-replay-${System.nanoTime()}.db"))
        val legacySource = ApmEvent("legacy", "offline")
        val encoded = ApmEventCodec.encode(legacySource)
        // The V4 trailing absent-occurrence flag did not exist in durable format V3.
        val legacyBytes = encoded.copyOf(encoded.size - 1)
        ByteBuffer.wrap(legacyBytes).putInt(3)
        val legacy = ApmEventCodec.decode(legacyBytes)
        assertEquals(legacySource.eventId, legacy.eventId)
        assertNull(legacy.occurrence)
        val occurrence = ApmOccurrenceContext("1.2", "12", "build-12", "release", "test-installation")
        val metric = ApmEvent("render", "duration", scene = "Feed",
            fields = mapOf("ms" to 12.5, "detail" to StringBuilder("review.user@example.invalid")))
            .withOccurrenceContext(occurrence)
        val aggregator = EventAggregator()
        aggregator.process(snapshotEvent(metric))
        val aggregate = PiiSanitizer().sanitize(aggregator.flush().single())
        val current = ApmEvent("sample", "current", fields = mapOf("value" to 7)).withOccurrenceContext(occurrence)
        database.appendBatch(listOf(legacy, aggregate, current))
        val initialRows = database.readPending(10)
        val legacyId = initialRows.single { it.event.eventId == legacy.eventId }.id
        val validIds = initialRows.filter { it.event.occurrence != null }.map { it.id }.toSet()
        val aggregateRow = initialRows.single { it.event.eventId == aggregate.eventId }
        assertEquals(12.5, aggregateRow.event.fields["ms_p50"])
        assertEquals("r***@example.invalid", aggregateRow.event.fields["detail"])
        assertEquals(occurrence, aggregateRow.event.occurrence)
        val acknowledged = CopyOnWriteArrayList<Long>()
        val discarded = CopyOnWriteArrayList<Long>()
        val retried = CopyOnWriteArrayList<Long>()
        val ackSignal = CountDownLatch(1)
        val store = object : PendingEventStore by database, DiscardablePendingEventStore {
            override fun acknowledgeClaim(ownerId: String, ids: List<Long>): Int {
                val removed = database.acknowledgeClaim(ownerId, ids)
                acknowledged.addAll(ids)
                ackSignal.countDown()
                return removed
            }
            override fun discardClaim(ownerId: String, ids: List<Long>): Int {
                discarded.addAll(ids)
                return database.discardClaim(ownerId, ids)
            }
            override fun failClaim(ownerId: String, ids: List<Long>) {
                retried.addAll(ids)
                database.failClaim(ownerId, ids)
            }
        }
        val monitor = SdkSelfMonitor()
        val wire = AtomicReference<ByteArray>()
        val requestHeaders = AtomicReference<Map<String, String>>()
        val serverError = AtomicReference<Throwable>()
        ServerSocket(0).use { server ->
            server.soTimeout = 5_000
            val serverThread = Thread {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = BufferedInputStream(socket.getInputStream())
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            check(next >= 0 && head.length < 16_384)
                            head.append(next.toChar())
                        }
                        val headers = head.lines().drop(1).filter { ':' in it }.associate {
                            it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                        }
                        requestHeaders.set(headers)
                        val body = input.readNBytes(headers.getValue("content-length").toInt())
                        wire.set(GZIPInputStream(ByteArrayInputStream(body)).use { it.readBytes() })
                        val response = "HTTP/1.1 200 OK\r\n" +
                            "X-APM-Schema-Version: ${headers.getValue("x-apm-schema-version")}\r\n" +
                            "X-APM-Batch-Id: ${headers.getValue("x-apm-batch-id")}\r\n" +
                            "X-APM-Event-Count: ${headers.getValue("x-apm-event-count")}\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(response.toByteArray())
                    }
                } catch (error: Throwable) { serverError.set(error) }
            }.apply { isDaemon = true; name = "v3-replay-http-test"; start() }
            val uploader = HttpApmUploader("http://127.0.0.1:${server.localPort}/events",
                serializationFormat = SerializationFormat.PROTOBUF_ENVELOPE_V3, enableGzip = true,
                logger = object : UploaderLogger {
                    override fun d(message: String) = Unit
                    override fun w(message: String) = Unit
                    override fun e(message: String, throwable: Throwable?) = Unit
                })
            val worker = PersistentUploadWorker(store, uploader, RetryPolicy(), 32,
                logger = object : ApmLogger {
                    override fun d(message: String) = Unit
                    override fun w(message: String) = Unit
                    override fun e(message: String, throwable: Throwable?) = Unit
                }, selfMonitor = monitor)
            try {
                assertTrue(ackSignal.await(5, TimeUnit.SECONDS))
                serverThread.join(5_000L)
                assertNull(serverError.get())
                assertEquals(validIds, acknowledged.toSet())
                assertEquals(listOf(legacyId), discarded.toList())
                assertTrue(retried.isEmpty())
                assertEquals(0, database.pendingCount())
                assertEquals(1L, monitor.getDropCount(SdkDropReason.UPLOAD_PROTOCOL_REJECTED))
                assertEquals("3", requestHeaders.get()["x-apm-schema-version"])
                assertEquals("2", requestHeaders.get()["x-apm-event-count"])
                assertFalse(wire.get().toString(Charsets.UTF_8).contains("review.user@example.invalid"))
            } finally {
                worker.shutdown()
                database.close()
                server.close()
                serverThread.join(5_000L)
            }
        }
    }
}
