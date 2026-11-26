plugins {
    // 插件版本统一取自主仓库的 version catalog，避免与主构建漂移
    alias(libs.plugins.android.library)
    id("com.apm.slow-method") version "0.2.0"
}

java {
    // consumer 验证固定使用 Java 21 toolchain，同时允许兼容的 Gradle 运行 JDK。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

android {
    namespace = "com.apm.consumer.smoke"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    // One published bundle must expose representative APIs from all transitive SDK modules.
    implementation("com.apm:apm-bundle:0.2.0")
}
