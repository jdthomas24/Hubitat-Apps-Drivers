/**
 * Device Health Monitor
 * Version: 1.6.0
 *
 * Learns each device's normal check-in pattern and flags devices that go quiet, across
 * Zigbee, Z-Wave, Matter, Hub Mesh, LAN, Virtual and Hub Variable. Verifies Poor/Offline
 * devices by state events, refresh/ping, or Hue Bridge / Konnected Panel round-trips.
 * Optional OAuth web portal.
 *
 * v1.6.0 -- Major UI refresh (Reolink/Battery Monitor pattern): status banner, Reports
 * cards, settings list with live values, Summary with Needs attention, Issues only,
 * search and built-in sort (no DataTables CDN), phone layouts, remote-friendly device
 * links. New Device actions page (location, description, detection fixes, snooze, reset) and
 * Bulk actions page replace Location Assignment, Protocol Overrides, Manage Snoozed
 * Devices and Reset History. Hub Mesh Overview retired (search "Hub Mesh" in Summary).
 * App Guide replaced by a Tips page.
 * Fixed: per-device snooze never stuck (page cleared the selection on every render).
 * Fixed: notifications master switch ignored by notification/Pushover devices.
 * Fixed: main page showed notifications ON while the toggle was off on new installs.
 * Fixed: Send Now reported "sent" when mode restriction or skip-empty blocked it; manual
 * sends now ignore mode restriction.
 * Fixed: portal "Last scan" showed page load time instead of the last completed scan.
 * Fixed: removed devices left capability, drop, prior-health and location state behind.
 * Fixed: per-device location edits could be overridden by an older stored value.
 * v1.5.10 -- Rounded runaway BigDecimal precision in history samples (state bloat).
 *
 * Full history in GitHub commit history.
 *
 * Author: jdthomas24
 */

import groovy.transform.Field

definition(
    name: "Device Health Monitor",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Monitor device check-in health across Zigbee, Z-Wave, Matter, Hub Mesh, LAN, Virtual and Hub Variable. Learns each device's normal pattern and alerts you when something goes quiet. Includes an OAuth web portal, batch scanning, location grouping, and richer notifications.",
    category: "Convenience",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Device%20Health%20Monitor/Raw%20Code/DeviceHealthMonitor.groovy",
    iconUrl: "",
    iconX2Url: "",
    version: "1.6.0",
    doNotFocus: true,
    oauth: true
)

@Field static final String APP_VERSION = "1.6.0"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/release-device-health-monitor/163229"
@Field static final String COFFEE_URL = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final String DEFAULT_TIP_TOPIC = "best"

// ============================================================
// ===================== OAUTH MAPPINGS ======================
// ============================================================
mappings {
    path("/dashboard")    { action: [GET: "serveDashboardPage"]   }
    path("/data")         { action: [GET: "serveDataEndpoint"]    }
    path("/refresh")      { action: [GET: "forceRefreshEndpoint"] }
    path("/updateDevice") { action: [GET: "updateDeviceEndpoint"] }
}

// ============================================================
// ===================== PREFERENCES =========================
// ============================================================
preferences {
    page(name: "mainPage")
    page(name: "devicesPage")
    page(name: "notificationsPage")
    page(name: "scanSettingsPage")
    page(name: "snoozeSettingsPage")
    page(name: "deepScanPage")
    page(name: "locationsPage")
    page(name: "portalPage")
    page(name: "appNamePage")
    page(name: "summaryPage")
    page(name: "verificationPage")
    page(name: "deviceManagePage")
    page(name: "deviceActionsPage")
    page(name: "bulkActionsPage")
    page(name: "forceScanPage")
    page(name: "tipsPage")
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

    if (settings?.enableSnooze == false) {
        state.snoozed = [:]
        if (debugEnabled()) log.debug "Snooze disabled — all active snoozes cleared"
    }

    // Retired settings from pre-1.6.0 pages
    ["devicesToSnooze", "devicesToUnsnooze", "confirmSnooze", "confirmUnsnooze",
     "resetHistoryDevices", "resetHistoryConfirm", "sendNowConfirm",
     "bulkLoc", "bulkDevs", "bulkApplyConfirm"].each {
        if (settings.containsKey(it)) app.removeSetting(it)
    }
    state.remove("lastBulkLoc")

    initialize()
    runIn(1800, disableDebugLogging)
}

def initialize() {
    if (debugEnabled()) log.debug "Device Health Monitor initializing"
    if (state.history       == null) state.history       = [:]
    if (state.health        == null) state.health        = [:]
    if (state.snoozed       == null) state.snoozed       = [:]
    if (state.verifying     == null) state.verifying     = [:]
    if (state.stateHistory  == null) state.stateHistory  = [:]
    // Reset scan state: a reboot mid-scan would otherwise trip the stuck-scan watchdog
    state.isScanning    = false
    state.scanStartTime = null
    state.scanQueue     = []
    state.tempResults   = []
    if (state.deviceCapabilities  == null) state.deviceCapabilities  = [:]
    if (state.deepScanResult      == null) state.deepScanResult      = [:]
    if (state.dropHistory         == null) state.dropHistory         = [:]
    if (state.fairHold            == null) state.fairHold            = [:]

    if (!state.capabilitiesResetDone) {
        state.deviceCapabilities  = [:]
        state.capabilitiesResetDone = true
        if (debugEnabled()) log.debug "Device Health Monitor: reset deviceCapabilities — will rebuild on next scan"
    }

    // v1.5.10: one-time rounding of runaway BigDecimal precision in history samples
    if (!state.samplesRoundedDone) {
        def roundedAny = false
        state.history?.each { id, data ->
            if (data?.samples) {
                data.samples = data.samples.collect { (it as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP) }
                roundedAny = true
            }
            if (data?.avgInterval != null) {
                data.avgInterval = (data.avgInterval as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
            }
        }
        state.samplesRoundedDone = true
        if (roundedAny) log.info "Device Health Monitor: one-time cleanup — rounded bloated sample precision in state.history"
    }
    if (state.deviceLocations == null) state.deviceLocations = [:]

    getAllMonitoredDevices()?.each { device ->
        def existing = settings["loc_${device.id}"]
        if (existing && !state.deviceLocations?.containsKey(device.id as String)) {
            if (!state.deviceLocations) state.deviceLocations = [:]
            state.deviceLocations[device.id as String] = existing
        }
    }
    state.deviceLocations = state.deviceLocations

    if (!state.accessToken) {
        try {
            createAccessToken()
        } catch (e) {
            log.error "Device Health Monitor: OAuth is not enabled. Please enable OAuth in the App Code screen."
        }
    }

    scheduleScanInterval()
    scheduleReportFrequency()
    scheduleDeepVerificationScan()
    if (debugEnabled()) log.debug "Monitoring ${getAllMonitoredDevices().findAll { getProtocol(it) != 'Unknown' }.size()} device(s)"
    runIn(5, scanAllDevices)
}

def debugEnabled() { return settings?.debugMode == true }

def disableDebugLogging() {
    log.info "Device Health Monitor: auto-disabling debug logging after 30 minutes"
    app.updateSetting("debugMode", [value: false, type: "bool"])
}

def applyCustomLabel() {
    if (settings?.customAppName) {
        if (app.label != settings?.customAppName) {
            app.updateLabel(settings.customAppName)
        }
    }
}

// ============================================================
// ===================== SNOOZE ==============================
// ============================================================
def snoozeEnabled() { return settings?.enableSnooze == true }

def snoozeDevice(deviceId) {
    if (!snoozeEnabled()) return
    def hours = (settings?.snoozeDurationHours ?: 24).toInteger()
    def until = now() + (hours * 3600000)
    if (!state.snoozed) state.snoozed = [:]
    state.snoozed[deviceId as String] = until
    state.snoozed = state.snoozed
}

def unsnoozeDevice(deviceId) {
    state.snoozed?.remove(deviceId as String)
    state.snoozed = state.snoozed ?: [:]
}

def isDeviceSnoozed(deviceId) {
    if (!snoozeEnabled()) return false
    def until = state.snoozed?.get(deviceId)
    if (!until) return false
    if (until >= now()) return true
    def s = state.snoozed ?: [:]
    s.remove(deviceId)
    state.snoozed = s
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
def getAllMonitoredDevices() { return monitoredDevices ?: [] }

def getRawProtocol(device) {
    try {
        def driverName = (device.typeName ?: "").toLowerCase()
        if (driverName.contains("hub variable") || driverName.contains("variable connector")) return "Hub Variable"
        if (driverName.contains("virtual")) return "Virtual"
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
            if (driverName.contains("zigbee") || driverName.contains("thirdreality") || driverName.contains("third reality")) return "Hub Mesh (Zigbee)"
            if (driverName.contains("z-wave") || driverName.contains("zwave")) return "Hub Mesh (Z-Wave)"
            if (driverName.contains("matter"))                                return "Hub Mesh (Matter)"
            def manufacturer = (device.getDataValue("Manufacturer") ?: "").toLowerCase()
            if (manufacturer in ["centralite", "lumi", "ikea", "sengled",
                                 "osram", "philips", "samsung", "smartthings",
                                 "sonoff", "tuya", "third reality", "thirdreality",
                                 "third_reality"]) {
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
        def override = settings["protocolOverride_${device.id}"]
        if (override && override != "" && override != "Auto-detect") return override
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
        case "Matter":             return "#e65100"
        case "Hub Mesh (Matter)":  return "#e65100"
        case "Hub Mesh":           return "#06b6d4"
        case "LAN":                return "#14b8a6"
        case "Virtual":            return "#ec4899"
        case "Hub Variable":       return "#eab308"
        case "Bluetooth":          return "#06b6d4"
        default:                   return "#c0c4cc"
    }
}

def isUnresolvableProtocol(protocol) {
    return protocol in ["Hub Mesh", "LAN", "Virtual", "Hub Variable"]
}

def usesFilteredSampling(protocol) {
    return protocol in ["Virtual", "Hub Variable"]
}

def isHueDevice(device) {
    def dn  = (device.typeName ?: "").toLowerCase()
    def dni = (device.deviceNetworkId ?: "").toLowerCase()
    if (dni.startsWith("hue/")) return true
    if (dn.startsWith("cocohue")) return true
    if (dn.contains("huebridgebulb") || dn.contains("huebridge")) return true
    return false
}

def findHueBridge() {
    return getAllMonitoredDevices().find { device ->
        def dn  = (device.typeName ?: "").toLowerCase()
        def dni = (device.deviceNetworkId ?: "").toLowerCase()
        (dni.startsWith("hue/") && dn.contains("bridge")) ||
        dn.contains("cocohue bridge") ||
        (dn.contains("huebridge") && !dn.contains("bulb"))
    }
}

def findKonnectedPanel(device) {
    def monitoredPanels = getAllMonitoredDevices().findAll { d ->
        (d.typeName ?: "").toLowerCase().contains("konnected alarm panel")
    }
    if (!monitoredPanels) return null

    try {
        def parentId = device.parentDeviceId
        if (parentId) {
            def panel = monitoredPanels.find { d -> d.id == parentId }
            if (panel) return panel
        }
    } catch (e) {}

    def dni = (device.deviceNetworkId ?: "")
    if (dni.contains("-")) {
        def parts    = dni.split("-")
        def parentId = parts[0]
        def suffix   = parts.size() > 1 ? parts[1] : ""
        if (suffix.isNumber() && new BigDecimal(suffix).toLong() > 1000) {
            def panel = monitoredPanels.find { d -> d.id == parentId }
            if (panel) return panel
        }
    }

    return null
}

def isKonnectedDevice(device) {
    return findKonnectedPanel(device) != null
}

def isModeOK() {
    if (!settings?.enableModeRestriction) return true
    if (!settings?.restrictedModes) return true
    return settings.restrictedModes.contains(location.mode)
}

// ============================================================
// ===================== DEVICE CATEGORY HELPERS =============
// ============================================================
def setDeviceLocation(String deviceId, String loc) {
    if (!state.deviceLocations) state.deviceLocations = [:]
    if (loc == null || loc == "") {
        state.deviceLocations.remove(deviceId)
        app.removeSetting("loc_${deviceId}")
    } else {
        state.deviceLocations[deviceId] = loc
        app.updateSetting("loc_${deviceId}", [type: "string", value: loc])
    }
    state.deviceLocations = state.deviceLocations
}

def getDeviceLocation(deviceId) {
    return state.deviceLocations?.get(deviceId as String) ?: settings["loc_${deviceId}"] ?: ""
}

def getDeviceDescription(device) {
    return settings["desc_${device.id}"] ?: ""
}

// ============================================================
// ===================== PING STATUS HELPER ==================
// ============================================================
def markChildrenPingAttempted(String parentId) {
    def allDevs = getAllMonitoredDevices()
    def capMap  = state.deviceCapabilities ?: [:]
    allDevs.each { device ->
        def dni = (device.deviceNetworkId ?: "")
        def isChild = false
        try { if (device.parentDeviceId == parentId) isChild = true } catch (e) {}
        if (!isChild && dni.contains("-")) {
            def prefix = dni.split("-")[0]
            if (prefix == parentId) isChild = true
        }
        if (isChild) {
            def capKey  = device.id as String
            def capData = capMap[capKey] ?: [:]
            if (capData.pingWorks != true) {
                capData.pingAttempted    = true
                capData.lastPingAttempt  = now()
                capMap[capKey]           = capData
            }
        }
    }
    state.deviceCapabilities = capMap
    if (debugEnabled()) log.debug "DHM: marked children of ${parentId} as pingAttempted"
}

def getPingStatus(deviceId) {
    def capMap = state.deviceCapabilities ?: [:]
    def cap    = capMap[deviceId as String]
    if (!cap) return "unknown"
    if (cap.pingWorks == true)  return "verified"
    if (cap.pingWorks == false) return "unverifiable"
    if (cap.declared  == true)  return "declared"
    return "unknown"
}

// v1.5.8: Fair + confirmed reachable displays as "Quiet" and isn't an active issue
def isQuietVerified(deviceId) {
    def h = state.health?.get(deviceId) ?: "Pending"
    if (h != "Fair") return false
    def cap = state.deviceCapabilities?.get(deviceId as String) ?: [:]
    return cap.pingWorks == true
}

// v1.5.9: weak (refresh/ping didn't throw) trust is capped at 2x the Offline Threshold
def getWeakTrustCeilingMs() {
    return ((settings?.offlineThresholdHours ?: 168) * 3600000L) * 2
}

// ============================================================
// ===================== REPEAT DROPS / EXTENDED STATE =======
// ============================================================
def isRepeatDrops(deviceId) {
    def drops = state.dropHistory?.get(deviceId as String) ?: []
    drops = drops.findAll { now() - it < 86400000 }
    return drops.size() >= 3
}

/** Returns a short note like "Active 3h" / "Open 30h" for long-running states, or null. */
def getExtendedStateNote(device) {
    if (device.hasAttribute("motion") && device.currentValue("motion") == "active") {
        try {
            def stateDate = device.currentState("motion")?.date
            if (stateDate) {
                def hoursActive = (now() - stateDate.time) / 3600000
                if (hoursActive >= 2) return "⏰ Active ${hoursActive.toInteger()}h"
            }
        } catch (e) {}
    }
    if (device.hasAttribute("contact") && device.currentValue("contact") == "open") {
        try {
            def stateDate = device.currentState("contact")?.date
            if (stateDate) {
                def hoursOpen = (now() - stateDate.time) / 3600000
                if (hoursOpen >= 24) return "⏰ Open ${hoursOpen.toInteger()}h"
            }
        } catch (e) {}
    }
    return null
}

def getExtendedStateTag(device) {
    def note = getExtendedStateNote(device)
    return note ? " <span style='color:#f97316;font-size:10px;'>${note}</span>" : ""
}

// ============================================================
// ===================== LOW ACTIVITY ========================
// ============================================================
def isLowActivity(deviceId) {
    def data = state.history?.get(deviceId)
    if (!data) return false
    def samples  = data?.samples?.size() ?: 0
    def lastSeen = data?.lastSeen ?: now()
    def ageMs    = now() - lastSeen
    def ageDays  = ageMs / (1000.0 * 60 * 60 * 24)
    return (ageDays >= 7 && samples < 3)
}

// ============================================================
// ===================== STATE-CHANGE VERIFICATION ===========
// ============================================================
def getStateVerified(deviceId) {
    try {
        def tracked = state.stateHistory?.get(deviceId as String)
        if (!tracked?.lastChanged) return false
        def data = state.history?.get(deviceId as String)
        if (!data?.lastSeen) return false
        def stateChangedAfterLastSeen = (tracked.lastChanged as Long) > (data.lastSeen as Long)
        def thresholdMs = ((settings?.offlineThresholdHours ?: 168) * 60 * 60 * 1000 * 1.0).toLong()
        def stateChangeIsRecent = (now() - (tracked.lastChanged as Long)) < thresholdMs
        return stateChangedAfterLastSeen && stateChangeIsRecent
    } catch (e) {
        if (debugEnabled()) log.debug "getStateVerified error for device ${deviceId}: ${e.message}"
        return false
    }
}

// ============================================================
// ===================== STATE TRACKING ======================
// ============================================================
def getMeaningfulAttributes(device) {
    def known = [
        "switch", "contact", "motion", "lock", "presence", "water",
        "smoke", "carbonMonoxide", "tamper", "shock", "valve", "door",
        "windowShade", "sleeping", "printState", "thermostatOperatingState",
        "thermostatMode", "mediaPlaybackStatus", "transportStatus", "chargingState",
        "currentStatus", "printerStatus", "status", "deviceStatus",
        "healthStatus", "connectionStatus", "operatingState", "mode"
    ]

    def driverName  = (device.typeName ?: "").toLowerCase()
    def deviceName  = (device.name ?: "").toLowerCase()
    def displayName = (device.displayName ?: "").toLowerCase()
    def nameCheck   = "${driverName} ${deviceName} ${displayName}"

    def isPrinter = nameCheck.contains("moonraker") || nameCheck.contains("klipper") ||
                    nameCheck.contains("octoprint") || nameCheck.contains("bambu") ||
                    nameCheck.contains("prusa") || nameCheck.contains("3d print") ||
                    nameCheck.contains("printer")
    if (isPrinter) known += ["progress", "currentLayer", "printTime", "remainingTime",
                              "printTimeLeft", "totalLayers", "fileName"]

    def isThermostat = nameCheck.contains("thermostat") || nameCheck.contains("ecobee") ||
                       nameCheck.contains("nest") || nameCheck.contains("honeywell") ||
                       nameCheck.contains("sinope")
    if (isThermostat) known += ["heatingSetpoint", "coolingSetpoint", "thermostatSetpoint",
                                 "temperature", "humidity"]

    def isEV = nameCheck.contains("tesla") || nameCheck.contains("electric vehicle")
    if (isEV) known += ["battery", "batteryLevel", "chargingState", "range", "odometer"]

    def isMedia = nameCheck.contains("sonos") || nameCheck.contains("denon") ||
                  nameCheck.contains("yamaha") || nameCheck.contains("roku") ||
                  nameCheck.contains("apple tv") || nameCheck.contains("media player")
    if (isMedia) known += ["trackDescription", "trackData", "mediaPlaybackStatus",
                            "transportStatus", "volume", "level"]

    def found = [] as Set
    try {
        device.capabilities?.each { cap ->
            cap?.attributes?.each { attr ->
                if (attr?.name && attr.name in known) found << attr.name
            }
        }
    } catch (e) {
        if (debugEnabled()) log.debug "getMeaningfulAttributes capability scan error for ${device.displayName}: ${e.message}"
    }

    try {
        device.currentStates?.each { s ->
            if (!s?.name || s?.value == null) return
            def val = s.value.toString().trim()
            if (val in ["", "null", "0"]) return
            def skipAttrs = ["battery", "batteryLastReplaced", "lastCheckin",
                             "temperature", "humidity", "illuminance", "pressure",
                             "carbonDioxide", "energy", "power", "voltage",
                             "current", "frequency", "rssi", "lqi", "driver",
                             "notPresentCounter", "restoredCounter", "firmware"]
            if (s.name in skipAttrs && !(s.name in known)) return
            if (val.isNumber() && !(s.name in known)) return
            found << s.name
        }
    } catch (e) {
        if (debugEnabled()) log.debug "getMeaningfulAttributes currentStates error for ${device.displayName}: ${e.message}"
    }
    return found.toList().sort()
}

def shouldShowStateOverride(device) {
    def attrs = getMeaningfulAttributes(device)
    if (attrs.size() > 1) return true
    if (attrs.size() == 0) return false

    def driverName  = (device.typeName ?: "").toLowerCase()
    def deviceName  = (device.name ?: "").toLowerCase()
    def displayName = (device.displayName ?: "").toLowerCase()
    def nameCheck   = "${driverName} ${deviceName} ${displayName}"

    def knownSingleAttrTypes = [
        "life360", "presence", "arrival", "mobile app",
        "lock", "deadbolt",
        "water", "leak",
        "smoke", "carbon monoxide",
        "contact", "door sensor", "window sensor",
        "motion", "motion sensor", "pir",
        "valve", "shock", "vibration", "tamper"
    ]
    if (knownSingleAttrTypes.any { nameCheck.contains(it) }) return true

    def overrideCandidateAttrs = [
        "presence", "lock", "water", "smoke", "carbonMonoxide",
        "contact", "motion", "tamper", "shock", "valve", "door"
    ]
    if (attrs[0] in overrideCandidateAttrs) return true

    return false
}

def getOverrideStateDisplay(device, attrName) {
    try {
        def val = device.currentValue(attrName)
        if (val == null) return null
        def vs = val.toString().trim()
        if (vs in ["", "null"]) return null
        def vl = vs.toLowerCase()

        switch (attrName) {
            case "switch":
                def isOn = vl == "on"
                return [label: isOn ? "ON" : "OFF",
                        color: isOn ? "#1565c0" : "#c0c4cc", isAlert: false, type: attrName]
            case "contact":
                def isOpen = vl == "open"
                return [label: isOpen ? "Open" : "Closed",
                        color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: attrName]
            case "motion":
                def isActive = vl == "active"
                return [label: isActive ? "Active" : "Inactive",
                        color: isActive ? "#1565c0" : "#c0c4cc", isAlert: false, type: attrName]
            case "lock":
                def isUnlocked = vl == "unlocked"
                return [label: isUnlocked ? "Unlocked" : "Locked",
                        color: isUnlocked ? "#e65100" : "#c0c4cc", isAlert: isUnlocked, type: attrName]
            case "presence":
                def isPresent = vl == "present"
                return [label: isPresent ? "Present" : "Not Present",
                        color: isPresent ? "#1565c0" : "#c0c4cc", isAlert: false, type: attrName]
            case "water":
                def isWet = vl == "wet"
                return [label: isWet ? "Wet" : "Dry",
                        color: isWet ? "#c62828" : "#c0c4cc", isAlert: isWet, type: attrName]
            case "smoke":
            case "carbonMonoxide":
            case "tamper":
                def isDetected = vl == "detected"
                return [label: isDetected ? "${attrName.capitalize()}!" : "Clear",
                        color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: attrName]
            case "valve":
            case "door":
                def isOpen2 = vl in ["open", "opening"]
                return [label: vs.capitalize(),
                        color: isOpen2 ? "#e65100" : "#c0c4cc", isAlert: isOpen2, type: attrName]
            case "printState":
            case "currentStatus":
            case "printerStatus":
                def isPrint = vl in ["printing", "busy"]
                def isPause = vl in ["paused", "pausing"]
                def isError = vl in ["error", "offline", "disconnected", "cancelled"]
                def color   = isPrint ? "#1565c0" : isPause ? "#e65100" :
                              isError ? "#c62828" : "#c0c4cc"
                return [label: vs.capitalize(), color: color, isAlert: isError, type: attrName]
            default:
                def isActive = vl in ["on", "active", "connected", "online", "running",
                                      "enabled", "playing", "present", "open"]
                def isAlert  = vl in ["offline", "disconnected", "error", "fault", "alarm",
                                      "wet", "detected"]
                def color    = isAlert ? "#c62828" : isActive ? "#1565c0" : "#c0c4cc"
                return [label: vs.capitalize(), color: color, isAlert: isAlert, type: attrName]
        }
    } catch (e) {
        if (debugEnabled()) log.debug "getOverrideStateDisplay error for ${device.displayName}: ${e.message}"
    }
    return null
}

def getCurrentStateDisplay(device) {
    try {
        def attrOverride = settings["stateAttrOverride_${device.id}"]
        if (attrOverride && attrOverride != "Auto-detect") {
            def overrideResult = getOverrideStateDisplay(device, attrOverride)
            if (overrideResult) return overrideResult
        }

        def driverName = (device.typeName ?: "").toLowerCase()

        def isContactDevice = driverName.contains("contact") ||
                              driverName.contains("door sensor") ||
                              driverName.contains("window sensor")
        if (isContactDevice) {
            def contact = device.currentValue("contact")
            if (contact != null) {
                def isOpen = contact.toString().toLowerCase() == "open"
                return [label: isOpen ? "Open" : "Closed", color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "contact"]
            }
        }

        def isMotionDevice = driverName.contains("motion sensor") ||
                             driverName.contains("motion detector") ||
                             driverName.contains("pir")
        if (isMotionDevice) {
            def motion = device.currentValue("motion")
            if (motion != null) {
                def isActive = motion.toString().toLowerCase() == "active"
                return [label: isActive ? "Active" : "Inactive", color: isActive ? "#1565c0" : "#c0c4cc", isAlert: false, type: "motion"]
            }
        }

        def isLockDevice = driverName.contains("lock") && !driverName.contains("unlock")
        if (isLockDevice) {
            def lock = device.currentValue("lock")
            if (lock != null) {
                def isUnlocked = lock.toString().toLowerCase() == "unlocked"
                return [label: isUnlocked ? "Unlocked" : "Locked", color: isUnlocked ? "#e65100" : "#c0c4cc", isAlert: isUnlocked, type: "lock"]
            }
        }

        def isPresenceDevice = driverName.contains("life360") ||
                               driverName.contains("presence") ||
                               driverName.contains("arrival") ||
                               driverName.contains("mobile")
        if (isPresenceDevice) {
            def presence = device.currentValue("presence")
            if (presence != null) {
                def isPresent = presence.toString().toLowerCase() == "present"
                return [label: isPresent ? "Present" : "Not Present", color: isPresent ? "#1565c0" : "#c0c4cc", isAlert: false, type: "presence"]
            }
        }

        def isPrinter = driverName.contains("moonraker") || driverName.contains("klipper") ||
                        driverName.contains("octoprint") || driverName.contains("bambu") ||
                        driverName.contains("prusa") || driverName.contains("3d print") ||
                        driverName.contains("printer")
        if (isPrinter) {
            def printerStatus = device.currentValue("printState") ?:
                                device.currentValue("currentStatus") ?:
                                device.currentValue("printerStatus") ?:
                                device.currentValue("status")
            if (printerStatus != null) {
                def ps      = printerStatus.toString().toLowerCase()
                def isPrint = ps in ["printing", "busy"]
                def isIdle  = ps in ["idle", "ready", "operational", "standby", "complete"]
                def isPause = ps in ["paused", "pausing"]
                def isError = ps in ["error", "offline", "disconnected", "cancelled"]
                def color   = isPrint ? "#1565c0" : isIdle ? "#c0c4cc" :
                              isPause ? "#e65100" : isError ? "#c62828" : "#c0c4cc"
                return [label: printerStatus.toString().capitalize(), color: color, isAlert: isError, type: "printerStatus"]
            }
        }

        def water = device.currentValue("water")
        if (water != null) {
            def isWet = water.toString().toLowerCase() == "wet"
            return [label: isWet ? "Wet" : "Dry", color: isWet ? "#c62828" : "#c0c4cc", isAlert: isWet, type: "water"]
        }

        def smoke = device.currentValue("smoke")
        if (smoke != null) {
            def isDetected = smoke.toString().toLowerCase() == "detected"
            return [label: isDetected ? "Smoke!" : "Clear", color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: "smoke"]
        }

        def co = device.currentValue("carbonMonoxide")
        if (co != null) {
            def isDetected = co.toString().toLowerCase() == "detected"
            return [label: isDetected ? "CO!" : "Clear", color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: "carbonMonoxide"]
        }

        def sw = device.currentValue("switch")
        if (sw != null) {
            def isOn = sw.toString().toLowerCase() == "on"
            return [label: isOn ? "ON" : "OFF", color: isOn ? "#1565c0" : "#c0c4cc", isAlert: false, type: "switch"]
        }

        def presence = device.currentValue("presence")
        if (presence != null) {
            def isPresent = presence.toString().toLowerCase() == "present"
            return [label: isPresent ? "Present" : "Not Present", color: isPresent ? "#1565c0" : "#c0c4cc", isAlert: false, type: "presence"]
        }

        def contact = device.currentValue("contact")
        if (contact != null) {
            def isOpen = contact.toString().toLowerCase() == "open"
            return [label: isOpen ? "Open" : "Closed", color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "contact"]
        }

        def motion = device.currentValue("motion")
        if (motion != null) {
            def isActive = motion.toString().toLowerCase() == "active"
            return [label: isActive ? "Active" : "Inactive", color: isActive ? "#1565c0" : "#c0c4cc", isAlert: false, type: "motion"]
        }

        def lock = device.currentValue("lock")
        if (lock != null) {
            def isUnlocked = lock.toString().toLowerCase() == "unlocked"
            return [label: isUnlocked ? "Unlocked" : "Locked", color: isUnlocked ? "#e65100" : "#c0c4cc", isAlert: isUnlocked, type: "lock"]
        }

        def tamper = device.currentValue("tamper")
        if (tamper != null) {
            def isDetected = tamper.toString().toLowerCase() == "detected"
            return [label: isDetected ? "Tampered!" : "Clear", color: isDetected ? "#c62828" : "#c0c4cc", isAlert: isDetected, type: "tamper"]
        }

        def shock = device.currentValue("shock")
        if (shock != null) {
            def isDetected = shock.toString().toLowerCase() == "detected"
            return [label: isDetected ? "Detected" : "Clear", color: isDetected ? "#e65100" : "#c0c4cc", isAlert: isDetected, type: "shock"]
        }

        def sleeping = device.currentValue("sleeping")
        if (sleeping != null) {
            def isSleeping = sleeping.toString().toLowerCase() == "sleeping"
            return [label: isSleeping ? "Sleeping" : "Not Sleeping", color: isSleeping ? "#8b5cf6" : "#c0c4cc", isAlert: false, type: "sleeping"]
        }

        def valve = device.currentValue("valve")
        if (valve != null) {
            def isOpen = valve.toString().toLowerCase() == "open"
            return [label: isOpen ? "Open" : "Closed", color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "valve"]
        }

        def door = device.currentValue("door")
        if (door != null) {
            def isOpen = door.toString().toLowerCase() in ["open", "opening"]
            return [label: door.toString().capitalize(), color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "door"]
        }

        def shade = device.currentValue("windowShade")
        if (shade != null) {
            def isOpen = shade.toString().toLowerCase() in ["open", "opening", "partially open"]
            return [label: shade.toString().capitalize(), color: isOpen ? "#1565c0" : "#c0c4cc", isAlert: false, type: "windowShade"]
        }

        def protocol = getProtocol(device)
        def isLANType = protocol in ["LAN", "Hub Mesh", "Hub Mesh (Zigbee)", "Hub Mesh (Z-Wave)", "Hub Mesh (Matter)", "Unknown"]
        if (isLANType) {
            def lanResult = getLANStateDisplay(device)
            if (lanResult != null) return lanResult
        }

        def temp = device.currentValue("temperature")
        if (temp != null) {
            def unit = location?.temperatureScale ?: "F"
            return [label: "${temp}°${unit}", color: "#c0c4cc", isAlert: false, type: "temperature"]
        }
        def humidity = device.currentValue("humidity")
        if (humidity != null) {
            return [label: "${humidity}% RH", color: "#c0c4cc", isAlert: false, type: "humidity"]
        }
        def co2 = device.currentValue("carbonDioxide")
        if (co2 != null) {
            return [label: "${co2} ppm CO₂", color: "#c0c4cc", isAlert: false, type: "carbonDioxide"]
        }

    } catch (e) {
        if (debugEnabled()) log.debug "getCurrentStateDisplay error for ${device.displayName}: ${e.message}"
    }
    return null
}

def getLANStateDisplay(device) {
    try {
        def currentStates = device.currentStates
        if (!currentStates) return null
        def stateMap = [:]
        currentStates.each { s ->
            if (s?.name && s?.value != null) stateMap[s.name] = s.value.toString()
        }
        if (!stateMap) return null

        def driverName = (device.typeName ?: "").toLowerCase()

        def isThermostat = driverName.contains("thermostat") || driverName.contains("ecobee") ||
                           driverName.contains("nest") || driverName.contains("honeywell") ||
                           driverName.contains("sinope") ||
                           stateMap.containsKey("thermostatMode") ||
                           stateMap.containsKey("thermostatOperatingState")
        if (isThermostat) {
            def opState = stateMap["thermostatOperatingState"]
            def mode    = stateMap["thermostatMode"]
            def temp    = stateMap["temperature"]
            def setpt   = stateMap["thermostatSetpoint"] ?: stateMap["coolingSetpoint"] ?: stateMap["heatingSetpoint"]
            if (opState) {
                def isActive = opState.toLowerCase() in ["heating", "cooling", "fan only", "pending heat", "pending cool"]
                def color    = opState.toLowerCase() == "heating" ? "#e65100" :
                               opState.toLowerCase() == "cooling" ? "#1565c0" :
                               isActive ? "#1565c0" : "#c0c4cc"
                def label    = opState.capitalize()
                if (temp)  label += " ${temp}°"
                if (setpt) label += " → ${setpt}°"
                return [label: label, color: color, isAlert: false, type: "thermostat"]
            }
            if (mode) {
                def isOff = mode.toLowerCase() == "off"
                return [label: "Mode: ${mode.capitalize()}", color: isOff ? "#c0c4cc" : "#1565c0", isAlert: false, type: "thermostat"]
            }
        }

        def isMedia = driverName.contains("sonos") || driverName.contains("denon") ||
                      driverName.contains("yamaha") || driverName.contains("roku") ||
                      driverName.contains("apple tv") || driverName.contains("media player") ||
                      stateMap.containsKey("trackDescription") ||
                      stateMap.containsKey("mediaPlaybackStatus") ||
                      stateMap.containsKey("transportStatus")
        if (isMedia) {
            def playback = stateMap["mediaPlaybackStatus"] ?: stateMap["transportStatus"] ?: stateMap["status"]
            if (playback) {
                def isPlaying = playback.toLowerCase() in ["playing", "play"]
                def color     = isPlaying ? "#1565c0" : "#c0c4cc"
                def track     = stateMap["trackDescription"] ?: stateMap["trackData"] ?: ""
                def label     = playback.capitalize()
                if (track && track.length() > 0 && track != "null") {
                    label += ": " + (track.length() > 20 ? track[0..19] + "…" : track)
                }
                return [label: label, color: color, isAlert: false, type: "media"]
            }
        }

        def isEV = driverName.contains("tesla") || driverName.contains("electric vehicle") ||
                   stateMap.containsKey("chargingState") || stateMap.containsKey("batteryLevel")
        if (isEV) {
            def charging = stateMap["chargingState"]
            def battery  = stateMap["battery"] ?: stateMap["batteryLevel"]
            if (charging) {
                def isCharging = charging.toLowerCase() in ["charging", "complete"]
                def color      = charging.toLowerCase() == "complete" ? "#16a34a" :
                                 isCharging ? "#1565c0" : "#c0c4cc"
                def label      = charging.capitalize()
                if (battery) label += " ${battery}%"
                return [label: label, color: color, isAlert: false, type: "ev"]
            }
            if (battery) {
                def pct = battery.isNumber() ? battery.toInteger() : 0
                def color = pct < 20 ? "#c62828" : pct < 40 ? "#e65100" : "#c0c4cc"
                return [label: "Battery ${battery}%", color: color, isAlert: pct < 20, type: "ev"]
            }
        }

        def isShelly = driverName.contains("shelly") ||
                       stateMap.containsKey("power") || stateMap.containsKey("energy")
        if (isShelly && stateMap.containsKey("switch")) {
            def sw    = stateMap["switch"]
            def isOn  = sw?.toLowerCase() == "on"
            def power = stateMap["power"]
            def label = isOn ? "ON" : "OFF"
            if (isOn && power && power.isNumber()) label += " ${power.toDouble().round(1)}W"
            return [label: label, color: isOn ? "#1565c0" : "#c0c4cc", isAlert: false, type: "shelly"]
        }

        if (stateMap.containsKey("door")) {
            def d = stateMap["door"].toLowerCase()
            def isOpen = d in ["open", "opening"]
            return [label: stateMap["door"].capitalize(), color: isOpen ? "#e65100" : "#c0c4cc", isAlert: isOpen, type: "door"]
        }

        def rankedAttrs = [
            "status", "deviceStatus", "healthStatus", "connectionStatus",
            "systemStatus", "operatingState", "currentState",
            "active", "enabled", "connected", "running",
            "mode", "level", "speed", "inputSource"
        ]
        for (attr in rankedAttrs) {
            def val = stateMap[attr]
            if (val == null) continue
            def vl = val.toLowerCase().trim()
            if (vl in ["unknown", "null", "", "none", "0", "false", "true"]) continue
            if (val.isNumber()) continue
            if (val.length() > 30) continue
            if (val.contains(": ")) continue
            def isActive = vl in ["on", "active", "connected", "online", "running",
                                   "enabled", "open", "playing", "present", "idle", "ready", "operational"]
            def isAlert  = vl in ["offline", "disconnected", "error", "fault", "alarm", "wet", "detected"]
            def color    = isAlert ? "#c62828" : isActive ? "#1565c0" : "#c0c4cc"
            return [label: val.capitalize(), color: color, isAlert: isAlert, type: attr]
        }

    } catch (e) {
        if (debugEnabled()) log.debug "getLANStateDisplay error for ${device.displayName}: ${e.message}"
    }
    return null
}

def updateStateTracking(device) {
    try {
        def id         = device.id
        def stateInfo  = getCurrentStateDisplay(device)
        if (!stateInfo) return

        def currentVal = stateInfo.label
        def sh         = state.stateHistory ?: [:]
        def tracked    = sh[id]

        if (!tracked) {
            sh[id] = [lastValue: currentVal, lastChanged: now()]
            state.stateHistory = sh
            return
        }

        if (tracked.lastValue != currentVal) {
            sh[id] = [lastValue: currentVal, lastChanged: now()]
            state.stateHistory = sh
            if (debugEnabled()) log.debug "${device.displayName}: state changed to ${currentVal}"
        }
    } catch (e) {
        if (debugEnabled()) log.debug "updateStateTracking error for ${device.displayName}: ${e.message}"
    }
}

// ============================================================
// ===================== LOCATIONS ===========================
// ============================================================
def getRoomOptions() {
    def locs = []
    (1..30).each { i ->
        def v = settings["loc${i}"] ?: ""
        def t = v.trim()
        if (t != "") locs << t
    }
    return locs.sort()
}

// ============================================================
// ===================== REPORT SCHEDULING ==================
// ============================================================
def scheduleReportFrequency() {
    unschedule("reportScheduler")
    if (!summaryTime) return
    schedule(summaryTime, reportScheduler)
}

def scheduleDeepVerificationScan() {
    unschedule("runDeepVerificationScan")
    if (settings?.enableDeepScan && settings?.deepScanTime) {
        schedule(settings.deepScanTime, runDeepVerificationScan)
        if (debugEnabled()) log.debug "Deep verification scan scheduled for ${settings.deepScanTime}"
    }
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
// ===================== DEEP VERIFICATION SCAN ==============
// ============================================================
def runDeepVerificationScan() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return

    def targets = devList.findAll { getPingStatus(it.id) in ["declared", "unknown"] }

    log.info "Device Health Monitor: deep verification scan starting — ${targets.size()} device(s) to verify"
    if (targets.size() == 0) {
        log.info "Device Health Monitor: deep verification scan — nothing to verify (all devices already Verified or Unverifiable)"
        return
    }

    def totalDevices = targets.size()
    def batchSize    = totalDevices > 200 ? 25 : 40
    def groups       = targets.collate(batchSize)
    def totalGroups  = groups.size()
    log.info "Device Health Monitor: deep scan — ${totalGroups} batch(es) of ${batchSize}"

    state.deepScanQueue   = groups.collect { group -> group.collect { it.id } }
    state.deepScanTotal   = totalGroups
    state.deepScanCurrent = 0

    processDeepScanGroup()
    def actualDelay = ((totalGroups - 1) * 2) + 10
    runIn(actualDelay, "finalizeDeepScan", [overwrite: false])
}

def processDeepScanGroup(data = null) {
    def queue   = state.deepScanQueue ?: []
    if (queue.isEmpty()) return

    def deviceIds   = queue.remove(0)
    state.deepScanQueue   = queue
    state.deepScanCurrent = (state.deepScanCurrent ?: 0) + 1
    def groupNum    = state.deepScanCurrent
    def totalGroups = state.deepScanTotal ?: 0
    def allDevs     = getAllMonitoredDevices()
    log.info "Device Health Monitor: deep scan batch ${groupNum}/${totalGroups} — pinging ${deviceIds.size()} device(s)"

    if (!queue.isEmpty()) {
        runIn(2, "processDeepScanGroup", [overwrite: false])
    }

    deviceIds.each { devId ->
        def device = allDevs.find { it.id == devId }
        if (!device) return

        def capMapD  = state.deviceCapabilities ?: [:]
        def capKeyD  = devId as String
        def capDataD = capMapD[capKeyD] ?: [:]
        def protocol  = getProtocol(device)
        def isVirtual = protocol in ["Virtual", "Hub Variable"]

        if (isVirtual) {
            capDataD.pingWorks  = false
            capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1
        } else if (isHueDevice(device)) {
            def bridge = findHueBridge()
            if (bridge) {
                try { bridge.refresh(); capDataD.pingAttempted = true; capDataD.lastPingAttempt = now() }
                catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else {
                capDataD.pingWorks = false
            }
        } else {
            def hasRefresh       = false
            def hasPing          = false
            def hasCustomRefresh = false
            try { hasRefresh       = device.hasCapability("Refresh") } catch (e) {}
            try { hasPing          = device.hasCapability("Ping")    } catch (e) {}
            try { hasCustomRefresh = device.hasCommand("forceRefresh") || device.hasCommand("refresh") } catch (e) {}

            if (hasRefresh) {
                try { device.refresh(); capDataD.pingAttempted = true; capDataD.lastPingAttempt = now() }
                catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else if (hasPing) {
                try { device.ping(); capDataD.pingAttempted = true; capDataD.lastPingAttempt = now() }
                catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else if (hasCustomRefresh) {
                try {
                    if (device.hasCommand("forceRefresh")) device.forceRefresh()
                    else device.refresh()
                    capDataD.pingAttempted = true; capDataD.lastPingAttempt = now()
                } catch (e) { capDataD.pingWorks = false; capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1 }
            } else {
                capDataD.pingWorks  = false
                capDataD.pingFailed = (capDataD.pingFailed ?: 0) + 1
            }
        }
        capMapD[capKeyD]         = capDataD
        state.deviceCapabilities = capMapD
    }
}

def finalizeDeepScan() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }

    def verified     = devList.count { getPingStatus(it.id) == "verified"     }
    def unverifiable = devList.count { getPingStatus(it.id) == "unverifiable" }
    def declared     = devList.count { getPingStatus(it.id) == "declared"     }

    state.deepScanResult = [
        ranAt:        now(),
        verified:     verified,
        unverifiable: unverifiable,
        declared:     declared
    ]

    app.updateSetting("enableDeepScan", [value: false, type: "bool"])
    unschedule("runDeepVerificationScan")

    log.info "Device Health Monitor: deep verification scan complete — ${verified} verified, ${unverifiable} unverifiable, ${declared} still declared"

    runIn(30, "scanAllDevices", [overwrite: true])
    log.info "Device Health Monitor: running scan to check for ping responses — waiting 30s for device responses"
}

// ============================================================
// ===================== SCAN — BATCHED ======================
// ============================================================
def scanAllDevices() {
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return

    def nowMs = new Date().time
    if (state.isScanning && state.scanStartTime && (nowMs - state.scanStartTime > 120000)) {
        log.warn "Device Health Monitor: previous scan appears stuck — resetting."
        state.isScanning  = false
        state.scanQueue   = []
        state.tempResults = []
    }

    if (state.isScanning) {
        if (debugEnabled()) log.debug "Scan already in progress — skipping duplicate request."
        return
    }

    log.info "Device Health Monitor: scan started — ${devList.size()} device(s) queued"
    state.isScanning    = true
    state.scanStartTime = nowMs
    state.tempResults   = []
    state.scanQueue     = devList.collect { it.id }

    purgeOrphanedState(getAllMonitoredDevices())
    runIn(1, "processScanChunk")
}

/** Drops per-device state and settings for devices no longer monitored. */
def purgeOrphanedState(devList) {
    def activeIds = devList.collect { it.id as String } as Set

    ["history", "health", "verifying", "stateHistory", "fairHold",
     "deviceCapabilities", "dropHistory", "prevHealth", "deviceLocations"].each { stateKey ->
        def map = state[stateKey]
        if (map instanceof Map) {
            def stale = map.keySet().findAll { !((it as String) in activeIds) }
            if (stale) {
                stale.each { map.remove(it) }
                state[stateKey] = map
                if (debugEnabled()) log.debug "Purged ${stale.size()} orphaned ${stateKey} entr${stale.size() == 1 ? 'y' : 'ies'}"
            }
        }
    }

    if (state.snoozed instanceof Map) {
        def snoozedCopy  = state.snoozed
        def staleSnoozed = snoozedCopy.keySet().findAll { !((it as String) in activeIds) }
        if (staleSnoozed) {
            staleSnoozed.each { snoozedCopy.remove(it) }
            state.snoozed = snoozedCopy
        }
    }

    def staleSettings = settings.keySet().findAll { k ->
        def m = (k =~ /(loc|desc|protocolOverride|stateAttrOverride)_(.+)/)
        m.matches() && !(m[0][2] in activeIds)
    }
    staleSettings.each { app.removeSetting(it) }
}

def processScanChunk() {
    if (!state.isScanning) return

    def queue = state.scanQueue ?: []
    if (queue.size() == 0) {
        finalizeScan()
        return
    }

    def totalDevices = getAllMonitoredDevices().size()
    def chunkSize    = totalDevices > 200 ? 25 : 40
    def chunk        = queue.take(chunkSize)
    def remaining    = queue.drop(chunkSize)
    state.scanQueue  = remaining
    def batchNum     = Math.ceil(totalDevices / chunkSize).toInteger() - Math.ceil(remaining.size() / chunkSize).toInteger()
    log.info "Device Health Monitor: scanning batch ${batchNum} — ${chunk.size()} devices (${remaining.size()} remaining)"

    def allDevs         = getAllMonitoredDevices()
    def intervalStr     = settings?.scanInterval ?: "3"
    def intervalMinutes = (intervalStr.toFloat() * 60).toInteger()
    def minGate         = Math.min(intervalMinutes * 0.5, 30.0)
    def nowMs           = new Date().time

    chunk.each { devId ->
        def device = allDevs.find { it.id == devId }
        if (!device) return

        try {
            def id       = device.id
            def data     = state.history[id]
            def protocol = getProtocol(device)
            def filtered = usesFilteredSampling(protocol)

            def lastActivity = device.getLastActivity()
            def lastSeen     = (lastActivity ? safeTime(lastActivity) : null) ?: now()
            // v1.5.6: also consider lastKnownStateDate from previous scans
            def capMapPre  = state.deviceCapabilities ?: [:]
            def prevKnown  = capMapPre[id as String]?.lastKnownStateDate as Long ?: 0
            if (prevKnown > lastSeen) lastSeen = prevKnown

            try {
                def stateDate = device.currentStates?.collect { safeTime(it.date) }?.findAll { it }?.max()
                if (stateDate && stateDate > lastSeen) lastSeen = stateDate
                // v1.5.6: store lastKnownStateDate so refresh responses advance lastSeen (Z-Wave)
                def capMapLS  = state.deviceCapabilities ?: [:]
                def capKeyLS  = id as String
                def capDataLS = capMapLS[capKeyLS] ?: [:]
                def prevStateDate = capDataLS.lastKnownStateDate as Long ?: 0
                if (stateDate && stateDate > prevStateDate) {
                    capDataLS.lastKnownStateDate = stateDate
                    capMapLS[capKeyLS] = capDataLS
                    state.deviceCapabilities = capMapLS
                    if (stateDate > lastSeen) lastSeen = stateDate
                }
            } catch (e) {
                if (debugEnabled()) log.debug "currentStates date check error for ${device.displayName}: ${e.message}"
            }

            def capMap  = state.deviceCapabilities ?: [:]
            def capKey  = id as String
            def capData = capMap[capKey] ?: [:]

            if (isHueDevice(device)) {
                if (debugEnabled()) log.debug "DHM capability scan: ${device.displayName} detected as Hue — marking declared"
                capData.declared        = true
                capData.declaredRefresh = true
                if (capData.pingWorks == false) { capData.pingWorks = null; capData.pingFailed = 0 }
            } else if (isKonnectedDevice(device)) {
                if (debugEnabled()) log.debug "DHM capability scan: ${device.displayName} detected as Konnected child (DNI: ${device.deviceNetworkId}) — marking declared"
                capData.declared        = true
                capData.declaredRefresh = true
                if (capData.pingWorks == false) { capData.pingWorks = null; capData.pingFailed = 0 }
            } else {
                def declaredRefresh  = false
                def declaredPing     = false
                def hasCustomRefresh = false
                def capCheckOk       = false
                try { declaredRefresh  = device.hasCapability("Refresh");    capCheckOk = true } catch (e) {}
                try { declaredPing     = device.hasCapability("Ping");       capCheckOk = true } catch (e) {}
                try { hasCustomRefresh = device.hasCommand("forceRefresh") ||
                                         device.hasCommand("refresh")       ; capCheckOk = true } catch (e) {}
                capData.declaredRefresh  = declaredRefresh || hasCustomRefresh
                capData.declaredPing     = declaredPing
                capData.declared         = declaredRefresh || declaredPing || hasCustomRefresh
                capData.customRefreshCmd = hasCustomRefresh && !declaredRefresh ? "forceRefresh" : null
                if (capCheckOk && !capData.declared && capData.pingWorks == null) {
                    capData.pingWorks  = false
                    capData.pingFailed = 0
                }
            }
            if (!capData.containsKey("pingFailed")) capData.pingFailed = 0
            capMap[capKey] = capData
            state.deviceCapabilities = capMap

            if (!data) {
                state.history[id] = [
                    lastSeen:     lastSeen,
                    samples:      [],
                    avgInterval:  null,
                    userInterval: null,
                    protocol:     protocol
                ]
                state.health[id] = "Pending"
            } else {
                def prevLastSeen = data.lastSeen ?: lastSeen
                if (lastSeen > prevLastSeen) {
                    def capMapRec  = state.deviceCapabilities ?: [:]
                    def capKeyRec  = id as String
                    def capDataRec = capMapRec[capKeyRec] ?: [:]
                    if (capDataRec.pingAttempted == true) {
                        capDataRec.pingWorks     = true
                        capDataRec.pingFailed    = 0
                        capDataRec.pingAttempted = false
                        // v1.5.9: a real lastSeen advance is genuine confirmation
                        capDataRec.pingTrustSource        = "confirmed"
                        capDataRec.weakTrustFirstGranted   = null
                        capDataRec.weakTrustCooldownUntil  = null
                        capMapRec[capKeyRec]     = capDataRec
                        state.deviceCapabilities = capMapRec
                        if (debugEnabled()) log.debug "${device.displayName}: ping confirmed working — device responded after verification attempt"
                    }
                    def elapsed = ((lastSeen - prevLastSeen) / (1000 * 60)).toBigDecimal().setScale(2, BigDecimal.ROUND_HALF_UP)
                    data.lastSeen = lastSeen
                    if (elapsed >= minGate) {
                        def recordSample = true
                        if (filtered) {
                            recordSample = elapsed <= (intervalMinutes * 1.5)
                        }
                        if (recordSample) {
                            // v1.5.10: round at computation so recursive smoothing can't bloat precision
                            def alpha      = 0.15
                            def prevSmooth = (data.samples && data.samples.size() > 0) ? data.samples[-1] : elapsed
                            def smoothed   = (alpha * elapsed + (1 - alpha) * prevSmooth).setScale(2, BigDecimal.ROUND_HALF_UP)
                            data.samples << smoothed
                            if (data.samples.size() > 20) data.samples.remove(0)
                            if (data.samples.size() >= 3) {
                                data.avgInterval = (data.samples.sum() / data.samples.size()).toBigDecimal().setScale(2, BigDecimal.ROUND_HALF_UP)
                            }
                        }
                    }
                }
                data.protocol     = protocol
                state.history[id] = data
                updateHealth(device)
            }
            updateStateTracking(device)

        } catch (e) {
            log.warn "Scan failed for ${device.displayName}: ${e.message}"
        }
    }

    if (state.scanQueue.size() > 0) {
        runIn(2, "processScanChunk")
    } else {
        runIn(1, "finalizeScan")
    }
}

def finalizeScan() {
    if (!state.isScanning) return
    state.isScanning    = false
    state.scanStartTime = null
    state.tempResults   = []
    state.scanQueue     = []
    state.lastScanCompleted = now()
    log.info "Device Health Monitor: scan complete — all devices processed"
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
        state.verifying?.remove(id)
        return
    }

    def offlineThreshold     = ((settings?.offlineThresholdHours ?: 168) * 60).toDouble()
    def minutesSinceLastSeen = (now() - (data.lastSeen ?: now())) / (1000 * 60)

    if (minutesSinceLastSeen >= offlineThreshold) {
        state.health[id] = "Offline"
    } else {
        // v1.5.3: protocol-aware baseline floor so burst-use devices don't learn an unrealistically short baseline
        def protocol    = getProtocol(device)
        def minBaseline = 30.0
        switch (protocol) {
            case "LAN":
            case "Hub Mesh":
            case "Hub Mesh (Zigbee)":
            case "Hub Mesh (Z-Wave)":
            case "Hub Mesh (Matter)":
                minBaseline = 480.0
                break
            case "Matter":
                minBaseline = 120.0
                break
            case "Virtual":
            case "Hub Variable":
                minBaseline = 1440.0
                break
        }
        def baseline = Math.max(
            (data.userInterval ?: data.avgInterval ?: 60).toDouble(),
            minBaseline
        )
        def ratio = minutesSinceLastSeen / baseline
        if      (ratio <= 1.5) state.health[id] = "Excellent"
        else if (ratio <= 3.0) state.health[id] = "Good"
        else if (ratio <= 6.0) state.health[id] = "Fair"
        else                   state.health[id] = "Poor"
    }

    def currentHealth = state.health[id]

    // v1.5.3: pingable devices entering Poor are held at Fair for one scan while a ping is sent
    if (currentHealth == "Poor") {
        def prevH = state.prevHealth?.get(id as String)
        if (prevH != "Poor" && prevH != "Offline") {
            def capChk     = state.deviceCapabilities?.get(id as String) ?: [:]
            def isPingable = capChk.pingWorks == true ||
                             capChk.declared  == true ||
                             isHueDevice(device) ||
                             isKonnectedDevice(device)
            def fairHolds  = state.fairHold ?: [:]
            def alreadyHeld = fairHolds[id as String] == true
            if (isPingable && !alreadyHeld) {
                state.health[id] = "Fair"
                currentHealth    = "Fair"
                if (!state.fairHold) state.fairHold = [:]
                state.fairHold[id as String] = true
                state.fairHold = state.fairHold
                if (debugEnabled()) log.debug "${device.displayName}: first Poor entry — holding at Fair for one scan pending verification ping"
            } else if (alreadyHeld) {
                def fh = state.fairHold ?: [:]
                fh.remove(id as String)
                state.fairHold = fh
                if (debugEnabled()) log.debug "${device.displayName}: fairHold expired — promoting to Poor"
            }
        } else {
            def fh = state.fairHold ?: [:]
            if (fh.containsKey(id as String)) { fh.remove(id as String); state.fairHold = fh }
        }
    } else if (currentHealth in ["Good", "Excellent", "Pending"]) {
        def fh = state.fairHold ?: [:]
        if (fh.containsKey(id as String)) { fh.remove(id as String); state.fairHold = fh }
    }

    // v1.5.5-1.5.9: verified devices are capped at Fair (Quiet). Trust expires after the Offline
    // Threshold; weak trust (refresh/ping merely didn't throw) is also capped at 2x the threshold,
    // then forced to show a real Poor/Offline for a full threshold window before it can return.
    if (currentHealth in ["Poor", "Offline"]) {
        def capChk = state.deviceCapabilities?.get(id as String) ?: [:]
        if (capChk.pingWorks == true) {
            def isWeak        = capChk.pingTrustSource == "weak"
            def weakCeilingMs = getWeakTrustCeilingMs()
            def weakExceeded  = isWeak && capChk.weakTrustFirstGranted &&
                                 (now() - (capChk.weakTrustFirstGranted as Long)) >= weakCeilingMs

            def pingAge      = now() - (capChk.lastPingAttempt as Long ?: 0)
            def maxPingAgeMs = ((settings?.offlineThresholdHours ?: 168) * 3600000L)

            if (weakExceeded) {
                def capMap  = state.deviceCapabilities ?: [:]
                def capData = capMap[id as String] ?: [:]
                capData.pingWorks              = null
                capData.pingFailed             = 0
                capData.pingTrustSource        = null
                capData.weakTrustFirstGranted  = null
                capData.weakTrustCooldownUntil = now() + maxPingAgeMs
                capMap[id as String] = capData
                state.deviceCapabilities = capMap
                if (debugEnabled()) log.debug "${device.displayName}: weak trust ceiling reached (${(weakCeilingMs/3600000).setScale(0, BigDecimal.ROUND_HALF_UP)}h) with no genuine confirmation — forcing real ${currentHealth} for at least ${(maxPingAgeMs/3600000).setScale(0, BigDecimal.ROUND_HALF_UP)}h"
            } else if (pingAge < maxPingAgeMs) {
                state.health[id] = "Fair"
                currentHealth    = "Fair"
                if (debugEnabled()) log.debug "${device.displayName}: capped at Fair — verified reachable (ping age ${(pingAge/3600000).setScale(1, BigDecimal.ROUND_HALF_UP)}h, trust=${capChk.pingTrustSource ?: 'confirmed'})"
            } else {
                // Trust expired; weakTrustFirstGranted is kept so the cumulative weak clock keeps running
                def capMap  = state.deviceCapabilities ?: [:]
                def capData = capMap[id as String] ?: [:]
                capData.pingWorks  = null
                capData.pingFailed = 0
                capMap[id as String] = capData
                state.deviceCapabilities = capMap
                if (debugEnabled()) log.debug "${device.displayName}: pingWorks trust expired (${(pingAge/3600000).setScale(1, BigDecimal.ROUND_HALF_UP)}h old) — clearing for fresh verification"
            }
        }
    }

    def prevHealth = state.prevHealth?.get(id as String)
    if (currentHealth in ["Poor", "Offline"] && !(prevHealth in ["Poor", "Offline"])) {
        def dropMap  = state.dropHistory ?: [:]
        def drops    = dropMap[id as String] ?: []
        drops << now()
        drops = drops.findAll { now() - it < 86400000 }
        dropMap[id as String] = drops
        state.dropHistory = dropMap
    }

    // v1.5.2/1.5.9: recovery to Good/Excellent resets verification and clears weak-trust tracking
    if (currentHealth in ["Good", "Excellent"] && prevHealth in ["Poor", "Offline"]) {
        def capMapR  = state.deviceCapabilities ?: [:]
        def capKeyR  = id as String
        def capDataR = capMapR[capKeyR] ?: [:]
        if (capDataR.pingWorks == false) {
            capDataR.pingWorks  = null
            capDataR.pingFailed = 0
            if (debugEnabled()) log.debug "${device.displayName}: health recovered to ${currentHealth} — verification status reset for fresh re-evaluation"
        }
        capDataR.pingTrustSource        = "confirmed"
        capDataR.weakTrustFirstGranted  = null
        capDataR.weakTrustCooldownUntil = null
        capMapR[capKeyR]    = capDataR
        state.deviceCapabilities = capMapR
    }

    if (!state.prevHealth) state.prevHealth = [:]
    def prevMap = state.prevHealth
    prevMap[id as String] = currentHealth
    state.prevHealth = prevMap

    if (!(currentHealth in ["Poor", "Offline"])) {
        state.verifying?.remove(id)
        return
    }

    if (state.verifying == null) state.verifying = [:]

    if (getStateVerified(id as String)) {
        state.verifying[id] = "state_verified"
        log.info "Device Health Monitor: ${currentHealth} — ${device.displayName} self-verified via state change event (no ping needed)"
        def capMapSV  = state.deviceCapabilities ?: [:]
        def capDataSV = capMapSV[id as String] ?: [:]
        if (capDataSV.pingTrustSource == "weak" || capDataSV.weakTrustFirstGranted || capDataSV.weakTrustCooldownUntil) {
            capDataSV.pingTrustSource        = "confirmed"
            capDataSV.weakTrustFirstGranted  = null
            capDataSV.weakTrustCooldownUntil = null
            capMapSV[id as String] = capDataSV
            state.deviceCapabilities = capMapSV
        }
        if (data?.samples?.size() > 0) {
            data.samples.remove(data.samples.size() - 1)
            if (data.samples.size() >= 3) {
                data.avgInterval = (data.samples.sum() / data.samples.size()).toBigDecimal().setScale(2, BigDecimal.ROUND_HALF_UP)
            }
            state.history[id] = data
        }
        return
    }

    def protocol     = getProtocol(device)
    def isVirtual    = protocol in ["Virtual", "Hub Variable"]
    def hasRefresh   = false
    def hasPing      = false
    def verifyMethod = ""

    if (isVirtual) {
        verifyMethod = "virtual"
    } else if (isHueDevice(device)) {
        def bridge = findHueBridge()
        if (bridge) {
            try {
                bridge.refresh()
                verifyMethod = "hue_bridge"
                markChildrenPingAttempted(bridge.id as String)
            }
            catch (e) { verifyMethod = "hue_bridge_failed" }
        } else {
            verifyMethod = "hue_no_bridge"
        }
    } else if (isKonnectedDevice(device)) {
        def panel = findKonnectedPanel(device)
        if (panel) {
            try {
                panel.refresh()
                verifyMethod = "konnected_panel"
                markChildrenPingAttempted(panel.id as String)
            }
            catch (e) { verifyMethod = "konnected_panel_failed" }
        } else {
            verifyMethod = "konnected_no_panel"
        }
    } else {
        try { hasRefresh = device.hasCapability("Refresh") } catch (e) { }
        try { hasPing    = device.hasCapability("Ping")    } catch (e) { }
        def hasCustomRefresh = false
        try { hasCustomRefresh = device.hasCommand("forceRefresh") || device.hasCommand("refresh") } catch (e) {}
        if (hasRefresh) {
            try { device.refresh(); verifyMethod = "refresh" }
            catch (e) { verifyMethod = "failed" }
        } else if (hasPing) {
            try { device.ping(); verifyMethod = "ping" }
            catch (e) { verifyMethod = "failed" }
        } else if (hasCustomRefresh) {
            try {
                if (device.hasCommand("forceRefresh")) { device.forceRefresh(); verifyMethod = "refresh" }
                else { device.refresh(); verifyMethod = "refresh" }
            } catch (e) { verifyMethod = "failed" }
        } else {
            verifyMethod = "none"
        }
    }
    state.verifying[id] = verifyMethod
    if (verifyMethod in ["refresh", "ping"]) {
        log.info "Device Health Monitor: ${currentHealth} — sent ${verifyMethod} to ${device.displayName}"
    } else if (verifyMethod in ["none", "virtual", "hue_no_bridge", "failed"]) {
        if (debugEnabled()) log.debug "Device Health Monitor: ${currentHealth} — cannot verify ${device.displayName} (${verifyMethod})"
    }

    def capMapH  = state.deviceCapabilities ?: [:]
    def capKeyH  = id as String
    def capDataH = capMapH[capKeyH] ?: [:]
    if (verifyMethod in ["hue_bridge", "konnected_panel"]) {
        // v1.5.8: a clean Bridge/Panel round-trip is proof of reachability; confirm immediately
        capDataH.lastPingAttempt    = now()
        capDataH.pingAttempted      = false
        capDataH.pingWorks          = true
        capDataH.pingFailed         = 0
        capDataH.pingTrustSource        = "confirmed"
        capDataH.weakTrustFirstGranted  = null
        capDataH.weakTrustCooldownUntil = null
        state.health[id] = "Fair"
        if (debugEnabled()) log.debug "${device.displayName}: ${verifyMethod} succeeded — confirmed reachable immediately (Bridge/Panel proxy verification)"
    } else if (verifyMethod in ["refresh", "ping"]) {
        // v1.5.9: generic refresh/ping is only weak proof; skip while in post-ceiling cooldown
        capDataH.lastPingAttempt = now()
        capDataH.pingAttempted   = true
        def inCooldown = capDataH.weakTrustCooldownUntil &&
                         now() < (capDataH.weakTrustCooldownUntil as Long)
        if (!inCooldown) {
            if (capDataH.pingTrustSource != "weak" || !capDataH.weakTrustFirstGranted) {
                capDataH.weakTrustFirstGranted = now()
            }
            capDataH.pingWorks       = true
            capDataH.pingTrustSource = "weak"
            capDataH.pingFailed      = 0
            state.health[id] = "Fair"
            if (debugEnabled()) log.debug "${device.displayName}: ${verifyMethod} succeeded — granting provisional Quiet status pending genuine confirmation"
        } else if (debugEnabled()) {
            log.debug "${device.displayName}: ${verifyMethod} succeeded but in post-ceiling cooldown — holding at real ${currentHealth} for visibility"
        }
    } else if (verifyMethod in ["none", "virtual", "hue_no_bridge", "hue_bridge_failed",
                                 "konnected_no_panel", "konnected_panel_failed", "failed"]) {
        capDataH.pingWorks       = false
        capDataH.pingFailed      = (capDataH.pingFailed ?: 0) + 1
        capDataH.pingTrustSource = null
    }
    capMapH[capKeyH]         = capDataH
    state.deviceCapabilities = capMapH

    if (currentHealth == "Offline" &&
        isLowActivity(id as String) &&
        verifyMethod in ["none", "virtual", "hue_no_bridge", "hue_bridge_failed", "failed"]) {
        state.health[id] = "Poor"
        if (debugEnabled()) log.debug "${device.displayName}: Low activity + unverifiable — capped at Poor instead of Offline"
    }
}

// ============================================================
// ===================== HEALTH INFO (DISPLAY) ===============
// ============================================================
/** Structured health for the UI: label, pill tone, and an optional short note. */
private Map healthInfo(device) {
    def id      = device.id as String
    def h       = state.health?.get(device.id) ?: "Pending"
    def samples = state.history?.get(device.id)?.samples?.size() ?: 0
    if (isDeviceSnoozed(id)) return [label: "Snoozed", tone: "gray", note: formatSnoozeRemaining(id), rank: 0]
    if (h == "Pending")      return [label: "Pending", tone: "gray", note: "${Math.min(samples, 3)}/3 samples", rank: 1]

    def tags = []
    if (isRepeatDrops(id)) tags << "🔄 Repeat drops"
    else if (isLowActivity(id) && h in ["Fair", "Poor", "Offline"]) tags << "Low activity"

    if (h in ["Poor", "Offline"]) {
        def vm = state.verifying?.get(device.id)
        def vNote = [
            state_verified:         "✅ State verified",
            refresh:                "Verifying (refresh sent)",
            ping:                   "Verifying (ping sent)",
            hue_bridge:             "Verifying via Hue Bridge",
            hue_no_bridge:          "Add Hue Bridge to verify",
            hue_bridge_failed:      "Hue Bridge refresh failed",
            konnected_panel:        "Verifying via Konnected Panel",
            konnected_no_panel:     "Add Konnected Panel to verify",
            konnected_panel_failed: "Konnected Panel refresh failed",
            virtual:                "Virtual, can't verify",
            none:                   "Can't verify (no ping or refresh)",
            failed:                 "Verification command failed"
        ][vm]
        if (vNote) tags << vNote
        return [label: h, tone: "red", note: tags.join(" · "), rank: h == "Offline" ? 6 : 5]
    }

    def cap = state.deviceCapabilities?.get(id) ?: [:]
    def ext = getExtendedStateNote(device)
    if (ext) tags << ext
    if (h == "Fair" && cap.pingWorks == true) {
        tags << (cap.pingTrustSource == "weak" ? "responded to refresh, unconfirmed" : "verified reachable")
        return [label: "Quiet", tone: "blue", note: tags.join(" · "), rank: 2]
    }
    if (h == "Fair") return [label: "Fair", tone: "amber", note: tags.join(" · "), rank: 4]
    return [label: h, tone: "green", note: tags.join(" · "), rank: h == "Good" ? 3 : 2]
}

/** Offline, Poor, or Fair that isn't Quiet-verified or snoozed. */
private boolean isActiveIssue(device) {
    def h = state.health?.get(device.id) ?: "Pending"
    return h in ["Offline", "Poor", "Fair"] && !isQuietVerified(device.id as String) && !isDeviceSnoozed(device.id as String)
}

private String verificationPill(deviceId) {
    def cap = state.deviceCapabilities?.get(deviceId as String) ?: [:]
    switch (getPingStatus(deviceId)) {
        case "verified":     return cap.pingTrustSource == "weak" ? bmPill("Verified (auto)", "blue") : bmPill("Verified", "green")
        case "unverifiable": return bmPill("Can't verify", "gray")
        case "declared":     return bmPill("Verifiable", "amber")
        default:             return ""
    }
}

private String statePill(Map stateInfo) {
    if (!stateInfo) return "<span class='bm-muted'>—</span>"
    def tone = ["#c62828": "red", "#e65100": "amber", "#1565c0": "blue", "#8b5cf6": "blue", "#16a34a": "green"][stateInfo.color]
    return tone ? bmPill(bmEsc(stateInfo.label), tone) : "<span>${bmEsc(stateInfo.label)}</span>"
}

private String shortProtocol(String p) {
    def m = (p =~ /Hub Mesh \((.+)\)/)
    return m.matches() ? "Mesh ${m[0][1]}" : p
}

private String protocolPill(device) {
    def p = getProtocol(device)
    def c = getProtocolColor(p)
    def ovr = settings["protocolOverride_${device.id}"] && settings["protocolOverride_${device.id}"] != "Auto-detect"
    return "<span class='bm-pill' style='background:${c}22;color:${c};' title='${bmEsc(p)}'>${bmEsc(shortProtocol(p))}${ovr ? ' ⚙' : ''}</span>"
}

// ============================================================
// ===================== SAFE HELPERS ========================
// ============================================================
def safeTime(ts) {
    if (ts == null) return null
    if (ts instanceof Number) return ts.toLong()
    try {
        def t = ts?.time
        if (t instanceof Number) return t.toLong()
        def s = t?.toString() ?: ts?.toString()
        if (!s) return null
        // Scientific notation (e.g. "72E3") from some device firmware
        if (s.isNumber()) return new BigDecimal(s).toLong()
        return null
    } catch (e) {
        return null
    }
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

def formatInterval(minutes) {
    if (!minutes) return "—"
    def m = minutes.toInteger()
    if (m < 60)   return "${m}m"
    if (m < 1440) return "${(m / 60).toInteger()}h ${m % 60}m"
    return "${(m / 1440).toInteger()}d ${((m % 1440) / 60).toInteger()}h"
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

def forceRefreshEndpoint() {
    try {
        runIn(1, "scanAllDevices", [overwrite: true])
        return render(contentType: "text/html", data: getPortalRedirectHtml(3000, "Running health scan..."), status: 200)
    } catch (e) {
        log.error "Device Health Monitor portal refresh error: ${e}"
        return render(contentType: "text/html", data: "Error: ${e.message}", status: 500)
    }
}

def updateDeviceEndpoint() {
    try {
        def dId = params?.deviceId
        if (dId) {
            if (params.loc != null) {
                setDeviceLocation(dId, params.loc)
            }
            if (params.desc != null) {
                if (params.desc == "") app.removeSetting("desc_${dId}")
                else app.updateSetting("desc_${dId}", [type: "text", value: params.desc])
            }
            if (debugEnabled()) log.debug "Portal updated device ${dId}: loc=${params.loc} desc=${params.desc}"
        }
        return render(contentType: "application/json", data: '{"success":true}', status: 200)
    } catch (e) {
        log.error "Device Health Monitor updateDevice error: ${e}"
        return render(contentType: "application/json", data: '{"error":"'+e+'"}', status: 500)
    }
}

// ============================================================
// ===================== PORTAL ENDPOINT: DATA ===============
// ============================================================
def serveDataEndpoint() {
    try {
        def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
        def roomOptions  = getRoomOptions()

        def estate = devList.collect { device ->
            def data         = state.history?.get(device.id)
            def h            = state.health?.get(device.id) ?: "Pending"
            def protocol     = getProtocol(device)
            def snoozed      = isDeviceSnoozed(device.id as String)
            def lastSeenMs   = data?.lastSeen ? (data.lastSeen as Long) : 0
            def lastSeenStr  = lastSeenMs ? formatTimeAgo(lastSeenMs) : "Never"
            def avgIntStr    = data?.userInterval ? formatInterval(data.userInterval) + " (manual)" :
                               data?.avgInterval  ? formatInterval(data.avgInterval) : "Learning..."
            def stateInfo    = getCurrentStateDisplay(device)
            def stateLabel   = stateInfo?.label ?: "—"
            def stateColor   = stateInfo?.color ?: "#c0c4cc"
            def isAlert      = stateInfo?.isAlert ?: false
            def tracked      = state.stateHistory?.get(device.id as String)
            def lastChanged  = tracked?.lastChanged ? formatTimeAgo(tracked.lastChanged as Long) : "—"
            def loc          = getDeviceLocation(device.id)
            def desc         = settings["desc_${device.id}"] ?: ""
            def verifyMethod = state.verifying?.get(device.id)
            def hasOverride  = settings["protocolOverride_${device.id}"] &&
                               settings["protocolOverride_${device.id}"] != "Auto-detect"
            def trustSource  = state.deviceCapabilities?.get(device.id as String)?.pingTrustSource ?: ""

            [
                id:              device.id,
                name:            device.displayName,
                health:          h,
                protocol:        protocol,
                protocolColor:   getProtocolColor(protocol),
                hasOverride:     hasOverride,
                snoozed:         snoozed,
                snoozeRemaining: snoozed ? formatSnoozeRemaining(device.id as String) : "",
                lastSeen:        lastSeenStr,
                lastSeenMs:      lastSeenMs,
                avgInterval:     avgIntStr,
                stateLabel:      stateLabel,
                stateColor:      stateColor,
                stateAlert:      isAlert,
                lastChanged:     lastChanged,
                location:        loc,
                description:     desc,
                pingStatus:      getPingStatus(device.id),
                pingTrustSource: trustSource,
                repeatDrops:     isRepeatDrops(device.id as String),
                extStateTag:     getExtendedStateTag(device),
                verifyMethod:    verifyMethod ?: "",
                lowActivity:     isLowActivity(device.id as String)
            ]
        }

        def healthOrder = ["Offline": 1, "Poor": 2, "Fair": 3, "Good": 4, "Excellent": 5, "Pending": 6]
        estate = estate.sort { a, b ->
            def pA = healthOrder[a.health] ?: 6
            def pB = healthOrder[b.health] ?: 6
            if (pA != pB) return pA <=> pB
            return a.name <=> b.name
        }

        def payload = [
            token:      state.accessToken,
            lastScan:   state.lastScanCompleted ? new Date(state.lastScanCompleted as Long).format("MM/dd/yyyy h:mm a", location.timeZone) : "not yet",
            locations:  roomOptions,
            isScanning: state.isScanning ?: false,
            estate:     estate
        ]

        return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(payload), status: 200)

    } catch (e) {
        log.error "Device Health Monitor data endpoint error: ${e}"
        return render(contentType: "application/json", data: '{"error":"Data unavailable"}', status: 500)
    }
}

// ============================================================
// ===================== PORTAL ENDPOINT: DASHBOARD (SPA) ====
// ============================================================
def serveDashboardPage() {
    try {
        def css = """
body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;padding:20px;background:#0d0d0d;color:#e0e0e0;margin:0}
.container{max-width:860px;margin:0 auto;background:#151515;padding:25px;border-radius:12px;box-sizing:border-box}
.loader{border:4px solid #333;border-top:4px solid #3b82f6;border-radius:50%;width:40px;height:40px;animation:spin 1s linear infinite;margin:30px auto}
@keyframes spin{0%{transform:rotate(0deg)}100%{transform:rotate(360deg)}}
h2{text-align:center;color:#fff;margin:0 0 4px 0}
.subtitle{text-align:center;font-size:12px;color:#666;margin-bottom:20px}
.summary-box{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:20px}
.summary-card{flex:1;min-width:90px;box-sizing:border-box;background:#1e1e1e;padding:12px;border-radius:8px;text-align:center;border-bottom:3px solid #333}
.summary-card b{display:block;font-size:22px;color:#fff;margin-bottom:4px}
.summary-card span{font-size:11px;color:#aaa;text-transform:uppercase}
.top-bar{display:flex;gap:10px;margin-bottom:12px;flex-wrap:wrap}
.btn{flex:1;background:#1f618d;color:#fff;border:none;padding:13px 16px;border-radius:8px;text-align:center;text-decoration:none;font-weight:600;cursor:pointer;font-size:13px;display:block}
.btn:hover{background:#1a5276}
.filter-bar{display:flex;gap:10px;align-items:center;margin-bottom:16px;flex-wrap:wrap}
.filter-bar input[type=text]{flex:1;min-width:160px;padding:9px 12px;border-radius:8px;border:1px solid #333;background:#1e1e1e;color:#e0e0e0;font-size:13px}
.filter-bar label{display:flex;align-items:center;gap:6px;font-size:13px;color:#ccc;cursor:pointer;white-space:nowrap}
details{margin-bottom:12px}
summary{padding:10px 14px;background:#1c1c1c;border-radius:6px;border-left:4px solid #3b82f6;cursor:pointer;color:#fff;font-weight:bold;font-size:15px;list-style:none}
summary:hover{background:#252525}
.cat-count{float:right;font-size:12px;color:#888;margin-top:2px}
.dev-card{background:#222;padding:12px 14px;border-radius:8px;margin-bottom:10px;border-left:4px solid #333}
.health-Offline{border-left-color:#991b1b;background:linear-gradient(90deg,rgba(153,27,27,.12) 0%,#222 30%)}
.health-Poor{border-left-color:#ef4444;background:linear-gradient(90deg,rgba(239,68,68,.1) 0%,#222 30%)}
.health-Fair{border-left-color:#f97316;background:linear-gradient(90deg,rgba(249,115,22,.1) 0%,#222 30%)}
.health-Good{border-left-color:#22c55e}
.health-Excellent{border-left-color:#22c55e}
.health-Pending{border-left-color:#94a3b8}
.health-Snoozed{border-left-color:#8b5cf6;opacity:0.7}
.row{display:flex;align-items:flex-start;gap:10px}
.dev-name{font-size:14px;font-weight:bold;color:#fff}
.dev-meta{font-size:11px;color:#888;margin-top:3px}
.dev-state{display:inline-block;padding:2px 8px;border-radius:8px;font-size:11px;font-weight:700;margin-top:4px}
.proto-tag{display:inline-block;font-size:10px;font-weight:700;padding:1px 6px;border-radius:4px;margin-left:6px;vertical-align:middle}
.empty{text-align:center;color:#777;padding:20px;font-size:13px}
.modal-overlay{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,.75);z-index:1000;align-items:center;justify-content:center;padding:20px;box-sizing:border-box}
.modal{background:#1a1a1a;border:1px solid #333;border-radius:12px;padding:22px;width:100%;max-width:500px;max-height:90vh;overflow-y:auto;position:relative}
.modal-title{font-size:17px;font-weight:bold;color:#fff;margin-bottom:15px;border-bottom:1px solid #333;padding-bottom:10px}
.modal-row{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #1e1e1e;font-size:13px}
.modal-row span:first-child{color:#888}
.modal-row span:last-child{color:#fff;text-align:right;max-width:60%}
.modal-input{width:100%;padding:7px;border-radius:4px;border:1px solid #444;background:#222;color:#fff;font-size:13px;box-sizing:border-box;margin-top:3px}
.modal-input:focus{outline:none;border-color:#3b82f6}
.modal-btns{display:flex;gap:10px;margin-top:15px}
.modal-save{flex:1;padding:11px;background:#22c55e;color:#fff;border:none;border-radius:6px;font-weight:bold;cursor:pointer}
.modal-save:hover{background:#16a34a}
.modal-cancel{flex:1;padding:11px;background:#2c3e50;color:#fff;border:none;border-radius:6px;font-weight:bold;cursor:pointer}
.modal-cancel:hover{background:#34495e}
.scanning-badge{background:#1f618d;color:#fff;font-size:11px;padding:3px 8px;border-radius:10px;margin-left:8px}
select.top-select{background:#2c3e50;color:#fff;border:none;border-radius:8px;padding:0 12px;font-size:13px;font-weight:600;cursor:pointer;height:44px}
@media(max-width:600px){body{padding:10px}.container{padding:14px}.row{flex-wrap:wrap}.summary-card{min-width:40%}}
"""

        def js = """
const ACCESS_TOKEN = '${state.accessToken}';
let db = null;
let groupMode = 'protocol';
let query = '';
let issuesOnly = false;
try { issuesOnly = localStorage.getItem('dhmIssuesOnly') === '1'; } catch (e) {}

function load() {
    document.getElementById('app').innerHTML = "<div class='loader'></div><p style='text-align:center;color:#666;margin-top:10px;'>Loading estate data...</p>";
    fetch('data?access_token=' + ACCESS_TOKEN)
        .then(r => r.json())
        .then(data => { db = data; render(); })
        .catch(err => {
            document.getElementById('app').innerHTML = "<p style='color:#ef4444;text-align:center;padding:40px;'>Connection error — hub may be busy.<br><small>" + err + "</small></p>";
        });
}

function silentRefresh() {
    if (document.getElementById('editModal').style.display !== 'flex') {
        fetch('data?access_token=' + ACCESS_TOKEN)
            .then(r => r.json())
            .then(data => { db = data; render(); });
    }
}

function healthIcon(h) {
    return {Offline:'💀',Poor:'🔴',Fair:'🟠',Good:'🟢',Excellent:'🟢',Pending:'⏳'}[h] || '⏳';
}

function healthLabel(dev) {
    let h = dev.health;
    let icon = healthIcon(h);
    if (dev.snoozed) return "😴 Snoozed (" + dev.snoozeRemaining + ")";
    if (h === 'Pending') return "⏳ Pending";

    let suffix = '';
    if (dev.repeatDrops) suffix = ' <span style="color:#f97316;font-size:10px;">🔄 Repeat Drops</span>';
    else if (dev.lowActivity && (h === 'Fair' || h === 'Poor' || h === 'Offline')) suffix = ' <span style="color:#94a3b8;font-size:10px;">ℹ️ Low Activity</span>';

    if (h === 'Poor' || h === 'Offline') {
        let vm = dev.verifyMethod;
        let vSuffix = '';
        if (vm === 'state_verified')               vSuffix = ' <span style="color:#22c55e;font-size:10px;">✅ State verified</span>';
        else if (vm === 'refresh' || vm === 'ping') vSuffix = ' <span style="color:#1a73e8;font-size:10px;">🔄 Verifying...</span>';
        else if (vm === 'none' || vm === 'virtual') vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Cannot verify</span>';
        else if (vm === 'hue_bridge')              vSuffix = ' <span style="color:#1a73e8;font-size:10px;">🔄 Hue Bridge refresh sent</span>';
        else if (vm === 'hue_no_bridge')           vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Add Hue Bridge</span>';
        else if (vm === 'konnected_panel')         vSuffix = ' <span style="color:#1a73e8;font-size:10px;">🔄 Konnected Panel refresh sent</span>';
        else if (vm === 'konnected_no_panel')      vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Add Konnected Panel</span>';
        else if (vm === 'konnected_panel_failed')  vSuffix = ' <span style="color:#94a3b8;font-size:10px;">⚠ Konnected Panel refresh failed</span>';
        return icon + ' ' + h + suffix + vSuffix;
    }

    if (h === 'Fair' && dev.pingStatus === 'verified') {
        let quietSuffix = dev.pingTrustSource === 'weak'
            ? ' <span style="color:#94a3b8;font-size:10px;">responded to refresh, unconfirmed</span>'
            : ' <span style="color:#94a3b8;font-size:10px;">verified reachable</span>';
        return icon + ' Quiet' + suffix + quietSuffix;
    }
    return icon + ' ' + h + suffix;
}

function stateTag(dev) {
    if (dev.stateLabel === '—') return '';
    let bg = dev.stateColor === '#c62828' ? '#fee2e2;color:#b91c1c' :
             dev.stateColor === '#e65100' ? '#fff3e0;color:#c2410c' :
             dev.stateColor === '#1565c0' ? '#dbeafe;color:#1d4ed8' :
             dev.stateColor === '#8b5cf6' ? '#f3e8ff;color:#7c3aed' :
             dev.stateColor === '#16a34a' ? '#dcfce7;color:#15803d' : 'transparent;color:#4b5563';
    return "<span class='dev-state' style='background:#" + bg + ";'>" + dev.stateLabel + "</span>";
}

function protoTag(dev) {
    return "<span class='proto-tag' style='background:" + dev.protocolColor + "22;color:" + dev.protocolColor + ";'>" +
           dev.protocol + (dev.hasOverride ? ' <span style=\\"color:#94a3b8\\">⚙</span>' : '') + "</span>";
}

function card(dev) {
    let locDesc = [];
    if (dev.location) locDesc.push('🏷️ ' + dev.location);
    let pingTag = dev.pingStatus === 'verified' ? (dev.pingTrustSource === 'weak'
                    ? "<span style='color:#0ea5e9;font-size:10px;'>🔄 Verified (auto)</span>"
                    : "<span style='color:#22c55e;font-size:10px;'>✅ Verified</span>") :
                  dev.pingStatus === 'unverifiable' ? "<span style='color:#94a3b8;font-size:10px;'>⚠ Cannot verify</span>" :
                  dev.pingStatus === 'declared' ? "<span style='color:#f97316;font-size:10px;'>🔄 Verifiable</span>" : '';
    if (dev.description) locDesc.push('📝 ' + dev.description);
    let locHtml = locDesc.length ? "<div style='font-size:11px;color:#888;margin-top:2px;'>" + locDesc.join(' &nbsp;|&nbsp; ') + "</div>" : '';

    return "<div class='dev-card health-" + (dev.snoozed ? 'Snoozed' : dev.health) + "' onclick='openEdit(this)' style='cursor:pointer;' " +
           "data-id='" + dev.id + "' data-name='" + dev.name.replace(/'/g, "&#39;") + "' " +
           "data-loc='" + (dev.location || '').replace(/'/g, "&#39;") + "' " +
           "data-desc='" + (dev.description || '').replace(/'/g, "&#39;") + "' " +
           "data-health='" + dev.health + "' data-protocol='" + dev.protocol + "' " +
           "data-lastseen='" + dev.lastSeen + "' data-avginterval='" + dev.avgInterval + "'>" +
           "<div class='row'><div style='flex:1;min-width:0;'>" +
           "<div class='dev-name'>" + dev.name + protoTag(dev) + "</div>" +
           locHtml +
           "<div class='dev-health'>" + healthLabel(dev) + "</div>" +
           stateTag(dev) +
           (pingTag ? "<div style='margin-top:3px;'>" + pingTag + "</div>" : "") +
           "</div><div style='text-align:right;flex-shrink:0;font-size:11px;color:#666;min-width:80px;'>" +
           "<div>Last: " + dev.lastSeen + "</div>" +
           "<div>Avg: " + dev.avgInterval + "</div>" +
           (dev.lastChanged !== '—' ? "<div>Changed: " + dev.lastChanged + "</div>" : '') +
           "</div></div></div>";
}

function matches(d) {
    if (!query) return true;
    let hay = (d.name + ' ' + d.protocol + ' ' + (d.location || '') + ' ' + (d.description || '')).toLowerCase();
    return hay.indexOf(query) >= 0;
}

function render() {
    if (!db) return;
    let all      = db.estate || [];
    let isQuiet  = d => d.health === 'Fair' && d.pingStatus === 'verified';
    let isIssue  = d => ['Offline','Poor','Fair'].includes(d.health) && !isQuiet(d) && !d.snoozed;
    let offline  = all.filter(d => d.health === 'Offline' && !d.snoozed).length;
    let poor     = all.filter(d => d.health === 'Poor'    && !d.snoozed).length;
    let fair     = all.filter(d => d.health === 'Fair'    && !d.snoozed && !isQuiet(d)).length;
    let healthy  = all.filter(d => (['Good','Excellent'].includes(d.health) || isQuiet(d)) && !d.snoozed).length;
    let total    = all.length;
    let scanning = db.isScanning ? "<span class='scanning-badge'>🔄 Scanning</span>" : "";
    let estate   = all.filter(matches);

    let html = "<h2>📡 Device Health</h2>";
    html += "<p class='subtitle'>Last scan: " + db.lastScan + scanning + "</p>";

    html += "<div class='summary-box'>";
    html += "<div class='summary-card' style='border-bottom-color:#991b1b;'><b>" + offline + "</b><span>Offline</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#ef4444;'><b>" + poor + "</b><span>Poor</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#f97316;'><b>" + fair + "</b><span>Fair</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#22c55e;'><b>" + healthy + "</b><span>Healthy</span></div>";
    html += "<div class='summary-card' style='border-bottom-color:#3b82f6;'><b>" + total + "</b><span>Total</span></div>";
    html += "</div>";

    html += "<div class='top-bar'>";
    html += "<a href='refresh?access_token=" + ACCESS_TOKEN + "' class='btn'>🔄 Force Scan</a>";
    html += "<select class='top-select' onchange='changeGroup(this.value)'>";
    html += "<option value='protocol' " + (groupMode==='protocol'?'selected':'') + ">📡 By Protocol</option>";
    html += "<option value='health'   " + (groupMode==='health'  ?'selected':'') + ">❤️ By Health</option>";
    html += "<option value='location' " + (groupMode==='location'?'selected':'') + ">🏷️ By Location</option>";
    html += "</select>";
    html += "</div>";

    html += "<div class='filter-bar'>";
    html += "<input type='text' id='q' placeholder='Search name, protocol, location' value='" + query.replace(/'/g, "&#39;") + "' oninput='setQuery(this.value)'>";
    html += "<label><input type='checkbox' " + (issuesOnly ? 'checked' : '') + " onchange='setIssues(this.checked)'>Issues only</label>";
    html += "</div>";

    let issues = estate.filter(isIssue);
    if (issues.length) {
        html += "<details open><summary style='border-left-color:#ef4444;'>⚠️ Active Issues <span class='cat-count'>" + issues.length + " devices</span></summary><div style='padding-top:10px;'>";
        issues.forEach(d => html += card(d));
        html += "</div></details>";
    } else if (issuesOnly) {
        html += "<div class='empty'>" + (query ? "No matching devices need attention." : "✅ No devices need attention.") + "</div>";
    }

    if (!issuesOnly) {
        let snoozed = estate.filter(d => d.snoozed);
        if (snoozed.length) {
            html += "<details><summary style='border-left-color:#8b5cf6;'>😴 Snoozed <span class='cat-count'>" + snoozed.length + "</span></summary><div style='padding-top:10px;'>";
            snoozed.forEach(d => html += card(d));
            html += "</div></details>";
        }

        let healthy_devs = estate.filter(d => (!['Offline','Poor','Fair'].includes(d.health) || isQuiet(d)) && !d.snoozed);
        let groups = {};
        healthy_devs.forEach(d => {
            let k = groupMode === 'protocol' ? d.protocol : groupMode === 'health' ? d.health : (d.location || 'Unassigned');
            if (!groups[k]) groups[k] = [];
            groups[k].push(d);
        });
        Object.keys(groups).sort().forEach(gName => {
            html += "<details" + (query ? " open" : "") + "><summary style='border-left-color:#3b82f6;'>" + gName + " <span class='cat-count'>" + groups[gName].length + " devices</span></summary><div style='padding-top:10px;'>";
            groups[gName].forEach(d => html += card(d));
            html += "</div></details>";
        });
        if (!estate.length) html += "<div class='empty'>No devices match your search.</div>";
    }

    document.getElementById('app').innerHTML = html;
    let box = document.getElementById('q');
    if (box && document.activeElement !== box && query) { box.focus(); box.setSelectionRange(query.length, query.length); }
}

function setQuery(v) { query = (v || '').toLowerCase(); render(); }
function setIssues(v) { issuesOnly = v; try { localStorage.setItem('dhmIssuesOnly', v ? '1' : '0'); } catch (e) {} render(); }
function changeGroup(mode) { groupMode = mode; render(); }

function openEdit(card) {
    document.getElementById('editDeviceId').value = card.getAttribute('data-id');
    document.getElementById('editDeviceName').innerText = card.getAttribute('data-name');
    document.getElementById('modalHealth').innerText    = card.getAttribute('data-health') || '—';
    document.getElementById('modalProtocol').innerText  = card.getAttribute('data-protocol') || '—';
    document.getElementById('modalLastSeen').innerText  = card.getAttribute('data-lastseen') || '—';
    document.getElementById('modalAvgInt').innerText    = card.getAttribute('data-avginterval') || '—';

    let locSel = document.getElementById('editLoc');
    locSel.innerHTML = '<option value="">— Unassigned —</option>';
    (db.locations || []).forEach(l => {
        let opt = new Option(l, l);
        if (l === card.getAttribute('data-loc')) opt.selected = true;
        locSel.add(opt);
    });
    let current = card.getAttribute('data-loc');
    if (current && !Array.from(locSel.options).some(o => o.value === current)) {
        let newOpt = new Option(current, current, false, true);
        locSel.add(newOpt);
        locSel.value = current;
    }

    document.getElementById('editDesc').value = card.getAttribute('data-desc') || '';
    document.getElementById('editModal').style.display = 'flex';
}

function saveEdit() {
    let btn = document.querySelector('.modal-save');
    btn.innerText = '⏳ Saving...'; btn.disabled = true;
    let dId  = document.getElementById('editDeviceId').value;
    let loc  = document.getElementById('editLoc').value;
    let desc = document.getElementById('editDesc').value;
    fetch('updateDevice?deviceId=' + dId + '&loc=' + encodeURIComponent(loc) + '&desc=' + encodeURIComponent(desc) + '&access_token=' + ACCESS_TOKEN)
        .then(r => r.json())
        .then(() => {
            btn.innerText = '💾 Save'; btn.disabled = false;
            let dev = db.estate.find(d => d.id == dId);
            if (dev) { dev.location = loc; dev.description = desc; }
            closeEdit(); render();
        })
        .catch(() => { btn.innerText = '💾 Save'; btn.disabled = false; alert('Save failed.'); });
}

function closeEdit() { document.getElementById('editModal').style.display = 'none'; }
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeEdit(); });
document.addEventListener('DOMContentLoaded', load);
setInterval(silentRefresh, 60000);
"""

        def html = "<!DOCTYPE html><html><head>" +
                   "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                   "<title>Device Health Portal</title>" +
                   "<style>${css}</style></head><body>" +
                   "<div class='container'><div id='app'></div></div>" +
                   "<div id='editModal' class='modal-overlay' onclick='closeEdit()'>" +
                   "<div class='modal' onclick='event.stopPropagation()'>" +
                   "<div class='modal-title'>📡 <span id='editDeviceName'></span></div>" +
                   "<input type='hidden' id='editDeviceId'>" +
                   "<div style='background:#1a1a1a;border-radius:6px;padding:10px;margin-bottom:14px;font-size:12px;'>" +
                   "<div class='modal-row' style='border-bottom:1px solid #222;'><span>Health</span><span id='modalHealth'>—</span></div>" +
                   "<div class='modal-row' style='border-bottom:1px solid #222;'><span>Protocol</span><span id='modalProtocol'>—</span></div>" +
                   "<div class='modal-row' style='border-bottom:1px solid #222;'><span>Last Check-in</span><span id='modalLastSeen'>—</span></div>" +
                   "<div class='modal-row'><span>Avg Check-in</span><span id='modalAvgInt'>—</span></div>" +
                   "</div>" +
                   "<div class='modal-row'><span>Location</span><span style='width:60%;'><select id='editLoc' class='modal-input'></select></span></div>" +
                   "<div class='modal-row'><span>Description</span><span style='width:60%;'><input type='text' id='editDesc' class='modal-input' placeholder='Optional notes...'></span></div>" +
                   "<div class='modal-btns'><button class='modal-save' onclick='saveEdit()'>💾 Save</button><button class='modal-cancel' onclick='closeEdit()'>Cancel</button></div>" +
                   "</div></div>" +
                   "<script>${js}</script></body></html>"

        return render(contentType: "text/html", data: html, status: 200)

    } catch (Exception e) {
        log.error "Device Health Monitor portal error: ${e}"
        return render(contentType: "text/html", data: "<h3 style='color:white;font-family:sans-serif;'>Portal Error</h3><p style='color:#ccc;'>${e}</p>", status: 500)
    }
}

// ============================================================
// ===================== SCHEDULED SUMMARY ===================
// ============================================================
/** Returns true only when a notification was actually sent. Manual sends skip mode restriction. */
def scheduledSummary(boolean manual = false) {
    if (settings?.enablePush != true) {
        if (debugEnabled()) log.debug "Notifications are off — skipping summary"
        return false
    }
    if (!manual && !isModeOK()) return false
    def devList = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devList) return false

    def usePushover = (settings?.enablePushover == true && settings?.pushoverPrefix?.trim())
    def prefix      = ""
    def postfix     = ""
    if (usePushover) {
        def tags = settings.pushoverPrefix.trim()
        def priorityMatch = tags =~ /^(\[[EHLNS]\])(.*)/
        if (priorityMatch) { prefix = priorityMatch[0][1]; postfix = priorityMatch[0][2].trim() }
        else { postfix = tags }
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
                def stateInfo = getCurrentStateDisplay(device)
                def stateStr  = stateInfo ? " [${stateInfo.label}]" : ""
                def lastStr   = state.history?.get(device.id)?.lastSeen
                    ? ", last seen ${formatTimeAgo(state.history[device.id].lastSeen)}" : ""
                sections[h].list << "${device.displayName.trim()}${stateStr}${lastStr}"
            }
        }
    }

    if (settings?.suppressEmptyReport) {
        def hasContent = sections.any { h, data -> data.enabled && data.list }
        if (!hasContent) return false
    }

    sections.each { health, data ->
        if (data.enabled) {
            body += "\n${data.emoji} ${health}:\n"
            if (data.list) { data.list.each { name -> body += "• ${name}\n" } }
            else { body += "None\n" }
        }
    }

    def pushoverBody = body
    def plainBody    = body
    if (postfix) pushoverBody += "${postfix}\n"

    if (settings?.enablePush)      sendPush(pushoverBody)
    if (settings?.pushoverDevices) settings.pushoverDevices.each { it.deviceNotification(pushoverBody) }
    if (settings?.notifyDevices)   notifyDevices.each { it.deviceNotification(plainBody) }
    return true
}

/** Send Now button: immediate, ignores mode restriction, reports what actually happened. */
private void sendNotificationNow() {
    if (!getAllMonitoredDevices()) {
        state.sendMsg = [tone: "err", text: "No monitored devices are selected yet."]
        return
    }
    if (settings?.enablePush != true) {
        state.sendMsg = [tone: "err", text: "Turn notifications on first."]
        return
    }
    def sent = false
    try {
        sent = scheduledSummary(true) == true
    } catch (e) {
        log.warn "Device Health Monitor: send now failed: ${e.message}"
        state.sendMsg = [tone: "err", text: "Sending failed. Check the logs."]
        return
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
// ===================== BUTTON HANDLER ======================
// ============================================================
void appButtonHandler(String btn) {
    switch (btn) {
        case "daSnooze":     state.daPending = [action: "snooze",   deviceId: state.daDeviceId]; break
        case "daUnsnooze":   state.daPending = [action: "unsnooze", deviceId: state.daDeviceId]; break
        case "daReset":      state.daPending = [action: "reset",    deviceId: state.daDeviceId]; break
        case "daCancel":     state.remove("daPending"); break
        case "daConfirm":    runDeviceAction(); break
        case "bulkSelOffline": bulkQuickSelect("offline"); break
        case "bulkSelPoor":    bulkQuickSelect("poor"); break
        case "bulkSelIssues":  bulkQuickSelect("issues"); break
        case "bulkSelClear":   app.updateSetting("bulkSelectedDevices", [value: [], type: "enum"]); break
        case "bulkApply":    runBulkAction(); break
        case "sendNow":      sendNotificationNow(); break
        case "btnRunDeepScan":
            runDeepVerificationScan()
            state.deepMsg = [tone: "ok", text: "Deep verification started. Results appear here in a minute or two."]
            break
        case "snoozeClearAll":
            state.snoozed = [:]
            state.snoozeMsg = [tone: "ok", text: "All snoozes ended."]
            break
    }
}

// ============================================================
// ===================== MAIN PAGE ===========================
// ============================================================
private String dhmBannerHtml() {
    def devs = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    if (!devs) return statusBannerHtml(false, "Setup required", "Choose <b>Monitored devices</b> below, then tap <b>Done</b>")
    int off = 0, poor = 0, fair = 0, snz = 0
    devs.each { d ->
        try {
            if (isDeviceSnoozed(d.id as String)) { snz++; return }
            if (!isActiveIssue(d)) return
            def h = state.health?.get(d.id)
            if (h == "Offline") { off++ } else if (h == "Poor") { poor++ } else { fair++ }
        } catch (e) { }
    }
    def parts = ["${countText(devs.size(), 'device')} monitored"]
    if (off)  parts << "${off} offline"
    if (poor) parts << "${poor} poor"
    if (fair) parts << "${fair} fair"
    if (snz)  parts << "${snz} snoozed"
    if (state.isScanning) parts << "🔄 scanning"
    boolean ok = !(off || poor || fair)
    return statusBannerHtml(ok, ok ? "No issues found" : "Attention needed", parts.join(" &middot; "))
}

private String stOn(String t = "On")   { "<span style='color:#1e7b34;font-weight:600;'>${t}</span>" }
private String stOff(String t = "Off") { "<span style='color:#b42318;font-weight:600;'>${t}</span>" }
private String stWarn(String t)        { "<span style='color:#9a5b00;'>${t}</span>" }

def mainPage() {
    applyCustomLabel()
    def devCount  = getAllMonitoredDevices().size()
    def notifOn   = settings?.enablePush == true
    def freq      = [daily: "daily", every2: "every 2 days", every3: "every 3 days", weekly: "weekly"][settings?.reportFrequency ?: "daily"]
    def scanLabel = ["0.5": "every 30 min", "1": "hourly", "3": "every 3 hours", "6": "every 6 hours"][settings?.scanInterval ?: "3"]
    def threshold = settings?.offlineThresholdHours ?: 168
    def snoozedN  = (state.snoozed ?: [:]).count { k, v -> v >= now() }
    def deepOn    = settings?.enableDeepScan == true
    def deepLast  = state.deepScanResult?.ranAt ? new Date(state.deepScanResult.ranAt as Long).format("MMM d", location.timeZone) : null
    def locCount  = getRoomOptions().size()
    def portalOn  = state.accessToken != null
    def modeOn    = settings?.enableModeRestriction == true && settings?.restrictedModes

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, dhmBannerHtml()
        }

        section(title: "<b>Reports</b>", sectionClass: "bm-cards bm-cards-primary") {
            href(name: "toSummary", page: "summaryPage",
                 title: "<i class='fa-solid fa-heart-pulse' aria-hidden='true'></i>Summary and health",
                 description: "Needs attention, plus every device's health and state", width: 4, style: "margin:8px;")
            href(name: "toVerification", page: "verificationPage",
                 title: "<i class='fa-solid fa-circle-check' aria-hidden='true'></i>Verification",
                 description: "Which devices can be confirmed reachable", width: 4, style: "margin:8px;")
            href(name: "toDevManage", page: "deviceManagePage",
                 title: "<i class='fa-solid fa-screwdriver-wrench' aria-hidden='true'></i>Device management",
                 description: "Locations, snooze, reset, and detection fixes", width: 4, style: "margin:8px;")
        }

        section(title: "<b>Settings</b>", sectionClass: "bm-settings") {
            href(name: "toDevices", page: "devicesPage",
                 title: "<i class='fa-solid fa-list-check' aria-hidden='true'></i>Monitored devices",
                 description: devCount ? "${devCount} selected" : stOff("None selected"),
                 width: 12, style: "margin:0;")
            href(name: "toNotifications", page: "notificationsPage",
                 title: "<i class='fa-solid fa-bell' aria-hidden='true'></i>Notifications",
                 description: notifOn ?
                     "${stOn()}, ${freq}${settings?.summaryTime ? '' : ', ' + stWarn('no time set')}${modeOn ? ' · selected modes only' : ''}" :
                     stOff(),
                 width: 12, style: "margin:0;")
            href(name: "toScanSettings", page: "scanSettingsPage",
                 title: "<i class='fa-solid fa-clock' aria-hidden='true'></i>Scan and thresholds",
                 description: "${scanLabel.capitalize()} · offline after ${threshold}h",
                 width: 12, style: "margin:0;")
            href(name: "toSnoozeSettings", page: "snoozeSettingsPage",
                 title: "<i class='fa-solid fa-bell-slash' aria-hidden='true'></i>Snooze",
                 description: snoozeEnabled() ?
                     "${stOn()}, ${settings?.snoozeDurationHours ?: 24}h${snoozedN ? ' · ' + stWarn("${snoozedN} snoozed") : ''}" :
                     stOff(),
                 width: 12, style: "margin:0;")
            href(name: "toDeepScan", page: "deepScanPage",
                 title: "<i class='fa-solid fa-magnifying-glass' aria-hidden='true'></i>Deep verification",
                 description: deepOn ? stWarn("Scheduled") : (deepLast ? "Last run ${deepLast}" : "Never run"),
                 width: 12, style: "margin:0;")
            href(name: "toLocations", page: "locationsPage",
                 title: "<i class='fa-solid fa-tags' aria-hidden='true'></i>Locations",
                 description: locCount ? "${locCount} defined" : "None yet",
                 width: 12, style: "margin:0;")
            href(name: "toPortal", page: "portalPage",
                 title: "<i class='fa-solid fa-globe' aria-hidden='true'></i>Web portal",
                 description: portalOn ? stOn() : "${stOff()} · needs OAuth",
                 width: 12, style: "margin:0;")
            href(name: "toAppName", page: "appNamePage",
                 title: "<i class='fa-solid fa-pen' aria-hidden='true'></i>App name",
                 description: bmEsc(app.label ?: "Device Health Monitor"),
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
    def all = getAllMonitoredDevices()
    dynamicPage(name: "devicesPage", title: "Monitored Devices", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>Choose the devices to monitor. Protocol is detected automatically.</div>"
            input "monitoredDevices", "capability.*", title: "Devices to monitor",
                  multiple: true, required: false, submitOnChange: true
            paragraph rawHtml: true, "<div class='bm-msg bm-msg-warn'>After changing devices, tap <b>Done</b> on the main page to save before opening reports.</div>"
        }
        if (all) {
            def protos = all.collect { getProtocol(it) }
            def groups = [
                ["Zigbee",       ["Zigbee", "Hub Mesh (Zigbee)"], "#3b82f6"],
                ["Z-Wave",       ["Z-Wave", "Hub Mesh (Z-Wave)"], "#8b5cf6"],
                ["Matter",       ["Matter", "Hub Mesh (Matter)"], "#e65100"],
                ["Hub Mesh",     ["Hub Mesh"],                    "#06b6d4"],
                ["LAN",          ["LAN"],                         "#14b8a6"],
                ["Virtual",      ["Virtual"],                     "#ec4899"],
                ["Hub Variable", ["Hub Variable"],                "#eab308"],
                ["Unknown (skipped)", ["Unknown"],                "#9ca3af"]
            ]
            def chips = groups.collect { g ->
                def n = protos.count { it in g[1] }
                n ? "<span class='bm-pill' style='background:${g[2]}22;color:${g[2]};margin:0 6px 6px 0;'>${g[0]} ${n}</span>" : ""
            }.join("")
            def unresolvable = all.count { isUnresolvableProtocol(getRawProtocol(it)) }
            def hints = ""
            if (unresolvable) hints += "<div class='bm-msg bm-msg-info' style='margin-top:8px;'>${unresolvable} device(s) show as Hub Mesh, LAN, Virtual, or Hub Variable. " +
                "Check them in <b>Device actions</b> with the <b>Connection type unsure</b> filter.</div>"
            if (all.any { isHueDevice(it) } && !findHueBridge()) hints += "<div class='bm-msg bm-msg-info' style='margin-top:8px;'>Hue devices found. " +
                "Add your <b>Hue Bridge</b> to monitored devices so Poor/Offline Hue devices can be verified.</div>"
            section("<b>By protocol</b>") {
                paragraph rawHtml: true, "<div>${chips}</div>${hints}"
            }
        }
    }
}

def notificationsPage() {
    def notifOn = settings?.enablePush == true
    dynamicPage(name: "notificationsPage", title: "Notifications", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() + "<style>.bm-btn-align { padding-top: 22px; box-sizing: border-box; }</style>"
            input "enablePush", "bool", title: "Enable notifications", defaultValue: false, submitOnChange: true, width: 4
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
                input "pushoverDevices", "capability.notification", title: "Pushover devices", multiple: true, required: false, width: 6
                if (settings?.enablePushover) {
                    input "pushoverPrefix", "text", title: "Pushover tags",
                          description: "e.g. [H][TITLE=Device Health Report][HTML][SELFDESTRUCT=43200]", required: false
                }
                input "enableModeRestriction", "bool", title: "Only send scheduled summaries in certain modes",
                      defaultValue: false, submitOnChange: true
                if (settings?.enableModeRestriction) {
                    input "restrictedModes", "mode", title: "Modes", multiple: true, required: false
                }
            }
            section("<b>What to include</b>") {
                input "notifyOffline",       "bool", title: "💀 Offline",   defaultValue: true,  width: 4
                input "notifyPoor",          "bool", title: "🔴 Poor",      defaultValue: true,  width: 4
                input "notifyFair",          "bool", title: "🟠 Fair",      defaultValue: true,  width: 4
                input "notifyGood",          "bool", title: "🟢 Good",      defaultValue: false, width: 4
                input "notifyExcellent",     "bool", title: "🟢 Excellent", defaultValue: false, width: 4
                input "suppressEmptyReport", "bool", title: "🔕 Skip when nothing to report", defaultValue: false, width: 4
            }
        }
    }
}

def scanSettingsPage() {
    dynamicPage(name: "scanSettingsPage", title: "Scan and Thresholds", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss()
            input "scanInterval", "enum", title: "Scan interval",
                  description: "How often device activity is checked and health is updated.",
                  options: ["0.5": "Every 30 Minutes", "1": "Hourly", "3": "Every 3 Hours", "6": "Every 6 Hours"],
                  defaultValue: "3", submitOnChange: true
            input "offlineThresholdHours", "number", title: "Offline after (hours without activity)",
                  description: "Default 168 (7 days). Also sets how long a verification is trusted.",
                  defaultValue: 168, required: true, submitOnChange: true
            paragraph rawHtml: true, "<div class='bm-hint'>Changes take effect when you tap <b>Done</b> on the main page.</div>"
        }
    }
}

def snoozeSettingsPage() {
    def snoozed = getAllMonitoredDevices().findAll { isDeviceSnoozed(it.id as String) }
                    .sort { a, b -> a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase() }
    dynamicPage(name: "snoozeSettingsPage", title: "Snooze", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>Snoozed devices stay monitored but are left out of notifications. Snooze or end a snooze from <b>Device actions</b> or <b>Bulk actions</b>.</div>"
            input "enableSnooze", "bool", title: "Enable snooze", defaultValue: false, submitOnChange: true
            if (snoozeEnabled()) {
                input "snoozeDurationHours", "number", title: "Snooze length (hours)", defaultValue: 24, required: true, width: 6
            }
        }
        if (snoozeEnabled()) {
            section("<b>Currently snoozed</b> ${bmPill("${snoozed.size()}", snoozed ? "amber" : "gray")}") {
                def smsg = state.remove("snoozeMsg")
                if (smsg) paragraph rawHtml: true, bmMsgHtml(smsg)
                if (!snoozed) {
                    paragraph rawHtml: true, "<div class='bm-hint'>No devices are snoozed.</div>"
                } else {
                    paragraph rawHtml: true, "<table class='bm-table'>" + snoozed.collect { d ->
                        "<tr><td>${hubLink("/device/edit/${d.id}", bmEsc(d.displayName))}</td><td style='text-align:right;' class='bm-muted'>${formatSnoozeRemaining(d.id as String)}</td></tr>"
                    }.join("") + "</table>" + hubLinkScript()
                    input "snoozeClearAll", "button", title: "End all snoozes", width: 3, styleClass: "bm-btn"
                }
            }
        }
    }
}

def deepScanPage() {
    def r = state.deepScanResult
    def last = r?.ranAt ? new Date(r.ranAt as Long).format("MMM d, h:mm a", location.timeZone) +
        " · ${r.verified} verified, ${r.unverifiable} can't verify, ${r.declared} still pending" : "Never run"
    dynamicPage(name: "deepScanPage", title: "Deep Verification", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>Pings every device that hasn't been verified yet, sorting them into Verified or Can't verify. It runs once, then turns itself off.</div>" +
                "<div style='margin-top:8px;'><b>Last run:</b> ${last}</div>"
            def dmsg = state.remove("deepMsg")
            if (dmsg) paragraph rawHtml: true, bmMsgHtml(dmsg)
            input "btnRunDeepScan", "button", title: "▶ Run now", width: 3, styleClass: "bm-btn"
            input "enableDeepScan", "bool", title: "Schedule a run (once, then turns off)", defaultValue: false, submitOnChange: true
            if (settings?.enableDeepScan) {
                input "deepScanTime", "time", title: "Run at", required: true, width: 6
            }
            href name: "tipsVerify", page: "tipsPage", params: [topic: "verify"],
                 title: "<i class='pi pi-info-circle' aria-hidden='true'></i>How verification works",
                 description: "State events, refresh/ping, Hue and Konnected", width: 6, style: "margin:8px;"
        }
    }
}

def locationsPage() {
    int lastFilled = 0
    (1..30).each { i -> if ((settings["loc${i}"] ?: "").trim()) lastFilled = i }
    int shown = Math.min(30, Math.max(lastFilled + 3, 6))
    dynamicPage(name: "locationsPage", title: "Locations", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>Rooms or areas used to group devices in the portal and on the Summary. " +
                "Assign devices in <b>Device actions</b>, <b>Bulk actions</b>, or by tapping a device in the portal. More boxes appear as you fill them.</div>"
            (1..shown).each { i ->
                input "loc${i}", "text", title: "Location ${i}", required: false, width: 4, submitOnChange: (i == shown)
            }
        }
    }
}

def portalPage() {
    def portalOn = state.accessToken != null
    dynamicPage(name: "portalPage", title: "Web Portal", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-hint'>A live dashboard of every device. Tap a device there to set its location or note. Add either link to a dashboard Link tile for one-tap access.</div>"
            if (portalOn) {
                def cloudUrl = "${getFullApiServerUrl()}/dashboard?access_token=${state.accessToken}"
                def localUrl = "${getFullLocalApiServerUrl()}/dashboard?access_token=${state.accessToken}"
                paragraph rawHtml: true, "<div class='bm-msg bm-msg-info' style='word-break:break-all;'>" +
                    "<b>Cloud (anywhere):</b><br><a href='${cloudUrl}' target='_blank'>${cloudUrl}</a><br><br>" +
                    "<b>Local (at home):</b><br><a href='${localUrl}' target='_blank'>${localUrl}</a></div>"
            } else {
                paragraph rawHtml: true, "<div class='bm-msg bm-msg-err'><b>OAuth isn't enabled yet.</b> To turn on the portal:<br><br>" +
                    "1. Open <b>Apps Code</b> (" + hubLink("/app/list", "open it here") + ")<br>" +
                    "2. Open <b>Device Health Monitor</b><br>" +
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

// ============================================================
// ===================== SUMMARY PAGE ========================
// ============================================================
def summaryPage() {
    dynamicPage(name: "summaryPage", title: "Summary and Health", install: false) {
        def rows = buildDhmRows()
        section("") {
            href(name: "toForceScanFromSummary", page: "forceScanPage",
                 title: "<i class='fa-solid fa-rotate-right' style='margin-right:6px;'></i>Force scan now",
                 description: "")
            if (state.isScanning) paragraph rawHtml: true, "<div style='font-size:13px;color:#1a56c4;'>🔄 Scan in progress. Health updates as each batch completes.</div>"
            if (!rows) { paragraph "No devices yet. Choose Monitored devices on the main page, then tap Done."; return }
            paragraph rawHtml: true, dhmSummaryHtml(rows)
        }
        section("<b>📖 Legend</b>", hideable: true, hidden: true) {
            paragraph "<div style='background-color:#e8f0fe; border-left:4px solid #1a73e8; padding:8px 12px; font-size:13px; color:#1a1a1a;'>" +
                      "<b>Health</b> compares time since the last check-in with the device's usual check-in: Excellent up to 1.5x, Good up to 3x, Fair up to 6x, then Poor. " +
                      "<b>Offline</b> means no activity for the offline threshold. <b>Quiet</b> is Fair but confirmed reachable. " +
                      "<b>Pending</b> is still learning (3 samples needed).<br><br>" +
                      "Tap a count to filter, a column header to sort, or search by name, protocol, or location. <b>Issues only</b> is remembered in this browser." +
                      "</div>"
        }
    }
}

private List buildDhmRows() {
    return getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }.collect { d ->
        def r = [dev: d, name: d.displayName ?: "Unknown Device", hi: [label: "Pending", tone: "gray", note: "", rank: 1],
                 issue: false, h: "Pending", snoozed: false, lastMs: 0, usual: null, stateInfo: null, loc: "", protocol: ""]
        try {
            r.hi        = healthInfo(d)
            r.issue     = isActiveIssue(d)
            r.h         = state.health?.get(d.id) ?: "Pending"
            r.snoozed   = isDeviceSnoozed(d.id as String)
            def data    = state.history?.get(d.id)
            r.lastMs    = data?.lastSeen ? (data.lastSeen as Long) : 0
            r.usual     = data?.userInterval ? formatInterval(data.userInterval) : data?.avgInterval ? formatInterval(data.avgInterval) : null
            r.stateInfo = getCurrentStateDisplay(d)
            r.loc       = getDeviceLocation(d.id)
            r.protocol  = getProtocol(d)
        } catch (e) {
            log.warn "Device Health Monitor: summary row failed for ${d.displayName}: ${e.message}"
        }
        r
    }
}

private String dhmSummaryHtml(List rows) {
    int nOff  = rows.count { it.issue && it.h == "Offline" }
    int nPoor = rows.count { it.issue && it.h == "Poor" }
    int nFair = rows.count { it.issue && it.h == "Fair" }
    int nSnz  = rows.count { it.snoozed }
    def nowMs = now()
    def nameLink = { r -> hubLink("/device/edit/${r.dev.id}", bmEsc(r.name)) }
    def seenText = { r -> r.lastMs ? formatTimeAgo(r.lastMs) : "Never" }

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
<style>
  .bm-table .bm-pill { font-size: 10px; padding: 1px 7px; }
  .bm-table td { padding: 5px 8px; line-height: 1.35; }
  .bm-table .bm-note { margin-top: 1px; }
</style>
<div class='bm-wrap'>
<div class='bm-stats'>
  <div class='bm-stat' data-f='offline'><div class='bm-stat-label'>Offline</div><div class='bm-stat-num ${nOff ? "bm-c-red" : ""}'>${nOff}</div></div>
  <div class='bm-stat' data-f='poor'><div class='bm-stat-label'>Poor</div><div class='bm-stat-num ${nPoor ? "bm-c-red" : ""}'>${nPoor}</div></div>
  <div class='bm-stat' data-f='fair'><div class='bm-stat-label'>Fair</div><div class='bm-stat-num ${nFair ? "bm-c-amber" : ""}'>${nFair}</div></div>
  <div class='bm-stat' data-f='snoozed'><div class='bm-stat-label'>Snoozed</div><div class='bm-stat-num'>${nSnz}</div></div>
  <div class='bm-stat bm-on' data-f='all'><div class='bm-stat-label'>Total</div><div class='bm-stat-num'>${rows.size()}</div></div>
</div>
"""

    def attention = rows.findAll { it.issue }.sort { a, b -> (b.hi.rank <=> a.hi.rank) ?: (a.name <=> b.name) }
    if (!attention) {
        sb << "<div class='bm-ok'><i class='fa-solid fa-circle-check' style='margin-right:6px;'></i>Nothing needs attention</div>"
    } else {
        sb << "<div class='bm-headrow'><div class='bm-h'>Needs attention</div></div><div class='bm-list'>"
        attention.each { r ->
            def icon = r.h == "Offline" ? "fa-solid fa-plug-circle-xmark bm-c-red" :
                       r.h == "Poor"    ? "fa-solid fa-circle-exclamation bm-c-red" : "fa-regular fa-clock bm-c-amber"
            def sub = [bmEsc(r.protocol)]
            if (r.loc) sub << bmEsc(r.loc)
            sub << "last check-in ${seenText(r)}"
            if (r.hi.note) sub << bmEsc(r.hi.note)
            sb << "<div class='bm-item'><i class='${icon}' style='font-size:16px;width:18px;text-align:center;' aria-hidden='true'></i>" +
                  "<div class='bm-item-main'>${nameLink(r)} <span class='bm-sub'>&nbsp;${sub.join(' · ')}</span></div>" +
                  "<div style='white-space:nowrap;'>${bmPill(r.hi.label, r.hi.tone)}</div></div>"
        }
        sb << "</div>"
    }

    sb << """
<div class='bm-toolbar'><div class='bm-h'>All devices</div>
<div class='bm-tools'><label class='bm-switch'><input id='bmIssues' type='checkbox'>Issues only</label>
<input id='bmSearch' type='text' placeholder='Search devices' aria-label='Search devices'></div></div>
<table class='bm-table'>
<thead><tr>
  <th class='bm-th' data-k='name' style='width:34%;'>Device</th>
  <th class='bm-th' data-k='health' style='width:20%;'>Health</th>
  <th style='width:16%;'>State</th>
  <th class='bm-th' data-k='seen' style='width:16%;'>Last check-in</th>
  <th style='width:14%;'>Verification</th>
</tr></thead>
<tbody id='bmBody'>
"""
    rows.each { r ->
        def flags = []
        if (r.issue && r.h == "Offline") flags << "offline"
        if (r.issue && r.h == "Poor")    flags << "poor"
        if (r.issue && r.h == "Fair")    flags << "fair"
        if (r.snoozed)                   flags << "snoozed"

        def meta = protocolPill(r.dev) + (r.loc ? bmPill(bmEsc(r.loc), "gray") : "")
        def nameCell = "${nameLink(r)}${meta}"
        def showNote = r.hi.note && r.hi.tone in ["red", "amber"]
        def healthPill = r.hi.note && !showNote ?
            "<span title='${bmEsc(r.hi.note)}'>${bmPill(r.hi.label, r.hi.tone)}</span>" : bmPill(r.hi.label, r.hi.tone)
        def healthCell = healthPill + (showNote ? "<div class='bm-note bm-muted'>${bmEsc(r.hi.note)}</div>" : "")
        def seenCell = "${seenText(r)}" + (r.usual ? "<span class='bm-muted'> · every ${r.usual}</span>" : "")
        def search = "${r.name} ${r.protocol} ${r.loc ?: ''}".toLowerCase()
        def sortSeen = r.lastMs ? ((nowMs - (r.lastMs as Long)) / 1000).toLong() : 999999999L

        sb << "<tr class='bm-row' data-name=\"${bmEsc(search)}\" data-health='${r.hi.rank}' data-seen='${sortSeen}' data-flags='${flags.join(' ')}'>" +
              "<td data-label='Device'>${nameCell}</td>" +
              "<td data-label='Health'>${healthCell}</td>" +
              "<td data-label='State'>${statePill(r.stateInfo)}</td>" +
              "<td data-label='Last check-in'>${seenCell}</td>" +
              "<td data-label='Verification'>${verificationPill(r.dev.id) ?: "<span class='bm-muted'>—</span>"}</td></tr>"
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
  var f = 'all', k = 'health', asc = false;
  var issuesBox = document.getElementById('bmIssues'), key = 'dhmIssuesOnly_' + location.pathname;
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
               (!issuesBox.checked || /offline|poor|fair/.test(fl)) &&
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
    sb << hubLinkScript()
    return sb.toString()
}

// ============================================================
// ===================== VERIFICATION PAGE ===================
// ============================================================
def verificationPage() {
    def all = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    def counts = [verified: 0, declared: 0, unverifiable: 0, unknown: 0]
    all.each { d -> def s = getPingStatus(d.id); counts[s] = (counts[s] ?: 0) + 1 }
    def flagged = all.findAll { getPingStatus(it.id) == "unverifiable" && !(state.health?.get(it.id) in ["Excellent", "Good", "Pending"]) }
                     .sort { a, b -> a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase() }
    def notScanned = all.findAll { getPingStatus(it.id) == "unknown" }
                        .sort { a, b -> a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase() }

    dynamicPage(name: "verificationPage", title: "Verification", install: false) {
        section {
            def statusRows = all.sort { x, y -> x.displayName.trim().toLowerCase() <=> y.displayName.trim().toLowerCase() }.collect { d ->
                def hi  = healthInfo(d)
                def cap = state.deviceCapabilities?.get(d.id as String) ?: [:]
                "<tr class='vf-row' data-s='${getPingStatus(d.id)}' style='display:none;'>" +
                "<td>${hubLink("/device/edit/${d.id}", bmEsc(d.displayName))}</td><td>${protocolPill(d)}</td>" +
                "<td>${bmPill(hi.label, hi.tone)}</td><td>${verificationPill(d.id) ?: "<span class='bm-muted'>Not scanned</span>"}</td>" +
                "<td style='text-align:right;' class='bm-muted'>${cap.pingFailed ?: 0}</td></tr>"
            }.join("")
            paragraph rawHtml: true, bmPageCss() + """
<style>
  .vf-tile { cursor: pointer; border: 1px solid transparent; }
  .vf-tile:hover { border-color: #cfd6de; }
  .vf-tile.vf-on { background: #e8f0fe; border-color: #1a56c4; }
</style>
<div class='bm-hint' style='margin-bottom:8px;'>When a device goes Poor or Offline, the app tries to confirm it's still reachable before alerting you. Tap a count to list those devices.</div>
<div class='bm-stats' style='grid-template-columns:repeat(4,minmax(0,1fr));'>
  <div class='bm-stat vf-tile' data-s='verified'><div class='bm-stat-label'>Verified</div><div class='bm-stat-num bm-c-green'>${counts.verified}</div></div>
  <div class='bm-stat vf-tile' data-s='declared'><div class='bm-stat-label'>Verifiable</div><div class='bm-stat-num'>${counts.declared}</div></div>
  <div class='bm-stat vf-tile' data-s='unverifiable'><div class='bm-stat-label'>Can't verify</div><div class='bm-stat-num'>${counts.unverifiable}</div></div>
  <div class='bm-stat vf-tile' data-s='unknown'><div class='bm-stat-label'>Not scanned yet</div><div class='bm-stat-num'>${counts.unknown}</div></div>
</div>
<div id='vfList' style='display:none;overflow-x:auto;margin-top:10px;'>
<table class='bm-table'><thead><tr><th>Device</th><th>Protocol</th><th>Health</th><th>Verification</th><th style='text-align:right;'>Failed attempts</th></tr></thead>
<tbody>${statusRows}</tbody></table>
<div id='vfEmpty' class='bm-hint' style='display:none;padding:10px 0;'>No devices in this group.</div>
</div>
<script>
(function(){
  var tiles = document.querySelectorAll('.vf-tile'), list = document.getElementById('vfList'), empty = document.getElementById('vfEmpty');
  var cur = null;
  [].forEach.call(tiles, function(t){
    t.addEventListener('click', function(){
      cur = (cur === t.dataset.s) ? null : t.dataset.s;
      [].forEach.call(tiles, function(x){ x.classList.toggle('vf-on', x.dataset.s === cur); });
      var shown = 0;
      [].forEach.call(document.querySelectorAll('.vf-row'), function(r){
        var ok = cur && r.dataset.s === cur; r.style.display = ok ? '' : 'none'; if (ok) shown++;
      });
      list.style.display = cur ? '' : 'none';
      empty.style.display = (cur && !shown) ? '' : 'none';
    });
  });
})();
</script>
${hubLinkScript()}
"""
        }
        section("<b>Flagged devices that can't be verified</b> ${bmPill("${flagged.size()}", flagged ? "amber" : "green")}") {
            if (!flagged) {
                paragraph rawHtml: true, "<div class='bm-hint'>None. Every flagged device can be verified.</div>"
            } else {
                paragraph rawHtml: true, "<div class='bm-hint' style='margin-bottom:6px;'>These can't be pinged or refreshed, so the app can't confirm whether they're truly unreachable. Verification resets automatically when a device recovers.</div>" +
                    "<div style='overflow-x:auto;'><table class='bm-table'><thead><tr><th>Device</th><th>Protocol</th><th>Health</th><th style='text-align:right;'>Failed attempts</th></tr></thead><tbody>" +
                    flagged.collect { d ->
                        def hi = healthInfo(d)
                        def cap = state.deviceCapabilities?.get(d.id as String) ?: [:]
                        "<tr><td>${hubLink("/device/edit/${d.id}", bmEsc(d.displayName))}</td><td>${protocolPill(d)}</td>" +
                        "<td>${bmPill(hi.label, hi.tone)}</td><td style='text-align:right;' class='bm-muted'>${cap.pingFailed ?: 0}</td></tr>"
                    }.join("") + "</tbody></table></div>" + hubLinkScript()
            }
        }
        if (notScanned) {
            section("<b>Not scanned yet</b> ${bmPill("${notScanned.size()}", "gray")}", hideable: true, hidden: true) {
                paragraph rawHtml: true, "<div class='bm-hint' style='margin-bottom:6px;'>Classified after the next scan.</div>" +
                    notScanned.collect { d -> "${protocolPill(d)} ${bmEsc(d.displayName)}" }.join("<br>")
            }
        }
        section(sectionClass: "bm-cards") {
            href name: "toDeepScanFromVerify", page: "deepScanPage",
                 title: "<i class='fa-solid fa-magnifying-glass' aria-hidden='true'></i>Deep verification",
                 description: "Ping every unverified device now", width: 6, style: "margin:8px;"
            href name: "tipsVerifyFromPage", page: "tipsPage", params: [topic: "verify"],
                 title: "<i class='pi pi-info-circle' aria-hidden='true'></i>How verification works",
                 description: "Confirmed vs. provisional trust", width: 6, style: "margin:8px;"
        }
    }
}

// ============================================================
// ===================== DEVICE MANAGEMENT ===================
// ============================================================
def deviceManagePage(Map params = [:]) {
    def all = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    int review = all.count { needsOverrideReview(it) }
    int unassigned = all.count { !getDeviceLocation(it.id) }
    dynamicPage(name: "deviceManagePage", title: "Device Management", install: false) {
        section(sectionClass: "bm-cards") {
            paragraph rawHtml: true, bmPageCss()
            href name: "toDeviceActions", page: "deviceActionsPage",
                 title: "<i class='fa-solid fa-sliders' aria-hidden='true'></i>Device actions",
                 description: "Location, note, snooze, reset, and detection fixes for one device", width: 6, style: "margin:8px;"
            href name: "toBulkActions", page: "bulkActionsPage",
                 title: "<i class='fa-solid fa-layer-group' aria-hidden='true'></i>Bulk actions",
                 description: "Assign a location, snooze, or reset several at once", width: 6, style: "margin:8px;"
        }
        section {
            def notes = []
            if (unassigned) notes << "${unassigned} device${unassigned == 1 ? '' : 's'} without a location"
            if (review)     notes << "${review} with a connection type to check"
            if (notes) paragraph rawHtml: true, "<div class='bm-msg bm-msg-info'>${notes.join(' · ')}.</div>"
        }
    }
}

/** State override only helps with a real choice, or when auto-detect shows something other than the one real state. */
private boolean stateOverrideUseful(device) {
    def attrs = getMeaningfulAttributes(device)
    if (attrs.size() > 1) return true
    if (attrs.size() == 1) {
        def auto = getCurrentStateDisplay(device)
        return auto?.type && auto.type != attrs[0]
    }
    return false
}

private boolean needsOverrideReview(device) {
    def po = settings["protocolOverride_${device.id}"]
    def so = settings["stateAttrOverride_${device.id}"]
    return isUnresolvableProtocol(getRawProtocol(device)) ||
           (po && po != "Auto-detect") || (so && so != "Auto-detect")
}

private void resetDeviceHistory(device) {
    def h = state.history ?: [:]
    h[device.id] = [
        lastSeen:     now(),
        samples:      [],
        avgInterval:  null,
        userInterval: state.history?.get(device.id)?.userInterval,
        protocol:     getProtocol(device)
    ]
    state.history = h
    def health = state.health ?: [:]
    health[device.id] = "Pending"
    state.health = health
    def sh = state.stateHistory ?: [:]
    sh.remove(device.id)
    state.stateHistory = sh
    state.verifying?.remove(device.id)
}

private String bmMsgHtml(Map m) {
    if (!m) return ""
    def cls = m.tone == "ok" ? "bm-msg-ok" : m.tone == "err" ? "bm-msg-err" : "bm-msg-warn"
    return "<div class='bm-msg ${cls}'>${m.text}</div>"
}

def deviceActionsPage(params) {
    // Hubitat can resend page params on refresh, so only reset when the device actually changes
    if (params?.deviceId && (params.deviceId as String) != (state.daDeviceId as String)) {
        state.daDeviceId = params.deviceId as String
        state.remove("daPending")
        app.removeSetting("daLoc")
    }
    def all  = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    def rank = { d ->
        if (isDeviceSnoozed(d.id as String)) return 4
        if (!isActiveIssue(d)) return 3
        def hh = state.health?.get(d.id)
        return hh == "Offline" ? 0 : hh == "Poor" ? 1 : 2
    }
    def devList = all.sort { a, b -> (rank(a) <=> rank(b)) ?: (a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase()) }
    def device  = state.daDeviceId ? devList.find { (it.id as String) == (state.daDeviceId as String) } : null
    if (!device && devList) { device = devList[0]; state.daDeviceId = device.id as String }

    // Apply a location change made with the temporary daLoc input
    if (device && settings.containsKey("daLoc")) {
        def newLoc = settings.daLoc == "_none" ? "" : (settings.daLoc ?: "")
        if (newLoc != getDeviceLocation(device.id)) setDeviceLocation(device.id as String, newLoc)
        app.removeSetting("daLoc")
    }

    def msg     = state.remove("daMessage")
    def pending = state.daPending
    if (pending && (pending.deviceId as String) != (device?.id as String)) { state.remove("daPending"); pending = null }
    def rooms = getRoomOptions()

    dynamicPage(name: "deviceActionsPage", title: "Device Actions", install: false) {
        section(sectionClass: "bm-da-index") {
            paragraph rawHtml: true, bmPageCss() + daStylesHtml() +
                "<input id='daSearch' class='bm-search' type='text' placeholder='Search devices' aria-label='Search devices'>" +
                "<label style='display:flex;align-items:center;gap:6px;font-size:13px;color:#374151;margin:0 0 6px;cursor:pointer;'>" +
                "<input id='daOvr' type='checkbox' style='margin:0;'>Connection type unsure</label>"
            devList.each { d ->
                def r   = rank(d)
                def dot = r == 4 ? "#9ca3af" : r <= 1 ? "#d93025" : r == 2 ? "#e08a00" : "#22a045"
                def cur = (d.id as String) == (device?.id as String) ? "bm-da-current" : ""
                def ovr = needsOverrideReview(d) ? " bm-ovr" : ""
                href name: "daDev_${d.id}", page: "deviceActionsPage", params: [deviceId: d.id as String],
                     title: "<span class='${cur}${ovr}'><span class='bm-dot' style='background:${dot};'></span>${bmEsc(d.displayName)}${r == 4 ? ' 😴' : ''}</span>",
                     description: "", width: 12, style: "margin:0 8px;"
            }
            paragraph rawHtml: true, dhmDaScript()
        }
        section(sectionClass: "bm-da-detail") {
            if (!device) {
                paragraph "No monitored devices. Choose Monitored devices on the main page, then tap Done."
                return
            }
            def id      = device.id as String
            def hi      = healthInfo(device)
            def data    = state.history?.get(device.id)
            def lastMs  = data?.lastSeen ? (data.lastSeen as Long) : 0
            def usual   = data?.userInterval ? formatInterval(data.userInterval) : data?.avgInterval ? formatInterval(data.avgInterval) : "Learning"
            def snoozed = isDeviceSnoozed(id)
            def name    = bmEsc(device.displayName)
            def pills   = bmPill(hi.label, hi.tone) + protocolPill(device)
            if (snoozed) pills += bmPill("😴 ${formatSnoozeRemaining(id)}", "gray")

            paragraph rawHtml: true, """
<div class='bm-da-head'><div class='bm-da-title'>${hubLink("/device/edit/${device.id}", name)}</div><div style='white-space:nowrap;'>${pills}</div></div>
${hi.note ? "<div class='bm-hint' style='margin:-6px 0 8px;'>${bmEsc(hi.note)}</div>" : ""}
<div class='bm-stats'>
  <div class='bm-stat'><div class='bm-stat-label'>Last check-in</div><div class='bm-stat-num'>${lastMs ? formatTimeAgo(lastMs) : "Never"}</div></div>
  <div class='bm-stat'><div class='bm-stat-label'>Usual check-in</div><div class='bm-stat-num'>${usual == "Learning" ? usual : "every " + usual}</div></div>
  <div class='bm-stat'><div class='bm-stat-label'>Verification</div><div class='bm-stat-num'>${verificationPill(device.id) ?: "<span class='bm-muted'>—</span>"}</div></div>
</div>
${bmMsgHtml(msg)}
${hubLinkScript()}
"""
            if (rooms) {
                def cur = getDeviceLocation(device.id)
                def opts = ["_none": "— Unassigned —"] + rooms.collectEntries { [(it): it] }
                if (cur && !(cur in rooms)) opts[cur] = cur
                input "daLoc", "enum", title: "Location", options: opts, defaultValue: cur ?: "_none",
                      required: false, submitOnChange: true, width: 6
            } else {
                paragraph rawHtml: true, "<div class='bm-hint' style='padding-top:22px;'>Add locations under <b>Settings, Locations</b> to assign one.</div>", width: 6
            }
            input "desc_${device.id}", "text", title: "Description", description: "Optional note",
                  required: false, submitOnChange: true, width: 6

            def protoOvr  = (settings["protocolOverride_${device.id}"] ?: "Auto-detect") != "Auto-detect"
            def stateOvr  = (settings["stateAttrOverride_${device.id}"] ?: "Auto-detect") != "Auto-detect"
            // Protocol override only helps where auto-detect can't be sure; state override where several attributes compete
            def showProto = protoOvr || isUnresolvableProtocol(getRawProtocol(device))
            def showState = stateOvr || stateOverrideUseful(device)
            def hasOverride = protoOvr || stateOvr
            if (showProto || showState) {
                def what = showProto && showState ? "connection type or status shown" : showProto ? "connection type" : "status shown"
                if (hasOverride) {
                    paragraph rawHtml: true, "<div style='font-size:14px;padding-top:6px;'><b>Detection</b> " + bmPill("⚙ corrected by you", "blue") +
                        "<span style='font-size:13px;color:#6b7280;'> · set back to Auto-detect to undo</span></div>"
                } else {
                    input "daAdvanced", "bool", title: "Correct detection: ${what}" +
                          "<span style='font-size:13px;color:#6b7280;'> · only needed when auto-detect gets it wrong</span>",
                          defaultValue: false, submitOnChange: true
                }
                if (settings?.daAdvanced || hasOverride) {
                    if (showProto) {
                        input "protocolOverride_${device.id}", "enum", title: "Connection type (detected as ${getRawProtocol(device)})",
                              options: ["Auto-detect", "Zigbee", "Z-Wave", "Matter",
                                        "Hub Mesh (Zigbee)", "Hub Mesh (Z-Wave)", "Hub Mesh (Matter)", "Hub Mesh",
                                        "LAN", "Virtual", "Hub Variable"],
                              defaultValue: settings["protocolOverride_${device.id}"] ?: "Auto-detect", required: false, submitOnChange: true, width: 6
                    }
                    if (showState) {
                        def autoState = getCurrentStateDisplay(device)
                        input "stateAttrOverride_${device.id}", "enum", title: "Status shown (now ${bmEsc(autoState?.label ?: '—')})",
                              options: ["Auto-detect"] + getMeaningfulAttributes(device),
                              defaultValue: settings["stateAttrOverride_${device.id}"] ?: "Auto-detect", required: false, submitOnChange: true, width: 6
                    }
                }
            }

            if (pending) {
                def hrs = settings?.snoozeDurationHours ?: 24
                def ask = pending.action == "snooze"   ? "Snooze <b>${name}</b> for ${hrs} hours? It stays monitored but is left out of notifications." :
                          pending.action == "unsnooze" ? "End the snooze for <b>${name}</b> now?" :
                                                         "Reset check-in history for <b>${name}</b>? Its usual pattern is relearned from scratch (Pending until 3 samples)."
                paragraph rawHtml: true, "<div class='bm-msg bm-msg-warn'>${ask}</div>"
                input "daConfirm", "button", title: "Confirm", width: 3, styleClass: "bm-btn bm-btn-primary"
                input "daCancel",  "button", title: "Cancel",  width: 3, styleClass: "bm-btn"
            } else {
                if (snoozeEnabled()) {
                    if (snoozed) {
                        input "daUnsnooze", "button", title: "End snooze", width: 4, styleClass: "bm-btn"
                    } else {
                        input "daSnooze", "button", title: "😴 Snooze ${settings?.snoozeDurationHours ?: 24}h", width: 4, styleClass: "bm-btn"
                    }
                } else {
                    paragraph rawHtml: true, "<div class='bm-hint' style='padding-top:10px;'>Turn on snooze under <b>Settings, Snooze</b> to use it.</div>", width: 4
                }
                input "daReset", "button", title: "<i class='fa-solid fa-rotate-left' style='margin-right:6px;'></i>Reset history",
                      width: 4, styleClass: "bm-btn"
            }
        }
    }
}

private void runDeviceAction() {
    def p = state.remove("daPending")
    def device = p ? getAllMonitoredDevices().find { (it.id as String) == (p.deviceId as String) } : null
    if (!device) return
    def name = bmEsc(device.displayName)
    try {
        if (p.action == "snooze") {
            snoozeDevice(device.id as String)
            state.daMessage = [tone: "ok", text: "<b>${name}</b> snoozed for ${settings?.snoozeDurationHours ?: 24} hours.".toString()]
        } else if (p.action == "unsnooze") {
            unsnoozeDevice(device.id as String)
            state.daMessage = [tone: "ok", text: "Snooze ended for <b>${name}</b>.".toString()]
        } else if (p.action == "reset") {
            resetDeviceHistory(device)
            state.daMessage = [tone: "ok", text: "History reset for <b>${name}</b>. Relearning its pattern.".toString()]
        }
    } catch (e) {
        log.warn "Device Health Monitor: device action failed for ${device.displayName}: ${e.message}"
        state.daMessage = [tone: "err", text: "That action failed for <b>${name}</b>. Check the logs.".toString()]
    }
}

private String dhmDaScript() {
    """
<script>
(function(){
  var list = document.querySelector('.bm-da-index > .mdl-grid');
  var cur = document.querySelector('.bm-da-index .bm-da-current');
  var btn = cur ? (cur.closest('button') || cur) : null;
  if (list && btn) {
    var offset = btn.getBoundingClientRect().top - list.getBoundingClientRect().top;
    list.scrollTop += offset - list.clientHeight / 3;
  }
  var box = document.getElementById('daSearch'), ovr = document.getElementById('daOvr');
  if (!box) return;
  function apply(){
    var q = box.value.toLowerCase(), only = ovr && ovr.checked;
    [].forEach.call(document.querySelectorAll('.bm-da-index button.hrefElem'), function(b){
      var cell = b.closest('.mdl-cell') || b;
      var ok = b.textContent.toLowerCase().indexOf(q) >= 0 && (!only || b.querySelector('.bm-ovr'));
      cell.style.display = ok ? '' : 'none';
    });
  }
  box.addEventListener('input', apply);
  if (ovr) ovr.addEventListener('change', apply);
})();
</script>
"""
}

// ============================================================
// ===================== BULK ACTIONS PAGE ===================
// ============================================================
def bulkActionsPage() {
    def res      = state.remove("bulkResult")
    def devList  = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
                     .sort { a, b -> a.displayName.trim().toLowerCase() <=> b.displayName.trim().toLowerCase() }
    def actions  = [location: "Assign a location", reset: "Reset history"]
    if (snoozeEnabled()) actions = [location: "Assign a location", snooze: "Snooze", unsnooze: "End snooze", reset: "Reset history"]
    def action   = settings?.bulkAction in actions.keySet() ? settings.bulkAction : "location"
    def selected = (settings?.bulkSelectedDevices ?: []).collect { it as String }
    def rooms    = getRoomOptions()

    dynamicPage(name: "bulkActionsPage", title: "Bulk Actions", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() + "<div class='bm-h'>1. Choose an action</div>"
            input "bulkAction", "enum", title: "", options: actions, defaultValue: "location", required: true, submitOnChange: true, width: 6
            if (action == "location") {
                if (rooms) {
                    input "bulkLocTarget", "enum", title: "", description: "Choose a location",
                          options: ["_none": "— Clear location —"] + rooms.collectEntries { [(it): it] }, required: false, width: 6
                } else {
                    paragraph rawHtml: true, "<div class='bm-hint' style='padding-top:10px;'>Add locations under <b>Settings, Locations</b> first.</div>", width: 6
                }
            }
        }
        section {
            paragraph rawHtml: true, "<div class='bm-h'>2. Select devices</div><div class='bm-hint'>Quick select, or pick from the list.</div>"
            input "bulkSelOffline", "button", title: "All offline", width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelPoor",    "button", title: "All poor",    width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelIssues",  "button", title: "All issues",  width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelClear",   "button", title: "Clear",       width: 3, styleClass: "bm-btn bm-quick"
            input "bulkSelectedDevices", "enum", title: "",
                  options: devList.collectEntries { d ->
                      def tags = [healthInfo(d).label]
                      def loc = getDeviceLocation(d.id)
                      if (loc) tags << loc
                      [(d.id as String): "${d.displayName} (${tags.join(' · ')})".toString()]
                  },
                  multiple: true, required: false, submitOnChange: true
        }
        section {
            int n = selected.size()
            def target = settings?.bulkLocTarget
            def verbs = [
                location: target ? (target == "_none" ? "have their location cleared" : "be assigned to <b>${bmEsc(target)}</b>") : "be assigned a location (choose one above)",
                snooze:   "be snoozed for ${settings?.snoozeDurationHours ?: 24} hours",
                unsnooze: "have their snooze ended",
                reset:    "have check-in history reset (Pending until 3 samples)"
            ]
            def review = n ? "<b>${n}</b> device${n == 1 ? '' : 's'} will ${verbs[action]}." : "Select at least one device."
            paragraph rawHtml: true, (res ? bmMsgHtml(res) + "<div style='height:8px;'></div>" : "") +
                "<div class='bm-msg ${n ? 'bm-msg-info' : 'bm-msg-muted'}'>${review}</div>"
            input "bulkApply", "button", title: "Apply", width: 3, styleClass: "bm-btn bm-btn-primary"
        }
    }
}

private void bulkQuickSelect(String kind) {
    def ids = getAllMonitoredDevices().findAll { d ->
        if (getProtocol(d) == "Unknown") return false
        try {
            def hh = state.health?.get(d.id)
            if (kind == "issues")  return isActiveIssue(d)
            if (kind == "offline") return isActiveIssue(d) && hh == "Offline"
            if (kind == "poor")    return isActiveIssue(d) && hh == "Poor"
        } catch (e) { }
        return false
    }.collect { it.id as String }
    app.updateSetting("bulkSelectedDevices", [value: ids, type: "enum"])
}

private void runBulkAction() {
    def action  = settings?.bulkAction ?: "location"
    def ids     = (settings?.bulkSelectedDevices ?: []).collect { it as String }
    def devices = getAllMonitoredDevices().findAll { ids.contains(it.id as String) }
    if (!devices) {
        state.bulkResult = [tone: "err", text: "Select at least one device first."]
        return
    }
    if (action == "location" && !settings?.bulkLocTarget) {
        state.bulkResult = [tone: "err", text: "Choose a location first."]
        return
    }
    if (action in ["snooze", "unsnooze"] && !snoozeEnabled()) {
        state.bulkResult = [tone: "err", text: "Turn on snooze under Settings, Snooze first."]
        return
    }
    def done = [], skipped = []
    devices.each { d ->
        def idStr = d.id as String
        try {
            if (action == "location") {
                setDeviceLocation(idStr, settings.bulkLocTarget == "_none" ? "" : settings.bulkLocTarget)
                done << d.displayName
            } else if (action == "snooze") {
                if (isDeviceSnoozed(idStr)) { skipped << "${d.displayName} (already snoozed)".toString() }
                else { snoozeDevice(idStr); done << d.displayName }
            } else if (action == "unsnooze") {
                if (!isDeviceSnoozed(idStr)) { skipped << "${d.displayName} (not snoozed)".toString() }
                else { unsnoozeDevice(idStr); done << d.displayName }
            } else if (action == "reset") {
                resetDeviceHistory(d); done << d.displayName
            }
        } catch (e) {
            skipped << "${d.displayName} (error)".toString()
            log.warn "Bulk action failed for ${d.displayName}: ${e.message}"
        }
    }
    app.updateSetting("bulkSelectedDevices", [value: [], type: "enum"])
    def verb = [location: "Location updated", snooze: "Snoozed", unsnooze: "Snooze ended", reset: "History reset"][action]
    def text = done ? "${verb} for ${done.size()} device${done.size() == 1 ? '' : 's'}: ${done.collect { bmEsc(it) }.join(', ')}." : "Nothing changed."
    if (skipped) text += "<br>Skipped: ${skipped.collect { bmEsc(it) }.join(', ')}."
    state.bulkResult = [tone: done ? "ok" : "warn", text: text.toString()]
}

// ============================================================
// ===================== FORCE SCAN PAGE =====================
// ============================================================
def forceScanPage() {
    scanAllDevices()
    def devList  = getAllMonitoredDevices().findAll { getProtocol(it) != "Unknown" }
    def minGate  = Math.min(((settings?.scanInterval ?: "3").toFloat() * 60).toInteger() * 0.5, 30.0).toInteger()
    dynamicPage(name: "forceScanPage", title: "Force Scan", install: false) {
        section {
            paragraph rawHtml: true, bmPageCss() +
                "<div class='bm-msg bm-msg-ok'>Scan started for ${devList.size()} device(s). Health updates as each batch completes.</div>" +
                "<div class='bm-hint' style='margin-top:8px;'>A new check-in sample is only recorded when at least ${minGate} minutes have passed since the last recorded activity.</div>"
        }
    }
}

// ============================================================
// ===================== TIPS CONTENT ========================
// ============================================================
private List tipsTopics() {
    [
        [id: "best", label: "Tips for best results", title: "Tips for best results", group: "Getting started", icon: "pi-star",
            body: "<p>A few habits that make health ratings more accurate and alerts more useful.</p>",
            checklist: [
                [title: "Give new devices a few days", detail: "Each device needs 3 samples before it leaves Pending."],
                [title: "Add your Hue Bridge and Konnected Panel", detail: "Lets Hue bulbs and Konnected sensors be verified through the bridge."],
                [title: "Assign locations", detail: "Groups devices by room in the portal and Summary."],
                [title: "Check unsure connection types", detail: "Device actions, Connection type unsure filter."],
                [title: "Run Force Scan after updating", detail: "Refreshes every health score right away."],
                [title: "Snooze devices you're working on", detail: "They stay monitored but leave notifications alone."]
            ]],
        [id: "portal", label: "Web portal", title: "Web portal", group: "Getting started", icon: "pi-globe",
            body: "<p>A live dashboard that works on any phone, tablet, or computer. It loads instantly and fetches device data in the background, then refreshes every 60 seconds.</p>" +
                "<p>Group devices by protocol, health, or location, search, and turn on <b>Issues only</b>. Tap any device to set its location or a note.</p>",
            checklist: [
                [title: "Open Apps Code", detail: "Find Device Health Monitor in the list."],
                [title: "Enable OAuth", detail: "OAuth (top right), then Enable OAuth in App, then Update."],
                [title: "Return and tap Done", detail: "The links appear under Settings, Web portal."]
            ]],
        [id: "ratings", label: "Health ratings", title: "Health ratings", group: "Health", icon: "pi-heart",
            body: "<p>Health compares the time since a device's last check-in with its usual check-in.</p>" +
                "<table><tr><td><b>Pending</b></td><td>Still learning (3 samples needed)</td></tr>" +
                "<tr><td><b>Excellent</b></td><td>Within 1.5x of usual</td></tr>" +
                "<tr><td><b>Good</b></td><td>Within 3x of usual</td></tr>" +
                "<tr><td><b>Fair</b></td><td>Within 6x of usual</td></tr>" +
                "<tr><td><b>Quiet</b></td><td>Fair, but confirmed reachable, so it's idle rather than lost</td></tr>" +
                "<tr><td><b>Poor</b></td><td>Beyond 6x of usual</td></tr>" +
                "<tr><td><b>Offline</b></td><td>No activity for the offline threshold (default 168 hours)</td></tr></table>" +
                "<p><b>Low activity</b> marks devices monitored 7+ days with fewer than 3 samples. If they can't be verified, they're capped at Poor instead of Offline.</p>"],
        [id: "baselines", label: "Usual check-in", title: "How the usual check-in is learned", group: "Health", icon: "pi-chart-line",
            body: "<p>Each time a device checks in, the time since its previous check-in becomes a smoothed sample. Up to 20 are kept, and the average is its usual check-in.</p>" +
                "<p>A sample only counts when at least half the scan interval (up to 30 minutes) has passed. Minimum baselines keep burst-use devices from learning an unrealistically short pattern: 8 hours for LAN and Hub Mesh, 2 hours for Matter, and 24 hours for Virtual and Hub Variable.</p>"],
        [id: "quiet", label: "Quiet devices", title: "Quiet: idle, not lost", group: "Health", icon: "pi-moon",
            body: "<p>A device that would be Fair but has confirmed it's reachable shows as <b>Quiet</b> and isn't counted as an issue.</p>" +
                "<p><b>Verified reachable</b> means real confirmation: a check-in, a state event, or a Hue/Konnected bridge round-trip. " +
                "<b>Responded to refresh, unconfirmed</b> means a Zigbee or Z-Wave refresh didn't fail, which isn't proof the device received it. " +
                "That provisional trust lasts up to 2x the offline threshold, then the device shows its real status for a full threshold before it can go Quiet again.</p>"],
        [id: "verify", label: "How verification works", title: "How verification works", group: "Verification", icon: "pi-check-circle",
            body: "<p>When a device drops to Poor or Offline, the app tries to confirm it's still reachable before alerting you.</p>",
            checklist: [
                [title: "State event", detail: "A state change after its last check-in counts as proof. No ping needed."],
                [title: "Refresh or ping", detail: "Otherwise the app sends refresh() or ping()."],
                [title: "Hold at Fair", detail: "A pingable device entering Poor is held at Fair for one scan while it's checked."],
                [title: "Resets on recovery", detail: "Back to Good or Excellent, verification starts fresh next time."]
            ],
            warning: "<b>A Zigbee or Z-Wave refresh that succeeds isn't full proof.</b><br>Hubitat hands the command to the mesh without confirming delivery, so it's treated as provisional."],
        [id: "bridges", label: "Hue and Konnected", title: "Hue and Konnected devices", group: "Verification", icon: "pi-sitemap",
            body: "<p>Add your <b>Hue Bridge</b> (or CoCoHue Bridge) and <b>Konnected Alarm Panel</b> to monitored devices. " +
                "When a bulb or sensor goes Poor or Offline, the app refreshes the bridge or panel. That's a real network round-trip, so success confirms the device immediately, even if it has sat untouched for weeks.</p>"],
        [id: "locations", label: "Locations", title: "Locations", group: "Management", icon: "pi-tags",
            body: "<p>Define rooms under <b>Settings, Locations</b>. Assign them one device at a time in <b>Device actions</b>, several at once in <b>Bulk actions</b>, or by tapping a device in the portal. All three stay in sync.</p>"],
        [id: "snooze", label: "Snooze", title: "Snoozing devices", group: "Management", icon: "pi-bell-slash",
            body: "<p>Turn snooze on under <b>Settings, Snooze</b> and set its length. Then snooze a device from <b>Device actions</b> or several from <b>Bulk actions</b>. " +
                "Snoozed devices keep being monitored but are left out of notifications until the snooze ends.</p>"],
        [id: "overrides", label: "Correcting detection", title: "Correcting detection", group: "Management", icon: "pi-cog",
            body: "<p>Auto-detect gets most devices right. When it doesn't, open the device in <b>Device actions</b> and turn on <b>Correct detection</b>.</p>" +
                "<p><b>Connection type</b> is only offered for devices detected as Hub Mesh, LAN, Virtual, or Hub Variable, since Zigbee, Z-Wave, and Matter are read directly from the hub. " +
                "Turn on <b>Connection type unsure</b> in the device list to find them. A ⚙ next to the type means you set it.</p>" +
                "<p><b>Status shown</b> picks what appears in the State column. It's offered when a device reports more than one status (like a fan's switch and speed), or when the column shows something other than its one real status. Numeric readings like power or temperature don't count.</p>" +
                "<p>Set either back to <b>Auto-detect</b> to undo it.</p>"],
        [id: "scanning", label: "Scanning", title: "Batch scanning", group: "Troubleshooting", icon: "pi-sync",
            body: "<p>Devices are scanned in batches of 40 (25 on installs over 200 devices), 2 seconds apart, so health updates progressively. A scan that hasn't finished within 2 minutes is reset automatically.</p>"],
        [id: "unknown", label: "Unknown devices", title: "Devices shown as Unknown", group: "Troubleshooting", icon: "pi-question-circle",
            body: "<p>Devices whose connection type can't be read at all are skipped, and counted under <b>Monitored devices</b>. Devices detected as Hub Mesh, LAN, Virtual, or Hub Variable are still monitored; correct their connection type in <b>Device actions</b> if it's wrong.</p>"],
        [id: "logging", label: "Logging", title: "Logging", group: "Troubleshooting", icon: "pi-file",
            body: "<p>Scans log a start and finish line at info level so you can confirm they run. <b>Debug logging</b> (the toggle at the bottom of the main page) adds step-by-step detail and turns itself off after 30 minutes.</p>"]
    ]
}

// ============================================================
// ===================== SHARED UI HELPERS ===================
// ============================================================
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

private void helpAndSupportSection() {
    section(title: "<b>Help & Support</b>", sectionClass: "app-main-support") {
        href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
            page: "tipsPage", description: "Health ratings, verification, and known quirks", width: 4, style: "margin:8px;"
        paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
            "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 4
        paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
            "Buy Me a Coffee", "Support development"), width: 4
    }
}

private void versionFooterSection() {
    section {
        paragraph "<div class='text-center text-color-secondary text-xs mt-2'>Device Health Monitor v${APP_VERSION}</div>"
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

private String bmPill(String text, String tone) {
    def t = tone in ["red", "green", "amber", "gray", "blue"] ? tone : "gray"
    "<span class='bm-pill bm-t-${t}'>${text}</span>"
}

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

