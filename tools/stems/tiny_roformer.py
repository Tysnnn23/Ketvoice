"""Writes the test stand-in for the studio voice network (RoformerTest): the RoFormer export's input and output
layout ([1, 2050, 801, 2], bins with both channels interleaved) with a fixed mask instead of a network: the left
channel's bins under 100 (about 2.15 kHz) pass, everything else (all of the right channel) is silenced.
Usage: python -I tiny_roformer.py desktop/src/test/resources/stems/tiny-roformer.onnx"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

mask = np.zeros((1, 2050, 1, 1), np.float32)
mask[0, 0:200:2] = 1.0  # (f s): even rows are the left channel, f < 100
graph = helper.make_graph([helper.make_node("Mul", ["spectrum", "mask"], ["vocals"])], "tiny-roformer",
                          [helper.make_tensor_value_info("spectrum", TensorProto.FLOAT, [1, 2050, 801, 2])],
                          [helper.make_tensor_value_info("vocals", TensorProto.FLOAT, [1, 2050, 801, 2])],
                          [numpy_helper.from_array(mask, "mask")])
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)], producer_name="ketvoice")
model.ir_version = 8
onnx.checker.check_model(model)
onnx.save(model, sys.argv[1])
print("wrote", sys.argv[1])
