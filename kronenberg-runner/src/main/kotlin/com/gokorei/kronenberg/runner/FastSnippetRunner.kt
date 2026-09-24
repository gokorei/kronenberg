package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Thread-safe PrintStream interceptor that captures stdout/stderr during in-process execution.
 */
public class ThreadLocalPrintStream(
    private val defaultStream: PrintStream,
) : PrintStream(
        object : OutputStream() {
            override fun write(b: Int) {
                val target = activeTarget.get() ?: defaultStream
                target.write(b)
            }

            override fun write(
                b: ByteArray,
                off: Int,
                len: Int,
            ) {
                val target = activeTarget.get() ?: defaultStream
                target.write(b, off, len)
            }

            override fun flush() {
                val target = activeTarget.get() ?: defaultStream
                target.flush()
            }
        },
        true,
        Charsets.UTF_8.name(),
    ) {
    override fun close() {
        flush()
    }

    public companion object {
        private val activeTarget = InheritableThreadLocal<PrintStream?>()

        public fun <T> withCapture(
            stream: PrintStream,
            block: () -> T,
        ): T {
            val prev = activeTarget.get()
            activeTarget.set(stream)
            return try {
                block()
            } finally {
                activeTarget.set(prev)
            }
        }

        @Volatile
        private var installed = false

        @Synchronized
        public fun install() {
            if (!installed) {
                val outInterceptor = ThreadLocalPrintStream(System.out)
                val errInterceptor = ThreadLocalPrintStream(System.err)
                System.setOut(outInterceptor)
                System.setErr(errInterceptor)
                installed = true
            }
        }
    }
}

/**
 * Execution outcome from running compiled bytecode inside a virtual-thread sandbox.
 */
public data class RunnerOutcome(
    val status: MutantStatus,
    val executionTimeMs: Long,
    val stdout: String = "",
    val stderr: String = "",
    val failureMessage: String? = null,
)

private class RunnerInfrastructureFailure(
    override val cause: Throwable,
) : RuntimeException(cause)

private class TestExecutionFailure(
    override val cause: Throwable,
) : RuntimeException(cause)

/**
 * In-process virtual-thread snippet execution sandbox contract.
 */
public interface FastSnippetRunner : AutoCloseable {
    /**
     * Executes bytecode in [classesDir] with isolated URLClassLoader and Virtual Thread sandbox.
     */
    public fun run(
        classesDir: Path,
        mainClass: String = "SnippetKt",
        timeoutMs: Long = 2000L,
        extraClasspath: List<String> = emptyList(),
    ): RunnerOutcome
}

/**
 * Default sandbox runner using isolated URLClassLoaders and Java 21 Virtual Threads.
 */
@Suppress("TooManyFunctions")
public class DefaultFastSnippetRunner(
    threadPoolSize: Int = 4,
) : FastSnippetRunner {
    private val executor: ExecutorService =
        try {
            Executors.newVirtualThreadPerTaskExecutor()
        } catch (_: Throwable) {
            Executors.newFixedThreadPool(threadPoolSize) { r ->
                Thread(r, "FastSnippetRunner-Worker").apply { isDaemon = true }
            }
        }

    init {
        ThreadLocalPrintStream.install()
    }

    override fun run(
        classesDir: Path,
        mainClass: String,
        timeoutMs: Long,
        extraClasspath: List<String>,
    ): RunnerOutcome {
        val startNanos = System.nanoTime()
        val fullCp =
            runCatching {
                listOf(classesDir.toUri().toURL()) +
                    extraClasspath.filter { it.isNotBlank() }.map { File(it).toURI().toURL() }
            }
        if (fullCp.isFailure) {
            val failure = fullCp.exceptionOrNull() ?: IllegalStateException("Classpath setup failed")
            return failureOutcome(startNanos, statusForFailure(failure), failureMessage(failure), "")
        }

        val capturedOut = ByteArrayOutputStream()
        val customPrintStream = PrintStream(capturedOut, true, Charsets.UTF_8.name())
        val task = Callable { executeSnippet(classesDir, mainClass, fullCp.getOrThrow(), customPrintStream) }

        val submitted = runCatching { executor.submit(task) }
        val future = submitted.getOrNull()
        return if (future == null) {
            failureFromException(
                startNanos,
                submitted.exceptionOrNull() ?: IllegalStateException("Runner rejected execution"),
                capturedOut,
            )
        } else {
            val completed = runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
            val failure = completed.exceptionOrNull()
            when {
                failure == null -> {
                    RunnerOutcome(
                        status = MutantStatus.SURVIVED,
                        executionTimeMs = elapsedMs(startNanos),
                        stdout = capturedOut.toString(Charsets.UTF_8.name()).trim(),
                    )
                }

                failure is TimeoutException -> {
                    future.cancel(true)
                    RunnerOutcome(
                        status = MutantStatus.TIMED_OUT,
                        executionTimeMs = elapsedMs(startNanos),
                        failureMessage = "Execution timed out after ${timeoutMs}ms",
                    )
                }

                failure is InterruptedException -> {
                    future.cancel(true)
                    Thread.currentThread().interrupt()
                    failureOutcome(
                        startNanos = startNanos,
                        status = MutantStatus.RUNNER_ERROR,
                        message = "Runner interrupted while waiting for execution",
                        stdout = capturedOut.toString(Charsets.UTF_8.name()).trim(),
                    )
                }

                else -> {
                    failureFromException(startNanos, failure, capturedOut)
                }
            }
        }
    }

    private fun executeSnippet(
        classesDir: Path,
        mainClass: String,
        fullCp: List<java.net.URL>,
        output: PrintStream,
    ) {
        val classLoader = createClassLoader(fullCp, classesDir)
        val execution = runCatching { invokeSnippet(classLoader, mainClass, output) }
        var failure = execution.exceptionOrNull()
        val closeFailure = runCatching { classLoader.close() }.exceptionOrNull()
        if (failure == null) {
            failure = closeFailure
        } else if (closeFailure != null) {
            val primaryFailure = failure
            primaryFailure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }

    private fun createClassLoader(
        fullCp: List<java.net.URL>,
        classesDir: Path,
    ): URLClassLoader =
        runCatching {
            object : URLClassLoader(fullCp.toTypedArray(), this::class.java.classLoader) {
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
        }.getOrElse { throw RunnerInfrastructureFailure(it) }

    private fun invokeSnippet(
        classLoader: URLClassLoader,
        mainClass: String,
        output: PrintStream,
    ) {
        val clazz = classLoader.loadClass(mainClass)
        val entryMethod =
            try {
                clazz.getMethod("main", Array<String>::class.java)
            } catch (_: NoSuchMethodException) {
                clazz.getMethod("main")
            }
        val initialProps = java.util.Properties().apply { putAll(System.getProperties()) }
        try {
            ThreadLocalPrintStream.withCapture(output) {
                val invokeArgs =
                    if (entryMethod.parameterCount == 1) arrayOf<Any>(emptyArray<String>()) else emptyArray()
                try {
                    entryMethod.invoke(null, *invokeArgs)
                } catch (e: InvocationTargetException) {
                    throw invocationFailure(e)
                }
            }
        } finally {
            System.setProperties(initialProps)
        }
    }

    private fun invocationFailure(failure: InvocationTargetException): Throwable {
        val target = failure.targetException ?: return RunnerInfrastructureFailure(failure)
        return if (isInfrastructureOrFatal(target)) target else TestExecutionFailure(target)
    }

    private fun isInfrastructureOrFatal(failure: Throwable): Boolean =
        when (failure) {
            is VirtualMachineError, is LinkageError -> true
            is Error -> failure !is AssertionError
            else -> false
        }

    private fun failureFromException(
        startNanos: Long,
        failure: Throwable,
        capturedOut: ByteArrayOutputStream,
    ): RunnerOutcome {
        val target = unwrapFailure(failure)
        return failureOutcome(
            startNanos = startNanos,
            status = statusForFailure(failure),
            message = failureMessage(target),
            stdout = capturedOut.toString(Charsets.UTF_8.name()).trim(),
        )
    }

    private fun failureOutcome(
        startNanos: Long,
        status: MutantStatus,
        message: String,
        stdout: String,
    ): RunnerOutcome =
        RunnerOutcome(
            status = status,
            executionTimeMs = elapsedMs(startNanos),
            stdout = stdout,
            failureMessage = message,
        )

    private fun statusForFailure(failure: Throwable): MutantStatus {
        val target = unwrapFailure(failure)
        return when {
            failure is TestExecutionFailure ||
                (failure is ExecutionException && failure.cause is TestExecutionFailure) -> MutantStatus.KILLED

            target is VirtualMachineError -> MutantStatus.RUNNER_ERROR

            target is LinkageError ||
                target is ClassNotFoundException ||
                target is NoSuchMethodException ||
                target is ReflectiveOperationException ||
                target is SecurityException -> MutantStatus.INFRASTRUCTURE_ERROR

            target is Error && target !is AssertionError -> MutantStatus.RUNNER_ERROR

            else -> MutantStatus.RUNNER_ERROR
        }
    }

    private fun unwrapFailure(failure: Throwable): Throwable =
        when (failure) {
            is TestExecutionFailure -> failure.cause
            is RunnerInfrastructureFailure -> unwrapFailure(failure.cause)
            is ExecutionException -> failure.cause?.let(::unwrapFailure) ?: failure
            is InvocationTargetException -> failure.targetException ?: failure
            else -> failure
        }

    private fun failureMessage(failure: Throwable): String = "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    override fun close() {
        executor.shutdownNow()
    }
}
