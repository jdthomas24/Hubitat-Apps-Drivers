**Release: Sofabaton Integration v1.1.8**
The hub device's switch now works like Harmony's: on while an activity runs, and it can start or stop activities.

**Problem:**
- Each hub device had a switch, but nothing ever set it. Rules couldn't use one device to check whether anything was running, and turning it on or off didn't control anything.

**The Fix:**
- The hub device's switch is **on** while any activity on that hub is running, and **off** when none are, including after the remote's Power Off key.
- Turning the hub device **off** stops the running activity.
- Turning the hub device **on** starts the activity picked under **Hub switch** in Edit Hub. Left on the default, it starts the last activity used.
- The hub card on the main page shows the hub switch state and what On starts.
- Starting and stopping need X2, or an X1S activity with a webhook. Follow-only X1S activities still update the switch state.
- The Bridge driver has no other changes. It's bumped to 1.1.8 to keep every file on the same version.

**Action needed:**
Update through HPM. No Done needed. To pick what On starts, open Edit Hub on the main page.

Thanks [@mjarends](https://community.hubitat.com/u/mjarends) for the suggestion.

---

**Release: Sofabaton Integration v1.1.7**
Tips & Troubleshooting opens at the top of the page.

**Problem:**
- Opening Tips & Troubleshooting from the main page could land at the bottom of the page, so you had to scroll back up to the topics.

**The Fix:**
- The Tips page scrolls to the top when it opens. On a phone, picking a topic takes you straight to it.
- No other changes. The Bridge, Remote and Activity drivers are bumped to 1.1.7 to keep every file on the same version.

**Action needed:**
Update through HPM. No Done needed.
