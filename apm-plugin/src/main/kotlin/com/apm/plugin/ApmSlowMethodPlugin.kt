package com.apm.plugin

import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.internal.pipeline.TransformManager
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * APM 慢方法检测 Gradle 插件。
 * 通过 ASM 字节码插桩在编译期注入方法计时代码。
 *
 * ## 插桩原理（对标微信 Matrix）
 * 1. 注册 Transform，拦截所有 class 文件
 * 2. 使用 ASM 访问每个方法的入口和出口
 * 3. 在方法入口注入：ApmSlowMethodTracer.onMethodEnter(className, methodName)
 * 4. 在方法出口注入：ApmSlowMethodTracer.onMethodExit(className, methodName)
 * 5. 运行时 Tracer 自动计算方法耗时并上报
 *
 * ## 使用方式
 * 在 app/build.gradle 中：
 * ```groovy
 * apply plugin: 'com.apm.slow-method'
 * apmSlowMethod {
 *     thresholdMs = 300   // 方法耗时阈值
 *     excludePackages = ["androidx.", "google."]  // 排除的包名
 * }
 * ```
 */
class ApmSlowMethodPlugin : Plugin<Project> {

    /** 插件扩展配置。 */
    lateinit var extension: ApmSlowMethodExtension

    override fun apply(project: Project) {
        // 注册插件扩展配置
        extension = project.extensions.create(
            "apmSlowMethod",
            ApmSlowMethodExtension::class.java
        )

        // 注册 Transform
        val appExtension = project.extensions.findByType(
            com.android.build.gradle.AppExtension::class.java
        )
        appExtension?.registerTransform(
            ApmSlowMethodTransform(extension)
        )

        project.logger.lifecycle("APM SlowMethod Plugin applied")
    }
}

/**
 * 慢方法插件配置扩展。
 */
open class ApmSlowMethodExtension {
    /** 方法耗时阈值（毫秒），超过此值上报。 */
    var thresholdMs: Long = 300L

    /** 需要排除的包名前缀列表（不插桩）。 */
    var excludePackages: List<String> = DEFAULT_EXCLUDE_PACKAGES

    /** 是否只插桩主线程方法。 */
    var mainThreadOnly: Boolean = false

    /** 是否启用插件。 */
    var enabled: Boolean = true

    companion object {
        /** 默认排除的包名。 */
        private val DEFAULT_EXCLUDE_PACKAGES = listOf(
            "androidx.",
            "android.",
            "com.google.",
            "kotlin.",
            "java.",
            "javax.",
            "dalvik."
        )
    }
}

/**
 * APM 慢方法 Transform。
 * 拦截编译后的 class 文件，通过 ASM 插入方法计时代码。
 */
class ApmSlowMethodTransform(
    /** 插件配置。 */
    private val config: ApmSlowMethodExtension
) : Transform() {

    override fun getName(): String = TRANSFORM_NAME

    override fun getInputTypes(): Set<QualifiedContent.ContentType> {
        return TransformManager.CONTENT_CLASS
    }

    override fun getScopes(): MutableSet<in QualifiedContent.Scope> {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    override fun isIncremental(): Boolean = true

    /**
     * 执行 Transform：遍历所有 class 文件并进行插桩。
     */
    override fun transform(transformInvocation: TransformInvocation) {
        if (!config.enabled) {
            // 插件禁用时直接复制
            transformInvocation.outputProvider.deleteAll()
            copyInputs(transformInvocation)
            return
        }

        // 处理所有输入
        transformInvocation.inputs.forEach { input ->
            // 处理 jar 包
            input.jarInputs.forEach { jarInput ->
                val inputJar = jarInput.file
                val outputJar = transformInvocation.outputProvider.getContentLocation(
                    jarInput.name,
                    jarInput.contentTypes,
                    jarInput.scopes,
                    Format.JAR
                )
                // 跳过不需要插桩的 jar
                if (shouldExcludeJar(inputJar.name)) {
                    inputJar.copyTo(outputJar, overwrite = true)
                } else {
                    ApmClassTransformer.transformJar(inputJar, outputJar, config)
                }
            }

            // 处理目录
            input.directoryInputs.forEach { dirInput ->
                val inputDir = dirInput.file
                val outputDir = transformInvocation.outputProvider.getContentLocation(
                    dirInput.name,
                    dirInput.contentTypes,
                    dirInput.scopes,
                    Format.DIRECTORY
                )
                ApmClassTransformer.transformDirectory(inputDir, outputDir, config)
            }
        }
    }

    /**
     * 判断 jar 是否应该排除。
     * 排除 Android 框架和第三方库。
     */
    private fun shouldExcludeJar(jarName: String): Boolean {
        val lower = jarName.lowercase()
        return EXCLUDE_JAR_PATTERNS.any { lower.contains(it) }
    }

    /**
     * 禁用时直接复制所有输入到输出。
     */
    private fun copyInputs(transformInvocation: TransformInvocation) {
        transformInvocation.inputs.forEach { input ->
            input.jarInputs.forEach { jarInput ->
                val output = transformInvocation.outputProvider.getContentLocation(
                    jarInput.name, jarInput.contentTypes, jarInput.scopes, Format.JAR
                )
                jarInput.file.copyTo(output, overwrite = true)
            }
            input.directoryInputs.forEach { dirInput ->
                val output = transformInvocation.outputProvider.getContentLocation(
                    dirInput.name, dirInput.contentTypes, dirInput.scopes, Format.DIRECTORY
                )
                dirInput.file.copyRecursively(output, overwrite = true)
            }
        }
    }

    companion object {
        /** Transform 名称。 */
        private const val TRANSFORM_NAME = "apmSlowMethod"
        /** 排除的 jar 关键词。 */
        private val EXCLUDE_JAR_PATTERNS = listOf(
            "androidx", "support", "kotlin", "google", "okhttp",
            "retrofit", "glide", "picasso", "rxjava"
        )
    }
}
