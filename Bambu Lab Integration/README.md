# Bambu Lab Integration for Hubitat

> **Status: v0.1.6 beta, tested on an H2D.** Local monitoring of Bambu Lab 3D printers, with optional control. No Bambu account needed: Hubitat talks to the printer on your network over MQTT, using the printer's LAN access code. In the default **Monitor only** mode your printer stays on Bambu Cloud and you keep using the Bambu apps as normal.

## What you get

| Device | What it does |
|---|---|
| **Printer** (one per printer) | Print state and stage, progress, elapsed time, time left and ETA, layers, file name, heater status (Heating to target, At temp, Cooling, Off) and temperatures for the nozzle, bed and chamber (both nozzles on the H2D), speed, chamber light, door (contact sensor), active filament and external spools, errors in plain English with a Wiki link, firmware and firmware updates, dashboard tile |
| **Chamber light** | A switch for the printer's chamber light (both modes; on the H2D it switches both chamber lights) |
| **AMS** (one per unit) | Humidity, temperature, and filament type, color and amount for each tray |

You choose which light and AMS devices Hubitat creates on the app's **Equipment** page.

## Requirements

- A Bambu Lab printer on the same network as the hub (X1, P1, A1 and H2 series)
- The printer's IP address and LAN access code (the serial is found automatically)
- Hubitat 2.2.5 or later
- Monitor only needs nothing else. Monitor and control needs Developer Mode (see **Modes**).

## Install

**Hubitat Package Manager:** Install → From a URL, and paste:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Bambu%20Lab%20Integration/packagemanifest.json`

**By hand:** add the two drivers in **Drivers Code** (Printer, AMS) and the app in **Apps Code**, from this folder.

## Set up

1. Open **Integrations** in the left menu and add **Bambu Lab Integration**.
2. Tap **Add printer**. Enter a name, the IP address and the LAN access code, and pick a **Mode**. On the printer, **Settings, Network** (**WLAN** on some models) shows the IP and access code. Just read them: Monitor only doesn't need LAN Only or Developer Mode, and the Bambu Handy app keeps working. Leave the serial blank; the integration reads it from the printer.
3. Tap **Done**. Each printer connects and adds its light and AMS devices within a few seconds.
4. Open the app again and tap **Equipment** to turn off anything you don't want in Hubitat.

Once connected, each printer shows as a card with its status, mode and AMS units (tray colors). **Edit Printer** opens its settings, **Refresh** asks for a status now, and **Remove Printer** (tap twice) deletes that printer and its devices. The app's **Tips & Troubleshooting** page covers choosing a mode, setup, rules and common problems.

A DHCP reservation for the printer keeps its IP address from changing.

## Modes

Each printer has its own mode.

| | Monitor only (default) | Monitor and control |
|---|---|---|
| Hubitat sees status, temperatures, AMS, errors, tile | ✅ | ✅ |
| Bambu Cloud and the Bambu Handy app | ✅ | ❌ |
| Bambu Studio on your network | ✅ | ✅ |
| Chamber light from Hubitat | ✅ | ✅ |
| **Printer Control** (Pause, Resume, speed) from Hubitat | ❌ (use the Bambu apps) | ✅ |
| Stop from Hubitat | ❌ | Only with **Allow Stop** turned on for that printer |
| Printer setting needed | None | Developer Mode (Settings, LAN Only, Developer Mode) |

Bambu's newer firmware only accepts control commands signed by Bambu's own software, unless Developer Mode is on, and Developer Mode keeps the printer off Bambu Cloud. That's why control is a separate mode. The chamber light is the exception: the printer takes it without Developer Mode. After each command the driver checks the next status and logs whether it took, was refused (Developer Mode) or was ignored.

> **Stop ends the print for good.** It's off by default, even in control mode. Turn on **Allow Stop** for a printer only if you trust every rule, dashboard and voice assistant that can reach it.

## Printer preferences

These are on each printer device's **Preferences** tab.

| Option | Default | What it does |
|---|---|---|
| Check while printing every | 30 seconds | How often Hubitat asks a printing printer for its status. Idle printers are checked every 5 minutes. A finished print or an error shows up within this time. |
| At temperature, report changes of | 5° | While a heater warms up or cools down only its status changes. Once it's at temperature (or off), smaller changes are skipped. |
| Temperature units | Celsius | Matches the printer screen, slicers and filament specs. Choose **Hub's setting** for °F. |

**Why sampling:** X1 and H2 printers push an 8 KB status every second while printing, which adds up on a long print. The integration stays connected but only listens for one status per check, so a 30 hour print costs the hub about 3,600 messages instead of over 100,000.

## Dashboards and rules

- **Tile:** add the Printer as an Attribute tile and pick **tile**. It shows state, file, a progress bar, layers, time left, temperatures and errors.
- **printState** is Idle, Preparing, Printing, Paused, Finished or Failed, so a rule can announce a finished print or a failure.
- **nozzleStatus** and **bedStatus** are "Heating to 220°", At temp, Cooling or Off. "bedStatus becomes At temp" is a clean trigger, with no watching numbers climb. **nozzleTemp** and **bedTemp** update at temperature or off.
- **timeRemaining** and **elapsedTime** are in minutes and move in 5 minute steps.
- **stage** shows what the printer is doing right now (Heating hotend, Changing filament, Paused: filament runout and so on).
- **errors** lists HMS codes in Bambu's format (HMS_0700_8000_0002_0001) and **errorText** says what they mean. The log and the app's printer card link each code to its Bambu Lab Wiki page.
- **contact** is open or closed for the printer's door (H2D tested), so the printer works as a contact sensor in rules.
- **firmwareUpdate** is none, or available with the new version.
- **externalSpool** and **externalSpool2** show the filament on the external holders once one is loaded.

**Error text** comes from a small table in this folder's **HMS** directory, fetched from GitHub the first time a code shows up (nothing goes to Bambu); only the matching line is kept on the hub. The English text and Wiki links come from the [ha-bambulab](https://github.com/greghesp/ha-bambulab) project (MIT license).

## Logging

One **Log level** in the app covers the app, every printer and every light and AMS device, and all of it shows under the app in **Logs**, labeled by device:

- **Errors Only** (default): problems only, like a rejected access code, a printer staying offline, or printer errors.
- **Normal**: plus connections, print state changes, commands and filament changes.
- **Full**: everything, including every report from the printer. Turns itself off after 30 minutes.

## Troubleshooting

**Access code rejected.** The code changes if you reset the printer's network settings. Check it on the printer, update it in the app and tap **Done**.

**Printer offline.** The printer reconnects on its own, waiting a little longer each try (up to 5 minutes). Check it is on and on the same network, and that nothing blocks port 8883.

**Waiting for printer.** Connected, but the printer hasn't sent a status yet, so its serial isn't known. Some printers stay quiet while idle. Enter the serial in the app (printer **Settings, Device**), or start anything on the printer.

**The serial in the app doesn't match.** The integration uses the serial the printer reports and logs a warning. Clear the serial field in the app.

**Commands do nothing.** In Monitor only they're refused on purpose; use the Bambu apps. In Monitor and control, turn on Developer Mode on the printer. The log says after each command whether it took.

**The light turned itself back on.** The printer turns its light on when a print finishes.

**A device I deleted came back, or one is missing.** Use the app's **Equipment** page. Deleting a device from the Devices page turns it off there; turn it back on to recreate it.

## Support

[Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
