import importlib.util
from pathlib import Path
import tempfile
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
