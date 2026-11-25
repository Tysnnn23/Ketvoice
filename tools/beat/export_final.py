"""Exports Beat This! final0 (CPJKU, ISMIR 2024, MIT code and checkpoints) to ONNX for Ketmix on desktop PCs and
checks the export against the reference PyTorch model on real-sized inputs.

Input `spectrogram` [1, FRAMES, 128] (log-mel frames at 50 a second, the shared BeatSpectrogram), outputs `beat` and
`downbeat` logits [1, FRAMES]. FRAMES is 1500 (30 s), the chunk the reference inference uses.

Usage: python -I export_final.py <beat_this source dir> <final0.ckpt> <out.onnx> [audio.wav]
With an audio file, the whole reference pipeline (beat_this.inference.Audio2Beats) and a chunked run of the ONNX
model on the same spectrogram are compared beat by beat as well.
"""
import os
import sys

import numpy as np
import onnxruntime as ort
import torch

src, ckpt, dst = sys.argv[1:4]
audio = sys.argv[4] if len(sys.argv) > 4 else None
sys.path.insert(0, src)
from beat_this.inference import load_model  # noqa: E402

FRAMES, MELS = int(os.environ.get("FRAMES", "1500")), 128
torch.manual_seed(0)
model = load_model(ckpt, "cpu").eval()


class Logits(torch.nn.Module):
    """The two heads as two named outputs."""

    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, spectrogram):
        out = self.net(spectrogram)
        return out["beat"], out["downbeat"]


wrapped = Logits(model).eval()
x = torch.from_numpy(np.log1p(1000 * np.abs(np.random.default_rng(3).standard_normal((1, FRAMES, MELS)))
                              * np.random.default_rng(4).uniform(0, 1, (1, 1, MELS))).astype(np.float32))
with torch.no_grad():
    torch.onnx.export(wrapped, (x,), dst, input_names=["spectrogram"], output_names=["beat", "downbeat"],
                      opset_version=17, dynamo=False, do_constant_folding=True)
    ref = wrapped(x)
session = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
got = session.run(None, {"spectrogram": x.numpy()})
for name, p, q in zip(("beat", "downbeat"), ref, got):
    diff = float(np.abs(p.numpy() - q).max())
    print(name, q.shape, "max abs difference", diff)
    assert q.shape == (1, FRAMES) and diff < 2e-3, name

if audio:
    from beat_this.inference import Audio2Beats, split_predict_aggregate  # noqa: E402
    from beat_this.preprocessing import LogMelSpect, load_audio  # noqa: E402
    import soxr  # noqa: E402

    signal, sr = load_audio(audio)
    if signal.ndim == 2:
        signal = signal.mean(1)
    signal = soxr.resample(signal, in_rate=sr, out_rate=22050)
    spect = LogMelSpect(device="cpu")(torch.tensor(signal, dtype=torch.float32))

    def onnx_model(chunk):
        b, d = session.run(None, {"spectrogram": chunk.numpy().astype(np.float32)})
        return {"beat": torch.from_numpy(b), "downbeat": torch.from_numpy(d)}

    with torch.no_grad():
        mine = split_predict_aggregate(spect, FRAMES, 6, "keep_first", onnx_model)
        theirs = split_predict_aggregate(spect, FRAMES, 6, "keep_first", model)
    for name in ("beat", "downbeat"):
        diff = float((mine[name] - theirs[name]).abs().max())
        print("whole title", name, "max abs logit difference", diff)
        assert diff < 2e-3
    beats, downs = Audio2Beats(ckpt, "cpu", dbn=False)(signal, 22050)
    print("reference beats", len(beats), "downbeats", len(downs), "first", np.round(beats[:4], 3))
print("ok", dst)
