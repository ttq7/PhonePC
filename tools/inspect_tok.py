import os
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

import json

d = json.load(open(os.path.join(ROOT, 'models', 'bge-small-zh', 'tokenizer.json'), encoding='utf-8'))
print('top-level keys:', list(d.keys()))
print()
print('normalizer   :', json.dumps(d.get('normalizer'), ensure_ascii=False)[:400])
print()
print('pre_tokenizer:', json.dumps(d.get('pre_tokenizer'), ensure_ascii=False)[:500])
print()
print('post_processor:', json.dumps(d.get('post_processor'), ensure_ascii=False)[:400])
print()
m = d['model']
print('model.type   :', m.get('type'))
print('unk / prefix :', m.get('unk_token'), '/', m.get('continuing_subword_prefix'))
print('vocab size   :', len(m['vocab']))
print()
for t in ['[CLS]', '[SEP]', '[PAD]', '[UNK]', '的', '图', '片', '上', '午', '##片']:
    print('   %-8s -> %s' % (t, m['vocab'].get(t)))
print()
print('added_tokens:', [(a.get('content'), a.get('id')) for a in d.get('added_tokens', [])][:8])
