"""Step 38 (plan v3.9 sections 31, 32, 35, 38): interaction events, recommendation feedback, hidden items, GET /quota.
Run:  cd backend && DEV_AUTH=1 python test_events.py
Cloudinary is replaced by a fake: no account and no network needed."""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402
from pydantic import ValidationError  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
MB = 1024 * 1024
SIZES, VIDEOS = {}, {}
m.asset_bytes = lambda pid: SIZES[pid] if pid in SIZES else (_ for _ in ()).throw(HTTPException(502, "Could not verify the upload"))
m.video_info = lambda pid: VIDEOS[pid] if pid in VIDEOS else (_ for _ in ()).throw(HTTPException(422, "Upload not found"))
m.destroy_video = lambda pid: None
m.kick_cloud_cleanup = lambda: None

A, B, C, D = "dev-a", "dev-b", "dev-c", "dev-d"       # A publishes. B and C watch. D is blocked by A.


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code
    except ValidationError:          # real pydantic refuses a bad body before the endpoint runs: same meaning as 422
        return 422


def photo(uid, n, audience="public"):
    pid = f"users/{uid}/post_{n}"
    SIZES[pid] = 1 * MB
    m._rate.clear()
    return m.create_post(m.NewPost(public_id=pid, caption=f"photo {n}", audience=audience), uid)


def video(uid, n, audience="public", seconds=6.0):
    pid = f"users/{uid}/short_{n}"
    VIDEOS[pid] = {"bytes": 3 * MB, "duration": seconds, "format": "mp4"}
    m._rate.clear()
    return m.create_short(m.NewShort(public_id=pid, caption=f"video {n}", audience=audience), uid)


def ev(uid, ctype, cid, event, key, value=None):
    m._rate.clear()
    return m.record_interaction(m.InteractionEvent(content_type=ctype, content_id=cid, event=event, idem_key=key, value_ms=value), uid)


def count(where="1=1", args=()):
    with closing(m.db()) as con:
        return con.execute(f"SELECT COUNT(*) FROM interaction_events WHERE {where}", args).fetchone()[0]


def fb(uid, action, ctype=None, cid=None):
    m._rate.clear()
    return m.recommendation_feedback(m.RecFeedback(action=action, content_type=ctype, content_id=cid), uid)


def feed_ids(uid, tab="for_you"):
    return [p["id"] for p in m.feed(tab, None, 50, uid)["posts"]]


def short_ids(uid):
    return [s["id"] for s in m.shorts_feed(None, 30, uid)["shorts"]]


def ok(msg):
    print("PASS", msg)


for u, n in [(A, "anna"), (B, "ben"), (C, "cara"), (D, "dan")]:
    m.onboard(m.Onboarding(display_name=n, username=n, age=20), u)
m.block_user("dan", A)

p1, p2 = photo(A, 1), photo(A, 2)
v1, v2 = video(A, 1), video(A, 2)
v_fo = video(A, 3, "followers")                                   # followers-only: B and C are NOT followers

# ---- 1. a watch event is stored with a SERVER time, once
t0 = int(time.time())
r = ev(B, "short", v1["id"], "watch", "key-watch-0001", 4000)
assert r == {"ok": True, "recorded": True}, r
with closing(m.db()) as con:
    row = con.execute("SELECT * FROM interaction_events WHERE uid=?", (B,)).fetchone()
assert row["event"] == "watch" and row["value_ms"] == 4000 and row["source"] == "app" and row["version"] == 1
assert t0 <= row["created_at"] <= int(time.time()) + 1
ok("a watch event is stored with the server time, a version and its source")

# ---- 2. the same key again (a retry) is counted once; a different key is a new event
r = ev(B, "short", v1["id"], "watch", "key-watch-0001", 4000)
assert r["recorded"] is False and r["duplicate"] is True and count("uid=?", (B,)) == 1
assert ev(B, "short", v1["id"], "watch", "key-watch-0002", 3000)["recorded"] is True and count("uid=?", (B,)) == 2
assert ev(C, "short", v1["id"], "watch", "key-watch-0001", 3000)["recorded"] is True      # another account may use the same key text
ok("a retry with the same key is counted once (idempotency), per account")

# ---- 3. impressions count only after the visible-time threshold (pre-loaded items do not count)
before = count()
assert ev(B, "post", p1["id"], "impression", "key-imp-0001", 300) == {"ok": True, "recorded": False, "reason": "below_threshold"}
assert ev(B, "post", p1["id"], "impression", "key-imp-0002") ["reason"] == "below_threshold"          # no value at all
assert count() == before
assert ev(B, "post", p1["id"], "impression", "key-imp-0003", 1500)["recorded"] is True
ok("an impression under 1 s (or without a time) is not counted; a real one is")

# ---- 4. impossible or empty watch values are ignored (200, not an error, nothing stored)
before = count()
assert ev(B, "short", v1["id"], "watch", "key-big-0001", 999999)["reason"] == "impossible"            # 6 s clip, 1000 s in one event
assert ev(B, "short", v1["id"], "watch", "key-zero-001", 0)["reason"] == "empty_watch"
assert count() == before
assert ev(B, "short", v1["id"], "watch", "key-loop-001", 6000 * 3)["recorded"] is True               # looping 3 times is real
ok("impossible / empty watch times are ignored, normal replays are kept")

# ---- 5. input validation (422): unknown event, phone-sent likes, bad key, video-only events on photos, huge value
assert code(ev, B, "post", p1["id"], "like", "key-like-0001") == 422          # likes can NEVER come from the phone
assert code(ev, B, "post", p1["id"], "save", "key-save-0001") == 422
assert code(ev, B, "post", p1["id"], "comment", "key-comm-0001") == 422
assert code(ev, B, "post", p1["id"], "impression", "short", 2000) == 422      # key too short
assert code(ev, B, "post", p1["id"], "impression", "bad key with spaces!!", 2000) == 422
assert code(ev, B, "post", p1["id"], "watch", "key-photo-001", 2000) == 422   # a photo has no watch time
assert code(ev, B, "post", p1["id"], "complete", "key-photo-002") == 422
assert code(ev, B, "short", v1["id"], "watch", "key-huge-001", 10 ** 9) == 422
assert code(ev, B, "post", 0, "impression", "key-id-00001", 2000) == 422
ok("unknown events, phone-sent likes / saves / comments, bad keys, photo watch times and huge values are refused")

# ---- 6. privacy: an event needs an item the person may see (same 404 as a missing one); own content is ignored
assert code(ev, B, "short", v_fo["id"], "impression", "key-fo-00001", 2000) == 404       # followers-only, B does not follow
assert code(ev, B, "short", 99999, "impression", "key-fo-00002", 2000) == 404
assert code(ev, D, "post", p1["id"], "impression", "key-blk-0001", 2000) == 404          # D is blocked by A
assert ev(A, "post", p1["id"], "impression", "key-own-0001", 2000) == {"ok": True, "recorded": False, "reason": "own_content"}
ok("events are refused for hidden / blocked items (same 404 as missing) and ignored for my own content")

# ---- 7. share and skip from the phone are accepted; skip may carry the time watched
assert ev(B, "post", p2["id"], "share", "key-share-001")["recorded"] is True
assert ev(B, "short", v2["id"], "skip", "key-skip-0001", 800)["recorded"] is True
ok("share and skip are accepted from the phone")

# ---- 8. like / save / comment / report are recorded BY THE SERVER, once, and not for my own content
m._rate.clear()
m.like(p1["id"], B); m.like(p1["id"], B)                                  # a double tap
m.bookmark(p1["id"], B)
m.add_comment(p1["id"], m.NewComment(text="nice"), B)
m.add_comment(p1["id"], m.NewComment(text="very nice"), B)                 # a second comment: same item, counted once
m.like_short(v1["id"], B); m.bookmark_short(v1["id"], B)
m.add_short_comment(v1["id"], m.NewComment(text="wow"), B)
m.report_short(v2["id"], m.Report(reason="spam"), B)
srv = {(r["content_type"], r["content_id"], r["event"]) for r in m.db().execute("SELECT * FROM interaction_events WHERE uid=? AND source='server'", (B,))}
assert srv == {("post", p1["id"], "like"), ("post", p1["id"], "save"), ("post", p1["id"], "comment"),
               ("short", v1["id"], "like"), ("short", v1["id"], "save"), ("short", v1["id"], "comment"),
               ("short", v2["id"], "report")}, srv
assert count("uid=? AND source='server'", (B,)) == 7
m.like(p1["id"], A)                                                         # the owner likes their own photo: not a taste signal
assert count("uid=?", (A,)) == 0
ok("like / save / comment / report are recorded by the server (once, never for own content)")

# ---- 9. "Not interested": that item leaves MY feed only
assert p1["id"] in feed_ids(B) and p1["id"] in feed_ids(C)
assert fb(B, "not_interested", "post", p1["id"]) == {"ok": True, "action": "not_interested"}
assert p1["id"] not in feed_ids(B) and p2["id"] in feed_ids(B) and p1["id"] in feed_ids(C)
assert fb(B, "not_interested", "short", v1["id"])["ok"]
assert v1["id"] not in short_ids(B) and v2["id"] in short_ids(B) and v1["id"] in short_ids(C)
ok("'Not interested' removes that photo / video from my feed only")

# ---- 10. "Hide creator": all of that creator's items leave MY For You and Following; others are unaffected
m.follow("anna", C)
assert p2["id"] in feed_ids(C, "following") and p2["id"] in feed_ids(C)
assert fb(C, "hide_creator", "post", p2["id"])["ok"]
assert not feed_ids(C) and not feed_ids(C, "following") and not short_ids(C)
assert p2["id"] in feed_ids(A) and p2["id"] in feed_ids(B)          # the creator and other viewers still see everything
assert m.get_post(p2["id"], C)["id"] == p2["id"]                           # a hidden creator is not blocked: the profile / direct link still opens
st = m.recommendation_state(C)
assert st["hidden_creators"] == 1 and st["not_interested"] == 0 and st["retention_days"] == 90
ok("'Hide creator' removes that creator from my For You, Following and Short feeds; nobody else is affected")

# ---- 11. hiding never overrides privacy and cannot be used on what I may not see or on myself
assert code(fb, D, "hide_creator", "post", p2["id"]) == 404                # blocked: same 404
assert code(fb, B, "not_interested", "short", v_fo["id"]) == 404           # followers-only, not a follower
assert code(fb, A, "hide_creator", "post", p2["id"]) == 422                # not my own content
assert code(fb, B, "not_interested") == 422                                # content missing
assert code(fb, B, "hide_creator", "photo", 1) == 422                      # bad type
ok("feedback needs a visible item of someone else (404 / 422 otherwise)")

# ---- 12. reset recommendation history: my events, my marks and my hidden creators are gone; others keep theirs
assert count("uid=?", (B,)) > 0
assert fb(B, "reset") == {"ok": True, "action": "reset"}
assert count("uid=?", (B,)) == 0
assert p1["id"] in feed_ids(B) and v1["id"] in short_ids(B)
assert count("uid=?", (C,)) > 0
fb(C, "reset")
assert p2["id"] in feed_ids(C) and m.recommendation_state(C)["hidden_creators"] == 0
ok("'Reset recommendation history' wipes my events, 'Not interested' marks and hidden creators (and undoes a hide)")

# ---- 13. named feed addresses: /feed/following is the same code as /feed; /feed/for-you is the ranked list (Step 39) and obeys the same hiding
m._rate.clear()
assert m.feed_following(None, 20, C) == m.feed("following", None, 20, C)
fy = m.feed_for_you(None, 20, B)
assert fy["ranked"] is True and {p["id"] for p in fy["posts"]} == set(feed_ids(B))
ok("GET /feed/following uses the same code as /feed; GET /feed/for-you shows the same eligible posts (ranked)")

# ---- 14. GET /quota: media + AI in one answer, server numbers only
m._rate.clear()
q = m.quota_all(B)
assert set(q) == {"media", "ai"} and q["media"]["period"] == "day" and q["media"]["limit_mb"] == 500 and q["ai"]["pro"] is False
ok("GET /quota returns today's media and AI allowance together")

# ---- 15. retention: events older than the limit are deleted
with closing(m.db()) as con:
    old = int(time.time()) - (m.EVENT_RETENTION_DAYS + 1) * 86400
    con.execute("INSERT INTO interaction_events(uid, content_type, content_id, event, source, idem_key, version, created_at) "
                "VALUES (?, 'post', ?, 'share', 'app', 'key-old-00001', 1, ?)", (B, p2["id"], old))
    con.commit()
    assert con.execute("SELECT COUNT(*) FROM interaction_events WHERE idem_key='key-old-00001'").fetchone()[0] == 1
    m.purge_old_events(con, force=True); con.commit()
    assert con.execute("SELECT COUNT(*) FROM interaction_events WHERE idem_key='key-old-00001'").fetchone()[0] == 0
ok("events older than the retention limit are deleted")

# ---- 16. deleting a post forgets its events and 'not interested' marks; deleting an account forgets everything about it
ev(C, "post", p2["id"], "share", "key-del-00001")
fb(C, "not_interested", "post", p2["id"])
assert count("content_type='post' AND content_id=?", (p2["id"],)) > 0
m.delete_post(p2["id"], A)
assert count("content_type='post' AND content_id=?", (p2["id"],)) == 0
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM rec_hidden WHERE target=?", (f"post:{p2['id']}",)).fetchone()[0] == 0
ev(C, "short", v2["id"], "watch", "key-del-00002", 2000)
fb(C, "hide_creator", "short", v2["id"])
m._rate.clear()
m.delete_account(C)                                                         # C leaves: everything C produced is gone
assert count("uid=?", (C,)) == 0
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM rec_hidden WHERE uid=?", (C,)).fetchone()[0] == 0
m._rate.clear()
m.delete_account(A)                                                         # A leaves: events about A's videos go, B's hidden-creator marks on A go
assert count("content_type='short'") == 0
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM rec_hidden WHERE kind='creator' AND target=?", (A,)).fetchone()[0] == 0
ok("deleting a post or an account also deletes the events and hide-marks that hang on it")

print("test_events: all checks passed")
