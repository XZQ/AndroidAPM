plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.didi.apm.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.didi.apm.sample"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
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
    implementation(project(":apm-core"))
    implementation(project(":apm-memory"))
    implementation(project(":apm-crash"))
    implementation(project(":apm-anr"))
    implementation(project(":apm-launch"))
    implementation(project(":apm-network"))
    implementation(project(":apm-fps"))
    implementation(project(":apm-slow-method"))
    implementation(project(":apm-io"))
    implementation(project(":apm-thread-monitor"))
    implementation(project(":apm-battery"))
    implementation(project(":apm-sqlite"))
    implementation(project(":apm-webview"))
    implementation(project(":apm-ipc"))
    implementation(project(":apm-gc-monitor"))
    implementation(project(":apm-render"))
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.appcompat:appcompat:1.4.1")
    implementation("com.google.android.material:material:1.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:4.8.0")
}
