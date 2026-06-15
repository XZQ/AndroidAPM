plugins {
    id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
    `java-gradle-plugin`
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
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
