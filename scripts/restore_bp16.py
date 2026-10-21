#!/usr/bin/env python3
"""Restore experimental CCBP0001 lossless archives. Never overwrite an existing output.
After restoring .ccraw, run inspect_ccraw.py to validate its container/frame checksums.
"""
import argparse
from pathlib import Path
import struct
import zlib

LIMIT = 257 * 1024 * 1024

def restore(source: Path, output: Path):
    created = False
    try:
        with source.open('rb') as inp:
            def read(n):
                data = inp.read(n)
                if len(data) != n:
                    raise ValueError('Truncated archive')
                return data
            header = read(24)
            magic, size, block, crc = struct.unpack('<8sQII', header)
            if magic != b'CCBP0001' or block != 65536 or zlib.crc32(header[:20]) != crc or not 0 < size <= LIMIT or size % 2:
                raise ValueError('Invalid archive header')
            with output.open('xb') as out:
                created = True
                remaining = size
                while remaining:
                    length, crc = struct.unpack('<II', read(8))
                    if not 8 <= length <= 65544:
                        raise ValueError('Invalid encoded block length')
                    data = read(length)
                    magic, bits, flags, samples = struct.unpack('<4sBBH', data[:8])
                    expected = min(remaining, block)
                    if magic != b'BP16' or bits > 16 or flags or samples * 2 != expected or length != 8 + (samples * bits + 7) // 8:
                        raise ValueError('Invalid block layout')
                    used = samples * bits % 8
                    if used and data[-1] >> used:
                        raise ValueError('Nonzero unused bits')
                    raw = bytearray(expected)
                    accumulator = pending = 0
                    at = 8
                    mask = (1 << bits) - 1
                    for i in range(samples):
                        while pending < bits:
                            accumulator |= data[at] << pending
                            pending += 8
                            at += 1
                        value = accumulator & mask
                        raw[2*i] = value & 255
                        raw[2*i+1] = value >> 8
                        accumulator >>= bits
                        pending -= bits
                    if zlib.crc32(raw) != crc:
                        raise ValueError('Decoded block CRC mismatch')
                    out.write(raw)
                    remaining -= expected
                if inp.read(1):
                    raise ValueError('Trailing archive bytes')
        return size
    except BaseException:
        if created:
            output.unlink(missing_ok=True)
        raise

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        print(f'Restored {restore(args.archive, args.output)} exact original bytes')
    except (ValueError, OSError) as error:
        parser.exit(1, f'Archive restore failed: {error}\n')
