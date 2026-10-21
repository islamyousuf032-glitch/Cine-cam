import importlib.util
from pathlib import Path
import tempfile
import struct
import zlib
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('restore_bp16', ROOT / 'scripts/restore_bp16.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class ArchiveTests(unittest.TestCase):
    def test_native_python_roundtrip(self):
        for archive, original in [('roundtrip.ccraw.bp16', 'ccraw/capture.ccraw'), ('multi.bp16', 'codec-probe.bin'), ('all-bits.bp16', 'all-bits.bin')]:
            with tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'restored.ccraw'
                module.restore(ROOT / 'build/native-tests' / archive, output)
                self.assertEqual(output.read_bytes(), (ROOT / 'build/native-tests' / original).read_bytes())

    def test_archive_bytes_match_independent_reference_encoder(self):
        # A different, bit-position based implementation verifies unchanged wire bytes.
        for archive, original in [('roundtrip.ccraw.bp16', 'ccraw/capture.ccraw'), ('all-bits.bp16', 'all-bits.bin')]:
            raw = (ROOT / 'build/native-tests' / original).read_bytes()
            header = struct.pack('<8sQI', b'CCBP0001', len(raw), 65536)
            expected = bytearray(header + struct.pack('<I', zlib.crc32(header)))
            for offset in range(0, len(raw), 65536):
                block = raw[offset:offset + 65536]
                samples = struct.unpack('<' + 'H' * (len(block) // 2), block)
                bits = max(samples).bit_length()
                payload = bytearray((len(samples) * bits + 7) // 8)
                for i, value in enumerate(samples):
                    for bit in range(bits):
                        if value & (1 << bit):
                            position = i * bits + bit
                            payload[position // 8] |= 1 << (position % 8)
                packed = struct.pack('<4sBBH', b'BP16', bits, 0, len(samples)) + payload
                expected += struct.pack('<II', len(packed), zlib.crc32(block)) + packed
            self.assertEqual(bytes(expected), (ROOT / 'build/native-tests' / archive).read_bytes())

    def test_corruption_truncation_trailing_and_no_overwrite(self):
        data = (ROOT / 'build/native-tests/roundtrip.ccraw.bp16').read_bytes()
        bad_header = bytearray(data); bad_header[0] ^= 1
        bad_payload = bytearray(data); bad_payload[-1] ^= 1
        with tempfile.TemporaryDirectory() as directory:
            source, output = Path(directory) / 'source', Path(directory) / 'out'
            for content in [bad_header, bad_payload, data[:23], data[:-1], data + b'x']:
                source.write_bytes(content)
                with self.assertRaises((ValueError, OSError)):
                    module.restore(source, output)
                self.assertFalse(output.exists())
            source.write_bytes(data); output.write_bytes(b'keep')
            with self.assertRaises(FileExistsError):
                module.restore(source, output)
            self.assertEqual(output.read_bytes(), b'keep')

if __name__ == '__main__':
    unittest.main()
