#!/usr/bin/env python3
"""Android source sanity checks that run WITHOUT the Android SDK (they do not replace the real Gradle build):
  1. every ( ) { } [ ] is balanced in each .kt file (outside strings and comments);
  2. every tr("key") / trf("key") used in the code exists in ui/theme/Strings.kt;
  3. every string row has all FIVE languages (English, Francais, Chinese, Korean, Russian), none blank;
  4. the placeholders (%s, %d, %.1f ...) of each translation match the English text (a wrong one crashes String.format);
  5. every drawable / raw resource name used as R.drawable.x / R.raw.x / R.string.x exists.
Usage: python tools/check_android_sources.py [path to app/src/main]   (exit code 1 when a problem is found)"""
import re, sys, pathlib

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "app/src/main")
kt_root = root / "java/com/sociax/app"
problems, warnings = [], []
ENGLISH_ONLY = {"terms_body", "privacy_policy_body"}   # the Terms / Privacy drafts are English only on purpose (README Step 28); other languages fall back to English


def strip(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i); i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2); i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3; out.append('""')
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            i = j + 1; out.append('""')
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == "\\" else 1
            i = j + 1; out.append("''")
        else:
            out.append(c); i += 1
    return "".join(out)


def kotlin_string(src, i):
    """Reads one "..." literal starting at src[i] == '"'. Returns (text, index after it)."""
    assert src[i] == '"'
    j, buf = i + 1, []
    while src[j] != '"':
        if src[j] == "\\":
            buf.append(src[j + 1]); j += 2
        else:
            buf.append(src[j]); j += 1
    return "".join(buf), j + 1


# ---- string table ---------------------------------------------------------------------------------------------------
strings_kt = (kt_root / "ui/theme/Strings.kt").read_text()
rows = {}
for m in re.finditer(r'^\s*"([a-z0-9_]+)" to e\(', strings_kt, re.M):
    key, i = m.group(1), m.end()
    vals = []
    while len(vals) < 5:
        while strings_kt[i] in " \n\t,":
            i += 1
        if strings_kt[i] != '"':
            break
        text, i = kotlin_string(strings_kt, i)
        vals.append(text)
    while strings_kt[i] in " \n\t":
        i += 1
    if len(vals) != 5 or strings_kt[i] != ")":
        problems.append(f"Strings.kt: row '{key}' does not have exactly five texts")
        continue
    rows[key] = vals
    if any(not v.strip() for v in vals) and key not in ENGLISH_ONLY:
        problems.append(f"Strings.kt: row '{key}' has a blank translation")
    # only the KIND of each placeholder is compared (%1$s and %s are the same kind: a language may reorder them)
    spec = lambda t: sorted(m_[-1] for m_ in re.findall(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc]", t))
    for lang, v in zip(("fr", "zh", "ko", "ru"), vals[1:]):
        if spec(v) != spec(vals[0]):
            problems.append(f"Strings.kt: row '{key}' ({lang}) placeholders {spec(v)} differ from English {spec(vals[0])}")

# ---- per file checks ------------------------------------------------------------------------------------------------
res_names = {"drawable": set(), "raw": set(), "string": set()}
for kind in ("drawable", "raw"):
    for p in root.glob(f"res/{kind}*/*"):
        res_names[kind].add(p.stem)
for p in root.glob("res/values/*.xml"):
    res_names["string"] |= set(re.findall(r'<string name="([^"]+)"', p.read_text()))

files = sorted(kt_root.rglob("*.kt"))
for f in files:
    raw = f.read_text()
    s = strip(raw)
    for a, b in ("()", "{}", "[]"):
        if s.count(a) != s.count(b):
            problems.append(f"{f.name}: unbalanced {a}{b} ({s.count(a)} vs {s.count(b)})")
    for k in sorted(set(re.findall(r'\btrf?\(\s*"([a-zA-Z0-9_]+)"', raw))):
        if k not in rows:
            problems.append(f"{f.name}: string key '{k}' is not in Strings.kt")
    for kind, name in re.findall(r"\bR\.(drawable|raw|string)\.([A-Za-z0-9_]+)", raw):
        if name not in res_names[kind]:
            problems.append(f"{f.name}: R.{kind}.{name} does not exist")
    for lit in re.findall(r'\bText\(\s*"([^"$]{3,})"', raw):
        if re.search(r"[A-Za-z]{3,}", lit) and not lit.startswith("Sociax") and lit != "AI":
            warnings.append(f"{f.name}: visible text hard-coded in English: \"{lit[:40]}\"")

for w in warnings:
    print("warning:", w)
for p in problems:
    print("PROBLEM:", p)
print(f"check_android_sources: {len(files)} Kotlin files, {len(rows)} string rows x 5 languages: "
      f"{'PROBLEMS FOUND' if problems else 'ok'}" + (f" ({len(warnings)} warnings)" if warnings else ""))
sys.exit(1 if problems else 0)
