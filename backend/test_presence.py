"""Backend checks for Online / last seen (Step 25). Run:  cd backend && DEV_AUTH=1 python test_presence.py"""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C = "dev-a", "dev-b", "dev-c"
for uid, name in ((A, "alice"), (B, "bob"), (C, "carol")):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def ok(msg): print("PASS", msg)
def pres(viewer, name): m._rate.clear(); return m.get_presence(name, viewer)["presence"]
def beat(uid): m._rate.clear(); return m.heartbeat(uid)
def set_ls(uid, v): m.update_me(m.ProfileUpdate(show_last_seen=v), uid)
def age(uid, seconds):
    with closing(m.db()) as con:
        con.execute("UPDATE users SET last_seen_at=? WHERE uid=?", (int(time.time()) - seconds, uid)); con.commit()

assert m.me(A)["show_last_seen"] is True
ok("default: on, returned by /users/me")

# no chat yet and no heartbeat: nothing visible
assert pres(A, "bob") is None
beat(B)
assert pres(A, "bob") is None
ok("without a one-to-one chat nobody sees presence")

m.start_dm(m.DmStart(username="bob"), A)
p = pres(A, "bob")
assert p and p["online"] is True and abs(p["last_seen_at"] - time.time()) < 5
ok("with a chat: bob is online right after his heartbeat")
assert [c for c in m.list_chats(A)["chats"]][0]["presence"]["online"] is True
ok("chat list carries presence too")

age(B, 600)
p = pres(A, "bob")
assert p["online"] is False and 590 < time.time() - p["last_seen_at"] < 620
ok("10 minutes later: not online, last seen time is shown")

# reciprocal: if I hide mine, I see nobody's
set_ls(A, False)
assert pres(A, "bob") is None
assert m.me(A)["show_last_seen"] is False
r = beat(A)
assert r["recording"] is False
with closing(m.db()) as con:
    assert con.execute("SELECT last_seen_at FROM users WHERE uid=?", (A,)).fetchone()[0] == 0
assert pres(B, "alice") is None
ok("switched off: nothing recorded, nothing shown either way")
set_ls(A, True)
assert pres(A, "bob") is not None
ok("switched on again: works again")

# bob hides his: alice sees nothing
set_ls(B, False)
assert pres(A, "bob") is None
set_ls(B, True); beat(B)
ok("a person who hides his own status is never shown")

# blocked: hidden
with closing(m.db()) as con:
    con.execute("INSERT INTO blocks(blocker, blocked) VALUES (?,?)", (B, A)); con.commit()
assert pres(A, "bob") is None and pres(B, "alice") is None
with closing(m.db()) as con:
    con.execute("DELETE FROM blocks"); con.commit()
ok("blocked people see nothing")

# unknown name = same null answer; carol (no chat) sees nothing
assert pres(A, "nobody-here") is None and pres(C, "bob") is None
ok("missing user / no chat: the same null")

# rate limit
m._rate.clear()
try:
    for _ in range(40): m.heartbeat(A)
    raise AssertionError("no rate limit")
except HTTPException as e:
    assert e.status_code == 429
ok("heartbeat is rate limited")
print("test_presence: all checks passed")
