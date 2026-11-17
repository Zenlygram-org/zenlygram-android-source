#!/usr/bin/env python3
"""SQL of ZenlyTasksStore.invalidateMigrationVerification against the store's actual schema (stdlib SQLite).

A new cloud store voids every verification recorded against an earlier one (TasksAutoMigration, design 30 §3.8): the
migration record and every attempt row go to verified generation -1; state, mappings and acknowledgements stay. Also
guards ../run_tests.py, which reads the FIRST 'UPDATE cloud_import_attempt SET verified_generation' statement of the
store source as markImportReverified's. The schema is read the way run_tests.py reads it (v1/v2 literals + SCHEMA_V3).
"""
import re
import sqlite3
import sys
from pathlib import Path

checks = 0


def check(condition, message):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)


source = Path(sys.argv[1]).read_text(encoding="utf-8")
literal = re.findall(r'db\.execSQL\("((?:CREATE|INSERT)[^"\n]+)"\)', source)
v3_block = re.search(r'static final String\[\] SCHEMA_V3 = \{(.*?)\};', source, re.S)
check(v3_block is not None, "SCHEMA_V3 list in ZenlyTasksStore")
schema = literal + re.findall(r'"((?:CREATE)[^"\n]+)"', v3_block.group(1))
body = re.search(r'public void invalidateMigrationVerification\(.*?\n    \}\n', source, re.S)
check(body is not None, "ZenlyTasksStore.invalidateMigrationVerification")
invalidate = re.findall(r'db\.execSQL\("([^"]+)"\)', body.group(0))
check(len(invalidate) == 2, "two statements: the record and the attempt rows")
reverify = re.search(r'"(UPDATE cloud_import_attempt SET verified_generation[^"]+)"', source).group(1)
check("?" in reverify and "acknowledged=0" in reverify, "run_tests.py still reads markImportReverified's UPDATE first")
record_sql = re.search(r'"(INSERT OR REPLACE INTO cloud_migration[^"]+)"', source).group(1)
snapshot_sql = re.search(r'"(SELECT m\.entity[^"]+)"', source).group(1)


def store():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys=ON")
    for statement in schema:
        db.execute(statement)
    return db


scope = "o-faketoken/01jfakeprincipal"
db = store()
db.execute("INSERT INTO folders VALUES('work','Work',1)")
db.execute("INSERT INTO tasks VALUES('t1','work','Ship it','',NULL,0,0,NULL,1,2)")
db.execute("INSERT INTO tasks VALUES('t2','work','Plan it','',NULL,0,0,NULL,1,2)")
work = db.execute("SELECT incarnation FROM folder_incarnations WHERE folder_id='work'").fetchone()[0]
db.execute("INSERT INTO cloud_import_map VALUES(?,'folder',?,'11111111-1111-4111-8111-111111111111','m-folder',0,NULL)", (scope, work))
db.execute("INSERT INTO cloud_import_map VALUES(?,'task','t1','22222222-2222-4222-8222-222222222222','m-acked',0,'11111111-1111-4111-8111-111111111111')", (scope,))
db.execute("INSERT INTO cloud_import_map VALUES(?,'task','t2','33333333-3333-4333-8333-333333333333','m-sent',1024,'11111111-1111-4111-8111-111111111111')", (scope,))
db.execute("INSERT INTO cloud_import_attempt(mutation_id,acknowledged,verified_generation) VALUES('m-acked',1,3)")
db.execute("INSERT INTO cloud_import_attempt(mutation_id,acknowledged,verified_generation) VALUES('m-sent',0,0)")
db.execute(record_sql, ("completed", None, scope, "uz", 100, 120, 130, 3, 1, 2, 0))
before = db.execute("SELECT state,reason,scope,normalization_locale,claimed_at,enqueued_at,completed_at,lists,tasks,kept_local FROM cloud_migration").fetchone()

for statement in invalidate:
    db.execute(statement)
check(db.execute("SELECT verified_generation FROM cloud_migration").fetchone()[0] == -1, "the record's verification is void")
check(db.execute("SELECT state,reason,scope,normalization_locale,claimed_at,enqueued_at,completed_at,lists,tasks,kept_local FROM cloud_migration").fetchone() == before,
      "the record's state, scope, times and counts stay")
check(db.execute("SELECT mutation_id,acknowledged,verified_generation FROM cloud_import_attempt ORDER BY mutation_id").fetchall()
      == [("m-acked", 1, -1), ("m-sent", 0, -1)], "every attempt row at -1, acknowledgements kept")
check(db.execute("SELECT count(*) FROM cloud_import_map").fetchone()[0] == 3 and db.execute("SELECT count(*) FROM tasks").fetchone()[0] == 2,
      "mappings and local rows untouched")
provenance = {row[3]: row[6:] for row in db.execute(snapshot_sql, (scope,))}
check(provenance == {"m-folder": (0, 0, 0), "m-acked": (1, 1, -1), "m-sent": (1, 0, -1)},
      "importSnapshot reads -1: older than generation 0 of the new store (a row never attempted has no verification)")
db.execute(reverify, (0, "m-acked"))
check(db.execute("SELECT acknowledged,verified_generation FROM cloud_import_attempt WHERE mutation_id='m-acked'").fetchone() == (0, 0),
      "markImportReverified then re-verifies the row at the new store's generation (resent once)")
for statement in invalidate:
    db.execute(statement)
check(db.execute("SELECT verified_generation FROM cloud_migration").fetchone()[0] == -1, "idempotent")

empty = store()
for statement in invalidate:
    empty.execute(statement)
check(empty.execute("SELECT count(*) FROM cloud_migration").fetchone()[0] == 0
      and empty.execute("SELECT count(*) FROM cloud_import_attempt").fetchone()[0] == 0, "no record, no attempts: a no-op (a store's first open)")
print(f"Task store migration verification SQL: {checks} checks passed")
