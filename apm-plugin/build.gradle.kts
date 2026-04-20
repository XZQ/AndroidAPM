plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-commons:9.5")
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
