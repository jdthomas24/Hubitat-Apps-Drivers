**Release: Roborock Integration v0.2.6 (beta)**
New dashboard tile that shows the vacuum's health at a glance.

**Problem:**
- There was no quick way to see the vacuum's status, errors and maintenance on a dashboard without several separate tiles.

**The Fix:**
- New **Dashboard tile** preference on each vacuum (off by default). Turned on, the new `tile` attribute holds a small health card: status and battery, online or offline, any vacuum or dock error, the current or last clean, cleaning progress while it runs, and filter and brush life while docked.
- The card updates at most once a minute, and only when something on it changes, so it adds almost no load.

**Action needed:**
Update through HPM. To use it, turn on **Dashboard tile** on the vacuum device, then add an **Attribute** tile on your dashboard with the attribute `tile`.
