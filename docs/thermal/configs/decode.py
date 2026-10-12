#!/usr/bin/env python3
"""
vivo PD2430A /vendor/etc/thermal/*.conf 解码器

混淆方式（逐行、逐字符的模 91 加法移位）:
    每行开头复位计数器 i = 0
    对行内第 i 个字符（0 起）:
        cipher[i] = 32 + ((plain[i] - 32 + (i % 10)) % 91)
    '\n' 原样透传，不计入 i
解码即逆运算:
        plain[i]  = 32 + ((cipher[i] - 32 - (i % 10)) % 91)
"""
import sys, pathlib

M, BASE = 91, 32
KEYLEN = 10

def decode_bytes(data: bytes) -> bytes:
    out = bytearray()
    i = 0
    for c in data:
        if c == 0x0A:                 # 换行原样
            out.append(0x0A)
            i = 0
            continue
        out.append((c - BASE - (i % KEYLEN)) % M + BASE)
        i += 1
    return bytes(out)

def encode_bytes(data: bytes) -> bytes:
    out = bytearray()
    i = 0
    for c in data:
        if c == 0x0A:
            out.append(0x0A); i = 0; continue
        out.append((c - BASE + (i % KEYLEN)) % M + BASE)
        i += 1
    return bytes(out)

if __name__ == '__main__':
    args = sys.argv[1:]
    if args and args[0] in ('-e', '--encode'):
        args = args[1:]
        fn = encode_bytes
    else:
        fn = decode_bytes
    for p in args:
        src = pathlib.Path(p)
        raw = src.read_bytes()
        print(fn(raw).decode('latin1'), end='')
