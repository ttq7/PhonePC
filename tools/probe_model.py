"""bge-small-zh ONNX probe.

Implements the BERT tokenizer by hand (the `tokenizers` wheel will not install on
this Python, and the Kotlin side needs the same logic anyway), then checks that
the embeddings actually rank sensible anchors for natural-language file queries.

Run:  python tools\probe_model.py
"""

import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import json
import time
import unicodedata

import numpy as np
import onnxruntime as ort

MODEL = os.path.join(ROOT, 'models', 'bge-small-zh', 'model_quantized.onnx')
TOKJSON = os.path.join(ROOT, 'models', 'bge-small-zh', 'tokenizer.json')


# ---------------------------------------------------------------- normalizer

def is_whitespace(c):
    return c in ' \t\n\r' or unicodedata.category(c) == 'Zs'


def is_control(c):
    if c in '\t\n\r':
        return False
    return unicodedata.category(c) in ('Cc', 'Cf')


def is_punct(c):
    cp = ord(c)
    if (33 <= cp <= 47) or (58 <= cp <= 64) or (91 <= cp <= 96) or (123 <= cp <= 126):
        return True
    return unicodedata.category(c).startswith('P')


def is_cjk(c):
    cp = ord(c)
    return ((0x4E00 <= cp <= 0x9FFF) or (0x3400 <= cp <= 0x4DBF) or
            (0x20000 <= cp <= 0x2A6DF) or (0x2A700 <= cp <= 0x2B73F) or
            (0x2B740 <= cp <= 0x2B81F) or (0x2B820 <= cp <= 0x2CEAF) or
            (0xF900 <= cp <= 0xFAFF) or (0x2F800 <= cp <= 0x2FA1F))


def normalize(text, lowercase=False):
    out = []
    for ch in text:
        cp = ord(ch)
        if cp == 0 or cp == 0xFFFD or is_control(ch):
            continue
        if is_whitespace(ch):
            out.append(' ')
        else:
            out.append(ch)
    text = ''.join(out)
    # handle_chinese_chars: put spaces around every CJK char
    buf = []
    for ch in text:
        if is_cjk(ch):
            buf.append(' ')
            buf.append(ch)
            buf.append(' ')
        else:
            buf.append(ch)
    text = ''.join(buf)
    if lowercase:
        text = text.lower()
    return text


def pre_tokenize(text):
    """split on whitespace, then peel punctuation off as its own token"""
    tokens = []
    for chunk in text.split():
        cur = ''
        for ch in chunk:
            if is_punct(ch):
                if cur:
                    tokens.append(cur)
                    cur = ''
                tokens.append(ch)
            else:
                cur += ch
        if cur:
            tokens.append(cur)
    return tokens


# ---------------------------------------------------------------- wordpiece

class WordPiece:
    def __init__(self, tok_json):
        d = json.load(open(tok_json, encoding='utf-8'))
        m = d['model']
        self.vocab = m['vocab']
        self.unk = m.get('unk_token', '[UNK]')
        self.prefix = m.get('continuing_subword_prefix', '##')
        self.lowercase = bool(d.get('normalizer', {}).get('lowercase', False))
        self.cls = self.vocab['[CLS]']
        self.sep = self.vocab['[SEP]']

    def _piece(self, token):
        if len(token) > 100:
            return [self.unk]
        out = []
        start = 0
        while start < len(token):
            end = len(token)
            cur = None
            while start < end:
                sub = token[start:end]
                if start > 0:
                    sub = self.prefix + sub
                if sub in self.vocab:
                    cur = sub
                    break
                end -= 1
            if cur is None:
                return [self.unk]
            out.append(cur)
            start = end
        return out

    def encode(self, text):
        ids = [self.cls]
        for t in pre_tokenize(normalize(text, self.lowercase)):
            for p in self._piece(t):
                ids.append(self.vocab[p])
        ids.append(self.sep)
        return ids


# ---------------------------------------------------------------- embedding

tok = WordPiece(TOKJSON)
sess = ort.InferenceSession(MODEL, providers=['CPUExecutionProvider'])
in_names = [i.name for i in sess.get_inputs()]

print('inputs :', [(i.name, i.shape) for i in sess.get_inputs()])
print('outputs:', [(o.name, o.shape) for o in sess.get_outputs()])

probe = tok.encode('上午')
print('tokenize "上午" ->', probe)
print('tokenize "我上午在百度下载的图片" ->', tok.encode('我上午在百度下载的图片'))
print()


def embed(texts):
    enc = [tok.encode(t) for t in texts]
    n = len(enc)
    L = max(len(e) for e in enc)
    ids = np.zeros((n, L), dtype=np.int64)
    mask = np.zeros((n, L), dtype=np.int64)
    types = np.zeros((n, L), dtype=np.int64)
    for i, e in enumerate(enc):
        ids[i, :len(e)] = e
        mask[i, :len(e)] = 1
    feeds = {}
    for nm in in_names:
        if nm == 'input_ids':
            feeds[nm] = ids
        elif nm == 'attention_mask':
            feeds[nm] = mask
        elif nm == 'token_type_ids':
            feeds[nm] = types
    out = sess.run(None, feeds)[0]
    v = out[:, 0, :]                       # BGE pools the [CLS] token
    return v / np.clip(np.linalg.norm(v, axis=1, keepdims=True), 1e-9, None)


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

A = embed(anchors)

print('=== top-5 anchors per query ===')
t0 = time.time()
for q in queries:
    v = embed([q])[0]
    sims = A @ v
    order = np.argsort(-sims)[:5]
    print('  %s' % q)
    for i in order:
        print('      %-8s %.3f' % (anchors[i], sims[i]))
    print()

dt = (time.time() - t0) / len(queries)
print('mean latency per query : %.0f ms' % (dt * 1000))
print('embedding dim          : %d' % A.shape[1])
