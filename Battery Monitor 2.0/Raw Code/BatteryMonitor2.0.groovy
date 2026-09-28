/**
 * Battery Monitor 2.0
 * Version: 2.6.0
 *
 * Tracks battery levels, drain, health, and replacements for selected devices.
 * Scans on a recurring schedule plus each device's own battery events. Health is
 * a confidence-weighted, EWMA-smoothed drain average; trend reacts faster to
 * recent readings. Replacements are auto-detected from a confirmed upward jump.
 * Optional OAuth web portal serves a live dashboard.
 *
 * v2.6.0 -- Li-ion cliff-drop detection (graduated from beta): urgent alert when a
 * cliff-enabled device drops by the configured threshold (default 40%) between two
 * consecutive readings. Auto-enabled for LIR types; opt-in for 18650/RCR123A/RCR2.
 * Bypasses snooze by default. Cliff alerts now respect the notifications master switch.
 * Summary page redesigned: filter counts, needs-attention list with a to-replace total,
 * five-column table with type pills, built-in sort/search (no DataTables CDN), phone
 * layout. Drain under 0.01%/day now shows as <0.01 instead of 0.00 (display only).
 * Device Actions (two-pane, real buttons with confirm), Bulk Actions (one action,
 * quick select, fixes silently skipped combined actions), Device Management,
 * Auto-Detection, Battery Types, and History pages redesigned. Report links are cards.
 * Removed orphaned Ignored Devices and Bulk Result pages; one shared battery type list.
 * Main page: Reports cards (tinted) under the banner; settings moved from collapsible
 * sections into a settings list with live values. Phone layouts added across all pages.
 * Web portal rebuilt on the Summary layout (dark theme): filter counts, needs attention,
 * to-replace total, issues-only, search, phone layout.
 * Fixed: replacing a dead or near-empty battery (5% or below) was never auto-detected,
 * since samples are cleared at ~0% and detection required 3. Pending day counter capped.
 * Fixed: replacement history sorted by month instead of date across a year boundary.
 * Cliff detection now re-defaults when a device's battery type changes (LIR on, others off).
 * v2.5.34 -- UI refresh (pattern from Reolink Integration v1.6.1): status banner
 * on the main page, Help & Support cards, version footer, and a new Tips &
 * Troubleshooting page replacing the App Guide. Added tips for fixed Poor/Fair
 * thresholds, readings that look wrong, and scan logging. Corrected the guide's
 * confidence note (full confidence at 5 samples, not 10). No logic changes.
 * v2.5.32 -- enablePush is now the master switch for all notification targets.
 * v2.5.30 -- Simplified replacement detection to a confirmed minimum jump;
 * Battery Types page split into unassigned/assigned sections.
 *
 * Full history in GitHub commit history.
 */
import groovy.transform.Field

definition(
    name: "Battery Monitor 2.0",
    namespace: "jdthomas24",
    author: "Jdthomas24",
    description: "Advanced Hubitat battery monitoring with analytics, trends and replacement tracking. Recurring scan schedule, confidence-weighted health, EWMA smoothing.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    iconUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Tests%20-%20Groovy%20RAW/Battery%20Monitor%202.0%20BETA%20Tests",
    iconX2Url: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    version: "2.6.0",
    doNotFocus: true,
    oauth: true
)

@Field static final String APP_VERSION = "2.6.0"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/release-battery-monitor-2-0/162329"
@Field static final String COFFEE_URL = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final String DEFAULT_TIP_TOPIC = "best"

// ============================================================
// ===================== OAUTH MAPPINGS ======================
// ============================================================
mappings {
    path("/dashboard") { action: [GET: "serveDashboardPage"]  }
    path("/refresh")   { action: [GET: "forceRefreshEndpoint"] }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    if (debugMode) log.debug "Installed - initializing app"
    applyCustomLabel()
    initialize()
}

def updated() {
    if (debugMode) log.debug "Updated - re-initializing app"
    applyCustomLabel()
    unschedule()
    unsubscribe()

    initialize()

    runIn(1800, disableDebugLogging)

    // Retired confirm-toggle settings from pre-2.6.0 action pages
    ["ddReplaceConfirm", "ddResetConfirm", "ddIgnoreConfirm", "ddLastDeviceId",
     "bulkReplaceConfirm", "bulkResetConfirm", "bulkIgnoreConfirm", "bulkUnignoreConfirm",
     "snoozeConfirm", "snoozeConfirmClear"].each {
        if (settings.containsKey(it)) app.removeSetting(it)
    }

    def devList    = autoDevices ?: []
    def currentIds = devList.collect { it.id as String }
    state.history?.keySet()?.findAll { !currentIds.contains(it) }?.each { removedId ->
        state.history.remove(removedId)
        state.trend?.remove(removedId)
        state.cliffLastAlert?.remove(removedId)
        state.cliffTypeSeen?.remove(removedId)
        if (debugMode) log.debug "Cleaned up removed device: ${removedId}"
    }

    if (state.replacements) {
        def before = state.replacements.size()
        state.replacements = state.replacements.findAll { r ->
            r.deviceId ? currentIds.contains(r.deviceId) : true
        }
        def pruned = before - state.replacements.size()
        if (pruned > 0 && debugMode) log.debug "Purged ${pruned} orphaned replacement history entr${pruned == 1 ? 'y' : 'ies'}"
    }

    if (state.pendingReplacement) {
        def pendingBefore = state.pendingReplacement.size()
        state.pendingReplacement = state.pendingReplacement.findAll { id, _ ->
            currentIds.contains(id)
        }
        def pendingPruned = pendingBefore - state.pendingReplacement.size()
        if (pendingPruned > 0 && debugMode) log.debug "Pruned ${pendingPruned} orphaned pending-replacement entr${pendingPruned == 1 ? 'y' : 'ies'}"
    }

    def migrationDirty  = false
    def migratedHistory = [:]
    state.history?.each { id, data ->
        if (data && !data.firstSeenDate) {
            def newData = new HashMap(data)
            newData.firstSeenDate = newData.replacedTime ?: newData.lastDate ?: now()
            migratedHistory[id]   = newData
            migrationDirty        = true
            if (debugMode) log.debug "Migrated firstSeenDate for device ${id}: ${new Date(newData.firstSeenDate as long)}"
        } else {
            migratedHistory[id] = data
        }
    }
    if (migrationDirty) state.history = migratedHistory

    def didMigrate = false
    state.replacements?.each { r ->
        if (!r.deviceId) {
            def match = autoDevices?.find { it.displayName == r.device }
            if (match) {
                r.deviceId = match.id
                didMigrate = true
                if (debugMode) log.debug "Migrated replacement entry deviceId for ${r.device}: ${r.deviceId}"
            }
        }
    }
    if (didMigrate) state.replacements = state.replacements

    def devList2 = autoDevices ?: []
    devList2.each { device ->
        def legacyInfo = settings["battInfo_${device.id}"]
        if (legacyInfo && legacyInfo != "" && !legacyInfo.startsWith("_sep") && !settings["battType_${device.id}"]) {
            def parts = legacyInfo.tokenize(" x")
            if (parts.size() >= 2) {
                def migratedType  = parts[0..-2].join(" ")
                def migratedCount = parts[-1]
                app.updateSetting("battType_${device.id}",  [value: migratedType,  type: "enum"])
                app.updateSetting("battCount_${device.id}", [value: migratedCount.toInteger(), type: "number"])
            } else {
                app.updateSetting("battType_${device.id}", [value: legacyInfo, type: "enum"])
                app.updateSetting("battCount_${device.id}", [value: 1, type: "number"])
            }
            app.removeSetting("battInfo_${device.id}")
            if (debugMode) log.debug "Migrated battery catalog entry for ${device.displayName}: ${legacyInfo}"
        }

    }
    syncCliffDefaults()
}

def disableDebugLogging() {
    log.info "Battery Monitor: auto-disabling debug logging after 30 minutes"
    app.updateSetting("debugMode", [value: false, type: "bool"])
}

def initialize() {
    if (debugMode) log.debug "Initialization complete"
    if (state.replacements         == null) state.replacements         = []
    if (state.history              == null) state.history              = [:]
    if (state.trend                == null) state.trend                = [:]
    if (state.notifSnoozedUntil    == null) state.notifSnoozedUntil    = 0
    if (state.ignoredDeviceIds     == null) state.ignoredDeviceIds     = []
    if (state.pendingReplacement   == null) state.pendingReplacement   = [:]

    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.error "Battery Monitor: OAuth is not enabled. Please enable OAuth in the App Code screen."
        }
    }

    scheduleReportFrequency()
    scheduleScanInterval()

    def devList = autoDevices ?: []
    if (devList) {
        subscribe(devList, "battery", batteryHandler)
    }
}

def applyCustomLabel() {
    if (settings?.customAppName) {
        if (app.label != settings?.customAppName) {
            app.updateLabel(settings.customAppName)
            if (debugMode) log.debug "App label updated to: ${settings.customAppName}"
        }
    }
}

// ============================================================
// ===================== IGNORED DEVICE HELPER ===============
// ============================================================
def isIgnored(device) {
    if (!device) return false
    def ignoredIds = (settings?.ignoredDevices?.collect { it as String }) ?: []
    return ignoredIds.contains(device.id as String)
}

// ============================================================
// ===================== CLIFF-DROP DETECTION ================
// ============================================================
// LIR types are always Li-ion, so cliff detection auto-enables. 18650/RCR123A/RCR2 are
// form-factor labels (LiFePO4 variants exist), so they default off until confirmed.
def autoCliffTypes()   { return ["LIR2016", "LIR2032", "LIR2430", "LIR2450"] }
def manualCliffTypes() { return ["18650", "RCR123A", "RCR2"] }

def isAutoCliffType(battType) {
    return battType && autoCliffTypes().contains(battType)
}

def isCliffCandidateType(battType) {
    return battType && (autoCliffTypes().contains(battType) || manualCliffTypes().contains(battType))
}

def isCliffEnabled(device) {
    def val = settings["cliffEnabled_${device.id}"]
    if (val != null) return (val == true || val == "true")
    return isAutoCliffType(settings["battType_${device.id}"])
}

/**
 * Re-defaults the cliff switch when a device's battery type changes (LIR on, anything else off).
 * A manual choice made without changing the type is kept.
 */
def syncCliffDefaults(List devices = null) {
    // First run (upgrade): record current types and only fill in unset switches, so existing choices survive
    if (state.cliffTypeSeen == null) {
        def initial = [:]
        (autoDevices ?: []).each { d ->
            def t = settings["battType_${d.id}"]
            if (!t || t.startsWith("_sep")) return
            initial[d.id as String] = t
            if (settings["cliffEnabled_${d.id}"] == null) {
                app.updateSetting("cliffEnabled_${d.id}", [value: isAutoCliffType(t), type: "bool"])
            }
        }
        state.cliffTypeSeen = initial
        return
    }
    def seen = state.cliffTypeSeen ?: [:]
    boolean changed = false
    (devices ?: autoDevices ?: []).each { device ->
        def key = device.id as String
        def bt  = settings["battType_${device.id}"]
        if (!bt || bt.startsWith("_sep")) return
        if (seen[key] != bt) {
            app.updateSetting("cliffEnabled_${device.id}", [value: isAutoCliffType(bt), type: "bool"])
            seen[key] = bt
            changed = true
        }
    }
    if (changed) state.cliffTypeSeen = seen
}

// Raw delta between two consecutive readings, independent of EWMA smoothing and elapsed
// time, so infrequent reporters are still caught.
def checkCliffDrop(device, oldLevel, newLevel) {
    if (device == null || oldLevel == null || newLevel == null) return
    if (isIgnored(device)) return
    if (!isCliffEnabled(device)) return

    def drop = (oldLevel as Integer) - (newLevel as Integer)
    if (drop <= 0) return

    def threshold = (settings?.cliffDropThreshold ?: 40).toInteger()
    if (drop < threshold) return

    def lastAlert = state.cliffLastAlert?.get(device.id)
    if (lastAlert && (now() - (lastAlert as Long)) < (60 * 60 * 1000)) {
        if (debugMode) log.debug "${device.displayName}: cliff drop detected but alert sent within the last hour, skipping duplicate"
        return
    }

    if (!state.cliffLastAlert) state.cliffLastAlert = [:]
    state.cliffLastAlert[device.id] = now()
    state.cliffLastAlert = state.cliffLastAlert

    log.warn "Battery Monitor: CLIFF DROP detected — ${device.displayName} ${oldLevel}% -> ${newLevel}% (threshold ${threshold}%)"
    sendCliffAlert(device, oldLevel as Integer, newLevel as Integer)
}

def sendCliffAlert(device, oldLevel, newLevel) {
    // Notifications master switch is authoritative (same rule as scheduledSummary)
    if (settings?.enablePush == false) {
        if (debugMode) log.debug "${device.displayName}: cliff alert suppressed — notifications are off"
        return
    }

    def ts  = new Date().format("MM/dd h:mm a", location.timeZone)
    def msg = "⚡ URGENT: ${device.displayName} battery dropped ${oldLevel}% → ${newLevel}% (${ts}). This pattern is typical of a Li-ion cell nearing end of life — replace as soon as possible."

    def bypassSnooze = settings?.cliffBypassSnooze != false
    def snoozed      = state.notifSnoozedUntil && state.notifSnoozedUntil >= now()
    if (snoozed && !bypassSnooze) {
        if (debugMode) log.debug "${device.displayName}: cliff alert suppressed — notifications snoozed and cliffBypassSnooze is off"
        return
    }

    if (settings?.enablePush)      sendPush(msg)
    if (settings?.pushoverDevices) settings.pushoverDevices.each { it.deviceNotification(msg) }
    if (settings?.notifyDevices)   notifyDevices.each { it.deviceNotification(msg) }
}

// ============================================================
// ===================== PREFERENCES =========================
// ============================================================
preferences {
    page(name: "mainPage")
    page(name: "devicesPage")
    page(name: "notificationsPage")
    page(name: "portalPage")
    page(name: "generalPage")
    page(name: "appNamePage")
    page(name: "summaryPage")
    page(name: "historyPage")
    page(name: "deleteHistoryPage")
    page(name: "deleteHistoryConfirmPage")
    page(name: "tipsPage")
    page(name: "forceScanPage")
    page(name: "deviceManagePage")
    page(name: "deviceActionsPage")
    page(name: "bulkActionsPage")
    page(name: "detectionSettingsPage")
    page(name: "batteryTypesPage")
}

// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
private String stOn(String t = "On")   { "<span style='color:#1e7b34;font-weight:600;'>${t}</span>" }
private String stOff(String t = "Off") { "<span style='color:#b42318;font-weight:600;'>${t}</span>" }
private String stWarn(String t)        { "<span style='color:#9a5b00;'>${t}</span>" }

def mainPage() {
    applyCustomLabel()

    def devCount = autoDevices?.size() ?: 0
    if (devCount) {
        if (!state.history) state.history = [:]
        if (!state.trend)   state.trend   = [:]
        (autoDevices ?: []).each { device ->
            app.updateSetting("deviceName_${device.id}", [value: device.displayName, type: "string"])
            if (!state.history[device.id]) {
                def lvl = device.currentValue("battery")
                state.history[device.id] = [
                    lastLevel:     lvl != null ? lvl.toInteger() : 100,
                    lastDate:      now(),
                    lastScanDate:  now(),
                    firstSeenDate: now(),
                    drain:         0.3,
                    samples:       [],
                    justReplaced:  false
                ]
                state.trend[device.id] = "Stable"
            }
        }
    }

    def snoozed   = state.notifSnoozedUntil && state.notifSnoozedUntil >= now()
    def hoursLeft = snoozed ? Math.ceil((state.notifSnoozedUntil - now()) / 3600000).toInteger() : 0
    def notifOn   = settings?.enablePush != false
    def freq      = [daily: "daily", every2: "every 2 days", every3: "every 3 days", weekly: "weekly"][settings?.reportFrequency ?: "daily"]
    def scanLabel = ["1": "hourly", "3": "every 3 hours", "6": "every 6 hours"][settings?.scanInterval ?: "3"]
    def portalOn  = state.accessToken != null

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, bmStatusBannerHtml()
        }

        section(title: "<b>Reports</b>", sectionClass: "bm-cards bm-cards-primary") {
            href(name: "toSummary", page: "summaryPage",
                 title: "<i class='fa-solid fa-chart-simple' aria-hidden='true'></i>Summary and trends",
                 description: "Levels, health, drain, and what needs attention", width: 4, style: "margin:8px;")
            href(name: "toHistory", page: "historyPage",
                 title: "<i class='fa-solid fa-clock-rotate-left' aria-hidden='true'></i>Replacement history",
                 description: "Auto and manual replacement log", width: 4, style: "margin:8px;")
            href(name: "toDevManage", page: "deviceManagePage",
                 title: "<i class='fa-solid fa-screwdriver-wrench' aria-hidden='true'></i>Device management",
                 description: "Types, replacements, bulk actions, detection", width: 4, style: "margin:8px;")
        }

        section(title: "<b>Settings</b>", sectionClass: "bm-settings") {
            href(name: "toDevices", page: "devicesPage",
                 title: "<i class='fa-solid fa-list-check' aria-hidden='true'></i>Monitored devices",
                 description: devCount ? "${devCount} selected" : stOff("None selected"),
                 width: 12, style: "margin:0;")
            href(name: "toNotifications", page: "notificationsPage",
                 title: "<i class='fa-solid fa-bell' aria-hidden='true'></i>Notifications",
                 description: notifOn ?
                     "${stOn()}, ${freq}${settings?.summaryTime ? '' : ', ' + stWarn('no time set')}${snoozed ? ' · ' + stWarn("snoozed ${hoursLeft}h") : ''}" :
                     stOff(),
                 width: 12, style: "margin:0;")
            href(name: "toPortal", page: "portalPage",
                 title: "<i class='fa-solid fa-globe' aria-hidden='true'></i>Web portal",
                 description: portalOn ? stOn() : "${stOff()} · needs OAuth",
                 width: 12, style: "margin:0;")
            href(name: "toGeneral", page: "generalPage",
                 title: "<i class='fa-solid fa-clock' aria-hidden='true'></i>Scan interval",
                 description: "${scanLabel.capitalize()} · stale after ${settings?.staleThresholdHours ?: 24}h",
                 width: 12, style: "margin:0;")
            href(name: "toAppName", page: "appNamePage",
                 title: "<i class='fa-solid fa-pen' aria-hidden='true'></i>App name",
                 description: bmEsc(app.label ?: "Battery Monitor 2.0"),
                 width: 12, style: "margin:0;")
        }

        helpAndSupportSection()
        section {
            input "debugMode", "bool", title: "Debug logging <span style='font-size:13px;color:#6b7280;'>· turns off after 30 minutes</span>",
                  defaultValue: false, submitOnChange: true
        }
        versionFooterSection()
    }
}

// ============================================================
// ===================== SETTINGS SUBPAGES ===================
// ============================================================
def devicesPage() {
    dynamicPage(name: "devicesPage", title: "Monitored Devices", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>Choose the battery devices to track for trends, health, and notifications.</div>"
            input "autoDevices", "capability.battery",
                  title: "Battery devices to monitor",
                  multiple: true,
                  required: false
            paragraph rawHtml: true, "<div class='bm-msg bm-msg-warn'>After changing devices, tap <b>Done</b> on the main page to save before opening reports.</div>"
        }
    }
}

def notificationsPage() {
    def snoozed   = state.notifSnoozedUntil && state.notifSnoozedUntil >= now()
    def hoursLeft = snoozed ? Math.ceil((state.notifSnoozedUntil - now()) / 3600000).toInteger() : 0
    def notifOn   = settings?.enablePush != false

    dynamicPage(name: "notificationsPage", title: "Notifications", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() + """
<style>
  .bm-btn-align { padding-top: 22px; box-sizing: border-box; }
</style>
"""
            input "enablePush", "bool", title: "Enable notifications", defaultValue: true, submitOnChange: true, width: 4
            if (notifOn) {
                input "sendNow", "button", title: "<i class='fa-solid fa-paper-plane' style='margin-right:6px;'></i>Send now",
                      width: 3, styleClass: "bm-btn"
                def sendMsg = state.remove("sendMsg")
                if (sendMsg) paragraph rawHtml: true, bmMsgHtml(sendMsg)
            }
        }
        if (notifOn) {
            section("<b>Schedule and delivery</b>") {
                input "reportFrequency", "enum", title: "Frequency",
                      options: ["daily": "Daily", "every2": "Every 2 Days", "every3": "Every 3 Days", "weekly": "Weekly"],
                      defaultValue: "daily", width: 4
                input "summaryTime", "time", title: "Time", required: false, width: 4
                input "enablePushover", "bool", title: "Pushover markup", defaultValue: false, submitOnChange: true, width: 4
                input "notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false, width: 6
                // Always visible: these devices receive notifications even with markup off
                input "pushoverDevices", "capability.notification",
                      title: "Pushover devices", multiple: true, required: false, width: 6
                if (settings?.enablePushover) {
                    input "pushoverPrefix", "text", title: "Pushover tags",
                          description: "e.g. [H][TITLE=Battery Report][HTML][SELFDESTRUCT=43200]",
                          required: false
                }
            }
            section("<b>What to include</b>") {
                input "notifyPoor",      "bool", title: "🔴 Poor (≤25%)",       defaultValue: true,  width: 4
                input "notifyFair",      "bool", title: "🟠 Fair (26–70%)",     defaultValue: true,  width: 4
                input "notifyGood",      "bool", title: "🟢 Good (71–99%)",     defaultValue: false, width: 4
                input "notifyExcellent", "bool", title: "🟢 Excellent (100%)",  defaultValue: false, width: 4
                input "notifyHighDrain", "bool", title: "⚠️ High drain",        defaultValue: true,  width: 4
                input "notifyStale",     "bool", title: "⚠️ Stale devices",     defaultValue: true,  width: 4
                input "suppressEmptyReport",  "bool", title: "🔕 Skip when nothing to report", defaultValue: false, width: 4
                input "notifyIncludeAppLink", "bool", title: "🔗 Include app link (local only)", defaultValue: false, width: 4
            }
            section("<b>Snooze</b> ${snoozed ? bmPill("Snoozed, ${hoursLeft}h left", "amber") : bmPill("Off", "gray")}") {
                def smsg = state.remove("snoozeMsg")
                if (snoozed) {
                    def until = new Date(state.notifSnoozedUntil as Long).format("MMM d, h:mm a", location.timeZone)
                    paragraph rawHtml: true, "<div class='bm-msg bm-msg-warn'>😴 Snoozed until <b>${until}</b>. Cliff alerts still come through by default.</div>"
                    input "snoozeClear", "button", title: "Resume now", width: 3, styleClass: "bm-btn"
                } else {
                    paragraph rawHtml: true, (smsg ? bmMsgHtml(smsg) + "<div style='height:6px;'></div>" : "") +
                        "<div class='bm-hint'>Pause notifications while you're away. Scanning continues, and cliff alerts still come through by default.</div>"
                    input "snoozeDurationDays", "number", title: "Days", defaultValue: 7, range: "1..60", required: false, width: 2
                    input "snoozeStart", "button", title: "😴 Snooze", width: 3, styleClass: "bm-btn bm-btn-align"
                }
            }
        }
    }
}

def portalPage() {
    def portalOn = state.accessToken != null
    dynamicPage(name: "portalPage", title: "Web Portal", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>A live dashboard of every device, sorted by battery level. Add either link to a dashboard Link tile for one-tap access.</div>"
            if (portalOn) {
                def cloudUrl = "${getFullApiServerUrl()}/dashboard?access_token=${state.accessToken}"
                def localUrl = "${getFullLocalApiServerUrl()}/dashboard?access_token=${state.accessToken}"
                paragraph rawHtml: true, "<div class='bm-msg bm-msg-info' style='word-break:break-all;'>" +
                    "<b>Cloud (anywhere):</b><br><a href='${cloudUrl}' target='_blank'>${cloudUrl}</a><br><br>" +
                    "<b>Local (at home):</b><br><a href='${localUrl}' target='_blank'>${localUrl}</a></div>"
            } else {
                def hubIp = location?.hub?.localIP ?: ""
                paragraph rawHtml: true, "<div class='bm-msg bm-msg-err'><b>OAuth isn't enabled yet.</b> To turn on the portal:<br><br>" +
                    "1. Open <b>Apps Code</b> (" + hubLink("/app/list", "open it here") + ")<br>" +
                    "2. Open <b>Battery Monitor 2.0</b><br>" +
                    "3. Click <b>OAuth</b> at the top right, then <b>Enable OAuth in App</b>, then <b>Update</b><br>" +
                    "4. Come back and tap <b>Done</b>. The links appear here.</div>" + hubLinkScript()
            }
        }
    }
}

def appNamePage() {
    dynamicPage(name: "appNamePage", title: "App Name", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>Rename how this app appears in your Hubitat Apps list. It updates when you return to the main page.</div>"
            input "customAppName", "text", title: "App name", required: false
        }
    }
}

def generalPage() {
    dynamicPage(name: "generalPage", title: "Scan Interval", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss()
            input "scanInterval", "enum",
                  title: "Battery scan interval",
                  description: "How often levels are read. Devices also update on their own battery events.",
                  options: ["1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3", submitOnChange: true
            input "staleThresholdHours", "number",
                  title: "Mark devices stale after (hours without activity)",
                  description: "Default 24. Used by the summary, banner, and notifications.",
                  defaultValue: 24, required: false
        }
    }
}

// ============================================================
// ===================== REPORT SCHEDULING ==================
// ============================================================
def scheduleReportFrequency() {
    unschedule("reportScheduler")
    if (!summaryTime) return
    if (settings?.enablePush == false) return
    schedule(summaryTime, reportScheduler)
}

def scheduleScanInterval() {
    unschedule("scanAllDevices")
    def interval = (settings?.scanInterval ?: "3").toInteger()
    def cronExpr = ""
    switch (interval) {
        case 1:  cronExpr = "0 0 * * * ?";   break
        case 3:  cronExpr = "0 0 */3 * * ?"; break
        case 6:  cronExpr = "0 0 */6 * * ?"; break
        default: cronExpr = "0 0 */3 * * ?"; break
    }
    schedule(cronExpr, scanAllDevices)
    if (debugMode) log.debug "Battery scan scheduled every ${interval}h (cron: ${cronExpr})"
}

def scanAllDevices() {
    def devList = (autoDevices ?: []).findAll { !isIgnored(it) }
    if (!devList) return
    if (debugMode) log.debug "Running scheduled battery scan for ${devList.size()} device(s)"
    log.info "Battery Monitor: scan started — ${devList.size()} device(s)"

    confirmPendingReplacements()

    def poor  = []
    def stale = []

    devList.each { device ->
        try {
            app.updateSetting("deviceName_${device.id}", [value: device.displayName, type: "string"])
            def level = device.currentValue("battery")?.toInteger()
            if (level != null) {
                updateBattery(device, level)
                if (debugMode) log.debug "Scanned ${device.displayName}: ${level}%"
                if (level <= 25 && !isBatteryDead(device)) poor  << "${device.displayName} (${level}%)"
                if (isStale(device))                        stale << device.displayName
            }
        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }

    def ts = new Date().format("MM/dd h:mm a", location.timeZone)
    def msg = "SCAN: ${devList.size()} device(s) scanned at ${ts}."
    if (poor.size()  > 0) msg += " Low battery: ${poor.join(', ')}."
    if (stale.size() > 0) msg += " Stale: ${stale.join(', ')}."
    log.info "Battery Monitor: scan complete — ${devList.size()} device(s) processed"
}

def reportScheduler() {
    switch (reportFrequency) {
        case "daily":  scheduledSummary(); break
        case "every2": if (shouldRunEveryXDays(2)) scheduledSummary(); break
        case "every3": if (shouldRunEveryXDays(3)) scheduledSummary(); break
        case "weekly": if (shouldRunWeekly())       scheduledSummary(); break
    }
}

def shouldRunEveryXDays(daysInterval) {
    def today   = new Date().clearTime()
    def lastRun = state.lastReportRun ? new Date(state.lastReportRun).clearTime() : null
    if (!lastRun) { state.lastReportRun = now(); return true }
    def diff = (today.time - lastRun.time) / (1000 * 60 * 60 * 24)
    if (diff >= daysInterval) { state.lastReportRun = now(); return true }
    return false
}

def shouldRunWeekly() {
    def today   = new Date()
    def lastRun = state.lastReportRun ? new Date(state.lastReportRun) : null
    if (!lastRun) { state.lastReportRun = now(); return true }
    if (today.format("u") == "1") {
        def diff = (today.time - lastRun.time) / (1000 * 60 * 60 * 24)
        if (diff >= 7) { state.lastReportRun = now(); return true }
    }
    return false
}

def scheduledSummary() {
    // v2.5.31→v2.5.32: Fix — enablePush previously only gated sendPush(); notifyDevices and
    // pushoverDevices kept firing even with the toggle off (and stayed hidden/uneditable in the
    // UI once off, with no way to clear them). This makes the master switch authoritative.
    if (settings?.enablePush == false) {
        if (debugMode) log.debug "Notifications disabled (enablePush off) — skipping summary"
        return false
    }

    if (state.notifSnoozedUntil && state.notifSnoozedUntil >= now()) {
        if (debugMode) log.debug "Notifications snoozed until ${new Date(state.notifSnoozedUntil)} — skipping summary"
        return false
    }

    def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null && !isIgnored(it) }
    if (!devList) return false

    def categories = [
        "🔴 Poor":      [list: [], enabled: notifyPoor      != null ? notifyPoor      : true],
        "🟠 Fair":      [list: [], enabled: notifyFair      != null ? notifyFair      : true],
        "🟢 Good":      [list: [], enabled: notifyGood      != null ? notifyGood      : false],
        "🟢 Excellent": [list: [], enabled: notifyExcellent != null ? notifyExcellent : false]
    ]

    devList.each { device ->
        if (isBatteryDead(device)) return
        def lvl = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
        def cat = lvl >= 100 ? "🟢 Excellent" : lvl > 70 ? "🟢 Good" : lvl > 25 ? "🟠 Fair" : "🔴 Poor"
        categories[cat].list << [device: device, name: device.displayName.trim(), level: lvl]
    }

    categories.each { cat, data ->
        categories[cat].list = data.list.sort { a, b ->
            a.level != b.level ? a.level <=> b.level : a.name <=> b.name
        }
    }

    def highDrainList = devList.findAll { device ->
        if (isBatteryDead(device)) return false
        def h = health(device)
        (h == "Poor" || h == "Fair") && getDrain(device) > 1.5
    }.collect { device ->
        def lvl = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
        [name: device.displayName.trim(), level: lvl, health: health(device), drain: displayDrain(device)]
    }.sort { a, b -> a.level != b.level ? a.level <=> b.level : a.name <=> b.name }

    def deadBatteryList = devList.findAll { isBatteryDead(it) }.collect { device ->
        [name: device.displayName.trim()]
    }.sort { a, b -> a.name <=> b.name }

    def usePushover = (settings?.enablePushover == true && settings?.pushoverPrefix?.trim())
    def prefix  = ""
    def postfix = ""

    if (usePushover) {
        def tags          = settings.pushoverPrefix.trim()
        def priorityMatch = tags =~ /^(\[[EHLNS]\])(.*)/
        if (priorityMatch) {
            prefix  = priorityMatch[0][1]
            postfix = priorityMatch[0][2].trim()
        } else {
            postfix = tags
        }
    }

    def timestamp = new Date().format("MM/dd HH:mm", location.timeZone)
    def body      = "${prefix}🔋 Battery Summary — ${timestamp}\n"

    def staleDevices = devList.findAll { isStale(it) }.collect {
        def last        = getLastActivityTime(it)
        def inactiveStr = last ? formatInactive(last) : "unknown"
        [device: it, name: it.displayName, inactiveStr: inactiveStr]
    }

    categories.each { cat, data ->
        if (data.enabled) {
            if (data.list) {
                body += "\n${cat}:\n"
                data.list.each { dev ->
                    if (cat == "🔴 Poor") {
                        def info    = getCatalogBatteryInfo(dev.device)
                        def infoStr = info ? " (${info})" : ""
                        body += "• ${dev.level}% ${dev.name}${infoStr}\n"
                    } else {
                        body += "• ${dev.level}% ${dev.name}\n"
                    }
                }
            } else {
                body += "\n${cat}: None\n"
            }
        }
    }

    if (notifyHighDrain != null ? notifyHighDrain : true) {
        if (highDrainList) {
            body += "\n⚠️ High Drain (Fair/Poor):\n"
            highDrainList.each { dev -> body += "• ${dev.health} (${dev.drain}%) ${dev.name} (${dev.level}%)\n" }
        } else {
            body += "\n⚠️ High Drain (Fair/Poor): None\n"
        }
    }

    if (notifyStale != null ? notifyStale : true) {
        if (staleDevices) {
            body += "\n⚠️ Stale Devices:\n"
            staleDevices.each { d ->
                def info    = getCatalogBatteryInfo(d.device)
                def infoStr = info ? " (${info})" : ""
                body += "• ${d.name}${infoStr} — inactive ${d.inactiveStr}\n"
            }
        } else {
            body += "\n⚠️ Stale Devices: None\n"
        }
    }

    if (deadBatteryList) {
        body += "\n🪫 Dead Batteries:\n"
        deadBatteryList.each { dev ->
            def foundDev = dev.deviceId
                ? devList.find { it.id == dev.deviceId }
                : devList.find { it.displayName.trim() == dev.name }
            def info    = getCatalogBatteryInfo(foundDev)
            def infoStr = info ? " (${info})" : ""
            body += "• ${dev.name}${infoStr}\n"
        }
    }

    if (suppressEmptyReport != null ? suppressEmptyReport : false) {
        def hasContent = categories.any { cat, data -> data.enabled && data.list } ||
            ((notifyHighDrain != null ? notifyHighDrain : true) && highDrainList) ||
            ((notifyStale != null ? notifyStale : true) && staleDevices) ||
            deadBatteryList
        if (!hasContent) return false
    }

    def pushoverBody = body
    def plainBody    = body

    if (notifyIncludeAppLink != null ? notifyIncludeAppLink : false) {
        def hubIp     = location.hub.localIP
        def htmlLink  = "\n🔗 <a href='http://${hubIp}/installedapp/configure/${app.id}/mainPage'>Battery Monitor</a>"
        def plainLink = "\n🔗 Battery Monitor: http://${hubIp}/installedapp/configure/${app.id}/mainPage"
        pushoverBody += htmlLink
        plainBody    += plainLink
    }

    if (postfix) pushoverBody += "${postfix}\n"

    if (settings?.enablePush)      sendPush(pushoverBody)
    if (settings?.pushoverDevices) settings.pushoverDevices.each { it.deviceNotification(pushoverBody) }
    if (settings?.notifyDevices)   notifyDevices.each { it.deviceNotification(plainBody) }

    def poorCount  = categories["🔴 Poor"]?.list?.size() ?: 0
    def staleCount = staleDevices?.size() ?: 0
    def deadCount  = deadBatteryList?.size() ?: 0
    return true
}

// ============================================================
// ===================== BATTERY HANDLER =====================
// ============================================================
def batteryHandler(evt) {
    def device = evt.device
    if (isIgnored(device)) return
    def level  = null
    try {
        level = evt.value ? (int) Double.parseDouble(evt.value) : null
    } catch (e) {
        log.warn "batteryHandler: Could not parse battery level '${evt.value}' for ${device?.displayName}: ${e.message}"
    }
    if (device && level != null) {
        updateBattery(device, level)
    }
}

def updateBattery(device, level) {
    def data = state.history[device.id]

    if (!data) {
        state.history[device.id] = [
            lastLevel:     level != null ? level : 100,
            lastDate:      now(),
            lastScanDate:  now(),
            firstSeenDate: now(),
            drain:         0.3,
            samples:       [],
            justReplaced:  false,
            zeroCount:     0
        ]
        state.trend[device.id] = "Stable"
        state.history = state.history
        data = state.history[device.id]
    }

    // Cliff check runs first on the raw previous->new delta, ahead of dead-battery,
    // EWMA, and replacement logic
    checkCliffDrop(device, data.lastLevel, level)

    if (level <= 1) {
        data.zeroCount = (data.zeroCount ?: 0) + 1
    } else {
        data.zeroCount = 0
    }

    if (isBatteryDead(device)) {
        data.lastLevel    = level
        data.lastScanDate = now()
        state.history[device.id] = data
        state.history = state.history
        if (debugMode) log.debug "${device.displayName}: battery confirmed dead (${data.zeroCount} consecutive 0% readings)"
        return
    }

    if (level <= 1 && (data.zeroCount ?: 0) < 3) {
        data.justReplaced      = false
        data.replacedTime      = null
        data.drain             = 1.0
        data.samples           = []
        state.trend[device.id] = "Heavy Drain"
    }

    detectReplacement(device, level, data.lastLevel)

    def replacedAt = data.replacedTime ?: now()
    if (data.justReplaced && (now() - safeTime(replacedAt)) > 1000 * 60 * 60 * 24) {
        data.justReplaced = false
    }

    def days  = (now() - safeTime(data.lastDate)) / (1000 * 60 * 60 * 24)
    def hours = days * 24

    if (days > 0 && hours >= 1.0 && !data.justReplaced) {
        def lastLevel    = data.lastLevel != null ? data.lastLevel : 100
        def rawDrain     = (lastLevel - level) / days
        def clampedDrain = Math.max(0.0, Math.min(rawDrain, 5.0))
        def validSample  = (rawDrain > 0) || (rawDrain == 0 && hours >= 24)

        if (validSample) {
            def isOutlier = false
            if (data.samples && data.samples.size() >= 3) {
                def rollingAvg = data.samples.sum() / data.samples.size()
                if (rollingAvg > 0 && clampedDrain > rollingAvg * 4) {
                    isOutlier = true
                    if (debugMode) log.debug "${device.displayName}: outlier sample rejected — clampedDrain=${clampedDrain}, rollingAvg=${rollingAvg}"
                }
            }

            if (!isOutlier) {
                def alpha      = 0.3
                def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : clampedDrain
                def smoothed   = alpha * clampedDrain + (1 - alpha) * prevSmooth
                data.samples << smoothed
                if (data.samples.size() > 10) data.samples.remove(0)
                data.lastDate = now()
            }

            if (data.samples && data.samples.size() > 0) {
                def avg  = data.samples.sum() / data.samples.size()
                data.drain = Math.min(avg, 3.0)
                updateTrend(device, data.drain)
            }
        }
    }

    data.lastLevel    = level
    data.lastScanDate = now()

    state.history[device.id] = data
    state.history = state.history
}

// ============================================================
// ===================== DEAD BATTERY DETECTION ==============
// ============================================================
def isBatteryDead(device) {
    def data      = state.history?.get(device.id)
    if (!data) return false
    def level     = device.currentValue("battery")
    def zeroCount = data.zeroCount ?: 0
    return (level != null && level.toInteger() <= 1 && zeroCount >= 3)
}

// ============================================================
// ===================== DETECT REPLACEMENT ==================
// ============================================================
def detectReplacement(device, newLevel, oldLevel) {
    newLevel = newLevel != null ? newLevel : 100
    oldLevel = oldLevel != null ? oldLevel
                                : (state.history[device.id]?.lastLevel != null
                                   ? state.history[device.id].lastLevel : 0)

    if (!state.history[device.id]) {
        state.history[device.id] = [
            lastLevel:    oldLevel,
            lastDate:     now(),
            lastScanDate: now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false,
            zeroCount:    0
        ]
        state.trend[device.id] = "Stable"
    }

    def data = state.history[device.id]

    // A jump up from a near-empty battery is unambiguous. Samples are cleared at ~0%, so the
    // sample/age gates below would otherwise block detection of dead-battery swaps entirely.
    def fromEmpty = (oldLevel as Integer) <= 5

    def sampleCount = data?.samples?.size() ?: 0
    if (!fromEmpty && sampleCount < 3) {
        if (debugMode) log.debug "${device.displayName}: replacement gate — only ${sampleCount}/3 prior samples, skipping"
        return
    }

    def firstSeen = data?.firstSeenDate ?: data?.lastDate ?: now()
    def ageDays   = (now() - (firstSeen as Long)) / (1000 * 60 * 60 * 24)
    if (!fromEmpty && ageDays < 3) {
        if (debugMode) log.debug "${device.displayName}: replacement gate — device only ${ageDays.toInteger()}d old (min 3d), skipping"
        return
    }

    def lastLogged = data?.lastReplacementLogged
    if (lastLogged) {
        def hoursSinceLast = (now() - (lastLogged as Long)) / (1000 * 60 * 60)
        if (hoursSinceLast < 12) {
            if (debugMode) log.debug "${device.displayName}: replacement gate — last replacement ${hoursSinceLast.toInteger()}h ago (cooldown 12h), skipping"
            return
        }
    }

    // v2.5.29→v2.5.30: Simplified detection — any upward jump of minJump% or more qualifies.
    // Batteries only drain naturally; any significant upward jump means a new battery was installed.
    def minJump   = (settings?.detectionMinJump ?: 30).toInteger()
    def largeJump = newLevel - oldLevel

    def qualifies = (largeJump >= minJump)
    if (!qualifies) {
        if (state.pendingReplacement?.containsKey(device.id)) {
            if (debugMode) log.debug "${device.displayName}: pending replacement cleared — jump ${largeJump}% < threshold ${minJump}% (${oldLevel}% → ${newLevel}%)"
            state.pendingReplacement.remove(device.id)
            state.pendingReplacement = state.pendingReplacement
        }
        return
    }

    def requireConfirm   = true
    def confirmWindowHrs = 48

    if (!state.pendingReplacement) state.pendingReplacement = [:]

    def pending = state.pendingReplacement[device.id]

    if (!pending) {
        state.pendingReplacement[device.id] = [
            stagedAt: now(),
            oldLevel: oldLevel,
            newLevel: newLevel,
            jumpSize: largeJump
        ]
        state.pendingReplacement = state.pendingReplacement
        if (debugMode) log.debug "${device.displayName}: replacement staged (awaiting confirmation) — ${oldLevel}% → ${newLevel}%, jump=${largeJump}%"
        return
    }

    def windowMs   = confirmWindowHrs * 60 * 60 * 1000
    def pendingAge = now() - (pending.stagedAt as Long)

    if (pendingAge > windowMs) {
        if (debugMode) log.debug "${device.displayName}: pending replacement expired (${(pendingAge / 3600000).toInteger()}h > ${confirmWindowHrs}h window) — re-staging"
        state.pendingReplacement[device.id] = [
            stagedAt: now(),
            oldLevel: oldLevel,
            newLevel: newLevel,
            jumpSize: largeJump
        ]
        state.pendingReplacement = state.pendingReplacement
        return
    }

    // Gate 5: level must still be above (pre-jump level + minJump) on confirming read
    def sustainThresh = (pending.oldLevel as Integer) + minJump
    if (newLevel < sustainThresh) {
        if (debugMode) log.debug "${device.displayName}: pending replacement cancelled — level dropped back to ${newLevel}% (must sustain ≥${sustainThresh}%)"
        state.pendingReplacement.remove(device.id)
        state.pendingReplacement = state.pendingReplacement
        return
    }

    state.pendingReplacement.remove(device.id)
    state.pendingReplacement = state.pendingReplacement
    data.zeroCount = 0
    logReplacement(device, newLevel, false)
    if (debugMode) log.debug "${device.displayName}: replacement CONFIRMED — ${pending.oldLevel}% → ${newLevel}%, staged ${(pendingAge / 60000).toInteger()}m ago"
}

// ============================================================
// ===================== CONFIRM PENDING REPLACEMENTS ========
// ============================================================
def confirmPendingReplacements() {
    if (!state.pendingReplacement || state.pendingReplacement.isEmpty()) return

    def minJump  = (settings?.detectionMinJump ?: 30).toInteger()
    def windowMs = 48 * 60 * 60 * 1000
    def toRemove = []

    state.pendingReplacement.each { deviceId, pending ->
        def device = autoDevices?.find { it.id == deviceId }
        if (!device) { toRemove << deviceId; return }

        def currentLevel = device.currentValue("battery")?.toInteger()
        if (currentLevel == null) return

        def pendingAge    = now() - (pending.stagedAt as Long)
        def sustainThresh = (pending.oldLevel as Integer) + minJump

        if (pendingAge > windowMs) {
            if (debugMode) log.debug "${device.displayName}: pending replacement EXPIRED during scan (${(pendingAge / 3600000).toInteger()}h old)"
            toRemove << deviceId
            return
        }

        if (currentLevel >= sustainThresh) {
            def histData = state.history[device.id]
            if (histData) histData.zeroCount = 0
            logReplacement(device, currentLevel, false)
            toRemove << deviceId
            if (debugMode) log.debug "${device.displayName}: replacement CONFIRMED by scan — level ${currentLevel}% sustained ≥${sustainThresh}%"
        } else {
            if (debugMode) log.debug "${device.displayName}: pending replacement DISCARDED by scan — level ${currentLevel}% dropped below sustain threshold ${sustainThresh}%"
            toRemove << deviceId
        }
    }

    if (toRemove) {
        toRemove.each { state.pendingReplacement.remove(it) }
        state.pendingReplacement = state.pendingReplacement
    }
}

// ============================================================
// ===================== TREND LOGIC =========================
// ============================================================
def updateTrend(device, drain) {
    if (!device || drain == null) return

    def devType      = (device?.name ?: device?.typeName ?: "").toLowerCase()
    def isLock       = devType.contains("lock")
    def isSensor     = devType.contains("contact") || devType.contains("motion")
    def isSlowSensor = devType.contains("smoke") || devType.contains("carbonmonoxide")

    def adjustedDrain = isLock       ? drain * 0.4 :
                        isSensor     ? drain * 0.5 :
                        isSlowSensor ? drain * 0.5 : drain

    if (adjustedDrain > 5) adjustedDrain = 0.3

    def hist = state.history[device.id]
    if (hist?.samples && hist.samples.size() >= 3) {
        def avg = hist.samples.sum() / hist.samples.size()
        if (avg > 3) adjustedDrain = Math.min(adjustedDrain, 1.0)
    }

    def stableThreshold   = isLock ? 0.9 : (isSensor || isSlowSensor) ? 0.6 : 0.3
    def moderateThreshold = isLock ? 2.0 : (isSensor || isSlowSensor) ? 1.5 : 0.8

    if (adjustedDrain <= stableThreshold)       state.trend[device.id] = "Stable"
    else if (adjustedDrain < moderateThreshold) state.trend[device.id] = "Moderate"
    else                                        state.trend[device.id] = "Heavy Drain"
}

// ============================================================
// ===================== CONFIDENCE HELPERS ==================
// ============================================================
def getConfidence(device) {
    def samples = state.history?.get(device.id)?.samples?.size() ?: 0
    def minN    = 5
    if (samples < 2)     return 0.05
    if (samples >= minN) return 1.0
    return Math.min(1.0, 0.05 + 0.95 * Math.pow((samples - 1) / (minN - 1.0), 1.5))
}

def getSampleQualityLabel(device, healthStr) {
    if (healthStr == "Pending") return null
    def conf  = getConfidence(device)
    def label = conf < 0.20 ? "Low" : conf < 0.60 ? "Medium" : conf < 1.0 ? "High" : "Full"
    return "<span style='color:#1a73e8;'>${label}</span>"
}

// ============================================================
// ===================== DRAIN / HEALTH HELPERS ==============
// ============================================================
def getDrain(device) {
    def d = state.history?.get(device.id)?.drain
    return (d != null && d > 0) ? d : 0.3
}
def displayDrain(device) { return String.format("%.2f", getDrain(device)) }

def estDays(device) {
    if (health(device) == "Pending") return null
    def level = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100
    def drain = getDrain(device)
    if (drain <= 0) drain = 0.3
    def est = Math.round(level / drain)
    return Math.min(est, 365)
}

def health(device) {
    def hist    = state.history?.get(device.id)
    def samples = hist?.samples?.size() ?: 0

    def devType      = (device?.name ?: device?.typeName ?: "").toLowerCase()
    def isLock       = devType.contains("lock")
    def isSensor     = devType.contains("contact") || devType.contains("motion")
    def isSlowSensor = devType.contains("smoke") || devType.contains("carbonmonoxide")
    def minSamples   = (isLock || isSlowSensor) ? 7 : 5

    def daysSinceReplaced = 999
    if (hist?.replacedTime) {
        daysSinceReplaced = (now() - (hist.replacedTime as Long)) / (1000 * 60 * 60 * 24)
    } else if (hist?.firstSeenDate) {
        daysSinceReplaced = (now() - (hist.firstSeenDate as Long)) / (1000 * 60 * 60 * 24)
    } else if (hist?.lastDate) {
        daysSinceReplaced = (now() - (hist.lastDate as Long)) / (1000 * 60 * 60 * 24)
    }

    def slowReporter = (daysSinceReplaced >= 14 && samples >= 2)
    if (!slowReporter && (samples < minSamples || daysSinceReplaced < 5)) return "Pending"

    def rawDrain      = getDrain(device)
    def adjustedDrain = isLock ? rawDrain * 0.4 : isSensor ? rawDrain * 0.5 : isSlowSensor ? rawDrain * 0.5 : rawDrain

    def conf     = getConfidence(device)
    def effDrain = 0.3 + conf * (adjustedDrain - 0.3)

    if (effDrain < 0.3)  return "Excellent"
    if (effDrain <= 0.8) return "Good"
    if (effDrain <= 1.5) return "Fair"
    return "Poor"
}

def getHealthDisplay(device) {
    def h       = health(device)
    def hist    = state.history?.get(device.id)
    def samples = hist?.samples?.size() ?: 0

    def devType      = (device?.name ?: device?.typeName ?: "").toLowerCase()
    def isLock       = devType.contains("lock")
    def isSlowSensor = devType.contains("smoke") || devType.contains("carbonmonoxide")
    def minSamples   = (isLock || isSlowSensor) ? 7 : 5

    if (h == "Pending") {
        def daysSinceReplaced = 0
        if (hist?.replacedTime) {
            daysSinceReplaced = ((now() - (hist.replacedTime as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        } else if (hist?.firstSeenDate) {
            daysSinceReplaced = ((now() - (hist.firstSeenDate as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        } else if (hist?.lastDate) {
            daysSinceReplaced = ((now() - (hist.lastDate as Long)) / (1000 * 60 * 60 * 24)).toInteger()
        }
        def minDays = 5
        return "<span style='color:#94a3b8; font-size:11px;'>⏳ ${Math.min(samples, minSamples)}/${minSamples} samples &nbsp;·&nbsp; ${Math.min(daysSinceReplaced, minDays)}/${minDays} days</span>"
    }

    def colorMap = ["Excellent": "#22c55e", "Good": "#22c55e", "Fair": "#f97316", "Poor": "#ef4444"]
    def color    = colorMap[h] ?: "#94a3b8"
    return "<span style='color:${color};font-weight:bold;'>${h}</span>"
}

// ============================================================
// ===================== SAFE HISTORY HELPERS ================
// ============================================================
def safeTime(ts) { return (ts instanceof Number) ? ts : ts?.time }

def safeHistory(device) {
    if (!device) return [:]
    def data = state.history?.get(device.id)
    if (!data) {
        def currentLevel = device.currentValue("battery")
        data = [
            lastLevel:    currentLevel != null ? currentLevel.toInteger() : 100,
            lastDate:     now(),
            lastScanDate: now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false,
            zeroCount:    0
        ]
        state.history[device.id] = data
        state.trend[device.id]   = "Stable"
    }
    return data
}

def getLastBatteryTime(device)  { return safeTime(state.history[device.id]?.lastScanDate ?: state.history[device.id]?.lastDate) }
def getLastActivityTime(device) { return safeTime(device.getLastActivity()) }

def getCatalogBatteryInfo(device) {
    if (!device) return null
    def battType  = settings["battType_${device.id}"]
    def battCount = settings["battCount_${device.id}"]
    if (battType && battType != "" && !battType.startsWith("_sep")) {
        def count        = (battCount != null && battCount.toString().trim() != "") ? battCount.toString().trim() : "1"
        def resolvedType = (battType == "Other") ? (settings["battCustomType_${device.id}"]?.trim() ?: "Other") : battType
        return "${resolvedType} x${count}"
    }
    def info = settings["battInfo_${device.id}"]
    if (!info || info == "" || info.startsWith("_sep")) return null
    return info
}

def isStale(device) {
    def lastActivity = getLastActivityTime(device)
    if (!lastActivity) return false
    def threshold = (settings?.staleThresholdHours != null && settings.staleThresholdHours > 0) ? settings.staleThresholdHours : 24
    def diffHours = (now() - lastActivity) / (1000 * 60 * 60)
    return diffHours >= threshold
}

def formatTimeAgo(ts) {
    if (!ts) return "N/A"
    ts = safeTime(ts)
    def diffMs = now() - ts
    def mins   = (diffMs / (1000 * 60)).toInteger()
    def hours  = (diffMs / (1000 * 60 * 60)).toInteger()
    def days   = (diffMs / (1000 * 60 * 60 * 24)).toInteger()
    def weeks  = (days / 7).toInteger()
    def months = (days / 30).toInteger()
    if (months >= 1) return "${months}mo ago"
    if (weeks  >= 1) return "${weeks}w ago"
    if (days   >= 1) return "${days}d ago"
    if (hours  >= 1) return "${hours}h ago"
    return "${mins}m ago"
}

def formatInactive(ts) {
    if (!ts) return "unknown"
    ts = safeTime(ts)
    def diffMs = now() - ts
    def mins   = (diffMs / (1000 * 60)).toInteger()
    def hours  = (diffMs / (1000 * 60 * 60)).toInteger()
    def days   = (diffMs / (1000 * 60 * 60 * 24)).toInteger()
    def weeks  = (days / 7).toInteger()
    def months = (days / 30).toInteger()
    if (months >= 1) return "${months}mo"
    if (weeks  >= 1) return "${weeks}w"
    if (days   >= 1) return "${days}d"
    if (hours  >= 1) return "${hours}h"
    return "${mins}m"
}

// ============================================================
// ===================== BATTERY DISPLAY =====================
// ============================================================
def getBatteryLevelDisplay(level, device = null) {
    if (device && isBatteryDead(device)) return "<span style='color:#ef4444;'>🪫 Dead</span>"
    level = (level instanceof Number ? level : null) != null ? level : 100
    def cat = level >= 100 ? "🟢 Excellent" : level > 70 ? "🟢 Good" : level > 25 ? "🟠 Fair" : "🔴 Poor"
    def label = "${cat} (${level}%)"
    def data         = (device && state.history?.containsKey(device.id)) ? safeHistory(device) : null
    def showTag      = data?.justReplaced == true
    def replacedTime = data?.replacedTime
    if (showTag) {
        replacedTime = safeTime(replacedTime)
        def hoursSinceReplacement = (now() - replacedTime) / (1000 * 60 * 60)
        if (hoursSinceReplacement >= 24) { if (data) data.justReplaced = false; showTag = false }
    }
    if (device && showTag) label += " (Recently Replaced)"
    return label
}

// ============================================================
// ===================== BATTERY REPLACEMENT LOGGER ==========
// ============================================================
def logReplacement(device, newLevel, manual = false) {
    if (!device) return

    def data = state.history[device.id]
    if (!data) {
        state.history[device.id] = [
            lastLevel:    newLevel != null ? newLevel : 100,
            lastDate:     now(),
            lastScanDate: now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false,
            zeroCount:    0
        ]
        data = state.history[device.id]
        state.trend[device.id] = "Stable"
    }

    data.drain                 = 0.3
    data.samples               = []
    data.lastLevel             = newLevel
    data.lastDate              = now()
    data.lastScanDate          = now()
    data.firstSeenDate         = now()
    data.justReplaced          = true
    data.replacedTime          = now()
    data.zeroCount             = 0
    state.trend[device.id]     = "Stable"
    data.lastReplacementLogged = now()

    state.replacements = state.replacements?.findAll { it.device != device.displayName } ?: []
    state.replacements << [
        deviceId: device.id,
        device:   device.displayName,
        level:    newLevel,
        date:     new Date().format("MM/dd/yyyy", location.timeZone),
        type:     manual ? "manual" : "auto"
    ]
    state.replacements = state.replacements.sort { a, b -> replacementTime(b) <=> replacementTime(a) }.take(100)

    state.history[device.id] = data
    state.history = state.history

    def typeStr = manual ? "Manual" : "Auto-detected"
}

// ============================================================
// ===================== OAUTH PORTAL HELPERS ================
// ============================================================
def getPortalRedirectHtml(delayMs, msgText) {
    return "<!DOCTYPE html><html><head><meta charset='UTF-8'>" +
           "<script>setTimeout(function(){window.location.href='dashboard?access_token=${state.accessToken}';},${delayMs});</script>" +
           "</head><body style='background:#0d0d0d;color:#fff;text-align:center;padding-top:100px;font-family:sans-serif;'>" +
           "<h3>🔄 Refreshing...</h3><p style='color:#666;'>${msgText}</p></body></html>"
}

// ============================================================
// ===================== PORTAL ENDPOINT: REFRESH ============
// ============================================================
def forceRefreshEndpoint() {
    try {
        runIn(1, "scanAllDevices", [overwrite: true])
        return render(contentType: "text/html", data: getPortalRedirectHtml(2500, "Running battery scan..."), status: 200)
    } catch (e) {
        log.error "Battery Monitor portal refresh error: ${e}"
        return render(contentType: "text/html", data: "Error: ${e.message}", status: 500)
    }
}

// ============================================================
// ===================== PORTAL ENDPOINT: DASHBOARD ==========
// ============================================================
def serveDashboardPage() {
    try {
        def rows = buildSummaryRows()
        def ts   = new Date().format("h:mm a", location.timeZone)
        def body = rows ? summaryHtml(rows, "", true) :
            "<div class='bm-ok'>No monitored devices with a battery reading yet.</div>"
        def html = """<!DOCTYPE html><html><head><meta charset='UTF-8'>
<meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Battery Monitor</title>
<link rel='stylesheet' href='https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.5.1/css/all.min.css'>
<style>${portalCss()}</style>
<script>setTimeout(function(){ location.reload(); }, 120000);</script>
</head><body>
<div class='bm-portal bm-dark'>
  <div class='bm-portal-head'>
    <div><div class='bm-portal-title'>🔋 Battery Monitor</div>
      <div class='bm-portal-sub'>Updated ${ts} · refreshes every 2 min</div></div>
    <a class='bm-scan' href='refresh?access_token=${state.accessToken}'><i class='fa-solid fa-rotate-right'></i>Force scan</a>
  </div>
  ${body}
  <div class='bm-portal-foot'>Battery Monitor v${APP_VERSION} · jdthomas24</div>
</div>
</body></html>"""
        return render(contentType: "text/html", data: html, status: 200)
    } catch (Exception e) {
        log.error "Battery Monitor portal error: ${e}"
        return render(contentType: "text/html", data: "<h3 style='color:white;font-family:sans-serif;'>Portal Error</h3><p style='color:#ccc;'>${e}</p>", status: 500)
    }
}

/** Dark theme for the standalone portal; shares markup with the Summary page. */
private String portalCss() {
    """
  body { margin: 0; padding: 16px; background: #0d0d0d; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
  .bm-portal { max-width: 1000px; margin: 0 auto; background: #151515; border-radius: 12px; padding: 18px 20px; box-sizing: border-box; }
  .bm-portal-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 14px; }
  .bm-portal-title { font-size: 20px; font-weight: 600; color: #fff; }
  .bm-portal-sub { font-size: 12px; color: #8b8b8b; margin-top: 2px; }
  .bm-scan { display: inline-flex; align-items: center; gap: 6px; padding: 7px 14px; border-radius: 6px; background: #1f618d; color: #fff; text-decoration: none; font-size: 14px; font-weight: 600; }
  .bm-scan:hover { background: #1a5276; }
  .bm-portal-foot { text-align: center; font-size: 11px; color: #555; margin-top: 18px; }
  .bm-dark .bm-wrap { color: #e5e7eb; }
  .bm-dark .bm-devname { color: #fff; font-weight: 600; }
  .bm-dark .bm-stat { background: #1e1e1e; }
  .bm-dark .bm-stat:hover { border-color: #3a3a3a; }
  .bm-dark .bm-stat.bm-on { background: #17263d; border-color: #3b82f6; }
  .bm-dark .bm-stat-label, .bm-dark .bm-sub, .bm-dark .bm-muted, .bm-dark .bm-table th { color: #9ca3af; }
  .bm-dark .bm-list { border-color: #2a2a2a; }
  .bm-dark .bm-item { border-top-color: #232323; }
  .bm-dark .bm-table th { border-bottom-color: #2a2a2a; }
  .bm-dark .bm-table td { border-bottom-color: #222; }
  .bm-dark .bm-table tr.bm-row:hover td { background: #1b1b1b; }
  .bm-dark .bm-bar { background: #333; }
  .bm-dark .bm-ok { background: #12301a; color: #4ade80; }
  .bm-dark .bm-buy, .bm-dark .bm-switch { color: #d1d5db; }
  .bm-dark .bm-toolbar input { background: #1e1e1e; border-color: #333; color: #e5e7eb; }
  .bm-dark .bm-t-red   { background: #3b1212; color: #f87171; }
  .bm-dark .bm-t-green { background: #12301a; color: #4ade80; }
  .bm-dark .bm-t-amber { background: #3b2a12; color: #fbbf24; }
  .bm-dark .bm-t-gray  { background: #262626; color: #a3a3a3; }
  .bm-dark .bm-t-blue  { background: #172554; color: #93c5fd; }
  .bm-dark .bm-c-red   { color: #f87171; }
  .bm-dark .bm-c-amber { color: #fbbf24; }
  .bm-dark .bm-c-green { color: #4ade80; }
  @media (max-width: 700px) {
    body { padding: 8px; }
    .bm-portal { padding: 14px 12px; }
    .bm-dark .bm-table tr.bm-row { border-color: #2a2a2a; }
  }
"""
}

// ============================================================
// ===================== SUMMARY PAGE ========================
// ============================================================
def summaryPage() {
    dynamicPage(name: "summaryPage", title: "Battery Summary & Trends", install: false) {

        if (!state.history || !autoDevices || autoDevices.size() == 0) {
            section("Setup Required") {
                paragraph "⚠ <b>Setup Not Complete</b><br><br>" +
                          "You must click <b>Done</b> after selecting your devices before viewing reports.<br><br>" +
                          "Please exit the app and reopen it, then try again."
            }
            return
        }

        def hubIp = location?.hub?.localIP ?: ""
        def rows  = buildSummaryRows()

        section("") {
            href(name: "toForceScanFromSummary", page: "forceScanPage",
                 title: "<i class='fa-solid fa-rotate-right' style='margin-right:6px;'></i>Force scan now",
                 description: "")
            if (!rows) { paragraph "No battery devices found."; return }
            paragraph rawHtml: true, summaryHtml(rows, hubIp)
        }

        section("<b>📖 Legend</b>", hideable: true, hidden: true) {
            paragraph "<div style='background-color:#e8f0fe; border-left:4px solid #1a73e8; border-radius:0; padding:8px 12px; font-size:13px; color:#1a1a1a;'>" +
                      "<b>Pending</b> = still learning; drain and life fill in once enough samples are collected. <b>Dead</b> = confirmed dead, replace now.<br><br>" +
                      "<b>Health</b>: Excellent/Good = healthy &nbsp;·&nbsp; Fair = elevated drain, worth watching &nbsp;·&nbsp; Poor = high drain, replace soon. " +
                      "A ⚠ note under health means the recent trend is worse than the long-term rating.<br><br>" +
                      "<b>&lt;0.01%/day</b> = very slow drain, normal for smoke and CO detectors.<br><br>" +
                      "Tap a count at the top to filter, a column header to sort, or search by name. <b>Issues only</b> hides devices with nothing flagged and is remembered in this browser." +
                      "</div>"
        }
    }
}

/** One data map per monitored, non-ignored device. Display only. */
private List buildSummaryRows() {
    def devList = (autoDevices ?: []).findAll {
        try { it?.currentValue("battery") != null && !isIgnored(it) } catch (e) { false }
    }
    def healthRank = ["Excellent": 1, "Good": 2, "Fair": 3, "Poor": 4]
    def trendRank  = ["Stable": 1, "Moderate": 2, "Heavy Drain": 3]
    return devList.collect { device ->
        def r = [dev: device, name: device.displayName ?: "Unknown Device", dead: false, level: 0,
                 h: "Pending", drain: 0.3, est: null, actMs: 0, stale: false, low: false, highDrain: false]
        try {
            r.dead      = isBatteryDead(device)
            r.level     = device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 0
            r.h         = r.dead ? "Dead" : health(device)
            r.drain     = getDrain(device)
            r.est       = estDays(device)
            try { r.actMs = safeTime(device.getLastActivity()) ?: 0 } catch (e) { }
            r.stale     = isStale(device)
            r.low       = !r.dead && r.level <= 25
            r.highDrain = !r.dead && (r.h == "Poor" || r.h == "Fair") && r.drain > 1.5
            r.type      = getCatalogBatteryInfo(device)
            r.replaced  = state.history?.get(device.id)?.justReplaced == true
            def trend   = state.trend?.get(device.id) ?: "Stable"
            def hR      = healthRank[r.h] ?: 2
            def tR      = trendRank[trend] ?: 1
            if (!r.dead && r.h != "Pending" && (hR >= 3 || tR > hR)) {
                r.trendNote = [label: trend == "Moderate" ? "Moderate Drain" : trend, warn: tR > hR, heavy: trend == "Heavy Drain"]
            }
            if (!r.dead && r.h == "Pending") r.pendingNote = getHealthDisplay(device)
        } catch (e) {
            log.warn "Battery Monitor: summary row failed for ${device.displayName}: ${e.message}"
        }
        r
    }
}

/** Hub-relative link; hubLinkScript() adds any remote-access path prefix. */
private String hubLink(String path, String inner) {
    "<a href='${path}' class='bm-hublink' data-path='${path}' target='_blank'>${inner}</a>"
}

private String hubLinkScript() {
    """
<script>
(function(){
  var p = location.pathname, i = p.indexOf('/installedapp/');
  var pre = i > 0 ? p.substring(0, i) : '';
  [].forEach.call(document.querySelectorAll('a.bm-hublink'), function(a){
    a.setAttribute('href', pre + a.getAttribute('data-path'));
  });
})();
</script>
"""
}

private String bmEsc(v) {
    (v == null ? "" : v.toString()).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace('"', "&quot;").replace("'", "&#39;")
}

/** Display-only drain; stored value stays exact. */
private String fmtDrain(d) {
    if (d == null) return "—"
    return (d as double) < 0.01 ? "<0.01" : String.format("%.2f", d as double)
}

private String bmPill(String text, String tone) {
    def t = tone in ["red", "green", "amber", "gray", "blue"] ? tone : "gray"
    "<span class='bm-pill bm-t-${t}'>${text}</span>"
}

/** Light theme colors for pills, counts, and icons. The portal overrides these for dark mode. */
private String bmToneCss() {
    """
  .bm-t-red   { background: #fdecec; color: #b42318; }
  .bm-t-green { background: #e7f6ec; color: #1e7b34; }
  .bm-t-amber { background: #fff4e0; color: #9a5b00; }
  .bm-t-gray  { background: #f1f3f5; color: #4b5563; }
  .bm-t-blue  { background: #e8f0fe; color: #1a56c4; }
  .bm-c-red   { color: #b42318; }
  .bm-c-amber { color: #9a5b00; }
  .bm-c-green { color: #1e7b34; }
"""
}

private String summaryHtml(List rows, String hubIp, boolean portal = false) {
    int nDead  = rows.count { it.dead }
    int nLow   = rows.count { it.low }
    int nDrain = rows.count { it.highDrain }
    int nStale = rows.count { it.stale }
    def hTone  = [Excellent: "green", Good: "green", Fair: "amber", Poor: "red", Dead: "red", Pending: "gray"]
    def hRank  = [Dead: 5, Poor: 4, Fair: 3, Good: 2, Excellent: 1, Pending: 0]

    def nameLink = { r ->
        portal ? "<span class='bm-devname'>${bmEsc(r.name)}</span>" : hubLink("/device/edit/${r.dev.id}", bmEsc(r.name))
    }
    def seenText = { r -> r.actMs ? formatTimeAgo(r.actMs) : "N/A" }

    def sb = new StringBuilder()
    sb << """
<style>
  .bm-wrap { font-size: 14px; color: #1f2937; }
  button.hrefElem[name^='_action_href_toForceScanFromSummary'] {
    display: inline-block; width: auto !important; min-height: 34px; padding: 0 14px; margin: 0;
    background: #fff; color: #1a56c4; border: 1px solid #cfd6de; border-radius: 4px; box-shadow: none;
    font-family: inherit; font-size: 14px; font-weight: 500; line-height: 34px;
  }
  button.hrefElem[name^='_action_href_toForceScanFromSummary']::before,
  button.hrefElem[name^='_action_href_toForceScanFromSummary'] > br,
  button.hrefElem[name^='_action_href_toForceScanFromSummary'] > .state-incomplete-text,
  button.hrefElem[name^='_action_href_toForceScanFromSummary'] > .state-complete-text { display: none; }
  button.hrefElem[name^='_action_href_toForceScanFromSummary']:hover { background: #f3f6fa; }
  .bm-stats { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; margin: 2px 0 12px; }
  .bm-stat { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; background: #f5f7fa; border: 1.5px solid transparent; border-radius: 6px; padding: 6px 12px; cursor: pointer; user-select: none; }
  .bm-stat:hover { border-color: #c9d3df; }
  .bm-stat.bm-on { border-color: #1a73e8; background: #eef4fd; }
  .bm-stat-label { font-size: 13px; color: #6b7280; }
  .bm-stat-num { font-size: 18px; font-weight: 600; }
  .bm-h { font-size: 15px; font-weight: 600; margin: 0; }
  .bm-headrow { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin: 0 0 6px; }
  .bm-list { border: 1px solid #e3e6ea; border-radius: 6px; margin-bottom: 14px; }
  .bm-item { display: flex; align-items: center; gap: 10px; padding: 6px 12px; border-top: 1px solid #eef0f3; }
  .bm-item:first-child { border-top: 0; }
  .bm-item-main { flex: 1; min-width: 0; }
  .bm-sub { font-size: 12px; color: #6b7280; }
  .bm-buy { font-size: 13px; color: #374151; }
  .bm-ok { background: #e7f6ec; color: #1e7b34; border-radius: 6px; padding: 8px 12px; margin-bottom: 14px; }
  .bm-switch { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #374151; cursor: pointer; user-select: none; white-space: nowrap; }
  .bm-switch input { width: 16px; height: 16px; margin: 0; cursor: pointer; }
  .bm-tools { display: flex; align-items: center; gap: 12px; }
  .bm-pill { display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 9px; border-radius: 999px; white-space: nowrap; margin-left: 4px; }
  ${bmToneCss()}
  .bm-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; margin: 0 0 4px; }
  .bm-toolbar input { max-width: 220px; padding: 6px 10px; border: 1px solid #cfd6de; border-radius: 4px; font-size: 14px; }
  .bm-table { width: 100%; border-collapse: collapse; }
  .bm-table th { font-size: 12px; font-weight: 600; color: #6b7280; text-align: left; padding: 8px; border-bottom: 1px solid #e3e6ea; cursor: pointer; user-select: none; white-space: nowrap; }
  .bm-table th.bm-sorted[data-dir='asc']::after { content: ' ▲'; font-size: 10px; }
  .bm-table th.bm-sorted[data-dir='desc']::after { content: ' ▼'; font-size: 10px; }
  .bm-table td { padding: 6px 8px; border-bottom: 1px solid #eef0f3; vertical-align: middle; }
  .bm-foot { font-size: 12px; color: #6b7280; margin-top: 6px; }
  .bm-table tr.bm-row:hover td { background: #fafbfc; }
  .bm-bar { display: inline-block; width: 46px; height: 6px; border-radius: 3px; background: #e5e7eb; vertical-align: middle; margin-right: 6px; overflow: hidden; }
  .bm-bar > span { display: block; height: 100%; }
  .bm-muted { color: #6b7280; }
  .bm-note { font-size: 11px; margin-top: 3px; }
  @media (max-width: 700px) {
    .bm-stats { grid-template-columns: repeat(3, minmax(0, 1fr)); }
    .bm-item { flex-wrap: wrap; }
    .bm-tools { width: 100%; justify-content: space-between; }
    .bm-toolbar input { max-width: none; flex: 1; }
    .bm-table thead { display: none; }
    .bm-table tr.bm-row { display: block; border: 1px solid #e3e6ea; border-radius: 6px; margin-bottom: 8px; padding: 4px; }
    .bm-table tr.bm-row td { display: flex; justify-content: space-between; align-items: center; border: 0; padding: 4px 6px; }
    .bm-table tr.bm-row td::before { content: attr(data-label); color: #6b7280; font-size: 12px; margin-right: 8px; }
  }
</style>
<div class='bm-wrap'>
<div class='bm-stats'>
  <div class='bm-stat' data-f='dead'><div class='bm-stat-label'>Dead</div><div class='bm-stat-num ${nDead ? "bm-c-red" : ""}'>${nDead}</div></div>
  <div class='bm-stat' data-f='low'><div class='bm-stat-label'>Low</div><div class='bm-stat-num ${nLow ? "bm-c-red" : ""}'>${nLow}</div></div>
  <div class='bm-stat' data-f='drain'><div class='bm-stat-label'>High drain</div><div class='bm-stat-num ${nDrain ? "bm-c-amber" : ""}'>${nDrain}</div></div>
  <div class='bm-stat' data-f='stale'><div class='bm-stat-label'>Stale</div><div class='bm-stat-num ${nStale ? "bm-c-amber" : ""}'>${nStale}</div></div>
  <div class='bm-stat bm-on' data-f='all'><div class='bm-stat-label'>Total</div><div class='bm-stat-num'>${rows.size()}</div></div>
</div>
"""

    // ---------- Needs attention ----------
    def attention = rows.findAll { it.dead || it.low || it.highDrain || it.stale }.sort { a, b ->
        def pa = a.dead ? 0 : a.low ? 1 : a.highDrain ? 2 : 3
        def pb = b.dead ? 0 : b.low ? 1 : b.highDrain ? 2 : 3
        pa != pb ? pa <=> pb : a.level <=> b.level
    }
    if (!attention) {
        sb << "<div class='bm-ok'><i class='fa-solid fa-circle-check' style='margin-right:6px;'></i>Nothing needs attention</div>"
    } else {
        // What to buy: dead and low devices only
        def buy = [:]
        int untyped = 0
        attention.findAll { it.dead || it.low }.each { r ->
            def bt = settings["battType_${r.dev.id}"]
            if (!bt || bt.startsWith("_sep")) { untyped++; return }
            if (bt == "Integrated") return
            def t = bt == "Other" ? (settings["battCustomType_${r.dev.id}"]?.trim() ?: "Other") : bt
            def q = 1
            try { q = (settings["battCount_${r.dev.id}"] ?: 1) as Integer } catch (e) { }
            buy[t] = (buy[t] ?: 0) + q
        }
        def buyHtml = ""
        if (buy || untyped) {
            def parts = buy.sort { it.key }.collect { t, q -> "<b>${q}×</b> ${bmEsc(t)}" }
            if (untyped) parts << "<span class='bm-muted'>${untyped} with no type set</span>"
            buyHtml = "<div class='bm-buy'><i class='fa-solid fa-cart-shopping' style='margin-right:6px;color:#1a73e8;'></i>To replace: ${parts.join(' &nbsp;·&nbsp; ')}</div>"
        }
        sb << "<div class='bm-headrow'><div class='bm-h'>Needs attention</div>${buyHtml}</div><div class='bm-list'>"
        attention.each { r ->
            def icon = r.dead ? "fa-solid fa-battery-empty bm-c-red' style='" :
                       r.low  ? "fa-solid fa-battery-quarter bm-c-red' style='" :
                       r.highDrain ? "fa-solid fa-arrow-trend-down bm-c-amber' style='" :
                       "fa-regular fa-clock bm-c-amber' style='"
            def sub = []
            if (r.type) sub << bmEsc(r.type)
            if (r.low) sub << "${r.level}%"
            if (r.highDrain) sub << "${fmtDrain(r.drain)}%/day"
            sub << "last active ${seenText(r)}"
            def pills = ""
            if (r.dead)      pills += bmPill("Dead", "red")
            if (r.low)       pills += bmPill("Low", "red")
            if (r.highDrain) pills += bmPill("High drain", "amber")
            if (r.stale)     pills += bmPill("Stale", "amber")
            sb << "<div class='bm-item'><i class='${icon} font-size:16px;width:18px;text-align:center;' aria-hidden='true'></i>" +
                  "<div class='bm-item-main'>${nameLink(r)} <span class='bm-sub'>&nbsp;${sub.join(' · ')}</span></div>" +
                  "<div style='white-space:nowrap;'>${pills}</div></div>"
        }
        sb << "</div>"
    }

    // ---------- Table ----------
    sb << """
<div class='bm-toolbar'><div class='bm-h'>All devices</div>
<div class='bm-tools'><label class='bm-switch'><input id='bmIssues' type='checkbox'>Issues only</label>
<input id='bmSearch' type='text' placeholder='Search devices' aria-label='Search devices'></div></div>
<table class='bm-table'>
<thead><tr>
  <th class='bm-th' data-k='name' style='width:34%;'>Device</th>
  <th class='bm-th' data-k='level' style='width:16%;'>Battery</th>
  <th class='bm-th' data-k='health' style='width:18%;'>Health</th>
  <th class='bm-th' data-k='drain' style='width:17%;'>Drain and life</th>
  <th class='bm-th' data-k='seen' style='width:15%;'>Last seen</th>
</tr></thead>
<tbody id='bmBody'>
"""
    def nowMs = now()
    rows.each { r ->
        def flags = []
        if (r.dead) flags << "dead"
        if (r.low) flags << "low"
        if (r.highDrain) flags << "drain"
        if (r.stale) flags << "stale"

        def meta = ""
        if (r.type) meta += bmPill(bmEsc(r.type), "gray")
        if (r.replaced) meta += bmPill("✓ Replaced", "blue")
        def nameCell = "${nameLink(r)}${meta}"

        def barColor = r.level > 70 ? "#22a045" : r.level > 25 ? "#e08a00" : "#d93025"
        def battCell = "<span class='bm-bar'><span style='width:${Math.max(0, Math.min(100, r.level))}%;background:${barColor};'></span></span>" +
            (r.dead ? "<span class='bm-c-red'>${r.level}%</span>" : "${r.level}%")

        def healthCell = bmPill(r.h, hTone[r.h] ?: "gray")
        if (r.pendingNote) healthCell += "<div class='bm-note'>${r.pendingNote}</div>"
        if (r.trendNote) {
            def c = r.trendNote.heavy ? "bm-c-red" : r.trendNote.label == "Stable" ? "bm-c-green" : "bm-c-amber"
            healthCell += "<div class='bm-note ${c}'>${r.trendNote.warn ? '⚠ ' : ''}${r.trendNote.label}</div>"
        }

        def drainCell = r.dead ? "<span class='bm-muted'>—</span>" :
            r.h == "Pending" ? "<span class='bm-muted'>Learning</span>" :
            "${fmtDrain(r.drain)}%/day<span class='bm-muted'> · ${r.est != null ? r.est + 'd' : '—'}</span>"

        def seenCell = seenText(r) + (r.stale ? bmPill("Stale", "amber") : "")

        def sortLevel  = r.dead ? -1 : r.level
        def sortDrain  = (r.dead || r.h == "Pending") ? -1 : (r.drain as double)
        def sortSeen   = r.actMs ? ((nowMs - (r.actMs as Long)) / 1000).toLong() : 999999999L

        sb << "<tr class='bm-row' data-name=\"${bmEsc(r.name.toLowerCase())}\" data-level='${sortLevel}' " +
              "data-health='${hRank[r.h] ?: 0}' data-drain='${sortDrain}' data-seen='${sortSeen}' data-flags='${flags.join(' ')}'>" +
              "<td data-label='Device'>${nameCell}</td>" +
              "<td data-label='Battery'>${battCell}</td>" +
              "<td data-label='Health'>${healthCell}</td>" +
              "<td data-label='Drain and life'>${drainCell}</td>" +
              "<td data-label='Last seen'>${seenCell}</td></tr>"
    }
    sb << """
</tbody>
<tfoot><tr id='bmEmpty' style='display:none;'><td colspan='5' class='bm-muted' style='text-align:center;padding:16px;'>No devices in this group</td></tr></tfoot>
</table>
</div>
<script>
(function(){
  var tb = document.getElementById('bmBody'); if (!tb) return;
  var rows = [].slice.call(tb.querySelectorAll('tr.bm-row'));
  var f = 'all', k = 'level', asc = true;
  var issuesBox = document.getElementById('bmIssues'), key = 'bmIssuesOnly_' + location.pathname;
  try { issuesBox.checked = localStorage.getItem(key) === '1'; } catch (e) {}
  function draw(){
    var q = (document.getElementById('bmSearch').value || '').toLowerCase(), shown = 0;
    rows.sort(function(a, b){
      var x = a.getAttribute('data-' + k), y = b.getAttribute('data-' + k);
      if (k !== 'name') { x = parseFloat(x); y = parseFloat(y); }
      return (x > y ? 1 : x < y ? -1 : 0) * (asc ? 1 : -1);
    });
    rows.forEach(function(r){
      tb.appendChild(r);
      var fl = r.getAttribute('data-flags');
      var ok = (f === 'all' || (' ' + fl + ' ').indexOf(' ' + f + ' ') >= 0) &&
               (!issuesBox.checked || fl.length > 0) &&
               r.getAttribute('data-name').indexOf(q) >= 0;
      r.style.display = ok ? '' : 'none';
      if (ok) shown++;
    });
    var empty = document.getElementById('bmEmpty');
    empty.style.display = shown ? 'none' : '';
    empty.firstElementChild.textContent = issuesBox.checked && f === 'all' && !q ? 'No devices need attention' : 'No devices in this group';
    [].forEach.call(document.querySelectorAll('.bm-th'), function(t){
      t.classList.toggle('bm-sorted', t.getAttribute('data-k') === k);
      t.setAttribute('data-dir', asc ? 'asc' : 'desc');
    });
  }
  [].forEach.call(document.querySelectorAll('.bm-stat'), function(s){
    s.addEventListener('click', function(){
      [].forEach.call(document.querySelectorAll('.bm-stat'), function(x){ x.classList.remove('bm-on'); });
      s.classList.add('bm-on'); f = s.getAttribute('data-f'); draw();
    });
  });
  [].forEach.call(document.querySelectorAll('.bm-th'), function(t){
    t.addEventListener('click', function(){
      var nk = t.getAttribute('data-k'); asc = (nk === k) ? !asc : (nk !== 'health' && nk !== 'drain'); k = nk; draw();
    });
  });
  document.getElementById('bmSearch').addEventListener('input', draw);
  issuesBox.addEventListener('change', function(){
    try { localStorage.setItem(key, issuesBox.checked ? '1' : '0'); } catch (e) {}
    draw();
  });
  draw();
})();
</script>
"""
    if (!portal) sb << hubLinkScript()
    return sb.toString()
}

// ============================================================
// ===================== SHARED PAGE HELPERS =================
// ============================================================
def batteryTypeOptions() {
    def o = ["": "— Not Set —"]
    o["_sep1"] = "──────── Standard ────────"
    ["AA", "AAA", "CR2", "CR1632", "CR2016", "CR2032", "CR2430", "CR2450", "CR2477", "CR123A", "9V", "ER14250", "LS14250"].each { o[it] = it }
    o["Integrated"] = "Integrated"
    o["_sep2"] = "──────── Rechargeable ────────"
    ["Rechargeable AA", "Rechargeable AAA", "LIR2016", "LIR2032", "LIR2430", "LIR2450", "18650", "RCR123A", "RCR2"].each { o[it] = it }
    o["_sep3"] = "──────── Other ────────"
    o["Other"] = "Other"
    return o
}

private Integer currentLevel(device) {
    try { return device.currentValue("battery") != null ? device.currentValue("battery").toInteger() : 100 } catch (e) { return 100 }
}

private List ignoredIdList() { (settings?.ignoredDevices?.collect { it as String }) ?: [] }

private void resetDrainHistory(device) {
    def existing = state.history[device.id] ?: [:]
    state.history[device.id] = [
        lastLevel:     existing.lastLevel ?: currentLevel(device),
        lastDate:      now(),
        lastScanDate:  now(),
        firstSeenDate: existing.firstSeenDate ?: existing.replacedTime ?: existing.lastDate ?: now(),
        replacedTime:  existing.replacedTime,
        justReplaced:  existing.justReplaced ?: false,
        drain:         0.3,
        samples:       [],
        zeroCount:     0
    ]
    state.trend[device.id] = "Stable"
    state.history = state.history
}

/** Fresh start after un-ignoring: history reset plus a Restored entry. */
private void markRestored(device) {
    def lvl = currentLevel(device)
    state.history[device.id] = [
        lastLevel: lvl, lastDate: now(), lastScanDate: now(),
        firstSeenDate: now(), replacedTime: now(), justReplaced: true,
        drain: 0.3, samples: [], zeroCount: 0
    ]
    state.trend[device.id] = "Stable"
    state.history = state.history
    state.replacements = state.replacements ?: []
    state.replacements << [deviceId: device.id, device: device.displayName, level: lvl,
        date: new Date().format("MM/dd/yyyy", location.timeZone), type: "restored"]
    state.replacements = state.replacements.sort { a, b -> replacementTime(b) <=> replacementTime(a) }.take(100)
}

/** Red = dead/low, amber = high drain/stale, green = fine. */
private String deviceStatusColor(device) {
    try {
        if (isBatteryDead(device) || currentLevel(device) <= 25) return "#d93025"
        def h = health(device)
        if (((h == "Poor" || h == "Fair") && getDrain(device) > 1.5) || isStale(device)) return "#e08a00"
    } catch (e) { }
    return "#22a045"
}

private String bmMsgHtml(Map m) {
    if (!m) return ""
    def cls = m.tone == "ok" ? "bm-msg-ok" : m.tone == "err" ? "bm-msg-err" : "bm-msg-warn"
    return "<div class='bm-msg ${cls}'>${m.text}</div>"
}

private String bmCardsCss() {
    """
  .bm-cards button.hrefElem {
    position: relative; background: #fff; border: 1px solid #e0e0e0; border-radius: 6px; box-shadow: none;
    min-height: 62px; padding: 8px 28px 8px 48px; font-family: inherit; text-align: left;
  }
  .bm-cards button.hrefElem > span:first-child { color: #1565c0; font-weight: 600; font-size: 16px !important; }
  .bm-cards button.hrefElem > .state-incomplete-text, .bm-cards button.hrefElem > .state-complete-text { color: #6b7280; font-size: 14px !important; }
  .bm-cards button.hrefElem i { position: absolute; left: 16px; top: 50%; transform: translateY(-50%); color: #1565c0; font-size: 18px; }
  .bm-cards button.hrefElem::before { color: #1565c0; }
  .bm-cards-primary button.hrefElem { background: #eef4fd; border-color: #c7dafc; }
  .bm-cards-primary button.hrefElem:hover { background: #e3edfc; }
  .bm-settings > .mdl-grid { border: 1px solid #e3e6ea; border-radius: 6px; overflow: hidden; padding: 0 !important; margin: 0 8px 8px; }
  .bm-settings .mdl-cell { margin: 0 !important; width: 100% !important; max-width: 100%; box-sizing: border-box; }
  .bm-settings button.hrefElem {
    display: flex; align-items: center; width: 100%; box-sizing: border-box; background: #fff; border: 0; border-bottom: 1px solid #eef0f3;
    border-radius: 0; box-shadow: none; min-height: 0; padding: 11px 44px 11px 14px; font-family: inherit; text-align: left;
  }
  .bm-settings .mdl-cell:last-child button.hrefElem { border-bottom: 0; }
  .bm-settings button.hrefElem:hover { background: #f7f9fb; }
  .bm-settings button.hrefElem > br { display: none; }
  .bm-settings button.hrefElem > span:first-child { color: #1f2937; font-weight: 500; font-size: 15px !important; }
  .bm-settings button.hrefElem > .state-incomplete-text,
  .bm-settings button.hrefElem > .state-complete-text { margin-left: auto; padding-left: 16px; color: #6b7280; font-size: 14px !important; text-align: right; white-space: nowrap; }
  .bm-settings button.hrefElem i { color: #6b7280; width: 18px; text-align: center; margin-right: 10px; }
  @media (max-width: 600px) {
    .bm-settings button.hrefElem { flex-wrap: wrap; }
    .bm-settings button.hrefElem > .state-incomplete-text,
    .bm-settings button.hrefElem > .state-complete-text {
      width: 100%; margin-left: 28px; padding-left: 0; text-align: left; white-space: normal; margin-top: 2px;
    }
  }
  .bm-helprow { text-align: center; font-size: 14px; color: #6b7280; margin-top: 4px; }
  .bm-helprow a { color: #1565c0; text-decoration: none; margin: 0 10px; white-space: nowrap; }
  .bm-helprow a:hover { text-decoration: underline; }
  .bm-helprow i { margin-right: 5px; }
"""
}

private String bmPageCss() {
    """
<style>
  ${tileSecondaryTextCss()}
  ${tipsCardCss()}
  ${bmCardsCss()}
  .bm-pill { display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 9px; border-radius: 999px; white-space: nowrap; margin-left: 4px; }
  ${bmToneCss()}
  .bm-h { font-size: 15px; font-weight: 600; margin: 0 0 2px; }
  .bm-muted { color: #6b7280; }
  .bm-hint { font-size: 13px; color: #6b7280; }
  .bm-msg { border-radius: 6px; padding: 8px 12px; font-size: 14px; }
  .bm-msg-ok { background: #e7f6ec; color: #1e7b34; }
  .bm-msg-warn { background: #fff4e0; color: #9a5b00; }
  .bm-msg-err { background: #fdecec; color: #b42318; }
  .bm-msg-info { background: #e8f0fe; color: #1a56c4; }
  .bm-msg-muted { background: #f5f7fa; color: #6b7280; }
  .bm-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-bottom: 10px; }
  .bm-stat { background: #f5f7fa; border-radius: 6px; padding: 6px 12px; }
  .bm-stat-label { font-size: 12px; color: #6b7280; }
  .bm-stat-num { font-size: 16px; font-weight: 600; }
  .bm-btn button {
    min-height: 36px; width: 100%; padding: 0 12px; border-radius: 4px; font-family: inherit; font-size: 14px;
    box-shadow: none; background: #fff; color: #1a56c4; border: 1px solid #cfd6de;
  }
  .bm-btn button:hover { background: #f3f6fa; }
  .bm-btn-primary button { background: #1a73e8 !important; color: #fff !important; border-color: #1a73e8 !important; }
  .bm-btn-danger button { color: #b42318 !important; }
  .bm-table { width: 100%; border-collapse: collapse; font-size: 14px; }
  .bm-table th { font-size: 12px; font-weight: 600; color: #6b7280; text-align: left; padding: 6px 8px; border-bottom: 1px solid #e3e6ea; }
  .bm-table td { padding: 6px 8px; border-bottom: 1px solid #eef0f3; vertical-align: middle; }
  @media (max-width: 600px) {
    .bm-stat { padding: 6px 8px; }
    .bm-stat-num { font-size: 13px; }
    .bm-quick { width: calc(50% - 16px) !important; display: inline-block; }
  }
</style>
"""
}

/** Sort key for history entries. Dates are stored as MM/dd/yyyy text, which sorts by month, not year. */
private long replacementTime(r) {
    def d = r?.date?.toString()
    if (!d) return 0L
    try {
        return (d =~ /^\d{4}-\d{2}-\d{2}/) ? new Date().parse("yyyy-MM-dd HH:mm", d).time : new Date().parse("MM/dd/yyyy", d).time
    } catch (e) {
        return 0L
    }
}

private String historyTypePill(String type) {
    type == "manual"   ? bmPill("Manual", "blue") :
    type == "auto"     ? bmPill("Auto", "green") :
    type == "restored" ? bmPill("Restored", "gray") : bmPill("?", "gray")
}

// ============================================================
// ===================== BUTTON HANDLER ======================
// ============================================================
void appButtonHandler(String btn) {
    switch (btn) {
        case "daReplace":    state.daPending = [action: "replace", deviceId: state.daDeviceId]; break
        case "daReset":      state.daPending = [action: "reset",   deviceId: state.daDeviceId]; break
        case "daIgnore":     state.daPending = [action: "ignore",  deviceId: state.daDeviceId]; break
        case "daCancel":     state.remove("daPending"); break
        case "daConfirm":    runDeviceAction(); break
        case "bulkSelDead":  bulkQuickSelect("dead"); break
        case "bulkSelLow":   bulkQuickSelect("low"); break
        case "bulkSelStale": bulkQuickSelect("stale"); break
        case "bulkSelClear": app.updateSetting("bulkSelectedDevices", [value: [], type: "enum"]); break
        case "bulkApply":    runBulkAction(); break
        case "sendNow":      sendNotificationNow(); break
        case "snoozeStart":
            def days = 7
            try { days = Math.max(1, (settings?.snoozeDurationDays ?: 7) as Integer) } catch (e) { }
            state.notifSnoozedUntil = now() + (days * 86400000L)
            break
        case "snoozeClear":
            state.notifSnoozedUntil = 0
            state.snoozeMsg = [tone: "ok", text: "Snooze cleared. Notifications resumed."]
            break
    }
}

// ============================================================
// ===================== DEVICE MANAGE PAGE ==================
// ============================================================
def deviceManagePage(Map params = [:]) {
    def ignoredIds   = ignoredIdList()
    def ignoredNames = (autoDevices ?: []).findAll { ignoredIds.contains(it.id as String) }.collect { it.displayName }
    int untyped = (autoDevices ?: []).count { dev ->
        def t = settings["battType_${dev.id}"] ?: ""
        !t || t.startsWith("_sep") || (t == "Other" && !(settings["battCustomType_${dev.id}"]?.trim()))
    }

    dynamicPage(name: "deviceManagePage", title: "Device Battery Management", install: false) {
        section(sectionClass: "bm-cards") {
            paragraph rawHtml: true, bmPageCss()
            href name: "toDeviceActions", page: "deviceActionsPage",
                 title: "<i class='fa-solid fa-sliders' aria-hidden='true'></i>Device actions",
                 description: "Type, cliff detection, replacements, and history for one device", width: 6, style: "margin:8px;"
            href name: "toBulkActions", page: "bulkActionsPage",
                 title: "<i class='fa-solid fa-layer-group' aria-hidden='true'></i>Bulk actions",
                 description: "Log replacements, reset, ignore, or restore several at once", width: 6, style: "margin:8px;"
            href name: "toBatteryTypes", page: "batteryTypesPage",
                 title: "<i class='fa-solid fa-battery-half' aria-hidden='true'></i>Battery types",
                 description: untyped ? "${untyped} device${untyped == 1 ? '' : 's'} need a type" : "All devices have a type",
                 width: 6, style: "margin:8px;"
            href name: "toDetectionSettings", page: "detectionSettingsPage",
                 title: "<i class='fa-solid fa-magnifying-glass' aria-hidden='true'></i>Auto-detection",
                 description: "Replacement detection and Li-ion cliff alerts", width: 6, style: "margin:8px;"
        }
        if (ignoredNames) {
            section {
                paragraph rawHtml: true, "<div class='bm-msg bm-msg-warn'><i class='fa-solid fa-ban' style='margin-right:6px;'></i><b>${ignoredNames.size()} ignored:</b> " +
                    "${ignoredNames.collect { bmEsc(it) }.join(', ')}. Restore from Device actions or Bulk actions.</div>"
            }
        }
    }
}

// ============================================================
// ===================== DETECTION SETTINGS PAGE =============
// ============================================================
def detectionSettingsPage() {
    dynamicPage(name: "detectionSettingsPage", title: "Auto-Detection Settings", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() + "<div class='bm-h'>Replacement detection</div>" +
                "<div class='bm-hint'>A confirmed upward jump of at least this much is logged as a replacement automatically.</div>"
            input "detectionMinJump", "number",
                  title: "Minimum upward jump (%)",
                  description: "Default 30. 25 to 30 works well; under 15 risks false positives.",
                  defaultValue: 30, range: "15..60", required: false, width: 6
            href name: "tipsDetection", page: "tipsPage", params: [topic: "detection"],
                 title: "<i class='pi pi-info-circle' aria-hidden='true'></i>How replacement detection works",
                 description: "Rules, confirmation, and cooldown", width: 6, style: "margin:8px;"
        }
        section {
            paragraph rawHtml: true, "<div class='bm-h'>⚡ Li-ion cliff alerts</div>" +
                "<div class='bm-hint'>Urgent alert when a cliff-enabled device drops this much between two readings. Turn it on per device in Battery types or Device actions.</div>"
            input "cliffDropThreshold", "number",
                  title: "Minimum drop (%)",
                  description: "Default 40. Applies to any two consecutive readings.",
                  defaultValue: 40, range: "15..90", required: false, width: 6
            href name: "tipsCliff", page: "tipsPage", params: [topic: "cliff"],
                 title: "<i class='pi pi-info-circle' aria-hidden='true'></i>How cliff alerts work",
                 description: "Which battery types, and why", width: 6, style: "margin:8px;"
            input "cliffBypassSnooze", "bool",
                  title: "Cliff alerts bypass Notification Snooze (recommended)",
                  defaultValue: true
        }
    }
}

// ============================================================
// ===================== BATTERY TYPES PAGE ==================
// ============================================================
def batteryTypesPage() {
    def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase() }
    def needsType = { dev ->
        def t = settings["battType_${dev.id}"] ?: ""
        !t || t.startsWith("_sep") || (t == "Other" && !(settings["battCustomType_${dev.id}"]?.trim()))
    }
    syncCliffDefaults(devList)
    def unassigned = devList.findAll { needsType(it) }
    def assigned   = devList.findAll { !needsType(it) }
    def opts       = batteryTypeOptions()

    dynamicPage(name: "batteryTypesPage", title: "Battery Types", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<style>.bm-bt-name { padding-top: 14px; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }" +
                "@media (max-width: 600px) { .bm-bt-name { padding-top: 10px; font-weight: 600; }" +
                " .bm-bt-type, .bm-bt-custom { width: calc(65% - 16px) !important; display: inline-block; }" +
                " .bm-bt-qty { width: calc(35% - 16px) !important; display: inline-block; }" +
                " .bm-bt-cliff { width: calc(100% - 16px) !important; border-bottom: 1px solid #eef0f3; padding-bottom: 6px; } }</style>" +
                "<div class='bm-hint'>Assign a type and quantity so notifications and the to-replace list show what to buy. " +
                "<b>⚡ Cliff</b> turns on Li-ion cliff alerts for that device. Changes save when you tap Done.</div>"
            href name: "tipsCliffTypes", page: "tipsPage", params: [topic: "cliff"],
                 title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Which batteries need cliff detection?",
                 description: "LIR, 18650, RCR, and custom cells", width: 6, style: "margin:8px;"
        }
        section("<b>Needs a type</b> ${unassigned ? bmPill("${unassigned.size()}", "red") : bmPill("All assigned", "green")}",
                hideable: true, hidden: !unassigned) {
            if (!unassigned) paragraph "All devices have a battery type."
            unassigned.each { dev -> batteryTypeRow(dev, opts) }
        }
        section("<b>Assigned</b> ${bmPill("${assigned.size()}", "blue")}", hideable: true, hidden: true) {
            if (!assigned) paragraph "No devices assigned yet."
            assigned.each { dev -> batteryTypeRow(dev, opts) }
        }
    }
}

/** One compact row: name, type, (custom), qty, cliff. Widths total 12. */
private void batteryTypeRow(dev, Map opts) {
    def t       = settings["battType_${dev.id}"]
    def isOther = t == "Other"
    def lvl     = "—"
    try { def l = dev.currentValue("battery"); if (l != null) lvl = "${l}%" } catch (e) { }
    paragraph rawHtml: true, "<div class='bm-bt-name'>${bmEsc(dev.displayName)} <span class='bm-muted'>${lvl}</span></div>", width: isOther ? 3 : 4
    input "battType_${dev.id}", "enum", title: "", options: opts, required: false,
          defaultValue: t ?: "", submitOnChange: true, width: 3, styleClass: "bm-bt-type"
    if (isOther) {
        input "battCustomType_${dev.id}", "text", title: "", description: "Custom type",
              required: false, width: 2, styleClass: "bm-bt-custom"
    }
    input "battCount_${dev.id}", "number", title: "", description: "Qty",
          defaultValue: settings["battCount_${dev.id}"] ?: 1, required: false, range: "1..99", width: isOther ? 1 : 2,
          styleClass: "bm-bt-qty"
    input "cliffEnabled_${dev.id}", "bool", title: "⚡ Cliff",
          defaultValue: (settings["cliffEnabled_${dev.id}"] != null) ? settings["cliffEnabled_${dev.id}"] : isAutoCliffType(t),
          width: 3, styleClass: "bm-bt-cliff"
}

// ============================================================
// ===================== BULK ACTIONS PAGE ===================
// ============================================================
def bulkActionsPage() {
    def cooldownMs  = 60000
    def elapsed     = now() - (state.bulkActionLastRun ?: 0)
    def secondsLeft = elapsed < cooldownMs ? Math.ceil((cooldownMs - elapsed) / 1000).toInteger() : 0
    def res         = state.remove("bulkResult")
    def ignored     = ignoredIdList()
    def devList     = (autoDevices ?: []).sort { a, b -> a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase() }
    def action      = settings?.bulkAction ?: "replace"
    def selected    = (settings?.bulkSelectedDevices ?: []).collect { it as String }
    def actions     = [replace: "Log battery replacement", reset: "Reset drain history (no replacement)",
                       ignore: "Ignore devices", restore: "Restore ignored devices"]

    dynamicPage(name: "bulkActionsPage", title: "Bulk Actions", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() + "<div class='bm-h'>1. Choose an action</div>"
            input "bulkAction", "enum", title: "", options: actions, defaultValue: "replace",
                  required: true, submitOnChange: true
        }
        section {
            paragraph rawHtml: true, "<div class='bm-h'>2. Select devices</div><div class='bm-hint'>Quick select, or pick from the list.</div>"
            input "bulkSelDead",  "button", title: "All dead",  width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelLow",   "button", title: "All low",   width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelStale", "button", title: "All stale", width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelClear", "button", title: "Clear",     width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelectedDevices", "enum", title: "",
                  options: devList.collectEntries { d -> [(d.id as String): bulkLabel(d, ignored)] },
                  multiple: true, required: false, submitOnChange: true
        }
        section {
            int n = selected.size()
            def verbs = [replace: "be logged as replaced", reset: "have drain history reset (no replacement logged)",
                         ignore: "be ignored", restore: "be restored"]
            def review = n ? "<b>${n}</b> device${n == 1 ? '' : 's'} will ${verbs[action]}." : "Select at least one device."
            paragraph rawHtml: true, (res ? bmMsgHtml(res) + "<div style='height:8px;'></div>" : "") +
                "<div class='bm-msg ${n ? 'bm-msg-info' : 'bm-msg-muted'}'>${review}</div>"
            if (secondsLeft > 0) {
                paragraph rawHtml: true, "<div class='bm-hint'><i class='fa-regular fa-clock' style='margin-right:4px;'></i>" +
                    "Bulk actions pause for 60 seconds after each run. Available again in about ${secondsLeft}s.</div>"
            } else {
                input "bulkApply", "button", title: "Apply", width: 3, styleClass: "bm-btn bm-btn-primary"
            }
        }
    }
}

private String bulkLabel(d, List ignored) {
    def tags = []
    try {
        if (ignored.contains(d.id as String)) {
            tags << "ignored"
        } else {
            if (isBatteryDead(d)) tags << "dead"
            else if (currentLevel(d) <= 25) tags << "low"
            if (isStale(d)) tags << "stale"
        }
    } catch (e) { }
    return "${d.displayName} (${currentLevel(d)}%)" + (tags ? " · ${tags.join(', ')}" : "")
}

private void bulkQuickSelect(String kind) {
    def ignored = ignoredIdList()
    def ids = (autoDevices ?: []).findAll { d ->
        if (ignored.contains(d.id as String)) return false
        try {
            if (kind == "dead")  return isBatteryDead(d)
            if (kind == "low")   return !isBatteryDead(d) && currentLevel(d) <= 25
            if (kind == "stale") return isStale(d)
        } catch (e) { }
        return false
    }.collect { it.id as String }
    app.updateSetting("bulkSelectedDevices", [value: ids, type: "enum"])
}

private void runBulkAction() {
    if (now() - (state.bulkActionLastRun ?: 0) < 60000) {
        state.bulkResult = [tone: "warn", text: "Bulk actions are pausing between runs. Try again shortly."]
        return
    }
    def action  = settings?.bulkAction ?: "replace"
    def ids     = (settings?.bulkSelectedDevices ?: []).collect { it as String }
    def devices = (autoDevices ?: []).findAll { ids.contains(it.id as String) }
    if (!devices) {
        state.bulkResult = [tone: "err", text: "Select at least one device first."]
        return
    }
    def done = [], skipped = []
    def ignored = ignoredIdList()
    devices.each { d ->
        def idStr = d.id as String
        try {
            if (action == "replace") {
                logReplacement(d, currentLevel(d), true); done << d.displayName
            } else if (action == "reset") {
                resetDrainHistory(d); done << d.displayName
            } else if (action == "ignore") {
                if (!ignored.contains(idStr)) { ignored << idStr; done << d.displayName }
                else skipped << "${d.displayName} (already ignored)".toString()
            } else if (action == "restore") {
                if (ignored.contains(idStr)) { ignored.remove(idStr); markRestored(d); done << d.displayName }
                else skipped << "${d.displayName} (not ignored)".toString()
            }
        } catch (e) {
            skipped << "${d.displayName} (error)".toString()
            log.warn "Bulk action failed for ${d.displayName}: ${e.message}"
        }
    }
    if (action in ["ignore", "restore"]) app.updateSetting("ignoredDevices", [value: ignored, type: "enum"])
    state.bulkActionLastRun = now()
    app.updateSetting("bulkSelectedDevices", [value: [], type: "enum"])
    def verb = [replace: "Replacement logged", reset: "Drain history reset", ignore: "Ignored", restore: "Restored"][action]
    def text = done ? "${verb} for ${done.size()} device${done.size() == 1 ? '' : 's'}: ${done.collect { bmEsc(it) }.join(', ')}." : "Nothing changed."
    if (skipped) text += "<br>Skipped: ${skipped.collect { bmEsc(it) }.join(', ')}."
    state.bulkResult = [tone: done ? "ok" : "warn", text: text.toString()]
}

// ============================================================
// ===================== DEVICE ACTIONS PAGE =================
// ============================================================
def deviceActionsPage(params) {
    // Hubitat can resend page params on refresh, so only reset when the device actually changes
    if (params?.deviceId && (params.deviceId as String) != (state.daDeviceId as String)) {
        state.daDeviceId = params.deviceId as String
        state.remove("daPending")
    }
    def ignoredIds = ignoredIdList()
    def rank = { d ->
        if (ignoredIds.contains(d.id as String)) return 3
        def c = deviceStatusColor(d)
        return c == "#d93025" ? 0 : c == "#e08a00" ? 1 : 2
    }
    def devList = (autoDevices ?: []).sort { a, b ->
        def ra = rank(a), rb = rank(b)
        ra != rb ? ra <=> rb : a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase()
    }
    def selId  = state.daDeviceId ?: settings?.ddDeviceId
    def device = selId ? devList.find { (it.id as String) == (selId as String) } : null
    if (!device && devList) { device = devList[0]; state.daDeviceId = device.id as String }
    if (device) syncCliffDefaults([device])
    def msg     = state.remove("daMessage")
    def pending = state.daPending
    if (pending && (pending.deviceId as String) != (device?.id as String)) { state.remove("daPending"); pending = null }

    dynamicPage(name: "deviceActionsPage", title: "Device Actions", install: false) {
        section(sectionClass: "bm-da-index") {
            paragraph rawHtml: true, bmPageCss() + daStylesHtml() +
                "<input id='daSearch' class='bm-search' type='text' placeholder='Search devices' aria-label='Search devices'>"
            devList.each { d ->
                def dot = ignoredIds.contains(d.id as String) ? "#9ca3af" : deviceStatusColor(d)
                def cur = (d.id as String) == (device?.id as String) ? "bm-da-current" : ""
                href name: "daDev_${d.id}", page: "deviceActionsPage", params: [deviceId: d.id as String],
                     title: "<span class='${cur}'><span class='bm-dot' style='background:${dot};'></span>${bmEsc(d.displayName)}</span>",
                     description: "", width: 12, style: "margin:0 8px;"
            }
            paragraph rawHtml: true, daSearchScript()
        }
        section(sectionClass: "bm-da-detail") {
            if (!device) {
                paragraph "No monitored devices. Select devices on the main page, then tap Done."
                return
            }
            def idStr   = device.id as String
            def isIgn   = ignoredIds.contains(idStr)
            def dead    = isBatteryDead(device)
            def lvl     = currentLevel(device)
            def h       = dead ? "Dead" : health(device)
            def hTone   = [Excellent: "green", Good: "green", Fair: "amber", Poor: "red", Dead: "red", Pending: "gray"]
            def pills   = bmPill(h, hTone[h] ?: "gray")
            if (isStale(device)) pills += bmPill("Stale", "amber")
            if (isIgn) pills += bmPill("Ignored", "gray")
            def cliffOn = isCliffEnabled(device)
            def est     = estDays(device)
            def drainTx = dead ? "—" : h == "Pending" ? "Learning" : "${fmtDrain(getDrain(device))}%/day · ${est != null ? est + 'd' : '—'}"
            def name    = bmEsc(device.displayName)

            paragraph rawHtml: true, """
<div class='bm-da-head'><div class='bm-da-title'>${name}</div><div style='white-space:nowrap;'>${pills}</div></div>
<div class='bm-stats'>
  <div class='bm-stat'><div class='bm-stat-label'>Battery</div><div class='bm-stat-num'>${lvl}%</div></div>
  <div class='bm-stat'><div class='bm-stat-label'>Drain and life</div><div class='bm-stat-num'>${drainTx}</div></div>
  <div class='bm-stat'><div class='bm-stat-label'>Cliff detection</div><div class='bm-stat-num' style='color:${cliffOn ? "#1e7b34" : "#6b7280"};'>${cliffOn ? "On" : "Off"}</div></div>
</div>
${bmMsgHtml(msg)}
"""
            def bt      = settings["battType_${device.id}"]
            def isOther = bt == "Other"
            input "battType_${device.id}", "enum", title: "Battery type", options: batteryTypeOptions(), required: false,
                  defaultValue: bt ?: "", submitOnChange: true, width: isOther ? 5 : 8
            if (isOther) {
                input "battCustomType_${device.id}", "text", title: "Custom type", description: "e.g. CR17450",
                      required: false, submitOnChange: true, width: 3
            }
            input "battCount_${device.id}", "number", title: "Qty", defaultValue: settings["battCount_${device.id}"] ?: 1,
                  required: false, range: "1..99", submitOnChange: true, width: 4
            def cliffNote = isAutoCliffType(bt) ? "auto-enabled, ${bt} is always Li-ion" :
                            isCliffCandidateType(bt) ? "${bt} is usually Li-ion, confirm before enabling" :
                            "enable only for Li-ion cells"
            input "cliffEnabled_${device.id}", "bool",
                  title: "⚡ Cliff detection <span class='bm-hint'>· ${cliffNote}</span>",
                  defaultValue: (settings["cliffEnabled_${device.id}"] != null) ? settings["cliffEnabled_${device.id}"] : isAutoCliffType(bt),
                  submitOnChange: true

            if (pending) {
                def ask = pending.action == "replace" ? "Log a replacement for <b>${name}</b> at ${lvl}%? Drain history restarts." :
                          pending.action == "reset"   ? "Reset drain history for <b>${name}</b>? No replacement is logged." :
                          isIgn ? "Restore <b>${name}</b>? Drain history resets and a Restored entry is logged." :
                                  "Ignore <b>${name}</b>? It's excluded from reports, notifications, and the portal until restored."
                paragraph rawHtml: true, "<div class='bm-msg bm-msg-warn'>${ask}</div>"
                input "daConfirm", "button", title: "Confirm", width: 3, styleClass: "bm-btn bm-btn-primary"
                input "daCancel",  "button", title: "Cancel",  width: 3, styleClass: "bm-btn"
            } else {
                input "daReplace", "button", title: "<i class='fa-solid fa-battery-full' style='margin-right:6px;'></i>Log replacement",
                      width: 4, styleClass: "bm-btn"
                input "daReset", "button", title: "<i class='fa-solid fa-rotate-left' style='margin-right:6px;'></i>Reset drain history",
                      width: 4, styleClass: "bm-btn"
                input "daIgnore", "button",
                      title: isIgn ? "<i class='fa-solid fa-rotate' style='margin-right:6px;'></i>Restore device" :
                                     "<i class='fa-solid fa-ban' style='margin-right:6px;'></i>Ignore device",
                      width: 4, styleClass: isIgn ? "bm-btn" : "bm-btn bm-btn-danger"
            }

            def hist = (state.replacements ?: []).findAll { r ->
                (r.deviceId as String) == idStr || r.device == device.displayName
            }.sort { a, b -> replacementTime(b) <=> replacementTime(a) }
            def histHtml = hist ? "<table class='bm-table'>" + hist.collect { r ->
                "<tr><td>${r.date}</td><td>${r.level}%</td><td style='text-align:right;'>${historyTypePill(r.type)}</td></tr>"
            }.join("") + "</table>" : "<div class='bm-hint'>No replacements logged yet.</div>"
            paragraph rawHtml: true, "<div class='bm-h' style='margin-top:6px;'>Replacement history</div>${histHtml}"
        }
    }
}

private void runDeviceAction() {
    def p = state.remove("daPending")
    def device = p ? autoDevices?.find { (it.id as String) == (p.deviceId as String) } : null
    if (!device) return
    def name = bmEsc(device.displayName)
    try {
        if (p.action == "replace") {
            def lvl = currentLevel(device)
            logReplacement(device, lvl, true)
            state.daMessage = [tone: "ok", text: "Replacement logged for <b>${name}</b> at ${lvl}%. Health reset to Pending.".toString()]
        } else if (p.action == "reset") {
            resetDrainHistory(device)
            state.daMessage = [tone: "ok", text: "Drain history reset for <b>${name}</b>. No replacement logged.".toString()]
        } else if (p.action == "ignore") {
            def ids   = ignoredIdList()
            def idStr = device.id as String
            if (ids.contains(idStr)) {
                app.updateSetting("ignoredDevices", [value: ids.findAll { it != idStr }, type: "enum"])
                markRestored(device)
                state.daMessage = [tone: "ok", text: "<b>${name}</b> restored. Drain history reset and a Restored entry logged.".toString()]
            } else {
                app.updateSetting("ignoredDevices", [value: ids + [idStr], type: "enum"])
                state.daMessage = [tone: "warn", text: "<b>${name}</b> is now ignored.".toString()]
            }
        }
    } catch (e) {
        log.warn "Battery Monitor: device action failed for ${device.displayName}: ${e.message}"
        state.daMessage = [tone: "err", text: "That action failed for <b>${name}</b>. Check the logs.".toString()]
    }
}

private String daStylesHtml() {
    """
<style>
  div.panel-body { padding: 0 !important; margin-left: -0.5em; margin-right: -0.5em; }
  .bm-da-index { float: left; width: calc(30% - 8px); box-sizing: border-box; }
  .bm-da-detail { float: right; width: 70%; border-left: 1px solid #e0e0e0; padding-left: 8px; box-sizing: border-box; }
  .bm-da-index > .mdl-grid, .bm-da-detail > .mdl-grid { padding: 4px 0 !important; }
  .bm-da-index > .mdl-grid { max-height: 72vh; overflow-y: auto; align-content: flex-start; padding-top: 0 !important; }
  .bm-da-index .mdl-cell { margin-top: 0 !important; margin-bottom: 0 !important; }
  .bm-da-index .mdl-cell:first-child {
    position: sticky; top: 0; z-index: 2; background: #fff; padding: 4px 0 6px;
    box-shadow: 0 4px 4px -4px rgba(0, 0, 0, 0.18);
  }
  .bm-da-index button.hrefElem {
    background: transparent; box-shadow: none; border: 0; border-left: 3px solid transparent; border-radius: 0;
    padding: 6px 10px; min-height: 0; font-family: inherit; font-size: 14px; color: #1f2937; text-align: left;
  }
  .bm-da-index button.hrefElem::before,
  .bm-da-index button.hrefElem > br,
  .bm-da-index button.hrefElem > .state-incomplete-text,
  .bm-da-index button.hrefElem > .state-complete-text { display: none; }
  .bm-da-index button.hrefElem:has(.bm-da-current) { background: #eaf2fc; border-left-color: #1565c0; font-weight: 600; }
  .bm-da-index button.hrefElem:hover { background: #f3f6fa; }
  .bm-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 8px; vertical-align: middle; }
  .bm-search { width: 100%; box-sizing: border-box; padding: 6px 10px; border: 1px solid #cfd6de; border-radius: 4px; font-size: 14px; margin: 0 0 6px; }
  .bm-da-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
  .bm-da-title { font-size: 17px; font-weight: 600; }
  #formApp:has(.bm-da-index) #fieldsetAppButtons { clear: both; }
  @media (max-width: 1000px) {
    .bm-da-index, .bm-da-detail { float: none; width: 100%; padding: 0; border-left: 0; }
    .bm-da-index > .mdl-grid { max-height: 280px; }
  }
</style>
"""
}

private String daSearchScript() {
    """
<script>
(function(){
  // Keep the selected device in view inside the scrolling list after a reload
  var list = document.querySelector('.bm-da-index > .mdl-grid');
  var cur = document.querySelector('.bm-da-index .bm-da-current');
  var btn = cur ? (cur.closest('button') || cur) : null;
  if (list && btn) {
    var offset = btn.getBoundingClientRect().top - list.getBoundingClientRect().top;
    list.scrollTop += offset - list.clientHeight / 3;
  }
  var box = document.getElementById('daSearch'); if (!box) return;
  box.addEventListener('input', function(){
    var q = box.value.toLowerCase();
    [].forEach.call(document.querySelectorAll('.bm-da-index button.hrefElem'), function(b){
      var cell = b.closest('.mdl-cell') || b;
      cell.style.display = b.textContent.toLowerCase().indexOf(q) >= 0 ? '' : 'none';
    });
  });
})();
</script>
"""
}

// ============================================================
// ===================== HISTORY PAGE ========================
// ============================================================
def historyPage() {
    def hubIp = location?.hub?.localIP ?: ""
    dynamicPage(name: "historyPage", title: "Battery Replacement History", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss()
            if (!state.replacements || state.replacements.size() == 0) {
                paragraph "No battery replacements have been logged yet."
                return
            }
            def rowsHtml = state.replacements.sort { a, b -> replacementTime(b) <=> replacementTime(a) }.take(100).collect { r ->
                def dev = r.deviceId
                    ? autoDevices?.find { it.id == r.deviceId }
                    : autoDevices?.find { it.displayName == r.device }
                def displayName = bmEsc(dev ? dev.displayName : r.device)
                def nameHtml = !dev ? "<span class='bm-muted'>${displayName} <em>(device removed)</em></span>" :
                    hubLink("/device/edit/${dev.id}", displayName)
                def info = dev ? getCatalogBatteryInfo(dev) : null
                def dateDisplay = r.date ?: ""
                try {
                    if (dateDisplay =~ /^\d{4}-\d{2}-\d{2}/) {
                        dateDisplay = new Date().parse("yyyy-MM-dd HH:mm", dateDisplay).format("MM/dd/yyyy", location.timeZone)
                    }
                } catch (e) { }
                "<tr${dev ? '' : " style='opacity:0.6;'"}><td>${nameHtml}${info ? bmPill(bmEsc(info), 'gray') : ''}</td>" +
                    "<td>${r.level}%</td><td>${dateDisplay}</td><td style='text-align:right;'>${historyTypePill(r.type)}</td></tr>"
            }.join("")
            paragraph rawHtml: true, "<div style='overflow-x:auto;'><table class='bm-table'>" +
                "<thead><tr><th>Device</th><th>Level</th><th>Date</th><th style='text-align:right;'>Type</th></tr></thead>" +
                "<tbody>${rowsHtml}</tbody></table></div>" +
                hubLinkScript()
        }
        section {
            href(name: "toDeleteHistory", page: "deleteHistoryPage",
                 title: "<i class='fa-regular fa-trash-can' style='margin-right:6px;'></i>Delete a history entry", description: "")
        }
    }
}

// ============================================================
// ===================== DELETE HISTORY PAGE =================
// ============================================================
def deleteHistoryPage() {
    app.removeSetting("deleteEntrySelection")
    app.updateSetting("confirmEntryDelete", [value: false, type: "bool"])

    dynamicPage(name: "deleteHistoryPage", title: "Delete a History Entry", install: false) {
        if (!state.replacements || state.replacements.size() == 0) {
            section() { paragraph "No replacement history to delete." }
        } else {
            def options = [:]
            state.replacements.sort { a, b -> replacementTime(b) <=> replacementTime(a) }.take(100).eachWithIndex { r, i ->
                options["${i}"] = "🗑️ ${r.device} — ${r.date}"
            }
            section("<b>Select Entry to Delete</b>") {
                input "deleteEntrySelection", "enum",
                      title: "Choose entry",
                      options: options,
                      multiple: false,
                      required: false
            }
            section("<b>Confirm Deletion</b>") {
                input "confirmEntryDelete", "bool",
                      title: "Confirm deletion",
                      defaultValue: false
            }
            section() {
                href(name: "toDeleteHistoryConfirm", page: "deleteHistoryConfirmPage", title: "Submit")
            }
        }
    }
}

// ============================================================
// ============= DELETE HISTORY CONFIRM PAGE =================
// ============================================================
def deleteHistoryConfirmPage() {
    dynamicPage(name: "deleteHistoryConfirmPage", title: "Delete Entry", install: false) {
        section("<b>Result</b>") {
            if (!confirmEntryDelete) {
                paragraph "⚠️ Deletion cancelled — confirm checkbox was not checked."
            } else if (deleteEntrySelection == null) {
                paragraph "⚠️ No entry selected."
            } else {
                def sorted = state.replacements.sort { a, b -> replacementTime(b) <=> replacementTime(a) }.take(100)
                def idx    = deleteEntrySelection.toInteger()
                if (idx >= 0 && idx < sorted.size()) {
                    def entry = sorted[idx]
                    state.replacements = state.replacements.findAll {
                        !(it.device == entry.device && it.date == entry.date)
                    }
                    app.updateSetting("confirmEntryDelete", [value: false, type: "bool"])
                    paragraph "✅ Deleted entry for ${entry.device} on ${entry.date}."
                } else {
                    paragraph "⚠️ Entry not found — it may have already been deleted."
                }
            }
        }
    }
}

// ============================================================
// ===================== SEND NOW ============================
// ============================================================
/** Sends the summary immediately, bypassing snooze. Result shown on the Notifications page. */
private void sendNotificationNow() {
    if (!(autoDevices?.size())) {
        state.sendMsg = [tone: "err", text: "No monitored devices are selected yet."]
        return
    }
    def hasTargets = (settings?.notifyDevices?.size() ?: 0) > 0 || (settings?.pushoverDevices?.size() ?: 0) > 0 ||
                     settings?.enablePush == true
    if (!hasTargets) {
        state.sendMsg = [tone: "err", text: "Add at least one notification device first."]
        return
    }
    def savedSnooze = state.notifSnoozedUntil
    def sent = false
    try {
        state.notifSnoozedUntil = 0
        sent = scheduledSummary() == true
    } catch (e) {
        log.warn "Battery Monitor: send now failed: ${e.message}"
        state.sendMsg = [tone: "err", text: "Sending failed. Check the logs."]
        return
    } finally {
        state.notifSnoozedUntil = savedSnooze
    }
    if (!sent) {
        state.sendMsg = [tone: "warn", text: "Nothing sent. There's nothing to report and <b>Skip when nothing to report</b> is on."]
        return
    }
    def sentTo = []
    if (settings?.notifyDevices)   sentTo.addAll(settings.notifyDevices.collect { bmEsc(it.displayName) })
    if (settings?.pushoverDevices) sentTo.addAll(settings.pushoverDevices.collect { "${bmEsc(it.displayName)} (Pushover)".toString() })
    state.sendMsg = [tone: "ok", text: (sentTo ? "Sent to ${sentTo.join(', ')}." : "Sent via hub push.").toString()]
}

// ============================================================
// ===================== FORCE SCAN PAGE =====================
// ============================================================
def forceScanPage() {
    scanAllDevices()
    if (debugMode) log.debug "Manual battery scan triggered by user"

    dynamicPage(name: "forceScanPage", title: "Force Scan", install: false) {
        section("<b>Scan Complete</b>") {
            def devList = (autoDevices ?: []).findAll { !isIgnored(it) }
            def count   = devList.size()
            paragraph "✅ Battery scan complete — ${count} device(s) read. " +
                      "Return to Battery Summary &amp; Trends to see updated values.<br><br>" +
                      "<b>Note:</b> A new drain sample is only recorded if the battery level " +
                      "has changed since the last reading. Devices reporting the same level " +
                      "will not generate a new sample."
        }
    }
}

// ============================================================
// ===================== UI: STATUS BANNER ===================
// ============================================================

private String bmStatusBannerHtml() {
    def devs = (autoDevices ?: []).findAll { !isIgnored(it) }
    if (!devs) return statusBannerHtml(false, "Setup required", "Choose <b>Monitored devices</b> below, then tap <b>Done</b>")

    int low = 0, dead = 0, stale = 0, drain = 0
    devs.each { d ->
        try {
            if (isBatteryDead(d)) { dead++; return }
            def lvl = d.currentValue("battery")
            if (lvl != null && lvl.toInteger() <= 25) low++
            if (isStale(d)) stale++
            def h = health(d)
            if ((h == "Poor" || h == "Fair") && getDrain(d) > 1.5) drain++
        } catch (e) { }
    }
    def parts = ["${countText(devs.size(), 'device')} monitored"]
    if (low)   parts << "${low} low"
    if (dead)  parts << "${dead} dead"
    if (drain) parts << "${drain} high drain"
    if (stale) parts << "${stale} stale"
    boolean ok = !(low || dead || drain || stale)
    return statusBannerHtml(ok, ok ? "No issues found" : "Attention needed", parts.join(" &middot; "))
}

private String statusBannerHtml(boolean ok, String title, String summary) {
    String tone = ok ? "bg-green-50 border-green-200" : "p-message p-message-warn app-message"
    String icon = ok ? "pi pi-check-circle text-green-700" : "fa-solid fa-exclamation-triangle text-yellow-700"
    """
<style>
  ${tileSecondaryTextCss()}
  ${tipsCardCss()}
  .app-heading { font-size: 16px; }
  .p-message.app-message { padding: 0.75rem !important; margin: 0; border: 0 !important; }
  .app-message .text-color-secondary, .app-message .text-blue-700, .app-message .text-yellow-700 { color: inherit !important; }
  .app-main-support button.hrefElem[name^='_action_href_tips'] { height: 61.5px; padding-bottom: 13.5px; box-sizing: border-box; }
  ${bmCardsCss()}
  @media (max-width: 600px) {
    .bm-banner { flex-wrap: wrap; }
    .bm-banner > a { margin-left: 44px; }
  }
</style>
<div class='bm-banner flex align-items-center justify-content-between gap-3 ${tone} border-1 border-round p-3'>
  <div class='flex align-items-center gap-3 min-w-0'>
    <div class='flex-shrink-0'><i class='${icon} text-2xl' aria-hidden='true'></i></div>
    <div class='min-w-0'>
      <div class='app-heading font-semibold'>${title}</div>
      <div class='text-color-secondary mt-1' style='font-size:14px;'>${summary}</div>
    </div>
  </div>
  <a href='/logs?tab=past&amp;appId=${app.id}' target='_blank' class='text-blue-700 font-semibold white-space-nowrap no-underline'>
    View logs <i class='fa-regular fa-external-link'></i>
  </a>
</div>
"""
}

private String countText(int count, String singular) {
    "${count} ${singular}${count == 1 ? '' : 's'}"
}

// ============================================================
// ===================== UI: HELP & SUPPORT ==================
// ============================================================

/** href must stay named "tips" for tipsCardCss() to style it. */
private void helpAndSupportSection() {
    section(title: "<b>Help & Support</b>", sectionClass: "app-main-support") {
        href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
            page: "tipsPage", description: "Colors, health, detection, and known quirks", width: 4, style: "margin:8px;"
        paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
            "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 4
        paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
            "Buy Me a Coffee", "Support development"), width: 4
    }
}

private void versionFooterSection() {
    section {
        paragraph "<div class='text-center text-color-secondary text-xs mt-2'>Battery Monitor v${APP_VERSION}</div>"
    }
}

private String supportLinkHtml(String url, String iconClass, String title, String subtitle) {
    """
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

// ============================================================
// ===================== UI: TIPS PAGE =======================
// ============================================================

def tipsPage(params = null) {
    def topics = tipsTopics()
    def topic = topics.find { it.id == params?.topic } ?: topics.find { it.id == DEFAULT_TIP_TOPIC } ?: topics[0]
    int i = topics.indexOf(topic)
    def next = i + 1 < topics.size() ? topics[i + 1] : null
    dynamicPage(name: "tipsPage", title: "Tips & Troubleshooting", install: false) {
        section(sectionClass: "app-tips-index") {
            paragraph rawHtml: true, tipsStylesHtml()
            topics.groupBy { it.group }.each { group, entries ->
                paragraph rawHtml: true, "<div class='app-heading font-semibold mt-2'>${group}</div>"
                entries.each { entry ->
                    String selected = entry.id == topic.id ? "app-topic-current" : ""
                    href name: "tip_${entry.id}",
                        title: "<span class='${selected}'><i class='pi ${entry.icon} mr-3' aria-hidden='true'></i>${entry.label}</span>",
                        description: "", page: "tipsPage", params: [topic: entry.id], width: 12, style: "margin:0 8px;"
                }
            }
        }
        section(sectionClass: "app-tips-article") {
            paragraph rawHtml: true, tipsArticleHtml(topic)
            if (topic.append) {
                def extra = topics.find { it.id == topic.append }
                if (extra) paragraph rawHtml: true, tipsArticleHtml(extra)
            }
            if (next) {
                href name: "nextTip", title: "<span class='text-blue-700'>${next.label}</span>",
                    description: "Next topic", page: "tipsPage", params: [topic: next.id], width: 12, style: "margin:8px;"
            }
        }
    }
}

private List tipsTopics() {
    [
        // ---------- Getting started ----------
        [id: "best", label: "Tips for best results", title: "Tips for best results", group: "Getting started", icon: "pi-star",
            body: "<p>A few habits that make ratings more accurate and notifications more useful.</p>",
            checklist: [
                [title: "Give new batteries a week", detail: "Health ratings need time to learn before they can be trusted."],
                [title: "Assign battery types", detail: "Used in notifications, the portal, and history, and they set Li-ion cliff detection defaults."],
                [title: "Log replacements that aren't auto-detected", detail: "Use Device Actions for integrated batteries or unreliable reporters."],
                [title: "Ignore spare or stored devices", detail: "Keeps them out of reports without removing them from Hubitat."],
                [title: "Reset drain history after a bad start", detail: "Useful if a device shows Heavy Drain right after first install."],
                [title: "Snooze notifications when traveling", detail: "Scanning continues; only notifications pause."]
            ]],
        [id: "pending", label: "How health is learned", title: "Pending health and samples", group: "Getting started", icon: "pi-hourglass",
            body: "<p>Health shows <b>Pending</b> until enough data is collected. Progress shows inline, for example " +
                "<b>3/5 samples &middot; 3/5 days</b>.</p>" +
                "<p>Requires <b>5 samples</b> and <b>5 days</b> minimum (<b>7 samples</b> for locks, smoke, and CO detectors). " +
                "Devices that report infrequently clear Pending automatically after <b>14 days</b> with 2 or more samples.</p>" +
                "<p><b>Confidence weighting:</b> early readings carry less weight, and the full measured drain is used from 5 samples on.</p>"],

        // ---------- Reading the report ----------
        [id: "levels", label: "Battery levels", title: "Battery level ranges", group: "Reading the report", icon: "pi-bolt",
            body: "<p>Level colors reflect current charge. Health uses the same colors but is based on drain rate, not percentage, " +
                "so a device can show a Good level and Poor health if it's draining unusually fast.</p>" +
                "<table><tr><td><b>Excellent</b></td><td>100%</td><td>Fully charged</td></tr>" +
                "<tr><td><b>Good</b></td><td>71&ndash;99%</td><td>Healthy, no action needed</td></tr>" +
                "<tr><td><b>Fair</b></td><td>26&ndash;70%</td><td>Getting low, keep an eye on it</td></tr>" +
                "<tr><td><b>Poor</b></td><td>0&ndash;25%</td><td>Replace soon</td></tr>" +
                "<tr><td><b>Dead</b></td><td>0% (confirmed)</td><td>Confirmed dead after 3 consecutive readings, replace now</td></tr></table>"],
        [id: "health", label: "Health and trend", title: "Battery health and trend", group: "Reading the report", icon: "pi-heart",
            body: "<p><b>Health</b> is a long-term, confidence-weighted drain average and changes slowly by design. " +
                "<b>Trend</b> reacts faster and shows what the battery is doing right now.</p>" +
                "<p>When they agree, only Health is shown. When Trend is <i>worse</i> than Health, a warning appears next to it. " +
                "That's the most actionable signal, meaning something recently changed.</p>" +
                "<table><tr><td><b>Pending</b></td><td>&mdash;</td><td>Still learning</td></tr>" +
                "<tr><td><b>Excellent</b></td><td>under 0.3%/day</td><td>Minimal drain</td></tr>" +
                "<tr><td><b>Good</b></td><td>0.3&ndash;0.8%/day</td><td>Normal usage</td></tr>" +
                "<tr><td><b>Fair</b></td><td>0.8&ndash;1.5%/day</td><td>Above average, worth watching</td></tr>" +
                "<tr><td><b>Poor</b></td><td>over 1.5%/day</td><td>High drain, notification fires</td></tr></table>" +
                "<p><b>Example:</b> <i>Good, Heavy Drain</i> means a solid history but unusually fast drain right now.</p>" +
                "<p>Locks use higher drain thresholds, so a Moderate lock warning isn't necessarily a concern. " +
                "Smoke and CO detectors often show <b>&lt;0.01%/day</b>, meaning very slow drain. That's normal.</p>" +
                "<p><b>Li-ion note:</b> health and trend are long-term averages and aren't built to catch a sudden end-of-life crash. See <b>Li-ion cliff alerts</b>.</p>"],
        [id: "drain", label: "Drain and estimates", title: "Drain, estimated life, and last seen", group: "Reading the report", icon: "pi-chart-line",
            body: "<p><b>Drain</b> is %/day based on the last 10 readings. <b>Est Days</b> is current level divided by drain, capped at 365.</p>" +
                "<p><b>Last seen</b> is the device's last activity of any kind. Past the stale threshold (default 24 hours, set under Scan interval) it's marked <b>Stale</b>.</p>" +
                "<p><b>Force Scan</b> reads all levels immediately. A new drain sample is only recorded when the level has changed.</p>"],

        // ---------- Replacements ----------
        [id: "detection", label: "Replacement detection", title: "Automatic replacement detection", group: "Replacements", icon: "pi-sync",
            body: "<p>Batteries only drain on their own, so a significant upward jump means a new battery was installed. " +
                "Battery Monitor watches for these jumps and logs the replacement automatically.</p>",
            checklist: [
                [title: "Jump meets the minimum", detail: "Default 30%, set under Device management, Auto-detection."],
                [title: "Confirmed by a second reading", detail: "Must hold within 48 hours, so a single spike isn't logged."],
                [title: "Enough history", detail: "Device needs 3+ drain samples and must be at least 3 days old, unless the old battery was near empty (5% or below)."],
                [title: "Cooldown", detail: "12 hours between detections prevents duplicates."]
            ],
            warning: "<b>Setting the minimum below 15% can cause false positives.</b><br>25&ndash;30% works well for most setups."],
        [id: "manage", label: "Device management", title: "Device battery management", group: "Replacements", icon: "pi-cog",
            body: "<p>Assign battery types, log replacements, reset drain history, and view per-device history from " +
                "<b>Device Battery Management</b>.</p>" +
                "<p><b>Bulk Actions</b> apply to several devices at once, with a 60-second cooldown to prevent accidental repeats.</p>" +
                "<p><b>Ignored devices</b> are excluded from reports, notifications, stale checks, health scoring, and the portal. " +
                "Restoring one resets its history, logs a <b>Restored</b> entry, and shows Recently Replaced for up to 24 hours.</p>"],

        // ---------- Notifications ----------
        [id: "snooze", label: "Notification snooze", title: "Notification snooze", group: "Notifications", icon: "pi-bell-slash",
            body: "<p>Silences all Battery Monitor notifications for a set number of days. Scanning continues; only notifications pause. " +
                "Find it at the bottom of the Notifications page. It expires automatically, and <b>Send now</b> bypasses it.</p>" +
                "<p>Li-ion cliff alerts bypass snooze by default. Change this under Device management, Auto-detection.</p>"],
        [id: "cliff", label: "Li-ion cliff alerts", title: "Li-ion cliff-drop alerts", group: "Notifications", icon: "pi-bolt",
            body: "<p>Li-ion rechargeable cells hold a flat charge for most of their life, then fail suddenly, sometimes dropping from a healthy level to near-dead within hours. " +
                "A Li-ion cell can look Excellent right up until the cliff. That's the chemistry, not a bug.</p>" +
                "<p>Cliff detection compares two <i>consecutive</i> readings, regardless of how far apart they were taken. If the drop meets the threshold (default 40%), " +
                "an urgent alert fires right away, separate from the scheduled summary.</p>",
            checklist: [
                [title: "LIR types: on automatically", detail: "LIR2016, LIR2032, LIR2430, and LIR2450 are always Li-ion."],
                [title: "18650, RCR123A, RCR2: off until confirmed", detail: "These are size labels; LiFePO4 cells come in the same sizes."],
                [title: "Any other type: available", detail: "Turn it on for a custom Li-ion cell under Other."],
                [title: "Set per device", detail: "On the Battery Types or Device Actions page. Changing a device's type resets its switch to that type's default."]
            ],
            warning: "<b>Assign the correct battery type first.</b><br>Cliff detection defaults come entirely from that assignment."],
        [id: "thresholds", label: "Level thresholds", title: "Why Poor and Fair thresholds are fixed", group: "Notifications", icon: "pi-sliders-h",
            body: "<p>The Poor (25% and below) and Fair (26&ndash;70%) cutoffs aren't adjustable. They drive the color coding, " +
                "report grouping, notifications, and portal counts across the whole app, so changing them in one place would make those disagree.</p>" +
                "<p>If a device consistently reports low or drops faster than expected, the cause is usually the device, its driver, " +
                "or the battery chemistry rather than the threshold. See <b>Readings look wrong</b>.</p>"],

        // ---------- Web portal ----------
        [id: "portal", label: "Web portal", title: "Battery web portal", group: "Web portal", icon: "pi-globe",
            body: "<p>Shows every device sorted by battery level, with health, drain, estimated days, last activity, and battery type. " +
                "Auto-refreshes every 2 minutes.</p>" +
                "<p>Once OAuth is enabled, Cloud and Local URLs appear on the <b>Web portal</b> settings page. Add a Link tile to a Hubitat dashboard " +
                "and paste in either URL for one-tap access.</p>",
            checklist: [
                [title: "Open Apps Code", detail: "Find Battery Monitor 2.0 in the list."],
                [title: "Enable OAuth", detail: "OAuth (top right), then Enable OAuth in App, then Update."],
                [title: "Return and tap Done", detail: "The links appear under Settings, Web portal."]
            ]],

        // ---------- Troubleshooting ----------
        [id: "readings", label: "Readings look wrong", title: "When battery readings look wrong", group: "Troubleshooting", icon: "pi-exclamation-triangle",
            body: "<p>Battery Monitor reports what each device sends. Before assuming an app issue, check the source.</p>",
            checklist: [
                [title: "Driver", detail: "Some drivers report battery infrequently, round heavily, or estimate from voltage."],
                [title: "Battery chemistry", detail: "Rechargeable cells run at a different voltage than alkaline or lithium, so some devices misread them."],
                [title: "Device reporting", detail: "Check the device's own page for how often it sends battery events."]
            ],
            warning: "<b>Rechargeable lithium cells can hold near-full, then drop quickly.</b><br>A sudden fall after weeks of stable readings is often the chemistry, not the app. Turn on cliff detection for those devices."],
        [id: "logging", label: "Scan logging", title: "Scan messages in the logs", group: "Troubleshooting", icon: "pi-file",
            body: "<p>Each scheduled scan logs a <b>scan started</b> and <b>scan complete</b> line at info level. This is intentional, " +
                "so you can confirm scans are running without turning on debug logging.</p>" +
                "<p><b>Debug logging</b> (the toggle at the bottom of the main page) adds step-by-step detail and turns itself off after 30 minutes.</p>"]
    ]
}

private String tipsArticleHtml(Map topic) {
    String extra = ""
    if (topic.checklist) {
        extra += "<div class='border-1 border-gray-200 border-round p-3 mb-3'>" + topic.checklist.collect { item ->
            "<div class='flex align-items-center gap-3 py-2'><i class='pi pi-check-circle text-blue-700 text-xl' aria-hidden='true'></i>" +
            "<div><div class='font-semibold'>${item.title}</div><div class='text-sm text-color-secondary'>${item.detail}</div></div></div>"
        }.join("") + "</div>"
    }
    if (topic.warning) extra += warningMessageHtml(topic.warning)
    """
<article class='app-tip-card'>
  <div class='app-tip-card-header flex align-items-center gap-3'>
    <i class='pi ${topic.icon} text-blue-700 text-xl' aria-hidden='true'></i>
    <h4 class='app-heading font-semibold m-0'>${topic.title}</h4>
  </div>
  <div class='app-tip-copy'>${topic.body}${extra}</div>
</article>
"""
}

private String tipsStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  .app-tips-index { float: left; width: calc(27% - 8px); box-sizing: border-box; }
  .app-tips-article { float: right; width: 73%; border-left: 1px solid #e0e0e0; padding-left: 8px; box-sizing: border-box; }
  .app-tips-index > .mdl-grid, .app-tips-article > .mdl-grid { padding: 4px 0 !important; }
  .app-tips-index .mdl-cell:has(> style) { display: none; }
  .app-tips-index button.hrefElem {
    background: transparent; box-shadow: none; border: 0; border-left: 3px solid transparent;
    border-radius: 0; padding: 9px 10px; font-family: inherit; font-size: 16px; color: #1565c0;
  }
  .app-tips-index button.hrefElem::before { display: none; }
  .app-tips-index button.hrefElem:has(.app-topic-current) { background: #eaf2fc; border-left-color: #1565c0; font-weight: 600; }
  .app-tips-index button.hrefElem:hover { background: #f3f6fa; }
  .app-tips-index button.hrefElem:focus-visible { outline: 2px solid #1565c0; outline-offset: 2px; }
  .app-tip-card { border: 1px solid #dfe3e8; border-radius: 4px; overflow: hidden; margin-bottom: 12px; }
  .app-tip-card-header { padding: 16px; background: #f5f7fa; border-bottom: 1px solid #e4e7ec; }
  .app-tip-copy { padding: 16px; line-height: 1.55; overflow-wrap: anywhere; }
  .app-tip-copy p { margin: 0 0 16px; }
  .app-tip-copy p:last-child { margin-bottom: 0; }
  .app-tip-copy table { width: 100%; border-collapse: collapse; margin: 0 0 16px; }
  .app-tip-copy td { padding: 6px 8px; border-bottom: 1px solid #e4e7ec; }
  .app-tips-article button.hrefElem { background: #fff; box-shadow: none; border: 1px solid #dfe3e8; border-radius: 4px; font-family: inherit; }
  .app-tips-article button.hrefElem::before { color: #1565c0; }
  #formApp:has(.app-tips-index) #fieldsetAppButtons { clear: both; }
  @media (max-width: 1000px) {
    .app-tips-index, .app-tips-article { float: none; width: 100%; padding: 0; border-left: 0; }
    .app-tips-index > .mdl-grid { max-height: 260px; overflow-y: auto; border-bottom: 1px solid #e0e0e0; }
  }
</style>
"""
}

// ============================================================
// ===================== UI: SHARED STYLES ===================
// ============================================================

private String warningMessageHtml(String content, String extraClasses = "") {
    "<div class='p-message p-message-warn app-message flex align-items-center gap-2 ${extraClasses}'>" +
        "<div class='flex-shrink-0'><i class='fa-solid fa-exclamation-triangle text-xl' aria-hidden='true'></i></div>" +
        "<div class='min-w-0 flex-1'>${content}</div></div>"
}

private String appPageSpacingCss() {
    """
  ${tileSecondaryTextCss()}
  .app-heading { font-size: 16px; }
  .p-message.app-message { padding: 0.75rem !important; margin: 0; border: 0 !important; }
  .app-message .text-color-secondary, .app-message .text-blue-700, .app-message .text-yellow-700 { color: inherit !important; }
  div.panel-body { padding: 0 !important; margin-left: -0.5em; margin-right: -0.5em; }
  fieldset#fieldsetAppButtons { margin-left: 0.5em; margin-right: 0.5em; }
"""
}

private String tileSecondaryTextCss() {
    "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

private String tipsCardCss() {
    """
  button.hrefElem[name^='_action_href_tips'] {
    position: relative; background: #fff; border: 1px solid #e0e0e0; border-radius: 4px; box-shadow: none;
    color: #333; height: 56px; font-family: inherit; font-size: 16px; font-weight: 500; line-height: 1.4;
    padding: 8px 28px 8px 46px;
  }
  button.hrefElem[name^='_action_href_tips'] > span:first-child { color: #1565c0; font-weight: 600; font-size: 16px !important; }
  button.hrefElem[name^='_action_href_tips'] > span.state-incomplete-text { color: #777; font-size: 14px; font-weight: 500; line-height: 1.4; }
  button.hrefElem[name^='_action_href_tips'] i.pi {
    position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: #1565c0; font-size: 20px;
  }
  button.hrefElem[name^='_action_href_tips']::before { color: #1565c0; }
"""
}
