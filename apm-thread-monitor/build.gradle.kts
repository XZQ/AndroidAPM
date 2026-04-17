plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.didi.apm.threadmonitor"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
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
    testImplementation("junit:junit:4.13.2")
}
