import hashlib
from pathlib import Path
import sqlite3
import tempfile
import unittest
from sqlite_lab import document, graph_edge, graph_node, migrations, open_db, search


class SchemaTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.path=Path(self.temp.name)/'nexus.db';self.db=open_db(self.path)
        with self.db:
            self.db.executemany('INSERT INTO collection VALUES(?,?)',[('A','First'),('B','Second')])
            self.a,self.text,self.sha=document(self.db,0);self.b,_,_=document(self.db,1);document(self.db,0,'B')

    def tearDown(self):
        self.db.close();self.temp.cleanup()

    def test_actual_ddl_migration_v3_preserves_existing_documents_and_fts(self):
        old=Path(self.temp.name)/'v3.db';db=open_db(old,3)
        db.execute("INSERT INTO collection VALUES('old','Old')")
        text='science legacy';sha=hashlib.sha256(text.encode()).hexdigest()
        db.execute("INSERT INTO source VALUES('s','synthetic:old',?,?)",(sha,len(text)))
        db.execute("INSERT INTO document VALUES('d','old','synthetic:old','Old',1,0,100)")
        db.execute("INSERT INTO revision VALUES('d',1,'s','Old')")
        db.execute("INSERT INTO fragment VALUES('d',1,0,?,0,?,NULL)",(text,len(text)))
        db.execute("INSERT INTO index_state VALUES('d',1,'READY')")
        db.execute("INSERT INTO fragment_fts(document_id,version,ordinal,body) VALUES('d',1,0,?)",(text,))
        db.commit();db.close()
        db=open_db(old)
        self.assertEqual([('old','Old')],db.execute('SELECT * FROM collection').fetchall())
        self.assertEqual([('science legacy',)],db.execute("SELECT body FROM fragment_fts WHERE fragment_fts MATCH 'science'").fetchall())
        self.assertEqual((sha,),db.execute('SELECT sha256 FROM source').fetchone())
        self.assertEqual((4,),db.execute('PRAGMA user_version').fetchone());db.close()

    def test_phrase_scope_type_and_source_indices(self):
        rows=search(self.db)
        self.assertEqual([self.a],[r[0] for r in rows])
        self.assertEqual(['B000000'],[r[0] for r in search(self.db,collection='B')])
        self.assertEqual(2,len(search(self.db,query='"mécanique" AND "quantique"')))

    def test_citation_offsets_and_hash_match_stored_source(self):
        text,start,end,sha=self.db.execute('SELECT f.text,f.start_offset,f.end_offset,s.sha256 FROM fragment f JOIN source s ON s.id=f.document_id WHERE f.document_id=?',(self.a,)).fetchone()
        self.assertEqual(text,text[start:end]);self.assertEqual(sha,hashlib.sha256(text.encode()).hexdigest())

    def test_cross_collection_nodes_and_edges_refused(self):
        with self.assertRaises(sqlite3.IntegrityError):graph_node(self.db,'bad',self.a,'B')
        graph_node(self.db,'na',self.a);graph_node(self.db,'nb','B000000','B')
        with self.assertRaises(sqlite3.IntegrityError):graph_edge(self.db,'bad','na','nb',self.a,self.text)

    def test_rejected_edge_and_optimistic_edit(self):
        graph_node(self.db,'a',self.a);graph_node(self.db,'b',self.b)
        graph_edge(self.db,'e','a','b',self.a,self.text,origin='SUGGESTED')
        self.assertEqual(('SUGGESTED','ACTIVE'),self.db.execute('SELECT origin,state FROM graph_edge').fetchone())
        first=self.db.execute("UPDATE graph_edge SET state='REJECTED',version=version+1 WHERE id='e' AND version=1").rowcount
        stale=self.db.execute("UPDATE graph_edge SET state='ACTIVE' WHERE id='e' AND version=1").rowcount
        self.assertEqual((1,0),(first,stale))

    def test_transaction_interruption_and_restart(self):
        self.db.commit()
        try:
            with self.db:
                graph_node(self.db,'interrupted',self.a)
                raise RuntimeError('injected interruption before commit')
        except RuntimeError:pass
        self.db.close();self.db=open_db(self.path)
        self.assertFalse(self.db.execute("SELECT id FROM graph_node WHERE id='interrupted'").fetchall())
        self.assertEqual(1,len(search(self.db)))

    def test_keyset_graph_pagination_has_no_duplicate_or_missing_node(self):
        for i in range(520):graph_node(self.db,f'n{i:04}',self.a)
        cursor='';seen=[]
        while True:
            rows=self.db.execute('SELECT id FROM graph_node WHERE collection_id=? AND id>? ORDER BY id LIMIT 200',('A',cursor)).fetchall()
            if not rows:break
            seen.extend(r[0] for r in rows);cursor=rows[-1][0]
        self.assertEqual(520,len(set(seen)));self.assertEqual(520,len(seen))

    def test_soft_delete_hides_fts_and_keeps_original_provenance(self):
        self.db.execute('UPDATE document SET deleted=1 WHERE id=?',(self.a,))
        self.assertFalse(search(self.db));self.assertEqual((self.sha,),self.db.execute('SELECT sha256 FROM source WHERE id=?',(self.a,)).fetchone())

    def test_sqlite_backup_preview_is_isolated_not_crypto_qualification(self):
        self.db.commit();before=self.path.read_bytes();copy=Path(self.temp.name)/'isolated.db'
        target=sqlite3.connect(copy);self.db.backup(target)
        self.assertEqual((3,),target.execute('SELECT count(*) FROM document').fetchone())
        target.execute('DELETE FROM graph_edge');target.commit();target.close()
        self.assertEqual(before,self.path.read_bytes())

    def test_migration_transaction_rolls_back_on_failure(self):
        empty=sqlite3.connect(':memory:')
        try:
            with empty:
                empty.execute('BEGIN')
                for statement in migrations()[1]:empty.execute(statement)
                empty.execute('INVALID injected SQL')
        except sqlite3.Error:pass
        self.assertFalse(empty.execute("SELECT name FROM sqlite_master WHERE name='collection'").fetchall());empty.close()


if __name__=='__main__':unittest.main(verbosity=2)
