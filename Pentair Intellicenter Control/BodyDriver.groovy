/*
    Pentair IntelliCenter - Body Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One device per body (pool, spa), created by the Bridge. on/off runs the body (pump); heat is the set
      point plus heat source. State comes from the controller through the Bridge.
     -Commands change the device only when the Bridge actually sent them; the controller's push confirms.
     -Heat Lock blocks every heat change (set point, source, Heat and Start); stopping heat still works.
     -Old emoji command names (1.6.x) stay as plain methods so existing rules keep working.
     -Units follow the hub's temperature scale. Log level comes from the app through the Bridge.
*/

metadata {
    definition(name: "Pentair IntelliCenter Body", namespace: "intellicenter", author: "jdthomas24") {
        capability "Switch"
        capability "TemperatureMeasurement"
        capability "ThermostatHeatingSetpoint"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "maxSetTemp", "number"
        attribute "heaterMode", "string"
        attribute "heatSource", "string"
        attribute "bodyStatus", "string"
        attribute "heatLock",   "string"
        attribute "tile",       "string"

        command "heatAndStart", [[name: "degrees*", type: "NUMBER", description: "Target temperature"]]
        command "stopHeat"
        command "setHeatSource", [[name: "source*", type: "ENUM",
            constraints: ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]]
        command "setHeatLock", [[name: "lock*", type: "ENUM", constraints: ["locked", "unlocked"]]]
    }

    preferences {
        input "minSetPoint", "number", title: "Lowest allowed set point", description: "Hub temperature scale (default 40°F / 4°C)", required: false
        input "maxSetPoint", "number", title: "Highest allowed set point", description: "Hub temperature scale (default 104°F / 40°C)", required: false
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.transform.Field

@Field static final String VERSION     = "1.7.1"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]

String fmtHelpInfo() {
    String info     = "Pentair IntelliCenter v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

def installed() {
    sendEvent(name: "heatLock", value: "unlocked")
    renderTile()
}

def updated() {
    cleanSettings()
    renderTile()
}

private void cleanSettings() {
    ["endpointBase", "debugMode"].each { device.removeSetting(it) }
}

def disableDebugLogging() { }   // 1.6.x job, harmless if it fires once

// ============================================================
// Commands
// ============================================================

def on() {
    if (parent?.setBodyStatus(device.deviceNetworkId, "ON")) {
        sendEvent(name: "switch", value: "on")
        sendEvent(name: "bodyStatus", value: "On")
        debounceTile()
    }
}

def off() {
    if (parent?.setBodyStatus(device.deviceNetworkId, "OFF")) {
        sendEvent(name: "switch", value: "off")
        sendEvent(name: "bodyStatus", value: "Off")
        debounceTile()
    }
}

def setHeatingSetpoint(temp) {
    if (locked()) return
    Integer t = inRange(temp)
    if (t == null) return
    if (parent?.setBodySetPoint(device.deviceNetworkId, t)) {
        sendEvent(name: "heatingSetpoint", value: t, unit: unit())
        debounceTile()
    }
}

def heatAndStart(degrees) {
    if (locked()) return
    Integer t = inRange(degrees)
    if (t == null) return
    String source = device.currentValue("heatSource")
    if (!source || source in ["Off", "—"]) source = "Heater"
    String dni = device.deviceNetworkId
    if (parent?.setBodySetPoint(dni, t) && parent?.setBodyHeatSource(dni, source) && parent?.setBodyStatus(dni, "ON")) {
        sendEvent(name: "heatingSetpoint", value: t, unit: unit())
        sendEvent(name: "heatSource", value: source)
        sendEvent(name: "switch", value: "on")
        sendEvent(name: "bodyStatus", value: "On")
        debounceTile()
    }
}

def stopHeat() {
    if (parent?.setBodyHeatSource(device.deviceNetworkId, "Off")) {
        sendEvent(name: "heatSource", value: "Off")
        sendEvent(name: "heaterMode", value: "Off")
        debounceTile()
    }
}

def setHeatSource(source) {
    if (locked()) return
    if (parent?.setBodyHeatSource(device.deviceNetworkId, source as String)) {
        sendEvent(name: "heatSource", value: source)
        debounceTile()
    }
}

def setHeatLock(String lock) {
    String v = lock == "locked" ? "locked" : "unlocked"
    sendEvent(name: "heatLock", value: v)
    logN "Heat Lock ${v}"
    debounceTile()
}

def adjustSetPointUp()   { setHeatingSetpoint(((device.currentValue("heatingSetpoint") ?: 80) as BigDecimal).intValue() + 1) }
def adjustSetPointDown() { setHeatingSetpoint(((device.currentValue("heatingSetpoint") ?: 80) as BigDecimal).intValue() - 1) }

def refresh() { parent?.componentRefresh(this) }

// 1.6.x command names, kept for existing rules.
def "🔥 Heat and Start Pump"(degrees) { heatAndStart(degrees) }
def "⚙ Stop Heat - Keep Pump On"()    { stopHeat() }
def "🔴 Stop Heat and Pump"()         { off() }
def "⚙ Set Heat Source"(source)       { setHeatSource(source) }
def "⚙ Disable Heat Lock"()           { setHeatLock("unlocked") }
def "⚙ Enable Heat Lock"()            { setHeatLock("locked") }

private boolean locked() {
    if (device.currentValue("heatLock") == "locked") {
        logE "Heat Lock is on, heat change ignored. Use Set Heat Lock: unlocked to allow it."
        return true
    }
    return false
}

private Integer inRange(v) {
    Integer t
    try { t = new BigDecimal(v.toString()).setScale(0, java.math.RoundingMode.HALF_UP).intValue() } catch (e) { logE "'${v}' isn't a temperature"; return null }
    boolean c = location?.temperatureScale == "C"
    int lo = (minSetPoint ?: (c ? 4 : 40)) as int
    int hi = (maxSetPoint ?: (c ? 40 : 104)) as int
    if (t < lo || t > hi) {
        logE "${t}${unit()} is outside the allowed range (${lo} to ${hi}${unit()}), ignored"
        return null
    }
    return t
}

private String unit() { "°${location?.temperatureScale ?: 'F'}" }

// ============================================================
// Dashboard tile
// ============================================================

def debounceTile() {
    runIn(3, "renderTile")
}

def renderTile() {
    def sw       = device.currentValue("switch") ?: "off"
    def temp     = (device.currentValue("temperature") ?: 0) as BigDecimal
    def setpt    = (device.currentValue("heatingSetpoint") ?: 0) as BigDecimal
    def htmode   = device.currentValue("heaterMode") ?: "Off"
    def htsrc    = device.currentValue("heatSource") ?: "Off"
    boolean isOn      = sw == "on"
    boolean isHeating = isOn && htmode != "Off" && htmode != "0" && htsrc != "Off"
    String lock    = device.currentValue("heatLock") == "locked" ? " (LOCKED)" : ""
    String pumpClr = isOn ? "#4ade80" : "#ef4444"
    String heatClr = isHeating ? "#4ade80" : "#ef4444"
    String html = "<div style='width:100%;height:100%;background:#0f172a;border-radius:16px;padding:10px;color:#fff;text-align:center;box-sizing:border-box;font-family:sans-serif;margin-left:1.5px'>" +
        "<div style='font-size:15px;font-weight:800;color:#e2e8f0;margin-bottom:4px'>${device.displayName}${lock}</div>" +
        "<div style='background:#1e3a5f;border-radius:8px;padding:8px;margin-bottom:6px'>" +
        "<table width='100%' cellpadding='0' cellspacing='0'><tr>" +
        "<td style='width:50%'><div style='font-size:9px;color:#64748b'>NOW</div><div style='font-size:28px;font-weight:800'>${temp.setScale(0, java.math.RoundingMode.HALF_UP)}&#176;</div></td>" +
        "<td style='width:50%'><div style='font-size:9px;color:#64748b'>TARGET</div><div style='font-size:28px;font-weight:800;color:#38bdf8'>${setpt.setScale(0, java.math.RoundingMode.HALF_UP)}&#176;</div></td>" +
        "</tr></table></div>" +
        "<div style='font-size:10px;color:#94a3b8'>Pump:<b style='color:${pumpClr}'>${isOn ? 'On' : 'Off'}</b> Src:${htsrc} Heat:<b style='color:${heatClr}'>${htmode}</b></div>" +
        "</div>"
    if (device.currentValue("tile") != html) sendEvent(name: "tile", value: html)
}

// ============================================================
// Logging (level comes from the app through the Bridge)
// ============================================================

void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
    cleanSettings()
}

private int lvl() { LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only") }
private void logE(String s) { slog("warn", s) }
private void logN(String s) { if (lvl() >= 1) slog("info", s) }

private void slog(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) } catch (e) { level == "warn" ? log.warn("${device.displayName}: ${msg}") : log.info("${device.displayName}: ${msg}") }
}
