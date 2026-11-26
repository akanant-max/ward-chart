# Ward Chart — working spec

Hand this whole file to an LLM before asking it to change anything. It is
written to be read cold: it says what the project is, what the data looks like,
what may not be broken, and how each feature actually works.

If you only read one section, read **Invariants**.

---

## 1. What this is

A habit and revision tracker for a first-year MBBS student. Three jobs:

1. **Daily blocks** — a checklist that resets each day, grouped under headings
   the user controls ("dividers": Morning / Lunch / Evening / Night by default).
2. **Rolling chores** — laundry, sheets, shoes. Each is due a fixed number of
   days *after it was last done*, never on a fixed weekday.
3. **Topic revision** — a topic is logged the day it is taught and comes back at
   widening intervals (3 → 10 → 30 → 90 → 180 days). This is topic-level
   ("do I still know this area?"), deliberately **not** flashcards; the user
   runs Anki separately for facts.

### The design rule behind all of it

Nothing is tied to a weekday, and nothing accumulates a backlog. A missed day
reschedules itself, because the whole point is that falling behind must not
produce a wall of overdue items — that is what makes people abandon trackers.
Two features exist only to protect this and **must not be removed as clutter**:

- **The daily cap** (`config.cap`, default 4) — only N revision topics are shown
  on Today however many are overdue.
- **Light day** — one button pushes everything due to tomorrow *except* blocks
  marked `floor: true`, which are the irreducible minimum.

---

## 2. The two deployments

The same page runs in two places. **This is the single most important thing to
understand before editing.**

| | claude.ai artifact | Android app |
|---|---|---|
| Page source | `ward-chart.html` | same file, built into `assets/index.html` |
| Store | artifact `db` capability (server-side) | `localStorage` via `assets/shim.js` |
| Needs network | yes | no |
| Cross-device | automatic, via the user's Claude account | via a JSON file in Google Drive |
| Fonts | Archivo + IBM Plex Mono from Google Fonts | system fonts |

The page's own code is **identical** in both. It asks for
`window.claude.use("db")` and `use("downloads")` and does not know or care which
backend answers. On claude.ai the platform provides them; in the app `shim.js`
provides them with the same call shapes.

**Keep it that way.** If you add a feature that needs storage, add it through the
`db` interface, not through `localStorage` directly, or it will work in one
deployment and silently fail in the other.

### Build step

`tools-build-asset.py` produces `assets/index.html` from `ward-chart.html`. It:

- strips the Google Fonts `<link>` (the app holds no INTERNET permission)
- strips the `claude:viewport-fit-sides` meta (artifact-host only)
- wraps the page in a real HTML skeleton with the same CSS reset the artifact
  host injects
- adds `<script src="shim.js"></script>` in the head

`shim.js` is a sibling asset, not inlined, so it can be edited independently.

---

## 3. Data model

Four record paths. Even segment counts are documents, odd are collections —
inherited from the artifact `db` grammar and preserved by the shim.

```
config/main    { version, cap, intervals[], subjects[], bands[], blocks[], chores[] }
state/chores   { done: { <choreId>: "YYYY-MM-DD" } }
state/days     { log:  { "YYYY-MM-DD": [blockId, ...] } }
topics/<id>    { subject, title, taught, due, stage, reviews, lastReviewed, example }
```

### config/main

```js
{
  version: 1,
  cap: 4,                                  // revision topics shown per day
  intervals: [3, 10, 30, 90, 180],         // days between revision passes
  subjects: ["Anatomy", "Physiology", "Biochemistry"],
  bands: ["Morning", "Lunch", "Evening", "Night"],   // divider names, in order
  blocks: [ { id, band, label, note?, floor? } ],
  chores: [ { id, label, every, note? } ]            // every = days between
}
```

`block.band` is a **string matching an entry in `bands`**, not an index.
Renaming a divider therefore has to rewrite every block that referenced it —
`renameBand()` does this. Blocks whose band is not in `bands` are not lost: they
render under an "Unsorted" heading on Today.

### topics/&lt;id&gt;

### Reminders (optional, on blocks and chores)

```js
block.remind = { on: false, time: "08:00", days: [1,1,1,1,1,1,1], nag: 0 }
chore.remind = { on: false, time: "18:00", lead: 0, nagDaily: false }
```

`days` is Mon..Sun. `nag` is minutes between repeats (0 = none; capped at
`REM_NAG_REPEATS` = 3 extra fires). `lead` is days before the due date to fire
first. Absent `remind` means off; code must treat it as optional. Because it
lives in `config`, it exports, imports and syncs with no extra work.

### topics/&lt;id&gt; (continued)

`stage` indexes `config.intervals`. Reviewing as *Solid* advances a stage,
*Shaky* drops one, and `due` becomes `today + intervals[stage]`. `example: true`
marks the seeded demo rows so the UI can offer to clear them.

### Dates

**Every date is a local `YYYY-MM-DD` string, never a UTC timestamp.** `ds()`
offsets by the timezone before slicing an ISO string, precisely so a tick at
11pm IST does not land on the following day. Keep this. Arithmetic goes through
`addDays()` and `diffDays()`, which rebuild dates from parts rather than adding
milliseconds, so DST and month ends behave.

---

## 4. Sync (Android only)

### Why it is built the way it is

The obvious design — Google Drive REST with OAuth — was rejected for two
reasons. First, the artifact deployment can never participate: artifacts run
under a content policy that blocks **all** outbound fetch/XHR/WebSocket to every
host, so no amount of work gets the web copy talking to any API. Second, OAuth
needs a Google Cloud project, an OAuth client registered against the app's
signing certificate, and a consent screen — setup the user has to do by hand,
for code that cannot be tested until all of it exists.

So sync uses the **Storage Access Framework**: the user picks one JSON file
through the system picker (Google Drive appears there as a provider), and the
app keeps a persisted read/write grant to that URI. No API keys, no Cloud
project, and the file is visible in the user's own Drive.

### Division of labour

- **Kotlin** (`MainActivity.Bridge`) moves bytes. It knows nothing about the
  data.
- **`shim.js`** (`WardSync`) owns the merge. All logic lives here.

Bridge methods, all synchronous, all callable from JS:

| Method | Returns | Does |
|---|---|---|
| `syncStatus()` | JSON `{wired, file, last}` | what the Sync panel shows |
| `syncRead()` | file contents, or `""` | reads the picked file |
| `syncWrite(text)` | `true`/`false` | overwrites it (`"rwt"`, so it truncates) |
| `syncChoose()` | — | `CreateDocument` — start a new sync file |
| `syncJoin()` | — | `OpenDocument` — adopt an existing one (second phone) |
| `syncForget()` | — | releases the grant and clears prefs |
| `saveFile(name, text)` | `true`/`false` | Export → Downloads |

After a pick, Kotlin calls `window.WardSync.onFileChosen()`.

### When it syncs

On load, on `visibilitychange` back to visible, in `onResume()`, and 1.5s
(debounced) after any change that actually altered stored data. Every sync is
**pull → merge → push**, so a push never clobbers what the other phone wrote.

### The merge

Whole-file last-writer-wins would lose data: phone A ticks blocks, phone B logs
a topic, and whoever syncs second erases the other. So the merge is **field
level**, keyed on stamps the shim maintains in two private records:

```
__meta         { "<stamp key>": <epoch ms> }   when this phone last changed it
__tombstones   { "<topicId>":   <epoch ms> }   deleted topics
```

Stamp keys are finer-grained than records, which is the whole trick:

```
config             the config object as a whole
chores:<choreId>   one chore's last-done date
days:<date>        one day's ticked blocks
topic:<topicId>    one topic
```

`stampWrite()` diffs an incoming write against what is already stored and stamps
**only the sub-keys that genuinely changed**. Without that diff, every save
would claim every day and every chore, and the merge would collapse back into
last-writer-wins.

Resolution per kind:

- **config** — newest stamp wins, whole object.
- **chores** — the later *date* always wins, because a later last-done date is
  simply the truth; the stamp only breaks ties.
- **days** — per date, newest stamp wins that date.
- **topics** — per topic, newest stamp wins. A tombstone beats a record whose
  stamp is older, so deletes stick; writing a topic id again clears its
  tombstone, so re-creating one works.

**What this does not solve:** editing the *same* item on both phones while both
are offline. There the later edit wins and the earlier is lost. That is a
deliberate limit, not an oversight — fixing it properly needs per-field
vector clocks, which is not worth it here.

### Tests

`test-sync.js` (in the project's scratch history, reproduce as needed) drives two
real browser contexts over a shared stubbed file and asserts: both phones
converge; offline divergence loses nothing; deletes do not resurrect; config and
chore changes propagate. **Re-run an equivalent test after touching `merge()`.**

---

## 4b. Reminders (Android only)

### Why the page computes and Kotlin only arms

A WebView cannot post a notification or schedule anything, so the native side
has to exist. But every *rule* (which block, what time, which weekdays, how often
to nag, how many days before a chore is due) already lives in `config`, next to
the thing it belongs to. Duplicating that logic in Kotlin would mean two
schedulers to keep in step. So:

- **The page** (`computeReminderSchedule()`) turns the rules plus live state
  (ticks today, chore due dates) into a flat list of concrete fire times for the
  next `REM_HORIZON_DAYS` (7), sorted, capped at `REM_CAP` (150). It pushes that
  list through `WardBridge.scheduleReminders(json)` 700 ms after any save, on
  load, and when the app returns to the foreground.
- **Kotlin** (`Reminders.apply`) cancels everything it armed last time, arms the
  new list with `AlarmManager`, and stores the JSON. It never reads `config`.

Entry shape: `{ key, at, title, body, kind, id }`. `key` is stable per
(item, day, nag-index) and hashes to the PendingIntent request code.
`kind:id` hashes to the **notification id**, so a nag replaces the earlier
notification for the same block instead of stacking four.

### Rules the computation encodes

- A block reminder on today's date is **skipped once the block is ticked**;
  ticking also calls `dismissReminder` so a notification already showing clears.
- A chore fires on `due - lead`. If that day is already past it fires today.
  With `nagDaily` it then fires every day until the chore is done, which moves
  `due` forward and the whole thing with it.
- Weekday filter is Mon=0..Sun=6 (`weekIdx`), computed from the local date.
- Anything with `at <= now` is dropped on both sides.

### Native pieces

| Piece | Job |
|---|---|
| `Reminders` object | channel, arm/cancel, re-arm from stored JSON, show, dismiss |
| `ReminderReceiver` | fired by AlarmManager; shows the notification |
| `BootReceiver` | `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` → re-arm (alarms die on both) |
| `POST_NOTIFICATIONS` | runtime permission on 13+, requested the first time a reminder is switched on |
| exact alarms | `USE_EXACT_ALARM` (13+, auto-granted when sideloaded); `SCHEDULE_EXACT_ALARM` on 12 via a Settings screen. Without either, `setWindow` with a 10-minute window, and the Alerts tab says so |

The app still declares **no INTERNET permission**.

### Horizon and the app not being opened

Alarms are armed a week ahead. If the app is not opened for a week they run out,
which is acceptable: a tracker nobody opens should go quiet. Opening it re-pushes.
Do not "fix" this with a native rescheduler unless you are also moving the rules
into Kotlin.

### Two phones

`remind` syncs with `config`, so both phones hold the same rules — and **both
fire**. That is the simplest truthful behaviour; the Alerts tab says it. A
per-device mute would be a `data/users`-style local flag, not a config field.

### Tests

`test-reminders.js` pins the clock to a Wednesday 09:00 IST and asserts: all off
by default; a past time today is skipped; weekday filter; nag expansion
(1 + 3 at the set interval); ticking removes today and dismisses; chore
lead / due-today / overdue wording / daily-until-done; doing a chore moves the
next fire; sorted and capped; settings persist into `config`. Re-run after
touching `computeReminderSchedule()`.

---

## 5. Import / export

Two separate mechanisms. Do not confuse them.

### Export (Setup → Backup → Export everything)

`exportAll()` builds its payload from the page's **in-memory** state
(`cfg`, `choreDone`, `dayLog`, `topics`) — *not* from storage. This is why
`__meta` and `__tombstones` never leak into a backup file.

```js
{
  kind: "ward-chart",
  version: 1,
  exported: "YYYY-MM-DD",
  config: { ... },             // the whole config/main object
  chores: { <choreId>: date }, // flat, not wrapped in {done:}
  days:   { <date>: [ids] },   // flat, not wrapped in {log:}
  topics: [ { id, ... } ]      // an ARRAY here, keyed by id in sync
}
```

It calls `claude.use("downloads").save({filename, data})`. On claude.ai the
platform prompts the viewer; in the app `saveFile` writes to Downloads. If
`downloads` is unavailable it falls back to dumping the JSON into the paste box
and selecting it.

### Import (Setup → Backup → Import)

From a file input or the paste box. `importText()` parses, **rejects anything
without `data.config`**, runs it through `normalizeConfig()`, deletes every
existing topic, then writes the imported ones. It is a **replace, not a merge**.

### The two shapes differ — this trips people up

| | export (`ward-chart`) | sync (`ward-chart-sync`) |
|---|---|---|
| `chores` | `{id: date}` | `{id: date}` (same) |
| `days` | `{date: [ids]}` | `{date: [ids]}` (same) |
| `topics` | **array** of records with `id` | **object** keyed by id |
| `meta` / `tombstones` | absent | present |
| semantics | replace everything | merge field by field |

If you unify them, keep `importText()` tolerant of both, and keep export free of
`meta`/`tombstones` — a backup should not carry one phone's clock into another.

### `normalizeConfig()`

Every config read passes through it, from storage and from import alike. It
backfills missing fields and, when `bands` is absent (configs written before
dividers existed), derives the band list from the distinct `block.band` values
and orders them against `BAND_ORDER`. **Extend this, do not bypass it**, when
you add a config field — it is the only migration path.

---

## 6. Invariants

Breaking any of these breaks the product, not just the build.

1. **The page must work with no store.** `claude.use("db")` can resolve `null`.
   The page renders, shows a banner, and does not throw. Do not assume a store.
2. **Never write `localStorage` from page code.** Go through `db`, or the
   feature only works in one deployment.
3. **Dates are local `YYYY-MM-DD` strings.** No UTC timestamps, no `Date` math
   in milliseconds.
4. **Nothing is scheduled on a weekday.** Due dates derive from when something
   was last done. Keep it that way.
5. **Keep the cap and Light day.** They are what stop a bad week ending the
   habit.
6. **`floor: true` blocks are the floor.** Light day must never push them.
7. **One write at a time per record.** Writes are serialised per path in
   `queues[]`; keep that if you add write paths.
8. **Re-render after every change** (`render()`), then re-query the DOM. Handles
   held across a render are stale — a real bug this project has already hit.
   `render()` preserves `window.scrollY` so the reorder arrows do not throw the
   user back to the top of a long Setup list; tab switches call
   `scrollTo(0, 0)` afterwards to override it.
8b. **A WebView ignores `<input type="file">`** unless the app implements
   `WebChromeClient.onShowFileChooser`. Import silently did nothing until that
   was added. If the callback is ever left unanswered — including on cancel,
   where it must receive `null` — the file input wedges for the rest of the
   process.
9. **No horizontal scroll at 400px.** Gutters are
   `max(16px, env(safe-area-inset-*))`. A past bug: the sticky header carried
   negative margins to escape a padded parent, but its parent is `body`, which
   has no padding — the page scrolled 16px sideways. Measure
   `documentElement.scrollWidth` against `innerWidth` on all four tabs after
   layout changes.
10. **Both themes, token-level.** Every colour is a `:root` token redefined
    under `@media (prefers-color-scheme: dark)` guarded
    `:root:not([data-theme="light"])` and again under `:root[data-theme="dark"]`.
    Never put a colour's only definition inside a media block.
11. **Android-only UI is gated on `window.WardBridge`.** The Sync panel and the
    whole Alerts tab are in the shared page; on the web the panel does not
    render and the tab is hidden at boot (and never restored from the saved
    tab).

---

## 7. Making changes

### A new daily block or chore

Don't edit code — the app does this at runtime (Setup → Daily blocks, Chores →
Add). Only change `DEFAULT_CONFIG` if you want it in a fresh install.

### Block order

`config.blocks` is one flat array; Today renders a divider by filtering it, so
**array order is the order within a divider**. Setup groups the editor by
divider to make that visible.

- `addBlock(band, label)` splices in after the last block of that band, so a new
  block appears inside its own divider rather than at the very bottom.
- `moveBlock(id, dir)` finds the nearest neighbour **with the same band** and
  swaps the two array slots. Swapping two same-band entries cannot disturb any
  other divider's internal order, which is why a swap is safe here and a
  rotation is not needed.
- `removeBlock(id)` looks the block up by id. It used to splice by the loop
  index from a flat `forEach`; once the editor was grouped that index no longer
  matched the array, so **address blocks by id, never by render position**.

### A new config field

Add to `DEFAULT_CONFIG`, backfill in `normalizeConfig()`, add a control in
`renderSetup()`. Stamping is automatic (the `config` stamp covers the object).

### A new record type

1. Pick a path with an even segment count.
2. Subscribe once in the bootstrap block at the bottom of the page script; add a
   flag to `loaded` and check it in `maybeReady()`.
3. Add a `save*()` that goes through `write()`.
4. Add a branch to `stampWrite()` in `shim.js`, or it will not sync.
5. Add it to `merge()` and `apply()`.
6. Add it to `snapshot()`, `exportAll()` and `importText()`.

Steps 4–6 are the ones that get forgotten; the symptom is data that works on one
phone and never appears on the other.

### Rebuilding after a page change

```
python3 tools-build-asset.py      # ward-chart.html -> assets/index.html
./gradlew assembleDebug
```

`shim.js` is edited in place and needs no build step.

### Upgrading to real Drive OAuth, if ever wanted

Only worth it if the file-picker route proves flaky. The shape:

1. Google Cloud project; enable the Drive API; OAuth client of type Android with
   the package name and the signing certificate's SHA-1; consent screen with
   scope `https://www.googleapis.com/auth/drive.appdata`.
2. Add `play-services-auth`; authorize that scope; obtain an access token.
3. Replace `syncRead`/`syncWrite` with Drive REST calls against
   `spaces=appDataFolder` — list by name, `POST .../upload/drive/v3/files`
   (multipart) to create, `PATCH .../upload/drive/v3/files/{id}?uploadType=media`
   to update, `GET .../files/{id}?alt=media` to read. Use OkHttp directly;
   **do not** pull in `google-api-client-android`, which brings a dependency
   conflict mess onto Android for no benefit at this size.
4. Add the INTERNET permission, and keep working-while-offline: queue the push
   and retry, rather than failing the write.
5. `shim.js` needs **no changes** — the bridge contract is the seam. That is why
   the merge lives in JS.

---

## 8. Known gaps

- **Reminders are Android-only** and run a week ahead (see §4b). There is no
  native rescheduler by design.
- **App and artifact do not sync to each other.** Two separate stores. Move data
  with export/import.
- **System fonts in the app.** To restore Archivo and IBM Plex Mono, drop the
  `.ttf` files in `assets/fonts/` and add `@font-face` rules — or add the
  INTERNET permission and restore the stylesheet link.
- **Same-item offline edits lose the earlier one.** See the merge section.
- **Auto Backup restores WebView storage through a LevelDB**, which is somewhat
  finicky. Sync is the dependable path; Auto Backup is a bonus.

---

## 9. Pinned versions

AGP 8.5.2 · Gradle 8.7 · Kotlin 1.9.24 · compileSdk 34 · minSdk 26 ·
appcompat 1.7.0 · webkit 1.11.0 · app version 1.3.

Chosen for a first-try Gradle sync rather than for being current. Android Studio
will offer an upgrade; accepting is fine. `minSdk 26` is what lets the launcher
be an adaptive icon with no PNG fallbacks.

## Push dialog (v1.4)

Push on a due chore or topic opens `pushSheet(label, apply)`: days field (1-365, chips 1/2/3/7), a live line "X will be due on <weekday d month yyyy>", and Confirm/Cancel. Nothing is written until Confirm. 1 = tomorrow, 7 = same weekday next week, always counted from today. Chores store `choreDone[id] = today + n - every`, so `choreDue` (= last + every) lands on the chosen date; when n > every, `last` is a future date and the "last" label is hidden. Light day still pushes everything one day with no dialog.
