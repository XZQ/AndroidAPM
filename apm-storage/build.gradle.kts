plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.storage"
}

dependencies {
    api(project(":apm-model"))
    testImplementation(libs.junit)
    // Robolectric 仅用于 SQLiteEventStore 的真实 SQLite 行为测试（test-only 依赖）
    testImplementation(libs.robolectric)
}
