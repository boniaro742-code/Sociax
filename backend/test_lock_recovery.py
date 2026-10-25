"""Backend checks for the App Lock recovery tokens (Step 32, plan v3.3 section 3).
Run:  cd backend && DEV_AUTH=1 python test_lock_recovery.py"""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, OLD = "dev-a", "dev-b", "dev-stale-c"
BEARER = lambda u: f"Bearer {u}"


def err(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k)
    except HTTPException as e:
        return e
    return None


def start(uid, auth=None):
    m._rate.clear()
    return m.lock_recovery_start(uid, auth or BEARER(uid))


def confirm(uid, token):
    m._rate.clear()
    return m.lock_recovery_confirm(m.LockToken(token=token), uid)


# 1. a fresh sign-in gets a token; only its hash is stored
r = start(A)
assert len(r["token"]) >= 40 and r["expires_in"] == 300
with closing(m.db()) as con:
    rows = con.execute("SELECT * FROM lock_recovery").fetchall()
assert len(rows) == 1 and rows[0]["token_hash"] != r["token"] and r["token"] not in str(dict(rows[0]))

# 2. the token works once, for its owner
assert confirm(A, r["token"]) == {"ok": True}
e = err(confirm, A, r["token"])
assert e is not None and e.status_code == 403 and e.detail["code"] == "recovery_invalid"      # single-use

# 3. somebody else's token never works (and the failure looks the same as every other failure)
r2 = start(A)
e = err(confirm, B, r2["token"])
assert e is not None and e.status_code == 403 and e.detail["code"] == "recovery_invalid"
assert confirm(A, r2["token"])["ok"]                                                            # still valid for the owner (B's try did not use it up)

# 4. a made-up token fails the same way
e = err(confirm, A, "x" * 43)
assert e is not None and e.status_code == 403 and e.detail["code"] == "recovery_invalid"

# 5. an OLD sign-in cannot start a recovery: a fresh Google sign-in is required
e = err(start, OLD)
assert e is not None and e.status_code == 403 and e.detail["code"] == "reauth_required"
assert not [1 for _ in m.db().execute("SELECT 1 FROM lock_recovery WHERE uid=?", (OLD,))]

# 6. expired tokens fail; a newer token replaces the older one
r3 = start(A)
with closing(m.db()) as con:
    con.execute("UPDATE lock_recovery SET expires_at=? WHERE uid=?", (int(time.time()) - 1, A)); con.commit()
e = err(confirm, A, r3["token"])
assert e is not None and e.status_code == 403
old = start(A); new = start(A)
assert err(confirm, A, old["token"]) is not None and confirm(A, new["token"])["ok"]

# 7. attempts are rate limited
m._rate.clear()
for _ in range(5):
    m.lock_recovery_start(A, BEARER(A))
e = None
try:
    m.lock_recovery_start(A, BEARER(A))
except HTTPException as ex:
    e = ex
assert e is not None and e.status_code == 429

# 8. deleting the account removes pending tokens
m._rate.clear()
start(B)
with closing(m.db()) as con:
    m._delete_user_data(con, B); con.commit()
    assert con.execute("SELECT COUNT(*) FROM lock_recovery WHERE uid=?", (B,)).fetchone()[0] == 0
print("PASS lock recovery: fresh sign-in needed, one-time, owner-only, 5 minutes, rate limited, cleaned on delete")
