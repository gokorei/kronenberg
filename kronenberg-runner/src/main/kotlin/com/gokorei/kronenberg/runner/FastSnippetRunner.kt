package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val CLASS_SUFFIX: String = ".class"

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

public sealed interface RunnerError {
    public data object MissingEntrypoint : RunnerError

    public data class AmbiguousEntrypoint(
        val candidates: List<String>,
    ) : RunnerError
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
    val error: RunnerError? = null,
)

private open class RunnerInfrastructureFailure(
    override val cause: Throwable,
) : RuntimeException(cause)

private class EntrypointFailure(
    val runnerError: RunnerError,
    override val message: String,
) : RunnerInfrastructureFailure(IllegalStateException(message))

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

    public fun run(
        classesDir: Path,
        entrypoint: CompilationEntrypoint,
        timeoutMs: Long = 2000L,
        extraClasspath: List<String> = emptyList(),
    ): RunnerOutcome =
        when (entrypoint) {
            is CompilationEntrypoint.Missing -> {
                RunnerOutcome(
                    status = MutantStatus.INFRASTRUCTURE_ERROR,
                    executionTimeMs = 0L,
                    failureMessage = "MissingEntrypoint: no deterministic main entrypoint was found",
                    error = RunnerError.MissingEntrypoint,
                )
            }

            is CompilationEntrypoint.Ambiguous -> {
                RunnerOutcome(
                    status = MutantStatus.INFRASTRUCTURE_ERROR,
                    executionTimeMs = 0L,
                    failureMessage = "AmbiguousEntrypoint: multiple main entrypoints were found",
                    error = RunnerError.AmbiguousEntrypoint(entrypoint.candidates.map { it.className }.distinct()),
                )
            }

            is CompilationEntrypoint.Resolved -> {
                run(classesDir, entrypoint.className, timeoutMs, extraClasspath)
            }
        }
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
    ): RunnerOutcome = runInternal(classesDir, mainClass, null, timeoutMs, extraClasspath)

    override fun run(
        classesDir: Path,
        entrypoint: CompilationEntrypoint,
        timeoutMs: Long,
        extraClasspath: List<String>,
    ): RunnerOutcome =
        when (entrypoint) {
            is CompilationEntrypoint.Missing -> runInternal(classesDir, null, entrypoint, timeoutMs, extraClasspath)
            is CompilationEntrypoint.Ambiguous -> runInternal(classesDir, null, entrypoint, timeoutMs, extraClasspath)
            is CompilationEntrypoint.Resolved -> runInternal(classesDir, null, entrypoint, timeoutMs, extraClasspath)
        }

    private fun runInternal(
        classesDir: Path,
        fallbackMainClass: String?,
        entrypoint: CompilationEntrypoint?,
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
        val task =
            Callable {
                executeSnippet(
                    classesDir,
                    entrypoint,
                    fallbackMainClass ?: DefaultSnippetCompiler.MAIN_CLASS,
                    fullCp.getOrThrow(),
                    customPrintStream,
                )
            }
        return awaitCompletion(startNanos, timeoutMs, capturedOut, runCatching { executor.submit(task) })
    }

    private fun awaitCompletion(
        startNanos: Long,
        timeoutMs: Long,
        capturedOut: ByteArrayOutputStream,
        submitted: Result<Future<*>>,
    ): RunnerOutcome {
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
        entrypoint: CompilationEntrypoint?,
        fallbackMainClass: String,
        fullCp: List<java.net.URL>,
        output: PrintStream,
    ) {
        val classLoader = createClassLoader(fullCp, classesDir)
        val execution =
            runCatching {
                if (entrypoint == null) {
                    invokeLegacySnippet(classLoader, fallbackMainClass, output)
                } else {
                    invokeSnippet(classLoader, classesDir, entrypoint, output)
                }
            }
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

    private fun invokeLegacySnippet(
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

    private fun invokeSnippet(
        classLoader: URLClassLoader,
        classesDir: Path,
        entrypoint: CompilationEntrypoint,
        output: PrintStream,
    ) {
        val resolved = requireResolvedEntrypoint(classLoader, classesDir, entrypoint)
        val clazz = loadEntrypointClass(classLoader, resolved)
        val entryMethod = findMainMethod(clazz, resolved)
        val receiver = entryReceiver(classLoader, clazz, resolved)
        val initialProps = java.util.Properties().apply { putAll(System.getProperties()) }
        try {
            ThreadLocalPrintStream.withCapture(output) {
                try {
                    if (entryMethod.parameterCount == 1) {
                        entryMethod.invoke(receiver, emptyArray<String>())
                    } else {
                        entryMethod.invoke(receiver)
                    }
                } catch (e: InvocationTargetException) {
                    throw invocationFailure(e)
                }
            }
        } finally {
            System.setProperties(initialProps)
        }
    }

    private fun requireResolvedEntrypoint(
        classLoader: URLClassLoader,
        classesDir: Path,
        entrypoint: CompilationEntrypoint,
    ): CompilationEntrypoint.Resolved =
        when (val resolved = resolveEntrypoint(classLoader, classesDir, entrypoint)) {
            is CompilationEntrypoint.Missing -> {
                throw EntrypointFailure(
                    RunnerError.MissingEntrypoint,
                    "MissingEntrypoint: no deterministic main entrypoint was found",
                )
            }

            is CompilationEntrypoint.Ambiguous -> {
                throw EntrypointFailure(
                    RunnerError.AmbiguousEntrypoint(resolved.candidates.map { it.className }.distinct()),
                    "AmbiguousEntrypoint: multiple main entrypoints were found",
                )
            }

            is CompilationEntrypoint.Resolved -> {
                resolved
            }
        }

    private fun loadEntrypointClass(
        classLoader: URLClassLoader,
        entrypoint: CompilationEntrypoint.Resolved,
    ): Class<*> =
        runCatching { classLoader.loadClass(entrypoint.className) }.getOrElse {
            throw EntrypointFailure(
                RunnerError.MissingEntrypoint,
                "MissingEntrypoint: class ${entrypoint.className} does not exist",
            )
        }

    private fun resolveEntrypoint(
        classLoader: URLClassLoader,
        classesDir: Path,
        entrypoint: CompilationEntrypoint,
    ): CompilationEntrypoint =
        when (entrypoint) {
            is CompilationEntrypoint.Missing -> discoverEntrypoint(classLoader, classesDir)
            is CompilationEntrypoint.Ambiguous -> entrypoint
            is CompilationEntrypoint.Resolved -> entrypoint
        }

    private fun discoverEntrypoint(
        classLoader: URLClassLoader,
        classesDir: Path,
    ): CompilationEntrypoint {
        val availableClassNames = classNames(classesDir)
        val candidates =
            availableClassNames.flatMap { className ->
                runCatching {
                    val clazz = classLoader.loadClass(className)
                    clazz.declaredMethods
                        .asSequence()
                        .filter { method ->
                            Modifier.isPublic(method.modifiers) &&
                                Modifier.isStatic(method.modifiers) &&
                                isMainMethod(method)
                        }.map { method ->
                            CompilationEntrypoint.Resolved(
                                className = className,
                                parameterCount = method.parameterCount,
                            )
                        }.toList()
                }.getOrDefault(emptyList())
            }
        val deterministicCandidates =
            candidates
                .distinct()
                .sortedWith(compareBy({ it.className }, { it.parameterCount }))
        return when (deterministicCandidates.size) {
            0 -> CompilationEntrypoint.Missing(availableClassNames)
            1 -> deterministicCandidates.single()
            else -> CompilationEntrypoint.Ambiguous(deterministicCandidates)
        }
    }

    private fun classNames(classesDir: Path): List<String> {
        if (!Files.isDirectory(classesDir)) return emptyList()
        return Files.walk(classesDir).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(CLASS_SUFFIX) }
                .sorted()
                .map { path ->
                    val relative = classesDir.relativize(path)
                    val simpleName = path.fileName.toString().removeSuffix(CLASS_SUFFIX)
                    val packageName =
                        if (relative.nameCount == 1) {
                            ""
                        } else {
                            relative.subpath(0, relative.nameCount - 1).joinToString(".")
                        }
                    if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
                }.toList()
        }
    }

    private fun findMainMethod(
        clazz: Class<*>,
        entrypoint: CompilationEntrypoint.Resolved,
    ): Method {
        val methods =
            clazz.declaredMethods
                .asSequence()
                .filter { method -> Modifier.isPublic(method.modifiers) && isMainMethod(method) }
                .filter { method -> entrypoint.parameterCount == null || method.parameterCount == entrypoint.parameterCount }
                .toList()
        return when (methods.size) {
            0 -> {
                throw EntrypointFailure(
                    RunnerError.MissingEntrypoint,
                    "MissingEntrypoint: ${entrypoint.className} has no matching main method",
                )
            }

            1 -> {
                methods.single()
            }

            else -> {
                throw EntrypointFailure(
                    RunnerError.AmbiguousEntrypoint(listOf(entrypoint.className)),
                    "AmbiguousEntrypoint: ${entrypoint.className} has multiple matching main methods",
                )
            }
        }
    }

    private fun isMainMethod(method: Method): Boolean =
        method.name == "main" &&
            (method.parameterCount == 0 || method.parameterTypes.singleOrNull() == Array<String>::class.java)

    private fun entryReceiver(
        classLoader: URLClassLoader,
        clazz: Class<*>,
        entrypoint: CompilationEntrypoint.Resolved,
    ): Any? =
        when (entrypoint.receiver) {
            EntrypointReceiver.STATIC -> {
                null
            }

            EntrypointReceiver.CLASS -> {
                clazz.getDeclaredConstructor().newInstance()
            }

            EntrypointReceiver.OBJECT -> {
                clazz.getField("INSTANCE").get(null)
            }

            EntrypointReceiver.COMPANION -> {
                val ownerName =
                    entrypoint.ownerClassName
                        ?: throw EntrypointFailure(
                            RunnerError.MissingEntrypoint,
                            "MissingEntrypoint: companion owner is unavailable for ${entrypoint.className}",
                        )
                val fieldName = entrypoint.className.substringAfterLast('$')
                classLoader.loadClass(ownerName).getField(fieldName).get(null)
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
    ): RunnerOutcome =
        failureOutcome(
            startNanos = startNanos,
            status = statusForFailure(failure),
            message = failureMessage(failure),
            stdout = capturedOut.toString(Charsets.UTF_8.name()).trim(),
            error = runnerError(failure),
        )

    private fun failureOutcome(
        startNanos: Long,
        status: MutantStatus,
        message: String,
        stdout: String,
        error: RunnerError? = null,
    ): RunnerOutcome =
        RunnerOutcome(
            status = status,
            executionTimeMs = elapsedMs(startNanos),
            stdout = stdout,
            failureMessage = message,
            error = error,
        )

    private fun statusForFailure(failure: Throwable): MutantStatus =
        when {
            failure is EntrypointFailure ||
                (failure is ExecutionException && failure.cause is EntrypointFailure) -> MutantStatus.INFRASTRUCTURE_ERROR

            failure is TestExecutionFailure ||
                (failure is ExecutionException && failure.cause is TestExecutionFailure) -> MutantStatus.KILLED

            else -> statusForTarget(unwrapFailure(failure))
        }

    private fun statusForTarget(target: Throwable): MutantStatus =
        when {
            target is VirtualMachineError -> MutantStatus.RUNNER_ERROR

            target is LinkageError ||
                target is ClassNotFoundException ||
                target is NoSuchMethodException ||
                target is ReflectiveOperationException ||
                target is SecurityException -> MutantStatus.INFRASTRUCTURE_ERROR

            else -> MutantStatus.RUNNER_ERROR
        }

    private fun unwrapFailure(failure: Throwable): Throwable =
        when (failure) {
            is TestExecutionFailure -> failure.cause
            is RunnerInfrastructureFailure -> unwrapFailure(failure.cause)
            is ExecutionException -> failure.cause?.let(::unwrapFailure) ?: failure
            is InvocationTargetException -> failure.targetException ?: failure
            else -> failure
        }

    private fun runnerError(failure: Throwable): RunnerError? =
        when (failure) {
            is EntrypointFailure -> failure.runnerError
            is ExecutionException -> failure.cause?.let(::runnerError)
            else -> null
        }

    private fun failureMessage(failure: Throwable): String =
        when (failure) {
            is EntrypointFailure -> failure.message.orEmpty()
            is ExecutionException -> failure.cause?.let(::failureMessage) ?: failure.toString()
            is TestExecutionFailure -> failureMessage(failure.cause)
            is RunnerInfrastructureFailure -> failureMessage(failure.cause)
            else -> "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
        }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    override fun close() {
        executor.shutdownNow()
    }
}
