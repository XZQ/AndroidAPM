plugins {
    kotlin("jvm")
    `java-gradle-plugin`
}

dependencies {
    implementation("com.android.tools.build:gradle:7.4.2")
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-commons:9.5")
    implementation(kotlin("stdlib"))
}

gradlePlugin {
    plugins {
        create("apmSlowMethod") {
            id = "com.apm.slow-method"
            implementationClass = "com.apm.plugin.ApmSlowMethodPlugin"
        }
    }
}
