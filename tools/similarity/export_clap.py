"""Exports the audio side of LAION's CLAP for music (`laion/larger_clap_music`, Apache-2.0 weights; LAION-AI/CLAP
code CC0-1.0, run here through Hugging Face transformers, Apache-2.0) to ONNX: Ketmix's sound-similarity embedding on
desktop PCs. The log-mel front end of ClapFeatureExtractor (48 kHz, Hann 1024, hop 480, 64 Slaney mels 50..14 000 Hz,
power in dB) is part of the graph, as a fixed DFT convolution, so the app only resamples to 48 kHz.

Input `audio` [1, 480000] (10 s, mono, 48 kHz); output `embedding` [1, 512], unit length (cosine = dot product).
Checked against transformers' own ClapFeatureExtractor + ClapModel.get_audio_features. The app downloads the int8
version (dynamic quantisation of the MatMul/Gemm weights: 77 MB instead of 282 MB), checked too (cosine >= 0.99).

Usage: python -I export_clap.py <model dir with config.json, preprocessor_config.json, pytorch_model.bin> <fp32.onnx> <int8.onnx> [audio.wav]
"""
import sys

import numpy as np
import onnxruntime as ort
import torch
from transformers import ClapFeatureExtractor, ClapModel

src, dst, dst8 = sys.argv[1:4]
audio = sys.argv[4] if len(sys.argv) > 4 else None
SAMPLES = 480_000
extractor = ClapFeatureExtractor.from_pretrained(src)
model = ClapModel.from_pretrained(src).eval()
assert not model.config.audio_config.enable_fusion and extractor.truncation == "rand_trunc"


class Embedding(torch.nn.Module):
    def __init__(self):
        super().__init__()
        n_fft, self.hop = extractor.n_fft, extractor.hop_length
        k = np.arange(n_fft)
        window = 0.5 - 0.5 * np.cos(2 * np.pi * k / n_fft)  # periodic Hann, as transformers' window_function
        f = np.arange(n_fft // 2 + 1)[:, None]
        angle = 2 * np.pi * f * k[None, :] / n_fft
        basis = np.concatenate([np.cos(angle) * window, -np.sin(angle) * window])[:, None, :]
        self.register_buffer("dft", torch.tensor(basis, dtype=torch.float32))
        self.register_buffer("mel", torch.tensor(extractor.mel_filters_slaney, dtype=torch.float32))
        self.bins = n_fft // 2 + 1
        self.pad = n_fft // 2
        self.clap = model

    def forward(self, audio):
        x = torch.nn.functional.pad(audio.unsqueeze(1), (self.pad, self.pad), mode="reflect")
        spec = torch.nn.functional.conv1d(x, self.dft, stride=self.hop)
        power = spec[:, : self.bins] ** 2 + spec[:, self.bins:] ** 2
        mel = torch.matmul(power.transpose(1, 2), self.mel)
        logmel = 10.0 * torch.log10(torch.clamp(mel, min=1e-10))
        emb = self.clap.get_audio_features(input_features=logmel.unsqueeze(1))
        return emb / torch.clamp(emb.norm(dim=-1, keepdim=True), min=1e-12)


def reference(wave):
    feats = extractor(wave, sampling_rate=48_000, return_tensors="pt")
    with torch.no_grad():
        emb = model.get_audio_features(input_features=feats["input_features"])
    return (emb / emb.norm(dim=-1, keepdim=True)).numpy()


net = Embedding().eval()
x = torch.from_numpy((np.random.default_rng(1).standard_normal((1, SAMPLES)) * 0.1).astype(np.float32))
with torch.no_grad():
    torch.onnx.export(net, (x,), dst, input_names=["audio"], output_names=["embedding"], opset_version=17, dynamo=False)
session = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
clips = [("noise", x.numpy()[0])]
if audio:
    import soundfile as sf
    import soxr
    wave, sr = sf.read(audio, dtype="float32", always_2d=True)
    mono = soxr.resample(wave.mean(axis=1), sr, 48_000).astype(np.float32)
    clips.append(("audio", mono[:SAMPLES] if len(mono) >= SAMPLES else np.pad(mono, (0, SAMPLES - len(mono)))))
embeddings = []
for name, wave in clips:
    got = session.run(None, {"audio": wave[None, :].astype(np.float32)})[0]
    ref = reference(wave)
    cosine = float((got * ref).sum())
    print(f"{name}: cosine with the reference {cosine:.6f}, max abs difference {float(np.abs(got - ref).max()):.2e}")
    assert got.shape == (1, 512) and cosine > 0.9999
    embeddings.append(got[0])
if len(embeddings) > 1:
    print("noise vs audio cosine", float(embeddings[0] @ embeddings[1]))

from onnxruntime.quantization import QuantType, quantize_dynamic  # noqa: E402

quantize_dynamic(dst, dst8, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])
small = ort.InferenceSession(dst8, providers=["CPUExecutionProvider"])
for name, wave in clips:
    got = small.run(None, {"audio": wave[None, :].astype(np.float32)})[0]
    cosine = float((got * reference(wave)).sum())
    print(f"{name} int8: cosine with the reference {cosine:.4f}")
    assert cosine > 0.99
print("ok", dst, dst8)
