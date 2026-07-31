plugins {
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
    alias(libs.plugins.binary.compatibility.validator)
    `java-gradle-plugin`
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(kotlin("stdlib"))
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        create("apmSlowMethod") {
            id = "com.apm.slow-method"
            implementationClass = "com.apm.plugin.ApmSlowMethodPlugin"
        }
    }
}
