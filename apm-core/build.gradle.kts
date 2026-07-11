plugins {
    // 仓库统一 Android library 约定：compileSdk/minSdk/Java 版本收敛在 build-logic
    id("com.apm.android-library")
}

android {
    // 模块自身的命名空间
    namespace = "com.apm.core"
}

dependencies {
    api(project(":apm-model"))
    implementation(project(":apm-storage"))
    api(project(":apm-uploader"))
    implementation(libs.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
