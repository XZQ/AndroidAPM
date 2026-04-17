plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.apm.memory"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // JNI/CMake 构建配置：编译 libapm_dumper.so（fork 子进程 hprof dump）
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.18.1"
        }
    }
}

dependencies {
    implementation(project(":apm-core"))
    implementation(project(":apm-model"))
    implementation(libs.lifecycle.process)
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    testImplementation(libs.junit)
}
