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
