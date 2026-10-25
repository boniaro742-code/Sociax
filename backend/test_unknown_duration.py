"""Step 53 (plan v3.9.1): Short Video has NO duration limit, and a length that cannot be read never blocks publishing.
Run:  cd backend && DEV_AUTH=1 python test_unknown_duration.py     (no internet: Cloudinary is a fake)

Checks: an old database (duration NOT NULL) is rebuilt once without losing a row or an id; long clips publish; an unreadable
length is stored as NULL (never 0) and shown as null; a late length is filled in by the background job; size, type and quota
still refuse; a repeated request never makes a duplicate post; watch events and ranking cope with an unknown length."""
import os, sqlite3, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
DB = os.path.join(tempfile.mkdtemp(), "t.db")
os.environ["SOCIAX_DB"] = DB

# ---- 0. an OLD database: shorts.duration is NOT NULL, two rows, the id counter is ahead of the last row ----------------------
con = sqlite3.connect(DB)
con.executescript("""
CREATE TABLE shorts(
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    uid TEXT NOT NULL, caption TEXT NOT NULL DEFAULT '',
    media_public_id TEXT NOT NULL, duration REAL NOT NULL,
    audience TEXT NOT NULL DEFAULT 'public',
    comments_enabled INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL);
CREATE INDEX shorts_uid ON shorts(uid, id);
INSERT INTO shorts(uid, caption, media_public_id, duration, created_at) VALUES ('old-a', 'first', 'users/old-a/short_1', 12.5, 1);
INSERT INTO shorts(uid, caption, media_public_id, duration, created_at) VALUES ('old-a', 'second', 'users/old-a/short_2', 59.0, 2);
INSERT INTO shorts(uid, caption, media_public_id, duration, created_at) VALUES ('old-a', 'gone', 'users/old-a/short_3', 3.0, 3);
DELETE FROM shorts WHERE id=3;
""")
con.commit(); con.close()

import main as m  # noqa: E402   (importing runs init_db(): the migration)
from fastapi import HTTPException  # noqa: E402

MB = 1024 * 1024
m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
m.DURATION_RETRY_WAIT = 0
m.DURATION_BACKFILL_DELAYS = ()
FAKE, DESTROYED = {}, []
m.video_info = lambda pid: FAKE[pid] if pid in FAKE else (_ for _ in ()).throw(HTTPException(422, "Upload not found"))
m.destroy_video = lambda pid: DESTROYED.append(pid)
A, B = "dev-a", "dev-b"


def ok(msg):
    print("PASS", msg)


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def upload(uid, n, dur, size=5 * MB, fmt="mp4"):
    pid = f"users/{uid}/short_{n}"
    FAKE[pid] = {"bytes": size, "format": fmt, **({} if dur is None else {"duration": dur})}
    return pid


def publish(uid, n, dur, **kw):
    m._rate.clear()
    size, fmt = kw.pop("size", 5 * MB), kw.pop("fmt", "mp4")
    return m.create_short(m.NewShort(public_id=upload(uid, n, dur, size, fmt), **kw), uid)


def shorts_count(uid):
    with closing(m.db()) as con:
        return con.execute("SELECT COUNT(*) FROM shorts WHERE uid=?", (uid,)).fetchone()[0]


# ---- 1. the migration kept every row, the ids, the counter and the index; and NULL is accepted now ------------------------
with closing(m.db()) as con:
    sql = con.execute("SELECT sql FROM sqlite_master WHERE name='shorts'").fetchone()[0]
    assert "duration REAL NOT NULL" not in sql.replace("  ", " ")
    rows = con.execute("SELECT id, caption, duration FROM shorts ORDER BY id").fetchall()
    assert [tuple(r) for r in rows] == [(1, "first", 12.5), (2, "second", 59.0)]
    assert con.execute("SELECT seq FROM sqlite_sequence WHERE name='shorts'").fetchone()[0] >= 3      # id 3 is never handed out again
    assert con.execute("SELECT 1 FROM sqlite_master WHERE type='index' AND name='shorts_uid'").fetchone()
    assert not con.execute("SELECT 1 FROM sqlite_master WHERE name='shorts_old'").fetchone()
    con.execute("INSERT INTO shorts(uid, media_public_id, duration, created_at) VALUES ('x','users/x/short_n',NULL,1)")
    con.rollback()
with closing(m.db()) as con2:
    assert m._make_short_duration_nullable(con2) is False                      # a second start does nothing
m.init_db()                                                                      # and init_db can run again safely
ok("old database rebuilt once: rows, ids, id counter and index kept; NULL accepted; second run is a no-op")

m.onboard(m.Onboarding(display_name="anna", username="anna", age=20), A)
m.onboard(m.Onboarding(display_name="ben", username="ben", age=20), B)

# ---- 2. long clips publish: no duration cap anywhere ------------------------------------------------------------------------
for n, secs in enumerate([61.0, 600.0, 3600.0, 86400.0], start=1):
    s = publish(A, n, secs)
    assert s["duration"] == secs and f"users/{A}/short_{n}" not in DESTROYED
assert publish(A, 5, 90.0, chosen_seconds=20)["id"]                              # an OLD app that sends 20 / 25 / 60 still works
assert publish(A, 6, 45.0, chosen_seconds=60)["id"]
ok("clips of 61 s, 10 min, 1 h and 24 h publish; old apps that send chosen_seconds still work")

# ---- 3. unreadable length: published, stored as NULL, shown as null (never 0) ---------------------------------------------
for n, dur in ((10, None), (11, 0.0)):
    s = publish(A, n, dur)
    assert s["duration"] is None, s["duration"]
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM shorts WHERE duration IS NULL").fetchone()[0] == 2
    assert con.execute("SELECT COUNT(*) FROM shorts WHERE duration = 0").fetchone()[0] == 0
feed = m.shorts_feed(None, 30, B)["shorts"]
assert any(x["duration"] is None for x in feed) and all(("duration" in x) for x in feed)
assert m.shorts_for_you(None, 10, B) is not None                                 # the ranked feed works with an unknown length
ok("unknown length: published, stored as NULL (never 0), returned as null in the feed")

# ---- 4. the late length is filled in from trusted Cloudinary data; a failure changes nothing ------------------------------
pid = f"users/{A}/short_10"
with closing(m.db()) as con:
    sid = con.execute("SELECT id FROM shorts WHERE media_public_id=?", (pid,)).fetchone()[0]
assert m._backfill_duration_once(sid, pid) is False                              # Cloudinary still does not know it
FAKE[pid]["duration"] = 33.25
assert m._backfill_duration_once(sid, pid) is True
with closing(m.db()) as con:
    assert con.execute("SELECT duration FROM shorts WHERE id=?", (sid,)).fetchone()[0] == 33.25
    con.execute("UPDATE shorts SET duration=7 WHERE id=?", (sid,)); con.commit()
FAKE[pid]["duration"] = 99.0
assert m._backfill_duration_once(sid, pid) is True
with closing(m.db()) as con:
    assert con.execute("SELECT duration FROM shorts WHERE id=?", (sid,)).fetchone()[0] == 7      # a known length is never overwritten
del FAKE[pid]
assert m._backfill_duration_once(sid, pid) is False                              # Cloudinary error: just "try later", no exception
m.DURATION_BACKFILL_DELAYS = (0,)
pid2 = upload(A, 12, None)
s = m.create_short(m.NewShort(public_id=pid2), A)
for _ in range(100):                                                             # the background thread asks again
    with closing(m.db()) as con:
        if con.execute("SELECT duration FROM shorts WHERE id=?", (s["id"],)).fetchone()[0] is not None:
            break
    FAKE[pid2]["duration"] = 21.0
    time.sleep(0.05)
with closing(m.db()) as con:
    assert con.execute("SELECT duration FROM shorts WHERE id=?", (s["id"],)).fetchone()[0] == 21.0
m.DURATION_BACKFILL_DELAYS = ()
ok("late length: filled in once from Cloudinary, never overwrites a known length, errors only mean 'try later'")

# ---- 5. the independent checks still work (duration is not a substitute for them) ------------------------------------------
before = shorts_count(A)
assert code(publish, A, 20, None, size=101 * MB) == 413 and f"users/{A}/short_20" in DESTROYED       # too big -> refused AND deleted
assert code(publish, A, 21, 30.0, fmt="avi") == 415 and f"users/{A}/short_21" in DESTROYED          # wrong type
assert code(publish, A, 22, None, size=0) == 413                                                    # empty file
assert code(m.create_short, m.NewShort(public_id=f"users/{B}/short_x"), A) == 403                  # not your upload
assert code(m.create_short, m.NewShort(public_id=f"users/{A}/short_nowhere"), A) == 422             # nothing was uploaded
assert code(m.sign_upload, m.SignRequest(purpose="short", size_bytes=101 * MB), A) == 413
assert code(m.validate_short, m.ShortMeta(size_mb=5, mime="image/png"), A) == 415
assert code(m.validate_short, m.ShortMeta(duration_seconds=7200, size_mb=101, mime="video/mp4"), A) == 413
assert shorts_count(A) == before                                                                    # failures made no post
m.FREE_MEDIA_MB = 8
assert code(publish, B, 24, None, size=5 * MB) == 200
assert code(publish, B, 25, None, size=5 * MB) == 413                                               # daily allowance still enforced
m.FREE_MEDIA_MB = 500
assert shorts_count(B) == 1
ok("size, type, ownership, quota and existence checks still refuse; a refused clip makes no post and is deleted")

# ---- 6. a retry never makes a duplicate post and is never counted twice ----------------------------------------------------
pid = upload(B, 30, None)
m._rate.clear(); first = m.create_short(m.NewShort(public_id=pid), B)
used = m.media_quota(B)["used_mb"]; n = shorts_count(B)
m._rate.clear(); again = m.create_short(m.NewShort(public_id=pid), B)
assert again["id"] == first["id"] and shorts_count(B) == n and m.media_quota(B)["used_mb"] == used
ok("retry of the same upload: same post, no duplicate, quota counted once")

# ---- 7. watch events and ranking cope with an unknown length ---------------------------------------------------------------
import ranker as R  # noqa: E402
assert R.watch_ratio("complete", None, None) == 1.0
assert R.watch_ratio("watch", 5000, None) is None and R.watch_ratio("skip", 5000, None) is None
assert R.watch_ratio("watch", 5000, 0) is None
assert R.watch_ratio("watch", 5000, 10) == 0.5
unknown = [x for x in m.shorts_feed(None, 30, B)["shorts"] if x["duration"] is None and x["username"] == "@anna"][0]
m._rate.clear()
r = m.record_interaction(m.InteractionEvent(content_type="short", content_id=unknown["id"], event="watch", idem_key="k-unknown-1", value_ms=3_000_000), B)
assert r.get("recorded") is True                                                 # nothing to compare with: not called 'impossible'
known = [x for x in m.shorts_feed(None, 30, B)["shorts"] if x["duration"] == 61.0][0]
m._rate.clear()
r = m.record_interaction(m.InteractionEvent(content_type="short", content_id=known["id"], event="watch", idem_key="k-known-1", value_ms=3_000_000), B)
assert r.get("recorded") is False and r.get("reason") == "impossible"           # a known length is still used to spot fake events
assert m.shorts_for_you(None, 10, B) is not None
ok("watch events and the For You ranking work when the length is unknown")
print("test_unknown_duration: all checks passed")
