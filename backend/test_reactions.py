"""Backend checks for emoji reactions on posts (reference screens: heart + 😮 😂 😢).
Run:  cd backend && DEV_AUTH=1 python test_reactions.py      (or: pytest -q test_reactions.py)
Cloudinary is replaced by a fake, so no account or network is needed."""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "demo", "key", "secret"
m.asset_bytes = lambda pid: 1024
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


def post(uid, n, audience="public"):
    m._rate.clear()
    return m.create_post(m.NewPost(public_id=f"users/{uid}/post_{n}", caption="hi", audience=audience), uid)["id"]


def reactions(viewer, pid):
    return next(p for p in m.feed("for_you", None, 20, viewer)["posts"] if p["id"] == pid)["reactions"]


def test_all():
    user(A, "aaa"); user(B, "bbb"); user(C, "ccc", private=True)
    pid = post(A, 1)

    # starts at zero, three kinds exist
    r = reactions(B, pid)
    assert set(r) == {"wow", "haha", "sad"} and all(v == {"count": 0, "mine": False} for v in r.values())

    # react, count and "mine" are per person
    assert code(m.react, pid, "wow", B) == 200
    assert code(m.react, pid, "wow", A) == 200
    assert code(m.react, pid, "sad", B) == 200
    assert reactions(B, pid)["wow"] == {"count": 2, "mine": True}
    assert reactions(A, pid)["sad"] == {"count": 1, "mine": False}

    # reacting twice does not double count; removing works; removing something never set is harmless
    assert code(m.react, pid, "wow", B) == 200
    assert reactions(B, pid)["wow"]["count"] == 2
    assert code(m.unreact, pid, "wow", B) == 200
    assert reactions(B, pid)["wow"] == {"count": 1, "mine": False}
    assert code(m.unreact, pid, "haha", B) == 200

    # reactions and the heart (like) are independent
    m.like(pid, B)
    assert reactions(B, pid)["sad"]["mine"] is True

    # privacy: a private account's post and a followers-only post answer 404 to outsiders (same as missing)
    hidden = post(C, 2)
    assert code(m.react, hidden, "wow", B) == 404
    only = post(A, 3, audience="followers")
    assert code(m.react, only, "wow", B) == 404
    assert code(m.react, 999999, "wow", B) == 404

    # blocked users cannot react
    m.block_user("aaa", B)
    assert code(m.react, pid, "haha", B) == 404

    # deleting the post removes its reactions
    m.delete_post(pid, A)
    with m.closing(m.db()) as con:
        assert con.execute("SELECT COUNT(*) FROM post_reactions WHERE post_id=?", (pid,)).fetchone()[0] == 0


if __name__ == "__main__":
    test_all()
    print("reaction checks passed")
