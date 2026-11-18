"""Fixes Beat This! small's input to [1, 1500, 128] (30 s at 50 frames/s), folds the shape arithmetic into
constants and checks that the result computes what the original ONNX model computes.
Usage: prepare.py small0.onnx small0_fixed.onnx"""
import sys
import numpy as np
import onnx
import onnxruntime as ort
from onnx.tools import update_model_dims
from onnxsim import simplify

src, dst = sys.argv[1:3]
FRAMES, MELS = 1500, 128
model = onnx.load(src)
model = update_model_dims.update_inputs_outputs_dims(
    model, {"spectrogram": [1, FRAMES, MELS]}, {"beat": [1, FRAMES], "downbeat": [1, FRAMES]})
model, ok = simplify(model)
assert ok, "onnxsim could not check the simplified model"
onnx.save(model, dst)
rng = np.random.default_rng(3)
x = np.log1p(1000 * np.abs(rng.standard_normal((1, FRAMES, MELS))) * rng.uniform(0, 1, (1, 1, MELS))).astype(np.float32)
a = ort.InferenceSession(src).run(None, {"spectrogram": x})
b = ort.InferenceSession(dst).run(None, {"spectrogram": x})
for name, p, q in zip(("beat", "downbeat"), a, b):
    print(name, p.shape, "max abs difference", float(np.abs(p - q).max()))
    assert np.abs(p - q).max() < 1e-3
ops = sorted({n.op_type for n in model.graph.node})
print("nodes", len(model.graph.node), "ops", ops)
