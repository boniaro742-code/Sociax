"""Backend checks for changing the @username (Step 29). Run:  cd backend && DEV_AUTH=1 python test_username_change.py"""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B = "dev-a", "dev-b"
for uid, name in ((A, "alice"), (B, "bob")):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def ok(msg): print("PASS", msg)
def change(uid, name, **extra):
    m._rate.clear()
    return m.update_me(m.ProfileUpdate(username=name, **extra), uid)
def refused(fn, code):
    try:
        fn()
    except HTTPException as e:
        assert e.status_code == code, (e.status_code, code)
        return
    raise AssertionError("expected HTTP %d" % code)

r = change(A, "@Alice_2")
assert r["username"] == "@alice_2" and m.me(A)["username"] == "@alice_2"
ok("username changes (leading @ removed, lower-case) and /users/me shows it")

assert m.user_profile("alice_2", B)["username"] == "@alice_2"
refused(lambda: m.user_profile("alice", B), 404)
ok("the new name finds the profile, the old name no longer does")

refused(lambda: change(B, "alice_2"), 409)
refused(lambda: change(B, "ALICE_2"), 409)
assert m.me(B)["username"] == "@bob"
ok("a name that is taken (any letter case) is refused and nothing changes")

assert change(B, "alice")["username"] == "@alice"
ok("the old name of someone else is free again")

for bad in ("ab", "x" * 21, "has space", "bad-dash", "@", "emoji\U0001F600name"):
    refused(lambda: change(A, bad), 422)
assert m.me(A)["username"] == "@alice_2"
ok("invalid names are refused (too short / long, spaces, dash, empty, emoji)")

r = change(A, "alice_2", display_name="Alice A")
assert r["username"] == "@alice_2" and r["display_name"] == "Alice A"
ok("sending the same name is fine and the other fields still save")

change(B, "taken_name_x")
refused(lambda: change(A, "taken_name_x", display_name="Should not save"), 409)
assert m.me(A)["display_name"] == "Alice A"
ok("when the username fails, the other fields in the same request are not saved")

m._rate.clear()
for i in range(5):
    m.update_me(m.ProfileUpdate(username="loop_%d" % i), A)
refused(lambda: m.update_me(m.ProfileUpdate(username="loop_9"), A), 429)
ok("rate limit: 5 username changes per hour")

m._rate.clear()
m.update_me(m.ProfileUpdate(bio="hello"), A)
assert m.me(A)["username"] == "@loop_4"
ok("updates without a username leave it alone")
print("ALL PASS")
