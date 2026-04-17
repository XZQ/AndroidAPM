plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.apm.render"
    compileSdk = 34
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":apm-core"))
    implementation(project(":apm-model"))
    testImplementation(libs.junit)
}
