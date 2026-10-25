"""Backend checks for Step 46: push notifications through Firebase Cloud Messaging (plan v3.9 section 20: Notifications).
Run:  cd backend && DEV_AUTH=1 python test_push.py
The real sender (_fcm_send) needs Firebase credentials and is NOT run here; everything around it is, with a fake sender.
Checked: tokens (register / move to another account / newest 5 kept / only my own can be removed); a push is queued ONLY
where the bell notification is written (category switch, blocked, myself) and only for a person with a registered phone;
the queued row and the data sent never contain message text; delivery (claim, retry with backoff, dropped after 3 tries or
10 minutes, dead tokens removed, no double send); account deletion removes tokens and queue."""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "push.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402
from pydantic import ValidationError  # noqa: E402

A, B, C = "dev-a", "dev-b", "dev-c"
for uid, name in ((A, "alice"), (B, "bob"), (C, "carol")):
    m.onboard(m.Onboarding(display_name=name, username=name, age=20), uid)


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


def tok(n):
    return f"fcm-token-{n:04d}-" + "x" * 30


def reg(uid, t):
    m._rate.clear()
    return m.push_register(m.PushToken(token=t), uid)


def code(fn, *a):
    m._rate.clear()
    try:
        fn(*a); return 200
    except HTTPException as e:
        return e.status_code
    except ValidationError:          # real pydantic refuses a bad body before the endpoint runs: same meaning as 422
        return 422


def code_token(token, uid):
    """Like code(), but the body is built INSIDE the try (real pydantic raises while building a bad token)."""
    def call(_uid):
        return m.push_register(m.PushToken(token=token), _uid)
    return code(call, uid)


def rows(sql="SELECT * FROM push_outbox ORDER BY id", args=()):
    with closing(m.db()) as con:
        return [dict(r) for r in con.execute(sql, args).fetchall()]


def clear_outbox():
    with closing(m.db()) as con:
        con.execute("DELETE FROM push_outbox"); con.commit()


def tokens_of(uid):
    return sorted(r["token"] for r in rows("SELECT token FROM push_tokens WHERE uid=?", (uid,)))


# ---------- tokens ----------
check("a too short token is refused (422)", code_token("short", A), 422)
check("a token with spaces / control characters is refused (422)", code_token("a b" + "x" * 30, A), 422)
reg(A, tok(1))
check("register stores the token", tokens_of(A), [tok(1)])
reg(A, tok(1))
check("registering twice keeps ONE row", tokens_of(A), [tok(1)])
reg(B, tok(1))
check("the same phone signing in as another account: the token MOVES (alice no longer gets bob's pushes)", (tokens_of(A), tokens_of(B)), ([], [tok(1)]))
for i in range(2, 9):
    reg(A, tok(i)); time.sleep(0.002)
check("at most 5 phones per account (newest kept)", len(tokens_of(A)), 5)
check("... the newest ones", tokens_of(A), sorted(tok(i) for i in range(4, 9)))
m._rate.clear(); m.push_remove(m.PushToken(token=tok(8)), B)
check("bob cannot remove alice's token", tok(8) in tokens_of(A), True)
m._rate.clear(); m.push_remove(m.PushToken(token=tok(8)), A)
check("alice removes her own token", tok(8) in tokens_of(A), False)
check("unknown user cannot register (404)", code(m.push_register, m.PushToken(token=tok(50)), "dev-ghost"), 404)

# ---------- what is queued ----------
clear_outbox()
m._rate.clear(); m.follow("alice", C)          # carol follows alice
q = rows()
check("a follow queues ONE push for alice with type, @actor, item, count", [(r["uid"], r["type"], r["actor"], r["count"]) for r in q], [(A, "follow", "@carol", 1)])
clear_outbox()
m._rate.clear(); m.follow("carol", A)          # alice follows carol: carol has NO phone registered
check("nobody registered a phone for carol: nothing queued", rows(), [])

# category switch off -> no bell, no push
m.update_me(m.ProfileUpdate(notifications={"follows": False}), A)
clear_outbox()
m._rate.clear(); m.follow("alice", B)
check("follows switched off: no push (same rule as the bell)", rows(), [])
m.update_me(m.ProfileUpdate(notifications={"follows": True}), A)

# blocked -> nothing
with closing(m.db()) as con:
    con.execute("INSERT INTO blocks(blocker, blocked) VALUES (?,?)", (A, C)); con.commit()
clear_outbox()
with closing(m.db()) as con:
    m.notify(con, A, C, "follow", 0); con.commit()
check("blocked person: no push", rows(), [])
with closing(m.db()) as con:
    con.execute("DELETE FROM blocks"); con.commit()
with closing(m.db()) as con:
    m.notify(con, A, A, "follow", 0); con.commit()
check("my own action: no push", rows(), [])

# messages: no text anywhere, count merges
SECRET = "my-private-text-12345"
m._rate.clear(); chat = m.start_dm(m.DmStart(username="alice"), B)["id"]
clear_outbox()
for i in range(3):
    m._rate.clear(); m.send_message(chat, m.NewMessage(text=f"{SECRET}-{i}"), B)
q = rows()
check("3 messages queue 3 rows for alice, counts 1, 2, 3", [(r["type"], r["count"]) for r in q], [("message", 1), ("message", 2), ("message", 3)])
check("the queue never contains message text", SECRET in str(q), False)

# ---------- delivery ----------
calls = []


def ok_sender(token, data):
    calls.append((token, dict(data))); return "ok"


now = int(time.time())
sent = m.push_deliver_once(ok_sender, now=now)
check("delivery: every queued push goes to every phone of alice (3 rows x 4 phones)", sent, 12)
check("the data sent has only type, actor, ref, count", sorted(set(k for _, d in calls for k in d)), ["actor", "count", "ref", "type"])
check("... and no message text", SECRET in str(calls), False)
check("sent rows are removed", rows(), [])
check("nothing queued: nothing sent", m.push_deliver_once(ok_sender, now=now), 0)

# dead token removed, others still get it
clear_outbox(); calls.clear()
with closing(m.db()) as con:
    m.notify(con, A, C, "post_like", 7); con.commit()
dead = tokens_of(A)[0]


def mixed(token, data):
    return "invalid" if token == dead else "ok"


sent = m.push_deliver_once(mixed, now=now)
check("one dead token: the other 3 phones still get it", sent, 3)
check("the dead token is deleted", dead in tokens_of(A), False)
check("row done", rows(), [])

# retry with backoff, 3 attempts, no double send while locked
clear_outbox()
with closing(m.db()) as con:
    m.notify(con, A, C, "post_comment", 9); con.commit()
n = {"calls": 0}


def failing(token, data):
    n["calls"] += 1; return "retry"


phones = len(tokens_of(A))
m.push_deliver_once(failing, now=now)
check("first attempt tried every phone", n["calls"], phones)
r = rows()
check("the row stays, attempts = 1, locked for a while", (len(r), r[0]["attempts"], r[0]["locked_until"] > now), (1, 1, True))
m.push_deliver_once(failing, now=now + 1)
check("a second worker right away does NOT send it again (locked)", n["calls"], phones)
m.push_deliver_once(failing, now=now + 31)
check("after the backoff it is tried again", n["calls"], 2 * phones)
m.push_deliver_once(failing, now=now + 100)
check("third failed attempt: dropped (no endless retries)", (n["calls"], rows()), (3 * phones, []))

# exception inside the sender = retry, not a crash
clear_outbox()
with closing(m.db()) as con:
    m.notify(con, A, C, "post_like", 11); con.commit()


def boom(token, data):
    raise RuntimeError("network down")


check("a sender that raises does not crash delivery", m.push_deliver_once(boom, now=now), 0)
check("... and the row is kept for a retry", len(rows()), 1)

# too old = dropped
clear_outbox()
with closing(m.db()) as con:
    m.notify(con, A, C, "post_like", 12); con.commit()
    con.execute("UPDATE push_outbox SET created_at=?", (now - m.PUSH_MAX_AGE - 5,)); con.commit()
check("a push older than 10 minutes is dropped, not sent", (m.push_deliver_once(ok_sender, now=now), rows()), (0, []))

# removing the last phone clears the queue
clear_outbox()
with closing(m.db()) as con:
    m.notify(con, B, C, "follow", 0); con.commit()
check("bob has a phone: a push is queued for him", len(rows()), 1)
m._rate.clear(); m.push_remove(m.PushToken(token=tok(1)), B)
check("bob removes his last phone: the queue for him is cleared", rows(), [])

# ---------- account deletion ----------
reg(C, tok(60))
with closing(m.db()) as con:
    m.notify(con, C, A, "post_comment", 3); con.commit()          # (a "follow" would be skipped: alice already followed carol above)
check("carol has a token and a queued push", (len(tokens_of(C)), len(rows())), (1, 1))
m._rate.clear(); m.delete_account(C)
check("deleting the account removes her tokens and her queue", (tokens_of(C), rows()), ([], []))

# the real sender fails safe without credentials
check("the real sender never raises: without Firebase credentials it answers 'retry'", m._fcm_send(tok(1), {"type": "follow"}) in ("retry", "invalid"), True)
print("test_push: all checks passed")
