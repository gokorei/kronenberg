plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.gokorei.kronenberg.cli.MainKt")
    applicationName = "kronenberg"
}

dependencies {
    implementation(project(":kronenberg-core"))
    implementation(project(":kronenberg-runner"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.clikt)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
