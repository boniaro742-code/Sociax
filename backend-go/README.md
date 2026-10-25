# Sociax engine (Go)

Optional Go service next to the Python backend (plan v3.4 sections 16-23, v3.9 section 30). **Today it does one job: it puts the
For You candidates in order** (the same ranking as `backend/ranker.py`). The phone never talks to it. Only the Python backend does.

- The Python backend stays the only place that decides who may see what. It filters first, then sends the remaining candidates here.
  This service can only reorder what it was given: Python refuses any answer that adds, drops or repeats an item.
- Nothing else depends on it. Leave `RANK_ENGINE_URL` empty (the default) or stop this service and Python ranks by itself (the rollback path).
- Standard library only (no third-party Go packages): nothing to download, nothing to audit besides this folder.

## Run it
```
cd backend-go
ENGINE_TOKEN=any-secret-of-24-or-more-characters go run ./cmd/sociax-engine      # listens on $PORT, default 8080
```
It refuses to start without `ENGINE_TOKEN` (24+ characters). Settings: `PORT`, `ENGINE_TOKEN`, `ENGINE_MAX_ITEMS` (2000), `ENGINE_MAX_INFLIGHT` (32), `LOG_LEVEL`.

Addresses: `GET /healthz`, `GET /readyz` (open, no data), `POST /v1/rank` and `GET /v1/stats` (header `X-Engine-Token`).

## Connect the Python backend
On the Python backend set `RANK_ENGINE_URL` (address of this service, no slash at the end) and `RANK_ENGINE_TOKEN` (the same secret).
Check: `GET /health/config` shows `rank_engine: {configured, paused, ok, failed, skipped, python}`; `GET /admin/metrics` shows the same counters.
Python waits 800 ms at most (`RANK_ENGINE_TIMEOUT_MS`); after 3 failures in a row it skips the engine for 30 seconds.

## Tests
```
cd backend-go && gofmt -l . && go vet ./... && go test ./... -race -count=1
cd backend && python test_rank_engine.py           # builds + starts the real service and compares it with ranker.py
python tools/bench_rank.py                         # honest Python-vs-Go timing on your machine
```
`internal/ranker/testdata/parity.json` holds 182 candidate lists and the order `ranker.py` gives. If you change `ranker.py`
(or the Go ranker), run `python tools/gen_rank_parity.py`, then `go test ./...`, and commit the file. GitHub checks it too.

## What it sends and keeps
The engine receives numbers and anonymous creator labels (`c0`, `c1` ...): no user id, username, caption, message or media name.
It stores nothing on disk. Logs contain route, status, counts and time only (never the secret, an id or a creator).

## Deploy it and check it (Step 49)
1. On Render: the `sociax-engine` service is now ACTIVE in `render.yaml`. Push this version to GitHub, then in Render > Blueprints click
   "Sync" (or New > Blueprint, pick the repository). Render builds `backend-go/` and asks for `ENGINE_TOKEN`: give it a secret of 24+
   characters (make one: `python3 -c "import secrets;print(secrets.token_urlsafe(32))"`) and keep a copy: you need it in steps 2 and 3.
   If Render asks for `RANK_ENGINE_URL` / `RANK_ENGINE_TOKEN` of `sociax-backend` now, leave them EMPTY (Python keeps ranking).
2. Check the running service from your computer (the secret is read from the environment, never typed on the command line):
   ```
   ENGINE_TOKEN=<the same secret> python tools/check_engine_deploy.py https://<your-engine>.onrender.com
   ```
   Every line must say PASS. It also prints the cold-start time (a free service sleeps) and the round-trip time from where YOU are.
3. Only then connect it: on the Python backend set `RANK_ENGINE_URL` (same address) and `RANK_ENGINE_TOKEN` (same secret), and run
   `python tools/check_engine_deploy.py <engine address> --backend <python backend address>` to see `rank_engine.configured`.
4. Compare `GET /admin/metrics` (group `feed`) before and after. If the feed is not faster for you, empty `RANK_ENGINE_URL`: nothing else changes.

GitHub also starts the REAL built service on every push and runs this checker against it (job "Go engine checks").
