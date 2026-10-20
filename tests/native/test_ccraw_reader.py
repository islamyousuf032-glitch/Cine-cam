import importlib.util
import struct
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("ccraw", ROOT / "scripts/inspect_ccraw.py")
ccraw = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ccraw)


class CcrawReaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = (ROOT / "build/native-tests/ccraw/capture.ccraw").read_bytes()

    def verify(self, data):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "test.ccraw"
            path.write_bytes(data)
            return ccraw.inspect(path)

    def test_independent_reader_checks_cpp_writer(self):
        result = self.verify(self.data)
        self.assertEqual(result["frameCount"], 2)
        self.assertEqual(result["payloadBytes"], 32)
        self.assertEqual(result["frames"][0]["timestampNs"], 200)
        self.assertEqual(result["frames"][1]["ptsNs"], 200)
        self.assertEqual(result["frames"][0]["iso"], 200)
        # Native row packing strips padding bytes 8..11, without changing samples.
        expected = bytes(range(8)) + bytes(range(12, 20))
        self.assertEqual(self.data[160:176], expected)
        self.assertEqual(self.data[272:288], expected)

    def test_corruption_is_rejected(self):
        for index in (0, 12, 60, 72, 108, 136, 140, 160, 176, 304):
            with self.subTest(index=index):
                data = bytearray(self.data)
                data[index] ^= 1
                with self.assertRaises(ccraw.InvalidClip):
                    self.verify(data)

    def test_truncation_and_trailing_data_rejected(self):
        for length in (0, 8, 63, 100, 175, 287, 288, 319):
            with self.subTest(length=length), self.assertRaises(ccraw.InvalidClip):
                self.verify(self.data[:length])
        with self.assertRaises(ccraw.InvalidClip):
            self.verify(self.data + b"unexpected")

    def test_unfinished_native_output_rejected(self):
        path = ROOT / "build/native-tests/ccraw/invalid/capture.ccraw.partial"
        with self.assertRaises(ccraw.InvalidClip):
            ccraw.inspect(path)


if __name__ == "__main__":
    unittest.main()
