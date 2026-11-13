"""Conservative complete-PR routing and a stable, fail-closed CI result gate."""
import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from docs_validation import check_links

FULL_JOBS = {'android_build', 'firebase', 'native', 'emulator'}
ROOT_DOCS = {'README.md', 'AGENTS.md', 'THIRD_PARTY_NOTICES.md',
             'CONTRIBUTING.md', 'SECURITY.md', 'LICENSE.md'}
OTHER_DOCS = {'firebase/README.md', 'scripts/fixtures/investigation/README.md',
              'design/icon-proposal/assets/Manrope-OFL.txt'}


@dataclass
class Route:
    mode: str
    paths: list[str]
    checkout_sha: str
    reason: str
    reuse_run_id: str = ''


def git(root, *args):
    try:
        return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL)
    except (OSError, subprocess.CalledProcessError) as error:
        raise ValueError('Cannot read complete Git evidence') from error


def direct_pr(event, inputs):
    # Reusable calls inherit their caller's event; even default false inputs
    # identify the workflow_call contract. Unknown contexts never enable docs.
    return event == 'pull_request' and isinstance(inputs, dict) and not inputs


def doc_path(path):
    if not path or '\\' in path or any(ord(char) < 32 for char in path):
        return False
    parts = PurePosixPath(path).parts
    if '..' in parts or path.startswith('/'):
        return False
    if path in ROOT_DOCS or path in OTHER_DOCS:
        return True
    suffix = PurePosixPath(path).suffix
    if path.startswith('docs/') and suffix in {'.md', '.png', '.mp4'}:
        return True
    return path.startswith('docs/android/evidence/') and suffix in {'.json', '.jsonl', '.txt'}


def choose_route(root, event, inputs, base, head, checkout_sha=None, draft=False):
    root = Path(root)
    checkout = git(root, 'rev-parse', 'HEAD').decode().strip()
    if checkout_sha and checkout != checkout_sha:
        raise ValueError('Unexpected checkout identity')
    if not direct_pr(event, inputs):
        return Route('full', [], checkout, 'manual, reusable, push or unknown context')
    if any(not isinstance(sha, str) or not re.fullmatch(r'[a-fA-F0-9]{40}|[a-fA-F0-9]{64}', sha)
           for sha in [base, head]):
        raise ValueError('Complete base/head commit identities are required')
    if git(root, 'rev-parse', '--is-shallow-repository').strip() != b'false':
        raise ValueError('Cannot classify a shallow repository as complete')
    for sha in [base, head]:
        if git(root, 'cat-file', '-t', sha).strip() != b'commit':
            raise ValueError('PR identity is not a commit')
    bases = git(root, 'merge-base', '--all', base, head).decode().splitlines()
    if len(bases) != 1:
        raise ValueError('A unique merge base is required')
    raw = git(root, 'diff', '--raw', '-z', '--no-abbrev', '--no-renames',
              '--no-ext-diff', '--no-textconv', bases[0], head, '--')
    fields = raw.split(b'\0')
    if fields[-1] != b'' or (len(fields) - 1) % 2:
        raise ValueError('Incomplete Git diff records')
    paths, eligible = [], True
    for i in range(0, len(fields) - 1, 2):
        record = fields[i].decode('ascii').split()
        if len(record) != 5 or not record[0].startswith(':'):
            raise ValueError('Unsupported Git diff record')
        path = os.fsdecode(fields[i + 1])
        paths.append(path)
        old_mode, new_mode, status = record[0][1:], record[1], record[4]
        eligible &= (status in {'A', 'D', 'M'} and
                     old_mode in {'000000', '100644'} and
                     new_mode in {'000000', '100644'} and doc_path(path))
    if not paths or not eligible:
        return Route('draft' if draft is True else 'full', paths, checkout,
                     'draft preview; full validation required when ready' if draft is True
                     else 'empty diff or non-documentation input')
    whitespace = subprocess.run(['git', 'diff', '--check', bases[0], head, '--'],
                                cwd=root, capture_output=True)
    if whitespace.returncode:
        # Do not print offending lines: they can contain accidentally staged data.
        raise ValueError('Documentation whitespace validation failed')
    tracked = [os.fsdecode(name) for name in git(root, 'ls-files', '-z').split(b'\0')
               if name and os.fsdecode(name).endswith('.md')]
    problems = check_links(root, tracked, paths)
    if problems:
        raise ValueError('Documentation link validation failed: ' + '; '.join(problems[:20]))
    return Route('docs', paths, checkout, 'reviewed documentation-only diff validated')


def validate_gate(needs, event, inputs, expected_sha, reuse_verified_run=''):
    if not isinstance(needs, dict) or set(needs) != FULL_JOBS | {'plan', 'security'}:
        raise ValueError('Required dependency evidence is missing or unexpected')
    plan = needs['plan']
    if plan.get('result') != 'success':
        raise ValueError('Classification/documentation validation did not succeed')
    output = plan.get('outputs', {})
    if output.get('checkout_sha') != expected_sha:
        raise ValueError('Classification revision does not match this run')
    mode = output.get('mode')
    if mode == 'draft':
        raise ValueError('Draft preview is incomplete; mark ready for review to run required validation')
    if mode == 'reused':
        run_id = output.get('reuse_run_id', '')
        if (event != 'push' or not isinstance(inputs, dict) or inputs or
                not run_id.isdigit() or reuse_verified_run != run_id):
            raise ValueError('Identical merged-tree validation proof is required')
        expected = 'skipped'
    elif mode == 'docs':
        if not direct_pr(event, inputs) or output.get('docs_validated') != 'true':
            raise ValueError('Documentation omission was not validated/authorized by the route')
        try:
            if int(output.get('changed_count', '0')) <= 0:
                raise ValueError('Documentation route has no changed-path evidence')
        except (TypeError, ValueError) as error:
            raise ValueError('Invalid documentation changed-path evidence') from error
        expected = 'skipped'
    elif mode == 'full' and output.get('docs_validated') == 'not_selected':
        expected = 'success'
    else:
        raise ValueError('Unknown or inconsistent validation route')
    independent_push_security = event == 'push' and isinstance(inputs, dict) and not inputs
    security_result = 'skipped' if independent_push_security else 'success'
    if needs['security'].get('result') != security_result:
        raise ValueError(f'security did not report the expected {security_result} result')
    for job in sorted(FULL_JOBS):
        if needs[job].get('result') != expected:
            raise ValueError(f'{job} did not report the expected {expected} result')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['select', 'gate'])
    args = parser.parse_args()
    try:
        event = os.environ.get('GITHUB_EVENT_NAME', '')
        inputs = json.loads(os.environ.get('WORKFLOW_INPUTS_CONTEXT', 'null'))
        expected = os.environ.get('GITHUB_SHA', '')
        if args.command == 'select':
            route = choose_route(Path.cwd(), event, inputs,
                                 os.environ.get('PR_BASE_SHA'), os.environ.get('PR_HEAD_SHA'), expected,
                                 draft=os.environ.get('PR_IS_DRAFT') == 'true')
            from ci_validation_proof import find_reusable
            reused = find_reusable(Path.cwd(), os.environ, inputs)
            if reused:
                route = Route('reused', [], expected, 'verified identical merged-tree full PR validation', reused)
            outputs = {'reuse_run_id': route.reuse_run_id, 'mode': route.mode, 'checkout_sha': route.checkout_sha,
                       'changed_count': str(len(route.paths)),
                       'docs_validated': 'true' if route.mode == 'docs' else 'not_selected'}
            with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
                output.write(''.join(f'{key}={value}\n' for key, value in outputs.items()))
            print(f'Validation route: {route.mode}; paths: {len(route.paths)}; {route.reason}; proof run: {reused or "none"}')
        else:
            needs = json.loads(os.environ['VALIDATION_NEEDS'])
            from ci_validation_proof import find_reusable, emit
            selected = needs.get('plan', {}).get('outputs', {})
            reused = (find_reusable(Path.cwd(), os.environ, inputs, selected.get('reuse_run_id'))
                      if selected.get('mode') == 'reused' else '')
            validate_gate(needs, event, inputs, expected, reused)
            if selected.get('mode') == 'full' and direct_pr(event, inputs):
                emit(Path.cwd(), os.environ, os.environ['CI_PROOF_OUTPUT'])
            print('Selected validation succeeded; omissions require verified documentation or merge proof')
    except (ValueError, KeyError, OSError) as error:
        print(f'Validation failed: {error}')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
