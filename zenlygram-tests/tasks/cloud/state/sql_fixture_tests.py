#!/usr/bin/env python3
"""SQL fixture tests of the local ZenlyTasksStore v1 -> v2 migration (folder incarnations + content-free import map)
and of the cloud database schema v2 (Tasks R4: TasksCloudSchema.DDL and its UPGRADE_1_TO_2).

Executes the literal db.execSQL statements from the production ZenlyTasksStore.java, and the literal string lists of
TasksCloudSchema.java, on host stdlib SQLite. This is NOT Android SQLite / SQLiteOpenHelper: the helper's own
user_version transaction is modelled explicitly.
"""
from pathlib import Path
import re
import sqlite3
import tempfile

ROOT = Path(__file__).resolve().parents[4]
STORE = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/tasks/ZenlyTasksStore.java"
UUID4 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

statements = re.findall(r'db\.execSQL\("((?:CREATE|INSERT)[^"\n]+)"\)', STORE.read_text())
migration = [s for s in statements if "folder_incarnation" in s or "cloud_import_map" in s]
original = [s for s in statements if s not in migration]
if len(migration) != 4 or len(original) != 5:
    raise AssertionError("expected v1 schema (5) + additive v2 migration (4)")
checks = 1


def check(condition, message):
    global checks
    if not condition:  # explicit: `assert` is stripped under python -O / PYTHONOPTIMIZE
        raise AssertionError(message)
    checks += 1


def v1_database(path):
    db = sqlite3.connect(path, isolation_level=None)
    db.execute("PRAGMA foreign_keys=ON")
    for statement in original:
        db.execute(statement)
    db.execute("PRAGMA user_version=1")
    db.execute("INSERT INTO folders VALUES('work','Work',1)")
    db.execute("INSERT INTO tasks VALUES('t1','work','Ship it','Notes',200,4,0,NULL,1,2)")
    db.execute("INSERT INTO tasks VALUES('t2','inbox','Inbox item','',NULL,0,1,5,3,5)")
    return db


def upgrade(db):
    """SQLiteOpenHelper runs onUpgrade and the user_version bump in ONE transaction."""
    db.execute("BEGIN")
    for statement in migration:
        db.execute(statement)
    db.execute("PRAGMA user_version=2")
    db.execute("COMMIT")


def incarnation(db, folder):
    row = db.execute("SELECT incarnation FROM folder_incarnations WHERE folder_id=?", (folder,)).fetchone()
    return row[0] if row else None


with tempfile.TemporaryDirectory(prefix="zenly-tasks-cloud-sql-") as temporary:
    db = v1_database(str(Path(temporary) / "v1.db"))
    folders_before = db.execute("SELECT * FROM folders ORDER BY id").fetchall()
    tasks_before = db.execute("SELECT * FROM tasks ORDER BY id").fetchall()
    upgrade(db)
    check(db.execute("PRAGMA user_version").fetchone()[0] == 2, "version bumped with the migration")
    check(db.execute("SELECT * FROM folders ORDER BY id").fetchall() == folders_before, "original folders untouched")
    check(db.execute("SELECT * FROM tasks ORDER BY id").fetchall() == tasks_before, "original tasks untouched (ms values kept)")
    backfilled = db.execute("SELECT folder_id,incarnation FROM folder_incarnations ORDER BY folder_id").fetchall()
    check([row[0] for row in backfilled] == ["inbox", "work"], "every existing folder backfilled, including Inbox")
    check(all(UUID4.match(row[1]) for row in backfilled) and len({row[1] for row in backfilled}) == 2, "distinct UUIDv4 incarnations")

    inbox = incarnation(db, "inbox")
    db.execute("UPDATE folders SET title='Renamed' WHERE id='work'")
    check(incarnation(db, "work") == backfilled[1][1], "title/order UPDATE keeps the incarnation")
    db.execute("INSERT INTO folders VALUES('home','Home',7)")
    first_home = incarnation(db, "home")
    check(first_home is not None and UUID4.match(first_home), "each actual INSERT mints an incarnation")
    db.execute("INSERT INTO cloud_import_map VALUES('01jaaa','folder',?,'11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222',2,NULL)", (first_home,))
    db.execute("DELETE FROM folders WHERE id='home'")
    check(incarnation(db, "home") is None, "deleted folder's incarnation goes with it")
    check(db.execute("SELECT count(*) FROM cloud_import_map").fetchone()[0] == 1, "content-free mapping survives folder deletion")
    db.execute("INSERT INTO folders VALUES('home','Home',7)")
    check(incarnation(db, "home") not in (None, first_home), "same id + same created_at recreated => NEW incarnation")
    db.execute("INSERT OR REPLACE INTO folders VALUES('home','Home again',7)")
    check(incarnation(db, "home") is not None and db.execute("SELECT count(*) FROM folder_incarnations WHERE folder_id='home'").fetchone()[0] == 1,
          "INSERT OR REPLACE path also (re)mints exactly one incarnation")
    check(incarnation(db, "inbox") == inbox, "unrelated folders unaffected")
    for sql, args in [
        ("INSERT INTO cloud_import_map VALUES('01jaaa','task','t9','33333333-3333-4333-8333-333333333333','22222222-2222-4222-8222-222222222222',0,NULL)", ()),
        ("INSERT INTO cloud_import_map VALUES('01jaaa','task','t9','11111111-1111-4111-8111-111111111111','44444444-4444-4444-8444-444444444444',0,NULL)", ()),
        ("INSERT INTO cloud_import_map VALUES('01jaaa','note','t9','55555555-5555-4555-8555-555555555555','66666666-6666-4666-8666-666666666666',0,NULL)", ()),
        ("INSERT INTO folder_incarnations VALUES('missing','77777777-7777-4777-8777-777777777777')", ()),
    ]:
        try:
            db.execute(sql, args)
            raise AssertionError("constraint accepted: " + sql[:60])
        except sqlite3.IntegrityError:
            checks += 1
    db.execute("INSERT INTO cloud_import_map VALUES('01jbbb','task','t1','11111111-1111-4111-8111-111111111111','88888888-8888-4888-8888-888888888888',0,NULL)")
    check(db.execute("SELECT count(*) FROM cloud_import_map WHERE local_key='t1'").fetchone()[0] == 1,
          "another principal has its own mapping scope (same cloud id allowed per principal)")
    db.close()

    # Interrupted migration: the helper transaction rolls back, v1 rows/version intact, no partial tables.
    broken = v1_database(str(Path(temporary) / "broken.db"))
    broken.execute("BEGIN")
    broken.execute(migration[0])
    broken.execute(migration[1])
    broken.execute("ROLLBACK")
    check(broken.execute("PRAGMA user_version").fetchone()[0] == 1, "failed upgrade keeps version 1")
    check(broken.execute("SELECT count(*) FROM sqlite_master WHERE name IN ('folder_incarnations','folder_incarnation_mint')").fetchone()[0] == 0,
          "no partial migration objects")
    check(broken.execute("SELECT count(*) FROM tasks").fetchone()[0] == 2, "no original row lost")
    upgrade(broken)
    check(broken.execute("SELECT count(*) FROM folder_incarnations").fetchone()[0] == 2, "retried upgrade succeeds")
    broken.close()

    # Fresh install: v2 onCreate order (schema, Inbox insert, then incarnation tables + backfill).
    fresh = sqlite3.connect(str(Path(temporary) / "fresh.db"), isolation_level=None)
    fresh.execute("PRAGMA foreign_keys=ON")
    for statement in statements:
        fresh.execute(statement)
    check(UUID4.match(incarnation(fresh, "inbox") or ""), "fresh install Inbox has an incarnation")
    fresh.close()

print(f"Local task store v2 migration SQL: {checks} checks passed")


# ---- Cloud Tasks database schema v2 (TasksCloudSchema.DDL / UPGRADE_1_TO_2) ----

CLOUD_SCHEMA = ROOT / "TMessagesProj/src/main/java/org/telegram/messenger/zenlygram/tasks/cloud/TasksCloudSchema.java"
# The version-1 schema exactly as the AND-2 build created it (TasksCloudSchema.java before Tasks R4).
CLOUD_V1 = [
    "CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)",
    "CREATE TABLE shadow (kind TEXT NOT NULL CHECK(kind IN ('list','task','invitation','block')),id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))",
    "CREATE TABLE staged (kind TEXT NOT NULL CHECK(kind IN ('list','task','invitation','block')),id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))",
    "CREATE TABLE staging (singleton INTEGER PRIMARY KEY NOT NULL CHECK(singleton=1),phase TEXT NOT NULL CHECK(phase IN ('pages','catchup')),cursor TEXT,changes_cursor TEXT,page_limit INTEGER NOT NULL CHECK(page_limit BETWEEN 1 AND 100),CHECK(phase='pages' OR (cursor IS NOT NULL AND changes_cursor IS NOT NULL)),CHECK(cursor IS NOT NULL OR changes_cursor IS NULL))",
    "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT,mutation_id TEXT NOT NULL UNIQUE,principal TEXT NOT NULL,kind TEXT NOT NULL,resource_id TEXT NOT NULL,chain TEXT NOT NULL,list_key TEXT,source_list_key TEXT,body BLOB NOT NULL,sealed INTEGER NOT NULL CHECK(sealed IN (0,1)),resolved_base TEXT,state TEXT NOT NULL CHECK(state IN ('held','queued','sending','backoff','stalled','blocked_creation','invalid','conflict','quarantined','not_found','acked')),sent INTEGER NOT NULL DEFAULT 0 CHECK(sent IN (0,1)),error TEXT,reason TEXT,attempts INTEGER NOT NULL DEFAULT 0,not_before INTEGER NOT NULL DEFAULT 0,acked_revision TEXT,acked_resource TEXT,CHECK(sent=0 OR sealed=1),CHECK(state<>'held' OR (sent=0 AND kind='task.delete')),CHECK(state<>'acked' OR acked_resource IS NOT NULL))",
    "CREATE INDEX outbox_chain ON outbox(chain,seq)",
    "CREATE TABLE notices (id INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL CHECK(kind IN ('access_lost','not_found','dependency_dropped')),resource_id TEXT NOT NULL)",
]


def java_string_list(text, name):
    """The statements of `NAME = Collections.unmodifiableList(Arrays.asList(...))`: literals joined by + , split on ,."""
    start = text.index(name + " = Collections.unmodifiableList(Arrays.asList(") + len(name + " = Collections.unmodifiableList(Arrays.asList(")
    statements, current, i = [], None, start
    while True:
        c = text[i]
        if c == '"':
            j, out = i + 1, []
            while text[j] != '"':
                if text[j] == "\\":
                    j += 1
                out.append(text[j])
                j += 1
            current = (current or "") + "".join(out)
            i = j + 1
        elif c == ",":
            statements.append(current)
            current, i = None, i + 1
        elif c == ")":
            statements.append(current)
            return statements
        else:
            if c not in " \n+":
                raise AssertionError("unexpected token in " + name + ": " + repr(text[i:i + 40]))
            i += 1


cloud_text = CLOUD_SCHEMA.read_text()
cloud_ddl = java_string_list(cloud_text, "DDL")
cloud_upgrade = java_string_list(cloud_text, "UPGRADE_1_TO_2")
check("public static final int VERSION = 2;" in cloud_text, "cloud schema version 2")
check(len(cloud_ddl) == 8 and len(cloud_upgrade) == 16, "v2 DDL (8) and the 1->2 upgrade (16) extracted")
R4_COLUMNS = [("origin", "TEXT", 1, "'user'"), ("auto_requeues", "INTEGER", 1, "0"), ("auto_resolutions", "INTEGER", 1, "0"),
              ("auto_resolve", "TEXT", 0, None), ("conflict_fields", "TEXT", 0, None), ("base_snapshot", "TEXT", 0, None),
              ("waits_members", "INTEGER", 1, "0"), ("acked_cycle", "INTEGER", 0, None)]


def columns(db, table):
    return [(row[1], row[2], row[3], row[4]) for row in db.execute("PRAGMA table_info(" + table + ")")]


def v1_cloud(path):
    db = sqlite3.connect(path, isolation_level=None)
    db.execute("BEGIN")
    for statement in CLOUD_V1:
        db.execute(statement)
    db.execute("INSERT INTO meta VALUES('incarnation','i'),('principal','01jaaaaaaaaaaaaaaaaaaaaaaa'),('changes_cursor','c9'),('shadow_complete','1')")
    db.execute("INSERT INTO shadow VALUES('task','t1','{}')")
    db.execute("INSERT INTO staged VALUES('task','t2','{}')")
    db.execute("INSERT INTO staging VALUES(1,'catchup','p','s',20)")
    db.execute("INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,body,sealed,state) VALUES('m1','p','task.create','t9','task:t9',X'7b7d',1,'queued')")
    db.execute("INSERT INTO notices(kind,resource_id) VALUES('access_lost','a'),('not_found','b')")
    db.execute("PRAGMA user_version=1")
    db.execute("COMMIT")
    return db


def upgrade_cloud(db):
    """SQLiteOpenHelper: onUpgrade (TasksCloudLedger.upgrade -> UPGRADE_1_TO_2) and the version bump in ONE transaction."""
    db.execute("BEGIN")
    for statement in cloud_upgrade:
        db.execute(statement)
    db.execute("PRAGMA user_version=2")
    db.execute("COMMIT")


def refused(db, sql):
    try:
        db.execute(sql)
    except sqlite3.IntegrityError:
        return True
    return False


with tempfile.TemporaryDirectory(prefix="zenly-tasks-cloud-v2-") as temporary:
    fresh = sqlite3.connect(str(Path(temporary) / "fresh.db"), isolation_level=None)
    for statement in cloud_ddl:
        fresh.execute(statement)
    check(columns(fresh, "outbox")[-8:] == R4_COLUMNS, "fresh v2 outbox: the R4 columns after the v1 ones, with their defaults")
    check(columns(fresh, "import_ack_pending") == [("mutation_id", "TEXT", 1, None)], "fresh v2: import_ack_pending")
    fresh.execute("INSERT INTO notices(kind,resource_id) VALUES('removed_in_cloud','x'),('delete_kept','y')")
    check(refused(fresh, "INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,body,sealed,state,origin) VALUES('m','p','task.create','r','task:r',X'00',1,'queued','other')"),
          "origin CHECK")
    check(refused(fresh, "INSERT INTO outbox(mutation_id,principal,kind,resource_id,chain,body,sealed,state,waits_members) VALUES('m','p','list.delete','r','list:r',X'00',0,'queued',2)"),
          "waits_members CHECK")
    check(refused(fresh, "INSERT INTO notices(kind,resource_id) VALUES('bogus','z')"), "notice kinds CHECK")

    db = v1_cloud(str(Path(temporary) / "v1.db"))
    shadow_before = db.execute("SELECT * FROM shadow").fetchall()
    notices_before = db.execute("SELECT * FROM notices ORDER BY id").fetchall()
    upgrade_cloud(db)
    check(db.execute("PRAGMA user_version").fetchone()[0] == 2, "v1 opens at v2")
    for table in ("meta", "shadow", "staged", "staging", "outbox", "notices", "import_ack_pending"):
        check(columns(db, table) == columns(fresh, table), "upgraded " + table + " == fresh v2 " + table)
    check(db.execute("SELECT name FROM sqlite_master WHERE type='index' AND name='outbox_chain'").fetchone() is not None, "outbox_chain index kept")
    row = db.execute("SELECT origin,auto_requeues,auto_resolutions,auto_resolve,conflict_fields,base_snapshot,waits_members,acked_cycle FROM outbox").fetchone()
    check(row == ("user", 0, 0, None, None, None, 0, None), "an R3 row reads the R4 defaults")
    check(db.execute("SELECT value FROM meta WHERE key='changes_cursor'").fetchone() is None
          and db.execute("SELECT count(*) FROM staged").fetchone()[0] == 0 and db.execute("SELECT count(*) FROM staging").fetchone()[0] == 0,
          "forced resnapshot: cursor, staged pages and staging gone")
    check(db.execute("SELECT * FROM shadow").fetchall() == shadow_before
          and db.execute("SELECT value FROM meta WHERE key='shadow_complete'").fetchone()[0] == "1", "the shadow stays until the new snapshot reconciles")
    check(db.execute("SELECT * FROM notices ORDER BY id").fetchall() == notices_before, "notices kept with their ids across the rebuild")
    db.execute("INSERT INTO notices(kind,resource_id) VALUES('removed_in_cloud','c')")
    check(db.execute("SELECT max(id) FROM notices").fetchone()[0] == notices_before[-1][0] + 1, "notice ids continue (AUTOINCREMENT sequence kept)")
    check(refused(db, "INSERT INTO notices(kind,resource_id) VALUES('bogus','z')"), "upgraded notices CHECK")
    check(refused(db, "UPDATE outbox SET origin='other'"), "upgraded origin CHECK")
    check(refused(db, "UPDATE outbox SET waits_members=5"), "upgraded waits_members CHECK")
    db.close()

    broken = v1_cloud(str(Path(temporary) / "broken.db"))
    broken.execute("BEGIN")
    for statement in cloud_upgrade[:10]:
        broken.execute(statement)
    broken.execute("ROLLBACK")
    check(broken.execute("PRAGMA user_version").fetchone()[0] == 1 and columns(broken, "outbox")[-1][0] == "acked_resource"
          and broken.execute("SELECT count(*) FROM sqlite_master WHERE name IN ('import_ack_pending','notices_v2')").fetchone()[0] == 0
          and broken.execute("SELECT value FROM meta WHERE key='changes_cursor'").fetchone()[0] == "c9",
          "an interrupted upgrade rolls back to an intact version 1")
    upgrade_cloud(broken)
    check(columns(broken, "outbox") == columns(fresh, "outbox"), "the retried upgrade succeeds")
    broken.close()
    fresh.close()

print(f"Cloud Tasks schema v2 SQL: {checks} checks passed (cumulative)")
