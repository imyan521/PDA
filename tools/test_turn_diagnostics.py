import io
import struct
import tempfile
import unittest
from pathlib import Path
import zipfile
from analyze_turn_diagnostics import analyze, analyze_stream


def record(kind, timestamp, payload):
    body = struct.pack('>Bq', kind, timestamp) + payload
    return struct.pack('>I', len(body)) + body


class DiagnosticReaderTest(unittest.TestCase):
    def test_complete_imu_stream_reports_sampling_and_stationary_bias(self):
        stream = b'CSLAM001'
        for index in range(101):
            stream += record(2, 1_000_000_000 + index * 20_000_000,
                             struct.pack('>6f', 0, 0, 9.81, 0, 0, .01))
        stream += record(127, 0, struct.pack('>q?', 0, False))
        result = analyze_stream(io.BytesIO(stream), stationary_seconds=1)
        self.assertTrue(result['complete_and_lossless'])
        self.assertEqual(20, result['imu_intervals']['median_ms'])
        self.assertAlmostEqual(.01, result['explicitly_assumed_stationary_prefix']['gyro_mean_xyz_rad_s'][2])

    def test_missing_footer_is_not_reported_as_complete(self):
        self.assertFalse(analyze_stream(io.BytesIO(b'CSLAM001'))['complete_and_lossless'])

    def test_dropped_records_are_visible(self):
        stream = b'CSLAM001' + record(127, 0, struct.pack('>q?', 3, False))
        self.assertFalse(analyze_stream(io.BytesIO(stream))['complete_and_lossless'])

    def test_truncated_record_is_rejected(self):
        with self.assertRaises(ValueError):
            analyze_stream(io.BytesIO(b'CSLAM001' + struct.pack('>I', 33) + b'123'))

    def test_invalid_scan_size_is_rejected(self):
        stream = b'CSLAM001' + record(3, 100, struct.pack('>fI', .1, 5))
        with self.assertRaises(ValueError):
            analyze_stream(io.BytesIO(stream))

    def test_exported_zip_metadata_and_stream_are_read_together(self):
        stream = b'CSLAM001' + record(127, 0, struct.pack('>q?', 0, False))
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'turn.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('sensors.bin', stream)
                archive.writestr('session.json', '{"imu_source":"BOARD"}')
            result = analyze(path)
            self.assertTrue(result['complete_and_lossless'])
            self.assertEqual('BOARD', result['session.json']['imu_source'])

    def test_data_after_footer_is_rejected(self):
        stream = b'CSLAM001' + record(127, 0, struct.pack('>q?', 0, False)) + record(1, 100, b'123')
        with self.assertRaises(ValueError):
            analyze_stream(io.BytesIO(stream))


if __name__ == '__main__':
    unittest.main()
