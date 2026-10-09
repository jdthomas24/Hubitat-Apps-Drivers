# Sofabaton Integration for Hubitat

Connects Sofabaton X2 and X1S hubs to a Hubitat Elevation hub. Activities become Hubitat switches that stay in sync with the remote, and spare remote keys can control Hubitat lights and scenes. The X2 works fully locally, over Hubitat's built-in MQTT broker.

**[Try the interactive demo](https://jdthomas24.github.io/Hubitat-Apps-Drivers/SofaBaton%20Integration/sofabatondemo.html)**: click through finding an X2, two-way activity control and remote buttons. No hub needed.

**Install:** Hubitat Package Manager, search "Sofabaton Integration".
**Discussion and support:** [Hubitat Community thread](FORUM_THREAD_URL)

## Features

- **Activities as switches.** Each activity is a Hubitat switch. Start an activity on the remote and Hubitat knows right away; turn one on in Hubitat and the hub runs it. Only one activity runs at a time, and the remote's Power Off key turns them all off.
- **X2 is fully local and two-way.** Activity changes and commands both travel over Hubitat's built-in MQTT broker. No cloud, no extra hardware.
- **Automatic setup on X2.** Find My X2 locates the hub on the network, and its activities are created automatically from the hub's own list, named Hub-Activity (for example "Living Room-Watch Apple TV") so they group together in device pickers.
- **Remote buttons control Hubitat (X2).** Assign commands from a "Hubitat Control" remote in the Sofabaton app to spare keys (A, B, C or a color key). The **Button Actions** page maps each one to lights, groups or scene activators with Toggle, Turn on or Turn off. Button events also fire on the hub device for Rule Machine and Button Controller.
- **X1S support.** Activity changes are reported locally over HTTP. Hubitat can also start and stop X1S activities through Sofabaton's cloud webhook if you add one.
- **Multiple hubs**, any mix of X2 and X1S.
- **One place for everything.** Setup guides and troubleshooting are on the in-app Tips page, and all Sofabaton logging shows under the app at one Log level.

## Supported hubs

| Hub | Hubitat knows the activity | Hubitat starts/stops it | Activities | Remote buttons |
|---|---|---|---|---|
| **X2** | Yes, local MQTT | Yes, local MQTT | Added automatically | Yes, with Button Actions |
| **X1S** | Yes, local HTTP | Optional, through Sofabaton's cloud webhook | Added by you | Button events for rules |
| **X1** | Not supported. Its IP control can only send to a port Hubitat doesn't listen on. | | | |

## Requirements

- **X2:** Hubitat 2.5.2.126 or newer, with the built-in MQTT broker enabled (Integrations, Add Built-In App, MQTT Import Integration, Use built-in MQTT service).
- **X1S:** a static IP for the Sofabaton hub (a DHCP reservation on your router).

## Setup

The full step-by-step guide is on the app's **Tips & Troubleshooting** page, with every step tagged by where it happens (Hubitat or the Sofabaton app). In short:

**X2**
1. Enable Hubitat's built-in MQTT broker and note its port, username and password.
2. In the Sofabaton app: **Me**, **Connect to Home Assistant (MQTT broker)**, and enter your Hubitat hub's IP plus the broker details. Despite the name, this points the X2 at Hubitat.
3. In the app: **Add a Hub**, model X2, tap **Find My X2**, start any activity on the remote, then **Check Again** and add your hub. Activities appear within a few seconds.
4. Optional: set up remote buttons (Tips, X2 setup step 4), then assign them on **Button Actions**.

**X1S**
1. Optional: in the Sofabaton app, turn on **API** for an activity and copy its webhook URL, so Hubitat can start and stop it.
2. In the app: **Add a Hub** (model X1S, the hub's IP), then **Add an Activity** for each one and note its Body Value.
3. In the Sofabaton app: add a virtual IP control device that sends a **PUT** to `http://[your Hubitat IP]:39501/` with the activity's Body Value, and add it to the activity's start sequence.

Tap **Done** on the app's main page when you're finished.

## Good to know

- **X2 commands confirm in about 8 to 10 seconds**, after the hub finishes its start or stop sequence. Until then the activity shows `starting` or `stopping`.
- **Turning off any activity powers off the hub**, the same as the remote's Power Off key. To switch activities, just turn on the new one.
- **Volume, channel and other in-activity keys** go straight to your devices over IR and never reach Hubitat. Only activity changes, Power Off and keys assigned to your Hubitat Control remote do.
- **Activity lists refresh every 3 hours.** Tap **Refresh Activities** after adding or renaming one in the Sofabaton app. An activity deleted there shows "Not on hub" and is never removed automatically, so your rules don't break unexpectedly.

## How it works

- **Sofabaton Integration** (app): setup, Button Actions and the Tips page.
- **Sofabaton Integration Bridge**: holds one shared MQTT connection for all X2 hubs and groups each hub's devices together.
- **Sofabaton Remote**: one per hub. Tracks the running activity and fires button events.
- **Sofabaton Activity**: one per activity, as a switch.

## Credits

The X1S local receive path is a fork of Derek Osborn's (dJOS1475) Sofabaton X Series driver, built on push-command building blocks by Mike Maxwell (mike.maxwell). X2 support follows Sofabaton's local MQTT protocol, and was developed with an X2 provided by Sofabaton.

Sofabaton is a trademark of its owner. This is an independent community integration.
