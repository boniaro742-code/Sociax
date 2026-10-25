"""Step 39 (plan v3.9 section 31): the For You ranker. Part 1 tests the pure scoring code; part 2 tests the real feed
against a database: privacy FIRST, then signals, diversity, exploration, stable paging, safe fallback.
Run:  cd backend && DEV_AUTH=1 python test_ranker.py
Cloudinary is replaced by a fake: no account and no network needed."""
import json, math, os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ.pop("RANK_WEIGHTS", None)
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t0.db")
import main as m  # noqa: E402
import ranker as R  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
MB = 1024 * 1024
SIZES, VIDEOS = {}, {}
m.asset_bytes = lambda pid: SIZES[pid] if pid in SIZES else (_ for _ in ()).throw(HTTPException(502, "Could not verify the upload"))
m.video_info = lambda pid: VIDEOS[pid] if pid in VIDEOS else (_ for _ in ()).throw(HTTPException(422, "Upload not found"))
m.destroy_video = lambda pid: None
m.kick_cloud_cleanup = lambda: None


def ok(msg):
    print("PASS", msg)


def close(a, b, tol=0.01):
    return abs(a - b) <= tol


# =====================================================================
# PART 1: pure scoring code
# =====================================================================
W = R.DEFAULT_WEIGHTS
assert close(sum(W.values()), 1.0) and W == {"interest": .30, "watch": .20, "fresh": .15, "engage": .15, "follow": .10, "diversity": .10}
assert m.RANK_WEIGHTS == W
ok("default weights are the plan's 30 / 20 / 15 / 15 / 10 / 10 and add up to 100%")

assert R.parse_weights("interest=0.4,watch=0.2,fresh=0.1,engage=0.1,follow=0.1,diversity=0.1")["interest"] == 0.4
for bad in ["interest=0.5", "interest=0.3,watch=0.2,fresh=0.15,engage=0.15,follow=0.1,diversity=0.2",   # missing keys / total 1.1
            "interest=-0.1,watch=0.4,fresh=0.2,engage=0.2,follow=0.2,diversity=0.1", "garbage", "a=b", "   ", None]:
    assert R.parse_weights(bad) == W, bad
ok("RANK_WEIGHTS: a valid setting is used; a wrong one (missing key, negative, total not 100%, garbage) falls back to the plan's weights")

assert close(R.freshness(0), 100) and close(R.freshness(48 * 3600), 50) and close(R.freshness(96 * 3600), 25)
assert R.freshness(1) > R.freshness(3600) > R.freshness(86400) > R.freshness(86400 * 30) >= 0 and close(R.freshness(-500), 100)
assert R.engagement(0) == 0 and R.engagement(1) < R.engagement(10) < R.engagement(50) and close(R.engagement(50), 100) and R.engagement(10 ** 6) == 100
assert R.interest(0) == 0 and close(R.interest(6), 50) and R.interest(1000) < 100 and R.interest(5) < R.interest(50)
ok("freshness halves every 48 h (no cliff), engagement and interest are 0..100 and saturate")

assert R.watch_ratio("complete", None, 10) == 1.0 and R.watch_ratio("watch", 5000, 10) == 0.5 and R.watch_ratio("watch", 90000, 10) == 1.0
assert R.watch_ratio("watch", None, 10) is None and R.watch_ratio("watch", 0, 10) is None
assert R.watch_ratio("skip", 300, 10) is None and R.watch_ratio("skip", None, 10) is None            # accidental skips say nothing
assert close(R.watch_ratio("skip", 5000, 10), 0.25) and R.watch_ratio("impression", 5000, 10) is None
assert close(R.watch_ratio("watch", 3000, 6), R.watch_ratio("watch", 5000, 10) * 0 + 0.5)              # normalised for the clip's length
assert R.watch_quality(1.0, 2) == R.NEUTRAL and R.watch_quality(None, 50) == R.NEUTRAL and R.watch_quality(0.8, 3) == 80
ok("watch quality: normalised for video length, accidental skips ignored, fewer than 3 viewers = neutral")

assert R.repeat_penalty(None, False) == 0 and R.repeat_penalty(3600, False) == 40 and R.repeat_penalty(5 * 86400, False) == 15
assert R.repeat_penalty(30 * 86400, False) == 0 and R.repeat_penalty(3600, True) == 65
assert R.quality_penalty(0) == 0 and R.quality_penalty(2) == 20 and R.quality_penalty(50) == 30
assert R.diversity(0) == 100 and R.diversity(1) == 50 and R.diversity(3) == 25
ok("repeat penalty (48 h / 14 days / skipped), quality penalty (capped at 30) and diversity curve")


def it(i, creator, **k):
    return R.Item(id=i, creator=creator, age_seconds=k.pop("age", 3600), **k)


a = [it(1, "a", engage_points=40), it(2, "b", engage_points=5), it(3, "c")]
assert R.order(a, W) == R.order(list(reversed(a)), W) == R.order(a, W)
ok("ordering is deterministic (same input, same order, whatever order the items arrive in)")

pool = [it(i, "x", engage_points=40) for i in range(1, 7)] + [it(10, "y"), it(11, "z")]
res = R.order(pool, W)
by = {p.id: p.creator for p in pool}
last_other = max(res.index(10), res.index(11))
run, longest = 1, 1
for p, q in zip(res[:last_other + 1], res[1:last_other + 1]):
    run = run + 1 if by[p] == by[q] else 1
    longest = max(longest, run)
assert longest <= 2, res
ok("no more than 2 items in a row from one creator while other creators are available")

pool = [it(i, "x%d" % (i % 3), engage_points=40) for i in range(1, 10)] + [it(50, "newbie")]
res = R.order(pool, W)
assert res.index(50) <= 4, res
assert 50 not in R.order(pool, W, explore_every=0)[:9]
ok("a new / small creator gets a protected exploration slot within the first 5 places (and would be last without it)")

# =====================================================================
# PART 2: the real feed
# =====================================================================
COUNTER = [0]


def fresh_db(names):
    m.DB_PATH = os.path.join(tempfile.mkdtemp(), "t.db")
    m.init_db()
    m.init_stories_db()                       # tables that main.py creates in its own later start-up steps
    with closing(m.db()) as con:
        con.execute("""CREATE TABLE IF NOT EXISTS payments(
            tx_ref TEXT PRIMARY KEY, uid TEXT NOT NULL, provider TEXT NOT NULL DEFAULT 'Chapa', product TEXT NOT NULL,
            amount TEXT NOT NULL, currency TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, paid_at INTEGER,
            pro_started_at INTEGER, pro_expires_at INTEGER, provider_ref TEXT, granted INTEGER NOT NULL DEFAULT 0)""")
        con.commit()
    for n in names:
        m._rate.clear()
        m.onboard(m.Onboarding(display_name=n, username=n, age=20), "dev-" + n)


def U(name):
    return "dev-" + name


def post(name, audience="public", age=3600):
    COUNTER[0] += 1
    pid = f"users/{U(name)}/post_{COUNTER[0]}"
    SIZES[pid] = 1 * MB
    m._rate.clear()
    row = m.create_post(m.NewPost(public_id=pid, caption="p", audience=audience), U(name))
    with closing(m.db()) as con:
        con.execute("UPDATE posts SET created_at=? WHERE id=?", (int(time.time()) - age, row["id"])); con.commit()
    return row["id"]


def video(name, audience="public", age=3600, seconds=10.0):
    COUNTER[0] += 1
    pid = f"users/{U(name)}/short_{COUNTER[0]}"
    VIDEOS[pid] = {"bytes": 3 * MB, "duration": seconds, "format": "mp4"}
    m._rate.clear()
    row = m.create_short(m.NewShort(public_id=pid, caption="v", audience=audience), U(name))
    with closing(m.db()) as con:
        con.execute("UPDATE shorts SET created_at=? WHERE id=?", (int(time.time()) - age, row["id"])); con.commit()
    return row["id"]


def boost(ctype, cid, n):
    t, c = ("likes", "post_id") if ctype == "post" else ("short_likes", "short_id")
    with closing(m.db()) as con:
        for i in range(n):
            con.execute(f"INSERT OR IGNORE INTO {t}({c}, uid) VALUES (?,?)", (cid, f"fake-{i}"))
        con.commit()


def ev(name, ctype, cid, event, key, value=None):
    m._rate.clear()
    return m.record_interaction(m.InteractionEvent(content_type=ctype, content_id=cid, event=event, idem_key=key, value_ms=value), U(name))


def fb(name, action, ctype=None, cid=None):
    m._rate.clear()
    return m.recommendation_feedback(m.RecFeedback(action=action, content_type=ctype, content_id=cid), U(name))


def rk(name, ctype="post"):
    with closing(m.db()) as con:
        return m.rank_candidates(con, U(name), ctype)


def page(name, cursor=None, limit=50, ctype="post"):
    m._rate.clear()
    fn = m.feed_for_you if ctype == "post" else m.shorts_for_you
    return fn(cursor, limit, U(name))


def ids_of(res):
    return [x["id"] for x in res["posts" if "posts" in res else "shorts"]]


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code, (e.detail.get("code") if isinstance(e.detail, dict) else None)


# ---- S1: privacy comes FIRST. Nothing forbidden can be pulled in by a big score
fresh_db(["viewer", "pubby", "folly", "privy", "blocky", "mody", "hidy", "repy"])
p_pub = post("pubby")
p_fol = post("folly", "followers"); boost("post", p_fol, 100)
m.update_me(m.ProfileUpdate(is_private=True), U("privy")); p_priv = post("privy"); boost("post", p_priv, 100)
p_blk = post("blocky"); boost("post", p_blk, 100); m.block_user("viewer", U("blocky"))
p_mod = post("mody"); boost("post", p_mod, 100)
with closing(m.db()) as con:
    con.execute("UPDATE posts SET moderation_state='restricted' WHERE id=?", (p_mod,)); con.commit()
p_hid = post("hidy"); boost("post", p_hid, 100); fb("viewer", "hide_creator", "post", p_hid)
p_rep = post("repy"); boost("post", p_rep, 100)
m._rate.clear(); m.report_post(p_rep, m.Report(reason="spam"), U("viewer"))
assert rk("viewer") == [p_pub] and ids_of(page("viewer")) == [p_pub]
m._rate.clear(); m.follow("folly", U("viewer"))
assert set(rk("viewer")) == {p_pub, p_fol}
ok("privacy first: followers-only, private account, blocked, restricted, hidden and self-reported items never appear, however popular (control: a follower sees it)")

# ---- S2: interest (by creator, from MY own actions)
fresh_db(["viewer", "cone", "ctwo"])
c1_old = post("cone", age=10 * 86400); c1_new = post("cone"); c2_new = post("ctwo")
m._rate.clear(); m.like(c1_old, U("viewer"))
r = rk("viewer")
assert r.index(c1_new) < r.index(c2_new)
ok("interest: after I liked a creator's earlier post, that creator's new post ranks above an otherwise identical one")

# ---- S3: engagement counts each person once and never the creator; spam does not help
fresh_db(["viewer", "caaa", "cbbb", "cself", "czero"])
pa, pb = post("caaa"), post("cbbb")
boost("post", pa, 10)
r = rk("viewer"); assert r.index(pa) < r.index(pb)
p_self = post("cself"); p_zero = post("czero")                  # p_zero is the NEWER id
with closing(m.db()) as con:
    con.execute("INSERT INTO likes(post_id, uid) VALUES (?,?)", (p_self, U("cself"))); con.commit()
    con.execute("INSERT OR IGNORE INTO likes(post_id, uid) VALUES (?,?)", (p_self, U("cself"))); con.commit()
r = rk("viewer"); assert r.index(p_zero) < r.index(p_self), "the creator's own like must not count"
ok("engagement: 10 different likers beat none; the creator's own like counts for nothing; a repeated like is still one")

# ---- S4: freshness is gradual and gives a new post a fair chance
fresh_db(["viewer", "cold", "cnew", "cpop", "cfresh"])
p_old, p_new = post("cold", age=3 * 86400), post("cnew")
r = rk("viewer"); assert r.index(p_new) < r.index(p_old)
p_popold = post("cpop", age=20 * 86400); boost("post", p_popold, 20); p_fresh0 = post("cfresh")
r = rk("viewer"); assert r.index(p_fresh0) < r.index(p_popold)
ok("freshness: newer beats older; a brand-new post with no engagement still beats a popular 20-day-old one")

# ---- S5: repeats are pushed down
fresh_db(["viewer", "cr1", "cr2", "cr3"])
q1, q2, q3 = post("cr1"), post("cr2"), post("cr3")             # q3 is the newest, so it would win ties
assert rk("viewer")[0] == q3
ev("viewer", "post", q3, "impression", "key-seen-0001", 3000)
r = rk("viewer"); assert r.index(q3) > r.index(q2)
ev("viewer", "post", q2, "skip", "key-skip-0001", 2000)
r = rk("viewer"); assert r[0] == q1 and r[-1] in (q2, q3)
ok("an item I already saw (and one I skipped) moves down; an unseen one comes first")

# ---- S6: watch quality for short videos (normalised, needs 3 different viewers, accidental skips ignored)
names = ["viewer", "vgood", "vbad", "vfew", "vacc", "wx1", "wx2", "wx3", "wx4", "wx5"]
fresh_db(names)
v_good, v_bad, v_few, v_acc = video("vgood"), video("vbad"), video("vfew"), video("vacc")
for i, w in enumerate(["wx1", "wx2", "wx3"]):
    ev(w, "short", v_good, "complete", f"key-good-{i:04d}")
    ev(w, "short", v_bad, "skip", f"key-bad--{i:04d}", 1500)                       # left after 1.5 s of 10 s
for i, w in enumerate(["wx1", "wx2"]):
    ev(w, "short", v_few, "complete", f"key-few--{i:04d}")                          # only 2 viewers: not enough evidence
for i, w in enumerate(["wx1", "wx2", "wx3", "wx4", "wx5"]):
    ev(w, "short", v_acc, "skip", f"key-acc--{i:04d}", 300)                         # accidental skips: they say nothing
r = rk("viewer", "short")
assert r.index(v_good) == 0 and r.index(v_bad) == len(r) - 1, r
assert r.index(v_few) < r.index(v_bad) and r.index(v_acc) < r.index(v_bad)
ok("watch quality: a clip most people finish ranks first, one people leave early ranks last; 2 viewers or accidental skips do not decide anything")

# ---- S7: reports lower an item (a signal, not proof)
fresh_db(["viewer", "cok", "cbad", "wx1", "wx2", "wx3"])
p_ok = post("cok"); p_bad = post("cbad")                      # p_bad is NEWER
assert rk("viewer")[0] == p_bad
for w in ("wx1", "wx2", "wx3"):
    m._rate.clear(); m.report_post(p_bad, m.Report(reason="spam"), U(w))
r = rk("viewer"); assert r == [p_ok, p_bad]
ok("an item reported by 3 different people ranks below an identical clean one (still visible: a report is a signal, not a verdict)")

# ---- S8: diversity (real database)
fresh_db(["viewer", "xxx", "yyy", "zzz"])
xs = [post("xxx") for _ in range(6)]
for x in xs:
    boost("post", x, 40)
y1, z1 = post("yyy"), post("zzz")
r = rk("viewer")
owner = {i: ("x" if i in xs else "y" if i == y1 else "z") for i in r}
last = max(r.index(y1), r.index(z1)); run = longest = 1
for a_, b_ in zip(r[:last + 1], r[1:last + 1]):
    run = run + 1 if owner[a_] == owner[b_] else 1
    longest = max(longest, run)
assert longest <= 2 and len(r) == 8
ok("diversity: six popular photos from one creator are spread out with other creators")

# ---- S9: exploration slot (real database)
fresh_db(["viewer", "xc1", "xc2", "xc3", "newb"])
for n in ("xc1", "xc2", "xc3"):
    for _ in range(3):
        boost("post", post(n), 40)
p_new_creator = post("newb")
assert rk("viewer").index(p_new_creator) <= 4
ok("a new creator with no engagement still reaches the first 5 places (exploration)")

# ---- S10: paging from a snapshot is stable
fresh_db(["viewer", "cc1", "cc2", "cc3", "cc4", "cc5"])
allp = [post(f"cc{k}") for k in (1, 2, 3, 4) for _ in range(3)]
first = page("viewer", None, 5)
assert first["ranked"] is True and first["ranker"] == "v1" and first["next_cursor"] and len(first["posts"]) == 5
got, cur, pages = ids_of(first), first["next_cursor"], 1
new_one = post("cc5")                                            # published while I scroll
while cur:
    pg = page("viewer", cur, 5); got += ids_of(pg); cur = pg["next_cursor"]; pages += 1
assert pages == 3 and len(got) == len(set(got)) == 12 and set(got) == set(allp) and new_one not in got
refresh = page("viewer", None, 50)
assert new_one in ids_of(refresh) and len(ids_of(refresh)) == 13 and refresh["next_cursor"] is None
assert ids_of(page("viewer", None, 50)) == ids_of(refresh)       # same input, same order
ok("paging: no repeats, nothing reshuffled by a new post, refresh shows it, the same input gives the same list")

# ---- S11: every page re-checks privacy (things that changed after the snapshot)
cur1 = first["next_cursor"]
base2 = ids_of(page("viewer", cur1, 5))
with closing(m.db()) as con:
    owner_of = {r_["id"]: r_["uid"] for r_ in con.execute("SELECT id, uid FROM posts")}


def only_removed(before_ids, after_ids):
    return set(after_ids) <= set(before_ids) and after_ids == [i for i in before_ids if i in after_ids]


victim = base2[0]; v_owner = owner_of[victim][4:]
m._rate.clear(); m.block_user("viewer", U(v_owner))                                                    # I block them: gone at once
a1 = ids_of(page("viewer", cur1, 5))
assert all(owner_of[i] != U(v_owner) for i in a1) and victim not in a1 and only_removed(base2, a1)
rest = [i for i in a1]
second_owner = owner_of[rest[0]][4:]
m._rate.clear(); m.update_me(m.ProfileUpdate(is_private=True), U(second_owner))                         # they go private: gone
a2 = ids_of(page("viewer", cur1, 5))
assert all(owner_of[i] != U(second_owner) for i in a2) and only_removed(a1, a2)
assert len(base2) == 5 and a2 and len(a2) < len(a1) <= len(base2)          # (so the next steps really test something)
gone = a2[0]
fb("viewer", "not_interested", "post", gone)                                                             # I hide one: gone
a3 = ids_of(page("viewer", cur1, 5)); assert gone not in a3 and only_removed(a2, a3) and len(a3) == len(a2) - 1
if a3:
    m._rate.clear(); m.delete_post(a3[0], owner_of[a3[0]])                                               # deleted: gone
    a4 = ids_of(page("viewer", cur1, 5)); assert a3[0] not in a4 and only_removed(a3, a4) and len(a4) == len(a3) - 1
ok("a page is checked again when served: blocked, private, hidden and deleted items disappear, nothing new appears")

# ---- S12: bad / foreign / expired cursors
tok = first["next_cursor"].rpartition(".")[0]
assert code(page, "viewer", "abc") == (410, "cursor_expired")
assert code(page, "viewer", "nothing.5") == (410, "cursor_expired")
assert code(page, "viewer", tok + ".99999999") == (410, "cursor_expired")
assert code(page, "cc1", first["next_cursor"]) == (410, "cursor_expired")                    # somebody else's list
assert code(page, "viewer", first["next_cursor"], 5, "short") == (410, "cursor_expired")    # a photo list is not a video list
with closing(m.db()) as con:
    con.execute("UPDATE feed_snapshots SET created_at=? WHERE token=?", (int(time.time()) - m.SNAPSHOT_TTL_S - 5, tok)); con.commit()
assert code(page, "viewer", first["next_cursor"]) == (410, "cursor_expired")
ok("garbage, foreign, wrong-type and expired cursors all answer 410 cursor_expired (nothing leaks)")

# ---- S13: if ranking breaks, the newest-first feed is served (and no error text)
fresh_db(["viewer", "faa", "fbb"])
f1, f2 = post("faa"), post("fbb")
real = m.rank_candidates
m.rank_candidates = lambda *a_, **k_: (_ for _ in ()).throw(RuntimeError("secret internal detail"))
try:
    res = page("viewer")
finally:
    m.rank_candidates = real
assert res["ranked"] is False and res["fallback"] == "chronological" and res["next_cursor"] is None and res["ranker"] is None
assert ids_of(res) == [f2, f1] and "secret" not in json.dumps(res)
sv = video("faa"); real_s = m.rank_candidates
m.rank_candidates = lambda *a_, **k_: (_ for _ in ()).throw(RuntimeError("boom"))
try:
    res = page("viewer", None, 10, "short")
finally:
    m.rank_candidates = real_s
assert res["ranked"] is False and ids_of(res) == [sv]
ok("if ranking fails the answer is the plain newest-first feed (ranked: false), never an error message")

# ---- S14: Following is separate and unchanged (newest first); /feed still works as before
fresh_db(["viewer", "faa", "fbb"])
m.follow("faa", U("viewer")); m.follow("fbb", U("viewer"))
g1, g2, g3 = post("faa"), post("fbb"), post("faa")
assert [p["id"] for p in m.feed_following(None, 20, U("viewer"))["posts"]] == [g3, g2, g1]
assert [p["id"] for p in m.feed("for_you", None, 20, U("viewer"))["posts"]] == [g3, g2, g1]
ok("Following and the old /feed are still newest-first")

# ---- S15: short videos use the same ranker, with the same privacy
fresh_db(["viewer", "saa", "sbb", "scc"])
s1, s2 = video("saa"), video("sbb"); s_fo = video("scc", "followers"); boost("short", s_fo, 100)
res = page("viewer", None, 1, "short")
assert res["ranked"] is True and len(res["shorts"]) == 1 and res["next_cursor"]
pg2 = page("viewer", res["next_cursor"], 1, "short")
assert sorted(ids_of(res) + ids_of(pg2)) == sorted([s1, s2]) and pg2["next_cursor"] is None and s_fo not in ids_of(res) + ids_of(pg2)
ok("GET /shorts/for-you: ranked, paged, and a followers-only video is never served to a non-follower")

# ---- S16: nothing to show, snapshots cleaned on reset / account deletion / per-user cap
fresh_db(["viewer", "empty", "other"])
e = page("empty"); assert e["posts"] == [] and e["next_cursor"] is None and e["ranked"] is True
for _ in range(8):
    page("viewer")
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM feed_snapshots WHERE uid=?", (U("viewer"),)).fetchone()[0] == m.SNAPSHOTS_PER_USER
fb("viewer", "reset")
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM feed_snapshots WHERE uid=?", (U("viewer"),)).fetchone()[0] == 0
page("other"); m._rate.clear(); m.delete_account(U("other"))
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM feed_snapshots WHERE uid=?", (U("other"),)).fetchone()[0] == 0
ok("empty feed is fine; at most 5 saved lists per person; reset and account deletion remove them")

# ---- S17: route order (a static check): /shorts/for-you must be registered before /shorts/{short_id}
src = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "main.py"), encoding="utf-8").read()
assert src.index('@app.get("/shorts/for-you")') < src.index('@app.get("/shorts/{short_id}")')
ok("/shorts/for-you is registered before /shorts/{short_id} (otherwise it would be read as a video number)")

print("test_ranker: all checks passed")
