// ============================================================
// Pentair IntelliCenter Body Driver
// Version: 1.6.2
// All files in this integration share this version number.
// ============================================================

metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Pool / Spa controller — pump, temperature and heat control",
        version: "1.6.2"
    ) {
        capability "Switch"

        attribute "switch",          "string"
        attribute "temperature",     "number"
        attribute "heatingSetpoint", "number"
        attribute "maxSetTemp",      "number"
        attribute "heaterMode",      "string"
        attribute "heatSource",      "string"
        attribute "bodyStatus",      "string"
        attribute "tile",            "string"
        attribute "heatLock",        "string"

        command "⚙ Disable Heat Lock"
        command "⚙ Enable Heat Lock"
        command "🔥 Heat and Start Pump", [[name: "degrees*", type: "NUMBER", description: "Target temp °F"]]
        command "🔴 Stop Heat and Pump"
        command "⚙ Set Heat Source", [[name: "source*", type: "ENUM",
            constraints: ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]]
        command "⚙ Stop Heat - Keep Pump On"
        command "refresh"
    }

    preferences {
        input "minSetPoint",  "number", title: "Minimum Set Point (°F)",      defaultValue: 40,  required: true
        input "maxSetPoint",  "number", title: "Maximum Set Point (°F)",       defaultValue: 104, required: true
        input "endpointBase", "text",   title: "App Endpoint Base (auto-set)", required: false
        input "debugMode",    "bool",   title: "Debug Logging (auto-disables after 60 min)", defaultValue: false
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

// ============================================================
// These must appear AFTER metadata for HPM compatibility
// ============================================================
import groovy.transform.Field

@Field static final String VERSION     = "1.6.0"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"

// ============================================================
// ===================== HELP INFO ===========================
// ============================================================
String fmtHelpInfo() {
    String info     = "Pentair IntelliCenter v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Body v1.6.0 installed: ${device.displayName}"
    sendEvent(name: "heatLock", value: "unlocked")
    renderTile()
}

def updated() {
    log.info "IntelliCenter Body v1.6.0 updated: ${device.displayName}"
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

def "🔥 Heat and Start Pump"(degrees) {
    def temp = degrees.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()
    if (temp < minT || temp > maxT) {
        log.warn "${device.displayName}: ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    if (debugMode) log.debug "${device.displayName}: Heat and Start Pump — target ${temp}°F"
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)
    def source = device.currentValue("heatSource") ?: "Heater"
    if (source == "Off" || source == "—") source = "Heater"
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
    if (debugMode) log.debug "${device.displayName}: heat source '${source}' sent with HTMODE"
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
    debounceTile()
}

def "⚙ Stop Heat - Keep Pump On"() {
    if (debugMode) log.debug "${device.displayName}: Heat Off"
    sendEvent(name: "heatSource", value: "Off")
    sendEvent(name: "heaterMode", value: "Off")
    parent?.setBodyHeatSource(device.deviceNetworkId, "Off")
    debounceTile()
}

def "🔴 Stop Heat and Pump"() {
    if (debugMode) log.debug "${device.displayName}: Stop Pump and Heat"
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "bodyStatus", value: "Off")
    parent?.setBodyStatus(device.deviceNetworkId, "OFF")
    debounceTile()
}

def on() {
    if (debugMode) log.debug "${device.displayName}: on() — starting pump"
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
    debounceTile()
}

def off() { "🔴 Stop Heat and Pump"() }

def setHeatingSetpoint(temp) {
    sendEvent(name: "heatingSetpoint", value: temp.toInteger(), unit: "°F")
    debounceTile()
}

def setHeatSource(source) {
    sendEvent(name: "heatSource", value: source)
    debounceTile()
}

def adjustSetPointUp() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    def maxT    = (maxSetPoint ?: 104).toInteger()
    def next    = Math.min(current + 1, maxT)
    sendEvent(name: "heatingSetpoint", value: next, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, next)
    debounceTile()
}

def adjustSetPointDown() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    def minT    = (minSetPoint ?: 40).toInteger()
    def next    = Math.max(current - 1, minT)
    sendEvent(name: "heatingSetpoint", value: next, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, next)
    debounceTile()
}

def "⚙ Set Heat Source"(source) {
    if (device.currentValue("heatLock") == "locked") {
        log.warn "${device.displayName} — Heat Lock is active. Use '⚙ Disable Heat Lock' command to unlock."
        return
    }
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
    if (debugMode) log.debug "${device.displayName}: heat source '${source}' sent"
    debounceTile()
}

def "⚙ Disable Heat Lock"() {
    log.info "${device.displayName} — Heat Lock removed (heating controls unlocked)"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

def "⚙ Enable Heat Lock"() {
    log.info "${device.displayName} — Heat Lock applied (heating controls locked)"
    sendEvent(name: "heatLock", value: "locked")
    debounceTile()
}

def refresh() {
    if (!device?.deviceNetworkId) {
        log.warn "${device?.displayName ?: 'Body'}: refresh skipped — device not fully initialised"
        return
    }
    if (debugMode) log.debug "${device.displayName}: refresh requested"
    parent?.componentRefresh(this)
}

def debounceTile() {
    unschedule(renderTile)
    runIn(3, renderTile)
}

// ============================================================
// ===================== TILE RENDERER =======================
// ============================================================
def renderTile() {
    def sw       = device.currentValue("switch")           ?: "off"
    def temp     = (device.currentValue("temperature")     ?: 0).toDouble()
    def setpt    = (device.currentValue("heatingSetpoint") ?: 0).toDouble()
    def htmode   = device.currentValue("heaterMode")       ?: "Off"
    def htsrc    = device.currentValue("heatSource")       ?: "Off"
    def heatLock = device.currentValue("heatLock")         ?: "unlocked"

    def isOn      = (sw == "on")
    def isHeating = isOn && htmode != "Off" && htmode != "0" && htsrc != "Off"
    def isLocked  = (heatLock == "locked")

    def name    = device.displayName
    def pumpClr = isOn      ? "#4ade80" : "#ef4444"
    def lock    = isLocked  ? " (LOCKED)" : ""
    def pumpTxt = isOn ? "On" : "Off"
    def heatClr = isHeating ? "#4ade80" : "#ef4444"
    def tNow    = Math.round(temp).toInteger()
    def tSet    = Math.round(setpt).toInteger()

    def html = "<div style='width:100%;height:100%;background:#0f172a;border-radius:16px;padding:10px;color:#fff;text-align:center;box-sizing:border-box;font-family:sans-serif;margin-left:1.5px'>" +
        "<div style='font-size:15px;font-weight:800;color:#e2e8f0;margin-bottom:4px'>${name}${lock}</div>" +
        "<div style='background:#1e3a5f;border-radius:8px;padding:8px;margin-bottom:6px'>" +
        "<table width='100%' cellpadding='0' cellspacing='0'><tr>" +
        "<td style='width:50%'><div style='font-size:9px;color:#64748b'>NOW</div><div style='font-size:28px;font-weight:800'>${tNow}&#176;</div></td>" +
        "<td style='width:50%'><div style='font-size:9px;color:#64748b'>TARGET</div><div style='font-size:28px;font-weight:800;color:#38bdf8'>${tSet}&#176;</div></td>" +
        "</tr></table></div>" +
        "<div style='font-size:10px;color:#94a3b8'>Pump:<b style='color:${pumpClr}'>${pumpTxt}</b> Src:${htsrc} Heat:<b style='color:${heatClr}'>${htmode}</b></div>" +
        "</div>"
    sendEvent(name: "tile", value: html, displayed: false)
}


