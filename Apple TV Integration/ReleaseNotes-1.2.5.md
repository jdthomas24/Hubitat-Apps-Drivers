**Release: Apple TV Integration v1.2.5**
transportStatus now shows paused in every app that reports it, and backing out of an app goes straight to stopped.

**Problem:**
- In the TV app and Plex, pausing never changed transportStatus. It stayed playing, because those apps keep both Play and Pause available while paused, and transportStatus only looked at which controls were available.
- Backing out of an app could briefly show paused before stopped.

**The Fix:**
- transportStatus now follows the playback state the app itself reports (playing, paused or stopped). This works in the TV app, Plex and YouTube.
- Leaving an app or the Apple TV going to sleep sets stopped right away, and it stays stopped until something actually starts playing.
- Apps that don't report a playback state, like Netflix, work the same as before.

**Action needed:**
Update through HPM. No Done needed.

Thanks to [@MichaelB](https://community.hubitat.com/u/michaelb) for the detailed logs across the TV app, YouTube and Plex that made this possible.
