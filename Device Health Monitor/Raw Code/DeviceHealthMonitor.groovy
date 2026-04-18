definition(
    name: "Device Health Monitor",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Monitor device check-in health across Zigbee, Z-Wave, Matter, Hub Mesh, LAN, Virtual and Hub Variable — learns each device's normal pattern and alerts you when something goes quiet.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    iconUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    iconX2Url: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    version: "1.3.3",
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
    page(name: "snoozeManagePage")
    page(name: "protocolOverridePage")
    page(name: "infoPage")
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    if (debugEnabled()) log.debug "Device Health Monitor installed"
    applyCustomLabel()
    initialize()
}

def updated() {
    if (debugEnabled()) log.debug "Device Health Monitor updated"
    applyCustomLabel()
    unschedule()
    unsubscribe()

    // v1.3.2: if snooze was just disabled, clear all active snoozes
    if (settings?.enableSnooze == false) {
        state.snoozed = [:]
        if (debugEnabled()) log.debug "Snooze disabled — all active snoozes cleared"
    }

    initialize()
    if (debugEnabled()) runIn(1800, disableDebugLogging)
}

def initialize() {
    if (debugEnabled()) log.debug "Device Health Monitor initializing"
    if (state.history == null) state.history = [:]
    if (state.health  == null) state.health  = [:]
    if (state.snoozed == null) state.snoozed = [:]
    scheduleScanInterval()
    scheduleReportFrequency()
    if (debugEnabled()) log.debug "Monitoring ${getAllMonitoredDevices().findAll { getProtocol(it) != 'Unknown' }.size()} device(s)"
    runIn(5, scanAllDevices)
}

def debugEnabled() {
    return settings?.debugMode == true
}

def disableDebugLogging() {
    log.info "Device Health Monitor: auto-disabling debug logging after 30 minutes"
    app.updateSetting("debugMode", [value: false, type: "bool"])
}

def applyCustomLabel() {
    if (settings?.customAppName) {
        if (app.label != settings?.customAppName) {
            app.updateLabel(settings.customAppName)
            if (debugEnabled()) log.debug "App label updated to: ${settings.customAppName}"
        }
    }
}

// ============================================================
// ===================== SNOOZE ==============================
// ============================================================
def snoozeEnabled() {
    // v1.3.2: default true so existing installs keep snooze working
    return settings?.enableSnooze != false
}

def snoozeDevice(deviceId) {
    if (!snoozeEnabled()) return
    def hours = (settings?.snoozeDurationHours ?: 24).toInteger()
    def until = now() + (hours * 3600000)
    if (!state.snoozed) state.snoozed = [:]
    state.snoozed[deviceId] = until
    if (debugEnabled()) log.debug "Snoozed device ${deviceId} for ${hours}h until ${new Date(until)}"
}

def unsnoozeDevice(deviceId) {
    state.snoozed?.remove(deviceId)
    if (debugEnabled()) log.debug "Unsnoozed device ${deviceId}"
}

def isDeviceSnoozed(deviceId) {
    if (!snoozeEnabled()) return false
    def until = state.snoozed?.get(deviceId)
    if (!until) return false
    if (until >= now()) return true
    state.snoozed.remove(deviceId)
    return false
}

def getSnoozedHoursRemaining(deviceId) {
    def until = state.snoozed?.get(deviceId)
    if (!until) return 0
    return Math.ceil((until - now()) / 3600000).toInteger()
}

def formatSnoozeRemaining(deviceId) {
    def until   = state.snoozed?.get(deviceId)
    if (!until) return "expired"
    def msLeft  = until - now()
    def days    = (msLeft / 86400000).toInteger()
    def hours   = ((msLeft % 86400000) / 3600000).toInteger()
    def minutes = ((msLeft % 3600000) / 60000).toInteger()
    if (days >= 1)  return "${days}d ${hours}h remaining"
    if (hours >= 1) return "${hours}h ${minutes}m remaining"
    return "${minutes}m remaining"
}

// ============================================================
// ===================== PROTOCOL DETECTION ==================
// ============================================================
def getAllMonitoredDevices() {
    return monitoredDevices ?: []
}

// v1.3.2: getRawProtocol — identical to getProtocol but IGNORES any user override.
// Used by the override page filter so devices with an active override stay visible.
def getRawProtocol(device) {
    try {
        def driverName = (device.typeName ?: "").toLowerCase()
        def devName    = (device.name     ?: "").toLowerCase()
        if (driverName.contains("hub variable") || driverName.contains("variable connector") ||
            devName.contains("hub variable")    || devName.contains("variable connector")) {
            return "Hub Variable"
        }
        if (driverName.contains("virtual") || devName.contains("virtual")) {
            return "Virtual"
        }
        def devData = device.properties
        if (devData?.controllerType == "LNK") {
            def encoding = device.getDataValue("Encoding")
            if (encoding?.toLowerCase() == "zigbee")                          return "Hub Mesh (Zigbee)"
            if (encoding?.toLowerCase() == "z-wave")                          return "Hub Mesh (Z-Wave)"
            if (device.getDataValue("In Clusters")  != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("inClusters")   != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("Out Clusters") != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("outClusters")  != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("zigbeeId")     != null)                  return "Hub Mesh (Zigbee)"
            if (device.getDataValue("zigbeeNodeType") != null)                return "Hub Mesh (Zigbee)"
            if (device.getDataValue("zwaveSecurePairingComplete") != null)    return "Hub Mesh (Z-Wave)"
            if (device.getDataValue("secureInClusters")           != null)    return "Hub Mesh (Z-Wave)"
            if (device.getDataValue("Zw Node Info")               != null)    return "Hub Mesh (Z-Wave)"
            if (driverName.contains("zigbee"))                                return "Hub Mesh (Zigbee)"
            if (driverName.contains("z-wave") || driverName.contains("zwave")) return "Hub Mesh (Z-Wave)"
            if (driverName.contains("matter"))                                return "Hub Mesh (Matter)"
            def manufacturer = (device.getDataValue("Manufacturer") ?: "").toLowerCase()
            if (manufacturer in ["centralite", "lumi", "ikea", "sengled",
                                 "osram", "philips", "samsung", "smartthings",
                                 "sonoff", "tuya", "third reality"]) {
                return "Hub Mesh (Zigbee)"
            }
            return "Hub Mesh"
        }
        if (devData?.controllerType == "ZGB") return "Zigbee"
        if (devData?.controllerType == "ZWV") return "Z-Wave"
        if (devData?.controllerType == "MAT") return "Matter"
        if (device.getDataValue("Endpoint Id")                != null) return "Zigbee"
        if (device.getDataValue("endpointId")                 != null) return "Zigbee"
        if (device.getDataValue("zigbeeNodeType")             != null) return "Zigbee"
        if (device.getDataValue("zigbeeId")                   != null) return "Zigbee"
        if (device.getDataValue("In Clusters")                != null) return "Z-Wave"
        if (device.getDataValue("inClusters")                 != null) return "Z-Wave"
        if (device.getDataValue("zwaveSecurePairingComplete") != null) return "Z-Wave"
        if (device.getDataValue("secureInClusters")           != null) return "Z-Wave"
        if (device.getDataValue("Zw Node Info")               != null) return "Z-Wave"
        return "LAN"
    } catch (e) {
        if (debugEnabled()) log.debug "getRawProtocol error for ${device.displayName}: ${e.message}"
    }
    return "Unknown"
}

def getProtocol(device) {
    try {
        // ── User override — always wins ──────────────────────────
        def override = settings["protocolOverride_${device.id}"]
        if (override && override != "" && override != "Auto-detect") return override

        // ── Fall through to raw detection ────────────────────────
        return getRawProtocol(device)
    } catch (e) {
        if (debugEnabled()) log.debug "getProtocol error for ${device.displayName}: ${e.message}"
    }
    return "Unknown"
}

def getProtocolColor(protocol) {
    switch (protocol) {
        case "Zigbee":             return "#3b82f6"
        case "Hub Mesh (Zigbee)":  return "#3b82f6"
        case "Z-Wave":             return "#8b5cf6"
        case "Hub Mesh (Z-Wave)":  return "#8b5cf6"
        case "Matter":             return "#f97316"
        case "Hub Mesh (Matter)":  return "#f97316"
        case "Hub Mesh":           return "#06b6d4"
        case "LAN":                return "#14b8a6"
        case "Virtual":            return "#ec4899"
        case "Hub Variable":       return "#eab308"
        case "Bluetooth":          return "#06b6d4"
        default:                   return "#94a3b8"
    }
}

def isUnresolvableProtocol(protocol) {
    return protocol in ["Hub Mesh", "LAN"]
}

def usesFilteredSampling(protocol) {
    return protocol in ["Virtual", "Hub Variable"]
}

def isModeOK() {
    if (!settings?.enableModeRestriction) return true
    if (!settings?.restrictedModes) return true
    return settings.restrictedModes.contains(location.mode)
}

// ============================================================
// ===================== LOW ACTIVITY ========================
// ============================================================
// v1.3.2: returns true if a device has been monitored for >7 days
//         but has fewer than 3 samples (low-activity / infrequent device)
def isLowActivity(deviceId) {
    def data = state.history?.get(deviceId)
    if (!data) return false
    def samples      = data?.samples?.size() ?: 0
    def lastSeen     = data?.lastSeen ?: now()
    def ageMs        = now() - lastSeen
    def ageDays      = ageMs / (1000.0 * 60 * 60 * 24)
    return (ageDays >= 7 && samples < 3)
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

        // ── Device Selection ─────────────────────────────────────
        def devicesSelected = (monitoredDevices?.size() ?: 0) > 0
        section("<b>Monitored Devices</b>", hideable: true, hidden: devicesSelected) {
            paragraph "<b>Select the devices you want to monitor.</b> Protocol is detected automatically."
            paragraph "<span style='color:red; font-weight:bold;'>IMPORTANT: After selecting devices, you MUST click 'Done' before viewing reports.</span>"
            input "monitoredDevices", "capability.*",
                  title: "Select devices to monitor",
                  multiple: true,
                  required: false,
                  submitOnChange: true
        }
        if (devicesSelected) {
            def allSelected          = monitoredDevices
            def zigbeeCount          = allSelected.count { getProtocol(it) in ["Zigbee", "Hub Mesh (Zigbee)"] }
            def zwaveCount           = allSelected.count { getProtocol(it) in ["Z-Wave", "Hub Mesh (Z-Wave)"] }
            def matterCount          = allSelected.count { getProtocol(it) in ["Matter", "Hub Mesh (Matter)"] }
            def hubMeshCount         = allSelected.count { getProtocol(it) == "Hub Mesh" }
            def lanCount             = allSelected.count { getProtocol(it) == "LAN" }
            def virtualCount         = allSelected.count { getProtocol(it) == "Virtual" }
            def hubVarCount          = allSelected.count { getProtocol(it) == "Hub Variable" }
            def unknownCount         = allSelected.count { getProtocol(it) == "Unknown" }
            def totalCount           = allSelected.size()
            def unresolvableCount    = allSelected.count { isUnresolvableProtocol(getRawProtocol(it)) }
            section("") {
                paragraph "<b><span style='color:blue;'>${totalCount} device(s) selected</span></b> — " +
                          "Zigbee: <b><span style='color:#3b82f6;'>${zigbeeCount}</span></b> | " +
                          "Z-Wave: <b><span style='color:#8b5cf6;'>${zwaveCount}</span></b> | " +
                          "Matter: <b><span style='color:#f97316;'>${matterCount}</span></b> | " +
                          "Hub Mesh: <b><span style='color:#06b6d4;'>${hubMeshCount}</span></b> | " +
                          "LAN: <b><span style='color:#14b8a6;'>${lanCount}</span></b> | " +
                          "Virtual: <b><span style='color:#ec4899;'>${virtualCount}</span></b> | " +
                          "Hub Variable: <b><span style='color:#eab308;'>${hubVarCount}</span></b>" +
                          (unknownCount > 0 ? " | <span style='color:orange;'>Unknown: <b>${unknownCount}</b> (skipped)</span>" : "") +
                          (unresolvableCount > 0 ? "<br><span style='color:#94a3b8;'>⚠ ${unresolvableCount} device(s) could not be fully identified — tap <b>Protocol Overrides</b> below to set manually.</span>" : "") +
                          " — Tap <b>Monitored Devices</b> above to change."
            }
        }

        if (!devicesSelected) {
            section("") {
                paragraph "<span style='color:red; font-weight:bold;'>⚠ No devices selected. Select devices above to begin monitoring.</span>"
            }
        }

        // ── v1.3.2: Consolidated Monitoring Settings ─────────────
        def scanIntervalLabel = ["0.5": "Every 30 min", "1": "Hourly", "3": "Every 3 h", "6": "Every 6 h"]
        def currentScan      = scanIntervalLabel[settings?.scanInterval ?: "3"] ?: "Every 3 h"
        def currentThreshold = settings?.offlineThresholdHours ?: 72
        def snoozeOn         = snoozeEnabled()
        def currentSnooze    = settings?.snoozeDurationHours ?: 24
        def modeOn           = settings?.enableModeRestriction == true
        def modeLabel        = modeOn ? (settings?.restrictedModes ? settings.restrictedModes.join(", ") : "none set") : "off"

        def monitoringSummary = "Scan: <b>${currentScan}</b> | " +
                                "Offline after: <b>${currentThreshold}h</b> | " +
                                "Snooze: <b>${snoozeOn ? "${currentSnooze}h" : "disabled"}</b> | " +
                                "Mode restriction: <b>${modeLabel}</b>"

        section("<b>Monitoring Settings</b>", hideable: true, hidden: true) {

            paragraph "<b>Scan Interval</b> — how often device activity is checked and health ratings are updated."
            input "scanInterval", "enum",
                  title: "Scan Frequency:",
                  options: ["0.5": "Every 30 Minutes", "1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3",
                  submitOnChange: true

            paragraph "<b>Offline after inactivity (hours)</b> — devices with no activity beyond this threshold are marked Offline."
            input "offlineThresholdHours", "number",
                  title: "Offline after inactivity (hours):",
                  defaultValue: 72,
                  required: true,
                  submitOnChange: true

            paragraph "<b>Snooze</b> — enable or disable snooze globally. When disabled all active snoozes are cleared and the Manage Snoozed Devices link is hidden."
            input "enableSnooze", "bool",
                  title: "Enable snooze",
                  defaultValue: true,
                  submitOnChange: true

            if (snoozeEnabled()) {
                input "snoozeDurationHours", "number",
                      title: "Snooze duration (hours):",
                      defaultValue: 24,
                      required: true,
                      submitOnChange: true
            }

            paragraph "<b>Mode Restriction</b> — optionally restrict notifications to specific hub modes. Scanning always runs."
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
        section("") {
            paragraph "<span style='color:blue;'>${monitoringSummary}</span> — tap <b>Monitoring Settings</b> above to change."
        }

        // ── Notifications ────────────────────────────────────────
        def notifConfigured = settings?.enablePush != false &&
                              (settings?.notifyDevices || settings?.pushoverDevices)
        section("<b>Notifications</b>", hideable: true, hidden: notifConfigured) {
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
                      required: false,
                      submitOnChange: true

                input "enablePushover", "bool", title: "⚙️ Enable Pushover Markup", defaultValue: false
                input "pushoverDevices", "capability.notification",
                      title: "Pushover notification devices <b>(receives Pushover-formatted message)</b>",
                      multiple: true,
                      required: false,
                      submitOnChange: true
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
                href(name: "toSendNotification", page: "sendNotificationPage",
                     title: "📤 Send Notification Now",
                     description: "Tap to preview and send a device health notification")
            }
        }
        if (notifConfigured) {
            section("") {
                def notifyTargets = []
                if (settings?.notifyDevices)   notifyTargets.addAll(settings.notifyDevices.collect { it.displayName })
                if (settings?.pushoverDevices) notifyTargets.addAll(settings.pushoverDevices.collect { "${it.displayName} (Pushover)" })
                paragraph "Notifications are <b><span style='color:blue;'>ON</span></b> — sending to: <b><span style='color:blue;'>${notifyTargets.join(', ')}</span></b> — tap <b>Notifications</b> above to change."
            }
        }

        // ── Reports ──────────────────────────────────────────────
        section("<b>Reports:</b>") {
            href(name: "toActivitySummary", page: "activitySummaryPage",
                 title: "Device Activity Summary",
                 description: "View all monitored devices and their health status")
            href(name: "toProblemDevices", page: "problemDevicesPage",
                 title: "⚠️ Problem Devices",
                 description: "View devices with Fair, Poor or Offline health")
            if (snoozeEnabled()) {
                href(name: "toSnoozeManage", page: "snoozeManagePage",
                     title: "😴 Manage Snoozed Devices",
                     description: "Snooze devices or clear active snoozes")
            }
            href(name: "toProtocolOverride", page: "protocolOverridePage",
                 title: "🔧 Protocol Overrides",
                 description: "Manually set protocol for devices that could not be auto-detected")
        }

        // ── Help & Support ────────────────────────────────────────
        // v1.3.2: App Guide, Community Thread, and Buy Me a Coffee merged into one section
        section("<b>Help & Support</b>") {
            href(name: "toInfoPage", page: "infoPage",
                 title: "📖 App Guide & Reference",
                 description: "Health scoring, check-in baselines, snooze, and troubleshooting explained")
            href url: "https://community.hubitat.com/t/beta-device-health-monitor/163229",
                 style: "external",
                 title: "💬 Hubitat Community Thread",
                 description: "Questions, feedback, and release notes"
            href url: "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US",
                 style: "external",
                 title: "☕ Buy Me a Coffee",
                 description: "Enjoying the app? Any amount is appreciated — thank you!"
        }

        // ── Diagnostics ──────────────────────────────────────────
        section("<b>Diagnostics</b>") {
            input "debugMode", "bool",
                  title: "Debug Logging (auto-disables after 30 min)",
                  defaultValue: false,
                  submitOnChange: true
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
    def intervalStr = settings?.scanInterval ?: "3"
    def cronExpr = ""
    switch (intervalStr) {
        case "0.5": cronExpr = "0 */30 * * * ?"; break
        case "1":   cronExpr = "0 0 * * * ?";    break
        case "3":   cronExpr = "0 0 */3 * * ?";  break
        case "6":   cronExpr = "0 0 */6 * * ?";  break
        default:    cronExpr = "0 0 */3 * * ?";  break
    }
    schedule(cronExpr, scanAllDevices)
    if (debugEnabled()) log.debug "Device scan scheduled: ${cronExpr}"
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
    if (debugEnabled()) log.debug "Running scheduled device scan for ${devList.size()} device(s)"

    def intervalStr     = settings?.scanInterval ?: "3"
    def intervalMinutes = (intervalStr.toFloat() * 60).toInteger()

    // v1.3.2 fix: use 30.0 (not 30) to avoid Groovy Math.min(BigDecimal, Integer) type error
    def minGate = Math.min(intervalMinutes * 0.5, 30.0)

    devList.each { device ->
        try {
            def id       = device.id
            def data     = state.history[id]
            def protocol = getProtocol(device)
            def filtered = usesFilteredSampling(protocol)

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
                    protocol:       protocol
                ]
                state.history = state.history
                state.health[id] = "Pending"
                if (debugEnabled()) log.debug "Seeded ${device.displayName} (${protocol}) from lastActivity: ${formatTimeAgo(lastSeen)}"
            } else {
                def prevLastSeen = data.lastSeen ?: lastSeen
                if (lastSeen > prevLastSeen) {
                    def elapsed = (lastSeen - prevLastSeen) / (1000 * 60)
                    if (elapsed >= minGate) {
                        def recordSample = true
                        if (filtered) {
                            recordSample = elapsed <= (intervalMinutes * 1.5)
                            if (!recordSample && debugEnabled()) {
                                log.debug "${device.displayName} (${protocol}): skipping sample — elapsed ${elapsed.toInteger()}min exceeds filter gate ${(intervalMinutes * 1.5).toInteger()}min"
                            }
                        }

                        if (recordSample) {
                            def alpha      = 0.3
                            def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : elapsed
                            def smoothed   = alpha * elapsed + (1 - alpha) * prevSmooth
                            data.samples << smoothed
                            if (data.samples.size() > 20) data.samples.remove(0)
                            if (data.samples.size() >= 3) {
                                data.avgInterval = data.samples.sum() / data.samples.size()
                            }
                            if (debugEnabled()) log.debug "${device.displayName} (${protocol}): interval=${elapsed.toInteger()}min smoothed=${smoothed.toInteger()}min avg=${data.avgInterval?.toInteger()}min gate=${minGate.toInteger()}min"
                        }
                        // v1.3.3: only advance lastSeen anchor when elapsed >= minGate so that
                        // rapid-fire devices (motion sensors, contact sensors firing every 1-2 min)
                        // don't walk lastSeen forward in tiny steps, which would produce a
                        // near-zero baseline and cause spurious Poor health when the device goes quiet.
                        data.lastSeen    = lastSeen
                        data.lastCheckin = lastSeen
                    } else {
                        if (debugEnabled()) log.debug "${device.displayName}: elapsed ${elapsed.toInteger()}min below gate ${minGate.toInteger()}min — skipping sample and not advancing lastSeen anchor"
                        // v1.3.3: do NOT update lastSeen here — leave the anchor where it was
                        // so the next scan measures elapsed from the last gate-qualifying activity
                    }
                } else {
                    if (debugEnabled()) log.debug "${device.displayName}: no new activity since last scan"
                }
                data.protocol = protocol
                state.history[id] = data
                state.history = state.history
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

    def offlineThreshold     = ((settings?.offlineThresholdHours ?: 72) * 60).toDouble()
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
    else                   state.health[id] = "Poor"

    if (debugEnabled()) log.debug "${device.displayName}: health=${state.health[id]} ratio=${ratio.round(2)} baseline=${baseline.toInteger()}min lastSeen=${minutesSinceLastSeen.toInteger()}min ago"
}

// ============================================================
// ===================== HEALTH DISPLAY ======================
// ============================================================
def getHealthDisplay(device) {
    def h       = state.health?.get(device.id) ?: "Pending"
    def samples = state.history?.get(device.id)?.samples?.size() ?: 0
    def snoozed = isDeviceSnoozed(device.id as String)

    if (snoozed) {
        def remaining = formatSnoozeRemaining(device.id as String)
        return "😴 <span style='color:#94a3b8;'>Snoozed (${remaining})</span>"
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
// v1.3.3: DataTables sortable columns
def activitySummaryPage() {
    dynamicPage(name: "activitySummaryPage", title: "Device Activity Summary", install: false) {
        section("") {
            // v1.3.3: load DataTables CSS and JS
            paragraph rawHtml: true, """
<link rel="stylesheet" href="https://cdn.datatables.net/1.13.6/css/jquery.dataTables.min.css">
<script src="https://cdn.datatables.net/1.13.6/js/jquery.dataTables.min.js"></script>
"""
            href(name: "toForceScan", page: "forceScanPage",
                 title: "🔄 Force Scan Now",
                 description: "Tap to immediately check all monitored devices")
            if (snoozeEnabled()) {
                href(name: "toSnoozeFromSummary", page: "snoozeManagePage",
                     title: "😴 Manage Snoozed Devices",
                     description: "Snooze devices or clear active snoozes")
            }

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

            def hubIp = location?.hub?.localIP ?: ""

            // v1.3.3: thead/tbody required for DataTables
            def table = "<table id='activityTable' style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<thead><tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Device</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Protocol</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Health</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Last Seen</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Avg Check-in</th>"
            table += "<th style='padding:4px; border:1px solid #ccc;'>Samples</th>"
            table += "</tr></thead><tbody>"

            def rowNum = 0
            devList.each { device ->
                def data        = state.history?.get(device.id)
                def protocol    = getProtocol(device)
                def snoozed     = isDeviceSnoozed(device.id as String)
                def hasOverride = settings["protocolOverride_${device.id}"] &&
                                  settings["protocolOverride_${device.id}"] != "Auto-detect"
                def rowBg       = snoozed ? "#f8f8f8" : (rowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                def protocolDisplay = hasOverride ? "${protocol} <span style='color:#94a3b8;font-size:10px;'>(override)</span>" : protocol

                // v1.3.3: data-order values
                // Last Seen — epoch ms (higher = more recent; invert so "most recent" sorts first asc)
                def lastSeenMs  = data?.lastSeen ? (data.lastSeen as Long) : 0
                def lastSeenStr = lastSeenMs ? formatTimeAgo(lastSeenMs) : "Never"

                // Avg Check-in — raw minutes for numeric sort; Learning... = 999999 (sorts last)
                def avgRawMin = data?.userInterval ? (data.userInterval as Long) :
                                data?.avgInterval  ? (data.avgInterval as Long) : 999999
                def avgIntStr = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                                data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."

                // Samples — numeric
                def sampleCount = data?.samples?.size() ?: 0

                // Low Activity label
                def lowActivity    = isLowActivity(device.id as String)
                def samplesDisplay = lowActivity
                    ? "${sampleCount} <span style='color:#f97316;font-size:10px;'>⚠ Low Activity</span>"
                    : "${sampleCount}"

                // Health sort: Offline=1, Poor=2, Fair=3, Good=4, Excellent=5, Pending=6, Snoozed=99
                def h           = state.health?.get(device.id) ?: "Pending"
                def healthOrder = snoozed ? 99 :
                                  (h == "Offline" ? 1 : h == "Poor" ? 2 : h == "Fair" ? 3 :
                                   h == "Good" ? 4 : h == "Excellent" ? 5 : 6)

                rowNum++

                def deviceLink
                if (hubIp) {
                    deviceLink = "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>"
                } else {
                    deviceLink = device.displayName
                }

                table += "<tr style='background-color:${rowBg};${snoozed ? "opacity:0.6;" : ""}'>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${device.displayName.toLowerCase().trim()}'>${deviceLink}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${protocol}'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocolDisplay}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${healthOrder}'>${getHealthDisplay(device)}</td>"
                // v1.3.3: negate epoch ms so ascending sort = most recent first
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${-lastSeenMs}'>${lastSeenStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${avgRawMin}'>${avgIntStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;' data-order='${sampleCount}'>${samplesDisplay}</td>"
                table += "</tr>"
            }

            table += "</tbody></table>"

            // v1.3.3: DataTables init — paging off, search on, default sort col 2 (Health) asc, numeric on cols 2/3/4/5
            paragraph rawHtml: true, """
${hubIp ? "<span style='color:#94a3b8;font-size:11px;'>⚠ Device links are accessible on your local network only.</span><br>" : ""}
<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>
<script>
\$(document).ready(function() {
    \$('#activityTable').DataTable({
        paging:     false,
        info:       false,
        searching:  true,
        order:      [[2, 'asc']],
        columnDefs: [
            { type: 'num', targets: [2, 3, 4, 5] }
        ]
    });
});
</script>
"""
        }

        section("<b>🔄 Reset Device History</b>", hideable: true, hidden: true) {
            paragraph "Reset check-in history for specific devices. Health returns to Pending while fresh data is collected."
            href(name: "toResetHistory", page: "resetHistoryPage",
                 title: "🔄 Reset Device History",
                 description: "Select devices to reset")
        }
    }
}

// ============================================================
// ===================== PROTOCOL OVERRIDE PAGE ==============
// ============================================================
def protocolOverridePage() {
    // v1.3.2: show only unresolvable devices, but keep devices with an active override
    // visible regardless of what their resolved protocol is now (fix for disappearing devices).
    // We check the RAW auto-detected protocol — ignoring the override — so once a device has
    // been overridden it stays on this page until the user sets it back to Auto-detect.
    def devList = getAllMonitoredDevices()
        .findAll { device ->
            def hasOverride  = settings["protocolOverride_${device.id}"] &&
                               settings["protocolOverride_${device.id}"] != "Auto-detect"
            def rawProtocol  = getRawProtocol(device)
            hasOverride || isUnresolvableProtocol(rawProtocol)
        }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    dynamicPage(name: "protocolOverridePage", title: "🔧 Protocol Overrides", install: false) {
        section("<b>About Protocol Overrides</b>") {
            paragraph "Some Hub Mesh linked devices and LAN devices cannot be automatically identified. " +
                      "Use this page to manually set the correct protocol for those devices.<br><br>" +
                      "The override always takes priority over auto-detection. " +
                      "To restore auto-detection, set the override back to <b>Auto-detect</b>."
        }

        if (!devList || devList.size() == 0) {
            section("") {
                paragraph "✅ All monitored devices have been successfully identified — no overrides needed."
            }
            return
        }

        section("<b>Unidentified / Overridden Devices (${devList.size()})</b>") {
            paragraph "Devices listed here are either showing as <b>Hub Mesh</b> or <b>LAN</b> (unidentified), " +
                      "or have an active manual override set. Devices with an override remain here so you can change or clear it."
            devList.each { device ->
                def currentProtocol = getProtocol(device)
                def currentOverride = settings["protocolOverride_${device.id}"] ?: "Auto-detect"
                input "protocolOverride_${device.id}",
                      "enum",
                      title: "<b>${device.displayName}</b> — currently: <span style='color:${getProtocolColor(currentProtocol)};'>${currentProtocol}</span>",
                      options: [
                          "Auto-detect",
                          "Zigbee", "Z-Wave", "Matter",
                          "Hub Mesh (Zigbee)", "Hub Mesh (Z-Wave)", "Hub Mesh (Matter)", "Hub Mesh",
                          "LAN", "Virtual", "Hub Variable"
                      ],
                      defaultValue: currentOverride,
                      required: false
            }
        }

        section("") {
            paragraph "Tap <b>Done</b> to save overrides. Changes take effect on the next scan."
        }
    }
}

// ============================================================
// ===================== SNOOZE MANAGE PAGE ==================
// ============================================================
def snoozeManagePage() {
    app.removeSetting("devicesToSnooze")
    app.removeSetting("devicesToUnsnooze")
    app.updateSetting("confirmSnooze",   [value: false, type: "bool"])
    app.updateSetting("confirmUnsnooze", [value: false, type: "bool"])

    def devList = getAllMonitoredDevices()
        .findAll { getProtocol(it) != "Unknown" }
        .sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }

    def snoozedList = devList.findAll { isDeviceSnoozed(it.id as String) }
    def activeList  = devList.findAll { !isDeviceSnoozed(it.id as String) }

    dynamicPage(name: "snoozeManagePage", title: "😴 Manage Snoozed Devices", install: false) {

        section("<b>Snooze Devices</b>") {
            paragraph "Select devices to snooze for <b>${settings?.snoozeDurationHours ?: 24} hours</b>. Snoozed devices are excluded from notifications and the Problem Devices page until the snooze expires."
            if (activeList) {
                input "devicesToSnooze", "enum",
                      title: "Select devices to snooze:",
                      options: activeList.collectEntries { [(it.id): "${it.displayName} (${state.health?.get(it.id) ?: 'Pending'})"] }
                                        .sort { a, b -> a.value <=> b.value },
                      multiple: true,
                      required: false
            } else {
                paragraph "All devices are currently snoozed."
            }
        }
        if (activeList) {
            section() {
                input "confirmSnooze", "bool",
                      title: "Confirm — snooze selected devices",
                      defaultValue: false,
                      submitOnChange: true
            }
            if (settings?.confirmSnooze == true) {
                section("<b>Snooze Result</b>") {
                    if (settings?.devicesToSnooze) {
                        def count = 0
                        settings.devicesToSnooze.each { deviceId ->
                            snoozeDevice(deviceId)
                            count++
                        }
                        app.updateSetting("confirmSnooze", [value: false, type: "bool"])
                        paragraph "✅ Snoozed ${count} device(s) for ${settings?.snoozeDurationHours ?: 24} hours."
                    } else {
                        app.updateSetting("confirmSnooze", [value: false, type: "bool"])
                        paragraph "No devices selected to snooze."
                    }
                }
            }
        }

        section("<b>Currently Snoozed</b>") {
            if (snoozedList) {
                paragraph snoozedList.collect { device ->
                    "😴 ${device.displayName} — ${formatSnoozeRemaining(device.id as String)}"
                }.join("\n")

                input "devicesToUnsnooze", "enum",
                      title: "Select devices to unsnooze early:",
                      options: snoozedList.collectEntries { [(it.id): "${it.displayName} (${formatSnoozeRemaining(it.id as String)})"] }
                                         .sort { a, b -> a.value <=> b.value },
                      multiple: true,
                      required: false
            } else {
                paragraph "No devices are currently snoozed."
            }
        }
        if (snoozedList) {
            section() {
                input "confirmUnsnooze", "bool",
                      title: "Confirm — unsnooze selected devices",
                      defaultValue: false,
                      submitOnChange: true
            }
            if (settings?.confirmUnsnooze == true) {
                section("<b>Unsnooze Result</b>") {
                    if (settings?.devicesToUnsnooze) {
                        def count = 0
                        settings.devicesToUnsnooze.each { deviceId ->
                            unsnoozeDevice(deviceId)
                            count++
                        }
                        app.updateSetting("confirmUnsnooze", [value: false, type: "bool"])
                        paragraph "✅ Unsnoozed ${count} device(s)."
                    } else {
                        app.updateSetting("confirmUnsnooze", [value: false, type: "bool"])
                        paragraph "No devices selected to unsnooze."
                    }
                }
            }
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

            def hubIp = location?.hub?.localIP ?: ""

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

                def deviceLink
                if (hubIp) {
                    deviceLink = "<a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${device.displayName}</a>"
                } else {
                    deviceLink = device.displayName
                }

                table += "<tr style='background-color:${rowBg};'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${deviceLink}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'><span style='color:${getProtocolColor(protocol)};font-weight:bold;'>${protocol}</span></td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${getHealthDisplay(device)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastSeen}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${avgInt}</td>"
                table += "</tr>"
            }

            table += "</table>"
            if (hubIp) {
                paragraph "<span style='color:#94a3b8;font-size:11px;'>⚠ Device links are accessible on your local network only.</span>"
            }
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }
    }
}

// ============================================================
// ===================== FORCE SCAN PAGE =====================
// ============================================================
def forceScanPage() {
    scanAllDevices()
    if (debugEnabled()) log.debug "Manual device scan triggered by user"

    dynamicPage(name: "forceScanPage", title: "Force Scan", install: false) {
        section("<b>Scan Complete</b>") {
            def devList         = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
            def intervalStr     = settings?.scanInterval ?: "3"
            def intervalMinutes = (intervalStr.toFloat() * 60).toInteger()
            // v1.3.2 fix: 30.0 not 30 — avoids Math.min(BigDecimal, Integer) type error
            def minGate         = Math.min(intervalMinutes * 0.5, 30.0).toInteger()
            paragraph "✅ Device scan complete — ${devList.size()} device(s) checked. " +
                      "Return to Device Activity Summary to see updated values.<br><br>" +
                      "<b>Note:</b> A new check-in sample is only recorded if at least <b>${minGate} minutes</b> " +
                      "have passed since the last recorded activity (half the scan interval, max 30 min)."
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
            href(name: "toResetConfirm", page: "resetHistoryConfirmPage",
                 title: "Submit Reset")
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
                        state.history = state.history
                        state.health[device.id] = "Pending"
                        resetNames << device.displayName
                        successCount++
                        if (debugEnabled()) log.debug "Reset history for ${device.displayName}"
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
            section("<b>Cannot Send</b>") { paragraph "⚠️ No monitored devices are selected." }
            return
        }
        if (!notifyOn) {
            section("<b>Cannot Send</b>") { paragraph "⚠️ Notifications are turned off." }
            return
        }
        if (!hasTargets) {
            section("<b>Cannot Send</b>") { paragraph "⚠️ No notification devices configured." }
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
        if (debugEnabled()) log.debug "Notification skipped — hub mode not in allowed modes"
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
                      "All monitoring is done via scheduled scans of Hubitat's built-in Last Activity data. " +
                      "No Maker API is required. No event subscriptions are used. " +
                      "The app polls each device's last activity timestamp on a configurable schedule and compares it against the learned baseline.<br><br>" +
                      "This is different from battery monitoring — a device can have a full battery but still have mesh or connectivity issues. " +
                      "It also monitors LAN, cloud, virtual devices, and hub variable connectors, making it useful for catching broken integrations and automations that have stopped firing.</div>"
        }

        section("<b>🔑 Health Ratings</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Health</td><td>Meaning</td></tr>" +
                      "<tr><td>⏳ Pending</td><td>Not enough check-in samples yet (need 3)</td></tr>" +
                      "<tr><td>🟢 Excellent</td><td>Checking in within 1.2x of baseline — right on schedule</td></tr>" +
                      "<tr><td>🟢 Good</td><td>Checking in within 2x of baseline — slightly late but normal</td></tr>" +
                      "<tr><td>🟠 Fair</td><td>Checking in within 3x of baseline — worth watching</td></tr>" +
                      "<tr><td>🔴 Poor</td><td>Checking in beyond 3x of baseline — likely a problem</td></tr>" +
                      "<tr><td>💀 <span style='color:#991b1b;font-weight:bold;'>Offline</span></td><td>No activity for ${settings?.offlineThresholdHours ?: 72}h — hard threshold only</td></tr>" +
                      "<tr><td>😴 Snoozed</td><td>Excluded from notifications for a set duration — still visible in reports</td></tr>" +
                      "<tr><td>⚠ Low Activity</td><td>Monitored 7+ days with fewer than 3 samples — normal for infrequent devices</td></tr>" +
                      "</table></div><br>" +
                      "<b>Note:</b> Offline is triggered <i>only</i> by the hard inactivity threshold. The ratio score maxes at Poor — it never causes Offline on its own.<br><br>" +
                      "<b>Protocol Colors:</b><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Color</td><td>Protocol</td></tr>" +
                      "<tr><td><span style='color:#3b82f6;font-weight:bold;'>Blue</span></td><td>Zigbee / Hub Mesh (Zigbee)</td></tr>" +
                      "<tr><td><span style='color:#8b5cf6;font-weight:bold;'>Purple</span></td><td>Z-Wave / Hub Mesh (Z-Wave)</td></tr>" +
                      "<tr><td><span style='color:#f97316;font-weight:bold;'>Orange</span></td><td>Matter / Hub Mesh (Matter)</td></tr>" +
                      "<tr><td><span style='color:#06b6d4;font-weight:bold;'>Cyan</span></td><td>Hub Mesh (underlying protocol unknown)</td></tr>" +
                      "<tr><td><span style='color:#14b8a6;font-weight:bold;'>Teal</span></td><td>LAN / Cloud</td></tr>" +
                      "<tr><td><span style='color:#ec4899;font-weight:bold;'>Pink</span></td><td>Virtual</td></tr>" +
                      "<tr><td><span style='color:#eab308;font-weight:bold;'>Yellow</span></td><td>Hub Variable</td></tr>" +
                      "</table></div></div>"
        }

        section("<b>⏳ Baseline Learning</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Each device starts as <b>⏳ Pending</b> while the app learns its normal check-in frequency. " +
                      "After 3 samples the app calculates a baseline using EWMA smoothing.<br><br>" +
                      "A sample is recorded only when the elapsed time since the last check-in is at least half the scan interval (capped at 30 minutes). " +
                      "This prevents burst-firing devices (switches, contact sensors) from flooding the sample buffer with very short intervals, " +
                      "while still allowing sub-hourly activity to be captured on short scan schedules.<br><br>" +
                      "The baseline adapts continuously as new check-ins arrive. " +
                      "Use Reset Device History if a device's baseline needs to be cleared after a mesh change or hardware swap.<br><br>" +
                      "<b>Low Activity:</b> If a device has been monitored for more than 7 days but has fewer than 3 samples, " +
                      "the Samples column shows <span style='color:#f97316;'>⚠ Low Activity</span>. " +
                      "This is informational only — it is normal for lights, fans, and switches that are used infrequently.</div>"
        }

        section("<b>🔀 Virtual & Hub Variable Devices</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Virtual devices and hub variable connectors are detected as separate protocols and displayed in their own colors.<br><br>" +
                      "<b><span style='color:#ec4899;'>Virtual</span> devices</b> — virtual switches, sensors, and other app-created virtual devices. " +
                      "These fire on demand rather than on a fixed schedule.<br><br>" +
                      "<b><span style='color:#eab308;'>Hub Variable</span> connectors</b> — devices linked to hub variables in Rule Machine. " +
                      "These only update when a rule runs — making them excellent canaries for broken automations. " +
                      "A hub variable connector showing Fair, Poor, or Offline is a strong signal that a Rule Machine rule has stopped running.<br><br>" +
                      "<b>Filtered sampling:</b> Both protocols use a filtered EWMA baseline. " +
                      "Only check-ins that occurred within the scan interval are recorded as samples. " +
                      "This prevents the baseline from inflating during periods when rules are not running.</div>"
        }

        section("<b>🔗 Hub Mesh Protocol Detection</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Hub Mesh linked devices always show <b>LNK</b> as their controller type regardless of the underlying protocol on the source hub. " +
                      "The app attempts to determine the real protocol using several methods in order:<br><br>" +
                      "1. <b>Encoding data value</b> — some devices (LUMI/Aqara) carry an Encoding field<br>" +
                      "2. <b>Cluster data values</b> — Zigbee cluster data is often preserved on linked devices<br>" +
                      "3. <b>Driver name heuristics</b> — driver names often contain protocol keywords<br>" +
                      "4. <b>Manufacturer heuristics</b> — known Zigbee manufacturers (CentraLite, LUMI, IKEA, etc.)<br><br>" +
                      "When it cannot be identified the device shows as plain <b><span style='color:#06b6d4;'>Hub Mesh</span></b> in cyan. " +
                      "Use <b>🔧 Protocol Overrides</b> to set it manually if needed.</div>"
        }

        section("<b>😴 Snooze</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "Use <b>Manage Snoozed Devices</b> from the main page or Activity Summary to snooze individual devices. " +
                      "Snoozed devices are excluded from notifications and the Problem Devices page for the configured snooze duration (default 24h).<br><br>" +
                      "Snoozed devices still appear in the Activity Summary with a 😴 indicator and a countdown showing time remaining. " +
                      "You can unsnooze devices early at any time. Snoozes expire automatically when the duration passes.<br><br>" +
                      "Snooze can be disabled entirely from <b>Monitoring Settings</b>. When disabled, all active snoozes are cleared and the Manage Snoozed Devices link is hidden.</div>"
        }

        section("<b>📋 Device Selection</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "All devices are selected from a single list. Protocol is detected automatically.<br><br>" +
                      "<b>Protocol Detection:</b><br>" +
                      "• <span style='color:#3b82f6;font-weight:bold;'>Zigbee</span> — directly paired Zigbee devices<br>" +
                      "• <span style='color:#8b5cf6;font-weight:bold;'>Z-Wave</span> — directly paired Z-Wave devices<br>" +
                      "• <span style='color:#f97316;font-weight:bold;'>Matter</span> — Matter devices<br>" +
                      "• <span style='color:#06b6d4;font-weight:bold;'>Hub Mesh</span> — linked from another Hubitat hub (sub-protocol detected where possible)<br>" +
                      "• <span style='color:#14b8a6;font-weight:bold;'>LAN</span> — local integrations (Hue, Shelly), cloud integrations (Govee, Tesla, Ecobee)<br>" +
                      "• <span style='color:#ec4899;font-weight:bold;'>Virtual</span> — virtual switches, sensors, and app-created virtual devices<br>" +
                      "• <span style='color:#eab308;font-weight:bold;'>Hub Variable</span> — hub variable connector devices<br><br>" +
                      "<b>Notes:</b><br>" +
                      "• Manually controlled devices (fans, lights, switches you operate by hand) will show Poor when not in use — this is expected. " +
                      "Use snooze, raise the offline threshold, or simply don't monitor those devices.<br>" +
                      "• Bluetooth devices (C-8 Pro only) will appear as LAN until a controllerType value is confirmed<br>" +
                      "• If a device shows as Hub Mesh or LAN and you know the real protocol, use Protocol Overrides to set it manually</div>"
        }

        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "• Let devices run for at least a day before trusting health ratings<br>" +
                      "• Set Scan Interval to Hourly to build baselines faster<br>" +
                      "• Use Every 30 Minutes for virtual devices or hub variable connectors tied to frequent automations<br>" +
                      "• Devices that only wake on events (motion, contact) will have longer natural intervals — this is normal<br>" +
                      "• A hub variable connector showing Offline means the Rule Machine rule tied to that variable has stopped running<br>" +
                      "• No hub event subscriptions are used — all monitoring is done via scheduled scans of Hubitat's Last Activity data<br>" +
                      "• Use Mode Restriction to suppress notifications during certain hub modes (e.g. Away, Night)<br>" +
                      "• Device names in the Activity Summary and Problem Devices pages are clickable links to the device edit page (local network only)</div>"
        }
    }
}
