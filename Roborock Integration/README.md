# Roborock Integration for Hubitat (beta)

> **Status: v0.1.3 beta.** Local control of Roborock vacuums. After a one-time sign-in, Hubitat talks straight to the vacuum on your network.

## What you get

One device per vacuum with:
- **Status:** state, battery, error, dock error, current room, clean time, area and percent, fan power, mop mode, mop water mode, consumables
- **Commands:** Start, Dock, Pause, Resume, clean selected rooms (with optional mop water level), run a Roborock scene, raw commands
- **Connection:** healthStatus, IP and the local protocol in use

Attribute and command names match bloodtick's Roborock driver, so rules and dashboards built on it carry over.

## Supported vacuums

| Vacuum | Local protocol | Status |
|---|---|---|
| Newer models (Qrevo Curv / Curv X, S8 MaxV Ultra, likely Saros) | L01 | Tested on a Qrevo Curv X |
| Older models (S5 to S8, earlier Qrevo and Q Max) | 1.0 | Built in, needs testers |
| Dyad, Zeo, Q7 / Q10 family | A01 / B01 | Not supported |

## Install

**HPM:** Install → From a URL:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Roborock%20Integration/packageManifest.json`

**By hand:** add `VacuumDriver.groovy` in **Drivers Code** and `ParentApp.groovy` in **Apps Code**.

## Set up

1. Open **Integrations** (or **Apps**) and add **Roborock Integration**.
2. Enter your Roborock account email and region, tap **Email me a sign-in code**, enter the code, then tap **Sign in**.
3. Your vacuums appear. Enter each one's **IP address** (set a DHCP reservation in your router so it doesn't change).
4. Tap **Done**. Each vacuum gets a device and connects within a few seconds (`healthStatus: online`).

Your account is only used to bring in the vacuums, their local keys, room names and scenes. Status and commands are local.

## How it stays up to date

The vacuum doesn't send updates on its own, so the driver checks it:
- every **20 seconds** while it's cleaning, returning or at work on the dock
- every **2 minutes** while docked (**Docked poll** setting: 1, 2 or 5 minutes)
- **3 seconds** after any command

A job started from the Roborock app or a schedule shows up within the docked poll time. A small keepalive every 30 seconds keeps the connection open.

## Rooms and scenes

- **roomsList** shows `ID: Room name` pairs. Use the IDs with **Vacuum Rooms**, e.g. `16, 17`.
- **scenesList** shows your Roborock scenes. **Vacuum Scene** runs one through the Roborock cloud (the only command that does).
- Renamed rooms or new scenes: tap **Rescan vacuums and rooms** in the app.

## Logging

One **Log level** in the app covers every vacuum. **Full** includes every request and turns itself off after 30 minutes.

## Troubleshooting

**healthStatus stays offline.** Check the IP in the app, and that the vacuum is on the same network as the hub. Set Log level to Full, tap **Initialize** on the device, and look for "HELLO" lines.

**Commands are rejected.** The log shows the command and the vacuum's reply. Post it in the beta thread with your model.

## Credits

Protocol details from [python-roborock](https://github.com/Python-roborock/python-roborock) and the [Homebridge Roborock plugin](https://github.com/tasict/homebridge-roborock-vacuum) (L01 support). Sign-in flow and attribute names from bloodtick's Roborock driver.

## Support

[Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
