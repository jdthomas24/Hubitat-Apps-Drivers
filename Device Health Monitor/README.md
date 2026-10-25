# Device Health Monitor for Hubitat

> **Status: v1.7.4.** Learns how often each device normally checks in and flags the ones that go quiet. Covers Zigbee, Z-Wave, Matter, Hub Mesh, LAN, Virtual and Hub Variable devices. Companion to [Battery Monitor 2.0](../Battery%20Monitor%202.0), which watches batteries.

## What it does

- **Learns each device's usual check-in** instead of using one fixed timeout, so a sensor that reports every few minutes and a lock that reports once a day are both judged fairly.
- **Health ratings** from Excellent to Offline, plus **Quiet** for devices that are idle but confirmed reachable.
- **Verifies before alerting.** A device that looks lost is checked first: a state event, a refresh or ping, the hub's own Z-Wave and Zigbee radio data, or a Hue Bridge or Konnected Panel round-trip.
- **Catches driver-reported problems** from drivers that publish a Health Status (or Echo Online Status).
- **Locations, snooze and notes** to keep reports and notifications useful.
- **Web portal** with every device on one page, for any phone, tablet or computer (optional).
- **Light and dark mode** that follow your hub's theme.

## Install

**Hubitat Package Manager (recommended):** Install → Search for **Device Health Monitor**. Or Install → From a URL:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Device%20Health%20Monitor/packageManifest.json`

**By hand:** Apps Code → New App → Import, paste
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Device%20Health%20Monitor/DeviceHealthMonitor.groovy`
then Save.

## Set up

1. Apps → **Add User App** → **Device Health Monitor**.
2. Open **Monitored devices** and pick what to watch. Include your **Hue Bridge** and **Konnected Panel** if you have them, so their devices can be verified.
3. Tap **Done**.
4. Optional: **Notifications**, **Scan interval**, **Locations** and **Snooze** under Settings.

Each device needs 3 check-ins before it leaves **Pending**, so give new devices a few days. Run **Force scan** after updating to refresh every score.

## Health ratings

| Rating | Meaning |
|---|---|
| Pending | Still learning (3 samples needed) |
| Excellent | Within 1.5x of its usual check-in |
| Good | Within 3x |
| Fair | Within 6x |
| Quiet | Would be Fair, but confirmed reachable, so idle rather than lost |
| Poor | Beyond 6x |
| Offline | No activity for the offline threshold (default 168 hours) |

- **Dead batteries:** a Zigbee, Z-Wave or Matter device reporting 5% or lower shows Poor at best until a new battery reports.
- **Driver reports offline:** the device shows Poor at best with a note. Z-Wave and Zigbee devices are checked with the hub radio right away and go Offline after 3 missed checks. Real activity or a radio answer always wins over the driver.
- **Minimum usual check-in** keeps burst devices from learning an unrealistic pattern: 8 hours for LAN and Hub Mesh, 2 hours for Matter, 24 hours for Virtual and Hub Variable.

## Verification

When a device drops to Poor or Offline, DHM tries to confirm it's still there before alerting:

- **State event** since its last check-in counts as proof.
- **Hub radio check** (Z-Wave, and Zigbee without a battery): DHM sends a refresh and checks the hub's radio data for the answer, about one device per second so normal commands aren't crowded out. 3 missed checks in a row means Offline. Battery devices sleep, so they're never probed.
- **Hue and Konnected:** the bridge or panel is refreshed, a real round-trip that confirms the device right away.

Check one device any time from **Device actions → Check with hub radio**, or run **Deep verification** on the Verification page for every unconfirmed device.

## Locations, snooze and corrections

- **Locations** group devices by room in the Summary, portal and notifications. Define them under Settings → Locations, then assign from Device actions, Bulk actions or the portal.
- **Snooze** keeps a device monitored but out of notifications for the time you set.
- **Correct detection** in Device actions fixes a wrong connection type (for devices detected as Hub Mesh, LAN, Virtual or Hub Variable) or the status shown in the State column. A ⚙ marks anything you set.

## Web portal

1. **Apps Code** → open Device Health Monitor → **OAuth** (top right) → **Enable OAuth in App** → **Update**.
2. Back in the app, tap **Done**. The links appear under **Settings → Web portal**.

The portal refreshes every 60 seconds, groups by protocol, health or location, and lets you set a device's location or note by tapping it. It's dark by default: pick Light or Follow device under **Portal theme**, or tap the ◐ button on the portal, which each browser remembers.

## Troubleshooting

**Devices shown as Unknown** have a connection type DHM can't read, so they're skipped and counted under Monitored devices.

**A healthy device keeps showing Poor.** Check it has had a few days to learn, add its bridge or panel if it has one, and use Device actions → Check with hub radio.

**Is it scanning?** Devices are scanned in batches (40, or 25 on installs over 200 devices) and each scan logs a start and finish line at info level. Debug logging (bottom of the main page) adds detail and turns itself off after 30 minutes.

The app's **Tips & Troubleshooting** page covers all of this in more detail.

## Support

[Hubitat Community thread](https://community.hubitat.com/t/release-device-health-monitor/163229) · [Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
