# Apple TV Now Playing (Beta) 1.2.0.10

Lets Hubitat see what your Apple TV is doing: **which app** is playing (Netflix, Plex, YouTube...) and whether it's **playing or paused**. Some apps also share the title.

This is an **experimental beta**. It's separate from Apple TV Integration and doesn't change it. There's no timeline, and it may or may not end up in the main integration.

**How is this different from Hubitat's built-in AirPlay integration?** The built-in one shows play state and a title. This beta adds **which app is playing** and whether the TV is **awake or asleep**, as separate attributes. That lets rules tell Netflix from Plex from YouTube. Both can run side by side.

## Install

**With Hubitat Package Manager (recommended, makes updates one click):**

1. Open **HPM → Install → From a URL**
2. Paste this link and follow the prompts:
   `https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Tests%20-%20Groovy%20RAW/Apple%20TV%20Integration%20BETA/packagemanifest.json`

It won't show up in HPM's search. That's on purpose, since it's a beta.

**Or by hand (2 links):**

1. **Apps Code → New App → Import**, paste this link, then **Save**:
   `https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Tests%20-%20Groovy%20RAW/Apple%20TV%20Integration%20BETA/AppleTVNowPlayingApp.groovy`
2. **Drivers Code → New Driver → Import**, paste this link, then **Save**:
   `https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Tests%20-%20Groovy%20RAW/Apple%20TV%20Integration%20BETA/AppleTVNowPlayingDriver.groovy`

## Set up

1. Open **Integrations** in the left menu and add **Apple TV Now Playing (Beta)**
2. Your Apple TVs show up under **Found on your network**. If one is missing, wake it with its remote and tap **Refresh**, or use **Add by IP**.
3. Tap **Pair**. A 4-digit PIN appears on the TV. Type it in and tap **Submit PIN**.
4. Tap **Done**. The device connects on its own and starts reporting.

Pairing adds "Hubitat" to the Apple TV's list of remotes and devices. This pairing is separate from the Apple TV Integration's.

## What the device shows

| Attribute | Values |
|---|---|
| `currentApp` | Netflix, Plex, YouTube... or `none` |
| `currentAppId` | The app's ID, e.g. `com.plexapp.plex` (most reliable for rules) |
| `playbackState` | `playing`, `paused`, `stopped`, `idle` |
| `title`, `artist`, `duration` | Only when the app shares them (Netflix doesn't, Plex and YouTube do). `none` / 0 otherwise |
| `tvState` | `awake` or `asleep` |
| `connection` | `connected`, `connecting`, `reconnecting`, `offline`, `disconnected` |

The device also creates two hidden helper devices, "(link)" and "(setup link)", that hold its extra connections. Each device on the hub can hold only one connection, and the Apple TV needs all three open at once. Leave them alone. Removing the TV in the app removes them too.

## Writing rules

Use **app and playback state together**, for example:

- **Movie night:** `currentApp` is Plex **and** `playbackState` is playing → dim the lights.
- **Movie over:** `playbackState` changes to paused or stopped → lights back up.

Good to know:

- **Leaving an app shows it as paused, not "none".** The Apple TV doesn't announce when you're back on the home screen. It still treats the last app as "now playing" (press Play on the remote and it resumes). So after leaving Plex for the home screen, the device shows Plex, `paused`. For rules, check `playbackState` is `playing`, not just `currentApp`.
- **Sleeping the TV clears it:** `currentApp` goes to `none`, `playbackState` to `stopped`, and title, artist and duration clear.
- **After waking, nothing shows until an app plays:** `currentApp` stays `none` (`idle`) on the home screen, even if the Apple TV still has a paused app in the background.
- **`currentApp` is the last app that actually played.** When an app closes or the TV turns off, the Apple TV sometimes falls back to an older paused app. The device ignores that and keeps showing the app you were watching (as `stopped` if it closed completely). Another app only takes over once it starts playing.
- **YouTube ads** show up as short items with the video's title.
- **Prime Video previews** (the trailers that autoplay while browsing) show no title. Full episodes and movies do.

## Known issues

- **Pausing inside Plex may still show `playing`.** Plex doesn't always tell the Apple TV it paused. Leaving Plex or sleeping the TV shows `paused` correctly.
- **The banner at the top of the app page** (counts like "1 connected") only updates when the page reloads. The Apple TV cards update live.
- **After a hub restart** each Apple TV reconnects on its own. If one didn't, the app reconnects it about a minute later.
- **Not tested yet:** what happens when an Apple TV gets a new IP address. It should recover on its own. Please tell me if it doesn't.

## What I'd like to hear back

- Your hub model (C-5, C-7, C-8, C-8 Pro), Apple TV model and tvOS version
- Which apps worked, and which reported something odd
- Whether it reconnects on its own after the TV sleeps overnight, and after a hub reboot
- Anything that looks off. Set **Log level** to **Full** in the app, reproduce it, and send the logs (Full turns itself off after 30 minutes)

## Updating

- **HPM:** **Update**, like any other package.
- **By hand:** open each file in Apps Code / Drivers Code, click **Import**, paste the same link, and **Save**.
- **Installed by hand and want HPM to take over?** Use **HPM → Match Up**. It links what you already have to the package.

## Removing

Open the app and tap **Remove** on the TV, or remove the whole app. To clear it on the Apple TV too, remove "Hubitat" from **Settings → Remotes and Devices** (or AirPlay and HomeKit, depending on tvOS).
