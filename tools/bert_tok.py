"""Hand-written BERT tokenizer for bge-small-zh.

The `tokenizers` wheel will not build on this Python, and the Kotlin side needs
the same logic anyway, so it lives here as plain code. This is the reference
implementation that the Kotlin port must match.

The rules come from the model's own tokenizer.json rather than from memory:
    normalizer    BertNormalizer(clean_text, handle_chinese_chars, lowercase=false)
    pre_tokenizer BertPreTokenizer
    model         WordPiece, unk=[UNK], continuing prefix="##", vocab 21128
    post          [CLS] + A + [SEP]
"""
import json
import unicodedata


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
        out.append(' ' if is_whitespace(ch) else ch)
    text = ''.join(out)
    buf = []
    for ch in text:
        if is_cjk(ch):
            buf.append(' ' + ch + ' ')
        else:
            buf.append(ch)
    text = ''.join(buf)
    return text.lower() if lowercase else text


def pre_tokenize(text):
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


class BertTokenizer:
    def __init__(self, tokenizer_json):
        d = json.load(open(tokenizer_json, encoding='utf-8'))
        m = d['model']
        self.vocab = m['vocab']
        self.unk = m.get('unk_token', '[UNK]')
        self.prefix = m.get('continuing_subword_prefix', '##')
        self.lowercase = bool(d.get('normalizer', {}).get('lowercase', False))
        self.cls_id = self.vocab['[CLS]']
        self.sep_id = self.vocab['[SEP]']
        self.pad_id = self.vocab.get('[PAD]', 0)
        self.mask_id = self.vocab.get('[MASK]', 103)

    def _wordpiece(self, token):
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
        """[CLS] ... [SEP], without padding."""
        ids = [self.cls_id]
        for t in pre_tokenize(normalize(text, self.lowercase)):
            for p in self._wordpiece(t):
                ids.append(self.vocab[p])
        ids.append(self.sep_id)
        return ids

    def encode_padded(self, texts, seq_len):
        """(ids, attention_mask, token_type_ids) as int64 arrays of shape [N, seq_len]."""
        import numpy as np
        n = len(texts)
        ids = np.zeros((n, seq_len), dtype=np.int64)
        mask = np.zeros((n, seq_len), dtype=np.int64)
        types = np.zeros((n, seq_len), dtype=np.int64)
        for i, t in enumerate(texts):
            e = self.encode(t)
            if len(e) > seq_len:
                e = e[:seq_len - 1] + [self.sep_id]
            ids[i, :len(e)] = e
            mask[i, :len(e)] = 1
        return ids, mask, types
