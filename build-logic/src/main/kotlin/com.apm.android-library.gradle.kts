// 仓库统一的 Android library 约定插件。
// 收敛所有监控/基础模块重复的 android {} 配置：compileSdk、minSdk、Java 版本。
// jvmTarget、consumer-rules、maven-publish 由根构建的 subprojects 块按插件类型统一注入。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/** 统一编译 SDK 版本，升级时仅改此处。 */
val apmCompileSdk = 34

/** 统一最低支持 SDK 版本。 */
val apmMinSdk = 24

java {
    // 编译器和测试运行时统一使用 Java 17，不受启动 Gradle 的兼容 JDK 版本影响。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    compileSdk = apmCompileSdk

    defaultConfig {
        minSdk = apmMinSdk
    }

    compileOptions {
        // Java 与 Kotlin 产物统一生成 Java 17 字节码。
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
