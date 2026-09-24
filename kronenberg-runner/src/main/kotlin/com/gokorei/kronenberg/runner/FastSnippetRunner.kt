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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val DEFAULT_MAX_OUTPUT_BYTES_PER_STREAM: Int = 64 * 1024
private const val INITIAL_CAPTURE_BUFFER_SIZE: Int = 8192

private data class CapturedOutput(
    val text: String,
    val truncated: Boolean,
    val discardedBytes: Long,
)

private class BoundedOutputStream(
    private val limit: Int,
) : OutputStream() {
    private var bytes = ByteArrayOutputStream(minOf(limit, INITIAL_CAPTURE_BUFFER_SIZE))
    private var discardedBytes: Long = 0L
    private var closed: Boolean = false

    init {
        require(limit > 0)
    }

    override fun write(b: Int) {
        synchronized(this) {
            if (closed) {
                discardedBytes++
                return
            }
            if (bytes.size() < limit) {
                bytes.write(b)
            } else {
                discardedBytes++
            }
        }
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException("Invalid byte range: offset=$off, length=$len")
        }
        synchronized(this) {
            if (closed) {
                discardedBytes += len
                return
            }
            val available = limit - bytes.size()
            if (available <= 0) {
                discardedBytes += len
                return
            }
            val retained = minOf(available, len)
            bytes.write(b, off, retained)
            discardedBytes += len - retained
        }
    }

    fun snapshot(): CapturedOutput =
        synchronized(this) {
            CapturedOutput(
                text = bytes.toString(Charsets.UTF_8.name()).trim(),
                truncated = discardedBytes > 0L,
                discardedBytes = discardedBytes,
            )
        }

    override fun close() {
        synchronized(this) {
            closed = true
            bytes = ByteArrayOutputStream()
        }
    }
}

/**
 * Thread-safe PrintStream interceptor that captures stdout/stderr during in-process execution.
 */
public class ThreadLocalPrintStream
    private constructor(
        private val defaultStream: PrintStream,
        private val captureStderr: Boolean,
    ) : PrintStream(
            object : OutputStream() {
                override fun write(b: Int) {
                    val target =
                        activeTarget.get()?.let { target ->
                            if (captureStderr) target.stderr else target.stdout
                        } ?: defaultStream
                    target.write(b)
                }

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    val target =
                        activeTarget.get()?.let { target ->
                            if (captureStderr) target.stderr else target.stdout
                        } ?: defaultStream
                    target.write(b, off, len)
                }

                override fun flush() {
                    val target =
                        activeTarget.get()?.let { target ->
                            if (captureStderr) target.stderr else target.stdout
                        } ?: defaultStream
                    target.flush()
                }
            },
            true,
            Charsets.UTF_8.name(),
        ) {
        public constructor(defaultStream: PrintStream) : this(defaultStream, false)

        override fun close() {
            flush()
        }

        public companion object {
            private class CaptureTarget(
                @Volatile var stdout: PrintStream?,
                @Volatile var stderr: PrintStream?,
            ) {
                private val owner: Thread = Thread.currentThread()

                fun close() {
                    if (Thread.currentThread() === owner) {
                        stdout = null
                        stderr = null
                    }
                }
            }

            private val activeTarget = InheritableThreadLocal<CaptureTarget?>()

            public fun <T> withCapture(
                stream: PrintStream,
                block: () -> T,
            ): T = withCapture(stream, stream, block)

            public fun <T> withCapture(
                stdout: PrintStream,
                stderr: PrintStream,
                block: () -> T,
            ): T {
                val previous = activeTarget.get()
                val target = CaptureTarget(stdout, stderr)
                activeTarget.set(target)
                return try {
                    block()
                } finally {
                    target.close()
                    activeTarget.set(previous)
                }
            }

            @Volatile
            private var installed = false

            @Synchronized
            public fun install() {
                if (!installed) {
                    val outInterceptor = ThreadLocalPrintStream(System.out, false)
                    val errInterceptor = ThreadLocalPrintStream(System.err, true)
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
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val stdoutDiscardedBytes: Long = 0L,
    val stderrDiscardedBytes: Long = 0L,
    val failureMessage: String? = null,
)

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
public class DefaultFastSnippetRunner(
    threadPoolSize: Int = 4,
    private val maxOutputBytesPerStream: Int = DEFAULT_MAX_OUTPUT_BYTES_PER_STREAM,
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
        require(maxOutputBytesPerStream > 0)
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
            listOf(classesDir.toUri().toURL()) +
                extraClasspath.filter { it.isNotBlank() }.map { File(it).toURI().toURL() }

        val capturedOut = BoundedOutputStream(maxOutputBytesPerStream)
        val capturedErr = BoundedOutputStream(maxOutputBytesPerStream)
        val customOut = PrintStream(capturedOut, true, Charsets.UTF_8.name())
        val customErr = PrintStream(capturedErr, true, Charsets.UTF_8.name())

        val task =
            Callable {
                val classLoader =
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
                try {
                    val clazz =
                        try {
                            classLoader.loadClass(mainClass)
                        } catch (e: ClassNotFoundException) {
                            val candidate =
                                classesDir
                                    .toFile()
                                    .walkTopDown()
                                    .firstOrNull { it.isFile && it.extension == "class" && !it.name.contains("$") }
                            val rel =
                                candidate
                                    ?.relativeTo(classesDir.toFile())
                                    ?.path
                                    ?.removeSuffix(".class")
                                    ?.replace('/', '.') ?: "SnippetKt"
                            classLoader.loadClass(rel)
                        }

                    val entryMethod =
                        try {
                            clazz.getMethod("main", Array<String>::class.java)
                        } catch (_: NoSuchMethodException) {
                            clazz.getMethod("main")
                        }

                    val initialProps = java.util.Properties().apply { putAll(System.getProperties()) }
                    try {
                        ThreadLocalPrintStream.withCapture(customOut, customErr) {
                            val invokeArgs =
                                if (entryMethod.parameterCount == 1) arrayOf<Any>(emptyArray<String>()) else emptyArray()
                            entryMethod.invoke(null, *invokeArgs)
                        }
                    } finally {
                        System.setProperties(initialProps)
                    }
                } finally {
                    runCatching { classLoader.close() }
                }
            }

        fun outcome(
            status: MutantStatus,
            failureMessage: String? = null,
        ): RunnerOutcome {
            val out = capturedOut.snapshot()
            val err = capturedErr.snapshot()
            return RunnerOutcome(
                status = status,
                executionTimeMs = (System.nanoTime() - startNanos) / 1_000_000,
                stdout = out.text,
                stderr = err.text,
                stdoutTruncated = out.truncated,
                stderrTruncated = err.truncated,
                stdoutDiscardedBytes = out.discardedBytes,
                stderrDiscardedBytes = err.discardedBytes,
                failureMessage = failureMessage,
            )
        }

        var future: Future<*>? = null
        return try {
            val f = executor.submit(task)
            future = f
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
            outcome(MutantStatus.SURVIVED)
        } catch (e: TimeoutException) {
            future?.cancel(true)
            outcome(
                status = MutantStatus.TIMED_OUT,
                failureMessage = "Execution timed out after ${timeoutMs}ms",
            )
        } catch (e: Throwable) {
            val target = (e.cause as? InvocationTargetException)?.targetException ?: e.cause ?: e
            outcome(
                status = MutantStatus.KILLED,
                failureMessage = "${target.javaClass.simpleName}: ${target.message.orEmpty()}",
            )
        } finally {
            customOut.close()
            customErr.close()
            capturedOut.close()
            capturedErr.close()
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
