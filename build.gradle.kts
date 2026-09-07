plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.spotless)
    `maven-publish`
}

apiValidation {
    ignoredProjects.addAll(listOf("kronenberg-cli", "kronenberg-gradle-plugin"))
    nonPublicMarkers.addAll(listOf("com.gokorei.kronenberg.InternalKronenbergApi"))
}

allprojects {
    group = "com.gokorei.kronenberg"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "org.jetbrains.dokka")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "maven-publish")

    dependencies {
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        if (project.name != "kronenberg-gradle-plugin") {
            explicitApiWarning()
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.addAll(
                "-Xjsr305=strict",
                "-opt-in=kotlin.RequiresOptIn"
            )
        }
    }

    spotless {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**")
            ktlint()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    extensions.configure<PublishingExtension> {
        publications {
            // java-gradle-plugin registers its own plugin maven publication; only register mavenJava if not a gradle plugin project
            if (project.name != "kronenberg-gradle-plugin") {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])

                    pom {
                        name.set(project.name)
                        description.set("In-Memory K2 AST Mutation Testing Engine for Kotlin")
                        url.set("https://github.com/gokorei/kronenberg")

                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }

                        developers {
                            developer {
                                id.set("gokorei")
                                name.set("Davy Maddelein")
                            }
                        }

                        scm {
                            connection.set("scm:git:git://github.com/gokorei/kronenberg.git")
                            developerConnection.set("scm:git:ssh://github.com:gokorei/kronenberg.git")
                            url.set("https://github.com/gokorei/kronenberg")
                        }
                    }
                }
            }
        }
    }
}

