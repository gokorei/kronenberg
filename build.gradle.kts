plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.spotless)
    alias(libs.plugins.kover)
    `maven-publish`
}

val detektTooling = configurations.create("detektTooling")

dependencies {
    detektTooling(libs.detekt.cli)
    dokka(project(":kronenberg-core"))
    dokka(project(":kronenberg-runner"))
    dokka(project(":kronenberg-cli"))
    dokka(project(":kronenberg-gradle-plugin"))
    kover(project(":kronenberg-core"))
    kover(project(":kronenberg-runner"))
    kover(project(":kronenberg-cli"))
    kover(project(":kronenberg-gradle-plugin"))
}

kover {
    reports {
        verify {
            rule {
                minBound(80) // Enforce minimum 80% line coverage threshold across project
            }
        }
    }
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
    apply(plugin = "org.jetbrains.kotlinx.kover")
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

    tasks.matching { it.name == "processResources" }.configureEach {
        dependsOn(rootProject.tasks.named("generateVersionResource"))
    }

    extensions.configure<SourceSetContainer> {
        named("main") {
            resources.srcDir(rootProject.layout.buildDirectory.dir("generated/version"))
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

val generateVersionResource = tasks.register("generateVersionResource") {
    group = "build"
    description = "Generates a version descriptor resource from project.version."
    inputs.property("version", provider { project.version.toString() })
    val outDir = layout.buildDirectory.dir("generated/version")
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "kronenberg-version.txt").writeText(project.version.toString())
    }
}

val generateMutatorDocs = tasks.register("generateMutatorDocs") {
    group = "documentation"
    description = "Generates the Markdown AST mutators reference from in-code mutator definitions."
    dependsOn(":kronenberg-core:generateMutatorDocs")
}

val generateChangelog = tasks.register("generateChangelog") {
    group = "documentation"
    description = "Generates CHANGELOG.md directly from docs/wiki/Release-Notes.md."
    dependsOn(":kronenberg-core:generateChangelog")
}

val bumpVersion = tasks.register("bumpVersion") {
    group = "publishing"
    description = "Bumps the project version across build.gradle.kts, Release-Notes.md, and CHANGELOG.md. Usage: ./gradlew bumpVersion -Pto=1.0.0"
    doLast {
        val newVersion = (project.findProperty("to") ?: project.findProperty("newVersion"))?.toString()
            ?: throw GradleException("Please supply target version via -Pto=X.Y.Z (e.g. ./gradlew bumpVersion -Pto=1.0.0)")

        if (!newVersion.matches(Regex("""^\d+\.\d+\.\d+(-[a-zA-Z0-9.]+)?$"""))) {
            throw GradleException("Invalid semantic version format: '$newVersion'. Expected format: X.Y.Z")
        }

        val today = java.time.LocalDate.now().toString()
        val buildGradle = file("build.gradle.kts")
        val releaseNotes = file("docs/wiki/Release-Notes.md")

        val buildText = buildGradle.readText()
        if (!buildText.contains(Regex("""version\s*=\s*"[^"]+""""))) {
            throw GradleException("Could not find version declaration in build.gradle.kts")
        }
        val updatedBuildText = buildText.replaceFirst(
            Regex("""version\s*=\s*"[^"]+""""),
            """version = "$newVersion""""
        )

        var updatedNotesText: String? = null
        if (releaseNotes.exists()) {
            val notesText = releaseNotes.readText()
            if (!notesText.contains("## Next")) {
                throw GradleException("docs/wiki/Release-Notes.md does not contain a '## Next' heading to promote.")
            }
            val nextSkeleton = """## Next

### New Features

### Bug Fixes

### Improvements

---

## v$newVersion — $today"""
            updatedNotesText = notesText.replaceFirst(Regex("""## Next"""), nextSkeleton)
        }

        buildGradle.writeText(updatedBuildText)
        logger.lifecycle("Updated build.gradle.kts version -> $newVersion")

        if (updatedNotesText != null) {
            releaseNotes.writeText(updatedNotesText)
            logger.lifecycle("Promoted ## Next to ## v$newVersion — $today in docs/wiki/Release-Notes.md")
        }
    }
    finalizedBy(generateChangelog)
}

val detektCheck = tasks.register<JavaExec>("detektCheck") {
    group = "verification"
    description = "Runs Detekt static code analysis against all module sources."
    classpath = detektTooling
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    val configFile = layout.projectDirectory.file("config/detekt/detekt.yml")
    val baselineFile = layout.projectDirectory.file("config/detekt/baseline.xml")
    inputs.file(configFile)
    if (baselineFile.asFile.exists()) {
        inputs.file(baselineFile)
    }
    subprojects.forEach { sub ->
        inputs.dir(sub.file("src/main/kotlin"))
        inputs.dir(sub.file("src/test/kotlin"))
    }
    outputs.file(layout.buildDirectory.file("reports/detekt/detekt.xml"))
    val inputPaths = subprojects.flatMap { sub ->
        listOf(sub.file("src/main/kotlin"), sub.file("src/test/kotlin")).filter { it.exists() }
    }.joinToString(",") { it.absolutePath }

    val baseArgs = mutableListOf(
        "--input", inputPaths,
        "--config", configFile.asFile.absolutePath,
        "--report", "xml:${layout.buildDirectory.file("reports/detekt/detekt.xml").get().asFile.absolutePath}"
    )
    if (baselineFile.asFile.exists() && baselineFile.asFile.length() > 0) {
        baseArgs.addAll(listOf("--baseline", baselineFile.asFile.absolutePath))
    }
    args = baseArgs
}

val detektBaseline = tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "Generates or updates Detekt code smell baseline."
    classpath = detektTooling
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    val configFile = layout.projectDirectory.file("config/detekt/detekt.yml")
    val baselineFile = layout.projectDirectory.file("config/detekt/baseline.xml")
    val inputPaths = subprojects.flatMap { sub ->
        listOf(sub.file("src/main/kotlin"), sub.file("src/test/kotlin")).filter { it.exists() }
    }.joinToString(",") { it.absolutePath }
    args = listOf(
        "--input", inputPaths,
        "--config", configFile.asFile.absolutePath,
        "--baseline", baselineFile.asFile.absolutePath,
        "--create-baseline"
    )
}

tasks.named("check") {
    dependsOn(detektCheck)
    dependsOn("koverVerify")
}

