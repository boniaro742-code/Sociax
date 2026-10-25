"""Step 48: the optional Go ranking engine. Part 1 tests the Python client alone (fake transport, no network, no Go needed):
what is sent, what is accepted, the fallback and the circuit breaker. Part 2 starts the REAL Go service when a Go toolchain is
installed and checks that it orders exactly like ranker.py, and that Python survives when the service is wrong, stopped or has
another secret (skipped with a message when `go` is not installed).
Run:  cd backend && python test_rank_engine.py        (no fastapi, no database, no internet needed)"""
import json, os, pathlib, random, shutil, socket, subprocess, sys, tempfile, time

import ranker as R
import rank_engine as E

W = R.DEFAULT_WEIGHTS
TOKEN = "t" * 40
FAILED = 0


def ok(msg):
    print("PASS", msg)


def check(name, cond):
    global FAILED
    if cond:
        ok(name)
    else:
        FAILED += 1
        print("FAIL", name)


def make_items(rng, n, creators=6):
    return [R.Item(id=i, creator=f"uid-secret-{rng.randrange(creators)}", age_seconds=rng.uniform(0, 86400 * 20),
                   interest_points=rng.choice([0.0, 0.0, 4.0, 12.0]), watch_avg=rng.choice([None, 0.3, 0.9]),
                   watch_viewers=rng.choice([0, 3, 8]), engage_points=float(rng.randint(0, 70)), followed=rng.random() < 0.3,
                   seen_age_seconds=rng.choice([None, 3600.0, 5 * 86400.0]), skipped=rng.random() < 0.1, reporters=rng.choice([0, 0, 2]))
            for i in rng.sample(range(1, 5000), n)]


class Fake:
    """A fake transport: records what was sent and answers with `reply(sent_json)`."""
    def __init__(self, reply=None, status=200, boom=None):
        self.reply, self.status, self.boom, self.calls = reply, status, boom, []

    def __call__(self, url, headers, body, timeout_s):
        self.calls.append((url, headers, json.loads(body), timeout_s))
        if self.boom:
            raise self.boom
        sent = json.loads(body)
        out = self.reply(sent) if self.reply else {"ids": [i["id"] for i in sent["items"]]}
        return self.status, (out if isinstance(out, bytes) else json.dumps(out).encode())


def client(transport, **kw):
    t = kw.pop("clock", None)
    return E.EngineClient(url="https://engine.example", token=TOKEN, timeout_ms=800, transport=transport, **({"clock": t} if t else {}))


rng = random.Random(48)
items = make_items(rng, 40)
python_order = R.order(items, W)

# ---------------------------------------------------------------- PART 1
# off by default: no URL = Python only, and nothing is ever sent
off = E.EngineClient(url="", token="", transport=Fake())
check("engine off by default: Python ranks, nothing sent", off.order(items, W) == python_order and not off.transport.calls and not off.configured)
check("a short secret or a URL without http(s) counts as NOT configured",
      not E.EngineClient(url="https://x.y", token="short").configured and not E.EngineClient(url="engine.example", token=TOKEN).configured)

# what is sent: anonymous creators, numbers, weights, the secret in a header, the right address
f = Fake()
c = client(f)
c.order(items, W, limit=10)
url, headers, sent, timeout = f.calls[0]
check("sent to /v1/rank with the secret in X-Engine-Token and a short timeout", url == "https://engine.example/v1/rank" and headers["X-Engine-Token"] == TOKEN and timeout == 0.8)
text = json.dumps(sent)
check("creators are anonymous labels (no user id leaves the Python backend)", "uid-secret" not in text and {i["creator"] for i in sent["items"]} <= {f"c{k}" for k in range(6)})
check("same creator = same label, different creator = different label",
      len({i["creator"] for i in sent["items"]}) == len({it.creator for it in items}) and
      all((a.creator == b.creator) == (x["creator"] == y["creator"]) for a, x in zip(items, sent["items"]) for b, y in zip(items[:5], sent["items"][:5])))
check("weights and limit are sent; the secret is not in the body", sent["weights"] == W and sent["limit"] == 10 and TOKEN not in text)
check("every candidate is sent exactly once", sorted(i["id"] for i in sent["items"]) == sorted(it.id for it in items))

# accepted answers
check("an engine answer that is a reordering is used", client(Fake(lambda s: {"ids": list(reversed([i["id"] for i in s["items"]]))})).order(items, W) == list(reversed([it.id for it in items])))
c = client(Fake(lambda s: {"ids": [i["id"] for i in s["items"]][:7]}))
check("with a limit, exactly `limit` ids are accepted", c.order(items, W, limit=7) == [it.id for it in items][:7] and c.stats["ok"] == 1)

# refused answers: each one falls back to Python (same result as ranker.py) and counts as a failure
ids = [it.id for it in items]
BAD = {
    "not JSON": b"<html>502</html>", "no ids key": {"ranked": ids}, "ids is not a list": {"ids": "1,2,3"},
    "missing one id": {"ids": ids[:-1]}, "an id that was never sent": {"ids": ids[:-1] + [999999]},
    "a duplicate id": {"ids": ids[:-1] + [ids[0]]}, "ids as text": {"ids": [str(i) for i in ids]},
    "booleans as ids": {"ids": [True] * len(ids)}, "extra ids": {"ids": ids + [ids[0]]}, "null": None,
}
for name, answer in BAD.items():
    c = client(Fake(lambda s, a=answer: a))
    got = c.order(items, W)
    check(f"bad answer ({name}): Python order is used", got == python_order and c.stats["failed"] == 1 and c.stats["python"] == 1)
c = client(Fake(lambda s: {"ids": ids}))
check("too many ids for the limit is refused", c.order(items, W, limit=5) == python_order[:5] and c.stats["failed"] == 1)
for status in (401, 404, 500, 503):
    c = client(Fake(status=status))
    check(f"HTTP {status}: Python order is used", c.order(items, W) == python_order and c.stats["failed"] == 1)
for exc in (TimeoutError("slow"), ConnectionRefusedError("https://engine.example refused"), OSError("dns")):
    c = client(Fake(boom=exc))
    check(f"{type(exc).__name__}: Python order is used", c.order(items, W) == python_order and c.stats["failed"] == 1)

# the log never contains the address or the secret
import logging, io
buf = io.StringIO()
h = logging.StreamHandler(buf)
logging.getLogger("sociax.rank_engine").addHandler(h)
client(Fake(boom=ConnectionRefusedError("https://engine.example/v1/rank?token=" + TOKEN))).order(items, W)
client(Fake(status=500)).order(items, W)
logging.getLogger("sociax.rank_engine").removeHandler(h)
check("warnings name the problem only: no address, no secret, no id", "engine.example" not in buf.getvalue() and TOKEN not in buf.getvalue() and "uid-secret" not in buf.getvalue() and "failed" in buf.getvalue())

# circuit breaker: 3 failures in a row pause the engine for 30 s; then it is tried again; one success resets the count
now = [1000.0]
f = Fake(status=500)
c = client(f, clock=lambda: now[0])
for _ in range(3):
    c.order(items, W)
check("3 failures in a row: the engine is paused", len(f.calls) == 3 and c.report()["paused"])
for _ in range(10):
    c.order(items, W)
check("while paused no call is made and Python answers every request", len(f.calls) == 3 and c.stats["skipped"] == 10 and c.stats["python"] == 13)
now[0] += E.PAUSE_SECONDS + 1
f.status = 200
check("after 30 seconds it is tried again and works", c.order(items, W) == ids and len(f.calls) == 4 and not c.report()["paused"])
f.status = 500
c.order(items, W); c.order(items, W)
f.status = 200
c.order(items, W)
f.status = 500
c.order(items, W); c.order(items, W)
check("a success in between resets the count (2 + 2 failures do not pause)", not c.report()["paused"])
check("empty candidate list: nothing sent, empty answer", (lambda g: (g.order([], W) == [] and not g.transport.calls))(client(Fake())))
rep = client(Fake()).report()
check("report() shows booleans and counters only", set(rep) == {"configured", "paused", "ok", "failed", "skipped", "python"} and all(isinstance(v, (bool, int)) for v in rep.values()))

# ---------------------------------------------------------------- PART 2: the real Go service
go = shutil.which("go")
go_dir = pathlib.Path(__file__).resolve().parent.parent / "backend-go"
if not go or not go_dir.is_dir():
    print("SKIP part 2: no Go toolchain here (GitHub runs it with Go installed)")
else:
    work = tempfile.mkdtemp()
    exe = os.path.join(work, "sociax-engine")
    b = subprocess.run([go, "build", "-o", exe, "./cmd/sociax-engine"], cwd=go_dir, capture_output=True, text=True, timeout=300)
    check("the Go service builds", b.returncode == 0)
    if b.returncode != 0:
        print(b.stderr)
    else:
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        env = {**os.environ, "PORT": str(port), "ENGINE_TOKEN": TOKEN, "LOG_LEVEL": "warn"}
        proc = subprocess.Popen([exe], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            import urllib.request
            for _ in range(100):
                try:
                    urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=0.5).read()
                    break
                except OSError:
                    time.sleep(0.05)
            real = E.EngineClient(url=f"http://127.0.0.1:{port}", token=TOKEN, timeout_ms=3000)
            same = 0
            for k in range(60):
                its = make_items(random.Random(k), random.Random(k + 1000).choice([1, 2, 5, 12, 40, 120, 300]), creators=random.Random(k).choice([1, 3, 20]))
                if real.order(its, W) == R.order(its, W):
                    same += 1
            check("REAL service: 60 random candidate lists are ordered exactly like ranker.py", same == 60 and real.stats["ok"] == 60 and real.stats["python"] == 0)
            custom = R.parse_weights("interest=0.0,watch=0.0,fresh=1.0,engage=0.0,follow=0.0,diversity=0.0")
            its = make_items(random.Random(5), 50)
            check("REAL service: the weights sent by Python are the ones used", real.order(its, custom) == R.order(its, custom))
            check("REAL service: limit returns the best N", real.order(its, W, limit=9) == R.order(its, W)[:9])
            wrong = E.EngineClient(url=f"http://127.0.0.1:{port}", token="w" * 40, timeout_ms=3000)
            check("REAL service: another secret = refused (401), Python still answers correctly", wrong.order(its, W) == R.order(its, W) and wrong.stats["failed"] == 1)
            st = json.loads(urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{port}/v1/stats", headers={"X-Engine-Token": TOKEN}), timeout=2).read())
            check("REAL service: /v1/stats counts the work and has no content", st["rank_samples"] >= 60 and "ids" not in json.dumps(st) and st["errors_4xx"] >= 1)
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
        check("REAL service: stops cleanly on SIGTERM (exit code 0)", proc.returncode == 0)
        down = E.EngineClient(url=f"http://127.0.0.1:{port}", token=TOKEN, timeout_ms=500)
        its = make_items(random.Random(9), 30)
        t0 = time.time()
        got = [down.order(its, W) for _ in range(6)]
        check("REAL service stopped: every request still gets the Python order", all(g == R.order(its, W) for g in got))
        check("REAL service stopped: after 3 failures it is skipped (no waiting)", down.stats["failed"] == 3 and down.stats["skipped"] == 3 and time.time() - t0 < 3)
        # a start-up without a secret must refuse to run
        bad = subprocess.run([exe], env={k: v for k, v in env.items() if k != "ENGINE_TOKEN"}, capture_output=True, text=True, timeout=10)
        check("Go service refuses to start without ENGINE_TOKEN (exit code 2, no value printed)", bad.returncode == 2 and "ENGINE_TOKEN" in bad.stderr)
    shutil.rmtree(work, ignore_errors=True)

print("FAILED" if FAILED else "ALL PASSED", FAILED or "")
sys.exit(1 if FAILED else 0)
