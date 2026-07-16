plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.launch"
}

dependencies {
    api(project(":apm-core"))
    implementation(project(":apm-model"))
    testImplementation(libs.junit)
}
