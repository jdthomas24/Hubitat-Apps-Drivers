[README (4).md](https://github.com/user-attachments/files/33257231/README.4.md)
# Reolink Integration for Hubitat

Local control of Reolink cameras, doorbells, NVRs and Home Hubs from a Hubitat Elevation hub. No cloud and no Reolink account, just the device's local IP and login.

**[Try the interactive demo](https://jdthomas24.github.io/Hubitat-Apps-Drivers/Reolink%20Integration/reolinkdemo.html)**: click through adding a Home Hub, live detections, device controls and recording presets. No hub needed.

**Install:** Hubitat Package Manager, search "Reolink Integration".
**Discussion and support:** [Hubitat Community thread](https://community.hubitat.com/t/165352)

This integration is also becoming Hubitat's built-in Reolink integration. This repo stays the source of truth, and HPM installs remain available.

## Features

- **Real-time detections.** Motion, person, vehicle, pet, package and doorbell press arrive within a second over a live event connection. If that connection drops, the app falls back to polling and switches back on its own.
- **Last motion.** `lastMotionTime` and `lastMotionType` for rules and dashboards.
- **Doorbell press** as a standard button (pushed 1), so Rule Machine treats it like any doorbell.
- **Snapshots and live video.** Cached snapshots for dashboard tiles, plus RTSP live streaming through Hubitat's video stream service on supported hubs.
- **Camera controls.** PTZ moves, presets and calibration; spotlight on/off and spotlight auto (whether motion turns it on at night); night vision; siren; PIR on/off for battery devices.
- **Battery devices.** Battery level, charging status (charging / plugged in / not charging) and a one-line Battery Wired summary. Wired devices never show battery values.
- **NVR recording control.** A master record switch, plus named per-channel schedule presets that Hubitat modes or rules can load (for example "Away" and "Home").
- **Capability detection.** A `supportedFeatures` attribute per device, read from the camera, shows what that model can actually do.
- **Logging levels.** Errors Only (default), Normal or Full. Full turns itself off after 60 minutes.

## Device commands

Commands are grouped into dropdowns so each device page stays short.

| Command | Options |
|---|---|
| Check | battery, abilities, ptz calibration, recording schedule |
| Set Interval | poll or snapshot, plus seconds |
| Ptz | Left, Right, Up, Down, ZoomInc, ZoomDec, Stop, Calibrate |
| Ptz Preset | go to or save here, plus preset ID |
| Set Spotlight | on, off, auto on, auto off |
| Set Night Vision | auto, on, off |
| Set Siren | on, off |
| Set Pir | on, off |
| Take Snapshot | |

Doorbells have Check, Set Interval, Set Pir and Take Snapshot. Hubitat shows every command on every device, so check `supportedFeatures` to see what a given model supports. Rules written before 1.6.8 with the older command names (Pir Off, Ptz Go To Preset, Spotlight On and so on) keep working.

## How it works

- **Sources.** A source is anything with its own IP and login: a standalone camera, an NVR or a Home Hub. A Hub or NVR has one channel per paired camera; a standalone camera has one channel.
- **Reolink Device Bridge.** Each source gets a bridge device that holds its live event connection and is the parent of its camera and doorbell devices, so they group together in the Devices list. Standalone cameras' bridges group under a single "Reolink Standalone Devices" device.
- **The app** (ParentApp.groovy) handles login, discovery, polling and every camera command. The camera and doorbell drivers are thin and pass everything to the app.
- **Hub health.** One central scheduler times all polling and skips any source with a healthy event connection. Battery devices poll slowly by default, since polling harder doesn't get fresher data and only costs battery.

## Installation

**Hubitat Package Manager (recommended):** Install, search "Reolink Integration". HPM installs the app and all four drivers.

**Manual:** add these in order, then add the app under Apps, Add User App:
1. Drivers Code: ReolinkDeviceBridge.groovy, StandaloneDevices.groovy, CameraDriver.groovy, DoorbellDriver.groovy
2. Apps Code: ParentApp.groovy

## Setup

1. **On the camera, Hub or NVR first:** enable HTTP, HTTPS and ONVIF under Network > Advanced (or Server) settings. They are often off by default, and this is the most common reason a source won't connect. On a Home Hub, these toggles are only in the Reolink desktop Client. Once a camera is paired to a Hub, manage these settings on the Hub.
2. Open the app and add a source with its IP address or hostname, port (default 443), username and password.
3. Discovery lists the channels. Turn on the ones you want and apply.
4. The live event connection uses port 9000. If a firewall blocks it, the source still works by polling.

To change a source's address or login later, use **Edit connection settings** on its page. There's no need to remove and re-add it.

The in-app **Tips & Troubleshooting** page covers device quirks, polling, battery behavior, PTZ, spotlight and recording presets, and is kept current with each release.

## Compatibility

| Device | Status |
|---|---|
| Home Hub Pro | ✅ Working, with multiple paired cameras on one event connection |
| Reolink NVRs (e.g. RLN16-410) | ✅ Working, confirmed by community users, including recording control |
| E1 Pro, E1 Zoom, Trackmix | ✅ Working, PTZ confirmed |
| RLC-1240A, Duo 3V PoE | ✅ Working |
| RLC-410W | ✅ Working (older model, motion only, no AI detection) |
| E1 Outdoor | ✅ Working. Some ~2021 firmware units give false "asleep" reports (see Tips) |
| Video Doorbell WiFi (wired) | ✅ Working, package detection confirmed |
| Argus 4 Pro and other battery cameras/doorbells | ✅ Working behind a Home Hub or NVR. ❌ Not standalone (see below) |
| Base E1, Lumus | ❓ Untested. Check the camera's own network settings for HTTP/HTTPS |

Other PoE and WiFi cameras outside the battery line are expected to work standalone.

## Known limitations

- **Battery-class devices need a Home Hub or NVR.** Confirmed on real hardware: a standalone Argus 4 Pro refuses both the HTTP API and the event port, even on a DC adapter. Add the Hub or NVR as the source and the device shows up as one of its channels.
- **Many standalone cameras.** Each standalone camera keeps its own connection to the hub. Past roughly 10, a Home Hub or NVR is the better setup: one connection and one login for every channel.
- **Recording control is NVR/Hub only**, and the master record switch applies to every channel at once (a Reolink API limitation). Use presets to control individual channels.
- **Spotlight auto in Hubitat** updates right away when changed from Hubitat. A change made in the Reolink app shows after a Refresh (read at most every 10 minutes) or Check > abilities.

## Security

The source password is stored only in the app on your hub. It is never written to logs, and the Device Bridge page shows only "Password: Saved, N characters" plus the last login result. Like most Hubitat apps, credentials live in the app's own settings, which are included in hub backups.

## Contributing and testing

Testing on more hardware helps, especially:
- A true external or accessory floodlight
- Newer battery doorbell firmware, including whether any battery model now exposes a local API

The `Tests - Groovy RAW` folder has standalone tools for dumping a device's raw API data. Reports and logs are welcome in the community thread.
