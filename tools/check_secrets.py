#!/usr/bin/env python3
"""Step 71 (report v0.67.0, R12): scan the project for private secrets that must never be in the ZIP / repository / APK.

Run from the project root:   python3 tools/check_secrets.py
Exit code 0 = clean, 1 = something found (CI goes red).

What it looks for (VALUES only; the NAME of a setting such as CLOUDINARY_API_SECRET is fine):
  1. forbidden files: release keystores (.jks, .p12, .pem), .env files, Firebase service-account json files
  2. private key blocks, service-account json bodies, Cloudinary URLs with a secret inside
  3. well-known token shapes: xAI / OpenAI-style keys, Anthropic keys, GitHub tokens, AWS keys, Google API keys,
     Chapa secret keys, Slack tokens
  4. a literal value written after a secret setting name (CLOUDINARY_API_SECRET = "abc...", CHAPA_SECRET_KEY: ...)
  5. the Android app (app/src) must never mention a private server setting name at all

Allowed on purpose (not secrets):
  * app/google-services.json  (Firebase CLIENT config: its public API key is meant to be inside the app; protect it with
    Firebase rules + API-key restrictions in the Google console instead)
  * sociax-debug.keystore     (the fixed DEBUG test key, password "android"; never used for release)
  * fake values inside test files, README and this tool (they are listed in ALLOW_FILES / marked sentinel)
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {".git", ".gradle", "build", "__pycache__", ".idea", "node_modules", "captures", ".cxx"}
BINARY_EXT = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".jar", ".zip", ".apk", ".aab", ".so", ".ttf", ".otf",
              ".woff", ".woff2", ".mp3", ".mp4", ".ogg", ".wav", ".keystore", ".class", ".dex", ".pyc", ".db"}

# files that may contain public client config (not secret) or intentionally fake values
ALLOW_GOOGLE_API_KEY_IN = {"app/google-services.json"}
ALLOW_FILES = {"tools/check_secrets.py"}                       # this file contains the patterns themselves
ALLOW_FORBIDDEN_FILES = {"sociax-debug.keystore"}              # the fixed debug key

FORBIDDEN_FILE_PATTERNS = [
    (re.compile(r"\.(jks|p12|pfx|pem)$", re.I), "a signing key / certificate file"),
    (re.compile(r"(^|/)\.env(\..+)?$", re.I), "an .env file with real settings (only .env.example is allowed)"),
    (re.compile(r"(^|/)(firebase-)?service-account.*\.json$", re.I), "a Firebase / Google service-account key file"),
    (re.compile(r"\.keystore$", re.I), "a keystore file"),
]
ENV_EXAMPLE = re.compile(r"\.env\.(example|sample|template)$", re.I)

TOKEN_PATTERNS = [
    ("private key block", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("service-account json", re.compile(r"\"type\"\s*:\s*\"service_account\"")),
    ("service-account private_key field", re.compile(r"\"private_key\"\s*:\s*\"-----BEGIN")),
    ("Cloudinary URL with secret", re.compile(r"cloudinary://[0-9]{6,}:[A-Za-z0-9_\-]{10,}@")),
    ("Anthropic key", re.compile(r"sk-ant-[A-Za-z0-9_\-]{20,}")),
    ("OpenAI-style key", re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_\-]{32,}")),
    ("xAI key", re.compile(r"\bxai-[A-Za-z0-9]{30,}")),
    ("GitHub token", re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}|\bgithub_pat_[A-Za-z0-9_]{40,}")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Slack token", re.compile(r"\bxox[abprs]-[A-Za-z0-9\-]{10,}")),
    ("Chapa secret key", re.compile(r"\bCHASECK(?:_TEST)?-[A-Za-z0-9]{16,}")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_\-]{30,}")),
]

SECRET_NAMES = ("CLOUDINARY_API_SECRET", "CHAPA_SECRET_KEY", "XAI_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY",
                "RANK_ENGINE_TOKEN", "ENGINE_TOKEN", "SOCIAX_RELEASE_STORE_PASSWORD", "SOCIAX_RELEASE_KEY_PASSWORD",
                "CHAPA_WEBHOOK_SECRET")
# NAME = "literal"   /   NAME: literal   (but not os.environ.get("NAME"), not ${{ secrets.NAME }}, not NAME=$VAR, not empty)
ASSIGN = re.compile(r"\b(" + "|".join(SECRET_NAMES) + r")\b\s*[:=]\s*[\"']?([^\s\"'#$\{\}\(\)\[\],]{16,})")
# values that are clearly placeholders / sentinels
PLACEHOLDER = re.compile(r"(?i)(your|example|placeholder|changeme|xxxx|\.\.\.|<|sentinel|ci-only|dummy|fake|test|todo|secret|the_same|any-)")


def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")


def iter_files():
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            yield os.path.join(base, f)


def read_text(path):
    try:
        with open(path, "rb") as fh:
            raw = fh.read()
    except OSError:
        return None
    if b"\x00" in raw[:4096]:
        return None
    return raw.decode("utf-8", "replace")


def main():
    problems = []
    scanned = 0
    for path in iter_files():
        r = rel(path)
        ext = os.path.splitext(r)[1].lower()
        # 1. forbidden files by name
        if r not in ALLOW_FORBIDDEN_FILES and not ENV_EXAMPLE.search(r):
            for pat, why in FORBIDDEN_FILE_PATTERNS:
                if pat.search(r):
                    problems.append(f"{r}: {why} must not be in the project (keep it in GitHub Secrets / Render Secret Files)")
                    break
        if ext in BINARY_EXT or r in ALLOW_FILES:
            continue
        text = read_text(path)
        if text is None:
            continue
        scanned += 1
        is_test = os.path.basename(r).startswith("test_") or "/test" in r
        # 2 + 3. token shapes
        for name, pat in TOKEN_PATTERNS:
            for m in pat.finditer(text):
                if name == "Google API key" and r in ALLOW_GOOGLE_API_KEY_IN:
                    continue
                if is_test and PLACEHOLDER.search(m.group(0)):
                    continue
                line = text.count("\n", 0, m.start()) + 1
                problems.append(f"{r}:{line}: looks like a {name} (value hidden)")
        # 4. literal value after a secret setting name
        for m in ASSIGN.finditer(text):
            value = m.group(2)
            if PLACEHOLDER.search(value):
                continue
            if re.fullmatch(r"[A-Za-z_\.]+", value):      # a variable / attribute name such as m.CLOUD_SECRET, not a literal secret
                continue
            line = text.count("\n", 0, m.start()) + 1
            problems.append(f"{r}:{line}: {m.group(1)} has a literal value written next to it (value hidden)")
        # 5. the Android app must never name a private server setting
        if r.startswith("app/src/") and r.endswith((".kt", ".xml", ".java")):
            for name in SECRET_NAMES:
                if name in text:
                    problems.append(f"{r}: the Android app mentions the private server setting {name}; private secrets never belong in the app")

    # release keystore must be read from the environment only (build.gradle.kts)
    gradle = read_text(os.path.join(ROOT, "app", "build.gradle.kts")) or ""
    for m in re.finditer(r"storePassword\s*=\s*\"([^\"]+)\"", gradle):
        if m.group(1) != "android":
            problems.append("app/build.gradle.kts: a signing password is written in the file (only the debug key's \"android\" is allowed)")

    if problems:
        print("check_secrets: FOUND %d problem(s) in %d text files:" % (len(problems), scanned))
        for p in problems:
            print("  - " + p)
        print("Remove the value, rotate the key if it was ever uploaded anywhere, and keep it in GitHub Secrets / Render environment.")
        return 1
    print("check_secrets: %d text files scanned, no private secret found: ok" % scanned)
    return 0


if __name__ == "__main__":
    sys.exit(main())
