**Release: Roborock Integration v0.2.5 (beta)**
Clean area shows in square meters for metric users, with the right label.

**Problem:**
- Clean area was always labeled cleanArea (sq ft), even when set to square meters, so metric users saw meters under a square-feet label.
- Square feet was the default for everyone, and the setting to change it was easy to miss.

**The Fix:**
- Metric users get cleanArea (sq m). Square-foot users keep cleanArea (sq ft), so nothing changes for them. Only one of the two shows on the device page.
- The Area unit preference on each vacuum now defaults to Hub setting: square meters when the hub uses Celsius, square feet when it uses Fahrenheit. Pick square feet or meters there to override it.

**Action needed:**
Update through HPM. Metric users with a dashboard tile or rule on cleanArea (sq ft) switch it to cleanArea (sq m).
