import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

import os
import sys

import numpy as np
import onnx

sys.path.insert(0, os.path.join(ROOT, 'tools'))
from bert_tok import BertTokenizer

BASE = os.path.join(ROOT, 'models', 'bge-small-zh')
model = onnx.load(os.path.join(BASE, 'model_quantized.onnx'))
g = model.graph
init = {i.name: i for i in g.initializer}

EMB = 'embeddings.word_embeddings.weight_quantized'
t = init[EMB]
print('shape', [int(d) for d in t.dims], 'dtype', onnx.TensorProto.DataType.Name(t.data_type))

arr = onnx.numpy_helper.to_array(t).astype(np.float32)

scale = zp = None
for node in g.node:
    if node.op_type == 'DequantizeLinear' and node.input and node.input[0] == EMB:
        scale = onnx.numpy_helper.to_array(init[node.input[1]])
        if len(node.input) > 2 and node.input[2] in init:
            zp = onnx.numpy_helper.to_array(init[node.input[2]])
        print('DequantizeLinear scale size', scale.size, 'zp', zp is not None, 'scale[:3]', scale.ravel()[:3])
        break

if scale is None:
    print('NO DequantizeLinear node; dumping graph consumers of', EMB)
    for n in g.node:
        if EMB in n.input:
            print('  ', n.op_type, list(n.input), '->', list(n.output))
    sys.exit(1)

if zp is not None:
    arr = arr - zp.astype(np.float32)
if scale.size == 1:
    arr = arr * float(scale)
else:
    arr = arr * scale.astype(np.float32).reshape(1, -1)

print('table', arr.shape, '%.2f MB fp32, %.2f MB int8' % (arr.nbytes / 1e6, arr.size / 1e6))
np.save(os.path.join(ROOT, 'models', 'bge-small-zh', 'emb_table.npy'), arr.astype(np.float32))
print()

tk = BertTokenizer(os.path.join(BASE, 'tokenizer.json'))


def emb(texts):
    out = []
    for x in texts:
        ids = [i for i in tk.encode(x) if i < arr.shape[0]]
        v = arr[ids].mean(axis=0)
        out.append(v / (np.linalg.norm(v) + 1e-9))
    return np.array(out)


anchors = ['上午', '下午', '晚上', '深夜', '凌晨', '昨天', '前天', '上周', '这个月',
           '图片', '照片', '截图', '视频', '音乐', '录音', '文档', '表格', 'PDF',
           '安装包', '压缩包', '百度', '微信', 'QQ浏览器', '迅雷', '钉钉', '夸克']
queries = ['我上午在百度下载的图片', '刚过中午那会儿存的照片', '上周微信发我的表格',
           '迅雷下的那个压缩包', '深夜里录的音', '昨天从百度网盘拿的视频']

A = emb(anchors)
print('=== STATIC (mean of token vectors) ===')
for q in queries:
    v = emb([q])[0]
    s = A @ v
    o = np.argsort(-s)[:5]
    print('  %s' % q)
    for i in o:
        print('      %-8s %.3f' % (anchors[i], s[i]))
    print()
