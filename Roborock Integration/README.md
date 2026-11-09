# Roborock Integration for Hubitat (beta)

> **Status: v0.2.5 beta.** Local control of Roborock vacuums. After a one-time sign-in, Hubitat talks straight to the vacuum on your network.

## What you get

One device per vacuum with:
- **Status:** state, battery, error, dock error, current room, clean time, area and percent, fan power, mop mode, mop water mode, consumables
- **Commands:** Vacuum Control (Start, Pause, Resume, Dock), clean selected rooms (with optional mop water level), run a Roborock scene, raw commands
- **Connection:** healthStatus, IP and the local protocol in use

Attribute names match bloodtick's Roborock driver, so dashboards carry over. Command names are different (see **Moving from bloodtick's driver** below).

## Supported vacuums

| Vacuum | Local protocol | Status |
|---|---|---|
| Newer models (Qrevo Curv / Curv X, Saros, S8 MaxV Ultra) | L01 | Tested on a Qrevo Curv X and Saros 10R |
| Older models (S5 to S8, earlier Qrevo and Q Max) | 1.0 | Tested on a Qrevo MaxV and Q5 Max+ |
| China-market units used outside China | | May refuse local status (error -10002) |
| Dyad, Zeo, Q7 / Q10 family | A01 / B01 | Not supported |

## Install

**HPM:** Install → From a URL:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Roborock%20Integration/packageManifest.json`

**By hand:** add `VacuumDriver.groovy` and `CloudHelper.groovy` in **Drivers Code** and `ParentApp.groovy` in **Apps Code**.

## Set up

1. Open **Integrations** (or **Apps**) and add **Roborock Integration**.
2. Enter your Roborock account email and region, tap **Email me a sign-in code**, enter the code, then tap **Sign in**.
3. Your vacuums appear. Enter each one's **IP address** (set a DHCP reservation in your router so it doesn't change).
4. Tap **Done**. Each vacuum gets a device and connects within a few seconds (`healthStatus: online`).

The vacuum's **MAC** shows on its card and device page once connected. Set a DHCP reservation for it in your router. If the IP changes anyway and you're signed in, the app finds the new one through Roborock's cloud and reconnects.

## Local vs cloud

| Local (works with the internet down) | Needs Roborock's cloud |
|---|---|
| All status: state, room, area, time, percent, battery, errors, modes, consumables | Sign-in and **Rescan** (vacuums, local keys, room names, scenes) |
| Commands from Hubitat: Vacuum Control, Vacuum Rooms, fan and mop settings through Execute | **Vacuum Scene** (scenes live in your Roborock account) |
| | **IP recovery**: finds a vacuum's new IP if it changes (automatic, only when it can't be reached) |
| | Jobs started from the Roborock app (Hubitat still tracks them locally once they start) |

Signing out doesn't stop local control. If the app's banner says **Sign in again**, Roborock stopped accepting the saved sign-in: sign out and back in. The same table is on the app's **Tips & Troubleshooting** page.

## How it stays up to date

The vacuum doesn't send updates on its own, so the driver checks it:
- every **20 seconds** while it's cleaning, returning or at work on the dock
- every **2 minutes** while docked (**Docked poll** setting: 1, 2 or 5 minutes)
- **3 seconds** after any command

Clean area shows as **cleanArea (sq ft)** or **cleanArea (sq m)**. The **Area unit** preference on the vacuum device follows the hub by default: square meters when the hub uses Celsius, square feet when it uses Fahrenheit. Pick one there to override it.

A job started from the Roborock app or a schedule shows up within the docked poll time. A small keepalive every 30 seconds keeps the connection open.

**switch** is on while the vacuum is working (cleaning, returning, washing or emptying at the dock) and off when it's docked, paused or idle. **Refresh** checks right away and logs one line, for example "Refreshed, no changes".

## Rooms and scenes

- **roomsList** shows each room as `( Room name: ID )`. Use the IDs with **Vacuum Rooms**, e.g. `16, 17`.
- **scenesList** shows your Roborock scenes. **Vacuum Scene** runs one through the Roborock cloud (the only command that does).
- Renamed rooms or new scenes: tap **Rescan vacuums and rooms** in the app. Roborock limits how often home data can be read, so Rescan waits 5 minutes between scans. If Roborock says there were too many requests (9002), wait an hour.
- Multi-level homes: room IDs only cover the map the vacuum has loaded. Switch maps in the Roborock app, then Rescan.
- Rescan flags a vacuum that's no longer in your Roborock account (Not in your Roborock account on its card). Its device is kept until you turn off Include on its card and tap Done.
- **mopMode** and **mopWaterMode** show the vacuum's saved settings, not a one-time choice made in the Roborock app for a single run.
- Vacuums with a water flow slider (like the Saros) show the app's name in **mopWaterMode** and the slider number (1 to 30) in **mopWaterLevel**.

## Execute (advanced)

**Execute** sends a Roborock command straight to the vacuum. Type the command, and any parameters as a JSON list (leave blank if none). It works from Rule Machine custom actions too, for example to set fan power before a room clean.

| Command | Params | What it does |
|---|---|---|
| `find_me` | (blank) | The vacuum announces itself |
| `set_custom_mode` | `[104]` | Fan power: 101 Quiet, 102 Balanced, 103 Turbo, 104 Max, 108 Max+ |
| `set_mop_mode` | `[301]` | Mop route: 300 Standard, 301 Deep, 303 Deep+, 304 Fast |
| `set_water_box_custom_mode` | `[202]` | Water flow on older models: 200 Off, 201 Low, 202 Medium, 203 High (not tested on slider models like the Saros yet) |
| `get_status` | (blank) | With Log level on Full, the raw reply shows in the logs |

Nothing is checked before sending. A wrong command or parameter is rejected by the vacuum, and the reason shows in the logs.

## Moving from bloodtick's driver

Attributes are the same, apart from `rooms` and `scenes`, which are `roomsList` and `scenesList` here. Commands take the same parameters, but the names changed, so update your rules:

| bloodtick | Roborock Integration |
|---|---|
| appClean | Vacuum Control: Start |
| appDock | Vacuum Control: Dock |
| appPause | Vacuum Control: Pause |
| appRoomResume | Vacuum Control: Resume |
| appRoomClean | vacuumRooms |
| appScene | vacuumScene |
| execute | execute |

Both can run side by side while you move rules over. Hubitat's **Swap Device** doesn't list app devices, so:

1. Add the vacuum here first.
2. Open the old device. Its **In Use By** list (bottom of the device page) shows every rule, dashboard and app using it.
3. Re-point each one to the new device, switching commands as in the table.
4. Remove the old device once its In Use By list is empty.

**From 0.2.3 or earlier of this integration:** vacuumStart, vacuumPause, vacuumResume and vacuumDock are now **Vacuum Control** with a Start, Pause, Resume or Dock choice. Update rules and Maker API calls that used them (on and off still work). Metric hubs now get `cleanArea (sq m)` in place of `cleanArea (sq ft)` (0.2.5).

## Logging

One **Log level** in the app covers every vacuum. **Full** includes every request and turns itself off after 3 hours (during the beta).

## Beta testers: Diagnostics

Tap **Diagnostics** on the vacuum device. It logs one block (always shown, whatever the log level) with your model, model code, firmware, protocol and the raw status the vacuum reports. Copy everything from `DIAGNOSTICS` to `END` and send it along with any warnings or errors. It never includes your local key. If Model code or Firmware says "tap Rescan", tap **Rescan vacuums and rooms** in the app first.

## Troubleshooting

**Need help on the device?** Each vacuum device has a **help** link to the app's Tips & Troubleshooting page.

**healthStatus stays offline.** Check the IP in the app, and that the vacuum is on the same network as the hub. If it's powered and on Wi-Fi but stays offline, open the Roborock app and check it's still signed in (an expired app session can cut off local access). Set Log level to Full, tap **Initialize** on the device, and look for "HELLO" lines.

**The local key isn't in State Variables.** On purpose since 0.2.4: the driver keeps it in memory and only stores a short fingerprint.

**Commands are rejected.** The log shows the command and the vacuum's reply. Post it in the beta thread with your model.

## Credits

Protocol details from [python-roborock](https://github.com/Python-roborock/python-roborock) and the [Homebridge Roborock plugin](https://github.com/tasict/homebridge-roborock-vacuum) (L01 support). Sign-in flow and attribute names from bloodtick's Roborock driver.

## Support

[Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
