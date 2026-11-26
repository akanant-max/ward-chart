# Ward Chart — Android

An offline Android build of the Ward Chart, with sync between phones through a
JSON file in your Google Drive.

The whole page is bundled into the app, state lives on the phone, and the app
declares **no INTERNET permission** — it works with the network off, which is
the point of building it at all.

Changing anything substantial? Read **ARCHITECTURE.md** first, and hand that file
to an LLM before asking it to make changes. This file is just setup.

---

## Get the APK without Android Studio

This repo builds itself. Every push to `main` runs GitHub Actions, which has the
Android SDK, builds a signed APK, and attaches it to a release.

On the phone: **github.com/akanant-max/ward-chart/releases/latest** → tap the
`.apk` → install. Allow installs from your browser if Android asks.

Every build is signed with the same key (`keystore/ward-chart.jks`, committed on
purpose — its only job is letting each new APK install over the old one). So
after the **first** install from GitHub you never uninstall again; updates keep
your data.

That first time: the copy currently on your phone was signed by Android Studio's
own debug key, which is different, so Android will refuse to update it. Export
from Setup → Backup first, uninstall, install the GitHub build, import (or let
the sync file pull it back).

Android Studio still works too — its builds now use the same committed key, so
Studio and GitHub builds install over each other interchangeably.

## Build it yourself in Android Studio

1. Android Studio → **Open** → pick this `WardChart` folder (the one with
   `settings.gradle.kts`). Use Open, not Import.
2. Let it sync. The first sync downloads the Android SDK, Gradle 8.7 and two
   libraries — that needs a connection and a few minutes. Only the *build* needs
   the network; the finished app never does.
3. If it offers to upgrade the Android Gradle Plugin, accept. Versions here are
   pinned to a known-good set, not the newest.
4. Phone plugged in with USB debugging on → **Run ▶**. Or **Build → Build
   Bundle(s) / APK(s) → Build APK(s)** and copy the APK across by hand.

From the command line: `./gradlew assembleDebug`, APK lands in
`app/build/outputs/apk/debug/`.

---

## Set up sync

**First phone:** Setup tab → Sync → **New sync file** → when the picker opens,
choose Google Drive and save `ward-chart.json` there.

**Second phone:** Setup → Sync → **Use an existing one** → pick that same file
from Drive.

That's it. From then on each phone syncs when the app opens, when you switch
back to it, and about a second after any change.

### What it does when both phones have been offline

Merging is **field by field, not file by file**. Ticks on one phone and topics
logged on the other both survive — whichever syncs second does not erase the
first. Deleting a topic sticks rather than coming back on the next sync.

The one case that does lose something: editing the **same** item on both phones
while both are offline. There the later edit wins. That's a deliberate limit;
ARCHITECTURE.md explains why fixing it properly isn't worth it here.

### Why a file instead of signing in with Google

The honest reason: a real Drive API integration needs a Google Cloud project, an
OAuth client registered against your signing certificate, and a consent screen —
setup you'd have to do by hand. The file-picker route needs none of it, keeps the
file visible in your own Drive, and the sync logic is identical either way.
ARCHITECTURE.md §7 specs the OAuth upgrade if you ever want it.

---

## How it stores things

The page asks for `window.claude.use("db")`. On claude.ai that's the artifact's
server-side store; here `app/src/main/assets/shim.js` answers the same calls from
`localStorage`, so the chart's own code is **unchanged** between the two.

`WebViewAssetLoader` serves the page from `https://appassets.androidplatform.net`
rather than a `file://` URL. That matters: `localStorage` on a `file://` origin
is unreliable across Android versions; on a real origin it isn't.

**The app and the claude.ai version do not sync to each other.** They're separate
copies. Move data between them with Setup → Backup — export one, import the other.

---

## Reminders

**Alerts** tab. Every block and every chore has its own switch, all **off** until
you turn one on.

- A **block** gets a time, the weekdays it applies to, and an optional repeat
  ("again every 10 min", three more times) until you tick it. Ticking the block
  cancels the rest and clears the notification.
- A **chore** gets a time, how many days before it's due to start, and an
  optional "daily until done". Doing the chore moves the next reminder with it.

The first time you switch one on, Android asks to allow notifications. On
Android 12 there's a second, optional grant for exact timing; without it a
reminder can land up to 10 minutes late and the tab says so. **Send a test**
proves the whole path works.

Reminders are armed a week ahead and re-armed every time the app opens. If the
phone restarts or the app is updated they come back on their own. Both phones on
one sync file get the same rules and both fire.

Keep the count small. Three or four well-placed reminders beat eighteen muted ones.

## Which build am I on?

Setup tab, bottom: a line reading **Ward Chart app 1.5** (or whatever the current
version is). If that line is missing you're on a build older than 1.1, which
predates grouped blocks and the reorder arrows — rebuild from the newest zip.

## Reordering the daily list

Setup → Daily blocks is grouped under its divider. The **↑ ↓** arrows move a
block within its divider; the dropdown moves it to a different one. Adding a
block puts it at the end of the divider you chose, so walk it up from there.

## Two differences from the web version

- **System fonts.** Archivo and IBM Plex Mono come from Google Fonts, which needs
  a connection. The offline build uses the phone's own fonts. To get them back,
  drop the `.ttf` files into `app/src/main/assets/fonts/` and add `@font-face`
  rules — or add
  `<uses-permission android:name="android.permission.INTERNET" />` to the
  manifest and restore the stylesheet link.
- **Export goes to Downloads**, written through the Kotlin bridge, with a toast
  naming the file.

---

## Files worth knowing

| Path | What it is |
|---|---|
| `ARCHITECTURE.md` | The working spec. Read before changing anything. |
| `app/src/main/assets/index.html` | The chart, built from `ward-chart.html` |
| `app/src/main/assets/shim.js` | Storage layer and all the sync merge logic |
| `app/src/main/java/com/wardchart/app/MainActivity.kt` | WebView host, file picker, JS bridge |
| `tools-build-asset.py` | Rebuilds `index.html` from the shared page |
| `app/src/main/AndroidManifest.xml` | No permissions requested |

To update the chart after editing the shared page:

```
python3 tools-build-asset.py
./gradlew assembleDebug
```

`shim.js` is edited in place — no build step.

---

## Google account backup

`allowBackup` is on, so Android's Auto Backup pushes app data to your Drive and
restores it on a reinstall or a new phone. Treat it as a bonus rather than the
plan: it restores WebView storage through a LevelDB, which is finicky. The sync
file is the dependable path.

---

## Pinned versions

AGP 8.5.2 · Gradle 8.7 · Kotlin 1.9.24 · compileSdk 34 · minSdk 26 ·
appcompat 1.7.0 · webkit 1.11.0. Chosen for a first-try sync, not for being
current.
