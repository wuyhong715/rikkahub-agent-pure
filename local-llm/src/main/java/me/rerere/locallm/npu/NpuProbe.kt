package me.rerere.locallm.npu

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Modality
import com.google.ai.edge.litertlm.ModelInfo
import java.io.File

/**
 * A one-shot, file-triggered probe answering "can this device actually reach its NPU through
 * LiteRT?".
 *
 * It is deliberately not a UI feature. Reaching an NPU through LiteRT needs two things the
 * APK does not ship -- the vendor *dispatch* library and the vendor's own runtime -- and a
 * half-installed set of them is not a catchable error: LiteRT faults the native execution
 * thread. So the protocol is a pair of files in the app's own files directory, and every step
 * is flushed to the result file **before** the risky call runs:
 *
 * ```
 * npu-probe-request      line 1: absolute path of a .litertlm embedding model
 *                        line 2: optional runtime dir, default <requestDir>/npu-runtime
 * npu-probe-result.txt   appended to as the probe progresses, in the same directory
 * ```
 *
 * Where "the app's own files directory" is deliberately *several* directories. The proot
 * workspace binds `/workspace` to `<filesDir>/workspaces/<uuid>/files`, not to `<filesDir>`
 * itself, so a file dropped from inside the workspace lands one level down. The request is
 * therefore searched for in the files dir and in every workspace's files dir beneath it, and
 * the result is written next to whichever one matched.
 *
 * The request file is deleted before anything is loaded, so a native abort cannot become a
 * crash loop on the next launch -- the normal app-start path runs this again on every cold
 * start.
 *
 * Nothing here touches an Android API: it takes plain [File]s, so the whole probe is
 * type-checked against the real published AAR on the JVM. Only the two call sites in `:app`
 * know about `Context`.
 */
object NpuProbe {

    const val REQUEST_FILE = "npu-probe-request"
    const val RESULT_FILE = "npu-probe-result.txt"

    /** Where the app is expected to have put `libLiteRtDispatch_*.so` and the vendor runtime. */
    const val DEFAULT_RUNTIME_DIR = "npu-runtime"

    /**
     * Runs the probe only when a request file exists under [appFilesDir] -- see
     * [findRequest] for the three places it may be.
     *
     * @param appNativeLibDir the app's own `applicationInfo.nativeLibraryDir`. Passed in so the
     *   probe can try the stock search path first and the downloaded runtime dir second; the
     *   difference between the two outcomes is the whole answer.
     * @param externalRequestDir the app's own external files dir, if it has one. An app's
     *   private data dir can only be written by that app, so a probe run against a build whose
     *   workspace we are not inside has no other way in: this directory is writable by the
     *   shell and readable by the app with no permission at all. Only the trigger is looked for
     *   here; where it is found decides where the result goes.
     */
    fun runIfRequested(
        appFilesDir: File,
        appNativeLibDir: String = "",
        sdkVersion: String = "",
        externalRequestDir: File? = null,
    ) {
        val request = findRequest(appFilesDir, externalRequestDir) ?: return
        val lines = runCatching { request.readLines() }.getOrDefault(emptyList())
        // Point of no return: drop the trigger before anything native is loaded.
        runCatching { request.delete() }

        val requestDir = request.parentFile ?: appFilesDir
        val modelPath = lines.getOrNull(0)?.trim().orEmpty()
        val runtimeDir = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: File(requestDir, DEFAULT_RUNTIME_DIR).absolutePath
        val result = File(requestDir, RESULT_FILE)
        runCatching { result.delete() }

        fun say(line: String) {
            runCatching { result.appendText(line + "\n") }
        }

        say("=== NpuProbe ===")
        say("litetrlmSdk   = $sdkVersion")
        say("requestDir    = ${requestDir.absolutePath}")
        say("appFilesDir   = ${appFilesDir.absolutePath}")
        say("appNativeDir  = $appNativeLibDir")
        say("model         = $modelPath")
        say("runtimeDir    = $runtimeDir")

        val runtime = File(runtimeDir)
        say("runtimeExists = ${runtime.isDirectory}")
        runtime.listFiles()?.sortedBy { it.name }?.forEach { say("  lib: ${it.name} (${it.length()} B)") }

        val model = File(modelPath)
        if (!model.isFile) {
            say("ABORT: model file not found")
            return
        }

        // 1. What does the bundle declare for *this* host? answered without loading anything.
        say("--- ModelInfo.from ---")
        runCatching {
            ModelInfo.from(modelPath).use { info ->
                say("modelType            = ${info.modelType}")
                say("maxContextTokens     = ${info.maxContextTokens()}")
                say("maxVisionTokenBudget = ${info.maxVisionTokenBudget()}")
                say("inputModalities      = ${info.inputModalities()}")
                for (m in Modality.entries) {
                    val backends = runCatching { info.supportedBackends(m) }.getOrElse { "ERR $it" }
                    val brand = runCatching { info.npuBrand(m) }.getOrElse { "ERR $it" }
                    val soc = runCatching { info.socName(m) }.getOrElse { "ERR $it" }
                    say("  ${m.name.padEnd(6)} backends=$backends npuBrand=$brand socName=$soc")
                }
                if (info is ModelInfo.Embedding) {
                    say("dimension            = ${info.dimension()}")
                }
            }
        }.onFailure { say("ModelInfo FAILED: ${describe(it)}") }

        // 2. The same thing LiteRtRuntime does, but with the NPU backend and the downloaded
        //    runtime directory. Two attempts, so the stock search path is a control.
        say("--- attempt A: nativeLibraryDir = app's own ---")
        attempt(::say, modelPath, appNativeLibDir, File(appNativeLibDir).parentFile?.absolutePath)

        say("--- attempt B: nativeLibraryDir = downloaded runtime ---")
        attempt(::say, modelPath, runtimeDir, requestDir.absolutePath)

        say("=== done ===")
    }

    /**
     * Three places, in order, because there are three ways to drop a trigger:
     *
     *  1. the app's files dir -- obvious from the app side;
     *  2. `<filesDir>/workspaces/<uuid>/files` -- what the proot workspace's `/workspace`
     *     actually binds to, so this is where a file written from inside it lands;
     *  3. the app's external files dir -- reachable from the shell side without being inside
     *     the app at all, which is the only option for an app whose workspace we do not host.
     */
    internal fun findRequest(appFilesDir: File, externalRequestDir: File? = null): File? {
        val direct = File(appFilesDir, REQUEST_FILE)
        if (direct.isFile) return direct
        val workspaces = File(appFilesDir, "workspaces")
        workspaces.listFiles()
            ?.asSequence()
            ?.map { File(File(it, "files"), REQUEST_FILE) }
            ?.firstOrNull { it.isFile }
            ?.let { return it }
        val external = externalRequestDir?.let { File(it, REQUEST_FILE) }
        return external?.takeIf { it.isFile }
    }

    private fun attempt(
        say: (String) -> Unit,
        modelPath: String,
        nativeLibDir: String,
        cacheDir: String?,
    ) {
        say("phase=ctor nativeLibraryDir=$nativeLibDir")
        val backend = Backend.NPU(nativeLibraryDir = nativeLibDir)
        val cfg = EmbeddingEngineConfig(
            modelPath = modelPath,
            backend = backend,
            // Not optional: with visionBackend left null the multimodal executors are never
            // built and an image then fails with "Vision executor is not available".
            visionBackend = Backend.CPU(),
            cacheDir = cacheDir,
        )
        val engine = runCatching { EmbeddingEngine(cfg) }.getOrElse {
            say("  ctor FAILED: ${describe(it)}")
            return
        }
        say("phase=initialize") // last line before the call that can abort the process
        runCatching { engine.initialize() }.onFailure {
            say("  initialize FAILED: ${describe(it)}")
            runCatching { engine.close() }
            return
        }
        say("  initialize OK")
        runCatching { engine.computeEmbedding(listOf(InputData.Text("task: search query | text: hello"))) }
            .onSuccess {
                say("  computeEmbedding OK: dim=${it.embedding.size} first=${it.embedding.take(3)}")
                say("  >>> THIS BACKEND IS USABLE <<<")
            }
            .onFailure { say("  computeEmbedding FAILED: ${describe(it)}") }
        runCatching { engine.close() }
    }

    /** `toString()` on these exceptions can be a multi-line native stack; keep the first line. */
    private fun describe(t: Throwable): String =
        "${t::class.java.name}: ${t.message?.lineSequence()?.firstOrNull() ?: "(no message)"}"
}
