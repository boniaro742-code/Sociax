"""Backend checks for the limit-reached answers (plan section 8): the app needs a machine-readable code
to show "upgrade or wait" (AI) and the separate App Pro offer (media).
Run:  cd backend && DEV_AUTH=1 python test_limits.py"""
import os, sqlite3, tempfile, time

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A = "dev-a"
m.onboard(m.Onboarding(display_name="alice", username="alice", age=20), A)


def err(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k)
    except HTTPException as e:
        return e
    return None


# ---- AI: the free allowance is used up -> 429 with code ai_limit and the real reset time
m.FREE_AI_REQUESTS = 2
from contextlib import closing  # noqa: E402
with closing(m.db()) as con:
    m.ai_take(con, A); m.ai_take(con, A)
    e = err(m.ai_take, con, A)
assert e is not None and e.status_code == 429 and isinstance(e.detail, dict), e
assert e.detail["code"] == "ai_limit" and 0 < e.detail["reset_in_seconds"] <= 48 * 3600 and "Free AI limit" in e.detail["message"]

# ---- media: today's allowance (500 MB free; here 1 MB) is full -> 413 with code media_full and the reset time
m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "c", "k", "s"
m.FREE_MEDIA_MB = 1
with closing(m.db()) as con:
    con.execute("INSERT INTO media(public_id, uid, kind, bytes, created_at) VALUES (?,?,?,?,?)",
                ("x", A, "post", 900 * 1024, int(time.time())))
    m.media_take(con, A, 900 * 1024)
    con.commit()
e = err(m.sign_upload, m.SignRequest(purpose="post", size_bytes=300 * 1024), A)
assert e is not None and e.status_code == 413 and e.detail["code"] == "media_full", e
assert 0 < e.detail["reset_in_seconds"] <= 24 * 3600 and "Daily media limit" in e.detail["message"], e.detail
# a file that still fits is signed normally
assert err(m.sign_upload, m.SignRequest(purpose="post", size_bytes=50 * 1024), A) is None
# per-file size errors stay plain text (not a quota problem, no upgrade offer)
e = err(m.sign_upload, m.SignRequest(purpose="post", size_bytes=20 * 1024 * 1024), A)
assert e.status_code == 413 and isinstance(e.detail, str)
print("test_limits: all checks passed")
