plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.apm.otel"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    compileOnly(project(":apm-core"))
    compileOnly(project(":apm-model"))
    // OpenTelemetry SDK — compileOnly 软依赖，宿主自行提供
    compileOnly(libs.otel.sdk)
    compileOnly(libs.otel.exporter.otlp)
    testImplementation(project(":apm-core"))
    testImplementation(project(":apm-model"))
    testImplementation(libs.junit)
}
