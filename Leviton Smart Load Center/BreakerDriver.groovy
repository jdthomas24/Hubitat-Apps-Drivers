/*
    Leviton Smart Load Center - Breaker Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One device per smart breaker, created by the Panel device. Values come from the Panel through parse().
     -On/Off reset and trip the breaker, only when the app's Allow breaker control is on. The switch changes
      only after Leviton accepts the command; the panel's push confirms it.
     -Events carry an optional delta: smaller changes are skipped (power threshold from the app).
     -Position, poles, rating, model and remote-on support are in the device's Data section.
     -Log level comes from the app through the Panel.
*/

metadata {
    definition(name: "Leviton Smart Load Center Breaker", namespace: "jdthomas24", author: "jdthomas24") {
        capability "Switch"
        capability "PowerMeter"
        capability "CurrentMeter"
        capability "VoltageMeasurement"
        capability "EnergyMeter"
        capability "Sensor"
        capability "Actuator"
        capability "Refresh"

        attribute "breakerState", "string"
        attribute "remoteState",  "string"
        attribute "frequency",    "number"
        attribute "energyImport", "number"
    }

    preferences {
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.transform.Field

@Field static final String VERSION     = "2.0.0"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-leviton-smart-load-center/163950"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]

String fmtHelpInfo() {
    String info     = "Leviton Smart Load Center v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

def installed() { }
def updated() { }

def on()  { command(true) }
def off() { command(false) }

def refresh() { parent?.restRefresh() }

private void command(boolean on) {
    String id = device.deviceNetworkId - "leviton-breaker-"
    boolean ok = false
    try { ok = parent?.breakerCommand(id, on) as boolean } catch (e) { logE "could not reach the panel: ${e.message}" }
    if (ok) sendEvent(name: "switch", value: on ? "on" : "off")
}

def parse(List<Map> events) {
    events.each { e ->
        if (moved(device.currentValue(e.name as String), e.value, e.delta)) {
            Map out = [name: e.name, value: e.value]
            if (e.unit) out.unit = e.unit
            if (e.name == "switch" || e.name == "breakerState") logN "${e.name} is ${e.value}"
            sendEvent(out)
        }
    }
}

private boolean moved(cur, val, delta) {
    if (cur == null) return true
    if (val instanceof Number) {
        BigDecimal c
        try { c = new BigDecimal(cur.toString()) } catch (e) { return true }
        BigDecimal v = val as BigDecimal
        if (v == c) return false
        if (v == 0) return true
        return (v - c).abs() >= ((delta ?: 0) as BigDecimal)
    }
    return cur.toString() != val.toString()
}

void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
}

private int lvl() { LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only") }
private void logE(String s) { relay("warn", s) }
private void logN(String s) { if (lvl() >= 1) relay("info", s) }

private void relay(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) }
    catch (e) { if (level == "warn") log.warn "${device.displayName}: ${msg}" else log.info "${device.displayName}: ${msg}" }
}
