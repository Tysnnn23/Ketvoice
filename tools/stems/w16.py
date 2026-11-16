"""Rewrites a float32 LiteRT model so that its large constants are stored in float16 and
dequantized to float32 once, when the model is loaded (what TensorFlow calls float16 weight
quantization). Usage: w16.py in.tflite out.tflite schema_generated.py"""
import sys, importlib.util, numpy as np, flatbuffers
spec = importlib.util.spec_from_file_location("schema", sys.argv[3]); S = importlib.util.module_from_spec(spec); spec.loader.exec_module(S)
buf = open(sys.argv[1], 'rb').read()
m = S.ModelT.InitFromPackedBuf(buf, 0)
deq = None
for i, oc in enumerate(m.operatorCodes):
    if max(oc.builtinCode, oc.deprecatedBuiltinCode) == S.BuiltinOperator.DEQUANTIZE: deq = i
if deq is None:
    oc = S.OperatorCodeT(); oc.builtinCode = S.BuiltinOperator.DEQUANTIZE; oc.deprecatedBuiltinCode = S.BuiltinOperator.DEQUANTIZE; oc.version = 2
    m.operatorCodes.append(oc); deq = len(m.operatorCodes) - 1
g = m.subgraphs[0]
inputs_of = set(i for op in g.operators for i in op.inputs)
new_ops = []; converted = 0
for ti in range(len(g.tensors)):
    t = g.tensors[ti]
    b = m.buffers[t.buffer] if t.buffer < len(m.buffers) else None
    data = None if b is None or b.data is None else np.asarray(b.data, dtype=np.uint8)
    if t.type != S.TensorType.FLOAT32 or data is None or data.size < 4 * 1024 or ti not in inputs_of: continue
    arr = data.view(np.float32)
    # new float16 constant
    hb = S.BufferT(); hb.data = np.frombuffer(arr.astype(np.float16).tobytes(), dtype=np.uint8)
    m.buffers.append(hb)
    ht = S.TensorT(); ht.shape = list(t.shape); ht.type = S.TensorType.FLOAT16; ht.buffer = len(m.buffers) - 1
    ht.name = (t.name or b'w') + b'_fp16'
    g.tensors.append(ht); hi = len(g.tensors) - 1
    # the original tensor becomes the dequantized output, computed once at Prepare
    b.data = None
    op = S.OperatorT(); op.opcodeIndex = deq; op.inputs = [hi]; op.outputs = [ti]
    new_ops.append(op); converted += 1
g.operators = new_ops + list(g.operators)
fb = flatbuffers.Builder(1024)
fb.Finish(m.Pack(fb), file_identifier=b"TFL3")
open(sys.argv[2], 'wb').write(fb.Output())
print('float16 constants:', converted)
left=[(g.tensors[i].name, m.buffers[g.tensors[i].buffer].data.size) for i in range(len(g.tensors)) if g.tensors[i].type==S.TensorType.FLOAT32 and m.buffers[g.tensors[i].buffer].data is not None and m.buffers[g.tensors[i].buffer].data.size>4096]
print('left', left[:10], len(left))
