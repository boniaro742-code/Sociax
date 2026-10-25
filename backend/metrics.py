"""Step 47 (plan v3.9 section 37 + Phase F): MEASURE before tuning. A tiny in-memory recorder of how long each kind of request
takes and how often it fails. It stores ONLY: the HTTP method, the route PATTERN (e.g. "/chats/{conv_id}/messages", never the real
address, a username, an id, a query or a token), the status class and the time in milliseconds. No user id, no content.
Numbers start again at 0 when the server restarts; this is a baseline tool, not a database of record (nothing is written to disk)."""
import threading
import time
from collections import deque

WINDOW = 500            # the newest 500 timings of each route are kept for percentiles
MAX_ROUTES = 300        # a hard bound on memory; more distinct patterns than this are counted under "other"

# The plan names the things to measure: feed API latency, search latency, chat delivery, upload success, backend error rate, AI.
GROUPS = ("feed", "search", "chat", "upload", "ai", "other")


def group_of(method: str, route: str) -> str:
    if route.startswith("/ai"):
        return "ai"
    if route == "/search":
        return "search"
    if route.startswith("/chats"):
        return "chat"
    if method == "POST" and (route in ("/posts", "/shorts", "/stories", "/stories/text") or route.startswith("/media/")):
        return "upload"
    if method == "GET" and (route.startswith("/feed") or route == "/shorts/feed" or route == "/shorts/for-you"):
        return "feed"
    return "other"


def percentile(sorted_values: list, p: float):
    """Nearest-rank percentile of an ASCENDING list (None when empty)."""
    if not sorted_values:
        return None
    k = max(0, min(len(sorted_values) - 1, int(-(-p * len(sorted_values) // 100)) - 1))
    return sorted_values[k]


class _Route:
    __slots__ = ("count", "c4", "c5", "total", "peak", "recent")

    def __init__(self):
        self.count = self.c4 = self.c5 = self.total = self.peak = 0
        self.recent = deque(maxlen=WINDOW)


class Metrics:
    def __init__(self):
        self._lock = threading.Lock()
        self._routes: dict = {}
        self.started = time.time()

    def record(self, method: str, route: str, status: int, ms: int):
        key = (method, route)
        with self._lock:
            r = self._routes.get(key)
            if r is None:
                if len(self._routes) >= MAX_ROUTES:
                    key = ("*", "other")
                    r = self._routes.get(key)
                if r is None:
                    r = self._routes[key] = _Route()
            r.count += 1
            r.total += ms
            r.peak = max(r.peak, ms)
            r.recent.append(ms)
            if status >= 500:
                r.c5 += 1
            elif status >= 400:
                r.c4 += 1

    def reset(self):
        with self._lock:
            self._routes.clear()
            self.started = time.time()

    def snapshot(self, top: int = 30) -> dict:
        with self._lock:
            items = [(k, r.count, r.c4, r.c5, r.total, r.peak, sorted(r.recent)) for k, r in self._routes.items()]
            started = self.started
        total = sum(i[1] for i in items)
        errors5 = sum(i[3] for i in items)
        groups = {g: {"count": 0, "errors_5xx": 0, "_ms": []} for g in GROUPS}
        for (method, route), count, c4, c5, _t, peak, recent in items:
            g = groups[group_of(method, route)]
            g["count"] += count
            g["errors_5xx"] += c5
            g["_ms"].extend(recent)
        out_groups = {}
        for name, g in groups.items():
            ms = sorted(g.pop("_ms"))
            out_groups[name] = {"count": g["count"], "errors_5xx": g["errors_5xx"],
                                "error_rate_5xx": round(g["errors_5xx"] / g["count"], 4) if g["count"] else 0.0,
                                "p50_ms": percentile(ms, 50), "p95_ms": percentile(ms, 95), "p99_ms": percentile(ms, 99)}
        routes = []
        for (method, route), count, c4, c5, _t, peak, recent in sorted(items, key=lambda i: -i[1])[:top]:
            routes.append({"route": f"{method} {route}", "count": count, "errors_4xx": c4, "errors_5xx": c5,
                           "p50_ms": percentile(recent, 50), "p95_ms": percentile(recent, 95), "max_ms": peak})
        return {"uptime_s": int(time.time() - started), "requests": total, "errors_5xx": errors5,
                "error_rate_5xx": round(errors5 / total, 4) if total else 0.0,
                "window": WINDOW, "groups": out_groups, "routes": routes}
