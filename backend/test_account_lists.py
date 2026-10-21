"""Backend checks for Step 17: blocked-users list, saved-posts list, account deletion (plan sections 9 + 11).
Run:  cd backend && DEV_AUTH=1 python test_account_lists.py      (or: pytest -q test_account_lists.py)
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
    cur = con.execute("INSERT INTO posts(uid, caption, media_public_id, audience, created_at) VALUES (?,?,?,?,?)",
                      (uid, caption, "x" + caption, audience, int(time.time())))
    con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)",
                ("x" + caption, uid, "post", 1000, int(time.time())))
    con.commit(); pid = cur.lastrowid; con.close()
    return pid


def count(sql, *a):
    con = sqlite3.connect(m.DB_PATH)
    n = con.execute(sql, a).fetchone()[0]; con.close()
    return n


def caps(r):
    return [p["caption"] for p in r["posts"]]


user(A, "alice"); user(B, "bob"); user(C, "carl"); user(D, "dina")
p_bob = add_post(B, "bob pub"); p_carl = add_post(C, "carl pub"); p_carl_f = add_post(C, "carl fol", "followers")
p_dina = add_post(D, "dina pub")

# ---- blocked list: only people I blocked, never people who blocked me
assert m.my_blocked(A)["users"] == []
m.block_user("bob", A); m.block_user("alice", C)
assert [u["username"] for u in m.my_blocked(A)["users"]] == ["@bob"]
assert [u["username"] for u in m.my_blocked(C)["users"]] == ["@alice"]
m.unblock_user("bob", A)
assert m.my_blocked(A)["users"] == []

# ---- saved posts: only what I saved, and still only what I may see
m.bookmark(p_bob, A); m.bookmark(p_dina, A)
assert sorted(caps(m.saved_posts(None, 30, A))) == ["bob pub", "dina pub"]
assert caps(m.saved_posts(None, 30, B)) == []                      # nobody else sees my list
m.block_user("dina", A)                                            # I block dina -> her post leaves my list
assert caps(m.saved_posts(None, 30, A)) == ["bob pub"]
m.unblock_user("dina", A)
m.update_me(m.ProfileUpdate(is_private=True), B)                   # bob goes private, I do not follow him
assert caps(m.saved_posts(None, 30, A)) == ["dina pub"]
assert all(p["saved"] for p in m.saved_posts(None, 30, A)["posts"])

# ---- account deletion
m.unblock_user("alice", C)
m.follow("carl", D); m.follow("dina", C)
m.bookmark(p_carl, D);
dm = m.start_dm(m.DmStart(username="dina"), C)
m.send_message(dm["id"], m.NewMessage(text="hi dina"), C)
grp = m.create_group(m.NewGroup(name="crew", usernames=["dina"], confirmed=True), C)
m.send_message(grp["id"], m.NewMessage(text="welcome"), C)

assert code(m.delete_account, "dev-zzz") == 404
assert code(m.delete_account, C) == 200
assert count("SELECT COUNT(*) FROM users WHERE uid=?", C) == 0
assert count("SELECT COUNT(*) FROM posts WHERE uid=?", C) == 0 and count("SELECT COUNT(*) FROM media WHERE uid=?", C) == 0
assert count("SELECT COUNT(*) FROM bookmarks WHERE post_id IN (?,?)", p_carl, p_carl_f) == 0
assert count("SELECT COUNT(*) FROM follows WHERE follower=? OR followee=?", C, C) == 0
assert count("SELECT COUNT(*) FROM messages WHERE uid=?", C) == 0
assert count("SELECT COUNT(*) FROM conversations WHERE kind='dm'") == 0            # the one-to-one chat is gone
assert count("SELECT COUNT(*) FROM conv_members WHERE uid=?", C) == 0
# the group I owned still exists, and dina is now its owner
assert count("SELECT COUNT(*) FROM conversations WHERE id=? AND owner=?", grp["id"], D) == 1
assert count("SELECT COUNT(*) FROM conv_members WHERE conv_id=? AND uid=? AND role='owner'", grp["id"], D) == 1
# other people are untouched; the username is free again
assert count("SELECT COUNT(*) FROM users") == 3 and count("SELECT COUNT(*) FROM posts") == 2
assert code(m.user_profile, "carl", A) == 404
user("dev-e", "carl")                                                               # same @username can be taken again
print("test_account_lists: all checks passed")
