# Apple TV Integration for Hubitat

Native, local control of Apple TVs from Hubitat Elevation. Turn your Apple TVs on and off, control playback, press remote buttons and launch apps from scenes, rules and dashboards, with live power and playback state. No Homebridge, no Home Assistant, no Raspberry Pi, no Python.

> **Status: beta (v1.1.0).** Install through HPM using the manifest URL below. It will be added to the HPM search once testing wraps up.

**Forum thread:** [Apple TV Integration on the Hubitat Community](https://community.hubitat.com/t/166801)

---

## What it does

- **Finds your Apple TVs automatically** over Bonjour. No IP addresses or ports to type.
- **Shows each Apple TV's real name**, read from the Apple TV itself.
- **One-time pairing** with the 4-digit PIN shown on the TV.
- **One device per Apple TV** with a standard **Switch**: on wakes it, off puts it to sleep.
- **Live power state** (`asleep`, `screensaver`, `awake`), including changes made with the Siri Remote.
- **Playback controls:** Play, Pause, Stop, Next, Previous and Skip, with live `playing` / `paused` / `stopped` state.
- **Siri Remote buttons and volume**, including a button that goes straight to the Home Screen.
- **Launch apps by name**, from a dropdown of common apps or any installed app.
- **Now playing title** for apps that share it (YouTube, Plex, NBC and Apple's TV app do; Netflix doesn't).
- **Stays connected** through one encrypted local connection per Apple TV, and reconnects on its own after hub reboots, network drops and IP changes.
- **Set up any number of Apple TVs in one place**, with default names like `Apple TV - Living Room` so they never collide with lights or speakers named after the room.

It uses the same protocol as the Apple TV Remote app and Control Center (Companion Link). Everything stays on your local network.

## Requirements

- Apple TV HD or Apple TV 4K on a current tvOS
- Apple TV and hub on the same network, with Bonjour (mDNS) able to reach the hub
- Hubitat Elevation hub

Only Apple TVs are listed. iPhones, iPads and Macs are filtered out. HomePods and TTS announcements stay with Hubitat's built-in AirPlay integration, which this does not replace.

## Install

### Hubitat Package Manager (recommended)

1. Open **Hubitat Package Manager** and choose **Install**.
2. Choose **From a URL** and paste:
   ```
   https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Apple%20TV%20Integration/packagemanifest.json
   ```
3. Finish the install. HPM will notify you of updates from then on.

### Manual

1. **Drivers Code → New Driver:** paste `AppleTVDriver.groovy` and save.
2. **Apps Code → New App:** paste `ParentApp.groovy` and save.

## Setup

1. **Apps → Add User App → Apple TV Integration.**
2. Give it about 30 seconds, then click **Refresh**. Your Apple TVs show under **Found on your network**. Not listed? Wake it with its remote and click Refresh, or type its IP under **Not listed? Add by IP** and click **Add**.
3. Change the **device name** if you like, then click **Pair**.
4. A PIN appears on the TV within a few seconds. Type it in and click **Submit PIN**.
5. Repeat for each Apple TV, then click **Done**.

After pairing, the Apple TV lists **Hubitat** under Settings → Remotes and Devices → Remote App and Devices. Removing it there unpairs the hub.

## Using it

| Attribute / command | What it does |
|---|---|
| `on` | Wakes the Apple TV |
| `off` | Puts the Apple TV to sleep |
| `switch` | `on` when awake or on the screensaver, `off` when asleep |
| `power` | `asleep`, `screensaver` or `awake` |
| `connection` | `connected`, `connecting`, `reconnecting`, `offline` or `not paired` |
| `refresh` | Re-reads the power state and the installed apps |
| `play`, `pause`, `stop`, `playPause` | Playback. The Apple TV has no real stop, so `stop` pauses |
| `nextTrack`, `previousTrack`, `skipForward`, `skipBackward` | Next and previous, skip 10 seconds |
| `volumeUp`, `volumeDown` | Volume, with an optional number of steps |
| `remoteButton` | `up`, `down`, `left`, `right`, `select`, `back`, `homeScreen`, `playPause`, with an optional number of presses |
| `launchApp` | Opens an app picked from the list, or typed under Other (name, bundle ID or app link) |
| `transportStatus` | `playing`, `paused` or `stopped`, including changes made with the Siri Remote |
| `nowPlaying` | The title, for apps that share it, otherwise `none`. Arrives when playback starts |

**State Variables** show **Installed Apps** (names you can type under Other) and **Last Launch From Hubitat**, for example `Netflix (launched)` or `Hulu (failed: not installed)`. It only covers launches sent from Hubitat, since the Apple TV doesn't report which app is on screen.

**Notes**
- `homeScreen` holds Back, like the Siri Remote, so it always goes to the app grid. The Siri Remote's TV button isn't offered because it follows each Apple TV's own setting and often opens the TV app.
- App links open only in apps that support them, and the Apple TV asks you to confirm. YouTube web links are converted to the YouTube app's own link automatically.

**Turn the TV on too:** on the Apple TV, enable **Settings → Remotes and Devices → Control TVs and Receivers** (HDMI-CEC). Waking the Apple TV then wakes the TV and switches its input.

## Logging

All logs for the integration show under the app, labeled by device. Set **Log level** in the app:

- **Errors Only** (default): real problems, such as an Apple TV going offline
- **Normal:** adds connections, commands, power and playback changes, and now playing titles
- **Full:** adds protocol detail for troubleshooting

## Troubleshooting

- **Apple TV not found:** wake it with its remote, then click Refresh. If it still isn't listed, add it by IP (find the IP on the Apple TV under Settings → Network). Networks that separate devices into VLANs need an mDNS repeater.
- **No PIN on screen:** wake the Apple TV first, and check that its AirPlay access setting (Allow Access) lets devices on your network connect.
- **"Back off" error while pairing:** too many attempts in a row. Wait a minute and try again.
- **Wrong name in the Found list:** the name comes from the Apple TV (Settings → General → About → Name). Your Hubitat device name is separate and never overwritten.
- **Device shows offline:** check that the Apple TV has power and network. It retries on its own every 5 minutes. If it says to pair again, the pairing was removed on the Apple TV side.

## Credits

Protocol knowledge from the [pyatv](https://github.com/postlund/pyatv) project by Pierre Ståhl (MIT), whose documentation of Apple's Companion Link protocol made this possible.

## License

See the repository license.
