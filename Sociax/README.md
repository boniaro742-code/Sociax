# Sociax

Android (Kotlin + Jetpack Compose) + FastAPI backend, built from the Sociax Master Plan v3.1.

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

Not done yet: white-surface Light Mode, push notifications, live (non-test) payments and automatic renewal, calls (Phase 8), image/video generation (provider not chosen). Android app is not yet compiled or tested on a device.

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
