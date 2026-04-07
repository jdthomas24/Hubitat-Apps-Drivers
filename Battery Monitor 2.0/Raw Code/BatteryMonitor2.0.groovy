definition(
    name: "Battery Monitor 2.0",
    namespace: "jdthomas24",
    author: "Jdthomas24",
    description: "Advanced Hubitat battery monitoring with analytics, trends and replacement tracking. Recurring scan schedule, confidence-weighted health, EWMA smoothing.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    iconUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Tests%20-%20Groovy%20RAW/Battery%20Monitor%202.0%20BETA%20Tests",
    iconX2Url: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Battery%20Monitor%202.0/Raw%20Code/BatteryMonitor2.0.groovy",
    version: "2.4.1",
    doNotFocus: true
)
 
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
 
    if (debugMode) {
        runIn(1800, disableDebugLogging)
    }
 
    def devList = autoDevices ?: []
    def currentIds = devList.collect { it.id as String }
    state.history?.keySet()?.findAll { !currentIds.contains(it) }?.each { removedId ->
        state.history.remove(removedId)
        state.trend?.remove(removedId)
        if (debugMode) log.debug "Cleaned up removed device: ${removedId}"
    }
}
 
def disableDebugLogging() {
    log.info "Battery Monitor: auto-disabling debug logging after 30 minutes"
    app.updateSetting("debugMode", [value: false, type: "bool"])
}
 
def initialize() {
    if (debugMode) log.debug "Initialization complete"
    if (state.replacements == null) state.replacements = []
    if (state.history == null) state.history = [:]
    if (state.trend == null)   state.trend   = [:]
    scheduleReportFrequency()
    scheduleScanInterval()
    def devList = autoDevices ?: []
    if (devList) {
        subscribe(devList, "battery", batteryHandler)
    }
}
 
// =================== APPLY CUSTOM LABEL ===================
def applyCustomLabel() {
    if (settings?.customAppName) {
        if (app.label != settings?.customAppName) {
            app.updateLabel(settings.customAppName)
            if (debugMode) log.debug "App label updated to: ${settings.customAppName}"
        }
    }
}
 
preferences {
    page(name: "mainPage")
    page(name: "summaryPage")
    page(name: "trendsPage")
    page(name: "historyPage")
    page(name: "deleteHistoryPage")
    page(name: "deleteHistoryConfirmPage")
    page(name: "manualReplacementPage")
    page(name: "manualReplacementConfirmPage")
    page(name: "infoPage")
    page(name: "batteryCatalogPage")
    page(name: "forceScanPage")
    page(name: "sendNotificationPage")
}
 
// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
def mainPage() {
    applyCustomLabel()
 
    dynamicPage(name: "mainPage",
                title: "Battery Monitor 2.0",
                install: true, uninstall: true) {
 
 
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
 
        def devicesSelected = (autoDevices?.size() ?: 0) > 0
        section("<b>Selected Monitored Devices</b>", hideable: true, hidden: devicesSelected) {
            paragraph "<b>⚠ Important: The app automatically detects all devices reporting battery levels. " +
                      "Select the devices you want to monitor from the list below. Only selected devices will be tracked for trends, battery health, and notifications.</b>"
            paragraph "<span style='color:red; font-weight:bold;'>Note for mobile users:</span> If your device names are long, they may extend past the screen in the selection list. This is a UI limitation on smaller screens. You can still select devices as usual."
            paragraph "<span style='color:red; font-weight:bold;'>IMPORTANT: After selecting devices, you MUST click 'Done' to exit the app BEFORE viewing the battery report. Skipping this step may cause an error.</span>"
 
            input "autoDevices", "capability.battery",
                  title: "Select battery devices to monitor",
                  multiple: true,
                  required: false
        }
        if (devicesSelected) {
            section("") {
                paragraph "<b><span style='color:blue;'>${autoDevices.size()} device(s)</span> selected.</b> Tap <b>Selected Monitored Devices</b> above to expand and change your selection."
            }
 
            def devList = autoDevices ?: []
            if (devList) {
                if (!state.history) state.history = [:]
                if (!state.trend)   state.trend   = [:]
                devList.each { device ->
                    if (!state.history[device.id]) {
                        def currentLevel = device.currentValue("battery")
                        state.history[device.id] = [
                            lastLevel:    currentLevel != null ? currentLevel.toInteger() : 100,
                            lastDate:     now(),
                            drain:        0.3,
                            samples:      [],
                            justReplaced: false
                        ]
                        state.trend[device.id] = "Stable"
                    }
                }
            }
        }
 
        section("<b>Battery Scan Interval</b>") {
            input "scanInterval", "enum",
                  title: "Scan Frequency:",
                  description: "How often battery levels are read. More frequent = faster health ratings. Devices also update on their own battery events.",
                  options: ["1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3",
                  submitOnChange: true
        }
 
        def notificationSettings = (notificationSettings != false)
        section("<b>Notifications</b>", hideable: true, hidden: notificationSettings) {
            paragraph "ℹ️ Enable the toggle below to reveal notification settings including frequency, timing, device targets, and which battery groups to include in reports."
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
 
                input "notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false
 
                input "enablePushover", "bool", title: "⚙️ Enable Pushover Markup", defaultValue: false
                input "pushoverDevices", "capability.notification",
                      title: "Pushover notification devices <b>(receives Pushover-formatted message)</b>",
                      multiple: true, required: false
                input "pushoverPrefix", "text",
                      title: "Pushover tags <b>(Only used if Enable Pushover Markup is toggled ON)</b>",
                      description: "Pushover-specific additions to the Battery Monitor notifications, e.g. [H][TITLE=Battery Report][HTML][SELFDESTRUCT=43200]",
                      required: false
 
                paragraph "<b>Report Sections (choose which battery groups to include in notifications):</b>"
                input "notifyPoor",      "bool", title: "🔴 Include Poor (≤25%)",                              defaultValue: true
                input "notifyFair",      "bool", title: "🟠 Include Fair (26–70%)",                            defaultValue: true
                input "notifyGood",      "bool", title: "🟢 Include Good (71–99%)",                            defaultValue: false
                input "notifyExcellent", "bool", title: "🟢 Include Excellent (100%)",                         defaultValue: false
                input "notifyHighDrain", "bool", title: "⚠️ Include Health (Fair, Poor, & High Drain Only)",   defaultValue: true
                input "notifyStale",     "bool", title: "⚠️ Include Stale Devices",                            defaultValue: true
                input "staleThresholdHours", "number",
                      title: "<b>Mark device as stale if no activity for X hours</b>",
                      defaultValue: 24
                input "suppressEmptyReport", "bool", title: "🔕 Don't send notification if nothing to report <b>(Skips Notification entirely when all enabled toggles are Empty)</b>", defaultValue: false
                input "notifyIncludeAppLink", "bool", title: "🔗 Include link to Battery Monitor app <b>(Local Only)</b>", defaultValue: false
 
                paragraph "<b>Send notification now:</b>"
                href "sendNotificationPage", title: "📤 Send Notification Now", description: "Tap to preview and send a battery summary notification"
            }
        }
        if (notificationSettings) {
            section("") {
                paragraph "Notifications are <b><span style='color:blue;'>" + (settings?.enablePush != false ? "ON" : "OFF") + "</span></b> — tap <b>Notifications</b> above to expand and configure."
            }
        }
 
        section("<b>Reports:</b>") {
            href "summaryPage",           title: "Battery Summary"
            href "trendsPage",            title: "Battery Trends"
            href "historyPage",           title: "Battery Replacement History"
            href "manualReplacementPage", title: "Manual Battery Replacement"
            href "batteryCatalogPage",    title: "🔋 Battery Catalog"
        }
 
        section("<b>Help & Info:</b>") {
            href "infoPage", title: "App Guide & Reference",
                 description: "Colors, drain rates, trends, confidence, and replacement detection explained"
        }
 
        section("<b>Diagnostics</b>") {
            input "debugMode", "bool", title: "Debug Logging (auto-disables after 30 min)", defaultValue: false, submitOnChange: true
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
 
// ============================================================
// ===================== SCAN INTERVAL SCHEDULING ============
// ============================================================
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
    def devList = autoDevices ?: []
    if (!devList) return
    if (debugMode) log.debug "Running scheduled battery scan for ${devList.size()} device(s)"
    devList.each { device ->
        try {
            def level = device.currentValue("battery")?.toInteger()
            if (level != null) {
                updateBattery(device, level)
                if (debugMode) log.debug "Scanned ${device.displayName}: ${level}%"
            }
        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }
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
    def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null }
    if (!devList) return
 
    def categories = [
        "🔴 Poor":      [list: [], enabled: notifyPoor      != null ? notifyPoor      : true],
        "🟠 Fair":      [list: [], enabled: notifyFair      != null ? notifyFair      : true],
        "🟢 Good":      [list: [], enabled: notifyGood      != null ? notifyGood      : false],
        "🟢 Excellent": [list: [], enabled: notifyExcellent != null ? notifyExcellent : false]
    ]
 
    devList.each { device ->
        def lvl = device.currentValue("battery")?.toInteger()
        lvl = lvl != null ? lvl : 100
        def cat = lvl >= 100 ? "🟢 Excellent" : lvl > 70 ? "🟢 Good" : lvl > 25 ? "🟠 Fair" : "🔴 Poor"
        categories[cat].list << [device: device, name: device.displayName.trim(), level: lvl]
    }
 
    categories.each { cat, data ->
        categories[cat].list = data.list.sort { a, b ->
            a.level != b.level ? a.level <=> b.level : a.name <=> b.name
        }
    }
 
    def highDrainList = devList.findAll { device ->
        def h = health(device)
        (h == "Poor" || h == "Fair") && getDrain(device) > 1.5
    }.collect { device ->
        def lvl = device.currentValue("battery")?.toInteger()
        lvl = lvl != null ? lvl : 100
        [name: device.displayName.trim(), level: lvl, health: health(device), drain: displayDrain(device)]
    }.sort { a, b -> a.level != b.level ? a.level <=> b.level : a.name <=> b.name }
 
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
 
    def body = "${prefix}🔋 Battery Summary\n"
 
    def staleDevices = devList.findAll { isStale(it) }.collect {
        def last    = getLastActivityTime(it)
        def timeAgo = last ? formatTimeAgo(last) : "unknown"
        [device: it, name: it.displayName, timeAgo: timeAgo]
    }
 
    categories.each { cat, data ->
        if (data.enabled) {
            body += "\n${cat}:\n"
            if (data.list) {
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
                body += "None\n"
            }
        }
    }
 
    if (notifyHighDrain != null ? notifyHighDrain : true) {
        body += "\n⚠️ High Drain (Fair/Poor):\n"
        if (highDrainList) {
            highDrainList.each { dev ->
                body += "• ${dev.health} (${dev.drain}%) ${dev.name} (${dev.level}%)\n"
            }
        } else {
            body += "None\n"
        }
    }
 
    if (notifyStale != null ? notifyStale : true) {
        body += "\n⚠️ Stale Devices:\n"
        if (staleDevices) {
            staleDevices.each { d ->
                def info    = getCatalogBatteryInfo(d.device)
                def infoStr = info ? " (${info})" : ""
                body += "• ${d.name}${infoStr} — no activity for ${d.timeAgo}\n"
            }
        } else {
            body += "None\n"
        }
    }
 
    if (suppressEmptyReport != null ? suppressEmptyReport : false) {
        def hasContent = categories.any { cat, data -> data.enabled && data.list } ||
            ((notifyHighDrain != null ? notifyHighDrain : true) && highDrainList) ||
            ((notifyStale != null ? notifyStale : true) && staleDevices)
        if (!hasContent) return
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
}
 
// ============================================================
// ===================== BATTERY HANDLER =====================
// ============================================================
def batteryHandler(evt) {
    def device = evt.device
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
            lastLevel:    level != null ? level : 100,
            lastDate:     now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false
        ]
        state.trend[device.id] = "Stable"
        data = state.history[device.id]
    }
 
    if (level == 0) {
        data.justReplaced = false
        data.replacedTime = null
        data.drain        = 1.0
        data.samples      = []
        state.trend[device.id] = "Heavy Drain"
    }
 
    detectReplacement(device, level, data.lastLevel)
 
    def replacedAt = data.replacedTime ?: now()
    if (data.justReplaced && level < 95) {
        data.justReplaced = false
    } else if (data.justReplaced && (now() - safeTime(replacedAt)) > 1000 * 60 * 60 * 24) {
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
            def alpha      = 0.3
            def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : clampedDrain
            def smoothed   = alpha * clampedDrain + (1 - alpha) * prevSmooth
            data.samples << smoothed
            if (data.samples.size() > 10) data.samples.remove(0)
        }
 
        if (data.samples && data.samples.size() > 0) {
            def avg = data.samples.sum() / data.samples.size()
            data.drain = Math.min(avg, 3.0)
            updateTrend(device, data.drain)
        }
    }
 
    data.lastLevel = level
    data.lastDate  = now()
}
 
// ============================================================
// ===================== DETECT REPLACEMENT ==================
// ============================================================
def detectReplacement(device, newLevel, oldLevel) {
    newLevel = newLevel != null ? newLevel : 100
    oldLevel = oldLevel != null ? oldLevel : (state.history[device.id]?.lastLevel != null ? state.history[device.id].lastLevel : 0)
 
    if (!state.history[device.id]) {
        state.history[device.id] = [
            lastLevel:    oldLevel,
            lastDate:     now(),
            drain:        0.3,
            samples:      [],
            justReplaced: false
        ]
        state.trend[device.id] = "Stable"
    }
 
    def data            = state.history[device.id]
    def largeJump       = (newLevel - oldLevel)
    def hadDrainHistory = data?.samples && data.samples.size() >= 2
    def detected        = false
 
    if (newLevel >= 95 && oldLevel <= 40)                                            detected = true
    else if (newLevel >= 90 && oldLevel <= 40 && largeJump >= 25)                    detected = true
    else if (newLevel >= 90 && hadDrainHistory && largeJump >= 15 && oldLevel <= 40) detected = true
    else if (newLevel >= 95 && hadDrainHistory && oldLevel <= 40)                    detected = true
 
    if (detected) {
        logReplacement(device, newLevel, false)
    }
}
 
// ============================================================
// ===================== TREND LOGIC =========================
// ============================================================
def updateTrend(device, drain) {
    if (!device || drain == null) return
 
    def devType = device?.getData()?.deviceType?.toLowerCase() ?: ""
    def isHighReporter = devType.contains("lock") || devType.contains("sensor") ||
                         devType.contains("contact") || devType.contains("smoke") ||
                         devType.contains("carbonmonoxide")
 
    def adjustedDrain = isHighReporter ? drain * 0.5 : drain
    if (adjustedDrain > 5) adjustedDrain = 0.3
 
    def hist = state.history[device.id]
    if (hist?.samples && hist.samples.size() >= 3) {
        def avg = hist.samples.sum() / hist.samples.size()
        if (avg > 3) adjustedDrain = Math.min(adjustedDrain, 1.0)
    }
 
    // Relax thresholds for high-reporter device types so normal
    // lock/sensor drain does not falsely trigger Heavy Drain
    def stableThreshold   = isHighReporter ? 0.6 : 0.3
    def moderateThreshold = isHighReporter ? 1.5 : 0.8
 
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
 
// ============================================================
// ===================== DRAIN / HEALTH HELPERS ==============
// ============================================================
def getDrain(device) {
    def d = state.history?.get(device.id)?.drain
    return d != null ? d : 0.3
}
def displayDrain(device) { return String.format("%.2f", getDrain(device)) }
 
def estDays(device) {
    def level = device.currentValue("battery")?.toInteger()
    level = level != null ? level : 100
    def drain = getDrain(device)
    if (drain <= 0) drain = 0.3
    return Math.round(level / drain)
}
 
def health(device) {
    def hist    = state.history?.get(device.id)
    def samples = hist?.samples?.size() ?: 0
 
    def daysSinceReplaced = 999
    if (hist?.replacedTime) {
        daysSinceReplaced = (now() - safeTime(hist.replacedTime)) / (1000 * 60 * 60 * 24)
    } else if (hist?.lastDate) {
        daysSinceReplaced = (now() - safeTime(hist.lastDate)) / (1000 * 60 * 60 * 24)
    }
 
    def slowReporter = (daysSinceReplaced >= 14 && samples >= 2)
    if (!slowReporter && (samples < 5 || daysSinceReplaced < 5)) return "Pending"
 
    def rawDrain = getDrain(device)
    def devType  = device?.getData()?.deviceType?.toLowerCase() ?: ""
    if (devType.contains("lock") || devType.contains("sensor") || devType.contains("contact") ||
        devType.contains("smoke") || devType.contains("carbonmonoxide")) {
        rawDrain = rawDrain * 0.5
    }
 
    def conf     = getConfidence(device)
    def effDrain = 0.3 + conf * (rawDrain - 0.3)
 
    if (effDrain < 0.3)  return "Excellent"
    if (effDrain <= 0.8) return "Good"
    if (effDrain <= 1.5) return "Fair"
    return "Poor"
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
            drain:        0.3,
            samples:      [],
            justReplaced: false
        ]
        state.history[device.id] = data
        state.trend[device.id]   = "Stable"
    }
    return data
}
 
def getLastBatteryTime(device)  { return safeTime(state.history[device.id]?.lastDate) }
def getLastActivityTime(device) { return safeTime(device.getLastActivity()) }
 
def getCatalogBatteryInfo(device) {
    if (!device) return null
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
 
// ============================================================
// ===================== BATTERY DISPLAY =====================
// ============================================================
def getBatteryLevelDisplay(level, device = null) {
    level = (level instanceof Number ? level : null) != null ? level : 100
 
    def cat = level >= 100 ? "🟢 Excellent" :
              level > 70   ? "🟢 Good" :
              level > 25   ? "🟠 Fair" :
                             "🔴 Poor"
 
    def label = "${cat} (${level}%)"
 
    def data         = (device && state.history?.containsKey(device.id)) ? safeHistory(device) : null
    def showTag      = data?.justReplaced == true
    def replacedTime = data?.replacedTime
 
    if (showTag) {
        replacedTime = safeTime(replacedTime)
        def hoursSinceReplacement = (now() - replacedTime) / (1000 * 60 * 60)
        if (hoursSinceReplacement >= 24) {
            if (data) data.justReplaced = false
            showTag = false
        }
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
            drain:        0.3,
            samples:      [],
            justReplaced: false
        ]
        data = state.history[device.id]
        state.trend[device.id] = "Stable"
    }
 
    data.drain        = 0.3
    data.samples      = []
    data.lastLevel    = newLevel
    data.lastDate     = now()
    data.justReplaced = true
    data.replacedTime = now()
    state.trend[device.id]     = "Stable"
    data.lastReplacementLogged = now()
 
    state.replacements = state.replacements?.findAll { it.device != device.displayName } ?: []
    state.replacements << [
        device: device.displayName,
        level:  newLevel,
        date:   new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
        type:   manual ? "manual" : "auto"
    ]
    state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }
}
 
// ============================================================
// ===================== SUMMARY PAGE ========================
// ============================================================
def summaryPage() {
    dynamicPage(name: "summaryPage", title: "Battery Summary", install: false) {
 
 
        if (!state.history || !autoDevices || autoDevices.size() == 0) {
            section("Setup Required") {
                paragraph "⚠ <b>Setup Not Complete</b><br><br>" +
                          "You must click <b>Done</b> after selecting your devices before viewing reports.<br><br>" +
                          "Please exit the app and reopen it, then try again."
            }
            return
        }
 
        def hubIp = location?.hub?.localIP ?: ""
 
        section("") {
            paragraph "<span style='color:red; font-weight:bold;'>⚠ Device links below are accessible only on your local network (LAN). They will not work remotely.</span>"
            paragraph "<span style='color:red; font-weight:bold;'>¹ Last Battery</span> shows when this app last received a battery reading — from a scheduled scan or device event. It is independent of Last Activity. A device can be active recently but still show an old Last Battery timestamp if its battery level has not changed or reported."
            href "forceScanPage", title: "🔄 Force Scan Now", description: "Tap to immediately read battery levels from all monitored devices"
 
            def devList = (autoDevices ?: []).findAll {
                try { it?.currentValue("battery") != null } catch (e) {
                    log.warn "Error checking battery capability for ${it?.displayName}: ${e.message}"
                    return false
                }
            }
 
            devList = devList.sort { a, b ->
                def levelA = null; def levelB = null
                try { levelA = a.currentValue("battery")?.toInteger() } catch (e) {
                    log.warn "Error getting battery level for ${a.displayName}: ${e.message}"
                }
                try { levelB = b.currentValue("battery")?.toInteger() } catch (e) {
                    log.warn "Error getting battery level for ${b.displayName}: ${e.message}"
                }
                levelA = levelA != null ? levelA : 100
                levelB = levelB != null ? levelB : 100
                levelA != levelB ? levelA <=> levelB : (a.displayName ?: "") <=> (b.displayName ?: "")
            }
 
            if (!devList) { paragraph "No battery devices found."; return }
 
            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Battery</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Drain %/day</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Est Days</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Health</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Last Battery¹</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Last Activity</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Battery Type</td>"
            table += "</tr>"
            def summaryRowNum = 0
 
            devList.each { device ->
                def level = null
                try { level = device.currentValue("battery")?.toInteger() } catch (e) {
                    log.warn "summaryPage: Error getting battery level for ${device.displayName}: ${e.message}"
                }
                level = level != null ? level : 100
 
                def catalogInfo = ""
                try { catalogInfo = getCatalogBatteryInfo(device) ?: "" } catch (e) {
                    log.warn "summaryPage: Error getting catalog info for ${device.displayName}: ${e.message}"
                }
 
                def drain = 0.3
                try { drain = getDrain(device) } catch (e) {
                    log.warn "summaryPage: Error getting drain for ${device.displayName}: ${e.message}"
                }
 
                def est = 0
                try { est = estDays(device) } catch (e) {
                    log.warn "summaryPage: Error getting estimated days for ${device.displayName}: ${e.message}"
                }
 
                def h = "Unknown"
                try { h = health(device) } catch (e) {
                    log.warn "summaryPage: Error getting health for ${device.displayName}: ${e.message}"
                }
 
                def sampleCount = 0
                try { sampleCount = state.history?.get(device.id)?.samples?.size() ?: 0 } catch (e) {
                    log.warn "summaryPage: Error getting sample count for ${device.displayName}: ${e.message}"
                }
 
                def lastBatteryStr = "N/A"
                try { lastBatteryStr = formatTimeAgo(getLastBatteryTime(device)) } catch (e) {
                    log.warn "summaryPage: Error getting last battery time for ${device.displayName}: ${e.message}"
                }
 
                def lastActivityStr = "N/A"
                def lastActivity    = null
                try { lastActivity = device.getLastActivity() } catch (e) {
                    log.warn "summaryPage: Error getting last activity for ${device.displayName}: ${e.message}"
                }
                if (lastActivity) {
                    try { lastActivityStr = formatTimeAgo(safeTime(lastActivity)) } catch (e) {
                        log.warn "summaryPage: Error formatting last activity for ${device.displayName}: ${e.message}"
                    }
                }
 
                def stale = false
                try { stale = isStale(device) } catch (e) {
                    log.warn "summaryPage: Error checking stale status for ${device.displayName}: ${e.message}"
                }
 
                def color = ""
                try { color = getBatteryLevelDisplay(level, device) } catch (e) {
                    log.warn "summaryPage: Error getting battery display for ${device.displayName}: ${e.message}"
                    color = "${level}%"
                }
 
                def staleTag = ""
                if (stale && lastActivity) {
                    try {
                        staleTag = " ⚠️ Stale (${formatTimeAgo(safeTime(lastActivity))})"
                    } catch (e) {
                        log.warn "summaryPage: Error building stale tag for ${device.displayName}: ${e.message}"
                    }
                }
 
                def healthDisplay = h
                if (h == "Pending") {
                    healthDisplay = "⏳ Pending (${sampleCount}/5 samples)"
                }
 
                def name = device.displayName ?: "Unknown Device"
                def summaryRowBg = (summaryRowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                summaryRowNum++
                table += "<tr style='background-color:${summaryRowBg};'>"
 
                if (hubIp) {
                    table += "<td style='padding:4px; border:1px solid #ccc;'><a href='http://${hubIp}/device/edit/${device.id}' target='_blank'>${name}</a></td>"
                } else {
                    table += "<td style='padding:4px; border:1px solid #ccc;'>${name}</td>"
                }
 
                table += "<td style='padding:4px; border:1px solid #ccc;'>${color}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${String.format('%.2f', drain)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${est}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${healthDisplay}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastBatteryStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${lastActivityStr}${staleTag}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${catalogInfo}</td>"
                table += "</tr>"
            }
 
            table += "</table>"
 
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }
    }
}
 
// ============================================================
// ===================== TRENDS PAGE =========================
// ============================================================
def trendsPage() {
    dynamicPage(name: "trendsPage", title: "Battery Trends", install: false) {
 
 
        section("") {
            href "forceScanPage", title: "🔄 Force Scan Now", description: "Tap to immediately read battery levels from all monitored devices"
            def devList = (autoDevices ?: []).findAll { it?.currentValue("battery") != null }
 
            if (!devList) { paragraph "No battery devices found for trends."; return }
 
            def trendPriority = ["Heavy Drain": 1, "Moderate": 2, "Stable": 3]
 
            devList = devList.sort { a, b ->
                def trendA = state.trend[a.id] ?: "Stable"
                def trendB = state.trend[b.id] ?: "Stable"
                def prioA  = trendPriority[trendA] ?: 3
                def prioB  = trendPriority[trendB] ?: 3
                if (prioA != prioB) return prioA <=> prioB
                def levelA = a.currentValue("battery")?.toInteger() ? a.currentValue("battery")?.toInteger() : 100
                def levelB = b.currentValue("battery")?.toInteger() ? b.currentValue("battery")?.toInteger() : 100
                if (levelA != levelB) return levelA <=> levelB
                return a.displayName.trim() <=> b.displayName.trim()
            }
 
            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Battery</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Trend</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Drain %/day</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Health</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Confidence</td>"
            table += "</tr>"
            def trendsRowNum = 0
 
            devList.each { device ->
                def hist    = safeHistory(device)
                def level   = device.currentValue("battery")?.toInteger() ? device.currentValue("battery")?.toInteger() : 100
                def drain   = hist?.drain != null ? hist.drain : 0.3
                def trend   = state.trend[device.id] ?: "Unknown"
                def h       = health(device)
                def conf    = getConfidence(device)
                def samples = hist?.samples?.size() ?: 0
 
                def trendColor = trend == "Heavy Drain" ? "🔴" : trend == "Moderate" ? "🟠" : "🟢"
                def color      = getBatteryLevelDisplay(level, device)
                def confPct    = Math.round(conf * 100)
                def confLabel  = h == "Pending" ? "⏳ ${samples}/5 samples" : "${confPct}%"
 
                def trendsRowBg = (trendsRowNum % 2 == 0) ? "#ffffff" : "#ebebeb"
                trendsRowNum++
                table += "<tr style='background-color:${trendsRowBg};'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${device.displayName}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${color}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${trendColor} ${trend}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${String.format('%.2f', drain)}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${h}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${confLabel}</td>"
                table += "</tr>"
            }
 
            table += "</table>"
 
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
        }
    }
}
 
// ============================================================
// ===================== HISTORY PAGE ========================
// ============================================================
def historyPage() {
    dynamicPage(name: "historyPage", title: "Battery Replacement History", install: false) {
 
 
        section("") {
            if (!state.replacements || state.replacements.size() == 0) {
                paragraph "No battery replacements have been logged yet."
                return
            }
 
            def table = "<table style='width:100%; border-collapse: collapse; border: 1px solid #ccc;'>"
            table += "<tr style='font-weight:bold; background-color:#f0f0f0;'>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Device</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Battery Type</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Level</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Date</td>"
            table += "<td style='padding:4px; border:1px solid #ccc;'>Type?</td>"
            table += "</tr>"
 
            state.replacements.sort { a, b -> b.date <=> a.date }.eachWithIndex { r, idx ->
                def historyRowBg = (idx % 2 == 0) ? "#ffffff" : "#ebebeb"
                def typeTag = r.type == "manual" ? "<span style='color:blue;'>M</span>" :
                              r.type == "auto"   ? "<span style='color:green;'>A</span>" : "?"
                def dev     = autoDevices?.find { it.displayName == r.device }
                def info    = dev ? getCatalogBatteryInfo(dev) : null
                def infoStr = info ? "${info}" : ""
 
                table += "<tr style='background-color:${historyRowBg};'>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${r.device}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${infoStr}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${r.level}%</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${r.date}</td>"
                table += "<td style='padding:4px; border:1px solid #ccc;'>${typeTag}</td>"
                table += "</tr>"
            }
 
            table += "</table>"
 
            paragraph "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'>${table}</div>"
            paragraph "<b>Legend:</b> <span style='color:green;'>A</span> = Automatic, <span style='color:blue;'>M</span> = Manual"
        }
 
        section("<b>Delete an Entry</b>") {
            href "deleteHistoryPage", title: "🗑️ Delete a History Entry"
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
            state.replacements.sort { a, b -> b.date <=> a.date }.eachWithIndex { r, i ->
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
                href "deleteHistoryConfirmPage", title: "Submit"
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
                def sorted = state.replacements.sort { a, b -> b.date <=> a.date }
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
// ===================== MANUAL REPLACEMENT PAGE =============
// ============================================================
def manualReplacementPage() {
    if (state.replacements == null) state.replacements = []
    def devList = autoDevices ?: []
 
    app.removeSetting("replaceDevicesManual")
    app.updateSetting("replaceConfirmManual", [value: false, type: "bool"])
 
    dynamicPage(name: "manualReplacementPage", title: "Manual Battery Replacement", install: false) {
        section("<b>Select Devices</b>") {
            if (!devList || devList.size() == 0) {
                paragraph "⚠ No battery devices available. Please select devices on the main page first."
            } else {
                input "replaceDevicesManual", "enum",
                      title: "Select Devices (can choose multiple)",
                      options: devList.collectEntries { [(it.id): it.displayName] }
                                      .sort { a, b -> a.value <=> b.value },
                      multiple: true,
                      required: false
            }
        }
        section("<b>Confirm Replacement</b>") {
            input "replaceConfirmManual", "bool",
                  title: "Confirm Battery Replaced",
                  required: false
        }
        section() {
            href "manualReplacementConfirmPage", title: "Submit Replacement"
        }
    }
}
 
// ============================================================
// ================ MANUAL REPLACEMENT CONFIRM PAGE ==========
// ============================================================
def manualReplacementConfirmPage() {
    if (state.replacements == null) state.replacements = []
    def devList = autoDevices ?: []
 
    dynamicPage(name: "manualReplacementConfirmPage", title: "Confirm Replacement", install: false) {
        section("<b>Replacement Registered</b>") {
            if (replaceDevicesManual && replaceConfirmManual) {
                def successCount = 0
 
                replaceDevicesManual.each { deviceId ->
                    def device = devList.find { it.id == deviceId }
                    if (device) {
                        def level = device.currentValue("battery")?.toInteger()
                        level = level != null ? level : 100
 
                        if (!state.history[device.id]) {
                            state.history[device.id] = [
                                lastLevel: level,
                                lastDate:  now(),
                                drain:     0.3,
                                samples:   []
                            ]
                        }
 
                        state.replacements = state.replacements?.findAll { it.device != device.displayName } ?: []
                        state.replacements << [
                            device: device.displayName,
                            level:  level,
                            date:   new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
                            type:   "manual"
                        ]
                        state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }
 
                        state.history[device.id].drain        = 0.3
                        state.history[device.id].samples      = []
                        state.trend[device.id]                = "Stable"
                        state.history[device.id].lastLevel    = level
                        state.history[device.id].lastDate     = now()
                        state.history[device.id].justReplaced = true
                        state.history[device.id].replacedTime = now()
 
                        successCount++
                    }
                }
 
                if (successCount > 0) {
                    paragraph "✅ Battery replacement for ${successCount} device(s) has been recorded. Health will show ⏳ Pending for approximately 5 days while fresh data is collected."
                } else {
                    paragraph "❌ No valid devices found. Please select devices on the main page first."
                }
            } else {
                paragraph "⚠ Please select device(s) and confirm replacement or use the Back button to cancel."
            }
        }
    }
}
 
// ============================================================
// ============= SEND NOTIFICATION PAGE ======================
// ============================================================
def sendNotificationPage() {
    dynamicPage(name: "sendNotificationPage", title: "Send Notification", install: false) {
 
        def devList      = autoDevices ?: []
        def hasDevices   = devList.size() > 0
        def hasTargets   = (settings?.notifyDevices?.size() ?: 0) > 0 ||
                           (settings?.pushoverDevices?.size() ?: 0) > 0 ||
                           (settings?.enablePush == true)
        def notifyOn     = settings?.enablePush != false
 
        if (!hasDevices) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ No monitored devices are selected. Please go back to the main page, select devices, and tap Done before sending a notification."
            }
            return
        }
 
        if (!notifyOn) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ Notifications are turned off. Enable the Notifications toggle on the main page before sending."
            }
            return
        }
 
        if (!hasTargets) {
            section("<b>Cannot Send</b>") {
                paragraph "⚠️ No notification devices are configured. Add at least one notification device on the main page before sending."
            }
            return
        }
 
        section("<b>Confirm</b>") {
            paragraph "This will send a battery summary notification to all configured notification devices right now."
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
// ===================== FORCE SCAN PAGE =====================
// ============================================================
def forceScanPage() {
    scanAllDevices()
    if (debugMode) log.debug "Manual battery scan triggered by user"
 
    dynamicPage(name: "forceScanPage", title: "Force Scan", install: false) {
        section("<b>Scan Complete</b>") {
            def devList = autoDevices ?: []
            def count   = devList.size()
            paragraph "✅ Battery scan complete — ${count} device(s) read. " +
                      "Return to Battery Summary or Trends to see updated values.<br><br>" +
                      "<b>Note:</b> A new drain sample is only recorded if the battery level " +
                      "has changed since the last reading. Devices reporting the same level " +
                      "will not generate a new sample."
        }
    }
}
 
// ============================================================
// ===================== BATTERY CATALOG PAGE ================
// ============================================================
def batteryCatalogPage() {
    dynamicPage(name: "batteryCatalogPage", title: "🔋 Battery Catalog", install: false) {
 
 
        def devList = (autoDevices ?: []).sort { a, b -> a.displayName.trim() <=> b.displayName.trim() }
 
        if (!devList) {
            section() { paragraph "No devices found. Please select devices on the main page first." }
            return
        }
 
        def standardTypes = [
            "AA":       ["1", "2", "3", "4", "6", "8"],
            "AAA":      ["1", "2", "3", "4", "6", "8"],
            "CR2":      ["1", "2"],
            "CR1632":   ["1"],
            "CR2016":   ["1", "2"],
            "CR2032":   ["1", "2"],
            "CR2430":   ["1", "2"],
            "CR2450":   ["1", "2"],
            "CR2477":   ["1"],
            "CR123A":   ["1", "2"],
            "9V":       ["1"],
            "ER14250":  ["1", "2"],
            "LS14250":  ["1", "2"]
        ]
        def rechargeableTypes = [
            "Rechargeable AA":  ["1", "2", "3", "4", "6", "8"],
            "Rechargeable AAA": ["1", "2", "3", "4", "6", "8"],
            "LIR2016": ["1", "2"],
            "LIR2032": ["1", "2"],
            "LIR2430": ["1", "2"],
            "LIR2450": ["1"],
            "18650":   ["1", "2", "3", "4"]
        ]
        def otherTypes = [
            "Other": ["1", "2", "3", "4", "6", "8"]
        ]
 
        def options = ["": "— Not Set —"]
 
        options["_sep1"] = "──────── Standard ────────"
        standardTypes.each { type, quantities ->
            quantities.each { qty ->
                def key = "${type} x${qty}"
                options[key] = key
            }
        }
 
        options["_sep2"] = "──────── Rechargeable ────────"
        rechargeableTypes.each { type, quantities ->
            quantities.each { qty ->
                def key = "${type} x${qty}"
                options[key] = key
            }
        }
 
        options["_sep3"] = "──────── Other ────────"
        otherTypes.each { type, quantities ->
            quantities.each { qty ->
                def key = "${type} x${qty}"
                options[key] = key
            }
        }
 
        section("") {
            paragraph "<span style='color:red; font-weight:bold;'>⚠ Select a battery type for each device below. Tap Done to save your selections — this is a one-time setup.</span>"
        }
 
        section("<b>Battery Catalog</b>") {
            devList.each { device ->
                section() {
                    input "battInfo_${device.id}",
                          "enum",
                          title: "<b>${device.displayName.trim()}</b>",
                          options: options,
                          required: false,
                          defaultValue: settings["battInfo_${device.id}"] ?: ""
                }
            }
        }
    }
}
 
// ============================================================
// ===================== INFO PAGE ===========================
// ============================================================
def infoPage(Map params = [:]) {
    dynamicPage(name: "infoPage", title: "App Guide & Reference", install: false) {
 
 
        section("<b>🔑 Color & Icon Legend</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Battery level colors reflect current charge percentage. " +
                      "Health ratings use the same color scheme but are based on drain rate — not battery percentage. " +
                      "A device can show 🟢 Good battery level yet 🔴 Poor health if it is draining unusually fast.<br><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Icon</td><td>Color</td><td>Meaning</td></tr>" +
                      "<tr><td>🟢</td><td>Green</td><td>Good or Excellent — healthy battery or low drain</td></tr>" +
                      "<tr><td>🟠</td><td>Orange</td><td>Fair — moderate drain, worth monitoring</td></tr>" +
                      "<tr><td>🔴</td><td>Red</td><td>Poor or Heavy Drain — high drain or possible issue</td></tr>" +
                      "<tr><td>⏳</td><td>—</td><td>Pending — not enough data yet to assign a health verdict</td></tr>" +
                      "<tr><td>⚠️</td><td>—</td><td>Warning — high drain device or stale activity</td></tr>" +
                      "<tr><td>⏱</td><td>—</td><td>Stale — device has not reported within the configured threshold</td></tr>" +
                      "</table></div></div>"
        }
 
        section("<b>🔋 Battery Health & Trends</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Health is a long-term confidence-weighted average drain rate. " +
                      "Trend uses the same thresholds but reacts faster to recent readings — a short spike may push Trend to 🔴 Heavy Drain " +
                      "while Health stays 🟢 Good until enough samples confirm the pattern.<br><br>" +
                      "<div style='overflow-x:auto; -webkit-overflow-scrolling:touch;'><table style='width:100%; border-collapse: collapse;'>" +
                      "<tr style='font-weight:bold;'><td>Health</td><td>Trend</td><td>Drain/day</td><td>What It Means</td></tr>" +
                      "<tr><td>⏳ Pending</td><td>—</td><td>—</td><td>Not enough data yet</td></tr>" +
                      "<tr><td>🟢 Excellent</td><td>🟢 Stable</td><td>&lt;= 0.3%</td><td>Very efficient, minimal drain</td></tr>" +
                      "<tr><td>🟢 Good</td><td>🟠 Moderate</td><td>0.3–0.8%</td><td>Normal battery usage</td></tr>" +
                      "<tr><td>🟠 Fair</td><td>🔴 Heavy Drain</td><td>0.8–1.5%</td><td>Above average — worth monitoring, no alert</td></tr>" +
                      "<tr><td>🔴 Poor</td><td>🔴 Heavy Drain</td><td>&gt; 1.5%</td><td>High drain — High Drain alert fires</td></tr>" +
                      "</table></div></div>"
        }
 
        section("<b>⏳ Pending Health, Samples & Confidence</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>The app withholds a health verdict and shows <b>⏳ Pending</b> until enough data is collected. " +
                      "This prevents false Poor ratings from sparse early readings.<br><br>" +
                      "<b>Standard gate</b> — both must be met:<br>" +
                      "• At least <b>5 samples</b> collected (shown as X/5 in the Health column)<br>" +
                      "• At least <b>5 days</b> since the battery was replaced or first seen<br><br>" +
                      "<b>Slow reporter gate</b> — for devices like smoke detectors that rarely report:<br>" +
                      "• After <b>14 days</b> with at least <b>2 samples</b>, Pending clears automatically<br>" +
                      "• A stable reading held for 24+ hours counts as a valid zero-drain sample<br><br>" +
                      "<b>Confidence weighting:</b> Early readings carry less weight than established ones. " +
                      "With 5 samples the blend is partial — by 10 samples the full measured drain is used. " +
                      "A single unusual reading cannot spike a device straight to Poor.</div>"
        }
 
        section("<b>🔍 Drain, Estimated Days & Last Battery</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Drain shows how fast a device uses battery (% per day). " +
                      "Calculated using EWMA (exponential weighted moving average) across the last 10 readings — " +
                      "recent readings matter slightly more but a single spike won't throw off the average.<br><br>" +
                      "<b>Estimated days remaining</b> = current level ÷ average daily drain. " +
                      "Works best after 7+ days of history. Devices showing Pending will have less reliable estimates.<br><br>" +
                      "<b>Last Battery</b> shows when the app last received a battery reading — from a scheduled scan or device event. " +
                      "It is independent of Last Activity. A device can be recently active but show an old Last Battery timestamp " +
                      "if its battery level has not changed or reported. This is normal.</div>"
        }
 
        section("") {
            paragraph rawHtml: true, "<div style='background-color:#e8f0fe; padding:6px 10px; border-radius:4px; font-weight:bold;'>⚙️ Features & Actions</div>"
        }
 
        section("<b>🔄 Force Scan & 🔁 Replacement Detection</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'><b>Force Scan Now</b> on the Summary and Trends pages immediately reads battery levels from all devices " +
                      "instead of waiting for the next scheduled scan.<br><br>" +
                      "<b>Force Scan does NOT instantly generate drain samples.</b> A sample requires the battery level to have " +
                      "changed since the last reading, or 24+ hours to have passed at the same level. " +
                      "The best way to build samples faster is to set Scan Interval to Hourly.<br><br>" +
                      "<b>Replacement detection</b> fires automatically when a device jumps from ≤40% up to ≥90–95%. " +
                      "If replaced before dropping to 40%, log it manually to keep trends accurate.<br>" +
                      "After any replacement, drain history resets and the device returns to ⏳ Pending. " +
                      "The Recently Replaced tag clears after 24 hours.</div>"
        }
 
        section("") {
            paragraph rawHtml: true, "<div style='background-color:#e8f0fe; padding:6px 10px; border-radius:4px; font-weight:bold;'>🔔 Alerts & Monitoring</div>"
        }
 
        section("<b>⚠️ High Drain Alerts & ⏱ Stale Devices</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'><b>High Drain alert</b> fires when a device crosses into 🔴 Poor territory — drain exceeds 1.5%/day. " +
                      "The alert and Health column always agree: if you see the alert, the device shows Poor.<br>" +
                      "🟠 Fair devices (0.8–1.5%/day) are worth monitoring but do not trigger an alert.<br><br>" +
                      "<b>Stale</b> means a device has not reported any activity within the configured threshold (default 24h). " +
                      "Stale is based on Last Activity — not Last Battery. Check stale devices for connectivity issues.</div>"
        }
 
        section("<b>🔔 Notification Schedule (2, 3, Weekly)</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>" +
                      "<b>Notification frequency options</b> (2, 3, Weekly) determine how often alert summaries are sent based on accumulated alert conditions.<br><br>" +
                      "<b>2 / 3 (interval-based notifications):</b><br>" +
                      "• Notifications are evaluated on a rolling interval (every 2 or 3 days depending on selection)<br>" +
                      "• The first notification is sent the day after the condition is first detected<br>" +
                      "• Subsequent notifications follow the configured interval as long as the condition persists<br><br>" +
                      "<b>Weekly notifications:</b><br>" +
                      "• Notifications are anchored to a weekly cycle starting on <b>Monday</b><br>" +
                      "• The first notification is sent the day after the condition is detected<br>" +
                      "• After the initial notification, the system aligns to the weekly schedule and continues sending on the next Monday cycle if conditions remain<br><br>" +
                      "<b>General behavior:</b><br>" +
                      "• Notifications are not duplicated within the same interval window<br>" +
                      "• Clearing a condition and re-triggering it will restart the notification cycle<br>" +
                      "• Scheduling ensures a consistent cadence while avoiding immediate repeated alerts</div>"
        }
 
        section("") {
            paragraph rawHtml: true, "<div style='background-color:#e8f0fe; padding:6px 10px; border-radius:4px; font-weight:bold;'>🛠 Help & Troubleshooting</div>"
        }
 
        section("<b>⚙️ Device Adjustments & Troubleshooting</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>Some device types have adjusted drain calculations to avoid overestimating their drain rate:<br>" +
                      "• <b>Locks, sensors, contact sensors</b> — drain adjusted down by 50%<br>" +
                      "• <b>Smoke & CO detectors</b> — drain adjusted down by 50%, slow reporter gate applied<br>" +
                      "• <b>Motion sensors</b> — covered under sensors above<br>" +
                      "• Other types use the standard calculation<br><br>" +
                      "<b>If a device shows High Drain or Poor health after Pending:</b><br>" +
                      "• Check signal strength and mesh routing<br>" +
                      "• Verify reporting frequency in the device driver<br>" +
                      "• Confirm correct battery type in the Battery Catalog<br>" +
                      "• Consider environmental factors (temperature, distance)<br>" +
                      "• Devices reporting every few minutes may show higher apparent drain — compare with similar devices</div>"
        }
 
        section("<b>💡 Tips for Best Results</b>") {
            paragraph rawHtml: true, "<div style='background-color:#f8f8f8; border:1px solid #dddddd; border-radius:6px; padding:10px; margin-bottom:4px;'>• Let new batteries run for at least a week before trusting health ratings<br>" +
                      "• Use the Battery Catalog to log battery types — helps with replacement planning<br>" +
                      "• Set Scan Interval to Hourly to build health ratings faster<br>" +
                      "• After replacing a battery use Manual Replacement to reset history immediately<br>" +
                      "• Consistent low drain over time = healthy, well-placed device</div>"
        }
    }
}
