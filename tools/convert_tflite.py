"""Convert the bge-small-zh ONNX model to a TFLite flatbuffer.

ONNX Runtime's Android library costs ~30 MB of native code; TFLite costs ~5.4 MB.
The conversion runs once at build time, the size saving is permanent.

Run:  python tools\convert_tflite.py
"""
import os
import subprocess
import sys

BASE = r'E:\此电脑\models\bge-small-zh'
SRC = os.path.join(BASE, 'model_quantized.onnx')
OUT = os.path.join(BASE, 'tflite')
SAVED = os.path.join(OUT, 'saved_model')
TFLITE = os.path.join(OUT, 'model.tflite')

os.makedirs(OUT, exist_ok=True)

print('onnx : %.2f MB' % (os.path.getsize(SRC) / 1e6))

# ---- 1. ONNX -> TensorFlow SavedModel -------------------------------------
# Input shapes MUST be static. TFLite's tf.Reshape cannot handle more than one
# dynamic dimension, and this graph has two (batch and sequence).
# Fixed 1x64 is fine here: queries and anchor phrases are short and we pad.
BATCH, SEQLEN = 1, 64

if os.path.isdir(SAVED):
    import shutil
    shutil.rmtree(SAVED, ignore_errors=True)

print('running onnx2tf (static %dx%d, integer quantized) ...' % (BATCH, SEQLEN))
r = subprocess.run(
    [sys.executable, '-m', 'onnx2tf', '-i', SRC, '-o', SAVED,
     '-b', str(BATCH),
     '-ois',
     'input_ids:%d,%d' % (BATCH, SEQLEN),
     'attention_mask:%d,%d' % (BATCH, SEQLEN),
     'token_type_ids:%d,%d' % (BATCH, SEQLEN),
     '-oiqt', '-qt', 'per-channel'],
    capture_output=True, text=True, encoding='utf-8', errors='replace'
)
if r.returncode != 0 or not os.path.isdir(SAVED):
    print('--- onnx2tf stdout tail ---')
    print('\n'.join((r.stdout or '').splitlines()[-30:]))
    print('--- stderr tail ---')
    print('\n'.join((r.stderr or '').splitlines()[-30:]))
    sys.exit(1)
print('onnx2tf ok')

# onnx2tf writes into a model-named subdirectory, so locate the SavedModel
# rather than assuming a path.
if not os.path.isfile(os.path.join(SAVED, 'saved_model.pb')):
    found = None
    for root, dirs, files in os.walk(OUT):
        if 'saved_model.pb' in files:
            found = root
            break
    if not found:
        print('SavedModel not found anywhere under', OUT)
        for root, dirs, files in os.walk(OUT):
            print('  ', root, '->', files[:6])
        sys.exit(1)
    print('saved_model found at:', found)
    SAVED = found

# ---- 2. SavedModel -> TFLite with dynamic-range quantization ---------------
import tensorflow as tf

conv = tf.lite.TFLiteConverter.from_saved_model(SAVED)
conv.optimizations = [tf.lite.Optimize.DEFAULT]
buf = conv.convert()
with open(TFLITE, 'wb') as f:
    f.write(buf)

print('')
print('tflite: %.2f MB   ->  %s' % (os.path.getsize(TFLITE) / 1e6, TFLITE))

# ---- 3. report the interface so the Kotlin side knows what to feed ---------
interp = tf.lite.Interpreter(model_path=TFLITE)
interp.allocate_tensors()
print('')
print('inputs:')
for d in interp.get_input_details():
    print('   %-16s shape=%s dtype=%s' % (d['name'], list(d['shape']), d['dtype'].__name__))
print('outputs:')
for d in interp.get_output_details():
    print('   %-16s shape=%s dtype=%s' % (d['name'], list(d['shape']), d['dtype'].__name__))
