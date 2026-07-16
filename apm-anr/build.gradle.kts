plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.anr"

    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // JNI/CMake 构建配置：编译 libapm-anr.so（SIGQUIT 信号检测）
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(project(":apm-core"))
    implementation(project(":apm-model"))
    testImplementation(libs.junit)
}
