# Apple TV Integration for Hubitat

Native, local control of Apple TVs from Hubitat Elevation. Turn your Apple TVs on and off, control playback, press remote buttons and launch apps from scenes, rules and dashboards, with live power and playback state. No Homebridge, no Home Assistant, no Raspberry Pi, no Python.

> **Status: v1.2.0.** Install through HPM: search for **Apple TV Integration**, or use the manifest URL below.

**Forum thread:** [Apple TV Integration on the Hubitat Community](https://community.hubitat.com/t/166801)

---

## What it does

- **Finds your Apple TVs automatically** over Bonjour. No IP addresses or ports to type.
- **Shows each Apple TV's real name**, read from the Apple TV itself.
- **One-time pairing** with the 4-digit PIN shown on the TV.
- **One device per Apple TV** with a standard **Switch**: on wakes it, off puts it to sleep.
- **Live power state** (`asleep`, `screensaver`, `awake`), including changes made with the Siri Remote.
- **Playback controls:** Play, Pause, Stop, Next, Previous and Skip, with live `playing` / `paused` / `stopped` state.
- **Siri Remote buttons and volume**, including one that goes straight to the Home Screen and the TV button (twice for the App Switcher).
- **Launch apps by name**, from a dropdown of common apps or any installed app.
- **Now playing title** for apps that share it (YouTube, Plex, NBC and Apple's TV app do; Netflix doesn't).
- **Which app is playing** (optional, off by default): turn on **Track current app** and each Apple TV reports `currentApp` and `playbackState`, so rules can react when Plex or Netflix starts.
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
2. **Drivers Code → New Driver:** paste `AppleTVHelper.groovy` and save (used by Track current app).
3. **Apps Code → New App:** paste `ParentApp.groovy` and save.

## Setup

1. Open **Integrations** in the left menu and add **Apple TV Integration**.
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
| `remoteButton` | `up`, `down`, `left`, `right`, `select`, `back`, `homeScreen`, `tvButton`, `playPause`, with an optional number of presses |
| `launchApp` | Opens an app picked from the list, or typed under Other (name, bundle ID or app link) |
| `transportStatus` | `playing`, `paused` or `stopped`, including changes made with the Siri Remote |
| `nowPlaying` | The title, for apps that share it, otherwise `none`. Arrives when playback starts |

**State Variables** show **Installed Apps** (names you can type under Other) and **Last Launch From Hubitat**, for example `Netflix (launched)` or `Hulu (failed: not installed)`. It only covers launches sent from Hubitat. For the app actually playing, use Track current app below.

**Notes**
- `homeScreen` holds Back, like the Siri Remote, so it always goes to the Home Screen. Use it in rules when you just want to go home.
- `tvButton` presses the Siri Remote's TV button. One press follows each Apple TV's TV Button setting (Settings → Remotes and Devices → TV Button), so it may open the Apple TV app instead of the Home Screen. Two presses (times 2) open the App Switcher.
- App links open only in apps that support them, and the Apple TV asks you to confirm. YouTube web links are converted to the YouTube app's own link automatically.

### Track current app

Off by default. Turn on **Track current app** under Quick Settings in the app, and each Apple TV device adds:

| Attribute | What it shows |
|---|---|
| `currentApp` | The app's name, or `none` |
| `currentAppId` | Its bundle ID, e.g. `com.plexapp.plex` |
| `playbackState` | `playing`, `paused`, `stopped` or `idle` |
| `title`, `artist`, `duration` | When the app shares them |

- **No extra PIN.** It reuses the Apple TV's existing pairing.
- **For rules, check `playbackState` is `playing`, not just `currentApp`.** The Apple TV never announces the Home Screen, so leaving an app shows it as paused.
- When the Apple TV sleeps it clears to `none` / `stopped`. After waking or reconnecting it stays `none` until an app actually plays.
- It adds three hidden helper devices per Apple TV, named after the TV (`Living Room control`, `Living Room event`, `Living Room data`). They hold the extra connections and stay out of dashboards and rules. Turning the setting off removes them.
- Running the Apple TV Now Playing (Beta)? Remove it before turning this on.

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
- **Apple TV 4K (1st gen) on Ethernet goes offline while asleep:** some of these units drop off the network when they sleep. Wi-Fi, an IR blaster or HDMI-CEC from the TV are workarounds for waking it.
- **Device shows offline:** check that the Apple TV has power and network. It retries on its own every 5 minutes. If it says to pair again, the pairing was removed on the Apple TV side.

## Credits

Protocol knowledge from the [pyatv](https://github.com/postlund/pyatv) project by Pierre Ståhl (MIT), whose documentation of Apple's Companion Link protocol made this possible.

## License

See the repository license.
