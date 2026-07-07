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

android {
    compileSdk = apmCompileSdk

    defaultConfig {
        minSdk = apmMinSdk
    }

    compileOptions {
        // 字节码目标保持 Java 11，与项目文档约定一致
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
