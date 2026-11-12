"""Adversarial provenance tests; no network or Actions state is required."""
import copy
import datetime
import io
import json
import unittest
import zipfile
from test_ci_validation import Repository
from ci_validation_proof import make_proof, verify_evidence, proof_archive, direct_main, find_reusable, ActionsAPI
from ci_validation import validate_gate
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
        self.jobs = [{'name':n,'conclusion':'success'} for n in [
            'Select validation and check documentation','Repository security / Secrets and personal data guard',
            'Android build validation','Firebase protocol and security rules','Native bridge validation',
            'Isolated Android acceptance','Kotlin, lint and APK']]

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
        self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,self.jobs[:-1],'owner/repo'))
        self.assertFalse(verify_evidence(self.repo.root,self.proof,self.run,self.jobs+self.jobs,'owner/repo'))

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
        class FakeAPI(ActionsAPI):
            def __init__(self):self.repository='owner/repo'
            def get(self,path,binary=False):return payloads[path]
        env={'GITHUB_EVENT_NAME':'push','GITHUB_REF':'refs/heads/main','PUSH_BEFORE_SHA':self.base}
        self.assertEqual('123',find_reusable(self.repo.root,env,{},run_id='123',api=FakeAPI()))
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


class LookupTests(unittest.TestCase):
    def test_network_missing_metadata_and_invalid_context_fall_back_full(self):
        class BrokenAPI:
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
        for job in ['android_build','firebase','native','emulator']:
            changed=copy.deepcopy(data);changed[job]['result']='failure'
            with self.assertRaises(ValueError):validate_gate(changed,'push',{},SHA,'123')
