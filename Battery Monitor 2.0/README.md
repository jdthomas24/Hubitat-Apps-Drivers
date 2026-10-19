# Battery Monitor 2.0 for Hubitat

> **Status: v2.7.1.** Tracks battery levels, drain, health and replacements for the devices you pick, with Li-ion cliff-drop alerts and an optional web portal. Companion to [Device Health Monitor](../Device%20Health%20Monitor), which watches connectivity.

## What it does

- **Levels and health** for every monitored device. Health comes from how fast the battery drains, not just its percentage, so a fast-draining battery is caught while it still reads Good.
- **Trend** next to health, which reacts faster. When Trend is worse than Health, a warning appears; that's the most useful early signal.
- **Replacements detected automatically** from a confirmed upward jump, with a replacement history.
- **Li-ion cliff-drop alerts** for rechargeable cells that fail suddenly instead of draining slowly.
- **Notifications** on a schedule you choose, with snooze for when you're away.
- **Web portal** with every device on one page, for any phone, tablet or computer (optional).
- **Light and dark mode** that follow your hub's theme.

## Install

**Hubitat Package Manager (recommended):** Install → Search for **Battery Monitor 2.0**. Or Install → From a URL:
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Battery%20Monitor%202.0/packageManifest.json`

**By hand:** Apps Code → New App → Import, paste
`https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Battery%20Monitor%202.0/BatteryMonitor2.0.groovy`
then Save.

## Set up

1. Apps → **Add User App** → **Battery Monitor 2.0**.
2. Open **Monitored devices** and pick your battery devices.
3. Tap **Done**. That's required before the reports fill in.
4. Optional: set up **Notifications**, the **Scan interval**, and battery types under **Device management**.

Give new batteries about a week. Health shows **Pending** until it has 5 samples over 5 days (7 for locks, smoke and CO detectors).

## Reading the report

| Level | Range |
|---|---|
| Excellent | 100% |
| Good | 71–99% |
| Fair | 26–70% |
| Poor | 0–25%, replace soon |
| Dead | 0%, confirmed after 3 readings |

| Health | Drain |
|---|---|
| Excellent | under 0.3%/day |
| Good | 0.3–0.8%/day |
| Fair | 0.8–1.5%/day, worth watching |
| Poor | over 1.5%/day, notification fires |

- **Drain** is the average pace since the battery was replaced or last stepped down, so batteries that report in steps (1%, or 10% chunks) don't read as fast drain. If the level keeps setting new lows over several days, the faster recent pace takes over.
- **Est Days** is current level divided by drain, capped at 365.
- **Stale** means no activity past the stale threshold (default 24 hours, under Scan interval).
- Smoke and CO detectors often show **<0.01%/day**. That's normal.
- Locks use higher drain thresholds.

## Replacements

A new battery is logged on its own when the level jumps by at least the minimum (default 30%), holds on a second reading within 48 hours, and the device has some history (or the old battery was at 5% or below). Detections are at least 12 hours apart. Log anything it misses (integrated batteries, unreliable reporters) from **Device actions**.

## Li-ion cliff alerts

Li-ion cells hold a flat charge, then can drop to near dead within hours. Cliff detection compares two readings in a row and fires an urgent alert when the drop meets the threshold (default 40%).

- **On automatically** for LIR2016, LIR2032, LIR2430 and LIR2450.
- **Off until you turn it on** for 18650, RCR123A and RCR2 (the same sizes come as LiFePO4).
- Set per device on **Battery Types** or **Device actions**. Assign the battery type first; it sets the default.
- Cliff alerts bypass notification snooze by default.

## Web portal

1. **Apps Code** → open Battery Monitor 2.0 → **OAuth** (top right) → **Enable OAuth in App** → **Update**.
2. Back in the app, tap **Done**. Cloud and Local links appear under **Settings → Web portal**.

Add either link to a dashboard Link tile for one-tap access. The portal refreshes every 2 minutes and has its own dark theme.

## Troubleshooting

**Readings look wrong.** Battery Monitor reports what each device sends. Check the device's own page: some drivers report rarely, round heavily, or estimate from voltage, and rechargeable cells can misread in devices built for alkaline.

**Heavy drain right after install.** Reset that device's drain history in **Device actions**.

**Is it scanning?** Each scan logs a start and finish line at info level. Debug logging (bottom of the main page) adds detail and turns itself off after 30 minutes.

The app's **Tips & Troubleshooting** page covers all of this in more detail.

## Support

[Hubitat Community thread](https://community.hubitat.com/t/release-battery-monitor-2-0/162329) · [Buy Me a Coffee](https://paypal.me/jdthomas24?locale.x=en_US&country.x=US)
