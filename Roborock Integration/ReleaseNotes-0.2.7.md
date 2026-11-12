**Release: Roborock Integration v0.2.7 (beta)**
The dashboard tile now shows up as soon as it's turned on.

**Problem:**
- Turning on Dashboard tile didn't create the card. It only appeared after something on the vacuum changed, so a docked, fully charged vacuum could go a long time without one.

**The Fix:**
- The card is built a couple of seconds after Save Preferences.
- If the card is ever missing while the tile is on (after a hub restart, for example), the next status check builds it.

**Action needed:**
Update through HPM. If you already turned on Dashboard tile, the card appears within the docked poll time, or tap Save Preferences on the vacuum to see it right away.
