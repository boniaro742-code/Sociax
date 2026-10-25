"""Step 48: the Go engine inside the REAL feed (main.py). The engine is replaced by a fake that is sometimes honest and sometimes
hostile. Privacy comes first: whatever the engine says, the feed can only contain what the viewer may see, and a lying engine
changes nothing.
Run:  cd backend && DEV_AUTH=1 python test_rank_engine_feed.py       (Cloudinary is faked: no account, no network)"""
import json, os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ.pop("RANK_WEIGHTS", None)
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t0.db")
import main as m  # noqa: E402
import rank_engine as E  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
MB = 1024 * 1024
SIZES = {}
m.asset_bytes = lambda pid: SIZES[pid] if pid in SIZES else (_ for _ in ()).throw(HTTPException(502, "Could not verify the upload"))
m.destroy_video = lambda pid: None
m.kick_cloud_cleanup = lambda: None
COUNTER = [0]


def ok(msg):
    print("PASS", msg)


def U(n):
    return "dev-" + n


def fresh_db(names):
    m.DB_PATH = os.path.join(tempfile.mkdtemp(), "t.db")
    m.init_db()
    m.init_stories_db()
    with closing(m.db()) as con:      # a table that main.py creates in a later start-up step (same as test_ranker.py)
        con.execute("""CREATE TABLE IF NOT EXISTS payments(
            tx_ref TEXT PRIMARY KEY, uid TEXT NOT NULL, provider TEXT NOT NULL DEFAULT 'Chapa', product TEXT NOT NULL,
            amount TEXT NOT NULL, currency TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, paid_at INTEGER,
            pro_started_at INTEGER, pro_expires_at INTEGER, provider_ref TEXT, granted INTEGER NOT NULL DEFAULT 0)""")
        con.commit()
    for n in names:
        m._rate.clear()
        m.onboard(m.Onboarding(display_name=n, username=n, age=20), U(n))


def post(name, audience="public", age=3600):
    COUNTER[0] += 1
    pid = f"users/{U(name)}/post_{COUNTER[0]}"
    SIZES[pid] = 1 * MB
    m._rate.clear()
    row = m.create_post(m.NewPost(public_id=pid, caption="p", audience=audience), U(name))
    with closing(m.db()) as con:
        con.execute("UPDATE posts SET created_at=? WHERE id=?", (int(time.time()) - age, row["id"])); con.commit()
    return row["id"]


def rk(name):
    with closing(m.db()) as con:
        return m.rank_candidates(con, U(name), "post")


def feed(name):
    m._rate.clear()
    return m.feed_for_you(None, 50, U(name))


class Engine:
    """Fake engine: `mode` decides what it answers. Remembers every request it received."""
    def __init__(self, mode="reverse", extra=None):
        self.mode, self.extra, self.seen = mode, extra or [], []

    def __call__(self, url, headers, body, timeout_s):
        sent = json.loads(body)
        self.seen.append(sent)
        ids = [i["id"] for i in sent["items"]]
        if self.mode == "reverse":
            out = list(reversed(ids))
        elif self.mode == "inject":                 # hostile: also names ids that the viewer may NOT see
            out = ids + list(self.extra)
        elif self.mode == "drop":
            out = ids[:-1]
        else:
            raise AssertionError(self.mode)
        if sent["limit"]:
            out = out[:sent["limit"]]
        return 200, json.dumps({"ids": out}).encode()


def use(engine):
    m.rank_engine.client = E.EngineClient(url="https://engine.example", token="t" * 40, transport=engine)
    return m.rank_engine.client


# ---- 1. engine OFF (the default): exactly the Python ranking
fresh_db(["viewer", "aaa", "bbb", "ccc"])
ids = [post("aaa"), post("bbb"), post("ccc", age=86400 * 5)]
m.rank_engine.client = E.EngineClient(url="", token="")
py = rk("viewer")
assert sorted(py) == sorted(ids)
ok("engine off by default: the feed is ranked by Python")

# ---- 2. engine ON and honest: its order is used and paging works
eng = Engine("reverse")
cl = use(eng)
got = rk("viewer")
assert got == list(reversed([i["id"] for i in eng.seen[0]["items"]])) and sorted(got) == sorted(ids) and cl.stats["ok"] == 1
res = feed("viewer")
assert [x["id"] for x in res["posts"]] == list(reversed([i["id"] for i in eng.seen[1]["items"]])) and res["ranked"] is True
ok("engine on: its order is the feed order (and the first page / cursor still work)")

# ---- 3. privacy: nothing the viewer may not see is ever SENT to the engine, and the engine cannot add it
fresh_db(["viewer", "pubby", "folly", "privy", "blocky", "hidy"])
p_pub = post("pubby")
p_fol = post("folly", "followers")
m.update_me(m.ProfileUpdate(is_private=True), U("privy")); p_priv = post("privy")
p_blk = post("blocky"); m.block_user("viewer", U("blocky"))
p_hid = post("hidy"); m._rate.clear(); m.recommendation_feedback(m.RecFeedback(action="hide_creator", content_type="post", content_id=p_hid), U("viewer"))
forbidden = [p_fol, p_priv, p_blk, p_hid]
eng = Engine("inject", extra=forbidden)
cl = use(eng)
got = rk("viewer")
sent_ids = {i["id"] for i in eng.seen[0]["items"]}
assert sent_ids == {p_pub}, sent_ids
assert got == [p_pub] and cl.stats["failed"] == 1 and cl.stats["python"] == 1
assert [x["id"] for x in feed("viewer")["posts"]] == [p_pub]
ok("privacy first: forbidden posts are never sent to the engine; an engine that names them anyway is ignored (Python order, feed unchanged)")

# ---- 4. nothing identifying is sent
text = json.dumps(eng.seen)
assert "dev-" not in text and "pubby" not in text and "users/" not in text
ok("the engine receives no user id, no username and no media name")

# ---- 5. a dropped item is refused too (an engine cannot hide content)
fresh_db(["viewer", "aaa", "bbb"])
a, b = post("aaa"), post("bbb")
use(Engine("drop"))
assert sorted(rk("viewer")) == sorted([a, b])
ok("an engine answer that leaves a candidate out is refused (Python order, nothing lost)")

# ---- 6. the engine is down: the feed still works and the app contract is unchanged
fresh_db(["viewer", "aaa"])
post("aaa")
def down(*args):
    raise ConnectionRefusedError("down")
use(down)
res = feed("viewer")
assert res["ranked"] is True and len(res["posts"]) == 1 and res["ranker"] == "v1" and "next_cursor" in res
ok("engine down: the feed is still ranked (by Python) and the answer has the same fields as before")

# ---- 7. what the owner can see
rep = m.config_report()["rank_engine"]
assert set(rep) == {"configured", "paused", "ok", "failed", "skipped", "python"} and "engine.example" not in json.dumps(m.config_report())
ok("/health/config shows only booleans and counters for the engine (never the address or the secret)")

print("test_rank_engine_feed: all checks passed")
