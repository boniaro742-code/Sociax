"""Backend checks for the Cloudinary clean-up queue (Step 24): removed files are really deleted on Cloudinary,
failed tries are repeated, and nothing is lost when Cloudinary is down.
Run:  cd backend && DEV_AUTH=1 python test_cloud_cleanup.py"""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
import httpx  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "cloud", "key", "secret"
m.kick_cloud_cleanup = lambda: None          # the tests run the worker by hand (no background thread)

A = "dev-a"
m.onboard(m.Onboarding(display_name="alice", username="alice", age=20), A)
now = int(time.time())


def media(pid, kind):
    with closing(m.db()) as con:
        con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)", (pid, A, kind, 1000, now))
        con.commit()


def trash():
    with closing(m.db()) as con:
        return {r["public_id"]: (r["resource_type"], r["tries"]) for r in con.execute("SELECT * FROM cloud_trash")}


def ok(msg):
    print("PASS", msg)


# ---- a deleted post goes to the queue as an IMAGE, quota is freed at once
media("post_1", "post")
with closing(m.db()) as con:
    con.execute("INSERT INTO posts(uid, caption, media_public_id, audience, created_at) VALUES (?,?,?,?,?)", (A, "", "post_1", "public", now))
    pid = con.execute("SELECT id FROM posts").fetchone()[0]
    con.commit()
m.delete_post(pid, A)
assert trash() == {"post_1": ("image", 0)}, trash()
ok("deleted post is queued as image")

# ---- a deleted short goes to the queue as a VIDEO
media("short_1", "short")
with closing(m.db()) as con:
    con.execute("INSERT INTO shorts(uid, caption, media_public_id, duration, audience, comments_enabled, created_at) VALUES (?,?,?,?,?,?,?)",
                (A, "", "short_1", 20, "public", 1, now))
    sid = con.execute("SELECT id FROM shorts").fetchone()[0]
    con.commit()
m.delete_short(sid, A)
assert trash()["short_1"] == ("video", 0), trash()
ok("deleted short is queued as video")

# ---- a deleted story and an EXPIRED story are queued
media("story_1", "story"); media("story_2", "story")
with closing(m.db()) as con:
    con.execute("INSERT INTO stories(uid, caption, media_public_id, created_at, expires_at) VALUES (?,?,?,?,?)", (A, "", "story_1", now, now + 3600))
    con.execute("INSERT INTO stories(uid, caption, media_public_id, created_at, expires_at) VALUES (?,?,?,?,?)", (A, "", "story_2", now - 90000, now - 1))
    first = con.execute("SELECT id FROM stories WHERE media_public_id='story_1'").fetchone()[0]
    con.commit()
    m.purge_expired_stories(con)
assert "story_2" in trash() and "story_1" not in trash()
m.delete_story(first, A)
assert trash()["story_1"] == ("image", 0)
ok("deleted + expired stories are queued")

# ---- Cloudinary is down: nothing is lost, tries go up
httpx.RESULT = httpx.HTTPError("down")
assert m.empty_cloud_trash() == 0
assert all(t == 1 for _, t in trash().values()), trash()
ok("failed try keeps the file in the queue (tries + 1)")

# ---- Cloudinary answers "ok": calls use the right endpoint (image / video) and the queue empties
httpx.RESULT = {"result": "ok"}
httpx.CALLS.clear()
assert m.empty_cloud_trash() == 4
assert trash() == {}, trash()
assert sum("/video/destroy" in c for c in httpx.CALLS) == 1 and sum("/image/destroy" in c for c in httpx.CALLS) == 3, httpx.CALLS
ok("queue emptied, video uses /video/destroy, images use /image/destroy")

# ---- "not found" also counts as done; other answers are retried
with closing(m.db()) as con:
    m.trash_media(con, "gone", "post"); con.commit()
httpx.RESULT = {"result": "not found"}
assert m.empty_cloud_trash() == 1 and trash() == {}
with closing(m.db()) as con:
    m.trash_media(con, "stubborn", "post"); con.commit()
httpx.RESULT = {"result": "error"}
for _ in range(m.CLOUD_TRASH_MAX_TRIES - 1):
    m.empty_cloud_trash()
assert "stubborn" in trash()
m.empty_cloud_trash()
assert "stubborn" not in trash()
ok("not found = done; endless failures are dropped after the maximum tries")

# ---- deleting the account queues every file (avatar, post, short, story)
B = "dev-b"
m.onboard(m.Onboarding(display_name="bob", username="bob", age=20), B)
with closing(m.db()) as con:
    for pid_, kind in (("b_av", "avatar"), ("b_post", "post"), ("b_short", "short"), ("b_story", "story")):
        con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)", (pid_, B, kind, 10, now))
    con.commit()
m.delete_account(B)
assert trash() == {"b_av": ("image", 0), "b_post": ("image", 0), "b_short": ("video", 0), "b_story": ("image", 0)}, trash()
ok("account deletion queues all four kinds of file")

# ---- without Cloudinary settings the worker does nothing and keeps the queue
m.CLOUD_NAME = None
assert m.empty_cloud_trash() == 0 and len(trash()) == 4
print("test_cloud_cleanup: all checks passed")
