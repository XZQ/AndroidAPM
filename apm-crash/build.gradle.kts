plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.didi.apm.crash"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // JNI/CMake 构建配置：编译 libapm_crash.so 信号处理器
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
    testImplementation("junit:junit:4.13.2")
}
