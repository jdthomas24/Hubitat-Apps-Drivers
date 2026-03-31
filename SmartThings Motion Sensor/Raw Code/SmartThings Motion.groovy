/*
SmartThings Motion Sensor Enhanced

Version: 1.7.0
Author: jdthomas24
Namespace: jdthomas24

Supported Models:
- STS-IRM-250 (motionv4)
- STS-IRM-251 (motionv5)
- GP-AEOMSSUS (Aeotec Zigbee motion)
- GP-U999SJVLBAA (Samsung SmartThings motion)

Enhancements:
- Adaptive presence detection
- Ability to disable presence detection (enablePresence)
- Motion auto reset with race condition fix
- Optional temperature reporting (enableTemp)
- Battery curve calibration & smoothing
- Battery reporting interval in minutes (converted to seconds for Zigbee)
- Zigbee mesh & route health monitoring with recovery
- Temperature cluster disable optimization
- Presence logging level control (All / Info Only / None)
- Health Check ping() implementation
- Missed checkins reset on presence restore
*/

import hubitat.zigbee.clusters.iaszone.ZoneStatus
import hubitat.zigbee.zcl.DataType

def driverVersion() { return "1.7.0" }

metadata {
    definition(
        name: "SmartThings Motion Sensor Enhanced",
        namespace: "jdthomas24",
        author: "jdthomas24"
    ) {
        capability "Battery"
        capability "Configuration"
        capability "MotionSensor"
        capability "Initialize"
        capability "PresenceSensor"
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "Health Check"

        attribute "batteryVoltage",  "number"
        attribute "lastCheckin",     "string"
        attribute "lqi",             "number"
        attribute "rssi",            "number"
        attribute "presenceTimeout", "number"
        attribute "checkinInterval", "number"
        attribute "zigbeeHealth",    "string"
        attribute "missedCheckins",  "number"
        attribute "routeHealth",     "string"

        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"motionv4",        manufacturer:"SmartThings"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"motionv5",        manufacturer:"SmartThings"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"GP-AEOMSSUS",     manufacturer:"Aeotec"
        fingerprint inClusters:"0000,0001,0003,000F,0020,0402,0500", model:"GP-U999SJVLBAA", manufacturer:"Samsung"
    }

    preferences {
        input name:"motionReset",          type:"number",  title:"Motion Reset Time (seconds)",          defaultValue:60
        input name:"enableTemp",           type:"bool",    title:"Enable Temperature Reporting",         defaultValue:true
        input name:"enablePresence",       type:"bool",    title:"Enable Presence Detection",            defaultValue:true
        input name:"tempAdj",              type:"decimal", title:"Temperature Offset",                   defaultValue:0
        input name:"batteryReportMinutes", type:"enum",
              title:"Battery Reporting Interval (minutes)",
              description:"How often the device reports battery. Converted to seconds for Zigbee reporting.",
              options:["30","60","120","240","360"],
              defaultValue:"60"
        input name:"infoLogging",    type:"bool", title:"Enable Info Logging",  defaultValue:true
        input name:"debugLogging",   type:"bool", title:"Enable Debug Logging", defaultValue:false
        input name:"presenceLogging", type:"enum", title:"Presence Logging Level",
              options:["All","Info Only","None"], defaultValue:"All"
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "Installed driver v${driverVersion()}"
    initialize()
}

def initialize() {
    // Configure Zigbee reporting on install/initialize, then refresh current state
    runIn(2, configure)
    runIn(7, refresh)
}

def updated() {
    log.info "Updated driver v${driverVersion()}"
    configure()
}

def configure() {
    // Convert user-selected minutes to seconds for Zigbee reporting
    def battInterval = (batteryReportMinutes ?: "60").toInteger() * 60

    def cmds = []
    cmds += zigbee.batteryConfig()
    cmds += zigbee.configureReporting(0x0500, 0x0002, DataType.BITMAP16, 30, 3600, null)
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 30, battInterval, 1)

    if (enableTemp) {
        cmds += zigbee.temperatureConfig(30, 1800)
    } else {
        if (infoLogging) log.info "Temperature reporting disabled — skipping cluster binding"
    }

    cmds += zigbee.enrollResponse()
    sendZigbeeCommands(cmds)
}

def refresh() {
    def cmds = []
    cmds += zigbee.readAttribute(0x0001, 0x0020)  // battery voltage
    cmds += zigbee.readAttribute(0x0500, 0x0002)  // zone status
    if (enableTemp) cmds += zigbee.readAttribute(0x0402, 0x0000)  // temperature
    sendZigbeeCommands(cmds)
}

// ============================================================
// ===================== HEALTH CHECK ========================
// ============================================================
def ping() {
    if (debugLogging) log.debug "ping() — refreshing device state"
    refresh()
}

// ============================================================
// ===================== PARSE ===============================
// ============================================================
def parse(String description) {
    if (!description) return

    updatePresence()
    sendEvent(name:"lastCheckin", value:new Date().format("MM/dd/yyyy HH:mm:ss", location.timeZone))

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.lqi)  { sendEvent(name:"lqi",  value:descMap.lqi);  updateRouteHealth(descMap.lqi.toInteger()) }
    if (descMap?.rssi) { sendEvent(name:"rssi", value:descMap.rssi) }

    // Zone status — motion
    if (description.startsWith("zone status")) {
        ZoneStatus status = zigbee.parseZoneStatus(description)
        processMotion(status)
        return
    }

    // Battery voltage — cluster 0x0001 attribute 0x0020 comes back as raw value
    // zigbee.getEvent() returns name="battery" for percentage or we handle voltage manually
    if (descMap?.cluster == "0001" && descMap?.attrId == "0020") {
        def rawVolts = Integer.parseInt(descMap.value, 16) / 10.0
        def volts    = smoothBattery(rawVolts)
        sendEvent(name:"batteryVoltage", value:volts, unit:"V")
        def pct = calculateBattery(volts)
        if (device.currentValue("battery") != pct) {
            if (infoLogging) log.info "Battery ${pct}% (${volts}V)"
            sendEvent(name:"battery", value:pct, unit:"%")
        }
        return
    }

    // Temperature and other events
    def evt = zigbee.getEvent(description)
    if (!evt) return

    if (evt.name == "temperature") {
        if (!enableTemp) return
        Double offset = tempAdj ?: 0
        def temp = (evt.value + offset).round(2)
        sendEvent(name:"temperature", value:temp, unit:evt.unit)
        return
    }
}

// ============================================================
// ===================== MOTION ==============================
// ============================================================
def processMotion(ZoneStatus status) {
    if (status.isAlarm1Set()) {
        // FIX: Cancel any pending reset before starting a new one — prevents
        // inactive firing mid-motion if device triggers rapidly
        unschedule("motionInactive")
        sendEvent(name:"motion", value:"active", isStateChange:true)
        if (motionReset) runIn(motionReset.toInteger(), motionInactive)
    } else {
        motionInactive()
    }
}

def motionInactive() {
    sendEvent(name:"motion", value:"inactive", isStateChange:true)
}

// ============================================================
// ===================== PRESENCE ============================
// ============================================================
def updatePresence() {
    if (!enablePresence) return

    def nowTime = now()

    if (!state.lastCheckin) {
        state.lastCheckin = nowTime
        state.missed      = 0
        sendEvent(name:"missedCheckins", value:0)
        return
    }

    def interval = (nowTime - state.lastCheckin) / 1000
    state.lastCheckin = nowTime

    if (!state.checkinHistory) state.checkinHistory = []
    state.checkinHistory << interval
    if (state.checkinHistory.size() > 5) state.checkinHistory.remove(0)

    def avg     = state.checkinHistory.sum() / state.checkinHistory.size()
    state.avgCheckin = avg
    def timeout = (avg * 3).toInteger()

    sendEvent(name:"checkinInterval",  value:avg.toInteger())
    sendEvent(name:"presenceTimeout",  value:timeout)

    // Restore presence and reset missed count on successful checkin
    if (device.currentValue("presence") != "present") {
        sendEvent(name:"presence", value:"present")
        logPresence("Presence detected: present", "info")
    }
    // FIX: Reset missed checkins counter and restore mesh health on successful checkin
    if ((state.missed ?: 0) > 0) {
        state.missed = 0
        sendEvent(name:"missedCheckins", value:0)
        updateMeshHealth()
    }

    runIn(timeout, presenceTimeoutCheck)
}

def presenceTimeoutCheck() {
    if (!enablePresence) return

    def last    = state.lastCheckin ?: now()
    def elapsed = (now() - last) / 1000
    def timeout = state.avgCheckin ? (state.avgCheckin * 3) : 7200

    if (elapsed > timeout) {
        sendEvent(name:"presence", value:"not present")
        state.missed = (state.missed ?: 0) + 1
        sendEvent(name:"missedCheckins", value:state.missed)
        logPresence("Presence timeout: not present", "warn")
        updateMeshHealth()
    }
}

private logPresence(String message, String level = "info") {
    if (presenceLogging == "None") return
    if (presenceLogging == "Info Only" && level != "info") return
    if (level == "info")  log.info  "${device.displayName} : ${message}"
    else if (level == "warn")  log.warn  "${device.displayName} : ${message}"
    else if (level == "debug") log.debug "${device.displayName} : ${message}"
}

// ============================================================
// ===================== MESH HEALTH =========================
// ============================================================
def updateMeshHealth() {
    def missed = state.missed ?: 0
    def health = "Excellent"
    if (missed > 1) health = "Good"
    if (missed > 3) health = "Weak"
    if (missed > 5) health = "Offline"
    sendEvent(name:"zigbeeHealth", value:health)
}

def updateRouteHealth(Integer lqi) {
    def health = "Excellent"
    if (lqi < 150) health = "Good"
    if (lqi < 100) health = "Weak"
    if (lqi < 60)  health = "Poor"
    sendEvent(name:"routeHealth", value:health)
}

// ============================================================
// ===================== BATTERY =============================
// ============================================================
def calculateBattery(Double voltage) {
    if (voltage >= 3.0) return 100
    if (voltage >= 2.9) return 90
    if (voltage >= 2.8) return 80
    if (voltage >= 2.7) return 60
    if (voltage >= 2.6) return 40
    if (voltage >= 2.5) return 20
    if (voltage >= 2.4) return 10
    if (voltage >= 2.3) return 5
    return 1
}

def smoothBattery(Double voltage) {
    if (!state.lastVolt) { state.lastVolt = voltage; return voltage }
    def smoothed = (state.lastVolt + voltage) / 2
    state.lastVolt = smoothed
    return smoothed.round(2)
}

// ============================================================
// ===================== ZIGBEE SEND =========================
// ============================================================
void sendZigbeeCommands(List cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

