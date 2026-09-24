package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Execution outcome from running trusted project bytecode in the host JVM.
 */
public data class RunnerOutcome(
    val status: MutantStatus,
    val executionTimeMs: Long,
    val stdout: String = "",
    val stderr: String = "",
    val failureMessage: String? = null,
)

/**
 * In-process virtual-thread execution contract for trusted local snippets.
 */
public interface FastSnippetRunner : AutoCloseable {
    /**
     * Executes trusted local bytecode in [classesDir] with a fresh URLClassLoader and Virtual Thread.
     */
    public fun run(
        classesDir: Path,
        mainClass: String = "SnippetKt",
        timeoutMs: Long = 2000L,
        extraClasspath: List<String> = emptyList(),
    ): RunnerOutcome
}

/**
 * Default trusted-local runner using fresh URLClassLoaders and Java 21 Virtual Threads.
 */
public class DefaultFastSnippetRunner(
    threadPoolSize: Int = 4,
) : FastSnippetRunner {
    private val executor: ExecutorService =
        try {
            Executors.newVirtualThreadPerTaskExecutor()
        } catch (_: Throwable) {
            Executors.newFixedThreadPool(threadPoolSize) { runnable ->
                Thread(runnable, "FastSnippetRunner-Worker").apply { isDaemon = true }
            }
        }
    private val timeoutExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "FastSnippetRunner-Timeout").apply { isDaemon = true }
        }

    override fun run(
        classesDir: Path,
        mainClass: String,
        timeoutMs: Long,
        extraClasspath: List<String>,
    ): RunnerOutcome {
        val startNanos = System.nanoTime()
        val task =
            Callable {
                globalStateLock.lockInterruptibly()
                try {
                    executeSnippet(
                        classesDir = classesDir,
                        mainClass = mainClass,
                        timeoutMs = timeoutMs,
                        extraClasspath = extraClasspath,
                    )
                } finally {
                    globalStateLock.unlock()
                }
            }

        return try {
            executor.submit(task).get()
        } catch (e: ExecutionException) {
            failedOutcome(startNanos, e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failedOutcome(startNanos, e)
        } catch (e: RejectedExecutionException) {
            failedOutcome(startNanos, e)
        }
    }

    private fun executeSnippet(
        classesDir: Path,
        mainClass: String,
        timeoutMs: Long,
        extraClasspath: List<String>,
    ): RunnerOutcome {
        val startNanos = System.nanoTime()
        val classLoader = createClassLoader(classesDir, extraClasspath, this::class.java.classLoader)
        val capturedOut = ByteArrayOutputStream()
        val capturedErr = ByteArrayOutputStream()
        val customOut = PrintStream(capturedOut, true, Charsets.UTF_8.name())
        val customErr = PrintStream(capturedErr, true, Charsets.UTF_8.name())
        val globalState = captureGlobalState()
        val timedOut = AtomicBoolean(false)
        var timeoutTask: ScheduledFuture<*>? = null
        var status = MutantStatus.SURVIVED
        var failureMessage: String? = null

        try {
            System.setOut(customOut)
            System.setErr(customErr)
            val worker = Thread.currentThread()
            timeoutTask =
                timeoutExecutor.schedule(
                    {
                        timedOut.set(true)
                        worker.interrupt()
                    },
                    timeoutMs,
                    TimeUnit.MILLISECONDS,
                )
            invokeSnippet(classLoader, classesDir, mainClass)
            if (timedOut.get()) {
                status = MutantStatus.TIMED_OUT
                failureMessage = "Execution timed out after ${timeoutMs}ms"
            }
        } catch (e: Throwable) {
            if (timedOut.get()) {
                status = MutantStatus.TIMED_OUT
                failureMessage = "Execution timed out after ${timeoutMs}ms"
            } else {
                status = MutantStatus.KILLED
                val target = (e as? InvocationTargetException)?.targetException ?: e
                failureMessage = "${target.javaClass.simpleName}: ${target.message.orEmpty()}"
            }
        } finally {
            timeoutTask?.cancel(false)
            try {
                restoreGlobalState(globalState)
            } finally {
                runCatching { customOut.close() }
                runCatching { customErr.close() }
                Thread.interrupted()
                runCatching { classLoader.close() }
            }
        }

        val durationMs = (System.nanoTime() - startNanos) / 1_000_000
        return RunnerOutcome(
            status = status,
            executionTimeMs = durationMs,
            stdout = capturedOut.toString(Charsets.UTF_8.name()).trim(),
            stderr = capturedErr.toString(Charsets.UTF_8.name()).trim(),
            failureMessage = failureMessage,
        )
    }

    private fun createClassLoader(
        classesDir: Path,
        extraClasspath: List<String>,
        parentClassLoader: ClassLoader,
    ): URLClassLoader {
        val classpath =
            listOf(classesDir.toUri().toURL()) +
                extraClasspath.filter { it.isNotBlank() }.map { File(it).toURI().toURL() }
        return object : URLClassLoader(classpath.toTypedArray(), parentClassLoader) {
            override fun loadClass(
                name: String,
                resolve: Boolean,
            ): Class<*> {
                val classFile = classesDir.resolve(name.replace('.', '/') + ".class").toFile()
                if (classFile.exists()) {
                    val loaded = findLoadedClass(name)
                    if (loaded != null) return loaded
                    return findClass(name)
                }
                return super.loadClass(name, resolve)
            }
        }
    }

    private fun invokeSnippet(
        classLoader: URLClassLoader,
        classesDir: Path,
        mainClass: String,
    ) {
        val clazz =
            try {
                classLoader.loadClass(mainClass)
            } catch (_: ClassNotFoundException) {
                val candidate =
                    classesDir
                        .toFile()
                        .walkTopDown()
                        .firstOrNull { it.isFile && it.extension == "class" && !it.name.contains('$') }
                val relativeClass =
                    candidate
                        ?.relativeTo(classesDir.toFile())
                        ?.path
                        ?.removeSuffix(".class")
                        ?.replace('/', '.')
                        ?: "SnippetKt"
                classLoader.loadClass(relativeClass)
            }
        val entryMethod =
            try {
                clazz.getMethod("main", Array<String>::class.java)
            } catch (_: NoSuchMethodException) {
                clazz.getMethod("main")
            }
        val invokeArgs =
            if (entryMethod.parameterCount == 1) arrayOf<Any>(emptyArray<String>()) else emptyArray()
        entryMethod.invoke(null, *invokeArgs)
    }

    override fun close() {
        timeoutExecutor.shutdownNow()
        executor.shutdownNow()
    }

    private companion object {
        private val globalStateLock = ReentrantLock()
    }
}

private data class GlobalStateSnapshot(
    val standardOutput: PrintStream,
    val standardError: PrintStream,
    val properties: Properties,
    val propertyValues: Map<String, String>,
)

private fun captureGlobalState(): GlobalStateSnapshot {
    val properties = System.getProperties()
    return GlobalStateSnapshot(
        standardOutput = System.out,
        standardError = System.err,
        properties = properties,
        propertyValues = properties.stringPropertyNames().associateWith { properties.getProperty(it) },
    )
}

private fun restoreGlobalState(snapshot: GlobalStateSnapshot) {
    val failures = mutableListOf<Throwable>()

    fun attempt(restore: () -> Unit) {
        runCatching(restore).exceptionOrNull()?.let(failures::add)
    }
    attempt { System.setOut(snapshot.standardOutput) }
    attempt { System.setErr(snapshot.standardError) }
    attempt {
        System.setProperties(snapshot.properties)
        restorePropertyDelta(snapshot.properties, snapshot.propertyValues)
    }
    failures.firstOrNull()?.let { firstFailure ->
        failures.drop(1).forEach(firstFailure::addSuppressed)
        throw firstFailure
    }
}

private fun restorePropertyDelta(
    properties: Properties,
    propertyValues: Map<String, String>,
) {
    val currentNames = properties.stringPropertyNames().toSet()
    (currentNames - propertyValues.keys).forEach(properties::remove)
    propertyValues.forEach { (key, value) ->
        if (properties.getProperty(key) != value) {
            properties.setProperty(key, value)
        }
    }
}

private fun failedOutcome(
    startNanos: Long,
    error: Throwable,
): RunnerOutcome {
    val target = (error as? InvocationTargetException)?.targetException ?: error
    return RunnerOutcome(
        status = MutantStatus.KILLED,
        executionTimeMs = (System.nanoTime() - startNanos) / 1_000_000,
        failureMessage = "${target.javaClass.simpleName}: ${target.message.orEmpty()}",
    )
}
