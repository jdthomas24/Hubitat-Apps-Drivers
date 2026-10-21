# Leviton Smart Load Center for Hubitat

> **Status: v2.0.2.** Brings your Leviton smart panel into Hubitat: power, current, voltage and energy for every smart breaker and CT clamp, with optional remote trip and reset. Works with LWHEM (2nd gen) and LDATA (1st gen) panels through your My Leviton account. Breaker and CT mapping ported from [rwoldberg/ldata-ha](https://github.com/rwoldberg/ldata-ha).

## What you get

| Device | What it does |
|---|---|
| **Panel** (one per panel) | Total power, voltage, frequency, firmware, online status and the cloud connection |
| **Breaker** | Power, current, voltage, frequency, energy, breaker state, and On/Off when breaker control is allowed |
| **CT clamp** (LWHEM only) | Power (negative while a source is generating), current and energy for Grid, Solar, Generator and so on |

You choose which breakers and CT clamps Hubitat creates on the app's **Equipment** page.

## Requirements

- A Leviton LWHEM or LDATA hub with smart breakers, set up in the My Leviton app
- Your My Leviton email and password. Accounts with two-step verification aren't supported yet.
- Hubitat 2.3.0 or later, with internet access (data comes from the Leviton cloud)

## Install

**Hubitat Package Manager (recommended):** Install → Search for **Leviton Smart Load Center**. Or Install → From a URL:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Leviton%20Smart%20Load%20Center/packagemanifest.json`

**By hand:** add the three drivers in **Drivers Code** (Panel, Breaker, CT) and the app in **Apps Code**, from this folder.

## Set up

1. Open **Integrations** in the left menu and add **Leviton Smart Load Center**.
2. Enter your My Leviton email and password.
3. Tap **Done**. The app signs in, adds each panel, and the panels add their breakers and CT clamps in about 30 seconds.
4. Open the app again and tap **Equipment** to turn off anything you don't want in Hubitat. Turn a whole panel off under **Panels**.

## Options

| Option | Default | What it does |
|---|---|---|
| Refresh interval | 1 minute | Energy counters and a backup read of every breaker |
| Report power changes of | 5 W | Smaller changes are skipped, which keeps hub load down. **Any change** reports everything |
| Live power updates | On | Power every few seconds. Off: power on the refresh interval only, much lighter on the hub |
| Allow breaker control | Off | Lets a breaker's **Off** trip it and **On** reset it |

## Breaker control

> **Off cuts power to that circuit.** Any rule, dashboard, voice command or "all switches off" that includes a breaker device can trip it. Keep breakers out of switch groups and voice assistants.

- Off trips the breaker; On resets it. The switch only changes after Leviton accepts the command.
- 1st gen breakers can be tripped from Hubitat but must be reset at the panel. The Breaker's Data section shows **remoteOn: supported** when it can be reset remotely.
- **switch** is on only when the breaker is on at the panel and not remotely tripped. **breakerState** and **remoteState** show the detail.

## Logging

One **Log level** in the app covers the app, every panel and every breaker and CT, and all of it shows under the app in **Logs**, labeled by device:

- **Errors Only** (default): problems only, like a failed sign-in or the Leviton cloud staying unreachable.
- **Normal**: plus connections and reconnects, breakers switching or tripping, commands, and devices added or removed.
- **Full**: everything, including every message from the Leviton cloud. Turns itself off after 30 minutes.

## Troubleshooting

**Sign-in failed.** Check the email and password in the My Leviton app, fix them here and tap **Done**.

**Panel offline / Reconnecting.** The panel reconnects on its own, waiting a little longer each try (up to 5 minutes). Check the My Leviton app shows live data. If it does and Hubitat doesn't after 10 minutes, set Log level to Full, tap **Initialize** on the Panel device and post the log in the community thread.

**A device I deleted came back, or one is missing.** Use the app's **Equipment** page. Deleting a device from the Devices page turns it off there; turn it back on to recreate it.

**Total power doesn't match a CT clamp.** Panel total power is the sum of every smart breaker, including ones you turned off in Equipment. Standard (non-smart) breakers report nothing.

## Coming from 1.2.x

1.2.x was a pair of drivers on a virtual device; 2.0.0 is an integration with new device types, so the old devices can't be upgraded in place.

1. Install 2.0.0 and set it up as above. Both versions can run side by side for a while.
2. Move your rules and dashboards to the new devices. **Settings → Swap Device** does this in one step per device.
3. Delete the old **Leviton Smart Load Center Parent** device (its breakers go with it), then the two old drivers from **Drivers Code**.

Attribute changes: **energyConsumption** is now just **energy**. Slot, poles, rating and model moved to each Breaker's Data section. The Panel's **totalPower** is now **power**, and **presence** and **wsStatus** are now **connectionStatus**.

## Removing

Remove the app from **Integrations**. That removes every panel, breaker and CT device with it.

## Support

[Hubitat Community thread](https://community.hubitat.com/t/release-leviton-smart-load-center/163950) · [Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
