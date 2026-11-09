**Release: Roborock Integration v0.2.4 (beta)**
One device-page cleanup pass, a fix for vacuums that showed as running while docked, and clearer cloud messages.

**Problem:**
- Start, Pause, Resume and Dock were four separate commands on the device page.
- On some models (seen on a Qrevo MaxV) the vacuum keeps reporting a cleaning flag while it sits docked with no job. The driver trusted that flag, so switch showed on and the vacuum was checked every 20 seconds around the clock.
- The vacuum's local key showed in State Variables.
- A manual Refresh gave no sign that it ran.
- Rescan could hit Roborock's request limit (about 5 an hour), and an expired sign-in only showed up as failed scenes or lookups.
- When a vacuum was simply off, the logs read like a fault.

**The Fix:**
- New Vacuum Control command with Start, Pause, Resume and Dock. On and Off still start and dock.
- switch and the 20 second checks follow what the vacuum is actually doing (cleaning, returning, washing or emptying at the dock). Docked, paused or idle is off. Thanks to the MaxV tester for the diagnostics.
- The local key is kept in memory only, with a short fingerprint to spot a change. The old cfgKey entry is removed.
- Refresh logs one line: "Refreshed, no changes" or how many values changed.
- Rescan waits 5 minutes between scans and explains Roborock's "too many requests" (9002) reply.
- A "Sign in again" banner when Roborock rejects the saved sign-in. Local control keeps working.
- Rescan flags a vacuum that's no longer in your Roborock account and keeps its device until you remove it.
- When a vacuum can't be reached and Roborock's cloud can't reach it either, the log says it's probably off, restarting or out of Wi-Fi, IP unchanged, and keeps trying locally. Log lines use the Hubitat device name.
- Tips & Troubleshooting: Moving from bloodtick's driver (using the old device's In Use By list), When a vacuum stays offline (Roborock app signed in, China-market units, 9002), multi-level maps, and how switch and Refresh work. Tips now opens at the top.
- Diagnostics reads "Room names in account" (the count covers every vacuum on the account).
- Protocol 1.0 confirmed on a Qrevo MaxV and a Q5 Max+.

**Action needed:**
Update through HPM. Rules or Maker API calls that used vacuumStart, vacuumPause, vacuumResume or vacuumDock need switching to Vacuum Control. Rules that use switch on or off to mean "cleaning" now see off while paused.
