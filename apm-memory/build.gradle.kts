plugins {
    // 仓库统一 Android library 约定：compileSdk/minSdk/Java 版本收敛在 build-logic
    id("com.apm.android-library")
}

android {
    // 模块自身的命名空间
    namespace = "com.apm.memory"

    defaultConfig {
        // 携带 JNI 的模块需要显式声明支持的 ABI
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    // JNI/CMake 构建配置：编译 libapm_dumper.so（fork 子进程 hprof dump）
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
    api(libs.lifecycle.process)
    api(libs.fragment.ktx)
    testImplementation(libs.junit)
}
