"""Backend checks for emoji reactions + save on Short Video (reference screen: heart + 😮 😂 😢, bookmark).
Run:  cd backend && DEV_AUTH=1 python test_short_reactions.py      (or: pytest -q test_short_reactions.py)
Cloudinary is replaced by a fake, so no account or network is needed."""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
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


def user(uid, name, private=False):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)
    if private:
        m.update_me(m.ProfileUpdate(is_private=True), uid)


def short(uid, n):
    m._rate.clear()
    pid = f"users/{uid}/short_{n}"
    FAKE[pid] = {"bytes": 1024, "duration": 5.0, "format": "mp4"}
    return m.create_short(m.NewShort(public_id=pid), uid)["id"]


def item(viewer, sid):
    return next(s for s in m.shorts_feed(None, 30, viewer)["shorts"] if s["id"] == sid)


def test_all():
    user(A, "aaa"); user(B, "bbb"); user(C, "ccc", private=True)
    sid = short(A, 1)

    # starts at zero, three reaction kinds exist, nothing saved
    s = item(B, sid)
    assert set(s["reactions"]) == {"wow", "haha", "sad"}
    assert all(v == {"count": 0, "mine": False} for v in s["reactions"].values())
    assert s["saved"] is False and s["save_count"] == 0

    # counts and "mine" are per person
    assert code(m.react_short, sid, "wow", B) == 200
    assert code(m.react_short, sid, "wow", A) == 200
    assert code(m.react_short, sid, "sad", B) == 200
    assert item(B, sid)["reactions"]["wow"] == {"count": 2, "mine": True}
    assert item(A, sid)["reactions"]["sad"] == {"count": 1, "mine": False}

    # twice does not double count; removing works; removing something never set is harmless
    assert code(m.react_short, sid, "wow", B) == 200
    assert item(B, sid)["reactions"]["wow"]["count"] == 2
    assert code(m.unreact_short, sid, "wow", B) == 200
    assert item(B, sid)["reactions"]["wow"] == {"count": 1, "mine": False}
    assert code(m.unreact_short, sid, "haha", B) == 200

    # reactions are independent of the heart (like)
    m.like_short(sid, B)
    assert item(B, sid)["reactions"]["sad"]["mine"] is True and item(B, sid)["liked"] is True

    # save: per person, no double counting
    assert code(m.bookmark_short, sid, B) == 200
    assert code(m.bookmark_short, sid, B) == 200
    assert code(m.bookmark_short, sid, A) == 200
    assert item(B, sid)["saved"] is True and item(B, sid)["save_count"] == 2
    assert code(m.unbookmark_short, sid, B) == 200
    assert item(B, sid)["saved"] is False and item(B, sid)["save_count"] == 1

    # privacy: private account's short and a followers-only short answer 404 to outsiders (same as missing)
    hidden = short(C, 2)
    assert code(m.react_short, hidden, "wow", B) == 404
    assert code(m.bookmark_short, hidden, B) == 404
    only = m.create_short(m.NewShort(public_id=(lambda p: (FAKE.__setitem__(p, {"bytes": 1024, "duration": 5.0, "format": "mp4"}), p)[1])("users/dev-a/short_3"),
                                     audience="followers"), A)["id"]
    assert code(m.react_short, only, "wow", B) == 404
    assert code(m.react_short, 999999, "wow", B) == 404
    assert code(m.unbookmark_short, 999999, B) == 404

    # blocked users cannot react or save
    m.block_user("aaa", B)
    assert code(m.react_short, sid, "haha", B) == 404
    assert code(m.bookmark_short, sid, B) == 404

    # deleting the short removes its reactions and saves
    m.delete_short(sid, A)
    with m.closing(m.db()) as con:
        assert con.execute("SELECT COUNT(*) FROM short_reactions WHERE short_id=?", (sid,)).fetchone()[0] == 0
        assert con.execute("SELECT COUNT(*) FROM short_bookmarks WHERE short_id=?", (sid,)).fetchone()[0] == 0


if __name__ == "__main__":
    test_all()
    print("short reaction checks passed")
