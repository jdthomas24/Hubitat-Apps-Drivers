metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "Custom Integration",
        description: "Combined pool/spa controller — on/off, current temp, set point, heater mode and source"
    ) {
        attribute "switch",          "string"   // on / off
        attribute "temperature",     "number"   // current water temp
        attribute "heatingSetpoint", "number"   // active set point (LOTMP)
        attribute "maxSetTemp",      "number"   // upper limit (HITMP)
        attribute "heaterMode",      "string"   // OFF / HEAT / SOLAR / SOLAR PREFERRED / HEAT PUMP etc
        attribute "heatSource",      "string"   // raw HTSRC value
        attribute "bodyStatus",      "string"   // human readable: On / Off
        attribute "pendingOn",       "string"   // "true" while waiting for confirmation

        command "on"
        command "confirmOn"
        command "off"
        command "setHeatingSetpoint", [[name: "temperature*", type: "NUMBER", description: "New set point (°F)"]]
        command "setHeatSource", [[name: "source*", type: "ENUM", description: "Heat source",
            constraints: ["OFF", "HEATER", "SOLAR ONLY", "SOLAR PREFERRED", "HEAT PUMP", "HEAT PUMP PREFERRED"]]]
        command "refresh"
    }

    preferences {
        input "minSetPoint",     "number", title: "Minimum Set Point (°F)",     defaultValue: 40,  required: true
        input "maxSetPoint",     "number", title: "Maximum Set Point (°F)",     defaultValue: 104, required: true
        input "confirmTimeout",  "number", title: "Confirm On timeout (seconds)", defaultValue: 10, required: true
        input "debugMode",       "bool",   title: "Debug Logging",              defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Body installed: ${device.displayName}"
    sendEvent(name: "pendingOn", value: "false")
}

def updated() {
    log.info "IntelliCenter Body updated: ${device.displayName}"
}

// ============================================================
// ===================== ON / OFF WITH CONFIRMATION ==========
// ============================================================
def on() {
    if (device.currentValue("switch") == "on") {
        log.info "${device.displayName} is already on"
        return
    }
    def timeout = (confirmTimeout ?: 10).toInteger()
    if (debugMode) log.debug "on() — pending confirmation (${timeout}s)"
    sendEvent(name: "pendingOn", value: "true")
    // Auto-cancel if not confirmed in time
    runIn(timeout, cancelOn)
}

def confirmOn() {
    if (device.currentValue("pendingOn") != "true") {
        log.warn "confirmOn called but no pending on request"
        return
    }
    unschedule(cancelOn)
    sendEvent(name: "pendingOn", value: "false")
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    if (debugMode) log.debug "confirmOn() — sending ON to controller"
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
}

def cancelOn() {
    if (debugMode) log.debug "cancelOn() — confirmation timed out"
    sendEvent(name: "pendingOn", value: "false")
    log.info "${device.displayName} turn-on cancelled (not confirmed in time)"
}

def off() {
    unschedule(cancelOn)
    sendEvent(name: "pendingOn", value: "false")
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "bodyStatus", value: "Off")
    if (debugMode) log.debug "off() — sending OFF to controller"
    parent?.setBodyStatus(device.deviceNetworkId, "OFF")
}

// ============================================================
// ===================== SET POINT ===========================
// ============================================================
def setHeatingSetpoint(temperature) {
    def temp = temperature.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()

    if (temp < minT || temp > maxT) {
        log.warn "Set point ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    if (debugMode) log.debug "setHeatingSetpoint: ${temp}°F"
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)
}

// ============================================================
// ===================== HEAT SOURCE =========================
// ============================================================
def setHeatSource(source) {
    if (debugMode) log.debug "setHeatSource: ${source}"
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
}

// ============================================================
// ===================== REFRESH =============================
// ============================================================
def refresh() {
    parent?.componentRefresh(this)
}
