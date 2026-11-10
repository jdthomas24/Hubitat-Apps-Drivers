**Release: Bambu Lab Integration v0.2.5 (beta)**
Clearer attribute names, units you can see on the device page, and right/left nozzles on the H2D.

**Problem:**
- **fileName** sat next to **lastPrint** without saying it was the current job.
- The device page doesn't show units, so **timeRemaining** and **elapsedTime** read as bare numbers, and **wifiSignal** was text ("-39dBm") that rules couldn't compare.
- On the H2D, **nozzle** and **nozzle2** didn't say which nozzle was which.
- Error text was saved in the device's State Variables, a growing block of JSON on the device page.

**The Fix:**
- **fileName** is now **currentPrint** (the job printing now, or Not printing). **errors** is now **errorCodes**.
- **timeRemaining (min)**, **elapsedTime (min)** and **wifiSignal (dBm)** carry the unit in the name. Wifi is now a number and still only updates on a 5 dBm change.
- H2D: **rightNozzleTemp / rightNozzleStatus** and **leftNozzleTemp / leftNozzleStatus**. The tile shows R and L. Single-nozzle printers keep **nozzleTemp / nozzleStatus**.
- Error text is kept in memory, so no more database writes for it. After a reboot, the text for any error still showing is fetched again without logging it twice.
- The old attributes are removed from the device page on their own.
- No new events: everything still reports on change only, in the same steps as before.

**Action needed:**
Update through HPM. Rules or dashboards that use fileName, errors, timeRemaining, elapsedTime, wifiSignal, nozzle2 (or nozzle on an H2D) need the new names.
