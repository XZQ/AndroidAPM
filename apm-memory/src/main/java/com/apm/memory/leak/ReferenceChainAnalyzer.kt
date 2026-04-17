package com.apm.memory.leak

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hprof 引用链分析器。
 *
 * 解析 Hprof 二进制格式，构建从 GC Root 到泄漏对象的引用链。
 * 实现思路（对标 LeakCanary/Shark）：
 * 1. 解析 Hprof header（magic + version + identifier size）
 * 2. 扫描 HEAP_DUMP_SEGMENT 中的 GC Root 记录
 * 3. 解析 CLASS_DUMP 获取字段引用关系
 * 4. 解析 INSTANCE_DUMP 获取实例字段值
 * 5. BFS 从 GC Root 搜索到目标对象
 * 6. 输出最短引用链
 *
 * 注意：此为简化版实现，生产环境建议使用 Shark (LeakCanary 2.x) 或
 * 自行集成完整的 Hprof 解析库。
 */
class ReferenceChainAnalyzer {

    /**
     * 分析指定 Hprof 文件中目标对象的引用链。
     *
     * @param hprofFile Hprof 文件
     * @param targetClassName 泄漏目标类名（如 "com.example.MyActivity"）
     * @return 引用链分析结果，失败时返回 null
     */
    fun analyze(hprofFile: File, targetClassName: String): ReferenceChainResult? {
        if (!hprofFile.exists() || hprofFile.length() == 0L) return null

        val startTime = System.currentTimeMillis()
        try {
            // 打开 Hprof 文件进行二进制读取
            val raf = RandomAccessFile(hprofFile, MODE_READ)
            raf.use { file ->
                // 1. 解析 Hprof header
                val header = parseHeader(file) ?: return null

                // 2. 扫描所有记录，构建引用图
                val gcRoots = mutableMapOf<Long, GcRootType>()
                val classNameMap = mutableMapOf<Long, String>()
                val instanceClassMap = mutableMapOf<Long, Long>()
                val instanceFields = mutableMapOf<Long, MutableMap<String, Long>>()
                val classSuperClassMap = mutableMapOf<Long, Long>()

                scanHeapDump(
                    file, header,
                    gcRoots, classNameMap, instanceClassMap,
                    instanceFields, classSuperClassMap
                )

                // 3. 查找目标类的所有实例
                val targetClassId = classNameMap.entries
                    .firstOrNull { it.value == targetClassName }?.key
                    ?: return null

                val targetInstances = instanceClassMap.entries
                    .filter { it.value == targetClassId }
                    .map { it.key }

                if (targetInstances.isEmpty()) return null

                // 4. BFS 从所有 GC Root 搜索最短路径到目标实例
                val chain = bfsShortestPath(
                    gcRoots.keys, targetInstances.toSet(),
                    instanceFields, classNameMap, instanceClassMap
                )

                val durationMs = System.currentTimeMillis() - startTime
                return ReferenceChainResult(
                    targetClassName = targetClassName,
                    chain = chain,
                    gcRootType = gcRoots[chain.firstOrNull()?.objectId]?.displayName
                        ?: GcRootType.UNKNOWN.displayName,
                    analysisDurationMs = durationMs
                )
            }
        } catch (e: Exception) {
            // Hprof 解析失败，不影响主流程
            Log.w(TAG, "Reference chain analysis failed: ${e.message}")
            return null
        }
    }

    /**
     * 解析 Hprof 文件头。
     * 格式：null-terminated string + identifier size (4 bytes) + timestamps (8 bytes)
     */
    private fun parseHeader(file: RandomAccessFile): HprofHeader? {
        // 读取 magic string（以 null 结尾）
        val magicBytes = ByteArrayOutputStream()
        var b: Int
        while (true) {
            b = file.read()
            if (b == -1) return null // 文件结束
            if (b == 0) break // null 终止符
            magicBytes.write(b)
        }
        val magic = magicBytes.toString(Charsets.UTF_8.name())
        // 验证是否为有效的 Hprof 文件
        if (!magic.startsWith(HPROF_MAGIC_PREFIX)) return null

        // 读取 identifier size（4 字节，大端序）
        val idSizeBytes = ByteArray(ID_SIZE_BYTES)
        file.readFully(idSizeBytes)
        val idSize = ByteBuffer.wrap(idSizeBytes)
            .order(ByteOrder.BIG_ENDIAN).int

        // 读取时间戳（8 字节）
        file.readFully(ByteArray(TIMESTAMP_BYTES))

        return HprofHeader(
            identifierSize = idSize,
            headerSize = magicBytes.size() + NULL_BYTE_SIZE + ID_SIZE_BYTES + TIMESTAMP_BYTES
        )
    }

    /**
     * 扫描 Hprof HEAP_DUMP_SEGMENT 记录，提取引用关系。
     * 只关注引用链构建所需的核心记录类型。
     */
    private fun scanHeapDump(
        file: RandomAccessFile,
        header: HprofHeader,
        gcRoots: MutableMap<Long, GcRootType>,
        classNameMap: MutableMap<Long, String>,
        instanceClassMap: MutableMap<Long, Long>,
        instanceFields: MutableMap<Long, MutableMap<String, Long>>,
        classSuperClassMap: MutableMap<Long, Long>
    ) {
        val idSize = header.identifierSize
        // 定位到 header 之后
        file.seek(header.headerSize.toLong())

        // 遍历所有 top-level record
        while (true) {
            // record header: tag (1 byte) + time (4 bytes) + length (4 bytes)
            val tag = file.read()
            if (tag == -1) break // EOF
            file.skipBytes(RECORD_TIME_BYTES)
            val length = file.readInt()

            when (tag) {
                // STRING 记录：id → string 映射
                TAG_STRING -> {
                    val id = readId(file, idSize)
                    val stringBytes = ByteArray(length - idSize)
                    file.readFully(stringBytes)
                    // 存储类名字符串映射
                    classNameMap[id] = String(stringBytes, Charsets.UTF_8)
                }
                // HEAP_DUMP 或 HEAP_DUMP_SEGMENT：核心堆数据
                TAG_HEAP_DUMP, TAG_HEAP_DUMP_SEGMENT -> {
                    // 解析堆转储中的子记录
                    parseHeapSubRecords(
                        file, length, idSize,
                        gcRoots, classNameMap, instanceClassMap,
                        instanceFields, classSuperClassMap
                    )
                }
                // HEAP_DUMP_END：跳过
                TAG_HEAP_DUMP_END -> file.skipBytes(length)
                // 其他记录类型：跳过
                else -> file.skipBytes(length)
            }
        }
    }

    /**
     * 解析堆转储子记录。
     * 处理 GC Root、Class Dump、Instance Dump 等关键记录。
     */
    private fun parseHeapSubRecords(
        file: RandomAccessFile,
        length: Int,
        idSize: Int,
        gcRoots: MutableMap<Long, GcRootType>,
        classNameMap: MutableMap<Long, String>,
        instanceClassMap: MutableMap<Long, Long>,
        instanceFields: MutableMap<Long, MutableMap<String, Long>>,
        classSuperClassMap: MutableMap<Long, Long>
    ) {
        val endPos = file.filePointer + length
        while (file.filePointer < endPos) {
            val subTag = file.read()
            if (subTag == -1) break

            when (subTag) {
                // GC Root: JNI Global
                SUBTAG_ROOT_JNI_GLOBAL -> {
                    val objectId = readId(file, idSize)
                    file.skipBytes(idSize) // referrer
                    gcRoots[objectId] = GcRootType.JNI_GLOBAL
                }
                // GC Root: JNI Local
                SUBTAG_ROOT_JNI_LOCAL -> {
                    val objectId = readId(file, idSize)
                    file.skipBytes(idSize + U4_BYTES) // thread + frame
                    gcRoots[objectId] = GcRootType.JNI_LOCAL
                }
                // GC Root: Java Frame（栈帧局部变量）
                SUBTAG_ROOT_JAVA_FRAME -> {
                    val objectId = readId(file, idSize)
                    file.skipBytes(idSize + U4_BYTES) // thread + frame
                    gcRoots[objectId] = GcRootType.JAVA_FRAME
                }
                // GC Root: Sticky Class
                SUBTAG_ROOT_STICKY_CLASS -> {
                    val objectId = readId(file, idSize)
                    gcRoots[objectId] = GcRootType.STICKY_CLASS
                }
                // GC Root: System Class
                SUBTAG_ROOT_SYSTEM_CLASS -> {
                    val objectId = readId(file, idSize)
                    gcRoots[objectId] = GcRootType.SYSTEM_CLASS
                }
                // GC Root: Thread Block
                SUBTAG_ROOT_THREAD_BLOCK -> {
                    val objectId = readId(file, idSize)
                    file.skipBytes(idSize) // thread
                    gcRoots[objectId] = GcRootType.THREAD_BLOCK
                }
                // GC Root: Native Stack
                SUBTAG_ROOT_NATIVE_STACK -> {
                    val objectId = readId(file, idSize)
                    file.skipBytes(idSize) // thread
                    gcRoots[objectId] = GcRootType.NATIVE_STACK
                }
                // GC Root: Finalizing
                SUBTAG_ROOT_FINALIZING -> {
                    val objectId = readId(file, idSize)
                    gcRoots[objectId] = GcRootType.FINALIZING
                }
                // GC Root: Monitor Used
                SUBTAG_ROOT_MONITOR_USED -> {
                    val objectId = readId(file, idSize)
                    gcRoots[objectId] = GcRootType.MONITOR_USED
                }
                // GC Root: Thread Object
                SUBTAG_ROOT_THREAD_OBJ -> {
                    val objectId = readId(file, idSize)
                    file.skipBytes(U4_BYTES + idSize + U4_BYTES) // seq + stack + thread
                    gcRoots[objectId] = GcRootType.THREAD_OBJ
                }
                // Class Dump
                SUBTAG_CLASS_DUMP -> {
                    parseClassDump(file, idSize, classNameMap, classSuperClassMap)
                }
                // Instance Dump
                SUBTAG_INSTANCE_DUMP -> {
                    parseInstanceDump(file, idSize, instanceClassMap, instanceFields, classNameMap)
                }
                // Object Array Dump
                SUBTAG_OBJ_ARRAY_DUMP -> {
                    skipObjArrayDump(file, idSize)
                }
                // Primitive Array Dump
                SUBTAG_PRIM_ARRAY_DUMP -> {
                    skipPrimArrayDump(file, idSize)
                }
                // 其他子记录：通过 readU4 读取长度后跳过
                else -> {
                    // 未知子记录，无法确定长度，终止解析
                    break
                }
            }
        }
        // 确保文件指针回到正确位置
        if (file.filePointer < endPos) {
            file.seek(endPos)
        }
    }

    /**
     * 解析 CLASS_DUMP 子记录。
     * 提取类的 superclass 引用和字段信息。
     */
    private fun parseClassDump(
        file: RandomAccessFile,
        idSize: Int,
        classNameMap: MutableMap<Long, String>,
        classSuperClassMap: MutableMap<Long, Long>
    ) {
        // class object ID
        val classId = readId(file, idSize)
        file.skipBytes(U4_BYTES) // stack trace
        // superclass object ID
        val superClassId = readId(file, idSize)
        classSuperClassMap[classId] = superClassId
        // class loader, signers, protection domain, reserved
        file.skipBytes(idSize * RESERVED_ID_COUNT)
        // instance size
        file.skipBytes(U4_BYTES)
        // constant pool
        val cpCount = file.readUnsignedShort()
        for (i in 0 until cpCount) {
            file.skipBytes(U2_BYTES) // cp index
            file.skipBytes(idSize) // cp value type + value
        }
        // static fields
        val staticCount = file.readUnsignedShort()
        for (i in 0 until staticCount) {
            file.skipBytes(idSize) // name id
            file.skipBytes(U1_BYTES) // type
            // 根据类型跳过值
            skipTypeValue(file, file.read(), idSize)
        }
        // instance fields
        val instanceFieldCount = file.readUnsignedShort()
        file.skipBytes(instanceFieldCount * (idSize + U1_BYTES))
    }

    /**
     * 解析 INSTANCE_DUMP 子记录。
     * 提取实例的 class ID 和字段值中的对象引用。
     */
    private fun parseInstanceDump(
        file: RandomAccessFile,
        idSize: Int,
        instanceClassMap: MutableMap<Long, Long>,
        instanceFields: MutableMap<Long, MutableMap<String, Long>>,
        classNameMap: MutableMap<Long, String>
    ) {
        val instanceId = readId(file, idSize)
        file.skipBytes(U4_BYTES) // stack trace
        val classId = readId(file, idSize)
        // 记录实例 → 类映射
        instanceClassMap[instanceId] = classId
        // 读取实例字段数据长度
        val fieldSize = file.readInt()
        // 简化处理：只读取前几个字段中的对象引用
        val fieldData = ByteArray(fieldSize)
        file.readFully(fieldData)
        // 尝试从字段数据中提取对象 ID（简化版：扫描对齐位置的值）
        extractObjectRefs(instanceId, fieldData, idSize, instanceFields)
    }

    /**
     * 从实例字段数据中提取对象引用。
     * 简化版：按 idSize 对齐扫描，假设每个位置可能是对象引用。
     */
    private fun extractObjectRefs(
        instanceId: Long,
        fieldData: ByteArray,
        idSize: Int,
        instanceFields: MutableMap<Long, MutableMap<String, Long>>
    ) {
        if (fieldData.size < idSize) return
        val fields = instanceFields.getOrPut(instanceId) { mutableMapOf() }
        // 按 idSize 对齐扫描字段数据
        var offset = 0
        var fieldIndex = 0
        while (offset + idSize <= fieldData.size && fieldIndex < MAX_FIELDS_PER_INSTANCE) {
            val refId = readIdFromBytes(fieldData, offset, idSize)
            // 非零引用可能是对象引用
            if (refId != 0L) {
                fields["field_$fieldIndex"] = refId
            }
            offset += idSize
            fieldIndex++
        }
    }

    /** 跳过 Object Array Dump 子记录。 */
    private fun skipObjArrayDump(file: RandomAccessFile, idSize: Int) {
        file.skipBytes(idSize) // array object ID
        file.skipBytes(U4_BYTES) // stack trace
        file.skipBytes(U4_BYTES) // length
        file.skipBytes(idSize) // element class ID
        file.skipBytes(U4_BYTES) // reserved
    }

    /** 跳过 Primitive Array Dump 子记录。 */
    private fun skipPrimArrayDump(file: RandomAccessFile, idSize: Int) {
        file.skipBytes(idSize) // array object ID
        file.skipBytes(U4_BYTES) // stack trace
        file.skipBytes(U4_BYTES) // length
        file.skipBytes(U1_BYTES) // element type
        // 元素数量 * 元素大小
        val length = file.readInt()
        file.skipBytes(length)
    }

    /** 根据类型标识跳过值。 */
    private fun skipTypeValue(file: RandomAccessFile, type: Int, idSize: Int) {
        val size = when (type) {
            TYPE_OBJECT -> idSize
            TYPE_BOOLEAN, TYPE_BYTE -> U1_BYTES
            TYPE_CHAR, TYPE_SHORT -> U2_BYTES
            TYPE_FLOAT, TYPE_INT -> U4_BYTES
            TYPE_DOUBLE, TYPE_LONG -> U8_BYTES
            else -> idSize // 默认按 id 大小跳过
        }
        file.skipBytes(size)
    }

    /**
     * BFS 搜索从 GC Roots 到目标实例的最短引用路径。
     */
    private fun bfsShortestPath(
        gcRootIds: Set<Long>,
        targetIds: Set<Long>,
        instanceFields: Map<Long, Map<String, Long>>,
        classNameMap: Map<Long, String>,
        instanceClassMap: Map<Long, Long>
    ): List<RefNode> {
        // 父节点记录：childId → (parentId, fieldName)
        val parentMap = mutableMapOf<Long, Pair<Long, String>>()
        // BFS 队列
        val queue = ArrayDeque<Long>()
        // 已访问集合
        val visited = mutableSetOf<Long>()

        // 所有 GC Root 入队
        for (rootId in gcRootIds) {
            queue.add(rootId)
            visited.add(rootId)
            parentMap[rootId] = Pair(ROOT_PARENT_ID, GC_ROOT_FIELD)
        }

        // BFS 搜索
        while (queue.isNotEmpty()) {
            val currentId = queue.removeFirst()
            // 到达目标实例，回溯路径
            if (currentId in targetIds) {
                return buildPath(parentMap, currentId, classNameMap, instanceClassMap)
            }
            // 获取当前对象的所有引用
            val refs = instanceFields[currentId] ?: continue
            for ((fieldName, refId) in refs) {
                if (refId !in visited) {
                    visited.add(refId)
                    parentMap[refId] = Pair(currentId, fieldName)
                    queue.add(refId)
                }
            }
        }

        // 未找到路径
        return emptyList()
    }

    /**
     * 从 parentMap 回溯构建引用链。
     */
    private fun buildPath(
        parentMap: Map<Long, Pair<Long, String>>,
        targetId: Long,
        classNameMap: Map<Long, String>,
        instanceClassMap: Map<Long, Long>
    ): List<RefNode> {
        val path = mutableListOf<RefNode>()
        var currentId: Long? = targetId

        while (currentId != null && currentId != ROOT_PARENT_ID) {
            val classId = instanceClassMap[currentId]
            val className = classId?.let { classNameMap[it] } ?: UNKNOWN_CLASS
            val parent = parentMap[currentId]
            val fieldName = parent?.second ?: GC_ROOT_FIELD
            // 跳过 GC Root 自身的 field 名
            if (fieldName != GC_ROOT_FIELD) {
                path.add(0, RefNode(
                    className = className,
                    fieldName = fieldName,
                    referenceType = REF_TYPE_INSTANCE_FIELD,
                    objectId = currentId
                ))
            }
            currentId = parent?.first
            // 到达 GC Root 的父节点时终止
            if (parent?.first == ROOT_PARENT_ID) {
                path.add(0, RefNode(
                    className = className,
                    fieldName = GC_ROOT_FIELD,
                    referenceType = REF_TYPE_GC_ROOT,
                    objectId = currentId ?: 0L
                ))
                break
            }
        }
        return path
    }

    /** 读取一个 ID（根据 idSize 为 4 或 8 字节）。 */
    private fun readId(file: RandomAccessFile, idSize: Int): Long {
        return if (idSize == U4_BYTES) {
            file.readInt().toLong() and MASK_INT_TO_LONG
        } else {
            file.readLong()
        }
    }

    /** 从字节数组中读取一个 ID。 */
    private fun readIdFromBytes(bytes: ByteArray, offset: Int, idSize: Int): Long {
        return if (idSize == U4_BYTES) {
            ((bytes[offset].toLong() and MASK_BYTE) shl SHIFT_24) or
            ((bytes[offset + 1].toLong() and MASK_BYTE) shl SHIFT_16) or
            ((bytes[offset + 2].toLong() and MASK_BYTE) shl SHIFT_8) or
            (bytes[offset + 3].toLong() and MASK_BYTE)
        } else {
            ((bytes[offset].toLong() and MASK_BYTE) shl SHIFT_56) or
            ((bytes[offset + 1].toLong() and MASK_BYTE) shl SHIFT_48) or
            ((bytes[offset + 2].toLong() and MASK_BYTE) shl SHIFT_40) or
            ((bytes[offset + 3].toLong() and MASK_BYTE) shl SHIFT_32) or
            ((bytes[offset + 4].toLong() and MASK_BYTE) shl SHIFT_24) or
            ((bytes[offset + 5].toLong() and MASK_BYTE) shl SHIFT_16) or
            ((bytes[offset + 6].toLong() and MASK_BYTE) shl SHIFT_8) or
            (bytes[offset + 7].toLong() and MASK_BYTE)
        }
    }

    companion object {
        /** Log Tag。 */
        private const val TAG = "RefChainAnalyzer"

        /** Hprof magic 前缀。 */
        private const val HPROF_MAGIC_PREFIX = "JAVA PROFILE"

        /** 文件读取模式。 */
        private const val MODE_READ = "r"

        /** null 字节大小。 */
        private const val NULL_BYTE_SIZE = 1

        /** ID 大小字节数。 */
        private const val ID_SIZE_BYTES = 4

        /** 时间戳字节数。 */
        private const val TIMESTAMP_BYTES = 8

        /** Record 时间戳字节数。 */
        private const val RECORD_TIME_BYTES = 4

        // --- Hprof Top-level Record Tags ---
        /** STRING 记录 tag。 */
        private const val TAG_STRING = 0x01
        /** HEAP_DUMP 记录 tag。 */
        private const val TAG_HEAP_DUMP = 0x0C
        /** HEAP_DUMP_SEGMENT 记录 tag。 */
        private const val TAG_HEAP_DUMP_SEGMENT = 0x1C
        /** HEAP_DUMP_END 记录 tag。 */
        private const val TAG_HEAP_DUMP_END = 0x2C

        // --- Heap Sub-record Tags ---
        /** GC Root: JNI Global。 */
        private const val SUBTAG_ROOT_JNI_GLOBAL = 0x01
        /** GC Root: JNI Local。 */
        private const val SUBTAG_ROOT_JNI_LOCAL = 0x02
        /** GC Root: Java Frame。 */
        private const val SUBTAG_ROOT_JAVA_FRAME = 0x03
        /** GC Root: System Class。 */
        private const val SUBTAG_ROOT_SYSTEM_CLASS = 0x04
        /** GC Root: Native Stack。 */
        private const val SUBTAG_ROOT_NATIVE_STACK = 0x05
        /** GC Root: Sticky Class。 */
        private const val SUBTAG_ROOT_STICKY_CLASS = 0x06
        /** GC Root: Thread Block。 */
        private const val SUBTAG_ROOT_THREAD_BLOCK = 0x07
        /** GC Root: Monitor Used。 */
        private const val SUBTAG_ROOT_MONITOR_USED = 0x08
        /** GC Root: Thread Object。 */
        private const val SUBTAG_ROOT_THREAD_OBJ = 0x09
        /** GC Root: Finalizing。 */
        private const val SUBTAG_ROOT_FINALIZING = 0x0A
        /** Class Dump。 */
        private const val SUBTAG_CLASS_DUMP = 0x20
        /** Instance Dump。 */
        private const val SUBTAG_INSTANCE_DUMP = 0x21
        /** Object Array Dump。 */
        private const val SUBTAG_OBJ_ARRAY_DUMP = 0x22
        /** Primitive Array Dump。 */
        private const val SUBTAG_PRIM_ARRAY_DUMP = 0x23

        // --- Type Constants ---
        /** 类型：Object。 */
        private const val TYPE_OBJECT = 2
        /** 类型：Boolean。 */
        private const val TYPE_BOOLEAN = 4
        /** 类型：Char。 */
        private const val TYPE_CHAR = 5
        /** 类型：Float。 */
        private const val TYPE_FLOAT = 6
        /** 类型：Double。 */
        private const val TYPE_DOUBLE = 7
        /** 类型：Byte。 */
        private const val TYPE_BYTE = 8
        /** 类型：Short。 */
        private const val TYPE_SHORT = 9
        /** 类型：Int。 */
        private const val TYPE_INT = 10
        /** 类型：Long。 */
        private const val TYPE_LONG = 11

        // --- Size Constants ---
        /** 1 字节。 */
        private const val U1_BYTES = 1
        /** 2 字节。 */
        private const val U2_BYTES = 2
        /** 4 字节。 */
        private const val U4_BYTES = 4
        /** 8 字节。 */
        private const val U8_BYTES = 8

        // --- Bit Masks ---
        /** int → long 掩码。 */
        private const val MASK_INT_TO_LONG = 0xFFFFFFFFL
        /** byte → long 掩码。 */
        private const val MASK_BYTE = 0xFFL

        // --- Shift Constants ---
        /** 位移：8 位。 */
        private const val SHIFT_8 = 8
        /** 位移：16 位。 */
        private const val SHIFT_16 = 16
        /** 位移：24 位。 */
        private const val SHIFT_24 = 24
        /** 位移：32 位。 */
        private const val SHIFT_32 = 32
        /** 位移：40 位。 */
        private const val SHIFT_40 = 40
        /** 位移：48 位。 */
        private const val SHIFT_48 = 48
        /** 位移：56 位。 */
        private const val SHIFT_56 = 56

        // --- Misc Constants ---
        /** CLASS_DUMP 中跳过的保留 ID 数量。 */
        private const val RESERVED_ID_COUNT = 7

        /** 每个实例最多解析的字段数。 */
        private const val MAX_FIELDS_PER_INSTANCE = 50

        /** BFS 起始父节点 ID 标记。 */
        private const val ROOT_PARENT_ID = -1L

        /** GC Root 字段名标记。 */
        private const val GC_ROOT_FIELD = "<gc_root>"

        /** 引用类型：实例字段。 */
        private const val REF_TYPE_INSTANCE_FIELD = "instance_field"

        /** 引用类型：GC Root。 */
        private const val REF_TYPE_GC_ROOT = "gc_root"

        /** 未知类名。 */
        private const val UNKNOWN_CLASS = "<unknown>"
    }
}

/** Hprof 文件头信息。 */
private data class HprofHeader(
    /** 标识符大小（4 或 8 字节）。 */
    val identifierSize: Int,
    /** header 总字节数。 */
    val headerSize: Int
)

/** GC Root 类型枚举。 */
enum class GcRootType(val displayName: String) {
    /** JNI 全局引用。 */
    JNI_GLOBAL("JNI Global"),
    /** JNI 局部引用。 */
    JNI_LOCAL("JNI Local"),
    /** Java 栈帧。 */
    JAVA_FRAME("Java Frame"),
    /** System 类。 */
    SYSTEM_CLASS("System Class"),
    /** Native 栈。 */
    NATIVE_STACK("Native Stack"),
    /** Sticky 类。 */
    STICKY_CLASS("Sticky Class"),
    /** 线程阻塞。 */
    THREAD_BLOCK("Thread Block"),
    /** Monitor 使用。 */
    MONITOR_USED("Monitor Used"),
    /** 线程对象。 */
    THREAD_OBJ("Thread Object"),
    /** Finalizing。 */
    FINALIZING("Finalizing"),
    /** 未知类型。 */
    UNKNOWN("Unknown")
}

/**
 * 引用链分析结果。
 */
data class ReferenceChainResult(
    /** 泄漏目标类名。 */
    val targetClassName: String,
    /** 从 GC Root 到目标的引用链。 */
    val chain: List<RefNode>,
    /** GC Root 类型。 */
    val gcRootType: String,
    /** 分析耗时（毫秒）。 */
    val analysisDurationMs: Long
)

/**
 * 引用链中的单个节点。
 */
data class RefNode(
    /** 持有引用的类名。 */
    val className: String,
    /** 引用字段名。 */
    val fieldName: String,
    /** 引用类型。 */
    val referenceType: String,
    /** 对象 ID。 */
    val objectId: Long
)
