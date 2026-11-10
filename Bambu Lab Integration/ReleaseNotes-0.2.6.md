**Release: Bambu Lab Integration v0.2.6 (beta)**
Opening the door during a print no longer shows up as a printer error.

**Problem:**
- When the door opens mid-print, the H2D raises its own notice, HMS_0300_9600_0003_0001 ("The front door is open"). The driver treated it as an error: a warning in the log, errorCount at 1, and any "errorCount above 0" rule fired just from checking on a print.
- The door attribute already reports it, so it was saying the same thing twice.

**The Fix:**
- The door-open notice is left out of errorCodes, errorCount and errorText, and no longer logs a warning.
- **door** still changes to open and logs at Normal level, so door rules work as before.
- Includes everything in 0.2.5: currentPrint, errorCodes, timeRemaining (min), elapsedTime (min), wifiSignal (dBm), right and left nozzles on the H2D, and error text kept in memory.

**Action needed:**
Update through HPM. No Done needed. If you skipped 0.2.5, rules using fileName, errors, timeRemaining, elapsedTime, wifiSignal or the H2D nozzle attributes need the new names.
