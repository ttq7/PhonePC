"""Dump the bge-small-zh token embedding table as a raw uint8 blob for Kotlin.

Layout: row = token id, 512 bytes per row, 21128 rows.
Values are uint8 with zero-point 128; the Kotlin side subtracts 128 and mean-pools.
The per-tensor scale cancels out under cosine similarity, so it is not stored.
"""

import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import os

import numpy as np
import onnx

BASE = os.path.join(ROOT, 'models', 'bge-small-zh')
OUT_DIR = os.path.join(ROOT, 'PhonePC', 'app', 'src', 'main', 'assets')
OUT = os.path.join(OUT_DIR, 'emb_u8.bin')

g = onnx.load(os.path.join(BASE, 'model_quantized.onnx')).graph
init = {i.name: i for i in g.initializer}
raw = onnx.numpy_helper.to_array(init['embeddings.word_embeddings.weight_quantized'])

assert raw.dtype == np.uint8, raw.dtype
rows, dim = raw.shape
print('table %d x %d  (%d bytes total)' % (rows, dim, raw.size))

os.makedirs(OUT_DIR, exist_ok=True)
with open(OUT, 'wb') as f:
    f.write(raw.tobytes())

print('written: %s  %.2f MB' % (OUT, os.path.getsize(OUT) / 1e6))
print('zero-point handled at read time (subtract 128)')
