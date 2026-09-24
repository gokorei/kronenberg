package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

private const val NANOS_PER_MILLISECOND: Long = 1_000_000L

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
 * Execution outcome from running trusted project bytecode in a worker process.
 */
public data class RunnerOutcome(
    val status: MutantStatus,
    val executionTimeMs: Long,
    val stdout: String = "",
    val stderr: String = "",
    val failureMessage: String? = null,
)

/**
 * Worker-process execution contract for trusted local snippets.
 */
public interface FastSnippetRunner : AutoCloseable {
    /**
     * Executes trusted local bytecode in [classesDir] with a fresh URLClassLoader in a worker process.
     */
    public fun run(
        classesDir: Path,
        mainClass: String = "SnippetKt",
        timeoutMs: Long = 2000L,
        extraClasspath: List<String> = emptyList(),
    ): RunnerOutcome
}

public class DefaultFastSnippetRunner(
    @Suppress("UNUSED_PARAMETER") threadPoolSize: Int = 4,
) : FastSnippetRunner {
    override fun run(
        classesDir: Path,
        mainClass: String,
        timeoutMs: Long,
        extraClasspath: List<String>,
    ): RunnerOutcome {
        val startNanos = System.nanoTime()
        val tempDir = Files.createTempDirectory("kronenberg-execute")
        val requestFile = tempDir.resolve("request.properties")
        val responseFile = tempDir.resolve("response.properties")
        val request =
            Properties().apply {
                setProperty("mode", "execute")
                setProperty("classesDir", classesDir.toString())
                setProperty("mainClass", mainClass)
                setProperty("responseFile", responseFile.toString())
                extraClasspath.filter { it.isNotBlank() }.forEachIndexed { index, entry ->
                    setProperty("extraClasspath.$index", entry)
                }
            }
        return try {
            Files.newOutputStream(requestFile).use { request.store(it, null) }
            when (val workerResult = runWorkerProcess(requestFile, responseFile, timeoutMs)) {
                is WorkerProcessResult.TimedOut -> {
                    RunnerOutcome(
                        status = MutantStatus.TIMED_OUT,
                        executionTimeMs = elapsedMs(startNanos),
                        failureMessage = "Execution timed out after ${timeoutMs.coerceAtLeast(1L)}ms; worker terminated",
                    )
                }

                is WorkerProcessResult.Completed -> {
                    val status =
                        runCatching {
                            MutantStatus.valueOf(workerResult.response.getProperty("status"))
                        }.getOrDefault(MutantStatus.KILLED)
                    RunnerOutcome(
                        status = status,
                        executionTimeMs = elapsedMs(startNanos),
                        stdout = workerResult.response.getProperty("stdout").orEmpty(),
                        stderr = workerResult.response.getProperty("stderr").orEmpty(),
                        failureMessage = workerResult.response.getProperty("message"),
                    )
                }
            }
        } catch (e: Throwable) {
            RunnerOutcome(
                status = MutantStatus.KILLED,
                executionTimeMs = elapsedMs(startNanos),
                failureMessage = "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
            )
        } finally {
            runCatching { tempDir.toFile().deleteRecursively() }
        }
    }

    override fun close(): Unit = Unit
}

private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND
