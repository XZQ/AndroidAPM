plugins {
    // 插件版本统一取自主仓库的 version catalog，避免与主构建漂移
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

java {
    // consumer 验证固定使用 Java 17 toolchain，同时允许兼容的 Gradle 运行 JDK。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

android {
    namespace = "com.apm.consumer.smoke"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.apm:apm-memory:0.1.0")
    implementation("com.apm:apm-network:0.1.0")
    implementation("com.apm:apm-otel-exporter:0.1.0")
    implementation("com.apm:apm-remote-config:0.1.0")
}
