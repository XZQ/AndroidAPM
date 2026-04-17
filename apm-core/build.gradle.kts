plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.didi.apm.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
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
    implementation(project(":apm-uploader"))
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    testImplementation("junit:junit:4.13.2")
}
