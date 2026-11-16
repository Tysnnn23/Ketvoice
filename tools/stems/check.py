"""Checks that a converted LiteRT model computes what the original ONNX model computes."""
import sys
import numpy as np
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

onnx_path, tflite_path = sys.argv[1:3]
rng = np.random.default_rng(7)
x = (np.abs(rng.standard_normal((2, 1, 512, 1024))) * rng.uniform(0, 40, (1, 1, 1, 1024))).astype(np.float32)
ref = ort.InferenceSession(onnx_path).run(None, {"x": x})[0]
it = Interpreter(model_path=tflite_path, num_threads=2)
it.allocate_tensors()
i, o = it.get_input_details()[0], it.get_output_details()[0]
assert list(i["shape"]) == [2, 1, 512, 1024] and i["dtype"] == np.float32, i
it.set_tensor(i["index"], x)
it.invoke()
y = it.get_tensor(o["index"])
rel = float(np.abs(y - ref).mean() / np.abs(ref).mean())
print(tflite_path, "relative difference", rel)
assert rel < 1e-2, "converted model differs from the original"
