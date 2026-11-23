**Release: Reolink Integration v1.6.18**
Doorbells paired to some NVRs are now detected as doorbells instead of cameras.

**Problem:**
- Some NVR firmware (seen on an RLN8-410, firmware v3.5.1.368) doesn't report a paired doorbell's type. It sends no model, no doorbell ability and no visitor support for that channel.
- With nothing to go on, the app added the doorbell as a **Reolink Camera**, so it had no button (PushableButton) for doorbell presses.

**The Fix:**
- The app now also checks the doorbell light ability (**supportDoorbellLight**), which only doorbells report.
- As a last resort, a channel whose name contains "doorbell" is added as a doorbell. The name can only turn a channel into a doorbell, never into a camera, so Home Hubs that name a doorbell "Hub" are still handled correctly.
- A failed login during discovery no longer skips the remaining checks.
- Full logging now shows which check identified the channel.

**Action needed:**
- HPM users: update through HPM now.
- Built-in users: no action needed, fixes come with a future Hubitat platform update (possibly sooner on beta firmware).
- If your doorbell was already added as a Reolink Camera, delete that device, then turn its channel back on in the app so it's added again as a Reolink Doorbell. Rules and dashboards that used it will need the new device.

