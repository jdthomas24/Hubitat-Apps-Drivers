/**
 * Reolink Integration (Parent App)
 * Version: 1.5.1
 *
 * Architecture: a "source" is anything answering the Reolink HTTP/JSON API
 * (standalone camera, PoE NVR, or Home Hub), each with its own IP + creds. A
 * multi-channel source (NVR/Hub) reports channels 0..N-1; a standalone camera
 * is a degenerate source with one channel: 0. Every child device is tagged
 * (sourceId, channel) and talks only through parent.componentX() -- never
 * HTTP directly. Poll interval is per-child (wired 2-5s tight, battery loose)
 * to avoid hammering sleeping devices. Each source is fronted by a "Reolink
 * Device Bridge" device (one per source) that holds the persistent real-time
 * event subscription and is the real parent of Camera/Doorbell, so they nest
 * under it in the Devices list. Event-driven updates are the standard path;
 * a source falls back to polling automatically on connection loss and
 * silently resumes event mode on reconnect.
 *
 * Device-specific findings/limitations/setup gotchas live in the README and
 * in-app Tips page, not duplicated here. TODO markers mark spots needing
 * exact command/param names verified against firmware (field names can
 * drift by version). Full history prior to 1.3.6 is in GitHub commit history.
 *
 * BREAKING CHANGE (v1.3.8): every camera/doorbell became a child of a new
 * per-source "Reolink Device Bridge" instead of a child of this app directly
 * -- existing installs had to delete/recreate devices (and repoint
 * dashboards/rules) via re-discovery under each source.
 *
 * v1.5.0 -- NVR recording control: a master record on/off switch, plus
 * named per-channel schedule presets loaded on demand. Confirmed against a
 * real RLN16-410 NVR (6 Elite Wifi floodlights + 1 Gen2 battery wifi
 * doorbell, hub is a C-8 Pro) across many rounds of live testing before
 * release.
 *  - componentSetRecordingEnabled() flips the NVR's master record switch
 *    (SetRecV20 with NO channel field) -- CONFIRMED this call is genuinely
 *    untargeted at the API level: it always applies to every channel of the
 *    source at once, there is no per-channel version of it. The per-camera
 *    "Enable Record" screen in the Reolink app does nothing for NVR-side
 *    recording; it only controls onboard SD-card recording, which these
 *    cameras don't have.
 *  - componentLoadPreset() writes a named, per-channel 168-char hourly
 *    schedule (defined on the in-app Recording Presets page) to the NVR,
 *    one channel at a time, via a fresh read-modify-write (fetch the
 *    channel's current full schedule, flip only the relevant field(s),
 *    send the whole thing back) -- CONFIRMED a minimal/partial Rec object
 *    is silently accepted (rspCode 200) but produces NO actual change, so
 *    the full read-modify-write is required, not optional. A channel with
 *    no string saved for a given preset is skipped entirely, which is how
 *    a battery-class channel (e.g. a WiFi doorbell) stays excluded from a
 *    preset meant for wired channels.
 *  - A permanent per-device "Exclude from ALL recording presets" lock
 *    (excludeFromRecordingPresets on the Camera/Doorbell drivers) is
 *    enforced here inside componentLoadPreset() -- a locked channel is
 *    skipped on every preset load regardless of what that preset specifies
 *    for it, distinct from the ordinary "skipped (no data)" case (a
 *    channel simply left blank in one particular preset). presetsPage()
 *    greys out a locked channel's picker with a 🔒 instead of rendering an
 *    editable input for it. Direct manual commands aimed at a locked
 *    device are unaffected -- only preset-driven writes are blocked.
 *  - Schedule API generation: GetRecV20 is tried first regardless of any
 *    GetAbility capability flag, falling back to classic GetRec only if
 *    V20 itself returns no usable value -- CONFIRMED GetAbility's own
 *    scheduleVersion field doesn't reliably predict which generation a
 *    given NVR actually needs. scheduleEnable is a TOP-LEVEL field on Rec
 *    (sibling to "schedule"), NOT nested inside schedule.enable -- source:
 *    reading reolink_aio's (Home Assistant's Reolink integration library)
 *    actual set_recording() implementation, confirmed against real raw
 *    schedule dumps. Every key already present in the device's OWN
 *    returned schedule.table gets set, rather than assuming a specific key
 *    name like "TIMING" -- real hardware showed this can't be assumed.
 *  - Ordering: the master enable call must fire AFTER every per-channel
 *    schedule write, not before -- CONFIRMED writing a per-channel
 *    schedule silently re-flips the master enable flag back on as a side
 *    effect, so sending master-enable=0 before per-channel writes gets
 *    undone by them. A ~300ms pause between per-channel writes
 *    (REC_CHANNEL_SETTLE_MS) is also required -- back-to-back writes
 *    closer together than that produced intermittent per-channel misses
 *    on real hardware (6 WiFi floodlight channels).
 *  - Preset button numbers (componentBridgeButtonPushed(),
 *    getOrAssignButtonNumber()/retireButtonNumber()) are assigned once per
 *    preset, permanently, from a per-source counter that never resets or
 *    reuses a retired number -- deliberately avoids a positional/numbered-
 *    button scheme's fragility: a deleted preset's old Rule Machine
 *    trigger just goes inert instead of ever silently firing whatever
 *    different preset happens to reuse its old number.
 *  - The Recording Presets page's default UI is a plain-language picker
 *    (Continuous / "Never record" / a daily time range / don't manage)
 *    that generates the 168-char string automatically; an opt-in
 *    "Advanced" toggle reveals the raw string field for a schedule the
 *    simple picker can't express. "Don't manage" leaves whatever schedule
 *    already exists untouched; "Never record" actively writes an all-zero
 *    schedule -- a real distinction the reporting user's own workaround
 *    surfaced during development.
 *  - checkRecordingSchedule() is a read-only diagnostic (Camera/Doorbell
 *    drivers and their bridge/standalone passthroughs) that reads and logs
 *    a channel's current schedule without writing anything -- useful for
 *    confirming a channel's schedule shape before defining a preset
 *    against it.
 *  - Two earlier designs were built, tested, and fully abandoned during
 *    development in favor of the above: a cache-and-restore recording
 *    toggle (replayed a schedule snapshot from the first time it was ever
 *    used, which went stale the moment a real custom schedule was set
 *    later), and a set of separate virtual child devices for the master
 *    switch/preset buttons (replaced by adding Switch/PushableButton
 *    capabilities directly to the bridge itself, since exactly one bridge
 *    already exists per source). Neither exists in the code anymore.
 *  - Two confirmed Hubitat/Rule Machine platform quirks worth remembering
 *    for future driver work: (1) a bridge command declared with
 *    description-only metadata (no real argument) renders fine on that
 *    device's own Commands tab, but Rule Machine's Custom Action treats it
 *    as a real argument slot and passes through whatever's typed -- a risk
 *    for any genuinely zero-argument command (on()/off()/
 *    loadSelectedPreset() are declared bare for this reason; see
 *    ReolinkDeviceBridge.groovy). (2) Rule Machine hands a NUMBER-type
 *    command argument to a method as BigDecimal, not Integer, and Groovy
 *    does not auto-coerce between them in that call context -- push(btn)
 *    takes BigDecimal accordingly.
 *
 * v1.4.2 -- HOTFIX: Camera/Doorbell driver preferences used bare
 * paragraph("text") calls, which is App-DSL-only and doesn't compile on a
 * driver -- blocked the 1.4.1 update entirely with "No signature of method:
 * Script1.paragraph()". Fixed in both driver files via input(type:
 * "paragraph"); no app-side code change.
 *
 * v1.4.1 -- one real-hardware testing session, four fixes:
 *  1. schedulerTick()'s battery-check gate required batteryMode == "battery"
 *     before ever checking -- a device with batteryMode ever left unset
 *     (exact real-world trigger not reproducible from code alone) silently
 *     failed this gate forever, due-time still advancing every tick with
 *     nothing logged and battery never updating short of a manual check.
 *     Now treats null as "unknown, go find out": backfills via a live
 *     GetBatteryInfo probe once, self-heals on the next tick after
 *     upgrading.
 *  2. Since the old gate kept advancing the stale due-time even while
 *     skipping the check, that stale schedule would otherwise delay fix #1
 *     up to a full batteryCheckIntervalHours after upgrading. A one-time
 *     runMigrations() (guarded by state.lastKnownAppVersion) clears it so
 *     the fix takes effect within a second of updating.
 *  3. A standalone source's bridge parents off "Reolink Standalone Devices"
 *     (not this app directly), so its parent?.logNormal/logFull() calls
 *     threw MissingMethodException on that driver -- meaning the socket
 *     connection never even started for ANY standalone source (found via a
 *     standalone Argus 4 Pro). Fixed by adding logNormal()/logFull()
 *     passthroughs to StandaloneDevices.groovy. Hub/NVR bridges (parented
 *     directly off the app) were never affected.
 *  4. Added chargingStatus attribute (charging/not_charging/unknown) to
 *     both drivers from GetBatteryInfo's Battery.chargeStatus -- both
 *     values (1/0) confirmed against real hardware plugged in vs.
 *     unplugged, corroborated by current's sign flip and adapterStatus.
 *  5. maybeCheckBatteryOnWake(): a real event push means a battery device
 *     is already awake for an unrelated reason, so opportunistically
 *     checking battery/charging then costs nothing extra vs. waiting for
 *     the next scheduled interval (up to 12h) or a manual check. OFF by
 *     default (checkBatteryOnEventWake), throttled
 *     (eventWakeBatteryThrottleSec, default 60s) so a burst of pushes
 *     triggers one check per wake.
 *  6. The scheduled battery check is now gated by an explicit
 *     batteryCheckEnabled toggle (OFF by default) instead of the old
 *     implicit "0 hours = disabled" convention.
 *  7. Tips page updated: Argus 4 Pro has NO local network API standalone
 *     (both HTTP CGI and the Baichuan event port refuse the connection,
 *     confirmed directly); a Doorbell 2K Gen 2 pairs/polls fine standalone
 *     but its sleep/battery behavior makes event delivery unreliable.
 *     Different failure modes, same conclusion: battery-class devices need
 *     a Home Hub or NVR.
 *
 * v1.3.9 -- real-world use behind a 23-channel NVR:
 *  1. The discovery-time battery probe (guessIsBattery()) was marking a
 *     whole source "unreachable" on the EXPECTED failure of a wired camera
 *     (~half of all cameras) -- doReolinkApiCall() now takes a `quiet` flag
 *     so a probe failure logs at Full tier only, no source-health impact.
 *  2. Check Battery could succeed but the attribute never updated --
 *     receiveBatteryInfo() was reading batteryPercent flat instead of
 *     nested under Battery.batteryPercent (the confirmed reolink_aio
 *     field). Now checks nested first.
 *  3. Bridge keepalive (25s) now logs a Full-tier heartbeat so a quiet-but-
 *     healthy event connection doesn't look identical to a silently stuck
 *     one, throttled to once per connection.
 *  4. Reverted Login's "action: 0" field (added 1.3.8, never confirmed for
 *     Login specifically) after a real rspCode:-7 login rejection --
 *     disproven as the cause (recurred without the field too) but reverted
 *     anyway since unconfirmed; every other command's action:0 is
 *     unaffected and separately confirmed.
 *  5. ensureSourceBridge() could throw a raw DuplicateDNIException and
 *     crash the whole app page if an orphaned device shared its bridge's
 *     DNI -- now caught, logs the DNI to search/delete, returns null
 *     gracefully.
 *  6. Added explicit uninstalled() walking removeSource() for every source
 *     instead of relying solely on Hubitat's automatic cascade-delete --
 *     likely root cause of the orphaned-bridge DNI collision in #5, since
 *     the 1.3.8 bridge restructuring made the device tree 2-3 levels deep.
 *
 * v1.3.8:
 *  1. Real-time event-driven updates -- persistent per-source connection,
 *     falls back to polling automatically on drop/failure, resumes event
 *     mode silently on reconnect. Per-source toggle, defaults on.
 *  2. PIR enable/disable for cameras (pirOn/pirOff, pirEnabled attribute),
 *     doorbells unaffected.
 *  3. Fixed a login bug where "new token acquired" logged even without a
 *     usable Token.name (masking real auth failures) -- success now only
 *     logs on a real token; failure logs the raw response.
 *  4. Fixed Login missing the "action" field every other command sends.
 *  5. Magic-header resync logging stays at debug tier (confirmed benign,
 *     self-recovering Hub-side noise via soak testing); still warns after
 *     20 failed resync attempts or a genuinely unrecognized message type.
 *  6. Bridge device previously logged directly via log.info/log.debug,
 *     bypassing the app's Log level entirely -- now routed through
 *     logNormal()/logFull() like the rest of the app.
 *  7. Standalone (non-Hub) cameras/doorbells now nest under a shared
 *     "Reolink Standalone Devices" entry instead of each bridge appearing
 *     separately -- matching how NVR/Hub channels already group. Each
 *     standalone camera still holds its own independent event connection
 *     (rawSocket is one-connection-per-driver-instance); only the nesting
 *     changed.
 *
 * v1.3.6 -- discoverPage() fixes: unchecking an existing single-channel
 * device to remove it never actually fired (only checking-on did) -- now
 * fires either direction. Multi-channel apply-toggle relabeled for clarity;
 * each row now says Existing/New Device. Danger-zone wording clarified.
 * Hub/NVR channel type detection now uses the channel's own name (API
 * returns no model field), fixing a mislabeled doorbell. Added a note about
 * removing devices from this page, not Hubitat's Devices page.
 */

import groovy.transform.Field


definition(
    name: "Reolink Integration",
    namespace: "jdthomas24",
    author: "Jason",
    description: "Discovers and manages Reolink cameras, doorbells, NVRs, and Home Hubs",
    category: "Convenience",
    menu: "Integrations", // groups this app under the "Integrations" section of Add User App
    iconUrl: "",
    iconX2Url: "",
    singleThreaded: true,
    oauth: true // required for createAccessToken()/local endpoint access used by the snapshot relay
)

@Field static final String APP_VERSION = "1.5.1"

@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

// Poll interval is a device-level setting ONLY -- these are just the one-time
// default applied to a newly created device, not user-configurable at the app
// level. To change an existing device's interval, use its own device page (or
// the Set Poll Interval / Set Snapshot Interval commands).
@Field static final Integer DEFAULT_WIRED_POLL_SEC = 3
@Field static final Integer DEFAULT_BATTERY_POLL_SEC = 30

// The real-time event-subscription protocol (Baichuan) always runs on port
// 9000, completely separate from each source's own configurable HTTPS API
// port (src.port, default 443, used for GetAiState/GetChannelstatus/etc.).
// Always use this constant for the event socket, never src.port.
@Field static final Integer BAICHUAN_PORT = 9000

// Pause between per-channel recording-schedule writes in
// componentLoadPreset()'s loop -- see the top-of-file v1.5.0 note for why.
// Not user-configurable; adjust here if 300ms proves too short/long in
// practice.
@Field static final int REC_CHANNEL_SETTLE_MS = 300

// A recording preset's per-channel schedule string is exactly 168
// characters: 24 hours x 7 days, one digit per hour (Sunday 12am first),
// 1 = record, 0 = don't. Used to validate presetsPage() input.
@Field static final int REC_SCHEDULE_LENGTH = 168

// Plain-language hour labels for the simple time-range picker (index 0 =
// 12:00 AM ... index 23 = 11:00 PM) -- REC_HOUR_LABELS[h] is what's shown
// in the dropdown, indexOf(label) converts a selection back to an hour
// number for buildRangeBitstring().
@Field static final List<String> REC_HOUR_LABELS = [
    "12:00 AM", "1:00 AM", "2:00 AM", "3:00 AM", "4:00 AM", "5:00 AM",
    "6:00 AM", "7:00 AM", "8:00 AM", "9:00 AM", "10:00 AM", "11:00 AM",
    "12:00 PM", "1:00 PM", "2:00 PM", "3:00 PM", "4:00 PM", "5:00 PM",
    "6:00 PM", "7:00 PM", "8:00 PM", "9:00 PM", "10:00 PM", "11:00 PM"
]

// Four choices in the simple per-channel picker. Used as both the enum
// option text AND the internal mode marker, so there's no separate mapping
// to keep in sync.
@Field static final String REC_MODE_OFF = "Don't manage (leave to Reolink app)"
@Field static final String REC_MODE_NEVER = "Never record"
@Field static final String REC_MODE_CONTINUOUS = "Continuous (24/7)"
@Field static final String REC_MODE_RANGE = "Time range each day"

/** All-1s, 168 characters -- the simple picker's "Continuous" choice. */
private String buildContinuousBitstring() {
    return "1" * REC_SCHEDULE_LENGTH
}

/** All-0s, 168 characters -- the simple picker's "Never record" choice. Distinct from REC_MODE_OFF: this actively writes a silent schedule, rather than leaving whatever schedule the channel already had untouched. */
private String buildNeverBitstring() {
    return "0" * REC_SCHEDULE_LENGTH
}

/**
 * Builds a 168-char schedule string for "record from startHour to endHour,
 * every day the same way." Handles an overnight window (e.g. 18 to 6)
 * automatically -- the active hours simply wrap past midnight. Equal
 * start/end is treated as an empty (all-zero) window rather than either
 * "all day" or a single instant, since there's no unambiguous way to read
 * "6 PM to 6 PM" otherwise; the picker's UI text calls this out.
 */
private String buildRangeBitstring(int startHour, int endHour) {
    if (startHour == endHour) return "0" * REC_SCHEDULE_LENGTH
    def dayBits = (0..23).collect { h ->
        boolean active = (startHour < endHour) ?
            (h >= startHour && h < endHour) :
            (h >= startHour || h < endHour)
        return active ? "1" : "0"
    }.join()
    return dayBits * 7
}

preferences {
    page(name: "mainPage")
    page(name: "addSourcePage")
    page(name: "discoverPage")
    page(name: "presetsPage")
    page(name: "tipsPage")
}

// Local (non-cloud) endpoint the dashboard image tile hits on every refresh.
// See componentTakeSnapshot() / handleSnapshotRequest() below.
mappings {
    path("/snap/:dni") {
        action: [GET: "handleSnapshotRequest"]
    }
}

def mainPage() {
    if (newLabel && newHost && newUser && newPass) {
        addSource()
        // FIXED (2026-08-17): newPort and newIsHub were never cleared here,
        // unlike the other four fields -- so a value typed for one source
        // (even a typo, e.g. "440" instead of "443") silently persisted
        // and got reused for every SUBSEQUENT "Add a source" too, since
        // Hubitat only shows an input's defaultValue when the setting has
        // never been set at all. Real-world impact: a single mistyped port
        // early in a rapid add/remove testing session caused every later
        // source to silently connect to the wrong port and fail outright,
        // with no indication anything carried over. Every field this page
        // collects now resets cleanly after each add.
        app.removeSetting("newLabel")
        app.removeSetting("newHost")
        app.removeSetting("newPort")
        app.removeSetting("newUser")
        app.removeSetting("newPass")
        app.removeSetting("newIsHub")
    }
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            paragraph pillHeader("Sources")
            paragraph "<b>A source is one camera, one NVR, or one Home Hub -- anything with its own IP/login.</b>"
            (state.sources ?: []).each { src ->
                def deviceCount = childrenForSource(src.id).size()
                def typeLine = src.isHub ?
                    "<span style='color:#1565C0;font-weight:700;'>Hub/NVR · ${deviceCount} device(s)</span>" :
                    "Standalone · ${deviceCount} device(s)"
                href name: "src_${src.id}", title: "${src.label} (${src.host})",
                    description: typeLine,
                    page: "discoverPage", params: [sourceId: src.id]
            }
            // Kept in the SAME section as the sources list above (rather
            // than its own separate section) -- Hubitat's vertical gap
            // between two sections is noticeably wider than the gap
            // between two elements inside one section, so this tightens
            // the visual space between the last source and "Add a source"
            // without needing any custom CSS Hubitat's own page framework
            // doesn't expose control over.
            href name: "addSource", title: "➕ Add a source...",
                description: "Standalone camera, NVR, or Home Hub", page: "addSourcePage"
        }
        section {
            paragraph pillHeader("Logging")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true
            paragraph logLevelPill("Errors Only") + " Default. Warnings and errors only."
            paragraph logLevelPill("Normal") + " Errors, plus meaningful one-time events and changes " +
                "(logins, asleep/awake, devices created, config changes)."
            paragraph logLevelPill("Full") + " Everything, including every routine poll step. " +
                "<b>Automatically reverts to Normal after 60 minutes.</b>"
        }
        section("<b>Help & Support</b>") {
            href name: "tips", title: "<b>Tips & Troubleshooting</b>", page: "tipsPage",
                description: "Known device quirks, setup gotchas, and confirmed capabilities"
            paragraph rawHtml: true, """
<div style='padding:4px 0;'>
  <a href='https://community.hubitat.com/t/release-reolink-integration-cameras-doorbells-nvrs-home-hubs/165352' target='_blank'
     style='display:block; background:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px 14px; text-decoration:none; color:#333; margin-bottom:6px;'>
    <span style='font-size:14px;'>\uD83D\uDCAC <b>Hubitat Community Thread</b></span><br>
    <span style='font-size:12px; color:#888;'>Questions, feedback, bug reports, and release notes</span>
  </a>
  <a href='https://www.paypal.com/paypalme/jdthomas24?locale.x=en_US&country.x=US' target='_blank'
     style='display:block; background:#f8f8f8; border:1px solid #ddd; border-radius:6px; padding:10px 14px; text-decoration:none; color:#333;'>
    <span style='font-size:14px;'>\u2615 <b>Buy Me a Coffee</b></span><br>
    <span style='font-size:12px; color:#888;'>Enjoying the app? Any amount is appreciated -- thank you!</span>
  </a>
</div>
"""
        }
        section {
            paragraph "<div style='text-align:center;color:#999;font-size:11px;margin-top:10px;'>" +
                "Reolink Integration v${APP_VERSION}</div>"
        }
    }
}

private String pillHeader(String text) {
    "<div style='display:inline-block;background:#E3F2FD;color:#1565C0;font-weight:700;" +
    "font-size:12px;letter-spacing:0.5px;padding:4px 16px;border-radius:14px;" +
    "margin-bottom:6px;'>${text.toUpperCase()}</div>"
}

/**
 * Small colored pill for a log level name, distinct from pillHeader's section-title style so
 * the two don't get visually confused. Color signals severity/verbosity at a glance: red for
 * the errors-only default, grey for the middle tier, dark blue for the noisiest/temporary one.
 */
private String logLevelPill(String level) {
    def colors = [
        "Errors Only": [bg: "#FFEBEE", fg: "#C62828"],
        "Normal":      [bg: "#ECEFF1", fg: "#455A64"],
        "Full":        [bg: "#E8EAF6", fg: "#283593"]
    ]
    def c = colors[level] ?: [bg: "#ECEFF1", fg: "#455A64"]
    "<span style='display:inline-block;background:${c.bg};color:${c.fg};font-weight:700;" +
    "font-size:11px;letter-spacing:0.3px;padding:2px 10px;border-radius:10px;'>${level}</span>"
}

def tipsPage() {
    dynamicPage(name: "tipsPage", title: "Tips & Notes") {
        // All topics merged into ONE section instead of one section per
        // topic -- Hubitat's own gap between separate sections is wider
        // than the gap between elements inside one, so this tightens the
        // page overall. A thin divider paragraph between each topic keeps
        // them visually distinct despite the tighter spacing, rather than
        // relying on whitespace alone to separate them.
        section {
            paragraph pillHeader("What a source is")
            paragraph "A source is one camera, one NVR, or one Home Hub -- anything with its own IP/login. " +
                "A standalone camera always has one channel: 0. An NVR/Home Hub has one channel per paired " +
                "camera -- run discovery to see what it finds."
            paragraph tipsDivider()

            paragraph pillHeader("Before adding any camera")
            paragraph "Check the camera's own Network > Advanced (or Server) settings and make sure HTTP, " +
                "HTTPS, and ONVIF are enabled. These are often off by default on every model tested so far -- " +
                "not just Reolink's E1 line -- and this is the single most common reason a source fails to " +
                "connect, before assuming a device needs a Hub/NVR or isn't supported."
            paragraph tipsDivider()

            paragraph pillHeader("Why a device's ID number looks out of order")
            paragraph "Each device's internal ID (part of its DNI, e.g. 'reolink-4-0') only ever goes up, " +
                "never reused. Gaps in the numbering just mean a source was removed and re-added at some " +
                "point -- normal, and nothing to fix."
            paragraph tipsDivider()

            paragraph pillHeader("Deleting a device the wrong way (Hubitat's Devices page instead of this app)")
            paragraph "Always remove a Camera/Doorbell from its source's Discover page (toggle it off + " +
                "Apply), not Hubitat's own Devices page. Hubitat gives apps no way to be notified when a " +
                "device is deleted directly from there, so deleting one outside this app leaves it thinking " +
                "the device still exists."
            paragraph "<b>What's handled automatically:</b> the next time you open that source's Discover " +
                "page, it notices the device is actually gone, corrects the stuck-on checkbox back to " +
                "off, and cleans up its poll/snapshot/battery-check scheduling entries for it. Not instant " +
                "-- only self-heals on that next page view -- but it stops the stale state from sitting " +
                "there indefinitely."
            paragraph "⚠️ <b>What's NOT handled:</b> deleting a source's \"Reolink Device Bridge\" device " +
                "itself this way, instead of using \"Remove this ENTIRE source\" below. The bridge holds " +
                "the live event connection and is the real parent of every Camera/Doorbell under it -- " +
                "Hubitat will likely cascade-delete those children along with it, but this app's own record " +
                "of that source would still think it exists, with no bridge left to find. This case isn't " +
                "specifically handled -- always remove a whole source via \"Remove this ENTIRE source,\" never " +
                "by deleting its bridge device directly."
            paragraph tipsDivider()

            paragraph pillHeader("Devices that won't work standalone")
            paragraph "⚠️ <b>Battery-class cameras/doorbells</b> (Argus line, Doorbell Battery, Gen 2 " +
                "doorbells) -- treat these as requiring a Home Hub or NVR. Add the Hub/NVR as the source " +
                "instead, and the device shows up as one of its channels. This does NOT depend on how the " +
                "device is powered -- even one running continuously on a DC adapter is affected, since it's " +
                "a firmware/network-stack limitation, not a charging-mode setting. Confirmed working well " +
                "behind a Hub/NVR across a real multi-device battery fleet."
            paragraph "⚠️ <b>E1, E1 Pro, and Lumus</b> -- Reolink's own docs on local HTTP/HTTPS support are " +
                "inconsistent for this line. Don't rely on the model name -- check the camera's own Network > " +
                "Advanced (or Server) settings for HTTP/HTTPS/ONVIF toggles directly. Everything else -- PoE " +
                "cameras, WiFi cameras outside the E1 line -- works standalone."
            paragraph tipsDivider()

            paragraph pillHeader("Poll interval")
            paragraph "Wired devices can be polled tight (a few seconds). Battery devices should stay loose " +
                "-- they only wake for their own events or an occasional check-in, and polling harder doesn't " +
                "get fresher data, it just drains the battery. This still holds behind a Hub, since you're " +
                "asking the Hub for its last-known state, not the device directly."
            paragraph "When a source's event connection is active/healthy, its children update in real time " +
                "and polling is skipped entirely -- polling only resumes automatically if that connection drops."
            paragraph tipsDivider()

            paragraph pillHeader("\"Use event-driven updates\" toggle (on each source's Discover page)")
            paragraph "On by default, and for almost every source there's nothing to do here -- a source " +
                "that supports it gets real-time updates, and if the connection ever drops, it retries " +
                "automatically (backing off over 10 attempts) before settling into plain polling on its own. " +
                "No toggle needed for that case; it self-recovers."
            paragraph "This toggle matters for ONE specific case: a source that structurally can't do event " +
                "mode at all -- port 9000 blocked by a firewall, or older firmware that doesn't speak the " +
                "event protocol. That source will still go through all 10 reconnect attempts (and their " +
                "logging) every time the hub restarts or the app re-initializes, before eventually giving up " +
                "and polling anyway. If you already know a source falls into this category, turning this off " +
                "skips that runway entirely and goes straight to polling -- a convenience, not a different " +
                "outcome, since it lands in the same place either way."
            paragraph tipsDivider()

            paragraph pillHeader("Sleep status")
            paragraph "<b>Awake</b> -- the last poll got a response. <b>Asleep</b> -- it didn't. For a " +
                "battery device, asleep is normal, not an error. ⚠️ For a <b>wired/PoE device</b>, asleep is " +
                "NOT normal -- it points to a real connectivity or load issue. Motion/person/vehicle/etc. " +
                "keep their last-known value rather than resetting to inactive when this happens."
            paragraph tipsDivider()

            paragraph pillHeader("Known older-firmware bug: false 'asleep' from garbled responses")
            paragraph "⚠️ Some E1-series cameras on ~2021-era firmware have a known bug where the camera's " +
                "web server intermittently returns corrupted data instead of a real response -- not a " +
                "connectivity problem, just bad data from the camera itself, reported as <b>asleep</b> even " +
                "though it's online. Tell-tale sign: flips to asleep with no real pattern, and Full logging " +
                "shows parse errors on GetAiState/GetMdState rather than plain timeouts. Newer firmware on " +
                "the same camera line doesn't show this."
            paragraph "Fix, in order: (1) In the Reolink app, toggle this camera's HTTP/HTTPS off then back " +
                "on under Network settings and reboot it -- reinitializes the web server. (2) If that doesn't " +
                "help, check for a firmware update via the Reolink desktop app's Download Center, or contact " +
                "Reolink support with your model/firmware version. Avoid any 'reset configuration' option " +
                "unless you actually want to reset the camera."
            paragraph tipsDivider()

            paragraph pillHeader("PTZ")
            paragraph "Reolink has no 'Home' command -- the equivalent is a saved preset. Use " +
                "<b>savePresetHere</b> once (commonly preset ID 1) to save wherever the camera is currently " +
                "pointed, then <b>ptzGoToPreset</b> with that ID any time to return there."
            paragraph tipsDivider()

            paragraph pillHeader("PTZ calibration")
            paragraph "⚠️ Only applies to PTZ-capable cameras (e.g. Trackmix, E1 Zoom) -- non-PTZ cameras " +
                "just harmlessly error if you try it. Use <b>calibratePtz</b> if preset recall starts " +
                "drifting off target over time; check progress with <b>checkPtzCalibrationStatus</b> " +
                "(Required / Running / Done)."
            paragraph tipsDivider()

            paragraph pillHeader("PIR (motion trigger) on/off -- cameras only")
            paragraph "Use <b>pirOn</b>/<b>pirOff</b> to enable or disable a camera's PIR trigger without " +
                "removing the device. Does NOT stop an in-progress recording -- it removes the trigger that " +
                "would have woken a battery camera to record. Manual only, no auto-revert timer -- build " +
                "battery-threshold automation with Rule Machine using the existing battery attribute."
            paragraph tipsDivider()

            paragraph pillHeader("Recording presets (NVR/Hub master switch + per-channel schedules)")
            paragraph "Two independent controls: the bridge device's own <b>On/Off switch</b> is the NVR's " +
                "master switch (every channel at once -- no per-channel targeting, that's a hardware/API " +
                "limitation). Loading a <b>preset</b> writes a named, per-channel schedule from the Recording " +
                "Presets page. In practice: turn the master switch on once and leave it, then use presets to " +
                "control what each channel actually records. A channel left as \"Don't manage\" in a preset " +
                "is skipped -- its existing schedule stays untouched -- which is how to keep a battery-class " +
                "channel out of a preset meant for wired ones. Every preset write is a fresh read-modify-write " +
                "against the channel's current schedule, never a cached/restored snapshot."
            paragraph "⚠️ <b>A preset's schedule covers continuous and AI/motion-triggered recording " +
                "together, not separately.</b> Confirmed against real hardware: a channel has one time-table " +
                "for continuous (\"TIMING\") and separate tables per AI type -- but a preset here sets all of " +
                "them to the same hours. So \"Continuous 6pm-6am\" also limits AI-triggered clips to that same " +
                "window. To get continuous-only-at-certain-hours while still catching AI events any time, use " +
                "two presets (e.g. \"Daytime\"/\"Nighttime\") switched by a time-based Rule Machine schedule."
            paragraph tipsDivider()

            paragraph pillHeader("Snapshot tiles on dashboards")
            paragraph "Snapshot URLs point at a local relay endpoint on this app, not the camera directly -- " +
                "the camera is only contacted on its own snapshot interval (separate from poll interval), and " +
                "the relay just serves whatever's cached. A dashboard tile can refresh as often as you like, " +
                "but the picture only actually changes as often as that device's snapshot interval -- the " +
                "tile's own refresh setting doesn't matter. Poll interval should stay tight for responsive " +
                "motion automations; snapshot interval only affects image freshness and can stay looser " +
                "(default 30s). If a tile feels slow to update, lower the device's snapshot interval, not the " +
                "poll interval."
            paragraph tipsDivider()

            paragraph pillHeader("Log levels")
            paragraph logLevelPill("Errors Only") + " Default. Warnings and errors only."
            paragraph logLevelPill("Normal") + " Errors, plus meaningful one-time events: logins, " +
                "asleep/awake, devices created, config changes. Routine unchanged polls log nothing."
            paragraph logLevelPill("Full") + " Everything, including every routine poll step. " +
                "<b>Automatically reverts to Normal after 60 minutes.</b>"
            paragraph "⚠️ It's normal for Errors Only/Normal to show nothing for long stretches -- that means " +
                "nothing worth flagging happened, not that the app stopped working. Switch to Full temporarily " +
                "to confirm it's actually running."
        }
    }
}

/** Thin horizontal rule between Tips topics -- see tipsPage()'s note for why this replaced one-section-per-topic. */
private String tipsDivider() {
    return "<hr style='border:none;border-top:1px solid #ddd;margin:14px 0 10px 0;'>"
}

def addSourcePage(params) {
    if (params?.cancel) {
        app.removeSetting("newLabel")
        app.removeSetting("newHost")
        app.removeSetting("newPort")
        app.removeSetting("newUser")
        app.removeSetting("newPass")
        app.removeSetting("newIsHub")
        return mainPage()
    }
    dynamicPage(name: "addSourcePage", title: "Add a Reolink Source", nextPage: "mainPage") {
        section {
            href name: "cancelAddSource", title: "Cancel", description: "Back to Sources without saving",
                page: "addSourcePage", params: [cancel: true]
        }
        section {
            paragraph "<span style='display:inline-block;background:#FFF3E0;color:#E65100;font-weight:700;" +
                "padding:2px 10px;border-radius:10px;font-size:11px;margin-right:6px;'>NOTE</span>" +
                "<b>Before adding: check the camera's own Network > Advanced (or Server) settings and make " +
                "sure HTTP, HTTPS, and ONVIF are enabled.</b> These are often off by default on every model " +
                "tested so far, not just Reolink's E1 line -- this is the single most common reason a source " +
                "fails to connect."
            paragraph "Battery-class cameras/doorbells and Reolink's E1 line have additional connection quirks " +
                "worth knowing about -- see the Tips page (link on the Sources list) if this one still refuses " +
                "to connect after enabling the ports above."
            input "newLabel", "text", title: "Label (e.g. 'Front Door Hub', 'Garage Cam')"
            input "newHost", "text", title: "IP address"
            input "newPort", "number", title: "HTTPS port", defaultValue: 443
            input "newUser", "text", title: "Username"
            input "newPass", "password", title: "Password"
            input "newIsHub", "bool", title: "This is an NVR or Home Hub (multiple channels)", defaultValue: false
            paragraph "Fill in Label, IP address, Username, and Password, then tap Next to save. " +
                "Leaving any of those blank just returns you to the Sources list without creating anything."
        }
    }
}

def discoverPage(params) {
    def sourceId = params?.sourceId ?: state.currentDiscoverySourceId
    state.currentDiscoverySourceId = sourceId
    def src = getSource(sourceId)

    // ensureSourceBridge() is NOT called unconditionally on every page load
    // -- doing so would mean just VIEWING the discover page (before any
    // channel has ever been toggled on) creates a real device and attempts
    // a live socket connection, before the user has expressed any intent
    // to add anything. Every read on this page (existing/new pill status,
    // single-channel auto-apply check, connection status display) already
    // handles a missing bridge safely via getSourceBridge()'s null-safe
    // lookup, so nothing here actually needs the bridge to exist yet. It's
    // created lazily instead, at the moment real intent exists --
    // createSelectedChildren() already calls ensureSourceBridge() itself,
    // right before creating the first camera/doorbell, which is the
    // natural point for it to exist. This also meaningfully reduces
    // exposure to orphaned-device risk: fewer needless bridge creations
    // means less surface area for something to go wrong during a botched
    // removal/reinstall (see uninstalled() below for the actual guarantee
    // against that, which this doesn't replace).

    // Auto-run discovery the first time this source's Discover page is opened
    // (no cached results yet for this source), in addition to an explicit
    // "Re-run discovery" click. Removes the old requirement to manually run
    // discovery once before anything showed up after adding a source.
    def alreadyCachedForThisSource = (state.lastDiscoverySourceId == sourceId)
    if (src && (params?.run || !alreadyCachedForThisSource)) {
        state.lastDiscovery = discoverChannels(sourceId)
        state.lastDiscoverySourceId = sourceId
    }

    def cachedForThisSource = (state.lastDiscoverySourceId == sourceId)
    def lastDiscovery = cachedForThisSource ? (state.lastDiscovery ?: []) : []
    def channelCount = lastDiscovery.size()

    if (confirmCreate) {
        createSelectedChildren(sourceId)
        app.updateSetting("confirmCreate", [type: "bool", value: false])
    }

    // Fires on EITHER direction of the per-channel checkbox: checking an
    // absent device (create) or unchecking a present one (remove), by
    // comparing the checkbox state against whether the device currently
    // exists -- not just on checking-on.
    if (channelCount == 1 && src) {
        def ch = lastDiscovery[0]
        def dni = childDni(sourceId, ch.channel)
        def bridge0 = getSourceBridge(sourceId)
        def existing = bridge0?.getChildDevice(dni) != null
        def wantIt = settings["create_${sourceId}_${ch.channel}"]
        if ((wantIt ?: false) != existing) {
            createSelectedChildren(sourceId)
        }
    }

    if (confirmRemoveSource && src) {
        removeSource(sourceId)
        app.updateSetting("confirmRemoveSource", [type: "bool", value: false])
        return mainPage()
    }

    return dynamicPage(name: "discoverPage", title: "Discover Channels - ${src?.label ?: '(source removed)'}", nextPage: "mainPage") {
        if (!src) {
            section {
                paragraph "This source has been removed. Go back and use Add a source... if this was a mistake."
            }
        } else {
            section {
                paragraph pillHeader("Event Connection")
                def connStatus = state.sourceConnMode?.get(sourceId.toString()) ?: "not started"
                def statusColor = connStatus == "connected" ? "#22c55e" : connStatus == "reconnecting" ? "#f97316" : "#94a3b8"
                input "useEventSubscription_${sourceId}", "bool",
                    title: "Use event-driven updates for this source (falls back to polling automatically if it can't connect)",
                    defaultValue: true, submitOnChange: true
                paragraph "<span style='color:${statusColor};font-weight:700;font-size:12px;'>Status: ${connStatus}</span>"
            }
            section {
                // Explicit warning here after a real-world case where a
                // device deleted from Hubitat's Devices page (instead of this
                // page) left the app's own checkbox state stale. Shortened
                // to just point people to the recommended path and the
                // fuller explanation, since the stuck-toggled-on part is
                // now self-healing (see the "Deleting a device the wrong
                // way" Tips topic for what's actually handled vs. still
                // risky).
                paragraph "<span style='display:inline-block;background:#FFEBEE;color:#C62828;font-weight:700;" +
                    "padding:2px 10px;border-radius:10px;font-size:11px;margin-right:6px;'>HEADS UP</span>" +
                    "<b>Remove devices from THIS page, not Hubitat's Devices page</b> -- see the Tips page " +
                    "for what happens either way."
                href name: "runDiscovery", title: "Re-run discovery",
                    description: "Discovery already ran automatically when this page opened. Use this to " +
                        "refresh the channel list, e.g. after pairing a new camera to an NVR/Home Hub.",
                    page: "discoverPage", params: [sourceId: sourceId, run: true]

                if (state.lastDiscoveryError) {
                    paragraph "⚠️ ${state.lastDiscoveryError}"
                }

                // Collapsible -- on a source with many channels, this list
                // was the single biggest chunk of the page, pushing
                // everything below it well below the fold. Collapsed by
                // default only once a source already has at least one
                // device (nothing to hide on a brand-new source with zero
                // channels added yet).
                def anyExisting = channelCount > 0 && lastDiscovery.any { ch ->
                    def dni = childDni(sourceId, ch.channel)
                    getSourceBridge(sourceId)?.getChildDevice(dni) != null
                }
                input "hideChannelList_${sourceId}", "bool",
                    title: "Collapse the channel list below (just adds/removes devices -- collapse once you're done)",
                    defaultValue: anyExisting, submitOnChange: true
                def channelListHidden = settings["hideChannelList_${sourceId}"] ?: false

                if (!channelListHidden) {
                    // A full-width paragraph after each toggle (an earlier
                    // colored badge design) forces the next item to a new
                    // row, which is what prevented the two-per-row
                    // width:6 layout below from packing -- colored emoji
                    // baked directly into the toggle's own title text
                    // sidesteps that entirely (no separate element, so
                    // nothing to force a row break) while still giving a
                    // real color cue, since emoji render as actual color
                    // regardless of whether Hubitat treats a title as
                    // plain text or HTML.
                    paragraph "<span style='color:#5F5E5A;font-size:12px;'>ℹ️ \uD83D\uDFE2 marks a channel " +
                        "that already has a device (toggle off + apply to remove it); \uD83C\uDD95 marks one " +
                        "that doesn't have a device yet (toggle on + apply to create it).<br><b>Toggling a " +
                        "device by itself doesn't apply anything -- use \"Apply changes now\" below once " +
                        "you're done toggling.</b></span>"
                    lastDiscovery.each { ch ->
                        def dni = childDni(sourceId, ch.channel)
                        def bridgeForList = getSourceBridge(sourceId)
                        def exists = bridgeForList?.getChildDevice(dni) != null
                        // Self-healing check for the exact stale-toggle
                        // trap the HEADS UP warning above exists to
                        // prevent. Hubitat has no callback that notifies a
                        // parent app when a child device is deleted
                        // directly from the Devices page -- once a toggle
                        // here is set to true, Hubitat remembers that
                        // value permanently regardless of whether the
                        // device still exists (defaultValue only applies
                        // the FIRST time a setting is ever touched). So a
                        // device deleted externally would otherwise show a
                        // stuck 🟢 forever. This corrects it every time the
                        // page loads: if the setting says "on" but the
                        // device is actually gone, reset the setting back
                        // to off so the checkbox and status tag reflect
                        // reality instead of stale state. Not real-time --
                        // only self-heals on the next page view -- but that
                        // beats staying wrong indefinitely.
                        def settingKey = "create_${sourceId}_${ch.channel}"
                        if (!exists && settings[settingKey] == true) {
                            app.updateSetting(settingKey, [type: "bool", value: false])
                            // Matches what normal removal (uncheck + Apply)
                            // already does via createSelectedChildren() --
                            // without this, an externally-deleted device's
                            // entries in nextPollDue/nextSnapshotDue/
                            // nextBatteryCheckDue/lastEventBatteryCheck
                            // would linger in state forever, since nothing
                            // else would ever clear them for a device that
                            // was never removed through the app's own path.
                            forgetSchedulingState(dni)
                        }
                        def doorbellTag = ch.deviceType == "doorbell" ? " (Doorbell)" : ""
                        def statusTag = exists ? " \uD83D\uDFE2" : " \uD83C\uDD95"
                        input settingKey, "bool",
                            title: "Ch ${ch.channel}: ${ch.name}${doorbellTag}${statusTag}",
                            defaultValue: exists, submitOnChange: true, width: 6
                    }

                    if (channelCount > 1) {
                        // A thin divider + a distinct icon/label (rather
                        // than a plain "Ch N: Name"-shaped row) so this
                        // doesn't visually blend into the channel toggles
                        // directly above it -- easy to mistake for just
                        // another device without some separation, since
                        // it's the exact same input type.
                        paragraph "<hr style='border:none;border-top:1px solid #ddd;margin:10px 0;'>"
                        input "confirmCreate", "bool", title: "<b>✅ Apply changes now</b>",
                            defaultValue: false, submitOnChange: true
                    } else if (channelCount == 1) {
                        paragraph "Standalone source, one channel -- toggling it applies immediately (toggled on " +
                            "creates it, toggled off removes it), no separate apply step needed."
                    }
                } else {
                    paragraph "<span style='color:#5F5E5A;font-size:12px;'>Channel list collapsed -- ${channelCount} " +
                        "channel(s) found. Toggle the box above to expand it.</span>"
                }
            }
            section {
                paragraph pillHeader("Danger zone")
                input "confirmRemoveSource", "bool",
                    title: "Remove this ENTIRE source and ALL ${childrenForSource(sourceId as Integer).size()} of its device(s) -- unrelated to the toggles above",
                    defaultValue: false, submitOnChange: true
            }
            // Recording Control is placed at the bottom of the page, below
            // Danger Zone -- most people set an "Away"/"Present"-style
            // preset once and never touch this again, so it shouldn't
            // compete with the add/remove-devices workflow everyone
            // actually uses every visit. Danger Zone stays last-but-one
            // rather than last since it's specifically about the devices
            // listed just above it; Recording Control is a separate,
            // unrelated feature that belongs after it, not before.
            if (src.isHub) {
                section {
                    paragraph pillHeader("Recording Control")
                    paragraph "Set what each channel records and when -- a completely separate thing from " +
                        "adding/removing devices above. Most people only need to visit this once or twice to " +
                        "define \"Away\"/\"Present\"-style presets, then trigger them from Rule Machine going " +
                        "forward."
                    href name: "presetsFromDiscover", title: "Recording Presets",
                        description: "Define what each channel records and when, and load it from Rule Machine",
                        page: "presetsPage", params: [sourceId: sourceId]
                }
            }
        }
    }
}

/**
 * Define named, per-channel recording schedule presets for a source. Each
 * preset is a Map of channel-number-string -> 168-char schedule string,
 * stored in state.recPresets[sourceId][presetName]. A channel with no
 * entry is left alone when the preset is loaded -- see componentLoadPreset()
 * below. Presets are edited a whole preset at a time (every channel's field
 * is on the page together, one "Save changes" toggle per preset) rather
 * than saving field-by-field, since Hubitat's dynamicPage only submits/
 * redraws on a submitOnChange input.
 */
def presetsPage(params) {
    def sourceId = params?.sourceId ?: state.currentPresetsSourceId
    state.currentPresetsSourceId = sourceId
    def src = getSource(sourceId)
    def channels = childrenForSource(sourceId as Integer)

    if (newPresetName) {
        def presetsAll = state.recPresets ?: [:]
        def bySource = presetsAll[sourceId.toString()] ?: [:]
        if (!bySource.containsKey(newPresetName)) {
            bySource[newPresetName] = [:]
            presetsAll[sourceId.toString()] = bySource
            state.recPresets = presetsAll
            logNormal "Reolink source ${sourceId}: created preset '${newPresetName}'"
        }
        app.removeSetting("newPresetName")
    }

    def presets = (state.recPresets ?: [:])[sourceId.toString()] ?: [:]

    // Per-source, off by default -- reveals the original raw 168-char text
    // field instead of the simple picker below. Read before the
    // save-handling loop since saving branches on it.
    def advancedMode = settings["advancedScheduleEditing_${sourceId}"] ?: false

    // Handle any pending save/delete toggles for existing presets before
    // rendering, same pattern as discoverPage()'s confirmCreate handling.
    presets.keySet().toList().each { name ->
        if (settings["savePreset_${name}"]) {
            def updated = [:]
            def simpleUpdated = [:]
            channels.each { ch ->
                def channelNum = ch.getDataValue("channel")
                // A locked channel never gets an entry in this preset's
                // saved data at all, regardless of whatever was stored
                // here before it was locked -- without this, a channel
                // locked AFTER already having a real schedule saved in
                // this preset would keep that old value sitting in
                // state.recPresets (invisible, since presetsPage() no
                // longer renders a picker for it), even though
                // componentLoadPreset() correctly refuses to ever write it.
                // Enforcement was already safe without this; this just
                // keeps the stored data itself honest.
                if (ch.getSetting("excludeFromRecordingPresets") == true) return
                if (advancedMode) {
                    def val = settings["preset_${sourceId}_${name}_${channelNum}"]
                    if (val) {
                        if (val.length() == REC_SCHEDULE_LENGTH && val ==~ /[01]+/) {
                            updated[channelNum] = val
                        } else {
                            log.warn "Reolink source ${sourceId}: preset '${name}' ch ${channelNum} schedule " +
                                "invalid (need exactly ${REC_SCHEDULE_LENGTH} chars of 0/1, got ${val.length()}) -- not saved"
                        }
                    }
                } else {
                    def mode = settings["presetMode_${sourceId}_${name}_${channelNum}"] ?: REC_MODE_OFF
                    if (mode == REC_MODE_CONTINUOUS) {
                        updated[channelNum] = buildContinuousBitstring()
                        simpleUpdated[channelNum] = [mode: REC_MODE_CONTINUOUS]
                    } else if (mode == REC_MODE_NEVER) {
                        updated[channelNum] = buildNeverBitstring()
                        simpleUpdated[channelNum] = [mode: REC_MODE_NEVER]
                    } else if (mode == REC_MODE_RANGE) {
                        def startLabel = settings["presetStart_${sourceId}_${name}_${channelNum}"] ?: REC_HOUR_LABELS[18]
                        def endLabel = settings["presetEnd_${sourceId}_${name}_${channelNum}"] ?: REC_HOUR_LABELS[6]
                        def startHour = REC_HOUR_LABELS.indexOf(startLabel)
                        def endHour = REC_HOUR_LABELS.indexOf(endLabel)
                        updated[channelNum] = buildRangeBitstring(startHour, endHour)
                        simpleUpdated[channelNum] = [mode: REC_MODE_RANGE, start: startHour, end: endHour]
                    }
                    // REC_MODE_OFF -- leave this channel out of `updated`
                    // entirely, same as an old blank text field: skipped,
                    // existing schedule untouched. Distinct from
                    // REC_MODE_NEVER above, which actively WRITES an
                    // all-zero schedule instead of leaving whatever was
                    // there alone -- see that mode's own comment.
                }
            }
            def presetsAll = state.recPresets ?: [:]
            def bySource = presetsAll[sourceId.toString()] ?: [:]
            bySource[name] = updated
            presetsAll[sourceId.toString()] = bySource
            state.recPresets = presetsAll

            if (!advancedMode) {
                // Remember the friendly picker choice itself (not just the
                // bitstring it generated) so the page can show "6:00 PM to
                // 6:00 AM" again next time, instead of trying to
                // reverse-engineer a plain-English range back out of an
                // arbitrary 168-char string.
                def simpleAll = state.recPresetSimpleConfig ?: [:]
                def simpleBySource = simpleAll[sourceId.toString()] ?: [:]
                simpleBySource[name] = simpleUpdated
                simpleAll[sourceId.toString()] = simpleBySource
                state.recPresetSimpleConfig = simpleAll
            }

            app.updateSetting("savePreset_${name}", [type: "bool", value: false])
            logNormal "Reolink source ${sourceId}: preset '${name}' saved (${updated.size()}/${channels.size()} channels set)"
        }
        if (settings["deletePreset_${name}"]) {
            def presetsAll = state.recPresets ?: [:]
            def bySource = presetsAll[sourceId.toString()] ?: [:]
            bySource.remove(name)
            presetsAll[sourceId.toString()] = bySource
            state.recPresets = presetsAll
            def simpleAll = state.recPresetSimpleConfig ?: [:]
            def simpleBySource = simpleAll[sourceId.toString()] ?: [:]
            simpleBySource.remove(name)
            simpleAll[sourceId.toString()] = simpleBySource
            state.recPresetSimpleConfig = simpleAll
            app.removeSetting("deletePreset_${name}")
            // Retire (don't reassign) this preset's button number -- see
            // getOrAssignButtonNumber()/retireButtonNumber()'s comments
            // for why the number must never come back into use.
            retireButtonNumber(sourceId, name)
            logNormal "Reolink source ${sourceId}: preset '${name}' deleted"
        }
    }

    // Re-fetch after any save/delete above so the page renders current data.
    presets = (state.recPresets ?: [:])[sourceId.toString()] ?: [:]

    // Every ACTIVE preset gets a permanent button number if it doesn't have
    // one yet -- idempotent, so this also backfills numbers for presets
    // created before this feature existed, on the next time this page
    // happens to render. The bridge's numberOfButtons attribute is kept at
    // the highest number ever assigned (see getOrAssignButtonNumber()'s
    // comment for why it never shrinks).
    presets.keySet().each { name -> getOrAssignButtonNumber(sourceId, name) }
    def bridgeForButtons = getSourceBridge(sourceId)
    def highestButton = (state.recNextButtonNumber ?: [:])[sourceId.toString()] as Integer ?: 0
    bridgeForButtons?.receiveNumberOfButtons(highestButton)

    // Pushes a quick-reference summary (preset name + its permanent button
    // number) to the bridge's own state, so it shows up in that device's
    // State Variables panel without needing to open this app page at all.
    // Recomputed and re-sent every time this page renders, so it can't go
    // stale relative to what's actually saved.
    def presetsSummaryText = presets.keySet().collect { name ->
        def num = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()]?.get(name)
        num ? "${name} (Button ${num})" : name
    }.join(", ")
    bridgeForButtons?.receivePresetsSummary(presetsSummaryText ?: "(none defined yet)")

    dynamicPage(name: "presetsPage", title: "Recording Presets - ${src?.label ?: ''}") {
        section {
            paragraph pillHeader("Rule Machine shortcuts")
            paragraph "This source's bridge device (\"Reolink Device Bridge (${src?.label ?: ''})\") also " +
                "exposes standard Switch and Button capabilities now, so both of these are available in Rule " +
                "Machine's simple pickers -- no Custom Action needed:"
            paragraph "&nbsp;&nbsp;• <b>Turn the bridge switch on/off</b> \u2192 the NVR's master record " +
                "switch."
            paragraph "&nbsp;&nbsp;• <b>Push the bridge's button N</b> \u2192 loads whichever preset is shown " +
                "as \"Button N\" below."
            paragraph "Each preset's button number is assigned once, permanently, and is never reused even " +
                "if that preset is later deleted -- so a rule built around a button number stays pointed at " +
                "the SAME preset for as long as it exists, and just does nothing (rather than firing a " +
                "different preset) if that preset is ever removed."
        }
        section {
            paragraph pillHeader("What this does")
            paragraph "Each preset controls what a source's channels record, per channel: leave a channel " +
                "alone (Reolink's own app stays in control of it), actively silence it, set it to continuous, " +
                "or give it a daily time window (e.g. 6:00 PM to 6:00 AM every night). Loading a preset -- via " +
                "the bridge device's \"Load Selected Preset\" command, its Push button, or Rule Machine -- " +
                "applies whatever you've set here to the real NVR."
            paragraph "<b>\"Don't manage\" is the default for every channel</b> -- a channel you never touch " +
                "stays completely untouched by this preset, which is the recommended way to exclude a " +
                "battery-class channel (e.g. a WiFi doorbell) from a preset meant for wired channels, or to " +
                "just let Reolink's own app handle a channel entirely. <b>\"Never record\" is different</b> -- " +
                "it actively writes a silent (all-zero) schedule to that channel, rather than leaving whatever " +
                "was already there alone."
            paragraph "This is separate from the NVR's master recording switch (the bridge device's own " +
                "On/Off) -- that switch applies to every channel at once and has no per-channel targeting at " +
                "the API level. The usual pattern is: turn the master switch on once and leave it on, then " +
                "use presets to control what each channel actually records."
            input "advancedScheduleEditing_${sourceId}", "bool",
                title: "Advanced: edit raw per-hour schedule strings directly (power users only)",
                defaultValue: false, submitOnChange: true
            if (advancedMode) {
                paragraph "<span style='display:inline-block;background:#FFF3E0;color:#E65100;font-weight:700;" +
                    "padding:2px 10px;border-radius:10px;font-size:11px;margin-right:6px;'>WARNING</span>" +
                    "You're editing raw 168-character schedule strings (one digit per hour of the week, " +
                    "Sunday 12am first, 1=record/0=don't) instead of the simple picker. Almost nobody needs " +
                    "this -- it exists only for a schedule the simple picker can't express, like different " +
                    "hours on different days. A malformed string is rejected on save (exact length, only 0/1 " +
                    "characters), but a well-formed WRONG string will be written to your NVR exactly as typed."
            }
        }
        section {
            paragraph pillHeader("Add a preset")
            input "newPresetName", "text", title: "New preset name (e.g. 'Away', 'Present')", submitOnChange: true
        }
        presets.each { name, chMap ->
            def btnNum = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()]?.get(name)
            section("Preset: ${name}${btnNum ? " (Button ${btnNum})" : ""}") {
                if (advancedMode) {
                    channels.each { ch ->
                        def channelNum = ch.getDataValue("channel")
                        // A locked channel gets no input at all -- rendering
                        // a disabled-looking input that Hubitat's own
                        // dynamicPage framework can't actually prevent
                        // submission on would be worse than no input, since
                        // it'd look interactive but silently do nothing. A
                        // plain paragraph makes the lock visually
                        // unmistakable and genuinely un-editable.
                        if (ch.getSetting("excludeFromRecordingPresets") == true) {
                            paragraph "🔒 <b>${ch.label ?: ch.name} (ch ${channelNum})</b> -- excluded from all " +
                                "presets (locked on the device's own preferences page). This preset will never " +
                                "write a schedule to it."
                            return
                        }
                        def key = "preset_${sourceId}_${name}_${channelNum}"
                        input key, "text", title: "${ch.label ?: ch.name} (ch ${channelNum})",
                            defaultValue: chMap[channelNum] ?: ""
                    }
                } else {
                    def simpleForPreset = (state.recPresetSimpleConfig ?: [:])[sourceId.toString()]?.get(name) ?: [:]
                    channels.each { ch ->
                        def channelNum = ch.getDataValue("channel")
                        // Same lock check as the advanced branch above --
                        // see that comment for why this is a paragraph,
                        // not a disabled input.
                        if (ch.getSetting("excludeFromRecordingPresets") == true) {
                            paragraph "🔒 <b>${ch.label ?: ch.name} (ch ${channelNum})</b> -- excluded from all " +
                                "presets (locked on the device's own preferences page). This preset will never " +
                                "write a schedule to it."
                            return
                        }
                        def simpleCfg = simpleForPreset[channelNum]
                        def modeKey = "presetMode_${sourceId}_${name}_${channelNum}"
                        def currentMode = settings[modeKey] ?: simpleCfg?.mode ?: REC_MODE_OFF
                        input modeKey, "enum", title: "${ch.label ?: ch.name} (ch ${channelNum})",
                            options: [REC_MODE_OFF, REC_MODE_NEVER, REC_MODE_CONTINUOUS, REC_MODE_RANGE],
                            defaultValue: simpleCfg?.mode ?: REC_MODE_OFF, submitOnChange: true
                        if (currentMode == REC_MODE_RANGE) {
                            def defaultStart = simpleCfg?.start != null ? REC_HOUR_LABELS[simpleCfg.start as Integer] : REC_HOUR_LABELS[18]
                            def defaultEnd = simpleCfg?.end != null ? REC_HOUR_LABELS[simpleCfg.end as Integer] : REC_HOUR_LABELS[6]
                            input "presetStart_${sourceId}_${name}_${channelNum}", "enum",
                                title: "&nbsp;&nbsp;&nbsp;&nbsp;Start recording at",
                                options: REC_HOUR_LABELS, defaultValue: defaultStart, submitOnChange: true
                            input "presetEnd_${sourceId}_${name}_${channelNum}", "enum",
                                title: "&nbsp;&nbsp;&nbsp;&nbsp;Stop recording at",
                                options: REC_HOUR_LABELS, defaultValue: defaultEnd, submitOnChange: true
                            paragraph "<span style='color:#5F5E5A;font-size:12px;margin-left:16px;'>ℹ️ Same " +
                                "window every day. An end time earlier than the start time (e.g. 6:00 PM to " +
                                "6:00 AM) is treated as overnight, wrapping past midnight.</span>"
                        }
                    }
                }
                input "savePreset_${name}", "bool", title: "Save changes to '${name}'",
                    defaultValue: false, submitOnChange: true
                input "deletePreset_${name}", "bool", title: "Delete preset '${name}'",
                    defaultValue: false, submitOnChange: true
            }
        }
        section {
            href name: "backToDiscoverFromPresets", title: "« Back", page: "discoverPage", params: [sourceId: sourceId]
        }
    }
}

// ---------- Source management ----------

def addSource() {
    state.sources = state.sources ?: []
    state.nextSourceId = (state.nextSourceId ?: 0) + 1
    def id = state.nextSourceId
    state.sources << [
        id: id, label: newLabel, host: newHost, port: newPort ?: 443,
        username: newUser, password: newPass, isHub: newIsHub ?: false,
        token: null, tokenExpires: 0
    ]
    logNormal "Added source ${id}: ${newLabel} (${newHost})"
}

def getSource(id) {
    (state.sources ?: []).find { it.id == (id as Integer) }
}

def childrenForSource(sourceId) {
    def bridge = getSourceBridge(sourceId)
    // A DEVICE's getChildDevices() (unlike an app's) can return null instead
    // of an empty list when it has zero children -- guard against that.
    return bridge ? (bridge.getChildDevices() ?: []) : []
}

private String bridgeDni(sourceId) {
    "reolink-bridge-${sourceId}"
}

@Field static final String STANDALONE_GROUP_DNI = "reolink-standalone-group"

/**
 * Looks up (but does not create) the shared "Reolink Standalone Devices"
 * group device -- the nesting-only parent that every standalone (non-Hub)
 * source's bridge lives under, so multiple standalone cameras/doorbells
 * group together in the Devices list instead of each bridge appearing as
 * its own separate unnested entry. Hub/NVR sources never use this; their
 * bridge always parents directly off the app. Returns null if no
 * standalone source has been added yet.
 */
private getStandaloneGroupDevice() {
    getChildDevice(STANDALONE_GROUP_DNI)
}

/** Looks up OR creates the shared standalone-devices group device -- lazily created the first time a standalone source needs a bridge. */
private ensureStandaloneGroupDevice() {
    def group = getStandaloneGroupDevice()
    if (!group) {
        group = addChildDevice("jdthomas24", "Reolink Standalone Devices", STANDALONE_GROUP_DNI, [
            name: "Reolink Standalone Devices",
            label: "Reolink Standalone Devices",
            isComponent: true
        ])
        logNormal "Reolink Integration: standalone-devices group device created"
    }
    return group
}

/**
 * Looks up an existing source's bridge device. A Hub/NVR source's bridge is
 * app-owned directly; a standalone source's bridge instead lives under the
 * shared standalone-devices group device (see ensureStandaloneGroupDevice()
 * above) -- this checks both locations so every other call site can look up
 * a bridge without needing to know the source type. Returns null if not yet
 * created.
 */
private getSourceBridge(sourceId) {
    def dni = bridgeDni(sourceId)
    getChildDevice(dni) ?: getStandaloneGroupDevice()?.getChildDevice(dni)
}

/**
 * Camera/Doorbell DNIs are "reolink-{sourceId}-{channel}" -- given one, finds
 * the bridge that owns it without needing sourceId passed separately. Used
 * anywhere only a dni string is available (e.g. runIn(...) callback data).
 */
private getSourceBridgeForChannelDni(String dni) {
    def parts = dni?.tokenize("-")
    if (!parts || parts.size() < 2) return null
    def sourceId = parts[1] as Integer
    return getSourceBridge(sourceId)
}

def removeSource(id) {
    def src = getSource(id as Integer)
    def bridge = getSourceBridge(id as Integer)
    if (bridge) {
        try { bridge.stopEventSubscription() } catch (e) { /* best effort */ }
        // Deleting the bridge cascades to delete its own children
        // (Camera/Doorbell) -- standard Hubitat parent/child device
        // behavior, same as deleting any multi-endpoint parent removes its
        // child endpoints too.
        bridge.getChildDevices()?.each { forgetSchedulingState(it.deviceNetworkId) }
        // Delete via whichever device actually owns this bridge -- a
        // Hub/NVR bridge is the app's own direct child, a standalone
        // bridge is the group device's child.
        if (src?.isHub) {
            deleteChildDevice(bridge.deviceNetworkId)
        } else {
            getStandaloneGroupDevice()?.removeBridgeDevice(bridge.deviceNetworkId)
        }
    }
    state.sources.removeAll { it.id == (id as Integer) }
    state.sourceUnreachable?.remove(id.toString())
    state.sourceConnMode?.remove(id.toString())
    // Clean up any presets defined for this source too, so state doesn't
    // accumulate dead entries forever.
    state.recPresets?.remove(id.toString())
    // Same cleanup for button-number bookkeeping.
    state.recPresetButtonNumbers?.remove(id.toString())
    state.recNextButtonNumber?.remove(id.toString())
    // Same cleanup for the simple picker's remembered choices.
    state.recPresetSimpleConfig?.remove(id.toString())
    logNormal "Removed source ${id}"
}

/** Drops a device's entries from the central scheduler's due-time maps once it's deleted, so state doesn't accumulate dead DNIs forever. */
private forgetSchedulingState(String dni) {
    state.nextPollDue?.remove(dni)
    state.nextSnapshotDue?.remove(dni)
    state.nextBatteryCheckDue?.remove(dni)
    state.lastEventBatteryCheck?.remove(dni)
}

// ---------- Auth ----------

private String reolinkLogin(sourceId) {
    def src = getSource(sourceId)
    if (src.token && now() < src.tokenExpires) {
        logFull "Reolink source ${sourceId}: reusing cached token, expires in ${(src.tokenExpires - now()) / 1000}s"
        return src.token
    }

    logFull "Reolink source ${sourceId}: cached token missing/expired, logging in fresh"
    // The "action: 0" field some other commands send was never confirmed
    // against real hardware for Login specifically -- it was added purely
    // by inference/symmetry with every OTHER command, which all genuinely
    // do send action:0 and have since been independently confirmed working
    // across 5+ real devices. Login never got that same confirmation, and a
    // real-world rspCode:-7 "login failed" rejection on multiple sources
    // (confirmed-correct credentials, a hard reject, not a timeout) matched
    // exactly what you'd expect if some Reolink firmware is stricter about
    // an unexpected field on Login than assumed. Reverted to the body with
    // no action field as the prime regression suspect -- every OTHER
    // command keeps action:0 unchanged, since those are separately
    // confirmed and unrelated.
    def body = [[cmd: "Login", param: [User: [userName: src.username, password: src.password]]]]
    def resp = reolinkRawPost(src, body)
    if (resp == null) {
        // reolinkRawPost() already logged (or suppressed, if this source is
        // already known-unreachable) the underlying connection failure --
        // nothing more to log here, and nothing to parse out of a response
        // that never arrived.
        return null
    }
    def first = firstResultValue(resp, src)
    def token = first?.Token?.name
    def leaseSec = (first?.Token?.leaseTime ?: 3600) as Integer

    src.token = token
    src.tokenExpires = now() + (leaseSec * 1000L) - 30000L

    // This success log only fires when a real token actually came back --
    // logging "new token acquired" on any parsed response, even one
    // without a usable Token.name (bad credentials, or an unexpected shape
    // on some firmware), used to mask the real failure and make every
    // following "no token available" abort look inexplicable. Failure logs
    // the raw response so the actual field shape/error is visible.
    if (token) {
        logNormal "Reolink source ${sourceId}: new token acquired, leaseTime=${leaseSec}s"
        markSourceReachable(sourceId)
    } else {
        log.warn "Reolink source ${sourceId}: Login response parsed but no Token.name found (check " +
            "credentials) -- raw: ${resp?.toString()?.take(500)}"
    }
    return token
}

private firstResultValue(resp, src) {
    try {
        return resp[0]?.value
    } catch (e) {
        log.warn "Reolink unexpected response shape from ${src.host} (source ${src.id}): " +
            "${e.message} -- raw: ${resp?.toString()?.take(300)}"
        return null
    }
}

private reolinkRawPost(src, bodyList) {
    def cmd = bodyList?.getAt(0)?.cmd ?: ""
    def uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=${cmd}"
    def params = [uri: uri, ignoreSSLIssues: true, requestContentType: "application/json",
                  body: groovy.json.JsonOutput.toJson(bodyList), timeout: 10]
    def result = null
    try {
        httpPost(params) { resp -> result = parseReolinkResponse(resp) }
    } catch (e) {
        markSourceUnreachable(src.id, "POST failed (${src.host}): ${e.message}")
    }
    return result
}

private parseReolinkResponse(resp) {
    def raw = resp?.data?.toString()
    return raw ? new groovy.json.JsonSlurper().parseText(raw) : null
}

/**
 * A source going unreachable (host down, network issue, etc.) is ONE
 * condition, not a fresh event every poll cycle -- these two helpers gate
 * on a per-source state flag so only the TRANSITION into/out of unreachable
 * gets logged. Full-tier logging still shows every individual attempt via
 * the existing logFull() calls elsewhere, for anyone actively
 * troubleshooting.
 */
private void markSourceUnreachable(sourceId, String reason) {
    def map = state.sourceUnreachable ?: [:]
    def key = sourceId.toString()
    if (map[key] != true) {
        log.warn "Reolink source ${sourceId}: ${reason} -- further identical warnings for this source are " +
            "suppressed until it recovers (switch to Full logging to see every attempt)"
        map[key] = true
        state.sourceUnreachable = map
    } else {
        logFull "Reolink source ${sourceId}: still unreachable -- ${reason}"
    }
}

private void markSourceReachable(sourceId) {
    def map = state.sourceUnreachable ?: [:]
    def key = sourceId.toString()
    if (map[key] == true) {
        log.info "Reolink source ${sourceId}: connection restored"
        map[key] = false
        state.sourceUnreachable = map
    }
}

def reolinkApiCall(sourceId, String cmd, Map param = [:], Integer channel = null) {
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    if (!token) {
        // Login already logged (or suppressed) the actual connection failure
        // above -- this is just the downstream consequence, not a new fact,
        // so it only needs Full-tier visibility, not its own warning.
        logFull "Reolink source ${sourceId}: no token available, aborting ${cmd}"
        return null
    }

    def outcome = doReolinkApiCall(src, sourceId, cmd, token, param, channel)

    // rspCode -6 ("please login first") means the camera invalidated our session
    // before our local tokenExpires said it should -- most likely a competing
    // client (Reolink app/NVR viewing this camera) forced a fresh login on the
    // camera side. Don't wait for the next poll cycle to notice; force our own
    // fresh login and retry once now.
    if (outcome.value == null && outcome.rspCode == -6) {
        logNormal "Reolink source ${sourceId}: token rejected by camera (please login first), forcing re-login"
        src.token = null
        src.tokenExpires = 0
        def freshToken = reolinkLogin(sourceId)
        if (freshToken) {
            outcome = doReolinkApiCall(src, sourceId, cmd, freshToken, param, channel)
        }
    } else if (outcome.value == null && outcome.parseFailure) {
        // Known bug on some older firmware (e.g. 2021-era E1 -- see Tips page):
        // the camera's web server intermittently returns corrupted/garbled data
        // instead of a real response, NOT an auth problem, so a fresh LOGIN
        // wouldn't help -- confirmed via real-world testing that an immediate
        // retry of the SAME call often succeeds right after a failed one.
        logNormal "Reolink source ${sourceId}: ${cmd} (ch ${channel}) returned unparseable data (known older-firmware " +
            "bug, see Tips page), retrying once immediately"
        outcome = doReolinkApiCall(src, sourceId, cmd, token, param, channel)
    }
    return outcome.value
}

/**
 * quiet=true suppresses the usual failure escalation (markSourceUnreachable
 * warn, or the JSON-parse-failure warn) and logs at Full tier instead. Used
 * by guessIsBattery() below -- see that method's comment for why a failed
 * GetBatteryInfo probe must NOT be treated as evidence the whole SOURCE is
 * unreachable.
 */
/**
 * timeoutSec lets a caller shorten the HTTP timeout below the normal 10s --
 * used by guessIsBattery() below, since a slow rejection and a fast one
 * mean the same thing for that specific probe (see that method's comment
 * for the full reasoning).
 */
private Map doReolinkApiCall(src, sourceId, String cmd, String token, Map param, Integer channel, boolean quiet = false, int timeoutSec = 10) {
    def p = channel != null ? param + [channel: channel] : param
    def uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=${cmd}&token=${token}"
    def body = [[cmd: cmd, action: 0, param: p]]
    def result = null
    try {
        httpPost([uri: uri, ignoreSSLIssues: true, requestContentType: "application/json",
                  body: groovy.json.JsonOutput.toJson(body), timeout: timeoutSec]) { resp -> result = parseReolinkResponse(resp) }
        def value = firstResultValue(result, src)
        def rspCode = result?.getAt(0)?.error?.rspCode
        if (value == null) {
            logFull "Reolink source ${sourceId}: ${cmd} (ch ${channel}) HTTP ok but no usable value -- raw: ${result?.toString()?.take(300)}"
        } else {
            // Includes the actual returned value at Full tier, not just
            // "succeeded" -- useful for diagnosing a real question like
            // "does GetAiState's raw response change at all when the
            // doorbell button is pressed, and under what field name."
            // Full tier already means "everything, very verbose" by its
            // own definition, so including the raw value here doesn't
            // change what tier this belongs at, just what's visible in it.
            logFull "Reolink source ${sourceId}: ${cmd} (ch ${channel}) succeeded -- raw: ${value?.toString()?.take(300)}"
        }
        markSourceReachable(sourceId)
        return [value: value, rspCode: rspCode, parseFailure: false]
    } catch (groovy.json.JsonException e) {
        if (quiet) {
            logFull "Reolink source ${sourceId} ch ${channel}: ${cmd} returned unparseable data (quiet probe) -- ${e.message}"
        } else {
            log.warn "Reolink cmd ${cmd} failed for source ${sourceId} ch ${channel}: ${e.message}"
        }
        return [value: null, rspCode: null, parseFailure: true]
    } catch (e) {
        if (quiet) {
            logFull "Reolink source ${sourceId} ch ${channel}: ${cmd} failed (quiet probe, not treated as source-unreachable) -- ${e.message}"
        } else {
            markSourceUnreachable(sourceId, "cmd ${cmd} (ch ${channel}) failed: ${e.message}")
        }
        return [value: null, rspCode: null, parseFailure: false]
    }
}

// ---------- Discovery ----------

def discoverChannels(sourceId) {
    def src = getSource(sourceId)
    def channels = []
    state.lastDiscoveryError = null
    def bridgeForDiscovery = getSourceBridge(sourceId)

    // One GetAbility call per source covers ALL channels at once (the response
    // includes an abilityChn[] array indexed by channel) -- confirmed against
    // real hardware, so this is NOT called again per-channel below.
    def abilityChnList = fetchAbilityChnList(sourceId)

    if (!src.isHub) {
        def info = reolinkApiCall(sourceId, "GetDevInfo")
        if (info == null) {
            state.lastDiscoveryError = "No response from ${src.host}. If this is a battery-class " +
                "camera or doorbell (not PoE/plug-in WiFi), it may not run a local HTTP/ONVIF " +
                "server at all -- those typically only become reachable once paired to a Home Hub or NVR."
            return channels
        }
        // Skip the GetBatteryInfo round-trip entirely for a channel that
        // already has a child device -- isBattery is ONLY ever read at
        // device CREATION time, so recomputing it on every discovery run for
        // an existing channel is a wasted HTTP round-trip.
        def existing0 = bridgeForDiscovery?.getChildDevice(childDni(sourceId, 0)) != null
        channels << [channel: 0, name: info?.DevInfo?.name ?: src.label, deviceType: guessDeviceType(info),
            isBattery: existing0 ? null : guessIsBattery(sourceId, 0),
            supportedFeatures: computeSupportedFeatures(abilityChnList?.getAt(0))]
    } else {
        def status = reolinkApiCall(sourceId, "GetChannelstatus")
        if (status == null) {
            state.lastDiscoveryError = "No response from ${src.host}. Check IP/credentials."
            return channels
        }
        status?.status?.each { ch ->
            if (ch.online) {
                def existing = bridgeForDiscovery?.getChildDevice(childDni(sourceId, ch.channel)) != null
                channels << [channel: ch.channel, name: ch.name ?: "Channel ${ch.channel}", deviceType: guessChannelDeviceType(ch),
                    isBattery: existing ? null : guessIsBattery(sourceId, ch.channel),
                    supportedFeatures: computeSupportedFeatures(abilityChnList?.getAt(ch.channel as Integer))]
            }
        }
    }
    return channels
}

/**
 * Standalone-source detection (GetDevInfo shape, has a real model field).
 * Confirmed reliable against every standalone camera/doorbell tested so far.
 */
private String guessDeviceType(info) {
    def model = (info?.DevInfo?.model ?: info?.model ?: "").toLowerCase()
    return model.contains("doorbell") ? "doorbell" : "camera"
}

/**
 * Hub/NVR-channel detection. GetChannelstatus (the Hub/NVR API) never
 * returns a model field, only GetDevInfo (the standalone API) does -- so
 * this uses the channel's own name instead. Reolink's own Hub/NVR channel
 * naming already reflects the device type (a paired doorbell channel is
 * named "Doorbell" by default). Falls back to "camera" if the name gives no
 * signal either way.
 */
private String guessChannelDeviceType(ch) {
    def name = (ch?.name ?: "").toLowerCase()
    return name.contains("doorbell") ? "doorbell" : "camera"
}

/**
 * Battery vs wired isn't reported directly by GetDevInfo/GetChannelstatus, so
 * this uses GetBatteryInfo as a signal instead: a battery-class device
 * answers it with real data, a wired/PoE device returns nothing usable --
 * and on some wired firmware, "nothing usable" is an outright timeout
 * rather than a clean unsupported-command response.
 *
 * This probe is EXPECTED to fail for roughly half of all cameras (any
 * wired one) -- that's not a source-health signal, it's routine. Calls
 * doReolinkApiCall() directly with quiet=true instead of going through the
 * public reolinkApiCall() wrapper, so a failure here logs at Full tier
 * only and never touches source-reachable state (a real PoE camera timing
 * out on this specific probe was previously marking its whole SOURCE
 * unreachable, then immediately flipping back to "connection restored" on
 * the very next unrelated successful call -- noisy and misleading, since
 * every other command for that source was working fine the whole time).
 *
 * Also passes a short 3s timeout instead of the normal 10s -- this is
 * called once per NEW channel, sequentially, synchronously, within a
 * single page render, and on a large Hub/NVR's FIRST-EVER discovery
 * (every channel is "new" at once), a wired channel timing out here is
 * the expected, common case, not rare. At 10s each, a 24-channel Hub with
 * many wired cameras could block for minutes inside one page load,
 * plausibly exceeding Hubitat's own execution-time limit and crashing the
 * whole page ("Unexpected Error"). A slow rejection and a fast one mean
 * the same thing here (not battery), so shortening the timeout loses no
 * real information while cutting worst-case blocking time roughly 3x.
 */
private Boolean guessIsBattery(sourceId, channel) {
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    if (!token) return false
    def outcome = doReolinkApiCall(src, sourceId, "GetBatteryInfo", token, [:], channel, true, 3)
    return outcome.value != null
}

/**
 * Fetches GetAbility for a source and returns the abilityChn[] array (one
 * entry per channel, index-aligned with the channel number). Returns null on
 * failure or an unexpected response shape -- callers must handle that by
 * falling back to an empty/unknown feature set, not by failing discovery
 * entirely, since capability detection is informational and should never
 * block a device from being creatable.
 */
private List fetchAbilityChnList(sourceId) {
    def src = getSource(sourceId)
    def result = reolinkApiCall(sourceId, "GetAbility", [User: [userName: src?.username]])
    def abilityChn = result?.Ability?.abilityChn
    if (!(abilityChn instanceof List)) {
        logFull "Reolink source ${sourceId}: GetAbility did not return the expected " +
            "Ability.abilityChn[] shape -- capability detection unavailable for this source"
        return null
    }
    return abilityChn
}

/**
 * Safe lookup: treats a missing key the SAME as unsupported. Checks BOTH the
 * "permit" and "ver" sub-fields, not permit alone -- these move together on
 * every field tested except ptzType, where permit stays 0 even on confirmed
 * PTZ cameras while ver correctly shows nonzero. PTZ presence is keyed off
 * ptzType specifically BECAUSE of this behavior (see computeSupportedFeatures()
 * below) -- checking both here is what makes ptzType usable at all as a PTZ
 * signal, and closes off the same risk for any other field.
 */
private int abilityPermit(Map abilityChn, String key) {
    def entry = abilityChn?.getAt(key)
    def permit = (entry?.permit ?: 0) as int
    def ver = (entry?.ver ?: 0) as int
    return Math.max(permit, ver)
}

/**
 * Maps GetAbility data to a human-readable feature list for the
 * supportedFeatures device attribute. Confirmed against real hardware across
 * 8+ cameras / 6+ models / firmware 2021-2024, cross-checked against
 * Reolink's own officially-backed reolink_aio library:
 *   - PTZ: ptzType > 0 (checked via abilityPermit()'s existing max(permit,
 *     ver) logic) -- confirmed via a real RLC-1240A (no physical PTZ)
 *     showing ptzType permit:0/ver:0 while ptzCtrl (a false-positive signal
 *     that reports any PTZ-style command channel, including basic digital
 *     zoom on some fixed cameras) showed nonzero on both. The E1 Pro's
 *     documented ptzType values (permit:0, ver nonzero) are exactly the
 *     case abilityPermit()'s check-both logic is built for.
 *   - PTZ Calibration: supportPtzCheck > 0 OR supportPtzCalibration > 0.
 *   - Spotlight: supportFLswitch > 0 OR floodLight > 0 (camera-only).
 *   - Night Vision (IR): ledControl > 0.
 *   - Status LED: supportDoorbellLight > 0 (doorbell button/ring light, NOT
 *     a spotlight).
 *   - Siren: alarmAudio > 0.
 *   - Person / Vehicle: supportAiPeople / supportAiVehicle > 0.
 *   - Pet: supportAiDogCat OR supportAiAnimal > 0.
 *   - Package: supportAiPackage > 0 (doorbell-specific).
 *   - Basic/older models can be missing entire families of keys --
 *     abilityPermit()'s missing-key-as-0 handling covers this correctly.
 */
private List<String> computeSupportedFeatures(Map abilityChn) {
    if (abilityChn == null) return []
    def features = []
    if (abilityPermit(abilityChn, "ptzType") > 0) features << "PTZ"
    if (abilityPermit(abilityChn, "supportPtzCheck") > 0 || abilityPermit(abilityChn, "supportPtzCalibration") > 0) {
        features << "PTZ Calibration"
    }
    if (abilityPermit(abilityChn, "supportFLswitch") > 0 || abilityPermit(abilityChn, "floodLight") > 0) {
        features << "Spotlight"
    }
    if (abilityPermit(abilityChn, "ledControl") > 0) features << "Night Vision"
    if (abilityPermit(abilityChn, "supportDoorbellLight") > 0) features << "Status LED"
    if (abilityPermit(abilityChn, "alarmAudio") > 0) features << "Siren"
    if (abilityPermit(abilityChn, "supportAiPeople") > 0) features << "Person Detection"
    if (abilityPermit(abilityChn, "supportAiVehicle") > 0) features << "Vehicle Detection"
    if (abilityPermit(abilityChn, "supportAiDogCat") > 0 || abilityPermit(abilityChn, "supportAiAnimal") > 0) {
        features << "Pet Detection"
    }
    def packageKey = abilityChn.keySet().find { it.toLowerCase().contains("ackage") }
    if (packageKey && abilityPermit(abilityChn, packageKey) > 0) features << "Package Detection"
    return features
}

// ---------- Child creation ----------

private String childDni(sourceId, channel) {
    "reolink-${sourceId}-${channel}"
}

// ---------- Event connection ----------

/**
 * The bridge device ALWAYS needs to exist (it's the real parent of Camera/
 * Doorbell, not just an optional event-mode extra) -- this always creates
 * the bridge if missing, then separately starts/stops the event
 * subscription ON that bridge based on the per-source toggle. Idempotent --
 * safe to call on every page load or initialize().
 *
 * A Hub/NVR source's bridge is created as a direct child of the app, same
 * as always. A standalone source's bridge instead lives under the shared
 * "Reolink Standalone Devices" group device (lazily created on first use)
 * -- since each standalone camera still needs its own independent event
 * connection (Hubitat's rawSocket interface is one-connection-per-driver-
 * instance, so that part can't be shared), but nesting them all under one
 * shared parent avoids N separate unnested bridges cluttering the Devices
 * list the way they would otherwise.
 */
def ensureSourceBridge(sourceId) {
    def src = getSource(sourceId)
    if (!src) return null
    def dni = bridgeDni(sourceId)
    def bridge = getSourceBridge(sourceId)
    if (!bridge) {
        def label = "Reolink Device Bridge (${src.label})"
        try {
            if (src.isHub) {
                bridge = addChildDevice("jdthomas24", "Reolink Device Bridge", dni, [
                    name: label, label: label, isComponent: true
                ])
                bridge.updateDataValue("sourceId", "${sourceId}")
            } else {
                def group = ensureStandaloneGroupDevice()
                bridge = group.createBridgeDevice(dni, label, sourceId as Integer)
            }
            logNormal "Reolink source ${sourceId}: bridge device created"
        } catch (com.hubitat.device.exception.DuplicateDNIException e) {
            // Hubitat enforces device network IDs as GLOBALLY unique across
            // the ENTIRE hub, not just unique among one parent's children --
            // but getSourceBridge() above only checks the two places a
            // bridge is supposed to live (direct app child, or under the
            // standalone group device). If a device with this exact DNI
            // exists ANYWHERE else on the hub (most likely an orphan left
            // behind by a partial removal, a stale HPM-vs-manual driver
            // mismatch, or an interrupted reinstall/wipe), that lookup
            // finds nothing, concludes no bridge exists, tries to create
            // one, and Hubitat rejects it. Caught here rather than crashing
            // the whole page render with a bare "Unexpected Error": logs a
            // clear, actionable warning and returns null so the caller can
            // handle a missing bridge gracefully.
            log.warn "Reolink source ${sourceId}: a device with DNI '${dni}' already exists somewhere on " +
                "this hub but isn't reachable as this source's bridge -- likely an orphaned device from an " +
                "earlier partial removal or reinstall. Search your full Devices list for Device Network Id " +
                "'${dni}' and delete it, then re-run discovery for this source. (${e.message})"
            return null
        }
    }
    // Unconditional -- always keeps the bridge's connection config in sync
    // with state.sources, independent of whether the subscription is
    // actually wanted right now.
    bridge.configureConnection(src.host, BAICHUAN_PORT, src.username, src.password, sourceId as Integer)

    def wantEvent = settings["useEventSubscription_${sourceId}"] != false  // default true
    def currentStatus = state.sourceConnMode?.get(sourceId.toString())
    def currentlyRunning = currentStatus in ["connected", "connecting", "reconnecting"]
    if (wantEvent && !currentlyRunning) {
        bridge.startEventSubscription()
        logNormal "Reolink source ${sourceId}: event subscription starting"
    } else if (!wantEvent && currentlyRunning) {
        try { bridge.stopEventSubscription() } catch (e) { /* best effort */ }
        state.sourceConnMode?.remove(sourceId.toString())
        logNormal "Reolink source ${sourceId}: event subscription stopped (polling only)"
    }
    return bridge
}

/** Called by the bridge whenever its event-subscription status changes. */
def componentEventConnectionStatus(child, sourceId, String status) {
    def map = state.sourceConnMode ?: [:]
    def key = sourceId.toString()
    def prev = map[key]
    map[key] = status
    state.sourceConnMode = map
    if (prev != status) {
        logNormal "Reolink source ${sourceId}: event connection ${status}"
    }
    if (status != "connected") {
        // Falling back to polling -- mark this source's children due
        // immediately instead of waiting out whatever interval they were on,
        // so there's no extra gap on top of the drop itself.
        childrenForSource(sourceId as Integer).each { markPollDueNow(it.deviceNetworkId) }
    }
}

/** True while a source's event connection is confirmed healthy -- schedulerTick() skips active polling for its children while this holds. */
private boolean isSourceEventConnected(sourceId) {
    return state.sourceConnMode?.get(sourceId.toString()) == "connected"
}

/**
 * Called by the bridge for every genuine per-channel motion/AI/visitor
 * change. Routes into the SAME parseReolinkState() the polling path already
 * calls -- the camera/doorbell drivers have no idea this came from a push
 * instead of a poll. The target child is looked up ON THE BRIDGE (its real
 * parent), not the app.
 */
def componentEventChannelUpdate(child, sourceId, channelId, String status, String aiType) {
    def bridge = getSourceBridge(sourceId)
    def dni = childDni(sourceId, channelId)
    def target = bridge?.getChildDevice(dni)
    if (!target) return  // channel not added as a device, or not yet discovered -- nothing to update
    def shapes = translateToLegacyShape(status, aiType)
    target.parseReolinkState(shapes.aiState, shapes.mdState, "event")
    logFull "Reolink source ${sourceId} ch ${channelId}: event push -- status='${status}', AItype='${aiType}'"
    maybeCheckBatteryOnWake(target)
}

/**
 * A battery-mode device only ever answers GetBatteryInfo (or anything else)
 * when it's genuinely awake -- that's the whole reason
 * batteryCheckIntervalHours exists on a long, conservative interval, so the
 * periodic scheduler doesn't waste battery forcing a wake just to ask. But
 * a REAL event push (this method's caller) means the device is ALREADY
 * awake and already talking to us right now, for a completely unrelated
 * reason -- piggybacking a battery/charging check onto that costs
 * essentially nothing extra, unlike the scheduler's own artificial checks.
 * Without this, chargingStatus (see CameraDriver.groovy) could only ever
 * update on the next scheduled check (up to batteryCheckIntervalHours away,
 * default 12h) or a manual Check Battery run, even though the device may
 * have been awake and reachable dozens of times in between via real
 * motion/AI events.
 *
 * OFF by default (checkBatteryOnEventWake device preference) -- even though
 * the marginal cost of piggybacking is low, it's still a behavior change
 * from what every existing installation has been running, and opt-in
 * respects that rather than silently changing what happens on every event
 * push for everyone. Throttle window is also configurable per device
 * (eventWakeBatteryThrottleSec, default 60s) rather than hardcoded, so it
 * can be tuned looser or tighter than the default guess.
 *
 * Throttled (state.lastEventBatteryCheck, keyed by DNI) so a rapid burst of
 * pushes -- e.g. motion, then person, then vehicle, then motion-inactive,
 * all within a few seconds, as seen in real logs -- triggers one check for
 * that wake, not one per push. Wired/non-battery devices are skipped
 * entirely (GetBatteryInfo is meaningless for them). Also nudges
 * nextBatteryCheckDue forward by the device's own interval from now, same
 * as a real scheduled check would, so schedulerTick() doesn't immediately
 * re-check the same device again on its very next tick.
 */
private void maybeCheckBatteryOnWake(child) {
    if (!child.hasCapability("Battery")) return
    if (child.currentValue("batteryMode") != "battery") return
    if (child.getSetting("checkBatteryOnEventWake") != true) return
    def dni = child.deviceNetworkId
    def nowMs = now()
    def throttleSec = (child.getSetting("eventWakeBatteryThrottleSec") ?: 60) as Integer
    def lastCheck = state.lastEventBatteryCheck ?: [:]
    def last = (lastCheck[dni] ?: 0) as Long
    if (nowMs - last < (Math.max(throttleSec, 1) * 1000L)) return
    lastCheck[dni] = nowMs
    state.lastEventBatteryCheck = lastCheck
    componentCheckBattery(child)
    def hours = (child.getSetting("batteryCheckIntervalHours") ?: 12) as Integer
    def battDue = state.nextBatteryCheckDue ?: [:]
    battDue[dni] = nowMs + (Math.max(hours, 1) * 3600L * 1000L)
    state.nextBatteryCheckDue = battDue
    logFull "Reolink Integration: ${child.displayName} (${dni}) checked battery/charging status opportunistically on a real event wake"
}

/** Called by the bridge for sleep-status pushes (cmd_id=145). Logged only for now -- not yet wired to markAsleep()/awake. */
def componentEventSleepUpdate(child, sourceId, channelId, String sleepState) {
    logFull "Reolink source ${sourceId} ch ${channelId}: event sleep push -- '${sleepState}' (not yet acted on)"
}

/**
 * Reshapes a pushed status/AItype pair into the same Map shape
 * parseReolinkState() already expects from POLLING (GetAiState/GetMdState
 * JSON).
 */
private Map translateToLegacyShape(String status, String aiType) {
    def aiActive = aiType && aiType != "none"
    def motionActive = (status == "MD") || aiActive
    return [
        mdState: [state: motionActive ? 1 : 0],
        aiState: [
            people:  [alarm_state: (aiType == "people")  ? 1 : 0],
            vehicle: [alarm_state: (aiType == "vehicle") ? 1 : 0],
            dog_cat: [alarm_state: (aiType == "dog_cat") ? 1 : 0],
            package: [alarm_state: (aiType == "package") ? 1 : 0],
            visitor: [alarm_state: (status == "visitor") ? 1 : 0]
        ]
    ]
}

def createSelectedChildren(sourceId) {
    // The bridge -- not the app -- creates/removes Camera/Doorbell, via
    // createChannelDevice()/removeChannelDevice(), so they end up as ITS
    // children (nested in the Devices list).
    def bridge = ensureSourceBridge(sourceId)
    if (!bridge) {
        log.warn "Reolink source ${sourceId}: no bridge device available, cannot create/remove children"
        return
    }
    (state.lastDiscovery ?: []).each { ch ->
        def wantIt = settings["create_${sourceId}_${ch.channel}"]
        def dni = childDni(sourceId, ch.channel)
        def existing = bridge.getChildDevice(dni)
        if (wantIt && !existing) {
            def driverName = ch.deviceType == "doorbell" ? "Reolink Doorbell" : "Reolink Camera"
            def pollDefault = ch.isBattery ? DEFAULT_BATTERY_POLL_SEC : DEFAULT_WIRED_POLL_SEC
            def child = bridge.createChannelDevice(driverName, dni, ch.name, pollDefault as Integer, ch.supportedFeatures ?: [])
            // batteryMode is declared as a device attribute but only ever
            // populated here, once, at creation time -- this is the one
            // moment ch.isBattery holds a real, freshly-probed value (it's
            // null on a re-discovery of an already-existing channel, by
            // design -- see discoverChannels()). Set on both device types --
            // both get the periodic auto-check (see schedulerTick(), gated
            // on hasCapability("Battery"), which both drivers declare).
            // schedulerTick() below self-heals any device that still ends
            // up without batteryMode set, rather than relying solely on
            // this single creation-time call succeeding.
            if (child) {
                child.receiveBatteryMode(ch.isBattery ? "battery" : "wired")
            }
            logNormal "Created child ${dni} (${driverName}) via bridge, poll interval defaulted to ${pollDefault}s (${ch.isBattery ? 'battery' : 'wired'}), features: ${ch.supportedFeatures ? ch.supportedFeatures.join(', ') : 'none detected'}"
        } else if (!wantIt && existing) {
            bridge.removeChannelDevice(dni)
            forgetSchedulingState(dni)
            logNormal "Removed child ${dni} via bridge (unchecked in discovery list)"
        }
    }
    initializePolling()
}

// ---------- Polling ----------

def installed() { initialize() }
def updated() { initialize() }

/**
 * Explicit teardown on full app removal. Without this, removing the entire
 * app instance (via Hubitat's Apps list, NOT the in-app "Remove this
 * ENTIRE source" toggle) relies purely on Hubitat's own built-in
 * cascade-delete of app-owned children -- platform behavior this app
 * doesn't control or fully verify, especially given the device tree is
 * 2-3 levels deep (App -> Bridge -> Camera, or for standalone: App ->
 * Group Device -> Bridge -> Camera) rather than a flat one-level tree. A
 * genuine production DuplicateDNIException was once traced to an orphaned
 * bridge device surviving what should have been a full removal -- this
 * closes that gap either way: every source now goes through the SAME
 * explicit, already-defensive removeSource() teardown (stop subscription,
 * delete children, delete bridge) that the per-source Danger Zone toggle
 * already uses and has been reliable, rather than trusting an implicit
 * mechanism this app can't inspect or guarantee.
 */
def uninstalled() {
    // removeSource() mutates state.sources internally
    // (state.sources.removeAll {...}) -- iterating that SAME live list
    // here while it's being mutated mid-loop is exactly what
    // ConcurrentModificationException guards against. .collect() snapshots
    // the list once up front, so removeSource()'s mutation of the real
    // state.sources no longer affects the iteration in progress.
    (state.sources ?: []).collect().each { src ->
        try {
            removeSource(src.id)
        } catch (e) {
            log.warn "Reolink Integration: cleanup failed for source ${src.id} during uninstall -- ${e.message}"
        }
    }
}

/**
 * Ensures polling resumes automatically after a hub reboot. Hubitat does not
 * guarantee runIn schedules survive a restart on their own, and nothing else
 * in this app gets called on boot -- without this, a hub reboot could leave
 * every camera silently un-polled until someone happened to open the app and
 * hit Done/Update, with no error or indication anything was wrong.
 */
def systemStartHandler(evt) {
    logNormal "Reolink Integration: hub restarted, resuming polling"
    initialize()
}

def initialize() {
    unschedule()
    unsubscribe()
    subscribe(location, "systemStart", "systemStartHandler")
    if (!state.accessToken) {
        try {
            createAccessToken()
            logNormal "Access token created for local snapshot relay endpoint"
        } catch (e) {
            log.warn "Reolink Integration: could not create access token (needed for dashboard snapshot tiles) -- ${e.message}. " +
                "If this persists, check that OAuth is enabled for this app under Apps Code."
        }
    }
    runMigrations()
    initializePolling()
    if (logLevel == "Full") {
        runIn(3600, "revertToNormalLogging")
    }
}

/**
 * One-time upgrade migration, guarded by state.lastKnownAppVersion so it
 * runs once per version transition, not on every Done/Update save. An
 * older, now-fixed battery-check gate used to keep advancing
 * nextBatteryCheckDue a full interval every tick even while silently
 * skipping the check, so that stale schedule would otherwise delay
 * schedulerTick()'s batteryMode backfill by up to a full
 * batteryCheckIntervalHours after upgrading. This clears
 * nextBatteryCheckDue for every device with no batteryMode set, so the
 * backfill runs on the very next tick (~1s) instead. Devices with a valid
 * batteryMode are untouched.
 */
private void runMigrations() {
    if (state.lastKnownAppVersion == APP_VERSION) return
    def fromVersion = state.lastKnownAppVersion ?: "(unknown/pre-migration-tracking)"

    def battDue = state.nextBatteryCheckDue ?: [:]
    int cleared = 0
    (state.sources ?: []).each { src ->
        def bridge = getSourceBridge(src.id)
        (bridge?.getChildDevices() ?: []).each { child ->
            if (child.hasCapability("Battery") && child.currentValue("batteryMode") == null) {
                battDue.remove(child.deviceNetworkId)
                cleared++
            }
        }
    }
    state.nextBatteryCheckDue = battDue
    if (cleared > 0) {
        logNormal "Reolink Integration: migration -- cleared stale battery-check schedule for " +
            "${cleared} device(s) with no batteryMode set, so the fix takes effect on the next tick " +
            "instead of waiting out an old schedule"
    }

    // state.recScheduleCache (an old, now-retired cache-and-restore
    // recording design's stale-snapshot mechanism) is cleared here too, so
    // any leftover entries from an earlier install don't linger in state
    // forever doing nothing.
    if (state.recScheduleCache) {
        int clearedRec = state.recScheduleCache.size()
        state.remove("recScheduleCache")
        logNormal "Reolink Integration: cleared ${clearedRec} leftover cached recording schedule(s) from the " +
            "old cache-and-restore design (${fromVersion} -> ${APP_VERSION}) -- recording schedules are now " +
            "managed via named presets (see the Recording Presets page)"
    }

    logNormal "Reolink Integration: upgraded ${fromVersion} -> ${APP_VERSION}"
    state.lastKnownAppVersion = APP_VERSION
}

/** Auto-reverts Full back to Normal after 60 minutes -- Full is meant for actively chasing something, not a steady state. Errors Only and Normal have no timer. */
def revertToNormalLogging() {
    app.updateSetting("logLevel", [type: "enum", value: "Normal"])
    log.info "Reolink Integration: log level auto-reverted from Full to Normal after 60 minutes"
}

/**
 * Backward-compat stub for the pre-1.2.5 debugLogging system's scheduled
 * callback name. Gives any leftover pending job from an old install
 * somewhere safe to land instead of erroring; nothing schedules a job under
 * this name going forward.
 */
def disableDebugLogging() {
    log.info "Reolink Integration: leftover pre-1.2.5 logging job fired, no action needed (see disableDebugLogging() comment)"
}

def initializePolling() {
    // Children live under each source's bridge, not the app directly --
    // iterate sources -> bridge -> its real (Camera/Doorbell) children.
    // Also (re)establishes each source's bridge/event-subscription state
    // for sources that already have one.
    def now = now()
    def pollDue = state.nextPollDue ?: [:]
    def snapDue = state.nextSnapshotDue ?: [:]
    def battDue = state.nextBatteryCheckDue ?: [:]
    (state.sources ?: []).each { src ->
        // ensureSourceBridge() is only called here for a source that
        // ALREADY has a bridge -- calling it unconditionally for every
        // configured source on every Done/Update click would create a
        // real bridge device (and attempt a live connection) for a source
        // that had just been added, before the user had ever opened its
        // discover page or selected a single channel. A brand-new source
        // with nothing selected yet stays completely untouched until real
        // intent exists via createSelectedChildren(), which is the only
        // place a bridge should ever get created.
        if (!getSourceBridge(src.id)) return
        def bridge = ensureSourceBridge(src.id)
        bridge?.getChildDevices()?.each { child ->
            def dni = child.deviceNetworkId
            if (!pollDue.containsKey(dni)) pollDue[dni] = now
            if (!snapDue.containsKey(dni)) snapDue[dni] = now
            if (!battDue.containsKey(dni)) battDue[dni] = now
        }
    }
    state.nextPollDue = pollDue
    state.nextSnapshotDue = snapDue
    state.nextBatteryCheckDue = battDue
    runIn(1, "schedulerTick", [overwrite: true])
}

/**
 * Single central scheduler -- exactly ONE recurring timer exists for the
 * whole app (this method, ticking every second), and each device's own
 * due-time is tracked independently in state (nextPollDue / nextSnapshotDue,
 * keyed by DNI). Nothing here can ever cancel another device's schedule,
 * because there is only one schedule.
 *
 * Two robustness measures, since this single tick is the ONE thing every
 * device's polling depends on:
 *  1. Each device is processed in its own try/catch. One device throwing
 *     logs a warning and moves on instead of aborting the whole tick.
 *  2. The next tick is re-armed in a finally block, so even an unexpected
 *     failure outside the per-device loop still can't prevent the scheduler
 *     from continuing to run.
 */
def schedulerTick() {
    try {
        def nowMs = now()
        def pollDue = state.nextPollDue ?: [:]
        def snapDue = state.nextSnapshotDue ?: [:]
        def battDue = state.nextBatteryCheckDue ?: [:]

        (state.sources ?: []).each { src ->
            def bridge = getSourceBridge(src.id)
            if (!bridge) return
            def sourceConnected = isSourceEventConnected(src.id)
            (bridge.getChildDevices() ?: []).each { child ->
                def dni = child.deviceNetworkId
                try {
                    // Battery level is NEVER delivered via the event push
                    // path (only motion/AI is), so this check deliberately
                    // runs regardless of sourceConnected -- placed before
                    // that early-return below, unlike poll/snapshot which
                    // correctly skip while event mode is healthy.
                    // hasCapability("Battery") scopes this to whichever
                    // devices actually declare it -- both Camera and
                    // Doorbell drivers do.
                    if (child.hasCapability("Battery") && nowMs >= ((battDue[dni] ?: 0) as Long)) {
                        // A device stuck with batteryMode never set would
                        // otherwise silently and permanently skip this gate
                        // (due-time still advanced, nothing logged, battery
                        // never updated short of a manual check). Missing
                        // batteryMode is treated as "unknown, go find out"
                        // rather than "not battery, skip forever" --
                        // backfilled via a live probe, once. Self-heals on
                        // the next tick.
                        def batteryMode = child.currentValue("batteryMode")
                        if (batteryMode == null) {
                            log.warn "Reolink Integration: ${child.displayName} (${dni}) has no batteryMode set -- " +
                                "backfilling via a live probe"
                            componentCheckBattery(child)
                            def backfilled = child.currentValue("battery") != null ? "battery" : "wired"
                            child.receiveBatteryMode(backfilled)
                            batteryMode = backfilled
                        }
                        def checkEnabled = child.getSetting("batteryCheckEnabled") == true
                        def hours = (child.getSetting("batteryCheckIntervalHours") ?: 12) as Integer
                        if (checkEnabled && hours > 0 && batteryMode == "battery") {
                            componentCheckBattery(child)
                        }
                        // Re-evaluated even when skipped (disabled, or wired
                        // device) so a later settings change or batteryMode
                        // correction is picked up within an hour rather than
                        // never re-checked again.
                        battDue[dni] = nowMs + (Math.max(hours, 1) * 3600L * 1000L)
                    }
                    // While this source has a confirmed-healthy event
                    // connection, skip active polling for it -- the push path
                    // is already delivering its state via
                    // componentEventChannelUpdate(). nextPollDue is
                    // deliberately left untouched here so if the connection
                    // drops, componentEventConnectionStatus() marking it
                    // due-now takes effect immediately.
                    if (sourceConnected) return
                    if (nowMs >= ((pollDue[dni] ?: 0) as Long)) {
                        pollChildNow(child)
                        def interval = (child.getSetting("pollIntervalSec") ?: 30) as Integer
                        pollDue[dni] = nowMs + (interval * 1000L)
                    }
                    if (nowMs >= ((snapDue[dni] ?: 0) as Long)) {
                        pollChildSnapshotNow(child)
                        def sInterval = (child.getSetting("snapshotIntervalSec") ?: 30) as Integer
                        snapDue[dni] = nowMs + (sInterval * 1000L)
                    }
                } catch (e) {
                    log.warn "Reolink Integration: schedulerTick() failed for device ${dni} -- ${e.message}. Skipping this device this tick, will retry next tick."
                    def interval = (child.getSetting("pollIntervalSec") ?: 30) as Integer
                    pollDue[dni] = nowMs + (interval * 1000L)
                }
            }
        }

        state.nextPollDue = pollDue
        state.nextSnapshotDue = snapDue
        state.nextBatteryCheckDue = battDue
    } catch (e) {
        log.warn "Reolink Integration: schedulerTick() failed outside the per-device loop -- ${e.message}"
    } finally {
        runIn(1, "schedulerTick", [overwrite: true])
    }
}

/** Marks a device due on the very next tick (within ~1s) -- used after a poll-interval change so it takes effect immediately rather than waiting out the old interval. */
private markPollDueNow(String dni) {
    def pollDue = state.nextPollDue ?: [:]
    pollDue[dni] = now()
    state.nextPollDue = pollDue
}

/** See markPollDueNow() -- same idea for the snapshot schedule. */
private markSnapshotDueNow(String dni) {
    def snapDue = state.nextSnapshotDue ?: [:]
    snapDue[dni] = now()
    state.nextSnapshotDue = snapDue
}

def pollChild(data) {
    def bridge = getSourceBridgeForChannelDni(data.dni)
    def child = bridge?.getChildDevice(data.dni)
    if (!child) return
    pollChildNow(child)
    markPollDueNow(child.deviceNetworkId)
}

/**
 * Checks the child's CURRENT sleepStatus before logging, so "marking asleep"/
 * "marking awake" only hits Normal-tier logging on a real transition. A
 * device that's already asleep and stays asleep (or already awake and stays
 * awake) only logs at Full tier, since that's routine and not worth
 * surfacing by default.
 */
private void pollChildNow(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer

    def aiState = reolinkApiCall(sourceId, "GetAiState", [:], channel)
    def mdState = reolinkApiCall(sourceId, "GetMdState", [:], channel)
    def wasAsleep = child.currentValue("sleepStatus") == "asleep"

    if (aiState == null && mdState == null) {
        if (wasAsleep) {
            logFull "Reolink source ${sourceId} ch ${channel}: still no response, still asleep"
        } else {
            logNormal "Reolink source ${sourceId} ch ${channel}: no response, marking asleep"
        }
        child.markAsleep()
    } else {
        if (wasAsleep) {
            logNormal "Reolink source ${sourceId} ch ${channel}: response received, marking awake"
        } else {
            logFull "Reolink source ${sourceId} ch ${channel}: response received (still awake)"
        }
        child.parseReolinkState(aiState, mdState)
    }
}

/**
 * Snapshot caching runs on its OWN schedule (nextSnapshotDue, see
 * schedulerTick() above), separate from AI/motion polling (nextPollDue).
 * Motion detection benefits from being fast; a dashboard image does not need
 * to be refreshed nearly that often, and pulling a full JPEG every few
 * seconds across several cameras against this app's singleThreaded
 * execution model risks a semaphore/queueing problem. Defaults to a much
 * looser interval than the poll interval.
 */
def pollChildSnapshot(data) {
    def bridge = getSourceBridgeForChannelDni(data.dni)
    def child = bridge?.getChildDevice(data.dni)
    if (!child) return
    pollChildSnapshotNow(child)
    markSnapshotDueNow(child.deviceNetworkId)
}

private void pollChildSnapshotNow(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    cacheSnapshot(child, sourceId, channel)
}

/**
 * Fetches a fresh snapshot and writes it to local hub file storage, keyed by
 * device DNI. This is the ONLY place that hits the camera for a snapshot --
 * the dashboard-facing relay endpoint (handleSnapshotRequest) just serves
 * whatever's cached here, instantly, with no camera round-trip in the
 * request path.
 */
private void cacheSnapshot(child, sourceId, channel) {
    def src = getSource(sourceId)
    if (!src) return
    def imageBytes = fetchSnapshotBytes(src, sourceId, channel)
    if (imageBytes == null) {
        logNormal "Reolink source ${sourceId} ch ${channel}: snapshot cache refresh failed, keeping last cached image (if any)"
        return
    }
    try {
        uploadHubFile(snapshotFileName(child.deviceNetworkId), imageBytes)
    } catch (e) {
        log.warn "Reolink source ${sourceId} ch ${channel}: failed to write snapshot to hub file storage -- ${e.message}"
    }
}

private String snapshotFileName(dni) {
    "reolink-snap-${dni}.jpg"
}

/**
 * Resolves a proper child device reference given a dni passed explicitly
 * from the driver (device.deviceNetworkId). Falls back to the raw passed
 * reference only for callers that haven't been updated to pass dni yet.
 * These calls arrive via the bridge's passthrough layer (Camera/Doorbell's
 * real parent), not directly from the child -- the lookup routes through
 * whichever bridge actually owns this dni.
 */
private resolveChild(child, String dni) {
    def effectiveDni = dni ?: child?.deviceNetworkId
    if (!effectiveDni) return child
    def bridge = getSourceBridgeForChannelDni(effectiveDni)
    return bridge ? (bridge.getChildDevice(effectiveDni) ?: child) : child
}

// ---------- Component callbacks (children call these via parent.X()) ----------

/** dni passed explicitly (device.deviceNetworkId from the driver) -- see resolveChild(). */
def componentRefresh(child, String dni = null) {
    def effectiveDni = dni ?: child?.deviceNetworkId
    if (!effectiveDni) {
        log.warn "Reolink Integration: componentRefresh() called with a device that has no deviceNetworkId"
        return
    }
    pollChild([dni: effectiveDni])
}

/**
 * Builds the dashboard-facing snapshot URL and, since the person explicitly
 * asked for a snapshot right now, immediately refreshes the cached image
 * rather than waiting for the next poll cycle. The URL itself points at this
 * app's local relay endpoint (see mappings + handleSnapshotRequest() below).
 */
def componentTakeSnapshot(child, String dni = null) {
    def effectiveDni = dni ?: child?.deviceNetworkId
    if (!effectiveDni) {
        log.warn "Reolink Integration: componentTakeSnapshot() called with a device that has no deviceNetworkId, refusing to build a snapshot URL"
        return
    }
    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.warn "Reolink Integration: no access token available, snapshot relay endpoint will not work -- ${e.message}"
            return
        }
    }
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    cacheSnapshot(c, sourceId, channel)
    def url = "${getFullLocalApiServerUrl()}/snap/${effectiveDni}?access_token=${state.accessToken}"
    logNormal "Reolink ${effectiveDni}: snapshot URL built (local relay endpoint, cache refreshed on demand)"
    c.receiveSnapshotUrl(url)
}

/**
 * Handler for GET /snap/:dni?access_token=... -- called by the browser every
 * time a dashboard image tile refreshes. Serves whatever's currently cached
 * in local hub file storage for this device. Deliberately does NOT talk to
 * the camera itself on every request.
 */
def handleSnapshotRequest() {
    def dni = params?.dni
    if (!dni || dni == "null") {
        log.warn "Reolink Integration: snapshot endpoint hit with no/null device id, this URL is stale -- run takeSnapshot again to regenerate it"
        render status: 400, data: "Missing or stale device id, run takeSnapshot again to regenerate this URL", contentType: "text/plain"
        return
    }
    def bridge = getSourceBridgeForChannelDni(dni)
    def child = bridge?.getChildDevice(dni)
    if (!child) {
        render status: 404, data: "Unknown device: ${dni}", contentType: "text/plain"
        return
    }

    byte[] cached = null
    try {
        cached = downloadHubFile(snapshotFileName(dni))
    } catch (e) {
        logFull "Reolink Integration: no cached snapshot yet for ${dni} -- ${e.message}"
    }
    if (!cached || cached.length == 0) {
        render status: 404, data: "No snapshot cached yet for this device -- wait for the next poll cycle or run takeSnapshot", contentType: "text/plain"
        return
    }
    render contentType: "image/jpeg", data: cached
}

/** Fetches a live snapshot, retrying once with a forced fresh login on auth failure. */
private byte[] fetchSnapshotBytes(src, sourceId, channel) {
    def token = reolinkLogin(sourceId)
    def bytes = doFetchSnapshot(src, sourceId, token, channel)
    if (bytes == null) {
        logFull "Reolink source ${sourceId} ch ${channel}: snapshot fetch failed, forcing re-login and retrying once"
        src.token = null
        src.tokenExpires = 0
        def freshToken = reolinkLogin(sourceId)
        if (freshToken) {
            bytes = doFetchSnapshot(src, sourceId, freshToken, channel)
        }
    }
    return bytes
}

/**
 * Low-level Snap GET. On success the camera returns raw JPEG bytes as an
 * InputStream on resp.data, which must be drained explicitly. On failure
 * the camera returns a small JSON error payload instead, detected via
 * content-type.
 */
private byte[] doFetchSnapshot(src, sourceId, token, channel) {
    def uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=Snap&channel=${channel}&token=${token}"
    byte[] result = null
    try {
        httpGet([uri: uri, ignoreSSLIssues: true, timeout: 10]) { resp ->
            def ct = resp?.contentType?.toString()?.toLowerCase() ?: ""
            if (ct.contains("json")) {
                def raw = resp?.data?.toString()
                logNormal "Reolink source ${sourceId} ch ${channel}: snapshot request returned JSON instead of an image -- ${raw?.take(300)}"
            } else if (resp?.data != null) {
                def bos = new ByteArrayOutputStream()
                bos << resp.data
                result = bos.toByteArray()
                if (!result || result.length == 0) {
                    logNormal "Reolink source ${sourceId} ch ${channel}: snapshot stream drained to 0 bytes"
                    result = null
                }
            }
        }
    } catch (e) {
        markSourceUnreachable(sourceId, "snapshot fetch (ch ${channel}) failed: ${e.message}")
    }
    return result
}

def componentPtz(child, String direction, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCtrl", [op: direction, speed: 32], channel)
}

def componentPtzGoToPreset(child, Integer presetId, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCtrl", [op: "ToPos", id: presetId, speed: 32], channel)
}

def componentSavePreset(child, Integer presetId, String name, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetPtzPreset",
        [PtzPreset: [channel: channel, enable: 1, id: presetId, name: name ?: "Preset${presetId}"]], null)
}

def componentSetSpotlight(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetWhiteLed", [WhiteLed: [channel: channel, state: (on ? 1 : 0)]], null)
}

def componentSetNightVision(child, String mode, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetIrLights", [IrLights: [channel: channel, state: mode]], null)
}

def componentSetSiren(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "AudioAlarmPlay", [alarm_mode: "manul", manual_switch: (on ? 1 : 0), times: 2], channel)
}

/**
 * PIR enable/disable, cameras only. Field names unconfirmed against real
 * hardware -- built following the same naming convention as
 * GetIrLights/SetIrLights, see the Tips page's "built but not tested" list.
 */
def componentSetPir(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "SetPirInfo", [PirInfo: [channel: channel, enable: (on ? 1 : 0)]], null)
}

def componentCheckBattery(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def battInfo = reolinkApiCall(sourceId, "GetBatteryInfo", [:], channel)
    c.receiveBatteryInfo(battInfo)
}

/**
 * Manual recheck for a single device's supportedFeatures attribute -- useful
 * after a firmware update that might add capabilities, or if the device was
 * created before this feature existed. Re-fetches GetAbility fresh rather
 * than relying on anything cached from the original discovery.
 */
def componentCheckAbilities(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def abilityChnList = fetchAbilityChnList(sourceId)
    def features = computeSupportedFeatures(abilityChnList?.getAt(channel))
    c.receiveSupportedFeatures(features)
    logNormal "Reolink source ${sourceId} ch ${channel}: capabilities rechecked -- ${features ? features.join(', ') : 'none detected'}"
}

def componentCalibratePtz(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    reolinkApiCall(sourceId, "PtzCheck", [:], channel)
    logNormal "Reolink source ${sourceId} ch ${channel}: PTZ calibration triggered"
}

def componentCheckPtzCalibrationStatus(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def result = reolinkApiCall(sourceId, "GetPtzCheckState", [:], channel)
    def state = result?.PtzCheckState
    logNormal "Reolink source ${sourceId} ch ${channel}: PTZ calibration state = ${state}"
    c.receivePtzCalibrationState(state)
}

/** dni resolved via resolveChild() -- see that method's doc comment for why. */
def componentSetPollInterval(child, Integer seconds, String dni = null) {
    def c = resolveChild(child, dni)
    if (!c) {
        log.warn "Reolink Integration: componentSetPollInterval() could not resolve a device"
        return
    }
    c.updateSetting("pollIntervalSec", [type: "number", value: seconds])
    logNormal "Set poll interval for ${c.deviceNetworkId ?: dni} to ${seconds}s"
    if (c.deviceNetworkId) markPollDueNow(c.deviceNetworkId)
}

/** dni resolved via resolveChild() -- see that method's doc comment for why. */
def componentSetSnapshotInterval(child, Integer seconds, String dni = null) {
    def c = resolveChild(child, dni)
    if (!c) {
        log.warn "Reolink Integration: componentSetSnapshotInterval() could not resolve a device"
        return
    }
    c.updateSetting("snapshotIntervalSec", [type: "number", value: seconds])
    logNormal "Set snapshot interval for ${c.deviceNetworkId ?: dni} to ${seconds}s"
    if (c.deviceNetworkId) markSnapshotDueNow(c.deviceNetworkId)
}

// ============================================================================
// v1.5.0 -- NVR/source recording control. Master-switch and read-modify-
// write schedule mechanics confirmed against a real RLN16-410 (see the
// top-of-file version history). Called by the bridge device's on()/
// off()/push()/loadSelectedPreset()/loadPreset().
// ============================================================================

/**
 * Master NVR-level record enable/disable ONLY -- no per-channel schedule
 * writes at all. CONFIRMED: this host-level SetRecV20 call with no channel
 * is genuinely the master switch -- turning it on starts recording on
 * every channel, including one whose own per-channel schedule was OFF, and
 * this is untargeted at the protocol level: there is no channel field on
 * this specific call, so it always applies to every channel of the source
 * at once. There is no way to target one channel with this call -- that's
 * an API/hardware limitation, not something this app can work around.
 * Per-channel targeting is achieved separately, via componentLoadPreset()
 * below and each preset's per-channel schedule.
 */
def componentSetRecordingEnabled(child, sourceId, Boolean enabled) {
    def bridge = getSourceBridge(sourceId)
    try {
        def hostResult = reolinkApiCall(sourceId, "SetRecV20", [Rec: [enable: enabled ? 1 : 0]])
        logNormal "Reolink source ${sourceId}: host-level SetRecV20 enable=${enabled ? 1 : 0} -- rspCode=${hostResult?.rspCode}"
        bridge?.receiveRecordingEnabled(enabled)
    } catch (e) {
        logNormal "Reolink source ${sourceId}: host-level SetRecV20 failed -- ${e.message}"
    }
}

/**
 * Writes a named preset's per-channel schedule strings to the NVR. Always
 * does a FRESH read-modify-write per channel per call -- no stale snapshot,
 * no "restores whatever was cached the first time this ever ran" trap. A
 * channel with no string saved for this preset is skipped (existing
 * schedule left alone), which also serves as the mechanism for excluding a
 * battery-class channel from a preset meant for wired channels -- just
 * leave that channel's field blank on the Recording Presets page.
 */
/**
 * Read-only lookup used by the bridge's Preferences page to populate a
 * live "Preset to load" dropdown -- see ReolinkDeviceBridge.groovy's
 * getAvailablePresetNames(). Returns the current preset names for this
 * source, sorted for a stable dropdown order across page loads.
 */
def componentGetPresetNames(sourceId) {
    def presets = (state.recPresets ?: [:])[sourceId.toString()] ?: [:]
    return presets.keySet().sort()
}

def componentLoadPreset(child, sourceId, String presetName) {
    def bridge = getSourceBridge(sourceId)
    if (!bridge) {
        log.warn "Reolink source ${sourceId}: no bridge device, cannot load preset"
        return
    }
    def channels = childrenForSource(sourceId as Integer)
    if (!channels) {
        log.warn "Reolink source ${sourceId}: no channel devices found, nothing to change"
        return
    }
    def chMap = (state.recPresets ?: [:])[sourceId.toString()]?.get(presetName)
    if (chMap == null) {
        log.warn "Reolink source ${sourceId}: preset '${presetName}' not found -- check the Recording Presets page"
        bridge.receiveRecordingResult("Preset '${presetName}' not found")
        return
    }

    int okCount = 0
    def failedChannels = []
    def skipped = []
    // Channels locked out entirely, distinct from `skipped` (a channel
    // simply left blank in THIS particular preset) -- a locked channel is
    // protected from EVERY preset, not just this one.
    def locked = []
    channels.each { ch ->
        def channelNum = ch.getDataValue("channel") as Integer
        def bitstring = chMap[channelNum.toString()]
        if (ch.getSetting("excludeFromRecordingPresets") == true) {
            // Enforced here regardless of whether this preset even has
            // data for this channel -- the whole point of the lock is that
            // it can't be bypassed by a future preset that DOES set
            // something for this channel, intentionally or by mistake.
            locked << channelNum
        } else if (!bitstring) {
            skipped << channelNum
        } else {
            try {
                if (applyPresetToChannel(sourceId, channelNum, bitstring)) {
                    okCount++
                } else {
                    failedChannels << channelNum
                }
            } catch (e) {
                log.warn "Reolink source ${sourceId} ch ${channelNum}: preset apply failed -- ${e.message}"
                failedChannels << channelNum
            }
        }
        // Settle delay between per-channel writes -- see
        // REC_CHANNEL_SETTLE_MS's declaration and the top-of-file v1.5.0
        // note for why.
        pauseExecution(REC_CHANNEL_SETTLE_MS)
    }

    def parts = ["${okCount}/${channels.size()} OK"]
    if (failedChannels) parts << "failed: ${failedChannels.collect { "ch${it}" }.join(', ')}"
    if (skipped) parts << "skipped (no data): ${skipped.collect { "ch${it}" }.join(', ')}"
    // Reported separately from "skipped (no data)" so it's clear at a
    // glance THIS was a deliberate, permanent lock, not just an unset
    // field in this one preset.
    if (locked) parts << "locked: ${locked.collect { "ch${it}" }.join(', ')}"
    def summary = parts.join(', ')
    logNormal "Reolink source ${sourceId}: preset '${presetName}' loaded -- ${summary}"
    bridge.receiveRecordingMode(presetName)
    bridge.receiveRecordingResult(summary)
}

/**
 * Fresh read-modify-write of one channel's schedule table to the given
 * 168-char bitstring. Every key already present in the device's own
 * returned schedule table gets set (the actual trigger-table key name(s)
 * can't be assumed to be "TIMING" -- real hardware testing showed this),
 * and scheduleEnable is a TOP-LEVEL field on Rec, not nested inside
 * schedule.enable. See fetchRecSchedule()/deepCopyRec() below.
 */
private boolean applyPresetToChannel(sourceId, Integer channel, String bitstring) {
    def fetched = fetchRecSchedule(sourceId, channel)
    if (fetched == null) {
        log.warn "Reolink source ${sourceId} ch ${channel}: could not read current schedule via GetRecV20 or " +
            "classic GetRec, skipping preset write for this channel"
        return false
    }
    def recParam = deepCopyRec(fetched.rec as Map)
    if (fetched.isV20) {
        recParam.channel = channel
        recParam.scheduleEnable = 1
        recParam.schedule = (recParam.schedule ?: [:]) as Map
        recParam.schedule.channel = channel
        recParam.schedule.table = (recParam.schedule.table ?: [:]) as Map
        if (recParam.schedule.table) {
            recParam.schedule.table.keySet().toList().each { key -> recParam.schedule.table[key] = bitstring }
        } else {
            recParam.schedule.table.TIMING = bitstring
        }
    } else {
        recParam.channel = channel
        recParam.enable = 1
        recParam.table = bitstring
    }
    def cmd = fetched.isV20 ? "SetRecV20" : "SetRec"
    def result = reolinkApiCall(sourceId, cmd, [Rec: recParam], channel as Integer)
    return result?.rspCode == 200 || result?.rspCode == 0
}

/**
 * Reads the channel's CURRENT actual recording schedule. Tries GetRecV20
 * first regardless of any GetAbility capability flag, and only falls back
 * to classic GetRec if V20 itself returns no usable value -- CONFIRMED
 * against a real RLN16-410 that GetAbility's own scheduleVersion.ver field
 * doesn't reliably predict which generation this hardware actually needs.
 * Returns [rec: Map, isV20: boolean] so the caller knows which API
 * generation actually worked, or null if both failed.
 */
private Map fetchRecSchedule(sourceId, channel) {
    def v20Result = reolinkApiCall(sourceId, "GetRecV20", [:], channel as Integer)
    if (v20Result?.Rec != null) return [rec: v20Result.Rec, isV20: true]
    logFull "Reolink source ${sourceId} ch ${channel}: GetRecV20 returned no usable value, trying classic GetRec"
    def classicResult = reolinkApiCall(sourceId, "GetRec", [:], channel as Integer)
    if (classicResult?.Rec != null) return [rec: classicResult.Rec, isV20: false]
    return null
}

/**
 * Deep-clones a Map/List structure via a JSON round-trip -- Groovy Maps
 * assign by reference, and applyPresetToChannel() above must NOT mutate the
 * freshly-fetched schedule in place beyond what's intentional. Cheap and
 * reliable in this sandboxed environment (JsonSlurper/JsonOutput are
 * already used elsewhere in this app for the same reason -- see
 * reolinkRawPost()/parseReolinkResponse()).
 */
private Map deepCopyRec(Map source) {
    return new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(source)) as Map
}

/**
 * Read-only sanity check, does NOT call SetRec/SetRecV20 or touch any
 * preset data. Logs which API generation actually worked (or that neither
 * did) for this channel -- useful for confirming a channel's schedule shape
 * before defining a preset against it.
 */
def componentCheckRecordingSchedule(child, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def result = fetchRecSchedule(sourceId, channel)
    if (result == null) {
        logNormal "Reolink source ${sourceId} ch ${channel}: neither GetRecV20 nor classic GetRec returned a usable schedule"
    } else {
        logNormal "Reolink source ${sourceId} ch ${channel}: ${result.isV20 ? 'GetRecV20' : 'GetRec (classic)'} succeeded -- raw Rec: ${result.rec}"
    }
}

// ============================================================================
// Persistent per-preset button numbering and push dispatch for the bridge's
// own PushableButton capability (see ReolinkDeviceBridge.groovy's push()).
// No child devices involved -- the bridge itself is what Rule Machine
// points at.
// ============================================================================

/**
 * Returns this preset's permanently-assigned button number, assigning one
 * from the per-source monotonic counter if it doesn't have one yet.
 * Idempotent -- safe to call every time the Recording Presets page renders,
 * which is also how a preset created before this feature existed gets
 * backfilled with a number the first time the page happens to load after
 * upgrading, with no special migration step required.
 * The counter (state.recNextButtonNumber) only ever increments -- see
 * retireButtonNumber() below for why a deleted preset's number must never
 * be handed back out to a different preset later.
 */
private Integer getOrAssignButtonNumber(sourceId, String presetName) {
    def key = sourceId.toString()
    def mapAll = state.recPresetButtonNumbers ?: [:]
    def bySource = mapAll[key] ?: [:]
    if (bySource.containsKey(presetName)) return bySource[presetName] as Integer
    def counters = state.recNextButtonNumber ?: [:]
    def next = ((counters[key] ?: 0) as Integer) + 1
    counters[key] = next
    state.recNextButtonNumber = counters
    bySource[presetName] = next
    mapAll[key] = bySource
    state.recPresetButtonNumbers = mapAll
    return next
}

/**
 * Removes a deleted preset's entry from the ACTIVE button-number mapping --
 * deliberately does NOT touch state.recNextButtonNumber (the counter), so
 * that number can never be assigned to a different preset later. A rule
 * built around that number simply stops doing anything (componentBridge
 * ButtonPushed() below finds no active preset for it and logs a no-op)
 * instead of ever silently firing whatever preset happens to occupy that
 * number next -- that's the entire point of this design over a plain
 * positional numbering scheme.
 */
private void retireButtonNumber(sourceId, String presetName) {
    def mapAll = state.recPresetButtonNumbers ?: [:]
    def key = sourceId.toString()
    def bySource = mapAll[key] ?: [:]
    bySource.remove(presetName)
    mapAll[key] = bySource
    state.recPresetButtonNumbers = mapAll
}

/**
 * Called by the bridge when its own push(btn) command fires (Rule Machine's
 * "button pushed" trigger, or a manual push from the device page). Looks up
 * which preset -- if any -- currently holds this button number and loads
 * it; a number with no active preset (retired via a deletion, or simply
 * never assigned) logs a no-op warning rather than guessing.
 */
def componentBridgeButtonPushed(child, sourceId, Integer btn) {
    def bySource = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()] ?: [:]
    def presetName = bySource.find { name, num -> num == btn }?.key
    if (!presetName) {
        log.warn "Reolink source ${sourceId}: button ${btn} pushed but no active preset is currently assigned " +
            "to it (may belong to a deleted preset) -- ignoring"
        return
    }
    componentLoadPreset(child, sourceId, presetName)
}

// ---------- Logging ----------

/** Rank of the current logLevel setting within LOG_LEVELS (0=Errors Only, 1=Normal, 2=Full). Defaults to Normal if unset/unrecognized. */
private int logLevelRank() {
    def idx = LOG_LEVELS.indexOf(logLevel ?: "Errors Only")
    return idx < 0 ? 0 : idx
}

/**
 * Logs at Normal tier and above (Normal, Full). Meaningful one-time events
 * and state transitions -- not routine unchanged polls.
 *
 * NOT private -- the Reolink Device Bridge device calls this via
 * parent?.logNormal(...) so its own connection-status logging (starting,
 * connected, reconnecting) obeys the app's Log level setting instead of
 * writing to the hub log unconditionally, same as everything else in this
 * app.
 */
void logNormal(msg) {
    if (logLevelRank() >= 1) log.debug msg
}

/**
 * Logs only at Full tier. Routine poll-by-poll / push-by-push detail --
 * token reuse, individual API calls succeeding, unchanged state repeats,
 * and (via the bridge) every routine event push and corruption-resync
 * detail. NOT private -- see logNormal()'s note above; same reasoning
 * applies here, and this is the tier that actually floods if left
 * unconditional.
 */
void logFull(msg) {
    if (logLevelRank() >= 2) log.debug msg
}
