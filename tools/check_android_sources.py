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
  9. (Step 58, plan v3.9.5 / v3.9.6) EXACTLY five bottom tabs Home, Short Video, AI, Chat, Profile (in that order since Step 82, plan v5.0.0 section 2); the AI tab opens the AI Hub;
     the Chat list has no AI card / button / shortcut and AI Chat is opened from the hub only; the tab circles are at least 48 dp,
     named for TalkBack and marked as tabs.
  12. (Step 74, plan v3.10.2 Home Feed Content Mix) Home shows photos AND videos in one feed: HomeScreen uses HomeVideoCard + mixHomeRefs and a
     LazyListState; only ONE video is active (pickActiveVideo); the video player starts muted (volume 0) and takes audio focus only when
     the sound is on; it pauses with the app and is released when the card leaves; a video that fails never blocks the photos.
  13. (Step 75, plan v4.0.1) tapping a Home video opens THAT video in the full-screen Short Video player (same list, not a second feed); Back /
     Android Back return to Home WITHOUT a reload (scroll position kept); the opened video and its neighbours are re-checked with the server;
     an unavailable one shows a message and no picture / player; the Short Video tab and the five tabs stay; log out wipes the Home feed.
  14. (Step 76, plan v3.10.4 / v3.10.5 Stage A #38) Photo Editor: PhotoMath.java is pure (no android import) and lists at least 20 filters, every
     filter has a name row in all five languages; the editor screen has the Filters / Adjust / Crop tabs, the strength slider, Reset, the
     discard question and the drag-to-move crop; the Create post page opens the editor after a photo is picked, shows the EDITED photo and
     uploads through loadEditedJpeg (never the old unedited path); the picked file is only read (no output stream on it); EXIF is applied;
     CI runs tools/PhotoMathTest.java (the real maths, compiled and run).
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
if tab_rows != [("Home", "tab_home"), ("Video", "tab_video"), ("Ai", "tab_ai"), ("Chat", "tab_chat"), ("Profile", "tab_profile")]:
    problems.append(f"MainActivity.kt: the bottom bar must have exactly Home, Short Video, AI, Chat, Profile in this order (plan v5.0.0 section 2); found {tab_rows}")
# Step 82 (plan v5.0.0 section 3): Short Video / AI / Chat / Profile are FULL-SCREEN (no bottom bar), each with a top Back arrow, and the phone's Back goes to Home.
if "Tab.Bio" in mk or "tab_bio" in mk:
    problems.append("MainActivity.kt: Bio is not a separate destination any more; the name is Profile (plan v5.0.0 section 2)")
if not re.search(r"if \(!tab\.fullScreen\) BottomBar\(", mk) or "val fullScreen: Boolean get() = this != Home" not in mk:
    problems.append("MainActivity.kt: the bottom bar must be shown on Home ONLY (plan v5.0.0 section 3)")
if not re.search(r"BackHandler\(enabled = stage == Stage\.Ready && overlay == null && tab\.fullScreen\)", mk) or "LocalBottomBarShown provides !tab.fullScreen" not in mk:
    problems.append("MainActivity.kt: the phone's Back on a full-screen tab must return to Home, and pages must give the bar's space back (plan v5.0.0 section 3)")
for _fn, _name in (("ShortVideoScreen(api, shorts", "Short Video"), ("ChatListScreen(api,", "Chat"), ("AiHubScreen(api, aiChat", "AI"), ("BioScreen(me", "Profile")):
    _i = mk.find(_fn)
    if _i < 0 or "onBack = { tab = Tab.Home }" not in mk[_i:_i + 1400]:
        problems.append(f"MainActivity.kt: the {_name} tab needs onBack = {{ tab = Tab.Home }} (top Back arrow, plan v5.0.0 section 3)")
# Step 83 (plan v5.0.0 section 5): ONE Create entry (Home header "+" and the Short Video "+") -> Take Photo / Record Video / Choose from Gallery; photo or video detected automatically.
_hub_p = kt_root / "ui/screens/CreateHubScreen.kt"
_hub = _hub_p.read_text() if _hub_p.exists() else ""
if not all(k in _hub for k in ('tr("take_photo")', 'tr("record_video")', 'tr("choose_gallery")', "ImageAndVideo", 'startsWith("video/")', 'startsWith("image/")', "TakePicture", "CaptureVideo")):
    problems.append("CreateHubScreen.kt: the Create screen needs Take Photo, Record Video and Choose from Gallery (image + video picker) and must detect photo / video itself (plan v5.0.0 section 5)")
if mk.count("onCreate = { overlay = Overlay.Create }") != 2 or "Overlay.NewPost }" in mk or "Overlay.NewShort }" in mk:
    problems.append("MainActivity.kt: the Home header '+' and the Short Video '+' must both open the ONE Create screen, not a photo-only / video-only upload (plan v5.0.0 section 5)")
if "initialUri" not in (kt_root / "ui/screens/SocialScreens.kt").read_text() or "initialUri" not in (kt_root / "ui/screens/ShortVideoScreens.kt").read_text():
    problems.append("the photo and video flows must accept the item chosen in the Create screen (initialUri) (plan v5.0.0 section 5)")
# Step 84 (plan v5.0.0 sections 4 and 6): long captions = a few lines + "See more" / "See less" (display only), above the photo, also on the post detail page and the video card.
_hs = (kt_root / "ui/screens/HomeScreen.kt").read_text()
_ec_p = kt_root / "ui/screens/ExpandableCaption.kt"
_ec = _ec_p.read_text() if _ec_p.exists() else ""
if not all(k in _ec for k in ("fun ExpandableCaption", "hasVisualOverflow", 'tr("read_more")', 'tr("show_less")', "Int.MAX_VALUE", "rememberSaveable(key)")):
    problems.append("ExpandableCaption.kt: the shared caption needs a preview, See more / See less and an in-place expand that keeps its state per post (plan v5.0.0 section 6)")
_pc = _hs[_hs.find("fun PostCard("):]
if "ExpandableCaption(p.caption" not in _pc or _pc.find("ExpandableCaption(p.caption") > _pc.find("AsyncImage(p.imageUrl"):
    problems.append("HomeScreen.kt: the photo post caption must use ExpandableCaption and sit ABOVE the photo (plan v5.0.0 section 6)")
if "ExpandableCaption(v.caption" not in (kt_root / "ui/screens/HomeVideoCard.kt").read_text():
    problems.append("HomeVideoCard.kt: the video card caption must use ExpandableCaption (plan v5.0.0 sections 4 and 6)")
_soc = (kt_root / "ui/screens/SocialScreens.kt").read_text()
if "PostCaptionHeader(post.username, post.caption" not in _soc or "header()" not in _soc:
    problems.append("SocialScreens.kt: the post detail (comments) page must show the post's caption with See more / See less (plan v5.0.0 section 6)")
_sv = (kt_root / "ui/screens/ShortVideoScreens.kt").read_text()
if "onClose ?: onBack" not in _sv:
    problems.append("ShortVideoScreens.kt: the Short Video tab must show the Back arrow and call onBack (plan v5.0.0 section 3)")
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

# ---- 10. design-only redesign colours and profile heading (plan v3.10.2) ----
theme_kt = (kt_root / "ui/theme/Theme.kt").read_text()
if not re.search(r"const val SociaxPurple: Long = 0xFF5838D8\b", theme_kt):
    problems.append("Theme.kt: the primary accent must be purple #5838D8 (plan v3.10.2 section 1)")
if not re.search(r"LavenderFill: Color get\(\) = if \(darkPalette\) [^\n]*else Color\(0xFFF5F3FC\)", theme_kt):
    problems.append("Theme.kt: the light lavender surface must be #F5F3FC (plan v3.10.2 section 1)")
if "var Accent by mutableStateOf(Color(SociaxPurple))" not in theme_kt or "listOf(SociaxPurple," not in theme_kt:
    problems.append("Theme.kt: the default accent and the first accent choice must be SociaxPurple")
if "OldSociaxPurple" not in (kt_root / "data/Prefs.kt").read_text():
    problems.append("Prefs.kt: a phone that still saved the old purple #6C4DF0 must move to the plan purple (other saved accents are kept)")
for f in sorted(kt_root.rglob("*.kt")):
    if re.search(r"6C4DF0", f.read_text(), re.I) and f.name != "Theme.kt":
        problems.append(f"{f.name}: the old purple #6C4DF0 is hard-coded; use SociaxPurple / Accent (plan v3.10.2)")
pv = (kt_root / "ui/screens/ProfileView.kt").read_text()
if "ProfileHeading(tr(\"tab_profile\")" not in pv or "TopBar(" in pv:
    problems.append("ProfileView.kt: another person's profile needs the centered Profile heading (plan v3.10.2, Bio/Profile)")
if "ProfilePostsTab()" not in (kt_root / "ui/screens/OtherScreens.kt").read_text():
    problems.append("OtherScreens.kt: my Profile page must use the shared Posts tab row (plan v3.10.2, Bio/Profile)")
if re.search(r"Tab\.values\(\)", bar) is None or "LavenderFill" not in bar:
    problems.append("MainActivity.kt: the selected bottom-bar tab needs the soft lavender pill (plan v3.10.2 section 3, appearance only)")

# ---- 11. leftover old colours (Step 67, plan v3.10.2: restrained purple, no teal / pink / navy) ----
ad_kt = (kt_root / "ui/screens/AdScreens.kt").read_text()
if re.search(r"2FE0B2", ad_kt, re.I):
    problems.append("AdScreens.kt: the ad call-to-action must use the Sociax purple #5838D8, not the old teal")
story_kt = (kt_root / "ui/screens/StoryScreens.kt").read_text(encoding="utf-8")
if any("sweepGradient" in ln and "EC4899" in ln.upper() for ln in story_kt.splitlines()):
    problems.append("StoryScreens.kt: story rings must be purple only (plan v3.10.2: restrained purple story rings)")
for f in sorted(kt_root.rglob("*.kt")):
    if f.name == "Theme.kt":
        continue
    t = f.read_text(encoding="utf-8")
    if re.search(r"Color\(0x[0-9A-F]{2}0B1220\)", t, re.I):
        problems.append(f"{f.name}: dark-blue overlay Color(0x..0B1220) found; use a neutral black scrim (plan v3.10.2: clean high-contrast overlays)")

# ---- 12. Home feed: photos and videos in one list (Step 74, plan v3.10.2 Home Feed Content Mix) ----
hv_kt = (kt_root / "ui/screens/HomeVideoCard.kt").read_text(encoding="utf-8")
hs_kt = (kt_root / "ui/screens/HomeScreen.kt").read_text(encoding="utf-8")
hf_kt = (kt_root / "ui/screens/HomeFeedState.kt").read_text(encoding="utf-8")
for needle, why in [("HomeVideoCard(", "HomeScreen must show video cards"), ("pickActiveVideo(", "only one video may be active"),
                    ("feed.listState", "HomeScreen must use the kept LazyListState to know which video is in view")]:
    if needle not in hs_kt:
        problems.append(f"HomeScreen.kt: {why} (missing `{needle}`)")
if "mixHomeRefs(" not in hf_kt:
    problems.append("HomeFeedState.kt: photos and videos must be mixed with mixHomeRefs")
for needle, why in [("volume = 0f", "the Home video must start muted"), ("setAudioAttributes(AudioAttributes.DEFAULT, !muted)", "audio focus only while the sound is on"),
                    ("exo.release()", "the player must be released when the card leaves"), ("onPlayerError", "a playback error must not crash the feed"),
                    ("ON_PAUSE", "the video must pause when the app goes to the background")]:
    if needle not in hv_kt:
        problems.append(f"HomeVideoCard.kt: {why} (missing `{needle}`)")
if hv_kt.count("ExoPlayer.Builder(") != 1:
    problems.append("HomeVideoCard.kt: exactly one ExoPlayer is built, inside HomeVideoPlayer")
if re.search(r"playWhenReady\s*=\s*true", hv_kt):
    problems.append("HomeVideoCard.kt: playWhenReady must follow the `playing` flag (only the active card plays), never a fixed true")

# ---- 13. Home video tap -> full-screen Short Video player (Step 75, plan v4.0.1) ----
main_kt = (kt_root / "MainActivity.kt").read_text(encoding="utf-8")
sv_kt = (kt_root / "ui/screens/ShortVideoScreens.kt").read_text(encoding="utf-8")
api_kt = (kt_root / "data/ApiClient.kt").read_text(encoding="utf-8")
for needle, why in [("onOpen(v)", "a tap on the Home video (and the marked full-screen button) must open the full-screen player"),
                    ("a11y_open_fullscreen", "the full-screen button needs a name for TalkBack")]:
    if needle not in hv_kt:
        problems.append(f"HomeVideoCard.kt: {why} (missing `{needle}`)")
if hv_kt.count("onOpen(v)") < 2:
    problems.append("HomeVideoCard.kt: both the video area AND the marked button must call onOpen(v)")
if "LazyListState()" not in hf_kt or "fun canKeep" not in hf_kt:
    problems.append("HomeFeedState.kt: the Home rows and the scroll position must be kept outside the Home screen (LazyListState + canKeep)")
if "Overlay.HomeVideo" not in main_kt or "homeFeed.makeViewer(" not in main_kt:
    problems.append("MainActivity.kt: Home must open the player through Overlay.HomeVideo / homeFeed.makeViewer (the SAME video, from the same list)")
if "if (!inHomeVideo) feedKey++" not in main_kt:
    problems.append("MainActivity.kt: Back from the Home video (or its comments / profile) must NOT bump feedKey (that reloads Home and loses the scroll position)")
if "closeHomeVideo()" not in main_kt or "homeFeed.absorbViewer(" not in main_kt:
    problems.append("MainActivity.kt: leaving the player must apply its changes to the Home rows (absorbViewer)")
if main_kt.count("homeFeed.reset()") < 3:
    problems.append("MainActivity.kt: every log out path must wipe the Home feed (homeFeed.reset())")
if "ShortVideoScreen(api, shorts, ads," not in main_kt:
    problems.append("MainActivity.kt: the Short Video TAB must stay (ShortVideoScreen with the tab's own state)")
for needle, why in [("fun short(id: Long)", "ApiClient.short(id) (server visibility rule) is needed to re-check the opened video")]:
    if needle not in api_kt:
        problems.append(f"ApiClient.kt: {why}")
for needle, why in [("api.short(id)", "the opened video and its neighbours are re-checked with the server"), ("state.unavailable", "an unavailable video must be blocked"),
                    ("short_unavailable", "an unavailable video needs a clear message")]:
    if needle not in sv_kt:
        problems.append(f"ShortVideoScreens.kt: {why} (missing `{needle}`)")
if "blocked" in sv_kt:
    i = sv_kt.index("if (blocked)"); j = sv_kt.index("return", i)
    if "AsyncImage" in sv_kt[i:j] or "ShortPlayer(" in sv_kt[i:j]:
        problems.append("ShortVideoScreens.kt: a blocked page must show no picture and no player (private media is never exposed)")
if sv_kt.count("onClose != null") < 2 and "val viewer = onClose != null" not in sv_kt:
    problems.append("ShortVideoScreens.kt: the full-screen mode (onClose) must have its own Back arrow")

# ---- 14. Photo Editor (Step 76, plan v3.10.4 / v3.10.5 Stage A #38) ----
pm_path = kt_root / "data/PhotoMath.java"
pe_path = kt_root / "data/PhotoEdit.kt"
ed_path = kt_root / "ui/screens/PhotoEditorScreen.kt"
if not (pm_path.exists() and pe_path.exists() and ed_path.exists()):
    problems.append("Photo Editor files missing (PhotoMath.java, PhotoEdit.kt, PhotoEditorScreen.kt)")
else:
    pm_src = pm_path.read_text(encoding="utf-8")
    pe_kt = pe_path.read_text(encoding="utf-8")
    ed_kt = ed_path.read_text(encoding="utf-8")
    ss_kt = (kt_root / "ui/screens/SocialScreens.kt").read_text(encoding="utf-8")
    iu_kt = (kt_root / "data/ImageUtils.kt").read_text(encoding="utf-8")
    main14 = (kt_root / "MainActivity.kt").read_text(encoding="utf-8")
    if re.search(r"^\s*import\s+android\.", pm_src, re.M) or "androidx" in pm_src:
        problems.append("PhotoMath.java must stay pure (no android / androidx import) so CI can run it as a normal program")
    ids_m = re.search(r"FILTER_IDS\s*=\s*\{(.*?)\}\s*;", pm_src, re.S)
    filter_ids = re.findall(r'"([a-z0-9_]+)"', ids_m.group(1)) if ids_m else []
    if len(filter_ids) < 20:
        problems.append(f"PhotoMath.FILTER_IDS must list at least 20 filters (found {len(filter_ids)}); plan v3.10.4 section 2")
    if len(set(filter_ids)) != len(filter_ids) or (filter_ids and filter_ids[0] != "original"):
        problems.append("PhotoMath.FILTER_IDS: ids must be unique and the first must be \"original\"")
    spec_rows = re.findall(r"/\*\s*([a-z0-9_]+)\s*\*/\s*\{", pm_src)
    if spec_rows != filter_ids:
        problems.append("PhotoMath.java: FILTER_SPECS rows must follow FILTER_IDS one by one (same names, same order)")
    for fid in filter_ids:
        if f"filter_{fid}" not in rows:
            problems.append(f"Strings.kt: missing the name row filter_{fid} (every filter needs its name in five languages)")
    for needle, why in [('tr("filter_$id")', "the tray must show the translated filter name"), ("PhotoMath.FILTER_IDS", "the tray must list the real filters"),
                        ("Slider(", "filter strength slider (0-100 %) and the Adjust sliders"), ("editor_intensity", "a visible strength control"),
                        ("editor_reset", "Reset"), ("BackHandler", "Back must ask before throwing edits away"), ("AlertDialog(", "the discard question"),
                        ("detectDragGestures", "drag-to-move crop"), ("PhotoEditorFlag.open = true", "light status-bar icons over the dark editor"),
                        ("EditorTab.Filters", "Filters tab"), ("EditorTab.Adjust", "Adjust tab"), ("EditorTab.Crop", "Crop tab"),
                        ("a11y_rotate_left", "rotate left (named for TalkBack)"), ("a11y_rotate_right", "rotate right"), ("a11y_flip", "flip"),
                        ("Role.Tab", "tabs are named / marked for TalkBack"), ("withContext(Dispatchers.Default)", "thumbnails / turning must not run on the main thread")]:
        if needle not in ed_kt:
            problems.append(f"PhotoEditorScreen.kt: {why} (missing `{needle}`)")
    for needle, why in [("PhotoEditorScreen(", "choosing a photo must open the Photo Editor"), ("EditedPhotoPreview(", "the page must show the EDITED photo"),
                        ("loadEditedJpeg(", "the upload must apply the edits"), ("it.second == chosenEdits", "a remembered upload belongs to one photo AND its edits"),
                        ("editing = true", "picking a photo must open the editor")]:
        if needle not in ss_kt:
            problems.append(f"SocialScreens.kt (Create post): {why} (missing `{needle}`)")
    cp = ss_kt[ss_kt.index("fun CreatePostScreen"):ss_kt.index("// ---------------- Comments")]
    if "loadJpeg(" in cp:
        problems.append("SocialScreens.kt: Create post must not use the unedited loadJpeg path any more (edits would be lost)")
    if cp.count("busy = false; return@launch") < 2:
        problems.append("SocialScreens.kt: a failed export must stop the spinner (busy = false) and keep the draft")
    for name, src in [("PhotoEdit.kt", pe_kt), ("PhotoEditorScreen.kt", ed_kt), ("ImageUtils.kt", iu_kt)]:
        if "openOutputStream" in src or "DocumentsContract.deleteDocument" in src:
            problems.append(f"{name}: the picked photo must only be READ (non-destructive editing), never written or deleted")
    for needle, why in [("ExifInterface", "the camera's EXIF turn must be applied (a portrait photo must not be saved sideways)"), ("PhotoMath.exifTransform", "EXIF turn via the tested table"),
                        ("fun loadEditedJpeg", "export of the edited photo"), ("Dispatchers.Default", "export runs off the main thread"),
                        ("check(coloured.compress", "a failed JPEG save must be an error, not a silent empty file")]:
        if needle not in pe_kt:
            problems.append(f"PhotoEdit.kt: {why} (missing `{needle}`)")
    if "decodeUpright(" not in iu_kt:
        problems.append("ImageUtils.kt: loadJpeg must read photos upright through decodeUpright (profile / story / post)")
    if "PhotoEditorFlag.open" not in main14:
        problems.append("MainActivity.kt: the status-bar icons must turn light while the photo editor is open (PhotoEditorFlag)")
    wf = pathlib.Path(".github/workflows/build.yml")
    if wf.exists():
        wf_txt = wf.read_text(encoding="utf-8")
        if "javac" not in wf_txt or "java -cp /tmp/pm PhotoMathTest" not in wf_txt:
            problems.append("build.yml: CI must compile AND run tools/PhotoMathTest.java (the real filter / crop / EXIF maths)")

for w in warnings:
    print("warning:", w)
for p in problems:
    print("PROBLEM:", p)
print(f"check_android_sources: {len(files)} Kotlin files, {len(rows)} string rows x 5 languages: "
      f"{'PROBLEMS FOUND' if problems else 'ok'}" + (f" ({len(warnings)} warnings)" if warnings else ""))
sys.exit(1 if problems else 0)
