plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.benchmark)
}

android {
    namespace = "com.apm.benchmark"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.profiling.mode"] = "None"
    }

    testBuildType = "release"

    buildTypes {
        getByName("release") {
            // The Benchmark plugin disables coverage, enables AOT compilation,
            // signs with the debug key, and exports measurement JSON/traces.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(project(":apm-model"))
    androidTestImplementation(project(":apm-storage"))
    androidTestImplementation(libs.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
