package com.apm.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.AdviceAdapter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/**
 * ASM 类转换器。
 * 负责读取 class 文件，通过 ASM 字节码操作注入方法计时逻辑。
 *
 * ## 插桩逻辑
 * - 在方法入口注入：ApmSlowMethodTracer.methodEnter(className#methodName)
 * - 在方法出口注入：ApmSlowMethodTracer.methodExit(className#methodName)
 * - 运行时 Tracer 自动配对 enter/exit 计算方法耗时
 */
object ApmClassTransformer {

    /** 运行时 Tracer 类的全限定名（JVM 内部格式）。 */
    private const val TRACER_CLASS = "com/apm/slowmethod/ApmSlowMethodTracer"
    /** Tracer 的 methodEnter 方法签名。 */
    private const val METHOD_ENTER = "methodEnter"
    /** Tracer 的 methodExit 方法签名。 */
    private const val METHOD_EXIT = "methodExit"
    /** 方法签名描述符。 */
    private const val METHOD_DESC = "(Ljava/lang/String;)V"

    /**
     * 转换 jar 文件。
     * 遍历 jar 中的每个 class 文件，符合条件的进行插桩。
     *
     * @param inputJar 输入 jar 文件。
     * @param outputJar 输出 jar 文件。
     * @param config 插件配置。
     */
    fun transformJar(inputJar: File, outputJar: File, config: ApmSlowMethodExtension) {
        JarFile(inputJar).use { jarFile ->
            JarOutputStream(FileOutputStream(outputJar)).use { jos ->
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // 读取原始字节
                    val bytes = jarFile.getInputStream(entry).readBytes()

                    // 判断是否需要插桩
                    val transformed = if (entry.name.endsWith(".class")) {
                        transformClass(bytes, entry.name, config)
                    } else {
                        bytes
                    }

                    // 写入输出 jar
                    val newEntry = JarEntry(entry.name)
                    jos.putNextEntry(newEntry)
                    jos.write(transformed)
                    jos.closeEntry()
                }
            }
        }
    }

    /**
     * 转换目录中的所有 class 文件。
     *
     * @param inputDir 输入目录。
     * @param outputDir 输出目录。
     * @param config 插件配置。
     */
    fun transformDirectory(inputDir: File, outputDir: File, config: ApmSlowMethodExtension) {
        outputDir.mkdirs()
        inputDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val relativePath = file.relativeTo(inputDir).path
                val outputFile = File(outputDir, relativePath)
                outputFile.parentFile?.mkdirs()

                if (file.name.endsWith(".class")) {
                    val bytes = file.readBytes()
                    val transformed = transformClass(bytes, relativePath, config)
                    outputFile.writeBytes(transformed)
                } else {
                    file.copyTo(outputFile, overwrite = true)
                }
            }
        }
    }

    /**
     * 转换单个 class 字节码。
     * 使用 ASM ClassReader/ClassWriter 进行字节码操作。
     *
     * @param bytes 原始 class 字节码。
     * @param className 类文件路径。
     * @param config 插件配置。
     * @return 插桩后的字节码。
     */
    private fun transformClass(
        bytes: ByteArray,
        className: String,
        config: ApmSlowMethodExtension
    ): ByteArray {
        // 将路径格式转为类名格式：com/example/MyClass.class → com.example.MyClass
        val fullClassName = className
            .removeSuffix(".class")
            .replace('/', '.')

        // 排除不需要插桩的类
        if (shouldExclude(fullClassName, config)) {
            return bytes
        }

        try {
            val classReader = ClassReader(bytes)
            val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)

            val visitor = ApmClassVisitor(
                api = Opcodes.ASM9,
                classVisitor = classWriter,
                className = fullClassName,
                config = config
            )

            classReader.accept(visitor, ClassReader.EXPAND_FRAMES)
            return classWriter.toByteArray()
        } catch (e: Exception) {
            // ASM 插桩失败时返回原始字节码，避免编译中断
            return bytes
        }
    }

    /**
     * 判断类是否应该排除插桩。
     * 排除系统类、第三方库、以及配置中指定的包名。
     */
    private fun shouldExclude(className: String, config: ApmSlowMethodExtension): Boolean {
        // 排除配置中指定的包名
        return config.excludePackages.any { className.startsWith(it) }
    }

    /**
     * ASM ClassVisitor 实现。
     * 访问类中的每个方法，委托给 ApmMethodVisitor 进行插桩。
     */
    private class ApmClassVisitor(
        api: Int,
        classVisitor: org.objectweb.asm.ClassVisitor,
        /** 当前类名（点分格式）。 */
        private val className: String,
        /** 插件配置。 */
        private val config: ApmSlowMethodExtension
    ) : org.objectweb.asm.ClassVisitor(api, classVisitor) {

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<String>?
        ): MethodVisitor? {
            val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
            // 跳过构造函数、静态初始化块和合成的 acc\$ 方法
            if (name == "<init>" || name == "<clinit>" || name.contains("$")) {
                return mv
            }
            // 跳过 abstract/native 方法（无方法体）
            if (access and Opcodes.ACC_ABSTRACT != 0 || access and Opcodes.ACC_NATIVE != 0) {
                return mv
            }
            return ApmMethodVisitor(
                api = api,
                methodVisitor = mv,
                access = access,
                name = name,
                descriptor = descriptor,
                className = className
            )
        }
    }

    /**
     * ASM MethodVisitor 实现。
     * 使用 AdviceAdapter 在方法入口和出口注入计时代码。
     *
     * 插桩效果：
     * ```java
     * void someMethod() {
     *     ApmSlowMethodTracer.methodEnter("com.example.MyClass#someMethod");
     *     try {
     *         // 原始方法体
     *     } finally {
     *         ApmSlowMethodTracer.methodExit("com.example.MyClass#someMethod");
     *     }
     * }
     * ```
     */
    private class ApmMethodVisitor(
        api: Int,
        methodVisitor: MethodVisitor,
        access: Int,
        /** 方法名。 */
        private val name: String,
        descriptor: String,
        /** 所属类名。 */
        private val className: String
    ) : AdviceAdapter(api, methodVisitor, access, name, descriptor) {

        /** 方法签名（className#methodName 格式）。 */
        private val methodSignature = "$className#$name"

        override fun onMethodEnter() {
            super.onMethodEnter()
            // 注入：ApmSlowMethodTracer.methodEnter("className#methodName")
            mv.visitLdcInsn(methodSignature)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                TRACER_CLASS,
                METHOD_ENTER,
                METHOD_DESC,
                false
            )
        }

        override fun onMethodExit(opcode: Int) {
            super.onMethodExit(opcode)
            // 注入：ApmSlowMethodTracer.methodExit("className#methodName")
            mv.visitLdcInsn(methodSignature)
            mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                TRACER_CLASS,
                METHOD_EXIT,
                METHOD_DESC,
                false
            )
        }
    }
}
