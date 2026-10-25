"""Step 35 (plan v3.6 + v3.7): what Account A publishes must reach Account B from SERVER data.
Public = every signed-in account (even a non-follower); followers-only = accepted followers only; private account / block /
moderation always win; failures never create a half-published post; a retry never makes a duplicate.
Run:  cd backend && DEV_AUTH=1 python test_cross_account.py
Cloudinary is replaced by a fake: no account and no network needed."""
import os, tempfile
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
MB = 1024 * 1024
SIZES, VIDEOS = {}, {}
m.asset_bytes = lambda pid: SIZES[pid] if pid in SIZES else (_ for _ in ()).throw(HTTPException(502, "Could not verify the upload"))
m.video_info = lambda pid: VIDEOS[pid] if pid in VIDEOS else (_ for _ in ()).throw(HTTPException(422, "Upload not found"))
m.destroy_video = lambda pid: None
m.kick_cloud_cleanup = lambda: None

A, B, C, D = "dev-a", "dev-b", "dev-c", "dev-d"       # A publishes. B follows A. C is a stranger. D is blocked by A.


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def photo(uid, n, audience="public", mb=1):
    pid = f"users/{uid}/post_{n}"
    SIZES[pid] = mb * MB
    m._rate.clear()
    return m.create_post(m.NewPost(public_id=pid, caption=f"photo {n}", audience=audience), uid)


def video(uid, n, audience="public"):
    pid = f"users/{uid}/short_{n}"
    VIDEOS[pid] = {"bytes": 3 * MB, "duration": 6.0, "format": "mp4"}
    m._rate.clear()
    return m.create_short(m.NewShort(public_id=pid, caption=f"video {n}", audience=audience), uid)


def feed_ids(uid, tab="for_you"):
    return [p["id"] for p in m.feed(tab, None, 50, uid)["posts"]]


def short_ids(uid):
    return [s["id"] for s in m.shorts_feed(None, 30, uid)["shorts"]]


def used(uid):
    with closing(m.db()) as con:
        r = con.execute("SELECT used_bytes FROM media_usage WHERE uid=?", (uid,)).fetchone()
        return r[0] if r else 0


def ok(msg):
    print("PASS", msg)


for u, n in [(A, "anna"), (B, "ben"), (C, "cara"), (D, "dan")]:
    m.onboard(m.Onboarding(display_name=n, username=n, age=20), u)
m.follow("anna", B)                          # B follows A (A is public, so accepted at once)
m.block_user("dan", A)                            # A blocks D

# ---- 1. public photo: a NON-follower (C) and a follower (B) both receive it, with a working media URL
p_pub = photo(A, 1)
assert p_pub["type"] == "photo" and p_pub["visibility"] == "public" and p_pub["image_url"].startswith("https://res.cloudinary.com/demo/image/upload/")
assert p_pub["id"] in feed_ids(C) and p_pub["id"] in feed_ids(B) and p_pub["id"] in feed_ids(A)
assert m.get_post(p_pub["id"], C)["image_url"] == p_pub["image_url"]            # opens by id, same media URL for everybody
ok("public photo reaches a non-follower and a follower (same media URL, not tied to the publisher's phone)")

# ---- 2. public video: same
v_pub = video(A, 1)
assert v_pub["type"] == "video" and v_pub["video_url"].endswith(f"{v_pub['video_url'].split('/')[-1]}") and "/video/upload/" in v_pub["video_url"]
assert v_pub["id"] in short_ids(C) and v_pub["id"] in short_ids(B)
assert m.get_short(v_pub["id"], C)["video_url"] == v_pub["video_url"]
ok("public video reaches a non-follower and a follower")

# ---- 3. followers-only: follower yes, stranger no, direct open gives the SAME 404 as a missing post
p_fo, v_fo = photo(A, 2, "followers"), video(A, 2, "followers")
assert p_fo["id"] in feed_ids(B) and p_fo["id"] not in feed_ids(C)
assert v_fo["id"] in short_ids(B) and v_fo["id"] not in short_ids(C)
assert code(m.get_post, p_fo["id"], C) == 404 and code(m.get_post, 99999, C) == 404
assert code(m.get_short, v_fo["id"], C) == 404 and code(m.get_short, 99999, C) == 404
assert p_fo["id"] not in feed_ids(C, "following")
ok("followers-only is hidden from an unrelated account (feed and direct open), shown to a follower")

# ---- 4. blocked users never see anything of each other
assert p_pub["id"] not in feed_ids(D) and v_pub["id"] not in short_ids(D) and code(m.get_post, p_pub["id"], D) == 404
ok("a blocked account cannot see the public posts of the blocker")

# ---- 5. a PRIVATE account's posts are not public, even when marked public
m.update_me(m.ProfileUpdate(is_private=True), C)
p_priv = photo(C, 1)
assert p_priv["id"] not in feed_ids(D) and p_priv["id"] not in feed_ids(B) and p_priv["id"] in feed_ids(C)
m.follow("cara", B)                                                         # request only: still not accepted
assert p_priv["id"] not in feed_ids(B)
m.update_me(m.ProfileUpdate(is_private=False), C)
ok("private account content stays private; the owner still sees it")

# ---- 6. the posts are still there after a restart (all state is in the database, not in the app or in memory)
m.init_db()
m._rate.clear()
assert p_pub["id"] in feed_ids(C) and v_pub["id"] in short_ids(C)
ok("posts survive a server restart and a feed refresh")

# ---- 7. moderation: a restricted post disappears for everyone except its owner
with closing(m.db()) as con:
    con.execute("UPDATE posts SET moderation_state='restricted' WHERE id=?", (p_pub["id"],)); con.commit()
assert p_pub["id"] not in feed_ids(C) and code(m.get_post, p_pub["id"], C) == 404 and p_pub["id"] in feed_ids(A)
with closing(m.db()) as con:
    con.execute("UPDATE posts SET moderation_state='ok' WHERE id=?", (p_pub["id"],)); con.commit()
ok("moderation state hides restricted content from everybody but the owner")

# ---- 8. a failed step never shows a false "Published": no row, no quota used
before_posts, before_used = len(feed_ids(A)), used(A)
assert code(m.create_post, m.NewPost(public_id=f"users/{A}/post_unverified"), A) == 502      # Cloudinary could not confirm the file
assert len(feed_ids(A)) == before_posts and used(A) == before_used
assert code(m.create_short, m.NewShort(public_id=f"users/{A}/short_unverified"), A) == 422
assert code(photo, A, 3, "public", 11) == 413 and len(feed_ids(A)) == before_posts and used(A) == before_used
ok("failed verification / too-large photo creates no post and uses no quota")

# ---- 9. a retry (answer lost) returns the SAME post: no duplicate, quota counted once
once = photo(A, 4)
q = used(A)
again = m.create_post(m.NewPost(public_id=f"users/{A}/post_4", caption="photo 4"), A)
assert again["id"] == once["id"] and used(A) == q and feed_ids(A).count(once["id"]) == 1
vonce = video(A, 4)
q = used(A)
vagain = m.create_short(m.NewShort(public_id=f"users/{A}/short_4"), A)
assert vagain["id"] == vonce["id"] and used(A) == q and short_ids(A).count(vonce["id"]) == 1
ok("retrying the same upload returns the same post / clip, never a duplicate or a second quota charge")

# ---- 10. nobody can publish somebody else's upload
SIZES["users/dev-b/post_x"] = MB
assert code(m.create_post, m.NewPost(public_id="users/dev-b/post_x"), A) == 403
ok("someone else's upload cannot be published")

# ---- 11. paging: pages never repeat and always say where to continue
for i in range(5, 12):
    photo(A, i)
page1 = m.feed("for_you", None, 5, C)
page2 = m.feed("for_you", page1["next_before"], 5, C)
assert page1["next_before"] and not set(p["id"] for p in page1["posts"]) & set(p["id"] for p in page2["posts"])
ok("feed pages do not overlap and report next_before")
print("test_cross_account: all checks passed")
