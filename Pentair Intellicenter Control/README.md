# Pentair IntelliCenter for Hubitat

> **Status: v1.7.1.** Local control of a Pentair IntelliCenter pool controller. No cloud, no account: Hubitat talks straight to the controller on your network.

## What you get

| Device | What it does |
|---|---|
| **Pool / Spa** (Body) | On/off (runs the pump), water temperature, set point, heat source, Heat Lock, dashboard tile |
| **Pump** | RPM, GPM and watts (when the pump reports them), running body's water temperature, speed control, dashboard tile |
| **Circuits, features and groups** | A switch for each (lights, waterfalls, cleaner, blower, light shows, circuit groups) |
| **Sensors** | Air and solar temperature |
| **Chlorinator** | IntelliChlor salt level, pool and spa output %, superchlorinate (read-only, when the controller reports one) |

You choose which of these Hubitat creates on the app's **Equipment** page.

## Requirements

- IntelliCenter 1 (firmware 1.064 or later) or IntelliCenter 2
- The controller and the hub on the same network
- Hubitat 2.3.0 or later

**IntelliCenter 2 firmware note:** tested up to 3.004. On 3.006 and later, some users have seen heat source and heater mode changes rejected by the controller. Everything else (pump, circuits, temperatures) works. Reports from 3.008 are welcome.

## Install

**Hubitat Package Manager (recommended):** Install → From a URL, and paste:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Pentair%20Intellicenter%20Control/packagemanifest.json`

**By hand:** add the four drivers in **Drivers Code** (Bridge, Body, Pump, Chlorinator) and the app in **Apps Code**, from this folder.

## Set up

1. Open **Integrations** in the left menu and add **Pentair IntelliCenter**.
2. Enter the controller's IP address. Leave the port at **6680** (some IntelliCenter 2 systems use **6681**).
3. Tap **Done**. The Bridge connects and adds your equipment in about 20 seconds.
4. Open the app again and tap **Equipment** to turn off anything you don't want in Hubitat.

**Finding the IP:** your router's device list (look for IntelliCenter or Pentair), or the Pentair app under Settings → Status → Network. A DHCP reservation keeps it from changing.

## Pool and spa commands

| Command | What it does |
|---|---|
| On / Off | Starts or stops the body (and its pump) |
| Set Heating Setpoint | Changes the set point |
| Heat And Start | Sets the set point, picks a heat source (keeps the current one, else Heater) and turns the body on |
| Stop Heat | Turns heat off and leaves the pump running |
| Set Heat Source | Off, Heater, Solar Only, Solar Preferred, Heat Pump, Heat Pump Preferred |
| Set Heat Lock | **locked** blocks every heat change from Hubitat (set point, source, Heat And Start). Stop Heat still works |
| Refresh | Re-reads the body from the controller |

Rules made with the 1.6.x command names (🔥 Heat and Start Pump, ⚙ Set Heat Source, and so on) keep working.

**Dashboard tile:** add the Pool, Spa or Pump device as an Attribute tile and pick the **tile** attribute.

## Pump

**Set Speed** changes the RPM of the running program (limits are in the pump's preferences). The pump runs with its body, so turn it on and off from the Pool or Spa device.

## Logging

One **Log level** in the app covers the app, the Bridge and every pool device, and all of it shows under the app in **Logs**, labeled by device:

- **Errors Only** (default): problems only, like the controller going offline.
- **Normal**: plus connections, commands, equipment turning on and off, and devices added or removed.
- **Full**: everything, including every message to and from the controller. Turns itself off after 30 minutes.

## Troubleshooting

**No devices after setup.** Check the IP, and that the Bridge device shows **connectionStatus: Connected**. Set Log level to Full, tap **Initialize** on the Bridge, and check the logs.

**Bridge shows "Waiting for hub startup".** Normal right after a hub reboot. The Bridge waits 3 minutes so the hub can finish starting up, then connects on its own. Tap **Refresh** on the Bridge if you need it sooner.

**Controller offline.** The Bridge retries every 2 minutes and reconnects on its own if the controller goes quiet. Check the controller has power and network, and that nothing blocks port 6680.

**A device I deleted came back, or one is missing.** Use the app's **Equipment** page. Deleting a device from the Devices page turns it off there; turn it back on to recreate it.

**Heat commands do nothing.** Check Heat Lock is unlocked, the heater is assigned to that body in the Pentair app (Settings → Advanced Settings → Heaters), and the IntelliCenter 2 firmware note above.

**No chlorinator device.** The controller has to report the salt cell. If the Pentair app shows salt but Hubitat doesn't, set Log level to Full, tap Initialize on the Bridge, and post the lines with `CHEM` in the community thread.

## Removing

Remove the app from **Integrations**. That removes the Bridge and every pool device with it.

## Support

[Hubitat Community thread](https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31) · [Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
