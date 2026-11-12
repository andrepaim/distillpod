"""Conservative, read-only reuse of successful full PR validation after a merge."""
import datetime
import io
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zipfile
import zlib

WORKFLOW = '.github/workflows/ci.yml'
ARTIFACT = 'ci-validation-proof-'
REQUIRED = {'Select validation and check documentation',
            'Repository security / Secrets and personal data guard',
            'Android build validation', 'Firebase protocol and security rules',
            'Native bridge validation', 'Isolated Android acceptance', 'Kotlin, lint and APK'}


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL).decode().strip()


def source(root):
    return {'checkout_sha': git(root, 'rev-parse', 'HEAD'),
            'tree': git(root, 'rev-parse', 'HEAD^{tree}'),
            'workflow_sha': git(root, 'rev-parse', f'HEAD:{WORKFLOW}'),
            'parents': git(root, 'show', '-s', '--format=%P', 'HEAD').split()}


def make_proof(root, env):
    s = source(root)
    if (env.get('GITHUB_EVENT_NAME') != 'pull_request' or len(s['parents']) != 2 or
            s['parents'] != [env.get('PR_BASE_SHA'), env.get('PR_HEAD_SHA')] or
            s['checkout_sha'] != env.get('GITHUB_SHA')):
        raise ValueError('Only a verified PR merge checkout can emit full validation proof')
    return {'schema': 1, 'mode': 'full', 'repository': env['GITHUB_REPOSITORY'],
            'run_id': int(env['GITHUB_RUN_ID']), 'run_attempt': int(env['GITHUB_RUN_ATTEMPT']),
            'base': s['parents'][0], 'head': s['parents'][1], **s}


def direct_main(root, env, inputs):
    if (env.get('GITHUB_EVENT_NAME') != 'push' or env.get('GITHUB_REF') != 'refs/heads/main' or
            not isinstance(inputs, dict) or inputs):
        return False
    parents = source(root)['parents']
    return len(parents) == 2 and parents[0] == env.get('PUSH_BEFORE_SHA')


def verify_evidence(root, proof, run, jobs, repository, tested_commit=None):
    try:
        current = source(root)
        created = datetime.datetime.fromisoformat(run['created_at'].replace('Z', '+00:00'))
        age = (datetime.datetime.now(datetime.timezone.utc) - created).total_seconds()
        if not 0 <= age <= 86400:
            return False
        if tested_commit is None:
            sha = proof['checkout_sha']
            tested_commit = {'sha': git(root, 'rev-parse', sha),
                             'tree': {'sha': git(root, 'rev-parse', f'{sha}^{{tree}}')},
                             'parents': [{'sha': p} for p in git(root, 'show', '-s', '--format=%P', sha).split()]}
        if (proof['schema'] != 1 or proof['mode'] != 'full' or proof['repository'] != repository or
                proof['tree'] != current['tree'] or proof['workflow_sha'] != current['workflow_sha'] or
                current['parents'] != [proof['base'], proof['head']] or
                proof['parents'] != current['parents'] or
                tested_commit['sha'] != proof['checkout_sha'] or tested_commit['tree']['sha'] != current['tree'] or
                [p['sha'] for p in tested_commit['parents']] != current['parents']):
            return False
        if (run['id'] != proof['run_id'] or run['run_attempt'] != proof['run_attempt'] or
                run['event'] != 'pull_request' or run['status'] != 'completed' or
                run['conclusion'] != 'success' or run['head_sha'] != proof['head'] or
                run['path'] != WORKFLOW or run['repository']['full_name'] != repository or
                run['head_repository']['full_name'] != repository):
            return False
        names = [j['name'] for j in jobs]
        return (len(names) == len(set(names)) and REQUIRED.issubset(names) and
                all(j.get('conclusion') == 'success' for j in jobs))
    except (KeyError, TypeError, ValueError, OSError, subprocess.CalledProcessError):
        return False


def proof_archive(data):
    if len(data) > 32768:
        raise ValueError('Oversized proof archive')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        files = archive.infolist()
        if len(files) != 1 or files[0].filename != 'proof.json' or files[0].file_size > 32768:
            raise ValueError('Unexpected proof archive contents')
        value = json.loads(archive.read(files[0]))
        if not isinstance(value, dict):
            raise ValueError('Invalid proof document')
        return value


class ActionsAPI:
    """Bounded CLI reads; credentials/remote error bodies never appear in output."""
    def __init__(self, repository):
        if not re.fullmatch(r'[\w.-]+/[\w.-]+', repository):
            raise ValueError('Invalid repository')
        self.repository = repository
        self.deadline = time.monotonic() + 45

    def get(self, path, binary=False):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise ValueError('Proof lookup budget exhausted')
        response = subprocess.run(['gh', 'api', f'repos/{self.repository}/{path}'],
                                  capture_output=True, timeout=min(15, remaining))
        if response.returncode or len(response.stdout) > 2_000_000:
            raise ValueError('Proof API read failed or exceeded its bound')
        return response.stdout if binary else json.loads(response.stdout)

    def evidence(self, root, run):
        run_id, attempt = int(run['id']), int(run['run_attempt'])
        artifacts = self.get(f'actions/runs/{run_id}/artifacts?per_page=100')
        if artifacts['total_count'] > len(artifacts['artifacts']):
            return False
        selected = [a for a in artifacts['artifacts'] if a['name'] == f'{ARTIFACT}{attempt}' and
                    not a['expired'] and a['size_in_bytes'] <= 32768]
        if len(selected) != 1:
            return False
        proof = proof_archive(self.get(f"actions/artifacts/{int(selected[0]['id'])}/zip", binary=True))
        jobs = self.get(f'actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100')
        if jobs['total_count'] != len(jobs['jobs']):
            return False
        sha = proof.get('checkout_sha', '')
        if not re.fullmatch('[a-f0-9]{40}', sha):
            return False
        commit = self.get(f'git/commits/{sha}')
        return verify_evidence(root, proof, run, jobs['jobs'], self.repository, commit)


def find_reusable(root, env, inputs, run_id=None, api=None):
    """Missing/failed/ambiguous evidence always selects fresh full validation."""
    try:
        if not direct_main(root, env, inputs):
            return ''
        api = api or ActionsAPI(env['GITHUB_REPOSITORY'])
        if run_id is not None:
            if not str(run_id).isdigit():
                return ''
            runs = [api.get(f'actions/runs/{int(run_id)}')]
        else:
            head = source(root)['parents'][1]
            runs = api.get(f'actions/workflows/ci.yml/runs?event=pull_request&status=success&head_sha={head}&per_page=5')['workflow_runs']
        for run in runs[:5]:
            if api.evidence(root, run):
                return str(run['id'])
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, RuntimeError, zlib.error):
        pass
    return ''


def emit(root, env, output):
    Path(output).parent.mkdir(parents=True, exist_ok=True)
    Path(output).write_text(json.dumps(make_proof(root, env), sort_keys=True) + '\n')
