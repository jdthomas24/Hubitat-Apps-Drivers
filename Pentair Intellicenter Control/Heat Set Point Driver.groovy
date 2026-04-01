metadata {
    definition(
        name: "Pentair IntelliCenter Set Point",
        namespace: "intellicenter",
        author: "Custom Integration",
        description: "Set point child device for Pentair IntelliCenter body (pool/spa)"
    ) {
        capability "ThermostatHeatingSetpoint"

        attribute "heatingSetpoint", "number"

        command "setHeatingSetpoint", [[name: "temperature*", type: "NUMBER", description: "Set point temperature (°F)"]]
    }

    preferences {
        input "minSetPoint", "number", title: "Minimum Set Point (°F)", defaultValue: 40,  required: true
        input "maxSetPoint", "number", title: "Maximum Set Point (°F)", defaultValue: 104, required: true
        input "debugMode",   "bool",   title: "Debug Logging",          defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Set Point installed: ${device.displayName}"
}

def updated() {
    log.info "IntelliCenter Set Point updated: ${device.displayName}"
}

// ============================================================
// ===================== COMMANDS ============================
// ============================================================
def setHeatingSetpoint(temperature) {
    def temp = temperature.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()

    if (temp < minT || temp > maxT) {
        log.warn "Set point ${temp}°F is out of range (${minT}–${maxT}°F) — ignoring"
        return
    }

    if (debugMode) log.debug "setHeatingSetpoint: ${temp}°F"

    // Optimistic update
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")

    // Call back to parent bridge driver
    parent?.setBodySetPoint(device.deviceNetworkId, temp)
}

