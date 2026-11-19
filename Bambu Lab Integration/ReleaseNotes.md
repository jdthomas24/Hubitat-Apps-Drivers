**Release: Bambu Lab Integration v0.2.17 (beta)**
New Diagnostics button for support requests.

**Problem:**
- Helping with a printer meant setting Full logging, waiting, and copying log lines, and Full logging cuts each printer report short. Details like the AMS slots or another model's firmware were hard to get.

**The Fix:**
- New **Diagnostics** button on the printer device. About 10 seconds after tapping it, one block appears in the logs from DIAGNOSTICS to END, whatever the log level, ready to paste into a forum post.
- It has the integration version and settings, the printer's model and firmware with each AMS and module, the connection, and the printer's raw status: print state, errors, every AMS slot, external spools, nozzles, lights and door.
- It never includes the LAN access code, serials are shortened, and it doesn't change anything on the printer.
- New Tips topic under Troubleshooting: Diagnostics. The Other models tip now points to it.

**Action needed:**
Update through HPM. No Done needed.

---

**Release: Bambu Lab Integration v0.2.16 (beta)**
Rules can now tell a planned pause from a problem, and AMS devices get their new names.

**Problem:**
- A pause added in the slicer (filament swap, magnets, inserts) and a pause from a runout or fault both just showed Paused. A rule couldn't tell them apart without parsing the stage text, and the printer card didn't say why.
- 0.2.15 didn't rename AMS devices from their old default names (like "H2D #1 AMS 1") to the new ones (AMS A, AMS HT A).

**The Fix:**
- New **pauseType** attribute on the printer: planned (a pause in the print file), user (paused from an app or the printer screen), problem (runout or fault), or none.
- While paused, the printer card shows the reason in place of Paused, for example Paused by G-code.
- AMS devices still on an old default name are renamed at the next check. Names you've set yourself are left alone.
- New Tips entry under Rules and dashboards: Planned pause.

**Action needed:**
Update through HPM. No Done needed. Tap Refresh on each printer to rename the AMS devices now.

---

**Release: Bambu Lab Integration v0.2.15 (beta)**
AMS names now match the printer screen, and each slot on the card shows its number.

**Problem:**
- Hubitat called the AMS units AMS 1 and AMS HT 1, while the printer screen and Bambu Studio call them AMS A and AMS HT A, with slots A1 to A4.
- The printer card showed each slot's color and amount, but not which slot it was.
- activeTray read "AMS 1 tray 2", which matched neither the card nor the printer.

**The Fix:**
- AMS units are named like the printer: AMS A, AMS B and so on, and AMS HT A for an AMS HT. AMS devices still on an earlier default name (like "H2D #1 AMS 1") are renamed once. Names you've set yourself are left alone.
- Each slot on the card shows a circled slot number, so AMS A ③ is A3 on the printer. An AMS HT holds one spool, so it shows no number.
- activeTray reads AMS A2, AMS HT A or external spool.
- On narrow screens, slots wrap whole, so a number stays with its color and amount.
- Tips, AMS and filament: how the names match the printer, and what to do if the letters don't.

**Action needed:**
Update through HPM. No Done needed. AMS devices are renamed at the next check, or right away with Refresh. A rule that compares activeTray to text like "AMS 1 tray 2" needs the new name (AMS A2). Rules on tray1 to tray4, emptySlots and lowSlots are unchanged.

---

**Release: Bambu Lab Integration v0.2.14 (beta)**
AMS slots read more clearly on the printer card, and the Tips page explains filament amounts.

**Problem:**
- On the printer card, each slot's amount sat loose next to the following slot's color, so it wasn't always clear which went together.
- Why some spools show an amount and others don't (tags, estimation, re-reading) wasn't explained anywhere.

**The Fix:**
- Each slot's color and amount now sit together in a small chip.
- New Tips topic, **AMS and filament**: how the AMS estimates, why there's no amount for some spools, what 0% means, how to get an estimate with Re-read, third-party spools, empty slots and the AMS HT.

**Action needed:**
Update through HPM. No Done needed.

---

**Release: Bambu Lab Integration v0.2.13 (beta)**
A nearly empty Bambu spool now shows as low, and changing the log level no longer logs errors on the chamber lights.

**Problem:**
- The AMS reports 0% both for a spool it has no estimate for and for a Bambu spool that's nearly out. Since 0.2.9, both showed no amount, so a nearly empty Bambu spool never appeared in lowSlots.
- Changing the log level logged a "setLogLevel" error on each chamber light device, which uses Hubitat's built-in switch driver.

**The Fix:**
- 0% on a spool whose Bambu tag was read now shows 0% and counts as low. 0% on an untagged spool still means no estimate.
- The log level is only passed to the AMS devices, not the chamber lights.

**Action needed:**
Update through HPM. No Done needed. The card catches up at the next check, or right away with Refresh.

---

**Release: Bambu Lab Integration v0.2.12 (beta)**
Empty AMS slots are caught by the AMS's filament sensors again.

**Problem:**
- 0.2.10 stopped using the AMS's filament sensors for emptySlots until their layout was confirmed. Without them, a spool that runs out while its tag is still read keeps showing as loaded.

**The Fix:**
- The sensors are back, with the layout confirmed from Full logs on two H2Ds: one per slot on a 4-slot AMS, and one per AMS HT.
- A slot is empty when the AMS reports no filament type, or its sensor says nothing is loaded.

**Action needed:**
Update through HPM. No Done needed.

---

**Release: Bambu Lab Integration v0.2.11 (beta)**
Full logging now shows each AMS slot's details in full.

**Problem:**
- Some loaded spools show no amount left in Hubitat while Bambu Handy shows one. Full logging cuts each printer report short, so the AMS details never made it into the log to find out why.

**The Fix:**
- At Full logging, each AMS slot logs its own line with everything the printer sends for it, whenever it changes (for example "AMS 1 slot 3: {...}").

**Action needed:**
Update through HPM. No Done needed. Logging stays as set; Full turns itself off after 30 minutes.

---

**Release: Bambu Lab Integration v0.2.10 (beta)**
Loaded AMS slots no longer show as empty.

**Problem:**
- On some printers, loaded AMS slots showed as empty, with no color and no amount, and were listed in emptySlots. The AMS's filament sensor field was read with the wrong layout.

**The Fix:**
- A slot is empty when the AMS reports no filament in it. The sensor field is no longer used for that.
- At Full logging, the printer logs the sensor field when it changes, to work out its layout for a later release.

**Action needed:**
Update through HPM. No Done needed. Slots catch up at the next check, or right away with Refresh.

---

**Release: Bambu Lab Integration v0.2.9 (beta)**
A loaded spool the AMS reports at 0% no longer counts as empty.

**Problem:**
- Some loaded spools report 0% when the AMS has no estimate for them yet. 0.2.8 treated 0% as a spool that ran out, so those slots would show as empty and trigger emptySlots rules.

**The Fix:**
- 0% now means no estimate: the slot shows no amount and isn't counted as empty or low.
- Empty comes only from the filament being gone from the slot (no filament type, or the AMS's filament sensor says it's empty), which still catches a real runout.

**Action needed:**
Update through HPM. No Done needed. The printer card catches up at the next check, or right away with Refresh.

---

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
