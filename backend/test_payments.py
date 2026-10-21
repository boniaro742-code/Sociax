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
    d = {"status": "success", "amount": 10, "currency": "USD", "tx_ref": tx, "reference": "CH123"}
    d.update(over)
    verdict[tx] = d


def verify(uid, tx):
    m._rate.clear()
    return m.payments_verify(tx, uid)


# 1. initialize: server fixes the price ($10 USD); the app sends nothing; checkout url comes back
tx = start(A)
assert sent["amount"] == "10" and sent["currency"] == "USD" and sent["tx_ref"] == tx
assert m.payments_status(tx, A)["status"] == "initiated" and not m.is_ai_pro(A)

# 2. a redirect / opening the page alone grants nothing; Chapa says pending -> no Pro
verdict[tx] = {"status": "pending", "amount": 10, "currency": "USD", "tx_ref": tx}
assert verify(A, tx)["status"] == "pending" and not m.is_ai_pro(A)

# 3. failed, cancelled -> no Pro
for st in ("failed", "cancelled"):
    t = start(A); verdict[t] = {"status": st, "amount": 10, "currency": "USD", "tx_ref": t}
    assert verify(A, t)["status"] == "failed" and not m.is_ai_pro(A)

# 4. unknown tx_ref -> rejected (404); verify says "not found" -> no Pro
assert err(m.payments_verify, "sx-nope", A).status_code == 404
t = start(A)  # exists for us, but Chapa does not know it
assert verify(A, t)["status"] == "failed" and not m.is_ai_pro(A)

# 5. wrong amount / wrong currency / wrong reference -> rejected, no Pro
for over in ({"amount": 1}, {"currency": "ETB"}, {"tx_ref": "sx-other"}):
    t = start(A); good(t, **over)
    assert verify(A, t)["status"] == "rejected" and not m.is_ai_pro(A), over

# 6. another user cannot use / read / verify my transaction
tx = start(A); good(tx)
assert err(m.payments_verify, tx, B).status_code == 404 and err(m.payments_status, tx, B).status_code == 404
assert not m.is_ai_pro(B) and not m.is_ai_pro(A)

# 7. success -> exactly 30 days of AI Pro, and the free AI allowance becomes 5x
m.FREE_AI_REQUESTS = 10
assert m.ai_limit(A) == 10
t0 = int(time.time())
r = verify(A, tx)
assert r["status"] == "success" and r["granted"] and abs(r["pro_expires_at"] - (t0 + 30 * 86400)) < 5, r
assert m.is_ai_pro(A) and m.ai_limit(A) == 50 and m.ai_limit(B) == 10
assert m.ai_quota(A)["ai_pro"] is True and m.ai_quota(B)["ai_pro"] is False
first_expiry = r["pro_expires_at"]

# 8. repeated verification never adds time again
for _ in range(3):
    assert verify(A, tx)["pro_expires_at"] == first_expiry
assert m.ai_pro_expires_at(A) == first_expiry

# 9. a second successful payment while Pro is active EXTENDS from the current end (60 days total)
tx2 = start(A); good(tx2)
assert verify(A, tx2)["pro_expires_at"] == first_expiry + 30 * 86400

# 10. expiry: after the paid period Pro is gone, until a new verified payment
with closing(m.db()) as con:
    con.execute("UPDATE payments SET pro_expires_at=? WHERE uid=?", (int(time.time()) - 5, A)); con.commit()
assert not m.is_ai_pro(A) and m.ai_limit(A) == 10
tx3 = start(A); good(tx3)
r = verify(A, tx3)
assert m.is_ai_pro(A) and abs(r["pro_expires_at"] - (int(time.time()) + 30 * 86400)) < 5  # restarts from now

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

# 12b. Sociax App Pro ($3, 600 MB) is a SEPARATE product (plan v3.1)
cc.verify = fake_verify   # Chapa is "up" again after test 12
m.FREE_MEDIA_MB = 150
assert m.user_limit_mb(B) == 150 and not m.is_app_pro(B)
m._rate.clear()
assert err(m.payments_initialize, B, "gold").status_code == 400          # unknown product -> refused
m._rate.clear()
r = m.payments_initialize(B, "app_pro")
txa = r["tx_ref"]
assert sent["amount"] == "3" and sent["currency"] == "USD" and r["product"] == "app_pro" and r["amount"] == "3"
assert sent["customization"]["title"] == "Sociax App Pro"
assert m.user_limit_mb(B) == 150                                           # opening checkout grants nothing
# paying $10 (the AI Pro price) for an App Pro reference is rejected: the server compares with the App Pro price
verdict[txa] = {"status": "success", "amount": 10, "currency": "USD", "tx_ref": txa, "reference": "CH9"}
assert verify(B, txa)["status"] == "rejected" and not m.is_app_pro(B) and m.user_limit_mb(B) == 150
# the correct $3 payment -> 30 days of App Pro and the 600 MB allowance, immediately
txa = m.payments_initialize(B, "app_pro")["tx_ref"]
verdict[txa] = {"status": "success", "amount": 3, "currency": "USD", "tx_ref": txa, "reference": "CH10"}
t0 = int(time.time())
r = verify(B, txa)
assert r["status"] == "success" and r["granted"] and r["product"] == "app_pro" and abs(r["pro_expires_at"] - (t0 + 30 * 86400)) < 5, r
assert m.is_app_pro(B) and m.user_limit_mb(B) == 600 and m.user_limit_mb(A) == 150
q = m.media_quota(B)
assert q["limit_mb"] == 600 and q["app_pro"] is True and q["app_pro_expires_at"] == r["pro_expires_at"]
assert m.media_quota(A)["app_pro"] is False
# separate products: App Pro does NOT unlock AI Pro (and the AI allowance stays 1x) ...
assert not m.is_ai_pro(B) and m.ai_limit(B) == m.FREE_AI_REQUESTS
# ... and AI Pro does not unlock App Pro (user A has AI Pro from test 10, but still 150 MB)
assert m.is_ai_pro(A) and not m.is_app_pro(A) and m.user_limit_mb(A) == 150
# repeating verify never adds time again; a second purchase extends from the current end
first = r["pro_expires_at"]
assert verify(B, txa)["pro_expires_at"] == first
txa2 = m.payments_initialize(B, "app_pro")["tx_ref"]
verdict[txa2] = {"status": "success", "amount": 3, "currency": "USD", "tx_ref": txa2}
assert verify(B, txa2)["pro_expires_at"] == first + 30 * 86400
# expiry: back to the free 150 MB until a new verified payment
with closing(m.db()) as con:
    con.execute("UPDATE payments SET pro_expires_at=? WHERE uid=? AND product='app_pro_1_month'", (int(time.time()) - 5, B)); con.commit()
assert not m.is_app_pro(B) and m.user_limit_mb(B) == 150
# the old default (no product given) is still AI Pro
m._rate.clear()
m.payments_initialize(B)
assert sent["amount"] == "10" and sent["customization"]["title"] == "Sociax AI Pro"

# 13. no real secret key in any source file (only backend/.env, which .gitignore keeps out of GitHub)
import re
pat = re.compile(r"(CHASECK_TEST-|CHAPA_TEST_PRIV_)(?!fake)[A-Za-z0-9_\-]{16,}")
here = os.path.dirname(os.path.abspath(__file__))
for root, _, files in os.walk(os.path.join(here, "..")):
    for f in files:
        if f.endswith((".py", ".kt", ".kts", ".md", ".example", ".xml", ".json")):
            assert not pat.search(open(os.path.join(root, f), errors="ignore").read()), f
print("test_payments: all checks passed")
