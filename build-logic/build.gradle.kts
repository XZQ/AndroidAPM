// 编译 precompiled script convention plugin（com.apm.android-library）。
plugins {
    `kotlin-dsl`
}

dependencies {
    // AGP 与 KGP 需要出现在 convention plugin 的编译类路径上，版本取自共享 version catalog
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}
