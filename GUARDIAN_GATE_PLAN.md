# Guardian Gate: implementation plan

Base: AntennaPod `3.12.3` (tag), branch `guardian-gate` in `Amitai-TTO/AntennaPod`.
Original wish list: `~/code/GabAI/groups/dm-with-amitai/reference/antennapod-gated-spec.md`.
This plan replaces it where they differ (it was written before reading the code).

Goal: adding a NEW podcast needs a code held by a guardian. Existing subscriptions keep
refreshing, downloading and playing with no code. Accountability tool, not anti-tamper.

## 1. How subscribing works in AntennaPod (verified in code)

Feed states (`model/.../feed/Feed.java:21-23`): `SUBSCRIBED=0`, `NOT_SUBSCRIBED=1` (preview), `ARCHIVED=2`.
`Feed.state` defaults to 0 = SUBSCRIBED, so `new Feed(url, ...)` saved to the DB is a subscription.

Two ways a new subscription is born:

**A. Preview, then the Subscribe button** (search, directories, "add by URL", every intent / deep link / share):
1. `OnlineFeedViewActivity.parseFeed` (`app/.../onlinefeedview/OnlineFeedViewActivity.java:293-301`) saves the feed as `NOT_SUBSCRIBED` via `FeedDatabaseWriter.updateFeed`.
2. User taps Subscribe: `FeedItemlistFragment.java:553-557` or `FeedInfoFragment.java:252-254` calls
   `DBWriter.setFeedState(ctx, feed, STATE_SUBSCRIBED)` (`storage/database/.../DBWriter.java:862`).
   The NOT_SUBSCRIBED to SUBSCRIBED branch is `DBWriter.java:869-880`.

**B. Direct insert as SUBSCRIBED** through `FeedDatabaseWriter.updateFeed` (`.../FeedDatabaseWriter.java:58`), new-feed branch `savedFeed == null` (:67-69) leading to `DBWriter.addNewFeed` (:208):
- OPML import: `app/.../activity/OpmlImportActivity.java:99-102` (exported activity, other apps can open it)
- Add local folder: `app/.../ui/screen/AddFeedFragment.java:221-223`
- gpodder / Nextcloud sync: `net/sync/service/.../SyncService.java:170-172` (background, no UI)
- Android cloud-backup restore: `storage/importexport/.../OpmlBackupAgent.java:143-146` (background, `android:backupAgent` in manifest :35)

**C. Bypasses everything:** full database restore, `DatabaseExporter.importBackup` (`storage/importexport/.../DatabaseExporter.java:76`), a raw SQLite file swap, started from Settings > Import/Export (`ImportExportPreferencesFragment.java:189` / `:256`).

Not "new": ARCHIVED to SUBSCRIBED (Restore button `FeedItemlistFragment.java:567`, `RemoveFeedDialog.java:84`). Refreshes of existing feeds (`FeedUpdateWorker`, `LocalFeedUpdater`) keep the saved state.
Checked and found no subscribe path: widget, Echo, Wear OS, Android Auto (Auto only read, not fully traced).

## 2. Design

### 2.1 `GuardianGate` (new class)
Put it in `:storage:preferences` so the DB, sync, UI and import modules can all reach it.
- Own `SharedPreferences` file: PBKDF2 hash + random salt, open-until timestamp, failed count, lockout-until.
- `isOpen()`, `verify(code)`, `setCode(code)`, `openFor(minutes)`, `lock()`, `isConfigured()`.
- Correct code opens a window (default 10 min, guardian-configurable), or until the app has been in the background more than 1 min.
- Five wrong codes, then a lockout of 1, 2, 4... minutes. It survives restarts.
- No recovery. Clearing app data wipes the gate and the subscriptions together.

### 2.2 Data-layer gates (the real enforcement, catches every path)
- `FeedDatabaseWriter.updateFeed`: if `savedFeed == null` and `newFeed.getState() != STATE_NOT_SUBSCRIBED` and the gate is closed, do not insert. Throw a `GuardianGateClosedException` (or return null), and handle it in callers.
- `DBWriter.setFeedState`: if `oldState == NOT_SUBSCRIBED && newState == SUBSCRIBED` and the gate is closed, refuse.
- Previews still work (they are `NOT_SUBSCRIBED`). Refreshes of existing feeds still work.

### 2.3 UI prompts (so the user sees a code dialog instead of a failure)
One reusable `GuardianCodeDialog` (in `:ui:common` or `:app`): `requestOpen(activity, onOpen)`.
- "+" FAB on Subscriptions: `SubscriptionFragment.java:163-168`. Prompt before opening `AddFeedFragment` (the owner's main requirement).
- Every other route into `AddFeedFragment`: drawer / bottom-nav "Add podcast" (`MainActivity.createFragmentInstance` :452-453, `NavigationNames`), default start page (`MainActivity.java:189/619`), `EXTRA_FRAGMENT_TAG` (:753-762). Also `DiscoveryFragment` (:461) and "search online" in `SearchFragment.java:472-485`.
- Subscribe buttons (`FeedItemlistFragment:553`, `FeedInfoFragment:252`). Prompt, then subscribe.
- OPML import confirm (`OpmlImportActivity:90`), add local folder, Settings > Database import (`ImportExportPreferencesFragment:189`). Prompt first.
- Incoming links (`OnlineFeedViewActivity`): let the preview load and gate only Subscribe. Decision D3 is whether to gate earlier.

### 2.4 Background paths
- Sync: at `SyncService.java:170`, if closed, skip the remote add and save the URL in a "pending" list (`lastSync` advances at :210, so it would otherwise be lost). Show a notification: "N podcasts from sync are waiting for the guardian code". Approving them opens the gate and runs `updateFeed` on each.
- Android backup agent: remove `android:backupAgent` from the manifest (simplest), or gate inside `restoreEntity`.

### 2.5 Guardian settings screen
New XML `ui/preferences/src/main/res/xml/preferences_guardian.xml`, a fragment extending `AnimatedPreferenceFragment`, wired in `preferences.xml`, `PreferenceActivity.getPreferenceScreen/getTitleOfPage`, and `MainPreferencesFragment.setupMainScreen`.
Everything on it needs the code: change code, window length, gate unsubscribing (optional, off by default), disable gate.
First run with no code set: show "Hand the phone to your guardian" and set the code twice.

### 2.6 Separate app identity (installs next to stock AntennaPod)
- `app/build.gradle`: add `applicationId "<new id>"`. Keep `namespace`.
- `provider_authority` resValues (`app/build.gradle:33,36`) plus the fallback in `ui/i18n/.../values/strings.xml:11`.
- `app_name` resValues (`common.gradle:22,25`), e.g. "AntennaPod Guarded".
- Remove the Google backup `api_key` meta-data (manifest :66-68).
- GPL: `MainPreferencesFragment.java:40-64` shows a red "violates the GPL" notice for any other package. Per its own comment: update About / Report-bug to point to our fork, keep the source public (the fork is public), then remove those lines.
- Build only the `free` flavor (no Wear / Cast / Play services). The Wear app is hard-wired to the stock id.
- Expect both apps to offer themselves for podcast links (a chooser). That's fine.

## 3. Build environment

- Needs JDK 21 (not 17), Android SDK platform 36, Gradle wrapper 8.13 (AGP 8.11). `-Werror` and lint `abortOnError` are on.
- The VPC has no JDK or SDK yet, and only 2 cores and 7 GB RAM. Builds will be slow and memory-heavy.
- **Recommended:** build the APK with GitHub Actions on the fork (upstream already has CI at `.github/workflows/`). Keep signing secrets in repo secrets. The VPC is then only used for editing and unit tests, if at all.
- Commands (from AGENTS.md): compile `./gradlew :app:assembleDebug`, tests `./gradlew --console=plain :<module>:test`, style `./gradlew checkstyle lint`.
- Release signing: `releaseStoreFile/releaseStorePassword/releaseKeyAlias/releaseKeyPassword` gradle properties (`app/build.gradle:19-28`). Keep the keystore outside the repo and back it up.

## 4. Tests
- Robolectric unit tests next to `storage/database/src/test/.../FeedDatabaseWriterTest.java`: closed gate blocks a new SUBSCRIBED insert, allows NOT_SUBSCRIBED preview, allows refresh of an existing feed. `setFeedState` preview to subscribed is blocked when closed and works when open.
- `GuardianGate` tests: hash and verify, lockout growth and persistence, window expiry.
- `SyncService`: a closed gate queues adds as pending.
- Manual phone checklist in `GUARDIAN_GATE.md`: every path in section 1, plus "existing podcasts still auto-update while locked".

## 5. Order of work
1. Identity rename + GPL notice + build in CI. Get a debug APK onto the phone early.
2. `GuardianGate` + tests.
3. Data-layer gates (2.2) + tests.
4. UI prompts (2.3), "+" first.
5. Settings screen + first-run setup (2.5).
6. Sync pending list + backup agent (2.4).
7. Manual checklist on a real phone, then a signed release APK.

## 6. Decisions for the owner
- D1. App id and name (e.g. `org.tefillinconnection.antennapod.guarded` / "AntennaPod Guarded").
- D2. Sync adds while locked: queue as pending for approval (recommended), or drop them.
- D3. Links from outside (browser, share): show the preview and gate only Subscribe (recommended), or ask for the code before showing anything.
- D4. Gate unsubscribing too? (spec: optional, off)
- D5. Android cloud backup: remove it (recommended) or gate the restore.
- D6. Build in GitHub Actions (recommended) or install JDK + SDK on the VPC.
