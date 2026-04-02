metadata {
    definition(
        name: "Pentair IntelliCenter Pump",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Variable speed pump — shows live RPM, watts and GPM, and allows speed control"
    ) {
        attribute "rpm",   "number"
        attribute "watts", "number"
        attribute "gpm",   "number"

        command "setSpeed", [[name: "rpm*", type: "NUMBER", description: "Target RPM (450–3450)"]]
        command "refresh"
    }

    preferences {
        input "minRPM",    "number", title: "Minimum RPM",   defaultValue: 450,  required: true
        input "maxRPM",    "number", title: "Maximum RPM",   defaultValue: 3450, required: true
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

/**
 * setSpeed — sends a new RPM target to the active pump program.
 * RPM is validated against the configured min/max before sending.
 * The pump card in Hubitat will update with the new RPM once the
 * controller confirms the change via a NotifyList push.
 */
def setSpeed(rpm) {
    def target = rpm.toInteger()
    def minR   = (minRPM ?: 450).toInteger()
    def maxR   = (maxRPM ?: 3450).toInteger()

    if (target < minR || target > maxR) {
        log.warn "${device.displayName}: RPM ${target} out of range (${minR}–${maxR}) — ignoring"
        return
    }

    if (debugMode) log.debug "setSpeed: ${target} RPM"

    // Optimistic local update so the UI reflects the change immediately
    sendEvent(name: "rpm", value: target, unit: "RPM")

    // Relay to bridge via parent (bridge is parent of pump devices)
    parent?.setPumpSpeed(device.deviceNetworkId, target)
}

def refresh() {
    parent?.componentRefresh(this)
}


