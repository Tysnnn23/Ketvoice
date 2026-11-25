package com.ketvoice.desktop.ketmix

import com.ketvoice.app.stems.ModelPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The large models of « Maximum » (Windows 2.0), converted by `.github/workflows/publish-ketmix-pc-models.yml` and
 * published as the `ketmix-pc-v1` prerelease (never republished: a new model is a new tag). Each file is kept only
 * with its pinned size and SHA-256 (`tools/models/expected-pc.sha256`, a test keeps them equal), and is downloaded
 * only when the tier asks for it ([KetmixPower.models]). Licences: `docs/licenses/ketmix-pc-models.md`.
 */
internal object PcModels {
    const val TAG = "ketmix-pc-v1"
    private const val RELEASE = "https://github.com/Tysnnn23/Ketvoice/releases/download/$TAG/"

    /** Beat This! final0 (CPJKU, MIT): the full rhythm model, input [1, 1500, 128] like the small one's spectrogram. */
    val beatFull = ModelPack(RELEASE, listOf(ModelPack.Part("ketmix-beat-final0.onnx", 82_553_584L,
        "cf75cd36770ccc84377009b936599b96aecc8d2313a9b1f8281257d8ac281a33", null)), "ketmix/pc/beat")
    /** Kimberley Jensen's Mel-Band RoFormer vocal model (MIT): the studio voice ([com.ketvoice.app.stems.RoformerSeparator]). */
    val voiceStudio = ModelPack(RELEASE, listOf(ModelPack.Part("ketmix-voice-melroformer.onnx", 930_959_995L,
        "43122029abe8ced9749ed260eeb8ffb25f836e2a396c7ff9bd41111b5201f35c", null)), "ketmix/pc/voice")
    /** S-KEY (Deezer, MIT): neural key, 24 probabilities ([NeuralKey]). */
    val key = ModelPack(RELEASE, listOf(ModelPack.Part("ketmix-key-skey.onnx", 321_948L,
        "da325f4a5866d3b198aa2c6b8a93299d0e0212ef3ec8677958a83c04ce4e4c85", null)), "ketmix/pc/key")
    /** LAION CLAP for music, audio side, int8 (Apache-2.0): sound embeddings ([SoundPrint]). */
    val sound = ModelPack(RELEASE, listOf(ModelPack.Part("ketmix-sound-clap-int8.onnx", 77_483_836L,
        "ee7d0a484c6ae1c254c5ed0bcbe0fcefa2701c7fa8fc6b122ebaf0636e00f6c3", null)), "ketmix/pc/sound")

    fun pack(model: PcModel): ModelPack = when (model) {
        PcModel.BeatFull -> beatFull
        PcModel.VoiceStudio -> voiceStudio
        PcModel.Key -> key
        PcModel.Sound -> sound
    }

    val all: List<ModelPack> get() = PcModel.values().map(::pack)

    fun init(filesDir: File) = all.forEach { it.init(filesDir) }

    /** The model's file when it is downloaded and checked, else null. */
    fun file(model: PcModel): File? = pack(model).takeIf { it.ready }?.let { it.file(it.parts[0]) }

    /** What « Maximum » still has to download on this PC, and its size. */
    fun missing(models: Set<PcModel>): List<PcModel> = models.filter { !pack(it).ready }

    fun bytes(models: Collection<PcModel>): Long = models.sumOf { pack(it).totalBytes }

    data class Progress(val running: Boolean = false, val fraction: Float = 0f, val failed: String? = null)
    private val _progress = MutableStateFlow(Progress())
    /** The whole « Maximum » download, for Settings. */
    val progress: StateFlow<Progress> = _progress

    /**
     * Downloads, one after the other, what the tier needs and is missing (smallest first, so the quick wins come
     * early); each pack checks its own digest. Blocking: call it off the UI thread.
     */
    suspend fun ensure(models: Set<PcModel>) {
        val todo = missing(models).sortedBy { pack(it).totalBytes }
        if (todo.isEmpty()) return
        val total = bytes(todo).toFloat()
        var done = 0L
        _progress.value = Progress(running = true)
        for (model in todo) {
            val p = pack(model)
            p.download()
            kotlinx.coroutines.coroutineScope {
                val watch = launch {
                    p.state.collect { s ->
                        if (s is ModelPack.State.Downloading) _progress.value = Progress(true, ((done + s.fraction * p.totalBytes) / total).coerceIn(0f, 1f))
                    }
                }
                p.join()
                watch.cancel()
            }
            val end = p.state.value
            if (end is ModelPack.State.Failed) { _progress.value = Progress(false, done / total, end.reason); return }
            done += p.totalBytes
        }
        _progress.value = Progress(false, 1f)
    }
}
