"""Extract the token embedding table from bge-small-zh and test whether plain
static (bag-of-token-vector) embeddings still rank the right anchors.

If this works, the whole native-runtime problem disappears: a lookup table plus
mean pooling is ~20 lines of Kotlin, zero ONNX/TFLite runtime, zero conversion
toolchain.

Run:  python tools\static_embed_test.py
"""

import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import os
import sys

import numpy as np
import onnx

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bert_tok import BertTokenizer

BASE = os.path.join(ROOT, 'models', 'bge-small-zh')
ONNX = os.path.join(BASE, 'model_quantized.onnx')
TOKJSON = os.path.join(BASE, 'tokenizer.json')

model = onnx.load(ONNX)
graph = model.graph

# ---- locate the token embedding table -------------------------------------
init = {i.name: i for i in graph.initializer}
print('initializers: %d' % len(init))

# the Gather that consumes input_ids is the token embedding lookup
emb_name = None
for node in graph.node:
    if node.op_type == 'Gather' and node.input and node.input[0] == 'input_ids':
        emb_name = node.input[1]
        break
if emb_name is None:
    # fall back: biggest 2D initializer
    best = None
    for n, t in init.items():
        dims = [int(d) for d in t.dims]
        if len(dims) == 2 and dims[0] > 1000:
            if best is None or dims[0] * dims[1] > best[1]:
                best = (n, dims[0] * dims[1])
    if best:
        emb_name = best[0]

print('embedding tensor:', emb_name)

table = None
if emb_name:
    t = init[emb_name]
    dims = [int(d) for d in t.dims]
    dt = onnx.TensorProto.DataType.Name(t.data_type)
    print('shape=%s dtype=%s' % (dims, dt))

    if dt == 'FLOAT':
        table = onnx.numpy_helper.to_array(t)
    else:
        # quantized: find a matching scale initializer
        arr = onnx.numpy_helper.to_array(t)
        scale_name = emb_name + '_scale'
        if scale_name in init:
            scale = onnx.numpy_helper.to_array(init[scale_name])
            table = arr.astype(np.float32) * scale.reshape(1, -1)
            print('dequantized with %s, scale shape %s' % (scale_name, scale.shape))
        else:
            cands = [n for n in init if n.startswith(emb_name) and 'scale' in n]
            print('no %s; candidates: %s' % (scale_name, cands[:5]))
            if cands:
                scale = onnx.numpy_helper.to_array(init[cands[0]])
                table = arr.astype(np.float32) * scale.reshape(1, -1)
                print('dequantized with %s' % cands[0])

if table is None:
    print('FAILED to obtain the embedding table')
    print('all 2D initializers with >1000 rows:')
    for n, t in init.items():
        d = [int(x) for x in t.dims]
        if len(d) == 2 and d[0] > 1000:
            print('   %-40s %s %s' % (n, d, onnx.TensorProto.DataType.Name(t.data_type)))
    sys.exit(1)

print('table: %s  %.2f MB (float32)' % (table.shape, table.nbytes / 1e6))
print('        %.2f MB (int8)' % (table.size / 1e6))
print()

# ---- static embedding = mean of token vectors ------------------------------
tk = BertTokenizer(TOKJSON)


def static_embed(texts):
    out = []
    for t in texts:
        ids = [i for i in tk.encode(t) if i < table.shape[0]]
        v = table[ids].mean(axis=0)
        out.append(v / (np.linalg.norm(v) + 1e-9))
    return np.array(out)


anchors = ['上午', '下午', '晚上', '深夜', '凌晨', '昨天', '前天', '上周', '这个月',
           '图片', '照片', '截图', '视频', '音乐', '录音', '文档', '表格', 'PDF',
           '安装包', '压缩包', '百度', '微信', 'QQ浏览器', '迅雷', '钉钉', '夸克']
queries = [
    '我上午在百度下载的图片',
    '刚过中午那会儿存的照片',
    '上周微信发我的表格',
    '迅雷下的那个压缩包',
    '深夜里录的音',
    '昨天从百度网盘拿的视频',
]

A = static_embed(anchors)
print('=== static embeddings: top-5 anchors per query ===')
for q in queries:
    v = static_embed([q])[0]
    sims = A @ v
    order = np.argsort(-sims)[:5]
    print('  %s' % q)
    for i in order:
        print('      %-8s %.3f' % (anchors[i], sims[i]))
    print()
