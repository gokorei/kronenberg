plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

gradlePlugin {
    plugins {
        create("kronenberg") {
            id = "com.gokorei.kronenberg"
            implementationClass = "com.gokorei.kronenberg.gradle.KronenbergPlugin"
            displayName = "Kronenberg Mutation Testing Gradle Plugin"
            description = "In-process K2 AST mutation testing plugin for Kotlin projects"
        }
    }
}

dependencies {
    implementation(project(":kronenberg-core"))
    implementation(project(":kronenberg-runner"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(gradleTestKit())
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
