# Sociax

Android (Kotlin + Jetpack Compose) + FastAPI backend, built from the Sociax Master Plan (now v3.9.1; v3.2 + v3.3 replaced the older pricing and media details, v3.9.1 removed the Short Video duration limit).

## Step 1 setup: Google login (Firebase)
1. Firebase Console: create a project, add Android app with package `com.sociax.app`.
2. Add your SHA-1 (Android Studio: Gradle > app > Tasks > android > signingReport).
3. Authentication > Sign-in method > enable **Google**.
4. Download `google-services.json` and put it in `app/` (build then applies the Firebase plugin automatically).
5. Backend needs a service account: Project settings > Service accounts > Generate key, then
   `export GOOGLE_APPLICATION_CREDENTIALS=/path/to/key.json`

## Step 2 setup: Cloudinary (profile photos)
1. Create a free Cloudinary account. In the dashboard copy Cloud name, API Key, API Secret.
2. Set them on the backend only (never in the app):
   `export CLOUDINARY_CLOUD_NAME=... CLOUDINARY_API_KEY=... CLOUDINARY_API_SECRET=...`
3. Flow: app asks backend for a signed upload (size and quota checked) -> app uploads to Cloudinary -> backend verifies the asset itself and saves the photo.
   Photos are shrunk on the phone (max 1024 px) and limited to 5 MB; the 150 MB free media quota is tracked on the server.

## Step 3 setup: AI (Grok leads, Gemini assists, Claude is the third provider) + secrets file
1. Copy `backend/.env.example` to `backend/.env` and paste your own keys (xAI, Gemini, Cloudinary, Chapa test key). `.env` is in `.gitignore`: never upload it. On Render, use its environment settings instead.
2. Keys stay on the server only. The Android app never contains them.
3. Default order: Grok answers first; if Grok is missing or fails, Gemini answers; if that fails too, Claude answers. Check: `GET /ai/status` shows which providers are connected (`grok`, `gemini`, `claude`).
   Claude needs `ANTHROPIC_API_KEY` (and optionally `ANTHROPIC_MODEL`, default `claude-sonnet-5-5`). To let Claude answer first set `AI_PROVIDER_ORDER=claude,grok,gemini`.
4. Free allowance: `FREE_AI_REQUESTS` per 48 hours (10 is a TEMPORARY testing value; the plan has not decided the number). Counted on the server.

## Step 41 setup: AdMob (ads for Free accounts)
1. Nothing is needed to test: Google's TEST ads are built in (no income). Real ads: AdMob account > add the Android app `com.sociax.app` > create a **Native** ad unit.
2. GitHub > Settings > Secrets and variables > Actions > **Variables**: `SOCIAX_ADMOB_APP_ID` and `SOCIAX_ADMOB_NATIVE_UNIT_ID`, then run the build again.
3. Optional backend setting: `AD_DAY_UTC_OFFSET_MINUTES` moves the daily reset (default 00:00 UTC).

## Run backend
```
cd backend
pip install -r requirements.txt
uvicorn main:app --reload --host 0.0.0.0
```
Testing without Firebase: `DEV_AUTH=1 uvicorn main:app --reload` accepts tokens like `dev-abc` (never use in production).
The emulator reaches your computer at http://10.0.2.2:8000. For a real phone set `SOCIAX_API_URL` (read by app/build.gradle.kts) to your deployed backend address.

## Run Android
Open this folder in Android Studio, sync, run.

## Status
Done:
- Step 53 (v0.58.0) UNLIMITED SHORT VIDEO DURATION + PUBLISHING NEVER FAILS BECAUSE THE LENGTH CANNOT BE READ (plan v3.9.1, whole addendum). Android app + backend + tests.
  * The fixed 60-second cap is GONE everywhere (it replaces the 25 s / 60 s rules of Steps 31 and 36, which are kept below only as history). App: no trim screen, no "must be trimmed" error, no `EXTRA_DURATION_LIMIT` on the camera, no length sent to the server; the picked video is compressed as a whole and shown in the preview. It is NEVER cut or shortened by the app. Every "max 60 s" text (Short Video note, + button description, Profile note, empty-feed text) was changed in all five languages and the five now-unused trim texts were deleted.
  * The length is OPTIONAL information. The app no longer reads the length of the picked video before publishing (the old "Cannot read this video. Try another one." wall is gone). After compressing it only reads the length to SHOW it (h:mm:ss); if that fails nothing is blocked and nothing is shown. A length is never invented: unknown stays unknown, in the app (`ShortVideo.duration` is `Double?`), in the API (`"duration": null`) and in the database (NULL, never 0).
  * Backend: no duration check any more in `POST /shorts` or `POST /media/validate-short` (`duration_seconds` is optional there); `chosen_seconds` from OLD apps is accepted and ignored. If Cloudinary does not know the length yet, the clip is still published (length NULL) and a small background job asks Cloudinary again after 15 s, 60 s and 180 s and fills it in only if it is still NULL (`DURATION_BACKFILL_DELAYS`). A failure of that job changes nothing. Existing databases are upgraded ONCE at start-up: `shorts.duration` was NOT NULL, so `init_db()` rebuilds that one table in a single transaction (all rows, ids, the id counter and the index are kept; a second start does nothing).
  * Ranking and events with an unknown length: a "complete" event still counts; a watched / skipped fraction cannot be computed without a length, so those events say nothing (`ranker.watch_ratio`), and the "impossible watch time" test is skipped (nothing to compare with). A known length still catches fake events.
  * STILL CHECKED, each on its own (duration is not a substitute for any of them): login, file type (mp4 / mov / webm / 3gp / mkv), real file size from Cloudinary (one clip: max 100 MB, `SHORT_MAX_BYTES`; the plan names no per-file value, so I did NOT raise it), the daily media quota (500 MB Free / 1.5 GB Pro), ownership of the upload, privacy / audience. "Published" is shown only after BOTH the Cloudinary upload and the server record succeeded; a retry of the same upload returns the same post (no duplicate, quota counted once). A refused clip is deleted from Cloudinary and makes no post.
  * IMPORTANT, honest consequence: with no duration cap, the 100 MB file-size cap and Cloudinary's own limits are now the real limit. At the "standard" upload quality (about 19 MB per minute) 100 MB is roughly 5 minutes. A larger file is refused with "Video must be under 100 MB". If you want longer videos, the per-file cap (`SHORT_MAX_BYTES`) and your Cloudinary plan limit must be raised on purpose; that is a decision for you, not something the plan fixes.
  * Tests: `test_unknown_duration.py` (new: old-database upgrade, 61 s / 10 min / 1 h / 24 h clips, unknown length stored as NULL and returned as null, background fill-in, size / type / quota / ownership still refuse, no duplicate on retry, events + ranking with an unknown length); `test_shorts.py` and `test_reliability.py` were changed to the new rules (they used to expect 422 for "longer than 60 s" and for "unreadable length"). CI runs every `test_*.py`.
  * NOT DONE / NOT TESTED HERE (the plan says do not claim it): nothing was compiled and no test was run in the place where this step was written (no Android SDK, no Python packages, no internet). The ONLY thing run for real was the database upgrade on an old-format database (rows, ids, id counter, index, a restricted row, NULL insert, second run). Please build on GitHub, then try on a real phone: a short video, a long one (more than a minute, under 100 MB), a video from the gallery, one recorded with the camera, one on a weak network (airplane mode in the middle of the upload, then retry), and watch it from a second account. Send me the first error if the build or a test fails.
- Step 52 (v0.57.0) SPEED REPORT ON THE PHONE: the measurement the plan requires BEFORE any C++ (plan v3.4 section 18 and 21: "native code only for a measured CPU-heavy need ... compare quality, speed, memory, battery"; v3.9 section 37: "establish baseline metrics ... before tuning"). Android app only (the backend only moved its version number). No C++ was added on purpose: there is still no number that says it is needed.
  * What is measured on THIS phone, in memory only (newest 30 per name, never written to disk, never sent by the app): `photo_shrink_ms` (picking a photo for profile / story / post: decode + shrink + JPEG), `video_export_ms` and `video_export_ms_per_10s_of_video` (the Media3 trim + compress of a Short Video), plus the start-up marks that already existed (`startup_screen_shown`, `login_screen_shown`, `home_ready`, `login_to_ready`).
  * Where to read it: Settings > "Data & accessibility" > "Speed report" > "Show speed report" > "Copy report". It prints the phone model, Android version and `n / min / median / max` per name: numbers only (no account, name, file name or address). The report text itself is in English on purpose (it is meant to be pasted to me); the buttons and the note are in all five languages.
  * What YOU do: build on GitHub, install, pick 3 photos (profile, story, post), export 2 videos (one short, one longer than a minute), open the Speed report, copy it and send it to me. Do it on the weakest phone you have (a low-memory phone is the honest test, plan section 37).
  * How I will decide on C++ (my suggestion, the plan names no number): Android already does photo decoding / scaling / JPEG in native code and the video export in the phone's hardware encoder through Media3, so a C++ copy is unlikely to be faster. Only if the median of one of these is high enough that a person waits visibly (for example photo shrink well above half a second, or a 60 s video needing minutes) and the time is spent in OUR code and not in the phone's codec, I will build ONE narrow native function behind a Kotlin fallback and compare it with the Kotlin one on the same phone.
  * HONEST LIMITS: (1) NOT compiled here (no Android SDK): build on GitHub and send me the first error if it fails. (2) The numbers show the time of the whole operation; they do not say whether the time went into the codec or into our code: that needs a closer look only if the numbers are high. (3) Server numbers stay in `GET /admin/metrics` (Step 47); the engine's in `tools/check_engine_deploy.py` (Step 49).
- Step 51 (v0.56.0) FIX: "server problem" right after choosing the Google account (screenshot of the Render log + the phone message). WHAT I FOUND (the log shows one `GET /users/me 200 OK` from Oct 20, so the server itself can answer; the failing step is the first call after the account chooser, `routeAfterLogin`). Three real causes could give exactly that message, and all three are fixed or made visible:
  * (1) The APK was built WITHOUT the server address and points to `http://10.0.2.2:8000` (only the emulator). `build.yml` read ONLY the GitHub *Variable* `SOCIAX_API_URL`: if it was saved as a *Secret* (easy mistake) it was empty. Now Variable OR Secret is accepted (`vars.SOCIAX_API_URL || secrets.SOCIAX_API_URL`), and `app/build.gradle.kts` no longer THROWS AWAY an address typed without `https://` (it adds it). In the app, such an APK now says clearly "this app was built without the server address ... set SOCIAX_API_URL" (5 languages) instead of "server may be waking up".
  * (2) The free Render server was asleep: it answers 502/503 or is slow while it wakes (the purple banner "free instance will spin down ... 50 seconds or more"). The app now retries BY ITSELF 3 times (8 s and 20 s apart, on top of the 30 s / 60 s waits) while showing "Waking up the Sociax server...", before it shows an error.
  * (3) The server could not check Google sign-ins (Firebase service-account secret file missing or wrong on Render). That used to answer 401 "Invalid or expired token" for EVERYONE; now it answers 503 and writes a clear line in the Render log (`firebase sign-in check unavailable ... is the secret file /etc/secrets/firebase-service-account.json set?`). Real bad / expired tokens still answer 401.
  * The error text now ends with what failed and which address the app uses, for example `[HTTP 503 · sociax-backend.onrender.com]` or `[SocketTimeoutException · ...]` or `[UnknownHostException · ...]`: one screenshot of it tells us the exact cause.
  * Checks: `cd backend && DEV_AUTH=1 python test_auth_errors.py` (new; ran here against `current_user` alone: bad token 401, Google keys unreachable 503, service account missing 503, test tokens still work), `tools/check_android_sources.py` (444 string rows x 5 languages: ok).
  * HONEST LIMITS: (1) I cannot see your Render or GitHub settings, so I do not KNOW which of the three causes you have: the new message will say. (2) The Android code was NOT compiled here (no Android SDK): build on GitHub and send me the first error if it fails. (3) The full backend test files that need the real FastAPI were not run here (only the new test's logic, in isolation); GitHub runs them. (4) If the message ends with `HTTP 503` after 3 tries, look at the Render log for the line about the Firebase secret file; if it says `UnknownHostException` the address in `SOCIAX_API_URL` is misspelled.
- Step 50 (v0.55.0) GO ENGINE SWITCHED ON IN `render.yaml` (deployment start). Only configuration: no Go, Python or app code changed (the app and backend only moved their version number). The `sociax-engine` service block is no longer commented out (validated as YAML: services `sociax-backend` and `sociax-engine`, runtime go, rootDir `backend-go`, health check `/healthz`, secret `ENGINE_TOKEN` asked by Render, never written in the file). `RANK_ENGINE_URL` / `RANK_ENGINE_TOKEN` of the Python backend stay EMPTY until `tools/check_engine_deploy.py` passes, so the feed does not change until you connect it on purpose.
  * What YOU do (I cannot reach your Render account): push, Blueprints > Sync, give `ENGINE_TOKEN`, run the checker, then set the two values on `sociax-backend` (exact steps: `backend-go/README.md` > "Deploy it and check it").
  * HONEST LIMITS: (1) NOT deployed yet and NOT tested on Render: whether Render's free Go build works with `go.mod` (`go 1.22`) is unknown until it builds; send me the first error from the Render log if it fails. (2) A free service sleeps: the first call after a sleep can take up to a minute and the backend then ranks by itself (the circuit breaker skips the engine for 30 s after 3 failures). (3) Because it is a second service, the engine adds one network hop: judge with `GET /admin/metrics` (group `feed`) and the checker's timing, not with the 7 ms localhost number.
- Step 49 (v0.54.0) GO SERVICE FINISHED FOR DEPLOYMENT: a checker for the running engine (plan v3.5 section 26 / v3.9: "verify ... deployment, and monitoring before migrating more work"). No phone-app change (only the version number) and the ranking code of Step 48 is unchanged.
  * New `tools/check_engine_deploy.py` (standard library only): `ENGINE_TOKEN=<secret> python tools/check_engine_deploy.py https://<engine>.onrender.com`. It checks health / ready answers, that a missing or wrong secret gets 401, that a saved list of candidates comes back in exactly the Python order, that broken JSON / a repeated id / a negative limit are refused, that `/v1/stats` shows no creator or id, and prints the cold-start time and min / median / p95 / max round-trip time from where you run it. The secret is read from the environment only and never printed.
  * `--backend <python address>` also reads `/health/config` and checks `rank_engine.configured`. `--self-test` runs the checker against one good fake and three broken fakes (wrong order, no secret needed, leaking stats): it PASSES the good one and FAILS each broken one (run here: all four verdicts right).
  * GitHub job "Go engine checks" now also (1) runs that self-test and (2) starts the REAL built Go service and runs the checker against it, then fails if the secret appears in the service log. `backend-go/README.md` has the 4-step deploy guide.
  * HONEST LIMITS: (1) The Go toolchain was NOT available in my workspace this time (no internet), so no Go code was changed and `go test` was not re-run; the real-service check runs for the first time on GitHub: if that job is red, send me the first error. (2) The engine is still NOT deployed on Render: that needs your Render account (steps in `backend-go/README.md`). Until it is, Python ranks as before. (3) Go chat / search (plan section 17) is still waiting for a database decision (one SQLite file inside the Python service cannot be shared with a second service: PostgreSQL, or Go inside the same service). (4) C++ is NOT added: no measured need exists (photo shrinking uses Android's own decoder; ranking is already fast in Go). It will be added only after a measurement on a real phone points to one operation.
- Step 48 (v0.53.0) FIRST GO SERVICE: the For You ranking engine (plan v3.4 sections 16-23, v3.9 section 30; roadmap Phase F "add Go ... only where measured"). Server side only: the Android app only moved its version number. New folder `backend-go/` (see `backend-go/README.md`).
  * What it is: a small separate Go program (standard library only) with `POST /v1/rank`. The Python backend still does ALL the privacy filtering (VISIBLE, blocks, hidden, reported, followers-only ...) and then sends the remaining candidates to Go to be put in order. `backend/rank_engine.py` is the client. The Go ranker is a line-by-line port of `ranker.py` (same weights 30/20/15/15/10/10, freshness, engagement, interest, watch quality, repeat / quality penalties, diversity, exploration slot every 5th place, at most 2 in a row from one creator, ties to the newer item).
  * Safety rules: (1) the engine can only REORDER: Python accepts the answer only if it is exactly the candidates it sent (nothing added, nothing missing, no repeats), otherwise it ranks them itself; (2) engine off / not configured (default) / down / slow (800 ms) / wrong secret / wrong answer = Python ranks as before, the feed never fails because of it; (3) 3 failures in a row = engine skipped for 30 s (a sleeping service never slows the feed); (4) the engine sees numbers and anonymous creator labels only (`c0`, `c1`), never a user id, username, caption or media name; (5) the shared secret (`ENGINE_TOKEN` = `RANK_ENGINE_TOKEN`, 24+ characters) is checked in constant time, the service refuses to start without it, and no log line contains a secret, id or creator; (6) the weights are sent by Python on each request, so there is ONE place to configure them (`RANK_WEIGHTS`).
  * Turn it on: deploy `backend-go/` (render.yaml has the service block, commented out), then on the Python backend set `RANK_ENGINE_URL` and `RANK_ENGINE_TOKEN`. Check `GET /health/config` > `rank_engine`. Turn it off: empty `RANK_ENGINE_URL`.
  * Checks: Go: `cd backend-go && go test ./... -race` (parity with Python on 182 saved lists incl. ties, exploration, one-creator and 300-item lists; mutation-checked: changing a constant makes it fail; server: secret, validation, limits, busy, logs, stats). Python: `cd backend && python test_rank_engine.py` (fake transport: 10 kinds of bad answers, failures, circuit breaker, no secret / address in logs; and, when `go` is installed, the REAL service: 60 random lists identical to `ranker.py`, wrong secret, stopped service, clean stop) and `DEV_AUTH=1 python test_rank_engine_feed.py` (inside the real feed: an engine that names a forbidden post is ignored; nothing forbidden is ever sent). `python tools/gen_rank_parity.py --check` (CI) fails when `ranker.py` changes without the Go side. GitHub: new job "Go engine checks" (gofmt, vet, race tests, build) and the Go toolchain in the backend job.
  * Also fixed: `test_events.py` and `test_push.py` built a bad request body outside the helper, so with the REAL pydantic they crashed instead of counting 422 (this would have turned the GitHub backend job red). Same fix as the other tests.
  * MEASURED (this sandbox: 2 CPU, localhost, no database; `python tools/bench_rank.py`): 300 candidates (one real request): Python `ranker.py` 9.8 ms; Go ranking alone 0.29 ms; Python asking the Go service over localhost (JSON + HTTP + answer) 3.0 ms. 2000 candidates: Python 429 ms, Go 9.3 ms (24 ms with HTTP). The first Go version took 1.2 ms for 300; after profiling the inner loop (no maps / closures) it takes 0.29 ms and gives identical orders.
  * HONEST LIMITS: (1) The saving is real on localhost but a REAL network hop between two Render services (plus the free plan sleeping) can cost more than the 7 ms saved at 300 candidates: measure with `GET /admin/metrics` (group `feed`) before and after turning it on, then decide. The ranking is also only a small part of a feed request (the SQL comes first). Nothing was done to raise `RANK_POOL` (300). (2) NOT deployed on Render and NOT run against the real Android app here; the Go build was only run in my sandbox (Go 1.24, `go.mod` says 1.22). (3) The plan says to start with chat or search; I started with the ranker on purpose: it is pure code, and search / chat need the database, which is one SQLite file inside the Python service (two Render services cannot share it). A Go search or chat service needs a decision first: move to a shared database (for example PostgreSQL), or run Go inside the same service. Say which you prefer. (4) Go tests ran here, but the Python tests ran with small stand-in `fastapi` / `httpx` modules (no internet in my workspace, real pydantic); the GitHub job runs them with the real packages. All 35 backend check files passed here (with those stand-ins); the new `test_rank_engine.py` passes without the `httpx` stand-in and uses the real `httpx` on GitHub (that code path was NOT run here). (5) C++ is NOT part of this step: it comes next, only for a measured need.
- Step 47 (v0.52.0) BASELINE MEASUREMENTS on the server (plan v3.9 section 37: "measure feed API latency, search latency, chat delivery, upload success, backend error rate ... establish baseline metrics before tuning. Do not claim 'fastest' without benchmark results"; Phase F "monitor quality and latency"). BACKEND ONLY (the app only moved its version number).
  * What is measured: every request's time and status, grouped by ROUTE PATTERN only (e.g. `/chats/{conv_id}/messages`, never a real address, username, id, query or token). Groups = the plan's list: feed, search, chat, upload, ai, other. For each group and route: count, 4xx, 5xx, p50 / p95 / p99 (the newest 500 timings per route), max. A crash inside a request is counted as a 500 and still reaches the caller. Health checks and the metrics page itself are not counted. Memory is bounded (300 route patterns, 500 timings each). Nothing is written to disk; the numbers start at 0 when the server restarts. (`backend/metrics.py`)
  * How to read it (owner only): set `METRICS_TOKEN` on the server (16+ random characters), then `python tools/read_metrics.py https://YOUR-SERVER YOUR_METRICS_TOKEN` prints a table (or call `GET /admin/metrics` with header `X-Metrics-Token`). Without the secret, with a short one or a wrong one, the page answers 404 exactly like a page that does not exist; guesses are limited to 60 per minute. It also shows how many pushes are waiting (Step 46 queue).
  * Checks: `cd backend && DEV_AUTH=1 python test_metrics.py` (new, 27 checks: percentiles, groups, error rates, bounded memory, only patterns stored, middleware records answers / crashes but not health, access rules, the reader tool).
  * HONEST LIMITS: (1) The real middleware ran only against a fake request here (Starlette is not installed in my sandbox); `test_metrics.py` calls it directly, GitHub runs the same file with the real packages. (2) These are SERVER numbers only. The plan also names time to first video, playback start, rebuffering and crash rate: those happen on the phone, need an app-side reporter and a privacy decision (what the phone may send), and are NOT built; the app still prints its own start-up times to Logcat (`SociaxPerf`). (3) "Chat delivery" is the time of the send / read requests, not the time until the other person SEES the message (the app polls every 2.5 s). (4) "Upload success" = answers of the upload address requests; the upload itself goes straight to Cloudinary and is not seen by the server. (5) "Per-user AI / media cost" is NOT in this page (no cost per user is stored; adding it needs a decision about what to keep). (6) One server process only: several processes each count their own requests. (7) A baseline is NOT a target: nothing here says the app is fast, only how fast it was on the day you read it, on your hosting.
- Step 46 (v0.51.0) PUSH NOTIFICATIONS through Firebase Cloud Messaging (plan v3.9 section 20: "call Firebase Cloud Messaging (FCM) for push notifications where appropriate"; the 3:00 AM / 3:00 PM alarms stay local and do not use any of this). Server + app.
  * Rule: push is OPT-IN per phone (Settings > Notifications > "Push on this phone"). It uses the SAME decision as the bell list: a push is queued only where a bell notification is written (group switched on, not blocked, not my own action) and only for an account with a registered phone. The server sends a small DATA message: type, @actor, item number, count. NO message text, caption or photo ever travels through push. The phone builds the sentence in the person's language from the same texts as the bell list; the same chat / item replaces its older notification ("3 new messages" is ONE).
  * Server: tables `push_tokens` (5 phones per account, newest kept; a token that is registered by another account MOVES to it) and `push_outbox`; `POST /push/token`, `POST /push/token/remove` (only my own token); `notify()` queues the row in the same database transaction as the bell row; `push_deliver_once()` sends: rows are claimed first (locked 60 s) so two workers never send twice, a failure is retried after 30 s and 60 s (3 tries), a push older than 10 minutes is dropped, dead tokens are removed, removing the last phone clears the queue, deleting the account removes tokens and queue. The real sender `_fcm_send` uses firebase-admin (already used for login) with high priority and a 1 hour lifetime.
  * HOW TO TURN IT ON (server): set `PUSH_ENABLED=1` next to the existing Firebase credentials (`GOOGLE_APPLICATION_CREDENTIALS`, real login, not DEV_AUTH). It starts one small background thread. Use ONE server process (several processes are safe thanks to the claim, but waste work). Firebase Console: Cloud Messaging API (V1) must be enabled for the project (it is by default for new projects).
  * App: `firebase-messaging-ktx`; `data/Push.kt` (PushRegistrar, PushNotifier, SociaxMessagingService); Android 13+ permission is asked ONLY when the person turns the switch On; on every start while signed in the current token is registered again; Log out first asks the server to forget this phone (3 s at most), and the phone shows no push after sign-out.
  * Checks: `cd backend && DEV_AUTH=1 python test_push.py` (new, 38 checks: tokens, moving between accounts, limit of 5, queue rules, no message text anywhere, delivery / retry / drop / dead token, account deletion). `check_android_sources` and `check_api_contract` (84 app addresses) pass.
  * HONEST LIMITS: (1) NEVER SENT A REAL PUSH: the real sender (`_fcm_send`) and the whole Android side were NOT run here (no Firebase credentials, no phone, Android SDK cannot run in my sandbox, `firebase-admin` is not installed here). Everything around the sender is tested with a fake sender. First real test: turn the switch On on phone 1, send a message from phone 2 with phone 1's app CLOSED, and send me the server log if nothing arrives.
    (2) Needs the Firebase project to allow FCM and the server to have `PUSH_ENABLED=1`; without it the switch can be turned On but nothing will arrive. (3) Tapping a push only opens the app (no jump to the exact chat / post yet). (4) A "3 new messages" push is built from a count: if the first push is lost, the next still says the right number. (5) Phones that Android puts in deep battery saving may delay data messages; Android decides, not us. (6) The plan says Go workers "if load tests justify it": this is one Python thread, enough for a small database; move it to a worker only after measured numbers.
    (7) Logging out while offline cannot tell the server: this phone then shows nothing (the switch is switched Off locally) and the server drops the dead token the next time Firebase says it is gone. (8) Not done for push: per-chat mute, quiet hours, iOS.
- Step 45 (v0.50.0) Search made to the plan's search rules (plan sections 19, 33, 40: "validate input, limit result counts, paginate results", "exact @username first, then relevant partial matches", tests for pagination). Server + app.
  * Server `GET /search?q=...&type=users|posts|shorts&offset=N`: the text is validated (50 characters at most, no control characters, unknown `type` refused: 422); every list is paged 20 at a time and the answer has `next` = {users, posts, shorts} (the offset of the next page, null = no more; nothing deeper than 200).
    People are ranked: exact @username, then username starts with it, then a name that starts with it / has a word that starts with it, then "contains"; ties by username. Posts and short videos: a caption where a WORD starts with the text ranks before one that only has it inside a word, newest first inside each group.
    BUG FIXED: `_` and `%` used to be deleted from the text, so searching the username "my_ab" really searched "myab"; they are now literal characters (LIKE ... ESCAPE).
    Unchanged: private accounts' posts, followers-only posts, blocked people (both directions) never appear; groups are never searchable.
  * App: the search screen shows "Load more" under People, Posts and Short videos when the server says there is more (appends, never shows an item twice); the search field takes 50 characters at most. The old answer is still readable by older apps (only a new `next` key was added).
  * Checks: `cd backend && DEV_AUTH=1 python test_search.py` (new, 29 checks: ranking, underscore, % not a wildcard, blocked both ways, pagination of all three lists with no overlap / no gaps, depth limit, validation, private account / followers-only / private group not found). `test_search_shorts.py` and `test_stories.py` were updated for the new `next` key.
  * HONEST LIMITS: (1) NOT COMPILED HERE (Android): GitHub does the real build: send me the first error if it fails. The backend tests ran with small stand-ins for FastAPI / pydantic (the real ones cannot be installed here); `test_stories.py` needs the real pydantic for its caption-limit check, so it was not fully run here; GitHub runs it.
    (2) Ranking uses text match + recency only. The plan also names "trustworthy engagement signals": likes / comments are NOT used yet, because mixing them in changes the paging order and needs a measured test with real data first.
    (3) Search is still a SQL LIKE scan (no separate search index): fine for a young database, slow when there are millions of posts: the plan says add an index / Go service only after measured numbers. (4) People are found by @username and display name only; accents / non-English letters are matched as written (SQLite lowercases ASCII only). (5) Not tried on a phone: typing fast, then tapping "Load more", then changing the text should be tested.
- Step 44 (v0.49.0) Settings > "Data & accessibility" (plan section 9: "Data/accessibility") + screen-reader labels in five languages (plan v3.5 section 24: "test ... accessibility labels"). APP ONLY: the backend only moved its version number.
  * Accessibility labels: the 32 button / picture descriptions that screen readers (TalkBack) read were hard-coded English ("Back", "Send", "More", "Profile photo" ...). They now come from the same translation table as the rest of the app (18 new keys x 5 languages), so TalkBack follows the app language. Pictures that show a user's own caption keep that caption; the logo keeps the brand name.
  * Data: Settings shows how much temporary data this phone keeps and "Clear temporary files". It removes ONLY Coil's picture cache and leftover temporary video files this app made itself in its cache folder that are over one hour old (an upload in progress is never touched). Nothing on the server, no account, posts, saved items, settings, App Lock or sign-in is touched. (`data/CacheTools.kt`)
  * Accessibility block also says where the existing choices are (text size + Reduce Motion in Appearance; Data Saver in Short Video). No new switch was invented.
  * HONEST LIMITS: (1) NOT COMPILED HERE (Android SDK / Gradle cannot run in my sandbox): the Coil cache calls (`imageLoader.diskCache?.size / clear()`) in particular are written from the Coil 2.6.0 API and checked only by the source sanity check; GitHub does the real build: send me the first error if it fails.
    (2) Many small icons (e.g. the like / comment buttons, tick marks) have no label at all (decorative or not yet described); this step translated the labels that EXISTED, it did not audit every control. (3) NOT tested with TalkBack on a real phone; text wrapping of the longer new texts in Russian / Korean has not been looked at on a real phone.
    (4) The size shown is the picture cache + old temp files only; Android's own "App info > Storage" number will be larger (it includes the app itself and downloaded data). (5) Backend tests: the ones that need the real pydantic / starlette (`test_ads`, `test_language`, `test_stories`, `test_reliability`) cannot run in my sandbox and were not re-run (the backend only changed its version number); GitHub runs them.
- Step 43 (v0.48.0) Settings > "Chat" block with READ RECEIPTS (plan section 9: "Chat: read receipts, media auto-download, group management"). Server + app.
  * Rule: a one-to-one chat shows "Seen" under the NEWEST message of mine the other person has read. It is reciprocal, exactly like Online / last seen: if I switch it off, nobody sees when I read AND I do not see when others read mine. Default: ON. Groups never show it.
  * Server: `users.read_receipts` (PATCH `/users/me` with `read_receipts`, also returned by `GET /users/me`); `conv_members.receipt_read_id` = the newest message this person has SHOWN as read. It is written ONLY while the reader's switch is on (one-to-one chats only) and never goes backwards,
    so switching it on later does NOT reveal what was read while it was off; switching it off clears it. `GET /chats/{id}/messages` now also answers `peer_read_id` (null = receipts not visible: group, blocked, or either switch off). The unread badge (`last_read_id`) is separate and unaffected by the switch.
    A "Follow me first" message that is released later is not "Seen" until the recipient really reads it. Old databases are upgraded by themselves (tested by opening a real v0.47.0 database with the new code).
  * App: the Conversation screen reads `peer_read_id` every poll and shows "Seen" (5 languages) under the newest read message of mine. A chat is only marked read while the screen is really on screen (not in the background / locked phone); it is marked when the app comes back. New Settings > Chat block with the switch; Group management is not copied (it stays in each group's Group info).
  * Checks: `cd backend && DEV_AUTH=1 python test_read_receipts.py` (new, 28 checks: default, forward-only, reciprocal, off clears, read-while-off never revealed, groups, blocked, follow-first release). `tools/check_android_sources.py` and `tools/check_api_contract.py` pass (82 app addresses).
  * HONEST LIMITS: (1) NOT COMPILED HERE: the Android SDK / Gradle cannot run in my sandbox; GitHub does the real build: send me the first error if it fails. The backend ran against small stand-ins for FastAPI / pydantic (real ones cannot be installed here): rules and database are tested, the HTTP layer and pydantic validation are not.
    (2) "Media auto-download" is NOT built: chat has text only (no photos / videos), so a switch would change nothing. It comes with chat media. (3) "Seen" shows for one-to-one chats only; group read receipts are not built (they would show who read what to the whole group).
    (4) Messages read before this version were never recorded as shown, so they do not show "Seen". (5) Still not built from section 9: Data / accessibility block (data usage, accessibility). (6) Needs a real test on TWO phones: send, open on the other phone, check "Seen" appears; switch it off on one phone and check it disappears on both.
- Step 42 (v0.47.0) Settings > "Sociax AI" block (plan section 9: "Sociax AI: quota/reset, privacy, providers, group AI permission, Pro upgrade"). APP ONLY: the backend only moved its version number (it already had `GET /quota/ai` and `GET /ai/status`).
  * Shows: how many AI requests are used of the limit and the reset time (the numbers the SERVER counts; with Pro the limit is 7x), which AI providers the server has connected (names only, never keys; "none connected" and "cannot check" are said plainly), the privacy rule (AI never reads private one-to-one chats; it sees only what you type to it or the one @AI message in a group),
    the group rule (AI is OFF in every group; only the creator / an admin turns it on in Group info > AI Join; members are told; it answers only on @AI), and a Pro line. Five languages (8 new rows). It is information only: no new switch, nothing about who can read what changed.
  * "Pro upgrade": the Plans block right above already has the buy button, so this block points to it instead of a second price offer (plan: only one Pro offer).
  * HONEST LIMITS: (1) NOT COMPILED HERE (same as before): GitHub builds it; send me the first error if it fails. (2) The list of providers includes Claude because Step 29 connected it; the plan's first version said only Grok + Gemini, so tell me if you want Claude hidden here.
    (3) Still NOT built from section 9: Chat settings (read receipts; media auto-download) and a data / accessibility block. Read receipts need a message-read record per person plus a privacy switch (both sides must agree) - that is the next real piece of work.
    (4) Step 41 (AdMob) is still not tested on a real phone.
- Step 41 (v0.46.0) AdMob ads in the Short Video feed, Free accounts only, at most 20 COUNTED ads per day (plan v3.8 section 28, uploaded as Sociax_Master_Plan_Version_3_8_AdMob_Feed_Ads.docx).
  * The SERVER decides and counts (never the phone): `GET /ads/status` (eligible? counted today, left, when the day resets, spacing 3-4 videos, `minor`) and `POST /ads/impression` (one counted impression per key; the same key on a retry counts once; never above 20 a day; Pro = nothing counted).
    Pro comes from the VERIFIED payment (`is_pro`), a payment that is not verified does not remove ads. The app-defined day is 00:00 UTC (move it with `AD_DAY_UTC_OFFSET_MINUTES`). Counting is one write at a time (`BEGIN IMMEDIATE`), so two phones cannot both take the last place. Rows keep only account, day, key and time; they are deleted with the account and after 35 days.
  * App: Google Mobile Ads SDK 23.2.0 + Google's consent form (UMP 2.2.0, shown only where Google requires it, e.g. EEA / UK). An ad page is a full-screen NATIVE ad (official `NativeAdView` + `MediaView`, video muted) with an always-visible "Ad" label in the five languages; no Sociax like / comment / follow buttons on it; the person leaves it by swiping; nothing forces a click.
    Placement: after about 3 ordinary videos (3 or 4, picked at random, values come from the server), never two ad pages in a row, never as the last page. If no ad is loaded (no fill, error, offline, consent refused) NOTHING is inserted and the feed just continues; after a failed load the app waits 30 s before asking again.
    Counting: an impression is sent to the server ONLY when the official SDK callback `onAdImpression` fires (loaded is not counted). The key is made when the ad is loaded; the phone retries (now, 2 s, 8 s, 30 s) with the same key. When the server says Pro or cap reached, waiting and not-yet-seen ad pages are removed and no new ad is requested.
    Pro / unknown: the SDK is started ONLY after the server said "eligible". If the server cannot be reached the answer is "no ads". Opening the Short Video tab (also coming back from Settings or a payment) asks the server again, so buying Pro switches ads off at once.
    Under 18 (age given at sign-up): AdMob is told "under age of consent" and max content rating PG (non-personalised, child-safe requests).
  * IDs: by default Google's public TEST ids are used (they show test ads and earn NOTHING). For real ads, in AdMob create an app + a NATIVE ad unit, then in GitHub > Settings > Secrets and variables > Actions > Variables add `SOCIAX_ADMOB_APP_ID` (ca-app-pub-...~...) and `SOCIAX_ADMOB_NATIVE_UNIT_ID` (ca-app-pub-.../...). A wrongly typed id is ignored (test id used) because a bad App ID would crash the app at start.
  * Pro plan text now also says "No ads" (five languages). On sign-out the Short Video list and ad state are cleared (the old list used to stay for the next account).
  * Checks: `backend/test_ads.py` (15 scenarios: eligible, counted once, retry, the 21st not counted, per account, new day, moved reset time, Pro, unverified payment, Pro ended, minor flag, bad key, flood limit, account deletion, nothing about the ad stored); `tools/check_api_contract.py` now covers the 2 new addresses (81 app addresses).
  * HONEST LIMITS: (1) NOT COMPILED HERE: the Android SDK / Gradle cannot run in my sandbox, so the Kotlin ads code (Ads.kt, AdScreens.kt, the pager change) was only checked by the source sanity check; GitHub does the real build: send me the first error if it fails.
    (2) NOT TESTED WITH A REAL AD: the plan says "test the real AdMob integration before marking it complete". With the test ids, install the APK on a phone, scroll the Short Video tab, and check: an ad page with the "Ad" label after ~3 videos, never two in a row, the counter (`GET /ads/status` after viewing: `used` goes up by one per ad), after 20 no more ads, a Pro account sees none. So this step is BUILT, not yet VERIFIED.
    (3) The backend test ran against small stand-ins for FastAPI / pydantic (the real ones cannot be installed here), so it checks the rules and the database, not the HTTP layer; GitHub runs it with the real libraries.
    (4) Native ads are used because AdMob has no format that lets an ordinary video ad sit as a feed item; whether the final look is allowed by AdMob's policies is checked by AdMob itself when you apply for real ids: if AdMob objects to the layout, tell me what it says.
    (5) The 20 limit is exact on the server, but an ad can already be on screen when another phone uses the last place: it is shown but NOT counted (never more than 20 counted).
    (6) Not decided in the plan, chosen here: the day resets at 00:00 UTC (not the person's local midnight); under-18 accounts still see child-safe ads (plan says Free users; tell me if you want none for under 18); ads are only in the Short Video feed (the plan names only that place).
    (7) Revenue is not promised: it depends on fill, country, audience and policy-compliant use. Measure it in the AdMob reports. Needs a real test with TWO accounts (one Free, one Pro).
- Step 40 (v0.45.0) The APP now USES the For You ranker, sends clean watch signals and has the recommendation controls (plan v3.9 sections 30, 31.2, 32; roadmap Phase B + C, Android side). The backend only moved its version number.
  * Home > For You and the Short Video tab now read `GET /feed/for-you` and `GET /shorts/for-you` (ranked, one `cursor` per refresh, no repeats while scrolling). Following is UNCHANGED (newest first). Refresh = the round arrow on Home (already there) and a new one in the Short Video top bar: both ask for a NEW ranking.
    Safe fallbacks (plan section 41), nothing ever shows an empty screen because of ranking: (1) the server answers `ranked: false` (ranking failed there) -> the app continues with the old newest-first paging; (2) an older server without the new address (404 on the first page) -> same fallback; (3) an expired cursor (410, after 30 minutes) -> the server's "pull to refresh" message + "Try again".
    Also fixed on Home: a FAILED next page no longer retries in a tight loop (it stops and offers "Try again").
  * What the phone reports (in the background, never blocks the screen, a lost event is harmless): `impression` = the post / video stayed on screen 1 second; `watch` = milliseconds a short video REALLY played (not paused, not in the background); `complete` = it reached 90 % of the clip; `skip` = it played 1-3 s and the person swiped away;
    `share` = the share sheet was opened. Never for my own content. Likes, saves, comments and reports are still recorded by the SERVER itself (the phone cannot fake them). Every event has an idempotency key, so a retry counts once.
  * Controls: the three-dot menu of a post (Home) and of a short video now has "Not interested" and "Hide this creator" (the item / all of that creator's items leave the list at once; profiles, saved lists and search do NOT show these two). Settings > Recommendations (new): plain-words explanation of what personalisation uses (never private chats), how much is remembered (counts only, from `GET /feedback/recommendation`) and "Reset recommendation history" with a Yes / Cancel question.
  * New texts: 8 rows in all five languages (English, French, Chinese, Korean, Russian). `tools/check_android_sources.py` passes.
  * New check in GitHub: `tools/check_api_contract.py` compares every address the app calls (79) with the routes the backend has (89) and fails the build when one is missing, so a typo or renamed address is found in GitHub, not on a phone. It was tested by breaking one address on purpose.
  * HONEST LIMITS: (1) NOT COMPILED HERE: the Android SDK / Gradle cannot run in my sandbox. Only the source sanity check and the address check ran. GitHub does the real build: send me the first error if it fails. The backend tests were not re-run (real FastAPI cannot be installed here) but the backend code did not change except its version number.
    (2) Nobody has proved this order is BETTER: weights are still starting hypotheses and there is no real usage data yet (plan 31.2 / 41). Do not call the feed "smart" before an experiment shows it.
    (3) Still creator-based, not topic-based: "Show less of this topic" is NOT built (posts have no topic tags; none are invented). (4) `share` only means the share sheet opened; Android does not tell us whether the person finished sharing.
    (5) Short Video: the 1-second "seen" counts even when Autoplay is off and the person has not pressed play; `watch` / `complete` / `skip` only come from real playback. (6) There is no screen listing hidden creators / "Not interested" items: Reset in Settings undoes them. (7) Events made while offline are lost, not queued.
    (8) Needs a real test with TWO accounts on two phones: watch some videos from account B on account A, hide one creator, refresh, check the hidden creator never returns, then Reset and check it can return.
- Step 39 (v0.44.0) The first For You RANKER (plan v3.9 section 31; roadmap Phase C). BACKEND ONLY: the Android app is unchanged (only the version number moved).
  * New addresses: `GET /feed/for-you` (photos) and `GET /shorts/for-you` (short videos). First call: no cursor. Next pages: send `next_cursor` back. Refresh = call again without a cursor. `GET /feed`, `GET /feed/following` and `GET /shorts/feed` are UNCHANGED (newest first):
    they are also the safe fallback. Answers carry `ranked: true` and `ranker: "v1"`.
  * Order of work (privacy first, always): (1) SQL takes the newest 300 items this person may see (same `VISIBLE` rule as everywhere + not hidden + not reported by me); (2) `backend/ranker.py` scores them; (3) the order is saved as a snapshot (item numbers only, 30 minutes, max 5 per person)
    and pages are read from it, so scrolling never repeats or reshuffles, and a new post only shows after a refresh; (4) every page re-checks privacy when it is served: something blocked / made private / hidden / removed / deleted after the snapshot disappears and nothing new appears;
    (5) if ranking fails for any reason the answer is the plain newest-first list with `ranked: false` (never an error text). A cursor that is wrong, expired, from someone else or from the other list answers 410 `cursor_expired`.
  * Score (every positive signal 0..100): 30% interest + 20% watch quality + 15% freshness + 15% engagement + 10% follow + 10% diversity - repeat penalty - quality penalty. The weights are the plan's starting HYPOTHESES; change them without code with `RANK_WEIGHTS` (see `.env.example`; a wrong value falls back to the plan's).
    Interest = MY own recent actions (30 days) on that creator's content (like 3, save 4, comment 4, share 4, finished video 2 points), saturating. Watch quality = share of the clip watched, normalised for its length, best value per viewer, averaged over different viewers; fewer than 3 viewers = neutral 50, accidental skips (under 1 s) ignored.
    Freshness halves every 48 h (gradual, no cliff). Engagement = likes + 2 x saves + 2 x commenters + 2 x sharers, each person counted once, never the creator, on a log scale. Follow = 100 if I follow the creator. Diversity: 100 for a creator not yet in the list, then 50, 33, 25...
    Repeat penalty: seen in the last 48 h -40, in the last 14 days -15, skipped -25. Quality penalty: -10 per different person who reported it, max -30 (a report is a signal, not proof: the item stays visible).
  * Variety and fairness: never more than 2 in a row from one creator while others exist; every 5th place goes to a new / small creator (hardly any engagement, not followed, no history with me) when one is eligible, so new creators are not buried.
  * Checks: `cd backend && DEV_AUTH=1 python test_ranker.py` (new: pure scoring tests + 15 database scenarios: privacy first, each signal, diversity, exploration, stable paging, re-check at serve time, bad cursors, fallback, shorts, cleanup); `test_events.py` updated. Both also run in GitHub.
  * HONEST LIMITS: (1) THE APP DOES NOT USE IT YET: the Home / Short Video screens still call the old newest-first addresses and do not send watch events, so on a real phone nothing looks different until the next steps. (2) Interest is by CREATOR, not by topic: posts have no topic tags yet and none are invented;
    "Show less of this topic" is still not built. (3) The weights and thresholds are starting hypotheses: there is no real usage data yet, so nobody can say this order is "better". Do not claim it before an experiment shows it (plan 31.2 / 41). (4) Only the newest 300 eligible items are ranked, and the list is up to 150 items;
    older items are not reachable through For You (the old feed still reaches them). (5) Scores are computed per request from the database (fine for a small user base; measure before growing: `X-Response-Time-ms` and the slow-request log exist). (6) Same sandbox limit as Step 38: real FastAPI could not be installed here,
    so the tests call the route functions directly; the SQL, the scoring and the privacy rules ARE tested, the HTTP layer and pydantic's own validation are not. GitHub runs the real thing: send me the first error if a check fails there.
- Step 38 (v0.43.0) Discovery engine FOUNDATION: clean signals + "Not interested" / "Hide creator" / "Reset" (plan v3.9 sections 31, 32, 35, 38; roadmap Phase A + B). BACKEND ONLY: the Android app is unchanged in this step (only the version number moved).
  * Phase A (audit, baseline): before changing anything every backend check was run: all passed (the few that need real FastAPI / pydantic validation could not be run in my sandbox, see HONEST LIMITS). Findings: public photos / videos, followers-only, private account, block and
    moderation rules were already enforced on the server by one shared rule (`VISIBLE`) for feed, shorts, search and direct opening (proved by `test_cross_account.py`); the feed pages with `before` (stable, no repeats); likes / saves are already de-duplicated by the database.
    What was missing: any record of what a person actually watched, any "not interested" control, and one combined quota answer. That is what this step adds.
  * `POST /events/interaction` (signed-in only): the phone reports ONLY what it saw: `impression`, `watch`, `complete`, `skip`, `share`, each with a fresh `idem_key` (8-64 letters / digits / `_ . : -`; the phone re-uses the same key when it retries) and an optional `value_ms`.
    The server uses its OWN clock (a time from the phone is never read), stores the version number, and counts a retry once. Rules: an impression counts only after 1 second really on screen (pre-loaded items never count); a watch time that is impossible (more than 10 plays of the clip in one event) or empty is
    ignored with `recorded: false` (200, so the phone does not retry); `watch` / `complete` exist for short videos only; an event on something the person may not see answers the same 404 as a missing item (no leak); an event on my own content is ignored; max 240 events per minute per account.
  * Like, save, comment and report are NEVER accepted from the phone (422): the server records them itself, once, inside the real action (`record_server_event`), so a phone cannot fake a popularity signal. No message text and nothing from private chats is ever stored.
  * `POST /feedback/recommendation` with `action`: `not_interested` (one photo / video), `hide_creator` (everything from that person), `reset`. The item must be one the person may see (404 otherwise), and not their own (422). Hidden things disappear from THAT person's For You, Following and Short feeds only
    (extra filter on top of `VISIBLE`: privacy still comes first; the creator's profile and direct links still open, it is not a block). `reset` deletes my events, my "Not interested" marks and my hidden creators (so it is also how to undo a hide). "Refresh feed" needs no endpoint: it is a new GET.
  * `GET /feedback/recommendation`: counts only (events kept, not-interested marks, hidden creators, retention days) so Settings can show and explain personalization. `GET /feed/for-you` and `GET /feed/following`: named addresses, same code as `GET /feed`. `GET /quota`: today's media + AI allowance in one answer (same numbers as `/quota/media` + `/quota/ai`).
  * Privacy / retention: events older than `EVENT_RETENTION_DAYS` (default 90) are deleted (checked at most once an hour); deleting a post / short deletes its events and marks; deleting an account deletes the person's events and hide-marks AND every event / mark about their content.
  * Checks: `cd backend && DEV_AUTH=1 python test_events.py` (new, 16 groups; also runs in GitHub with the other `test_*.py`).
  * HONEST LIMITS: (1) THERE IS NO RANKING YET. For You is still newest-first: the weighted score, freshness, repeat penalty and diversity (plan 31.2) are the next step (Phase C); this step only collects the signals and the controls. Do not call the feed "smart" yet.
    (2) The Android app does not send events or show "Not interested" / "Hide creator" buttons yet (next steps), so on a real phone nothing changes today except what the server now records for likes / saves / comments / reports.
    (3) "Show less of this topic" is NOT built: posts have no topic tags yet, and I will not invent topics. (4) Hidden-creator and not-interested lists are in the database only (no screen to review them; use Reset).
    (5) In my sandbox FastAPI itself could not be installed, so the checks call the route functions directly with a small stand-in for FastAPI / pydantic: the logic, SQL and privacy rules ARE tested, but request-body validation by the real pydantic (for example the 8-64 key pattern) and the HTTP routes are not;
    the same rules are repeated in code (`_check_event`) so they are tested. GitHub runs the real thing: if a check fails there, send me the first error. (6) Not compiled here: no Android code changed, but build on GitHub as always.
- Step 37 (v0.42.0) First-open language selector + start-up speed and honesty (plan v3.5 sections 24 + 25).
  * "Choose your language" is now at the TOP of the opening / sign-in screen, before Google sign-in (no account, no internet needed): English, Français, 中文, 한국어, Русский (a small menu).
    Picking one changes the WHOLE app at once (navigation, settings, onboarding, errors, quota / Pro, alarm and lock screens: they all read the same saved language) and it is saved on the phone, so it is the same after closing the app.
    Settings > Language still changes it later, also offline. English is the default (plan); before this step the app followed the phone's language on first start: it does not any more.
  * The choice is also kept on the account (`users.language`, `PATCH /users/me` with `language`, only the five codes are accepted; sent with onboarding too). New phone and nothing chosen there yet -> the account language is used;
    chosen on this phone -> it is saved on the account. The phone's own copy always works without internet. A missing translation falls back to English, then to the key (never a blank label, never a crash).
  * Start-up: the first screen shows only the logo (nothing waits for the feed, AI, ads or big downloads). The first call after start (`GET /users/me`) now waits up to a minute (a free server may be asleep); if the server cannot be reached
    or answers 5xx the person STAYS signed in and sees the logo with a message and "Try again" (before: thrown back to the login screen). Wrong server address (404) and expired sign-in (401) still say what they mean (Step 36).
  * Measurements (plan: measure, do not guess): Logcat tag `SociaxPerf` prints "startup_screen_shown", "login_screen_shown", "home_ready" (ms since the app process started = cold start) and "login_to_ready" (Google tap -> Home).
    Read them with `adb logcat -s SociaxPerf` on a real phone, ideally also with weak internet. The server adds `X-Response-Time-ms` to every answer and logs requests slower than 1.5 s (`SLOW_REQUEST_MS`) with the ROUTE PATTERN only.
  * New safety net for me and for you: `python tools/check_android_sources.py` (also runs in GitHub before the APK build) checks balanced brackets, every `tr("key")` exists, every string has all five languages with matching placeholders, and every `R.drawable / R.raw` exists.
    It found nothing wrong now, but it is NOT the compiler: the real Gradle build is still the final test.
  * Checks: `cd backend && DEV_AUTH=1 python test_language.py` (new).
  * HONEST LIMITS: (1) The five languages cover the app's own texts; user posts, usernames and the English-only Terms / Privacy drafts are not translated. (2) Text wrapping, fonts and date / number formats in all five languages have not been looked at on a real phone:
    please check the longest screens (Settings, Plans, lock) in Russian and Korean. (3) Login stays Kotlin + Firebase: no Go or C++ was added (plan 3.5: only when a measurement proves a need; no such measurement exists yet).
    (4) Not compiled here: build on GitHub and send me the first error if it fails.
- Step 36 (v0.41.0) Reliability: video length, AI errors, deployment check, Google sign-in (plan v3.6 sections 2, 3, 4).
  * Video length (Android `data/VideoUtils.kt`): a picked video is only a content address, so the length is now read in FIVE independent ways (metadata reader on the address, on a file descriptor, track headers,
    a temporary copy, and finally the video player itself). The first that works wins; every reader is released; a failure writes only the way + error kind to the log (tag `SociaxVideo`), never a file name, and never crashes.
    A clip is refused only when ALL ways fail, with the message "Cannot read this video. Try another one." (History: the 60-second rule of this step was removed in Step 53, plan v3.9.1; a clip is never cut silently and never needs a readable length.)
    The server also asks Cloudinary again (2 extra tries, 1 s apart) when it does not know a new video's length yet; if it is still unknown the clip is refused (422) and nothing is published.
  * AI (`backend/ai_providers.py`, `POST /ai/ask`): every failure now has a SAFE category in the server log (`http_401` bad key, `http_429` provider limit, `http_5xx`, `timeout`, `bad_format`, `network`) next to the provider name:
    never the question, the answer or a key. One quick retry only for connection / 5xx failures (never for a bad key or a rate limit), and no new provider is started after 50 s (`AI_TOTAL_DEADLINE_S`).
    The app gets structured answers: `ai_not_configured` (503, not retryable) and `ai_unavailable` (502, retryable, "busy" wording when only rate limits / timeouts happened). A failed answer never uses the free allowance.
    The AI screen shows "Try again" for a retryable failure (the question is put back so you can send it again).
  * Deployment check: open `https://<your-render-address>/health/config` after every deploy (no login needed). It answers `ok`, which services are connected (firebase, cloudinary, each AI provider, payments),
    the NAMES of missing settings (for example `CLOUDINARY_API_SECRET`) and `dev_auth_enabled` (must be `false` in production). It never shows a value, key or path.
  * Google sign-in: this app signs in with Android Credential Manager + Firebase, so there is no browser redirect page on the backend. Choosing a Google account is not a finished login: right after it the app asks the server
    `GET /users/me`. If that answers 404 the app now says "Signed in, but the Sociax server did not recognise the request (404): wrong server address or old server" (and 401 says the sign-in expired); the status code is logged (tag `SociaxAuth`).
    A 404 at the end of Google sign-in almost always means the app was built without `SOCIAX_API_URL` (GitHub > Settings > Secrets and variables > Actions > Variables; since Step 51 a Secret with that name also works) or Render is still running an old version: check `/health/config`
    shows the new version `0.41.0`. For safety the backend also has `GET /auth/callback` (a plain "return to the app" page, never a 404, never echoes what it receives) in case a redirect address was ever pointed at the backend.
  * Checks: `cd backend && DEV_AUTH=1 python test_reliability.py` (new) + updated `test_ai_providers.py`.
  * HONEST LIMITS: (1) I cannot see your Render or Android logs from here: the AI error on your phone and the 404 are diagnosed by the new log lines and `/health/config`; please open `/health/config` and send me the answer if either problem remains.
    (2) The video readers are written against the Android API but NOT compiled or run here; test gallery videos with sound, camera videos, portrait / landscape and a video with an unusual file name on your phone.
    (3) Not compiled here (no Android SDK): build on GitHub and send me the first error if it fails.
- Step 35 (v0.40.0) Posts really reach other accounts, safe retry, and a real release gate (plan v3.6 sections 1, 2, 5, 6 + v3.7).
  * What was checked: Public photos / videos were already read from the SERVER (`GET /feed`, `GET /shorts/feed`) for every signed-in account, follower or not. Step 35 PROVES it with `backend/test_cross_account.py`
    (Account A publishes, Account B follows, Account C is a stranger, Account D is blocked): public photo + video reach the non-follower with a working Cloudinary URL; followers-only reaches only an accepted follower
    (a stranger gets the same 404 as for a missing post); a blocked account sees nothing; a PRIVATE account's posts stay private even when marked Public; posts survive a restart; paging never repeats.
  * "Published" is honest: the app says posted only after the server returned the saved post. A failed Cloudinary check or a too-large photo creates NO post and uses NO quota (the refused photo is queued for deletion).
  * Retry without duplicates: asking again for the SAME upload (lost answer, weak network) returns the SAME post / clip (200) instead of "409 Already posted"; the daily media quota is counted once.
    The app also remembers an already uploaded photo / clip, so "try again" re-sends only the post record (no second upload, no orphan file). After a permanent refusal it uploads again.
  * New: `GET /posts/{id}` and `GET /shorts/{id}` (open one item; hidden and missing give the same 404), `next_before` in `GET /feed`, `type` / `visibility` / `moderation_state` in every post and short,
    and a `moderation_state` column ('ok' normal; 'restricted' / 'removed' hide the item from everybody except its owner; the feed obeys it now, a moderator tool is not built yet).
  * Home: a feed failure shows the message AND a "Try again" button (never a silent empty feed).
  * Release gate: the GitHub "Backend checks" job is no longer allowed to fail silently: it runs a Python syntax check + every `test_*.py`, and the run is RED when one fails (the APK job is separate, so the APK is still built).
    Three old checks (`test_chat.py`, `test_text_stories.py`, `test_cloud_cleanup.py`) only passed with stand-in modules; they now pass with the REAL pydantic / httpx as well.
  * HONEST LIMITS: (1) A Public post of a PRIVATE account is not public (the plan says private content must not appear to unauthorized users). (2) The two-DEVICE manual check of plan 3.6 / 3.7 (A publishes, B plays with sound on a second phone)
    still has to be done by you on real phones with the deployed backend: the server rules are tested here, playback on a phone is not. (3) The Android part is NOT compiled here (no Android SDK / internet): build on GitHub and send me the first error if it fails.
    Checks: `cd backend && DEV_AUTH=1 python test_cross_account.py`.
- Step 34 (v0.39.0) New Sociax logo, exactly like the supplied picture (purple chat bubble with a white play triangle + orange-pink play shape).
  * Replaced everywhere: opening / sign-in screen, onboarding, App Lock screens, Home header (colour logo + "Sociax" in white), the adaptive launcher icon and the normal + round launcher icons (all five densities).
    The picture has no text, so the opening and sign-in screens show the name "Sociax" under it (plan: "Sociax logo/name").
  * The white background of the picture was removed (transparent) so the logo sits cleanly on the dark app screens. The launcher icon keeps the picture's white background so it looks like the picture.
  * Files: `res/drawable-nodpi/sociax_logo.png`, `sociax_mark.png`, `ic_launcher_foreground.png`, `res/mipmap-*/ic_launcher*.png`, `values/colors.xml` (launcher background white), `ui/theme/SociaxLogo.kt`. The new picture is saved as `logo_reference.jpeg`.
  * Not compiled here: build on GitHub and send me the first error if it fails. Please check the logo on your phone (launcher icon, opening screen, Home header).
- Step 33 (v0.38.0) Optional Daily Alarms at 3:00 AM and 3:00 PM, no internet needed (plan v3.3 sections 1 + 4).
  * Home: a dismissible, non-blocking prompt near the top: "Would you like Sociax to play an alarm every day at 3:00 AM and 3:00 PM?" with Yes, No and X. Yes turns alarms On (after the Notifications permission on
    Android 13+); No and X do NOT turn them on and the prompt does not come back (turn them on later in Settings). Normal use of the app is never blocked.
  * Settings > Daily Alarms: master On/Off with clear state, the two times (3:00 AM / 3:00 PM by the phone's local time and time zone; fixed in this version), next alarm time, FIVE built-in sounds
    (Sunrise, Bell, Beep, Chime, Pulse: tap to choose, Preview to listen, choice saved on the phone), permission status and a button to the phone's notification settings.
  * How it works (`data/DailyAlarm.kt`): Android `AlarmManager.setAlarmClock()` (exact, works in Doze, no special exact-alarm permission) + a small foreground service that plays the chosen sound from the app itself
    (looping, alarm volume, up to 1 minute, with a Stop button). No server request, no internet permission use. Rescheduled after every ring, after reboot, after an app update and when the time / time zone changes.
    If Android refuses to start the service, a high-priority notification with a bundled sound is used instead.
  * Sounds: five short original sounds generated for Sociax (`res/raw/alarm_*.wav`), so there is no licence to worry about.
  * If the Notifications permission is denied (or taken away later) the alarms stay Off and the screen explains how to turn them on. HONEST LIMITS: battery saver / restricted battery, Do Not Disturb, a muted alarm
    volume or a force-stopped app can still stop an alarm: the screen says it is not guaranteed.
  * Texts in the five languages. Not compiled here: build on GitHub and send me the first error. Please test on a real phone: airplane mode, after a reboot, with the app closed, and on Android 13+ / 14.
- Step 32 (v0.37.0) Mandatory App Lock: 4-digit PIN or pattern (plan v3.3 sections 2 + 3 + 4).
  * Right after sign-in + onboarding (and for any install that has no lock yet) a full-screen "Protect your account" step asks you to choose a PIN or a pattern and confirm it. It cannot be skipped or left blank;
    a pattern needs at least 4 dots. After that the lock screen opens with the app (always after a full close) and again after the timeout (Settings > App Lock: 30 s / 1 min / 5 min / 15 min, default 1 min).
    The screens underneath stay as they were (an upload in progress is not cancelled) but cannot be touched.
  * Storage (`data/AppLock.kt`): only a salted PBKDF2 verifier, sealed with a non-exportable Android Keystore HMAC key; the PIN / pattern is never stored in plain text, never logged and never sent to the server.
    The lock file is excluded from cloud backup / phone-to-phone transfer. Wrong tries are throttled: after every 5 wrong tries the app waits 30 s, 60 s, 120 s ... (max 15 min); the counter survives closing the app.
  * "Forgot PIN/Pattern?": the old one can NOT be shown or emailed. You sign in again with Google (Firebase re-authentication: another Google account is refused); the server checks that the sign-in is at most 5 minutes old
    (`POST /lock/recovery`), gives a one-time token (stored as a hash, 5 minutes, one use, only for you, 5 requests per hour), the app redeems it (`POST /lock/recovery/confirm`) and only then you choose a new lock.
    Every failure looks the same, so nothing reveals whether an account exists. Google sign-in itself was not changed.
  * Settings > App Lock: current type, "Change PIN / pattern" (asks for the current lock first), timeout. Log out / delete account removes the lock from the phone. All texts in the five languages.
  * OPEN: the plan asks for an account-support process for people who cannot use their Google account: the screen says support verifies identity first, but the real support contact (email / form) is not decided yet.
  * Checks: `cd backend && python test_lock_recovery.py`. The Android part is not compiled here: please run the GitHub build and send me the first error if it fails. Test on a phone: wrong tries / wait, pattern with 3 dots, change lock, Forgot.
- Step 31 (v0.36.0) Short Video is now up to 60 seconds (plan v3.2 section 3). The 20 s / 25 s choice is gone: you pick or record a clip, and a clip longer than 60 s MUST be trimmed by you (a slider chooses which
  minute to keep); nothing is ever shortened or published silently. The server checks the real file again (limit 60 s + 0.5 s rounding) and deletes a refused clip from Cloudinary. Older app versions that still send
  20 / 25 keep their own smaller limit. Caption, audience, comments, upload quality and Data Saver are unchanged. Texts in the five languages updated. Check: `cd backend && python test_shorts.py`.
- Step 30 (v0.35.0) ONE paid plan, Sociax Pro $15 / month, and DAILY media limits (plan v3.2 sections 1 + 2). The old $10 AI Pro and $3 App Pro can no longer be bought.
  * Free: 500 MB of media per day (posts, photos, videos, files combined). Pro: 1.5 GB per day = 1536 MB (one conversion everywhere: 1 GB = 1024 MB). Pro AI allowance = 7x the free AI allowance.
  * The media day is a 24-hour window that starts at the user's first upload (table `media_usage`); `GET /quota/media` returns used / remaining / exact `reset_at`. Deleting a post never gives bytes back
    and a reset never deletes anything. The counter is kept on the server only; every upload path (avatar, post, short, story) checks it. The "media_full" answer now carries `reset_in_seconds`.
  * Payments: `POST /payments/initialize?product=pro` ($15 USD, Chapa TEST mode, 30 days, NOT automatic renewal). Time already paid under the old plans is honoured until it ends, as Pro. Benefits unlock
    only after the server verifies the payment. `GET /quota/media` and `GET /quota/ai` now say `pro` / `pro_expires_at`.
  * App: Settings > Storage & plans shows today's media (used, left, reset time), the AI usage and ONE Pro card; the onboarding Pro intro shows one card; the "limit reached" popups offer Sociax Pro or waiting
    for the reset. All texts are in the five languages.
  * OPEN DECISIONS (from the plan): (1) the free AI amount per period is still the TEST value (`FREE_AI_REQUESTS`); (2) the plan asks you to confirm whether the free AI window stays 48 hours (kept at 48 h
    until you say otherwise); (3) the Pro media limit applies to the 24-hour window above, not to total storage.
  * Checks: `cd backend && python test_media_daily.py` (new), plus updated `test_limits.py`, `test_payments.py`, `test_shorts.py`, `test_stories.py`.
  * NOT in this step (next steps): 60-second Short Video, mandatory PIN / pattern App Lock, daily alarms, AI Create (images / videos), read receipts. Not compiled here: send me the first error if the build fails.
- Build helper (after v0.34.0): if "Sociax Android APK Build" fails, the run page now shows a summary "The APK build failed. First errors" and red annotations with the Kotlin error lines,
  and the artifact **Sociax-build-log** holds the full Gradle log. Copy the first error lines (or the log) to Claude to get the exact fix. Read receipts (Step 30) are NOT in this zip: paused until the APK builds.
- Step 29 (v0.34.0) Claude connected to the server as an AI provider, and changing the @username (plan section 9: Account > username).
  * Claude (Anthropic): `backend/ai_providers.py` now has three providers. Default order Grok, Gemini, Claude (the first one with a key that answers wins; a failing one is skipped). `AI_PROVIDER_ORDER` changes the order
    (for example `claude,grok,gemini` to let Claude lead). New env values: `ANTHROPIC_API_KEY` (secret, server only; on Render add it in the dashboard, `render.yaml` already lists it) and `ANTHROPIC_MODEL` (default `claude-sonnet-5-5`).
    It is used by private AI chat (`POST /ai/ask`) and `@AI` in groups, with the same rules as before (48-hour free allowance, one request only, never private chats). `GET /ai/status` also reports `claude`.
    Checks: `cd backend && python test_ai_providers.py` (fake HTTP: order, fallback, request format, key skipped, answer length).
  * Username: Edit profile now has an editable @username field (a-z, 0-9, _ or ., 3 to 20 characters). Server: `PATCH /users/me` with `username`: same rule as onboarding, unique in any letter case, 409 when taken,
    nothing else in the same request is saved when the name fails, at most 5 changes per hour. Checks: `cd backend && DEV_AUTH=1 python test_username_change.py`. Texts in the five languages.
  HONEST LIMITS: (1) Plan v3.0 section 2 said Grok + Gemini only for the first version and not to promise Claude; Claude was added because the product owner asked for it: nothing in the app tells users which provider answered.
  (2) NOT tested against the live Anthropic API (no internet in my workspace): the request follows the Messages API (`x-api-key`, `anthropic-version: 2023-06-01`); if the model name is rejected, change `ANTHROPIC_MODEL`. Claude gets the same short system prompt and answers are cut at 4000 characters like the others.
  (3) Changing the username has no waiting period: the old name is free for anybody at once, so a person could take someone's old name. Old group system lines (for example "@old was added by @x") keep the old name as written.
  Tell me if you want a waiting time. (4) Still NOT built from section 9: Chat settings (read receipts: the app does not show read ticks at all yet; media auto-download: chat has text only), a "Sociax AI" settings block, a data / accessibility block.
  Also still open: white-surface Light Mode, push notifications (Firebase Cloud Messaging), live payments / auto-renewal, calls, image / video generation. (5) The backend checks passed here with stand-in fastapi / pydantic modules; the Android part was NOT compiled here:
  let the GitHub Action build it and send me the first error line if it fails.
- Step 28 (v0.33.0) Settings > "Help & legal" and the connected Google account (plan section 9: "help, report problem, Terms, Privacy Policy" and "connected Google account").
  * Settings now has a "Help & legal" section: Help (short how-to list), Report a problem (text box, 10 to 1000 characters, sent to the server), Terms of Service, Privacy Policy.
    Settings > Account shows the Google e-mail you signed in with (read on the phone from Firebase, not sent anywhere).
  * Server: `POST /feedback` saves the text in the `reports` table (`target_type = 'problem'`, version of the app in `target_id`), max 5 per hour per person, never written to the logs;
    deleting an account removes that person's own problem reports (reports about posts / short videos stay as moderation records). Checks: `cd backend && DEV_AUTH=1 python test_problem_report.py`.
  * Labels and the Help text are in the five languages (16 new rows in `Strings.kt`).
  HONEST LIMITS: (1) The Terms and the Privacy Policy are PLAIN-LANGUAGE DRAFTS, in English only, written from what this app really does. They are NOT legal advice: the plan (section 14) says the age / privacy / store rules of the launch countries still have to be checked,
  and Google Play also needs a public Privacy Policy web address. (2) There is no support e-mail or help web page in the plan, so none is shown; problems only reach the `reports` table, and nothing in the app lets an admin read them yet.
  (3) Still NOT built from section 9: changing the @username, the Chat settings (read receipts, media auto-download), a "Sociax AI" settings block, and a data / accessibility block. (4) The new backend check passed here with stand-in fastapi / pydantic modules (older checks too);
  the Android part was NOT compiled here (no Android SDK / internet): let the GitHub Action build it and send me the first error line if it fails.
- Step 27 (v0.32.0) Notifications (plan section 9: Notifications: messages, follows, likes/comments, group activity, short-video interactions, AI responses).
  * Settings > Notifications now has SIX real switches (all on by default), saved on the server (`PATCH /users/me` with `notifications`, answered inside `GET /users/me`).
  * The bell on Home now opens a Notifications list (it used to open only the follow requests; they are one tap away at the top of the list and in Bio). A small dot on the bell shows unread ones
    (the app asks the server every 30 s while no page is open); opening the list marks everything as read. Tap an entry: follows / likes / comments open that person's profile, messages / group / AI open the Chat tab.
  * What creates an entry (server): someone follows me, asks to follow me, or accepts my request; likes / reacts to / comments on my post; likes / reacts to / comments on my short video; a new message in one of my chats
    (ONE merged entry per chat while unread: "3 new messages"; opening the chat clears it; a message that is waiting because of "Follow me first" notifies only when it is released); someone adds me to a group;
    Sociax AI answers my @AI request in a group.
  * Rules enforced on the SERVER: never about my own actions, never between people who blocked each other (blocking also deletes the entries between us), a switched-off group writes nothing new AND its old entries are hidden
    (they come back if I switch it on again), the same person + thing notifies only once (like / unlike / like again = one entry), only the newest 200 entries per person are kept, deleting an account removes its entries.
    An entry never contains message or comment text: only who + what kind. Endpoints: `GET /notifications` (`before`, `limit`), `GET /notifications/unread-count`, `POST /notifications/read`.
    Checks: `cd backend && DEV_AUTH=1 python test_notifications.py` (passed here with stand-in fastapi / pydantic / httpx modules; the older checks still pass too, except the two known stand-in limits of `test_stories.py` caption limit and `test_cloud_cleanup.py`).
    Texts in the five languages (27 new rows in `Strings.kt`).
  HONEST LIMITS: (1) There is NO push notification yet (nothing on the lock screen, nothing while the app is closed): that needs Firebase Cloud Messaging and a device token table: it is the next notification step.
  This is an in-app list, so a new entry shows up when the app next asks (up to 30 s on Home, at once when you open the bell). (2) The plan lists "short-video interactions" and "likes/comments": likes / comments on photo POSTS are in "Likes and comments",
  everything on short videos is in "Short video interactions". Story views and replies do not create entries (a story reply is already a private message, so it appears under Messages). (3) Notifications that were not written while a group was switched off are gone for good
  (switching it on again does not recreate them). (4) Tapping a like / comment entry opens the person's profile, not the exact post (there is no "open one post by id" call yet). (5) The Android part was NOT compiled here (no Android SDK / internet in my workspace):
  push to GitHub, let the Action build it, and send me the first error line if it fails.
- Step 26 (v0.31.0) Search now finds short videos too (plan section 4: "users, public posts/videos").
  * Server: `GET /search` answers a third list `shorts` (caption match, newest first, 20 at most). Only PUBLIC clips of PUBLIC accounts, never followers-only clips (not even for a follower),
    never clips of someone who blocked me / whom I blocked. Same answer for "missing" and "hidden". Checks: `cd backend && DEV_AUTH=1 python test_search_shorts.py`.
  * App: Home search shows a new "Short videos" section (grid of first-frame thumbnails, 3 per row) under People and Posts; tap one to watch it full-screen (respects Data Saver), back button returns to the results.
    Old apps that only read `users` and `posts` keep working. Text in the five languages (1 new row).
  HONEST LIMITS: (1) Search matches the CAPTION only (not the @username of the owner: that is what the People list is for). (2) The full-screen search player shows video, @username and caption only:
    no like / comment buttons there (open the clip in the Short Video tab for that). (3) Same testing note as before: the backend check passed here with stand-in modules; the Android part was not compiled here.
- Step 24 + 25 (v0.30.0) Cloudinary clean-up and "Online / last seen" (plan sections 9 + 11). Also: the GitHub build now works with a `gradlew` launcher (see below).
  * Cloudinary clean-up (Step 24): every file that leaves Sociax (deleted post / short / story, EXPIRED story, replaced profile photo, deleted account) is written into the new table
    `cloud_trash` in the same database transaction, and a small worker really deletes it on Cloudinary (images and videos use their own endpoint; "not found" counts as done).
    A failed try is repeated (every 10 minutes and after every deletion); after 8 failed tries the entry is dropped. The app never waits for Cloudinary. Checks: `cd backend && DEV_AUTH=1 python test_cloud_cleanup.py`.
    Needs the three Cloudinary values in `.env` (without them the queue just waits).
  * Online / last seen (Step 25): Settings > Privacy & Security > "Online status and last seen" (on by default). While the app is on screen it sends a small heartbeat every 45 s
    (`POST /presence`); in a one-to-one chat the header shows "Online" (heartbeat in the last 90 s) or "Last seen 12m ago" (`GET /presence/{username}`, and `presence` inside every chat summary).
    Rules enforced on the SERVER: RECIPROCAL (switch it off and nothing of yours is recorded AND you see nobody's), only people who already have a one-to-one chat together,
    never between people who blocked each other, and the answer is the same `null` for every reason (missing user, hidden, no chat, nothing recorded). Checks: `cd backend && DEV_AUTH=1 python test_presence.py`.
    Texts in the five languages (4 new rows in `Strings.kt`).
  * GitHub: the project now has a `gradlew` launcher. It uses Gradle 8.9 (or downloads it once), so a workflow that runs `chmod +x gradlew` + `./gradlew ...` works as well as the included `build-apk.yml`.
    `assembleRelease` now builds an installable APK (signed with the same fixed test key, no shrinking).
  HONEST LIMITS: (1) The reciprocal rule and the "only people I chat with" rule are my interpretation: the plan only says "online / last seen" is a privacy choice. Tell me if you want it different.
  (2) Presence is a heartbeat, not push: the status in an open chat refreshes every 20 s, and "Online" can lag up to 90 s. It shows only in one-to-one chats (not in the chat list or on profiles yet; the server already sends it in the chat list).
  (3) Files that were removed BEFORE this version stay on Cloudinary (the queue only knows deletions from now on). (4) Notification settings, push, calls, white Light Mode, automatic renewal and image / video generation are still NOT built.
  (5) The backend checks passed here with stand-in fastapi / pydantic / httpx modules (no internet in my workspace; the old `test_stories.py` caption-limit line needs the real pydantic): the GitHub job runs them with the real packages.
  The Android part was NOT compiled here: push to GitHub, let the Action build it, and send me the first error line if it fails.
- Step 23 (v0.29.0) Sociax App Pro ($3 / 600 MB) is now REAL, separate from AI Pro (plan v3.1 sections 2 + 3), and the GitHub APK build was hardened.
  * Server: `POST /payments/initialize?product=ai_pro|app_pro` (default `ai_pro`, so older apps keep working). The price ($10 / $3, USD) and the 30 days are chosen ONLY on the server;
    the app never sends a price. `GET /payments/verify/{tx_ref}` checks with Chapa directly, compares reference, status, amount, currency and user, and grants exactly once.
    Each product has its OWN expiry: AI Pro never unlocks App Pro and the other way round (the old code took one shared expiry; now it is per product, old AI Pro payments keep working).
    While App Pro is active the combined media allowance is 600 MB (instead of 150 MB), immediately after the verified payment; `GET /quota/media` also returns `app_pro` and `app_pro_expires_at`.
    Checks: `cd backend && DEV_AUTH=1 python test_payments.py` (new section 12b: wrong price rejected, separate products, extension, expiry back to 150 MB).
  * App: Settings > Storage & plans now has TWO equal sections (AI Pro and App Pro), each with price, "one-time, 30 days, no auto-renew", a buy button that opens the Chapa checkout, automatic
    verification when you come back, "Check my payment" button, and "active until <date>". Texts in the five languages (4 new rows in `Strings.kt`).
  * GitHub: `.github/workflows/build-apk.yml` builds the debug APK on every push (file `Sociax-v<version>.apk` in the run's Artifacts), warns when `SOCIAX_API_URL` is missing, and runs all backend
    checks with the REAL packages in a second job that never blocks the APK. `gradle.properties` got more memory for the Compose build. `.gitignore` also keeps `*.apk`, `.idea`, `__pycache__` out.
  HONEST LIMITS: (1) Payments are Chapa TEST mode, one-time for 30 days (not an automatic monthly subscription): the plan says "monthly"; real auto-renewal needs a subscription product and is not built.
  "Renewal / cancellation terms" are therefore the plain sentence "does not renew automatically". (2) When App Pro ends, files stay but you cannot upload while you are over 150 MB (nothing is deleted).
  (3) The old text key `plan_terms` ("renews every month") is no longer shown anywhere. (4) NOT compiled here (no Android SDK / internet in my workspace): push to GitHub, let the Action build it, and send me the first
  error line if the build fails. The backend checks passed here with stand-in fastapi / pydantic / httpx modules; the GitHub job runs them with the real ones.
- Step 22 (v0.25.0) "Follow me first" prompt (plan section 4): Settings > Privacy & Security > "Ask people to follow me first" (off by default).
  When it is ON and someone who does not follow me sends me a private message, the message WAITS: I do not see it, it does not count as unread, and my chat preview does not show it.
  The sender sees a popup "Follow me first" with Yes / No (Yes = follow now; No = the message simply keeps waiting) and a small "Waiting for your follow" under the message.
  When the sender is accepted as a follower (public account: at once; also after I accept a request), the messages appear for me as UNREAD. Follow acceptance and read confirmation stay
  separate states: showing a waiting message never marks it as read, and opening the chat while it waits does not read it. Switching the option off releases everything waiting.
  * Server: new columns `users.follow_first` and `messages.held` (added automatically to existing databases); `PATCH /users/me` takes `follow_first`; `POST /chats/{id}/messages`
    answers `follow_prompt` when the message was held. Checks: `cd backend && DEV_AUTH=1 python test_follow_first.py`. Texts in the five languages (6 new rows in `Strings.kt`).
  HONEST LIMITS: (1) The plan only gives the sentence "Follow me first - would you like to read this?" and the rules "Yes/No" and "do not falsely mark as read"; how it works exactly (who sees the
  prompt, what waits) is my interpretation as described above: tell me if you meant something else. (2) Applies to one-to-one chats only (not groups). (3) The sender's "Waiting for your follow" label
  updates after answering Yes, or when the chat is opened again; there is no live push yet. (4) Story replies to such a person also wait (a note is shown). (5) The new test passed here with stand-in
  fastapi / pydantic / httpx modules; please run `python test_follow_first.py` once with the real packages. The Android part was not compiled here: open in Android Studio, sync, and send me the first error if there is one.
- Step 21 (v0.24.0) Saved short videos list (plan sections 4 + 5): Settings > Privacy & Security > "Saved short videos" shows the clips I bookmarked on Short Video
  as a grid of first-frame thumbnails (30 per page + "Load more"). Tap one to watch it full-screen (looping, respects Data Saver), with a back button and "Remove from saved".
  * Server: `GET /shorts/saved` (`before`, `limit`). Same VISIBLE rule as the feed: a saved clip whose owner went private, switched it to followers-only (and I do not follow),
    or blocked me quietly disappears from my list, and comes back if it becomes visible again. Checks: `cd backend && DEV_AUTH=1 python test_saved_shorts.py`.
  * Texts in the five languages (4 new rows in `Strings.kt`).
  HONEST LIMITS: (1) The saved-clip player shows only the video, caption and owner name: no like / comment buttons there (open the clip in the Short Video tab for that).
  (2) The thumbnails come from Cloudinary (first frame); they need the Cloudinary cloud name in `.env`, like all videos. (3) Same testing note as before: the new check passed here with
  stand-in fastapi / pydantic / httpx modules; please run `python test_saved_shorts.py` once with the real packages. The Android part was not compiled here: open in Android Studio,
  sync, and send me the first error if there is one.
- Step 20 (v0.23.0) Followers / Following lists (plan section 4): the numbers on a profile are now tappable (my own Bio page and other people's profiles).
  * New screen with a Followers | Following switch, avatar + @username + name per person, tap a person to open their profile (the back button returns to the list),
    "Load more" after 50 people, empty-list texts in the five languages.
  * Server: `GET /users/{username}/followers` and `/following` (`offset`, `limit`, answers `more`). Rules: a missing user or any block between us = the same "not found";
    a private account I do not follow (or only requested) = refused, the list stays hidden (the app does not even make the numbers tappable); people who blocked me / whom I blocked
    are left out of the list. Checks: `cd backend && DEV_AUTH=1 python test_follow_lists.py`.
  HONEST LIMITS: (1) The big numbers on a profile still count everyone, the list leaves out blocked people, so the two can differ by a few. (2) No search inside the lists and no
  Follow / Unfollow button on each row yet (open the profile for that). (3) Same testing note as before: the new check passed here with stand-in fastapi / pydantic / httpx modules;
  please run `python test_follow_lists.py` once with the real packages. The Android part was not compiled here: open in Android Studio, sync, and send me the first error if there is one.
- Step 19 (v0.22.0) Privacy settings completed (plan sections 9 + 11): Settings > Privacy & Security now has two more real choices.
  * "Who can message me": Everyone / Only my followers / Nobody. Enforced on the SERVER when someone starts a new chat or adds you to a group
    (a private account still needs an accepted follower even under "Everyone"; blocked people can never message you). The profile page's Message button follows the same rule.
  * "Default audience for new posts": Public / Followers only. It is only the pre-selected choice on the New post and New short video screens; you can still change it each time.
  * Server: new columns `who_can_message` and `default_audience` (added automatically to existing databases), both returned by `GET /users/me` and changed with `PATCH /users/me`.
    Checks: `cd backend && DEV_AUTH=1 python test_privacy_settings.py`. Texts in the five languages (7 new rows in `Strings.kt`).
  * Also fixed: `test_chat.py` used an old stand-in for the AI reply (wrong arguments / return value), so it could not pass; it passes now.
  HONEST LIMITS: (1) Chats that already exist keep working after you change "Who can message me" (use Block to cut one off). (2) "Online / last seen" is NOT built: the app has no
  presence tracking yet, so no switch is shown. (3) Notifications settings are still not built (they need push notifications first). (4) The new test passed here with stand-in
  fastapi / pydantic / httpx modules (no internet in my workspace); the older checks (profiles, account lists, limits, reactions, chat) also still pass. Please run
  `python test_privacy_settings.py` once with the real packages. The Android part was not compiled here: open in Android Studio, sync, and send me the first error if there is one.
- Step 18 (v0.21.0) "Limit reached" popups (plan section 8): when the free AI allowance is used up, or the media storage is full, a popup explains it in the five languages.
  * AI limit: "Free AI limit reached" + the real hours until the 48-hour reset, with the choice WAIT FOR RESET or SEE PLANS (Sociax AI Pro, $10 per month, 5x the free allowance).
  * Media full: "Media storage is full" with the choice NOT NOW or SEE PLANS (Sociax App Pro, $3 per month, 600 MB instead of 150 MB) and a hint to delete posts / videos to free space
    (the media allowance is a total, it does not reset by itself). Both popups say that AI Pro and App Pro are two SEPARATE plans.
  * Works from every screen (post, short video, story, avatar uploads, private AI chat): the server now answers these two cases with a machine-readable `code`
    (`ai_limit` + `reset_in_seconds`, `media_full`) and the app shows one popup on top of everything. Other errors (e.g. "photo must be under 10 MB") stay plain text.
    Server checks: `cd backend && DEV_AUTH=1 python test_limits.py`.
  HONEST LIMITS: "See plans" opens Settings > Storage & plans, where the buttons still say that payment is not connected (Phase 7): nothing is charged. Texts inside the screens that show the
  server message (the small red line) are still in English; the popup itself is translated. The `@AI` answer in a group still uses its own notice (not this popup). The test passed here with
  stand-in fastapi / pydantic / httpx modules: please run it once with the real packages. The Android part was not compiled here: open in Android Studio, sync, and send me the first error if there is one.
- Step 17 (v0.20.0) Settings made real: Privacy & Security, Blocked users, Saved posts, Delete account (plan sections 9 + 11).
  * Settings > Privacy & Security: "Private account" switch (saved on the server; private = only approved followers see posts / stories and can message you),
    plus links to "Blocked users" (list of the people I blocked, each with Unblock) and "Saved posts" (photo grid of my bookmarks, tap one to open it with its
    comments; "Load more" after 30; the back button returns to the list). Settings > Account: "Edit profile" row, Log out, and "Delete account" (asks once more).
  * Server: `GET /users/me/blocked`, `GET /posts/saved` (goes through the same VISIBLE rule as the feed: a saved post of someone who went private or blocked me
    quietly disappears from my list), `DELETE /users/me` (removes the profile, posts, shorts, stories, comments / likes / saves / reactions, follows, blocks, media quota rows,
    AI usage, one-to-one chats and my messages; a group I own is handed to an admin, or to the longest member, and deleted if nobody is left; the @username becomes free
    again; also deletes the Firebase login, best effort). Checks: `cd backend && DEV_AUTH=1 python test_account_lists.py`.
  * Texts in the five languages (13 new rows in `Strings.kt`).
  HONEST LIMITS: (1) Files on Cloudinary are NOT deleted when an account is deleted (same open point as posts / stories: a clean-up job comes with the release phase),
  so storage there stays until that job exists. (2) Saved SHORT videos have no list yet (only saved photo posts). (3) The other privacy options in the plan
  (online / last seen, who can message, content audience as an account default) and the Notifications section are still not built: only the private-account switch is real.
  (4) The new backend check passed here with stand-in fastapi / pydantic / httpx modules (no internet in my workspace): please run `python test_account_lists.py` once with the real
  packages. The Android part was not compiled here: open in Android Studio, sync, and send me the first error if there is one.
- Step 16 (v0.19.0) Other people's profile pages + my own posts on Bio (plan section 4): tap a name / photo on a post or a search result to open the profile
  (photo, name, @username, bio, posts / followers / following, Follow / Requested / Following, Message, Block / Unblock, grid of photos; tap a photo to open it with its comments).
  My Bio page now shows my own photo grid. Server (`GET /users/{username}`, `GET /users/{username}/posts`, checks in `backend/test_profiles.py`):
  someone who blocked me or a missing user = the same "not found"; someone I blocked = only name + photo so I can unblock; a private account I do not follow
  (or only requested) shows no posts and not even the post count; "followers-only" posts only to accepted followers; Message only when messaging is allowed.
  HONEST LIMITS: the optional "Follow me first - would you like to read this?" prompt is not built; no blocked-users list screen yet (you can unblock from the profile);
  only the first 30 photos load (no "load more" yet); followers / following lists are not tappable yet. The backend rules were checked here with stand-in
  fastapi/pydantic modules (please run `python test_profiles.py` once with the real packages); the Android part was not compiled here.
- Step 15 (v0.18.0) Settings > "Storage & plans" (plan sections 8 + 3.1): shows the media used / left out of the 150 MB free allowance (bar + numbers, from the server)
  and the free AI requests used + reset time; then the two SEPARATE plans (Sociax AI Pro $10/month = 5x AI allowance, Sociax App Pro $3/month = 600 MB media),
  each with price, monthly billing, renewal/cancellation note and the allowance. Five languages.
  HONEST LIMITS: payment is NOT connected (needs store billing + server-side purchase verification = Phase 7), so the buttons read "Upgrade (coming soon)" and say
  that payment is not available yet; nothing is charged and nobody is upgraded. The exact cancellation terms depend on the payment provider and are still to be decided.
  The "limit reached -> offer upgrade or wait" popups are not built yet. Not compiled here: open in Android Studio, sync, send me the first error.
- Step 14 (v0.17.0) Sociax AI screen in the app: the Chat tab now starts with a "Sociax AI" card. It opens a private conversation with the assistant
  (Grok leads, Gemini assists) through `POST /ai/ask`; it shows "N of M free AI requests used - resets in H h" (counted on the server). The conversation
  is kept on the phone only while the screen is open; only the one question you send goes to the server, never anything from your other chats.
  Texts are in the five languages. `backend/.env` now holds the xAI and Gemini keys (Cloudinary key + secret still to be pasted).
  HONEST LIMITS: not compiled here (open in Android Studio, sync, send me the first error); not tested against the live providers (no internet here);
  answers are plain text (no images/files yet, no saved history); generated images/videos need a generation API that is not chosen yet (plan section 7).
- Step 13 (v0.16.0) AI connected on the backend: `backend/ai_providers.py` (Grok = lead via xAI `/v1/responses`, Gemini = assistant / fallback),
  keys only from `backend/.env`. New endpoints: `POST /ai/ask`, `GET /ai/status`; `GET /quota/ai` now shows used / remaining and the real reset time.
  In groups, `@AI` now really answers (only when an admin enabled AI, only the one message with @AI is sent, counted in the asker's free allowance;
  new notices when the allowance is used up or the AI is down, translated in 5 languages). Cloudinary was already wired (Step 2): just fill the 3 values in `.env`.
  HONEST LIMITS: (1) NOT tested against the live providers here (no internet in my workspace); only the fallback logic was tested with fake answers.
  Model names (`grok-4.6`, `gemini-2.5-flash`) are in `.env`: change them there if a provider rejects them. (2) No private AI chat screen in the app yet (the
  `/ai/ask` endpoint is ready for it): next step. (3) Free amount (10 / 48 h) is a placeholder; AI Pro x5 comes with billing (Phase 7).
  (4) Cloudinary API key + secret were not in your message (only a name), so you must add them.
- Step 12 (v0.15.0) Design fidelity pass 2: every colour, glass button and the bar were re-measured from the reference image and re-done.
  * Colours (dark mode = the reference): background is now cobalt `#2B56B6` at the top, a long calm blue middle and a quick fall into deep navy `#0B1220`
    (6 gradient stops in `ui/theme/Theme.kt`, same stops for the start-up window in `res/drawable/window_bg.xml`).
  * One glass system (`Theme.kt` + `Glass.kt`): `ControlFill` = the frosted blue-grey of ALL round buttons, reaction chips and the tab bar;
    `InsetFill` = the dark inset pill of the For you / Following switch, search field, comment / chat / story message bars and text fields;
    `PaperWhite` = the soft-white selected tab, Follow and primary buttons. Old names (`MediaGlassFill`, `BarFill`) still exist and point to the new values.
  * Home: white "S" + "Sociax" header (new `SociaxMark`, `res/drawable-nodpi/sociax_mark.png`), three round frosted buttons, "Add Story" glass circle with thin edge,
    dark inset For you / Following switch, author bar on posts = frosted pill with ringed avatar + soft-white Follow pill.
  * Tab bar now FLOATS above the page like the reference (pages scroll behind it; every page leaves room at the bottom with `bottomBarClearance()`),
    thin outlined tab icons, soft-white selected circle. Short Video is full-bleed behind the status bar and the bar.
  * Short Video: round frosted back button (goes to Home) + round + (upload) at the top, right rail = plain white outline icons with numbers (no circles),
    ringed avatar, reaction chips sit right above the bar.
  * Story viewer + chat composer: dark inset "Send Message..." pill with the soft-white paper-plane button inside it and a separate frosted round heart (orange heart) beside it.
  * Logo: unchanged artwork (your supplied image, used on login / onboarding / launcher icon); the Home header uses its one-colour white "S" form like the reference.
  HONEST LIMITS: (1) NOT compiled here (no Android SDK / Maven access in my workspace): open in Android Studio, sync, and send me the first error if there is one.
  (2) The reference has real background blur behind the glass; Jetpack Compose cannot blur what is behind a view without an extra library (Haze), so the glass is a
  tinted translucent fill matched to the measured colours. (3) The reference tab bar has 5 icons; the plan says exactly 4 tabs, so 4 are kept.
  (4) The reference Home has no search field and no emoji row; the plan needs search, and the emoji reactions were added in Step 8, so both stay (restyled).
  (5) Music line, share counter, emoji "add" button and "followers" count under the author name are in the reference but have no feature / data in the plan, so they are not shown.
  (6) Light Mode is still the lighter-blue version of the same look (white-surface light theme not built yet).
- Step 11 (v0.14.0) Text-only stories, like the middle reference screen: in New story choose Photo or Text; the Text tab shows a live preview
  (cinematic blue background, large centred words, soft grey at the top getting brighter downwards) and up to 200 characters.
  The viewer shows the same look (progress bars, avatar + name + time, reply bar "Send Message..." + heart, "Seen by N" for the owner).
  Server: `POST /stories/text`; stories now carry `kind` ("photo" | "text"). A text story uses NO media quota, lives 24 hours and follows exactly
  the same privacy rules as photo stories (owner + ACCEPTED followers only, blocked people and pending requests see nothing, hidden = "not found").
  No database change is needed (a text story is stored with an empty photo id), so existing databases keep working.
  Checks: `cd backend && DEV_AUTH=1 python test_text_stories.py` (passed here with stand-in fastapi/pydantic modules; please run it once with the real packages).
  HONEST LIMITS: one background look only (no colour / font choices yet); the text field is a single line (the preview shows the full text);
  the first words are not "typed in" animated like some apps; the song / music line under the name in the reference is not built (the plan has no music feature).
  Android part was not compiled here: open in Android Studio, sync, and send me the first error if there is one.
- Step 10 (v0.13.0) Short Video screen now follows the right-hand reference screen: reaction row under the caption (♥ + 😮 😂 😢 with counts,
  same chips as Home), right-hand rail = comments, save (bookmark, with count), share, more (the heart moved into the reaction row),
  caption shows "Read more" / "Show less" when it is longer than two lines (translated in the five languages).
  Server: new tables `short_reactions` and `short_bookmarks`; endpoints `POST/DELETE /shorts/{id}/react/{wow|haha|sad}` and
  `POST/DELETE /shorts/{id}/bookmark`; every short now carries `reactions`, `saved`, `save_count`. Same visibility as likes (private accounts,
  followers-only, blocked users, missing shorts all answer "not found"); one row per person + emoji; deleted with the short.
  Checks: `cd backend && DEV_AUTH=1 python test_short_reactions.py` (passed here with stand-in fastapi/pydantic modules; please run it once with the real packages).
  Also fixed: `test_reactions.py` used a wrong upload id (`.../p1` instead of `.../post_1`), so it could not pass; it passes now.
  HONEST LIMITS: NOT in this step (the reference shows them, but the plan has no such feature yet): music line under the name ("Blinding . Lights"),
  share counter (we do not count shares), emoji "add" button, the round back arrow (Short Video is a tab here), and the fifth tab (the plan says exactly four).
  Text-only stories (middle reference screen) are the likely next step. Android part was not compiled here: open in Android Studio, sync, and send me the first error if there is one.
- Step 9 (v0.12.0) Five languages now cover the rest of the app: Chat (list, conversation, new chat, new group, group info, AI Join texts),
  Short Video (feed menu, create / trim / preview / publish flow, error messages), new post, comments, search, follow requests,
  edit profile, stories (create, viewer, "Seen by"), report menus. About 200 texts in total, all in `ui/theme/Strings.kt`
  (`tr("key")`, and `trf("key", values)` for texts with %s / %d). Report reasons are shown translated, the English word is what is sent to the server.
  HONEST LIMITS: still English: text that comes from the SERVER (system messages in groups such as "AI was turned on", server error messages),
  relative times ("5m ago"), a few notices created outside the screens (e.g. the "report received" notice on a Home post, Share sheet titles),
  and the content users write themselves (of course). Every translation is machine-quality: please have a native speaker check the five languages
  before release. Not compiled here: open in Android Studio and send me the first error if there is one.
- Step 8 (v0.11.0) Emoji reactions on posts, like the reference (row under the photo: ♥ 😮 😂 😢 with counts; the comment / share / save row moved below it).
  The heart stays the normal "like"; 😮 😂 😢 are new, each person can use any of them (tap again to remove), your own ones look brighter.
  Server rules: same visibility as likes (private accounts, followers-only posts, blocked users and missing posts all answer "not found"),
  one row per person + emoji (no double counting), reactions are deleted with the post. New table `post_reactions`, endpoints
  `POST/DELETE /posts/{id}/react/{wow|haha|sad}`, and every post now carries `reactions`. Checks: `cd backend && DEV_AUTH=1 python test_reactions.py`.
  HONEST LIMITS: the new backend test could not be run where this was written (no FastAPI installed there); the SQL itself was run in SQLite
  and gives the right counts. Reactions are on Home posts only (not Short Video yet). Needs the updated backend running.
- Step 7 (v0.10.0) Settings: appearance + five languages.
  * Languages: English (default), Français, 中文, 한국어, Русский. Pick one in Settings > Language; it is saved. On first start the app
    follows the phone language if it is one of the five. Translated now: bottom-bar names, login, onboarding (incl. validation messages and
    "where did you hear" options), Home (search, For you / Following, Follow / Requested, empty texts, Add Story), Bio / profile page, and the
    whole Settings screen. All texts live in `ui/theme/Strings.kt` (one row per text, 5 columns) - add a row there and use `tr("key")`.
  * Appearance: Dark Mode / Light Mode / System Default (list with check mark), Accent colour (5 choices; teal is the original),
    Text size (Small / Normal / Large), Reduce Motion (chat scrolls without animation, story timer moves in steps). All saved across sessions.
  * About: shows the app version.
  HONEST LIMITS: Chat, Short Video, comments, search and the error messages that come from the server are still English only (next passes).
  Light Mode is still the lighter blue version (white text); the white-surface light theme needs a colour-token pass over every screen.
  The accent colour changes buttons' highlights, switches, story rings, unread badges and the profile photo ring - not the blue background.
  This build was again written without a compiler: open it in Android Studio, sync, and send me the first error if there is one.
- Step 6 (v0.9.0) Stories: story rings on Home like the reference ("Add Story" + people you follow; bright ring = new, faint ring = seen).
  Share a photo story (optional text up to 200 characters), full-screen viewer with progress bars (5 s per photo, tap right = next,
  tap left = back, hold = pause, typing a reply = pause), reply bar "Send Message..." + heart (sent as a normal private message to the owner,
  so the usual messaging privacy rules apply), owner can delete a story and sees only HOW MANY people viewed it (never who).
  Rules enforced on the SERVER: a story is visible only to its owner and to people with an ACCEPTED follow; blocked people never see it;
  private accounts need an accepted request; search never shows stories; hidden, expired and missing stories all answer "not found";
  stories expire after 24 hours and give their media quota back; the 150 MB combined media quota and the 10 MB photo limit apply.
  Backend checks: `cd backend && DEV_AUTH=1 python test_stories.py`.
  HONEST LIMITS: expired / deleted story photos are removed from the app and from your quota, but the file itself is not yet deleted from Cloudinary
  (same as posts today) - a clean-up job comes with the release phase. Photo stories only at that step (text-only stories came in Step 11; no video stories yet).
  Reporting a story is not built yet.
- Step 5.5 (v0.8.0) Design fidelity pass (look matches the reference screens and the logo image):
  * Logo: the supplied logo image is now used in higher resolution with clean rounded transparent corners (login, onboarding, Home header).
  * Background: cobalt -> deep navy gradient plus a soft light glow in the top-left, drawn behind the status bar and navigation bar
    (edge-to-edge, transparent system bars), so there is no dark band at the top. The start-up screen uses the same gradient.
  * Dark mode is the default and now saved across sessions (Settings > Appearance: Dark / Light / System). Light = a lighter blue version of the same
    cinematic look (white text kept readable). A white-surface light theme with dark text comes with the full Settings step.
  * Home header like the reference: logo + "Sociax" on the left; round glass buttons (+ new post, bell = follow requests, chat = open Chat tab) on the right.
    Refresh moved into the search pill. Post cards use darker glass chips/buttons on top of the photo (like, comment, share, save, more).
  * Bottom bar: dark glass pill with glass circles; the selected tab is a white circle with a navy icon (still exactly four tabs).
  * Pages opened on top of the tabs now keep clear of the navigation bar.
  NOT in this step (honest list): Stories row (needs backend), true light theme, accent colour / text size / Reduce Motion, the five languages
  (English, French, Chinese, Korean, Russian), emoji-reaction row on posts. These come next. This build was written without a compiler available,
  so please open it in Android Studio, sync, and tell me the first error if there is one.
- Step 5 (v0.7.0) Chat + groups + AI Join: Chat tab lists private chats and groups with unread badges; one-to-one chat, glass composer with
  send + heart (like the reference); create a group in 3 steps (name -> members -> summary -> explicit confirmation); group info with roles
  (owner / admin / member), add / remove / leave / delete; AI Join (OFF by default, only owner/admin can switch it, every member is told).
  Rules enforced on the SERVER: outsiders always get "not found" (a private chat or group's existence is never revealed), groups never appear in
  search, blocked people cannot message each other and their chat disappears, private accounts can only be messaged by accepted followers,
  AI never sees one-to-one chats and in a group only receives the single message that contains @AI (no history).
  Backend checks: `cd backend && DEV_AUTH=1 python test_chat.py`.
  HONEST LIMITS: "realtime" here = the app asks the server every ~2.5 s while a chat is open (works, simple, not push). Firebase Realtime Database
  / push notifications come later. Text messages only (photos/voice in chat later). The AI itself is Phase 6: until a provider is connected, @AI in an
  enabled group shows "AI is not connected yet" to the sender and nobody else. Group size cap of 100 is a technical safety value (the plan sets none).
- Step 4 (v0.6.0) Short Video: full-screen vertical feed (swipe), autoplay + Data Saver (480p copy), like / comment / share / follow / report / delete,
  create flow: choose from Gallery or record with the camera app -> pick 20 s or 25 s -> clips longer than that MUST be trimmed by the user
  (never published silently) -> compressed on the phone (Upload quality setting) -> preview -> caption, audience, comments on/off -> Cloudinary upload.
  The server re-checks type, size, duration (max 25 s) and quota on the file that was really uploaded, and deletes rejected clips.
  Same privacy rules as posts (private accounts, followers-only, blocks). Backend checks: `cd backend && DEV_AUTH=1 python test_shorts.py`.
- Step 3.5 (v0.5.0): the real Sociax logo image is used on the login/onboarding screens and as the app icon (normal + adaptive);
  background gradient moved closer to the reference screens.
- Design system, logo, 4-tab shell, real Google login, onboarding (15+, unique @username), profile edit + photo.
- Step 3: Home feed (For you / Following), photo posts (public or followers-only), like, comment, save, share, delete, report,
  follow / unfollow, private accounts with follow requests, block (backend), search (people + public posts only).
- Privacy is enforced on the server: private accounts, followers-only posts and blocked users never appear to unauthorized people.
  (Backend rules were tested with 19 automated checks; see below.)

Not done yet (plan v3.3): AI Create flow (image / video generation, provider not chosen), white-surface Light Mode, live (non-test) payments and automatic renewal, calls (Phase 8). (Read receipts: built in Step 43.) Android app is not yet compiled or tested on a device.

## GitHub repository structure (v0.32.0)
Follows `Sociax_GitHub_Structure_and_Plan.md`. At the TOP of the repository (not inside a sub-folder):
`.github/workflows/build.yml`, `app/`, `backend/`, `gradle/wrapper/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew`, `gradlew.bat`, `.gitignore`, `README.md`.
- There is exactly ONE workflow: `.github/workflows/build.yml` ("Sociax Android APK Build"). If your repository still has an older workflow file
  (for example `build-apk.yml` or anything using `working-directory: ./android`), delete it on GitHub so the APK is not built twice.
- `gradle/wrapper/gradle-wrapper.jar` is a binary produced by Gradle. If it is missing in your repository, run once on a computer or Codespace that has Gradle:
  `gradle wrapper --gradle-version 8.9` and commit `gradle/wrapper/gradle-wrapper.jar`. Until then the workflow generates it during the run, and `./gradlew` falls back to Gradle 8.9.
- Secrets: never commit `backend/.env`, release keystores, passwords, API keys or Firebase service-account files (all covered by `.gitignore`).
  `app/google-services.json` (Firebase client config) is allowed. `sociax-debug.keystore` is a DEBUG key (password `android`) kept so the Google sign-in SHA-1 stays the same; never use it for a Play Store release.

## Build the APK on GitHub (no Android Studio needed)
1. Create a GitHub repository and upload the CONTENTS of this folder (the `.github` folder must stay at the top level). Never upload `backend/.env`.
2. Run the backend on Render (`render.yaml` is ready) and copy its address, e.g. `https://sociax-backend.onrender.com`.
3. GitHub > Settings > Secrets and variables > Actions > **Variables** > New repository variable: `SOCIAX_API_URL` = that address. (Without it the APK only works in the emulator.)
4. GitHub > Actions > "Sociax Android APK Build" > Run workflow (it also starts by itself after every upload). Wait ~10 minutes.
5. Open the finished run > **Artifacts > Sociax-Android-APK** > download, unzip, install `Sociax-v<version>.apk` on the phone (allow "install unknown apps").
6. Google sign-in: the run prints the SHA-1 of `sociax-debug.keystore` (it is the same in every build). Add it in Firebase > Project settings > your Android app > SHA certificate fingerprints,
   then download the NEW `google-services.json`, replace `app/google-services.json`, and upload again.
   SHA-1 of the included key: `D4:65:DE:A2:13:EE:ED:91:A2:A3:B1:8E:44:71:5D:FE:20:B6:09:11`

## Testing backend rules
The visibility logic lives in `backend/main.py` (`VISIBLE`). Run the server, create two users with `DEV_AUTH=1` tokens
(`Authorization: Bearer dev-a`, `dev-b`) and call `/feed`, `/users/{name}/follow`, `/search`.

## Chapa payments (v0.26.0, TEST mode)
- Sociax AI Pro = $10 USD, one-time, 30 days (not a subscription). The price is fixed on the server.
- Sociax App Pro = $3 USD, one-time, 30 days, 600 MB combined media (v0.29.0). Separate product with its own expiry: `POST /payments/initialize?product=app_pro`.
- Put your Chapa TEST secret key in `backend/.env` as `CHAPA_SECRET_KEY=` (server only; never in the app or GitHub).
- Endpoints: `POST /payments/initialize`, `GET /payments/verify/{tx_ref}`, `GET /payments/status/{tx_ref}`.
- Pro is granted only after the server verifies the payment directly with Chapa. Live keys are refused until `CHAPA_ALLOW_LIVE=1`.
- Test: `cd backend && DEV_AUTH=1 python test_payments.py`

### Chapa in the Android app (v0.27.0)
- Settings > Storage & plans > "Buy AI Pro — $10 / 1 month" asks the server for a checkout link and opens it in the browser.
- When you come back to the app it asks the server to verify with Chapa (or tap "I paid — check my payment").
- For a REAL phone the backend must run on a server the phone can reach: set the GitHub variable `SOCIAX_API_URL`
  (default `http://10.0.2.2:8000` only works in the Android emulator) and put `CHAPA_SECRET_KEY` in that server's secret settings.
