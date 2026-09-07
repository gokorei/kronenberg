package com.gokorei.kronenberg.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet

/**
 * First-party Gradle plugin providing in-process K2 AST mutation testing (`kronenbergCheck`).
 */
public class KronenbergPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("kronenberg", KronenbergExtension::class.java)

        val auditTask =
            project.tasks.register("kronenbergCheck", KronenbergAuditTask::class.java) { task ->
                task.minScore.convention(extension.minScore)
                task.baselineTimeoutMs.convention(extension.baselineTimeoutMs)
                task.includeExtreme.convention(extension.includeExtreme)
                task.higherOrderMutants.convention(extension.higherOrderMutants)
                if (extension.maxMutants.isPresent) {
                    task.maxMutants.convention(extension.maxMutants)
                }
                task.enableCache.convention(extension.enableCache)

                val defaultReportDir = project.layout.buildDirectory.dir("reports/kronenberg")
                task.reportsDir.convention(
                    extension.reportsDir.orElse(defaultReportDir),
                )
            }

        project.afterEvaluate {
            val javaExtension = project.extensions.findByType(JavaPluginExtension::class.java)
            if (javaExtension != null) {
                val mainSourceSet = javaExtension.sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
                val testSourceSet = javaExtension.sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME)

                auditTask.configure { task ->
                    if (mainSourceSet != null) {
                        task.sourceFiles.from(
                            mainSourceSet.allSource.filter { it.extension == "kt" },
                        )
                        task.classpath.from(mainSourceSet.output.classesDirs)
                    }

                    if (testSourceSet != null) {
                        task.testFiles.from(
                            testSourceSet.allSource.filter { it.extension == "kt" },
                        )
                        task.classpath.from(testSourceSet.runtimeClasspath)
                        task.classpath.from(testSourceSet.output.classesDirs)
                        task.dependsOn(testSourceSet.classesTaskName)
                    }
                }
            }
        }
    }
}
