/*
    Bambu Lab Integration - AMS Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One device per AMS unit, created by the Printer device. Values come from the Printer through parse().
     -humidity is the AMS reading in percent (newer firmware); dryness is Bambu's 1 to 5 humidity index as
      reported. Which end is dry is unconfirmed until a real log.
     -tray1..tray4: filament type and amount left; trayNColor: the filament color as #RRGGBB.
     -Events carry an optional delta: smaller changes are skipped (temperature step from the app).
*/

metadata {
    definition(name: "Bambu Lab AMS", namespace: "jdthomas24", author: "jdthomas24") {
        capability "TemperatureMeasurement"
        capability "RelativeHumidityMeasurement"
        capability "Sensor"
        capability "Refresh"

        attribute "dryness",    "string"
        attribute "tray1",      "string"
        attribute "tray2",      "string"
        attribute "tray3",      "string"
        attribute "tray4",      "string"
        attribute "tray1Color", "string"
        attribute "tray2Color", "string"
        attribute "tray3Color", "string"
        attribute "tray4Color", "string"
    }

    preferences {
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.transform.Field

@Field static final String VERSION     = "0.1.0"
@Field static final String COMM_LINK   = "https://community.hubitat.com/"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]

String fmtHelpInfo() {
    String info     = "Bambu Lab Integration v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

def installed() { }
def updated() { }

def refresh() { parent?.componentRefresh(device) }

def parse(List<Map> events) {
    events.each { e ->
        if (moved(device.currentValue(e.name as String), e.value, e.delta)) {
            Map out = [name: e.name, value: e.value]
            if (e.unit) out.unit = e.unit
            if ((e.name as String).matches(/tray\d/) && lvl() >= 1) relay("info", "${e.name}: ${e.value}")
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

private void relay(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) } catch (e) { log.info "${device.displayName}: ${msg}" }
}
