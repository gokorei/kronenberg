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
            listOf(classesDir.toUri().toURL()) +
                extraClasspath.filter { it.isNotBlank() }.map { File(it).toURI().toURL() }

        val capturedOut = ByteArrayOutputStream()
        val customPrintStream = PrintStream(capturedOut, true, Charsets.UTF_8.name())

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
                        ThreadLocalPrintStream.withCapture(customPrintStream) {
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

        var future: Future<*>? = null
        return try {
            val f = executor.submit(task)
            future = f
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val out = capturedOut.toString(Charsets.UTF_8.name()).trim()
            RunnerOutcome(
                status = MutantStatus.SURVIVED,
                executionTimeMs = durationMs,
                stdout = out,
            )
        } catch (e: TimeoutException) {
            future?.cancel(true)
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            RunnerOutcome(
                status = MutantStatus.TIMED_OUT,
                executionTimeMs = durationMs,
                failureMessage = "Execution timed out after ${timeoutMs}ms",
            )
        } catch (e: Throwable) {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            val target = (e.cause as? InvocationTargetException)?.targetException ?: e.cause ?: e
            val errorMsg = "${target.javaClass.simpleName}: ${target.message.orEmpty()}"
            RunnerOutcome(
                status = MutantStatus.KILLED,
                executionTimeMs = durationMs,
                failureMessage = errorMsg,
            )
        }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
