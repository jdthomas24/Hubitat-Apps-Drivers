# Bambu Lab Integration for Hubitat

> **Status: v0.1.0 beta, not yet tested on a printer.** Local monitoring of Bambu Lab 3D printers, with optional control. No Bambu account needed: Hubitat talks to the printer on your network over MQTT, using the printer's LAN access code. In the default **Monitor only** mode your printer stays on Bambu Cloud and you keep using the Bambu apps as normal.

## What you get

| Device | What it does |
|---|---|
| **Printer** (one per printer) | Print state and stage, progress, time left and ETA, layers, file name, nozzle, bed and chamber temperatures (both nozzles on the H2D), speed, chamber light, active filament, HMS errors, firmware, dashboard tile |
| **Chamber light** (control mode only) | A switch for the printer's chamber light |
| **AMS** (one per unit) | Humidity, temperature, and filament type, color and amount for each tray |

You choose which light and AMS devices Hubitat creates on the app's **Equipment** page.

## Requirements

- A Bambu Lab printer on the same network as the hub (X1, P1, A1 and H2 series)
- The printer's IP address, serial number and LAN access code
- Hubitat 2.2.5 or later
- Monitor only needs nothing else. Monitor and control needs Developer Mode (see **Modes**).

## Install

**Hubitat Package Manager:** Install → From a URL, and paste:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Bambu%20Lab%20Integration/packagemanifest.json`

**By hand:** add the two drivers in **Drivers Code** (Printer, AMS) and the app in **Apps Code**, from this folder.

## Set up

1. Open **Integrations** in the left menu and add **Bambu Lab Integration**.
2. For each printer, enter a name, the IP address, the serial number and the LAN access code, and pick a **Mode**. On the printer, **Settings, WLAN** (or **LAN Only**) shows the IP and access code, and **Settings, Device** shows the serial.
3. Tap **Done**. Each printer connects and adds its light and AMS devices within a few seconds.
4. Open the app again and tap **Equipment** to turn off anything you don't want in Hubitat.

A DHCP reservation for the printer keeps its IP address from changing.

## Modes

Each printer has its own mode.

| | Monitor only (default) | Monitor and control |
|---|---|---|
| Hubitat sees status, temperatures, AMS, errors, tile | ✅ | ✅ |
| Bambu Cloud and the Bambu Handy app | ✅ | ❌ |
| Bambu Studio on your network | ✅ | ✅ |
| Pause, Resume, Stop, Set Speed and the light from Hubitat | ❌ (use the Bambu apps) | ✅ |
| Printer setting needed | None | Developer Mode (Settings, LAN Only, Developer Mode) |

Bambu's newer firmware only accepts control commands signed by Bambu's own software, unless Developer Mode is on, and Developer Mode keeps the printer off Bambu Cloud. That's why control is a separate mode. If the printer refuses a command, the log says so once, with the fix.

> **Stop ends the print for good.** In control mode, any rule, dashboard or voice command that runs it cancels the job.

## Options

| Option | Default | What it does |
|---|---|---|
| Update busy printers every | 5 seconds | X1 and H2 printers send a full report every second while printing; this processes one at most every 5 seconds. Print state changes always go through right away. |
| Report temperature changes of | 1° | Smaller changes are skipped to keep hub load down |

## Dashboards and rules

- **Tile:** add the Printer as an Attribute tile and pick **tile**. It shows state, file, a progress bar, layers, time left, temperatures and errors.
- **printState** is Idle, Preparing, Printing, Paused, Finished or Failed, so a rule can announce a finished print or a failure.
- **stage** shows what the printer is doing right now (Heating hotend, Changing filament, Paused: filament runout and so on).
- **errors** lists HMS codes in Bambu's format (HMS_0700_8000_0002_0001). Search the code on the Bambu Lab Wiki for what it means.

## Logging

One **Log level** in the app covers the app, every printer and every light and AMS device, and all of it shows under the app in **Logs**, labeled by device:

- **Errors Only** (default): problems only, like a rejected access code, a printer staying offline, or printer errors.
- **Normal**: plus connections, print state changes, commands and filament changes.
- **Full**: everything, including every report from the printer. Turns itself off after 30 minutes.

## Troubleshooting

**Access code rejected.** The code changes if you reset the printer's network settings. Check it on the printer, update it in the app and tap **Done**.

**Printer offline.** The printer reconnects on its own, waiting a little longer each try (up to 5 minutes). Check it is on and on the same network, and that nothing blocks port 8883.

**Commands do nothing.** In Monitor only they're refused on purpose; use the Bambu apps. In Monitor and control, turn on Developer Mode on the printer.

**A device I deleted came back, or one is missing.** Use the app's **Equipment** page. Deleting a device from the Devices page turns it off there; turn it back on to recreate it.

## Support

[Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
