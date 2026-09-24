package com.gokorei.kronenberg.runner

import com.gokorei.kronenberg.model.MutantStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit

private const val TERMINATION_POLL_MS: Long = 10L

internal object SnippetWorker {
    @Suppress("TooGenericExceptionCaught")
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) return
        val requestPath = Path.of(args[0])
        val request = Properties()
        try {
            Files.newInputStream(requestPath).use { request.load(it) }
            val response = runRequest(request)
            runCatching { terminateDescendantProcesses() }
            writeResponse(Path.of(request.getProperty("responseFile")), response)
        } catch (e: Throwable) {
            val response = Properties()
            response.setProperty("status", "failed")
            response.setProperty("message", errorMessage(e))
            runCatching { writeResponse(Path.of(request.getProperty("responseFile")), response) }
        }
    }

    private fun runRequest(request: Properties): Properties {
        val response = Properties()
        when (request.getProperty("mode")) {
            "compile" -> {
                val result =
                    DefaultSnippetCompiler().compileInProcess(
                        sourceFile = Path.of(request.getProperty("sourceFile")),
                        outDir = Path.of(request.getProperty("outDir")),
                        tempRoot = Path.of(request.getProperty("tempRoot")),
                        extraClasspath = request.list("extraClasspath"),
                    )
                response.setProperty("status", if (result is CompileResult.Compiled) "compiled" else "failed")
                when (result) {
                    is CompileResult.Compiled -> response.setProperty("outDir", result.outDir.toString())
                    is CompileResult.Failed -> response.setProperty("message", result.message)
                    is CompileResult.TimedOut -> response.setProperty("message", result.message)
                }
                response.putAll(diagnosticsProperties(result.diagnosticsOrEmpty()))
            }

            "execute" -> {
                response.putAll(execute(request))
            }

            else -> {
                response.setProperty("status", "failed")
                response.setProperty("message", "Unknown worker mode")
            }
        }
        return response
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private fun execute(request: Properties): Properties {
        val response = Properties()
        val classesDir = Path.of(request.getProperty("classesDir"))
        val mainClass = request.getProperty("mainClass") ?: "SnippetKt"
        val extraClasspath = request.list("extraClasspath")
        val fullClasspath =
            buildList {
                add(classesDir.toUri().toURL())
                extraClasspath.filter { it.isNotBlank() }.forEach { add(File(it).toURI().toURL()) }
            }
        val capturedOut = ByteArrayOutputStream()
        val capturedErr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val originalProperties = Properties().apply { putAll(System.getProperties()) }
        var classLoader: URLClassLoader? = null

        try {
            val executionClassLoader =
                object : URLClassLoader(fullClasspath.toTypedArray(), SnippetWorker::class.java.classLoader) {
                    override fun loadClass(
                        name: String,
                        resolve: Boolean,
                    ): Class<*> {
                        val classFile = classesDir.resolve(name.replace('.', '/') + ".class").toFile()
                        return if (classFile.exists()) {
                            findLoadedClass(name) ?: findClass(name)
                        } else {
                            super.loadClass(name, resolve)
                        }
                    }
                }
            classLoader = executionClassLoader
            System.setOut(PrintStream(capturedOut, true, Charsets.UTF_8.name()))
            System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8.name()))

            val clazz =
                try {
                    executionClassLoader.loadClass(mainClass)
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
                            ?.replace('/', '.') ?: "SnippetKt"
                    executionClassLoader.loadClass(relativeClass)
                }
            val entryMethod =
                try {
                    clazz.getMethod("main", Array<String>::class.java)
                } catch (_: NoSuchMethodException) {
                    clazz.getMethod("main")
                }
            if (entryMethod.parameterCount == 1) {
                entryMethod.invoke(null, emptyArray<String>())
            } else {
                entryMethod.invoke(null)
            }
            response.setProperty("status", MutantStatus.SURVIVED.name)
        } catch (e: Throwable) {
            response.setProperty("status", MutantStatus.KILLED.name)
            response.setProperty("message", errorMessage(e))
        } finally {
            runCatching { System.setOut(originalOut) }
            runCatching { System.setErr(originalErr) }
            runCatching { System.setProperties(originalProperties) }
            runCatching { classLoader?.close() }
            response.setProperty("stdout", capturedOut.toString(Charsets.UTF_8.name()).trim())
            response.setProperty("stderr", capturedErr.toString(Charsets.UTF_8.name()).trim())
        }
        return response
    }

    private fun diagnosticsProperties(diagnostics: List<CompilerDiagnostic>): Properties {
        val properties = Properties()
        diagnostics.forEachIndexed { index, diagnostic ->
            properties.setProperty("diagnostic.$index.severity", diagnostic.severity)
            properties.setProperty("diagnostic.$index.message", diagnostic.message)
            diagnostic.line?.let { properties.setProperty("diagnostic.$index.line", it.toString()) }
            diagnostic.column?.let { properties.setProperty("diagnostic.$index.column", it.toString()) }
        }
        return properties
    }

    private fun terminateDescendantProcesses() {
        val root = ProcessHandle.current()
        val descendants = mutableSetOf<ProcessHandle>()
        root.descendants().use { descendantStream ->
            descendantStream.forEach { descendants.add(it) }
        }
        descendants.forEach { it.destroy() }
        while (descendants.any { it.isAlive }) {
            root.descendants().use { descendantStream ->
                descendantStream.forEach { descendants.add(it) }
            }
            descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
            runCatching { Thread.sleep(TERMINATION_POLL_MS) }
        }
    }

    private fun writeResponse(
        responseFile: Path,
        response: Properties,
    ) {
        Files.newOutputStream(responseFile).use { response.store(it, null) }
    }
}

internal sealed class WorkerProcessResult {
    internal data class Completed(
        val response: Properties,
        val exitCode: Int,
    ) : WorkerProcessResult()

    internal data object TimedOut : WorkerProcessResult()
}

internal fun runWorkerProcess(
    requestFile: Path,
    responseFile: Path,
    timeoutMs: Long,
): WorkerProcessResult {
    val process =
        ProcessBuilder(workerCommand(requestFile))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
    val completed = process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
    if (!completed) {
        terminateProcessTree(process)
        return WorkerProcessResult.TimedOut
    }
    val response = Properties()
    if (Files.exists(responseFile)) {
        Files.newInputStream(responseFile).use { response.load(it) }
    }
    return WorkerProcessResult.Completed(response, process.exitValue())
}

private fun workerCommand(requestFile: Path): List<String> {
    val executable =
        if (System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) {
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()
        } else {
            Path.of(System.getProperty("java.home"), "bin", "java").toString()
        }
    val request = Properties()
    Files.newInputStream(requestFile).use { request.load(it) }
    return listOf(
        executable,
        "-cp",
        workerClasspath(request.getProperty("mode") == "compile"),
        SnippetWorker::class.java.name,
        requestFile.toString(),
    )
}

private fun workerClasspath(includeCompilerDependencies: Boolean): String {
    val entries = mutableListOf<String>()
    if (includeCompilerDependencies) {
        addCompilerClasspath(entries)
    }
    addCodeSource(entries, SnippetWorker::class.java)
    addCodeSource(entries, kotlin.Unit::class.java)
    if (includeCompilerDependencies) {
        addCodeSource(entries, DefaultSnippetCompiler::class.java)
        addCodeSource(entries, org.jetbrains.kotlin.cli.jvm.K2JVMCompiler::class.java)
    }
    return entries.distinct().joinToString(File.pathSeparator)
}

private fun addCompilerClasspath(entries: MutableList<String>) {
    System.getProperty("java.class.path").orEmpty().split(File.pathSeparator).forEach { entry ->
        if (entry.isNotBlank()) entries.add(entry)
    }
    var classLoader: ClassLoader? = SnippetWorker::class.java.classLoader
    while (classLoader != null) {
        addClassLoaderEntries(entries, classLoader)
        classLoader = classLoader.parent
    }
}

private fun addClassLoaderEntries(
    entries: MutableList<String>,
    classLoader: ClassLoader,
) {
    if (classLoader !is URLClassLoader) return
    classLoader.urLs.forEach { url -> runCatching { entries.add(Path.of(url.toURI()).toString()) } }
}

private fun addCodeSource(
    entries: MutableList<String>,
    type: Class<*>,
) {
    val location = type.protectionDomain?.codeSource?.location
    if (location != null) {
        runCatching { entries.add(Path.of(location.toURI()).toString()) }
    }
}

private fun terminateProcessTree(process: Process) {
    val root = process.toHandle()
    val descendants = mutableSetOf<ProcessHandle>()

    fun collectDescendants(handle: ProcessHandle) {
        handle.descendants().use { descendantStream ->
            descendantStream.forEach { child ->
                if (descendants.add(child)) collectDescendants(child)
            }
        }
    }
    collectDescendants(root)
    descendants.forEach { it.destroy() }
    root.destroy()
    while (root.isAlive || descendants.any { it.isAlive }) {
        root.descendants().use { descendantStream ->
            descendantStream.forEach { descendants.add(it) }
        }
        descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
        if (root.isAlive) root.destroyForcibly()
        runCatching { Thread.sleep(TERMINATION_POLL_MS) }
    }
}

private fun Properties.list(prefix: String): List<String> {
    val values = mutableListOf<String>()
    var index = 0
    while (true) {
        val value = getProperty("$prefix.$index") ?: break
        values.add(value)
        index++
    }
    return values
}

private fun CompileResult.diagnosticsOrEmpty(): List<CompilerDiagnostic> =
    when (this) {
        is CompileResult.Compiled -> diagnostics
        is CompileResult.Failed -> diagnostics
        is CompileResult.TimedOut -> diagnostics
    }

private fun errorMessage(error: Throwable): String {
    val target = (error as? InvocationTargetException)?.targetException ?: error
    return "${target.javaClass.simpleName}: ${target.message.orEmpty()}"
}
