"""Step 55: the AI Create routes in main.py (Pro only, not available until a provider is chosen, Yes = use once, No = discard).
Calls the route functions directly (no web server) with Cloudinary replaced by a stub.  Run: DEV_AUTH=1 python test_ai_create_api.py"""
import os
import re
import tempfile

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
for k in ("RENDER", "SOCIAX_BACKUP", "GEN_PROVIDER"):
    os.environ.pop(k, None)
from fastapi import HTTPException  # noqa: E402
import main as m  # noqa: E402
import ai_create  # noqa: E402

assert re.fullmatch(r"\d+\.\d+\.\d+", m.API_VERSION), m.API_VERSION   # not pinned to one release
UID = "dev-user-1"
stored = []


def fake_store(uid, kind, data, mime):
    pid = f"users/{uid}/{'short' if kind == 'video' else 'post'}_gen_{len(stored)}"
    stored.append(pid)
    return pid


def err(fn, *a, **k):
    try:
        fn(*a, **k)
    except HTTPException as e:
        return e
    raise AssertionError("expected an HTTPException")


m._store_generated = fake_store
m.rate_limit = lambda *a, **k: None          # the limiter is tested elsewhere; this test makes many calls
m.is_pro = lambda uid: False

# 1. not Pro -> 403 pro_required (nothing is generated, no slot taken)
e = err(m.ai_create_make, m.GenCreate(kind="image", prompt="a cat"), UID)
assert e.status_code == 403 and e.detail["code"] == "pro_required"

# 2. Pro but no provider chosen (the default) -> honest 503, status says nothing is available
m.is_pro = lambda uid: True
assert m._gen_provider is None
st = m.ai_create_status(UID)
assert st["pro"] is True and st["available"] == {"image": False, "video": False} and st["quota"]["image"]["remaining"] == 4
e = err(m.ai_create_make, m.GenCreate(kind="image", prompt="a cat"), UID)
assert e.status_code == 503 and e.detail["code"] == "generation_unavailable" and e.detail["retryable"] is True
assert m.ai_create_status(UID)["quota"]["image"]["used"] == 0

# 3. with a provider: a DRAFT comes back, nothing is posted, the quota shows it
m._gen_provider = ai_create.FakeProvider()
out = m.ai_create_make(m.GenCreate(kind="image", prompt="a cat"), UID)
d = out["draft"]
assert d["kind"] == "image" and out["quota"]["image"]["remaining"] == 3 and out["quota"]["video"]["remaining"] == 3
assert m.ai_create_status(UID)["available"] == {"image": True, "video": True}

# 4. group is not possible yet (group chats have no media): honest answer, the draft stays unused
e = err(m.ai_create_use, d["id"], m.GenUse(destination="group"), UID)
assert e.status_code == 422 and e.detail["code"] == "destination_unavailable"

# 5. Yes with a failing action: the draft goes back so the person can try again; then Yes works ONCE
calls = []


def failing_post(body, uid):
    calls.append("fail")
    raise HTTPException(413, "Daily media limit reached")


def ok_post(body, uid):
    calls.append("ok")
    return {"id": 7, "public_id": body.public_id}


m.create_post = failing_post
e = err(m.ai_create_use, d["id"], m.GenUse(destination="post", caption="hi"), UID)
assert e.status_code == 413
m.create_post = ok_post
r = m.ai_create_use(d["id"], m.GenUse(destination="post", caption="hi"), UID)
assert r["ok"] and r["result"]["public_id"].startswith("users/dev-user-1/post_gen_")
assert calls == ["fail", "ok"]
e = err(m.ai_create_use, d["id"], m.GenUse(destination="post"), UID)
assert e.status_code == 409 and e.detail["code"] == "draft_not_usable"          # a second Yes on the same draft is refused

# 6. only an image can be a profile photo; an image can
v = m.ai_create_make(m.GenCreate(kind="video", prompt="waves"), UID)["draft"]
e = err(m.ai_create_use, v["id"], m.GenUse(destination="profile"), UID)
assert e.status_code == 422 and e.detail["code"] == "bad_request"
m.confirm_avatar = lambda body, uid: {"avatar": body.public_id}
img = m.ai_create_make(m.GenCreate(kind="image", prompt="logo"), UID)["draft"]
assert m.ai_create_use(img["id"], m.GenUse(destination="profile"), UID)["ok"]

# 7. No = discard; someone else's draft is "not found"
n = m.ai_create_make(m.GenCreate(kind="image", prompt="x"), UID)["draft"]
assert err(m.ai_create_discard, n["id"], "someone-else").status_code == 404
assert m.ai_create_discard(n["id"], UID)["ok"]
assert err(m.ai_create_use, n["id"], m.GenUse(destination="post"), UID).status_code == 409

# 8. the limit: 4 images, then 429 with the time left; a failed generation costs nothing
m._gen_provider = ai_create.FakeProvider(fail=True)
before = m.ai_create_status(UID)["quota"]["image"]["used"]
assert err(m.ai_create_make, m.GenCreate(kind="image", prompt="x"), UID).status_code == 502
assert m.ai_create_status(UID)["quota"]["image"]["used"] == before
m._gen_provider = ai_create.FakeProvider()
while m.ai_create_status(UID)["quota"]["image"]["remaining"] > 0:
    m.ai_create_make(m.GenCreate(kind="image", prompt="more"), UID)
e = err(m.ai_create_make, m.GenCreate(kind="image", prompt="one too many"), UID)
assert e.status_code == 429 and e.detail["code"] == "gen_limit" and e.detail["reset_in_seconds"] > 0

# 9. the prompt is not stored anywhere in the database
import sqlite3  # noqa: E402
con = sqlite3.connect(os.environ["SOCIAX_DB"])
for (name,) in con.execute("SELECT name FROM sqlite_master WHERE type='table'").fetchall():
    cols = [c[1] for c in con.execute(f"PRAGMA table_info({name})")]
    if name.startswith("ai_gen"):
        assert not any("prompt" in c for c in cols), name
print("test_ai_create_api: all checks passed")
