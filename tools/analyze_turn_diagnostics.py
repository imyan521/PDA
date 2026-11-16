#!/usr/bin/env python3
"""Inspect exported CSLAM001 ZIPs without Android or third-party Python packages."""
import argparse
from collections import Counter
import json
import math
from pathlib import Path
import statistics
import struct
import zipfile


def records(stream):
    if stream.read(8) != b'CSLAM001':
        raise ValueError('Unsupported diagnostic format')
    ended = False
    while raw_size := stream.read(4):
        if ended or len(raw_size) != 4:
            raise ValueError('Trailing or truncated record')
        size = struct.unpack('>I', raw_size)[0]
        if not 9 <= size <= 1024 * 1024:
            raise ValueError('Invalid record size')
        body = stream.read(size)
        if len(body) != size:
            raise ValueError('Truncated record body')
        kind, timestamp = struct.unpack_from('>Bq', body)
        ended = kind == 127
        yield kind, timestamp, body[9:]


def intervals(timestamps):
    values = [(b - a) / 1e6 for a, b in zip(timestamps, timestamps[1:])]
    if not values:
        return None
    ordered = sorted(values)
    return {'min_ms': ordered[0], 'median_ms': statistics.median(ordered),
            'p95_ms': ordered[min(len(ordered) - 1, int(len(ordered) * .95))],
            'max_ms': ordered[-1], 'non_increasing': sum(x <= 0 for x in values)}


def analyze_stream(stream, stationary_seconds=None):
    counts = Counter()
    times = {2: [], 3: [], 4: []}
    imus = []
    durations = []
    scan_counts = []
    overlaps = 0
    previous_scan = None
    footer = None
    usb_bytes = 0
    for kind, timestamp, payload in records(stream):
        if kind == 127:
            if len(payload) != 9:
                raise ValueError('Invalid footer')
            dropped, capped = struct.unpack('>q?', payload)
            footer = {'dropped_records': dropped, 'size_capped': capped}
            continue
        counts[kind] += 1
        if kind in times:
            times[kind].append(timestamp)
        if kind == 1:
            usb_bytes += len(payload)
        elif kind == 2:
            if len(payload) != 24:
                raise ValueError('Invalid IMU record')
            imus.append((timestamp, struct.unpack('>6f', payload)))
        elif kind == 3:
            if len(payload) < 8:
                raise ValueError('Invalid scan header')
            duration, count = struct.unpack_from('>fI', payload)
            if len(payload) != 8 + count * 8:
                raise ValueError('Invalid scan record')
            if previous_scan is not None and timestamp - duration * 1e9 < previous_scan:
                overlaps += 1
            previous_scan = timestamp
            durations.append(duration * 1000)
            scan_counts.append(count)
        elif kind == 4:
            if len(payload) != 56:
                raise ValueError('Invalid pose record')
        else:
            raise ValueError(f'Unknown record type {kind}')
    result = {
        'record_counts': dict(counts), 'raw_usb_bytes': usb_bytes,
        'footer': footer,
        'complete_and_lossless': footer is not None and footer['dropped_records'] == 0 and not footer['size_capped'],
        'imu_intervals': intervals(times[2]), 'scan_intervals': intervals(times[3]),
        'pose_fetch_intervals': intervals(times[4]),
        'scan_duration_median_ms': statistics.median(durations) if durations else None,
        'points_per_scan_median': statistics.median(scan_counts) if scan_counts else None,
        'overlapping_scan_intervals': overlaps,
        'limitations': ['board timestamps are receive-time estimates',
                        'pose timestamps denote retrieval, not pose measurement time',
                        'queue delay cannot measure fixed UART/MCU transport delay']
    }
    finite = [item for item in imus if all(math.isfinite(x) for x in item[1])]
    result['nonfinite_imu_records'] = len(imus) - len(finite)
    if finite:
        integrals = [0.0, 0.0, 0.0]
        for (ta, a), (tb, _) in zip(finite, finite[1:]):
            if tb > ta:
                for axis in range(3):
                    integrals[axis] += a[3 + axis] * (tb - ta) / 1e9
        result['gyro_integral_xyz_degrees'] = [math.degrees(x) for x in integrals]
        if stationary_seconds is not None:
            cutoff = finite[0][0] + stationary_seconds * 1e9
            static = [values for t, values in finite if t <= cutoff]
            means = [statistics.mean(v[i] for v in static) for i in range(6)]
            result['explicitly_assumed_stationary_prefix'] = {
                'seconds': stationary_seconds, 'samples': len(static),
                'acceleration_mean_xyz': means[:3],
                'acceleration_mean_norm': math.sqrt(sum(x*x for x in means[:3])),
                'gyro_mean_xyz_rad_s': means[3:],
                'gyro_mean_xyz_deg_s': [math.degrees(x) for x in means[3:]],
                'gyro_std_xyz_rad_s': [statistics.pstdev(v[i] for v in static) for i in range(3, 6)]
            }
    return result


def analyze(path, stationary_seconds=None):
    path = Path(path)
    if path.suffix.lower() == '.zip':
        with zipfile.ZipFile(path) as archive:
            info = archive.getinfo('sensors.bin')
            if info.file_size > 70 * 1024 * 1024:
                raise ValueError('Diagnostic stream exceeds expected export limit')
            with archive.open(info) as stream:
                result = analyze_stream(stream, stationary_seconds)
            for name in ('session.json', 'end.json'):
                if name in archive.namelist():
                    if archive.getinfo(name).file_size > 1024 * 1024:
                        raise ValueError('Oversized metadata')
                    result[name] = json.loads(archive.read(name))
            return result
    with (path / 'sensors.bin' if path.is_dir() else path).open('rb') as stream:
        return analyze_stream(stream, stationary_seconds)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--stationary-seconds', type=float,
                        help='Only set when the user deliberately kept this prefix still')
    args = parser.parse_args()
    if args.stationary_seconds is not None and args.stationary_seconds <= 0:
        parser.error('stationary duration must be positive')
    print(json.dumps(analyze(args.input, args.stationary_seconds), ensure_ascii=False, indent=2))
