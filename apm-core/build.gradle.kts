plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.apm.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":apm-model"))
    implementation(project(":apm-storage"))
    api(project(":apm-uploader"))
    implementation(libs.lifecycle.process)
    testImplementation(libs.junit)
}
