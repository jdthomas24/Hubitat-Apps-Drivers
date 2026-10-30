# Pentair IntelliCenter 1.8.0-beta1 (heat control)

Test build for heat source control. Not in HPM. Only the Bridge and Body drivers change; the app stays on 1.7.2.

## What changed
- Heat source is now selected the way the Home Assistant integration does it (HEATER + MODE on the body). 1.7.x wrote HTSRC, which newer IntelliCenter 2 firmware rejects (the 404s seen on 3.006 and later).
- Heat source choices come from your panel. The Body device shows them in **heatSources**, e.g. `Off, Solar Only, Solar Preferred, Gas Heater`. Set Heat Source takes any of those names. The old names (Heater, Heat Pump, Heat Pump Preferred) still work for existing rules.
- UltraTemp ETi hybrid heaters get Gas Only, Heat Pump Only, Hybrid and Dual.
- **heaterMode** is now what the body is doing: Heating, Idle or Off.
- Heat and Start sends the source and set point together, then turns the pump on. Turning heat back on restores the last set point, since some firmware resets it while heat is off.
- If the controller rejects a command, the log says exactly what was sent.

## Install
1. Drivers Code → **Pentair IntelliCenter Bridge** → paste `BridgeDeviceDriver.groovy` → Save.
2. Drivers Code → **Pentair IntelliCenter Body** → paste `BodyDriver.groovy` → Save.
3. Open the Bridge device and click **Initialize** (it re-reads your heaters).
4. Set the app's Log level to **Full** while testing.

## Test
- Check **heatSources** on each Body device matches what your Pentair app offers.
- Try each source with Set Heat Source, then Off. Confirm in the Pentair app or at the panel.
- Try Heat and Start with a temperature.
- Send me: firmware version, the heatSources line, and the log lines from each try (anything with "rejected" especially).

## Go back to 1.7.2
HPM → Repair → Pentair IntelliCenter Controller.
