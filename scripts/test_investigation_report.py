import sys,unittest,json,tempfile
from pathlib import Path
from uuid import UUID
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'telemetry'))
import investigation_report as report
from export_operations import collect_window,private_write
def uid(n):return str(UUID(int=n,version=4))
def row(n,**kw):
 r=dict(message='distillation.attempt_summary',event_id=uid(n+100),summary_id=uid(n),installation_id=uid(900),schema_version=1,correlation_version=1,capture_request_id=uid(1),moment_id=uid(2),generation_attempt_id=uid(3),execution_id=uid(4),observed_at_ms=n,emitted_at_ms=n+10,outcome_version=1,stage='publication',stage_outcome='published')
 r.update(kw);return r
def export(rows,complete=True):return dict(records=rows,observation=dict(complete=complete,start='2026-01-01T00:00:00Z',end='2026-01-02T00:00:00Z',warnings=[]))
class InvestigationTest(unittest.TestCase):
 def test_success_replay(self):
  r=row(10);result=report.investigate(export([r,r]),uid(1))
  self.assertEqual('observed_published',result['publication']['state']);self.assertEqual(1,len(result['timeline']));self.assertEqual(1,result['duplicates'])
  self.assertNotIn('success_rate',json.dumps(result))
 def test_abstention_rejection_missing_terminal(self):
  for stage,outcome in [('generation','generator_needs_context'),('review','review_rejected')]:
   result=report.investigate(export([row(10,stage=stage,stage_outcome=outcome)]),uid(1))
   self.assertEqual(outcome,result['timeline'][0]['fields']['stage_outcome']);self.assertEqual('unknown',result['publication']['state'])
  self.assertEqual('unknown',report.investigate(export([row(10,message='capture.accepted')]),uid(1))['publication']['state'])
 def test_context_unknown_measurements(self):
  result=report.investigate(export([row(10,message='capture.context_summary',context_schema_version=1,context_reason='coverage_missing',coverage_measured=False,segments_measured=False,usable_segment_count=0)]),uid(1))
  self.assertIn('missing_duration_ms',result['timeline'][0]['unavailable']);self.assertIn('usable_segment_count',result['timeline'][0]['unavailable'])
 def test_retry_resume_late_summary(self):
  first=row(10,stage_outcome='request_failed',stage='generation',emitted_at_ms=1000)
  second=row(20,previous_attempt_id=uid(3),retry_trigger='explicit_retry',candidate_reused=False,model_changed=True,generation_attempt_id=uid(5),execution_id=uid(6))
  resume=row(30,retry_trigger='review_resume',candidate_reused=True,generation_attempted_calls=0,generation_elapsed_ms=-1,generation_outcome='cached',generation_attempt_id=uid(5),execution_id=uid(7))
  result=report.investigate(export([resume,second,first]),uid(1))
  self.assertEqual([10,20,30],[r['observed_at_ms'] for r in result['timeline']]);self.assertEqual(1000,result['timeline'][0]['emitted_at_ms']);self.assertEqual(2,len(result['attempts']));self.assertEqual(2,len(result['attempts'][1]['execution_ids']))
 def test_no_timestamp_transcript_legacy_join(self):
  first=row(10,transcript_revision_id=uid(50),attempt_id=uid(51))
  other=row(11,transcript_revision_id=uid(50),attempt_id=uid(51),capture_request_id=uid(60),moment_id=uid(61),generation_attempt_id=uid(62),execution_id=uid(63))
  self.assertEqual(1,len(report.investigate(export([first,other]),uid(1))['timeline']))
 def test_versions_conflicts_gaps(self):
  r=row(10,outcome_version=2);result=report.investigate(export([r],False),uid(1))
  self.assertEqual('unknown',result['publication']['state']);self.assertIn('unsupported_outcome_version',result['gaps']);self.assertIn('incomplete_export',result['gaps'])
  self.assertIn('conflicting_duplicate',report.investigate(export([r,dict(r,stage_outcome='request_failed')]),uid(1))['gaps'])
 def test_private_output(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'report.json';private_write(p,{'synthetic':True});self.assertEqual(0o600,p.stat().st_mode & 0o777)
   link=Path(d)/'link';link.symlink_to(p)
   with self.assertRaises(ValueError):private_write(link,{})
 def test_budget(self):
  result=report.investigate(export([row(10,message='diagnostics.health',budget_policy_version=2,critical_dropped=4),row(11)]),uid(1))
  self.assertIn('budget_admission_suppression_observed',result['gaps']);self.assertIn('not proof of permanent loss',result['limits'])
class Response:
 def __init__(self,data,link=''):self.data=data;self.headers={'Link':link}
 def __enter__(self):return self
 def __exit__(self,*args):pass
 def read(self):return json.dumps(self.data).encode()
class ExportTest(unittest.TestCase):
 def collect(self,**kw):return collect_window('secret','synthetic',1,'production','2026-01-01T00:00:00Z','2026-01-02T00:00:00Z',**kw)
 def test_pages_and_cap(self):
  link='<https://us.sentry.io/api/0/organizations/synthetic/events/?cursor=two>; rel="next"; results="true"'
  with patch('export_operations._open_readonly',side_effect=[Response({'data':[{'message':'capture.accepted'}]},link),Response({'data':[]})]):self.assertTrue(self.collect()['observation']['complete'])
  with patch('export_operations._open_readonly',return_value=Response({'data':[{}]},link)):
   r=self.collect(max_rows=1);self.assertFalse(r['observation']['complete']);self.assertIn('row_cap',r['observation']['warnings'])
 def test_api_failure_partial_and_bad_cursor(self):
  for response in [RuntimeError('secret'),Response({'data':[],'partial':True}),Response({'data':[]},'<https://attacker.invalid/?token=secret>; rel="next"; results="true"'),Response({'data':[]},'broken; rel="next"; results="true"')]:
   with patch('export_operations._open_readonly',side_effect=response if isinstance(response,Exception) else None,return_value=response):result=self.collect()
   self.assertFalse(result['observation']['complete']);self.assertNotIn('secret',json.dumps(result))
 def test_window(self):
  with self.assertRaises(ValueError):collect_window('secret','org',1,'production','2026-01-01T00:00:00Z','2026-02-01T00:00:00Z')

class ProducerFixtureTest(unittest.TestCase):
 def test_real_context_producer_envelopes(self):
  expected={'ready':'ready','missing':'coverage_missing','partial':'coverage_missing',
            'unknown':'ready','empty':'empty_speech','oversize':'oversized',
            'source':'source_mismatch','revision':'revision_mismatch'}
  for name,reason in expected.items():
   with self.subTest(name=name):
    items=json.loads((Path(__file__).parent/'fixtures/investigation'/('context-'+name+'.json')).read_text())
    rows=[report.normalize(i) for i in items]
    self.assertTrue(rows)
    result=report.investigate(export(rows),rows[0]['capture_request_id'])
    self.assertEqual(1,len(result['timeline']))
    self.assertEqual(reason,result['timeline'][0]['fields']['context_reason'])
    self.assertEqual('unknown',result['publication']['state'])
 def test_stage_fixture_directory_not_empty(self):
  # Added actual producer stage fixtures are consumed without inventing envelope attributes.
  files=list((Path(__file__).parent/'fixtures/investigation').glob('stage-*.json'))
  self.assertTrue(files,'Stage producer fixtures required for end-to-end acceptance')
  observed=set()
  for path in files:
   for item in json.loads(path.read_text()):
    r=report.normalize(item);observed.add(r['stage_outcome'])
    result=report.investigate(export([r]),r['capture_request_id'])
    self.assertEqual(r['stage_outcome'],result['timeline'][0]['fields']['stage_outcome'])
    if r['stage_outcome'] in ('published','published_without_quote'):
     self.assertEqual('observed_published',result['publication']['state'])
    if r.get('retry_trigger')=='review_resume':
     self.assertEqual(0,r['generation_attempted_calls']);self.assertEqual('cached',r['generation_outcome'])
  self.assertTrue({'published','generator_needs_context','review_rejected'}<=observed)

class AdditionalEvidenceTest(unittest.TestCase):
 def test_linked_transcript_not_unrelated_capture(self):
  link=row(10,message='capture.transcript_link',transcript_id=uid(50))
  transcript=row(11,message='transcription.completed',operation_id=uid(50))
  for k in report.IDS:transcript.pop(k,None)
  unrelated=row(12,capture_request_id=uid(60),moment_id=uid(61),generation_attempt_id=uid(62),execution_id=uid(63),transcript_revision_id=uid(50))
  result=report.investigate(export([link,transcript,unrelated]),uid(1))
  self.assertEqual(['capture.transcript_link','transcription.completed'],[r['event'] for r in result['timeline']])
 def test_unknown_comparison_not_false(self):
  result=report.investigate(export([row(10)]),uid(1))
  self.assertIn('model_changed',result['timeline'][0]['unavailable'])
  self.assertNotIn('model_changed',result['timeline'][0]['fields'])
 def test_replay_new_event_id_does_not_change_summary(self):
  first=row(10);later=dict(first,event_id=uid(800),emitted_at_ms=500)
  result=report.investigate(export([later,first]),uid(1))
  self.assertEqual(1,result['duplicates']);self.assertEqual(20,result['timeline'][0]['emitted_at_ms'])
 def test_legacy_unknown_and_no_installed_state_assumption(self):
  legacy=row(10,correlation_version=0)
  result=report.investigate(export([legacy]),uid(1))
  self.assertEqual('unknown',result['publication']['state'])
  self.assertIn('reference_not_observed_with_supported_correlation',result['gaps'])
 def test_future_envelope_not_publication(self):
  result=report.investigate(export([row(10,schema_version=2)]),uid(1))
  self.assertEqual('unknown',result['publication']['state'])
  self.assertIn('unsupported_envelope_version',result['gaps'])
 def test_no_content_projection(self):
  r=report.normalize(dict(row(10),transcript='sensitive',prompt='sensitive',response='sensitive'))
  self.assertNotIn('sensitive',json.dumps(r))
 def test_repeated_cursor_scope_change_page_cap(self):
  from export_operations import collect_window
  def collect(**kwargs):return collect_window('secret','synthetic',1,'production','2026-01-01T00:00:00Z','2026-01-02T00:00:00Z',**kwargs)
  for link,reason,kwargs in [
   ('<https://us.sentry.io/api/0/organizations/synthetic/events/?cursor=two>; rel="next"; results="true"','repeated_cursor',{}),
   ('<https://us.sentry.io/api/0/organizations/synthetic/events/?cursor=two&project=999>; rel="next"; results="true"','changed_pagination_scope',{}),
   ('<https://us.sentry.io/api/0/organizations/synthetic/events/?cursor=two>; rel="next"; results="true"','page_cap',dict(max_pages=1))]:
   with patch('export_operations._open_readonly',return_value=Response({'data':[]},link)):
    r=collect(**kwargs)
   self.assertFalse(r['observation']['complete']);self.assertIn(reason,r['observation']['warnings'])
 def test_redirect_refused_before_auth_forwarding(self):
  import urllib.request
  from export_operations import _NoRedirect
  request=urllib.request.Request('https://us.sentry.io/',headers={'Authorization':'Bearer synthetic'})
  self.assertIsNone(_NoRedirect().redirect_request(request,None,302,'',{},'https://other.invalid/'))

class PrivacyProjectionTest(unittest.TestCase):
 def test_allowed_field_secret_sentinels_are_not_exported(self):
  sentinel='SECRET_SENTINEL_do_not_retain'
  r=row(10,reason=sentinel,generator_model=sentinel,reviewer_model=sentinel,
    retry_trigger=sentinel,operation_release=sentinel,candidate_reused=sentinel,
    transcript_id=sentinel,generation_attempted_calls=sentinel,
    stage_outcome=sentinel,source=sentinel,context_reason=sentinel,timestamp=sentinel)
  e=export([r]);e['observation'].update(warnings=[sentinel,{}],start=sentinel,
    environment=sentinel,scope=sentinel,secret=sentinel)
  result=report.investigate(e,uid(1))
  self.assertNotIn(sentinel,json.dumps(result))
  self.assertIn('generator_model',result['timeline'][0]['unavailable'])
  self.assertIn('invalid_observation_metadata',result['gaps'])
 def test_message_and_malformed_attributes(self):
  for r in [dict(row(10),message='capture.SECRET_SENTINEL'),{'attributes':['SECRET_SENTINEL']},
            {'attributes':{'message':{'value':['SECRET_SENTINEL']}}}]:
   self.assertNotIn('SECRET_SENTINEL',json.dumps(report.normalize(r)))
 def test_future_fields_never_receive_semantic_interpretation(self):
  r=row(10,outcome_version=200,stage_outcome='SECRET_SENTINEL',reviewer_model='SECRET_SENTINEL')
  result=report.investigate(export([r]),uid(1))
  self.assertEqual('unknown',result['publication']['state'])
  self.assertNotIn('SECRET_SENTINEL',json.dumps(result))
  self.assertIn('unsupported_outcome_version',result['gaps'])
 def test_numeric_boolean_and_identity_validation(self):
  r=report.normalize(row(10,execution_id='not-a-uuid',issue_quote_count=21,
      generation_elapsed_ms=86400001,model_changed='false',context_expanded='unknown'))
  self.assertNotIn('execution_id',r);self.assertNotIn('issue_quote_count',r)
  self.assertNotIn('generation_elapsed_ms',r);self.assertIs(False,r['model_changed'])
  self.assertEqual('unknown',r['context_expanded'])

class LifecycleProducerTest(unittest.TestCase):
 def test_real_capture_cancel_resume_and_operation_summaries(self):
  items=json.loads((Path(__file__).parent/'fixtures/investigation/lifecycle.json').read_text())
  rows=[report.normalize(i) for i in items]
  reference=next(r['capture_request_id'] for r in rows if r.get('capture_request_id'))
  result=report.investigate(export(rows),reference)
  self.assertEqual('observed_saved',result['capture_acceptance']['state'])
  self.assertNotIn('capture_acceptance_unavailable',result['gaps'])
  events={r['event'] for r in result['timeline']}
  self.assertTrue({'capture.completed','capture.saved','capture.transcript_link','operation.summary'}<=events)
  self.assertNotIn('capture.accepted',events)
  self.assertEqual('observed_published',result['publication']['state'])
 def test_start_and_input_intent_do_not_prove_save(self):
  for name in ('capture.started','capture.accepted'):
   self.assertEqual('unknown',report.investigate(export([row(10,message=name)]),uid(1))['capture_acceptance']['state'])

class RealResumeTest(unittest.TestCase):
 def test_actual_quota_then_review_resume(self):
  rows=[report.normalize(i) for p in (Path(__file__).parent/'fixtures/investigation').glob('stage-*.json') for i in json.loads(p.read_text())]
  published=next(r for r in rows if r['stage_outcome']=='published' and r['retry_trigger']=='review_resume')
  result=report.investigate(export(rows),published['capture_request_id'])
  self.assertEqual(['quota','published'],[r['fields']['stage_outcome'] for r in result['timeline']])
  self.assertEqual(1,len(result['attempts']));self.assertEqual(2,len(result['attempts'][0]['execution_ids']))
  self.assertEqual(0,result['timeline'][-1]['fields']['generation_attempted_calls'])
  self.assertEqual('cached',result['timeline'][-1]['fields']['generation_outcome'])

class MissingContextGraphTest(unittest.TestCase):
 def test_actual_missing_context_does_not_join_unrelated_lifecycle(self):
  root=Path(__file__).parent/'fixtures/investigation'
  missing=[report.normalize(i) for i in json.loads((root/'context-missing.json').read_text())]
  unrelated=[report.normalize(i) for i in json.loads((root/'lifecycle.json').read_text())]
  result=report.investigate(export(missing+unrelated),missing[0]['capture_request_id'])
  self.assertEqual(['capture.context_summary'],[r['event'] for r in result['timeline']])
  self.assertEqual('coverage_missing',result['timeline'][0]['fields']['context_reason'])
  self.assertEqual('unknown',result['publication']['state'])
  self.assertEqual('unknown',result['capture_acceptance']['state'])
  self.assertIn('stage_evidence_unavailable',result['gaps'])
