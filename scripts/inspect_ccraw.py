#!/usr/bin/env python3
"""Independent CCRAW001 verifier. Standard library only; never loads a whole RAW clip."""
import argparse
import json
import struct
import zlib
from pathlib import Path


class InvalidClip(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidClip(message)


def read_exact(stream, size):
    data = stream.read(size)
    require(len(data) == size, "Truncated header/payload/footer")
    return data


def inspect(path):
    with Path(path).open("rb") as source:
        header = read_exact(source, 64)
        require(header[:8] == b"CCRAW001", "Unknown magic/version")
        header_size, width, height, bits, fps, clock = struct.unpack_from("<6I", header, 8)
        epoch = struct.unpack_from("<Q", header, 32)[0]
        require(header_size == 64 and bits == 16 and fps == 0 and clock == 1, "Unsupported file configuration")
        require(0 < width <= 16384 and 0 < height <= 16384 and width * height * 2 <= 256 * 1024**2, "Invalid dimensions")
        require(epoch < 2**63 and header[40:60] == bytes(20), "Invalid epoch/reserved fields")
        require(zlib.crc32(header[:60]) == struct.unpack_from("<I", header, 60)[0], "File header CRC mismatch")
        frames = []
        payload_total = 0
        previous = epoch - 1
        while True:
            magic = read_exact(source, 4)
            if magic == b"END1":
                footer = magic + read_exact(source, 28)
                size, count, payload, crc = struct.unpack_from("<IQQI", footer, 4)
                require(size == 32 and footer[28:] == bytes(4), "Invalid footer")
                require(crc == zlib.crc32(footer[:24]), "Footer CRC mismatch")
                require(count == len(frames) and payload == payload_total, "Footer count/size mismatch")
                require(source.read(1) == b"", "Trailing bytes after footer")
                break
            require(magic == b"FRM1" and len(frames) < 8, "Invalid frame magic/count")
            frame = magic + read_exact(source, 92)
            size = struct.unpack_from("<I", frame, 4)[0]
            timestamp, exposure, payload = struct.unpack_from("<QQQ", frame, 8)
            fw, fh, stride, iso, number, storage_bits = struct.unpack_from("<6I", frame, 32)
            focus, nr, ng, nb = struct.unpack_from("<4f", frame, 56)
            payload_crc, header_crc = struct.unpack_from("<2I", frame, 72)
            require(size == 96 and frame[80:] == bytes(16), "Unsupported frame header")
            require(header_crc == zlib.crc32(frame[:76]), "Frame header CRC mismatch")
            require((fw, fh, stride, storage_bits) == (width, height, width * 2, 16), "Frame geometry mismatch")
            require(payload == width * height * 2, "Frame payload size mismatch")
            require(epoch <= timestamp < 2**63 and timestamp > previous and exposure < 2**63, "Invalid/non-monotonic timestamp")
            payload_total += payload
            require(payload_total <= 256 * 1024**2, "Payload byte limit exceeded")
            remaining, crc = payload, 0
            while remaining:
                chunk = read_exact(source, min(remaining, 1024 * 1024))
                crc = zlib.crc32(chunk, crc)
                remaining -= len(chunk)
            require(crc == payload_crc, "RAW payload CRC mismatch")
            frames.append(dict(timestampNs=timestamp, ptsNs=timestamp - epoch, exposureNs=exposure,
                               iso=iso, sourceFrameNumber=number, focusDiopters=focus,
                               neutralWhitePoint=[nr, ng, nb], payloadBytes=payload))
            previous = timestamp
    return dict(format="CCRAW001", complete=True, width=width, height=height,
                storageBits=bits, clock="BOOTTIME", epochNs=epoch, frameCount=len(frames),
                payloadBytes=payload_total, frames=frames,
                warning="Experimental raw burst, not playable MP4/MotionCam MCRAW; sensor precision is in metadata.json")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("clip", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(inspect(args.clip), indent=2, allow_nan=False))
    except (InvalidClip, OSError, ValueError) as error:
        parser.exit(1, f"Invalid CCRAW: {error}\n")
