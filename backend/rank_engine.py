"""Step 48 (plan v3.4 sections 17 + 21, v3.9 section 30): optional Go ranking engine (backend-go/).

The Python backend stays the ONLY place that decides who may see what. It filters the candidates first (VISIBLE, blocks, hidden,
reported ...), then asks the Go engine to put the SAME candidates in order. Nothing here can add content:
  * the reply must be exactly a reordering of the ids that were sent (anything else is thrown away),
  * if the engine is off, slow, wrong or unreachable, Python ranks the candidates itself with ranker.py (the rollback path),
  * after 3 failures in a row the engine is skipped for 30 seconds, so a sleeping or dead service never slows the feed down.

Privacy: the engine receives numbers and ANONYMOUS creator labels (c0, c1 ...). Never a user id, username, caption or message.
Settings (server only): RANK_ENGINE_URL (empty = engine off, the default), RANK_ENGINE_TOKEN (the same secret as ENGINE_TOKEN of the engine),
RANK_ENGINE_TIMEOUT_MS (default 800). Values are never logged."""
import json
import logging
import os
import threading
import time

import ranker

_log = logging.getLogger("sociax.rank_engine")

FAIL_LIMIT = 3             # this many failures in a row open the circuit ...
PAUSE_SECONDS = 30.0       # ... and the engine is skipped for this long
MAX_RESPONSE_BYTES = 1 << 20


def _env_int(name: str, default: int, lo: int, hi: int) -> int:
    try:
        return max(lo, min(hi, int(os.getenv(name, str(default)))))
    except ValueError:
        return default


class EngineClient:
    """One instance per process. `transport(url, headers, body_bytes, timeout_s) -> (status, bytes)` can be replaced in tests."""

    def __init__(self, url: str | None = None, token: str | None = None, timeout_ms: int | None = None, transport=None, clock=time.monotonic):
        self.url = (os.getenv("RANK_ENGINE_URL", "") if url is None else url).strip().rstrip("/")
        self.token = os.getenv("RANK_ENGINE_TOKEN", "") if token is None else token
        self.timeout_s = (_env_int("RANK_ENGINE_TIMEOUT_MS", 800, 50, 10000) if timeout_ms is None else timeout_ms) / 1000.0
        self.transport = transport or self._http_post
        self._clock = clock
        self._lock = threading.Lock()
        self._fails = 0
        self._paused_until = 0.0
        self.stats = {"ok": 0, "failed": 0, "skipped": 0, "python": 0}
        self._client = None

    # ---- state the owner can look at (booleans and counters only) ----
    @property
    def configured(self) -> bool:
        return bool(self.url and len(self.token) >= 24 and self.url.startswith(("http://", "https://")))

    def report(self) -> dict:
        with self._lock:
            return {"configured": self.configured, "paused": self._clock() < self._paused_until, **self.stats}

    # ---- transport ----
    def _http_post(self, url, headers, body, timeout_s):
        try:
            import httpx
        except ImportError:                      # stdlib fallback (also used by the tests)
            import urllib.request, urllib.error
            req = urllib.request.Request(url, data=body, headers=headers, method="POST")
            try:
                with urllib.request.urlopen(req, timeout=timeout_s) as r:
                    return r.status, r.read(MAX_RESPONSE_BYTES)
            except urllib.error.HTTPError as e:
                return e.code, b""
        if self._client is None:                 # one keep-alive connection pool: no new TLS handshake per feed request
            self._client = httpx.Client(timeout=timeout_s)
        r = self._client.post(url, headers=headers, content=body, timeout=timeout_s)
        return r.status_code, r.content[:MAX_RESPONSE_BYTES]

    # ---- circuit breaker ----
    def _allowed(self) -> bool:
        with self._lock:
            if self._clock() < self._paused_until:
                self.stats["skipped"] += 1
                return False
            return True

    def _success(self):
        with self._lock:
            self._fails = 0
            self.stats["ok"] += 1

    def _failure(self, why: str):
        with self._lock:
            self._fails += 1
            self.stats["failed"] += 1
            if self._fails >= FAIL_LIMIT:
                self._paused_until = self._clock() + PAUSE_SECONDS
                self._fails = 0
                _log.warning("rank engine paused for %ds after %d failures (last: %s)", int(PAUSE_SECONDS), FAIL_LIMIT, why)
            else:
                _log.warning("rank engine failed (%s): ranked in Python", why)

    # ---- the one call ----
    def order(self, items: list, weights: dict, limit: int = 0) -> list:
        """Returns item ids, best first. NEVER raises and NEVER returns anything that is not a reordering of `items`."""
        ids = self._ask_engine(items, weights, limit) if (self.configured and items) else None
        if ids is None:
            with self._lock:
                self.stats["python"] += 1
            ids = ranker.order(items, weights)
            return ids[:limit] if limit else ids
        return ids

    def _ask_engine(self, items: list, weights: dict, limit: int):
        if not self._allowed():
            return None
        labels: dict = {}
        payload_items = []
        for it in items:                         # creators become c0, c1 ...: the engine only needs to know who is the same person
            payload_items.append({
                "id": it.id, "creator": labels.setdefault(it.creator, f"c{len(labels)}"), "age_seconds": float(it.age_seconds),
                "interest_points": float(it.interest_points), "watch_avg": it.watch_avg, "watch_viewers": int(it.watch_viewers),
                "engage_points": float(it.engage_points), "followed": bool(it.followed),
                "seen_age_seconds": it.seen_age_seconds, "skipped": bool(it.skipped), "reporters": int(it.reporters)})
        body = json.dumps({"weights": weights, "items": payload_items, "limit": limit or 0}, separators=(",", ":")).encode()
        try:
            status, raw = self.transport(self.url + "/v1/rank", {"Content-Type": "application/json", "X-Engine-Token": self.token},
                                         body, self.timeout_s)
        except Exception as e:                   # noqa: BLE001  timeout, refused, DNS, TLS ...: the type only, never the text (it may hold the address)
            self._failure(type(e).__name__)
            return None
        if status != 200:
            self._failure(f"HTTP {status}")
            return None
        try:
            data = json.loads(raw)
            got = data["ids"]
        except (ValueError, KeyError, TypeError):
            self._failure("unreadable answer")
            return None
        if not self._is_reordering(got, items, limit):
            self._failure("answer is not a reordering of the candidates")
            return None
        self._success()
        return got

    @staticmethod
    def _is_reordering(got, items, limit: int) -> bool:
        """The engine's answer must be distinct ids, all from what was sent, and complete (or exactly `limit` long)."""
        if not isinstance(got, list) or any(type(x) is not int for x in got):
            return False
        sent = {it.id for it in items}
        want = min(limit, len(items)) if limit else len(items)
        return len(got) == want and len(set(got)) == len(got) and all(x in sent for x in got)


# the process-wide client used by main.py
client = EngineClient()
