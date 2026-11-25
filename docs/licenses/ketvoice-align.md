# Word sync by AI (Windows 2.0): licence review and conversion checks (2026-11-25)

On desktop PCs in « Puissance de Ketmix » « Maximum » (Auto on a PC without a system battery), lyrics that are timed by
line only (or not at all) are timed word by word on the song's separated voice. The model is converted by
`.github/workflows/publish-ketvoice-align.yml`, published as the `ketvoice-align-v1` prerelease (never republished: a
new model is a new tag; `ketmix-pc-v1` is not reused), pinned by SHA-256 in `tools/models/expected-align.sha256` and
`desktop/.../lyrics/AlignModels.kt` (a test keeps them equal), and downloaded only when the listener asks for it.
Only a model whose code **and** weights are under a permissive licence is used.

## Shipped (downloaded on request)

| File | What | Code licence | Weights / data licence | Size |
| --- | --- | --- | --- | --- |
| `ketvoice-align-encoder.onnx` | OpenAI Whisper « small » (multilingual), audio encoder with Whisper's log-mel inside the graph | MIT, https://github.com/openai/whisper/blob/main/LICENSE (« The code and the model weights of Whisper are released under the MIT License ») | MIT (same file); checkpoint `small.pt` from https://openaipublic.azureedge.net/main/whisper/models/9ecf779972d90ba49c06d968637d720dd632c55bbf19d441fb42bf17a411e794/small.pt (the URL holds its SHA-256, checked) | 353.5 MB |
| `ketvoice-align-decoder.onnx` | Whisper small, text decoder: the cross-attention of its ten alignment heads, the next-token probabilities, the language logits | MIT (as above) | MIT (as above) | 615.5 MB |
| `ketvoice-align-vocab.tiktoken` | Whisper's multilingual byte-pair ranks (`whisper/assets/multilingual.tiktoken`, byte for byte) | MIT (as above) | MIT (as above) | 0.8 MB |

Repository pinned at commit `86098128c0b4f24f0e2aa2994de830614b474227`. The export reuses Whisper's own modules
(`whisper.model`, `whisper.audio`, `whisper.timing`), and the app reimplements in Kotlin the parts it needs: the BPE
encoder (`lyrics/WhisperTokens.kt`) and the word-timing recipe of `whisper.timing.find_alignment` (`lyrics/AlignMath.kt`).
The Python tools the export uses (PyTorch BSD-3-Clause, ONNX and ONNX Runtime MIT/Apache-2.0, tiktoken MIT, numba
BSD-2-Clause) are not shipped.

Considered and not used: Meta's MMS (CC-BY-NC weights, non-commercial); per-language wav2vec2 CTC models (Apache-2.0,
but one 1.2 GB model per language). Whisper covers 99 languages with one model and aligns known text without
transcribing it.

## Conversion checks (recorded 2026-11-25, Linux x86-64, packages of `tools/align/requirements.txt`)

The export is reproducible: the same packages gave byte-identical files twice.

- **Log-mel** (a strided convolution with the Hann-windowed DFT basis, reflect padding, Whisper's 80 mel filters, log,
  clamp, scale): against `whisper.audio.log_mel_spectrogram`, max abs difference 2.0e-6 (30 s of noise), 2.6e-6 (7 s),
  3.0e-5 (jfk.flac).
- **Encoder**: against `model.encoder(log_mel_spectrogram(…))`, max abs difference 4.3e-3 (30 s of noise; values up to
  30), 6.6e-4 (7 s), 1.3e-3 (jfk.flac).
- **Decoder**: against Whisper's own forward with the hooks of `find_alignment` on jfk.flac's 27 tokens: cross-attention
  2.5e-5 (values up to 17.9), next-token probabilities 2.8e-6, language logits 1.3e-5; with 6 tokens 1.2e-5 / 1.4e-6 /
  8.6e-6 (the token axis is dynamic). jfk.flac is read as English.
- **Word times**: Whisper's `find_alignment` on jfk.flac (the repository's public-domain test clip, « And so my fellow
  Americans … ») and the same recipe on the ONNX outputs agree to the frame (worst difference 0.000 s for all 22 words);
  the mean text-token probability is 0.88. The Kotlin pipeline (`WordAligner` with `WhisperModel`) on the same clip gives
  the same 22 word starts (`WordAlignerTest.realModelOnTheReferenceClip`, run locally with the files).
- **Tokenizer**: the Kotlin BPE gives tiktoken's tokens for English, French, Russian, Korean, Japanese, Greek, Arabic,
  numbers, an emoji and runs of spaces (`WhisperTokensTest.sameTokensAsTiktoken`).
- **Post-processing**: on a cross-attention made from a formula, `AlignMath.wordTimes` returns the times of Whisper's
  own `median_filter`/`dtw` code (`AlignMathTest.postProcessingMatchesWhispersTiming`).

Not measured: how well the words land on real sung music. Whisper was trained on speech; singing over the separated
voice is an easier case than over the full mix, and the app keeps line timing for any line whose words were not heard
(mean token probability under 0.03) and drops the result when the text does not match what is sung (under 0.12 over
the song).
