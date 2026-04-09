// ============================================================
// Pentair IntelliCenter Pump Driver
// Version: 1.5.8
// ============================================================
 
metadata {
    definition(
        name: "Pentair IntelliCenter Pump",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Variable speed pump — RPM, watts, GPM and water temperature display",
        version: "1.5.8"
    ) {
        capability "Switch"
 
        attribute "rpm",         "number"
        attribute "watts",       "number"
        attribute "gpm",         "number"
        attribute "temperature", "number"
        attribute "tile",        "string"
 
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
    renderTile()
}
 
def updated() {
    log.info "IntelliCenter Pump updated: ${device.displayName}"
    unschedule(disableDebugLogging)
    if (debugMode) {
        log.info "${device.displayName}: debug logging enabled — will auto-disable in 60 minutes"
        runIn(3600, disableDebugLogging)
    }
    renderTile()
}
 
def disableDebugLogging() {
    log.info "${device.displayName}: auto-disabling debug logging after 60 minutes"
    device.updateSetting("debugMode", [value: false, type: "bool"])
}
 
// ============================================================
// ===================== COMMANDS ============================
// ============================================================
def setSpeed(rpm) {
    def target = rpm.toInteger()
    def minR   = (minRPM ?: 450).toInteger()
    def maxR   = (maxRPM ?: 3450).toInteger()
 
    if (target < minR || target > maxR) {
        log.warn "${device.displayName}: RPM ${target} out of range (${minR}–${maxR}) — ignoring"
        return
    }
    if (debugMode) log.debug "setSpeed: ${target} RPM"
    sendEvent(name: "rpm", value: target, unit: "RPM")
    parent?.setPumpSpeed(device.deviceNetworkId, target)
    debounceTile()
}
 
def refresh() {
    parent?.componentRefresh(this)
    renderTile()
}
 
// Stubs required by capability "Switch" — pump on/off is controlled
// by the pool body, not directly here.
def on()  { if (debugMode) log.debug "${device.displayName}: on() stub — use body device to control pump" }
def off() { if (debugMode) log.debug "${device.displayName}: off() stub — use body device to control pump" }
 
// ============================================================
// ===================== TILE DEBOUNCE =======================
// ============================================================
def debounceTile() {
    unschedule(renderTile)
    runIn(3, renderTile)
}
 
// ============================================================
// ===================== TILE RENDERER =======================
// ============================================================
def renderTile() {
    def rpm  = device.currentValue("rpm")
    def temp = device.currentValue("temperature")
 
    def tempVal   = temp != null ? "${temp}°F" : "—°F"
    def rpmVal    = rpm  != null ? "${rpm}"    : "—"
    def isRunning = rpm  != null && rpm.toInteger() > 0
    def statusColor = isRunning ? "#4ade80" : "#64748b"
    def statusLabel = isRunning ? "● Running" : "● Off"
    def name = device.displayName
 
    // Keep switch attribute in sync with RPM so dashboard switch tiles
    // and automations see the correct on/off state
    sendEvent(name: "switch", value: isRunning ? "on" : "off")
 
    def html = "<div style='font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",sans-serif;background:#0f172a;border-radius:16px;padding:10px;color:#fff;width:100%;height:100%;margin:auto;box-sizing:border-box;'>" +
        "<div style='font-size:13px;font-weight:800;text-align:center;margin-bottom:3px;color:#e2e8f0;'>${name}</div>" +
        "<div style='text-align:center;font-size:11px;font-weight:700;margin-bottom:12px;color:${statusColor};'>${statusLabel}</div>" +
        "<div style='text-align:center;margin-bottom:8px;'>" +
            "<div style='font-size:32px;font-weight:800;color:#38bdf8;line-height:1;'>${rpmVal}</div>" +
            "<div style='font-size:10px;color:#64748b;margin-top:2px;text-transform:uppercase;letter-spacing:.5px;'>RPM</div>" +
        "</div>" +
        "<div style='display:flex;gap:6px;'>" +
            "<div style='flex:1;background:#1e3a5f;border-radius:10px;padding:6px;text-align:center;'>" +
                "<div style='font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.4px;margin-bottom:3px;'>Water</div>" +
                "<div style='font-size:12px;font-weight:700;color:#e2e8f0;'>${tempVal}</div>" +
            "</div>" +
        "</div>" +
        "</div>"
 
    sendEvent(name: "tile", value: html, displayed: false)
}
