"""Exports S-KEY (Deezer, ICASSP 2025; MIT code and weights, `skey/models/skey.pt` in the repository) to ONNX for
Ketmix's neural key on desktop PCs, and checks it against the reference PyTorch pipeline (`skey.key_detection`).

Input `audio` [1, samples]: mono at 22 050 Hz, any length from 4 s (the graph normalises its peak to 1 as
`load_audio` does). Output `keys` [1, 24]: the probabilities in S-KEY's order (0 A major .. 11 G# major,
12 B minor .. 23 Bb minor, see `key_map`).

Usage: python -I export_skey.py <skey source dir> <out.onnx> [audio.wav]
"""
import sys

import numpy as np
import onnxruntime as ort
import torch

src, dst = sys.argv[1:3]
audio = sys.argv[3] if len(sys.argv) > 3 else None
sys.path.insert(0, src)
sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/models")
import safe_load  # noqa: E402
from skey.key_detection import load_model_components, key_map  # noqa: E402

ckpt = safe_load.load(f"{src}/skey/models/skey.pt")
assert ckpt["audio"]["sr"] == 22050
hcqt, chromanet, crop = load_model_components(ckpt, torch.device("cpu"))
# The reference keeps skey's own layers; the exported copy gets the export-friendly layer norm below.
ref_hcqt, ref_net, _ = load_model_components(ckpt, torch.device("cpu"))


def whole_layer_norm(x, _shape, eps=1e-5):
    """layer_norm over all of (C, H, W) without weights, as skey calls it, with a time length the export can vary."""
    mean = x.mean(dim=(1, 2, 3), keepdim=True)
    var = ((x - mean) ** 2).mean(dim=(1, 2, 3), keepdim=True)
    return (x - mean) / torch.sqrt(var + eps)


for block in list(chromanet.convnext_blocks) + list(chromanet.time_downsampling_blocks):
    if getattr(block, "norm", None) is torch.nn.functional.layer_norm:
        block.norm = whole_layer_norm


class SKey(torch.nn.Module):
    """skey's infer_key as one graph; the global average pool over (12, time) is a mean over time (same values)."""

    def __init__(self):
        super().__init__()
        self.hcqt, self.net = hcqt, chromanet

    def forward(self, audio):
        audio = audio / torch.clamp(audio.abs().amax(dim=1, keepdim=True), min=1e-9)
        x = self.hcqt(audio)[:, :, 0:84, :]
        for conv, down in zip(self.net.convnext_blocks, self.net.time_downsampling_blocks):
            x = conv(down(x))
        x = self.net.octave_pool(x).mean(dim=3, keepdim=True)
        x = self.net.batch_norm(self.net.classifier(x)).flatten(1)
        return torch.softmax(x / self.net.temperature, dim=-1)


def reference(wave):
    with torch.no_grad():
        w = torch.from_numpy(wave)
        w = w / w.abs().max()
        return ref_net(crop(ref_hcqt(w.unsqueeze(0)), torch.zeros(1))).numpy()


model = SKey().eval()
x = torch.from_numpy(np.random.default_rng(5).standard_normal((1, 22050 * 12)).astype(np.float32) * 0.1)
with torch.no_grad():
    torch.onnx.export(model, (x,), dst, input_names=["audio"], output_names=["keys"], opset_version=17, dynamo=False,
                      dynamic_axes={"audio": {1: "samples"}})
session = ort.InferenceSession(dst, providers=["CPUExecutionProvider"])
for seconds in (6, 12, 31):
    w = np.random.default_rng(seconds).standard_normal((1, 22050 * seconds)).astype(np.float32) * 0.1
    got = session.run(None, {"audio": w})[0]
    ref = reference(w)
    diff = float(np.abs(got - ref).max())
    print(f"noise {seconds} s: max abs difference {diff:.2e}")
    assert diff < 1e-4

if audio:
    import soundfile as sf
    import soxr
    wave, sr = sf.read(audio, dtype="float32", always_2d=True)
    mono = soxr.resample(wave.mean(axis=1), sr, 22050).astype(np.float32)[None, :]
    got = session.run(None, {"audio": mono})[0]
    ref = reference(mono)
    print("audio: max abs difference", float(np.abs(got - ref).max()), "->", key_map[int(got.argmax())],
          f"({got.max():.2f}); reference", key_map[int(ref.argmax())])
    assert int(got.argmax()) == int(ref.argmax()) and np.abs(got - ref).max() < 1e-4
print("ok", dst)
