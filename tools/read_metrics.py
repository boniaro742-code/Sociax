#!/usr/bin/env python3
"""Step 47: print the server's baseline numbers (plan v3.9 section 37) as a plain table.
Usage:   python tools/read_metrics.py https://YOUR-SERVER  YOUR_METRICS_TOKEN
(The token is the METRICS_TOKEN you set on the server. It is sent as a header and never printed.)"""
import json
import sys
import urllib.request


def _ms(v):
    return "-" if v is None else f"{v} ms"


def format_report(d: dict) -> str:
    lines = [f"Sociax server {d.get('version', '?')}: up {d['uptime_s'] // 60} min, {d['requests']} requests, "
             f"{d['errors_5xx']} server errors ({d['error_rate_5xx'] * 100:.2f}%)",
             f"(newest {d['window']} timings per route; starts again at 0 when the server restarts)", "",
             f"{'WHAT THE PLAN MEASURES':<24}{'requests':>9}{'5xx':>6}{'p50':>10}{'p95':>10}{'p99':>10}"]
    for name, g in d["groups"].items():
        lines.append(f"{name:<24}{g['count']:>9}{g['errors_5xx']:>6}{_ms(g['p50_ms']):>10}{_ms(g['p95_ms']):>10}{_ms(g['p99_ms']):>10}")
    lines += ["", f"{'ROUTE (busiest first)':<44}{'n':>7}{'4xx':>6}{'5xx':>6}{'p50':>9}{'p95':>9}{'max':>9}"]
    for r in d["routes"]:
        lines.append(f"{r['route'][:43]:<44}{r['count']:>7}{r['errors_4xx']:>6}{r['errors_5xx']:>6}"
                     f"{_ms(r['p50_ms']):>9}{_ms(r['p95_ms']):>9}{_ms(r['max_ms']):>9}")
    pb = d.get("push_backlog", {})
    lines += ["", f"Push waiting to be sent: {pb.get('waiting', 0)} (oldest {pb.get('oldest_s', 0)} s)"]
    return "\n".join(lines)


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    req = urllib.request.Request(argv[1].rstrip("/") + "/admin/metrics", headers={"X-Metrics-Token": argv[2]})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            print(format_report(json.load(r)))
        return 0
    except Exception as e:  # noqa: BLE001
        print("Could not read the metrics:", type(e).__name__, "(wrong address or token, or METRICS_TOKEN is not set on the server)")
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
