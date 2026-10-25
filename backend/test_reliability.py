"""Step 36 (plan v3.6 sections 2, 3, 4): AI errors are safe and structured, failed AI answers cost nothing, the configuration
check never shows a secret, the sign-in callback never gives a 404, and a video whose length is not known yet is checked again.
Run:  cd backend && DEV_AUTH=1 python test_reliability.py     (no internet: every provider is a fake)"""
import json, logging, os, tempfile, time
from contextlib import closing

os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "t.db")
os.environ.pop("AI_PROVIDER_ORDER", None)
import main as m  # noqa: E402
import ai_providers as ap  # noqa: E402
from fastapi import HTTPException  # noqa: E402

A = "dev-a"
m.onboard(m.Onboarding(display_name="alice", username="alice", age=20), A)
ap.RETRY_WAIT_S = 0
SECRET = "sk-SUPER-SECRET-123"
PROMPT = "my private question about my bank PIN 4321"


def ok(msg):
    print("PASS", msg)


def err(fn, *a, **k):
    m._rate.clear()
    try:
        fn(*a, **k)
    except HTTPException as e:
        return e
    return None


class HttpErr(Exception):
    def __init__(self, status):
        self.response = type("R", (), {"status_code": status})()


class TimeoutException(Exception):
    pass


# ---- 1. failure categories (what the server log shows) --------------------------------------------------------------------
assert ap.reason_of(HttpErr(401)) == "http_401" and ap.reason_of(HttpErr(429)) == "http_429" and ap.reason_of(HttpErr(503)) == "http_503"
assert ap.reason_of(TimeoutException()) == "timeout" and ap.reason_of(ValueError("empty")) == "bad_format" and ap.reason_of(OSError("x")) == "network"
ok("failure categories: bad key, rate limit, server error, timeout, bad format, network")

# ---- 2. no provider configured: safe structured 503, nothing leaks, nothing is charged -------------------------------------
ap.XAI_KEY = ap.GEMINI_KEY = ap.ANTHROPIC_KEY = None
e = err(m.ai_ask, m.AiAsk(prompt="hello"), A)
assert e.status_code == 503 and e.detail["code"] == "ai_not_configured" and e.detail["retryable"] is False
assert "key" not in e.detail["message"].lower() and "XAI" not in json.dumps(e.detail)
ok("AI not configured -> safe structured 503")

# ---- 3. every provider fails: 502 ai_unavailable, the free allowance is refunded, the log has no prompt and no key ----------
ap.XAI_KEY, ap.GEMINI_KEY = SECRET, "gem-" + SECRET
logs = []


class Capture(logging.Handler):
    def emit(self, record):
        logs.append(record.getMessage())


logging.getLogger("sociax.ai").addHandler(Capture())
calls = []


def boom(status):
    def f(prompt):
        calls.append(prompt)
        raise HttpErr(status)
    return f


ap._ask_grok, ap._ask_gemini = boom(401), boom(429)
used_before = m.ai_quota(A)["used"]
e = err(m.ai_ask, m.AiAsk(prompt=PROMPT), A)
assert e.status_code == 502 and e.detail["code"] == "ai_unavailable" and e.detail["retryable"] is True
assert m.ai_quota(A)["used"] == used_before, "a failed answer must not use the allowance"
joined = " ".join(logs)
assert "grok" in joined and "http_401" in joined and "http_429" in joined
assert PROMPT not in joined and SECRET not in joined and "4321" not in joined and SECRET not in json.dumps(e.detail)
ok("all providers fail -> safe 502, allowance refunded, log shows provider + category only")

# ---- 4. only rate limits / timeouts -> the message says "busy"; a quick retry happens only for network / 5xx ---------------
ap._ask_grok, ap._ask_gemini = boom(429), boom(429)
e = err(m.ai_ask, m.AiAsk(prompt="hi"), A)
assert "busy" in e.detail["message"]
calls.clear()
ap._ask_grok, ap._ask_gemini = boom(401), boom(401)
err(m.ai_ask, m.AiAsk(prompt="hi"), A)
assert len(calls) == 2, "a bad key is never retried"
calls.clear()
ap._ask_grok, ap._ask_gemini = boom(503), boom(503)
err(m.ai_ask, m.AiAsk(prompt="hi"), A)
assert len(calls) == 4, "a 5xx gets exactly one quick retry per provider"
ok("busy message for rate limits; retries bounded (bad key none, 5xx once)")

# ---- 5. fallback works and a success is charged once ------------------------------------------------------------------------
ap._ask_grok, ap._ask_gemini = boom(500), (lambda p: "gemini answer")
m._rate.clear()
r = m.ai_ask(m.AiAsk(prompt="hi"), A)
assert r["answer"] == "gemini answer" and r["provider"] == "gemini"
ok("Grok fails -> Gemini answers")

# ---- 6. the total deadline stops a long chain of slow providers -------------------------------------------------------------
ap.TOTAL_DEADLINE_S = -1
ap._ask_grok = ap._ask_gemini = boom(500)
try:
    ap.ask("hi")
    raise AssertionError("should raise")
except ap.AiUnavailable as ex:
    assert set(ex.reasons.values()) == {"skipped_deadline"}
ap.TOTAL_DEADLINE_S = 50
ok("no new provider is started after the deadline")

# ---- 7. /health/config: booleans and NAMES only, never a value --------------------------------------------------------------
m.CLOUD_NAME, m.CLOUD_KEY, m.CLOUD_SECRET = "mycloud", "cloudkey-999", "cloudsecret-888"
os.environ["CHAPA_SECRET_KEY"] = "CHASECK_TEST-777"
rep = m.health_config()
text = json.dumps(rep)
for secret in (SECRET, "cloudkey-999", "cloudsecret-888", "CHASECK_TEST-777", "mycloud"):
    assert secret not in text, secret
assert rep["cloudinary"] is True and rep["ai"]["grok"] is True and rep["payments"] is True and rep["dev_auth_enabled"] is True
m.CLOUD_SECRET = None
rep = m.health_config()
assert rep["cloudinary"] is False and "CLOUDINARY_API_SECRET" in rep["missing"] and rep["ok"] is False
m.CLOUD_SECRET = "cloudsecret-888"
ap.XAI_KEY = ap.GEMINI_KEY = ap.ANTHROPIC_KEY = None
assert any("AI provider" in x for x in m.health_config()["missing"])
ok("configuration check lists missing setting NAMES and never a value")

# ---- 8. the sign-in callback never answers 404 and never echoes what it was given -----------------------------------------
good = m.auth_callback()
bad = m.auth_callback(error="<script>alert(1)</script>")
assert good.status_code == 200 and bad.status_code == 400
assert b"<script>" not in bad.body and b"alert" not in bad.body and b"Sociax" in good.body
ok("/auth/callback: success page, failure page, no echo, never 404")

# ---- 9. a video whose length is not known yet is asked for again (and a truly unreadable one is refused, not published) ------
m.kick_cloud_cleanup = lambda: None
m.destroy_video = lambda pid: None
m.DURATION_RETRY_WAIT = 0
answers = [{"bytes": 1000, "format": "mp4"}, {"bytes": 1000, "format": "mp4", "duration": 0}, {"bytes": 1000, "duration": 8.5, "format": "mp4"}]
asked = []


def late(pid):
    asked.append(pid)
    return answers[min(len(asked) - 1, 2)]


m.video_info = late
s = m.create_short(m.NewShort(public_id=f"users/{A}/short_late"), A)
assert s["duration"] == 8.5 and len(asked) == 3
asked.clear()
m.video_info = lambda pid: (asked.append(pid), {"bytes": 1000, "format": "mp4"})[1]
e = err(m.create_short, m.NewShort(public_id=f"users/{A}/short_never"), A)
assert e.status_code == 422 and len(asked) == 1 + m.DURATION_RETRIES
with closing(m.db()) as con:
    assert con.execute("SELECT COUNT(*) FROM shorts WHERE media_public_id LIKE '%short_never'").fetchone()[0] == 0
ok("unknown length: asked again; still unknown -> refused with 422, nothing published")
print("test_reliability: all checks passed")
