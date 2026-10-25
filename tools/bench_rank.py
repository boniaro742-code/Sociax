#!/usr/bin/env python3
"""Step 48: honest numbers for "is the Go ranking engine faster?" (plan v3.4 section 21 + v3.5 section 25: no claim without measurements).
Measures, on THIS computer, for 300 candidates (the real size of one For You request):
  1. ranker.py (Python)                          - what the server did before
  2. Go engine, server side only                 - the `took_us` the engine reports (ranking only)
  3. Go engine, as Python sees it                - build JSON + HTTP on localhost + read the answer (what a feed request would pay)
Run:  python tools/bench_rank.py            (needs `go`; builds the engine into a temporary folder)
It does NOT include the network between two real servers (Render -> Render): add that yourself with
`curl -w '%{time_total}' ...` against your deployed engine. A fast engine behind a slow link can still be slower than Python."""
import json, os, pathlib, random, shutil, socket, statistics, subprocess, sys, tempfile, time, urllib.request

root = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(root / "backend"))
import ranker as R          # noqa: E402
import rank_engine as E     # noqa: E402


def items(n, creators, seed):
    rng = random.Random(seed)
    return [R.Item(id=i + 1, creator=f"c{rng.randrange(creators)}", age_seconds=rng.uniform(0, 86400 * 30),
                   interest_points=float(rng.randrange(3) * 4), watch_avg=rng.random(), watch_viewers=rng.randrange(6),
                   engage_points=float(rng.randrange(60)), followed=rng.random() < 0.25, seen_age_seconds=rng.uniform(0, 86400 * 20),
                   skipped=rng.random() < 0.12, reporters=rng.randrange(3)) for i in range(n)]


def timeit(fn, runs):
    t = []
    for _ in range(runs):
        t0 = time.perf_counter(); fn(); t.append((time.perf_counter() - t0) * 1000)
    t.sort()
    return statistics.median(t), t[int(len(t) * 0.95) - 1], t[-1]


def row(label, res):
    print(f"  {label:<46} median {res[0]:8.3f} ms   p95 {res[1]:8.3f} ms   max {res[2]:8.3f} ms")


def main():
    go = shutil.which("go")
    W = R.DEFAULT_WEIGHTS
    print(f"Python {sys.version.split()[0]}  |  {os.cpu_count()} CPU  |  {time.strftime('%Y-%m-%d')}")
    for n, runs in ((100, 200), (300, 100), (2000, 5)):
        its = items(n, max(5, n // 12), n)
        print(f"\n{n} candidates:")
        row("Python ranker.py", timeit(lambda: R.order(its, W), runs))
        if go and n <= 300:
            pass
    if not go:
        print("\nNo Go toolchain here: Go numbers skipped.")
        return
    work = tempfile.mkdtemp()
    exe = os.path.join(work, "engine")
    subprocess.run([go, "build", "-o", exe, "./cmd/sociax-engine"], cwd=root / "backend-go", check=True)
    s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
    token = "bench" * 8
    proc = subprocess.Popen([exe], env={**os.environ, "PORT": str(port), "ENGINE_TOKEN": token, "LOG_LEVEL": "error"})
    try:
        for _ in range(100):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/healthz", timeout=0.5).read(); break
            except OSError:
                time.sleep(0.05)
        client = E.EngineClient(url=f"http://127.0.0.1:{port}", token=token, timeout_ms=5000)
        for n, runs in ((100, 200), (300, 100), (2000, 20)):
            its = items(n, max(5, n // 12), n)
            assert client.order(its, W) == R.order(its, W), "the two rankers disagree: stop"
            print(f"\n{n} candidates, Go engine over localhost HTTP:")
            row("as Python sees it (JSON + HTTP + answer)", timeit(lambda: client.order(its, W), runs))
            body = json.dumps({"weights": W, "items": [{"id": i.id, "creator": i.creator, "age_seconds": i.age_seconds, "interest_points": i.interest_points,
                    "watch_avg": i.watch_avg, "watch_viewers": i.watch_viewers, "engage_points": i.engage_points, "followed": i.followed,
                    "seen_age_seconds": i.seen_age_seconds, "skipped": i.skipped, "reporters": i.reporters} for i in its]}).encode()
            took = []
            for _ in range(runs):
                req = urllib.request.Request(f"http://127.0.0.1:{port}/v1/rank", data=body, headers={"X-Engine-Token": token}, method="POST")
                took.append(json.loads(urllib.request.urlopen(req).read())["took_us"] / 1000)
            took.sort()
            row("Go engine, ranking only (took_us)", (statistics.median(took), took[int(len(took) * 0.95) - 1], took[-1]))
    finally:
        proc.terminate(); proc.wait(timeout=10); shutil.rmtree(work, ignore_errors=True)
    print("\nRead this honestly: numbers are from one machine, localhost, no real network, no database. The ranking itself is a small part "
          "of a feed request (the SQL queries come first). Decide with your own numbers from GET /admin/metrics (group 'feed').")


if __name__ == "__main__":
    main()
