#!/usr/bin/env python3
"""Step 73 (v0.73.0, report v0.67.0 item R8): PROOF that the database backup can really be restored.

Three modes (standard library only; the live modes also need `pip install -r backend/requirements.txt`):

  python3 tools/check_backup_restore.py
      OFFLINE self-test (runs in CI, no internet, no account). Builds a sample database, backs it up through the real
      backend/durable.py code into a folder, restores it into a DIFFERENT file and compares every table and row count.
      Also proves: a damaged newest copy falls back to the older one, nothing usable is an ERROR (never an empty
      database), an unreachable store is an ERROR, and no secret value appears in any log line or report.

  python3 tools/check_backup_restore.py --live
      READ-ONLY test against your REAL backup (Cloudinary). Needs CLOUDINARY_CLOUD_NAME / _API_KEY / _API_SECRET in the
      environment (for example in the Render Shell of the sociax-backend service). It downloads the newest good copy,
      writes it to a temporary file (never the live database), prints the slot, version, age and the number of rows per
      table, and deletes the temporary file. It never uploads and never changes anything.

  python3 tools/check_backup_restore.py --live-roundtrip
      Proves UPLOAD + private download with the real Cloudinary account WITHOUT touching your real backup slots: a tiny
      test database goes to its own folder (sociax-backup-drill), is downloaded again with the signed private link,
      compared, and deleted.

Only numbers, slot names and error NAMES are printed: never a key, an address or a row's content.
Exit code 0 = every check passed, 1 = something failed.
"""
import logging
import os
import sqlite3
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "backend"))
import durable  # noqa: E402
from durable import (DirStore, Durable, StoreUnavailable, inspect_store, restore_drill, table_counts)  # noqa: E402

FAILED = []
SENTINEL = "SENTINEL-secret-value-8f3a91c2"


def check(ok: bool, text: str) -> None:
    print(("PASS  " if ok else "FAIL  ") + text)
    if not ok:
        FAILED.append(text)


class LogCatcher(logging.Handler):
    def __init__(self):
        super().__init__()
        self.lines = []

    def emit(self, record):
        self.lines.append(record.getMessage())


def make_sample(path: str, users: int, posts: int) -> None:
    con = sqlite3.connect(path)
    con.executescript("CREATE TABLE IF NOT EXISTS users(uid TEXT PRIMARY KEY, username TEXT);"
                      "CREATE TABLE IF NOT EXISTS posts(id INTEGER PRIMARY KEY AUTOINCREMENT, uid TEXT, body TEXT);"
                      "DELETE FROM users; DELETE FROM posts;")
    for i in range(users):
        con.execute("INSERT INTO users VALUES (?,?)", (f"u{i}", f"@u{i}"))
    for i in range(posts):
        con.execute("INSERT INTO posts(uid, body) VALUES (?,?)", (f"u{i % max(users, 1)}", f"post {i}"))
    con.commit()
    con.close()


# ----------------------------------------------------------------------------------------------------------------------
def offline_self_test() -> None:
    print("== OFFLINE self-test (folder store, sample data) ==")
    catcher = LogCatcher()
    lg = logging.getLogger("sociax.durable")
    lg.addHandler(catcher)
    lg.setLevel(logging.INFO)
    work = tempfile.mkdtemp()
    store = DirStore(os.path.join(work, "store"))
    live = os.path.join(work, "live.db")
    make_sample(live, users=5, posts=12)
    d = Durable(live, store)
    d.startup(attempts=1)

    check(d.backup_now() is True, "backup 1 uploaded (slot a)")
    make_sample(live, users=6, posts=20)
    check(d.backup_now() is True, "backup 2 uploaded (slot b): the older copy was NOT overwritten")
    rows = {r["slot"]: r for r in inspect_store(store)}
    check(rows["a"]["valid"] and rows["b"]["valid"] and {rows["a"]["seq"], rows["b"]["seq"]} == {1, 2},
          "both slots hold a valid copy with version numbers 1 and 2 (two versions kept)")
    check(all(r["saved_at"] for r in rows.values()), "every copy carries a time stamp")

    out = os.path.join(work, "restored.db")
    res = restore_drill(store, out)
    check(res["seq"] == 2 and res["tables"] == table_counts(live), f"restored copy (version {res['seq']}) has the same tables and row counts as the live database: {res['tables']}")
    check(os.path.abspath(out) != os.path.abspath(live), "the restore went to a DIFFERENT file than the live database")
    con = sqlite3.connect(out)
    check(con.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "restored database passes SQLite integrity_check")
    con.close()

    # the newest copy gets damaged: the older good copy is used
    newest = os.path.join(store.path, f"sociax-db-{res['slot']}.bin")
    blob = open(newest, "rb").read()
    open(newest, "wb").write(blob[:-25] + b"\x00" * 25)
    res2 = restore_drill(store, os.path.join(work, "restored2.db"))
    check(res2["seq"] == 1, "newest copy damaged: the older good copy (version 1) is used")
    open(newest, "wb").write(blob)

    # nothing usable = an ERROR, never an empty database; unreachable store = an ERROR
    try:
        restore_drill(DirStore(os.path.join(work, "empty")), os.path.join(work, "e.db"))
        check(False, "an empty store must be an error")
    except ValueError:
        check(True, "nothing saved: reported as an ERROR (not an empty database)")

    class Down:
        def get(self, slot):
            raise StoreUnavailable("down")
    try:
        restore_drill(Down(), os.path.join(work, "d.db"))
        check(False, "an unreachable store must be an error")
    except StoreUnavailable:
        check(True, "store unreachable: reported as an ERROR (not an empty database)")

    # failures are logged and counted, and the log never contains a secret
    class Leaky(DirStore):
        def put(self, slot, data):
            raise StoreUnavailable("https://x/?api_secret=" + SENTINEL)
    live2 = os.path.join(work, "live2.db")
    make_sample(live2, users=2, posts=2)
    d2 = Durable(live2, Leaky(os.path.join(work, "leaky")))
    d2.startup(attempts=1)
    d2.backup_now()
    d2.backup_now(force=True)
    check(d2.report()["failures_in_row"] == 2 and sum("BACKUP FAILED" in x for x in catcher.lines) == 2, "failed backups are logged and counted")
    check(any("BACKUP OK seq=" in x for x in catcher.lines), "good backups are logged with their version number")
    check(SENTINEL not in " ".join(catcher.lines) and SENTINEL not in repr(d2.report()), "no secret value in any log line or in the report")
    lg.removeHandler(catcher)


# ----------------------------------------------------------------------------------------------------------------------
def _cloud_env():
    name, key, secret = (os.environ.get(k) for k in ("CLOUDINARY_CLOUD_NAME", "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET"))
    if not (name and key and secret):
        print("STOP  the live modes need CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY and CLOUDINARY_API_SECRET in the environment "
              "(for example in the Render Shell of sociax-backend). Nothing was changed.")
        sys.exit(1)
    return name, key, secret


def live_read_only() -> None:
    print("== LIVE read-only restore test (your real backup; nothing is uploaded or changed) ==")
    store = durable.CloudinaryStore(*_cloud_env())
    try:
        rows = inspect_store(store)
    except StoreUnavailable as e:
        check(False, "could not reach Cloudinary: " + str(e)[:80])
        return
    for r in rows:
        if not r["exists"]:
            print(f"      slot {r['slot']}: not there")
        else:
            age = (int(time.time()) - r["saved_at"]) if r["saved_at"] else None
            print(f"      slot {r['slot']}: version {r['seq']}, {r['bytes']} bytes, saved {age} s ago, valid={r['valid']} {r['problem']}")
    check(any(r["valid"] for r in rows), "at least one saved copy exists and is valid")
    tmpdir = tempfile.mkdtemp()
    out = os.path.join(tmpdir, "restore-test.db")
    try:
        res = restore_drill(store, out)
    except (ValueError, StoreUnavailable) as e:
        check(False, "restore test failed: " + e.__class__.__name__ + " " + str(e)[:80])
        return
    finally:
        pass
    print(f"      restored slot {res['slot']} (version {res['seq']}), age {res['age_s']} s, {res['bytes']} bytes")
    for t, n in res["tables"].items():
        print(f"      table {t}: {n} rows")
    con = sqlite3.connect(out)
    ok = con.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    con.close()
    check(ok, "restored database passes SQLite integrity_check")
    check("users" in res["tables"], "restored database has the users table")
    check(res["age_s"] < 6 * 3600, "the newest copy is less than 6 hours old (if the server was idle this can be fine: judge it yourself)")
    try:
        os.remove(out)
        os.rmdir(tmpdir)
    except OSError:
        pass


def live_roundtrip() -> None:
    print("== LIVE round trip (own test folder; your real backup slots are NOT touched) ==")
    name, key, secret = _cloud_env()

    class DrillStore(durable.CloudinaryStore):
        FOLDER = "sociax-backup-drill"

    store = DrillStore(name, key, secret)
    work = tempfile.mkdtemp()
    live = os.path.join(work, "drill-live.db")
    make_sample(live, users=3, posts=7)
    d = Durable(live, store)
    d.restored_from, d.state = "local file", "ready"        # skip the start-up restore: this is a controlled test
    try:
        ok = d.backup_now(force=True)
        check(ok, "upload to Cloudinary (private raw file) worked" + ("" if ok else ": " + d.last_error))
        if ok:
            res = restore_drill(store, os.path.join(work, "drill-restored.db"))
            check(res["tables"] == table_counts(live), f"private download + restore gives the same rows: {res['tables']}")
    except (ValueError, StoreUnavailable) as e:
        check(False, "round trip failed: " + e.__class__.__name__ + " " + str(e)[:80])
    finally:
        try:
            import cloudinary
            import cloudinary.uploader
            cloudinary.config(cloud_name=name, api_key=key, api_secret=secret, secure=True)
            for slot in durable.SLOTS:
                cloudinary.uploader.destroy(store._public_id(slot), resource_type="raw", type="authenticated", invalidate=True)
            print("      test files removed from Cloudinary")
        except Exception as e:                              # cleaning up is best effort; the files are private and tiny
            print("      note: could not remove the test files (" + e.__class__.__name__ + "); folder sociax-backup-drill can be deleted by hand")


def main(argv) -> int:
    if "--live" in argv:
        live_read_only()
    elif "--live-roundtrip" in argv:
        live_roundtrip()
    else:
        offline_self_test()
    print("\nRESULT: " + ("PASS" if not FAILED else f"FAIL ({len(FAILED)} problem(s))"))
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
