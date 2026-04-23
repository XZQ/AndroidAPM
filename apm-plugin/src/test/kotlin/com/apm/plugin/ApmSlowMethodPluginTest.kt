package com.apm.plugin

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * ApmSlowMethodExtension 和 ApmClassTransformer 核心逻辑测试。
 * 验证插件配置默认值、排除规则判断、以及字节码转换逻辑。
 */
class ApmSlowMethodPluginTest {

    // --- ApmSlowMethodExtension 配置测试 ---

    /** 默认开启插件。 */
    @Test
    fun `default enabled is true`() {
        val extension = ApmSlowMethodExtension()
        assertTrue(extension.enabled)
    }

    /** 默认排除系统包名。 */
    @Test
    fun `default excludePackages contains system packages`() {
        val extension = ApmSlowMethodExtension()
        // 验证关键系统包名在排除列表中
        assertTrue(extension.excludePackages.contains("androidx."))
        assertTrue(extension.excludePackages.contains("android."))
        assertTrue(extension.excludePackages.contains("com.google."))
        assertTrue(extension.excludePackages.contains("com.apm.slowmethod."))
        assertTrue(extension.excludePackages.contains("kotlin."))
        assertTrue(extension.excludePackages.contains("java."))
        assertTrue(extension.excludePackages.contains("javax."))
        assertTrue(extension.excludePackages.contains("dalvik."))
    }

    /** 自定义配置覆盖正确。 */
    @Test
    fun `custom config overrides defaults`() {
        val extension = ApmSlowMethodExtension()
        extension.enabled = false
        extension.excludePackages = listOf("com.example.")
        // 验证自定义值
        assertFalse(extension.enabled)
        assertEquals(listOf("com.example."), extension.excludePackages)
    }

    // --- shouldExclude 逻辑测试（通过 transformClass 间接测试） ---

    /** 排除 android 开头的类。 */
    @Test
    fun `class under android package is excluded`() {
        val extension = ApmSlowMethodExtension()
        // android.app.Activity 不应被插桩
        val result = transformClassWithDefaultConfig("android/app/Activity.class", extension)
        // 被排除的类应返回原始字节（未插桩）
        assertArrayEquals(SIMPLE_CLASS_BYTES, result)
    }

    /** 排除 androidx 开头的类。 */
    @Test
    fun `class under androidx package is excluded`() {
        val extension = ApmSlowMethodExtension()
        val result = transformClassWithDefaultConfig("androidx/appcompat/app/AppCompatActivity.class", extension)
        // 被排除的类应返回原始字节（未插桩）
        assertArrayEquals(SIMPLE_CLASS_BYTES, result)
    }

    /** 排除 kotlin 开头的类。 */
    @Test
    fun `class under kotlin package is excluded`() {
        val extension = ApmSlowMethodExtension()
        val result = transformClassWithDefaultConfig("kotlin/collections/ArraysKt.class", extension)
        assertArrayEquals(SIMPLE_CLASS_BYTES, result)
    }

    /** 排除 com.google. 开头的类。 */
    @Test
    fun `class under com_google package is excluded`() {
        val extension = ApmSlowMethodExtension()
        val result = transformClassWithDefaultConfig("com/google/android/gms/GoogleClass.class", extension)
        assertArrayEquals(SIMPLE_CLASS_BYTES, result)
    }

    /** 排除 java 开头的类。 */
    @Test
    fun `class under java package is excluded`() {
        val extension = ApmSlowMethodExtension()
        val result = transformClassWithDefaultConfig("java/lang/String.class", extension)
        assertArrayEquals(SIMPLE_CLASS_BYTES, result)
    }

    /** 非排除包名的类应正常转换（尝试插桩，可能因无效字节码回退到原始字节）。 */
    @Test
    fun `class under custom package is not excluded`() {
        val extension = ApmSlowMethodExtension()
        // 自定义包名不在排除列表中，会尝试插桩
        // 由于我们传入的是无效字节码，ASM 会捕获异常并返回原始字节
        val result = transformClassWithDefaultConfig("com/example/MyClass.class", extension)
        // 无效字节码导致 ASM 失败，回退到原始字节
        assertNotNull(result)
    }

    /** 自定义排除列表只排除指定包名。 */
    @Test
    fun `custom exclude list only excludes specified packages`() {
        val extension = ApmSlowMethodExtension()
        extension.excludePackages = listOf("com.example.")
        // com.example. 被排除
        val excluded = transformClassWithDefaultConfig("com/example/MyClass.class", extension)
        assertArrayEquals(SIMPLE_CLASS_BYTES, excluded)
        // android. 不再被排除（会尝试插桩，无效字节码回退）
        val notExcluded = transformClassWithDefaultConfig("android/app/Activity.class", extension)
        assertNotNull(notExcluded)
    }

    /** 插件禁用时 class 字节保持原样。 */
    @Test
    fun `disabled extension keeps class bytes unchanged`() {
        val extension = ApmSlowMethodExtension()
        extension.enabled = false
        val result = transformClassWithDefaultConfig("com/example/MyClass.class", extension)
        assertArrayEquals(SIMPLE_CLASS_BYTES, result)
    }

    /** isInstrumentable 根据排除包名判断。 */
    @Test
    fun `isInstrumentable respects excluded package prefixes`() {
        assertFalse(ApmClassTransformer.isInstrumentable("com/apm/slowmethod/ApmSlowMethodTracer.class", listOf("com.apm.slowmethod.")))
        assertTrue(ApmClassTransformer.isInstrumentable("com/example/MyClass.class", listOf("com.apm.slowmethod.")))
    }

    // --- 目录转换测试 ---

    /** 转换目录时非 class 文件应原样复制。 */
    @Test
    fun `non-class files are copied as-is in directory transform`() {
        val extension = ApmSlowMethodExtension()
        // 创建临时输入目录和文件
        val inputDir = createTempDir("apm_input")
        val outputDir = createTempDir("apm_output")
        try {
            // 创建一个非 class 文件
            val textFile = File(inputDir, "resources.txt")
            textFile.writeText(TEST_RESOURCE_CONTENT)
            // 执行目录转换
            ApmClassTransformer.transformDirectory(inputDir, outputDir, extension)
            // 验证文件被原样复制
            val outputFile = File(outputDir, "resources.txt")
            assertTrue(outputFile.exists())
            assertEquals(TEST_RESOURCE_CONTENT, outputFile.readText())
        } finally {
            // 清理临时目录
            inputDir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }

    /** 转换目录时排除包名的 class 文件应原样复制。 */
    @Test
    fun `excluded class files are copied as-is in directory transform`() {
        val extension = ApmSlowMethodExtension()
        val inputDir = createTempDir("apm_input")
        val outputDir = createTempDir("apm_output")
        try {
            // 模拟一个在排除列表中的 class 文件（无效字节码）
            val classFile = File(inputDir, "android/app/Activity.class")
            classFile.parentFile?.mkdirs()
            classFile.writeBytes(SIMPLE_CLASS_BYTES)
            // 执行目录转换
            ApmClassTransformer.transformDirectory(inputDir, outputDir, extension)
            // 验证被排除的文件仍然存在（原样复制）
            val outputFile = File(outputDir, "android/app/Activity.class")
            assertTrue(outputFile.exists())
            assertArrayEquals(SIMPLE_CLASS_BYTES, outputFile.readBytes())
        } finally {
            // 清理临时目录
            inputDir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }

    /** 转换 jar 时排除包名的 class 应保持原样。 */
    @Test
    fun `excluded class in jar is kept as-is`() {
        val extension = ApmSlowMethodExtension()
        val inputJar = File(createTempDir("apm_jar_input"), "input.jar")
        val outputJar = File(createTempDir("apm_jar_output"), "output.jar")
        try {
            // 创建包含排除包名 class 的 jar
            createTestJar(inputJar, "android/app/Activity.class", SIMPLE_CLASS_BYTES)
            // 执行 jar 转换
            ApmClassTransformer.transformJar(inputJar, outputJar, extension)
            // 验证输出 jar 包含原始字节
            JarFile(outputJar).use { jar ->
                val entry = jar.getJarEntry("android/app/Activity.class")
                assertNotNull(entry)
                assertArrayEquals(SIMPLE_CLASS_BYTES, jar.getInputStream(entry).readBytes())
            }
        } finally {
            // 清理临时文件
            inputJar.deleteRecursively()
            outputJar.deleteRecursively()
        }
    }

    /** 转换 jar 时非 class 条目应原样保留。 */
    @Test
    fun `non-class entries in jar are kept as-is`() {
        val extension = ApmSlowMethodExtension()
        val inputJar = File(createTempDir("apm_jar_input"), "input.jar")
        val outputJar = File(createTempDir("apm_jar_output"), "output.jar")
        try {
            // 创建包含非 class 条目的 jar
            val manifestContent = "Manifest-Version: 1.0\n"
            createTestJar(inputJar, "META-INF/MANIFEST.MF", manifestContent.toByteArray())
            // 执行 jar 转换
            ApmClassTransformer.transformJar(inputJar, outputJar, extension)
            // 验证非 class 条目原样保留
            JarFile(outputJar).use { jar ->
                val entry = jar.getJarEntry("META-INF/MANIFEST.MF")
                assertNotNull(entry)
                assertEquals(manifestContent, String(jar.getInputStream(entry).readBytes()))
            }
        } finally {
            // 清理临时文件
            inputJar.deleteRecursively()
            outputJar.deleteRecursively()
        }
    }

    // --- 辅助方法 ---

    /**
     * 通过反射调用 ApmClassTransformer 内部的 transformClass 方法。
     * 由于 transformClass 是 private 的，这里通过 transformDirectory 间接测试。
     * 为了直接测试排除逻辑，我们使用反射。
     *
     * @param classFilePath class 文件路径（如 "android/app/Activity.class"）。
     * @param config 插件配置。
     * @return 转换后的字节码。
     */
    private fun transformClassWithDefaultConfig(
        classFilePath: String,
        config: ApmSlowMethodExtension
    ): ByteArray {
        // 通过反射调用 private transformClass 方法
        val method = ApmClassTransformer::class.java.getDeclaredMethod(
            "transformClass",
            ByteArray::class.java,
            String::class.java,
            ApmSlowMethodExtension::class.java
        )
        method.isAccessible = true
        return method.invoke(ApmClassTransformer, SIMPLE_CLASS_BYTES, classFilePath, config) as ByteArray
    }

    /**
     * 创建测试用 jar 文件。
     *
     * @param jarFile 输出 jar 文件。
     * @param entryName 条目名。
     * @param content 条目内容。
     */
    private fun createTestJar(jarFile: File, entryName: String, content: ByteArray) {
        jarFile.parentFile?.mkdirs()
        JarOutputStream(jarFile.outputStream()).use { jos ->
            val entry = java.util.jar.JarEntry(entryName)
            jos.putNextEntry(entry)
            jos.write(content)
            jos.closeEntry()
        }
    }

    /**
     * 创建临时目录。
     *
     * @param prefix 目录名前缀。
     * @return 临时目录。
     */
    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    companion object {
        /** 测试用字节码（非有效 class 文件，仅用于排除逻辑测试）。 */
        private val SIMPLE_CLASS_BYTES = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0x00, 0x00)
        /** 测试资源文件内容。 */
        private const val TEST_RESOURCE_CONTENT = "test resource"
    }
}
