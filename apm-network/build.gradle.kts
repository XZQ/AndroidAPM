plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.apm.network"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":apm-core"))
    implementation(project(":apm-model"))
    compileOnly("com.squareup.okhttp3:okhttp:4.11.0")
    testImplementation(libs.junit)
}
