#!/usr/bin/env python3
"""Step 3 (report v0.67.0, R4): Google Play needs API 36 (Android 16) for new apps and updates (since 2026-08-31).
Checks, without building, that the build files still agree with that: targetSdk/compileSdk >= 36, AGP >= 8.9.1 (the first
AGP that supports API 36), the Gradle wrapper and the CI Gradle >= 8.11.1 (what AGP 8.9 needs), and CI installs platform 36.
Run:  python3 tools/check_play_target.py        (exit code 1 = a problem, printed below)"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
problems = []


def read(rel):
    p = ROOT / rel
    if not p.exists():
        problems.append(f"{rel}: file not found")
        return ""
    return p.read_text(encoding="utf-8")


def ver(text):
    return tuple(int(x) for x in re.findall(r"\d+", text)[:3])


app = read("app/build.gradle.kts")
for key in ("compileSdk", "targetSdk"):
    m = re.search(rf"\b{key}\s*=\s*(\d+)", app)
    if not m or int(m.group(1)) < 36:
        problems.append(f"app/build.gradle.kts: {key} must be 36 or higher (found {m.group(1) if m else 'nothing'})")

top = read("build.gradle.kts")
m = re.search(r'com\.android\.application"\)\s*version\s*"([\d.]+)"', top)
if not m or ver(m.group(1)) < (8, 9, 1):
    problems.append(f"build.gradle.kts: Android Gradle Plugin must be 8.9.1 or higher for API 36 (found {m.group(1) if m else 'nothing'})")

wrapper = read("gradle/wrapper/gradle-wrapper.properties")
m = re.search(r"gradle-([\d.]+)-bin\.zip", wrapper)
if not m or ver(m.group(1)) < (8, 11, 1):
    problems.append(f"gradle-wrapper.properties: Gradle must be 8.11.1 or higher for AGP 8.9 (found {m.group(1) if m else 'nothing'})")

ci = read(".github/workflows/build.yml")
for v in re.findall(r'gradle-version:\s*"([\d.]+)"', ci):
    if ver(v) < (8, 11, 1):
        problems.append(f".github/workflows/build.yml: gradle-version {v} is too old for AGP 8.9 (needs 8.11.1+)")
if "platforms;android-36" not in ci:
    problems.append(".github/workflows/build.yml: the workflow must install platforms;android-36")

if problems:
    print("PLAY TARGET CHECK FAILED:")
    for p in problems:
        print(" -", p)
    sys.exit(1)
print("Play target check: OK (API 36, AGP >= 8.9.1, Gradle >= 8.11.1)")
