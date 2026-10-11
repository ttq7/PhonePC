import os
import sys

import numpy as np
import onnx

sys.path.insert(0, r'E:\此电脑\tools')
from bert_tok import BertTokenizer

BASE = r'E:\此电脑\models\bge-small-zh'
g = onnx.load(BASE + r'\model_quantized.onnx').graph
init = {i.name: i for i in g.initializer}
raw = onnx.numpy_helper.to_array(init['embeddings.word_embeddings.weight_quantized'])
print('raw table', raw.shape, raw.dtype, 'min', int(raw.min()), 'max', int(raw.max()),
      'mean %.1f' % float(raw.mean()))

tk = BertTokenizer(BASE + r'\tokenizer.json')
anchors = ['上午', '下午', '晚上', '深夜', '凌晨', '昨天', '前天', '上周', '这个月',
           '图片', '照片', '截图', '视频', '音乐', '录音', '文档', '表格', 'PDF',
           '安装包', '压缩包', '百度', '微信', 'QQ浏览器', '迅雷', '钉钉', '夸克']
queries = ['我上午在百度下载的图片', '刚过中午那会儿存的照片', '上周微信发我的表格',
           '迅雷下的那个压缩包', '深夜里录的音', '昨天从百度网盘拿的视频']


def run(table, tag):
    t = table.astype(np.float32)
    n = np.linalg.norm(t, axis=1, keepdims=True)
    n[n == 0] = 1e-9
    t = t / n

    def emb(texts):
        out = []
        for x in texts:
            ids = [i for i in tk.encode(x) if i < t.shape[0]]
            v = t[ids].mean(axis=0)
            out.append(v / (np.linalg.norm(v) + 1e-9))
        return np.array(out)

    A = emb(anchors)
    print('=== %s ===' % tag)
    for q in queries:
        v = emb([q])[0]
        s = A @ v
        o = np.argsort(-s)[:4]
        print('  %s' % q)
        print('      ' + ' | '.join('%s %.3f' % (anchors[i], s[i]) for i in o))
    print()


run(raw, 'raw uint8 as float')
run(raw.astype(np.float32) - 128.0, 'uint8 - 128 (zp=128)')
