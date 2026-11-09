**Release: Roborock Integration v0.2.5 (beta)**
Clean area shows in square meters for metric users, with the right label.

**Problem:**
- The clean area attribute was named cleanArea (sq ft) even when set to square meters, so metric users saw meters under a square-feet label.
- Square feet was the default for everyone, and the setting to change it was easy to miss.

**The Fix:**
- cleanArea (sq ft) is now cleanArea, with ft² or m² shown as its unit. The old attribute is removed from the device page.
- The Area unit preference on each vacuum now defaults to Hub setting: square meters when the hub uses Celsius, square feet when it uses Fahrenheit. Pick square feet or meters there to override it.

**Action needed:**
Update through HPM. Dashboards and rules that used cleanArea (sq ft) need switching to cleanArea.
