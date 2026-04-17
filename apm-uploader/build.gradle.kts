plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.apm.uploader"
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
    testImplementation(libs.junit)
}
