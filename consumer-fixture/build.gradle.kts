plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":kronenberg-core"))
    implementation(project(":kronenberg-runner"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
