"""Adversarial provenance tests; no network or Actions state is required."""
import copy
import datetime
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from test_ci_validation import Repository
from ci_validation_proof import make_proof, verify_evidence, proof_archive, direct_main, find_reusable, ActionsAPI, check_source, StaleBase
from ci_validation import choose_route, validate_gate
from test_ci_validation import results, SHA


class ProofTests(unittest.TestCase):
    def setUp(self):
        self.repo = Repository({'.github/workflows/ci.yml': 'workflow\n'})
        self.addCleanup(self.repo.__exit__)
        self.base = self.repo.base
        self.repo.write('app/code.kt', 'new code\n')
        self.head = self.repo.commit('head')
        tree = self.repo.git('rev-parse', 'HEAD^{tree}')
        self.checkout = self.repo.git('commit-tree', tree, '-p', self.base, '-p', self.head, input='merge\n')
        self.repo.git('reset', '--hard', self.checkout)
        self.env = {'GITHUB_REPOSITORY':'owner/repo','GITHUB_RUN_ID':'123',
                    'GITHUB_RUN_ATTEMPT':'1','GITHUB_EVENT_NAME':'pull_request','GITHUB_SHA':self.checkout,
                    'PR_BASE_SHA':self.base,'PR_HEAD_SHA':self.head}
        self.proof = make_proof(self.repo.root, self.env)
        self.run = {'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat(), 'id':123,'run_attempt':1,'event':'pull_request','status':'completed',
                    'conclusion':'success','head_sha':self.head,'path':'.github/workflows/ci.yml',
                    'repository':{'full_name':'owner/repo'},'head_repository':{'full_name':'owner/repo'}}
        self.jobs = [{'name':n,'conclusion':'success','steps':[{'name':'Verify validation source','conclusion':'success'}]} for n in [
            'Select validation and check documentation','Repository security / Secrets and personal data guard',
            'Android build validation','Opt-in voice build validation','Firebase protocol and security rules',
            'Native bridge validation','Isolated Android acceptance','Isolated Android acceptance, part 2',
            'Kotlin, lint and APK']]
        self.jobs.append({'name':'Unsigned release build','conclusion':'skipped','steps':[]})

    def test_clean_but_wrong_checkout_cannot_pass_job_source_guard(self):
        self.assertIsNone(check_source(self.repo.root,self.checkout))
        with self.assertRaises(ValueError):check_source(self.repo.root,self.head)

    def test_dirty_tracked_or_nonignored_untracked_source_cannot_emit_proof(self):
        self.assertIsNone(check_source(self.repo.root))
        self.repo.write('app/code.kt','untested change\n')
        with self.assertRaises(ValueError):make_proof(self.repo.root,self.env)
        self.repo.git('reset','--hard',self.checkout)
        self.repo.write('app/extra.kt','untracked input\n')
        with self.assertRaises(ValueError):check_source(self.repo.root)

    def test_identical_merge_tree_with_successful_full_provenance_passes(self):
        # The real merge SHA may differ from the tested synthetic merge SHA.
        tree = self.repo.git('rev-parse', 'HEAD^{tree}')
        merged = self.repo.git('commit-tree', tree, '-p', self.base, '-p', self.head, input='different message\n')
        self.repo.git('reset','--hard',merged)
        self.assertTrue(verify_evidence(self.repo.root,self.proof,self.run,self.jobs,'owner/repo'))

    def test_every_source_and_workflow_identity_must_match(self):
        for field in ['tree','checkout_sha','workflow_sha','head','base','repository','run_id','run_attempt','mode']:
            p = copy.deepcopy(self.proof)
            p[field] = 'invalid'
            with self.subTest(field=field):
                self.assertFalse(verify_evidence(self.repo.root,p,self.run,self.jobs,'owner/repo'))
        self.repo.write('app/code.kt','changed after validation\n')
        self.repo.commit('intervening code')
        self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,self.jobs,'owner/repo'))

    def test_failed_incomplete_foreign_manual_and_rerun_evidence_never_pass(self):
        for field,value in [('created_at',None),('created_at',123),('created_at','2000-01-01T00:00:00Z'),('conclusion','failure'),('status','in_progress'),('event','workflow_dispatch'),
                            ('head_sha','b'*40),('run_attempt',2),('path','.github/workflows/other.yml'),
                            ('repository',{'full_name':'other/repo'}),('head_repository',{'full_name':'fork/repo'})]:
            r = copy.deepcopy(self.run);r[field]=value
            with self.subTest(field=field):
                self.assertFalse(verify_evidence(self.repo.root,self.proof,r,self.jobs,'owner/repo'))
        for conclusion in ['failure','skipped','cancelled',None]:
            j=copy.deepcopy(self.jobs);j[2]['conclusion']=conclusion
            self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,j,'owner/repo'))
        missing_guard=copy.deepcopy(self.jobs);missing_guard[2]['steps']=[]
        self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,missing_guard,'owner/repo'))
        self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,self.jobs[:-1],'owner/repo'))
        self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,self.jobs+self.jobs,'owner/repo'))

    def test_every_parallel_build_job_needs_success_and_its_source_guard(self):
        for name in ['Opt-in voice build validation','Isolated Android acceptance, part 2',
                     'Android build validation','Isolated Android acceptance']:
            index=[j['name'] for j in self.jobs].index(name)
            for change in [dict(conclusion='failure'),dict(conclusion='cancelled'),dict(conclusion='skipped'),
                           dict(steps=[]),dict(steps=[{'name':'Verify validation source','conclusion':'failure'}])]:
                j=copy.deepcopy(self.jobs);j[index].update(change)
                with self.subTest(name=name,change=change):
                    self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,j,'owner/repo'))
            with self.subTest(name=name,missing=True):
                self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,
                                                 [j for j in self.jobs if j['name']!=name],'owner/repo'))

    def test_release_candidate_job_must_be_present_and_skipped_in_reused_pr_evidence(self):
        index=[j['name'] for j in self.jobs].index('Unsigned release build')
        for conclusion in ['success','failure','cancelled',None]:
            j=copy.deepcopy(self.jobs);j[index]['conclusion']=conclusion
            with self.subTest(conclusion=conclusion):
                self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,j,'owner/repo'))
        self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,
                                         [j for j in self.jobs if j['name']!='Unsigned release build'],'owner/repo'))

    def test_cache_refresh_is_required_when_recent_main_has_only_reused_jobs(self):
        run=dict(self.run,id=789,event='push',head_branch='main')
        jobs=[{'name':name,'conclusion':'skipped','completed_at':self.run['created_at']}
              for name in ['Android build validation','Isolated Android acceptance']]
        class CacheAPI(ActionsAPI):
            def __init__(self):self.repository='owner/repo'
            def get(self,path,binary=False):
                if 'workflow_runs' not in path and '/runs?' in path:return {'workflow_runs':[run]}
                return {'total_count':2,'jobs':jobs}
        api=CacheAPI()
        self.assertFalse(api.cache_is_warm())
        for job in jobs:job['conclusion']='success'
        self.assertTrue(api.cache_is_warm())
        jobs[0]['completed_at']='2000-01-01T00:00:00Z'
        self.assertFalse(api.cache_is_warm())

    def test_only_direct_single_merge_main_pushes_can_reuse(self):
        env={'GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main','PUSH_BEFORE_SHA':self.base}
        self.assertTrue(direct_main(self.repo.root, env, {}))
        for event,ref,inputs in [('workflow_dispatch','refs/heads/main',{}),
                                 ('push','refs/tags/v1',{}),('push','refs/heads/main',{'build_release':False})]:
            self.assertFalse(direct_main(self.repo.root,dict(env,GITHUB_EVENT_NAME=event,GITHUB_REF=ref),inputs))
        self.assertFalse(direct_main(self.repo.root,dict(env,PUSH_BEFORE_SHA='b'*40),{}))

    def test_api_artifact_and_job_coverage_are_required_and_lookup_reverifies(self):
        output=io.BytesIO()
        with zipfile.ZipFile(output,'w') as z:z.writestr('proof.json',json.dumps(self.proof))
        payloads = {
            'actions/runs/123': self.run,
            'actions/runs/123/artifacts?per_page=100': {'total_count':1,'artifacts':[
                {'name':'ci-validation-proof-1','id':456,'expired':False,'size_in_bytes':len(output.getvalue())}]},
            'actions/artifacts/456/zip': output.getvalue(),
            'actions/runs/123/attempts/1/jobs?per_page=100': {'total_count':len(self.jobs),'jobs':self.jobs},
            f'git/commits/{self.checkout}': {'sha':self.checkout,'tree':{'sha':self.proof['tree']},
                                           'parents':[{'sha':self.base},{'sha':self.head}]}}
        payloads['actions/workflows/ci.yml/runs?event=push&branch=main&status=success&per_page=5'] = {
            'workflow_runs':[dict(self.run,id=789,event='push',head_branch='main')]}
        payloads['actions/runs/789/attempts/1/jobs?per_page=100'] = {
            'total_count':2,'jobs':[dict(j,completed_at=self.run['created_at']) for j in self.jobs
                                  if j['name'] in {'Android build validation','Isolated Android acceptance'}]}
        payloads[f'actions/workflows/ci.yml/runs?event=pull_request&head_sha={self.head}&per_page=5'] = {'workflow_runs':[self.run]}
        class FakeAPI(ActionsAPI):
            def __init__(self):self.repository='owner/repo'
            def get(self,path,binary=False):return payloads[path]
        env={'GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main','PUSH_BEFORE_SHA':self.base}
        self.assertEqual('123',find_reusable(self.repo.root,env,{},run_id='123',api=FakeAPI()))
        latest_path=f'actions/workflows/ci.yml/runs?event=pull_request&head_sha={self.head}&per_page=5'
        for status,conclusion in [('completed','failure'),('in_progress',None),('completed','cancelled')]:
            payloads[latest_path]={'workflow_runs':[dict(self.run,id=124,status=status,conclusion=conclusion),self.run]}
            self.assertEqual('',find_reusable(self.repo.root,env,{},run_id='123',api=FakeAPI()))
            self.assertEqual('',find_reusable(self.repo.root,env,{},api=FakeAPI()))
        payloads[latest_path]={'workflow_runs':[self.run]}

        artifact=payloads['actions/runs/123/artifacts?per_page=100']['artifacts'][0]
        artifact['expired']=True
        self.assertEqual('',find_reusable(self.repo.root,env,{},run_id='123',api=FakeAPI()))
        artifact['expired']=False
        payloads['actions/runs/123/attempts/1/jobs?per_page=100']['total_count']+=1
        self.assertEqual('',find_reusable(self.repo.root,env,{},run_id='123',api=FakeAPI()))

    def test_archive_is_bounded_and_contains_only_expected_file(self):
        def archive(files):
            output=io.BytesIO()
            with zipfile.ZipFile(output,'w') as z:
                for name,data in files:z.writestr(name,data)
            return output.getvalue()
        self.assertEqual(self.proof,proof_archive(archive([('proof.json',json.dumps(self.proof))])))
        for files in [[('../proof.json','{}')],[('proof.json','x'*40000)],
                      [('proof.json','{}'),('other','private')],[('proof.json','not json')]]:
            with self.assertRaises((ValueError,KeyError)):
                proof_archive(archive(files))


GATE = Path(__file__).resolve().with_name('ci_validation.py')


class StaleBaseTests(unittest.TestCase):
    """GitHub tests a PR against current main, which can be newer than the event's base."""
    def setUp(self):
        self.repo = Repository({'.github/workflows/ci.yml': 'workflow\n'})
        self.addCleanup(self.repo.__exit__)
        self.old_base = self.repo.base
        self.repo.write('app/code.kt', 'new code\n')
        self.head = self.repo.commit('head')
        self.repo.git('reset', '--hard', self.old_base)
        self.repo.write('app/other.kt', 'merged meanwhile\n')
        self.main = self.repo.commit('intervening main')
        self.repo.git('merge', '-q', '--no-ff', '--no-commit', self.head)
        tree = self.repo.git('write-tree')
        self.checkout = self.repo.git('commit-tree', tree, '-p', self.main, '-p', self.head, input='merge\n')
        self.repo.git('reset', '--hard', self.checkout)
        self.env = {'GITHUB_REPOSITORY': 'owner/repo', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1',
                    'GITHUB_EVENT_NAME': 'pull_request', 'GITHUB_SHA': self.checkout,
                    'PR_BASE_SHA': self.old_base, 'PR_HEAD_SHA': self.head}

    def assertFatal(self, env):
        with self.assertRaises(ValueError) as caught:
            make_proof(self.repo.root, env)
        self.assertNotIsInstance(caught.exception, StaleBase)

    def test_event_base_behind_tested_main_is_a_distinct_no_proof_outcome(self):
        with self.assertRaisesRegex(StaleBase, 'behind main'):
            make_proof(self.repo.root, self.env)
        self.assertEqual(self.main, make_proof(self.repo.root, dict(self.env, PR_BASE_SHA=self.main))['base'])

    def test_every_other_proof_failure_with_a_stale_base_stays_fatal(self):
        for field, value in [('PR_HEAD_SHA', self.old_base), ('GITHUB_SHA', self.head),
                             ('GITHUB_EVENT_NAME', 'push'), ('PR_BASE_SHA', 'f' * 40),
                             ('PR_BASE_SHA', None), ('PR_BASE_SHA', '--all')]:
            with self.subTest(field=field, value=value):
                self.assertFatal(dict(self.env, **{field: value}))
        side = self.repo.git('commit-tree', self.repo.git('rev-parse', f'{self.old_base}^{{tree}}'),
                             '-p', self.old_base, input='unrelated side commit\n')
        self.assertFatal(dict(self.env, PR_BASE_SHA=side))
        self.repo.write('app/code.kt', 'untested change\n')
        self.assertFatal(self.env)
        self.repo.git('reset', '--hard', self.checkout)
        self.repo.write('app/extra.kt', 'untracked input\n')
        self.assertFatal(self.env)
        (self.repo.root / 'app/extra.kt').unlink()
        tree = self.repo.git('rev-parse', 'HEAD^{tree}')
        for parents in [[self.main], [self.main, self.head, self.old_base], [self.head, self.main]]:
            commit = self.repo.git('commit-tree', tree, *[a for p in parents for a in ('-p', p)], input='other\n')
            self.repo.git('reset', '--hard', commit)
            with self.subTest(parents=len(parents)):
                self.assertFatal(dict(self.env, GITHUB_SHA=commit))

    def gate(self, **env):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        work = Path(temp.name)
        needs = results()
        needs['plan']['outputs']['checkout_sha'] = self.checkout
        variables = dict(os.environ, **self.env, VALIDATION_NEEDS=json.dumps(needs),
                         WORKFLOW_INPUTS_CONTEXT='{}', GITHUB_OUTPUT=str(work / 'output'),
                         CI_PROOF_OUTPUT=str(work / 'proof' / 'proof.json'))
        variables.update(env)
        (work / 'output').write_text('')
        completed = subprocess.run([sys.executable, str(GATE), 'gate'], cwd=self.repo.root, env=variables,
                                   capture_output=True, text=True)
        return completed, (work / 'output').read_text(), work / 'proof' / 'proof.json'

    def test_gate_passes_with_a_notice_and_no_proof_when_the_base_is_behind_main(self):
        completed, output, proof = self.gate()
        self.assertEqual(0, completed.returncode, completed.stdout)
        self.assertRegex(completed.stdout, r'(?m)^::notice title=[^:]*::.*behind main.*validated in full on main')
        self.assertFalse(proof.exists())
        self.assertNotIn('proof_written=true', output)

    def test_gate_writes_and_announces_proof_only_for_the_exact_event_base(self):
        completed, output, proof = self.gate(PR_BASE_SHA=self.main)
        self.assertEqual(0, completed.returncode, completed.stdout)
        self.assertEqual(self.main, json.loads(proof.read_text())['base'])
        self.assertIn('proof_written=true\n', output)
        self.assertNotIn('::notice', completed.stdout)

    def test_gate_still_fails_other_proof_mismatches(self):
        completed, output, proof = self.gate(PR_HEAD_SHA=self.old_base)
        self.assertEqual(1, completed.returncode)
        self.assertIn('Only a verified PR merge checkout', completed.stdout)
        self.assertFalse(proof.exists())
        self.assertNotIn('proof_written=true', output)


class LookupTests(unittest.TestCase):
    def test_main_push_without_a_proof_artifact_selects_fresh_full_validation(self):
        # A stale-base PR run uploads no proof; its merge must not be reused on main.
        with Repository({'.github/workflows/ci.yml': 'workflow\n'}) as repo:
            base = repo.base
            head = repo.git('commit-tree', repo.git('rev-parse', 'HEAD^{tree}'), '-p', base, input='head\n')
            merge = repo.git('commit-tree', repo.git('rev-parse', 'HEAD^{tree}'), '-p', base, '-p', head, input='merge\n')
            repo.git('reset', '--hard', merge)
            now = datetime.datetime.now(datetime.timezone.utc).isoformat()
            pr_run = {'id': 123, 'run_attempt': 1, 'event': 'pull_request', 'status': 'completed',
                      'conclusion': 'success', 'head_sha': head, 'created_at': now}
            main_run = {'id': 789, 'run_attempt': 1, 'event': 'push', 'head_branch': 'main', 'conclusion': 'success',
                        'path': '.github/workflows/ci.yml', 'repository': {'full_name': 'owner/repo'}, 'created_at': now}
            payloads = {
                'actions/workflows/ci.yml/runs?event=push&branch=main&status=success&per_page=5': {'workflow_runs': [main_run]},
                'actions/runs/789/attempts/1/jobs?per_page=100': {'total_count': 2, 'jobs': [
                    {'name': n, 'conclusion': 'success', 'completed_at': now}
                    for n in ['Android build validation', 'Isolated Android acceptance']]},
                f'actions/workflows/ci.yml/runs?event=pull_request&head_sha={head}&per_page=5': {'workflow_runs': [pr_run]},
                'actions/runs/123': pr_run,
                'actions/runs/123/artifacts?per_page=100': {'total_count': 0, 'artifacts': []}}
            reads = []
            class NoProofAPI(ActionsAPI):
                def __init__(self): self.repository = 'owner/repo'
                def get(self, path, binary=False): reads.append(path); return payloads[path]
            env = {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/main', 'PUSH_BEFORE_SHA': base}
            self.assertEqual('', find_reusable(repo.root, env, {}, api=NoProofAPI()))
            self.assertEqual('', find_reusable(repo.root, env, {}, run_id='123', api=NoProofAPI()))
            self.assertIn('actions/runs/123/artifacts?per_page=100', reads)
            self.assertEqual('full', choose_route(repo.root, 'push', {}, None, None).mode)


    def test_network_missing_metadata_and_invalid_context_fall_back_full(self):
        class BrokenAPI:
            def cache_is_warm(self):
                raise OSError
            def get(self, path):
                raise OSError('private-body-must-not-be-printed')
        with Repository() as repo:
            base = repo.base
            head = repo.git('commit-tree',repo.git('rev-parse','HEAD^{tree}'),'-p',base,input='head\n')
            merge = repo.git('commit-tree',repo.git('rev-parse','HEAD^{tree}'),'-p',base,'-p',head,input='merge\n')
            repo.git('reset','--hard',merge)
            env={'GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main','PUSH_BEFORE_SHA':base}
            self.assertEqual('',find_reusable(repo.root,env,{},api=BrokenAPI()))
            self.assertEqual('',find_reusable(repo.root,env,{},run_id='unsafe/path',api=BrokenAPI()))
            self.assertEqual('',find_reusable(repo.root,env,{'build_release':False},api=BrokenAPI()))

    def test_reused_gate_requires_independently_reverified_run_and_exact_skips(self):
        data=results('docs')
        data['plan']['outputs'].update(mode='reused',reuse_run_id='123',docs_validated='not_selected')
        data['security']['result']='skipped'
        self.assertIsNone(validate_gate(data,'push',{},SHA,'123'))
        for event,inputs,verified in [('push',{},''),('pull_request',{},'123'),
                                      ('push',{'build_release':False},'123')]:
            with self.assertRaises(ValueError):validate_gate(data,event,inputs,SHA,verified)
        for job in ['android_build','voice_build','firebase','native','emulator','emulator_part2','release_build']:
            changed=copy.deepcopy(data);changed[job]['result']='failure'
            with self.assertRaises(ValueError):validate_gate(changed,'push',{},SHA,'123')
