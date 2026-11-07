**Release: Bambu Lab Integration v0.2.3 (beta)**
P1 and A1 printers are asked for a full status at most every 5 minutes, so Hubitat can't make them lag.

**Problem:**
- The integration asks for a full status (pushall) on every check: every 30 seconds while printing. X1 and H2 printers handle that fine, but P1 and A1 printers are known to lag or stutter when asked that often.

**The Fix:**
- P1 and A1 printers get a full status at most every 5 minutes. The checks in between just listen for 5 seconds and pick up whatever changed, so progress and temperatures stay current. A quiet check is normal and never counts as a missed reply.
- A finished or paused print can take up to 5 minutes to show on a P1 or A1. Refresh always gets a full status right away.
- It's automatic, from the model the printer reports. Until the model is known (the first seconds after connecting) the driver plays it safe the same way.
- X1 and H2 printers are unchanged.
- Tips & Troubleshooting has a new P1 and A1 entry and an Other models entry. The README notes that X1, P1 and A1 are untested so far.

**Action needed:**
Update through HPM. No Done needed. X1, P1 and A1 owners: if anything looks off, set Log level to Full and post the logs here.
