#!/usr/bin/env python3
"""Compile actual pure-Java task rules and exercise actual SQL schema using stdlib SQLite.

This intentionally does not claim to be an Android UI/instrumentation test.
"""
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "TMessagesProj/src/main/java"
TASKS = JAVA / "org/telegram/messenger/zenlygram/tasks"
HERE = Path(__file__).resolve().parent


def java_tool(name):
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / name)
    candidates.append(Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin") / name)
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)
    resolved = shutil.which(name)
    if not resolved:
        raise RuntimeError("JDK required: set JAVA_HOME")
    return resolved


with tempfile.TemporaryDirectory(prefix="zenly-tasks-tests-") as temporary:
    subprocess.run([java_tool("javac"), "--release", "8", "-d", temporary,
                    str(TASKS / "ZenlyTaskModel.java"), str(TASKS / "ZenlyTaskDeadline.java"),
                    str(TASKS / "ZenlyTaskCalendar.java"),
                    str(HERE / "ZenlyTasksModelTest.java"), str(HERE / "ZenlyTaskDeadlineTest.java"),
                    str(HERE / "ZenlyTaskCalendarTest.java")], check=True)
    subprocess.run([java_tool("java"), "-cp", temporary, "ZenlyTasksModelTest"], check=True)
    subprocess.run([java_tool("java"), "-cp", temporary, "ZenlyTaskDeadlineTest"], check=True)
    subprocess.run([java_tool("java"), "-cp", temporary, "ZenlyTaskCalendarTest"], check=True)

    source = (TASKS / "ZenlyTasksStore.java").read_text()
    literal = re.findall(r'db\.execSQL\("((?:CREATE|INSERT)[^"\n]+)"\)', source)
    v3_block = re.search(r'static final String\[\] SCHEMA_V3 = \{(.*?)\};', source, re.S)
    assert v3_block, "SCHEMA_V3 list in ZenlyTasksStore"
    schema_v3 = re.findall(r'"((?:CREATE)[^"\n]+)"', v3_block.group(1))
    assert len(schema_v3) == 2, "v3 adds cloud_import_attempt and cloud_migration"
    assert "VERSION = 3;" in source, "store database version 3"
    v2_migration = [s for s in literal if "folder_incarnation" in s or "cloud_import_map" in s]
    v1_schema = [s for s in literal if s not in v2_migration]
    assert len(v1_schema) == 5 and len(v2_migration) == 4, "v1 schema (5) + v2 migration (4) as literals"
    schema = literal + schema_v3

    def database(name):
        db = sqlite3.connect(Path(temporary) / name)
        db.execute("PRAGMA foreign_keys=ON")
        for statement in schema:
            db.execute(statement)
        db.commit()
        return db

    one = database("account-one.db")
    one.execute("INSERT INTO folders VALUES(?,?,?)", ("work", "Work", 1))
    row = ("task1", "work", "Ship it", "Don't lose notes", 200, 4, 0, None, 1, 2)
    one.execute("INSERT INTO tasks VALUES(?,?,?,?,?,?,?,?,?,?)", row)
    one.commit()
    one.close()
    one = sqlite3.connect(Path(temporary) / "account-one.db")
    one.execute("PRAGMA foreign_keys=ON")
    assert one.execute("SELECT * FROM tasks").fetchone() == row, "Reopen preserves fields"
    one.execute("UPDATE tasks SET completed=1,completed_at=10 WHERE id='task1'")
    one.commit()
    assert one.execute("SELECT completed,completed_at FROM tasks").fetchone() == (1, 10)

    checks = 2
    for sql, args in [
        ("UPDATE tasks SET title=?", ("   ",)),
        ("UPDATE tasks SET priority=?", (5,)),
        ("UPDATE tasks SET due_at=?", (-1,)),
        ("UPDATE tasks SET description=?", ("x" * 10001,)),
        ("UPDATE tasks SET folder_id=?", ("missing",)),
        ("UPDATE tasks SET completed_at=NULL", ()),
    ]:
        try:
            one.execute(sql, args)
            raise AssertionError("Constraint accepted invalid data")
        except sqlite3.IntegrityError:
            one.rollback()
            checks += 1

    one.execute("UPDATE tasks SET completed=0,completed_at=NULL WHERE id='task1'")
    one.commit()
    one.execute("DELETE FROM folders WHERE id='work'")
    assert one.execute("SELECT count(*) FROM tasks").fetchone()[0] == 0, "Cascade deletes folder tasks"
    one.rollback()
    assert one.execute("SELECT count(*) FROM tasks").fetchone()[0] == 1, "Rollback restores folder and tasks"
    one.execute("DELETE FROM folders WHERE id='work'")
    one.commit()
    assert one.execute("SELECT id FROM folders").fetchall() == [("inbox",)], "Inbox remains"
    checks += 3
    one.close()
    print(f"Task SQLite persistence/schema: {checks} checks passed")

    # ---- store v3 (Tasks R4): upgrades 1->3 and 2->3 keep every local row; provenance, record, release ----
    v3 = 0

    def check(condition, message):
        global v3
        if not condition:
            raise AssertionError(message)
        v3 += 1

    def versioned(name, statements, version):
        db = sqlite3.connect(Path(temporary) / name, isolation_level=None)
        db.execute("PRAGMA foreign_keys=ON")
        for statement in statements:
            db.execute(statement)
        db.execute(f"PRAGMA user_version={version}")
        db.execute("INSERT INTO folders VALUES('work','Work',1)")
        db.execute("INSERT INTO tasks VALUES('t1','work','Ship it','Notes',200,4,0,NULL,1,2)")
        db.execute("INSERT INTO tasks VALUES('t2','inbox','Inbox item','',NULL,0,1,5,3,5)")
        return db

    def upgrade(db, old):
        """SQLiteOpenHelper.onUpgrade + the user_version bump in ONE transaction (ZenlyTasksStore.Database)."""
        db.execute("BEGIN")
        if old == 1:
            for statement in v2_migration:
                db.execute(statement)
        for statement in schema_v3:
            db.execute(statement)
        db.execute("PRAGMA user_version=3")
        db.execute("COMMIT")

    def tables(db):
        return {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}

    for old, statements in ((1, v1_schema), (2, v1_schema + v2_migration)):
        db = versioned(f"v{old}.db", statements, old)
        folders = db.execute("SELECT * FROM folders ORDER BY id").fetchall()
        rows = db.execute("SELECT * FROM tasks ORDER BY id").fetchall()
        incarnations = db.execute("SELECT * FROM folder_incarnations ORDER BY folder_id").fetchall() if old == 2 else None
        upgrade(db, old)
        check(db.execute("PRAGMA user_version").fetchone()[0] == 3, f"{old}->3 bumps the version")
        check(db.execute("SELECT * FROM folders ORDER BY id").fetchall() == folders, f"{old}->3 keeps folders")
        check(db.execute("SELECT * FROM tasks ORDER BY id").fetchall() == rows, f"{old}->3 keeps tasks")
        check({"folder_incarnations", "cloud_import_map", "cloud_import_attempt", "cloud_migration"} <= tables(db), f"{old}->3 tables")
        if incarnations is not None:
            check(db.execute("SELECT * FROM folder_incarnations ORDER BY folder_id").fetchall() == incarnations, "2->3 keeps incarnations")
        else:
            check(db.execute("SELECT count(*) FROM folder_incarnations").fetchone()[0] == 2, "1->3 backfills incarnations")
        db.close()

    # An interrupted 2->3 upgrade rolls back to an intact version 2.
    broken = versioned("broken.db", v1_schema + v2_migration, 2)
    broken.execute("BEGIN")
    broken.execute(schema_v3[0])
    broken.execute("ROLLBACK")
    check(broken.execute("PRAGMA user_version").fetchone()[0] == 2 and "cloud_import_attempt" not in tables(broken),
          "interrupted upgrade leaves version 2 and no partial table")
    upgrade(broken, 2)
    check("cloud_migration" in tables(broken), "retried upgrade succeeds")
    broken.close()

    # Fresh v3 (onCreate): every table; the store's own provenance / record / release statements.
    fresh = sqlite3.connect(Path(temporary) / "fresh.db", isolation_level=None)
    fresh.execute("PRAGMA foreign_keys=ON")
    for statement in schema:
        fresh.execute(statement)
    scope = "o-tok/01jfakeprincipal"
    work = fresh.execute("SELECT incarnation FROM folder_incarnations WHERE folder_id='inbox'").fetchone()[0]
    fresh.execute("INSERT INTO folders VALUES('work','Work',1)")
    fresh.execute("INSERT INTO folders VALUES('home','Home',2)")
    fresh.execute("INSERT INTO tasks VALUES('t1','work','Ship it','',NULL,0,0,NULL,1,2)")
    fresh.execute("INSERT INTO tasks VALUES('t2','inbox','Inbox item','',NULL,0,0,NULL,1,2)")
    work = fresh.execute("SELECT incarnation FROM folder_incarnations WHERE folder_id='work'").fetchone()[0]
    fresh.execute("INSERT INTO cloud_import_map VALUES(?,'folder',?,'11111111-1111-4111-8111-111111111111','m-folder',0,NULL)", (scope, work))
    fresh.execute("INSERT INTO cloud_import_map VALUES(?,'task','t1','22222222-2222-4222-8222-222222222222','m-task',0,'11111111-1111-4111-8111-111111111111')", (scope,))
    attempt = re.search(r'"(INSERT OR IGNORE INTO cloud_import_attempt[^"]+)"', source).group(1)
    acknowledge = re.search(r'"(UPDATE cloud_import_attempt SET acknowledged=1[^"]+)"', source).group(1)
    reverify = re.search(r'"(UPDATE cloud_import_attempt SET verified_generation[^"]+)"', source).group(1)
    unmapped = re.search(r'"(SELECT \(SELECT count\(\*\) FROM tasks t[^"]+)"', source).group(1)
    snapshot = re.search(r'"(SELECT m\.entity[^"]+)"', source).group(1)
    for mutation in ("m-task", "m-user-create"):
        fresh.execute(attempt, (mutation,))
    check(fresh.execute("SELECT mutation_id,acknowledged,verified_generation FROM cloud_import_attempt").fetchall() == [("m-task", 0, 0)],
          "attempts only for import ids (others are no-ops)")
    fresh.execute(attempt, ("m-task",))
    fresh.execute(acknowledge, ("m-task",))
    check(fresh.execute("SELECT acknowledged FROM cloud_import_attempt").fetchone()[0] == 1, "acknowledged")
    fresh.execute(reverify, (4, "m-task"))
    check(fresh.execute("SELECT acknowledged,verified_generation FROM cloud_import_attempt").fetchone() == (0, 4),
          "re-verified: unacknowledged at the generation (resent once)")
    provenance = {row[3]: row[6:] for row in fresh.execute(snapshot, (scope,))}
    check(provenance == {"m-folder": (0, 0, 0), "m-task": (1, 0, 4)}, "snapshot joins the provenance")
    check(fresh.execute(unmapped, (scope, scope)).fetchone()[0] == 2, "U = unmapped live task t2 + folder home (inbox never counts)")
    check(fresh.execute(unmapped, ("o-other/x", "o-other/x")).fetchone()[0] == 4, "U is per scope")
    record = re.search(r'"(INSERT OR REPLACE INTO cloud_migration[^"]+)"', source).group(1)
    fresh.execute(record, ("claimed", None, scope, "uz", 100, None, None, 0, 0, 0, 0))
    fresh.execute(record, ("enqueued", None, scope, "uz", 100, 120, None, 0, 1, 1, 0))
    check(fresh.execute("SELECT count(*),state FROM cloud_migration").fetchone() == (1, "enqueued"), "singleton record replaced")
    for bad in (("bogus", None, scope, "uz", 1, None, None, 0, 0, 0, 0), ("claimed", None, scope, "de", 1, None, None, 0, 0, 0, 0)):
        try:
            fresh.execute(record, bad)
            raise AssertionError("cloud_migration accepted " + repr(bad[:4]))
        except sqlite3.IntegrityError:
            v3 += 1
    try:
        fresh.execute("INSERT INTO cloud_import_attempt(mutation_id) VALUES('not-mapped')")
        raise AssertionError("attempt without a mapping accepted")
    except sqlite3.IntegrityError:
        v3 += 1
    # releaseMigration: every mapping goes, the attempts cascade, the record becomes released; local rows stay.
    fresh.execute("BEGIN")
    fresh.execute("DELETE FROM cloud_import_map")
    fresh.execute("UPDATE cloud_migration SET state='released',reason='withdrawn'")
    fresh.execute("COMMIT")
    check(fresh.execute("SELECT count(*) FROM cloud_import_attempt").fetchone()[0] == 0, "attempts cascade with the mappings")
    check(fresh.execute("SELECT count(*) FROM tasks").fetchone()[0] == 2, "release never touches local rows")
    fresh.execute("DELETE FROM cloud_migration")
    check(fresh.execute("SELECT count(*) FROM cloud_migration").fetchone()[0] == 0, "clearMigrationRecord")
    fresh.close()
    print(f"Task store v3 (R4) SQL: {v3} checks passed")

resources = ROOT / "TMessagesProj/src/main/res"
LOCALES = ("values", "values-ru", "values-uz")
PLURAL = re.compile(r"_(zero|one|two|few|many|other)$")
FORMAT = re.compile(r"%\d+\$[sd]")


def strings(locale, name):
    return {node.attrib["name"]: node.text or "" for node in ET.parse(resources / locale / name).getroot() if node.tag == "string"}


def check_translations(label, name, keep=lambda key: True):
    """The same strings in en, ru and uz, none empty, with the same format arguments.

    A plural (name_one / _few / _many / _other, read by LocaleController.formatPluralString) counts as one string:
    every language has the forms its own grammar needs, always with _other, and each form takes the arguments of the
    English _other.
    """
    bundles = {locale: {key: value for key, value in strings(locale, name).items() if keep(key)} for locale in LOCALES}
    base = bundles["values"]
    assert base, f"No {label} strings in {name}"

    def stem(key):
        return PLURAL.sub("", key)

    for locale, entries in bundles.items():
        assert {stem(key) for key in entries} == {stem(key) for key in base}, f"Missing/extra {label} strings: {locale}"
        for key, value in entries.items():
            assert value.strip(), f"Empty {label} translation: {locale}/{key}"
            if PLURAL.search(key):
                assert stem(key) + "_other" in entries, f"Plural without _other: {locale}/{key}"
            reference = base[key] if key in base else base[stem(key) + "_other"]
            assert sorted(FORMAT.findall(value)) == sorted(FORMAT.findall(reference)), f"Format mismatch: {locale}/{key}"
    return len({stem(key) for key in base})


# This tree keeps the Tasks texts in three files (the import had zenly_tasks.xml and zenly_tasks_cloud.xml): the board's
# own strings (ZG_tasks_* of strings_zenlygram_android.xml, shared with other Zenlygram screens), the Cloud board, the
# Cloud / device switch and the Device archive (strings_zenlygram_tasks2.xml), and the engine's Cloud texts
# (strings_zenlygram_engine_tasks.xml).
board = check_translations("task", "strings_zenlygram_android.xml", lambda key: key.startswith("ZG_tasks_"))
board += check_translations("task", "strings_zenlygram_tasks2.xml")
print(f"Task translations: {board} strings × {len(LOCALES)} locales checked")

cloud = check_translations("cloud task", "strings_zenlygram_engine_tasks.xml")
print(f"Cloud task translations: {cloud} strings × {len(LOCALES)} locales checked")

# Uzbek is written with ʻ (U+02BB) and ʼ (U+02BC): never an ASCII apostrophe or a quotation mark in their place.
for key, value in strings("values-uz", "strings_zenlygram_tasks2.xml").items():
    assert not re.search("['\u2018\u2019`]", value), f"Uzbek apostrophe: {key}"
print("Uzbek apostrophes of the Tasks strings checked")
