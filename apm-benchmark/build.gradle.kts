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

/** Checked-in end-to-end device-soak budgets for smoke, 24-hour, and 72-hour profiles. */
val deviceSoakBudgets = layout.projectDirectory.file("device-soak-budgets.json")

/** Versioned OEM/API/profile coverage contract for managed physical-device validation. */
val deviceLabMatrix = layout.projectDirectory.file("device-lab-matrix.json")

/** Host artifact supplied to the standalone device-soak verification task. */
val deviceSoakResults = providers.gradleProperty("apmDeviceSoakResults")

/** Named budget profile supplied with a host device-soak artifact. */
val deviceSoakProfile = providers.gradleProperty("apmDeviceSoakProfile")

/** Comma-separated explicit result artifacts supplied to the aggregate matrix gate. */
val deviceLabResults = providers.gradleProperty("apmDeviceLabResults")

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

/** Fail-closed verifier for an already collected end-to-end physical-device campaign. */
tasks.register<Exec>("verifyDeviceSoakFromResults") {
    group = "verification"
    description = "Checks a physical-device smoke/24h/72h artifact against checked-in budgets."
    inputs.file(deviceSoakBudgets)
    outputs.upToDateWhen { false }

    // Properties stay mandatory so a missing or stale implicit artifact can never pass.
    doFirst {
        val resultPath = deviceSoakResults.orNull
            ?: error("Set -PapmDeviceSoakResults=<result.json>")
        val profile = deviceSoakProfile.orNull
            ?: error("Set -PapmDeviceSoakProfile=smoke|24h|72h")
        commandLine(
            benchmarkPython.get(),
            layout.projectDirectory.file("verify_device_soak.py").asFile.absolutePath,
            "--budgets",
            deviceSoakBudgets.asFile.absolutePath,
            "--results",
            file(resultPath).absolutePath,
            "--profile",
            profile
        )
    }
}

/** Host-only schema/policy gate; this validates the plan without claiming physical evidence. */
tasks.register<Exec>("verifyDeviceLabMatrix") {
    group = "verification"
    description = "Validates the checked-in OEM/API/profile device-lab matrix plan."
    inputs.file(deviceLabMatrix)
    inputs.file(deviceSoakBudgets)
    outputs.upToDateWhen { false }

    doFirst {
        commandLine(
            benchmarkPython.get(),
            layout.projectDirectory.file("verify_device_matrix.py").asFile.absolutePath,
            "--matrix",
            deviceLabMatrix.asFile.absolutePath,
            "--budgets",
            deviceSoakBudgets.asFile.absolutePath
        )
    }
}

/** Fail-closed aggregate gate for a complete set of explicit managed-device artifacts. */
tasks.register<Exec>("verifyDeviceLabCoverageFromResults") {
    group = "verification"
    description = "Checks exact APK/source provenance and complete device-lab matrix coverage."
    inputs.file(deviceLabMatrix)
    inputs.file(deviceSoakBudgets)
    outputs.upToDateWhen { false }

    doFirst {
        val rawResults = deviceLabResults.orNull
            ?: error("Set -PapmDeviceLabResults=<result1.json,result2.json,...>")
        val resultFiles = rawResults.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { file(it) }
        require(resultFiles.isNotEmpty()) {
            "-PapmDeviceLabResults must contain at least one explicit result path"
        }
        commandLine(
            buildList {
                add(benchmarkPython.get())
                add(layout.projectDirectory.file("verify_device_matrix.py").asFile.absolutePath)
                add("--matrix")
                add(deviceLabMatrix.asFile.absolutePath)
                add("--budgets")
                add(deviceSoakBudgets.asFile.absolutePath)
                add("--results")
                addAll(resultFiles.map { it.absolutePath })
            }
        )
    }
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
    androidTestImplementation(project(":apm-core"))
    androidTestImplementation(project(":apm-model"))
    androidTestImplementation(project(":apm-storage"))
    androidTestImplementation(libs.benchmark.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
