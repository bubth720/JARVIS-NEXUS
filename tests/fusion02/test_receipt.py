import copy
import importlib.util
import json
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('receipt',ROOT/'tools/check-fusion02.py')
receipt=importlib.util.module_from_spec(spec);spec.loader.exec_module(receipt)
class ReceiptTest(unittest.TestCase):
    def setUp(self):
        self.v=json.loads((ROOT/'docs/fusion-02/VALIDATION.json').read_text())
        self.p=json.loads((ROOT/'docs/fusion-02/PROVENANCE.json').read_text())
    def test_genuine(self):self.assertFalse(receipt.verify(ROOT)['androidRagGo'])
    def reject(self):
        with self.assertRaises(ValueError):receipt.verify(ROOT,self.v,self.p)
    def test_global_go_refused(self):self.v['overall']='READY';self.reject()
    def test_native_activation_refused(self):self.v['activation']['externalSqlite']=True;self.reject()
    def test_unknown_activation_refused(self):self.v['activation']['ipc']='UNKNOWN';self.reject()
    def test_invented_counts_refused(self):self.v['jvm']['tests']+=1;self.reject()
    def test_evidence_tamper_refused(self):self.p['evidence'][next(iter(self.p['evidence']))]='0'*64;self.reject()
    def test_source_tamper_refused(self):self.p['sources'][next(iter(self.p['sources']))]='0'*64;self.reject()
    def test_unqualified_rag_refused(self):self.v['android']['24']['rag']='PASS';self.reject()
    def test_wrong_parent_refused(self):self.p['base']='0'*40;self.reject()
    def test_physical_go_refused(self):self.v['android']['24']['physical']='PASS';self.reject()
if __name__=='__main__':unittest.main()
