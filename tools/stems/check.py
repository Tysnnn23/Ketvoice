"""Checks that a converted LiteRT model computes what the original ONNX model computes.
Usage: check.py model.onnx model.tflite [input_name d0,d1,d2,d3] (Spleeter's x 2,1,512,1024 by default)."""
import sys
import numpy as np
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

onnx_path, tflite_path = sys.argv[1:3]
name = sys.argv[3] if len(sys.argv) > 3 else "x"
shape = [int(v) for v in sys.argv[4].split(",")] if len(sys.argv) > 4 else [2, 1, 512, 1024]
rng = np.random.default_rng(7)
if name == "x":  # Spleeter reads magnitudes
    x = (np.abs(rng.standard_normal(shape)) * rng.uniform(0, 40, (1, 1, 1, shape[-1]))).astype(np.float32)
else:  # UVR reads real and imaginary parts
    x = (rng.standard_normal(shape) * rng.uniform(0, 20, (1, 1, shape[2], 1))).astype(np.float32)
ref = ort.InferenceSession(onnx_path).run(None, {name: x})[0]
it = Interpreter(model_path=tflite_path, num_threads=2)
it.allocate_tensors()
i, o = it.get_input_details()[0], it.get_output_details()[0]
assert list(i["shape"]) == shape and i["dtype"] == np.float32, i
it.set_tensor(i["index"], x)
it.invoke()
y = it.get_tensor(o["index"])
if y.shape != ref.shape:  # channels last after conversion
    y = np.moveaxis(y, -1, 1)
rel = float(np.abs(y - ref).mean() / np.abs(ref).mean())
print(tflite_path, "relative difference", rel)
assert rel < 1e-2, "converted model differs from the original"
