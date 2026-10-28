import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('matrix',ROOT/'labs/prefusion_lot3/matrix.py')
matrix=importlib.util.module_from_spec(spec);spec.loader.exec_module(matrix)

def record(scope='HOST_SQLITE',api=0):
 return {'schema':matrix.SCHEMA,'scope':scope,'build_commit':'a'*40,'api':api,
         'abis':['host'] if api==0 else ['x86_64'],'sqlite_version':'3.46.1',
         'last_stage':'COMPLETE','status':'SUPPORTED','production_enabled':False,
         'checks':{c:'PASS' for c in matrix.CHECKS}}

class MatrixTest(unittest.TestCase):
 def test_host_and_robolectric_never_qualify_android(self):
  for scope in ['HOST_SQLITE','ROBOLECTRIC']:
   result=matrix.summarize([record(scope)])
   self.assertTrue(all(r['instrumented_sql_primitives']=='NOT_RUN' for r in result['rows']))
   self.assertEqual(result['runtime_gate'],'BLOCKED')
 def test_real_android_evidence_is_api_specific_not_rag_or_physical(self):
  result=matrix.summarize([record('ANDROID_INSTRUMENTATION',24)])
  self.assertEqual(result['rows'][0]['instrumented_sql_primitives'],'SUPPORTED')
  self.assertEqual(result['rows'][1]['instrumented_sql_primitives'],'NOT_RUN')
  self.assertFalse(result['rows'][0]['rag_saf_qualified'])
 def test_missing_or_failed_primitive_cannot_be_supported(self):
  for check in matrix.CHECKS:
   for status in ['NOT_RUN','FAIL','UNSUPPORTED']:
    r=record();r['checks'][check]=status
    with self.assertRaises(matrix.ObservationError):matrix.validate(r)
 def test_partial_unsupported_does_not_become_all_pass(self):
  r=record('ANDROID_INSTRUMENTATION',25);r['status']='UNSUPPORTED';r['last_stage']='CREATE'
  r['checks']={c:'NOT_RUN' for c in matrix.CHECKS};r['checks']['create']='UNSUPPORTED'
  self.assertEqual(matrix.summarize([r])['rows'][1]['instrumented_sql_primitives'],'UNSUPPORTED')
 def test_unknown_cannot_be_normalized_to_unsupported(self):
  r=record();r.update(status='UNKNOWN',last_stage='OPEN',sqlite_version=None)
  r['checks']={c:'NOT_RUN' for c in matrix.CHECKS}
  self.assertEqual(matrix.validate(r)['status'],'UNKNOWN')
 def test_duplicate_and_conflicting_evidence_refused(self):
  r=record()
  with self.assertRaises(matrix.ObservationError):matrix.summarize([r,copy.deepcopy(r)])
 def test_unversioned_data_untrusted_fields_and_activation_rejected(self):
  for k,v in [('schema','unknown'),('build_commit','a311379'),('production_enabled',True),('api',True)]:
   r=record();r[k]=v
   with self.assertRaises(matrix.ObservationError):matrix.validate(r)
  r=record();r['document']='SYNTHETIC_FORBIDDEN'
  with self.assertRaises(matrix.ObservationError):matrix.validate(r)
 def test_android_needs_process_abi_and_supported_api(self):
  for api,abi in [(23,['arm64-v8a']),(24,[]),(24,['host'])]:
   r=record('ANDROID_INSTRUMENTATION',api);r['abis']=abi
   with self.assertRaises(matrix.ObservationError):matrix.validate(r)
 def test_off_does_not_claim_work(self):
  r=record();r.update(status='OFF',last_stage='OPEN',sqlite_version=None)
  with self.assertRaises(matrix.ObservationError):matrix.validate(r)
  r['checks']={c:'NOT_RUN' for c in matrix.CHECKS};matrix.validate(r)
 def test_parser_refuses_duplicate_nonfinite_and_bad_unicode(self):
  for raw in ['{"a":1,"a":2}','NaN','"\\ud800"']:
   with self.assertRaises(matrix.ObservationError):matrix.strict_json(raw)
 def test_malformed_nested_values_fail_closed(self):
  for key,value in [('scope',[]),('abis',[{}]),('status',[]),('last_stage',[]),('checks',{c:[] for c in matrix.CHECKS})]:
   r=record();r[key]=value
   with self.assertRaises(matrix.ObservationError):matrix.validate(r)
 def test_bounded_cli_rejects_large_input(self):
  with tempfile.TemporaryDirectory() as d:
   p=Path(d)/'input.json';p.write_text(' '*65537)
   self.assertEqual(matrix.main([str(p)]),1)

if __name__=='__main__':unittest.main()
