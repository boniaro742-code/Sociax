"""Backend checks for Step 43: Chat > read receipts (plan section 9).
Run:  cd backend && DEV_AUTH=1 python test_read_receipts.py      (or: pytest -q test_read_receipts.py)
Rules checked: default ON; "Seen" exists for one-to-one chats only; it is reciprocal (if I switch it off I see nobody's, nobody sees mine);
what is shown is recorded ONLY while the reader's switch is on (switching on later never reveals what was read while off);
it never goes backwards; released "follow me first" messages are not "Seen" until read; blocked / group chats show nothing;
switching off clears what was shown; the app's own unread badge is not affected by the switch."""
import os, tempfile
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "rr.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C = "dev-a", "dev-b", "dev-c"
for uid, name in ((A, "alice"), (B, "bob"), (C, "carol")):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


def send(uid, cid, text):
    m._rate.clear()
    return m.send_message(cid, m.NewMessage(text=text), uid)["message"]["id"]


def seen_by_peer(viewer, cid):
    """What `viewer` is told about the other person: peer_read_id from GET /chats/{id}/messages."""
    return m.get_messages(cid, None, 50, viewer)["peer_read_id"]


def switch(uid, on):
    m._rate.clear()
    return m.update_me(m.ProfileUpdate(read_receipts=on), uid)


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


check("default: on, returned by /users/me", m.me(A)["read_receipts"], True)
with closing(m.db()) as con:
    check("new columns exist (users.read_receipts, conv_members.receipt_read_id)",
          ({r["name"] for r in con.execute("PRAGMA table_info(users)")} >= {"read_receipts"}
           and {r["name"] for r in con.execute("PRAGMA table_info(conv_members)")} >= {"receipt_read_id"}), True)

m._rate.clear()
cid = m.start_dm(m.DmStart(username="bob"), A)["id"]
m1 = send(A, cid, "hello bob")
check("bob has not opened the chat: alice sees 0 (nothing seen yet)", seen_by_peer(A, cid), 0)

m.mark_read(cid, B)
check("bob opens the chat: alice sees the message as seen", seen_by_peer(A, cid), m1)
check("bob is not told anything about alice's reading (she has not read his messages)", seen_by_peer(B, cid), 0)

m2 = send(A, cid, "second")
check("a new message is not seen until bob reads it", seen_by_peer(A, cid), m1)
m.mark_read(cid, B)
check("after bob reads again: seen moves forward", seen_by_peer(A, cid), m2)

# never backwards
with closing(m.db()) as con:
    con.execute("UPDATE conv_members SET last_read_id=0 WHERE conv_id=? AND uid=?", (cid, B)); con.commit()
m.mark_read(cid, B)
check("never goes backwards", seen_by_peer(A, cid) >= m2, True)

# the unread badge is separate
b_summary = [c for c in m.list_chats(B)["chats"] if c["id"] == cid][0]
check("bob's own unread badge is 0 after reading", b_summary["unread"], 0)

# reciprocal: bob switches OFF
r = switch(B, False)
check("switch off is saved and returned", r["read_receipts"], False)
check("bob off: alice sees nothing (null)", seen_by_peer(A, cid), None)
check("bob off: bob sees nothing either (null)", seen_by_peer(B, cid), None)
with closing(m.db()) as con:
    check("switching off clears what was shown", con.execute(
        "SELECT receipt_read_id FROM conv_members WHERE conv_id=? AND uid=?", (cid, B)).fetchone()[0], 0)

# messages read while OFF must not appear when it is switched on again
m3 = send(A, cid, "sent while bob is off")
m.mark_read(cid, B)                                   # bob reads it while his switch is OFF
with closing(m.db()) as con:
    check("read while off: nothing recorded for sharing", con.execute(
        "SELECT receipt_read_id FROM conv_members WHERE conv_id=? AND uid=?", (cid, B)).fetchone()[0], 0)
check("read while off: bob's own unread badge still works", [c for c in m.list_chats(B)["chats"] if c["id"] == cid][0]["unread"], 0)
switch(B, True)
check("bob switches on again: what he read while off is NOT revealed", seen_by_peer(A, cid), 0)
m4 = send(A, cid, "after on")
m.mark_read(cid, B)
check("from now on it works again", seen_by_peer(A, cid), m4)

# alice off: she sees nothing and bob sees nothing of hers
bob_msg = send(B, cid, "hi alice")
m.mark_read(cid, A)
check("alice on: bob sees her read of his message", seen_by_peer(B, cid), bob_msg)
switch(A, False)
check("alice off: bob sees null", seen_by_peer(B, cid), None)
check("alice off: alice sees null", seen_by_peer(A, cid), None)
switch(A, True)

# groups never show receipts
m._rate.clear()
g = m.create_group(m.NewGroup(name="team", usernames=["bob"], confirmed=True), A)
gid = g["id"]
send(A, gid, "group hello")
m.mark_read(gid, B)
check("group chat: no receipts at all", m.get_messages(gid, None, 50, A)["peer_read_id"], None)
with closing(m.db()) as con:
    check("group chat: nothing recorded for sharing", con.execute(
        "SELECT receipt_read_id FROM conv_members WHERE conv_id=? AND uid=?", (gid, B)).fetchone()[0], 0)

# blocked: the chat is gone (same 404 as before), no receipt leaks
m._rate.clear()
cid2 = m.start_dm(m.DmStart(username="carol"), A)["id"]
send(A, cid2, "hi carol")
m.mark_read(cid2, C)
check("carol read it: alice sees it", seen_by_peer(A, cid2) > 0, True)
m._rate.clear()
m.block_user("carol", A)
check("blocked: the chat is not reachable (404)", code(m.get_messages, cid2, None, 50, A), 404)
check("blocked: the other side gets the same 404", code(m.get_messages, cid2, None, 50, C), 404)

# "Follow me first": a released message is not "Seen" until the recipient reads it
D, E = "dev-d", "dev-e"
for uid, name in ((D, "dora"), (E, "eli")):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)
m._rate.clear()
m.update_me(m.ProfileUpdate(follow_first=True), E)                   # eli asks senders to follow him first
cid3 = m.start_dm(m.DmStart(username="eli"), D)["id"]
held_id = send(D, cid3, "hello eli (held)")
m.mark_read(cid3, E)                                                 # eli opens the chat: the held message is invisible to him
check("held message is not 'Seen' (eli cannot see it)", seen_by_peer(D, cid3) < held_id, True)
m._rate.clear()
m.follow("eli", D)                                                   # dora follows eli: the message is released
check("released message is NOT 'Seen' until eli reads it", seen_by_peer(D, cid3) < held_id, True)
m.mark_read(cid3, E)
check("eli reads the released message: now 'Seen'", seen_by_peer(D, cid3), held_id)

print("test_read_receipts: all checks passed")
