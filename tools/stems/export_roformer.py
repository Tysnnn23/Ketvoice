"""Exports Kimberley Jensen's Mel-Band RoFormer vocal model (weights MIT, huggingface.co/KimberleyJSN/melbandroformer;
architecture from ZFTurbo's Music-Source-Separation-Training, MIT, after lucidrains' BS-RoFormer, MIT) to ONNX: the
« studio » voice model of Ketmix on desktop PCs.

The STFT and its inverse stay outside the graph (ONNX has no inverse STFT): the app computes the centred, reflect-padded
STFT (Hann 2048, hop 441, unnormalised) of an 8 s chunk (352 800 samples, 801 frames) of each channel and feeds
`spectrum` [1, 2050, 801, 2]: frequency-major, channels interleaved ((f s): bin 0 left, bin 0 right, bin 1 left, ...),
then real/imaginary. The graph returns `vocals`, the voice's complex spectrum in the same layout (its DC bin zeroed, as
the reference does), which the app turns back into sound with the inverse STFT. Chunks overlap by half and are blended
with the reference's linear fades (`separate` below; the app does the same in Kotlin, `stems/Roformer.kt`).

Checks: one chunk through the graph against the PyTorch model, then a whole clip through `separate` against the
reference chunking (`utils.model_utils.demix` of Music-Source-Separation-Training).

Usage: python -I export_roformer.py export|check <Music-Source-Separation-Training dir> <MelBandRoformer.ckpt> <out.onnx> [audio.wav]
(two runs, so that the export and the checks each fit in a few GB of memory)
"""
import gc
import sys

import numpy as np
import onnxruntime as ort
import torch

step, msst, ckpt, dst = sys.argv[1:5]
audio = sys.argv[5] if len(sys.argv) > 5 else None
sys.path.insert(0, msst)
sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/models")
import safe_load  # noqa: E402
from models.bs_roformer.mel_band_roformer import MelBandRoformer  # noqa: E402

RATE, CHUNK, N_FFT, HOP, OVERLAP = 44_100, 352_800, 2048, 441, 2
FRAMES = CHUNK // HOP + 1


def build():
    """The reference model with Kim's weights (configs/KimberleyJensen/config_vocals_mel_band_roformer_kj.yaml)."""
    m = MelBandRoformer(dim=384, depth=6, stereo=True, num_stems=1, time_transformer_depth=1, freq_transformer_depth=1,
                        num_bands=60, dim_head=64, heads=8, attn_dropout=0, ff_dropout=0, flash_attn=True, dim_freqs_in=1025,
                        sample_rate=RATE, stft_n_fft=N_FFT, stft_hop_length=HOP, stft_win_length=N_FFT,
                        stft_normalized=False, mask_estimator_depth=2).eval()
    state = safe_load.load(ckpt)
    m.load_state_dict(state.get("state_dict", state))
    del state
    gc.collect()
    return m


class Core(torch.nn.Module):
    """MelBandRoformer.forward from its STFT to its masked STFT, without complex tensors."""

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, spectrum):  # [1, 2050, T, 2]
        m = self.m
        x = spectrum[:, m.freq_indices]
        b, f, t, c = x.shape
        x = x.permute(0, 2, 1, 3).reshape(b, t, f * c)
        x = m.band_split(x)
        for time_transformer, freq_transformer in m.layers:
            bb, tt, ff, d = x.shape
            x = x.permute(0, 2, 1, 3).reshape(bb * ff, tt, d)
            x = time_transformer(x)
            x = x.reshape(bb, ff, tt, d).permute(0, 2, 1, 3).reshape(bb * tt, ff, d)
            x = freq_transformer(x)
            x = x.reshape(bb, tt, ff, d)
        mask = m.mask_estimators[0](x)  # [1, T, L * 2]
        mask = mask.reshape(b, t, -1, 2).permute(0, 2, 1, 3)  # [1, L, T, 2]
        index = m.freq_indices.view(1, -1, 1, 1).expand(b, -1, t, 2)
        summed = torch.zeros_like(spectrum).scatter_add(1, index, mask)
        denom = m.num_bands_per_freq.repeat_interleave(2).clamp(min=1e-8).to(spectrum.dtype).view(1, -1, 1, 1)
        mr, mi = (summed / denom).unbind(-1)
        sr, si = spectrum.unbind(-1)
        out = torch.stack((sr * mr - si * mi, sr * mi + si * mr), dim=-1)
        keep = torch.ones(out.shape[1], dtype=out.dtype)
        keep[:2] = 0  # zero_dc: bin 0 of both channels
        return out * keep.view(1, -1, 1, 1)


def stft(chunk):  # [2, CHUNK] -> [1, 2050, T, 2]
    window = torch.hann_window(N_FFT)
    s = torch.view_as_real(torch.stft(torch.from_numpy(chunk), N_FFT, HOP, N_FFT, window, return_complex=True))
    return s.permute(1, 0, 2, 3).reshape(1, -1, s.shape[2], 2).numpy()


def istft(spec):  # [1, 2050, T, 2] -> [2, CHUNK]
    s = torch.from_numpy(spec).reshape(-1, 2, spec.shape[2], 2).permute(1, 0, 2, 3).contiguous()
    return torch.istft(torch.view_as_complex(s), N_FFT, HOP, N_FFT, torch.hann_window(N_FFT)).numpy()


def separate(session, mix):
    """The voice of [2, n] samples, chunked and blended as the reference `demix` does (generic mode)."""
    fade, step = CHUNK // 10, CHUNK // OVERLAP
    border = CHUNK - step
    length = mix.shape[1]
    padded = length > 2 * border
    if padded:
        mix = np.pad(mix, ((0, 0), (border, border)), mode="reflect")
    window = np.ones(CHUNK, dtype=np.float32)
    window[:fade], window[-fade:] = np.linspace(0, 1, fade), np.linspace(1, 0, fade)
    result = np.zeros_like(mix)
    counter = np.zeros(mix.shape[1], dtype=np.float32)
    i = 0
    while i < mix.shape[1]:
        part = mix[:, i:i + CHUNK]
        n = part.shape[1]
        part = np.pad(part, ((0, 0), (0, CHUNK - n)), mode="reflect" if n > CHUNK // 2 else "constant")
        voice = istft(session.run(None, {"spectrum": stft(part.astype(np.float32))})[0])
        w = window.copy()
        if i == 0:
            w[:fade] = 1
        if i + step >= mix.shape[1]:
            w[-fade:] = 1
        result[:, i:i + n] += voice[:, :n] * w[:n]
        counter[i:i + n] += w[:n]
        i += step
    result /= counter
    return result[:, border:-border] if padded else result


rng = np.random.default_rng(11)
chunk = (rng.standard_normal((2, CHUNK)) * 0.1).astype(np.float32)
if step == "export":
    with torch.no_grad():
        torch.onnx.export(Core(build()).eval(), (torch.from_numpy(stft(chunk)),), dst, input_names=["spectrum"],
                          output_names=["vocals"], opset_version=17, dynamo=False)
    print("exported", dst)
    sys.exit(0)
# The graph's results first, then the reference's: never both models in memory at once.
options = ort.SessionOptions()
options.enable_cpu_mem_arena = False
session = ort.InferenceSession(dst, options, providers=["CPUExecutionProvider"])
mine = istft(session.run(None, {"spectrum": stft(chunk)})[0])
mix = ours = None
if audio:
    import soundfile as sf
    wave, sr = sf.read(audio, dtype="float32", always_2d=True)
    assert sr == RATE
    mix = wave[: RATE * 16].T.copy()
    ours = separate(session, mix)
del session
gc.collect()

model = build()
with torch.no_grad():
    reference = model(torch.from_numpy(chunk)[None]).numpy()[0]
print("one chunk: max abs difference", float(np.abs(mine - reference).max()), "reference peak", float(np.abs(reference).max()))
assert np.abs(mine - reference).max() < 2e-3

if audio:
    from utils.model_utils import demix  # noqa: E402
    from ml_collections import ConfigDict  # noqa: E402

    config = ConfigDict({"audio": {"chunk_size": CHUNK}, "inference": {"num_overlap": OVERLAP, "batch_size": 1},
                         "training": {"instruments": ["vocals", "other"], "target_instrument": "vocals", "use_amp": False}})
    theirs = demix(config, model, mix, torch.device("cpu"), "mel_band_roformer")["vocals"]
    err = ours - theirs
    snr = 10 * np.log10((theirs ** 2).sum() / max(1e-12, (err ** 2).sum()))
    print(f"whole clip: max abs difference {np.abs(err).max():.2e}, agreement {snr:.1f} dB; voice share",
          f"{(theirs ** 2).sum() / (mix ** 2).sum():.3f}")
    # Against the sound of the clip itself as well, for a clip where the model hears almost no voice.
    assert np.abs(err).max() < 1e-4 and (snr > 60 or (err ** 2).sum() < 1e-8 * (mix ** 2).sum())
print("ok", dst)
