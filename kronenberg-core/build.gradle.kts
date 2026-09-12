plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

val generateMutatorDocs = tasks.register<JavaExec>("generateMutatorDocs") {
    group = "documentation"
    description = "Generates the Markdown AST mutators reference from in-code mutator definitions."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gokorei.kronenberg.doc.MutatorDocGeneratorKt")
    val docFile = rootProject.layout.projectDirectory.file("docs/wiki/Mutators-Reference.md")
    args = listOf(docFile.asFile.absolutePath)
    outputs.file(docFile)
}

val generateChangelog = tasks.register<JavaExec>("generateChangelog") {
    group = "documentation"
    description = "Generates CHANGELOG.md directly from docs/wiki/Release-Notes.md."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gokorei.kronenberg.doc.ChangelogGeneratorKt")
    val releaseNotesFile = rootProject.layout.projectDirectory.file("docs/wiki/Release-Notes.md")
    val changelogFile = rootProject.layout.projectDirectory.file("CHANGELOG.md")
    args = listOf(releaseNotesFile.asFile.absolutePath, changelogFile.asFile.absolutePath)
    inputs.file(releaseNotesFile)
    outputs.file(changelogFile)
}
