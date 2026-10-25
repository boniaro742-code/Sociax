#!/usr/bin/env python3
"""Check a RUNNING Sociax Go engine (local or deployed), plan v3.5 section 26 / v3.9 section 30:
"verify ... deployment, and monitoring before migrating more work".

    ENGINE_TOKEN=<the secret> python tools/check_engine_deploy.py https://sociax-engine.onrender.com
    ENGINE_TOKEN=<the secret> python tools/check_engine_deploy.py http://localhost:8080 --rounds 50
    python tools/check_engine_deploy.py URL --backend https://your-python-backend.onrender.com   # also reads /health/config
    python tools/check_engine_deploy.py --self-test        # no network: runs the checker against a small fake (CI uses this)

The secret is read ONLY from the ENGINE_TOKEN environment variable (never from the command line, so it does not land in
your shell history) and it is never printed. Standard library only.

What it checks (each line prints PASS or FAIL):
  1. /healthz and /readyz answer without a secret and say nothing else.
  2. /v1/rank and /v1/stats refuse a missing and a wrong secret (401).
  3. With the secret, one saved candidate list (from backend-go/internal/ranker/testdata/parity.json) comes back in EXACTLY
     the order the Python ranker gave it. This is the same proof the Go tests give, but against the deployed service.
  4. Bad input is refused (broken JSON, a repeated id, a negative limit) instead of crashing the service.
  5. /v1/stats shows counts and times only: no creator label, no id.
  6. Timing: the FIRST call (a free Render service may be asleep: this is the cold start) and then --rounds calls;
     prints min / median / p95 / max in milliseconds. This is a baseline from where YOU run it, not a promise.
Exit code 0 = everything passed, 1 = something failed, 2 = could not start (missing token / address).
"""
import argparse
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PARITY = os.path.join(HERE, "..", "backend-go", "internal", "ranker", "testdata", "parity.json")


def call(base, method, path, body=None, token=None, raw=None, timeout=70):
    """Returns (status, parsed_json_or_None, seconds). Never raises on an HTTP error status."""
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token is not None:
        req.add_header("X-Engine-Token", token)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, text = r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode("utf-8", "replace")
    dt = time.perf_counter() - t0
    try:
        return status, json.loads(text), dt
    except ValueError:
        return status, None, dt


def pick_case():
    with open(PARITY, encoding="utf-8") as f:
        cases = json.load(f)["cases"]
    for c in cases:
        if c["kind"] == "random" and 20 <= len(c["items"]) <= 60:
            return c
    raise SystemExit("no suitable case in parity.json")


class Report:
    def __init__(self):
        self.failed = 0

    def check(self, ok, text, extra=""):
        print(("PASS  " if ok else "FAIL  ") + text + (("  (" + extra + ")") if extra and not ok else ""))
        if not ok:
            self.failed += 1


def run_checks(base, token, rounds, backend=None):
    rep = Report()
    base = base.rstrip("/")
    case = pick_case()
    payload = {"weights": case["weights"], "items": case["items"]}

    # the FIRST request is the cold start (a sleeping free service can need a minute)
    s, j, dt = call(base, "GET", "/healthz")
    print("first call (cold start if the service was asleep): %.0f ms" % (dt * 1000))
    rep.check(s == 200 and isinstance(j, dict) and j.get("ok") is True and j.get("service") == "sociax-engine",
              "/healthz answers ok", "status %s" % s)
    s, j, _ = call(base, "GET", "/readyz")
    rep.check(s == 200 and isinstance(j, dict) and j.get("ready") is True, "/readyz answers ready", "status %s" % s)
    rep.check(isinstance(j, dict) and set(j) <= {"ready", "ranker"}, "/readyz shows no extra data")

    for who, tok in (("no secret", None), ("a wrong secret", "wrong-secret-" + "x" * 20)):
        s, _, _ = call(base, "POST", "/v1/rank", payload, token=tok)
        rep.check(s == 401, "/v1/rank refuses " + who, "status %s" % s)
        s, _, _ = call(base, "GET", "/v1/stats", token=tok)
        rep.check(s == 401, "/v1/stats refuses " + who, "status %s" % s)

    s, j, _ = call(base, "POST", "/v1/rank", payload, token=token)
    ok = s == 200 and isinstance(j, dict) and j.get("ids") == case["expected"]
    rep.check(ok, "ranking %d saved candidates gives exactly the Python order" % len(case["items"]),
              "status %s, %s" % (s, "other order" if s == 200 else "no answer"))

    s, _, _ = call(base, "POST", "/v1/rank", token=token, raw=b"{not json")
    rep.check(s == 400, "broken JSON is refused with 400", "status %s" % s)
    dup = {"items": [dict(case["items"][0]), dict(case["items"][0])]}
    s, _, _ = call(base, "POST", "/v1/rank", dup, token=token)
    rep.check(s == 400, "a repeated id is refused with 400", "status %s" % s)
    s, _, _ = call(base, "POST", "/v1/rank", {"items": [], "limit": -1}, token=token)
    rep.check(s == 400, "a negative limit is refused with 400", "status %s" % s)
    s, j, _ = call(base, "POST", "/v1/rank", payload, token=token)
    rep.check(s == 200 and j.get("ids") == case["expected"], "the service still answers after bad input", "status %s" % s)

    s, j, _ = call(base, "GET", "/v1/stats", token=token)
    text = json.dumps(j)
    leaked = any(('"%s"' % it["creator"]) in text or str(it["id"]) in text for it in case["items"][:10])
    rep.check(s == 200 and isinstance(j, dict) and "requests" in j and not leaked,
              "/v1/stats shows counts and times only (no creator, no id)", "status %s" % s)

    times = []
    for _ in range(max(rounds, 1)):
        s, j, dt = call(base, "POST", "/v1/rank", payload, token=token)
        if s == 200:
            times.append(dt * 1000)
    rep.check(len(times) == max(rounds, 1), "%d of %d timing calls answered 200" % (len(times), max(rounds, 1)))
    if times:
        times.sort()
        p95 = times[min(len(times) - 1, int(0.95 * len(times)))]
        print("timing over the network from here, %d candidates: min %.1f ms, median %.1f ms, p95 %.1f ms, max %.1f ms"
              % (len(case["items"]), times[0], statistics.median(times), p95, times[-1]))
        print("(compare with Python ranking the same list: `python tools/bench_rank.py`; the plan says do not claim a speed-up "
              "without a measurement like this)")

    if backend:
        s, j, _ = call(backend.rstrip("/"), "GET", "/health/config")
        re_ = (j or {}).get("rank_engine") if isinstance(j, dict) else None
        rep.check(s == 200 and isinstance(re_, dict) and re_.get("configured") is True,
                  "the Python backend has RANK_ENGINE_URL set (rank_engine.configured)", "status %s, %s" % (s, re_))
        if isinstance(re_, dict):
            print("python backend rank_engine counters:", {k: re_.get(k) for k in ("ok", "failed", "skipped", "python", "paused")})
    return rep.failed


# ---------------------------------------------------------------------------------------------------------------------
# --self-test: a small FAKE of the engine's contract, plus three broken fakes, so we know this checker really fails when
# it should. (The real Go service is tested by `go test ./...`; this only tests the checker.)
# ---------------------------------------------------------------------------------------------------------------------
def make_fake(mode, secret):
    cases = {}
    with open(PARITY, encoding="utf-8") as f:
        for c in json.load(f)["cases"]:
            cases[tuple(sorted(i["id"] for i in c["items"]))] = c["expected"]
    counters = {"n": 0}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, obj):
            b = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)

        def authed(self):
            return mode == "noauth" or self.headers.get("X-Engine-Token") == secret

        def do_GET(self):
            counters["n"] += 1
            if self.path == "/healthz":
                return self.send(200, {"ok": True, "service": "sociax-engine", "ranker": "v1"})
            if self.path == "/readyz":
                return self.send(200, {"ready": True, "ranker": "v1"})
            if self.path == "/v1/stats":
                if not self.authed():
                    return self.send(401, {"error": "unauthorized"})
                extra = {"top_creator": "c0"} if mode == "leak" else {}
                return self.send(200, dict({"requests": counters["n"], "rank_us": {"p50": 1}}, **extra))
            self.send(404, {"error": "not_found"})

        def do_POST(self):
            counters["n"] += 1
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n)
            if self.path != "/v1/rank":
                return self.send(404, {"error": "not_found"})
            if not self.authed():
                return self.send(401, {"error": "unauthorized"})
            try:
                body = json.loads(raw)
            except ValueError:
                return self.send(400, {"error": "bad_json"})
            items = body.get("items", [])
            ids = [i["id"] for i in items]
            if len(set(ids)) != len(ids):
                return self.send(400, {"error": "duplicate_id"})
            if body.get("limit", 0) < 0:
                return self.send(400, {"error": "bad_limit"})
            out = list(cases.get(tuple(sorted(ids)), ids))
            if mode == "reversed":
                out.reverse()
            self.send(200, {"ids": out, "engine": "go", "ranker": "v1", "took_us": 5})

    return H


def self_test():
    secret = "self-test-secret-0123456789abcdef"
    results = []
    for mode, should_pass in (("good", True), ("reversed", False), ("noauth", False), ("leak", False)):
        srv = HTTPServer(("127.0.0.1", 0), make_fake(mode, secret))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        print("--- fake engine, mode: %s (checker should %s) ---" % (mode, "PASS" if should_pass else "FAIL"))
        failed = run_checks("http://127.0.0.1:%d" % srv.server_address[1], secret, rounds=5)
        srv.shutdown()
        results.append((mode, (failed == 0) == should_pass))
    print("---")
    bad = [m for m, ok in results if not ok]
    for m, ok in results:
        print(("PASS  " if ok else "FAIL  ") + "checker verdict is right for the '%s' fake" % m)
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("url", nargs="?", help="address of the Go engine, no slash at the end")
    ap.add_argument("--rounds", type=int, default=30, help="timing calls (default 30)")
    ap.add_argument("--backend", help="address of the Python backend, to read /health/config")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    token = os.environ.get("ENGINE_TOKEN", "")
    if not a.url or len(token) < 24:
        print("Give the engine address and set ENGINE_TOKEN (24+ characters) in the environment. See --help.")
        return 2
    return 1 if run_checks(a.url, token, a.rounds, a.backend) else 0


if __name__ == "__main__":
    sys.exit(main())
