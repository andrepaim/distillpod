"""Synthetic Actions histories verify accounting without GitHub/network access."""
import copy
import unittest
import json
import os
from pathlib import Path
import tempfile
from ci_metrics import analyze, collect, pages, import_audit, write_private, stats


def job(ident, name='Isolated Android acceptance', start='2026-11-12T00:01:00Z',
        end='2026-11-12T00:03:00Z', conclusion='success', attempt=1):
    return dict(id=ident, run_attempt=attempt, name=name, created_at='2026-11-12T00:00:30Z',
                started_at=start, completed_at=end, status='completed', conclusion=conclusion)


def snapshot():
    return {'schema': 1, 'scope': {'repo': 'owner/repo'},
            'coverage': {'complete': True, 'errors': []},
            'runs': [dict(id=1, event='pull_request', head_branch='feature',
                head_repository={'full_name':'owner/repo'}, pull_requests=[],
                created_at='2026-11-12T00:00:00Z', run_attempt=2)],
            'pulls': [dict(number=10, head={'ref':'feature','repo':{'full_name':'owner/repo'}})],
            'attempts': [dict(run_id=1, attempt=1, metadata={'run_started_at':'2026-11-12T00:00:10Z'},
                jobs=[job(1), job(2, 'Kotlin, lint and APK')]),
                dict(run_id=1, attempt=2, metadata={'run_started_at':'2026-11-12T00:05:00Z'},
                jobs=[job(3, start='2026-11-12T00:05:10Z', end='2026-11-12T00:06:00Z',attempt=2)])]}


class AccountingTest(unittest.TestCase):
    def test_parallel_jobs_sum_execution_but_not_wall_time_and_deduplicate(self):
        data=snapshot(); data['attempts'][0]['jobs'].append(copy.deepcopy(data['attempts'][0]['jobs'][0]))
        result=analyze(data)
        self.assertAlmostEqual(290/60,result['runner_minutes'])
        self.assertEqual(2,result['attempt_count'])
        self.assertEqual(3,result['job_count'])
        self.assertEqual(170,result['attempt_wall_seconds']['maximum'])
        self.assertEqual(30,result['job_queue_seconds']['minimum'])
        self.assertEqual(10,result['initial_dispatch_delay_seconds']['median'])
        self.assertEqual(1,result['rerun_attempts'])
        self.assertAlmostEqual(50/60,result['rerun_runner_minutes'])
        self.assertIsNone(result['billed_minutes'])
        self.assertEqual({'10':1},result['runs_per_pr'])
        self.assertEqual({'10':2},result['full_successful_attempts_per_pr'])

    def test_failed_only_rerun_reuses_successful_jobs_under_new_ids(self):
        data=snapshot()
        copied=copy.deepcopy(data['attempts'][0]['jobs'][0])
        copied.update(id=99,run_attempt=2,created_at='2026-11-12T00:05:00Z')
        data['attempts'][1]['jobs'].append(copied)
        result=analyze(data)
        self.assertAlmostEqual(290/60,result['runner_minutes'])
        self.assertAlmostEqual(50/60,result['rerun_runner_minutes'])
        self.assertEqual(1,result['carried_forward_jobs'])
        self.assertEqual(3,result['executed_job_count'])

    def test_true_failure_and_intentional_incomplete_draft_are_separate(self):
        data=snapshot(); first=data['attempts'][0]
        first['jobs']=[job(1,'Kotlin, lint and APK',conclusion='failure')]
        for i,name in enumerate(['Android build validation','Firebase protocol and security rules',
                                  'Native bridge validation','Isolated Android acceptance'],2):
            first['jobs'].append(job(i,name,conclusion='skipped'))
        first['draft_incomplete_marker']=True
        result=analyze(data)
        self.assertEqual(1,result['attempt_classifications']['draft_incomplete'])
        self.assertEqual(0,result['attempt_classifications'].get('failure',0))
        first['draft_incomplete_marker']=False
        self.assertEqual(1,analyze(data)['attempt_classifications']['failure'])
        first['draft_incomplete_marker']=True;first['jobs'][-1]['conclusion']='failure'
        self.assertEqual(1,analyze(data)['attempt_classifications']['failure'])

    def test_reused_main_requires_marker_success_and_only_planned_omissions(self):
        data=snapshot();entry=data['attempts'][0]
        entry['metadata']['event']='push';entry['metadata']['conclusion']='success'
        entry['jobs']=[job(1,'Kotlin, lint and APK')]
        for ident,name in enumerate(['Android build validation','Firebase protocol and security rules',
                                    'Native bridge validation','Isolated Android acceptance'],10):
            entry['jobs'].append(job(ident,name,conclusion='skipped'))
        entry['reused_validation_source_run']=123
        self.assertEqual(1,analyze(data)['attempt_classifications']['reused_validation'])
        entry['reused_validation_source_run']=None
        self.assertEqual(0,analyze(data)['attempt_classifications'].get('reused_validation',0))
        entry['reused_validation_source_run']=123;entry['jobs'][-1]['conclusion']='failure'
        self.assertEqual(1,analyze(data)['attempt_classifications']['failure'])

    def test_missing_times_and_ambiguous_pr_are_unknown_not_zero(self):
        data=snapshot();data['attempts'][0]['jobs'][0]['completed_at']=None
        data['pulls'].append(dict(number=11,head=copy.deepcopy(data['pulls'][0]['head'])))
        result=analyze(data)
        self.assertEqual(1,result['jobs_without_execution_time'])
        self.assertEqual(1,result['unassociated_pr_runs'])
        self.assertFalse(result['accounting_complete'])

    def test_queue_clock_inconsistency_is_missing_not_clamped(self):
        data=snapshot();data['attempts'][0]['jobs'][0]['created_at']='2026-11-12T00:02:00Z'
        result=analyze(data)
        self.assertEqual(1,result['inconsistent_job_queue_timestamps'])
        self.assertEqual(1,result['jobs_without_queue_time'])
        self.assertEqual(2,result['job_queue_seconds']['n'])

    def test_candidate_supersession_requires_temporal_overlap(self):
        data=snapshot();data['attempts'][0]['jobs'][0]['conclusion']='cancelled'
        later=copy.deepcopy(data['runs'][0]);later.update(id=2,created_at='2026-11-12T00:02:00Z')
        data['runs'].append(later)
        result=analyze(data)
        self.assertEqual(1,result['candidate_superseded_attempts'])
        self.assertEqual(4,result['candidate_superseded_runner_minutes'])
        later['created_at']='2026-11-12T00:04:00Z'
        self.assertEqual(0,analyze(data)['candidate_superseded_attempts'])

    def test_percentile_is_nearest_rank_and_empty_is_unknown(self):
        self.assertEqual(9,stats(range(1,11))['p90'])
        self.assertIsNone(stats([])['median'])

    def test_cancelled_cost_is_not_proven_supersession(self):
        data=snapshot();data['attempts'][0]['jobs'][0]['conclusion']='cancelled'
        result=analyze(data)
        self.assertEqual(4,result['cancelled_attempt_runner_minutes'])
        self.assertIn('does not prove', ' '.join(result['limitations']))

    def test_invalid_negative_timestamp_is_rejected(self):
        data=snapshot();data['attempts'][0]['jobs'][0]['completed_at']='2026-11-11T00:00:00Z'
        with self.assertRaisesRegex(ValueError,'negative'):
            analyze(data)


class CollectionTest(unittest.TestCase):
    def test_pagination_exact_count_and_cap(self):
        def read(endpoint):
            return {'total_count':3,'jobs':[{'id':1},{'id':2}]} if 'page=1' in endpoint else {'total_count':3,'jobs':[{'id':3}]}
        rows,complete=pages(read,'jobs','jobs',2,page_size=2)
        self.assertTrue(complete);self.assertEqual(3,len(rows))
        rows,complete=pages(read,'jobs','jobs',1,page_size=2)
        self.assertFalse(complete)

    def test_changing_total_duplicate_pages_are_incomplete(self):
        def read(endpoint):return {'total_count':3,'jobs':[{'id':1},{'id':2}]}
        rows,complete=pages(read,'jobs','jobs',2,page_size=2)
        self.assertFalse(complete);self.assertEqual(2,len(rows))

    def test_bounded_collection_records_api_failure_instead_of_silent_success(self):
        calls=[]
        def read(endpoint):
            calls.append(endpoint)
            if '/actions/workflows/' in endpoint:
                return {'total_count':1,'workflow_runs':[dict(id=1,run_attempt=1,created_at='2026-11-12T00:00:00Z')]}
            if '/pulls?' in endpoint:return []
            raise RuntimeError('private error text must not escape')
        data=collect(read,'owner/repo','2026-11-12T00:00:00Z','2026-11-13T00:00:00Z',max_runs=2)
        self.assertFalse(data['coverage']['complete'])
        self.assertNotIn('private error',str(data['coverage']))
        self.assertLess(len(calls),6)

    def test_collector_verifies_reuse_marker_with_bounded_plan_log(self):
        run=dict(id=1,run_attempt=1,event='push',created_at='2026-11-12T00:00:00Z')
        jobs=[job(1,'Kotlin, lint and APK'),job(2,'Select validation and check documentation')]
        for ident,name in enumerate(['Android build validation','Firebase protocol and security rules',
                                    'Native bridge validation','Isolated Android acceptance'],10):
            jobs.append(job(ident,name,conclusion='skipped'))
        def read(endpoint):
            if '/actions/workflows/' in endpoint:return {'total_count':1,'workflow_runs':[run]}
            if '/pulls?' in endpoint:return []
            if '/jobs?' in endpoint:return {'total_count':len(jobs),'jobs':jobs}
            if endpoint.endswith('/logs'):
                return 'Validation route: reused; paths: 0; verified proof; proof run: 123\n'
            return dict(run,status='completed',conclusion='success')
        data=collect(read,'owner/repo','2026-11-12T00:00:00Z','2026-11-13T00:00:00Z')
        self.assertTrue(data['coverage']['complete'])
        self.assertEqual(1,data['coverage']['log_reads'])
        summary=analyze(data)
        self.assertEqual(1,summary['attempt_classifications']['reused_validation'])
        self.assertEqual(123,summary['attempt_metrics'][0]['validation_source_run'])
        capped=collect(read,'owner/repo','2026-11-12T00:00:00Z','2026-11-13T00:00:00Z',max_logs=0)
        self.assertFalse(capped['coverage']['complete'])
        self.assertNotIn('reused_validation',analyze(capped)['attempt_classifications'])

    def test_request_budget_is_enforced_and_reported(self):
        calls=[]
        def read(endpoint):
            calls.append(endpoint)
            return {'total_count':1,'workflow_runs':[dict(id=1,run_attempt=3,created_at='2026-11-12T00:00:00Z')]}
        data=collect(read,'owner/repo','2026-11-12T00:00:00Z','2026-11-13T00:00:00Z',max_requests=1)
        self.assertEqual(1,len(calls))
        self.assertFalse(data['coverage']['complete'])
        self.assertTrue(data['coverage']['request_limit_hit'])

    def test_legacy_import_does_not_apply_latest_start_to_old_attempt(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            run=dict(id=1,path='.github/workflows/ci.yml',created_at='2026-11-12T00:00:00Z',
                     run_attempt=2,run_started_at='2026-11-12T00:05:00Z',conclusion='success')
            (root/'runs.json').write_text(json.dumps([{'total_count':1,'workflow_runs':[run]}]))
            (root/'jobs-1.json').write_text(json.dumps([{'total_count':2,'jobs':[job(1),job(2,attempt=2)]}]))
            data=import_audit(root/'runs.json',root,None,'owner/repo',
                              '2026-11-12T00:00:00Z','2026-11-13T00:00:00Z')
            self.assertTrue(data['coverage']['complete'])
            self.assertEqual({},data['attempts'][0]['metadata'])
            self.assertEqual(run['run_started_at'],data['attempts'][1]['metadata']['run_started_at'])
            (root/'jobs-1.json').unlink()
            self.assertFalse(import_audit(root/'runs.json',root,None,'owner/repo',
                '2026-11-12T00:00:00Z','2026-11-13T00:00:00Z')['coverage']['complete'])

    def test_raw_snapshot_is_private_and_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'snapshot.json'
            write_private(path,{'example':1})
            self.assertEqual(0o600,path.stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError):write_private(path,{'example':2})
            self.assertEqual({'example':1},json.loads(path.read_text()))

    def test_reversed_window_is_rejected_before_network(self):
        with self.assertRaises(ValueError):
            collect(lambda _:self.fail('network'),'owner/repo','2026-11-13T00:00:00Z','2026-11-12T00:00:00Z')
