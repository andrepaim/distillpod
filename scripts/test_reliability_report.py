import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('reliability_report',Path(__file__).parent/'telemetry/reliability_report.py')
report=importlib.util.module_from_spec(spec);spec.loader.exec_module(report)
class ReliabilityReportTest(unittest.TestCase):
 def test_retries_unknown_waits_and_duplicates_are_not_successes(self):
  def row(job,seq,outcome,attempt='a',test=False):
   return dict(summary_id=job+str(seq),installation_id='device',feature='transcription',operation_id=job,sequence=seq,outcome=outcome,attempt_id=attempt,test_traffic=test,schema_version=1)
  rows=[row('retry',1,'failed'),row('retry',2,'running','b'),row('retry',3,'completed','b'),row('wait',1,'waiting'),row('unknown',1,'unknown'),row('failure',1,'failed'),row('test',1,'failed',test=True)]
  rows+=rows[:3]
  result=report.summarize(rows)['features']['transcription']
  self.assertEqual(4,result['observed_jobs']);self.assertEqual(3,result['observed_attempts']);self.assertEqual(1,result['queued_only_attempt_ids']);self.assertEqual(1,result['unknown_only_attempt_ids'])
  self.assertEqual(.5,result['resolved_success_rate']);self.assertEqual(1,result['recovered_jobs'])
 def test_empty_sample_has_no_success_rate(self):
  self.assertEqual({},report.summarize([])['features'])

 def test_partial_transcript_is_visible_without_a_completed_success(self):
  row=dict(summary_id='partial',installation_id='device',feature='transcription',operation_id='job',sequence=1,outcome='partial',attempt_id='attempt',test_traffic=False,schema_version=1)
  f=report.summarize([row])['features']['transcription']
  self.assertEqual(1,f['outcomes']['partial']);self.assertIsNone(f['resolved_success_rate']);self.assertEqual(1,f['observed_attempts'])
