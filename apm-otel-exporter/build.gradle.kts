plugins {
    // 仓库统一 Android library 约定：compileSdk/minSdk/Java 版本收敛在 build-logic
    id("com.apm.android-library")
}

android {
    // 模块自身的命名空间
    namespace = "com.apm.otel"
}

dependencies {
    api(project(":apm-model"))
    testImplementation(libs.junit)
}
