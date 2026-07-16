plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.webview"
}

dependencies {
    api(project(":apm-core"))
    implementation(project(":apm-model"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
