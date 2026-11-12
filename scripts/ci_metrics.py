"""Bounded, read-only Actions collection and content-free throughput accounting."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
from urllib.parse import quote

DRAFT_MARKER = 'Draft preview is incomplete; mark ready for review to run required validation'
EXPENSIVE = {'Android build validation', 'Firebase protocol and security rules',
             'Native bridge validation', 'Isolated Android acceptance'}


def instant(value):
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.tzinfo is None:
        raise ValueError('Timestamps must include an explicit timezone')
    return result.astimezone(timezone.utc)


def seconds(start, end):
    if not start or not end:
        return None
    duration = (instant(end) - instant(start)).total_seconds()
    if duration < 0:
        raise ValueError('Unexpected negative timestamp interval')
    return duration


def stats(values):
    ordered = sorted(v for v in values if v is not None)
    if not ordered:
        return {'n': 0, 'minimum': None, 'median': None, 'p90': None, 'maximum': None}
    return {'n': len(ordered), 'minimum': ordered[0], 'median': statistics.median(ordered),
            'p90': ordered[math.ceil(len(ordered) * .9) - 1], 'maximum': ordered[-1]}


def pages(read, endpoint, key, max_pages, page_size=100):
    """Return deduplicated items and explicit completeness, including capped reads."""
    rows, seen, total = [], set(), None
    for page in range(1, max_pages + 1):
        payload = read(f'{endpoint}{"&" if "?" in endpoint else "?"}per_page={page_size}&page={page}')
        batch = payload[key] if key else payload
        if not isinstance(batch, list):
            raise ValueError('Unexpected API page shape')
        if key:
            current_total = payload['total_count']
            if total is not None and total != current_total:
                return rows, False
            total = current_total
        added = 0
        for row in batch:
            identity = row['id'] if 'id' in row else row['number']
            if identity not in seen:
                seen.add(identity); rows.append(row); added += 1
        if key and len(rows) == total:
            return rows, True
        if len(batch) < page_size:
            return rows, total is None or len(rows) == total
        if not added:
            return rows, False
    return rows, False


def collect(read, repo, start, end, max_pages=10, max_runs=1000, max_logs=10, max_requests=2000):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo):
        raise ValueError('Expected repository owner/name')
    if instant(end) <= instant(start):
        raise ValueError('End must follow start')
    if not (1 <= max_pages <= 100 and 1 <= max_runs <= 1000 and 0 <= max_logs <= 100 and 1 <= max_requests <= 10000):
        raise ValueError('Collection limits outside supported bounds')
    data = {'schema': 1, 'scope': {'repo': repo, 'start': start, 'end': end,
            'workflow': 'ci.yml', 'max_pages': max_pages, 'max_runs': max_runs,
            'max_log_reads': max_logs, 'max_requests': max_requests, 'collected_at': datetime.now(timezone.utc).isoformat()},
            'coverage': {'complete': True, 'errors': [], 'log_reads': 0, 'requests': 0},
            'runs': [], 'attempts': [], 'pulls': []}
    coverage = data['coverage']
    provider = read

    def read(endpoint):
        if coverage['requests'] >= max_requests:
            coverage['request_limit_hit'] = True
            raise RuntimeError('request budget exhausted')
        coverage['requests'] += 1
        return provider(endpoint)

    def fetch(label, endpoint, key):
        try:
            items, complete = pages(read, endpoint, key, max_pages)
            if not complete:
                coverage['complete'] = False
                coverage['errors'].append(f'{label}: pagination incomplete or capped')
            return items
        except (RuntimeError, ValueError, KeyError, TypeError):
            coverage['complete'] = False
            coverage['errors'].append(f'{label}: API read or shape failure')
            return []

    # GitHub's filtered Actions search is capped at 1,000. Partition the UTC
    # window for larger collections; do not infer that the cap means exhaustion.
    runs = fetch('runs', f'repos/{repo}/actions/workflows/ci.yml/runs?created={quote(start, safe=":")}..{quote(end, safe=":")}', 'workflow_runs')
    runs = [r for r in runs if instant(start) <= instant(r['created_at']) < instant(end)]
    if len(runs) >= max_runs:
        coverage['complete'] = False
        coverage['errors'].append('run limit reached; narrow or partition the UTC window')
    data['runs'] = runs[:max_runs]
    data['pulls'] = fetch('pulls', f'repos/{repo}/pulls?state=all&sort=updated&direction=desc', None)
    for run in data['runs']:
        if coverage.get('request_limit_hit'):
            break
        attempts = run.get('run_attempt', 1)
        if not isinstance(attempts, int) or not 1 <= attempts <= 100:
            coverage['complete'] = False
            coverage['errors'].append(f"run {run['id']}: unsupported attempt count")
            continue
        for number in range(1, attempts + 1):
            if coverage.get('request_limit_hit'):
                break
            endpoint = f"repos/{repo}/actions/runs/{run['id']}/attempts/{number}"
            metadata = {}
            try:
                metadata = read(endpoint)
            except (RuntimeError, ValueError):
                coverage['complete'] = False
                coverage['errors'].append(f"run {run['id']} attempt {number}: metadata unavailable")
            entry = {'run_id': run['id'], 'attempt': number, 'metadata': metadata,
                     'jobs': fetch(f"run {run['id']} attempt {number}", endpoint + '/jobs', 'jobs')}
            # Keep only a bounded known marker, never persist raw command logs.
            gates = [j for j in entry['jobs'] if j['name'] == 'Kotlin, lint and APK'
                     and j.get('conclusion') == 'failure']
            for gate in gates:
                if coverage['log_reads'] >= max_logs:
                    coverage['complete'] = False
                    coverage['errors'].append('draft classification log limit reached; failed gates may be unknown')
                    break
                coverage['log_reads'] += 1
                try:
                    log = read(f"repos/{repo}/actions/jobs/{gate['id']}/logs")
                    entry['draft_incomplete_marker'] = isinstance(log, str) and DRAFT_MARKER in log
                except (RuntimeError, ValueError):
                    coverage['complete'] = False
                    coverage['errors'].append(f"job {gate['id']}: draft classification log unavailable")
            results = {j['name']: j.get('conclusion') for j in entry['jobs']}
            if (run.get('event') == 'push' and
                    results.get('Kotlin, lint and APK') == 'success' and
                    all(results.get(name) == 'skipped' for name in EXPENSIVE)):
                plans = [j for j in entry['jobs'] if j['name'] == 'Select validation and check documentation'
                         and j.get('conclusion') == 'success']
                if len(plans) == 1:
                    if coverage['log_reads'] >= max_logs:
                        coverage['complete'] = False
                        coverage['errors'].append('reuse classification log limit reached')
                    else:
                        coverage['log_reads'] += 1
                        try:
                            log = read(f"repos/{repo}/actions/jobs/{plans[0]['id']}/logs")
                            matches = re.findall(r'Validation route: reused;[^\n]*; proof run: ([1-9]\d*)', log)
                            if len(matches) == 1:
                                entry['reused_validation_source_run'] = int(matches[0])
                        except (RuntimeError, ValueError, TypeError):
                            coverage['complete'] = False
                            coverage['errors'].append(f"job {plans[0]['id']}: reuse classification log unavailable")
            data['attempts'].append(entry)
    return data


def classification(entry):
    jobs = entry['jobs']
    results = {j['name']: j.get('conclusion') for j in jobs}
    if (entry.get('draft_incomplete_marker') is True and
        results.get('Kotlin, lint and APK') == 'failure' and
        all(results.get(name) == 'skipped' for name in EXPENSIVE) and
        all(j.get('conclusion') in {'success', 'skipped'}
            for j in jobs if j['name'] != 'Kotlin, lint and APK')):
        return 'draft_incomplete'
    reused_source = entry.get('reused_validation_source_run')
    if (type(reused_source) is int and reused_source > 0 and
        entry.get('metadata', {}).get('event') == 'push' and
        entry.get('metadata', {}).get('conclusion') == 'success' and
        results.get('Kotlin, lint and APK') == 'success' and
        all(results.get(name) == 'skipped' for name in EXPENSIVE) and
        all(j.get('conclusion') in {'success', 'skipped'} for j in jobs)):
        return 'reused_validation'
    if any(j.get('conclusion') in {'failure', 'timed_out', 'startup_failure', 'action_required'} for j in jobs):
        return 'failure'
    if entry.get('metadata', {}).get('conclusion') in {'failure', 'timed_out', 'startup_failure', 'action_required'}:
        return 'failure'
    if entry.get('metadata', {}).get('conclusion') == 'cancelled' or any(j.get('conclusion') == 'cancelled' for j in jobs):
        return 'cancelled'
    if not jobs or any(j.get('status') != 'completed' for j in jobs):
        return 'unknown_or_running'
    if any(j.get('conclusion') not in {'success', 'skipped'} for j in jobs):
        return 'unknown_or_running'
    if results.get('Isolated Android acceptance') == 'success':
        return 'full_success'
    return 'lightweight_or_other_success'


def analyze(data):
    if data.get('schema') != 1:
        raise ValueError('Unsupported snapshot schema')
    runs = {r['id']: r for r in data['runs']}
    branches = defaultdict(set)
    for pull in data.get('pulls', []):
        head = pull.get('head', {})
        branches[((head.get('repo') or {}).get('full_name'), head.get('ref'))].add(pull['number'])
    pr_map, unassociated = {}, 0
    for ident, run in runs.items():
        if run.get('event') != 'pull_request':
            continue
        candidates = {p['number'] for p in run.get('pull_requests', [])}
        if not candidates:
            candidates = branches[((run.get('head_repository') or {}).get('full_name'), run.get('head_branch'))]
        if len(candidates) == 1:
            pr_map[ident] = str(next(iter(candidates)))
        else:
            unassociated += 1
    totals = Counter(); categories = Counter(); per_pr = Counter(pr_map.values()); full_pr = Counter()
    execution, queue, wall, span, dispatch = [], [], [], [], []
    by_job = defaultdict(list); attempt_metrics = []; seen_jobs = set(); seen_attempts = set(); seen_executions = set()
    for original in sorted(data['attempts'], key=lambda a: (a['run_id'], a['attempt'])):
        key = (original['run_id'], original['attempt'])
        if key in seen_attempts:
            continue
        seen_attempts.add(key)
        entry = dict(original, jobs=[])
        for job in original['jobs']:
            if job['id'] in seen_jobs:
                continue
            seen_jobs.add(job['id']); entry['jobs'].append(job)
        category = classification(entry); categories[category] += 1
        if category == 'full_success' and entry['run_id'] in pr_map:
            full_pr[pr_map[entry['run_id']]] += 1
        attempt_seconds = 0; starts, ends = [], []
        for job in entry['jobs']:
            if job.get('conclusion') == 'skipped':
                continue
            # Failed-only reruns clone prior successes with NEW job IDs and the
            # ORIGINAL runner/timestamps. These are results, not new execution.
            identity = (entry['run_id'], job['name'], job.get('started_at'),
                        job.get('completed_at'), job.get('runner_id'))
            has_interval = bool(job.get('started_at') and job.get('completed_at'))
            if has_interval and identity in seen_executions:
                totals['carried_forward_jobs'] += 1
                continue
            if has_interval:
                seen_executions.add(identity)
            totals['executed_job_count'] += 1
            duration = seconds(job.get('started_at'), job.get('completed_at'))
            try:
                wait = seconds(job.get('created_at'), job.get('started_at'))
            except ValueError:
                # Some API timestamps are inconsistent; expose missing queue
                # evidence instead of clamping a negative interval to zero.
                wait = None
                totals['inconsistent_job_queue_timestamps'] += 1
            if duration is None:
                totals['jobs_without_execution_time'] += 1
            else:
                execution.append(duration); attempt_seconds += duration
                by_job[job['name'].split(' / ')[-1]].append(duration)
            if wait is not None:
                queue.append(wait)
            else:
                totals['jobs_without_queue_time'] += 1
            if job.get('started_at'): starts.append(job['started_at'])
            if job.get('completed_at'): ends.append(job['completed_at'])
        totals['runner_seconds'] += attempt_seconds
        if category == 'cancelled': totals['cancelled_seconds'] += attempt_seconds
        if category == 'draft_incomplete': totals['draft_seconds'] += attempt_seconds
        if category == 'reused_validation': totals['reused_seconds'] += attempt_seconds
        if entry['attempt'] > 1:
            totals['rerun_attempts'] += 1; totals['rerun_seconds'] += attempt_seconds
        metadata = entry.get('metadata', {})
        start = metadata.get('run_started_at')
        if start and ends:
            wall.append(seconds(start, max(ends)))
        else:
            totals['attempts_without_wall_time'] += 1
        if starts and ends:
            span.append(seconds(min(starts), max(ends)))
        attempt_metrics.append({'run_id': entry['run_id'], 'attempt': entry['attempt'],
            'classification': category, 'runner_minutes': attempt_seconds / 60,
            'validation_source_run': entry.get('reused_validation_source_run') if category == 'reused_validation' else None,
            'wall_seconds': seconds(start, max(ends)) if start and ends else None,
            'execution_span_seconds': seconds(min(starts), max(ends)) if starts and ends else None})
        if category == 'cancelled' and ends:
            source = runs.get(entry['run_id'], {})
            group = (source.get('event'), source.get('head_branch'),
                     (source.get('head_repository') or {}).get('full_name'))
            for other in runs.values():
                candidate = (other.get('event'), other.get('head_branch'),
                             (other.get('head_repository') or {}).get('full_name'))
                if (group == candidate and source.get('created_at') and other.get('created_at')
                    and instant(source['created_at']) < instant(other['created_at']) <= instant(max(ends))):
                    totals['candidate_superseded_seconds'] += attempt_seconds
                    totals['candidate_superseded_attempts'] += 1
                    break
        if entry['attempt'] == 1:
            run = runs.get(entry['run_id'], {})
            if start: dispatch.append(seconds(run.get('created_at'), start))
    limitations = [
        'Runner minutes sum observed job intervals; billed minutes are unavailable and are not inferred.',
        'Cancelled work does not prove intentional supersession; cancellation causes require separate evidence.',
        'Carried-forward rerun jobs are deduplicated by run/name/runner/execution interval, not new job IDs.',
        'Job queue delay excludes dependency waiting before job creation; initial dispatch delay is not runner queue delay.',
        'Attempt wall time ends at the last completed job, excluding workflow finalization; execution span omits initial orchestration.',
        'PR branch fallback requires one matching repository/ref; branch reuse or missing records remain unassociated.',
        'Unequal windows, code, cache state, runner hardware and small samples prevent causal speedup or flake-rate claims.',
        'Failed gates without the verified draft marker remain failures, not assumed draft previews.',
        'Proof-reused main validations require a successful gate, planned omissions and an explicit plan-log source-run marker; they are not full suite executions.'
    ]
    return {'schema': 1, 'scope': data.get('scope', {}), 'coverage': data['coverage'],
            'accounting_complete': bool(data['coverage']['complete']) and not totals['jobs_without_execution_time'],
            'run_count': len(runs), 'attempt_count': len(seen_attempts), 'job_count': len(seen_jobs),
            'executed_job_count': totals['executed_job_count'],
            'carried_forward_jobs': totals['carried_forward_jobs'],
            'inconsistent_job_queue_timestamps': totals['inconsistent_job_queue_timestamps'],
            'runner_minutes': totals['runner_seconds'] / 60, 'billed_minutes': None,
            'cancelled_attempt_runner_minutes': totals['cancelled_seconds'] / 60,
            'candidate_superseded_attempts': totals['candidate_superseded_attempts'],
            'candidate_superseded_runner_minutes': totals['candidate_superseded_seconds'] / 60,
            'draft_preview_runner_minutes': totals['draft_seconds'] / 60,
            'reused_validation_runner_minutes': totals['reused_seconds'] / 60,
            'rerun_attempts': totals['rerun_attempts'], 'rerun_runner_minutes': totals['rerun_seconds'] / 60,
            'jobs_without_execution_time': totals['jobs_without_execution_time'],
            'jobs_without_queue_time': totals['jobs_without_queue_time'],
            'attempts_without_wall_time': totals['attempts_without_wall_time'],
            'attempt_classifications': dict(categories), 'job_execution_seconds': stats(execution),
            'job_queue_seconds': stats(queue), 'attempt_wall_seconds': stats(wall),
            'execution_span_seconds': stats(span), 'initial_dispatch_delay_seconds': stats(dispatch),
            'job_runner_minutes': {name: sum(values)/60 for name, values in sorted(by_job.items())},
            'runs_per_pr': dict(sorted(per_pr.items())),
            'full_successful_attempts_per_pr': dict(sorted(full_pr.items())),
            'unassociated_pr_runs': unassociated, 'attempt_metrics': attempt_metrics, 'limitations': limitations}



def import_audit(runs_file, jobs_dir, pulls_file, repo, start, end):
    """Read the earlier paginated audit without altering private source exports."""
    if instant(end) <= instant(start):
        raise ValueError('End must follow start')
    run_pages = json.loads(Path(runs_file).read_text())
    runs = {run['id']: run for page in run_pages for run in page['workflow_runs']}
    complete = bool(run_pages) and len(runs) == run_pages[0]['total_count']
    data = {'schema': 1, 'scope': {'repo': repo, 'start': start, 'end': end,
            'workflow': 'ci.yml', 'source': 'legacy paginated audit'},
            'coverage': {'complete': complete, 'errors': [], 'log_reads': 0},
            'runs': [], 'attempts': [], 'pulls': []}
    if not complete:
        data['coverage']['errors'].append('legacy run pages incomplete')
    if pulls_file:
        pull_pages = json.loads(Path(pulls_file).read_text())
        data['pulls'] = [pull for page in pull_pages for pull in page]
        if not pull_pages or len(pull_pages[-1]) >= 100:
            data['coverage']['complete'] = False
            data['coverage']['errors'].append('legacy pull pagination cannot establish exhaustion')
    for run in runs.values():
        if run.get('path') != '.github/workflows/ci.yml' or not (
                instant(start) <= instant(run['created_at']) < instant(end)):
            continue
        data['runs'].append(run)
        try:
            job_pages = json.loads((Path(jobs_dir) / f"jobs-{run['id']}.json").read_text())
            jobs = {job['id']: job for page in job_pages for job in page['jobs']}
            if not job_pages or len(jobs) != job_pages[0]['total_count']:
                raise ValueError('incomplete job pages')
        except (OSError, ValueError, KeyError):
            data['coverage']['complete'] = False
            data['coverage']['errors'].append(f"run {run['id']}: legacy jobs missing or incomplete")
            continue
        for attempt in range(1, run.get('run_attempt', 1) + 1):
            # Latest run metadata cannot describe a historical attempt's start.
            metadata = {'run_started_at': run.get('run_started_at'),
                        'conclusion': run.get('conclusion')} if attempt == run.get('run_attempt', 1) else {}
            data['attempts'].append({'run_id': run['id'], 'attempt': attempt,
                'metadata': metadata, 'jobs': [job for job in jobs.values()
                    if job['run_attempt'] == attempt]})
    return data


def gh_read(endpoint):
    command = subprocess.run(['gh', 'api', endpoint], capture_output=True, text=True)
    if command.returncode:
        raise RuntimeError('GitHub read failed')
    if endpoint.endswith('/logs'):
        return command.stdout
    return json.loads(command.stdout)


def write_private(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    # Refuse to replace a symlink or an existing file, and keep raw API data private.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(data, output, indent=2); output.write('\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    query = commands.add_parser('collect')
    query.add_argument('--repo', required=True); query.add_argument('--start', required=True)
    query.add_argument('--end', required=True); query.add_argument('--output', required=True)
    query.add_argument('--max-pages', type=int, default=10)
    query.add_argument('--max-runs', type=int, default=1000)
    query.add_argument('--max-logs', type=int, default=10)
    query.add_argument('--max-requests', type=int, default=2000)
    legacy = commands.add_parser('import-audit')
    legacy.add_argument('--runs', required=True); legacy.add_argument('--jobs-dir', required=True)
    legacy.add_argument('--pulls'); legacy.add_argument('--repo', required=True)
    legacy.add_argument('--start', required=True); legacy.add_argument('--end', required=True)
    legacy.add_argument('--output', required=True)
    report = commands.add_parser('analyze'); report.add_argument('snapshot')
    args = parser.parse_args()
    try:
        if args.command == 'collect':
            data = collect(gh_read, args.repo, args.start, args.end,
                           args.max_pages, args.max_runs, args.max_logs, args.max_requests)
            write_private(args.output, data)
            print(json.dumps({'runs': len(data['runs']), 'coverage': data['coverage']}))
            return 0 if data['coverage']['complete'] else 2
        if args.command == 'import-audit':
            data = import_audit(args.runs, args.jobs_dir, args.pulls, args.repo, args.start, args.end)
            write_private(args.output, data)
            print(json.dumps({'runs': len(data['runs']), 'coverage': data['coverage']}))
            return 0 if data['coverage']['complete'] else 2
        print(json.dumps(analyze(json.loads(Path(args.snapshot).read_text())), indent=2))
    except (ValueError, OSError, KeyError, TypeError) as error:
        # Avoid printing raw provider responses, command arguments or private file content.
        print(f'CI metrics failed: {type(error).__name__}')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
