**Release: Roborock Integration v1.0.1**
Rescan no longer throws an error, and your vacuums pick up new rooms and scenes right away.

**Problem:**
- After a Rescan, the logs showed `No signature of method: ...Roborock_Cloud_Helper...configChanged()`.
- Rescan was telling every device under the app to reload, including the hidden Roborock Cloud Helper, which has nothing to reload. The error stopped that step partway, so a vacuum could keep its old rooms and scenes lists until its daily refresh.

**The Fix:**
- Rescan now only reloads the vacuums and skips the Cloud Helper. No more error, and every vacuum gets the new lists as soon as the scan finishes.

**Action needed:**
Update through HPM. If you added or renamed rooms or scenes recently, tap **Rescan** once afterward.

---

**Release: Roborock Integration v1.0.0**
Roborock Integration is out of beta.

**Problem:**
- The beta build was set up for testing: Full logging stayed on for 3 hours, there was no list of confirmed models, and the Diagnostics button had no note saying what it's for.

**The Fix:**
- Full logging turns itself off after 30 minutes.
- New **Confirmed models** page in Tips & Troubleshooting (and the README): Qrevo Curv X, Saros 10R and S8 MaxV Ultra (L01), Qrevo MaxV and Q5 Max+ (1.0).
- **Diagnostics** stays for support, with a note on the vacuum device that it's only needed when asking for help and doesn't change anything on the vacuum.
- Beta labels removed from the app, Tips and README.
- Now listed in my HPM repository: search for **Roborock Integration**.

**Action needed:**
Beta testers: update through HPM. Nothing else to do; your devices, rules and settings stay as they are.

Thanks to everyone who tested: @Sebastien (https://community.hubitat.com/u/sebastien), @USER, @USER

---

**Release: Roborock Integration v0.2.10 (beta)**
The scenes list now keeps itself up to date with your Roborock app.

**Problem:**
- scenesList only changed when you tapped Rescan in the app. Routines added or deleted in the Roborock app didn't show up (or kept showing), and Initialize or Refresh on the vacuum didn't help.

**The Fix:**
- Scenes are re-read from Roborock automatically:
  - **once a day**, early in the morning;
  - **when you tap Initialize** on the vacuum device (at most once every 5 minutes);
  - **after a scene fails to run**, which usually means it was deleted, so it drops off the list.
- Rescan still refreshes scenes too, along with rooms.
- Only the scene list is read, one vacuum at a time, so it stays well clear of Roborock's limits. Local control isn't affected, and you need to be signed in.
- The Tips page (Rooms and scenes, Cloud only) and README explain when the list updates.

**Action needed:**
Update through HPM, then tap **Done** in the app once (sets up the daily refresh; it also sets itself up within a few minutes if you don't). To see your current scenes right away, tap **Initialize** on the vacuum.

Thanks to @USER for spotting the stale scenes list while testing.

---

**Release: Roborock Integration v0.2.9 (beta)**
Filter, main brush and side brush fit on one line on a dashboard tile again.

**Problem:**
- 0.2.8 kept the tile's values from splitting on the narrow device page, but on a dashboard tile the side brush value dropped to a second line and could be cut off at the bottom.

**The Fix:**
- Tighter spacing between the three values, spread across the full width, so all three fit on one line on a dashboard tile.
- On the narrow device page, a value that doesn't fit moves to the next line whole, never split.
- Tips and README note that dashboards cut a tile off at 1,024 characters, so keep the vacuum's name to 40 characters or fewer.

**Action needed:**
Update through HPM. The tile refreshes on its next update, or tap Save Preferences on the vacuum to see it right away.

---

**Release: Roborock Integration v0.2.8 (beta)**
The dashboard tile now reads cleanly on the device page too.

**Problem:**
- On the device page, where the column is narrow, the tile split words across lines ("Onli / ne", and the filter and brush percentages broke in the middle).

**The Fix:**
- The Online/Offline badge and each filter and brush value stay in one piece. When space runs short, whole items move to the next line instead.
- No change on dashboards.

**Action needed:**
Update through HPM. The tile refreshes on its next update, or tap Save Preferences on the vacuum to see it right away.

---

**Release: Roborock Integration v0.2.7 (beta)**
The dashboard tile now shows up as soon as it's turned on.

**Problem:**
- Turning on Dashboard tile didn't create the card. It only appeared after something on the vacuum changed, so a docked, fully charged vacuum could go a long time without one.

**The Fix:**
- The card is built a couple of seconds after Save Preferences.
- If the card is ever missing while the tile is on (after a hub restart, for example), the next status check builds it.

**Action needed:**
Update through HPM. If you already turned on Dashboard tile, the card appears within the docked poll time, or tap Save Preferences on the vacuum to see it right away.

---

**Release: Roborock Integration v0.2.6 (beta)**
New dashboard tile that shows the vacuum's health at a glance.

**Problem:**
- There was no quick way to see the vacuum's status, errors and maintenance on a dashboard without several separate tiles.

**The Fix:**
- New **Dashboard tile** preference on each vacuum (off by default). Turned on, the new `tile` attribute holds a small health card: status and battery, online or offline, any vacuum or dock error, the current or last clean, cleaning progress while it runs, and filter and brush life while docked.
- The card updates at most once a minute, and only when something on it changes, so it adds almost no load.

**Action needed:**
Update through HPM. To use it, turn on **Dashboard tile** on the vacuum device, then add an **Attribute** tile on your dashboard with the attribute `tile`.
