# Ketmix PC models (Windows 2.0, « Maximum »): licence review and conversion checks (2026-11-25)

« Puissance de Ketmix » gives desktop PCs (no system battery, 12 GB of memory, 4 cores; or « Maximum » forced) larger
models. They are converted by `.github/workflows/publish-ketmix-pc-models.yml`, published as the `ketmix-pc-v1`
prerelease, pinned by SHA-256 in `tools/models/expected-pc.sha256` and `desktop/.../ketmix/PcModels.kt`, and downloaded
only when the tier needs them. Only models whose code **and** weights are under a permissive licence are used.

## Shipped (downloaded on request)

| File | Model | Code licence | Weights licence | Size |
| --- | --- | --- | --- | --- |
| `ketmix-beat-final0.onnx` | Beat This! final0 (Foscarin, Schlüter, Widmer, ISMIR 2024) | MIT, https://github.com/CPJKU/beat_this/blob/main/LICENSE | MIT: the checkpoints are distributed by the same repository's code from the authors' cloud (https://cloud.cp.jku.at/index.php/s/7ik4RrBKTS273gp); the repository and its `pyproject.toml` declare MIT and no separate licence for the checkpoints | 82.6 MB |
| `ketmix-voice-melroformer.onnx` | Mel-Band RoFormer vocal model by Kimberley Jensen | MIT: ZFTurbo's Music-Source-Separation-Training (https://github.com/ZFTurbo/Music-Source-Separation-Training/blob/main/LICENSE, the architecture file used) after lucidrains' BS-RoFormer (https://github.com/lucidrains/BS-RoFormer/blob/main/LICENSE, MIT) | MIT: model card `license: mit`, https://huggingface.co/KimberleyJSN/melbandroformer (revision ac9b0614) | 931 MB |
| `ketmix-key-skey.onnx` | S-KEY (Kong, Meseguer-Brocal, Lostanlen, Lagrange, Hennequin; Deezer; ICASSP 2025) | MIT, https://github.com/deezer/skey/blob/main/LICENSE; its HCQT front end is nnAudio (MIT) | MIT: `skey/models/skey.pt` is part of that MIT repository | 0.3 MB |
| `ketmix-sound-clap-int8.onnx` | LAION CLAP for music, audio encoder (`laion/larger_clap_music`) | CC0-1.0 (https://github.com/LAION-AI/CLAP/blob/main/LICENSE); run here through Hugging Face transformers (Apache-2.0) | Apache-2.0: model card `license: apache-2.0`, https://huggingface.co/laion/larger_clap_music (revision a0b4534a) | 77.5 MB |

Runtime downloaded on request for the graphics card (not a model): the WebGPU plugin execution provider of ONNX
Runtime, NuGet `Microsoft.ML.OnnxRuntime.EP.WebGpu` 0.6.0 (MIT, Microsoft; its `ThirdPartyNotices.txt` covers Dawn and
the DirectX Shader Compiler), fetched from nuget.org and checked against its pinned digest; only
`onnxruntime_providers_webgpu.dll` and `dxcompiler.dll` are extracted. Not redistributed by Ketvoice.

## Conversion checks (recorded 2026-11-25, Linux x86-64, packages of `tools/models/requirements-pc.txt`)

All exports are reproducible: the same packages give byte-identical files (each export was run twice).

- **Beat This! final0** (`tools/beat/export_final.py`): ONNX vs PyTorch on a [1, 1500, 128] input: max abs logit
  difference 2.9e-6 (beat) and 3.8e-6 (downbeat). Whole 30 s clip through the reference chunking
  (`split_predict_aggregate`): 3.1e-5 / 2.5e-5. Reference beats on the 124 BPM test clip: 63 beats, 16 downbeats,
  every 0.48 s. The desktop feeds it the shared `BeatSpectrogram`, as it already does for the small model.
- **S-KEY** (`tools/key/export_skey.py`): the export replaces skey's whole-tensor `layer_norm` and adaptive pooling by
  the same arithmetic with a variable length; against skey's own pipeline: max abs probability difference 3.6e-7 to
  1.6e-6 on 6, 12 and 31 s of noise, 5.8e-6 on the test clip; the test nocturne in the skey repository (E-flat
  major) is read as D# major by both.
- **CLAP music** (`tools/similarity/export_clap.py`): ClapFeatureExtractor's log-mel is in the graph (fixed DFT
  convolution); against transformers' extractor + `get_audio_features`: cosine 1.000000 (max abs 4.6e-7). The int8
  version the app downloads: cosine 0.9995 (noise) and 0.9951 (test clip) with the reference.
- **Mel-Band RoFormer** (`tools/stems/export_roformer.py`): the STFT and inverse STFT stay outside the graph (ONNX has
  no inverse STFT). One 8 s chunk: max abs difference 4.2e-9 with the PyTorch model. Whole clip (16 s of a sung
  title, voice 48 % of the energy) through `separate` (the app's chunking, mirrored in `stems/Roformer.kt`) against
  Music-Source-Separation-Training's `demix`: agreement 135.6 dB (max abs difference 8.9e-8). The Kotlin pipeline is
  checked with a stand-in network (`RoformerTest`, `tools/stems/tiny_roformer.py`).
  Quality: Music-Source-Separation-Training's table lists this model at 10.98 dB vocals SDR (MDX23C: 10.17); UVR-MDX-NET
  Voc FT is not in that table and was not measured here against it (no music data set on this machine).

## Considered and not shipped

| Candidate | Why not |
| --- | --- |
| BS-RoFormer « viperx » 1297 / 1296 (vocals) | Weights published in TRvlvr/model_repo without a licence; the ONNX copies on `ashudesai/songbird-models` carry the uploader's MIT tag, not the authors'. |
| HTDemucs / HTDemucs-ft (Meta) | Code MIT; separate weight terms are not stated; 4-stem model (4-stem separation was not picked) and weaker vocals than the RoFormers. |
| Other Mel-RoFormer fine-tunes (pcunwa, GaboxR67, …) | No licence on their model cards. |
| Sucial's dereverb Mel-RoFormers | CC BY-NC-SA 4.0. |
| All-In-One structure model (mir-aidj, `taejunkim/allinone`) | Code and weights MIT, but it runs NATTEN's custom neighbourhood attention (no ONNX export) on four HTDemucs stems; not converted. Structure uses `SongStructure.kt` (self-similarity and novelty on the bar grid of Beat This!) instead. |
| SongFormer (ASLP-lab) | No licence on its model card; MuQ/MusicFM backbones. |
| MERT (m-a-p) | CC BY-NC 4.0. |
| madmom / Essentia key and embedding models | Weights CC BY-NC-SA. |
| Microsoft CLAP (`microsoft/msclap`) | MS-PL weights (weak copyleft): not used while an Apache-2.0 music CLAP exists. |
| A neural energy model | None found with a permissive licence that adds to loudness and spectral flux; `EnergyCurve` computes energy from the beat model's spectrogram. |
