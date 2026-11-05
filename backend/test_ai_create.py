"""Step 55 (plan v3.2 / v3.9 AI Create): quotas, drafts and the Yes / No rule. Standard library only.  Run: python test_ai_create.py"""
import sqlite3
import tempfile
import os

import ai_create as ac

con = sqlite3.connect(os.path.join(tempfile.mkdtemp(), "t.db"))
con.row_factory = sqlite3.Row
ac.ensure_tables(con)
saved = []


def store(kind, data, mime):
    pid = f"users/u1/{'short' if kind == 'video' else 'post'}_gen_{len(saved)}"
    saved.append(pid)
    return pid


P = ac.FakeProvider()
T0 = 1_000_000

# no provider chosen yet -> honest "not available", and no slot is taken
try:
    ac.generate(con, "u1", "image", "a cat", None, store, now=T0)
    raise AssertionError("no provider must not generate")
except ac.Unavailable as e:
    assert e.code == "generation_unavailable" and e.status == 503
assert ac.quota_state(con, "u1", T0)["image"]["used"] == 0
assert ac.provider_from_env({}) is None and ac.provider_from_env({"GEN_PROVIDER": "grok"}) is None
assert isinstance(ac.provider_from_env({"GEN_PROVIDER": "fake"}), ac.FakeProvider)

# bad input
for kind, prompt in (("audio", "x"), ("image", "  "), ("image", "x" * 1001)):
    try:
        ac.generate(con, "u1", kind, prompt, P, store, now=T0)
        raise AssertionError("bad input accepted")
    except ac.BadRequest:
        pass
assert P.calls == 0

# 4 images per window, the 5th is refused with the time left; videos are counted separately (3)
for i in range(4):
    d = ac.generate(con, "u1", "image", "a red fox", P, store, now=T0 + i)
    assert d["status"] == "draft" and d["public_id"].startswith("users/u1/post_gen_")
try:
    ac.generate(con, "u1", "image", "one more", P, store, now=T0 + 10)
    raise AssertionError("5th image allowed")
except ac.LimitReached as e:
    assert e.code == "gen_limit" and e.status == 429 and e.reset_in == T0 + ac.WINDOW_SECONDS - (T0 + 10)
q = ac.quota_state(con, "u1", T0 + 10)
assert q["image"]["remaining"] == 0 and q["video"]["remaining"] == 3 and q["image"]["reset_in_seconds"] > 0
for i in range(3):
    v = ac.generate(con, "u1", "video", "waves", P, store, now=T0 + 20 + i)
    assert v["public_id"].startswith("users/u1/short_gen_")
try:
    ac.generate(con, "u1", "video", "again", P, store, now=T0 + 30)
    raise AssertionError("4th video allowed")
except ac.LimitReached:
    pass
# another person has their own allowance
assert ac.generate(con, "u2", "image", "hi", P, store, now=T0 + 30)["uid"] == "u2"
# the window ends 24 h after the first one: the allowance is back
assert ac.quota_state(con, "u1", T0 + ac.WINDOW_SECONDS)["image"]["remaining"] == 4
assert ac.generate(con, "u1", "image", "new day", P, store, now=T0 + ac.WINDOW_SECONDS)["status"] == "draft"

# a FAILED generation (provider error, rejected result, upload error) gives the slot back
bad = ac.FakeProvider(fail=True)
before = ac.quota_state(con, "u3", T0)["image"]["used"]
try:
    ac.generate(con, "u3", "image", "x", bad, store, now=T0)
    raise AssertionError
except ac.ProviderFailed:
    pass
assert ac.quota_state(con, "u3", T0)["image"]["used"] == before == 0


def boom(kind, data, mime):
    raise RuntimeError("cloudinary down with secret details")


try:
    ac.generate(con, "u3", "image", "x", P, boom, now=T0)
    raise AssertionError
except ac.ProviderFailed as e:
    assert "secret" not in str(e)
assert ac.quota_state(con, "u3", T0)["image"]["used"] == 0

# the Yes / No rule: a draft is used at most once; No discards; nobody else can touch it
d = ac.generate(con, "u4", "image", "logo", P, store, now=T0)
try:
    ac.get_draft(con, "intruder", d["id"])
    raise AssertionError("someone else's draft was visible")
except ac.NotFound:
    pass
assert ac.is_pending_draft(con, "u4", d["public_id"], "image") and not ac.is_pending_draft(con, "intruder", d["public_id"], "image")
assert not ac.is_pending_draft(con, "u4", d["public_id"], "video")
c = ac.claim(con, "u4", d["id"])
assert c["status"] == "using"
for fn in (ac.claim, ac.discard):
    try:
        fn(con, "u4", d["id"])
        raise AssertionError("a draft in use was claimed / discarded again")
    except ac.Conflict:
        pass
ac.release(con, "u4", d["id"])                      # the action failed: try again is possible
assert ac.get_draft(con, "u4", d["id"])["status"] == "draft"
ac.claim(con, "u4", d["id"])
ac.complete(con, "u4", d["id"])
assert ac.get_draft(con, "u4", d["id"])["status"] == "used" and not ac.is_pending_draft(con, "u4", d["public_id"], "image")
try:
    ac.claim(con, "u4", d["id"])
    raise AssertionError("a used draft was used twice")
except ac.Conflict:
    pass
n = ac.generate(con, "u4", "image", "second", P, store, now=T0 + 5)
assert ac.discard(con, "u4", n["id"])["status"] == "discarded"
try:
    ac.claim(con, "u4", n["id"])
    raise AssertionError("a discarded draft was used")
except ac.Conflict:
    pass

# unused drafts expire after 24 h and their files are listed for deletion
e1 = ac.generate(con, "u5", "image", "old", P, store, now=T0)
e2 = ac.generate(con, "u5", "video", "new", P, store, now=T0 + ac.DRAFT_TTL_SECONDS)
gone = ac.expire_old(con, now=T0 + ac.DRAFT_TTL_SECONDS)
assert (e1["public_id"], "image") in gone and (e2["public_id"], "video") not in gone, gone     # every old unused draft of everybody expires
assert ac.get_draft(con, "u5", e1["id"])["status"] == "expired" and ac.get_draft(con, "u5", e2["id"])["status"] == "draft"

# the prompt is never stored
cols = [r[1] for r in con.execute("PRAGMA table_info(ai_gen_drafts)")]
assert "prompt" not in cols and not any("prompt" in c for c in cols)

# Step 65: Gemini image provider (no network: a fake HTTP client)
import base64  # noqa: E402


class FakeResp:
    def __init__(self, payload, status=200):
        self.payload, self.status_code = payload, status

    def raise_for_status(self):
        if self.status_code >= 400:
            err = Exception("boom")
            err.response = self
            raise err

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, resp):
        self.resp, self.sent = resp, []

    def post(self, url, headers=None, json=None, timeout=None):
        self.sent.append((url, headers, json))
        return self.resp


assert ac.provider_from_env({"GEN_PROVIDER": "gemini"}) is None                          # no key -> not available
g = ac.provider_from_env({"GEN_PROVIDER": "gemini", "GEMINI_API_KEY": "k123", "GEN_IMAGE_MODEL": "m-x"})
assert isinstance(g, ac.GeminiImageProvider) and g.kinds() == {"image"} and g.model == "m-x"
png = ac.FakeProvider.PNG
ok = FakeResp({"candidates": [{"content": {"parts": [{"text": "here"}, {"inlineData": {"mimeType": "image/png", "data": base64.b64encode(png).decode()}}]}}]})
cl = FakeClient(ok)
g.client = cl
res = g.generate("image", "a red fox")
assert res.data == png and res.mime == "image/png"
assert cl.sent[0][1] == {"x-goog-api-key": "k123"} and "m-x:generateContent" in cl.sent[0][0] and "k123" not in cl.sent[0][0]
for bad in (FakeResp({"candidates": []}), FakeResp({"promptFeedback": {"blockReason": "SAFETY"}}),
            FakeResp({"candidates": [{"content": {"parts": [{"text": "no picture"}]}}]}), FakeResp({}, status=429), FakeResp({}, status=500)):
    g.client = FakeClient(bad)
    try:
        g.generate("image", "x")
        raise AssertionError("a failed answer was accepted")
    except ac.ProviderFailed:
        pass
try:
    g.generate("video", "x")
    raise AssertionError("gemini must not claim video")
except ac.ProviderFailed:
    pass
# through generate(): a failed Gemini call gives the slot back; videos stay "not available"
g.client = FakeClient(FakeResp({"candidates": []}))
try:
    ac.generate(con, "u9", "image", "fox", g, store, now=T0)
    raise AssertionError("failed generation must raise")
except ac.ProviderFailed:
    pass
assert ac.quota_state(con, "u9", T0)["image"]["used"] == 0
try:
    ac.generate(con, "u9", "video", "waves", g, store, now=T0)
    raise AssertionError("video must be unavailable with gemini")
except ac.Unavailable:
    pass
g.client = cl
d9 = ac.generate(con, "u9", "image", "fox", g, store, now=T0)
assert d9["status"] == "draft" and ac.quota_state(con, "u9", T0)["image"]["used"] == 1

print("test_ai_create: all checks passed")
