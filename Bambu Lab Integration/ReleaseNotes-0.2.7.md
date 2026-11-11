**Release: Bambu Lab Integration v0.2.7 (beta)**
Far fewer events during a print, so the printer devices keep a much smaller history on the hub.

**Problem:**
- Printer devices built up a large event and state history (flagged red in Logs, Device stats). Wi-Fi signal jitter sent an event every 30 to 60 seconds, the ETA flip-flopped between two times, and the tile redrew whenever the idle nozzle cooled a few degrees.
- When an error cleared, errorText could be set to "none" twice at the same moment.

**The Fix:**
- **Wi-Fi signal:** reported on changes of 10 dBm or more, at most every 15 minutes. New **Report Wi-Fi signal** setting in the printer device's Preferences (on by default) turns it off completely.
- **ETA:** set when the print starts, then moved only when the estimate shifts by 10 minutes or more, or in the last 10 minutes. It's still checked on every status.
- **Progress:** every 2%, with 0 and 100 always exact.
- **Time remaining:** every 10 minutes, then every 5 in the last hour. **Elapsed time:** every 15 minutes.
- **Tile:** an idle heater shows "off", so a cooling nozzle no longer redraws it.
- **errorText:** set once when an error clears.
- In a simulated 10-hour print, events dropped from about 1,100 to about 215.
- Print state, stage, errors, door, active tray and heater status still report right away, so rules react immediately.
- New Tips topic: **Events and history** lists every step.

**Action needed:**
Update through HPM. No Done needed. Existing history ages out on its own.
