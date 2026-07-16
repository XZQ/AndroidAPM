plugins {
    id("com.apm.android-library")
}

android {
    namespace = "com.apm.remoteconfig"
}

dependencies {
    api(project(":apm-core"))
    implementation(libs.gson)
    implementation(libs.tink.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
