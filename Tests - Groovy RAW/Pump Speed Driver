metadata {
    definition(
        name: "Pentair IntelliCenter Pump",
        namespace: "intellicenter",
        author: "Custom Integration",
        description: "Pump child device for Pentair IntelliCenter — shows RPM/Watts and allows speed control"
    ) {
        attribute "rpm",   "number"
        attribute "watts", "number"
        attribute "gpm",   "number"

        command "setSpeed", [[name: "rpm*", type: "NUMBER", description: "Target RPM (450–3450)"]]
    }

    preferences {
        input "minRPM",    "number", title: "Minimum RPM", defaultValue: 450,  required: true
        input "maxRPM",    "number", title: "Maximum RPM", defaultValue: 3450, required: true
        input "debugMode", "bool",   title: "Debug Logging", defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Pump installed: ${device.displayName}"
}

def updated() {
    log.info "IntelliCenter Pump updated: ${device.displayName}"
}

// ============================================================
// ===================== COMMANDS ============================
// ============================================================
def setSpeed(rpm) {
    def target = rpm.toInteger()
    def minR   = (minRPM ?: 450).toInteger()
    def maxR   = (maxRPM ?: 3450).toInteger()

    if (target < minR || target > maxR) {
        log.warn "RPM ${target} is out of range (${minR}–${maxR}) — ignoring"
        return
    }

    if (debugMode) log.debug "setSpeed: ${target} RPM"

    // Optimistic update
    sendEvent(name: "rpm", value: target, unit: "RPM")

    // Call back to parent bridge driver
    parent?.setPumpSpeed(device.deviceNetworkId, target)
}

def refresh() {
    parent?.componentRefresh(this)
}
