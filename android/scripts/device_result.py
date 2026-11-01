"""ADB can exit zero for failing instrumentation; require the runner's completion report."""
import re
import sys
from pathlib import Path


def verify_result(output: str) -> int:
    if any(marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed', 'INSTRUMENTATION_STATUS_CODE: -2', 'INSTRUMENTATION_STATUS_CODE: -1', 'INSTRUMENTATION_STATUS_CODE: -3', 'INSTRUMENTATION_STATUS_CODE: -4')):
        raise ValueError('Android instrumentation reported failures')
    matches = re.findall(r'^OK \((\d+) tests?\)', output, re.MULTILINE)
    if len(matches) != 1 or int(matches[0]) == 0:
        raise ValueError('Android instrumentation did not finish a nonempty test suite')
    return int(matches[0])


if __name__ == '__main__':
    try:
        print(f'Android instrumentation passed: {verify_result(Path(sys.argv[1]).read_text())} tests')
    except (ValueError, OSError, IndexError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
