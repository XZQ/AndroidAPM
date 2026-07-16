plugins {
    // application 模块不套用 library 约定插件，直接走 catalog 别名
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.apm.slow-method")
}

android {
    namespace = "com.apm.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.apm.sample"
        minSdk = 24
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
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
    implementation(libs.android.core.ktx)
    implementation(libs.android.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
}
