"""Quantize bge-small-zh to an int8 TFLite flatbuffer.

onnx2tf's built-in INT8 path assumes 4D float image inputs, so it needs a
calibration dataset supplied with -cind. We generate one by tokenizing a corpus
of realistic Chinese file-search phrases with the verified tokenizer.

Run:  python tools\quantize_tflite.py
"""
import itertools
import os
import subprocess
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bert_tok import BertTokenizer

BASE = r'E:\此电脑\models\bge-small-zh'
SRC = os.path.join(BASE, 'model_quantized.onnx')
TOKJSON = os.path.join(BASE, 'tokenizer.json')
OUT = os.path.join(BASE, 'tflite')
CALIB = os.path.join(OUT, 'calib')
SAVED = os.path.join(OUT, 'saved_model')
BATCH, SEQLEN = 1, 64

TIMES = ['今天', '昨天', '前天', '上午', '下午', '晚上', '中午', '凌晨', '深夜',
         '上周', '本周', '这个月', '上个月', '最近三天', '昨天下午', '今天早上']
TYPES = ['图片', '照片', '截图', '视频', '电影', '音乐', '录音', '文档', '表格',
         'PDF', '安装包', '压缩包', '代码', '文本', '电子书']
SOURCES = ['百度', '微信', 'QQ浏览器', '迅雷', '钉钉', '夸克', 'UC浏览器', '百度网盘', '蓝牙']
FILL = ['我', '的', '在', '了', '找', '一下', '那个', '帮我', '下载', '刚才', '存']

FREE = ['会议纪要', '项目报告', '身份证', '发票', '合同', '简历', '截图', '壁纸',
        '头像', '说明书', 'readme', 'config', 'data', 'IMG_2026', 'VID_2026']


def corpus():
    out = []
    for t in TIMES:
        for ty in TYPES:
            out.append('%s%s' % (t, ty))
            out.append('我%s在%s下载的%s' % (t, SOURCES[len(out) % len(SOURCES)], ty))
    for s in SOURCES:
        for ty in TYPES:
            out.append('%s发我的%s' % (s, ty))
            out.append('%s下载的%s' % (s, ty))
    for f in FREE:
        for t in TIMES:
            out.append('%s的%s' % (t, f))
        out.append('找一下%s' % f)
    for a, b in itertools.product(FILL, TYPES):
        out.append(a + b)
    out += [
        '我上午在百度下载的图片', '刚过中午那会儿存的照片', '上周微信发我的表格',
        '迅雷下的那个压缩包', '深夜里录的音', '昨天从百度网盘拿的视频',
        '帮我找找上个月的项目报告', '今天早上截的图在哪', '那个蓝色的设计图',
    ]
    return out


os.makedirs(CALIB, exist_ok=True)
tk = BertTokenizer(TOKJSON)
texts = corpus()
print('calibration samples: %d' % len(texts))

ids, mask, types = tk.encode_padded(texts, SEQLEN)
p_ids = os.path.join(CALIB, 'input_ids.npy')
p_mask = os.path.join(CALIB, 'attention_mask.npy')
p_types = os.path.join(CALIB, 'token_type_ids.npy')
np.save(p_ids, ids)
np.save(p_mask, mask)
np.save(p_types, types)
print('shapes: ids=%s mask=%s types=%s' % (ids.shape, mask.shape, types.shape))
print('max tokens used: %d (seq_len=%d)' % (int(mask.sum(1).max()), SEQLEN))

# ---- convert ---------------------------------------------------------------
if os.path.isdir(SAVED):
    import shutil
    shutil.rmtree(SAVED, ignore_errors=True)

cmd = [sys.executable, '-m', 'onnx2tf', '-i', SRC, '-o', SAVED,
       '-osd',
       '-b', str(BATCH),
       '-ois',
       'input_ids:%d,%d' % (BATCH, SEQLEN),
       'attention_mask:%d,%d' % (BATCH, SEQLEN),
       'token_type_ids:%d,%d' % (BATCH, SEQLEN),
       '-oiqt', '-qt', 'per-channel',
       # -cind takes exactly (NAME, PATH, MEAN, STD) per occurrence, so repeat it
       '-cind', 'input_ids', p_ids, '0.0', '1.0',
       '-cind', 'attention_mask', p_mask, '0.0', '1.0',
       '-cind', 'token_type_ids', p_types, '0.0', '1.0']

print('running onnx2tf with calibration data ...')
r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace')
tail = (r.stdout or '').splitlines()[-25:]
print('\n'.join(tail))
if r.returncode != 0:
    print('--- stderr ---')
    print('\n'.join((r.stderr or '').splitlines()[-25:]))

print()
print('=== produced files ===')
for root, dirs, files in os.walk(OUT):
    for f in files:
        p = os.path.join(root, f)
        print('  %-50s %8.2f MB' % (f, os.path.getsize(p) / 1e6))
