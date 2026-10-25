"""Backend checks for Step 47: baseline measurements (plan v3.9 section 37). Run:  cd backend && DEV_AUTH=1 python test_metrics.py
Checked: percentiles, groups the plan names (feed, search, chat, upload, ai), error counting, bounded memory, only route PATTERNS
are kept (no user, id or query), the middleware records every answer (and a crash as 500) but not health checks, and the
metrics page answers only to the owner's secret (everything else looks like a page that does not exist)."""
import asyncio, os, sys, tempfile, types
os.environ["DEV_AUTH"] = "1"
os.environ["SOCIAX_DB"] = os.path.join(tempfile.mkdtemp(), "mt.db")
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tools"))
import metrics as M  # noqa: E402
import main as m  # noqa: E402
import read_metrics  # noqa: E402
from fastapi import HTTPException  # noqa: E402


def check(name, got, want):
    ok = got == want
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  (got {got!r}, wanted {want!r})"))
    assert ok, name


# ---------- the recorder ----------
check("percentile: empty list", M.percentile([], 95), None)
check("percentile: nearest rank on 1..100", (M.percentile(list(range(1, 101)), 50), M.percentile(list(range(1, 101)), 95), M.percentile(list(range(1, 101)), 99)), (50, 95, 99))
check("percentile: one value", M.percentile([7], 99), 7)
check("groups: feed / search / chat / upload / ai / other", [M.group_of(*x) for x in (
    ("GET", "/feed/for-you"), ("GET", "/shorts/for-you"), ("GET", "/shorts/feed"), ("GET", "/search"), ("GET", "/chats/{conv_id}/messages"),
    ("POST", "/chats/{conv_id}/messages"), ("POST", "/posts"), ("POST", "/shorts"), ("POST", "/media/sign-upload"), ("POST", "/ai/ask"),
    ("GET", "/users/me"), ("POST", "/posts/{post_id}/like"))],
    ["feed", "feed", "feed", "search", "chat", "chat", "upload", "upload", "upload", "ai", "other", "other"])

x = M.Metrics()
for ms in range(1, 101):
    x.record("GET", "/search", 200, ms)
x.record("GET", "/search", 404, 5); x.record("GET", "/search", 500, 900)
for _ in range(3):
    x.record("GET", "/feed/for-you", 200, 40)
snap = x.snapshot()
g = snap["groups"]
check("search group: 102 requests, one 5xx, p50 / p95", (g["search"]["count"], g["search"]["errors_5xx"], g["search"]["p50_ms"], g["search"]["p95_ms"]), (102, 1, 50, 96))   # sorted: 1,2,3,4,5,5,6..100,900 -> the 51st value is 50, the 97th is 96
check("5xx rate is computed per group and overall", (g["search"]["error_rate_5xx"], snap["requests"], snap["errors_5xx"]), (round(1 / 102, 4), 105, 1))
check("feed group", (g["feed"]["count"], g["feed"]["p50_ms"]), (3, 40))
check("an unused group has no numbers (not zeros that look like speed)", (g["chat"]["count"], g["chat"]["p95_ms"]), (0, None))
r0 = snap["routes"][0]
check("busiest route first, with 4xx and max", (r0["route"], r0["count"], r0["errors_4xx"], r0["errors_5xx"], r0["max_ms"]), ("GET /search", 102, 1, 1, 900))

# bounded memory
y = M.Metrics()
for i in range(M.MAX_ROUTES + 50):
    y.record("GET", f"/r{i}", 200, 1)
check("route table never grows past its bound (the rest is counted under 'other')", (len(y._routes) <= M.MAX_ROUTES + 1, y.snapshot(top=1000)["requests"]), (True, M.MAX_ROUTES + 50))
z = M.Metrics()
for i in range(M.WINDOW * 3):
    z.record("GET", "/a", 200, i)
check("only the newest WINDOW timings are kept per route", len(z._routes[("GET", "/a")].recent), M.WINDOW)
check("... while the count is the true total", z.snapshot()["routes"][0]["count"], M.WINDOW * 3)

# ---------- the middleware ----------
def fake_request(method, path):
    return types.SimpleNamespace(method=method, scope={"route": types.SimpleNamespace(path=path)})


async def ok_next(request):
    return types.SimpleNamespace(headers={}, status_code=200)


async def fail_next(request):
    return types.SimpleNamespace(headers={}, status_code=503)


async def crash_next(request):
    raise RuntimeError("boom with secret user data")

m._metrics.reset()
resp = asyncio.run(m._timing(fake_request("GET", "/chats/{conv_id}/messages"), ok_next))
asyncio.run(m._timing(fake_request("GET", "/chats/{conv_id}/messages"), fail_next))
asyncio.run(m._timing(fake_request("GET", "/health"), ok_next))
asyncio.run(m._timing(fake_request("GET", "/admin/metrics"), ok_next))
try:
    asyncio.run(m._timing(fake_request("POST", "/posts"), crash_next)); crashed = False
except RuntimeError:
    crashed = True
snap = m._metrics.snapshot()
check("the answer still carries X-Response-Time-ms", "X-Response-Time-ms" in resp.headers, True)
check("2 chat requests (one 503) and 1 crashed upload counted; health and the metrics page are not", (snap["groups"]["chat"]["count"], snap["groups"]["chat"]["errors_5xx"], snap["groups"]["upload"]["count"], snap["requests"]), (2, 1, 1, 3))
check("an exception still reaches the caller (the middleware does not hide it)", crashed, True)
check("only route patterns are stored", sorted(r["route"] for r in snap["routes"]), ["GET /chats/{conv_id}/messages", "POST /posts"])
check("the crash text is not stored anywhere", "secret" in str(snap), False)
asyncio.run(m._timing(types.SimpleNamespace(method="GET", scope={}), ok_next))
check("an unmatched address is counted as 'unmatched' (never the real path)", any(r["route"] == "GET unmatched" for r in m._metrics.snapshot()["routes"]), True)

# ---------- the owner-only page ----------
def page(token):
    m._rate.clear()
    try:
        return m.admin_metrics(token)
    except HTTPException as e:
        return e.status_code


os.environ.pop("METRICS_TOKEN", None)
check("no METRICS_TOKEN set: the page does not exist (404)", page("anything-at-all-123456"), 404)
os.environ["METRICS_TOKEN"] = "short"
check("a short secret is never accepted (404)", page("short"), 404)
SECRET = "owner-secret-0123456789"
os.environ["METRICS_TOKEN"] = SECRET
check("no header: 404", page(None), 404)
check("wrong token: the SAME 404", page("owner-secret-0123456780"), 404)
out = page(SECRET)
check("right token: the numbers", (out["version"], sorted(out["groups"]), out["push_backlog"]["waiting"]), (m.API_VERSION, sorted(M.GROUPS), 0))
check("the secret and any user data are not in the answer", (SECRET in str(out), "dev-" in str(out)), (False, False))
m._rate.clear()
codes = []
for _ in range(70):
    try:
        m.admin_metrics("wrong-wrong-wrong-wrong"); codes.append(200)
    except HTTPException as e:
        codes.append(e.status_code)
check("after 60 tries in a minute the guesses get 429", (codes[:60].count(404), codes[60:].count(429)), (60, 10))
m._rate.clear()

# ---------- the reader tool ----------
text = read_metrics.format_report(dict(out, requests=5, errors_5xx=1, error_rate_5xx=0.2))
check("the reader tool prints a table with the plan's groups", all(w in text for w in ("feed", "search", "chat", "upload", "ai", "ROUTE")), True)
check("... and never the token", SECRET in text, False)
print("test_metrics: all checks passed")
