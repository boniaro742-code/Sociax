"""Step 41 (plan v3.8 section 28): AdMob rules kept by the SERVER: Free only, at most 20 COUNTED impressions per day,
idempotent counting, Pro sees no ads, reset at the app-defined day, minors flagged, account deletion clears the counter.
Run:  cd backend && DEV_AUTH=1 python test_ads.py"""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A, B, C, KID = "dev-a", "dev-b", "dev-c", "dev-kid"


def ok(msg):
    print("PASS", msg)


def imp(uid, key):
    m._rate.clear()
    return m.ads_impression(m.AdImpression(idem_key=key), uid)


def status(uid):
    m._rate.clear()
    return m.ads_status(uid)


def code(fn, *a):
    m._rate.clear()
    try:
        fn(*a); return 200
    except HTTPException as e:
        return e.status_code


for u, n, age in [(A, "anna", 20), (B, "ben", 25), (C, "cara", 30), (KID, "kid", 16)]:
    m.onboard(m.Onboarding(display_name=n, username=n, age=age), u)

# ---- 1. a new Free account may see ads: 0 of 20 used
s = status(A)
assert s["eligible"] is True and s["pro"] is False and s["daily_cap"] == 20 and s["used"] == 0 and s["remaining"] == 20
assert s["every_min"] == 3 and s["every_max"] == 4 and s["resets_at"] > int(time.time()) and s["minor"] is False
ok("Free account: eligible, 20 of 20 left, spacing 3-4 videos, reset time given")

# ---- 2. a counted impression raises the counter; the same key again (a retry) counts once
r = imp(A, "ad-key-0001")
assert r["counted"] is True and r["used"] == 1 and r["remaining"] == 19
r = imp(A, "ad-key-0001")
assert r["counted"] is False and r["duplicate"] is True and r["used"] == 1
assert status(A)["used"] == 1
ok("an impression is counted once; a retry with the same key is ignored")

# ---- 3. the cap: exactly 20 are counted, the 21st is NOT, and the feed is told to stop asking for ads
for i in range(2, 21):
    assert imp(A, f"ad-key-{i:04d}")["counted"] is True
s = status(A)
assert s["used"] == 20 and s["remaining"] == 0 and s["eligible"] is False and s["reason"] == "cap"
r = imp(A, "ad-key-0021")
assert r["counted"] is False and r["reason"] == "cap" and r["used"] == 20
with closing(m.db()) as con:
    assert m.ads_counted_today(con, A) == 20
ok("the 20-impression cap holds: the 21st impression is not counted")

# ---- 4. a retry of an impression that WAS counted still answers 'duplicate' after the cap is full
r = imp(A, "ad-key-0005")
assert r["duplicate"] is True and r["counted"] is False and r["used"] == 20
ok("a retry after the cap is a duplicate, not an error")

# ---- 5. one account's counter never touches another's (two accounts, same keys)
assert status(B)["used"] == 0 and imp(B, "ad-key-0001")["counted"] is True and status(B)["used"] == 1
ok("counters are per account (the same key text works for another account)")

# ---- 6. reset: when the app-defined day changes the account can see ads again
with closing(m.db()) as con:
    con.execute("UPDATE ad_impressions SET day = day - 1 WHERE uid=?", (A,)); con.commit()          # pretend those 20 were yesterday
s = status(A)
assert s["used"] == 0 and s["remaining"] == 20 and s["eligible"] is True
assert imp(A, "ad-key-new1")["counted"] is True
assert m.ad_day(0) == 0 and m.ad_day(86399) == 0 and m.ad_day(86400) == 1      # day boundary at 00:00 UTC (offset 0)
ok("a new day starts a fresh count (reset at 00:00 UTC by default)")
old = m.AD_DAY_OFFSET_MINUTES
m.AD_DAY_OFFSET_MINUTES = 180
assert m.ad_day(86400 - 180 * 60) == 1 and m.ad_day(86400 - 180 * 60 - 1) == 0
assert m.ad_day_resets_at(86400 - 180 * 60 - 1) == 86400 - 180 * 60
m.AD_DAY_OFFSET_MINUTES = old
ok("the reset time can be moved with AD_DAY_UTC_OFFSET_MINUTES")

# ---- 7. Pro: no ads, nothing counted, decided from the VERIFIED payment on the server
t0 = int(time.time())
with closing(m.db()) as con:
    con.execute("INSERT INTO payments(tx_ref, uid, product, amount, currency, status, created_at, paid_at, pro_started_at, pro_expires_at, granted) "
                "VALUES ('p1',?, 'pro_1_month','15','USD','success',?,?,?,?,1)", (C, t0, t0, t0, t0 + 30 * 86400)); con.commit()
s = status(C)
assert s["pro"] is True and s["eligible"] is False and s["remaining"] == 0 and s["reason"] == "pro"
r = imp(C, "ad-key-pro1")
assert r["counted"] is False and r["reason"] == "pro"
with closing(m.db()) as con:
    assert m.ads_counted_today(con, C) == 0
ok("Pro: not eligible, remaining 0, an impression is never counted")

# ---- 8. an UNVERIFIED payment (not granted) does not make an account Pro: it still gets ads
with closing(m.db()) as con:
    con.execute("INSERT INTO payments(tx_ref, uid, product, amount, currency, status, created_at, paid_at, pro_started_at, pro_expires_at, granted) "
                "VALUES ('p2',?, 'pro_1_month','15','USD','pending',?,NULL,NULL,0,0)", (B, t0)); con.commit()
assert status(B)["pro"] is False and status(B)["eligible"] is True
ok("a payment that the server has not verified does not remove ads")

# ---- 9. when Pro ends the account is Free again
with closing(m.db()) as con:
    con.execute("UPDATE payments SET pro_expires_at=? WHERE tx_ref='p1'", (t0 - 10,)); con.commit()
assert status(C)["pro"] is False and status(C)["eligible"] is True
ok("when the paid time ends, ads are allowed again")

# ---- 10. minors are flagged so the phone asks AdMob for child-safe treatment
assert status(KID)["minor"] is True and status(A)["minor"] is False
ok("an account under 18 is flagged (minor: true)")

# ---- 11. bad keys are refused (the model and the code both check)
assert code(m.ads_impression, m.AdImpression.model_construct(idem_key="short"), A) == 422
assert code(m.ads_impression, m.AdImpression.model_construct(idem_key="bad key with spaces!"), A) == 422
ok("a malformed idempotency key answers 422")

# ---- 12. abuse limit: more than 30 reports a minute from one account answers 429
m._rate.clear()
res = []
for i in range(40):
    try:
        m.ads_impression(m.AdImpression(idem_key=f"flood-{i:04d}"), B); res.append(200)
    except HTTPException as e:
        res.append(e.status_code)
assert 429 in res and res.count(200) <= 30
ok("an account that floods the address is slowed down (429)")

# ---- 13. deleting the account deletes its counter
m._rate.clear()
m.delete_account(A)
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM ad_impressions WHERE uid=?", (A,)).fetchone()[0] == 0
ok("account deletion removes the ad counter rows")

# ---- 14. no AdMob secret or ad content is stored on the server
with closing(m.db()) as con:
    cols = [r[1] for r in con.execute("PRAGMA table_info(ad_impressions)")]
assert cols == ["id", "uid", "day", "idem_key", "created_at"], cols
ok("the table keeps only account, day, key and time (nothing about the ad)")

print("ALL ADS CHECKS PASSED")
