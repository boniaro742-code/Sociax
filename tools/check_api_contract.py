#!/usr/bin/env python3
"""App <-> backend address check (runs WITHOUT Android SDK and WITHOUT a server).
Reads every address the Android app calls (ApiClient.kt) and checks that the backend (backend/main.py) really has a route with the same
method and path. A typing mistake or a renamed address is found here, in GitHub, instead of on a phone.
Usage: python tools/check_api_contract.py   (exit code 1 when an address is missing)"""
import re, sys, pathlib

root = pathlib.Path(__file__).resolve().parent.parent
api = (root / "app/src/main/java/com/sociax/app/data/ApiClient.kt").read_text(encoding="utf-8")
api = re.sub(r"\$\{[^{}]*\}", "$v", api)      # ${ ... } template pieces (they may contain quotes) become one variable
main = (root / "backend/main.py").read_text(encoding="utf-8")

# backend routes: @app.get("/x/{id}/y")
routes = set()
for m in re.finditer(r'@app\.(get|post|put|delete|patch)\(\s*"([^"]+)"', main):
    routes.add((m.group(1).upper(), re.sub(r"\{[^}]+\}", "{}", m.group(2))))


def norm(path: str) -> str:
    path = path.split("?")[0]
    path = re.sub(r"\$\{[^}]*\}", "{}", path)          # ${expr}
    path = re.sub(r"\$[A-Za-z_][A-Za-z0-9_]*", "{}", path)   # $name
    return path


# app calls: call("GET", "/x/$id")  or  call(if (on) "POST" else "DELETE", "/x/$id")  or rankedPage("/x?limit=1", ...)
calls = []
for m in re.finditer(r'call\(\s*(if \([^)]*\) "([A-Z]+)" else "([A-Z]+)"|"([A-Z]+)")\s*,\s*"([^"]+)"', api):
    methods = [x for x in (m.group(2), m.group(3), m.group(4)) if x]
    for meth in methods:
        calls.append((meth, norm(m.group(5))))
for m in re.finditer(r'rankedPage\(\s*"([^"]+)"', api):
    calls.append(("GET", norm(m.group(1))))

# A path whose last piece is a variable that is really one of a few fixed words: every word must exist on the backend.
EXPANDED = {("GET", "/users/{}/{}"): [("GET", "/users/{}/followers"), ("GET", "/users/{}/following")],
            ("POST", "/follow-requests/{}/{}"): [("POST", "/follow-requests/{}/{}")]}
expanded = []
for c in calls:
    expanded.extend(EXPANDED.get(c, [c]))
missing = sorted({c for c in expanded if c not in routes})
if missing:
    print("check_api_contract: the app calls addresses the backend does not have:")
    for meth, path in missing:
        print(f"  {meth} {path}")
    sys.exit(1)
print(f"check_api_contract: {len(set(expanded))} app addresses, {len(routes)} backend routes: ok")
