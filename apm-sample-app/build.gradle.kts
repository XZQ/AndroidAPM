plugins {
    alias(libs.plugins.android.application)
    id("com.apm.slow-method")
}

java {
    // Gradle 可由兼容的新 JDK 启动，应用编译仍固定使用 Java 21 toolchain。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

android {
    namespace = "com.apm.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apm.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Use a short-lived staging credential only. BuildConfig values are included in the APK.
        // The path is required: HttpApmUploader posts to the endpoint as-is and the collector
        // only accepts event batches on POST /v1/events.
        val strictEndpoint = (project.findProperty("apm.endpoint") as String?)
            ?: "https://43.133.227.175/v1/events"
        val ingestKey = (project.findProperty("apm.ingestKey") as String?) ?: ""
        buildConfigField("String", "APM_STRICT_ENDPOINT", "\"$strictEndpoint\"")
        buildConfigField("String", "APM_INGEST_KEY", "\"$ingestKey\"")
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
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
    implementation(libs.android.appcompat)
}
