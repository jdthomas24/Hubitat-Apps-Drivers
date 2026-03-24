// ============================================================
// Battery Monitor 2.0
// Version 2.3.6
// Author: Jdthomas24
// Namespace: jdthomas24
// Description: Advanced Hubitat battery monitoring with analytics, trends and replacement tracking (v2.3.2). Auto-adjusts drain for low-activity devices.
// ============================================================

definition(
    name: "Battery Monitor 2.0", // DO NOT CHANGE (HPM SAFE)
    namespace: "jdthomas24",
    author: "Jdthomas24",
    description: "Advanced Hubitat battery monitoring with analytics, trends and replacement tracking (v2.3.2). Auto-adjusts drain for low-activity devices.",
    category: "Convenience",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/examples/icons/battery.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/examples/icons/battery@2x.png",
    iconX3Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/examples/icons/battery@2x.png",
    version: "2.3.6"
)
def installed() {
    log.debug "Installed - initializing app"
    initialize()
}

def updated() {
    log.debug "Updated - re-initializing app"
    unschedule()
    unsubscribe()
    initialize()

    // Clean up state for devices that have been removed
    def currentIds = autoDevices?.collect { it.id as String } ?: []

    state.history?.keySet()?.findAll { !currentIds.contains(it) }?.each { removedId ->
        state.history.remove(removedId)
        state.trend?.remove(removedId)
        log.debug "Cleaned up removed device: ${removedId}"
    }

    // Also remove replacement history for devices no longer monitored
    //if (state.replacements) {
    //    def currentNames = autoDevices?.collect { it.displayName } ?: []
    //    state.replacements = state.replacements.findAll { currentNames.contains(it.device) }
    //}

    // Fire test notification if toggle was set, then reset it
    if (settings?.sendTestNow) {
        scheduledSummary()
        app.updateSetting("sendTestNow", [value: false, type: "bool"])
    }
}

def initialize(){
    log.debug "Initialization complete"

    // Schedule automatic reports
    scheduleReportFrequency()

    // Subscribe to battery events for all selected devices
    if(autoDevices){
        subscribe(autoDevices, "battery", batteryHandler)
    }
}

preferences {
    page(name:"mainPage")
    page(name:"summaryPage")
    page(name:"trendsPage")
    page(name:"historyPage")
    page(name:"manualReplacementPage")
    page(name:"manualReplacementConfirmPage")
    page(name:"infoPage")
}


// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
def mainPage() {
    // Update app label if custom name provided
    if(settings?.customAppName) {
        if(app.label != settings.customAppName) {
            app.updateLabel(settings.customAppName)
        }
    }

    dynamicPage(name: "mainPage",
                title: (settings?.customAppName ?: "Battery Monitor 2.0"),
                install: true, uninstall: true) {

        // ================= App Display Name =================
        section("App Display Name (optional)") {
            input "customAppName", "text",
                  title: "Custom App Name",
                  description: "Change how the app name appears in Hubitat UI",
                  required: false
        }

        // ================= Auto Battery Discovery =============
section("Auto Battery Discovery") {
    paragraph "<b>⚠ Important: The app automatically detects all devices reporting battery levels. " +
          "Select the devices you want to monitor from the list below. Only selected devices will be tracked for trends, battery health, and notifications.</b>"

    // ⚠ Mobile note for long device names
    paragraph "<span style='color:red; font-weight:bold;'>Note for mobile users:</span> If your device names are long, they may extend past the screen in the selection list. This is a UI limitation on smaller screens. You can still select devices as usual."

    paragraph "<span style='color:red; font-weight:bold;'>IMPORTANT: After selecting devices, you MUST click 'Done' to exit the app BEFORE viewing the battery report. Skipping this step may cause an error.</span>"

    // Existing input logic stays exactly the same
    input "autoDevices", "capability.battery",
          title: "Select battery devices to monitor",
          multiple: true,
          required: true

    // Initialize state.history and state.trend for selected devices
    if(autoDevices) {
        if(!state.history) state.history = [:]
        if(!state.trend) state.trend = [:]
        autoDevices.each { device ->
            if(!state.history[device.id]) {
                state.history[device.id] = [
                    lastLevel: device.currentValue("battery")?.toInteger() ?: 100,
                    lastDate: now(),
                    drain: 0.3,
                    samples: [],
                    justReplaced: false
                ]
                state.trend[device.id] = "Stable"
            }
        }
    }
}
        // ================= Battery Scan Interval =================
        section("Battery Scan Interval") {
            input "scanInterval", "enum",
                  title: "Scan Frequency",
                  options: ["1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3"
        }

        // ================= Report Settings =================
        section("Report Settings") {
            input "reportFrequency", "enum",
                  title: "Report Frequency",
                  options: [
                      "daily": "Daily",
                      "every2": "Every 2 Days",
                      "every3": "Every 3 Days",
                      "weekly": "Weekly",
                      "critical": "Only When Critical"
                  ],
                  defaultValue: "daily"

            input "summaryTime", "time",
                  title: "Report Time (ignored when set to Critical Only — alerts are event-driven)",
                  required: false
        }

        // ================= Notifications =================
        section("Notifications") {
            input "enablePush", "bool", title: "Enable notifications", defaultValue: true
            input "notifyDevices", "capability.notification", title: "Notification devices", multiple: true, required: false
			
			paragraph "<b>Report Sections (choose which battery groups to include in notifications):</b>"
			input "notifyPoor",      "bool", title: "🔴 Include Poor (≤25%)",    defaultValue: true
			input "notifyFair",      "bool", title: "🟠 Include Fair (26–70%)",   defaultValue: true
			input "notifyGood",      "bool", title: "🟢 Include Good (71–99%)",   defaultValue: false
			input "notifyExcellent", "bool", title: "🟢 Include Excellent (100%)", defaultValue: false
			input "notifyHighDrain", "bool", title: "⚠️ Include High Drain (drain ≥ 0.7%/day)", defaultValue: true

			paragraph "<b>Test notification:</b>"
			input "sendTestNow", "bool", title: "📤 Send Test Notification Now (tap, then click Done)", defaultValue: false
        }

        // ================= Reports =================
        section("Reports") {
            href "summaryPage", title: "Battery Summary"
            href "trendsPage", title: "Battery Trends"
            href "historyPage", title: "Battery Replacement History"
            href "manualReplacementPage", title: "Manual Battery Replacement"
        }

        // ================= Help & Info =================
        section("Help & Info") {
            href "infoPage",
                 title: "Battery Health Guide",
                 description: "Learn what battery drain, health, and trends mean"
        }
    }
}

// ============================================================
// ===================== REPORT SCHEDULING ==================
// ============================================================
def scheduleReportFrequency(){
    unschedule("reportScheduler")
    if(reportFrequency == "critical") return
    if(!summaryTime) return
    schedule(summaryTime, reportScheduler)
}

def reportScheduler(){
    switch(reportFrequency){
        case "daily": scheduledSummary(); break
        case "every2": if(shouldRunEveryXDays(2)) scheduledSummary(); break
        case "every3": if(shouldRunEveryXDays(3)) scheduledSummary(); break
        case "weekly": if(shouldRunWeekly()) scheduledSummary(); break
    }
}

def shouldRunEveryXDays(daysInterval){
    def today = new Date().clearTime()
    def lastRun = state.lastReportRun ? new Date(state.lastReportRun).clearTime() : null
    if(!lastRun){ state.lastReportRun = now(); return true }
    def diff = (today.time - lastRun.time) / (1000*60*60*24)
    if(diff >= daysInterval){ state.lastReportRun = now(); return true }
    return false
}

def shouldRunWeekly(){
    def today = new Date()
    def lastRun = state.lastReportRun ? new Date(state.lastReportRun) : null
    if(!lastRun){ state.lastReportRun = now(); return true }
    if(today.format("u") == "1"){  // Monday
        def diff = (today.time - lastRun.time) / (1000*60*60*24)
        if(diff >= 7){ state.lastReportRun = now(); return true }
    }
    return false
}

def scheduledSummary() {
    def devs = (autoDevices ?: []).findAll { it?.currentValue("battery") != null }
    if (!devs) return

    // Categorize devices by battery percentage using same color codes
    // Map category name → [list of devices, toggle setting]
    def categories = [
        "🔴 Poor":      [list: [], enabled: notifyPoor      ?: true],
        "🟠 Fair":      [list: [], enabled: notifyFair      ?: true],
        "🟢 Good":      [list: [], enabled: notifyGood      ?: false],
        "🟢 Excellent": [list: [], enabled: notifyExcellent ?: false]
    ]

    devs.each { device ->
        def lvl = device.currentValue("battery")?.toInteger() ?: 100
        def cat = lvl <= 25 ? "🔴 Poor" : lvl <= 70 ? "🟠 Fair" : lvl <= 100 ? "🟢 Good" : "🟢 Excellent"
        categories[cat].list << [device: device, name: device.displayName, level: lvl]
    }

    // Sort each category by battery percentage (lowest first)
    categories.each { cat, data ->
        categories[cat].list = data.list.sort { it.level }
    }

    // ---- High Drain Section ----
    // Flags any device whose health() is NOT Excellent (i.e. Good, Fair, or Poor drain rate)
    def highDrainList = devs.findAll { device ->
        getDrain(device) >= 0.7
    }.collect { device ->
        def lvl = device.currentValue("battery")?.toInteger() ?: 100
        [name: device.displayName, level: lvl, health: health(device), drain: displayDrain(device)]
    }.sort { it.level }

    // ---- Build message — only include enabled categories ----
    def msg = "🔋 Battery Summary\n"
    def hasContent = false

    // Battery level sections
    categories.each { cat, data ->
        if (data.enabled && data.list) {
            msg += "\n${cat} (${data.list.size()} devices):\n"
            data.list.each { dev -> msg += "• ${dev.name} (${dev.level}%)\n" }
            hasContent = true
        }
    }

    // High drain section
    if ((notifyHighDrain ?: true) && highDrainList) {
        msg += "\n⚠️ High Drain (${highDrainList.size()} devices):\n"
        highDrainList.each { dev ->
            msg += "• ${dev.name} (${dev.level}%) — ${dev.health} health, ${dev.drain}%/day\n"
        }
        hasContent = true
    }

    // Don't send if nothing to report
    if (!hasContent) return

    // Send notifications
    if (enablePush) sendPush(msg)
    if (notifyDevices) notifyDevices.each { it.deviceNotification(msg) }
}

// ============================================================
// ===================== CRITICAL ALERTS =====================
// ============================================================
def sendCriticalReport(device, level) {
    def msg = "🔴 Battery Critical Alert\n\n${device.displayName} is at ${level}%."
    if (enablePush) sendPush(msg)
    if (notifyDevices) notifyDevices.each { it.deviceNotification(msg) }
}

// ============================================================
// ===================== BATTERY HANDLER =====================
// ============================================================
def batteryHandler(evt){
    def device = evt.device
    def level = evt.value?.toInteger()
    if(device && level != null){
        updateBattery(device, level)
    }
}

def updateBattery(device, level){
    def data = state.history[device.id]

    // Initialize history entry if missing
    if(!data){
        state.history[device.id] = [
            lastLevel: level ?: 100,
            lastDate: now(),
            drain: 0.3,
            samples: [],
            justReplaced: false
        ]
        state.trend[device.id] = "Stable"
        data = state.history[device.id]
    }

    // Handle 0% battery correctly
    if(level == 0){
        data.justReplaced = false
        data.replacedTime = null
        data.drain = 1.0
        data.samples = []
        state.trend[device.id] = "Heavy Drain"
    }

    // Force sanity check for sudden high battery
    if(level >= 90 && data?.lastLevel <= 50 && data?.lastDate){
        def hoursSinceLast = (now() - safeTime(data.lastDate)) / (1000*60*60)
        if(hoursSinceLast >= 24){
            detectReplacement(device, level, data.lastLevel)
        }
    }

    // Always check for possible replacement
    detectReplacement(device, level, data.lastLevel)

    // Clear justReplaced flag after real usage OR 24h fallback
    def replacedAt = data.replacedTime ?: now()

    // 🔹 Clear if battery has started draining (real usage detected)
    if(data.justReplaced && level < 95){
    data.justReplaced = false
}

    // 🔹 Fallback: clear after 24 hours
    else if(data.justReplaced && (now() - safeTime(replacedAt)) > 1000*60*60*24){
    data.justReplaced = false
}

    // Update drain based on lastLevel and samples
    def days = (now() - safeTime(data.lastDate)) / (1000*60*60*24)
    if(days > 0 && !data.justReplaced){
        def drain = (data.lastLevel - level) / days
        if(drain > 0 && drain < 5){
            data.samples << drain
            if(data.samples.size() > 5) data.samples.remove(0)
        }

        if(data.samples && data.samples.size() > 0){
            def avg = data.samples.sum() / data.samples.size()
            data.drain = Math.min(avg, 1.5)
            updateTrend(device, data.drain)
        }
    }

    // Update lastLevel and lastDate after drain calculation
    data.lastLevel = level
    data.lastDate = now()

    // Critical report check
    if(reportFrequency == "critical" && level <= 25){
        sendCriticalReport(device, level)
    }
}

// ============================================================
// ===================== DETECT REPLACEMENT ==================
// ============================================================
def detectReplacement(device, newLevel, oldLevel){
    newLevel = newLevel ?: 100
    oldLevel = oldLevel ?: state.history[device.id]?.lastLevel ?: 0

    if(!state.history[device.id]){
        state.history[device.id] = [
            lastLevel: oldLevel,
            lastDate: now(),
            drain: 0.3,
            samples: [],
            justReplaced: false
        ]
        state.trend[device.id] = "Stable"
    }

    def data = state.history[device.id]

    def largeJump = (newLevel - oldLevel)
    def hadDrainHistory = data?.samples && data.samples.size() >= 2
    def detected = false

    if(newLevel >= 95 && oldLevel <= 40) detected = true
    else if(newLevel >= 90 && oldLevel <= 40 && largeJump >= 25) detected = true
    else if(newLevel >= 90 && hadDrainHistory && largeJump >= 15 && oldLevel <= 40) detected = true
    else if(newLevel >= 95 && hadDrainHistory && oldLevel <= 40) detected = true

    if(detected){
    logReplacement(device, newLevel, false)
   }
}

// ============================================================
// ===================== TREND LOGIC =========================
// ============================================================
def updateTrend(device, drain){
    def adjustedDrain = drain
    if(drain > 5){
        adjustedDrain = 0.3
    }

    def hist = state.history[device.id]
    if(hist?.samples && hist.samples.size() >= 3){
        def avg = hist.samples.sum() / hist.samples.size()
        if(avg > 3){
            adjustedDrain = Math.min(adjustedDrain, 1.0)
        }
    }

    if(adjustedDrain < 0.3) state.trend[device.id] = "Stable"
    else if(adjustedDrain < 0.8) state.trend[device.id] = "Moderate"
    else state.trend[device.id] = "Heavy Drain"
}

def getDrain(device){ return state.history?.get(device.id)?.drain ?:0.3 }
def displayDrain(device){ return String.format("%.2f",getDrain(device)) }
def estDays(device){
    def level=device.currentValue("battery")?.toInteger() ?:100
    def drain=getDrain(device)
    if(drain<=0) drain=0.3
    return Math.round(level/drain)
}
def health(device){
    def drain=getDrain(device)
    if(drain<0.3) return "Excellent"
    if(drain<0.7) return "Good"
    if(drain<1.2) return "Fair"
    return "Poor"
}

// ============================================================
// ===================== SAFE HISTORY HELPERS =================
// ============================================================
def safeTime(ts){ return (ts instanceof Number) ? ts : ts?.time }

def safeHistory(device){
    if(!device) return [:]
    def data = state.history?.get(device.id)
    if(!data){
        data = [
            lastLevel: device.currentValue("battery")?.toInteger() ?: 100,
            lastDate: now(),
            drain: 0.3,
            samples: [],
            justReplaced: false
        ]
        state.history[device.id] = data
        state.trend[device.id] = "Stable"
    }
    return data
}

def getLastBatteryTime(device){ return safeTime(state.history[device.id]?.lastDate) }
def getLastActivityTime(device){ 
    def last = device.getLastActivity()
    return safeTime(last)
}

def isStale(device){
    def lastBattery = getLastBatteryTime(device)
    def lastActivity = getLastActivityTime(device)
    if(!lastBattery || !lastActivity) return false
    def diffHours = (lastActivity - lastBattery) / (1000*60*60)
    return diffHours >= 24
}

def formatTimeAgo(ts){
    if(!ts) return "N/A"
    ts = safeTime(ts)
    def diffMs = now() - ts
    def mins = (diffMs / (1000*60)).toInteger()
    return mins < 60 ? "${mins}m ago" : "${(mins/60).toInteger()}h ago"
}

// ============================================================
// ===================== BATTERY DISPLAY =====================
// ============================================================
def getBatteryLevelDisplay(level, device=null){
    level = (level instanceof Number ? level : null) != null ? level : 100

    if(level == 0){
        def data = device ? safeHistory(device) : null
        if(data) data.justReplaced = false
    }

    def label = ""
    if(level<=25) label = "🔴 ${level}%"
    else if(level<=70) label = "🟠 ${level}%"
    else label = "🟢 ${level}%"

    def data = (device && state.history?.containsKey(device.id)) ? safeHistory(device) : null
    def showTag = data?.justReplaced == true
    def replacedTime = data?.replacedTime

    if(showTag){
        replacedTime = safeTime(replacedTime)
        def hoursSinceReplacement = (now() - replacedTime) / (1000*60*60)
        if(hoursSinceReplacement >= 24){
            if(data) data.justReplaced = false
            showTag = false
        }
    }

    if(device && showTag){
        label += " (Recently Replaced)"
    }

    return label
}

// ============================================================
// ===================== BATTERY REPLACEMENT LOGGER ===========
// ============================================================
def logReplacement(device, newLevel, manual=false){
    if(!device) return

    def data = state.history[device.id]
    if(!data){
        state.history[device.id] = [
            lastLevel: newLevel ?: 100,
            lastDate: now(),
            drain: 0.3,
            samples: [],
            justReplaced: false
        ]
        data = state.history[device.id]
        state.trend[device.id] = "Stable"
    }

    data.drain = 0.3
    data.samples = []
    data.lastLevel = newLevel
    data.lastDate = now()
    data.justReplaced = true
    data.replacedTime = now()
    state.trend[device.id] = "Stable"
    data.lastReplacementLogged = now()

    // ===== DEDUPLICATE PREVIOUS ENTRIES FOR THIS DEVICE =====
    state.replacements = state.replacements?.findAll{ it.device != device.displayName } ?: []

    // Add new replacement entry
    // tag as manual or auto
       state.replacements << [
       device: device.displayName,
       level: newLevel,
       date: new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
       type: manual ? "manual" : "auto"
]

    // Sort descending by date
    state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }
}

// ============================================================
// ===================== SUMMARY PAGE ========================
// ============================================================
def summaryPage(){
    dynamicPage(name:"summaryPage",title:"Battery Summary",install:false){

        // 🛑 FIRST RUN PROTECTION
        if(!state.history || state.history.size() == 0){
            section("Setup Required"){
                paragraph "⚠ <b>Setup Not Complete</b><br><br>" +
                          "You must click <b>Done</b> after selecting your devices before viewing reports.<br><br>" +
                          "Please exit the app and reopen it, then try again."
            }
            return
        }

        section("Battery Summary"){
            def devs = (autoDevices ?: []).findAll{ it?.currentValue("battery") != null }
            devs = devs.sort{ a,b -> 
                def levelA = a.currentValue("battery") ?: 100
                def levelB = b.currentValue("battery") ?: 100
                levelA != levelB ? levelA <=> levelB : a.displayName <=> b.displayName
            }
            if(!devs){
                paragraph "No battery devices found."
                return
            }

            def table="<table style='width:100%; border-collapse: collapse;'>"
            table+="<tr style='font-weight:bold;'>"
            table+="<td>Device</td><td>Battery</td><td>Drain</td><td>Est Days</td><td>Health</td><td>Last Battery</td><td>Last Activity</td>"
            table+="</tr>"

            devs.each{ device ->
                def data = safeHistory(device)

                def level = device.currentValue("battery")?.toInteger() ?: 100
                def drain = getDrain(device)
                def est = estDays(device)
                def h = health(device)
                def lastBatteryStr = formatTimeAgo(getLastBatteryTime(device))
                def lastActivityStr = formatTimeAgo(getLastActivityTime(device))
                def stale = isStale(device)
                def color = getBatteryLevelDisplay(level, device)

                table+="<tr>"
                table+="<td>${device.displayName}</td>"
                table+="<td>${color}</td>"
                table+="<td>${String.format('%.2f',drain)}</td>"
                table+="<td>${est}</td>"
                table+="<td>${h}</td>"
                table+="<td>${lastBatteryStr}</td>"
                table+="<td>${lastActivityStr}${stale?' ⚠':' '}</td>"
                table+="</tr>"
            }

            table+="</table>"
            paragraph table
        }
    }
}
// ============================================================
// ===================== TRENDS PAGE =========================
// ============================================================
def trendsPage(){
    dynamicPage(name:"trendsPage", title:"Battery Trends", install:false){
        section("Battery Trends"){
            def devs = (autoDevices ?: []).findAll{ it?.currentValue("battery") != null }

            if(!devs){
                paragraph "No battery devices found for trends."
                return
            }

            // Sort by worst battery first using same color thresholds
            devs = devs.sort { a, b ->
                def levelA = a.currentValue("battery")?.toInteger() ?: 100
                def levelB = b.currentValue("battery")?.toInteger() ?: 100

                // Lower battery first
                levelA <=> levelB
            }

            // Start table
            def table="<table style='width:100%; border-collapse: collapse;'>"
            table+="<tr style='font-weight:bold;'>"
            table+="<td>Device</td><td>Battery</td><td>Trend</td><td>Day Drain</td>"
            table+="</tr>"

            devs.each{ device ->
                def hist = safeHistory(device)
                def level = device.currentValue("battery")?.toInteger() ?: 100
                def drain = hist?.drain ?: 0.3
                def trend = state.trend[device.id] ?: "Unknown"

                // Use the same color codes as summary notifications
                def color = getBatteryLevelDisplay(level, device)

                table+="<tr>"
                table+="<td>${device.displayName}</td>"
                table+="<td>${color}</td>"
                table+="<td>${trend}</td>"
                table+="<td>${String.format('%.2f', drain)}</td>"
                table+="</tr>"
            }

            table+="</table>"
            paragraph table
        }
    }
}
// ============================================================
// ===================== HISTORY PAGE ========================
// ============================================================
def historyPage(){
    dynamicPage(name:"historyPage", title:"Battery Replacement History", install:false){
        section("Battery Replacement History"){
            if(!state.replacements || state.replacements.size() == 0){
                paragraph "No battery replacements have been logged yet."
                return
            }

            def table="<table style='width:100%; border-collapse: collapse;'>"
            table+="<tr style='font-weight:bold;'>"
            table+="<td>Device</td><td>Level</td><td>Date</td><td>Type?</td>"
            table+="</tr>"

            state.replacements.sort{ a,b -> b.date <=> a.date }.each{ r ->
                def typeTag = r.type == "manual" ? "<span style='color:blue;'>M</span>" :
                              r.type == "auto"   ? "<span style='color:green;'>A</span>" : "?"
                table+="<tr>"
                table+="<td>${r.device}</td>"
                table+="<td>${r.level}%</td>"
                table+="<td>${r.date}</td>"
                table+="<td>${typeTag}</td>"
                table+="</tr>"
            }

            table+="</table>"
            paragraph table
            paragraph "<b>Legend:</b> <span style='color:green;'>A</span> = Automatic, <span style='color:blue;'>M</span> = Manual"
        }
    }
}

// ============================================================
// ===================== MANUAL REPLACEMENT PAGE =============
// ============================================================
def manualReplacementPage() {
    if(state.replacements == null) state.replacements = []
    if(!autoDevices) autoDevices = []

    // Clear previous selections (Hubitat settings)
    app.removeSetting("replaceDevicesManual")
    app.updateSetting("replaceConfirmManual",[value: false, type:"bool"])

    dynamicPage(name: "manualReplacementPage", title: "Manual Battery Replacement", install: false) {
        section("Select Devices") {
            if(!autoDevices || autoDevices.size() == 0) {
                paragraph "⚠ No battery devices available. Please select devices on the main page first."
            } else {
                input "replaceDevicesManual", "enum",
                      title: "Select Devices (can choose multiple)",
                      options: autoDevices.collectEntries { [(it.id): it.displayName] }
                                         .sort { a, b -> a.value <=> b.value },
                      multiple: true,
                      required: false
            }
        }

        section("Confirm Replacement") {
            input "replaceConfirmManual", "bool",
                  title: "Confirm Battery Replaced",
                  required: false
        }

        section() {
            href "manualReplacementConfirmPage", title: "Submit Replacement"
        }

        section() {
            href "manualReplacementPage", title: "Back"
        }
    }
}

// ============================================================
// ================ MANUAL REPLACEMENT CONFIRM PAGE ==========
// ============================================================
def manualReplacementConfirmPage() {
    if(state.replacements == null) state.replacements = []
    if(!autoDevices) autoDevices = []

    dynamicPage(name: "manualReplacementConfirmPage", title: "Confirm Replacement", install: false) {
        section("Replacement Registered") {
            if(replaceDevicesManual && replaceConfirmManual) {
                def successCount = 0

                replaceDevicesManual.each { deviceId ->
                    def device = autoDevices.find { it.id == deviceId }
                    if(device) {
                        def level = device.currentValue("battery")?.toInteger() ?: 100

                        // Ensure history exists
                        if(!state.history[device.id]) {
                            state.history[device.id] = [
                                lastLevel: level,
                                lastDate: now(),
                                drain: 0.3,
                                samples: []
                            ]
                        }

                        // ===== DEDUPLICATE PREVIOUS MANUAL ENTRIES =====
                        state.replacements = state.replacements?.findAll { it.device != device.displayName } ?: []

                        // Log the manual replacement
                       // tag as manual
                          state.replacements << [
                          device: device.displayName,
                          level: level,
                          date: new Date().format("yyyy-MM-dd HH:mm", location.timeZone),
                          type: "manual"
                        ]

                        // Sort descending by date
                        state.replacements = state.replacements.sort { a, b -> b.date <=> a.date }

                        // Reset device history and mark as replaced
                        state.history[device.id].drain = 0.3
                        state.history[device.id].samples = []
                        state.trend[device.id] = "Stable"
                        state.history[device.id].lastLevel = 100
                        state.history[device.id].lastDate = now()

                        // THIS IS THE MISSING PIECE
                        state.history[device.id].justReplaced = true
                        state.history[device.id].replacedTime = now()

                        successCount++
                    }
                }

                if(successCount > 0) {
                    paragraph "✅ Battery replacement for ${successCount} device(s) has been recorded."
                } else {
                    paragraph "❌ No valid devices found. Please select devices on the main page first."
                }

            } else {
                paragraph "⚠ Please select device(s) and confirm replacement or use the Back button to cancel."
            }
        }

        section() {
            href "manualReplacementPage", title: "Back to Replacement Page"
        }
    }
}
// ============================================================
// ===================== INFO PAGE ===========================
// ============================================================
def infoPage(Map params = [:]){
    dynamicPage(name:"infoPage", title:"Battery Health Guide", install:false){
        
        // ⚠ Note about color differences
        section("<b>Note on Colors</b>") {
            paragraph "⚠ Colors in battery reports reflect battery percentage (🔴 low, 🟠 medium, 🟢 good), while colors in this guide reflect drain-based health (🟢 Excellent, 🟡 Good, 🟠 Fair, 🔴 Poor)."
        }

        section("<b>Battery Health Breakdown</b>"){
            def table = """<table style='width:100%; border-collapse: collapse;'>
<tr style='font-weight:bold;'><td>Health</td><td>Drain Rate (per day)</td><td>What It Means</td></tr>
<tr><td>🟢 Excellent</td><td>&lt; 0.3%</td><td>Battery is barely draining (very efficient device)</td></tr>
<tr><td>🟡 Good</td><td>0.3 – 0.7%</td><td>Normal battery usage</td></tr>
<tr><td>🟠 Fair</td><td>0.7 – 1.2%</td><td>Higher-than-normal drain</td></tr>
<tr><td>🔴 Poor</td><td>&gt; 1.2%</td><td>Battery draining fast (problem likely)</td></tr>
</table>"""
            paragraph table
        }

        section("<b>🔍 What is Battery Drain?</b>"){ 
            paragraph "Battery drain shows how fast a device uses battery (% per day). Lower values mean longer battery life. Higher values may indicate excessive usage, weak signal, or device issues." 
        }
        
        section("<b>📅 How Estimated Days Works</b>"){ 
            paragraph "Estimated days remaining is calculated using the current battery level divided by the average daily drain rate. This becomes more accurate after multiple battery reports." 
        }
        
        section("<b>📊 Understanding Trends</b>"){ 
            paragraph "Trends are calculated using recent battery activity. Devices need multiple battery reports before trends become accurate.\n• Stable = very low drain\n• Moderate = normal usage\n• Heavy Drain = higher-than-normal usage" 
        }
        
        section("<b>🔋 Battery Replacement Detection</b>"){ 
            paragraph "The app automatically detects battery replacement when a device jumps from ≤40% to ≥95%. Manual replacement can be used if a battery is changed outside this range.\n\nAfter a battery is replaced, the device will be marked as 'Recently Replaced'. This tag will automatically clear after about 24 hours or once the device reports again." 
        }
        
        section("<b>⚠ Troubleshooting High Drain</b>"){ 
            paragraph "If a device shows high drain:\n• Check signal strength (Z-Wave/Zigbee routing)\n• Verify device isn't reporting too frequently\n• Confirm correct battery type is used\n• Look for environmental factors (cold, humidity)\n• Consider device firmware or driver issues" 
        }
        
        section("<b>💡 Tips</b>"){ 
            paragraph "• Devices with consistent low drain are healthy\n• Sudden spikes usually indicate a problem\n• Compare similar devices to identify outliers\n" 
        }
    }
}
