# Bambu Lab Integration for Hubitat

> **Status: v0.1.0 beta, not yet tested on a printer.** Local monitoring and optional control of Bambu Lab 3D printers. No cloud and no Bambu account: Hubitat talks to the printer on your network over MQTT, using the printer's LAN access code.

## What you get

| Device | What it does |
|---|---|
| **Printer** (one per printer) | Print state and stage, progress, time left and ETA, layers, file name, nozzle, bed and chamber temperatures (both nozzles on the H2D), speed, active filament, HMS errors, firmware, dashboard tile, optional Pause, Resume, Stop and Set Speed |
| **Chamber light** | A switch for the printer's chamber light |
| **AMS** (one per unit) | Humidity, temperature, and filament type, color and amount for each tray |

You choose which light and AMS devices Hubitat creates on the app's **Equipment** page.

## Requirements

- A Bambu Lab printer on the same network as the hub (X1, P1, A1 and H2 series)
- The printer's IP address, serial number and LAN access code
- Hubitat 2.2.5 or later
- **For control from Hubitat on newer firmware:** Developer Mode on the printer (Settings, LAN Only, Developer Mode). Monitoring works without it.

## Install

**Hubitat Package Manager:** Install → From a URL, and paste:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Bambu%20Lab%20Integration/packagemanifest.json`

**By hand:** add the two drivers in **Drivers Code** (Printer, AMS) and the app in **Apps Code**, from this folder.

## Set up

1. Open **Integrations** in the left menu and add **Bambu Lab Integration**.
2. For each printer, enter a name, the IP address, the serial number and the LAN access code. On the printer, **Settings, WLAN** (or **LAN Only**) shows the IP and access code, and **Settings, Device** shows the serial.
3. Tap **Done**. Each printer connects and adds its light and AMS devices within a few seconds.
4. Open the app again and tap **Equipment** to turn off anything you don't want in Hubitat.

A DHCP reservation for the printer keeps its IP address from changing.

## Options

| Option | Default | What it does |
|---|---|---|
| Update busy printers every | 5 seconds | X1 and H2 printers send a full report every second while printing; this processes one at most every 5 seconds. Print state changes always go through right away. |
| Report temperature changes of | 1° | Smaller changes are skipped to keep hub load down |
| Allow print control | Off | Pause, Resume, Stop and Set Speed on the Printer device |

The chamber light works whether or not print control is on.

## Print control

> **Stop ends the print for good.** Any rule, dashboard or voice command that runs it cancels the job.

Newer Bambu firmware only accepts control commands that are signed by Bambu's own software. With **Developer Mode** on, the printer accepts them from Hubitat. If a command is refused, the log says so once, with the fix. Monitoring is unaffected either way.

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

**Commands do nothing.** Check that **Allow print control** is on, then turn on Developer Mode on the printer.

**A device I deleted came back, or one is missing.** Use the app's **Equipment** page. Deleting a device from the Devices page turns it off there; turn it back on to recreate it.

## Support

[Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
