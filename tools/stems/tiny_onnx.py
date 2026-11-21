# Writes the tiny ONNX stand-ins of desktop/src/test/resources/stems/ (python3 tools/stems/tiny_onnx.py <dir>).
# They carry the input and output names and shapes of the separation models the desktop app runs
# (sherpa-onnx's Spleeter 2 stems and UVR-MDX-NET Voc FT), so the desktop tests check the ONNX Runtime
# plumbing without the real 145 MB of models.
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

out = sys.argv[1]


def save(graph, name):
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="ketvoice-test")
    model.ir_version = 8
    onnx.checker.check_model(model)
    onnx.save(model, f"{out}/{name}")


def const(name, value, dtype=np.float32):
    return numpy_helper.from_array(np.array(value, dtype=dtype), name)


# Spleeter-shaped, x [2, 1, 512, 1024] -> y. Vocals: x * softmax(x over the bins) * 300 (depends on the
# whole frame, so a frame or bin order mistake shows). Accompaniment: x * 0.5.
x = helper.make_tensor_value_info("x", TensorProto.FLOAT, [2, 1, 512, 1024])
y = helper.make_tensor_value_info("y", TensorProto.FLOAT, [2, 1, 512, 1024])
save(helper.make_graph([
    helper.make_node("Softmax", ["x"], ["s"], axis=3),
    helper.make_node("Mul", ["x", "s"], ["m"]),
    helper.make_node("Mul", ["m", "k"], ["y"]),
], "vocals", [x], [y], [const("k", 300.0)]), "tiny-vocals.onnx")
save(helper.make_graph([helper.make_node("Mul", ["x", "h"], ["y"])], "accompaniment", [x], [y], [const("h", 0.5)]),
     "tiny-accompaniment.onnx")

# UVR-shaped, input [1, 4, 768, 64] -> output: left and right swapped, times 0.5 (a channel layout mistake shows).
i = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 4, 768, 64])
o = helper.make_tensor_value_info("output", TensorProto.FLOAT, [1, 4, 768, 64])
save(helper.make_graph([
    helper.make_node("Split", ["input", "split"], ["l", "r"], axis=1),
    helper.make_node("Concat", ["r", "l"], ["c"], axis=1),
    helper.make_node("Mul", ["c", "half"], ["output"]),
], "uvr", [i], [o], [const("half", 0.5), const("split", [2, 2], np.int64)]), "tiny-uvr.onnx")
