"""Backend checks for the DAILY media allowance (Step 30, plan v3.2): 500 MB free / 1.5 GB Sociax Pro per day,
counted on the server, one 24 h window that starts at the first upload, no refund on delete, nothing deleted on reset.
Run:  cd backend && DEV_AUTH=1 python test_media_daily.py"""
import os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
import main as m  # noqa: E402
from fastapi import HTTPException  # noqa: E402

MB = 1024 * 1024
A, B = "dev-a", "dev-b"
m.onboard(m.Onboarding(display_name="alice", username="alice", age=20), A)
m.onboard(m.Onboarding(display_name="bob", username="bobby", age=20), B)
m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "c", "k", "s"
SIZES = {}
m.asset_bytes = lambda pid: SIZES.get(pid, 0)


def err(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k)
    except HTTPException as e:
        return e
    return None


def post(uid, n, mb):
    pid = f"users/{uid}/post_{n}"
    SIZES[pid] = mb * MB
    m._rate.clear()
    return m.create_post(m.NewPost(public_id=pid), uid)


# the plan numbers and ONE consistent conversion
assert m.FREE_MEDIA_MB == 500 and m.PRO_MEDIA_MB == 1536 and m.MB == MB and m.MEDIA_RESET_SECONDS == 24 * 3600
assert m.POST_MAX_BYTES == 10 * MB

# fresh user: nothing used, no window running yet, exact numbers come from the server
q = m.media_quota(A)
assert q["period"] == "day" and q["limit_mb"] == 500 and q["used_mb"] == 0 and q["remaining_mb"] == 500 and q["reset_at"] is None and q["pro"] is False

# uploads count against today's allowance; the window starts at the first upload
t0 = int(time.time())
post(A, 1, 8)
q = m.media_quota(A)
assert q["used_mb"] == 8 and q["remaining_mb"] == 492 and abs(q["reset_at"] - (t0 + 86400)) < 5 and 0 < q["reset_in_seconds"] <= 86400

# fill the day with a small allowance, then the next upload is refused with the machine-readable code + reset time
m.FREE_MEDIA_MB = 20
post(A, 2, 8)                                    # 16 of 20 MB
e = err(m.create_post, m.NewPost(public_id=(lambda p: (SIZES.__setitem__(p, 8 * MB), p)[1])(f"users/{A}/post_3")), A)
assert e.status_code == 413 and e.detail["code"] == "media_full" and e.detail["reset_in_seconds"] > 0, e
assert err(m.sign_upload, m.SignRequest(purpose="post", size_bytes=5 * MB), A).detail["code"] == "media_full"
assert err(m.sign_upload, m.SignRequest(purpose="post", size_bytes=4 * MB), A) is None      # still fits
assert err(m.validate_short, m.ShortMeta(duration_seconds=10, size_mb=5, mime="video/mp4"), A).detail["code"] == "media_full"

# a refused upload is not counted
assert m.media_quota(A)["used_mb"] == 16

# deleting a post does NOT give bytes back (it is an upload allowance, not storage)
pid = [p for p in m.feed("following", None, 20, A)["posts"]][0]["id"] if False else None
with closing(m.db()) as con:
    ids_ = [r[0] for r in con.execute("SELECT id FROM posts WHERE uid=?", (A,))]
m.delete_post(ids_[0], A)
q = m.media_quota(A)
assert q["used_mb"] == 16 and q["stored_mb"] == 8, q            # stored shrank, today's counter did not

# the other user is not affected
assert m.media_quota(B)["used_mb"] == 0

# window ends -> counter starts again at 0; the files that are still stored are NOT deleted
with closing(m.db()) as con:
    con.execute("UPDATE media_usage SET window_start=? WHERE uid=?", (int(time.time()) - 24 * 3600 - 5, A)); con.commit()
q = m.media_quota(A)
assert q["used_mb"] == 0 and q["reset_at"] is None and q["stored_mb"] == 8
post(A, 4, 8)
assert m.media_quota(A)["used_mb"] == 8 and m.media_quota(A)["stored_mb"] == 16

# Sociax Pro (verified) -> 1.5 GB per day at once
m.FREE_MEDIA_MB = 500
with closing(m.db()) as con:
    con.execute("INSERT INTO payments(tx_ref, uid, product, amount, currency, status, created_at, paid_at, pro_started_at, pro_expires_at, granted) "
                "VALUES ('t1',?, 'pro_1_month','15','USD','success',?,?,?,?,1)", (A, t0, t0, t0, t0 + 30 * 86400)); con.commit()
q = m.media_quota(A)
assert q["limit_mb"] == 1536 and q["pro"] is True and q["pro_expires_at"] == t0 + 30 * 86400 and q["remaining_mb"] == 1528

# deleting the account clears the counter too
m.delete_account(A)
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM media_usage WHERE uid=?", (A,)).fetchone()[0] == 0
print("test_media_daily: all checks passed")
