plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(libs.jmc.flightrecorder)
    implementation(libs.jmc.rules)
    implementation(libs.jmc.rules.jdk)
}
