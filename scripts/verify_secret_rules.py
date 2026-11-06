"""Verify scanner coverage with generated, never-issued credential fixtures."""

import argparse
import json
from pathlib import Path
import secrets
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gitleaks", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        source = temporary / "input"
        source.mkdir()
        fixtures = {
            "anthropic-oauth-token": "sk-ant-" + "oat01-" + secrets.token_urlsafe(72),
            "telegram-bot-token": "1234567890" + ":" + secrets.token_urlsafe(27)[:35],
            "tavily-api-token": "tvly-" + secrets.token_urlsafe(24),
        }
        for rule, value in fixtures.items():
            (source / (rule + ".txt")).write_text(value + "\n")
        report = temporary / "report.json"
        result = subprocess.run([
            args.gitleaks, "dir", str(source), "--config", str(root / ".gitleaks.toml"),
            "--redact=100", "--report-format=json", "--report-path", str(report),
        ], capture_output=True)
        if result.returncode != 1 or not report.exists():
            raise SystemExit("Scanner regression check failed: expected detected fixtures")
        detected = {finding["RuleID"] for finding in json.loads(report.read_text())}
        missing = set(fixtures) - detected
        if missing:
            raise SystemExit("Scanner missed fixture types: " + ", ".join(sorted(missing)))
        print("Secret scanner regression check: all three credential formats detected")


if __name__ == "__main__":
    main()
