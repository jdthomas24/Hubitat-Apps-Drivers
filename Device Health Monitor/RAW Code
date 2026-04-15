definition(
    name: "Device Health Monitor",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Zigbee, Z-Wave, Matter, LAN and Hub Mesh device health monitoring with check-in tracking, baseline learning, trend analysis and notifications.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    iconUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    iconX2Url: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    version: "1.1.0-beta",
    doNotFocus: true
)

preferences {
    page(name: "mainPage")
    page(name: "activitySummaryPage")
    page(name: "problemDevicesPage")
    page(name: "sendNotificationPage")
    page(name: "forceScanPage")
    page(name: "resetHistoryPage")
    page(name: "resetHistoryConfirmPage")
    page(name: "infoPage")
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    if (debugMode) log.debug "Device Health Monitor installed"
    applyCustomLabel()
    initialize()
}

def updated() {
    if (debugMode) log.debug "Device Health Monitor updated"
    applyCustomLabel()
    unschedule()
    unsubscribe()
    initialize()
    if (debugMode) runIn(1800, disableDebugLogging)

    def allDevices = getAllMonitoredDevices()
    def currentIds = allDevices.collect { it.id as String }
    state.history?.keySet()?.findAll { !currentIds.contains(it) }?.each { removedId ->
        state.history.remove(removedId)
        state.health?.remove(removedId)
        if (debugMode) log.debug "Cleaned up removed device: ${removedId}"
    }
}

def initialize() {
    if (debugMode) log.debug "Device Health Monitor initializing"
    if (state.history  == null) state.history  = [:]
    if (state.health   == null) state.health   = [:]
    if (state.snoozed  == null) state.snoozed  = [:]
    scheduleScanInterval()
    scheduleReportFrequency()
    if (debugMode) log.debug "Monitoring ${getAllMonitoredDevices().findAll { getProtocol(it) != 'Unknown' }.size()} device(s)"
    runIn(5, scanAllDevices)
}

def disableDebugLogging() {
    log.info "Device Health Monitor: auto-disabling debug logging after 30 minutes"
    app.updateSetting("debugMode", [value: false, type: "bool"])
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
// ===================== SNOOZE ==============================
// ============================================================
def snoozeDevice(deviceId) {
    def hours     = (settings?.snoozeDurationHours ?: 24).toInteger()
    def until     = now() + (hours * 3600000)
    if (!state.snoozed) state.snoozed = [:]
    state.snoozed[deviceId] = until
    if (debugMode) log.debug "Snoozed device ${deviceId} for ${hours}h until ${new Date(until)}"
}

def unsnoozeDevice(deviceId) {
    state.snoozed?.remove(deviceId)
    if (debugMode) log.debug "Unsnoozed device ${deviceId}"
}

def isDeviceSnoozed(deviceId) {
    def until = state.snoozed?.get(deviceId)
    if (!until) return false
    if (until >= now()) return true
    state.snoozed.remove(deviceId)
    return false
}

// ============================================================
// ===================== APP BUTTON HANDLER ==================
// ============================================================
def appButtonHandler(btn) {
    if (btn.startsWith("snooze_")) {
        def deviceId = btn.replace("snooze_", "")
        snoozeDevice(deviceId)
    } else if (btn.startsWith("unsnooze_")) {
        def deviceId = btn.replace("unsnooze_", "")
        unsnoozeDevice(deviceId)
    }
}

// ============================================================
// ===================== HELPERS =============================
// ============================================================
def getAllMonitoredDevices() {
    return monitoredDevices ?: []
}

def getProtocol(device) {
    try {
        def devData = device.properties
        if (devData?.controllerType == "ZGB") return "Zigbee"
        if (devData?.controllerType == "ZWV") return "Z-Wave"
        if (devData?.controllerType == "MAT") return "Matter"
        if (devData?.controllerType == "LNK") return "Hub Mesh"
        // Bluetooth (C-8 Pro only) — controllerType value unconfirmed, placeholder for future
        // if (devData?.controllerType == "BLE") return "Bluetooth"

        // Zigbee fallbacks
        if (device.getDataValue("Endpoint Id")                != null) return "Zigbee"
        if (device.getDataValue("endpointId")                 != null) return "Zigbee"
        if (device.getDataValue("zigbeeNodeType")             != null) return "Zigbee"
        if (device.getDataValue("zigbeeId")                   != null) return "Zigbee"
        // Z-Wave fallbacks
        if (device.getDataValue("In Clusters")                != null) return "Z-Wave"
        if (device.getDataValue("inClusters")                 != null) return "Z-Wave"
        if (device.getDataValue("zwaveSecurePairingComplete") != null) return "Z-Wave"
        if (device.getDataValue("secureInClusters")           != null) return "Z-Wave"
        if (device.getDataValue("Zw Node Info")               != null) return "Z-Wave"

        return "LAN"
    } catch (e) {
        if (debugMode) log.debug "getProtocol error for ${device.displayName}: ${e.message}"
    }
    return "Unknown"
}

def getProtocolColor(protocol) {
    switch (protocol) {
        case "Zigbee":    return "#3b82f6"
        case "Z-Wave":    return "#8b5cf6"
        case "Matter":    return "#f97316"
        case "Hub Mesh":  return "#06b6d4"
        case "LAN":       return "#14b8a6"
        case "Bluetooth": return "#06b6d4"
        default:          return "#94a3b8"
    }
}

def isModeOK() {
    if (!settings?.enableModeRestriction) return true
    if (!settings?.restrictedModes) return true
    return settings.restrictedModes.contains(location.mode)
}

// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
def mainPage() {
    applyCustomLabel()
    dynamicPage(name: "mainPage", title: "Device Health Monitor", install: true, uninstall: true) {

        def hasCustomName = settings?.customAppName?.trim()
        section("<b>App Display Name (optional)</b>", hideable: true, hidden: hasCustomName) {
            paragraph "Enter a name to rename this app in your Hubitat app list."
            input "customAppName", "text",
                  title: "Custom App Name",
                  description: "Rename how this app appears in your Hubitat app list",
                  required: false
        }
        if (hasCustomName) {
            section("") {
                paragraph "Current name: <b><span style='color:blue;'>${settings.customAppName}</span></b> — tap <b>App Display Name (optional)</b> above to change."
            }
        }

        // ── Device Selection ─────────────────────────────────
        def devicesSelected = (monitoredDevices?.size() ?: 0) > 0
        section("<b>Monitored Devices</b>", hideable: true, hidden: devicesSelected) {
            paragraph "<b>Select the devices you want to monitor.</b> Protocol (Zigbee, Z-Wave, Matter, Hub Mesh, LAN) is detected automatically."
            paragraph "<span style='color:red; font-weight:bold;'>IMPORTANT: After selecting devices, you MUST click 'Done' before viewing reports.</span>"
            input "monitoredDevices", "capability.*",
                  title: "Select devices to monitor",
                  multiple: true,
                  required: false,
                  submitOnChange: true
        }
        if (devicesSelected) {
            def allSelected  = monitoredDevices
            def zigbeeCount  = allSelected.count { getProtocol(it) == "Zigbee" }
            def zwaveCount   = allSelected.count { getProtocol(it) == "Z-Wave" }
            def matterCount  = allSelected.count { getProtocol(it) == "Matter" }
            def hubMeshCount = allSelected.count { getProtocol(it) == "Hub Mesh" }
            def lanCount     = allSelected.count { getProtocol(it) == "LAN" }
            def unknownCount = allSelected.count { getProtocol(it) == "Unknown" }
            def totalCount   = allSelected.size()
            section("") {
                paragraph "<b><span style='color:blue;'>${totalCount} device(s) selected</span></b> — " +
                          "Zigbee: <b><span style='color:#3b82f6;'>${zigbeeCount}</span></b> | " +
                          "Z-Wave: <b><span style='color:#8b5cf6;'>${zwaveCount}</span></b> | " +
                          "Matter: <b><span style='color:#f97316;'>${matterCount}</span></b> | " +
                          "Hub Mesh: <b><span style='color:#06b6d4;'>${hubMeshCount}</span></b> | " +
                          "LAN: <b><span style='color:#14b8a6;'>${lanCount}</span></b>" +
                          (unknownCount > 0 ? " | <span style='color:orange;'>Unknown: <b><span style='color:orange;'>${unknownCount}</span></b> (these will be skipped)</span>" : "") +
                          " — Tap <b>Monitored Devices</b> above to change."
            }
        }

        if (!devicesSelected) {
            section("") {
                paragraph "<span style='color:red; font-weight:bold;'>⚠ No devices selected. Select devices above to begin monitoring.</span>"
            }
        }

        // ── Scan Interval ────────────────────────────────────
        def scanIntervalLabel = ["1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"]
        def currentScan = scanIntervalLabel[settings?.scanInterval ?: "3"] ?: "Every 3 Hours"
        section("<b>Device Scan Interval</b>", hideable: true, hidden: true) {
            paragraph "How often device activity is checked and health ratings are updated."
            input "scanInterval", "enum",
                  title: "Scan Frequency:",
                  options: ["1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3",
                  submitOnChange: true
        }
        section("") {
            paragraph "Scan interval: <b><span style='color:blue;'>${currentScan}</span></b> — tap <b>Device Scan Interval</b> above to change."
        }

        // ── Offline Threshold ────────────────────────────────
        def currentThreshold = settings?.offlineThresholdHours ?: 24
        section("<b>Offline Threshold</b>", hideable: true, hidden: true) {
            paragraph "Devices with no activity beyond this threshold will be marked Offline regardless of their baseline ratio."
            input "offlineThresholdHours", "number",
                  title: "Mark device Offline if no activity for X hours:",
                  defaultValue: 24,
                  required: true,
                  submitOnChange: true
        }
        section("") {
            paragraph "Offline threshold: <b><span style='color:blue;'>${currentThreshold}h</span></b> — tap <b>Offline Threshold</b> above to change."
        }

        // ── Snooze Duration ──────────────────────────────────
        def currentSnooze = settings?.snoozeDurationHours ?: 24
        section("<b>Snooze Duration</b>", hideable: true, hidden: true) {
            paragraph "When a device is snoozed from the Activity Summary it will be excluded from notifications for this many hours."
            input "snoozeDurationHours", "number",
                  title: "Snooze duration (hours):",
                  defaultValue: 24,
                  required: true,
                  submitOnChange: true
        }
        section("") {
            paragraph "Snooze duration: <b><span style='color:blue;'>${currentSnooze}h</span></b> — tap <b>Snooze Duration</b> above to change."
        }

        // ── Mode Restriction ─────────────────────────────────
        section("<b>Mode Restriction</b>", hideable: true, hidden: true) {
            paragraph "Optionally restrict notifications to specific hub modes. Scanning always runs — only notifications are affected."
            input "enableModeRestriction", "bool",
                  title: "Enable mode restriction for notifications",
                  defaultValue: false,
                  submitOnChange: true
            if (settings?.enableModeRestriction) {
                input "restrictedModes", "mode",
                      title: "Only send notifications when hub is in one of these modes:",
                      multiple: true,
                      required: false
            }
        }

        // ── Notifications ────────────────────────────────────
        def notificationSettings = (notificationSettings != false)
        section("<b>Notifications</b>", hideable: true, hidden: notificationSettings) {
            paragraph "ℹ️ Enable the toggle below to configure notification settings."
            input "enablePush", "bool", title: "Enable notifications", defaultValue: true, submitOnChange: true

            if (settings?.enablePush != false) {
                input "reportFrequency", "enum",
                      title: "Notification Frequency:",
                      options: [
                          "daily":  "Daily",
                          "every2": "Every 2 Days",
                          "every3": "Every 3 Days",
                          "weekly": "Weekly"
                      ],
                      defaultValue: "daily"

                input "summaryTime", "time",
                      title: "Notification Time:",
                      required: false

                input "notifyDevices", "capability.notification",
                      title: "Notification devices",
                      multiple: true,
                      required: false

                input "enablePushover", "bool", title: "⚙️ Enable Pushover Markup", defaultValue: false
                input "pushoverDevices", "capability.notification",
                      title: "Pushover notification devices <b>(receives Pushover-formatted message)</b>",
                      multiple: true, required: false
                input "pushoverPrefix", "text",
                      title: "Pushover tags <b>(Only used if Enable Pushover Markup is toggled ON)</b>",
                      description: "e.g. [H][TITLE=Device Health Report][HTML][SELFDESTRUCT=43200]",
                      required: false

                paragraph "<b>Report Sections:</b>"
                input "notifyOffline",   "bool", title: "💀 Include Offline devices",         defaultValue: true
                input "notifyPoor",      "bool", title: "🔴 Include Poor health devices",      defaultValue: true
                input "notifyFair",      "bool", title: "🟠 Include Fair health devices",      defaultValue: true
                input "notifyGood",      "bool", title: "🟢 Include Good health devices",      defaultValue: false
                input "notifyExcellent", "bool", title: "🟢 Include Excellent health devices", defaultValue: false
                input "suppressEmptyReport", "bool",
                      title: "🔕 Don't send notification if nothing to report",
                      defaultValue: false

                paragraph "<b>Send notification now:</b>"
                href "sendNotificationPage", title: "📤 Send Notification Now",
                     description: "Tap to preview and send a device health notification"
            }
        }
        if (notificationSettings) {
            section("") {
                paragraph "Notifications are <b><span style='color:blue;'>" + (settings?.enablePush != false ? "ON" : "OFF") + "</span></b> — tap <b>Notifications</b> above to expand and configure."
            }
        }

        // ── Reports ──────────────────────────────────────────
        section("<b>Reports:</b>") {
            href "activitySummaryPage", title: "Device Activity Summary"
            href "problemDevicesPage",  title: "⚠️ Problem Devices"
        }

        // ── Help ─────────────────────────────────────────────
        section("<b>Help & Info:</b>") {
            href "infoPage", title: "App Guide & Reference",
                 description: "Health scoring, check-in baselines, snooze, and troubleshooting explained"
        }

        // ── Diagnostics ──────────────────────────────────────
        section("<b>Diagnostics</b>") {
            input "debugMode", "bool",
                  title: "Debug Logging (auto-disables after 30 min)",
                  defaultValue: false,
                  submitOnChange: true
        }

        // ── Support ──────────────────────────────────────────
        section("<b>Support & Community</b>") {
            href url: "https://community.hubitat.com",
                 style: "external",
                 title: "💬 Hubitat Community Thread",
                 description: "Questions, feedback, and release notes"
            href url: "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US",
                 style: "external",
                 title: "☕ Buy Me a Coffee",
                 description: "Enjoying the app? Any amount is appreciated — thank you!"
        }
    }
}

// ============================================================
// ===================== REPORT SCHEDULING ==================
// ============================================================
def scheduleReportFrequency() {
    unschedule("reportScheduler")
    if (!summaryTime) return
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
    if (debugMode) log.debug "Device scan scheduled every ${interval}h (cron: ${cronExpr})"
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

// ============================================================
// ===================== SCAN ================================
// ============================================================
def scanAllDevices() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return
    if (debugMode) log.debug "Running scheduled device scan for ${devList.size()} device(s)"
    devList.each { device ->
        try {
            def id   = device.id
            def data = state.history[id]

            def lastActivity = device.getLastActivity()
            def lastSeen     = lastActivity ? safeTime(lastActivity) : now()

            if (!data) {
                state.history[id] = [
                    lastSeen:       lastSeen,
                    lastCheckin:    lastSeen,
                    samples:        [],
                    avgInterval:    null,
                    userInterval:   null,
                    missedCheckins: 0,
                    protocol:       getProtocol(device)
                ]
                state.health[id] = "Pending"
                if (debugMode) log.debug "Seeded ${device.displayName} from lastActivity: ${formatTimeAgo(lastSeen)}"
            } else {
                def prevLastSeen = data.lastSeen ?: lastSeen
                if (lastSeen > prevLastSeen) {
                    def elapsed = (lastSeen - prevLastSeen) / (1000 * 60)
                    if (elapsed >= 10) {
                        def alpha      = 0.3
                        def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : elapsed
                        def smoothed   = alpha * elapsed + (1 - alpha) * prevSmooth
                        data.samples << smoothed
                        if (data.samples.size() > 20) data.samples.remove(0)
                        if (data.samples.size() >= 3) {
                            data.avgInterval = data.samples.sum() / data.samples.size()
                        }
                        if (debugMode) log.debug "${device.displayName}: new activity interval=${elapsed.toInteger()}min smoothed=${smoothed.toInteger()}min avg=${data.avgInterval?.toInteger()}min"
                    }
                    data.lastSeen    = lastSeen
                    data.lastCheckin = lastSeen
                } else {
                    if (debugMode) log.debug "${device.displayName}: no new activity since last scan"
                }
                data.protocol = getProtocol(device)
                updateHealth(device)
            }
        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }
}

// ============================================================
// ===================== HEALTH SCORING ======================
// ============================================================
def updateHealth(device) {
    def id   = device.id
    def data = state.history[id]
    if (!data) return

    def samples = data.samples?.size() ?: 0
    if (samples < 3) {
        state.health[id] = "Pending"
        return
    }

    def offlineThreshold     = ((settings?.offlineThresholdHours ?: 24) * 60).toDouble()
    def minutesSinceLastSeen = (now() - (data.lastSeen ?: now())) / (1000 * 60)

    if (minutesSinceLastSeen >= offlineThreshold) {
        state.health[id] = "Offline"
        return
    }

    def baseline = (data.userInterval ?: data.avgInterval ?: 60).toDouble()
    def ratio    = minutesSinceLastSeen / baseline

    if      (ratio <= 1.2) state.health[id] = "Excellent"
    else if (ratio <= 2.0) state.health[id] = "Good"
    else if (ratio <= 3.0) state.health[id] = "Fair"
    else if (ratio <= 5.0) state.health[id] = "Poor"
    else                   state.health[id] = "Offline"

    if (debugMode) log.debug "${device.displayName}: health=${state.health[id]} ratio=${ratio.round(2)} baseline=${baseline.toInteger()}min lastSeen=${minutesSinceLastSeen.toInteger()}min ago"
}

// ============================================================
// ===================== HEALTH DISPLAY ======================
// ============================================================
def getHealthDisplay(device) {
    def h       = state.health?.get(device.id) ?: "Pending"
    def samples = state.history?.get(device.id)?.samples?.size() ?: 0
    def snoozed = isDeviceSnoozed(device.id as String)

    if (snoozed) {
        def until = state.snoozed?.get(device.id as String)
        def hoursLeft = until ? Math.ceil((until - now()) / 3600000).toInteger() : 0
        return "😴 <span style='color:#94a3b8;'>Snoozed (${hoursLeft}h remaining)</span>"
    }

    if (h == "Pending") {
        return "<span style='color:#94a3b8;'>⏳ Pending (${samples}/3 samples)</span>"
    }

    switch (h) {
        case "Excellent": return "🟢 Excellent"
        case "Good":      return "🟢 Good"
        case "Fair":      return "🟠 Fair"
        case "Poor":      return "🔴 Poor"
        case "Offline":   return "💀 <span style='color:#991b1b;font-weight:bold;'>Offline</span>"
        default:          return "${h}"
    }
}

def getHealthEmoji(h) {
    switch (h) {
        case "Excellent": return "🟢"
        case "Good":      return "🟢"
        case "Fair":      return "🟠"
        case "Poor":      return "🔴"
        case "Offline":   return "💀"
        default:          return "⏳"
    }
}

// ============================================================
// ===================== SAFE HELPERS ========================
// ============================================================
def safeTime(ts) { return (ts instanceof Number) ? ts : ts?.time }

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

def formatInterval(minutes) {
    if (!minutes) return "—"
    def m = minutes.toInteger()
    if (m < 60)   return "${m}m"
    if (m < 1440) return "${(m / 60).toInteger()}h ${m % 60}m"
    return "${(m / 1440).toInteger()}d ${((m % 1440) / 60).toInteger()}h"
}

// ============================================================
// ===================== ACTIVITY SUMMARY PAGE ===============
// ============================================================
def activitySummaryPage() {
    dynamicPage(name: "activitySummaryPage", title: "Device Activity Summary", install: false) {
        section("") {
            href "forceScanPage", title: "🔄 Force Scan Now",
                 description: "Tap to immediately check all monitored devices"

            def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
            if (!devList) { paragraph "No devices found. Please select devices on the main page first."; return }

            devList = devList.sort { a, b ->
                def healthPriority = ["Offline": 1, "Poor": 2, "Fair": 3, "Good": 4, "Excellent": 5, "Pending": 6]
                def hA = state.health?.get(a.id) ?: "Pending"
                def hB = state.health?.get(b.id) ?: "Pending"
                def pA = healthPriority[hA] ?: 6
                def pB = healthPriority[hB] ?: 6
                if (pA != pB) return pA <=> pB
                return a.displayName.trim() <=> b.displayName.trim()
            }

            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Protocol</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Health</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Last Seen</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Avg Check-in</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Samples</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Snooze</td>"
            table += "</tr>"

            def rowNum = 0
            devList.each { device ->
                def data     = state.history?.get(device.id)
                def protocol = getProtocol(device)
                def lastSeen = data?.lastSeen ? formatTimeAgo(data.lastSeen) : "Never"
                def avgInt   = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                               data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."
                def samples  = data?.samples?.size() ?: 0
                def snoozed  = isDeviceSnoozed(device.id as String)
                def rowBg    = snoozed ? "#f8f8f8" : (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                rowNum++

                def snoozeBtn = snoozed ?
                    "<input type='button' value='✅' onclick='buttonClick(this)' name='unsnooze_${device.id}' style='cursor:pointer;font-size:16px;border:none;background:none;' title='Click to unsnooze'>" :
                    "<input type='button' value='😴' onclick='buttonClick(this)' name='snooze_${device.id}' style='cursor:pointer;font-size:16px;border:none;background:none;' title='Click to snooze'>"

                table += "<tr style='background-color:${rowBg};${snoozed ? "opacity:0.6;" : ""}'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${device.displayName}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocol}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${getHealthDisplay(device)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastSeen}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${avgInt}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${samples}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc; text-align:center;'>${snoozeBtn}</td>"
                table += "</tr>"
            }

            table += "</table>"
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }

        section("<b>🔄 Reset Device History</b>", hideable: true, hidden: true) {
            paragraph "Reset check-in history for specific devices. Health returns to Pending while fresh data is collected."
            href "resetHistoryPage", title: "🔄 Reset Device History", description: "Select devices to reset"
        }
    }
}

// ============================================================
// ===================== PROBLEM DEVICES PAGE ================
// ============================================================
def problemDevicesPage() {
    dynamicPage(name: "problemDevicesPage", title: "⚠️ Problem Devices", install: false) {
        section("") {
            def devList = getAllMonitoredDevices().findAll { device ->
                def h = state.health?.get(device.id) ?: "Pending"
                h in ["Offline", "Poor", "Fair"] && !isDeviceSnoozed(device.id as String)
            }

            if (!devList) {
                paragraph "✅ No problem devices found — all monitored devices are healthy."
                return
            }

            devList = devList.sort { a, b ->
                def healthPriority = ["Offline": 1, "Poor": 2, "Fair": 3]
                def hA = state.health?.get(a.id) ?: "Fair"
                def hB = state.health?.get(b.id) ?: "Fair"
                (healthPriority[hA] ?: 3) <=> (healthPriority[hB] ?: 3)
            }

            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Protocol</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Health</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Last Seen</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Avg Check-in</td>"
            table += "</tr>"

            def rowNum = 0
            devList.each { device ->
                def data     = state.history?.get(device.id)
                def protocol = getProtocol(device)
                def lastSeen = data?.lastSeen ? formatTimeAgo(data.lastSeen) : "Never"
                def avgInt   = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                               data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."
                def rowBg    = (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                rowNum++

                table += "<tr style='background-color:${rowBg};'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${device.displayName}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocol}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${getHealthDisplay(device)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastSeen}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${avgInt}</td>"
                table += "</tr>"
            }

            table += "</table>"
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }
    }
}

// ============================================================
// ===================== FORCE SCAN PAGE =====================
// ============================================================
def forceScanPage() {
    scanAllDevices()
    if (debugMode) log.debug "Manual device scan triggered by user"

    dynamicPage(name: "forceScanPage", title: "Force Scan", install: false) {
        section("<b>Scan Complete</b>") {
            def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
            paragraph "✅ Device scan complete — ${devList.size()} device(s) checked. " +
                      "Return to Device Activity Summary to see updated values.<br><br>" +
                      "<b>Note:</b> A new check-in sample is only recorded if at least 10 minutes " +
                      "have passed since the last recorded activity."
        }
    }
}

// ============================================================
// ===================== RESET HISTORY PAGE ==================
// ============================================================
def resetHistoryPage() {
    app.removeSetting("resetHistoryDevices")
    app.updateSetting("resetHistoryConfirm", [value: false, type: "bool"])

    def devList = getAllMonitoredDevices()
        .findAll { getProtocol(it) != "Unknown" }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    dynamicPage(name: "resetHistoryPage", title: "Reset Device History", install: false) {
        section("<b>Select Devices to Reset</b>") {
            if (!devList || devList.size() == 0) {
                paragraph "No devices available."
            } else {
                paragraph "Select one or more devices to reset. Their check-in history and learned baseline will be cleared. Health will return to Pending while fresh data is collected."
                input "resetHistoryDevices", "enum",
                      title: "Select devices to reset",
                      options: devList.collectEntries { [(it.id): "${it.displayName} (${state.health?.get(it.id) ?: 'Pending'})"] }
                                      .sort { a, b -> a.value <=> b.value },
                      multiple: true,
                      required: false
            }
        }
        section("<b>Confirm Reset</b>") {
            input "resetHistoryConfirm", "bool",
                  title: "Confirm — clear history for selected devices",
                  defaultValue: false
        }
        section() {
            href "resetHistoryConfirmPage", title: "Submit Reset"
        }
    }
}

// ============================================================
// ================ RESET HISTORY CONFIRM PAGE ===============
// ============================================================
def resetHistoryConfirmPage() {
    def devList = getAllMonitoredDevices()

    dynamicPage(name: "resetHistoryConfirmPage", title: "Reset Device History", install: false) {
        section("<b>Result</b>") {
            if (!resetHistoryConfirm) {
                paragraph "Reset cancelled — confirm checkbox was not checked."
            } else if (!resetHistoryDevices) {
                paragraph "No devices selected."
            } else {
                def successCount = 0
                def resetNames   = []

                resetHistoryDevices.each { deviceId ->
                    def device = devList.find { it.id == deviceId }
                    if (device) {
                        state.history[device.id] = [
                            lastSeen:       now(),
                            lastCheckin:    now(),
                            samples:        [],
                            avgInterval:    null,
                            userInterval:   state.history?.get(device.id)?.userInterval,
                            missedCheckins: 0,
                            protocol:       getProtocol(device)
                        ]
                        state.health[device.id] = "Pending"
                        resetNames << device.displayName
                        successCount++
                        if (debugMode) log.debug "Reset history for ${device.displayName}"
                    }
                }

                if (successCount > 0) {
                    paragraph "✅ History reset for ${successCount} device(s): ${resetNames.join(', ')}. " +
                              "Health will show Pending while fresh data is collected."
                } else {
                    paragraph "No valid devices found."
                }
            }
        }
    }
}

// ============================================================
// ===================== SEND NOTIFICATION PAGE ==============
// ============================================================
def sendNotificationPage() {
    dynamicPage(name: "sendNotificationPage", title: "Send Notification", install: false) {
        def devList    = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
        def hasDevices = devList.size() > 0
        def hasTargets = (settings?.notifyDevices?.size() ?: 0) > 0 ||
                         (settings?.pushoverDevices?.size() ?: 0) > 0 ||
                         (settings?.enablePush == true)
        def notifyOn   = settings?.enablePush != false

        if (!hasDevices) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ No monitored devices are selected."
            }
            return
        }
        if (!notifyOn) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ Notifications are turned off."
            }
            return
        }
        if (!hasTargets) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ No notification devices configured."
            }
            return
        }

        section("<b>Confirm</b>") {
            paragraph "This will send a device health summary notification now."
            input "sendNowConfirm", "bool",
                  title: "✅ Confirm — send the notification",
                  defaultValue: false,
                  submitOnChange: true
        }
        if (settings?.sendNowConfirm) {
            section("<b>Result</b>") {
                scheduledSummary()
                app.updateSetting("sendNowConfirm", [value: false, type: "bool"])
                def sentTo = []
                if (settings?.notifyDevices)   sentTo.addAll(settings.notifyDevices.collect { it.displayName })
                if (settings?.pushoverDevices) sentTo.addAll(settings.pushoverDevices.collect { "${it.displayName} (Pushover)" })
                if (sentTo) {
                    paragraph "✅ Notification sent to:\n" + sentTo.collect { "• ${it}" }.join("\n")
                } else {
                    paragraph "✅ Notification sent via hub push."
                }
            }
        }
    }
}

// ============================================================
// ===================== SCHEDULED SUMMARY ===================
// ============================================================
def scheduledSummary() {
    if (!isModeOK()) {
        if (debugMode) log.debug "Notification skipped — hub mode not in allowed modes"
        return
    }

    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return

    def usePushover = (settings?.enablePushover == true && settings?.pushoverPrefix?.trim())
    def prefix  = ""
    def postfix = ""

    if (usePushover) {
        def tags = settings.pushoverPrefix.trim()
        def priorityMatch = tags =~ /^(\[[EHLNS]\])(.*)/
        if (priorityMatch) {
            prefix  = priorityMatch[0][1]
            postfix = priorityMatch[0][2].trim()
        } else {
            postfix = tags
        }
    }

    def body = "${prefix}📡 Device Health Summary\n"

    def sections = [
        "Offline":   [emoji: "💀", enabled: settings?.notifyOffline   != false, list: []],
        "Poor":      [emoji: "🔴", enabled: settings?.notifyPoor      != false, list: []],
        "Fair":      [emoji: "🟠", enabled: settings?.notifyFair      != false, list: []],
        "Good":      [emoji: "🟢", enabled: settings?.notifyGood      ?: false, list: []],
        "Excellent": [emoji: "🟢", enabled: settings?.notifyExcellent ?: false, list: []]
    ]

    devList.each { device ->
        if (!isDeviceSnoozed(device.id as String)) {
            def h = state.health?.get(device.id) ?: "Pending"
            if (sections.containsKey(h)) {
                sections[h].list << device.displayName.trim()
            }
        }
    }

    if (suppressEmptyReport != null ? suppressEmptyReport : false) {
        def hasContent = sections.any { h, data -> data.enabled && data.list }
        if (!hasContent) return
    }

    sections.each { health, data ->
        if (data.enabled) {
            body += "\n${data.emoji} ${health}:\n"
            if (data.list) {
                data.list.each { name -> body += "• ${name}\n" }
            } else {
                body += "None\n"
            }
        }
    }

    def pushoverBody = body
    def plainBody    = body
    if (postfix) pushoverBody += "${postfix}\n"

    if (settings?.enablePush)      sendPush(pushoverBody)
    if (settings?.pushoverDevices) settings.pushoverDevices.each { it.deviceNotification(pushoverBody) }
    if (settings?.notifyDevices)   notifyDevices.each { it.deviceNotification(plainBody) }
}

// ============================================================
// ===================== INFO PAGE ===========================
// ============================================================
def infoPage(Map params = [:]) {
    dynamicPage(name: "infoPage", title: "App Guide & Reference", install: false) {

        section("<b>📡 What This App Does</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Device Health Monitor tracks how frequently your devices check in with the hub. " +
                      "It learns each device's normal check-in pattern and flags anything that goes quiet, checks in late, or drops offline.<br><br>" +
                      "This is different from battery monitoring — a device can have a full battery but still have mesh or connectivity issues. " +
                      "It also monitors LAN, cloud, and app-created devices, making it useful for catching broken integrations and automations that have stopped firing.</div>"
        }

        section("<b>🔑 Health Ratings</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Health</td><td>Meaning</td></tr>" +
                      "<tr><td>⏳ Pending</td><td>Not enough check-in samples yet (need 3)</td></tr>" +
                      "<tr><td>🟢 Excellent</td><td>Checking in within 1.2x of baseline — right on schedule</td></tr>" +
                      "<tr><td>🟢 Good</td><td>Checking in within 2x of baseline — slightly late but normal</td></tr>" +
                      "<tr><td>🟠 Fair</td><td>Checking in within 3x of baseline — worth watching</td></tr>" +
                      "<tr><td>🔴 Poor</td><td>Checking in within 5x of baseline — likely a problem</td></tr>" +
                      "<tr><td>💀 <span style='color:#991b1b;font-weight:bold;'>Offline</span></td><td>No activity for ${settings?.offlineThresholdHours ?: 24}h or beyond 5x baseline</td></tr>" +
                      "<tr><td>😴 Snoozed</td><td>Excluded from notifications for a set duration — still visible in reports</td></tr>" +
                      "</table></div><br>" +
                      "<b>Protocol Colors:</b><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Color</td><td>Protocol</td></tr>" +
                      "<tr><td><span style='color:#3b82f6;font-weight:bold;'>Blue</span></td><td>Zigbee</td></tr>" +
                      "<tr><td><span style='color:#8b5cf6;font-weight:bold;'>Purple</span></td><td>Z-Wave</td></tr>" +
                      "<tr><td><span style='color:#f97316;font-weight:bold;'>Orange</span></td><td>Matter</td></tr>" +
                      "<tr><td><span style='color:#06b6d4;font-weight:bold;'>Cyan</span></td><td>Hub Mesh</td></tr>" +
                      "<tr><td><span style='color:#14b8a6;font-weight:bold;'>Teal</span></td><td>LAN / Cloud / Virtual</td></tr>" +
                      "</table></div></div>"
        }

        section("<b>⏳ Baseline Learning</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Each device starts as <b>⏳ Pending</b> while the app learns its normal check-in frequency. " +
                      "After 3 samples the app calculates a baseline using EWMA smoothing — the same approach used in Battery Monitor 2.0.<br><br>" +
                      "The baseline adapts continuously as new check-ins arrive. " +
                      "Use Reset Device History if a device's baseline needs to be cleared after a mesh change or hardware swap.</div>"
        }

        section("<b>😴 Snooze</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Tap 😴 next to any device in the Activity Summary to snooze it. Snoozed devices are excluded from notifications for the configured snooze duration (default 24h).<br><br>" +
                      "Snoozed devices still appear in the Activity Summary table with a 😴 indicator and a countdown showing hours remaining. " +
                      "Tap ✅ to unsnooze a device early. Snoozes expire automatically when the duration passes.<br><br>" +
                      "Snoozed devices are never included in Problem Devices — use snooze for devices you are actively working on.</div>"
        }

        section("<b>📋 Device Selection</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "All devices are selected from a single list. Protocol is detected automatically from each device's hub properties.<br><br>" +
                      "<b>Protocol Detection:</b><br>" +
                      "• <span style='color:#3b82f6;font-weight:bold;'>Zigbee</span> — directly paired Zigbee devices<br>" +
                      "• <span style='color:#8b5cf6;font-weight:bold;'>Z-Wave</span> — directly paired Z-Wave devices<br>" +
                      "• <span style='color:#f97316;font-weight:bold;'>Matter</span> — Matter devices<br>" +
                      "• <span style='color:#06b6d4;font-weight:bold;'>Hub Mesh</span> — devices linked from another Hubitat hub<br>" +
                      "• <span style='color:#14b8a6;font-weight:bold;'>LAN</span> — local integrations (Hue, Shelly), cloud integrations (Govee, Tesla, Ecobee), and app-created devices<br><br>" +
                      "<b>Notes:</b><br>" +
                      "• Virtual switches appear as LAN — simply don't select them if you don't want them monitored<br>" +
                      "• App-created child devices appear as LAN — monitoring these is useful for detecting broken automations<br>" +
                      "• Bluetooth devices (C-8 Pro only) will appear as LAN until a controllerType value is confirmed</div>"
        }

        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "• Let devices run for at least a day before trusting health ratings<br>" +
                      "• Set Scan Interval to Hourly to build baselines faster<br>" +
                      "• Devices that only wake on events (motion, contact) will have longer natural intervals — this is normal<br>" +
                      "• App-created devices that haven't fired in a long time will show 💀 Offline — useful for spotting broken automations<br>" +
                      "• Hub variable connector devices going stale will show as Offline — useful for detecting broken Rule Machine rules<br>" +
                      "• No hub event subscriptions are used — all monitoring is done via scheduled scans of Hubitat's Last Activity data<br>" +
                      "• Use Mode Restriction to suppress notifications during certain hub modes (e.g. Away, Night)</div>"
        }
    }
}
