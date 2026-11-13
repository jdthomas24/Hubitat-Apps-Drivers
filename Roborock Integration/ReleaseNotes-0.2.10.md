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
