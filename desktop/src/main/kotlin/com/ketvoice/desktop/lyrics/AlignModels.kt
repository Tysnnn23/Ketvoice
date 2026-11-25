package com.ketvoice.desktop.lyrics

import com.ketvoice.app.stems.ModelPack
import java.io.File

/**
 * The word sync by AI's model: OpenAI Whisper « small » (MIT code and weights) converted by
 * `.github/workflows/publish-ketvoice-align.yml` and published as the `ketvoice-align-v1` prerelease (never republished:
 * a new model is a new tag). Each file is kept only with its pinned size and SHA-256 (`tools/models/expected-align.sha256`,
 * a test keeps them equal), downloaded only when the listener asks for it. Licence: `docs/licenses/ketvoice-align.md`.
 */
internal object AlignModels {
    const val TAG = "ketvoice-align-v1"
    private const val RELEASE = "https://github.com/Tysnnn23/Ketvoice/releases/download/$TAG/"

    val encoder = ModelPack.Part("ketvoice-align-encoder.onnx", 353_515_348L, "4e0c362de8cfa728fd68e29eb695e01acd5ec8396aaeb04225055284845b45a6", null)
    val decoder = ModelPack.Part("ketvoice-align-decoder.onnx", 615_461_709L, "5882e59487bebbcfe8fd6c1078a1e4f28e02478e2e50c0dbcf45e481bfb3ce5a", null)
    /** Whisper's own `multilingual.tiktoken`, the same bytes as in its repository. */
    val vocab = ModelPack.Part("ketvoice-align-vocab.tiktoken", 816_730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
        "https://raw.githubusercontent.com/openai/whisper/86098128c0b4f24f0e2aa2994de830614b474227/whisper/assets/multilingual.tiktoken")

    val pack = ModelPack(RELEASE, listOf(vocab, encoder, decoder), "lyrics/align")

    fun init(filesDir: File) = pack.init(filesDir)

    val ready: Boolean get() = pack.ready

    /** The loaded model, or null while it is not downloaded. */
    fun open(): AlignModel? {
        if (!pack.ready) return null
        return WhisperModel(pack.file(encoder) ?: return null, pack.file(decoder) ?: return null, pack.file(vocab) ?: return null)
    }
}
