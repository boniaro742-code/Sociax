"""Backend checks for Step 21: list of saved short videos (plan sections 4, 5, 11).
Run:  cd backend && DEV_AUTH=1 python test_saved_shorts.py      (or: pytest -q test_saved_shorts.py)
Cloudinary is replaced by a fake, so no account or network is needed."""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "s.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
FAKE = {}
m.video_info = lambda pid: FAKE[pid]
m.destroy_video = lambda pid: None
A, B, C = "dev-a", "dev-b", "dev-c"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def user(uid, name):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def short(uid, n, audience="public"):
    m._rate.clear()
    pid = f"users/{uid}/short_{n}"
    FAKE[pid] = {"bytes": 1024, "duration": 5.0, "format": "mp4"}
    return m.create_short(m.NewShort(public_id=pid, audience=audience), uid)["id"]


def ids(viewer, **k):
    return [s["id"] for s in m.saved_shorts(k.get("before"), k.get("limit", 30), viewer)["shorts"]]


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


user(A, "alice"); user(B, "bob"); user(C, "carol")
s1, s2, s3 = short(A, 1), short(A, 2), short(A, 3)

check("starts empty", ids(B), [])
for s in (s1, s3):
    m.bookmark_short(s, B)
check("saved shorts, newest first", ids(B), [s3, s1])
check("saved flag is true in the list", all(x["saved"] for x in m.saved_shorts(None, 30, B)["shorts"]), True)
check("another person's list is separate", ids(C), [])
check("paging: limit 1", ids(B, limit=1), [s3])
check("paging: before", ids(B, before=s3), [s1])

m.unbookmark_short(s1, B)
check("unsave removes it", ids(B), [s3])

# visibility: followers-only -> a non-follower's saved short disappears (and comes back after following)
m.follow("alice", C)                                   # carol follows alice (public) -> accepted
fo = short(A, 4, audience="followers")
m.bookmark_short(fo, C)
check("followers-only short is listed for a follower", ids(C), [fo])
m.unfollow("alice", C)
check("after unfollowing it quietly disappears", ids(C), [])

# private account: saved short disappears when the owner goes private
m.bookmark_short(s2, B)
check("public short is listed", s2 in ids(B), True)
m.update_me(m.ProfileUpdate(is_private=True), A)
check("owner went private: gone from my list", ids(B), [])

# blocked
m.update_me(m.ProfileUpdate(is_private=False), A)
check("public again: back in the list", s2 in ids(B), True)
m.block_user("bob", A)
check("owner blocked me: gone from my list", ids(B), [])
print("test_saved_shorts: all checks passed")
