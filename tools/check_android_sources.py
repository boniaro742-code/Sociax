#!/usr/bin/env python3
"""Android source sanity checks that run WITHOUT the Android SDK (they do not replace the real Gradle build):
  1. every ( ) { } [ ] is balanced in each .kt file (outside strings and comments);
  2. every tr("key") / trf("key") used in the code exists in ui/theme/Strings.kt;
  3. every string row has all FIVE languages (English, Francais, Chinese, Korean, Russian), none blank;
  4. the placeholders (%s, %d, %.1f ...) of each translation match the English text (a wrong one crashes String.format);
  5. every drawable / raw resource name used as R.drawable.x / R.raw.x / R.string.x exists;
  6. (Step 54, plan v3.9.2) `deleteAccount(` is called ONLY by the explicit Settings > Delete account row (PrivacyScreens.kt):
     logging out, closing the app or signing in elsewhere can never delete an account.
  7. (Step 56, plan v3.10.1) keyboard and text input: the activity declares adjustResize + configChanges (a rotation must not recreate
     the screen and lose the text); Chat / AI Chat / comments / story reply use the shared ComposerField (Send key, bounded height, no
     bare TextField); their drafts are rememberSaveable; the search box is NOT inside the scrolling list; Search has the Search key;
     Back with the keyboard open only closes the keyboard.
  8. (Step 57, plan v3.10.0) persistent login / one-time onboarding: start-up waits for Firebase's own answer about the saved session
     (not a bare `currentUser` read); a 401 gets one fresh token before it is believed; the session is observed; coming back online
     re-runs the start-up decision; onboarding is shown ONLY when the server says "no profile" (one place), a 409 "already onboarded"
     counts as success; start-up never writes profile fields; `signOut()` is called in exactly three reviewed places.
  9. (Step 58, plan v3.9.5 / v3.9.6) EXACTLY five bottom tabs Home, Bio, Short Video, Chat, AI (in that order); the AI tab opens the AI Hub;
     the Chat list has no AI card / button / shortcut and AI Chat is opened from the hub only; the tab circles are at least 48 dp,
     named for TalkBack and marked as tabs.
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

# ---- 6. only the Delete account row may delete an account ----
for f in files:
    for m_ in re.finditer(r"\bdeleteAccount\s*\(", strip(f.read_text())):
        if f.name not in ("ApiClient.kt", "PrivacyScreens.kt"):
            problems.append(f"{f.name}: deleteAccount() is called here; only the Delete account row (PrivacyScreens.kt) may call it")
main_kt = strip((kt_root / "MainActivity.kt").read_text())
logout = main_kt[main_kt.find("onLogout"):]
logout = logout[:logout.find("lock = lock")] if "lock = lock" in logout else logout
if "lock.clear()" in logout.split("onChangeLock")[0].split("val finish")[1] if "val finish" in logout else False:
    problems.append("MainActivity.kt: log out clears the App Lock; logging out must only end the session (plan v3.9.2)")

# ---- 7. keyboard and text input (plan v3.10.1) ----
manifest = (root / "AndroidManifest.xml").read_text()
act = manifest[manifest.find('android:name=".MainActivity"'):]
act = act[:act.find(">")]
if 'android:windowSoftInputMode="adjustResize"' not in act:
    problems.append('AndroidManifest.xml: MainActivity must declare android:windowSoftInputMode="adjustResize" (without it the window is panned AND lifted: the keyboard covers or shifts the page)')
cc = re.search(r'android:configChanges="([^"]*)"', act)
if not cc or not {"orientation", "screenSize"} <= set(cc.group(1).split("|")):
    problems.append("AndroidManifest.xml: MainActivity must handle configChanges orientation|screenSize, otherwise a rotation recreates the app and the typed text and the open page are lost")
for name in ("ChatScreens.kt", "AiScreen.kt", "SocialScreens.kt", "StoryScreens.kt"):
    code = strip((kt_root / "ui/screens" / name).read_text())
    if re.search(r"(?<![A-Za-z])TextField\(", code.replace("OutlinedTextField(", "")):
        problems.append(f"{name}: a bare TextField( is used; message boxes must use ComposerField (Send key, max height, same behaviour everywhere)")
for name, var in (("ChatScreens.kt", "text"), ("AiScreen.kt", "text"), ("SocialScreens.kt", "text"), ("SocialScreens.kt", "query")):
    code = strip((kt_root / "ui/screens" / name).read_text())
    if re.search(r"var " + var + r" by remember \{ mutableStateOf\(\"\"\) \}", (kt_root / "ui/screens" / name).read_text()) and \
       not re.search(r"var " + var + r" by rememberSaveable", (kt_root / "ui/screens" / name).read_text()):
        problems.append(f"{name}: the draft '{var}' must be rememberSaveable so it is not lost when the screen is rebuilt")
social = (kt_root / "ui/screens/SocialScreens.kt").read_text()
if re.search(r"item\s*\{\s*GlassField\(", social):
    problems.append("SocialScreens.kt: the search box is inside the scrolling list; scrolling disposes it and the keyboard closes while typing")
if "ImeAction.Search" not in social:
    problems.append("SocialScreens.kt: the search box has no Search key (ImeAction.Search)")
mk = (kt_root / "MainActivity.kt").read_text()
if "WindowInsetsCompat.Type.ime()" not in mk:
    problems.append("MainActivity.kt: the Back handler does not close an open keyboard first (it would close the page and lose the draft)")
if not re.search(r"Modifier\.navigationBarsPadding\(\)\.imePadding\(\)", mk):
    problems.append("MainActivity.kt: pages opened on top of the tabs are not lifted above the keyboard (imePadding)")

# ---- 8. persistent login and one-time onboarding (plan v3.10.0) ----
auth_kt = (kt_root / "data/AuthRepository.kt").read_text()
for need in ("suspend fun awaitSession(", "addAuthStateListener", "suspend fun refreshSession(", "fun observeEnded(", "FirebaseAuthInvalidUserException"):
    if need not in auth_kt:
        problems.append(f"AuthRepository.kt: '{need}' is missing (plan v3.10.0: restore, observe and check the saved session)")
if "auth.awaitSession()" not in mk or re.search(r"if \(auth\.isSignedIn\) routeAfterLogin\(\)", mk):
    problems.append("MainActivity.kt: start-up must wait for auth.awaitSession(); a bare isSignedIn read can send a signed-in person to the login screen")
for need, why in (("auth.refreshSession()", "a 401 must get one fresh token before it ends the session"),
                  ("auth.observeEnded", "the Firebase session must be observed"),
                  ("registerDefaultNetworkCallback", "coming back online must re-run the start-up decision"),
                  ("routingNow", "only one start-up decision may run at a time"),
                  ("e.code == 409", "an 'already onboarded' answer must count as success")):
    if need not in mk:
        problems.append(f"MainActivity.kt: '{need}' is missing: {why}")
mk_code = strip(mk)
if len(re.findall(r"\.signOut\(\)", mk_code)) != 3:
    problems.append("MainActivity.kt: signOut() must be called in exactly 3 places (Log out, Delete account, a session Firebase calls invalid); a new place needs review (plan v3.10.0 section 4)")
if re.search(r"stage\s*=\s*Stage\.NeedsOnboarding", mk_code) or mk_code.count("else Stage.NeedsOnboarding") != 1:
    problems.append("MainActivity.kt: onboarding may be chosen in ONE place only: `stage = if (me.onboarded) Ready else NeedsOnboarding` after the server answered")
startup = mk[mk.find("suspend fun routeAfterLoginRun"):mk.find("val routingNow")] + mk[mk.find("suspend fun syncLanguage"):mk.find("fun connectFailure")]
for bad in ("updateProfile(", "updatePrivacy(", "updateNotifications(", "api.onboard("):
    if bad in startup:
        problems.append(f"MainActivity.kt: the start-up code calls {bad}: opening the app must never write profile fields")
if "ACCESS_NETWORK_STATE" not in manifest:
    problems.append("AndroidManifest.xml: ACCESS_NETWORK_STATE is missing (needed to notice that the network is back)")

# ---- 9. five tabs; every AI tool behind the AI tab, none in Chat (plan v3.9.5 / v3.9.6) ----
tab_block = re.search(r"enum class Tab\([^)]*\) \{(.*?)\n\}", mk, re.S)
tab_rows = re.findall(r'^\s*(\w+)\(Icons\.[\w.]+,\s*Icons\.[\w.]+,\s*"[^"]*",\s*"(\w+)"\)', tab_block.group(1) if tab_block else "", re.M)
if tab_rows != [("Home", "tab_home"), ("Bio", "tab_bio"), ("Video", "tab_video"), ("Chat", "tab_chat"), ("Ai", "tab_ai")]:
    problems.append(f"MainActivity.kt: the bottom bar must have exactly Home, Bio, Short Video, Chat, AI in this order (plan v3.9.5); found {tab_rows}")
if not re.search(r"Tab\.Ai\s*->\s*AiHubScreen\(", mk):
    problems.append("MainActivity.kt: the AI tab must open AiHubScreen (plan v3.9.6 section 3)")
chat_kt = (kt_root / "ui/screens/ChatScreens.kt").read_text()
chat_list = chat_kt[chat_kt.find("fun ChatListScreen"):chat_kt.find("// One conversation")]
if not chat_list or re.search(r"onAi|Overlay\.Ai|AiAssistantScreen|\"ai_|\bAI\b", strip(chat_list).replace('""', "")) or re.search(r'tr\("ai_', chat_list):
    problems.append("ChatScreens.kt: the Chat list contains an AI entry point; AI lives only in the AI tab (plan v3.9.6 section 2)")
tab_chat_branch = mk[mk.find("Tab.Chat -> ChatListScreen("):mk.find("Tab.Ai -> AiHubScreen(")]
if "Overlay.Ai" in tab_chat_branch or "onAi" in tab_chat_branch:
    problems.append("MainActivity.kt: the Chat tab opens the AI screen; only the AI Hub may do that")
if "Overlay.Ai" in mk:
    problems.append("MainActivity.kt: Step 59: the AI tab IS the chat, so the old Overlay.Ai screen must not come back (found %d uses of Overlay.Ai)" % mk.count("Overlay.Ai"))
bar = mk[mk.find("fun BottomBar("):]
if not re.search(r"\.height\(BottomBarHeight\)", bar) or "Role.Tab" not in bar or "contentDescription" not in bar or "selected = active" not in bar:
    problems.append("MainActivity.kt: every bottom-bar tab must be BottomBarHeight tall, have a TalkBack name, Role.Tab and a selected state (plan v3.9.5 section 2)")
bh = re.search(r"val BottomBarHeight\s*=\s*(\d+)\.dp", (kt_root / "ui/theme/Theme.kt").read_text())
if not bh or int(bh.group(1)) < 48:
    problems.append("Theme.kt: BottomBarHeight must be at least 48 dp (touch target of a bottom-bar tab)")
if "AiHubScreen" not in (kt_root / "ui/screens/AiScreen.kt").read_text():
    problems.append("AiScreen.kt: AiHubScreen is missing")

for w in warnings:
    print("warning:", w)
for p in problems:
    print("PROBLEM:", p)
print(f"check_android_sources: {len(files)} Kotlin files, {len(rows)} string rows x 5 languages: "
      f"{'PROBLEMS FOUND' if problems else 'ok'}" + (f" ({len(warnings)} warnings)" if warnings else ""))
sys.exit(1 if problems else 0)
