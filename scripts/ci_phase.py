"""Record content-free CI phase timings without capturing command arguments/output."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['startup', 'ordinary', 'cloud', 'voice'])
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command
    if command[:1] == ['--']:
        command = command[1:]
    if not command:
        parser.error('a command is required')
    start = time.monotonic()
    try:
        code = subprocess.run(command).returncode
        if code < 0:
            code = 128 - code
    except OSError:
        code = 127
    record = {'phase': args.phase, 'elapsed_seconds': round(time.monotonic() - start, 3),
              'exit_code': code}
    # A recording failure must not mask a failed command or turn missing evidence green.
    try:
        with Path(os.environ.get('DISTILLPOD_PHASE_TIMINGS',
                  '/tmp/distillpod-ci-phase-timings.jsonl')).open('a') as output:
            output.write(json.dumps(record) + '\n')
    except OSError:
        print('CI phase timing could not be recorded', flush=True)
        return code or 1
    print(f"CI phase {args.phase}: {record['elapsed_seconds']} seconds; exit {code}", flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
