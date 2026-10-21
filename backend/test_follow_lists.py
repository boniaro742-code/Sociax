"""Backend checks for Step 20: followers / following lists (plan section 4 + 11).
Run:  cd backend && DEV_AUTH=1 python test_follow_lists.py      (or: pytest -q test_follow_lists.py)"""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "f.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C, D, E = "dev-a", "dev-b", "dev-c", "dev-d", "dev-e"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def names(r):
    return [u["username"] for u in r["users"]]


def user(uid, name):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


for uid, n in ((A, "alice"), (B, "bob"), (C, "carol"), (D, "dave"), (E, "erin")):
    user(uid, n)

m.follow("alice", B); m.follow("alice", C); m.follow("bob", A)       # bob, carol follow alice; alice follows bob
check("followers of alice", sorted(names(m.user_followers("alice", 0, 50, A))), ["@bob", "@carol"])
check("following of alice", names(m.user_following("alice", 0, 50, A)), ["@bob"])
check("works with @ in the name", sorted(names(m.user_followers("@alice", 0, 50, D))), ["@bob", "@carol"])
check("stranger can read a PUBLIC account's list", code(m.user_followers, "alice", 0, 50, E), 200)

# paging
p1 = m.user_followers("alice", 0, 1, A)
p2 = m.user_followers("alice", 1, 1, A)
check("page 1 has one + more", (len(p1["users"]), p1["more"]), (1, True))
check("page 2 has the other, no more", (len(p2["users"]), p2["more"]), (1, False))
check("the two pages differ", p1["users"][0]["username"] != p2["users"][0]["username"], True)
check("missing user -> 404", code(m.user_followers, "nobody", 0, 50, A), 404)

# private account: pending follower sees nothing, accepted follower sees the list
m.update_me(m.ProfileUpdate(is_private=True), E)
m.follow("erin", D)                                                   # pending
check("private: pending requester refused", code(m.user_followers, "erin", 0, 50, D), 403)
check("private: stranger refused", code(m.user_following, "erin", 0, 50, A), 403)
check("private: owner reads her own list", code(m.user_followers, "erin", 0, 50, E), 200)
m.answer_follow_request("dave", "accept", E)
check("private: accepted follower reads the list", code(m.user_followers, "erin", 0, 50, D), 200)

# blocks: blocked people disappear from lists, blocked pair = not found
m.block_user("carol", A)
check("blocked person left out of my list", names(m.user_followers("alice", 0, 50, A)), ["@bob"])
check("blocker's list: not found for the blocked", code(m.user_followers, "alice", 0, 50, C), 404)
check("blocked person's list: not found for the blocker", code(m.user_followers, "carol", 0, 50, A), 404)
check("blocking removed her follow (third person sees bob only)", names(m.user_followers("alice", 0, 50, D)), ["@bob"])

# a block between two OTHER people: each leaves the other's view of a shared list
m.follow("alice", D)                                                  # dave follows alice (alice is public)
m.block_user("dave", B)                                               # bob blocks dave
check("bob sees only himself, not dave", names(m.user_followers("alice", 0, 50, B)), ["@bob"])
check("dave sees only himself, not bob", names(m.user_followers("alice", 0, 50, D)), ["@dave"])
check("a third person sees both", sorted(names(m.user_followers("alice", 0, 50, E))), ["@bob", "@dave"])
print("test_follow_lists: all checks passed")
