/*
    Pentair IntelliCenter - Pump Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One device per variable speed pump, created by the Bridge. Shows RPM, GPM and watts (when the pump
      reports them; some report no watts) and the running body's water temperature.
     -switch follows the pump (on when RPM > 0). Turning the pump on or off is done from the Body device,
      so on()/off() here only log a reminder. Set Speed changes the RPM of the running program.
     -Units follow the hub's temperature scale. Log level comes from the app through the Bridge.
*/

metadata {
    definition(name: "Pentair IntelliCenter Pump", namespace: "intellicenter", author: "jdthomas24") {
        capability "Switch"
        capability "PowerMeter"
        capability "TemperatureMeasurement"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "rpm",   "number"
        attribute "watts", "number"
        attribute "gpm",   "number"
        attribute "tile",  "string"

        command "setSpeed", [[name: "rpm*", type: "NUMBER", description: "Target RPM"]]
    }

    preferences {
        input "minRPM", "number", title: "Lowest allowed RPM",  defaultValue: 450,  required: true
        input "maxRPM", "number", title: "Highest allowed RPM", defaultValue: 3450, required: true
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

def installed() { renderTile() }

def updated() {
    device.removeSetting("debugMode")
    renderTile()
}

def disableDebugLogging() { }   // 1.6.x job, harmless if it fires once

def setSpeed(rpm) {
    Integer target
    try { target = new BigDecimal(rpm.toString()).intValue() } catch (e) { logE "'${rpm}' isn't an RPM"; return }
    int lo = (minRPM ?: 450) as int
    int hi = (maxRPM ?: 3450) as int
    if (target < lo || target > hi) {
        logE "${target} RPM is outside the allowed range (${lo} to ${hi}), ignored"
        return
    }
    if (parent?.setPumpSpeed(device.deviceNetworkId, target)) {
        sendEvent(name: "rpm", value: target, unit: "RPM")
        debounceTile()
    }
}

def on()  { logE "the pump runs with its body. Turn on the Pool or Spa device instead." }
def off() { logE "the pump runs with its body. Turn off the Pool or Spa device instead." }

def refresh() { parent?.componentRefresh(this) }

def debounceTile() { runIn(3, "renderTile") }

def renderTile() {
    def rpm  = device.currentValue("rpm")
    def temp = device.currentValue("temperature")
    boolean running = rpm != null && (rpm as BigDecimal) > 0
    String sw = running ? "on" : "off"
    if (device.currentValue("switch") != sw) sendEvent(name: "switch", value: sw)
    String u = "°${location?.temperatureScale ?: 'F'}"
    String html = "<div style='width:100%;height:100%;background:#0f172a;border-radius:16px;padding:10px;color:#fff;text-align:center;box-sizing:border-box;font-family:sans-serif;margin-left:1.5px'>" +
        "<div style='font-size:13px;font-weight:800;color:#e2e8f0;margin-bottom:3px'>${device.displayName}</div>" +
        "<div style='font-size:11px;font-weight:700;margin-bottom:8px;color:${running ? '#4ade80' : '#64748b'}'>${running ? '● Running' : '● Off'}</div>" +
        "<div style='margin-bottom:8px'>" +
            "<div style='font-size:32px;font-weight:800;color:#38bdf8;line-height:1'>${rpm != null ? rpm : '—'}</div>" +
            "<div style='font-size:10px;color:#64748b;text-transform:uppercase;letter-spacing:.5px'>RPM</div>" +
        "</div>" +
        "<div style='background:#1e3a5f;border-radius:10px;padding:6px'>" +
            "<div style='font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.4px;margin-bottom:3px'>Water</div>" +
            "<div style='font-size:12px;font-weight:700;color:#e2e8f0'>${temp != null ? temp : '—'}${u}</div>" +
        "</div>" +
        "</div>"
    if (device.currentValue("tile") != html) sendEvent(name: "tile", value: html)
}

void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
    device.removeSetting("debugMode")
}

private int lvl() { LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only") }
private void logE(String s) { slog("warn", s) }

private void slog(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) } catch (e) { log.warn "${device.displayName}: ${msg}" }
}
