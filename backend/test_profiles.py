"""Backend checks for other people's profile pages (plan sections 4 + 11).
Run:  cd backend && DEV_AUTH=1 python test_profiles.py      (or: pytest -q test_profiles.py)
No Cloudinary account or network is needed."""
import os, sqlite3, tempfile, time

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C, D = "dev-a", "dev-b", "dev-c", "dev-d"


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


def add_post(uid, caption, audience="public"):
    con = sqlite3.connect(m.DB_PATH)
    con.execute("INSERT INTO posts(uid, caption, media_public_id, audience, created_at) VALUES (?,?,?,?,?)",
                (uid, caption, "x" + caption, audience, int(time.time())))
    con.commit(); con.close()


def caps(r):
    return [p["caption"] for p in r["posts"]]


user(A, "alice"); user(B, "bob", private=True); user(C, "carl"); user(D, "dina")
add_post(B, "bob secret"); add_post(C, "carl pub"); add_post(C, "carl fol", "followers")

# private account, I am not following: no posts, no post count, cannot message
p = m.user_profile("bob", A)
assert p["can_view_posts"] is False and p["posts"] is None and p["can_message"] is False
assert caps(m.user_posts("bob", None, 30, A)) == []

# public account, followers-only post stays hidden from a stranger
assert caps(m.user_posts("carl", None, 30, A)) == ["carl pub"] and m.user_profile("carl", A)["posts"] == 1

# after an ACCEPTED follow the posts appear (a pending request is not enough)
m.follow("bob", A)
assert m.user_profile("bob", A)["follow_status"] == "pending" and caps(m.user_posts("bob", None, 30, A)) == []
m.answer_follow_request("alice", "accept", B)
assert caps(m.user_posts("bob", None, 30, A)) == ["bob secret"] and m.user_profile("bob", A)["posts"] == 1
m.follow("carl", A)
assert sorted(caps(m.user_posts("carl", None, 30, A))) == ["carl fol", "carl pub"]

# someone who blocked me: the same "not found" as a missing user
m.block_user("alice", D)
assert code(m.user_profile, "dina", A) == 404 and code(m.user_posts, "dina", None, 30, A) == 404
assert code(m.user_profile, "nobody", A) == 404

# someone I blocked: only name + photo, so I can unblock; no posts
m.block_user("carl", A)
p = m.user_profile("carl", A)
assert p["i_blocked"] is True and p["posts"] is None and p["bio"] == "" and p["can_message"] is False
assert code(m.user_posts, "carl", None, 30, A) == 404
m.unblock_user("carl", A)
assert m.user_profile("carl", A)["i_blocked"] is False

# my own profile
assert m.user_profile("alice", A)["is_me"] is True
print("test_profiles: all checks passed")
