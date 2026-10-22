/**
 * Reolink Integration (Parent App)
 * Version: 1.6.15
 *
 * A "source" is anything answering the Reolink HTTP/JSON API (standalone camera, NVR or Home Hub),
 * each with its own IP and login; NVR/Hub channels are 0..N-1, a standalone camera is channel 0.
 * Child devices are tagged (sourceId, channel) and only call parent.componentX(), never HTTP.
 * Each source has one "Reolink Device Bridge" that holds the real-time event connection and is the
 * real parent of its Camera/Doorbell devices. Events are the normal path; a source falls back to
 * polling when the connection drops and returns to events on reconnect. Poll intervals are per
 * device (wired 2-5s, battery loose). Device quirks live in the README and Tips page. TODO markers
 * flag API names still to verify against firmware. Full history is in GitHub commit history.
 *
 * v1.6.15 -- Version sync (setting descriptions are in the drivers).
 * v1.6.14 -- Hub load: backs off for a few minutes when the hub limits the app instead
 *    of retrying into it; event connections start one at a time, and a source isn't polled while its
 *    connection is starting; while falling back to polling, polls are at least 30s apart and
 *    snapshots 5 minutes; at most 2 fallback jobs per 2s tick (events-off sources keep their pace); only a real drop marks devices due now; a
 *    failed snapshot no longer forces a re-login; a locked login (-506) waits out its unlock time; timing at Full.
 * v1.6.13 -- Dark mode (thanks @vurvantsev): hub theme classes, with a scoped light fallback
 *    (legacyLightThemeCss) for pre-2.5.3 firmware. Also fixed Find changed IP status box, Apply
 *    device changes button, Reconnecting status and the Advanced schedule warning pill.
 * v1.6.12 -- Hub/NVR doorbells detected from typeInfo, doorbellVersion or GetAiState visitor,
 *    never the name (a Home Hub reported one as "Hub"). Event status/AItype read as
 *    comma-separated lists like reolink_aio ("MD,visitor", "people,vehicle" were missed).
 * v1.6.11 -- Spotlight refused over HTTP (-13, Home Hub Mini battery cams) retried over the
 *    Bridge (Baichuan: manual 288, auto read 289/write 290); battery cams use Baichuan first (HTTP
 *    "on" only flashes from standby). Attribute changes only when the camera accepts, follows the
 *    floodlight push (291), re-read 190s after "on" only when events are down. Battery "on" is
 *    re-sent every 20s for 180s (light lasts ~30s awake), then off. Baichuan 400 (waking) retried
 *    after 1.5s, up to 3 times.
 * v1.6.10 -- Bridge keeps per-message data in memory. Tips: Manual recording rewritten.
 * v1.6.9 -- Manual Record via SetManualRec {channel, enable, duration} (as Home Assistant);
 *    optional seconds, default and max 600, the app sends off at the end.
 * v1.6.8 -- Device commands grouped into dropdowns (old names still work). Spotlight auto =
 *    WhiteLed "mode" (0 off, 1 Night Smart), partial write so on/off is untouched; turning auto
 *    back on restores the saved mode (spotlightAutoMode data value). Mode read by Check >
 *    abilities and Refresh, at most every 10 min per device (SPOTLIGHT_READ_MIN_MS).
 * v1.6.6 -- Source password kept in memory only (bridges ask the app after reboot/driver save);
 *    State shows "Password: Saved, N characters" plus last login. Never logged. PIR on/off fixed:
 *    SetPirInfo was always rejected (-12); now read GetPirInfo, change "enable", write the full
 *    object back, update the page only once the camera accepts.
 * v1.6.5 -- Battery vs wired from GetAbility "battery" (an NVR answers GetBatteryInfo with junk
 *    for PoE channels). batteryMode retired for powerMode data value + "batteryWired" attribute;
 *    migration runs on its own after update, retrying every 10 min. chargingStatus "plugged_in".
 *    RTSP validation queued one device at a time, retried on timeout.
 * v1.6.4 -- Done/reboot restarts any source whose bridge can't prove live traffic; a given-up
 *    source retries when its camera answers again, else hourly via the audit.
 * v1.6.3 -- Reboot clears saved connection modes ("connected" survived, the socket didn't: likely
 *    the 5-day outage). Log level pushed to bridges (setLogRank). singleInstance, installOnOpen.
 * v1.6.2 -- Scheduler ticks 1s only while a source polls, else 30s (IDLE_TICK_SEC); an idle 1s
 *    tick was ~34% of hub time on 8 standalone sources and hit LimitExceededException.
 * v1.6.1 -- RTSP port and width defaults passed to new children.
 * v1.5.3 -- After a 5-day silent Home Hub outage: Bridge stale watchdog restored, keepalive always
 *    reschedules, plus an independent 15-min audit (auditEventConnections, via schedule()).
 *    Full logging reverts to Errors Only after 60 min.
 * v1.5.2 -- state.knownDeviceDnis: the deleted-device checkbox self-heal only applies to DNIs
 *    this app created before (new channels were bouncing back to unselected).
 * v1.5.0 -- NVR recording control, confirmed on an RLN16-410:
 *  - Master record (SetRecV20, no channel) always applies to every channel; no per-channel call.
 *    The Reolink app's per-camera Enable Record only controls SD-card recording.
 *  - Presets write 168-char hourly schedules per channel by full read-modify-write (a partial
 *    Rec returns 200 but changes nothing). Blank channel = skipped; excludeFromRecordingPresets
 *    locks a device out of every preset (manual commands unaffected).
 *  - GetRecV20 first, GetRec fallback (GetAbility scheduleVersion is unreliable). scheduleEnable
 *    is top-level on Rec, not in schedule; set every key in the device's own schedule.table.
 *  - Master enable goes after the per-channel writes (they re-enable it); ~300ms between channel
 *    writes (REC_CHANNEL_SETTLE_MS) or some are missed.
 *  - Preset button numbers are permanent and never reused, so a deleted preset's rule goes inert.
 *  - "Don't manage" leaves a schedule alone; "Never record" writes all zeros.
 *  - Platform quirks: a command param with a name but no type becomes a Rule Machine argument
 *    slot (keep zero-arg commands bare); RM passes NUMBER arguments as BigDecimal.
 * BREAKING (v1.3.8): devices moved under a per-source Device Bridge; existing installs had to
 *    re-discover and repoint rules and dashboards.
 * v1.3.6-1.4.5 -- Standalone device support (battery standalones later closed), PIR, real-time
 *    events with polling fallback, scheduler/battery-check fixes, DuplicateDNIException handled,
 *    explicit uninstalled() teardown.
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
    singleInstance: true,
    installOnOpen: true,
    oauth: true // required for createAccessToken()/local endpoint access used by the snapshot relay
)

@Field static final String APP_VERSION = "1.6.15"

// v1.6.5: power mode migration key; changing it re-runs migrateBatteryModes().
@Field static final String POWER_MIGRATION_KEY = "batteryWired"
@Field static final long POWER_MIGRATION_RETRY_MS = 600000L

// v1.6.6: password display migration key; changing it re-runs migrateCredentials().
@Field static final String CRED_MIGRATION_KEY = "passwordMasked"

// v1.6.5: RTSP validation queue timing.
@Field static final int RTSP_GAP_SEC = 5
@Field static final int RTSP_WATCHDOG_SEC = 30
@Field static final List<Integer> RTSP_RETRY_DELAYS = [60, 180]

@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

// Poll interval is a device-level setting ONLY -- these are just the one-time
// default applied to a newly created device, not user-configurable at the app
// level. To change an existing device's interval, use its own device page (or
// the Set Interval command).
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
// than the bridge's own internal 180s watchdog threshold (STALE_CONNECTION_
// THRESHOLD_SEC in ReolinkDeviceBridge.groovy) -- this audit exists to
// catch the case where that first-layer watchdog itself silently stops
// running, not to compete with it on timing.
@Field static final int SOURCE_STALE_AUDIT_THRESHOLD_SEC = 300

// v1.6.8: Refresh reads a spotlight camera's mode at most this often (in memory, per device).
@Field static final long SPOTLIGHT_READ_MIN_MS = 600000L
@Field static java.util.concurrent.ConcurrentHashMap SPOTLIGHT_READ_AT = new java.util.concurrent.ConcurrentHashMap()

// v1.6.2: scheduler tick rate while every source is event-connected (nothing to poll).
@Field static final int IDLE_TICK_SEC = 30
@Field static final int POLL_TICK_SEC = 2
@Field static final int MAX_JOBS_PER_TICK = 2
@Field static final long TICK_BUDGET_MS = 4000L
@Field static final int FALLBACK_POLL_FLOOR_SEC = 30
@Field static final int FALLBACK_SNAPSHOT_FLOOR_SEC = 300
@Field static final int BRIDGE_START_GAP_SEC = 2
@Field static final long BRIDGE_START_GRACE_MS = 30000L
@Field static final List<Integer> LOAD_BACKOFF_MINUTES = [3, 5, 10, 15]

// v1.6.4: minimum gap between app-driven retries of a source that isn't connected.
@Field static final long EVENT_RETRY_INTERVAL_MS = 3600000L

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
    // v1.6.3: keep each bridge's cached log level in sync with this setting.
    if (state.pushedLogLevel != (logLevel ?: "Errors Only")) pushLogRankToBridges()
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
        section(sectionClass: "app-ui-links reolink-main-sources") {
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
        section(sectionClass: "app-ui-links reolink-main-settings") {
            paragraph rawHtml: true, mainPageColumnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph rawHtml: true, loggingDetailsPopupHtml()
        }
        section(title: "<b>Help & Support</b>", sectionClass: "app-ui-links reolink-main-support") {
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
    String tone = configured ? "p-message p-message-success reolink-message" : "p-message p-message-warn reolink-message"
    String iconColor = configured ? "hub-text-success" : "hub-text-warning"
    String icon = configured ? "pi pi-check-circle" : "fa-solid fa-exclamation-triangle"

    return """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
  .reolink-main-sources { float: left; width: calc(62% - 8px); }
  .reolink-main-settings {
    float: right; width: 38%; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12));
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
  <a href='/logs?tab=past&amp;appId=${app.id}' target='_blank' class='hub-text-link font-semibold white-space-nowrap no-underline'>
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
    background: var(--surface-card, #fff);
    border: 1px solid var(--surface-border, rgba(0,0,0,.12));
    border-radius: 4px;
    box-shadow: none;
    color: var(--text-color, rgba(0,0,0,.87));
    height: 56px;
    font-family: inherit;
    font-size: 16px;
    font-weight: 500;
    line-height: 1.4;
    padding: 8px 28px 8px 46px;
  }
  button.hrefElem[name^='_action_href_tips'] > span:first-child { color: var(--hubitat-link-color, #1565c0); font-weight: 600; font-size: 16px !important; }
  button.hrefElem[name^='_action_href_tips'] > span.state-incomplete-text {
    color: var(--text-color-secondary, rgba(0,0,0,.6)); font-size: 14px; font-weight: 500; line-height: 1.4;
  }
  button.hrefElem[name^='_action_href_tips'] i.pi {
    position: absolute;
    left: 14px;
    top: 50%;
    transform: translateY(-50%);
    color: var(--hubitat-link-color, #1565c0);
    font-size: 20px;
  }
  button.hrefElem[name^='_action_href_tips']::before { color: var(--hubitat-link-color, #1565c0); }
"""
}

/** Shared page inset rules keep the main and discovery pages aligned. */
private String appPageSpacingCss() {
    """
  ${tileSecondaryTextCss()}
  .reolink-status-heading { font-size: 16px; }
  .p-message.reolink-message { padding: 0.75rem !important; margin: 0; border: 0 !important; }
  .reolink-message .text-color-secondary,
  .reolink-message .hub-text-link, .reolink-message .hub-text-warning,
  .reolink-message .hub-text-success, .reolink-message .hub-text-danger { color: inherit !important; }
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

/**
 * Supplies semantic styles when firmware does not advertise the app UI class contract.
 * The hub app container advertises version 1 only when its semantic classes are available.
 * Scope covers this app form and its logging dialog without changing other hub controls.
 * @return scoped fallback CSS; capable firmware uses the hub semantic classes
 */
private String legacyLightThemeCss() {
    """
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .hub-text-link { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .hub-text-success { color: var(--hubitat-success-color, #356700) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .hub-text-warning { color: var(--hubitat-warning-color, #8a5a00) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .hub-text-danger { color: var(--hubitat-validation-color, #b71c1c) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-links .hrefElem { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-links .hrefElem > .state-incomplete-text,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-links .hrefElem > .state-complete-text { color: var(--text-color-secondary, #616161); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button { background: var(--hubitat-control-background, rgba(158,158,158,.2)); color: var(--hubitat-control-text, #363636); border: 1px solid transparent; border-radius: 4px; box-shadow: 0 2px 2px rgba(0,0,0,.14); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button:enabled:hover { background: var(--hubitat-control-hover-background, #cacfdc); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button-primary,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button-primary:enabled:hover { background: var(--hubitat-brand-background, #81bc00); color: var(--hubitat-on-brand, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button-secondary { background: var(--surface-card, #fff); color: var(--hubitat-link-color, #1565c0); border-color: var(--hubitat-control-border, #b0bec5); box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button-danger,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button-danger:enabled:hover { background: var(--hubitat-danger-background, #d32f2f); color: var(--hubitat-on-danger, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button:enabled:active { box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button:disabled { background: var(--hubitat-control-background, #eee); color: var(--text-color-secondary, #616161); opacity: .6; cursor: not-allowed; box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-navigation .hrefElem { background: transparent; color: var(--hubitat-link-color, #1565c0) !important; border: 0; border-left: 3px solid transparent; border-radius: 0; box-shadow: none; padding: 12px; font-family: inherit; font-size: 16px; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-navigation .hrefElem::before { display: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-navigation .hrefElem:not(:disabled):not(:has(.app-ui-navigation-selected)):hover { background: var(--surface-hover, #f3f6fa); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-navigation .hrefElem:has(.app-ui-navigation-selected) { background: var(--hubitat-selection-background, #eaf2fc); border-left-color: var(--hubitat-link-color, #1565c0); font-weight: 600; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-navigation .hrefElem:disabled { opacity: .6; cursor: not-allowed; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) button.mdl-button.app-ui-button:focus-visible,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .reolink-dialog) .app-ui-navigation .hrefElem:focus-visible { outline: 2px solid var(--hubitat-link-color, #1565c0); outline-offset: 2px; }
"""
}

/** Match Hubitat's native href secondary text across all app pages. */
private String tileSecondaryTextCss() {
    legacyLightThemeCss() + "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

private String emptySourcesHtml() {
    """
<div class='border-1 surface-border border-round p-3 text-color-secondary'>
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
        colorClass = "hub-text-danger"
    } else if (connectionMode == "connected") {
        status = "Online"
        colorClass = "hub-text-success"
    } else if (pollingOnly) {
        status = "Polling"
        colorClass = "hub-text-link"
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
<div class='reolink-dialog text-left text-color' style='max-width:520px;line-height:1.45;'>
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
   class='flex align-items-center justify-content-between gap-3 surface-ground border-1 surface-border border-round px-3 py-2 h-full text-color no-underline'>
  <span>
    <span class='block font-semibold'>Logging details</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>What each level records</span>
  </span>
  <i class='pi pi-chevron-right hub-text-link'></i>
</a>
"""
}

private String loggingLevelDetailHtml(String name, String description, String currentLevel) {
    String current = name == currentLevel ?
        "<span class='hub-text-success text-xs font-bold'>Current</span>" : ""
    String tone = name == "Errors Only" ? "p-tag p-tag-danger" :
        name == "Full" ? "p-tag p-tag-warning" : "p-tag p-tag-info"
    String warning = name == "Full" ? warningMessageHtml(
        "<b>Full</b> is a temporary setting. It automatically reverts to <b>Errors Only</b> after 60 minutes.", "mt-3 text-base") : ""
    return """
<div class='border-1 surface-border border-round px-3 py-2 mb-2'>
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
   class='flex align-items-center gap-3 border-1 surface-border border-round px-3 py-2 text-color no-underline'>
  <i class='${iconClass} hub-text-link text-xl flex-shrink-0'></i>
  <span class='min-w-0'>
    <span class='block hub-text-link font-semibold'>${title}</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>${subtitle}</span>
  </span>
</a>
"""
}

private String countText(int count, String singular) {
    "${count} ${singular}${count == 1 ? '' : 's'}"
}

private String pillHeader(String text) {
    "<div class='inline-block p-tag p-tag-info font-bold text-xs uppercase px-3 py-1 " +
    "border-round-xl mb-2'>${text.toUpperCase()}</div>"
}

/**
 * Small colored pill for a log level name, distinct from pillHeader's section-title style so
 * the two do not get visually confused. Semantic styles distinguish errors, normal logging,
 * and the temporary full-logging warning in either hub theme.
 */
private String logLevelPill(String level) {
    def tones = [
        "Errors Only": "p-tag p-tag-danger",
        "Normal":      "surface-ground text-color-secondary",
        "Full":        "p-tag p-tag-warning"
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
        section(sectionClass: "app-ui-links reolink-tips-index app-ui-navigation") {
            paragraph rawHtml: true, tipsStylesHtml()
            topics.groupBy { it.group }.each { group, entries ->
                paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold mt-2'>${group}</div>"
                entries.each { entry ->
                    String selected = entry.id == topic.id ? "app-ui-navigation-selected" : ""
                    href name: "tip_${entry.id}",
                        title: "<span class='${selected}'><i class='pi ${entry.icon} mr-3' aria-hidden='true'></i>${entry.label}</span>",
                        description: "", page: "tipsPage", params: [topic: entry.id],
                        width: 12, style: "margin:0 8px;"
                }
            }
        }
        section(sectionClass: "app-ui-links reolink-tips-article") {
            paragraph rawHtml: true, tipsArticleHtml(topic)
            if (topic.id == "network") {
                def sources = topics.find { it.id == "sources" }
                paragraph rawHtml: true, tipsArticleHtml(sources)
            }
            if (nextTopic) {
                href name: "nextTip", title: "<span class='hub-text-link'>${nextTopic.label}</span>",
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
  .reolink-tips-article { float: right; width: 73%; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); padding-left: 8px; box-sizing: border-box; }
  .reolink-tips-index > .mdl-grid, .reolink-tips-article > .mdl-grid { padding: 4px 0 !important; }
  .reolink-tips-index .mdl-cell:has(> style) { display: none; }
  .reolink-tip-card { border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; overflow: hidden; }
  .reolink-tip-card-header { padding: 16px; background: var(--surface-ground, #fafafa); border-bottom: 1px solid var(--surface-border, rgba(0,0,0,.12)); }
  .reolink-tip-copy { padding: 16px; line-height: 1.55; overflow-wrap: anywhere; }
  .reolink-tip-copy p { margin: 0 0 16px; }
  .reolink-tip-copy p:last-child { margin-bottom: 0; }
  .reolink-tips-article button.hrefElem { background: var(--surface-card, #fff); box-shadow: none; border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; font-family: inherit; }
  .reolink-tips-article button.hrefElem::before { color: var(--hubitat-link-color, #1565c0); }
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
        extra = "<div class='border-1 surface-border border-round p-3 mb-3'>" +
            ["HTTP", "HTTPS", "ONVIF"].collect { service ->
                "<div class='flex align-items-center gap-3 py-2'><i class='pi pi-check-circle hub-text-link text-xl' aria-hidden='true'></i>" +
                "<div><div class='font-semibold'>${service}</div><div class='text-sm text-color-secondary'>Enable in the camera settings.</div></div></div>"
            }.join("") + "</div>" +
            warningMessageHtml("<b>Network services are often disabled by default.</b><br>Check these before troubleshooting the connection.")
    }
    """
<article class='reolink-tip-card'>
  <div class='reolink-tip-card-header flex align-items-center gap-3'>
    <i class='pi ${topic.icon} hub-text-link text-xl' aria-hidden='true'></i>
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
                "camera -- run discovery to see what it finds.") + "</p>",
                "<p>" + ("<b>Your login:</b> the source's password is stored only in this app on your hub, and is " +
                "never shown or written to the logs. The source's Reolink Device Bridge shows <b>Password: Saved, " +
                "N characters</b> and the last <b>Login</b> result, so a wrong password is easy to spot. To change " +
                "it, use <b>Edit connection settings</b> on the source's page.") + "</p>"
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
                "and polling is skipped entirely -- polling only resumes automatically if that connection drops.") + "</p>",
                "<p>" + ("<b>While falling back to polling,</b> each device is polled at most every 30 seconds and " +
                "its snapshot refreshed at most every 5 minutes, whatever its own settings, to keep hub load down. " +
                "A source with event mode turned off uses the device's own intervals. If the hub ever limits the " +
                "app for load, it pauses polling for a few minutes and logs one warning.") + "</p>"
            ].join("")],
        [id: "events", label: "Event-driven updates", title: "Event-driven updates", group: "Devices & connections", icon: "pi-wifi",
            body: [
                "<p>" + ("On by default, and for almost every source there's nothing to do here -- a source " +
                "that supports it gets real-time updates, and if the connection ever drops, it retries " +
                "automatically (backing off over 10 attempts) before falling back to polling, then tries event " +
                "mode again as soon as the camera answers, or at least once an hour. No toggle needed; it self-recovers.") + "</p>",
                "<p>" + ("This toggle matters for ONE specific case: a source that structurally can't do event " +
                "mode at all -- port 9000 blocked by a firewall, or older firmware that doesn't speak the " +
                "event protocol. That source will still go through all 10 reconnect attempts (and their " +
                "logging) every time the hub restarts or the app re-initializes, before eventually giving up " +
                "and polling anyway. If you already know a source falls into this category, turning this off " +
                "skips that runway entirely and goes straight to polling -- a convenience, not a different " +
                "outcome, since it lands in the same place either way.") + "</p>",
                "<p>" + ("<b>Many standalone cameras:</b> each standalone camera keeps its own live " +
                "connection to the hub. Past roughly 10, an NVR or Home Hub is the better setup: one " +
                "connection and one login cover every channel.") + "</p>"
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
                "<b>Ptz Preset > save here</b> once (commonly preset ID 1) to save wherever the camera is currently " +
                "pointed, then <b>Ptz Preset > go to</b> with that ID any time to return there.") + "</p>"
            ].join("")],
        [id: "calibration", label: "PTZ calibration", title: "PTZ calibration", group: "Camera controls", icon: "pi-compass",
            body: [
                "<p>" + ("&#9888; Only applies to PTZ-capable cameras (e.g. Trackmix, E1 Zoom) -- non-PTZ cameras " +
                "just harmlessly error if you try it. Use <b>Ptz > Calibrate</b> if preset recall starts " +
                "drifting off target over time. It then checks its own progress for a few minutes " +
                "(ptzCalibrationStatus: Required / Running / Done), or use <b>Check > ptz calibration</b> any time.") + "</p>"
            ].join("")],
        [id: "pir", label: "PIR trigger", title: "PIR motion trigger", group: "Camera controls", icon: "pi-bolt",
            body: [
                "<p>" + ("Use <b>Set Pir</b> (on/off) to enable or disable a camera's PIR trigger without " +
                "removing the device. Does NOT stop an in-progress recording -- it removes the trigger that " +
                "would have woken a battery camera to record. Manual only, no auto-revert timer -- build " +
                "battery-threshold automation with Rule Machine using the existing battery attribute.") + "</p>",
                "<p>" + ("Turning PIR off while you're home is a good way to stop recordings and alerts without " +
                "24/7 recording. If the camera still records with PIR off, turn off <b>Pre-recording</b> for " +
                "that camera in the Reolink app.") + "</p>"
            ].join("")],
        [id: "spotlight", label: "Spotlight", title: "Spotlight and motion", group: "Camera controls", icon: "pi-sun",
            body: [
                "<p>" + ("<b>Set Spotlight</b> has two kinds of choices. <b>on</b>/<b>off</b> turn " +
                "the light on or off right now. <b>auto on</b>/<b>auto off</b> control whether the " +
                "camera turns the light on by itself for motion at night (Spotlight > Modes in the Reolink app: " +
                "Night Smart Mode or Off). The <b>spotlightAuto</b> attribute shows the current setting. It updates right " +
                "away when changed from Hubitat; a change made in the Reolink app shows after a Refresh (read at most every " +
                "10 minutes) or <b>Check > abilities</b>.") + "</p>",
                "<p>" + ("<b>Example:</b> keep the patio light from switching on every time you move while you're " +
                "sitting outside. In Rule Machine, when a Patio switch turns on, use <b>Run Custom Action</b> to " +
                "send <b>setSpotlight</b> with <b>auto off</b> to that camera. When it turns off, send <b>auto on</b>. " +
                "One action can target several cameras at once.") + "</p>",
                "<p>" + ("Turning auto off doesn't turn off a light that's already on; it goes off on its normal " +
                "timer, or send <b>off</b> in the same rule. Auto on restores the mode the camera had " +
                "before (for example a schedule), not always Night Smart Mode.") + "</p>",
                "<p>" + ("<b>Spotlight on</b> is a manual light that Reolink turns off by itself after about " +
                "3 minutes. A battery camera only keeps its light on while it's awake, so the integration keeps it " +
                "awake for those 3 minutes (about the same battery use as 3 minutes of live view); sending <b>off</b> " +
                "ends it early. The <b>spotlight</b> attribute follows the camera. To keep the light on longer, " +
                "have a rule send <b>on</b> again.") + "</p>",
                "<p>" + ("Some cameras refuse light changes over the normal API (seen on battery cameras behind a " +
                "<b>Home Hub Mini</b>). The integration then sends them over the event connection instead, the " +
                "same way Home Assistant does, so the Device Bridge's event connection needs to be up.") + "</p>"
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
                "two presets (e.g. \"Daytime\"/\"Nighttime\") switched by a time-based Rule Machine schedule.") + "</p>",
                "<p>" + ("<b>Presets set schedules, they don't start a recording.</b> To record right now, use the " +
                "camera's <b>Manual Record</b> command (see Manual recording). Recording control was built and tested " +
                "on an NVR; on a Home Hub it's untested. Battery cameras usually record on events only, so a 24/7 " +
                "schedule may not apply to them.") + "</p>"
            ].join("")],
        [id: "manualrec", label: "Manual recording", title: "Manual recording", group: "Recording & snapshots", icon: "pi-circle-fill",
            body: [
                "<p>" + ("Use <b>Manual Record</b> on a camera or doorbell to record now, for example when another " +
                "sensor sees motion. Turn it <b>on</b> with optional seconds (1 to 600, default 600); it stops by " +
                "itself, or use <b>off</b> to stop sooner. The <b>Manual Record</b> attribute shows on/off.") + "</p>",
                "<p>" + ("<b>Works on cameras that record on events only</b>, wired and battery (tested behind a Home " +
                "Hub Pro: the clip saves to the hub). With <b>continuous</b> recording it's redundant, since the camera " +
                "is already recording; that footage shows in playback once each recording file closes (often hourly). " +
                "In playback, look at the timeline for that time, since a manual clip isn't tagged as motion or person.") + "</p>",
                "<p>" + ("The clip runs a little longer than the seconds you set, because of the camera's " +
                "<b>Post-recording</b> time in the Reolink app.") + "</p>",
                "<p>" + ("&#9888; <b>Battery cameras stay awake while recording</b>, so keep it short. Their FTP upload " +
                "only sends motion clips, so manual clips aren't uploaded by FTP. A change made in the Reolink app " +
                "isn't shown on the attribute. On NVR firmware 3.5.1 and 3.6.x, some NVRs reject manual recording " +
                "(a known Reolink firmware bug); the app then logs a warning and nothing changes.") + "</p>"
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
                 "sleep", "firmware", "ptz", "calibration", "pir", "spotlight", "recording", "manualrec", "snapshots", "logging"]
    // Topics missing from "order" are appended rather than silently dropped.
    order.collect { id -> topics.find { it.id == id } }.findAll() + topics.findAll { !order.contains(it.id) }
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
                    paragraph rawHtml: true, "<div class='border-1 surface-border border-round p-3'>" +
                        "<div class='font-semibold'>${title}</div>" +
                        "<div class='text-sm text-color-secondary mt-1'>ID: ${discoveryEscapeHtml(candidate.uid)}</div>" +
                        "<div class='hub-text-success mt-2'>Already added: ${discoveryEscapeHtml(existing.label)}</div></div>"
                } else {
                    href name: "discoveredSource_${index}",
                        title: "<i class='fa-regular fa-plus mr-2' aria-hidden='true'></i>${title}",
                        description: "Set up this source - ID: ${candidate.uid}", page: "addSourcePage",
                        params: [discoveryNonce: scan.nonce, candidateIndex: index], width: 12, style: "margin:8px;"
                }
            }
            paragraph "Select a new source to review its details and enter credentials. Devices must be on the same local network; sleeping devices may not respond."
        }
        section(sectionClass: "app-ui-links reolink-recovery-actions") {
            input "rescanSources", "button", title: "<i class='fa-solid fa-rotate-right mr-2' aria-hidden='true'></i>Search again",
                width: 4, submitOnChange: true, disabled: busy, inputClass: "app-ui-button"
            input "cancelSourceDiscovery", "button",
                title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, inputClass: "app-ui-button"
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
        section(sectionClass: "app-ui-links reolink-add-form") {
            paragraph rawHtml: true, recoveryStylesHtml() + addSourceStylesHtml()
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>Source details</div>" +
                "<div class='text-sm text-color-secondary mt-1'>Enter connection details for your Reolink camera, NVR, or Home Hub.</div>"
            input "newLabel", "text", title: "Label (e.g. 'Front Door Hub', 'Garage Cam')"
            input "newHost", "text", title: "IP address or hostname", width: 8
            input "newPort", "number", title: "HTTPS port", defaultValue: 443, width: 4
            input "newUser", "text", title: "Username"
            input "newPass", "password", title: "Password"
            input "newIsHub", "bool", title: "This is an NVR or Home Hub" +
                "<div class='text-sm text-color-secondary mt-1'>Multiple channels from one source.</div>",
                defaultValue: false, styleClass: "reolink-add-source-type"
        }
        section(sectionClass: "app-ui-links reolink-add-guidance") {
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>Before you connect</div>" +
                "<div class='text-sm text-color-secondary mt-1'>Check the source's server settings in Reolink.</div>"
            paragraph rawHtml: true, warningMessageHtml("<div class='font-semibold'>Enable HTTPS first</div>" +
                "<div class='text-sm mt-2'>Enable HTTPS in Reolink Network &gt; Advanced (or Server) settings. " +
                "Match the HTTPS port to the value configured there.</div>")
            paragraph rawHtml: true, "<div class='p-message p-message-info reolink-message'>" +
                "<div class='font-semibold'><i class='pi pi-info-circle mr-2' aria-hidden='true'></i>Real-time events</div>" +
                "<div class='text-sm mt-2'>Allow Hubitat to reach TCP port 9000 on the source over your local network. " +
                "If real-time events are unavailable, the app falls back to polling.</div></div>"
            paragraph rawHtml: true, "<div class='surface-ground border-round p-3'><div class='font-semibold'>Optional services</div>" +
                "<div class='text-sm text-color-secondary mt-2'>RTSP is required for video streaming. HTTP, RTMP, and ONVIF are optional.</div></div>"
            href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
                description: "Known quirks and setup guidance", page: "tipsPage", width: 12, style: "margin:8px;"
            paragraph rawHtml: true, "<div class='surface-ground border-round p-3'><div class='font-semibold'>Compatibility note</div>" +
                "<div class='text-sm text-color-secondary mt-2'>Some battery cameras, doorbells, and E1 models have additional " +
                "connection limitations. See Tips &amp; Troubleshooting for details.</div></div>"
        }
        section(sectionClass: "app-ui-links reolink-add-footer") {
            paragraph rawHtml: true, "<div class='text-sm text-color-secondary'>Fill in Label, IP address, Username, and Password, then select Add source. " +
                "Leaving any of those blank returns to Sources without creating anything.</div>"
        }
        section(sectionClass: "app-ui-links reolink-recovery-actions reolink-add-actions") {
            input "confirmAddSource", "button",
                title: "<i class='fa-solid fa-plus mr-2' aria-hidden='true'></i>Add source",
                width: 4, submitOnChange: true, styleClass: "reolink-recovery-primary",
                inputClass: "app-ui-button app-ui-button-primary"
            input "cancelAddSource", "button",
                title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, inputClass: "app-ui-button"
        }
    }
}

private String addSourceStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
  .reolink-add-form { float: left; width: calc(62% - 8px); }
  .reolink-add-guidance { float: right; width: 38%; padding-left: 8px; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); box-sizing: border-box; }
  .reolink-add-form > .mdl-grid, .reolink-add-guidance > .mdl-grid { padding: 4px 0 !important; }
  .reolink-add-form .mdl-cell:has(> style) { display: none; }
  .reolink-add-source-type { background: var(--surface-ground, #fafafa); border-radius: 4px; padding: 12px; box-sizing: border-box; }
  .reolink-add-source-type .mdl-switch { height: auto; min-height: 24px; }
  .reolink-add-source-type .mdl-switch__label { line-height: 24px; }
  .reolink-add-footer { clear: both; border-top: 1px solid var(--surface-border, rgba(0,0,0,.12)); margin: 0 8px !important; }
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
            section(sectionClass: "app-ui-links reolink-discovery-summary") {
                paragraph rawHtml: true, discoveryOverviewHtml(channelCount,
                    childrenForSource(sourceId as Integer).size(), !!state.lastDiscoveryError)
            }
            section(sectionClass: "app-ui-links reolink-discovery-channels") {
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
                        def statusTag = exists ? "<span class='p-tag p-tag-success text-sm ml-3 px-2 py-1 border-round-xl'>Added</span>" :
                            "<span class='p-tag p-tag-info text-sm ml-3 px-2 py-1 border-round-xl'>New</span>"
                        input settingKey, "bool",
                            title: "Ch ${ch.channel}: ${discoveryEscapeHtml(ch.name)}${doorbellTag}${statusTag}",
                            defaultValue: exists, submitOnChange: true, width: 12,
                            styleClass: "reolink-channel-row"
                    }

                    if (channelCount > 1) {
                        paragraph "<span class='text-sm text-color-secondary'>Select the channels you want, " +
                            "then use <b>Apply device changes</b>. Added channels already have a device; New channels do not.</span>"
                        paragraph "<hr class='border-0 border-top-1 surface-border my-2'>"
                        input "applyDeviceChanges_${sourceId}", "button",
                            title: "<i class='fa-solid fa-floppy-disk mr-2' aria-hidden='true'></i>Apply device changes",
                            width: 12, inputClass: "app-ui-button"
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
                    sectionClass: "app-ui-links reolink-discovery-connection") {
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
                def statusClass = connStatus == "connected" ? "hub-text-success" :
                    connStatus == "reconnecting" ? "hub-text-warning" : "text-color-secondary"
                paragraph rawHtml: true, "<div class='border-top-1 surface-border pt-3'>" +
                    "<div class='flex gap-3 mb-3'><span class='w-6rem flex-shrink-0'>Status</span>" +
                    "<span class='${statusClass} font-semibold'>&#9679; ${discoveryEscapeHtml(connStatus.capitalize())}</span></div>" +
                    "<div class='flex gap-3'><span class='w-6rem flex-shrink-0'>Source</span>" +
                    "<span>${discoveryEscapeHtml(src.label)} (${discoveryEscapeHtml(src.host)})" +
                    "<span class='block text-sm text-color-secondary mt-1'>${src.isHub ? 'Hub/NVR' : 'Standalone camera'}</span></span></div></div>"
            }
            if (src.isHub) {
                section(sectionClass: "app-ui-links reolink-discovery-recording") {
                    paragraph rawHtml: true, "<div class='reolink-discovery-heading font-semibold'>Recording</div>" +
                        "<div class='text-sm text-color-secondary mt-1'>Configure when each channel records.</div>"
                    href name: "presetsFromDiscover", title: "<i class='pi pi-video mr-2' aria-hidden='true'></i>Recording Presets",
                        description: "Set recording schedules per channel",
                        page: "presetsPage", params: [sourceId: sourceId]
                }
            }
            section(sectionClass: "app-ui-links reolink-discovery-danger p-message p-message-error reolink-message border-round") {
                paragraph rawHtml: true, "<div class='reolink-discovery-heading font-semibold hub-text-danger'>Danger zone</div>" +
                    "<div class='text-color-secondary mt-1' style='font-size:14px;'>Remove this source and its devices from Hubitat. " +
                    "You will be asked to confirm before anything is removed.</div>"
                href name: "reviewSourceRemoval", title: "<span class='hub-text-danger font-semibold'><i class='fa-solid fa-trash mr-2' aria-hidden='true'></i>Remove source...</span>",
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
            // v1.6.6: never shows the password itself, only its length and the last login result.
            paragraph rawHtml: true, editLoginLineHtml(src)
            paragraph "These settings apply to all devices belonging to this source."
        }
        section(sectionClass: "app-ui-links reolink-recovery-actions") {
            input "saveSourceConnection", "button",
                title: "<i class='fa-regular fa-floppy-disk mr-2' aria-hidden='true'></i>Save changes",
                width: 4, submitOnChange: true, styleClass: "reolink-recovery-primary",
                inputClass: "app-ui-button app-ui-button-primary"
            input "cancelSourceEdit", "button",
                title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, inputClass: "app-ui-button"
        }
    }
}

/** v1.6.6: "Password: Saved, 12 characters · Last login OK, 10:42 AM" under the password field. */
private String editLoginLineHtml(src) {
    Map rec = (state.sourceLogin ?: [:])[src.id.toString()] as Map
    String login = rec?.ok == true ? "Last login OK, ${shortTime(rec.at as Long)}" :
        rec?.ok == false ? "Login failed: ${rec.reason}" : "Login not checked yet"
    String tone = rec?.ok == true ? "hub-text-success" : rec?.ok == false ? "hub-text-danger" : "text-color-secondary"
    String icon = rec?.ok == true ? "<i class='pi pi-check-circle mr-1' aria-hidden='true'></i>" :
        rec?.ok == false ? "<i class='fa-solid fa-exclamation-triangle mr-1' aria-hidden='true'></i>" : ""
    return "<div class='text-sm font-semibold ${tone}'>${icon}Password: ${passwordSummary(src.password)} &middot; " +
        "${discoveryEscapeHtml(login)}</div>"
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
        section(sectionClass: "app-ui-links reolink-recovery-summary") {
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
        section(sectionClass: "app-ui-links reolink-recovery-status") {
            String tone = verified ? "success" : (!hasUid || recovery.phase == "error") ? "warn" : "info"
            String statusIcon = verified ? "fa-circle-check" : busy ? "fa-spinner fa-spin" :
                tone == "warn" ? "fa-circle-exclamation" : "fa-magnifying-glass"
            String heading = verified ? "Device verified" : !hasUid ? "Device identity not saved" :
                recovery.phase == "searching" ? "Finding your device" : recovery.phase == "verifying" ? "Verifying device identity" :
                recovery.phase == "error" ? "Unable to verify device" : recovery.phase == "done" ? "Search result" : "Ready to locate this device"
            String message = !hasUid ? "Connect this source at its current IP and refresh a device to save its identity. If it has already moved, use Edit connection settings." :
                verified ? "The device UID matches your saved source." : recovery.message ?: "Search your local network for the same device."
            paragraph rawHtml: true, "<div class='p-message p-message-${tone} reolink-message border-round'>" +
                "<div class='reolink-status-heading font-semibold flex align-items-center gap-2'>" +
                "<i class='fa-solid ${statusIcon}' aria-hidden='true'></i><span>${heading}</span></div>" +
                (verified ? "<div class='flex align-items-center flex-wrap gap-3 mt-3'>" +
                    "<div><div class='text-sm text-color-secondary mb-1'>Saved IP</div><code>${discoveryEscapeHtml(src.host)}</code></div>" +
                    "<i class='fa-solid fa-arrow-right text-color-secondary' aria-hidden='true'></i>" +
                    "<div><div class='text-sm text-color-secondary mb-1'>New IP</div><code class='font-semibold'>${discoveryEscapeHtml(recovery.host)}</code></div></div>" : "") +
                "<div class='text-color-secondary mt-2'>${discoveryEscapeHtml(message)}</div></div>"
        }
        section(sectionClass: "app-ui-links reolink-recovery-actions") {
            // Use native button inputs and the same classes as the recording preset actions.
            if (verified) {
                input "applyIp_${recovery.nonce}", "button", title: "<i class='fa-solid fa-check mr-2' aria-hidden='true'></i>Use this IP",
                    width: 4, submitOnChange: true, styleClass: "reolink-recovery-primary", inputClass: "app-ui-button app-ui-button-primary"
            }
            if (hasUid) {
                input "findIp_${recovery.nonce}", "button", title: "<i class='fa-solid ${verified ? 'fa-rotate-right' : 'fa-magnifying-glass'} mr-2' aria-hidden='true'></i>${verified ? 'Search again' : 'Search LAN'}",
                    width: 4, submitOnChange: true, disabled: busy, styleClass: verified ? "reolink-recovery-secondary" : "reolink-recovery-primary",
                    inputClass: verified ? "app-ui-button app-ui-button-secondary" : "app-ui-button app-ui-button-primary"
            }
            input "cancelIp_${recovery.nonce}", "button", title: "<i class='fa-regular fa-xmark mr-2' aria-hidden='true'></i>Cancel",
                width: 4, submitOnChange: true, disabled: busy, inputClass: "app-ui-button"
        }
        section(sectionClass: "app-ui-links reolink-recovery-footer") {
            paragraph rawHtml: true, "<div class='border-top-1 surface-border pt-3 text-sm text-color-secondary'>" +
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
  .reolink-recovery-row { display: grid; grid-template-columns: 140px minmax(0, 1fr); gap: 16px; padding: 14px 0; border-top: 1px solid var(--surface-border, rgba(0,0,0,.12)); }
  .reolink-recovery-summary code, .reolink-recovery-status code { font-size: 16px; overflow-wrap: anywhere; }
  .reolink-recovery-actions button { min-width: 128px; min-height: 40px; padding: 0 16px; border-radius: 4px; font-family: inherit; font-size: 14px; }
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
    // v1.6.6: new credentials start unchecked; the next login records the result.
    def logins = state.sourceLogin ?: [:]
    def rec = (logins[src.id.toString()] ?: [:]) as Map
    rec.remove("ok")
    rec.remove("reason")
    logins[src.id.toString()] = rec
    state.sourceLogin = logins
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
        section(sectionClass: "app-ui-links reolink-removal-confirmation") {
            paragraph rawHtml: true, "<style>${tileSecondaryTextCss()} #formApp:has(.reolink-removal-confirmation) #fieldsetAppButtons button[value='Done'] { display: none !important; }</style>" +
                "<div class='p-message p-message-error reolink-message border-round p-3'>" +
                "<div class='font-semibold hub-text-danger'>Remove ${discoveryEscapeHtml(src.label)}?</div>" +
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
            href name: "confirmSourceRemoval", title: "<span class='hub-text-danger font-semibold'>Yes, remove this source</span>",
                description: "Permanently remove ${countText(devices.size(), 'device')} and this source",
                page: "removeSourcePage", params: [sourceId: sourceId, action: "remove", token: token]
        }
    }
}

/** Display-only markup; native Hubitat inputs and href actions remain in their sections. */
private String discoveryOverviewHtml(int channelCount, int deviceCount, boolean discoveryFailed) {
    String tone = discoveryFailed ? "p-message p-message-warn reolink-message" : "p-message p-message-success reolink-message"
    String icon = discoveryFailed ? "fa-solid fa-exclamation-triangle hub-text-warning" : "pi pi-check-circle hub-text-success"
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
    float: right; width: 38%; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); padding-left: 8px; box-sizing: border-box;
  }
  .reolink-discovery-recording { clear: right; }
  .reolink-discovery-danger {
    clear: both; padding: 0 8px; position: relative; box-sizing: border-box;
    width: calc(100% - 16px); margin: 0 8px 16px !important;
  }
  .reolink-discovery-danger::before {
    content: ''; position: absolute; left: 0; right: 0; top: -9px; border-top: 1px solid var(--surface-border, rgba(0,0,0,.12));
  }
  .reolink-discovery-connection > .mdl-grid {
    margin: 12px 8px 0; padding: 4px !important; border: 1px solid var(--surface-border, rgba(0,0,0,.12));
    border-radius: 4px; background: var(--surface-card, #fff);
  }
  .reolink-channel-toolbar {
    background: var(--surface-ground, #fafafa); border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px 4px 0 0;
    padding: 12px; margin-bottom: 0;
  }
  .reolink-channel-toolbar:not(:has(~ .reolink-channel-row)) { border-radius: 4px; margin-bottom: 8px; }
  .reolink-channel-row {
    background: var(--surface-ground, #fafafa); border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-top: 0;
    padding: 8px; margin-top: 0; margin-bottom: 0;
  }
  .reolink-channel-row:not(:has(~ .reolink-channel-row)) { border-radius: 0 0 4px 4px; margin-bottom: 8px; }
  .reolink-channel-row > .w-fit {
    width: 100% !important; box-sizing: border-box; background: var(--surface-card, #fff);
    border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; padding: 10px 12px;
  }
  .reolink-channel-row .mdl-switch, .reolink-channel-toolbar .mdl-switch,
  .reolink-connection-toggle .mdl-switch { height: auto; min-height: 24px; }
  .reolink-channel-row .mdl-switch__label { line-height: 24px; }
  .reolink-discovery-channels button.hrefElem {
    background: var(--surface-card, #fff); color: var(--hubitat-link-color, #1565c0); border: 1px solid var(--surface-border, rgba(0,0,0,.12));
    border-radius: 4px; box-shadow: none;
  }
  .reolink-discovery-channels button[name^='_action_href_tips|'] {
    height: 62px; box-sizing: border-box;
  }
  .reolink-discovery-channels button[name^='_action_href_tips|'] > .state-incomplete-text {
    display: inline-block; padding-bottom: 0.5em;
  }
  .reolink-discovery-channels button.hrefElem .state-incomplete-text { font-size: 14px; }
  .reolink-discovery-channels button.hrefElem::before { color: var(--hubitat-link-color, #1565c0); }
  .reolink-discovery-channels .mdl-cell:has(> button[name^='_action_href_runDiscovery|']) { text-align: right; }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|'] {
    display: inline-block; width: auto !important; min-height: 36px; padding: 0 16px;
    background: var(--hubitat-control-background, rgba(158,158,158,.2)); color: var(--hubitat-control-text, #363636); border: 0; border-radius: 4px;
    font-family: inherit; font-size: 14px; font-weight: 500; line-height: 36px;
    box-shadow: var(--hubitat-control-shadow, 0 2px 2px rgba(0,0,0,.14));
  }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|']::before,
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|'] > br,
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|'] > .state-incomplete-text { display: none; }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|']:hover { background: var(--hubitat-control-hover-background, #cacfdc); }
  .reolink-discovery-channels button[name^='_action_href_runDiscovery|']:focus-visible { outline: 2px solid var(--hubitat-link-color, #1565c0); outline-offset: 2px; }
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

        section(sectionClass: "app-ui-links reolink-presets-index app-ui-navigation") {
            paragraph rawHtml: true, presetsStylesHtml()
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>Presets</div>"
            if (!presets) paragraph "<span class='text-base text-color-secondary'>No presets yet. Add one below to get started.</span>"
            presets.keySet().eachWithIndex { name, index ->
                def btnNum = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()]?.get(name)
                href name: "selectPreset_${index}", page: "presetsPage",
                    params: [sourceId: sourceId, preset: name],
                    title: "<span class='hub-text-link ${name == selectedPreset ? 'app-ui-navigation-selected' : ''}'>${discoveryEscapeHtml(name)}</span>",
                    description: btnNum ? "Button ${btnNum}" : "", width: 12, style: "margin:0 8px;"
            }
            paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold border-top-1 surface-border pt-3 mt-3'>Add a preset</div>"
            input "newPresetName", "text", title: "New preset name (e.g. 'Away', 'Present')", submitOnChange: true
        }
        presets.each { name, chMap ->
            def btnNum = (state.recPresetButtonNumbers ?: [:])[sourceId.toString()]?.get(name)
            section(sectionClass: "app-ui-links reolink-preset-editor ${name == selectedPreset ? '' : 'reolink-preset-hidden'}") {
                paragraph rawHtml: true, "<div class='reolink-status-heading font-semibold'>${discoveryEscapeHtml(name)} " +
                    (btnNum ? "<span class='text-base p-tag p-tag-info border-round px-2 py-1 ml-2'>Button ${btnNum}</span>" : "") +
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

        section(sectionClass: "app-ui-links reolink-presets-help") {
            if (!presets) {
                paragraph rawHtml: true, "<div class='border-1 surface-border border-round p-3'><div class='reolink-status-heading font-semibold'>Create your first preset</div>" +
                    "<div class='text-base text-color-secondary mt-2'>Enter a name on the left, then configure what each channel records. New channels default to Don't manage, leaving their existing schedules untouched.</div></div>"
            }
            paragraph rawHtml: true, presetsGuidanceHtml(src?.label ?: "")
            input "advancedScheduleEditing_${sourceId}", "bool",
                title: "Advanced: edit raw per-hour schedule strings directly (power users only)",
                defaultValue: false, submitOnChange: true
            if (advancedMode) {
                paragraph "<span class='inline-block p-tag p-tag-warning font-bold text-xs px-2 py-1 " +
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
            section(sectionClass: "app-ui-links reolink-presets-actions") {
                paragraph rawHtml: true, "<div class='text-base text-color-secondary'>Actions for ${discoveryEscapeHtml(selectedPreset)}</div>"
                input "presetAction_delete_${sourceId}_${selectedButton}", "button",
                    title: "<i class='fa-regular fa-trash mr-2' aria-hidden='true'></i>Delete preset",
                    width: 6, submitOnChange: true, styleClass: "reolink-preset-delete", inputClass: "app-ui-button app-ui-button-danger"
                input "presetAction_save_${sourceId}_${selectedButton}", "button",
                    title: "<i class='fa-regular fa-floppy-disk mr-2' aria-hidden='true'></i>Save preset",
                    width: 6, submitOnChange: true, styleClass: "reolink-preset-save", inputClass: "app-ui-button app-ui-button-primary"
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
    float: right; width: 73%; padding-left: 8px; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); box-sizing: border-box;
  }
  .reolink-presets-help, .reolink-presets-actions { clear: right; }
  .reolink-preset-hidden { display: none !important; }
  .reolink-presets-index > .mdl-grid, .reolink-preset-editor > .mdl-grid,
  .reolink-presets-help > .mdl-grid, .reolink-presets-actions > .mdl-grid { padding: 4px 0 !important; }
  .reolink-presets-index .mdl-cell:has(> style) { display: none; }
  .reolink-preset-card-heading {
    font-size: 16px; background: var(--surface-ground, #fafafa); border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; padding: 12px;
  }
  .reolink-preset-channel {
    background: var(--surface-ground, #fafafa); border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; padding: 12px; box-sizing: border-box;
  }
  .reolink-presets-actions > .mdl-grid { border-top: 1px solid var(--surface-border, rgba(0,0,0,.12)); margin: 0 8px; }
  .reolink-preset-save { text-align: right; }
  .reolink-preset-editor .mdl-switch, .reolink-presets-help .mdl-switch { height: auto; min-height: 24px; }
  .reolink-preset-editor .mdl-switch__label, .reolink-presets-help .mdl-switch__label { line-height: 24px; }
  .reolink-preset-guidance { border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; margin-top: 16px; }
  .reolink-preset-guidance summary { padding: 12px; background: var(--surface-ground, #fafafa); cursor: pointer; list-style: none; font-size: 16px; }
  .reolink-preset-guidance summary::-webkit-details-marker { display: none; }
  .reolink-preset-guidance summary::after { content: '\\203A'; float: right; color: var(--hubitat-link-color, #1565c0); }
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
  <div class='reolink-status-heading font-semibold hub-text-link'><i class='pi pi-cog mr-2' aria-hidden='true'></i>Rule Machine shortcuts</div>
  <div class='text-base mt-2'>Use <b>Reolink Device Bridge (${discoveryEscapeHtml(sourceLabel)})</b> in Rule Machine's standard Switch and Button pickers. No Custom Action is needed.</div>
  <div class='text-base mt-2'><b>Switch on/off:</b> controls master recording for all channels.<br><b>Push button N:</b> loads the preset labeled Button N.</div>
  <div class='text-base mt-2'>You can also use the bridge's <b>Load Preset</b> command with a preset name (or blank for the one picked on the bridge's Preferences). Button numbers are permanent and never reused; a deleted preset's button does nothing.</div>
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
    state.lastEventRetry?.remove(id.toString())
    state.sourceLogin?.remove(id.toString())
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
        // v1.6.6: a token that's still valid means the login worked; show it without waiting for the next fresh login.
        if ((state.sourceLogin ?: [:])[sourceId.toString()]?.ok == null) recordLoginResult(sourceId, true)
        return src.token
    }

    // v1.6.14: the camera locked its login; wait out its unlock time instead of retrying into it.
    Long lockedUntil = ((state.loginLockUntil ?: [:])[sourceId.toString()] ?: 0) as Long
    if (now() < lockedUntil) {
        logFull "Reolink source ${sourceId}: login locked by the camera, ${(lockedUntil - now()).intdiv(1000)}s left"
        return null
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
        recordLoginResult(sourceId, false, "no response from the source")
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
            recordLoginResult(sourceId, false, "a different device answered, use Find changed IP")
            return null
        }
        if (uid && !src.identity?.uid) {
            src.identity = [version: 1, uid: uid, verifiedAt: now()]
            state.sources = (state.sources ?: []).collect { it.id == src.id ? src : it }
        }
        logNormal "Reolink source ${sourceId}: new token acquired, leaseTime=${leaseSec}s"
        if (state.loginLockUntil?.containsKey(sourceId.toString())) {
            def locks = state.loginLockUntil
            locks.remove(sourceId.toString())
            state.loginLockUntil = locks
        }
        markSourceReachable(sourceId)
        recordLoginResult(sourceId, true)
    } else {
        // The raw response is the camera's reply only; it never contains the password.
        log.warn "Reolink source ${sourceId}: Login response parsed but no Token.name found (check " +
            "credentials) -- raw: ${resp?.toString()?.take(500)}"
        recordLoginResult(sourceId, false, loginFailureReason(resp))
        noteLoginLock(sourceId, resp)
    }
    return token
}

/** v1.6.14: on "Login has been locked" (-506), no login attempts until the camera's unlock_time passes. */
private void noteLoginLock(sourceId, resp) {
    String raw = resp?.toString() ?: ""
    if (!raw.contains("-506") && !raw.toLowerCase().contains("has been locked")) return
    int sec = 300
    int at = raw.indexOf("unlock_time")
    if (at >= 0) {
        String digits = ""
        for (int i = at + 11; i < raw.length() && i < at + 24; i++) {
            String c = raw.substring(i, i + 1)
            if ("0123456789".contains(c)) digits += c
            else if (digits) break
        }
        if (digits) sec = digits as Integer
    }
    def locks = state.loginLockUntil ?: [:]
    locks[sourceId.toString()] = now() + (sec + 5) * 1000L
    state.loginLockUntil = locks
    log.warn "Reolink source ${sourceId}: the camera locked its login for ${sec}s after failed attempts. " +
        "The app will wait that long before logging in again."
}

// ---------- Login status and password display (v1.6.6) ----------

/** Bridges call this after a reboot or driver save; the password itself lives only in this app's state. */
def componentGetSourceSecret(sourceId) {
    return getSource(sourceId)?.password
}

private String passwordSummary(String password) {
    password ? "Saved, ${password.length()} characters" : "Not set"
}

/** "10:42 AM" for today, "Sep 30, 9:15 PM" otherwise, in the hub's time zone. */
private String shortTime(Long ms) {
    if (!ms) return ""
    def tz = location?.timeZone ?: TimeZone.getDefault()
    def d = new Date(ms)
    boolean today = d.format("yyyyMMdd", tz) == new Date().format("yyyyMMdd", tz)
    return d.format(today ? "h:mm a" : "MMM d, h:mm a", tz)
}

private String loginStatusText(Map rec) {
    if (rec?.ok == true) return "OK, ${shortTime(rec.at as Long)}"
    if (rec?.ok == false) return "Failed: ${rec.reason}"
    return "Not checked yet"
}

/** Records a fresh login result and shows it on the bridge. A repeat failure with the same reason writes nothing. */
private void recordLoginResult(sourceId, boolean ok, String reason = null) {
    def all = state.sourceLogin ?: [:]
    def key = sourceId.toString()
    Map rec = (all[key] ?: [:]) as Map
    if (!ok && rec.ok == false && rec.reason == reason) return
    rec.ok = ok
    rec.reason = ok ? null : reason
    rec.at = now()
    if (ok) rec.lastGood = rec.at
    all[key] = rec
    state.sourceLogin = all
    pushLoginStatusToBridge(sourceId)
}

private void pushLoginStatusToBridge(sourceId) {
    Map rec = (state.sourceLogin ?: [:])[sourceId.toString()] as Map
    try {
        getSourceBridge(sourceId)?.receiveLoginStatus(loginStatusText(rec),
            rec?.lastGood ? shortTime(rec.lastGood as Long) : null)
    } catch (e) { /* older bridge driver */ }
}

/** Plain-language reason from a rejected Login reply (the reply never contains the credentials). */
private String loginFailureReason(resp) {
    def err = (resp instanceof List && resp) ? resp[0]?.error : null
    String detail = err?.detail?.toString()?.toLowerCase() ?: ""
    if (detail.contains("lock") || detail.contains("max") || detail.contains("too many"))
        return "too many sessions or attempts, try again later"
    if (detail.contains("password") || detail.contains("user") || detail.contains("login"))
        return "wrong username or password"
    return detail ? "the source replied '${detail}'".toString() : "unexpected reply from the source"
}

/** v1.6.6: replaces the plain-text password in every existing bridge's state, once. */
def migrateCredentials() {
    state.remove("credMigrationQueued")
    int updated = 0
    (state.sources ?: []).each { src ->
        def bridge = getSourceBridge(src.id)
        if (!bridge) return
        try {
            bridge.configureConnection(src.host, BAICHUAN_PORT, src.username, src.password, src.id as Integer)
            pushLoginStatusToBridge(src.id)
            updated++
        } catch (e) {
            log.warn "Reolink source ${src.id}: could not update the bridge's password display -- ${e.message}"
        }
    }
    state.credMigrationKey = CRED_MIGRATION_KEY
    logNormal "Reolink Integration: password display updated on ${updated} bridge(s)"
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
        // v1.6.4: camera is back; resume event mode if it isn't connected.
        runIn(5, "retryStuckEventConnection", [overwrite: false, data: [sourceId: sourceId as Integer]])
    }
}

def reolinkApiCall(sourceId, String cmd, Map param = [:], Integer channel = null) {
    return reolinkApiOutcome(sourceId, cmd, param, channel).value
}

/** v1.6.11: same as reolinkApiCall() but returns [value, rspCode], so a caller can act on a specific refusal. */
private Map reolinkApiOutcome(sourceId, String cmd, Map param = [:], Integer channel = null) {
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    if (!token) {
        // Login already logged (or suppressed) the actual connection failure
        // above -- this is just the downstream consequence, not a new fact,
        // so it only needs Full-tier visibility, not its own warning.
        logFull "Reolink source ${sourceId}: no token available, aborting ${cmd}"
        return [value: null, rspCode: null, parseFailure: false]
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
    return outcome
}

/**
 * quiet=true suppresses the usual failure escalation (markSourceUnreachable
 * warn, or the JSON-parse-failure warn) and logs at Full tier instead. Used
 * by detectIsBattery()'s fallback probe below -- a failed GetBatteryInfo
 * probe must NOT be treated as evidence the whole SOURCE is unreachable.
 * timeoutSec lets a caller shorten the HTTP timeout below the normal 10s.
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
            state.lastDiscoveryError = "No response from ${src.host}. Check the IP address and HTTPS port, " +
                "and that HTTPS is enabled on the device. (Battery-only cameras and doorbells usually need " +
                "to be added through a Home Hub or NVR instead.)"
            return channels
        }
        // Skip battery detection entirely for a channel that already has a
        // child device -- isBattery is ONLY ever read at device CREATION time.
        def existing0 = bridgeForDiscovery?.getChildDevice(childDni(sourceId, 0)) != null
        channels << [channel: 0, name: info?.DevInfo?.name ?: src.label, deviceType: guessDeviceType(info),
            isBattery: existing0 ? null : detectIsBattery(sourceId, 0, abilityChnList?.getAt(0)),
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
                def abilityCh = abilityChnList?.getAt(ch.channel as Integer)
                def existingChild = existing ? bridgeForDiscovery.getChildDevice(childDni(sourceId, ch.channel)) : null
                String type = existingChild ? (existingChild.name == "Reolink Doorbell" ? "doorbell" : "camera") :
                    channelDeviceType(sourceId, ch, abilityCh)
                channels << [channel: ch.channel, name: ch.name ?: "Channel ${ch.channel}", deviceType: type,
                    isBattery: existing ? null : detectIsBattery(sourceId, ch.channel, abilityChnList?.getAt(ch.channel as Integer)),
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
 * v1.6.12: Hub/NVR channel type, never from the channel name (a Hub can report a doorbell as "Hub").
 * Same signals as reolink_aio: GetChannelstatus typeInfo (model, not sent by every device), then
 * GetAbility doorbellVersion, then GetAiState visitor support (3s quiet probe). Otherwise camera.
 */
private String channelDeviceType(sourceId, ch, Map abilityCh) {
    String model = (ch?.typeInfo ?: "").toString()
    if (model) return model.toLowerCase().contains("doorbell") ? "doorbell" : "camera"
    if (abilityCh != null && abilityPermit(abilityCh, "doorbellVersion") > 0) return "doorbell"
    def token = reolinkLogin(sourceId)
    if (!token) return "camera"
    def outcome = doReolinkApiCall(getSource(sourceId), sourceId, "GetAiState", token, [:], ch.channel as Integer, true, 3)
    def ai = outcome.value
    boolean visitor = ((ai?.visitor ?: ai?.aiState?.visitor)?.support ?: 0) as Integer == 1
    logFull "Reolink source ${sourceId} ch ${ch.channel}: no typeInfo, visitor support=${visitor}"
    return visitor ? "doorbell" : "camera"
}

/**
 * v1.6.5: GetAbility's per-channel "battery" ability decides first. An NVR
 * answers GetBatteryInfo even for PoE channels, with junk data (zero voltage,
 * shifted fields), so the probe is only a fallback when the ability is
 * missing, and its data must look like a real battery. The probe stays quiet
 * (Full logging only, never marks the source unreachable) with a short 3s
 * timeout, since wired channels commonly time out on it.
 */
private Boolean detectIsBattery(sourceId, channel, Map abilityChn) {
    String mode = batteryModeFromAbility(abilityChn)
    if (mode) return mode == "battery"
    def src = getSource(sourceId)
    def token = reolinkLogin(sourceId)
    if (!token) return false
    def outcome = doReolinkApiCall(src, sourceId, "GetBatteryInfo", token, [:], channel, true, 3)
    return looksLikeRealBattery(outcome.value, true)
}

/** "battery" or "wired" from GetAbility, or null if the ability isn't reported. */
private String batteryModeFromAbility(Map abilityChn) {
    if (abilityChn == null || !abilityChn.containsKey("battery")) return null
    return abilityPermit(abilityChn, "battery") > 0 ? "battery" : "wired"
}

/**
 * v1.6.5: a device's "battery"/"wired" mode, from its powerMode data value.
 * Falls back to the retired batteryMode attribute until migrateBatteryModes()
 * has converted the device. Null = unknown.
 */
private String powerModeOf(child) {
    String mode = child?.getDataValue("powerMode")
    if (mode) return mode
    try { return child?.currentValue("batteryMode") } catch (e) { return null }
}

/** Rejects NVR junk. Strict also requires a nonzero voltage (used when deciding battery vs wired). */
private boolean looksLikeRealBattery(value, boolean strict = false) {
    def b = value?.Battery ?: value
    def pct = b?.batteryPercent
    if (!(pct instanceof Number) || pct < 0 || pct > 100) return false
    if (!strict) return true
    def volt = b?.voltage
    return volt instanceof Number && volt > 0
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
    pushLoginStatusToBridge(sourceId)
    try { bridge.setLogRank(logLevelRank()) } catch (e) { /* older bridge driver */ }

    def wantEvent = settings["useEventSubscription_${sourceId}"] != false  // default true
    def currentStatus = state.sourceConnMode?.get(sourceId.toString())
    def currentlyRunning = currentStatus in ["connected", "connecting", "reconnecting"]
    // v1.6.4: the saved status is only a hint; confirm against the bridge's live traffic.
    def alive = currentlyRunning
    try { alive = bridge.isEventConnectionAlive() } catch (e) { /* older bridge driver: trust saved status */ }
    // v1.6.6: a live event connection logged in with the same credentials, so the login is good.
    if (alive == true) recordLoginResult(sourceId, true)
    if (wantEvent && !alive) {
        noteBridgeStart(sourceId)
        bridge.startEventSubscription()
        logNormal(currentlyRunning ?
            "Reolink source ${sourceId}: event connection wasn't running (saved status '${currentStatus}'), starting it" :
            "Reolink source ${sourceId}: event subscription starting")
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
        logFull "Reolink source ${sourceId}: event connection ${status}"
    }
    // v1.6.14: only a real drop (was connected) marks devices due now; a reconnect loop used to
    // re-mark them on every attempt, so a wired camera polled every few seconds.
    if (status != "connected" && prev == "connected") {
        childrenForSource(sourceId as Integer).each { markPollDueNow(it.deviceNetworkId) }
        runIn(1, "schedulerTick", [overwrite: true])
    }
}

/** v1.6.14: a source isn't polled for BRIDGE_START_GRACE_MS after its event connection starts. */
private void noteBridgeStart(sourceId, Long at = null) {
    def starts = state.bridgeStartAt ?: [:]
    starts[sourceId.toString()] = at ?: now()
    state.bridgeStartAt = starts
}

private boolean bridgeStarting(sourceId, long nowMs) {
    def at = (state.bridgeStartAt ?: [:])[sourceId.toString()]
    return at != null && nowMs < (at as Long) + BRIDGE_START_GRACE_MS
}

/** v1.6.14: starts one source's event connection per run, BRIDGE_START_GAP_SEC apart. */
def startNextBridge() {
    List q = new ArrayList((state.bridgeStartQueue ?: []) as List)
    if (!q) return
    def sid = q.remove(0)
    state.bridgeStartQueue = q
    long t0 = now()
    try {
        ensureSourceBridge(sid)
    } catch (e) {
        if (isLoadLimitError(e)) startLoadBackoff("starting source ${sid}")
        else log.warn "Reolink source ${sid}: could not start the event connection -- ${e.message}"
    }
    logFull "Reolink source ${sid}: bridge start took ${now() - t0} ms"
    if (q) runIn(BRIDGE_START_GAP_SEC, "startNextBridge", [overwrite: true])
}

/** v1.6.14: the hub's load limiter ("generates excessive hub load"). */
private boolean isLoadLimitError(e) {
    String s = e?.toString() ?: ""
    return s.contains("LimitExceeded") || s.contains("excessive hub load")
}

private boolean inLoadBackoff(long nowMs) {
    return nowMs < ((state.loadBackoffUntil ?: 0) as Long)
}

/** v1.6.14: pause polling and snapshots, longer each time it repeats (3, 5, 10, 15 minutes). */
private void startLoadBackoff(String where) {
    int level = Math.min(((state.loadBackoffLevel ?: 0) as Integer) + 1, LOAD_BACKOFF_MINUTES.size())
    int minutes = LOAD_BACKOFF_MINUTES[level - 1]
    state.loadBackoffLevel = level
    state.loadBackoffUntil = now() + minutes * 60000L
    log.warn "Reolink Integration: the hub is limiting this app for load (${where}). Pausing polling and " +
        "snapshots for ${minutes} minutes. If this repeats, raise the poll and snapshot intervals and check " +
        "that each source's event connection is up."
}

private int pollSeconds(child, boolean eventMode) {
    int s = (child.getSetting("pollIntervalSec") ?: 30) as Integer
    return eventMode ? Math.max(s, FALLBACK_POLL_FLOOR_SEC) : Math.max(s, 1)
}

private int snapshotSeconds(child, boolean eventMode) {
    int s = (child.getSetting("snapshotIntervalSec") ?: 30) as Integer
    return eventMode ? Math.max(s, FALLBACK_SNAPSHOT_FLOOR_SEC) : Math.max(s, 1)
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
 * A real event push means a battery device is already awake, so a
 * battery/charging check piggybacks for free instead of waiting for the
 * long scheduled interval. Opt-in per device (checkBatteryOnEventWake),
 * throttled per device (eventWakeBatteryThrottleSec, default 60s) so a
 * burst of pushes from one wake triggers one check. Wired devices are
 * skipped. Also pushes nextBatteryCheckDue forward so schedulerTick()
 * doesn't immediately re-check the same device.
 */
private void maybeCheckBatteryOnWake(child) {
    if (!child.hasCapability("Battery")) return
    if (powerModeOf(child) != "battery") return
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
/** v1.6.11: floodlight status push (cmd_id 291): the light's real on/off state, including when the camera turns it off itself. */
def componentEventSpotlightUpdate(child, sourceId, channelId, Integer status) {
    def c = getSourceBridge(sourceId as Integer)?.getChildDevice(childDni(sourceId, channelId as Integer))
    if (!c || !hasSpotlight(c) || status == null) return
    logFull "Reolink source ${sourceId} ch ${channelId}: spotlight ${status > 0 ? 'on' : 'off'} (camera push)"
    c.receiveSpotlight(status > 0 ? "on" : "off")
}

def componentEventSleepUpdate(child, sourceId, channelId, String sleepState) {
    logFull "Reolink source ${sourceId} ch ${channelId}: event sleep push -- '${sleepState}' (not yet acted on)"
}

/**
 * Reshapes a pushed status/AItype pair into the same Map shape
 * parseReolinkState() already expects from POLLING (GetAiState/GetMdState
 * JSON).
 */
private Map translateToLegacyShape(String status, String aiType) {
    // v1.6.12: both fields are comma-separated lists ("MD,visitor", "people,vehicle"), as in reolink_aio.
    // "other" is PIR motion on battery cameras.
    Set st = pushList(status)
    Set ai = pushList(aiType) - ["none"]
    def on = { boolean b -> [alarm_state: b ? 1 : 0] }
    return [
        mdState: [state: (st.contains("MD") || ai) ? 1 : 0],
        aiState: [people: on(ai.contains("people")), vehicle: on(ai.contains("vehicle")),
                  dog_cat: on(ai.contains("dog_cat")), package: on(ai.contains("package")),
                  visitor: on(st.contains("visitor"))]
    ]
}

private Set pushList(String v) {
    return (v ?: "").split(",").collect { it.trim() }.findAll { it } as Set
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
            // Power mode is set once here, at creation (the only moment
            // ch.isBattery holds a freshly-probed value; it's null on
            // re-discovery of an existing channel). schedulerTick()
            // backfills any device that still ends up without one.
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
 * v1.6.5: the device returns true when it needs validation, and the app
 * queues it here (a device calling back into the app mid-call is unsafe with
 * singleThreaded).
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
    if (child.receiveRtspConfig([host: src.host, username: src.username,
        password: src.password, channel: channel, rtspPort: 554,
        outputWidth: 640]) == true) {
        componentQueueRtspValidation(child, child.deviceNetworkId)
    }
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
    // v1.6.3: sockets don't survive a reboot; saved connection modes must not either.
    state.remove("sourceConnMode")
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
    // v1.6.5: power mode conversion (schedulerTick() also starts it without Done).
    if (state.powerMigrationKey != POWER_MIGRATION_KEY) runIn(15, "migrateBatteryModes")
    // v1.6.6: the queued flag never survives a Done (unschedule() above cancels the job).
    state.remove("credMigrationQueued")

    // v1.6.5: start the RTSP validation queue fresh (anything pending re-queues below).
    state.rtspQueue = []
    state.remove("rtspActive")
    state.remove("rtspRetries")

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
 * schedulerTick()'s power mode backfill by up to a full
 * batteryCheckIntervalHours after upgrading. This clears
 * nextBatteryCheckDue for every device with no power mode set, so the
 * backfill runs on the very next tick (~1s) instead. Devices with a valid
 * power mode are untouched.
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
            if (child.hasCapability("Battery") && powerModeOf(child) == null) {
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
            "${cleared} device(s) with no power mode set, so the fix takes effect on the next tick " +
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

/**
 * v1.6.5: converts every device to the powerMode data value + batteryWired
 * attribute. Mode comes from GetAbility, then the existing mode, then a live
 * probe. receiveBatteryMode() clears battery values on wired devices. Only
 * marked done when no device failed; schedulerTick() retries otherwise.
 */
def migrateBatteryModes() {
    state.lastPowerMigrationAttempt = now()
    int corrected = 0
    int converted = 0
    int failed = 0
    (state.sources ?: []).each { src ->
        def bridge = getSourceBridge(src.id)
        if (!bridge) return
        def abilities = fetchAbilityChnList(src.id)
        (bridge.getChildDevices() ?: []).each { child ->
            try {
                if (!child.hasCapability("Battery")) return
                def chn = child.getDataValue("channel") as Integer
                String previous = powerModeOf(child)
                // Ability first, then the existing mode, then a live probe (never skipped).
                String mode = batteryModeFromAbility(abilities?.getAt(chn)) ?: previous ?:
                    (detectIsBattery(src.id, chn, null) ? "battery" : "wired")
                child.receiveBatteryMode(mode)
                converted++
                if (previous && previous != mode) corrected++
            } catch (e) {
                failed++
                log.warn "Reolink Integration: power mode migration failed for ${child.displayName} -- ${e.message}"
            }
        }
    }
    if (!failed) {
        state.powerMigrationKey = POWER_MIGRATION_KEY
        state.remove("powerModeMigrated")
        state.remove("batteryWiredModeMigrated")
    }
    logNormal "Reolink Integration: power mode migration converted ${converted} device(s)" +
        (failed ? ", ${failed} failed (retrying in 10 minutes)" : "")
    if (corrected) log.info "Reolink Integration: corrected battery/wired mode on ${corrected} device(s)"
}

/** Auto-reverts Full back to Errors Only after 60 minutes -- Full is meant for actively chasing something, not a steady state. Reverting to Errors Only (not Normal) matches this app's actual default, so a forgotten Full session doesn't leave routine logging elevated indefinitely. */
def revertToNormalLogging() {
    app.updateSetting("logLevel", [type: "enum", value: "Errors Only"])
    pushLogRankToBridges(0)
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
    List startQueue = []
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
        def bridge = getSourceBridge(src.id)
        if (!bridge) return
        // v1.6.14: started one at a time by startNextBridge(), not all in this run.
        noteBridgeStart(src.id, now + startQueue.size() * BRIDGE_START_GAP_SEC * 1000L)
        startQueue << (src.id as Integer)
        bridge.getChildDevices()?.each { child ->
            def dni = child.deviceNetworkId
            if (!pollDue.containsKey(dni)) pollDue[dni] = now
            if (!snapDue.containsKey(dni)) snapDue[dni] = now
            if (!battDue.containsKey(dni)) battDue[dni] = now
        }
    }
    state.nextPollDue = pollDue
    state.nextSnapshotDue = snapDue
    state.nextBatteryCheckDue = battDue
    state.bridgeStartQueue = startQueue
    if (startQueue) runIn(1, "startNextBridge", [overwrite: true])
    runIn(POLL_TICK_SEC, "schedulerTick", [overwrite: true])
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
    if (inLoadBackoff(now())) return
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
        // v1.6.4: not subscribed at all (gave up, or stuck) -> retry, at most hourly.
        if (!stale) {
            def alive = true
            try { alive = bridge.isEventConnectionAlive() } catch (e) { /* older bridge driver */ }
            if (alive) recordLoginResult(src.id, true)
            def last = ((state.lastEventRetry ?: [:])[key] ?: 0) as Long
            if (!alive && now() - last >= EVENT_RETRY_INTERVAL_MS) retryEventConnection(src.id, "not connected")
        }
    }
}

/** v1.6.4: reachability-triggered retry (scheduled from markSourceReachable()). */
def retryStuckEventConnection(data) {
    def sourceId = data?.sourceId as Integer
    if (sourceId == null || settings["useEventSubscription_${sourceId}"] == false) return
    if (inLoadBackoff(now())) return
    def bridge = getSourceBridge(sourceId)
    if (!bridge) return
    def alive = true
    try { alive = bridge.isEventConnectionAlive() } catch (e) { return }
    if (!alive) retryEventConnection(sourceId, "camera reachable again")
}

/** v1.6.4: one app-driven retry; the bridge logs offline/back-online itself. */
private void retryEventConnection(sourceId, String reason) {
    def bridge = getSourceBridge(sourceId)
    if (!bridge) return
    def retries = state.lastEventRetry ?: [:]
    retries[sourceId.toString()] = now()
    state.lastEventRetry = retries
    logFull "Reolink source ${sourceId}: retrying event connection (${reason})"
    try {
        bridge.retryEventSubscription()
    } catch (e) {
        try { bridge.startEventSubscription() } catch (e2) { /* best effort */ }
    }
}

/**
 * Single central scheduler -- exactly ONE recurring timer exists for the
 * whole app, and each device's own due-time is tracked independently in
 * state (nextPollDue / nextSnapshotDue / nextBatteryCheckDue, keyed by DNI).
 *
 * Robustness: each device runs in its own try/catch, and the next tick is
 * re-armed in a finally block, so no single failure can stop the scheduler.
 *
 * v1.6.2: ticks every 1s only while at least one source needs polling
 * (event connection not healthy, or event mode turned off); otherwise every
 * IDLE_TICK_SEC. The state maps are only written back when something
 * actually changed. See the v1.6.2 header note for the load this fixes.
 */
def schedulerTick() {
    boolean anyPolling = false
    boolean anyPollingOnly = false
    boolean limited = false
    try {
        def nowMs = now()
        // v1.6.14: the hub is limiting this app; stay quiet until the pause ends.
        if (inLoadBackoff(nowMs)) {
            limited = true
            return
        }
        def pollDue = state.nextPollDue ?: [:]
        def snapDue = state.nextSnapshotDue ?: [:]
        def battDue = state.nextBatteryCheckDue ?: [:]
        boolean dirty = false
        int jobs = 0
        String limitedAt = null

        // v1.6.5: run the power mode migration on its own after a code update.
        if (state.powerMigrationKey != POWER_MIGRATION_KEY &&
            nowMs - ((state.lastPowerMigrationAttempt ?: 0) as Long) > POWER_MIGRATION_RETRY_MS) {
            state.lastPowerMigrationAttempt = nowMs
            runIn(2, "migrateBatteryModes", [overwrite: true])
        }
        // v1.6.6: mask the password on existing bridges once, without needing Done.
        if (state.credMigrationKey != CRED_MIGRATION_KEY && !state.credMigrationQueued) {
            state.credMigrationQueued = true
            runIn(3, "migrateCredentials", [overwrite: true])
        }

        (state.sources ?: []).each { src ->
            if (limited) return
            def bridge = getSourceBridge(src.id)
            if (!bridge) return
            def sourceConnected = isSourceEventConnected(src.id)
            boolean eventMode = settings["useEventSubscription_${src.id}"] != false
            boolean starting = !sourceConnected && eventMode && bridgeStarting(src.id, nowMs)
            if (!sourceConnected) anyPolling = true
            if (!eventMode) anyPollingOnly = true
            (bridge.getChildDevices() ?: []).each { child ->
                if (limited) return
                def dni = child.deviceNetworkId
                try {
                    // Battery level never arrives via push, so this runs
                    // regardless of sourceConnected (before the early return).
                    if (child.hasCapability("Battery") && nowMs >= ((battDue[dni] ?: 0) as Long)) {
                        // Missing power mode = unknown, backfill once (GetAbility first).
                        def powerMode = powerModeOf(child)
                        if (powerMode == null) {
                            log.warn "Reolink Integration: ${child.displayName} (${dni}) has no power mode set -- " +
                                "backfilling via a live probe"
                            def sid = child.getDataValue("sourceId") as Integer
                            def chn = child.getDataValue("channel") as Integer
                            def backfilled = detectIsBattery(sid, chn, fetchAbilityChnList(sid)?.getAt(chn)) ? "battery" : "wired"
                            child.receiveBatteryMode(backfilled)
                            powerMode = backfilled
                        }
                        def checkEnabled = child.getSetting("batteryCheckEnabled") == true
                        def hours = (child.getSetting("batteryCheckIntervalHours") ?: 12) as Integer
                        if (checkEnabled && hours > 0 && powerMode == "battery") {
                            componentCheckBattery(child)
                        }
                        // Re-armed even when skipped so a settings change is picked up.
                        battDue[dni] = nowMs + (Math.max(hours, 1) * 3600L * 1000L)
                        dirty = true
                    }
                    // Healthy event connection, or one still starting: skip polling.
                    // nextPollDue is left alone so a drop takes effect now.
                    if (sourceConnected || starting) return
                    // v1.6.14: a few fallback jobs per tick; whatever is left is still due next tick.
                    // Sources with events turned off keep their own pace.
                    if (eventMode && (jobs >= MAX_JOBS_PER_TICK || now() - nowMs > TICK_BUDGET_MS)) return
                    if (nowMs >= ((pollDue[dni] ?: 0) as Long)) {
                        long t0 = now()
                        boolean ok = pollChildNow(child)
                        if (eventMode) jobs++
                        pollDue[dni] = nowMs + (pollSeconds(child, eventMode) * 1000L)
                        dirty = true
                        logFull "Reolink source ${src.id}: poll of ${child.displayName} took ${now() - t0} ms"
                        if (!ok) {
                            limited = true
                            limitedAt = "polling ${child.displayName}"
                            return
                        }
                    }
                    if ((!eventMode || jobs < MAX_JOBS_PER_TICK) && nowMs >= ((snapDue[dni] ?: 0) as Long)) {
                        long t0 = now()
                        pollChildSnapshotNow(child)
                        if (eventMode) jobs++
                        snapDue[dni] = nowMs + (snapshotSeconds(child, eventMode) * 1000L)
                        dirty = true
                        logFull "Reolink source ${src.id}: snapshot of ${child.displayName} took ${now() - t0} ms"
                    }
                } catch (e) {
                    if (isLoadLimitError(e)) {
                        limited = true
                        limitedAt = "polling ${child.displayName}"
                    } else {
                        log.warn "Reolink Integration: schedulerTick() failed for device ${dni} -- ${e.message}. Skipping this device this tick, will retry next tick."
                    }
                    pollDue[dni] = nowMs + (pollSeconds(child, eventMode) * 1000L)
                    if (nowMs >= ((snapDue[dni] ?: 0) as Long)) snapDue[dni] = nowMs + (snapshotSeconds(child, eventMode) * 1000L)
                    dirty = true
                }
            }
        }

        if (dirty) {
            state.nextPollDue = pollDue
            state.nextSnapshotDue = snapDue
            state.nextBatteryCheckDue = battDue
        }
        if (limited) {
            startLoadBackoff(limitedAt ?: "polling")
        } else if (dirty && state.loadBackoffLevel) {
            state.loadBackoffLevel = 0
        }
        if (dirty) logFull "Reolink Integration: tick ran ${jobs} job(s) in ${now() - nowMs} ms"
    } catch (e) {
        if (isLoadLimitError(e)) {
            limited = true
            startLoadBackoff("scheduler")
        } else {
            log.warn "Reolink Integration: schedulerTick() failed outside the per-device loop -- ${e.message}"
            anyPolling = true
        }
    } finally {
        int next = limited ? IDLE_TICK_SEC : (anyPollingOnly ? 1 : (anyPolling ? POLL_TICK_SEC : IDLE_TICK_SEC))
        runIn(next, "schedulerTick", [overwrite: true])
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
private boolean pollChildNow(child) {
    def sourceId = child.getDataValue("sourceId") as Integer
    def channel = child.getDataValue("channel") as Integer
    def took

    def aiState = reolinkApiCall(sourceId, "GetAiState", [:], channel)
    def mdState = reolinkApiCall(sourceId, "GetMdState", [:], channel)
    def wasAsleep = child.currentValue("sleepStatus") == "asleep"

    if (aiState == null && mdState == null) {
        if (wasAsleep) {
            logFull "Reolink source ${sourceId} ch ${channel}: still no response, still asleep"
        } else {
            logNormal "Reolink source ${sourceId} ch ${channel}: no response, marking asleep"
        }
        took = child.markAsleep()
    } else {
        if (wasAsleep) {
            logNormal "Reolink source ${sourceId} ch ${channel}: response received, marking awake"
        } else {
            logFull "Reolink source ${sourceId} ch ${channel}: response received (still awake)"
        }
        took = child.parseReolinkState(aiState, mdState)
    }
    // v1.6.14: 1.6.14+ devices return true, so once one has, a null means the hub turned the call away.
    if (took == true) {
        if (!state.devicesConfirm) state.devicesConfirm = true
        return true
    }
    return !state.devicesConfirm
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
    // v1.6.5: Refresh also backfills a missing power mode, so a device never stays blank.
    def c = resolveChild(child, effectiveDni)
    if (c?.hasCapability("Battery") && !powerModeOf(c)) {
        try {
            def sid = c.getDataValue("sourceId") as Integer
            def chn = c.getDataValue("channel") as Integer
            c.receiveBatteryMode(detectIsBattery(sid, chn, fetchAbilityChnList(sid)?.getAt(chn)) ? "battery" : "wired")
        } catch (e) {
            log.warn "Reolink ${effectiveDni}: power mode backfill failed -- ${e.message}"
        }
    }
    if (hasSpotlight(c)) {
        long nowMs = now()
        Long last = SPOTLIGHT_READ_AT.get(effectiveDni) as Long
        if (last == null || nowMs - last >= SPOTLIGHT_READ_MIN_MS) {
            SPOTLIGHT_READ_AT.put(effectiveDni, nowMs)
            try { componentSetSpotlightAuto(c, null, effectiveDni) } catch (e) { logFull "Reolink ${effectiveDni}: spotlight mode read failed -- ${e.message}" }
        }
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

/**
 * Fetches a live snapshot. v1.6.14: no forced re-login and retry on failure (repeated logins
 * locked the camera out, -506); a "login first" reply clears the token for the next run instead.
 */
private byte[] fetchSnapshotBytes(src, sourceId, channel) {
    def token = reolinkLogin(sourceId)
    if (!token) return null
    return doFetchSnapshot(src, sourceId, token, channel)
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
                if (raw?.toLowerCase()?.contains("login first")) {
                    src.token = null
                    src.tokenExpires = 0
                }
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

// v1.6.11: HTTP refusals that reolink_aio retries over Baichuan (the event connection).
@Field static final List<Integer> BC_FALLBACK_CODES = [-4, -9, -12, -13, -16, -17]
// Reolink turns a manual spotlight off by itself (about 180s on battery cameras); re-read after that.
@Field static final int SPOTLIGHT_RECHECK_SEC = 190
// Battery cameras: the light only stays on while the camera is awake (~30s after a Baichuan command),
// so "on" is re-sent every SPOTLIGHT_RESEND_SEC for SPOTLIGHT_HOLD_SEC, then turned off, like a wired camera.
@Field static final int SPOTLIGHT_HOLD_SEC = 180
@Field static final int SPOTLIGHT_RESEND_SEC = 20
@Field static final java.util.concurrent.ConcurrentHashMap<String, Map> SPOTLIGHT_HOLD = new java.util.concurrent.ConcurrentHashMap()
// HTTP spotlight mode numbers -> Baichuan alarmMode (from reolink_aio).
@Field static final Map<Integer, Integer> WHITELED_MODE_HTTP_TO_BC = [0: 0, 1: 1, 2: 2, 3: 3, (-4): 4, 4: 5, 5: 6, (-6): 7]
@Field static final String FLOODLIGHT_MANUAL_XML =
    '<?xml version="1.0" encoding="UTF-8" ?>\n<body>\n<FloodlightManual version="1.1">\n<channelId>%d</channelId>\n' +
    '<status>%d</status>\n<duration>180</duration>\n</FloodlightManual>\n</body>\n'

/**
 * v1.6.11: Spotlight on/off. The spotlight attribute changes only once the camera accepts it.
 * Battery cameras go over Baichuan first (see componentSetSpotlight); wired cameras use HTTP.
 * A refusal in BC_FALLBACK_CODES (e.g. -13 on Home Hub Mini battery cameras) is retried over
 * the Bridge's event connection (Baichuan FloodlightManual, 180s), like reolink_aio.
 */
def componentSetSpotlight(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    SPOTLIGHT_HOLD.remove(c.deviceNetworkId)   // a new command ends any keep-awake in progress
    // Battery cameras: Baichuan first. Over HTTP the light only flashes from standby (camera goes
    // back to sleep within seconds); FloodlightManual carries a 180s duration. HTTP if that fails.
    if (powerModeOf(c) == "battery" && sendSpotlightBaichuan(c, sourceId, channel, on, false)) return
    setSpotlightHttp(c, sourceId, channel, on, true)
}

private boolean sendSpotlightBaichuan(c, Integer sourceId, Integer channel, Boolean on, boolean httpTried, int attempt = 1) {
    String xml = String.format(FLOODLIGHT_MANUAL_XML, channel, on ? 1 : 0)
    return getSourceBridge(sourceId)?.sendBaichuanCommand(288, channel, xml,
        [kind: "spotlight", dni: c.deviceNetworkId, on: on, httpTried: httpTried, attempt: attempt]) == true
}

// Status 400 = battery camera still waking; reolink_aio retries after 1.5s, up to 3 attempts.
@Field static final int BC_WAKE_ATTEMPTS = 3

def retrySpotlightBaichuan(Map data) {
    def c = resolveChild(null, data?.dni as String)
    if (!c) return
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    if (!sendSpotlightBaichuan(c, sourceId, channel, data.on as Boolean, data.httpTried as Boolean, data.attempt as Integer)
            && !(data.httpTried as Boolean)) {
        setSpotlightHttp(c, sourceId, channel, data.on as Boolean, false)
    }
}

private void setSpotlightHttp(c, Integer sourceId, Integer channel, Boolean on, boolean allowBaichuan) {
    def out = reolinkApiOutcome(sourceId, "SetWhiteLed", [WhiteLed: [channel: channel, state: (on ? 1 : 0)]], null)
    if (out.value != null) {
        spotlightConfirmed(c, on)
        return
    }
    if (allowBaichuan && out.rspCode in BC_FALLBACK_CODES) {
        if (sendSpotlightBaichuan(c, sourceId, channel, on, true)) {
            logFull "Reolink ${c.displayName}: spotlight refused over HTTP (rspCode ${out.rspCode}), sent over the event connection"
            return
        }
        log.warn "Reolink ${c.displayName}: spotlight not changed -- the camera refused it over HTTP and the event connection isn't up to retry"
        return
    }
    log.warn "Reolink ${c.displayName}: spotlight not changed -- the camera rejected the request (Full logging shows its reply)"
}

private void spotlightConfirmed(c, Boolean on) {
    c.receiveSpotlight(on ? "on" : "off")
    if (!on) return
    def bridge = getSourceBridge(c.getDataValue("sourceId") as Integer)
    if (bridge?.currentValue("connectionStatus") == "connected") {
        // Battery cameras: keep awake for the full 3 minutes, whichever path turned the light on.
        if (powerModeOf(c) == "battery" && !SPOTLIGHT_HOLD.containsKey(c.deviceNetworkId)) startSpotlightHold(c)
        // The floodlight push (cmd_id 291) reports the light going off; a re-read would wake a battery camera.
        return
    }
    runIn(SPOTLIGHT_RECHECK_SEC, "recheckSpotlight", [data: [dni: c.deviceNetworkId], overwrite: false])
}

/** Battery cameras: keeps the camera awake (and the light on) for SPOTLIGHT_HOLD_SEC by re-sending "on". */
private void startSpotlightHold(c) {
    String dni = c.deviceNetworkId
    long gen = now()
    SPOTLIGHT_HOLD.put(dni, [until: gen + SPOTLIGHT_HOLD_SEC * 1000L, gen: gen])
    runIn(SPOTLIGHT_RESEND_SEC, "spotlightKeepAwake", [data: [dni: dni, gen: gen], overwrite: false])
}

def spotlightKeepAwake(Map data) {
    String dni = data?.dni as String
    Map hold = SPOTLIGHT_HOLD.get(dni)
    if (!hold || hold.gen != (data.gen as Long)) return   // cancelled, or replaced by a newer command
    def c = resolveChild(null, dni)
    if (!c || c.currentValue("spotlight") != "on") {        // turned off another way (Reolink app, camera)
        SPOTLIGHT_HOLD.remove(dni)
        return
    }
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    long left = (hold.until as Long) - now()
    def bridge = getSourceBridge(sourceId)
    if (left <= 1000L) {
        SPOTLIGHT_HOLD.remove(dni)
        logFull "Reolink ${c.displayName}: spotlight hold done (${SPOTLIGHT_HOLD_SEC}s), turning it off"
        bridge?.sendBaichuanCommand(288, channel, String.format(FLOODLIGHT_MANUAL_XML, channel, 0),
            [kind: "spotlight", dni: dni, on: false, httpTried: true])
        return
    }
    bridge?.sendBaichuanCommand(288, channel, String.format(FLOODLIGHT_MANUAL_XML, channel, 1),
        [kind: "spotlight", dni: dni, on: true, httpTried: true, hold: true])
    logFull "Reolink ${c.displayName}: spotlight keep-awake resend (${(left / 1000L) as Integer}s left)"
    int next = Math.min(SPOTLIGHT_RESEND_SEC, Math.max(1, (left / 1000L) as Integer))
    runIn(next, "spotlightKeepAwake", [data: [dni: dni, gen: hold.gen], overwrite: false])
}

/** Re-reads the light after Reolink's own manual-on timer, so the attribute doesn't stay "on". */
def recheckSpotlight(Map data) {
    def c = resolveChild(null, data?.dni as String)
    if (!c) return
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def current = reolinkApiCall(sourceId, "GetWhiteLed", [:], channel)
    String key = (current instanceof Map) ? current.keySet().find { it.toString().equalsIgnoreCase("WhiteLed") } : null
    Integer st = (key && current[key] instanceof Map) ? toIntOrNull(current[key].state) : null
    if (st != null) c.receiveSpotlight(st > 0 ? "on" : "off")
}

/**
 * v1.6.11: replies to settings sent over a Bridge's event connection (status null = no reply).
 * spotlightAutoRead/Write: read the light settings (289), change the mode, write them back (290).
 */
def componentBaichuanReply(bridge, Map ctx, Integer status, String body) {
    def c = resolveChild(null, ctx?.dni as String)
    if (!c) return
    String why = status == null ? "no reply" : "status ${status}"
    switch (ctx.kind) {
        case "spotlight":
            if (ctx.hold) {
                if (status != 200) {
                    logFull "Reolink ${c.displayName}: spotlight keep-awake resend not accepted (${why}), resending over HTTP"
                    reolinkApiOutcome(c.getDataValue("sourceId") as Integer, "SetWhiteLed",
                        [WhiteLed: [channel: c.getDataValue("channel") as Integer, state: 1]], null)
                }
                break
            }
            int attempt = (ctx.attempt ?: 1) as Integer
            if (status == 200) {
                logNormal "Reolink ${c.displayName}: spotlight ${ctx.on ? 'on' : 'off'} (sent over the event connection)"
                spotlightConfirmed(c, ctx.on as Boolean)
            } else if (status == 400 && attempt < BC_WAKE_ATTEMPTS) {
                logFull "Reolink ${c.displayName}: camera still waking (status 400), retrying in 1.5s (attempt ${attempt + 1} of ${BC_WAKE_ATTEMPTS})"
                runInMillis(1500, "retrySpotlightBaichuan",
                    [data: [dni: c.deviceNetworkId, on: ctx.on, httpTried: ctx.httpTried ?: false, attempt: attempt + 1], overwrite: false])
            } else if (!ctx.httpTried) {
                logFull "Reolink ${c.displayName}: spotlight over the event connection failed (${why}), trying HTTP"
                setSpotlightHttp(c, c.getDataValue("sourceId") as Integer, c.getDataValue("channel") as Integer, ctx.on as Boolean, false)
            } else {
                log.warn "Reolink ${c.displayName}: spotlight not changed -- the camera refused it over the event connection too (${why})"
            }
            break
        case "spotlightAutoRead":
            if (status != 200 || !body?.contains("<FloodlightTask")) {
                logFull "Reolink ${c.displayName}: light settings read over the event connection failed (${why}) -- raw: ${body?.take(300)}"
                log.warn "Reolink ${c.displayName}: spotlight auto not changed -- the camera didn't return its light settings (${why})"
                break
            }
            Integer target = ctx.target as Integer
            String bc = (WHITELED_MODE_HTTP_TO_BC[target] ?: target).toString()
            int start = body.indexOf("<FloodlightTask")
            int end = body.indexOf("</FloodlightTask>", start)
            if (end < 0) end = body.length()
            String task = body.substring(start, end)
                .replaceFirst(/<alarmMode>[^<]*<\/alarmMode>/, "<alarmMode>${bc}</alarmMode>")
                .replaceFirst(/<enable>[^<]*<\/enable>/, "<enable>${bc}</enable>")
            String xml = body.substring(0, start) + task + body.substring(end)
            if (bridge?.sendBaichuanCommand(290, c.getDataValue("channel") as Integer, xml,
                    [kind: "spotlightAutoWrite", dni: c.deviceNetworkId, target: target]) != true) {
                log.warn "Reolink ${c.displayName}: spotlight auto not changed -- the event connection isn't up"
            }
            break
        case "spotlightAutoWrite":
            if (status == 200) {
                logNormal "Reolink ${c.displayName}: spotlight auto set to ${spotlightModeName(ctx.target as Integer)} (sent over the event connection)"
                c.receiveSpotlightAuto(ctx.target as Integer)
            } else {
                log.warn "Reolink ${c.displayName}: spotlight auto not changed -- the camera refused it over the event connection too (${why})"
            }
            break
        default:
            break
    }
}

/**
 * v1.6.8: spotlight auto (motion) mode on/off; on == null reads only. Reads
 * WhiteLed first to remember the current auto mode, then writes {channel, mode}.
 */
def componentSetSpotlightAuto(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def current = reolinkApiCall(sourceId, "GetWhiteLed", [:], channel)
    String key = (current instanceof Map) ? current.keySet().find { it.toString().equalsIgnoreCase("WhiteLed") } : null
    Integer mode = (key && current[key] instanceof Map) ? toIntOrNull(current[key].mode) : null
    Integer lightState = (key && current[key] instanceof Map) ? toIntOrNull(current[key].state) : null
    if (lightState != null) c.receiveSpotlight(lightState > 0 ? "on" : "off")
    if (mode == null) {
        if (on == null) {
            logFull "Reolink ${c.displayName}: no spotlight mode returned -- raw: ${current?.toString()?.take(300)}"
        } else {
            log.warn "Reolink ${c.displayName}: spotlight auto not changed -- the camera returned no spotlight settings (no spotlight, or it didn't answer)"
        }
        return
    }
    if (on == null || (on ? mode > 0 : mode == 0)) {
        c.receiveSpotlightAuto(mode)
        if (on != null) logNormal "Reolink ${c.displayName}: spotlight auto already ${spotlightModeName(mode)}"
        return
    }
    if (mode > 0) c.receiveSpotlightAuto(mode)
    Integer saved = toIntOrNull(c.getDataValue("spotlightAutoMode"))
    int target = on ? ((saved != null && saved > 0) ? saved : 1) : 0
    def out = reolinkApiOutcome(sourceId, "SetWhiteLed", [WhiteLed: [channel: channel, mode: target]], null)
    if (out.value != null) {
        logNormal "Reolink ${c.displayName}: spotlight auto set to ${spotlightModeName(target)}"
        c.receiveSpotlightAuto(target)
    } else if (out.rspCode in BC_FALLBACK_CODES) {
        // v1.6.11: read-modify-write over the event connection; finished in componentBaichuanReply().
        if (getSourceBridge(sourceId)?.sendBaichuanCommand(289, channel, "", [kind: "spotlightAutoRead", dni: c.deviceNetworkId, target: target]) == true) {
            logFull "Reolink ${c.displayName}: spotlight auto refused over HTTP (rspCode ${out.rspCode}), retrying over the event connection"
        } else {
            log.warn "Reolink ${c.displayName}: spotlight auto not changed -- the camera refused it over HTTP and the event connection isn't up to retry"
        }
    } else {
        log.warn "Reolink ${c.displayName}: spotlight auto not changed -- the camera rejected the request (Full logging shows its reply)"
    }
}

private String spotlightModeName(Integer mode) {
    if (mode == 0) return "Off"
    if (mode == 1) return "Night Smart Mode"
    return "mode ${mode}"
}

private Integer toIntOrNull(v) {
    if (v == null) return null
    try { return v as Integer } catch (e) { return null }
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
 * v1.6.9: manual recording (same call as Home Assistant's Manual record switch).
 * SetManualRec {channel, enable, duration}; the camera stops after duration seconds
 * (max 600). The app also sends off at that time, and updates the manualRecord attribute.
 */
def componentSetManualRecord(child, Boolean on, Integer seconds = null, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    int secs = Math.max(1, Math.min(MANUAL_REC_MAX_SEC, (seconds != null && seconds > 0) ? seconds : MANUAL_REC_MAX_SEC))
    Map rec = [channel: channel, enable: on ? 1 : 0]
    if (on) rec.duration = secs
    def result = reolinkApiCall(sourceId, "SetManualRec", [Rec: rec], null)
    if (result?.rspCode == 200 || result?.rspCode == 0) {
        logNormal "Reolink ${c.displayName}: manual recording ${on ? "on for ${secs}s" : 'off'}"
        c.receiveManualRecord(on)
        c.updateDataValue("manualRecordUntil", on ? "${now() + secs * 1000L}" : "0")
        if (on) runIn(secs, "manualRecordAutoOff", [overwrite: false, data: [dni: c.deviceNetworkId]])
    } else {
        log.warn "Reolink ${c.displayName}: manual recording not changed -- the camera rejected it or doesn't support it (Full logging shows its reply)"
    }
}

@Field static final int MANUAL_REC_MAX_SEC = 600

/** v1.6.9: ends a timed manual recording. Harmless if it already stopped or was turned off. */
def manualRecordAutoOff(Map data) {
    def c = data?.dni ? resolveChild(null, data.dni as String) : null
    if (!c || c.currentValue("manualRecord") != "on") return
    // A newer, longer Manual Record on the same device owns the stop time.
    Long until = c.getDataValue("manualRecordUntil")?.isLong() ? c.getDataValue("manualRecordUntil") as Long : 0L
    if (until > now() + 1500L) return
    componentSetManualRecord(c, false, null, data.dni as String)
}

/**
 * v1.6.6: PIR on/off as a read-modify-write (a partial SetPirInfo is rejected,
 * same as recording schedules). Reports the confirmed result to the camera.
 */
def componentSetPir(child, Boolean on, String dni = null) {
    def c = resolveChild(child, dni)
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def current = reolinkApiCall(sourceId, "GetPirInfo", [:], channel)
    String key = (current instanceof Map) ? current.keySet().find { it.toString().equalsIgnoreCase("pirInfo") } : null
    if (!key || !(current[key] instanceof Map)) {
        log.warn "Reolink ${c.displayName}: PIR not changed -- the camera returned no PIR settings (no PIR sensor, or it didn't answer)"
        c.receivePirState(on, "camera has no PIR, or didn't answer")
        return
    }
    Map pir = deepCopyRec(current[key] as Map)
    pir.channel = channel
    pir.enable = on ? 1 : 0
    def result = reolinkApiCall(sourceId, "SetPirInfo", [(key): pir], null)
    if (result?.rspCode == 200 || result?.rspCode == 0) {
        logNormal "Reolink ${c.displayName}: PIR ${on ? 'enabled' : 'disabled'}"
        c.receivePirState(on, null)
    } else {
        log.warn "Reolink ${c.displayName}: PIR not changed -- the camera rejected the request (Full logging shows its reply)"
        c.receivePirState(on, "camera rejected the change")
    }
}

/** v1.6.5: skipped for wired devices; implausible (NVR junk) battery data is ignored. */
def componentCheckBattery(child, String dni = null) {
    def c = resolveChild(child, dni)
    if (powerModeOf(c) == "wired") {
        logNormal "Reolink ${c.deviceNetworkId}: wired device, battery check skipped"
        return
    }
    def sourceId = c.getDataValue("sourceId") as Integer
    def channel = c.getDataValue("channel") as Integer
    def battInfo = reolinkApiCall(sourceId, "GetBatteryInfo", [:], channel)
    if (battInfo != null && !looksLikeRealBattery(battInfo)) {
        logFull "Reolink source ${sourceId} ch ${channel}: ignoring implausible battery data -- ${battInfo}"
        return
    }
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
    if (features?.contains("Spotlight") && c.hasCommand("setSpotlight")) componentSetSpotlightAuto(c, null, dni)
}

/** Spotlight devices on the camera driver (doorbells have no spotlight commands). */
private boolean hasSpotlight(c) {
    return c?.hasCommand("setSpotlight") && c.currentValue("supportedFeatures")?.toString()?.contains("Spotlight")
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
 * Read-only lookup used by the bridge's Preferences page to populate a
 * live "Preset to load" dropdown -- see ReolinkDeviceBridge.groovy's
 * getAvailablePresetNames(). Returns the current preset names for this
 * source, sorted for a stable dropdown order across page loads.
 */
def componentGetPresetNames(sourceId) {
    def presets = (state.recPresets ?: [:])[sourceId.toString()] ?: [:]
    return presets.keySet().sort()
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

// ---------- RTSP validation queue (v1.6.5) ----------

/**
 * A Hub/NVR can't open several validation streams at once, so devices
 * validate one at a time, RTSP_GAP_SEC apart. Called by the app itself
 * (configureRtspChild) or by a device (via its bridge) on save/refresh.
 */
def componentQueueRtspValidation(child, String dni = null) {
    String d = dni ?: child?.deviceNetworkId
    if (!d) return
    List q = state.rtspQueue ?: []
    if (state.rtspActive?.dni != d && !q.contains(d)) {
        q << d
        state.rtspQueue = q
    }
    // Always a fresh execution, never a nested call back into the device that asked.
    runIn(1, "processRtspQueue", [overwrite: true])
}

/** Starts the next queued validation if none is running. */
def processRtspQueue() {
    if (state.rtspActive) return
    List q = state.rtspQueue ?: []
    while (q) {
        String d = q.remove(0)
        def c = getSourceBridgeForChannelDni(d)?.getChildDevice(d)
        if (!c) continue
        state.rtspQueue = q
        state.rtspActive = [dni: d, startedAt: now()]
        runIn(RTSP_WATCHDOG_SEC, "rtspValidationWatchdog", [data: [dni: d], overwrite: true])
        boolean started = false
        try { started = c.startRtspValidation() == true } catch (e) { log.warn "Reolink ${d}: ${e.message}" }
        if (!started) {
            log.warn "Reolink ${d}: could not start RTSP validation"
            state.remove("rtspActive")
            unschedule("rtspValidationWatchdog")
            runIn(RTSP_GAP_SEC, "processRtspQueue", [overwrite: true])
        }
        return
    }
    state.rtspQueue = q
}

/**
 * Reported by the device when its validation finishes. Timeouts retry after
 * RTSP_RETRY_DELAYS. Returns [attempt, delay] when a retry is scheduled so the
 * device can show it (a call back into the device here would be unsafe).
 */
def componentRtspValidationDone(child, String dni, Boolean success, String message) {
    Map retryInfo = null
    String d = dni ?: child?.deviceNetworkId
    if (state.rtspActive?.dni == d) {
        state.remove("rtspActive")
        unschedule("rtspValidationWatchdog")
    }
    Map retries = state.rtspRetries ?: [:]
    String msg = message?.toLowerCase() ?: ""
    if (!success && (msg.contains("timed out") || msg.contains("request failed"))) {
        int attempt = ((retries[d] ?: 0) as int) + 1
        if (attempt <= RTSP_RETRY_DELAYS.size()) {
            retries[d] = attempt
            int delay = RTSP_RETRY_DELAYS[attempt - 1]
            retryInfo = [attempt: attempt, delay: delay]
            runIn(delay, "requeueRtspValidation", [data: [dni: d], overwrite: false])
            logNormal "Reolink ${d}: RTSP validation timed out, retry ${attempt} in ${delay}s"
        } else {
            retries.remove(d)
            log.warn "Reolink ${d}: RTSP validation still timing out after ${RTSP_RETRY_DELAYS.size()} retries. " +
                "Save the device's preferences to try again."
        }
    } else {
        retries.remove(d)
    }
    state.rtspRetries = retries
    runIn(RTSP_GAP_SEC, "processRtspQueue", [overwrite: true])
    return retryInfo
}

def requeueRtspValidation(data) {
    componentQueueRtspValidation(null, data?.dni)
}

/** Keeps the queue moving if a device never reports back. */
def rtspValidationWatchdog(data) {
    if (state.rtspActive?.dni != data?.dni) return
    logFull "Reolink ${data.dni}: no RTSP validation result after ${RTSP_WATCHDOG_SEC}s, moving on"
    state.remove("rtspActive")
    processRtspQueue()
}

// ---------- Logging ----------

/** v1.6.3: pushes the log rank to every bridge so they skip suppressed messages locally. */
private void pushLogRankToBridges(Integer rank = null) {
    int r = rank != null ? rank : logLevelRank()
    (state.sources ?: []).each { src ->
        try { getSourceBridge(src.id)?.setLogRank(r) } catch (e) { /* older bridge driver */ }
    }
    state.pushedLogLevel = LOG_LEVELS[r]
}

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
