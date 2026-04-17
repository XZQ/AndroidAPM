plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.didi.apm.memory"
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
    implementation(project(":apm-core"))
    implementation(project(":apm-model"))
    implementation("androidx.lifecycle:lifecycle-process:2.6.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    testImplementation("junit:junit:4.13.2")
}
