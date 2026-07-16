plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.otel"
}

dependencies {
    api(project(":apm-model"))
    testImplementation(libs.junit)
}
