package com.apm.memory.oom

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Contract tests for primitive-array redaction in Hprof strip output. */
class HprofStripProcessorTest {
    /** Temporary input and output files owned by each test. */
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Primitive array payload bytes are replaced while record structure is retained. */
    @Test
    fun `primitive array contents are zeroed`() {
        val input = temporaryFolder.newFile("input.hprof")
        val output = temporaryFolder.newFile("output.hprof")
        input.writeBytes(buildPrimitiveArrayHprof(byteArrayOf(1, 2, 3)))

        assertTrue(HprofStripProcessor().strip(input, output))

        assertArrayEquals(byteArrayOf(0, 0, 0), readPrimitiveArrayPayload(output.readBytes()))
    }

    /** Malformed input returns failure and removes the partial destination. */
    @Test
    fun `malformed input removes output`() {
        val input = temporaryFolder.newFile("broken.hprof")
        val output = temporaryFolder.newFile("broken-output.hprof")
        input.writeBytes(byteArrayOf(1, 2, 3))

        assertFalse(HprofStripProcessor().strip(input, output))
        assertFalse(output.exists())
    }

    /** Builds one minimal heap-dump segment containing a byte array. */
    private fun buildPrimitiveArrayHprof(payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(HprofStripProcessor.GC_PRIMITIVE_ARRAY_DUMP)
                output.writeLong(1L)
                output.writeInt(0)
                output.writeInt(payload.size)
                output.writeByte(TYPE_BYTE)
                output.write(payload)
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(HPROF_HEADER)
                output.writeByte(0)
                output.writeInt(IDENTIFIER_SIZE)
                output.writeLong(0L)
                output.writeByte(HprofStripProcessor.TAG_HEAP_DUMP_SEGMENT)
                output.writeInt(0)
                output.writeInt(body.size)
                output.write(body)
            }
        }.toByteArray()
    }

    /** Extracts the byte-array payload from the generated stripped record. */
    private fun readPrimitiveArrayPayload(hprof: ByteArray): ByteArray {
        val input = DataInputStream(hprof.inputStream())
        while (input.readByte().toInt() != 0) {
            // Skip the null-terminated format string.
        }
        input.readInt()
        input.readLong()
        input.readByte()
        input.readInt()
        input.readInt()
        input.readByte()
        input.readLong()
        input.readInt()
        val elementCount = input.readInt()
        input.readByte()
        return ByteArray(elementCount).also(input::readFully)
    }

    companion object {
        /** Minimal Hprof format string. */
        private val HPROF_HEADER = "JAVA PROFILE 1.0.3".toByteArray()
        /** Identifier width used by the current strip implementation. */
        private const val IDENTIFIER_SIZE = 8
        /** Hprof primitive type code for byte. */
        private const val TYPE_BYTE = 8
    }
}
