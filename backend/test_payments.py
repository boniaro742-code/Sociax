"""Chapa payment checks (TEST mode, Chapa is faked: no network, no real key).
Run:  cd backend && DEV_AUTH=1 python test_payments.py"""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
os.environ["CHAPA_SECRET_KEY"] = "CHASECK_TEST-fake-for-unit-tests"
import main as m  # noqa: E402
import chapa_client as cc  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B = "dev-a", "dev-b"
m.onboard(m.Onboarding(display_name="alice", username="alice", age=20), A)
m.onboard(m.Onboarding(display_name="bob", username="bobby", age=20), B)


def err(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k)
    except HTTPException as e:
        return e
    return None


# ---- fake Chapa ----
sent = {}
verdict = {}  # tx_ref -> data dict returned by "verify"


def fake_init(payload):
    sent.update(payload)
    return {"status": "success", "message": "Hosted Link", "data": {"checkout_url": "https://checkout.chapa.co/x/" + payload["tx_ref"]}}


def fake_verify(tx_ref):
    d = verdict.get(tx_ref)
    if d is None:
        return {"status": "failed", "message": "Transaction not found", "data": None}
    return {"status": "success", "message": "ok", "data": d}


cc.initialize, cc.verify = fake_init, fake_verify


def start(uid):
    m._rate.clear()
    r = m.payments_initialize(uid)
    return r["tx_ref"]


def good(tx, **over):
    d = {"status": "success", "amount": 15, "currency": "USD", "tx_ref": tx, "reference": "CH123"}
    d.update(over)
    verdict[tx] = d


def verify(uid, tx):
    m._rate.clear()
    return m.payments_verify(tx, uid)


# 1. initialize: server fixes the price ($15 USD, ONE plan: Sociax Pro); the app sends nothing; checkout url comes back
tx = start(A)
assert sent["amount"] == "15" and sent["currency"] == "USD" and sent["tx_ref"] == tx
assert m.payments_status(tx, A)["status"] == "initiated" and not m.is_pro(A)

# 2. a redirect / opening the page alone grants nothing; Chapa says pending -> no Pro
verdict[tx] = {"status": "pending", "amount": 15, "currency": "USD", "tx_ref": tx}
assert verify(A, tx)["status"] == "pending" and not m.is_pro(A)

# 3. failed, cancelled -> no Pro
for st in ("failed", "cancelled"):
    t = start(A); verdict[t] = {"status": st, "amount": 15, "currency": "USD", "tx_ref": t}
    assert verify(A, t)["status"] == "failed" and not m.is_pro(A)

# 4. unknown tx_ref -> rejected (404); verify says "not found" -> no Pro
assert err(m.payments_verify, "sx-nope", A).status_code == 404
t = start(A)  # exists for us, but Chapa does not know it
assert verify(A, t)["status"] == "failed" and not m.is_pro(A)

# 5. wrong amount / wrong currency / wrong reference -> rejected, no Pro
for over in ({"amount": 1}, {"currency": "ETB"}, {"tx_ref": "sx-other"}):
    t = start(A); good(t, **over)
    assert verify(A, t)["status"] == "rejected" and not m.is_pro(A), over

# 6. another user cannot use / read / verify my transaction
tx = start(A); good(tx)
assert err(m.payments_verify, tx, B).status_code == 404 and err(m.payments_status, tx, B).status_code == 404
assert not m.is_pro(B) and not m.is_pro(A)

# 7. success -> exactly 30 days of Sociax Pro: AI allowance x7 and media 1.5 GB per day, immediately
m.FREE_AI_REQUESTS = 10
assert m.ai_limit(A) == 10
t0 = int(time.time())
r = verify(A, tx)
assert r["status"] == "success" and r["granted"] and abs(r["pro_expires_at"] - (t0 + 30 * 86400)) < 5, r
assert m.is_pro(A) and m.ai_limit(A) == 70 and m.ai_limit(B) == 10
assert m.ai_quota(A)["pro"] is True and m.ai_quota(B)["pro"] is False
m.FREE_MEDIA_MB = 500
assert m.user_limit_mb(A) == 1536 and m.user_limit_mb(B) == 500          # 1.5 GB vs 500 MB per day
assert m.media_quota(A)["limit_mb"] == 1536 and m.media_quota(A)["pro"] is True and m.media_quota(B)["pro"] is False
first_expiry = r["pro_expires_at"]

# 8. repeated verification never adds time again
for _ in range(3):
    assert verify(A, tx)["pro_expires_at"] == first_expiry
assert m.pro_expires_at(A) == first_expiry

# 9. a second successful payment while Pro is active EXTENDS from the current end (60 days total)
tx2 = start(A); good(tx2)
assert verify(A, tx2)["pro_expires_at"] == first_expiry + 30 * 86400

# 10. expiry: after the paid period Pro is gone, until a new verified payment
with closing(m.db()) as con:
    con.execute("UPDATE payments SET pro_expires_at=? WHERE uid=?", (int(time.time()) - 5, A)); con.commit()
assert not m.is_pro(A) and m.ai_limit(A) == 10
tx3 = start(A); good(tx3)
r = verify(A, tx3)
assert m.is_pro(A) and abs(r["pro_expires_at"] - (int(time.time()) + 30 * 86400)) < 5  # restarts from now

# 11. TEST mode guard: a live key is refused unless the owner allows it
os.environ["CHAPA_SECRET_KEY"] = "CHASECK-live-fake"
assert not cc.configured() and err(m.payments_initialize, A).status_code == 503
os.environ["CHAPA_SECRET_KEY"] = "CHAPA_LIVE_PRIV_fake"
assert not cc.configured()
os.environ["CHAPA_SECRET_KEY"] = "CHAPA_TEST_PRIV_fake"
assert cc.configured()  # new-style TEST key is accepted
os.environ["CHAPA_SECRET_KEY"] = ""
assert err(m.payments_initialize, A).status_code == 503  # not connected -> clear message, no crash
os.environ["CHAPA_SECRET_KEY"] = "CHASECK_TEST-fake-for-unit-tests"

# 12. Chapa down: initialize -> 502 and no Pro; verify -> 502 and nothing changes
cc.initialize = lambda p: (_ for _ in ()).throw(cc.ChapaError("down"))
assert err(m.payments_initialize, A).status_code == 502
cc.initialize = fake_init
t = start(A)
cc.verify = lambda tx: (_ for _ in ()).throw(cc.ChapaError("down"))
assert err(m.payments_verify, t, A).status_code == 502 and m.payments_status(t, A)["status"] == "initiated"

# 12b. ONE plan only (plan v3.2): the old $10 AI Pro and $3 App Pro cannot be bought any more
cc.verify = fake_verify   # Chapa is "up" again after test 12
m._rate.clear()
assert err(m.payments_initialize, B, "gold").status_code == 400          # unknown product -> refused
for old in ("ai_pro", "app_pro"):
    m._rate.clear()
    assert err(m.payments_initialize, B, old).status_code == 400, old   # removed purchase paths
m._rate.clear()
r = m.payments_initialize(B)                                              # default product is Sociax Pro
assert sent["amount"] == "15" and sent["customization"]["title"] == "Sociax Pro" and r["product"] == "pro"
assert not m.is_pro(B) and m.user_limit_mb(B) == 500                      # opening checkout grants nothing
# paying the OLD $10 price for a Pro reference is rejected: the server compares with the $15 price
txb = r["tx_ref"]
verdict[txb] = {"status": "success", "amount": 10, "currency": "USD", "tx_ref": txb, "reference": "CH9"}
assert verify(B, txb)["status"] == "rejected" and not m.is_pro(B) and m.ai_limit(B) == m.FREE_AI_REQUESTS
# the correct $15 payment unlocks EVERYTHING at once
txb = m.payments_initialize(B)["tx_ref"]
verdict[txb] = {"status": "success", "amount": 15, "currency": "USD", "tx_ref": txb, "reference": "CH10"}
assert verify(B, txb)["granted"] and m.is_pro(B) and m.user_limit_mb(B) == 1536 and m.ai_limit(B) == 7 * m.FREE_AI_REQUESTS
# time already paid under the old plans is honoured until it ends (never sold again)
with closing(m.db()) as con:
    con.execute("INSERT INTO payments(tx_ref, uid, product, amount, currency, status, created_at, paid_at, pro_started_at, pro_expires_at, granted) "
                "VALUES ('old1','dev-c','app_pro_1_month','3','USD','success',?,?,?,?,1)",
                (int(time.time()), int(time.time()), int(time.time()), int(time.time()) + 86400)); con.commit()
assert m.is_pro("dev-c") and m.user_limit_mb("dev-c") == 1536
assert err(m.payments_verify, "old1", "dev-c").status_code == 404      # a legacy row is never granted again

# 13. no real secret key in any source file (only backend/.env, which .gitignore keeps out of GitHub)
import re
pat = re.compile(r"(CHASECK_TEST-|CHAPA_TEST_PRIV_)(?!fake)[A-Za-z0-9_\-]{16,}")
here = os.path.dirname(os.path.abspath(__file__))
for root, _, files in os.walk(os.path.join(here, "..")):
    for f in files:
        if f.endswith((".py", ".kt", ".kts", ".md", ".example", ".xml", ".json")):
            assert not pat.search(open(os.path.join(root, f), errors="ignore").read()), f
print("test_payments: all checks passed")
