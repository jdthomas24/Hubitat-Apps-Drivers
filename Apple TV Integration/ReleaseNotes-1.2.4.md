**Release: Apple TV Integration v1.2.4**
Dashboard buttons for the Siri Remote, plus clearer help when an Apple TV can't be found or added.

**Problem:**
- Button rules that ran push('down') failed with a NumberFormatException. The action was running on a virtual button device, which only takes numbers, and the Apple TV device had no push command to use instead.
- Adding an Apple TV by IP could fail with "No Apple TV answered" even though the Apple TV answered ping, with no hint about why.

**The Fix:**
- Each Apple TV is now a **12-button device**. A dashboard button tile, or **push** in a rule, presses a Siri Remote button: 1 up, 2 down, 3 left, 4 right, 5 select, 6 back, 7 homeScreen, 8 tvButton, 9 controlCenter, 10 closeApp, 11 volumeUp, 12 volumeDown. push also takes the name, like push(down).
- Pressing a button doesn't fire a button pushed event on the Apple TV, so a Button Controller rule on it can't trigger itself into a double press.
- The "No Apple TV answered" message now says what's checked (AirPlay on port 7000) and what to look at.
- New Tips: Dashboard buttons and Button Controller, a checklist for Apple TVs that can't be added by IP (AirPlay Allow Access, Require Password, firewall and VLAN rules, a browser test), and what to check when an Apple TV pairs but won't connect (Remote App and Devices, Restrictions).

**Action needed:**
Update through HPM. No Done needed. If your button rules run push('down') on a virtual button, point the action at the Apple TV device instead.

Thanks to @weiser.chris and @USER for the reports.
