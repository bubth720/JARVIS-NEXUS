"""Execute production migration DDL against real SQLite. Not a Kotlin importer.

Benchmarks deliberately isolate storage/FTS/graph SQL; they do not measure the
unavailable JVM toolchain, extraction, Android or Wikipedia.
"""
import hashlib
import json
from pathlib import Path
import re
import resource
import sqlite3
import statistics
import tempfile
import time
import tracemalloc

ROOT = Path(__file__).resolve().parents[2]


def migrations():
    source = (ROOT / 'nexus-core/src/main/kotlin/fr/jarvis/nexus/store/NexusDatabase.kt').read_text()
    result = {}
    for match in re.finditer(r'private val v(\d+) = listOf\(', source):
        pos = match.end(); level = 1; strings = []
        while level:
            ch = source[pos]
            if ch == '"':
                end = pos + 1
                while True:
                    if source[end] == '\\': end += 2; continue
                    if source[end] == '"': break
                    end += 1
                strings.append(json.loads(source[pos:end+1])); pos = end
            elif ch == '(': level += 1
            elif ch == ')': level -= 1
            pos += 1
        result[int(match.group(1))] = strings
    assert sorted(result) == [1, 2, 3, 4]
    return result


def open_db(path, target=4):
    db = sqlite3.connect(path)
    db.execute('PRAGMA foreign_keys=ON')
    version = db.execute('PRAGMA user_version').fetchone()[0]
    assert version <= target
    with db:
        for number, statements in sorted(migrations().items()):
            if version < number <= target:
                for statement in statements: db.execute(statement)
        db.execute(f'PRAGMA user_version={target}')
    return db


def document(db, number, collection='A'):
    key = f'{collection}{number:06}'
    text = f'Document synthétique {number}. ' + ('La mécanique quantique décrit les atomes.' if number % 2 == 0 else 'La mécanique décrit un modèle quantique.')
    raw = text.encode(); sha = hashlib.sha256(raw).hexdigest()
    uri = 'synthetic:' + key
    db.execute('INSERT INTO source VALUES(?,?,?,?)', (key, uri, sha, len(raw)))
    db.execute('INSERT INTO document VALUES(?,?,?,?,1,0,?)', (key, collection, uri, key, 1_800_000_000_000))
    db.execute('INSERT INTO revision VALUES(?,1,?,?)', (key, key, key))
    db.execute('INSERT INTO fragment VALUES(?,1,0,?,0,?,NULL)', (key, text, len(text)))
    db.execute("INSERT INTO index_state VALUES(?,1,'READY')", (key,))
    db.execute('INSERT INTO fragment_fts(document_id,version,ordinal,body) VALUES(?,1,0,?)', (key, text))
    db.execute('INSERT INTO document_metadata VALUES(?,?,?,?,?,?,?)', (key,key+'.csv','csv',len(raw),1_800_000_000_000,'Sciences','physique\nlocal'))
    db.execute('INSERT INTO fragment_location VALUES(?,1,0,?)', (key,'Synthetic source; UTF-16 offsets'))
    return key, text, sha


def graph_node(db, key, doc, collection='A'):
    db.execute("INSERT INTO graph_node VALUES(?,?,'DOCUMENT',?,'Sciences',?)", (key, collection, key, doc))


def graph_edge(db, key, a, b, doc, text, collection='A', origin='EXPLICIT'):
    digest = hashlib.sha256(text.encode()).hexdigest()
    db.execute("INSERT INTO graph_edge VALUES(?,?,?,?,?,?, 'ACTIVE',1,?,1,0,0,?,?)", (key,collection,a,b,'RELATED_TO',origin,doc,len(text),digest))


def search(db, query='"mécanique quantique"', collection='A', limit=20):
    return db.execute("SELECT f.document_id,f.text,bm25(fragment_fts) FROM fragment_fts x JOIN fragment f ON f.document_id=x.document_id AND f.version=x.version AND f.ordinal=x.ordinal JOIN document d ON d.id=f.document_id JOIN index_state i ON i.document_id=d.id JOIN document_metadata m ON m.document_id=d.id WHERE fragment_fts MATCH ? AND d.collection_id=? AND m.media_type='csv' AND d.deleted=0 AND d.version=f.version AND i.state='READY' AND i.indexed_version=d.version ORDER BY bm25(fragment_fts),f.document_id LIMIT ?", (query,collection,limit)).fetchall()


def benchmark(documents=5000):
    with tempfile.TemporaryDirectory(prefix='nexus-final-sql-') as tmp:
        path = Path(tmp)/'synthetic.sqlite'; db = open_db(path)
        tracemalloc.start(); started = time.perf_counter()
        with db:
            db.execute("INSERT INTO collection VALUES('A','Synthetic benchmark')")
            for i in range(documents):
                key,text,_ = document(db,i)
                graph_node(db,'n'+key,key)
                if i:
                    for distance in range(1,min(5,i)+1):
                        graph_edge(db,f'e{i:06}-{distance}', 'n'+key, f'nA{i-distance:06}', key,text,origin='SUGGESTED' if distance%2 else 'EXPLICIT')
        ingest_seconds = time.perf_counter()-started
        started=time.perf_counter(); first=search(db); cold=time.perf_counter()-started
        samples=[]
        for _ in range(50):
            started=time.perf_counter(); search(db); samples.append(time.perf_counter()-started)
        graph_samples=[]
        for n in (20,100,250):
            started=time.perf_counter(); rows=db.execute('SELECT id,label FROM graph_node WHERE collection_id=? AND id>? ORDER BY id LIMIT ?',('A','',n)).fetchall()
            graph_samples.append({'limit':n,'returned':len(rows),'seconds':time.perf_counter()-started})
        current,peak=tracemalloc.get_traced_memory();tracemalloc.stop()
        assert db.execute('PRAGMA integrity_check').fetchone()==('ok',)
        assert not db.execute('PRAGMA foreign_key_check').fetchall()
        result={'measurement':'Python harness / production SQLite schema and FTS5; not JVM import or Android','sqlite':sqlite3.sqlite_version,'documents':documents,'nodes':db.execute('SELECT count(*) FROM graph_node').fetchone()[0],'edges':db.execute('SELECT count(*) FROM graph_edge').fetchone()[0],'storage_population_seconds':ingest_seconds,'first_query_seconds':cold,'query_p50_seconds':statistics.median(samples),'query_p95_seconds':sorted(samples)[47],'query_returned':len(first),'database_bytes':path.stat().st_size,'python_peak_tracemalloc_bytes':peak,'process_peak_rss_kib_linux':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'graph_pages':graph_samples}
        db.close();return result


if __name__=='__main__':
    print(json.dumps(benchmark(),ensure_ascii=False,indent=2))
