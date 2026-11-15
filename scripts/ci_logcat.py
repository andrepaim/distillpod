"""Failure-only logcat capture for disposable hosted CI emulators (#251).

The emulator holds only synthetic fixtures. Logcat streams to a private host file for the
whole phase, so a failure early in a long phase survives the device's small ring buffer.
The file is kept only when the phase fails, bounded to its newest bytes.
"""
import os
from pathlib import Path
import subprocess
import tempfile

from ci_failure_state import diagnostics_enabled

MAX_BYTES = 64 * 1024 * 1024
BUFFERS = 'main,system,crash'


def output_path(phase, env):
    return Path(env.get('DISTILLPOD_LOGCAT_DIR', '/tmp')) / f'distillpod-logcat-{phase}.txt'


class Capture:
    def __init__(self, process, path):
        self.process = process
        self.path = path


def _spawn(serial, output):
    # -T 1 starts at the current end of the device buffer; earlier phases keep their own files.
    return subprocess.Popen(['adb', '-s', serial, 'logcat', '-v', 'threadtime', '-b', BUFFERS, '-T', '1'],
                            stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.DEVNULL)


def start(phase, env=None, spawn=_spawn):
    """Returns a capture, or None outside an explicitly enabled hosted emulator run."""
    env = os.environ if env is None else env
    if not diagnostics_enabled(env):
        return None
    descriptor, name = tempfile.mkstemp(prefix=f'distillpod-logcat-{phase}-', suffix='.partial')
    try:
        with os.fdopen(descriptor, 'wb') as output:
            process = spawn(env['DISTILLPOD_SERIAL'], output)
    except Exception:
        Path(name).unlink(missing_ok=True)
        raise
    return Capture(process, Path(name))


def _stop(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def finish(capture, phase, failed, env=None, limit=MAX_BYTES):
    """Stops the stream; keeps the newest `limit` bytes for a failed phase, discards otherwise."""
    if capture is None:
        return None
    env = os.environ if env is None else env
    try:
        _stop(capture.process)
        if not failed:
            return None
        target = output_path(phase, env)
        size = capture.path.stat().st_size
        with capture.path.open('rb') as source, target.open('wb') as output:
            if size > limit:
                source.seek(size - limit)
                source.readline()  # Start at a whole line.
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        return target
    finally:
        capture.path.unlink(missing_ok=True)
