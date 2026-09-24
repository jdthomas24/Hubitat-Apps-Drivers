/**
 * Reolink Integration (Parent App)
 * Version: 1.6.1
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
 * v1.6.1 -- Pass RTSP port and output width defaults when configuring newly
 * created children so the doorbell driver saves all stream settings at creation.
 *
 * BREAKING CHANGE (v1.3.8): every camera/doorbell became a child of a new
 * per-source "Reolink Device Bridge" instead of a child of this app directly
 * -- existing installs had to delete/recreate devices (and repoint
 * dashboards/rules) via re-discovery under each source.
 *
 * v1.5.3 -- HOTFIX: found during a real production outage where a Home
 * Hub's entire shared event connection (all channels) silently died for
 * 5 days with zero log trace, while still self-reporting "connected."
 * Root cause: the 90s stale-connection watchdog documented in the 1.4.4
 * history below was lost at some point during the 1.5.0 recording-control
 * rewrite (confirmed absent from the actual sendKeepalive() code in
 * ReolinkDeviceBridge.groovy) -- with it gone, a half-open TCP connection
 * (remote side or a router's NAT mapping disappears without a proper
 * close/error) could sit indefinitely reporting healthy with nothing to
 * catch it. A related bug in the same method (sendKeepalive() returning
 * early on a stage change without rescheduling itself) meant even the
 * ordinary keepalive loop could silently stop running under the right
 * conditions.
 * Fixed: the staleness watchdog is restored (ReolinkDeviceBridge.groovy
 * tracks a real last-message timestamp, sendKeepalive() checks it every
 * cycle and forces a reconnect past 90s of silence), and sendKeepalive()
 * now always reschedules itself regardless of which branch it takes.
 * Also added a new, structurally independent audit job (auditEventConnections(),
 * app-level, every 15 minutes via Hubitat's own schedule() rather than this
 * app's runIn chain) that double-checks every source's real liveness and
 * force-reconnects any source its own watchdog missed -- a second layer
 * specifically because the first layer already failed silently once in
 * production.
 * Logging refined after real-world feedback: detecting staleness and
 * starting a reconnect is routine (could just be a transient IP/network
 * blip) and now stays silent by default; log.warn only fires if a
 * reconnect needs a SECOND attempt, and giving up after all 10 attempts
 * is now log.error, not log.warn, since that's the one outcome that
 * actually needs attention. The Full-logging auto-revert (60 minutes)
 * now reverts to Errors Only instead of Normal, matching this app's
 * actual default.
 *
 * v1.5.2 -- HOTFIX: discoverPage()'s per-channel checkbox self-heal logic
 * (added to correct a stale-checked checkbox left over when a device was
 * deleted outside the app -- see the Tips page's "Deleting a device the
 * wrong way" topic) could not distinguish that case from "a brand-new
 * channel the user just toggled on, not yet applied." Both look identical
 * to that check: no device currently exists for this DNI, and the checkbox
 * is true. Since every channel checkbox reposts the page on change
 * (submitOnChange: true), toggling on a never-before-created channel on a
 * multi-channel Hub/NVR source triggered the self-heal on the very next
 * render and silently reset the checkbox back to false before Apply could
 * ever be clicked -- reported as new channels "bouncing back to
 * unselected" and never becoming selectable. Single-channel standalone
 * sources were unaffected (they apply immediately on toggle, no repost in
 * between), and any ALREADY-created device was unaffected (exists == true
 * skips the check entirely regardless of registry state).
 * Fixed by adding a persistent per-app registry (state.knownDeviceDnis) of
 * every DNI this app has actually created at least once. The self-heal
 * check now only fires for a DNI that's genuinely in that registry --
 * i.e. a device that existed and was removed some other way -- never for
 * a channel that's simply never been created yet. markDeviceEverCreated()
 * is called the moment a channel device is actually created;
 * forgetDeviceEverCreated() is called both on an intentional removal
 * (createSelectedChildren()'s uncheck-and-apply path, so re-adding that
 * channel later behaves like a fresh add, not a stale-checkbox case) and
 * on full source removal (removeSource()), alongside the existing
 * forgetSchedulingState() cleanup.
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
 * v1.3.6 through v1.4.5 (condensed, full detail in GitHub commit history):
 * discoverPage() checkbox-toggle fixes and clearer wording; standalone
 * (non-Hub) camera/doorbell support, later found in testing to need a
 * Home Hub or NVR after all (see the "Standalone battery-device support,
 * closed" note below); PIR enable/disable; real-time event-driven updates
 * over a persistent Baichuan subscription, falling back to polling
 * automatically; several scheduler/battery-check gating bugs found via
 * real hardware and fixed (stuck batteryMode, stale due-times surviving
 * an upgrade); a DuplicateDNIException crash on orphaned devices caught
 * gracefully; explicit uninstalled() teardown added rather than relying
 * on Hubitat's implicit cascade-delete.
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

@Field static final String APP_VERSION = "1.6.1"

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

// v1.5.3: how stale a source's event connection can look (per the bridge's
// own isEventConnectionStale() liveness check) before the app-level audit
// job (auditEventConnections()) force-reconnects it. Deliberately looser
// than the bridge's own internal 90s watchdog threshold (STALE_CONNECTION_
// THRESHOLD_SEC in ReolinkDeviceBridge.groovy) -- this audit exists to
// catch the case where that first-layer watchdog itself silently stops
// running, not to compete with it on timing.
@Field static final int SOURCE_STALE_AUDIT_THRESHOLD_SEC = 300

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
    page(name: "discoverSourcesPage")
    page(name: "discoverPage")
    page(name: "removeSourcePage")
    page(name: "editSourcePage")
    page(name: "findChangedIpPage")
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

/** Renders the source list and clears abandoned editing/recovery sessions.
 * @return main configuration page
 */
def mainPage() {
    state.remove("sourceDiscovery")
    state.remove("sourceDiscoverySelection")
    state.remove("ipRecovery")
    state.remove("editSourceId")
    app.removeSetting("editPass")
    state.remove("pendingSourceRemoval")
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
    def sources = state.sources ?: []
    int sourceCount = sources.size()
    int totalDeviceCount = sources.collect { src -> childrenForSource(src.id).size() }.sum() ?: 0

    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, integrationOverviewHtml(sourceCount, totalDeviceCount)
        }
        section(sectionClass: "reolink-main-sources") {
            paragraph rawHtml: true, mainPageColumnHeader("Connected Sources")
            if (sources) {
                sources.each { src ->
                    def deviceCount = childrenForSource(src.id).size()
                    href name: "src_${src.id}", title: "${src.label} (${src.host})",
                        description: sourceSummaryLine(src, deviceCount),
                        page: "discoverPage", params: [sourceId: src.id], width: 12, style: "margin:8px;"
                }
            } else {
                paragraph rawHtml: true, emptySourcesHtml()
            }

            // Keep native navigation inside the same column layout as discovery.
            if (supportsSourceDiscovery()) {
                href name: "discoverSources", title: "<i class='fa-solid fa-magnifying-glass mr-2' aria-hidden='true'></i>Discover sources",
                    description: "Find Reolink devices on the local network",
                    page: "discoverSourcesPage", width: 12, style: "margin:8px;"
            }
            href name: "addSource", title: "<span><i class='fa-regular fa-plus mr-2'></i>Add source</span>",
                description: "Standalone camera, NVR, or Home Hub",
                page: "addSourcePage", width: 12, style: "margin:8px;"
        }
        section(sectionClass: "reolink-main-settings") {
            paragraph rawHtml: true, mainPageColumnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph rawHtml: true, loggingDetailsPopupHtml()
        }
        section(title: "<b>Help & Support</b>", sectionClass: "reolink-main-support") {
            href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>" +
                    "Tips & Troubleshooting", page: "tipsPage",
                description: "Known quirks and setup guidance", width: 4, style: "margin:8px;"
            paragraph rawHtml: true, supportLinkHtml(
                "https://community.hubitat.com/t/release-reolink-integration-cameras-doorbells-nvrs-home-hubs/165352",
                "pi pi-comments", "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 4
            paragraph rawHtml: true, supportLinkHtml(
                "https://www.paypal.com/paypalme/jdthomas24?locale.x=en_US&country.x=US",
                "fa-solid fa-mug-hot", "Buy Me a Coffee", "Support development"), width: 4
        }
        section {
            paragraph "<div class='text-center text-color-secondary text-xs mt-2'>" +
                "Reolink Integration v${APP_VERSION}</div>"
        }
    }
}

/** Status-first summary used by the redesigned main page. Display only. */
private String integrationOverviewHtml(int sourceCount, int deviceCount) {
    boolean configured = sourceCount > 0
    String title = configured ? "Integration ready" : "Setup required"
    String summary = configured ?
        "${countText(sourceCount, 'source')} configured &middot; ${countText(deviceCount, 'device')} created" :
        "Add a source to begin discovering Reolink devices"
    String tone = configured ? "bg-green-50 border-green-200" : "p-message p-message-warn reolink-message"
    String iconColor = configured ? "text-green-700" : "text-yellow-700"
    String icon = configured ? "pi pi-check-circle" : "fa-solid fa-exclamation-triangle"

    return """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
  .reolink-main-sources { float: left; width: calc(62% - 8px); }
  .reolink-main-settings {
    float: right; width: 38%; border-left: 1px solid #e0e0e0;
    padding-left: 8px; box-sizing: border-box;
  }
  .reolink-main-sources > .mdl-grid, .reolink-main-settings > .mdl-grid { padding: 4px 0 !important; }
  .reolink-main-support { clear: both; }
  .reolink-main-support button.hrefElem[name^='_action_href_tips'] {
    height: 61.5px; padding-bottom: 13.5px; box-sizing: border-box;
  }
  @media (max-width: 1000px) {
    .reolink-main-sources, .reolink-main-settings { float: none; width: 100%; border-left: 0; padding-left: 0; }
  }
</style>
<div class='flex align-items-center justify-content-between gap-3 ${tone} border-1 border-round p-3'>
  <div class='flex align-items-center gap-3 min-w-0'>
    <div class='flex-shrink-0'><i class='${icon} ${iconColor} text-2xl' aria-hidden='true'></i></div>
    <div class='min-w-0'>
      <div class='reolink-status-heading font-semibold'>${title}</div>
      <div class='text-color-secondary mt-1' style='font-size:14px;'>${summary}</div>
    </div>
  </div>
  <a href='/logs?tab=past&amp;appId=${app.id}' target='_blank' class='text-blue-700 font-semibold white-space-nowrap no-underline'>
    View logs <i class='fa-regular fa-external-link'></i>
  </a>
</div>
"""
}

/** Shared native Tips navigation card styling for the main and discovery pages. */
private String tipsCardCss() {
    """
  button.hrefElem[name^='_action_href_tips'] {
    position: relative;
    background: #fff;
    border: 1px solid #e0e0e0;
    border-radius: 4px;
    box-shadow: none;
    color: #333;
    height: 56px;
    font-family: inherit;
    font-size: 16px;
    font-weight: 500;
    line-height: 1.4;
    padding: 8px 28px 8px 46px;
  }
  button.hrefElem[name^='_action_href_tips'] > span:first-child { color: #1565c0; font-weight: 600; font-size: 16px !important; }
  button.hrefElem[name^='_action_href_tips'] > span.state-incomplete-text {
    color: #777; font-size: 14px; font-weight: 500; line-height: 1.4;
  }
  button.hrefElem[name^='_action_href_tips'] i.pi {
    position: absolute;
    left: 14px;
    top: 50%;
    transform: translateY(-50%);
    color: #1565c0;
    font-size: 20px;
  }
  button.hrefElem[name^='_action_href_tips']::before { color: #1565c0; }
"""
}

/** Shared page inset rules keep the main and discovery pages aligned. */
private String appPageSpacingCss() {
    """
  ${tileSecondaryTextCss()}
  .reolink-status-heading { font-size: 16px; }
  .p-message.reolink-message { padding: 0.75rem !important; margin: 0; border: 0 !important; }
  .reolink-message .text-color-secondary,
  .reolink-message .text-blue-700, .reolink-message .text-yellow-700 { color: inherit !important; }
  div.panel-body {
    padding: 0 !important;
    margin-left: -0.5em;
    margin-right: -0.5em;
  }
  fieldset#fieldsetAppButtons {
    margin-left: 0.5em;
    margin-right: 0.5em;
  }
"""
}

/** Separate icon/text columns keep wrapped warning text clear of the icon. */
private String warningMessageHtml(String content, String extraClasses = "") {
    "<div class='p-message p-message-warn reolink-message flex align-items-center gap-2 ${extraClasses}'>" +
        "<div class='flex-shrink-0'><i class='fa-solid fa-exclamation-triangle text-xl' aria-hidden='true'></i></div>" +
        "<div class='min-w-0 flex-1'>${content}</div></div>"
}

private String mainPageColumnHeader(String title) {
    "<div class='reolink-status-heading font-semibold'>${title}</div>"
}

/** Match Hubitat's native href secondary text across all app pages. */
private String tileSecondaryTextCss() {
    "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

private String emptySourcesHtml() {
    """
<div class='border-1 border-gray-200 border-round p-3 text-color-secondary'>
  <div class='font-semibold text-color mb-1'>No sources configured</div>
  <div class='text-sm'>A source is one camera, one NVR, or one Home Hub with its own IP/login.</div>
</div>
"""
}

private String sourceSummaryLine(src, int deviceCount) {
    String sourceType = src.isHub ? "Hub/NVR" : "Standalone"
    String key = src.id.toString()
    boolean unreachable = state.sourceUnreachable?.get(key) == true
    String connectionMode = state.sourceConnMode?.get(key)
    boolean pollingOnly = settings["useEventSubscription_${src.id}"] == false

    String status
    String colorClass
    if (unreachable) {
        status = "Unreachable"
        colorClass = "text-red-700"
    } else if (connectionMode == "connected") {
        status = "Online"
        colorClass = "text-green-700"
    } else if (pollingOnly) {
        status = "Polling"
        colorClass = "text-blue-700"
    } else {
        status = "Configured"
        colorClass = "text-color-secondary"
    }

    return "${sourceType} &middot; ${countText(deviceCount, 'device')} &nbsp;&nbsp;" +
        "<span class='${colorClass} font-semibold'>&#9679;&nbsp; ${status}</span>"
}

/**
 * Opens an HTML-formatted explanation in Hubitat's native dialog. Escape the
 * JavaScript string for the single-quoted onclick attribute after JSON encoding.
 */
private String loggingDetailsPopupHtml() {
    String currentLevel = LOG_LEVELS.contains(logLevel) ? logLevel : "Errors Only"
    String details = """
<div class='text-left text-color' style='max-width:520px;line-height:1.45;'>
  <div class='text-xl font-bold mb-1'>Logging levels</div>
  <div class='text-color-secondary text-base mb-3'>Choose how much activity appears in the app logs.</div>
  ${loggingLevelDetailHtml("Errors Only", "Warnings and errors only.", currentLevel)}
  ${loggingLevelDetailHtml("Normal", "Errors, plus meaningful events and changes: logins, asleep/awake, devices created, and configuration changes.", currentLevel)}
  ${loggingLevelDetailHtml("Full", "Everything, including every routine poll step. Use while troubleshooting.", currentLevel)}
</div>
"""
    String jsDetails = groovy.json.JsonOutput.toJson(details)
        .replace("&", "&amp;").replace("'", "&#39;")
        .replace("<", "&lt;").replace(">", "&gt;")
    return """
<a href='#' onclick='window.alertHubitat(${jsDetails}); return false;'
   class='flex align-items-center justify-content-between gap-3 bg-gray-50 border-1 border-gray-200 border-round px-3 py-2 h-full text-color no-underline'>
  <span>
    <span class='block font-semibold'>Logging details</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>What each level records</span>
  </span>
  <i class='pi pi-chevron-right text-blue-700'></i>
</a>
"""
}

private String loggingLevelDetailHtml(String name, String description, String currentLevel) {
    String current = name == currentLevel ?
        "<span class='text-green-700 text-xs font-bold'>Current</span>" : ""
    String tone = name == "Errors Only" ? "bg-red-50 text-red-700" :
        name == "Full" ? "bg-indigo-50 text-indigo-700" : "bg-blue-50 text-blue-700"
    String warning = name == "Full" ? warningMessageHtml(
        "<b>Full</b> is a temporary setting. It automatically reverts to <b>Errors Only</b> after 60 minutes.", "mt-3 text-base") : ""
    return """
<div class='border-1 border-gray-200 border-round px-3 py-2 mb-2'>
  <div class='flex align-items-center justify-content-between gap-3 mb-1'>
    <span class='${tone} border-round-xl px-2 py-1 text-xs font-bold'>${name}${name == "Errors Only" ? " (default)" : ""}</span>
    ${current}
  </div>
  <div class='text-base text-color-secondary'>${description}</div>
  ${warning}
</div>
"""
}

private String supportLinkHtml(String url, String iconClass, String title, String subtitle) {
    return """
<a href='${url}' target='_blank' rel='noopener noreferrer'
   class='flex align-items-center gap-3 border-1 border-gray-200 border-round px-3 py-2 text-color no-underline'>
  <i class='${iconClass} text-blue-700 text-xl flex-shrink-0'></i>
  <span class='min-w-0'>
    <span class='block text-blue-700 font-semibold'>${title}</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>${subtitle}</span>
  </span>
</a>
"""
}

private String countText(int count, String singular) {
    "${count} ${singular}${count == 1 ? '' : 's'}"
}

private String pillHeader(String text) {
    "<div class='inline-block bg-blue-50 text-blue-700 font-bold text-xs uppercase px-3 py-1 " +
    "border-round-xl mb-2'>${text.toUpperCase()}</div>"
}

/**
 * Small colored pill for a log level name, distinct from pillHeader's section-title style so
 * the two don't get visually confused. Color signals severity/verbosity at a glance: red for
 * the errors-only default, grey for the middle tier, dark blue for the noisiest/temporary one.
 */
private String logLevelPill(String level) {
    def tones = [
        "Errors Only": "bg-red-50 text-red-700",
        "Normal":      "bg-gray-100 text-gray-700",
        "Full":        "bg-indigo-50 text-indigo-700"
    ]
    def tone = tones[level] ?: tones["Normal"]
    "<span class='inline-block ${tone} font-bold text-xs px-2 py-1 border-round-xl'>${level}</span>"
}

def tipsPage(params = null) {
    def topics = tipsTopics()
    def topic = topics.find { it.id == params?.topic } ?: topics.find { it.id == "network" }
    int topicIndex = topics.indexOf(topic)
    def nextTopic = topicIndex + 1 < topics.size() ? topics[topicIndex + 1] : null
    dynamicPage(name: "tipsPage", title: "Tips & Troubleshooting") {
        section(sectionClass: "reolink-tips-index") {
            paragraph rawHtml: true, tipsStylesHtml()
            topics.groupBy { it.group }.each { group, entries ->
                paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold mt-2'>${group}</div>"
                entries.each { entry ->
                    String selected = entry.id == topic.id ? "reolink-topic-current" : ""
                    href name: "tip_${entry.id}",
                        title: "<span class='${selected}'><i class='pi ${entry.icon} mr-3' aria-hidden='true'></i>${entry.label}</span>",
                        description: "", page: "tipsPage", params: [topic: entry.id],
                        width: 12, style: "margin:0 8px;"
                }
            }
        }
        section(sectionClass: "reolink-tips-article") {
            paragraph rawHtml: true, tipsArticleHtml(topic)
            if (topic.id == "network") {
                def sources = topics.find { it.id == "sources" }
                paragraph rawHtml: true, tipsArticleHtml(sources)
            }
            if (nextTopic) {
                href name: "nextTip", title: "<span class='text-blue-700'>${nextTopic.label}</span>",
                    description: "Next topic", page: "tipsPage", params: [topic: nextTopic.id],
                    width: 12, style: "margin:8px;"
            }
        }
    }
}

private String tipsStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  .reolink-tips-index { float: left; width: calc(27% - 8px); box-sizing: border-box; }
  .reolink-tips-article { float: right; width: 73%; border-left: 1px solid #e0e0e0; padding-left: 8px; box-sizing: border-box; }
  .reolink-tips-index > .mdl-grid, .reolink-tips-article > .mdl-grid { padding: 4px 0 !important; }
  .reolink-tips-index .mdl-cell:has(> style) { display: none; }
  .reolink-tips-index button.hrefElem {
    background: transparent; box-shadow: none; border: 0; border-left: 3px solid transparent;
    border-radius: 0; padding: 9px 10px; font-family: inherit; font-size: 16px; color: #1565c0;
  }
  .reolink-tips-index button.hrefElem::before { display: none; }
  .reolink-tips-index button.hrefElem:has(.reolink-topic-current) { background: #eaf2fc; border-left-color: #1565c0; font-weight: 600; }
  .reolink-tips-index button.hrefElem:hover { background: #f3f6fa; }
  .reolink-tips-index button.hrefElem:focus-visible { outline: 2px solid #1565c0; outline-offset: 2px; }
  .reolink-tip-card { border: 1px solid #dfe3e8; border-radius: 4px; overflow: hidden; }
  .reolink-tip-card-header { padding: 16px; background: #f5f7fa; border-bottom: 1px solid #e4e7ec; }
  .reolink-tip-copy { padding: 16px; line-height: 1.55; overflow-wrap: anywhere; }
  .reolink-tip-copy p { margin: 0 0 16px; }
  .reolink-tip-copy p:last-child { margin-bottom: 0; }
  .reolink-tips-article button.hrefElem { background: #fff; box-shadow: none; border: 1px solid #dfe3e8; border-radius: 4px; font-family: inherit; }
  .reolink-tips-article button.hrefElem::before { color: #1565c0; }
  .form:has(> .reolink-tips-index) + fieldset,
  #formApp:has(.reolink-tips-index) #fieldsetAppButtons { clear: both; }
  @media (max-width: 1000px) {
    .reolink-tips-index, .reolink-tips-article { float: none; width: 100%; padding: 0; border-left: 0; }
  }
</style>
"""
}

private String tipsArticleHtml(Map topic) {
    String extra = ""
    if (topic.id == "network") {
        extra = "<div class='border-1 border-gray-200 border-round p-3 mb-3'>" +
            ["HTTP", "HTTPS", "ONVIF"].collect { service ->
                "<div class='flex align-items-center gap-3 py-2'><i class='pi pi-check-circle text-blue-700 text-xl' aria-hidden='true'></i>" +
                "<div><div class='font-semibold'>${service}</div><div class='text-sm text-color-secondary'>Enable in the camera settings.</div></div></div>"
            }.join("") + "</div>" +
            warningMessageHtml("<b>Network services are often disabled by default.</b><br>Check these before troubleshooting the connection.")
    }
    """
<article class='reolink-tip-card'>
  <div class='reolink-tip-card-header flex align-items-center gap-3'>
    <i class='pi ${topic.icon} text-blue-700 text-xl' aria-hidden='true'></i>
    <h4 class='reolink-status-heading font-semibold m-0'>${topic.title}</h4>
  </div>
  <div class='reolink-tip-copy'>${topic.body}${extra}</div>
</article>
"""
}

/** All existing guidance, grouped for native topic navigation. */
private List tipsTopics() {
    def topics = [
        [id: "sources", label: "Source basics", title: "What is a source?", group: "Getting started", icon: "pi-info-circle",
            body: [
                "<p>" + ("A source is one camera, one NVR, or one Home Hub -- anything with its own IP/login. " +
                "A standalone camera always has one channel: 0. An NVR/Home Hub has one channel per paired " +
                "camera -- run discovery to see what it finds.") + "</p>"
            ].join("")],
        [id: "network", label: "Network setup", title: "Before adding a camera", group: "Getting started", icon: "pi-sitemap",
            body: [
                "<p>" + ("Check the camera's own Network > Advanced (or Server) settings and make sure HTTP, " +
                "HTTPS, and ONVIF are enabled. These are often off by default on every model tested so far -- " +
                "not just Reolink's E1 line -- and this is the single most common reason a source fails to " +
                "connect, before assuming a device needs a Hub/NVR or isn't supported.") + "</p>"
            ].join("")],
        [id: "ids", label: "Device IDs", title: "Why device IDs have gaps", group: "Devices & connections", icon: "pi-list",
            body: [
                "<p>" + ("Each device's internal ID (part of its DNI, e.g. 'reolink-4-0') only ever goes up, " +
                "never reused. Gaps in the numbering just mean a source was removed and re-added at some " +
                "point -- normal, and nothing to fix.") + "</p>"
            ].join("")],
        [id: "removal", label: "Device removal", title: "Remove devices safely", group: "Devices & connections", icon: "pi-trash",
            body: [
                "<p>" + ("Remove a Camera/Doorbell from its source's Discover Channels page, not Hubitat's Devices page. " +
                "For one channel, turning it off removes the device immediately. For multiple channels, turn it off " +
                "and use <b>Apply device changes</b>. To remove a whole source, choose <b>Remove source...</b>, " +
                "review the affected devices, and confirm. Hubitat gives apps no way to be notified when a " +
                "device is deleted directly from the Devices page, so doing that can leave stale app records.") + "</p>",
                "<p>" + ("<b>What's handled automatically:</b> the next time you open that source's Discover " +
                "page, it notices the device is actually gone, corrects the stuck-on checkbox back to " +
                "off, and cleans up its poll/snapshot/battery-check scheduling entries for it. Not instant " +
                "-- only self-heals on that next page view -- but it stops the stale state from sitting " +
                "there indefinitely.") + "</p>",
                "<p>" + ("&#9888; <b>What's NOT handled:</b> deleting a source's \"Reolink Device Bridge\" device " +
                "itself this way, instead of using the source-removal confirmation page. The bridge holds " +
                "the live event connection and is the real parent of every Camera/Doorbell under it -- " +
                "Hubitat will likely cascade-delete those children along with it, but this app's own record " +
                "of that source would still think it exists, with no bridge left to find. This case isn't " +
                "specifically handled -- always remove a whole source via the source-removal confirmation page, never " +
                "by deleting its bridge device directly.") + "</p>"
            ].join("")],
        [id: "compatibility", label: "Compatibility", title: "Camera compatibility", group: "Getting started", icon: "pi-camera",
            body: [
                "<p>" + ("&#9888; <b>Battery-class cameras/doorbells</b> (Argus line, Doorbell Battery, Gen 2 " +
                "doorbells) -- treat these as requiring a Home Hub or NVR. Add the Hub/NVR as the source " +
                "instead, and the device shows up as one of its channels. This does NOT depend on how the " +
                "device is powered -- even one running continuously on a DC adapter is affected, since it's " +
                "a firmware/network-stack limitation, not a charging-mode setting. Confirmed working well " +
                "behind a Hub/NVR across a real multi-device battery fleet.") + "</p>",
                "<p>" + ("&#9888; <b>E1, E1 Pro, and Lumus</b> -- Reolink's own docs on local HTTP/HTTPS support are " +
                "inconsistent for this line. Don't rely on the model name -- check the camera's own Network > " +
                "Advanced (or Server) settings for HTTP/HTTPS/ONVIF toggles directly. Everything else -- PoE " +
                "cameras, WiFi cameras outside the E1 line -- works standalone.") + "</p>"
            ].join("")],
        [id: "polling", label: "Polling intervals", title: "Polling intervals", group: "Devices & connections", icon: "pi-clock",
            body: [
                "<p>" + ("Wired devices can be polled tight (a few seconds). Battery devices should stay loose " +
                "-- they only wake for their own events or an occasional check-in, and polling harder doesn't " +
                "get fresher data, it just drains the battery. This still holds behind a Hub, since you're " +
                "asking the Hub for its last-known state, not the device directly.") + "</p>",
                "<p>" + ("When a source's event connection is active/healthy, its children update in real time " +
                "and polling is skipped entirely -- polling only resumes automatically if that connection drops.") + "</p>"
            ].join("")],
        [id: "events", label: "Event-driven updates", title: "Event-driven updates", group: "Devices & connections", icon: "pi-wifi",
            body: [
                "<p>" + ("On by default, and for almost every source there's nothing to do here -- a source " +
                "that supports it gets real-time updates, and if the connection ever drops, it retries " +
                "automatically (backing off over 10 attempts) before settling into plain polling on its own. " +
                "No toggle needed for that case; it self-recovers.") + "</p>",
                "<p>" + ("This toggle matters for ONE specific case: a source that structurally can't do event " +
                "mode at all -- port 9000 blocked by a firewall, or older firmware that doesn't speak the " +
                "event protocol. That source will still go through all 10 reconnect attempts (and their " +
                "logging) every time the hub restarts or the app re-initializes, before eventually giving up " +
                "and polling anyway. If you already know a source falls into this category, turning this off " +
                "skips that runway entirely and goes straight to polling -- a convenience, not a different " +
                "outcome, since it lands in the same place either way.") + "</p>"
            ].join("")],
        [id: "sleep", label: "Sleep status", title: "Awake and asleep", group: "Devices & connections", icon: "pi-moon",
            body: [
                "<p>" + ("<b>Awake</b> -- the last poll got a response. <b>Asleep</b> -- it didn't. For a " +
                "battery device, asleep is normal, not an error. &#9888; For a <b>wired/PoE device</b>, asleep is " +
                "NOT normal -- it points to a real connectivity or load issue. Motion/person/vehicle/etc. " +
                "keep their last-known value rather than resetting to inactive when this happens.") + "</p>"
            ].join("")],
        [id: "firmware", label: "Older firmware", title: "False asleep reports on older firmware", group: "Devices & connections", icon: "pi-exclamation-triangle",
            body: [
                "<p>" + ("&#9888; Some E1-series cameras on ~2021-era firmware have a known bug where the camera's " +
                "web server intermittently returns corrupted data instead of a real response -- not a " +
                "connectivity problem, just bad data from the camera itself, reported as <b>asleep</b> even " +
                "though it's online. Tell-tale sign: flips to asleep with no real pattern, and Full logging " +
                "shows parse errors on GetAiState/GetMdState rather than plain timeouts. Newer firmware on " +
                "the same camera line doesn't show this.") + "</p>",
                "<p>" + ("Fix, in order: (1) In the Reolink app, toggle this camera's HTTP/HTTPS off then back " +
                "on under Network settings and reboot it -- reinitializes the web server. (2) If that doesn't " +
                "help, check for a firmware update via the Reolink desktop app's Download Center, or contact " +
                "Reolink support with your model/firmware version. Avoid any 'reset configuration' option " +
                "unless you actually want to reset the camera.") + "</p>"
            ].join("")],
        [id: "ptz", label: "PTZ presets", title: "PTZ presets", group: "Camera controls", icon: "pi-arrows-alt",
            body: [
                "<p>" + ("Reolink has no 'Home' command -- the equivalent is a saved preset. Use " +
                "<b>savePresetHere</b> once (commonly preset ID 1) to save wherever the camera is currently " +
                "pointed, then <b>ptzGoToPreset</b> with that ID any time to return there.") + "</p>"
            ].join("")],
        [id: "calibration", label: "PTZ calibration", title: "PTZ calibration", group: "Camera controls", icon: "pi-compass",
            body: [
                "<p>" + ("&#9888; Only applies to PTZ-capable cameras (e.g. Trackmix, E1 Zoom) -- non-PTZ cameras " +
                "just harmlessly error if you try it. Use <b>calibratePtz</b> if preset recall starts " +
                "drifting off target over time; check progress with <b>checkPtzCalibrationStatus</b> " +
                "(Required / Running / Done).") + "</p>"
            ].join("")],
        [id: "pir", label: "PIR trigger", title: "PIR motion trigger", group: "Camera controls", icon: "pi-bolt",
            body: [
                "<p>" + ("Use <b>pirOn</b>/<b>pirOff</b> to enable or disable a camera's PIR trigger without " +
                "removing the device. Does NOT stop an in-progress recording -- it removes the trigger that " +
                "would have woken a battery camera to record. Manual only, no auto-revert timer -- build " +
                "battery-threshold automation with Rule Machine using the existing battery attribute.") + "</p>"
            ].join("")],
        [id: "recording", label: "Recording presets", title: "Recording presets", group: "Recording & snapshots", icon: "pi-video",
            body: [
                "<p>" + ("Two independent controls: the bridge device's own <b>On/Off switch</b> is the NVR's " +
                "master switch (every channel at once -- no per-channel targeting, that's a hardware/API " +
                "limitation). Loading a <b>preset</b> writes a named, per-channel schedule from the Recording " +
                "Presets page. In practice: turn the master switch on once and leave it, then use presets to " +
                "control what each channel actually records. A channel left as \"Don't manage\" in a preset " +
                "is skipped -- its existing schedule stays untouched -- which is how to keep a battery-class " +
                "channel out of a preset meant for wired ones. Every preset write is a fresh read-modify-write " +
                "against the channel's current schedule, never a cached/restored snapshot.") + "</p>",
                "<p>" + ("&#9888; <b>A preset's schedule covers continuous and AI/motion-triggered recording " +
                "together, not separately.</b> Confirmed against real hardware: a channel has one time-table " +
                "for continuous (\"TIMING\") and separate tables per AI type -- but a preset here sets all of " +
                "them to the same hours. So \"Continuous 6pm-6am\" also limits AI-triggered clips to that same " +
                "window. To get continuous-only-at-certain-hours while still catching AI events any time, use " +
                "two presets (e.g. \"Daytime\"/\"Nighttime\") switched by a time-based Rule Machine schedule.") + "</p>"
            ].join("")],
        [id: "snapshots", label: "Snapshot tiles", title: "Snapshot tiles on dashboards", group: "Recording & snapshots", icon: "pi-image",
            body: [
                "<p>" + ("Snapshot URLs point at a local relay endpoint on this app, not the camera directly -- " +
                "the camera is only contacted on its own snapshot interval (separate from poll interval), and " +
                "the relay just serves whatever's cached. A dashboard tile can refresh as often as you like, " +
                "but the picture only actually changes as often as that device's snapshot interval -- the " +
                "tile's own refresh setting doesn't matter. Poll interval should stay tight for responsive " +
                "motion automations; snapshot interval only affects image freshness and can stay looser " +
                "(default 30s). If a tile feels slow to update, lower the device's snapshot interval, not the " +
                "poll interval.") + "</p>"
            ].join("")],
        [id: "logging", label: "Log levels", title: "Log levels", group: "Logging", icon: "pi-file",
            body: [
                "<p>" + (logLevelPill("Errors Only") + " Default. Warnings and errors only.") + "</p>",
                "<p>" + (logLevelPill("Normal") + " Errors, plus meaningful one-time events: logins, " +
                "asleep/awake, devices created, config changes. Routine unchanged polls log nothing.") + "</p>",
                "<p>" + (logLevelPill("Full") + " Everything, including every routine poll step. " +
                "<b>Automatically reverts to Errors Only after 60 minutes.</b>") + "</p>",
                "<p>" + ("&#9888; It's normal for Errors Only/Normal to show nothing for long stretches -- that means " +
                "nothing worth flagging happened, not that the app stopped working. Switch to Full temporarily " +
                "to confirm it's actually running.") + "</p>"
            ].join("")]
    ]
    def order = ["sources", "network", "compatibility", "removal", "ids", "polling", "events",
                 "sleep", "firmware", "ptz", "calibration", "pir", "recording", "snapshots", "logging"]
    order.collect { id -> topics.find { it.id == id } }
}

/** Checks the minimum firmware that includes native source discovery.
 * @return true for local builds or numeric firmware versions 2.5.2.122 or newer; false when unknown
 */
private boolean supportsSourceDiscovery() {
    String version = location?.hub?.firmwareVersionString?.toString()
    if (version?.startsWith("local.") || version?.endsWith(".local")) return true
    if (!version || !(version ==~ /\d+\.\d+\.\d+\.\d+/)) return false
    def actual = version.tokenize('.').collect { new BigInteger(it) }
    def minimum = [2, 5, 2, 122]
    // Compare components numerically so builds such as 99 sort before 122.
    for (int i = 0; i < minimum.size(); i++) {
        int comparison = actual[i] <=> minimum[i]
        if (comparison != 0) return comparison > 0
    }
    return true
}

/** Finds LAN sources without authenticating or creating devices. */
def discoverSourcesPage() {
    if (state.remove("cancelSourceDiscoveryRequested")) return mainPage()
    if (!state.sourceDiscovery) startSourceDiscovery()
    // Initial setup apps cannot run scheduled jobs; page refresh also collects the scan.
    def pendingScan = state.sourceDiscovery
    if (pendingScan?.phase == "searching") {
        completeSourceDiscovery([nonce: pendingScan.nonce, scanId: pendingScan.scanId])
    }
    def scan = state.sourceDiscovery
    boolean busy = scan.phase == "searching"
    dynamicPage(name: "discoverSourcesPage", title: "Discover sources", refreshInterval: busy ? 2 : 0) {
        section {
            paragraph rawHtml: true, recoveryStylesHtml()
            paragraph scan.message
            (scan.candidates ?: []).eachWithIndex { candidate, index ->
                def existing = discoveredSourceMatch(candidate)
                String title = "Reolink device (${discoveryEscapeHtml(candidate.host)})"
                if (existing) {
                    paragraph rawHtml: true, "<div class='border-1 border-gray-200 border-round p-3'>" +
                        "<div class='font-semibold'>${title}</div>" +
                        "<div class='text-sm text-color-secondary mt-1'>ID: ${discoveryEscapeHtml(candidate.uid)}</div>" +
                        "<div class='text-green-700 mt-2'>Already added: ${discoveryEscapeHtml(existing.label)}</div></div>"
                } else {
                    href name: "discoveredSource_${index}",
                        title: "<i class='fa-regular fa-plus mr-2' aria-hidden='true'></i>${title}",
                        description: "Set up this source - ID: ${candidate.uid}", page: "addSourcePage",
                        params: [discoveryNonce: scan.nonce, candidateIndex: index], width: 12, style: "margin:8px;"
                }
            }
            paragraph "Select a new source to review its details and enter credentials. Devices must be on the same local network; sleeping devices may not respond."
        }
        section(sectionClass: "reolink-recovery-actions") {
            input "rescanSources", "button", title: "<i class='fa-solid fa-rotate-right mr-2' aria-hidden='true'></i>Search again",
                width: 4, submitOnChange: true, disabled: busy, inputClass: "p-button"
            input "cancelSourceDiscovery", "button",
                title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, inputClass: "p-button"
        }
    }
}

private def discoveredSourceMatch(Map candidate) {
    (state.sources ?: []).find { src ->
        (normalizeSourceUid(candidate.uid) && normalizeSourceUid(candidate.uid) == normalizeSourceUid(src.identity?.uid)) ||
            src.host?.toString()?.trim() == candidate.host
    }
}

private void startSourceDiscovery() {
    if (state.sourceDiscovery?.phase == "searching") return
    def scan = [nonce: java.util.UUID.randomUUID().toString(), phase: "searching", startedAt: now(),
        candidates: [], message: "Searching the local network..."]
    state.remove("sourceDiscoverySelection")
    try {
        scan.scanId = hubitat.helper.NetworkUtils.startReolinkDiscovery()
        state.sourceDiscovery = scan
        runIn(4, "completeSourceDiscovery", [data: [nonce: scan.nonce, scanId: scan.scanId]])
    } catch (MissingMethodException ignored) {
        scan.phase = "error"
        scan.message = "This hub firmware does not support Reolink LAN discovery. Select Cancel, then Add source to enter the connection details."
    } catch (Exception ignored) {
        scan.phase = "error"
        scan.message = "Discovery could not start. Try again or add the source manually."
    }
    state.sourceDiscovery = scan
}

/** Collects a bounded scan and ignores callbacks from abandoned pages or older searches. */
def completeSourceDiscovery(data) {
    def scan = state.sourceDiscovery
    if (!scan || scan.nonce != data.nonce || scan.scanId != data.scanId || scan.phase != "searching") return
    try {
        def result = hubitat.helper.NetworkUtils.getReolinkDiscoveryStatus(scan.scanId)
        if (result.status == "running" && now() - scan.startedAt < 15000L) {
            runIn(2, "completeSourceDiscovery", [data: data])
            return
        }
        if (result.status == "complete") {
            scan.candidates = (result.candidates ?: []).findAll { candidate ->
                String host = candidate.host?.toString()
                normalizeSourceUid(candidate.uid) && host && host ==~ /(?:\d{1,3}\.){3}\d{1,3}/ &&
                    host.tokenize('.').every { it.toInteger() <= 255 }
            }.collect { [host: it.host.toString(), uid: normalizeSourceUid(it.uid)] }
                .unique { "${it.uid}|${it.host}" }.sort { a, b -> a.host <=> b.host }
            scan.phase = "done"
            scan.message = scan.candidates ? "Found ${scan.candidates.size()} Reolink device(s)." :
                "No Reolink devices found. Check power and network connections, then search again or add a source manually."
        } else {
            scan.phase = "error"
            scan.message = result.errorCode == "reply_port_in_use" ?
                "UDP port 3000 is already in use. Close Reolink Client if it is running on the same computer as this hub, wait 10 seconds, then search again." :
                "Discovery failed or expired. Try again or add the source manually."
        }
    } catch (Exception ignored) {
        scan.phase = "error"
        scan.message = "Discovery could not complete. Try again or add the source manually."
    }
    if (state.sourceDiscovery?.nonce == data.nonce && state.sourceDiscovery?.scanId == data.scanId)
        state.sourceDiscovery = scan
}

/** Prepares a fresh draft once per selected scan result, preserving subsequent user edits. */
private boolean prepareDiscoveredSource(params) {
    def scan = state.sourceDiscovery
    String indexText = params.candidateIndex?.toString()
    if (!scan || scan.phase != "done" || scan.nonce != params.discoveryNonce || !indexText?.isInteger()) return false
    int index = indexText.toInteger()
    if (index < 0 || index >= (scan.candidates ?: []).size()) return false
    def candidate = scan.candidates[index]
    if (discoveredSourceMatch(candidate)) return false
    String selection = "${scan.nonce}:${index}"
    if (state.sourceDiscoverySelection != selection) {
        app.updateSetting("newLabel", [type: "text", value: "Reolink ${candidate.host}"])
        app.updateSetting("newHost", [type: "text", value: candidate.host])
        app.updateSetting("newPort", [type: "number", value: 443])
        app.removeSetting("newUser")
        app.removeSetting("newPass")
        app.removeSetting("newIsHub")
        state.sourceDiscoverySelection = selection
    }
    return true
}

def addSourcePage(params) {
    def action = state.remove("addSourceAction")
    if (params?.cancel || action == "cancel") {
        app.removeSetting("newLabel")
        app.removeSetting("newHost")
        app.removeSetting("newPort")
        app.removeSetting("newUser")
        app.removeSetting("newPass")
        app.removeSetting("newIsHub")
        return mainPage()
    }
    if (action == "add") return mainPage()
    if (params?.discoveryNonce && !prepareDiscoveredSource(params)) return discoverSourcesPage()
    dynamicPage(name: "addSourcePage", title: "Add a Reolink Source") {
        section(sectionClass: "reolink-add-form") {
            paragraph rawHtml: true, recoveryStylesHtml() + addSourceStylesHtml()
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>Source details</div>" +
                "<div class='text-sm text-color-secondary mt-1'>Enter connection details for your Reolink camera, NVR, or Home Hub.</div>"
            input "newLabel", "text", title: "Label (e.g. 'Front Door Hub', 'Garage Cam')"
            input "newHost", "text", title: "IP address", width: 8
            input "newPort", "number", title: "HTTPS port", defaultValue: 443, width: 4
            input "newUser", "text", title: "Username"
            input "newPass", "password", title: "Password"
            input "newIsHub", "bool", title: "This is an NVR or Home Hub" +
                "<div class='text-sm text-color-secondary mt-1'>Multiple channels from one source.</div>",
                defaultValue: false, styleClass: "reolink-add-source-type"
        }
        section(sectionClass: "reolink-add-guidance") {
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>Before you connect</div>" +
                "<div class='text-sm text-color-secondary mt-1'>Check the source's server settings in Reolink.</div>"
            paragraph rawHtml: true, warningMessageHtml("<div class='font-semibold'>Enable HTTPS first</div>" +
                "<div class='text-sm mt-2'>Enable HTTPS in Reolink Network &gt; Advanced (or Server) settings. " +
                "Match the HTTPS port to the value configured there.</div>")
            paragraph rawHtml: true, "<div class='p-message p-message-info reolink-message'>" +
                "<div class='font-semibold'><i class='pi pi-info-circle mr-2' aria-hidden='true'></i>Real-time events</div>" +
                "<div class='text-sm mt-2'>Allow Hubitat to reach TCP port 9000 on the source over your local network. " +
                "If real-time events are unavailable, the app falls back to polling.</div></div>"
            paragraph rawHtml: true, "<div class='bg-gray-50 border-round p-3'><div class='font-semibold'>Optional services</div>" +
                "<div class='text-sm text-color-secondary mt-2'>RTSP is required for video streaming. HTTP, RTMP, and ONVIF are optional.</div></div>"
            href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
                description: "Known quirks and setup guidance", page: "tipsPage", width: 12, style: "margin:8px;"
            paragraph rawHtml: true, "<div class='bg-gray-50 border-round p-3'><div class='font-semibold'>Compatibility note</div>" +
                "<div class='text-sm text-color-secondary mt-2'>Some battery cameras, doorbells, and E1 models have additional " +
                "connection limitations. See Tips &amp; Troubleshooting for details.</div></div>"
        }
        section(sectionClass: "reolink-add-footer") {
            paragraph rawHtml: true, "<div class='text-sm text-color-secondary'>Fill in Label, IP address, Username, and Password, then select Add source. " +
                "Leaving any of those blank returns to Sources without creating anything.</div>"
        }
        section(sectionClass: "reolink-recovery-actions reolink-add-actions") {
            input "confirmAddSource", "button",
                title: "<i class='fa-solid fa-plus mr-2' aria-hidden='true'></i>Add source",
                width: 4, submitOnChange: true, styleClass: "reolink-recovery-primary",
                inputClass: "p-button bg-hubitat-primary-green text-white"
            input "cancelAddSource", "button",
                title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, inputClass: "p-button"
        }
    }
}

private String addSourceStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
  .reolink-add-form { float: left; width: calc(62% - 8px); }
  .reolink-add-guidance { float: right; width: 38%; padding-left: 8px; border-left: 1px solid #e0e0e0; box-sizing: border-box; }
  .reolink-add-form > .mdl-grid, .reolink-add-guidance > .mdl-grid { padding: 4px 0 !important; }
  .reolink-add-form .mdl-cell:has(> style) { display: none; }
  .reolink-add-source-type { background: #f5f7fa; border-radius: 4px; padding: 12px; box-sizing: border-box; }
  .reolink-add-source-type .mdl-switch { height: auto; min-height: 24px; }
  .reolink-add-source-type .mdl-switch__label { line-height: 24px; }
  .reolink-add-footer { clear: both; border-top: 1px solid #e0e0e0; margin: 0 8px !important; }
  .reolink-add-actions { clear: both; }
  @media (max-width: 1000px) {
    .reolink-add-form, .reolink-add-guidance { float: none; width: 100%; padding: 0; border-left: 0; }
  }
</style>
"""
}

/** Shows channels and connection actions for the selected source.
 * @param params navigation arguments containing sourceId and optional discovery action
 * @return source configuration page
 */
def discoverPage(params) {
    state.remove("ipRecovery")
    state.remove("editSourceId")
    app.removeSetting("editPass")
    state.remove("pendingSourceRemoval")
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

    // Consume the button action once, and only for the source it was clicked on.
    def pendingDeviceSourceId = state.remove("pendingDeviceSourceId")
    if (src && pendingDeviceSourceId != null && pendingDeviceSourceId.toString() == sourceId.toString()) {
        createSelectedChildren(sourceId)
    }
    if (settings.containsKey("confirmCreate")) app.removeSetting("confirmCreate")

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

    // Retire the old immediate-removal switch; a stale saved value must never delete a source.
    if (settings.containsKey("confirmRemoveSource")) app.removeSetting("confirmRemoveSource")

    return dynamicPage(name: "discoverPage", title: "Discover Channels - ${src?.label ?: '(source removed)'}", nextPage: "mainPage", nextPageLabel: "Done") {
        if (!src) {
            section {
                paragraph "This source has been removed. Go back and use Add a source... if this was a mistake."
            }
        } else {
            section(sectionClass: "reolink-discovery-summary") {
                paragraph rawHtml: true, discoveryOverviewHtml(channelCount,
                    childrenForSource(sourceId as Integer).size(), !!state.lastDiscoveryError)
            }
            section(sectionClass: "reolink-discovery-channels") {
                paragraph rawHtml: true, "<div class='reolink-discovery-heading font-semibold'>Channels</div>" +
                    "<div class='text-color-secondary mt-1' style='font-size:14px;'>Toggle channels to add or remove devices.</div>", width: 7
                href name: "runDiscovery", title: "<i class='fa-solid fa-rotate-right mr-2' aria-hidden='true'></i>Re-run discovery",
                    description: "",
                    page: "discoverPage", params: [sourceId: sourceId, run: true], width: 5,
                    style: "margin:8px;"

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
                    title: "Collapse channel list<span class='block text-sm text-color-secondary mt-1'>" +
                        "Just hides or shows the channels below.</span>",
                    defaultValue: anyExisting, submitOnChange: true,
                    styleClass: "reolink-channel-toolbar"
                def channelListHidden = settings["hideChannelList_${sourceId}"] ?: false

                if (!channelListHidden) {
                    if (channelCount == 0 && !state.lastDiscoveryError) {
                        paragraph "No channels found. Check the source's connection and re-run discovery."
                    }
                    lastDiscovery.each { ch ->
                        def dni = childDni(sourceId, ch.channel)
                        def bridgeForList = getSourceBridge(sourceId)
                        def exists = bridgeForList?.getChildDevice(dni) != null
                        // v1.5.2 FIX: this self-healing check is meant for
                        // ONE specific case -- a device that used to exist
                        // and was deleted OUTSIDE this app (see the Tips
                        // page's "Deleting a device the wrong way" topic),
                        // leaving a stale checked box behind. Previously it
                        // fired on ANY device with exists==false and the
                        // checkbox true -- which is ALSO exactly what a
                        // brand-new, never-yet-created channel looks like
                        // the instant the user toggles it on, before Apply
                        // is clicked. Since this checkbox reposts on change
                        // (submitOnChange: true), that meant toggling on a
                        // new channel on a multi-channel Hub/NVR source got
                        // silently reset back to off on the very next
                        // render -- reported as new channels "bouncing
                        // back to unselected" and never becoming
                        // selectable. Gated on state.knownDeviceDnis (see
                        // markDeviceEverCreated()/forgetDeviceEverCreated()
                        // below) so this only fires for a DNI that has
                        // actually been created by this app at some point
                        // -- never for a channel that's simply never been
                        // created yet.
                        def settingKey = "create_${sourceId}_${ch.channel}"
                        def everExisted = (state.knownDeviceDnis ?: []).contains(dni)
                        if (everExisted && !exists && settings[settingKey] == true) {
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
                            forgetDeviceEverCreated(dni)
                        }
                        def doorbellTag = ch.deviceType == "doorbell" ? " (Doorbell)" : ""
                        def statusTag = exists ? "<span class='bg-green-50 text-green-700 text-sm ml-3 px-2 py-1 border-round-xl'>Added</span>" :
                            "<span class='bg-blue-50 text-blue-700 text-sm ml-3 px-2 py-1 border-round-xl'>New</span>"
                        input settingKey, "bool",
                            title: "Ch ${ch.channel}: ${discoveryEscapeHtml(ch.name)}${doorbellTag}${statusTag}",
                            defaultValue: exists, submitOnChange: true, width: 12,
                            styleClass: "reolink-channel-row"
                    }

                    if (channelCount > 1) {
                        paragraph "<span class='text-sm text-color-secondary'>Select the channels you want, " +
                            "then use <b>Apply device changes</b>. Added channels already have a device; New channels do not.</span>"
                        paragraph "<hr class='border-0 border-top-1 border-gray-200 my-2'>"
                        input "applyDeviceChanges_${sourceId}", "button",
                            title: "<i class='fa-solid fa-floppy-disk mr-2' aria-hidden='true'></i>Apply device changes",
                            width: 12
                    } else if (channelCount == 1) {
                        paragraph "<span class='text-sm text-color-secondary'>One channel: changes apply immediately. " +
                            "Turn on to create the device; turn off to remove it.</span>"
                    }
                } else {
                    paragraph "<span class='text-color-secondary text-xs'>Channel list collapsed -- ${channelCount} " +
                        "channel(s) found. Toggle the box above to expand it.</span>"
                }
                paragraph rawHtml: true, warningMessageHtml("<div class='font-semibold'>Remove devices here, not from Hubitat's Devices page.</div>" +
                    "<div class='text-color-secondary mt-1' style='font-size:14px;'>See Tips &amp; Troubleshooting for details.</div>")
                href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>" +
                        "Tips & Troubleshooting", page: "tipsPage",
                    description: "Known quirks and setup guidance", width: 12, style: "margin:8px;"
            }
            section(title: "<div class='reolink-discovery-heading font-semibold'>Connection</div>" +
                    "<div class='text-color-secondary mt-1' style='font-size:14px;'>Real-time updates from this source.</div>",
                    sectionClass: "reolink-discovery-connection") {
                href name: "findChangedIp", title: "<i class='fa-solid fa-magnifying-glass mr-2' aria-hidden='true'></i>Find changed IP",
                    page: "findChangedIpPage", params: [sourceId: sourceId],
                    description: "Locate this source on the local network"
                href name: "editSource", title: "<i class='fa-solid fa-gear mr-2' aria-hidden='true'></i>Edit connection settings",
                    page: "editSourcePage", params: [sourceId: sourceId, begin: true],
                    description: "IP/hostname, HTTPS port, username, and password"
                input "useEventSubscription_${sourceId}", "bool",
                    title: "Use event-driven updates for this source" +
                        "<span class='block text-sm text-color-secondary mt-1'>Falls back to polling if the connection drops.</span>",
                    defaultValue: true, submitOnChange: true, styleClass: "reolink-connection-toggle"
                def connStatus = state.sourceConnMode?.get(sourceId.toString()) ?: "not started"
                def statusClass = connStatus == "connected" ? "text-green-700" :
                    connStatus == "reconnecting" ? "text-orange-700" : "text-color-secondary"
                paragraph rawHtml: true, "<div class='border-top-1 border-gray-200 pt-3'>" +
                    "<div class='flex gap-3 mb-3'><span class='w-6rem flex-shrink-0'>Status</span>" +
                    "<span class='${statusClass} font-semibold'>&#9679; ${discoveryEscapeHtml(connStatus.capitalize())}</span></div>" +
                    "<div class='flex gap-3'><span class='w-6rem flex-shrink-0'>Source</span>" +
                    "<span>${discoveryEscapeHtml(src.label)} (${discoveryEscapeHtml(src.host)})" +
                    "<span class='block text-sm text-color-secondary mt-1'>${src.isHub ? 'Hub/NVR' : 'Standalone camera'}</span></span></div></div>"
            }
            if (src.isHub) {
                section(sectionClass: "reolink-discovery-recording") {
                    paragraph rawHtml: true, "<div class='reolink-discovery-heading font-semibold'>Recording</div>" +
                        "<div class='text-sm text-color-secondary mt-1'>Configure when each channel records.</div>"
                    href name: "presetsFromDiscover", title: "<i class='pi pi-video mr-2' aria-hidden='true'></i>Recording Presets",
                        description: "Set recording schedules per channel",
                        page: "presetsPage", params: [sourceId: sourceId]
                }
            }
            section(sectionClass: "reolink-discovery-danger bg-red-50 border-1 border-red-200 border-round") {
                paragraph rawHtml: true, "<div class='reolink-discovery-heading font-semibold text-red-700'>Danger zone</div>" +
                    "<div class='text-color-secondary mt-1' style='font-size:14px;'>Remove this source and its devices from Hubitat. " +
                    "You will be asked to confirm before anything is removed.</div>"
                href name: "reviewSourceRemoval", title: "<span class='text-red-700 font-semibold'><i class='fa-solid fa-trash mr-2' aria-hidden='true'></i>Remove source...</span>",
                    description: "Review the source and devices before confirming",
                    page: "removeSourcePage", params: [sourceId: sourceId], width: 12, style: "margin:8px;"
            }
        }
    }
}

/** Draft connection settings are committed only by the explicit Save button. */
def editSourcePage(params) {
    def sourceId = params?.sourceId ?: state.editSourceId
    def src = sourceId != null ? getSource(sourceId) : null
    if (!src) return mainPage()
    if (state.remove("cancelSourceEditRequested")) return discoverPage([sourceId: src.id])
    if (state.editSourceId?.toString() != sourceId.toString()) {
        state.editSourceId = src.id
        state.remove("editSourceMessage")
        app.updateSetting("editHost", [type: "text", value: src.host])
        app.updateSetting("editPort", [type: "number", value: src.port])
        app.updateSetting("editUser", [type: "text", value: src.username])
        app.removeSetting("editPass")
    }
    dynamicPage(name: "editSourcePage", title: "Connection settings - ${discoveryEscapeHtml(src.label)}") {
        section {
            paragraph rawHtml: true, recoveryStylesHtml()
            if (state.editSourceMessage) paragraph state.editSourceMessage
            input "editHost", "text", title: "IP address / hostname", required: true, width: 8
            input "editPort", "number", title: "HTTPS port", range: "1..65535", required: true, width: 4
            input "editUser", "text", title: "Username", required: true
            input "editPass", "password", title: "New password (leave blank to keep current password)"
            paragraph "These settings apply to all devices belonging to this source."
        }
        section(sectionClass: "reolink-recovery-actions") {
            input "saveSourceConnection", "button",
                title: "<i class='fa-regular fa-floppy-disk mr-2' aria-hidden='true'></i>Save changes",
                width: 4, submitOnChange: true, styleClass: "reolink-recovery-primary",
                inputClass: "p-button bg-hubitat-primary-green text-white"
            input "cancelSourceEdit", "button",
                title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, inputClass: "p-button"
        }
    }
}

/** Validates and commits the active connection editor draft. */
private void saveSourceConnection() {
    def src = state.editSourceId != null ? getSource(state.editSourceId) : null
    if (!src) return
    String host = settings.editHost?.toString()?.trim()
    String user = settings.editUser?.toString()?.trim()
    String portText = settings.editPort?.toString()
    if (!host || host =~ /[\s\/:?#@]/ || !user || !portText?.isInteger() ||
        portText.toInteger() < 1 || portText.toInteger() > 65535) {
        state.editSourceMessage = "Enter an IP address or hostname (without a URL), username, and a whole-number HTTPS port from 1 to 65535."
        return
    }
    String password = settings.editPass?.toString() ?: src.password
    boolean changed = src.host != host || src.port?.toString() != portText ||
        src.username != user || src.password != password
    if (changed) applySourceConnection(src, host, portText.toInteger(), user, password)
    app.removeSetting("editPass")
    state.editSourceMessage = "Connection settings saved."
}

/** Reconnects only the latest saved connection revision.
 * @param data sourceId and revision captured at save time
 */
def reconnectEditedSource(data) {
    def src = getSource(data.sourceId)
    if (src && (src.connectionRevision ?: 0) == data.revision && getSourceBridge(data.sourceId)) {
        state.sourceConnMode?.remove(data.sourceId.toString())
        ensureSourceBridge(data.sourceId)
    }
}

/** Recovery UI. Merely opening or refreshing this page never starts a scan.
 * @param params navigation arguments containing sourceId
 * @return Hubitat dynamic page
 */
def findChangedIpPage(params) {
    def sourceId = params?.sourceId ?: state.ipRecovery?.sourceId
    def src = sourceId != null ? getSource(sourceId) : null
    if (!src) return mainPage()
    if (state.ipRecovery?.sourceId?.toString() != src.id.toString()) {
        state.ipRecovery = [sourceId: src.id, revision: src.connectionRevision ?: 0,
            nonce: java.util.UUID.randomUUID().toString(), message: "Search the local network for this source."]
    }
    if (state.ipRecovery?.cancelRequested) return discoverPage([sourceId: src.id])
    def recovery = state.ipRecovery
    boolean busy = recovery.phase in ["searching", "verifying"]
    boolean verified = recovery.phase == "verified"
    boolean hasUid = !!src.identity?.uid
    // Prefer the installed driver type, then cached discovery; never infer type from a user label.
    boolean doorbell = !src.isHub && (childrenForSource(src.id).any { it.name == "Reolink Doorbell" } ||
        (state.lastDiscoverySourceId?.toString() == src.id.toString() &&
            (state.lastDiscovery ?: []).any { it.deviceType == "doorbell" }))
    String icon = src.isHub ? "fa-server" : doorbell ? "fa-bell" : "fa-video"
    String type = src.isHub ? "Hub / NVR" : doorbell ? "Doorbell" : "Standalone camera"
    dynamicPage(name: "findChangedIpPage", title: "Find changed IP", refreshInterval: busy ? 2 : 0) {
        section(sectionClass: "reolink-recovery-summary") {
            paragraph rawHtml: true, recoveryStylesHtml() +
                "<div class='text-color-secondary mb-3'>Reconnect your device without adding it again.</div>" +
                "<div class='reolink-status-heading font-semibold flex align-items-center gap-2'>" +
                "<i class='fa-solid ${icon}' aria-hidden='true'></i><span>${discoveryEscapeHtml(src.label)}</span></div>" +
                "<div class='text-sm text-color-secondary mt-1 mb-3'>${type}</div>" +
                "<div class='reolink-recovery-row'><span>Device UID</span><div>" +
                "<code>${discoveryEscapeHtml(src.identity?.uid ?: 'Not saved yet')}</code>" +
                "<div class='text-sm text-color-secondary mt-1'>Permanent Reolink identifier</div></div></div>" +
                "<div class='reolink-recovery-row'><span>Saved IP</span><code>${discoveryEscapeHtml(src.host)}</code></div>"
        }
        section(sectionClass: "reolink-recovery-status") {
            String tone = verified ? "green" : (!hasUid || recovery.phase == "error") ? "yellow" : "blue"
            String statusIcon = verified ? "fa-circle-check" : busy ? "fa-spinner fa-spin" :
                tone == "yellow" ? "fa-circle-exclamation" : "fa-magnifying-glass"
            String heading = verified ? "Device verified" : !hasUid ? "Device identity not saved" :
                recovery.phase == "searching" ? "Finding your device" : recovery.phase == "verifying" ? "Verifying device identity" :
                recovery.phase == "error" ? "Unable to verify device" : recovery.phase == "done" ? "Search result" : "Ready to locate this device"
            String message = !hasUid ? "Connect this source at its current IP and refresh a device to save its identity. If it has already moved, use Edit connection settings." :
                verified ? "The device UID matches your saved source." : recovery.message ?: "Search your local network for the same device."
            paragraph rawHtml: true, "<div class='bg-${tone}-50 border-1 border-${tone}-200 border-round p-3'>" +
                "<div class='reolink-status-heading font-semibold flex align-items-center gap-2'>" +
                "<i class='fa-solid ${statusIcon} text-${tone}-700' aria-hidden='true'></i><span>${heading}</span></div>" +
                (verified ? "<div class='flex align-items-center flex-wrap gap-3 mt-3'>" +
                    "<div><div class='text-sm text-color-secondary mb-1'>Saved IP</div><code>${discoveryEscapeHtml(src.host)}</code></div>" +
                    "<i class='fa-solid fa-arrow-right text-color-secondary' aria-hidden='true'></i>" +
                    "<div><div class='text-sm text-color-secondary mb-1'>New IP</div><code class='font-semibold'>${discoveryEscapeHtml(recovery.host)}</code></div></div>" : "") +
                "<div class='text-color-secondary mt-2'>${discoveryEscapeHtml(message)}</div></div>"
        }
        section(sectionClass: "reolink-recovery-actions") {
            // Use native button inputs and the same classes as the recording preset actions.
            if (verified) {
                input "applyIp_${recovery.nonce}", "button", title: "<i class='fa-solid fa-check mr-2' aria-hidden='true'></i>Use this IP",
                    width: 4, submitOnChange: true, styleClass: "reolink-recovery-primary", inputClass: "p-button bg-hubitat-primary-green text-white"
            }
            if (hasUid) {
                input "findIp_${recovery.nonce}", "button", title: "<i class='fa-solid ${verified ? 'fa-rotate-right' : 'fa-magnifying-glass'} mr-2' aria-hidden='true'></i>${verified ? 'Search again' : 'Search LAN'}",
                    width: 4, submitOnChange: true, disabled: busy, styleClass: verified ? "reolink-recovery-secondary" : "reolink-recovery-primary",
                    inputClass: verified ? "p-button p-button-outlined" : "p-button bg-hubitat-primary-green text-white"
            }
            input "cancelIp_${recovery.nonce}", "button", title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, disabled: busy, inputClass: "p-button"
        }
        section(sectionClass: "reolink-recovery-footer") {
            paragraph rawHtml: true, "<div class='border-top-1 border-gray-200 pt-3 text-sm text-color-secondary'>" +
                "${verified ? 'Your devices, labels, and automations stay in place.' : 'Devices must be on the same local network. Sleeping devices or devices on other VLANs may not respond.'}</div>"
        }
    }
}

/** Matches the main/discovery page spacing and the preset page's native button styling.
 * @return scoped recovery page styles
 */
private String recoveryStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  ${tileSecondaryTextCss()}
  .reolink-recovery-summary > .mdl-grid, .reolink-recovery-status > .mdl-grid,
  .reolink-recovery-actions > .mdl-grid, .reolink-recovery-footer > .mdl-grid { padding: 4px 0 !important; }
  .reolink-recovery-summary .reolink-status-heading i, .reolink-recovery-status .reolink-status-heading i { font-size: 1em; }
  .reolink-recovery-row { display: grid; grid-template-columns: 140px minmax(0, 1fr); gap: 16px; padding: 14px 0; border-top: 1px solid #e0e0e0; }
  .reolink-recovery-summary code, .reolink-recovery-status code { font-size: 16px; overflow-wrap: anywhere; }
  .reolink-recovery-actions button { min-width: 128px; min-height: 40px; padding: 0 16px; border-radius: 4px; font-family: inherit; font-size: 14px; }
  .reolink-recovery-primary button { background: var(--hubitat-primary-green, #81BC00) !important; color: #fff !important; }
  .reolink-recovery-secondary button { background: #fff; color: #1565c0; border: 1px solid #b0bec5; box-shadow: none; }
  .reolink-recovery-actions button:disabled { opacity: 0.6; cursor: not-allowed; }
  .reolink-recovery-actions button:focus-visible { outline: 2px solid #1565c0; outline-offset: 2px; }
  @media (max-width: 480px) { .reolink-recovery-row { grid-template-columns: 1fr; gap: 6px; } }
</style>
"""
}

/** Starts an explicit bounded scan without exposing credentials to the hub helper. */
private void startIpRecovery() {
    def recovery = state.ipRecovery
    def src = recovery ? getSource(recovery.sourceId) : null
    if (!src?.identity?.uid) return
    if (recovery.phase in ["searching", "verifying"]) return
    recovery.nonce = java.util.UUID.randomUUID().toString()
    recovery.revision = src.connectionRevision ?: 0
    recovery.remove("host")
    try {
        recovery.scanId = hubitat.helper.NetworkUtils.startReolinkDiscovery()
        recovery.phase = "searching"
        recovery.message = "Searching the local network..."
        recovery.startedAt = now()
        state.ipRecovery = recovery
        runIn(4, "completeIpRecovery", [data: [nonce: recovery.nonce, scanId: recovery.scanId]])
    } catch (MissingMethodException ignored) {
        recovery.phase = "error"
        recovery.message = "This hub firmware does not support Reolink LAN discovery. Use Edit connection settings."
        state.ipRecovery = recovery
    } catch (Exception ignored) {
        recovery.phase = "error"
        recovery.message = "LAN discovery could not start. Try again or use Edit connection settings."
        state.ipRecovery = recovery
    }
}

/** Completes a scan, matching UID before attempting authentication.
 * @param data scheduled nonce and scanId identifying the active recovery request
 */
def completeIpRecovery(data) {
    def recovery = state.ipRecovery
    if (!recovery || recovery.nonce != data.nonce || recovery.scanId != data.scanId) return
    def src = getSource(recovery.sourceId)
    if (!src || (src.connectionRevision ?: 0) != recovery.revision) return
    try {
        def scan = hubitat.helper.NetworkUtils.getReolinkDiscoveryStatus(recovery.scanId)
        if (scan.status == "running" && now() - recovery.startedAt < 15000L) {
            runIn(2, "completeIpRecovery", [data: data])
            return
        }
        def matches = (scan.candidates ?: []).findAll { normalizeSourceUid(it.uid) == src.identity?.uid }
        def hosts = matches.collect { it.host }.unique()
        recovery.phase = "done"
        if (scan.status != "complete") {
            recovery.message = scan.errorCode == "reply_port_in_use" ?
                "UDP port 3000 is already in use. Close Reolink Client if it is running on the same computer as this hub, wait 10 seconds, then search again." :
                "Discovery failed or expired. Try again."
        } else if (hosts.size() != 1) {
            recovery.message = hosts ? "Multiple addresses claim this device identity. No settings were changed." :
                "No matching device found. Check power, network, and VLAN settings, or edit the IP manually."
        } else if (hosts[0] == src.host) {
            recovery.message = "The device still reports its saved IP address. No address change is needed."
        } else {
            recovery.phase = "verifying"
            recovery.message = "Checking the matching device..."
            state.ipRecovery = recovery
            String uid = verifyCandidateIdentity(src, hosts[0])
            // The user may have cancelled or edited the source during the HTTP request.
            if (state.ipRecovery?.nonce != data.nonce || state.ipRecovery?.scanId != data.scanId ||
                (getSource(src.id)?.connectionRevision ?: 0) != recovery.revision) return
            recovery.phase = uid == src.identity.uid ? "verified" : "error"
            recovery.message = uid == src.identity.uid ? "Device identity verified. Choose Use this IP to save it." :
                "Could not verify this device with the saved HTTPS port and credentials. No settings were changed."
            if (uid == src.identity.uid) {
                recovery.host = hosts[0]
                recovery.verifiedAt = now()
            }
        }
    } catch (Exception ignored) {
        recovery.phase = "error"
        recovery.message = "Discovery could not complete. No settings were changed."
    }
    if (state.ipRecovery?.nonce == data.nonce && state.ipRecovery?.scanId == data.scanId) state.ipRecovery = recovery
}

/** Rechecks identity and commits only the IP of the current, unexpired result. */
private void applyRecoveredIp() {
    def recovery = state.ipRecovery
    def src = recovery ? getSource(recovery.sourceId) : null
    if (!src || recovery.phase != "verified") return
    if ((src.connectionRevision ?: 0) != recovery.revision || now() - recovery.verifiedAt > 60000L) {
        recovery.phase = "error"
        recovery.message = "This result has expired or connection settings changed. Search again."
        state.ipRecovery = recovery
        return
    }
    recovery.phase = "verifying"
    recovery.message = "Verifying the address before saving..."
    state.ipRecovery = recovery
    String uid = verifyCandidateIdentity(src, recovery.host)
    def current = getSource(src.id)
    if (!current || state.ipRecovery?.nonce != recovery.nonce || state.ipRecovery?.scanId != recovery.scanId ||
        (current.connectionRevision ?: 0) != recovery.revision) return
    recovery.phase = "done"
    if (uid != src.identity?.uid) {
        recovery.message = "Device verification failed. No settings were changed."
    } else {
        applySourceConnection(current, recovery.host, current.port as Integer, current.username, current.password)
        recovery.message = "IP address updated. Reconnecting existing devices."
    }
    state.ipRecovery = recovery
}

/** Normalizes a Reolink UID without accepting names or arbitrary response text.
 * @param value UID returned by a device
 * @return normalized UID, or null for an invalid/missing value
 */
private String normalizeSourceUid(value) {
    String uid = value?.toString()?.trim()?.toUpperCase()
    return uid && uid ==~ /[A-Z0-9]{6,64}/ ? uid : null
}

/** Makes an isolated request without changing source tokens or availability state.
 * @param src temporary connection configuration
 * @param cmd Reolink command
 * @param param command parameters
 * @param token optional temporary authentication token
 * @return command value map, or null on failure
 */
private Map recoveryRequest(src, String cmd, Map param = [:], String token = null) {
    def result = null
    try {
        String uri = "https://${src.host}:${src.port}/cgi-bin/api.cgi?cmd=${cmd}"
        if (token) uri += "&token=${java.net.URLEncoder.encode(token, 'UTF-8')}"
        def command = [cmd: cmd, param: param]
        if (!(cmd in ["Login", "Logout"])) command.action = 0
        httpPost([uri: uri, ignoreSSLIssues: true, requestContentType: "application/json", timeout: 5,
            body: groovy.json.JsonOutput.toJson([command])]) { response ->
            def parsed = parseReolinkResponse(response)
            if (parsed instanceof List && parsed && parsed[0]?.code == 0 && parsed[0]?.value instanceof Map)
                result = parsed[0].value
        }
    } catch (Exception ignored) { /* Candidate failures must not mark the saved source unreachable. */ }
    return result
}

/** Authenticates only a UID-matched candidate and reads its source UID.
 * @param src saved source configuration, never modified
 * @param host candidate IPv4 address
 * @return authenticated UID, or null if login/identity retrieval failed
 */
private String verifyCandidateIdentity(src, String host) {
    if (!(host ==~ /(?:\d{1,3}\.){3}\d{1,3}/) || host.tokenize('.').any { it.toInteger() > 255 }) return null
    def candidate = [host: host, port: src.port]
    String token = recoveryRequest(candidate, "Login", [User: [userName: src.username, password: src.password]])?.Token?.name
    if (!token) return null
    try {
        return normalizeSourceUid(recoveryRequest(candidate, "GetP2p", [:], token)?.P2p?.uid)
    } finally {
        recoveryRequest(candidate, "Logout", [:], token)
    }
}

/** Persists connection changes while preserving device identity, IDs, labels, and port preferences.
 * @param src source being updated
 * @param host new hostname/IP
 * @param port HTTPS port
 * @param username login username
 * @param password login password
 */
private void applySourceConnection(src, String host, Integer port, String username, String password) {
    def bridge = getSourceBridge(src.id)
    if (bridge) bridge.stopEventSubscription()
    src.host = host
    src.port = port
    src.username = username
    src.password = password
    src.token = null
    src.tokenExpires = 0
    src.connectionRevision = (src.connectionRevision ?: 0) + 1
    state.sources = (state.sources ?: []).collect { it.id == src.id ? src : it }
    state.sourceUnreachable?.remove(src.id.toString())
    childrenForSource(src.id).each { child ->
        try { configureRtspChild(child, src, child.getDataValue("channel")) }
        catch (Exception ignored) { log.warn "Reolink source ${src.id}: stream refresh failed; refresh the device to retry" }
        markPollDueNow(child.deviceNetworkId)
        markSnapshotDueNow(child.deviceNetworkId)
    }
    if (bridge) runIn(3, "reconnectEditedSource", [overwrite: false,
        data: [sourceId: src.id, revision: src.connectionRevision]])
}

/** Two-step removal, bound to the source reviewed on this confirmation page. */
def removeSourcePage(params) {
    def sourceId = params?.sourceId
    def src = sourceId != null ? getSource(sourceId as Integer) : null
    if (!src) {
        state.remove("pendingSourceRemoval")
        return mainPage()
    }
    if (params?.action == "cancel") {
        state.remove("pendingSourceRemoval")
        return discoverPage([sourceId: sourceId])
    }
    def pending = state.pendingSourceRemoval
    if (params?.action == "remove" && pending?.sourceId?.toString() == sourceId.toString() &&
        pending?.token && params?.token == pending.token) {
        // Consume before deleting so refresh/back cannot repeat a confirmed operation.
        state.remove("pendingSourceRemoval")
        removeSource(sourceId)
        return mainPage()
    }

    String token = java.util.UUID.randomUUID().toString()
    state.pendingSourceRemoval = [sourceId: sourceId.toString(), token: token]
    def devices = childrenForSource(sourceId as Integer)
    dynamicPage(name: "removeSourcePage", title: "Confirm source removal") {
        section(sectionClass: "reolink-removal-confirmation") {
            paragraph rawHtml: true, "<style>${tileSecondaryTextCss()} #formApp:has(.reolink-removal-confirmation) #fieldsetAppButtons button[value='Done'] { display: none !important; }</style>" +
                "<div class='bg-red-50 border-1 border-red-200 border-round p-3'>" +
                "<div class='font-semibold text-red-700'>Remove ${discoveryEscapeHtml(src.label)}?</div>" +
                "<div class='mt-2'>${discoveryEscapeHtml(src.host)} &middot; ${countText(devices.size(), 'device')}</div>" +
                "<div class='mt-2'>This removes the source, its bridge, all devices listed below, and its saved " +
                "recording presets from Hubitat. It cannot be undone. The physical cameras are not reset.</div></div>"
            if (devices) {
                paragraph rawHtml: true, "<ul>" + devices.collect { device ->
                    "<li>${discoveryEscapeHtml(device.label ?: device.name)}</li>"
                }.join("") + "</ul>"
            }
            href name: "cancelSourceRemoval", title: "Cancel and keep this source",
                description: "Return without removing anything", page: "removeSourcePage",
                params: [sourceId: sourceId, action: "cancel"]
            href name: "confirmSourceRemoval", title: "<span class='text-red-700 font-semibold'>Yes, remove this source</span>",
                description: "Permanently remove ${countText(devices.size(), 'device')} and this source",
                page: "removeSourcePage", params: [sourceId: sourceId, action: "remove", token: token]
        }
    }
}

/** Display-only markup; native Hubitat inputs and href actions remain in their sections. */
private String discoveryOverviewHtml(int channelCount, int deviceCount, boolean discoveryFailed) {
    String tone = discoveryFailed ? "p-message p-message-warn reolink-message" : "bg-green-50 border-green-200"
    String icon = discoveryFailed ? "fa-solid fa-exclamation-triangle text-yellow-700" : "pi pi-check-circle text-green-700"
    String title = discoveryFailed ? "Discovery needs attention" : channelCount ? "Discovery complete" : "No channels found"
    return """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
  .reolink-discovery-heading { font-size: 16px; }
  .reolink-discovery-summary { margin-bottom: 8px !important; }
  .reolink-discovery-channels > .mdl-grid,
  .reolink-discovery-recording > .mdl-grid,
  .reolink-discovery-danger > .mdl-grid { padding: 4px 0 !important; }
  .reolink-discovery-channels { float: left; width: calc(62% - 8px); }
  .reolink-discovery-connection, .reolink-discovery-recording {
    float: right; width: 38%; border-left: 1px solid #e0e0e0; padding-left: 8px; box-sizing: border-box;
  }
  .reolink-discovery-recording { clear: right; }
  .reolink-discovery-danger {
    clear: both; padding: 0 8px; position: relative; box-sizing: border-box;
    width: calc(100% - 16px); margin: 0 8px 16px !important;
  }
  .reolink-discovery-danger::before {
    content: ''; position: absolute; left: 0; right: 0; top: -9px; border-top: 1px solid #dfe3e8;
  }
  .reolink-discovery-connection > .mdl-grid {
    margin: 12px 8px 0; padding: 4px !important; border: 1px solid #dfe3e8;
    border-radius: 4px; background: #fff;
  }
  .reolink-channel-toolbar {
    background: #f7f8fa; border: 1px solid #dfe3e8; border-radius: 4px 4px 0 0;
    padding: 12px; margin-bottom: 0;
  }
  .reolink-channel-toolbar:not(:has(~ .reolink-channel-row)) { border-radius: 4px; margin-bottom: 8px; }
  .reolink-channel-row {
    background: #fafbfc; border: 1px solid #dfe3e8; border-top: 0;
    padding: 8px; margin-top: 0; margin-bottom: 0;
  }
  .reolink-channel-row:not(:has(~ .reolink-channel-row)) { border-radius: 0 0 4px 4px; margin-bottom: 8px; }
  .reolink-channel-row > .w-fit {
    width: 100% !important; box-sizing: border-box; background: #fff;
    border: 1px solid #dfe3e8; border-radius: 4px; padding: 10px 12px;
  }
  .reolink-channel-row .mdl-switch, .reolink-channel-toolbar .mdl-switch,
  .reolink-connection-toggle .mdl-switch { height: auto; min-height: 24px; }
  .reolink-channel-row .mdl-switch__label { line-height: 24px; }
  .reolink-discovery-danger { background: #fff5f5 !important; border-color: #edb7bb !important; }
  .reolink-discovery-summary .bg-green-50 { background: #f3faf1 !important; border-color: #bdd8b1 !important; }
  .reolink-discovery-channels button.hrefElem {
    background: #fff; color: #1565c0; border: 1px solid #e0e0e0;
    border-radius: 4px; box-shadow: none;
  }
  .reolink-discovery-channels button[name^='_action_href_tips|'] {
    height: 62px; box-sizing: border-box;
  }
  .reolink-discovery-channels button[name^='_action_href_tips|'] > .state-incomplete-text {
    display: inline-block; padding-bottom: 0.5em;
  }
  .reolink-discovery-channels button.hrefElem .state-incomplete-text { font-size: 14px; }
  .reolink-discovery-channels button.hrefElem::before { color: #1565c0; }
  .reolink-discovery-channels .mdl-cell:has(> button[name^='_action_href_runDiscovery|']) { text-align: right; }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|'] {
    display: inline-block; width: auto !important; min-height: 36px; padding: 0 16px;
    background: rgba(158,158,158,0.2); color: #363636; border: 0; border-radius: 4px;
    font-family: inherit; font-size: 14px; font-weight: 500; line-height: 36px;
    box-shadow: 0 2px 2px 0 rgba(0,0,0,.14), 0 3px 1px -2px rgba(0,0,0,.2), 0 1px 5px 0 rgba(0,0,0,.12);
  }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|']::before,
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|'] > br,
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|'] > .state-incomplete-text { display: none; }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|']:hover { background: #cacfc8; }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|']:focus-visible { outline: 2px solid #386a95; outline-offset: 2px; }
  @media (max-width: 1000px) {
    .reolink-discovery-channels, .reolink-discovery-connection, .reolink-discovery-recording {
      float: none; width: 100%; border-left: 0; padding-left: 0;
    }
  }
</style>
<div class='flex align-items-center gap-3 ${tone} border-1 border-round p-3'>
  <div class='flex-shrink-0'><i class='${icon} text-2xl' aria-hidden='true'></i></div>
  <div class='min-w-0'><div class='reolink-status-heading font-semibold'>${title}</div>
    <div class='text-color-secondary mt-1' style='font-size:14px;'>${countText(channelCount, 'channel')} found &middot; ${countText(deviceCount, 'device')} added</div>
    ${discoveryFailed ? "<div class='mt-2' style='font-size:14px;'><b>Check Reolink Server Settings:</b> HTTPS must be enabled, and the app's configured HTTPS port must match. HTTP, RTMP, RTSP, and ONVIF are not required by this integration.<br>Real-time events also use TCP port 9000; allow Hubitat to reach it on your local network. If unavailable, the app falls back to polling.</div>" : ""}
  </div>
</div>
"""
}

private String discoveryEscapeHtml(Object value) {
    (value == null ? "" : value.toString()).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace('"', "&quot;").replace("'", "&#39;")
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
    // rendering, before building the updated editor.
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

    // Presentation-only selection; does not load or modify a recording preset.
    def selectedBySource = state.presetsUiSelection ?: [:]
    def selectedPreset = params?.preset ?: selectedBySource[sourceId.toString()]
    if (!presets.containsKey(selectedPreset)) selectedPreset = presets.keySet().find { true }
    selectedBySource[sourceId.toString()] = selectedPreset
    state.presetsUiSelection = selectedBySource

    dynamicPage(name: "presetsPage", title: "Recording Presets - ${src?.label ?: ''}",
        nextPage: "discoverPage", nextPageLabel: "Done") {

        section(sectionClass: "reolink-presets-index") {
            paragraph rawHtml: true, presetsStylesHtml()
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>Presets</div>"
            if (!presets) paragraph "<span class='text-base text-color-secondary'>No presets yet. Add one below to get started.</span>"
            presets.keySet().eachWithIndex { name, index ->
                def btnNum = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()]?.get(name)
                href name: "selectPreset_${index}", page: "presetsPage",
                    params: [sourceId: sourceId, preset: name],
                    title: "<span class='text-blue-700 ${name == selectedPreset ? 'reolink-preset-selected' : ''}'>${discoveryEscapeHtml(name)}</span>",
                    description: btnNum ? "Button ${btnNum}" : "", width: 12, style: "margin:0 8px;"
            }
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold border-top-1 border-gray-200 pt-3 mt-3'>Add a preset</div>"
            input "newPresetName", "text", title: "New preset name (e.g. 'Away', 'Present')", submitOnChange: true
        }
        presets.each { name, chMap ->
            def btnNum = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()]?.get(name)
            section(sectionClass: "reolink-preset-editor ${name == selectedPreset ? '' : 'reolink-preset-hidden'}") {
                paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>${discoveryEscapeHtml(name)} " +
                    (btnNum ? "<span class='text-base text-blue-700 bg-blue-50 border-round px-2 py-1 ml-2'>Button ${btnNum}</span>" : "") +
                    "</div><div class='text-base text-color-secondary mt-1'>Editing or saving this preset does not load it.</div>"
                paragraph rawHtml: true, "<div class='reolink-preset-card-heading font-semibold'>Channel recording modes</div>"
                if (!channels) paragraph "No devices have been added for this source. Add devices on Discover Channels to configure their recording modes."

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
                            paragraph "<i class='pi pi-lock text-color-secondary mr-2'></i><b>${discoveryEscapeHtml(ch.label ?: ch.name)} (ch ${channelNum})</b> -- excluded from all " +
                                "presets (locked on the device's own preferences page). This preset will never " +
                                "write a schedule to it."
                            return
                        }
                        def key = "preset_${sourceId}_${name}_${channelNum}"
                        input key, "text", title: "${discoveryEscapeHtml(ch.label ?: ch.name)} (ch ${channelNum})",
                            styleClass: "reolink-preset-channel",
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
                            paragraph "<i class='pi pi-lock text-color-secondary mr-2'></i><b>${discoveryEscapeHtml(ch.label ?: ch.name)} (ch ${channelNum})</b> -- excluded from all " +
                                "presets (locked on the device's own preferences page). This preset will never " +
                                "write a schedule to it."
                            return
                        }
                        def simpleCfg = simpleForPreset[channelNum]
                        def modeKey = "presetMode_${sourceId}_${name}_${channelNum}"
                        def currentMode = settings[modeKey] ?: simpleCfg?.mode ?: REC_MODE_OFF
                        input modeKey, "enum", title: "${discoveryEscapeHtml(ch.label ?: ch.name)} (ch ${channelNum})",
                            styleClass: "reolink-preset-channel",
                            options: [REC_MODE_OFF, REC_MODE_NEVER, REC_MODE_CONTINUOUS, REC_MODE_RANGE],
                            defaultValue: simpleCfg?.mode ?: REC_MODE_OFF, submitOnChange: true
                        if (currentMode == REC_MODE_RANGE) {
                            def defaultStart = simpleCfg?.start != null ? REC_HOUR_LABELS[simpleCfg.start as Integer] : REC_HOUR_LABELS[18]
                            def defaultEnd = simpleCfg?.end != null ? REC_HOUR_LABELS[simpleCfg.end as Integer] : REC_HOUR_LABELS[6]
                            input "presetStart_${sourceId}_${name}_${channelNum}", "enum",
                                title: "Start recording at", width: 6,
                                options: REC_HOUR_LABELS, defaultValue: defaultStart, submitOnChange: true
                            input "presetEnd_${sourceId}_${name}_${channelNum}", "enum",
                                title: "Stop recording at", width: 6,
                                options: REC_HOUR_LABELS, defaultValue: defaultEnd, submitOnChange: true
                            paragraph "<span class='text-color-secondary text-base'>Same " +
                                "window every day. An end time earlier than the start time (e.g. 6:00 PM to " +
                                "6:00 AM) is treated as overnight, wrapping past midnight.</span>"
                        }
                    }
                }
            }
        }

        section(sectionClass: "reolink-presets-help") {
            if (!presets) {
                paragraph rawHtml: true, "<div class='border-1 border-gray-200 border-round p-3'><div class='reolink-status-heading font-semibold'>Create your first preset</div>" +
                    "<div class='text-base text-color-secondary mt-2'>Enter a name on the left, then configure what each channel records. New channels default to Don't manage, leaving their existing schedules untouched.</div></div>"
            }
            paragraph rawHtml: true, presetsGuidanceHtml(src?.label ?: "")
            input "advancedScheduleEditing_${sourceId}", "bool",
                title: "Advanced: edit raw per-hour schedule strings directly (power users only)",
                defaultValue: false, submitOnChange: true
            if (advancedMode) {
                paragraph "<span class='inline-block bg-orange-50 text-orange-700 font-bold text-xs px-2 py-1 " +
                    "border-round-xl mr-2'><i class='fa-solid fa-exclamation-triangle mr-2' aria-hidden='true'></i>WARNING</span>" +
                    "You're editing raw 168-character schedule strings (one digit per hour of the week, " +
                    "Sunday 12am first, 1=record/0=don't) instead of the simple picker. Almost nobody needs " +
                    "this -- it exists only for a schedule the simple picker can't express, like different " +
                    "hours on different days. A malformed string is rejected on save (exact length, only 0/1 " +
                    "characters), but a well-formed WRONG string will be written to your NVR exactly as typed."
            }
        }

        if (selectedPreset != null) {
            def selectedButton = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()]?.get(selectedPreset)
            section(sectionClass: "reolink-presets-actions") {
                paragraph rawHtml: true, "<div class='text-base text-color-secondary'>Actions for ${discoveryEscapeHtml(selectedPreset)}</div>"
                input "presetAction_delete_${sourceId}_${selectedButton}", "button",
                    title: "<i class='fa-regular fa-trash mr-2' aria-hidden='true'></i>Delete preset",
                    width: 6, submitOnChange: true, styleClass: "reolink-preset-delete", inputClass: "p-button p-button-danger"
                input "presetAction_save_${sourceId}_${selectedButton}", "button",
                    title: "<i class='fa-regular fa-floppy-disk mr-2' aria-hidden='true'></i>Save preset",
                    width: 6, submitOnChange: true, styleClass: "reolink-preset-save", inputClass: "p-button bg-hubitat-primary-green text-white"
            }
        }
    }
}

/** Dispatches source recovery, connection editing, and recording preset actions.
 * @param buttonName native button name, including a recovery nonce where applicable
 */
void appButtonHandler(String buttonName) {
    def deviceAction = buttonName =~ /^applyDeviceChanges_(\d+)$/
    if (deviceAction.matches()) {
        String sourceId = deviceAction[0][1]
        if (sourceId == state.currentDiscoverySourceId?.toString() &&
            sourceId == state.lastDiscoverySourceId?.toString()) {
            state.pendingDeviceSourceId = sourceId
        }
        return
    }
    if (buttonName == "cancelSourceDiscovery") {
        state.cancelSourceDiscoveryRequested = true
        return
    }
    if (buttonName in ["confirmAddSource", "cancelAddSource"]) {
        state.addSourceAction = buttonName == "confirmAddSource" ? "add" : "cancel"
        return
    }
    if (buttonName == "rescanSources") {
        startSourceDiscovery()
        return
    }
    if (buttonName == "cancelSourceEdit") {
        state.cancelSourceEditRequested = true
        return
    }
    if (state.ipRecovery && buttonName == "cancelIp_${state.ipRecovery.nonce}") {
        if (!(state.ipRecovery.phase in ["searching", "verifying"])) {
            def recovery = state.ipRecovery
            recovery.cancelRequested = true
            state.ipRecovery = recovery
        }
        return
    }
    if (state.ipRecovery && buttonName == "findIp_${state.ipRecovery.nonce}") {
        startIpRecovery()
        return
    }
    if (state.ipRecovery && buttonName == "applyIp_${state.ipRecovery.nonce}") {
        applyRecoveredIp()
        return
    }

    if (buttonName == "saveSourceConnection") {
        saveSourceConnection()
        return
    }
    def match = buttonName =~ /^presetAction_(save|delete)_(\d+)_(\d+)$/
    if (!match.matches()) return
    String action = match[0][1]
    String sourceId = match[0][2]
    Integer buttonNumber = match[0][3] as Integer
    // Resolve the permanent button number, rather than embedding a user-entered preset name.
    def presetName = (state.recPresetButtonNumbers ?: [:])[sourceId]?.find { name, number ->
        number == buttonNumber
    }?.key
    if (presetName == null || !(state.recPresets ?: [:])[sourceId]?.containsKey(presetName)) return
    if (sourceId != state.currentPresetsSourceId?.toString()) return
    app.updateSetting("${action == 'save' ? 'savePreset' : 'deletePreset'}_${presetName}",
        [type: "bool", value: true])
}

/** Preset navigation and styling only; recording handlers remain in presetsPage(). */
private String presetsStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  .reolink-presets-index { float: left; width: calc(27% - 8px); box-sizing: border-box; }
  .reolink-preset-editor, .reolink-presets-help, .reolink-presets-actions {
    float: right; width: 73%; padding-left: 8px; border-left: 1px solid #e0e0e0; box-sizing: border-box;
  }
  .reolink-presets-help, .reolink-presets-actions { clear: right; }
  .reolink-preset-hidden { display: none !important; }
  .reolink-presets-index > .mdl-grid, .reolink-preset-editor > .mdl-grid,
  .reolink-presets-help > .mdl-grid, .reolink-presets-actions > .mdl-grid { padding: 4px 0 !important; }
  .reolink-presets-index .mdl-cell:has(> style) { display: none; }
  .reolink-presets-index button.hrefElem {
    background: transparent; border: 0; border-left: 3px solid transparent;
    border-radius: 0; box-shadow: none; padding: 12px; font-family: inherit; font-size: 16px;
  }
  .reolink-presets-index button.hrefElem::before { display: none; }
  .reolink-presets-index button.hrefElem:has(.reolink-preset-selected) {
    background: #eaf2fc; border-left-color: #1565c0;
  }
  .reolink-presets-index button.hrefElem:hover { background: #f3f6fa; }
  .reolink-presets-index button.hrefElem:focus-visible { outline: 2px solid #1565c0; outline-offset: 2px; }
  .reolink-preset-card-heading {
    font-size: 16px; background: #f5f7fa; border: 1px solid #dfe3e8; border-radius: 4px; padding: 12px;
  }
  .reolink-preset-channel {
    background: #fafbfc; border: 1px solid #dfe3e8; border-radius: 4px; padding: 12px; box-sizing: border-box;
  }
  .reolink-presets-actions > .mdl-grid { border-top: 1px solid #e0e0e0; margin: 0 8px; }
  .reolink-preset-save { text-align: right; }
  .reolink-preset-save button { background: var(--hubitat-primary-green, #81BC00) !important; color: #fff !important; border-radius: 4px; }
  .reolink-preset-delete button { background: #D32F2F !important; color: #fff !important; border-radius: 4px; }
  .reolink-preset-editor .mdl-switch, .reolink-presets-help .mdl-switch { height: auto; min-height: 24px; }
  .reolink-preset-editor .mdl-switch__label, .reolink-presets-help .mdl-switch__label { line-height: 24px; }
  .reolink-preset-guidance { border: 1px solid #dfe3e8; border-radius: 4px; margin-top: 16px; }
  .reolink-preset-guidance summary { padding: 12px; background: #f5f7fa; cursor: pointer; list-style: none; font-size: 16px; }
  .reolink-preset-guidance summary::-webkit-details-marker { display: none; }
  .reolink-preset-guidance summary::after { content: '\\203A'; float: right; color: #386a95; }
  .reolink-preset-guidance[open] summary::after { transform: rotate(90deg); }
  .reolink-preset-guidance p { margin: 0 0 12px; }
  .reolink-preset-guidance p:last-child { margin-bottom: 0; }
  #formApp:has(.reolink-presets-index) #fieldsetAppButtons { clear: both; }
  @media (max-width: 1000px) {
    .reolink-presets-index, .reolink-preset-editor, .reolink-presets-help, .reolink-presets-actions {
      float: none; width: 100%; padding: 0; border-left: 0;
    }
  }
</style>
"""
}

private String presetsGuidanceHtml(String sourceLabel) {
    """
<div class='p-message p-message-info reolink-message'>
  <div class='reolink-status-heading font-semibold text-blue-700'><i class='pi pi-cog mr-2' aria-hidden='true'></i>Rule Machine shortcuts</div>
  <div class='text-base mt-2'>Use <b>Reolink Device Bridge (${discoveryEscapeHtml(sourceLabel)})</b> in Rule Machine's standard Switch and Button pickers. No Custom Action is needed.</div>
  <div class='text-base mt-2'><b>Switch on/off:</b> controls master recording for all channels.<br><b>Push button N:</b> loads the preset labeled Button N.</div>
  <div class='text-base mt-2'>You can also use the bridge's <b>Load Selected Preset</b> command. Button numbers are permanent and never reused; a deleted preset's button does nothing.</div>
</div>
<details class='reolink-preset-guidance'>
  <summary class='font-semibold'><i class='pi pi-book mr-2' aria-hidden='true'></i>Recording modes &amp; guidance</summary>
  <div class='p-3 text-base'>
    <p><b>Don't manage (default):</b> leaves the channel's existing schedule untouched. Use this to let Reolink manage a channel, or to leave a battery-class channel out of a preset intended for wired cameras.</p>
    <p><b>Never record:</b> actively writes a silent, all-zero recording schedule. This is different from Don't manage.</p>
    <p><b>Continuous:</b> records throughout the day.</p>
    <p><b>Daily time window:</b> records during the same hours every day. An end time earlier than the start time wraps past midnight, such as 6:00 PM to 6:00 AM.</p>
    <p>Saving a preset stores its configuration. Loading it through the bridge or Rule Machine applies its per-channel schedules to the source.</p>
  </div>
</details>
${warningMessageHtml("The master recording switch affects all channels at once. Normally, leave it on and use presets to control each channel's recording schedule.", "mt-3 text-base")}
"""
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

/**
 * v1.5.2: records that this DNI has actually been created by this app at
 * least once -- see discoverPage()'s self-heal check for why this registry
 * exists (distinguishing "existed, then deleted externally" from "never
 * created yet"). Called the moment createSelectedChildren() actually
 * creates a channel device.
 */
private void markDeviceEverCreated(String dni) {
    def known = state.knownDeviceDnis ?: []
    if (!known.contains(dni)) {
        known << dni
        state.knownDeviceDnis = known
    }
}

/**
 * v1.5.2: counterpart to markDeviceEverCreated() above -- called both on an
 * intentional removal (createSelectedChildren()'s uncheck-and-apply path,
 * so re-adding that same channel later is treated as a fresh add, not a
 * stale-checkbox case) and on full source removal (removeSource()).
 */
private void forgetDeviceEverCreated(String dni) {
    def known = state.knownDeviceDnis ?: []
    known.remove(dni)
    state.knownDeviceDnis = known
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
        bridge.getChildDevices()?.each {
            forgetSchedulingState(it.deviceNetworkId)
            forgetDeviceEverCreated(it.deviceNetworkId)
        }
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

/** Authenticates the source and captures or verifies its stable UID.
 * @param sourceId saved source identifier
 * @return token, or null if authentication or identity verification fails
 */
private String reolinkLogin(sourceId) {
    def src = getSource(sourceId)
    if (src.token && now() < src.tokenExpires &&
        (src.identity?.uid || (src.identityCheckedAt && now() - src.identityCheckedAt < 3600000L))) {
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
        // Learn the physical source UID on fresh login, before trusting this address.
        String uid = normalizeSourceUid(recoveryRequest(src, "GetP2p", [:], token)?.P2p?.uid)
        src.identityCheckedAt = now()
        if (src.identity?.uid && src.identity.uid != uid) {
            src.token = null
            src.tokenExpires = 0
            log.warn "Reolink source ${sourceId}: could not verify the saved device identity; use Find changed IP"
            return null
        }
        if (uid && !src.identity?.uid) {
            src.identity = [version: 1, uid: uid, verifiedAt: now()]
            state.sources = (state.sources ?: []).collect { it.id == src.id ? src : it }
        }
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
    def src = getSource(sourceId)
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
                configureRtspChild(child, src, ch.channel)
                // v1.5.2: records this DNI as genuinely created, so
                // discoverPage()'s self-heal check can later tell a
                // deleted-externally device apart from a channel that's
                // simply never been created -- see that check's own
                // comment for the full story.
                markDeviceEverCreated(dni)
            }
            logNormal "Created child ${dni} (${driverName}) via bridge, poll interval defaulted to ${pollDefault}s (${ch.isBattery ? 'battery' : 'wired'}), features: ${ch.supportedFeatures ? ch.supportedFeatures.join(', ') : 'none detected'}"
        } else if (wantIt && existing) {
            // Re-discovery also repairs a child that predates RTSP support.
            configureRtspChild(existing, src, ch.channel)
        } else if (!wantIt && existing) {
            bridge.removeChannelDevice(dni)
            forgetSchedulingState(dni)
            // v1.5.2: an intentional removal through this page -- forget
            // this DNI so a later re-add of the same channel is treated as
            // a fresh add (defaultValue: exists correctly starts it
            // unchecked), not mistaken for the deleted-externally case the
            // self-heal check exists for.
            forgetDeviceEverCreated(dni)
            logNormal "Removed child ${dni} via bridge (unchecked in discovery list)"
        }
    }
    initializePolling()
}

/**
 * Copies source login settings to an RTSP-capable child after its channel data
 * value exists. The hub stream service reads these private device settings.
 *
 * @param child camera or doorbell created by the bridge or found during discovery
 * @param src source holding the camera/NVR host and login
 * @param channel zero-based Reolink API channel number
 */
private void configureRtspChild(child, src, channel) {
    if (!child?.hasCapability("RTSPStream")) return
    if (!src?.host || channel == null) {
        log.warn "Reolink ${child.deviceNetworkId}: cannot configure RTSP without a source host and channel"
        return
    }
    // src.port is the Reolink HTTP API port, not the RTSP port. The doorbell
    // driver saves these stream defaults only when its fields are unset.
    child.receiveRtspConfig([host: src.host, username: src.username,
        password: src.password, channel: channel, rtspPort: 554,
        outputWidth: 640])
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
    // v1.5.3: independent audit job, registered via Hubitat's own cron
    // scheduler rather than this app's runIn/schedulerTick chain -- see
    // auditEventConnections()'s comment for why it must be structurally
    // separate from the thing it's checking on.
    schedule("0 */15 * * * ?", "auditEventConnections")
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

    // Keep existing camera children in sync when the app or driver is upgraded.
    (state.sources ?: []).each { src ->
        childrenForSource(src.id).each { child ->
            configureRtspChild(child, src, child.getDataValue("channel"))
        }
    }
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
 *
 * v1.5.2: also backfills state.knownDeviceDnis for every device that
 * already exists at upgrade time -- without this, discoverPage()'s
 * self-heal check would treat every pre-1.5.2 device as "never created"
 * (registry starts empty) until the NEXT time it happened to be deleted
 * externally, at which point the self-heal simply wouldn't fire for it.
 * Harmless in practice (the check only matters at the moment of external
 * deletion, and a not-yet-seen device would just correctly get added to
 * the registry the first time it WAS actually created going forward), but
 * backfilling here means existing installs get full self-heal coverage
 * immediately on upgrade rather than device-by-device over time.
 */
private void runMigrations() {
    if (state.lastKnownAppVersion == APP_VERSION) return
    def fromVersion = state.lastKnownAppVersion ?: "(unknown/pre-migration-tracking)"

    def battDue = state.nextBatteryCheckDue ?: [:]
    int cleared = 0
    def known = state.knownDeviceDnis ?: []
    int backfilled = 0
    (state.sources ?: []).each { src ->
        def bridge = getSourceBridge(src.id)
        (bridge?.getChildDevices() ?: []).each { child ->
            if (child.hasCapability("Battery") && child.currentValue("batteryMode") == null) {
                battDue.remove(child.deviceNetworkId)
                cleared++
            }
            if (!known.contains(child.deviceNetworkId)) {
                known << child.deviceNetworkId
                backfilled++
            }
        }
    }
    state.nextBatteryCheckDue = battDue
    state.knownDeviceDnis = known
    if (cleared > 0) {
        logNormal "Reolink Integration: migration -- cleared stale battery-check schedule for " +
            "${cleared} device(s) with no batteryMode set, so the fix takes effect on the next tick " +
            "instead of waiting out an old schedule"
    }
    if (backfilled > 0) {
        logNormal "Reolink Integration: migration -- backfilled ${backfilled} existing device(s) into the " +
            "known-device registry used by discoverPage()'s stale-checkbox self-heal check"
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
/** Auto-reverts Full back to Errors Only after 60 minutes -- Full is meant for actively chasing something, not a steady state. Reverting to Errors Only (not Normal) matches this app's actual default, so a forgotten Full session doesn't leave routine logging elevated indefinitely. */
def revertToNormalLogging() {
    app.updateSetting("logLevel", [type: "enum", value: "Errors Only"])
    log.info "Reolink Integration: log level auto-reverted from Full to Errors Only after 60 minutes"
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
 * v1.5.3: independent liveness audit, structurally separate from the
 * bridge's own internal watchdog (ReolinkDeviceBridge.groovy's
 * sendKeepalive()/isEventConnectionStale()) -- registered via Hubitat's
 * own schedule() cron mechanism (see initialize()) rather than this app's
 * runIn/schedulerTick chain, specifically because the production incident
 * this exists for was caused by the FIRST watchdog silently dying. A
 * second check built on the same mechanism as the first could die the
 * same way; this one can't, since Hubitat's platform-level cron
 * scheduling doesn't depend on any job this app's own code keeps alive.
 * Every 15 minutes, asks each source's bridge whether it believes its own
 * connection is stale (past SOURCE_STALE_AUDIT_THRESHOLD_SEC with no real
 * traffic) and force-reconnects it if so. Logs once per transition into
 * staleness, not every audit cycle, same suppression pattern as
 * markSourceUnreachable() elsewhere in this file.
 */
def auditEventConnections() {
    (state.sources ?: []).each { src ->
        if (settings["useEventSubscription_${src.id}"] == false) return
        def bridge = getSourceBridge(src.id)
        if (!bridge) return
        def key = src.id.toString()
        def staleFlags = state.sourceAuditStale ?: [:]
        boolean stale
        try {
            stale = bridge.isEventConnectionStale(SOURCE_STALE_AUDIT_THRESHOLD_SEC)
        } catch (e) {
            log.warn "Reolink source ${src.id}: audit liveness check failed, ${e.message}"
            return
        }
        if (stale) {
            if (staleFlags[key] != true) {
                // v1.5.3 refinement: this fires the MOMENT staleness is
                // detected and a reconnect is about to be attempted -- a
                // single reconnect is routine (could be a transient IP/
                // network blip, nothing more) and shouldn't read as an
                // alarm on its own. logNormal instead of log.warn keeps it
                // silent at the default Errors Only tier; the bridge's own
                // scheduleReconnect() escalates to log.warn/log.error if
                // this doesn't resolve quickly on its own.
                logNormal "Reolink source ${src.id}: audit found event connection stale (no real traffic in " +
                    "${SOURCE_STALE_AUDIT_THRESHOLD_SEC}s+) despite reporting connected, forcing a reconnect"
            }
            staleFlags[key] = true
            state.sourceAuditStale = staleFlags
            try { bridge.stopEventSubscription() } catch (e) { /* best effort */ }
            bridge.startEventSubscription()
        } else if (staleFlags[key] == true) {
            staleFlags[key] = false
            state.sourceAuditStale = staleFlags
        }
    }
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
