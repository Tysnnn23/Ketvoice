"""Writes the tiny network the desktop app runs once on the graphics card and once on the processor to check the GPU
runtime before trusting it (`OrtAccel.verify`): a convolution, a transformer-like attention (MatMul, Softmax),
LayerNormalization and GELU-style Erf, the operators Ketmix's models lean on. A few KB, fixed seed, no learnt weights.
Usage: python -I gpu_probe_onnx.py desktop/src/main/resources/ketmix/gpu-probe.onnx"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

rng = np.random.default_rng(42)
D, T = 16, 32


def const(name, shape, scale=0.3):
    return numpy_helper.from_array((rng.standard_normal(shape) * scale).astype(np.float32), name)


inits = [const("conv_w", (D, 4, 3)), const("conv_b", (D,)), const("wq", (D, D)), const("wk", (D, D)), const("wv", (D, D)),
         numpy_helper.from_array(np.ones(D, np.float32), "ln_s"), numpy_helper.from_array(np.zeros(D, np.float32), "ln_b"),
         numpy_helper.from_array(np.array([0.25], np.float32), "scale"), numpy_helper.from_array(np.array([0.70710678], np.float32), "rsqrt2"),
         numpy_helper.from_array(np.array([1.0], np.float32), "one"), numpy_helper.from_array(np.array([0.5], np.float32), "half")]
nodes = [
    helper.make_node("Conv", ["x", "conv_w", "conv_b"], ["c"], pads=[1, 1]),          # [1, D, T]
    helper.make_node("Transpose", ["c"], ["h"], perm=[0, 2, 1]),                       # [1, T, D]
    helper.make_node("LayerNormalization", ["h", "ln_s", "ln_b"], ["n"], axis=-1),
    helper.make_node("MatMul", ["n", "wq"], ["q"]), helper.make_node("MatMul", ["n", "wk"], ["k"]),
    helper.make_node("MatMul", ["n", "wv"], ["v"]),
    helper.make_node("Transpose", ["k"], ["kt"], perm=[0, 2, 1]),
    helper.make_node("MatMul", ["q", "kt"], ["s0"]), helper.make_node("Mul", ["s0", "scale"], ["s"]),
    helper.make_node("Softmax", ["s"], ["a"], axis=-1), helper.make_node("MatMul", ["a", "v"], ["o"]),
    helper.make_node("Mul", ["o", "rsqrt2"], ["g0"]), helper.make_node("Erf", ["g0"], ["g1"]),
    helper.make_node("Add", ["g1", "one"], ["g2"]), helper.make_node("Mul", ["o", "g2"], ["g3"]),
    helper.make_node("Mul", ["g3", "half"], ["y"]),
]
graph = helper.make_graph(nodes, "ketvoice-gpu-probe", [helper.make_tensor_value_info("x", TensorProto.FLOAT, [1, 4, T])],
                          [helper.make_tensor_value_info("y", TensorProto.FLOAT, [1, T, D])], inits)
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="ketvoice")
model.ir_version = 8
onnx.checker.check_model(model)
onnx.save(model, sys.argv[1])
print("wrote", sys.argv[1])
