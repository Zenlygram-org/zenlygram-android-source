#!/usr/bin/env python3
"""Host SQLite executor for TasksCloudStateTests (line-delimited JSON over stdin/stdout).

Executes the production TasksCloudSchema/TasksCloudLedger SQL verbatim on a real on-disk SQLite file in WAL mode.
Host stdlib SQLite is newer than Android's (API 23 ships 3.8.10); production SQL deliberately avoids post-3.8
features, but this bridge is NOT Android SQLite. Values: {"s":str} | {"i":int} | {"b":base64} | null.
"""
import base64
import json
import sqlite3
import sys


def decode(value):
    if value is None:
        return None
    if "s" in value:
        return value["s"]
    if "i" in value:
        return int(value["i"])
    return base64.b64decode(value["b"])


def encode(value):
    if value is None:
        return None
    if isinstance(value, bytes):
        return {"b": base64.b64encode(value).decode("ascii")}
    if isinstance(value, int):
        return {"i": str(value)}
    if isinstance(value, float):
        raise TypeError("REAL values are not part of the cloud schema")
    return {"s": value}


def main(path):
    db = sqlite3.connect(path, isolation_level=None)
    db.execute("PRAGMA journal_mode=WAL")
    for line in sys.stdin:
        request = json.loads(line)
        op = request["op"]
        try:
            if op == "close":
                db.close()
                print(json.dumps({"ok": True}), flush=True)
                return
            if op in ("begin", "commit", "rollback"):
                db.execute({"begin": "BEGIN IMMEDIATE", "commit": "COMMIT", "rollback": "ROLLBACK"}[op])
                print(json.dumps({"ok": True}), flush=True)
                continue
            args = [decode(value) for value in request.get("args", [])]
            cursor = db.execute(request["sql"], args)
            rows = [[encode(cell) for cell in row] for row in cursor.fetchall()] if op == "query" else []
            # Directly affected rows of THIS statement (no trigger/FK-action rows), like Android executeUpdateDelete().
            changes = db.execute("SELECT changes()").fetchone()[0] if op == "update" else 0
            print(json.dumps({"ok": True, "rows": rows, "changes": changes}), flush=True)
        except sqlite3.Error as error:
            print(json.dumps({"ok": False, "error": type(error).__name__ + ": " + str(error)}), flush=True)


if __name__ == "__main__":
    main(sys.argv[1])
