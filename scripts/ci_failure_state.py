"""Failure-only, content-free liveness evidence for disposable hosted CI emulators."""
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

MEMORY_KEYS = {'MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree'}
EMULATOR_NAMES = {'emulator', 'qemu-system-x86', 'qemu-system-aar'}


def diagnostics_enabled(env):
    return (env.get('DISTILLPOD_FAILURE_DIAGNOSTICS') == 'true' and
            env.get('GITHUB_ACTIONS') == 'true' and
            env.get('RUNNER_ENVIRONMENT') == 'github-hosted' and
            re.fullmatch(r'emulator-[0-9]{1,5}', env.get('DISTILLPOD_SERIAL', '')) is not None)


def read_bounded(path):
    try:
        with path.open() as source:
            return source.read(8192)
    except (OSError, UnicodeError):
        return ''


def probe_adb(serial):
    if re.fullmatch(r'emulator-[0-9]{1,5}', serial) is None:
        return 'refused'
    try:
        result = subprocess.run(['adb', '-s', serial, 'get-state'], timeout=2,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
    except subprocess.TimeoutExpired:
        return 'timeout'
    except OSError:
        return 'unavailable'
    # Never serialize the command, target or raw output, including error messages.
    if result.returncode == 0 and result.stdout.strip() in {b'device', b'offline', b'unknown'}:
        return result.stdout.strip().decode('ascii')
    if b'device offline' in result.stderr:
        return 'offline'
    return 'unknown'


def collect_failure_state(phase, env=None, proc=Path('/proc'), transport=probe_adb):
    env = os.environ if env is None else env
    if not diagnostics_enabled(env):
        return None
    if phase not in {'startup', 'ordinary', 'cloud', 'voice'}:
        raise ValueError('Unknown CI phase')
    proc = Path(proc)
    data = {'phase': phase, 'memory_kib': {}, 'process_counts': {
        'emulator': 0, 'emulator_zombie': 0, 'adb': 0, 'adb_zombie': 0}}
    for line in read_bounded(proc / 'meminfo').splitlines():
        match = re.fullmatch(r'([A-Za-z]+):\s+([0-9]+) kB', line)
        if match and match[1] in MEMORY_KEYS:
            data['memory_kib'][match[1]] = int(match[2])
    for line in read_bounded(proc / 'vmstat').splitlines():
        match = re.fullmatch(r'oom_kill ([0-9]+)', line)
        if match:
            data['host_oom_kills_total'] = int(match[1])
    # Do not read cmdline, environ, logcat or arbitrary process names into evidence.
    try:
        inspected = 0
        for entry in proc.iterdir():
            if not entry.name.isdigit():
                continue
            if inspected == 4096:
                data['process_scan_truncated'] = True
                break
            inspected += 1
            name = read_bounded(entry / 'comm').strip()
            label = 'emulator' if name in EMULATOR_NAMES else 'adb' if name == 'adb' else None
            if label:
                data['process_counts'][label] += 1
                if re.search(r'^State:\s+Z\b', read_bounded(entry / 'status'), re.MULTILINE):
                    data['process_counts'][label + '_zombie'] += 1
        data.setdefault('process_scan_truncated', False)
    except OSError:
        data['process_scan_unavailable'] = True
    try:
        usage = shutil.disk_usage('/')
        data['disk_free_bytes'] = usage.free
        data['disk_total_bytes'] = usage.total
    except OSError:
        pass
    data['adb_state'] = transport(env['DISTILLPOD_SERIAL'])
    return data


def record_failure_state(phase):
    data = collect_failure_state(phase)
    if data is None:
        return
    target = Path(os.environ.get('DISTILLPOD_FAILURE_STATE',
                                 '/tmp/distillpod-ci-failure-state.jsonl'))
    with target.open('a') as output:
        output.write(json.dumps(data, sort_keys=True) + '\n')
