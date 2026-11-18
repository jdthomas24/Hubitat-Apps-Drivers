**Release: Bambu Lab Integration v0.2.8 (beta)**
See how much filament is left in each AMS slot, and get told when a slot is empty or running low.

**Problem:**
- The printer card showed each AMS tray's color but not how much filament was left.
- An AMS HT showed as "AMS 129" with four tray dots, but it holds one spool.
- Knowing when a slot ran empty took one rule per tray, and a spool that ran out with its RFID tag still in place could keep showing as loaded.
- There was no way to get a warning before a spool ran out.

**The Fix:**
- **Printer card:** each tray shows its amount left next to its color (for example 85%). Low slots show in orange. Hover a tray for the full filament name.
- **AMS HT:** named **AMS HT 1** with one tray. An existing "AMS 129" device is renamed on its own, unless you already renamed it yourself.
- **emptySlots** on each AMS device lists the empty slots (for example "2, 4"), or none. It also uses the AMS's filament sensors, so a runout counts even if the spool's tag is still read.
- **lowSlots** lists slots at or below the new **Low filament warning at** setting on the printer device's Preferences (10% by default).
- One rule per AMS now covers every slot. Rules and dashboards keyed on tray1 to tray4 keep working.
- Amount left needs a Bambu RFID tag on the spool and **Update Remaining Capacity** turned on in the AMS settings.
- New Tips entries under **Rules and dashboards**: AMS slot empty and Filament low.

**Action needed:**
Update through HPM. No Done needed. The new AMS attributes fill in at the printer's next check, or right away with Refresh.

---

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
