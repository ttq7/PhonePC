import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

import numpy as np
import onnx

BASE = os.path.join(ROOT, 'models', 'bge-small-zh')
g = onnx.load(BASE + r'\model_quantized.onnx').graph
init = {i.name: i for i in g.initializer}

probe = 'Gather_output_0_quantized'
for depth in range(3):
    nxt = []
    for n in g.node:
        if probe in n.input:
            print('  %-18s inputs=%s' % (n.op_type, list(n.input)))
            if n.op_type == 'DequantizeLinear':
                scale = onnx.numpy_helper.to_array(init[n.input[1]])
                zp = onnx.numpy_helper.to_array(init[n.input[2]]) if len(n.input) > 2 and n.input[2] in init else None
                print('        scale size=%d value[:3]=%s  zero_point=%s' %
                      (scale.size, scale.ravel()[:3], None if zp is None else zp.ravel()[:3]))
            nxt += list(n.output)
    if not nxt:
        break
    probe = nxt[0]
