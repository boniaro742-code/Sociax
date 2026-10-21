"""Backend checks for Step 22: the "Follow me first" prompt (plan section 4).
Run:  cd backend && DEV_AUTH=1 python test_follow_first.py      (or: pytest -q test_follow_first.py)
Rules checked: the recipient opts in; a message from someone who does not follow them is HELD (invisible, not unread,
never marked read); the sender is told once with a prompt; following releases the message as UNREAD (follow acceptance and
read confirmation are separate); switching the option off releases everything; private accounts need an ACCEPTED follow."""
import os, tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "ff.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C = "dev-a", "dev-b", "dev-c"


def code(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k); return 200
    except HTTPException as e:
        return e.status_code


def user(uid, name):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


def send(uid, cid, text):
    m._rate.clear()
    return m.send_message(cid, m.NewMessage(text=text), uid)


def texts(uid, cid):
    return [x["text"] for x in m.get_messages(cid, None, 50, uid)["messages"]]


def unread(uid, cid):
    return next(c for c in m.list_chats(uid)["chats"] if c["id"] == cid)["unread"]


user(A, "alice"); user(B, "bob"); user(C, "carol")
check("default: option is off", m.me(A)["follow_first"], False)

# option OFF: normal delivery, no prompt
cid = m.start_dm(m.DmStart(username="@alice"), B)["id"]
r = send(B, cid, "hello (option off)")
check("off: no prompt", "follow_prompt" in r, False)
check("off: alice sees it", texts(A, cid), ["hello (option off)"])

# alice turns the prompt ON
check("saved + returned", m.update_me(m.ProfileUpdate(follow_first=True), A)["follow_first"], True)
m.mark_read(cid, A)
r = send(B, cid, "please read this")
check("on: sender gets the prompt", r.get("follow_prompt", {}).get("username"), "@alice")
check("on: sender's message is marked held", r["message"]["held"], True)
check("on: sender still sees own message", texts(B, cid)[-1], "please read this")
check("on: recipient does NOT see it", texts(A, cid), ["hello (option off)"])
check("on: recipient has no unread for it", unread(A, cid), 0)
check("on: chat preview does not leak it", next(c for c in m.list_chats(A)["chats"] if c["id"] == cid)["last_text"], "hello (option off)")
m.mark_read(cid, A)                                              # alice opens the chat while the message waits: it must NOT count as read

# bob follows alice -> released, and UNREAD (follow acceptance != read confirmation)
check("following works", m.follow("alice", B)["status"], "accepted")
check("after follow: recipient sees it", texts(A, cid)[-1], "please read this")
check("after follow: it is UNREAD, not falsely read", unread(A, cid), 1)
m.mark_read(cid, A)
check("after reading: unread is 0", unread(A, cid), 0)
r = send(B, cid, "second message")
check("follower: no prompt, delivered at once", ("follow_prompt" in r, texts(A, cid)[-1]), (False, "second message"))

# unfollow later: new messages wait again, old ones stay visible
m.unfollow("alice", B)
send(B, cid, "third after unfollow")
check("after unfollow: new one waits", texts(A, cid)[-1], "second message")
# switching the option OFF releases it (unread)
m.update_me(m.ProfileUpdate(follow_first=False), A)
check("option off: waiting message released", texts(A, cid)[-1], "third after unfollow")
check("option off: released as unread (plus the unread second message)", unread(A, cid), 2)

# the recipient's own messages are never held
m.update_me(m.ProfileUpdate(follow_first=True), B)
r = send(A, cid, "reply from alice")
check("alice (not a follower of bob) is held by bob's setting", "follow_prompt" in r, True)

# private recipient: only an ACCEPTED follow releases
m.update_me(m.ProfileUpdate(is_private=True, follow_first=True), C)
check("private account: stranger cannot start a chat", code(m.start_dm, m.DmStart(username="@carol"), A), 403)
print("test_follow_first: all checks passed")
