plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.benchmark)
}

/** Python executable used by the host-side benchmark budget verifier. */
val benchmarkPython = providers.gradleProperty("apmBenchmarkPython").orElse("python")

/** AndroidX JSON directory produced by the connected Release benchmark task. */
val connectedBenchmarkResults = layout.buildDirectory.dir(
    "outputs/connected_android_test_additional_output/releaseAndroidTest/connected"
)

/** Checked-in absolute release budgets for the measured SDK hot paths. */
val benchmarkBudgets = layout.projectDirectory.file("benchmark-budgets.json")

/** Applies the common fail-closed host verifier command to a Gradle Exec task. */
fun Exec.configureBenchmarkBudgetVerification() {
    inputs.file(benchmarkBudgets)
    inputs.dir(connectedBenchmarkResults).optional()
    outputs.upToDateWhen { false }

    // Resolve paths immediately before execution so missing outputs produce an actionable failure.
    doFirst {
        commandLine(
            benchmarkPython.get(),
            layout.projectDirectory.file("verify_benchmark_budgets.py").asFile.absolutePath,
            "--budgets",
            benchmarkBudgets.asFile.absolutePath,
            "--results",
            connectedBenchmarkResults.get().asFile.absolutePath
        )
    }
}

/** Host-only gate for already collected AndroidX benchmark JSON. */
tasks.register<Exec>("verifyBenchmarkBudgetsFromResults") {
    group = "verification"
    description = "Fails when existing AndroidX benchmark JSON exceeds checked-in release budgets."
    configureBenchmarkBudgetVerification()
}

/** End-to-end physical-device performance gate intended for a dedicated CI runner. */
tasks.register<Exec>("verifyReleasePerformanceBudgets") {
    group = "verification"
    description = "Runs Release microbenchmarks on a connected device and enforces release budgets."
    dependsOn("connectedReleaseAndroidTest")
    configureBenchmarkBudgetVerification()
}

java {
    // Benchmark 与 SDK 模块使用相同的 Java 17 编译/测试工具链。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
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
