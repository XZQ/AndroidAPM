plugins {
    // Remote configuration is an Android library because its durable cache uses SharedPreferences.
    id("com.apm.android-library")
}

android {
    /** Module namespace for signed remote configuration APIs. */
    namespace = "com.apm.remoteconfig"
}

dependencies {
    api(project(":apm-core"))
    implementation(libs.gson)
    implementation(libs.tink.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
