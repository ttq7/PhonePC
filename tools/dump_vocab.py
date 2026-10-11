"""Dump the BERT vocab in id order so Kotlin can load it without a JSON parser."""

import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
import json
import os

BASE = os.path.join(ROOT, 'models', 'bge-small-zh')
OUT = os.path.join(ROOT, 'PhonePC', 'app', 'src', 'main', 'assets', 'vocab.txt')

d = json.load(open(os.path.join(BASE, 'tokenizer.json'), encoding='utf-8'))
vocab = d['model']['vocab']

rows = max(vocab.values()) + 1
tokens = ['[UNK]'] * rows
for tok, idx in vocab.items():
    tokens[idx] = tok
# tokens must not contain newlines; replace any just in case
tokens = [t.replace('\n', ' ').replace('\r', ' ') for t in tokens]

with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
    f.write('\n'.join(tokens))

print('vocab rows: %d' % rows)
print('written: %s  %.1f KB' % (OUT, os.path.getsize(OUT) / 1024))
print('[CLS]=%d [SEP]=%d [UNK]=%d [PAD]=%d' %
      (vocab['[CLS]'], vocab['[SEP]'], vocab['[UNK]'], vocab['[PAD]']))
