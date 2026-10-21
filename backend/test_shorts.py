"""Backend checks for Short Video (plan section 5) and its privacy rules.
Run:  cd backend && DEV_AUTH=1 python test_shorts.py      (or: pytest -q test_shorts.py)
Cloudinary is replaced by a fake, so no account or network is needed."""
import os, tempfile, time

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
FAKE = {}          # public_id -> {"bytes":..., "duration":..., "format":...}
DESTROYED = []
m.video_info = lambda pid: FAKE[pid] if pid in FAKE else (_ for _ in ()).throw(HTTPException(422, "Upload not found"))
m.destroy_video = lambda pid: DESTROYED.append(pid)

MB = 1024 * 1024
A, B, C, D = "dev-a", "dev-b", "dev-c", "dev-d"


def code(fn, *a, **k):
    m._rate.clear()          # the rate limiter has its own check at the end
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def user(uid, name, private=False):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)
    if private:
        m.update_me(m.ProfileUpdate(is_private=True), uid)


def upload(uid, n, dur=10.0, size=5 * MB, fmt="mp4"):
    pid = f"users/{uid}/short_{n}"
    FAKE[pid] = {"bytes": size, "duration": dur, "format": fmt}
    return pid


def publish(uid, n, dur=10.0, **kw):
    m._rate.clear()
    body = dict(caption="hi", audience="public", comments_enabled=True, chosen_seconds=25)
    body.update({k: v for k, v in kw.items() if k in body}); 
    return m.create_short(m.NewShort(public_id=upload(uid, n, dur, **{k: v for k, v in kw.items() if k in ("size", "fmt")}), **body), uid)


def publish_quick(uid, i):
    return m.create_short(m.NewShort(public_id=upload(uid, i, 1.0, size=1024)), uid)


def ids(uid):
    return [s["id"] for s in m.shorts_feed(None, 10, uid)["shorts"]]


def test_all():
    for u, n, p in [(A, "anna", False), (B, "ben", False), (C, "cara", True), (D, "dan", False)]:
        user(u, n, p)

    # --- signing ---
    s = m.sign_upload(m.SignRequest(purpose="short", size_bytes=20 * MB), A)
    assert s["allowed_formats"] == "mp4,mov,webm,3gp,mkv" and s["public_id"].startswith(f"users/{A}/short_")
    assert code(m.sign_upload, m.SignRequest(purpose="short", size_bytes=101 * MB), A) == 413
    assert m.sign_upload(m.SignRequest(purpose="post", size_bytes=MB), A)["allowed_formats"].startswith("jpg")

    # --- duration rules (hard maximum 25 s, user choice 20/25) ---
    assert publish(A, 1, 24.9)["duration"] == 24.9
    assert publish(A, 2, 25.4)["id"]                                     # container rounding tolerance
    assert code(publish, A, 3, 25.6) == 422 and f"users/{A}/short_3" in DESTROYED   # too long: refused AND deleted
    assert code(publish, A, 4, 30.0) == 422
    assert code(publish, A, 5, 22.0, chosen_seconds=20) == 422            # chose 20 s but clip is 22 s
    assert publish(A, 6, 19.9, chosen_seconds=20)["id"]
    assert code(publish, A, 7, 0.0) == 422                                # unreadable length
    assert code(publish, A, 8, 10.0, fmt="avi") == 415
    assert code(publish, A, 9, 10.0, size=101 * MB) == 413
    # not your upload / path tricks / duplicate
    FAKE["users/dev-b/short_x"] = {"bytes": MB, "duration": 5, "format": "mp4"}
    assert code(m.create_short, m.NewShort(public_id="users/dev-b/short_x"), A) == 403
    assert code(m.create_short, m.NewShort(public_id=f"users/{A}/short_../../x"), A) == 403
    assert code(m.create_short, m.NewShort(public_id=f"users/{A}/short_1"), A) == 409
    assert code(m.create_short, m.NewShort(public_id=f"users/{A}/short_nope"), A) == 422
    # media-validate (advisory) also refuses > 25 s
    assert code(m.validate_short, m.ShortMeta(duration_seconds=26, size_mb=5, mime="video/mp4"), A) == 422

    # --- quota (150 MB combined, server-side) ---
    assert code(publish, A, 10, 10.0, size=99 * MB) == 200               # A now ~ 99+5*3 MB
    assert code(publish, A, 11, 10.0, size=60 * MB) == 413               # would exceed 150 MB
    q = m.media_quota(A); assert q["used_mb"] <= 150
    # deleting frees the space
    sid = [s for s in m.shorts_feed(None, 30, A)["shorts"] if s["duration"] == 10.0][0]["id"]
    assert m.delete_short(sid, A)["ok"]
    assert code(m.delete_short, sid, A) == 404 and code(m.delete_short, 1, B) == 404   # not yours

    # --- privacy ---
    mine = ids(A)
    assert ids(B) == mine                                                # public shorts of public account
    priv = publish(C, 1, 8.0)["id"]; fo = publish(A, 12, 8.0, audience="followers")["id"]
    assert priv not in ids(B) and fo not in ids(B)                       # private account + followers-only hidden
    assert priv in ids(C) and fo in ids(A)                               # owners always see their own
    for sid_ in (priv, fo):                                              # hidden == not found, for every action
        assert code(m.like_short, sid_, B) == 404
        assert code(m.list_short_comments, sid_, B) == 404
        assert code(m.add_short_comment, sid_, m.NewComment(text="x"), B) == 404
        assert code(m.report_short, sid_, m.Report(reason="spam"), B) == 404
    m.follow("anna", B); assert fo in ids(B)                             # accepted follow reveals followers-only
    m.follow("cara", B); assert priv not in ids(B)                       # pending request still hidden
    m.answer_follow_request("ben", "accept", C); assert priv in ids(B)
    m.block_user("anna", B)                                              # block hides both ways
    assert not any(s["username"] == "@anna" for s in m.shorts_feed(None, 30, B)["shorts"])
    assert not any(s["username"] == "@ben" for s in m.shorts_feed(None, 30, A)["shorts"])
    assert code(m.like_short, fo, B) == 404
    m.unblock_user("anna", B); m.follow("anna", B)

    # --- likes / comments / comments-off / report ---
    pub = mine[0]
    m.like_short(pub, B); m.like_short(pub, B)                            # idempotent
    s = [x for x in m.shorts_feed(None, 30, B)["shorts"] if x["id"] == pub][0]
    assert s["like_count"] == 1 and s["liked"] and not [x for x in m.shorts_feed(None, 30, A)["shorts"] if x["id"] == pub][0]["liked"]
    m.unlike_short(pub, B)
    m.add_short_comment(pub, m.NewComment(text="nice"), B)
    assert len(m.list_short_comments(pub, A)["comments"]) == 1
    m.block_user("ben", A)                                               # blocked users' comments disappear
    assert code(m.add_short_comment, pub, m.NewComment(text="y"), B) == 404
    m.unblock_user("ben", A)
    off = publish(A, 13, 5.0, comments_enabled=False)["id"]
    assert code(m.add_short_comment, off, m.NewComment(text="x"), B) == 403
    assert m.list_short_comments(off, B) == {"comments": [], "comments_enabled": False}
    assert m.report_short(pub, m.Report(reason="spam"), B)["ok"]
    # no private media URL leaks: every URL is built from the visible row only
    out = m.shorts_feed(None, 30, B)["shorts"][0]
    assert out["video_url"].startswith("https://res.cloudinary.com/demo/video/upload/") and out["video_url_low"] != out["video_url"]
    # rate limit: only 10 publishes per hour per user
    m._rate.clear()
    hit = 0
    for i in range(100, 112):
        try:
            publish_quick(A, i)
        except HTTPException as e:
            hit += e.status_code == 429
    assert hit >= 1
    print("ALL SHORT VIDEO CHECKS PASSED")


if __name__ == "__main__":
    test_all()
