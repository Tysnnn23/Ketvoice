"""Checks that the converted LiteRT model computes what the original ONNX model computes, on random input and
on a 120 BPM pulse, and writes which converted output is the beat and which is the downbeat.
Usage: check.py model.onnx model.tflite io.json"""
import json
import sys
import numpy as np
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

onnx_path, tflite_path, io_path = sys.argv[1:4]
FRAMES, MELS = 1500, 128
rng = np.random.default_rng(11)


def sample(kind):
    base = np.abs(rng.standard_normal((1, FRAMES, MELS))) * rng.uniform(0, 1, (1, 1, MELS))
    if kind == 1:  # a pulse every 25 frames (120 BPM at 50 frames a second), strongest in the low bands
        pulse = np.zeros((1, FRAMES, 1))
        pulse[:, ::25, :] = 1
        base = base * 0.2 + pulse * np.linspace(1, 0.1, MELS)[None, None, :] * 3
    return np.log1p(1000 * base).astype(np.float32)


def peaks(logits):
    x = logits.reshape(-1)
    pad = np.pad(x, 3, constant_values=-1e9)
    window = np.stack([pad[i:i + len(x)] for i in range(7)])
    return set(np.flatnonzero((x > 0) & (x >= window.max(axis=0))).tolist())


ref = ort.InferenceSession(onnx_path)
it = Interpreter(model_path=tflite_path, num_threads=2)
it.allocate_tensors()
inp = it.get_input_details()[0]
assert list(inp["shape"]) == [1, FRAMES, MELS] and inp["dtype"] == np.float32, inp
outs = it.get_output_details()
mapping = {}
for kind in (0, 1):
    x = sample(kind)
    reference = dict(zip(("beat", "downbeat"), ref.run(None, {"spectrogram": x})))
    it.set_tensor(inp["index"], x)
    it.invoke()
    ys = [it.get_tensor(o["index"]).reshape(1, FRAMES) for o in outs]
    for name, r in reference.items():
        errs = [float(np.abs(y - r).mean() / (np.abs(r).mean() + 1e-6)) for y in ys]
        best = int(np.argmin(errs))
        print("input", kind, name, "relative differences", errs)
        assert errs[best] < 0.03, "converted model differs from the original"
        mapping.setdefault(name, best)
        assert mapping[name] == best, "outputs swapped between inputs"
        a, b = peaks(r), peaks(ys[best])
        if a or b:
            f1 = 2 * len(a & b) / (len(a) + len(b))
            print("  peak agreement", round(f1, 3), "of", len(a), "and", len(b))
            assert f1 > 0.85, "different peaks"
io = {"input": {"name": inp["name"], "shape": [int(v) for v in inp["shape"]]},
      "outputs": [{"name": o["name"], "shape": [int(v) for v in o["shape"]]} for o in outs],
      "beat_output": mapping["beat"], "downbeat_output": mapping["downbeat"]}
json.dump(io, open(io_path, "w"), indent=1)
print(json.dumps(io))
