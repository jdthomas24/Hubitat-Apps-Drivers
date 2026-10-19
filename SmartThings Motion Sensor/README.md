# SmartThings Motion Sensor Enhanced for Hubitat

> **Status: v1.8.2.** A local Zigbee driver for SmartThings, Samsung and Aeotec motion sensors: motion, temperature and battery, with a battery curve tuned for the CR2 cells these sensors use, plus Zigbee signal monitoring. Companion to [Battery Monitor 2.0](../Battery%20Monitor%202.0) and [Device Health Monitor](../Device%20Health%20Monitor).

## Supported models

| Model | Fingerprint |
|---|---|
| SmartThings STS-IRM-250 | motionv4 |
| SmartThings STS-IRM-251 | motionv5 |
| Aeotec Zigbee motion | GP-AEOMSSUS |
| Samsung SmartThings motion | GP-U999SJVLBAA |

## What it does

- **Motion** with an adjustable reset time, so motion ends cleanly even if the sensor misses its own inactive report.
- **Temperature** with an offset, or turned off entirely (no events, no logs) if you don't use it.
- **Battery** from voltage on a CR2 lithium curve (3.0 V fresh, about 2.8 V for most of its life, steep drop below 2.5 V), with finer steps near the end of life and smoothing between readings. `batteryVoltage` shows the raw reading.
- **Signal:** `lqi`, `rssi` and a plain-language `routeHealth`.
- **Health Check:** ping support for apps that use it.

## Install

**Hubitat Package Manager (recommended):** Install → Search for **SmartThings Motion Sensor Driver**. Or Install → From a URL:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/SmartThings%20Motion%20Sensor/packagemanifest.json`

**By hand:** Drivers Code → New Driver → Import, paste
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/SmartThings%20Motion%20Sensor/SmartThingsMotion.groovy`
then Save.

## Set up

1. Pair the sensor from **Devices → Add Device → Zigbee**. Supported models pick this driver on their own.
2. If it paired as a generic device, open it, set **Type** to **SmartThings Motion Sensor Enhanced**, Save, then tap **Configure**.
3. Adjust the preferences below if you like and tap **Save Preferences**.

## Preferences

| Preference | Default | What it does |
|---|---|---|
| Motion Reset Time | 30 seconds | How long after the last motion before it returns to inactive |
| Enable Temperature Reporting | On | Off stops temperature events and clears the attribute |
| Temperature Offset | 0 | Added to every reading |
| Battery Reporting Interval | 240 minutes | How often the sensor reports battery (30 to 360 minutes) |
| Enable Info Logging | On | Motion, temperature and battery readings |
| Enable Debug Logging | Off | Everything, including signal readings. Turns itself off after 30 minutes |

Changing temperature reporting or the battery interval reconfigures the sensor. Other changes don't, so saving preferences won't interrupt its reporting.

## Route health

| routeHealth | LQI |
|---|---|
| Excellent | 150 or more |
| Good | 100 to 149 |
| Weak | 60 to 99 |
| Poor | under 60 |

Weak or Poor usually means the sensor needs a closer repeater (any mains-powered Zigbee device).

## Troubleshooting

**Battery reads oddly after a battery change.** Each reading is averaged with the last one, so it climbs over a few reports. A fresh CR2 should land at or near 100%.

**Battery Monitor shows erratic drain.** Keep the battery interval at 240 minutes or longer. Very frequent reports make small voltage swings look like drain.

**Motion is slow or reports are missing.** Check `routeHealth`. Zigbee mesh quality has the biggest effect on these sensors.

## Support

[Hubitat Community thread](https://community.hubitat.com/t/release-smartthings-motion-sensor-driver-enhanced/162512) · [Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
