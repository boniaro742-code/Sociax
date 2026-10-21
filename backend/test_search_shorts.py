"""Backend checks: short videos in search (Step 26). Run:  cd backend && DEV_AUTH=1 python test_search_shorts.py"""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402

A, B, C, D, E = "dev-a", "dev-b", "dev-c", "dev-d", "dev-e"
for uid, n in ((A, "alice"), (B, "bob"), (C, "carol"), (D, "dave"), (E, "erin")):
    m.onboard(m.Onboarding(display_name=n, username=n, age=20), uid)
now = int(time.time())


def short(uid, pid, caption, audience="public"):
    with closing(m.db()) as con:
        con.execute("INSERT INTO shorts(uid, caption, media_public_id, duration, audience, comments_enabled, created_at) VALUES (?,?,?,?,?,?,?)",
                    (uid, caption, pid, 20, audience, 1, now)); con.commit()


def found(viewer, q="sunset"):
    m._rate.clear()
    return sorted(s["caption"] for s in m.search(q, viewer)["shorts"])


def ok(msg): print("PASS", msg)


short(B, "s1", "sunset surf")                        # public, normal account
short(C, "s2", "sunset for followers", "followers")  # followers only
short(D, "s3", "sunset at my private place")         # public clip but PRIVATE account
m.update_me(m.ProfileUpdate(is_private=True), D)
short(E, "s4", "Sunset by erin")                     # case-insensitive match; erin will block alice below

assert found(A) == ["Sunset by erin", "sunset surf"], found(A)
ok("only public clips of public accounts are found (case-insensitive)")

with closing(m.db()) as con:
    con.execute("INSERT INTO follows(follower, followee, status, created_at) VALUES (?,?,?,?)", (A, C, "accepted", now)); con.commit()
assert found(A) == ["Sunset by erin", "sunset surf"], found(A)
ok("even a follower does not get followers-only clips through search")

with closing(m.db()) as con:
    con.execute("INSERT INTO blocks(blocker, blocked) VALUES (?,?)", (E, A)); con.commit()
assert found(A) == ["sunset surf"], found(A)
with closing(m.db()) as con:
    con.execute("DELETE FROM blocks"); con.commit()
ok("blocked people's clips disappear")

assert found(A, "nomatch") == [] and m.search("s", A) == {"users": [], "posts": [], "shorts": []}
r = m.search("sunset", A)["shorts"][0]
assert r["video_url"] is None or "video/upload" in r["video_url"]
assert {"id", "caption", "username", "poster_url", "video_url", "video_url_low"} <= set(r)
ok("no match / too short query is empty; result has everything the app needs")
print("test_search_shorts: all checks passed")
