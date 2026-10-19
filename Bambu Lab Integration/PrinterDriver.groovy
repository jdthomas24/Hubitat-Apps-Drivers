/*
    Bambu Lab Integration - Printer Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -Created by the app, one per printer. Local only: MQTT over TLS to ssl://IP:8883, user bblp, password =
      the printer's LAN access code (asked from the app at connect time, never stored here). The printer's
      certificate is self-signed, so ignoreSSLIssues (Hubitat 2.2.5+).
     -Topics: device/<serial>/report (in), device/<serial>/request (out). On connect: pushall + get_version.
      P1/A1 send only changes after a pushall; X1/H2 send the full status about once a second while printing.
     -Full reports (over 3000 chars) are processed at most once per update interval unless gcode_state changed,
      checked by a cheap text match before parsing. Small change reports are always merged. The merged
      report lives in memory (RUNTIME); a driver save clears it and the next pushall rebuilds it.
     -Temperatures: classic print.* fields first; H2 series fall back to print.device (extruder.info[n].temp,
      ctc.info.temp), packed as current = low 16 bits, target = high 16 bits. Unconfirmed until a real H2 log.
      Reported in the hub's scale, only when they move by the app's temperature step.
     -Mode is per printer, from the app. Monitor only (default): works alongside Bambu Cloud and the Bambu
      apps; chamberLight is read-only and commands are refused locally. Monitor and control: needs Developer
      Mode (LAN Only, no Bambu Cloud); adds the chamber light switch device and Pause, Resume, Stop, Set Speed.
      Unsigned commands refused by the printer (84033543) are reported once with the fix.
     -Children (chosen on the app's Equipment page): one AMS device per unit, plus the chamber light
      (Generic Component Switch) in control mode. Exclusions as in IntelliCenter.
     -Reconnect backoff 5 s to 5 min. Quiet for 6 min: pushall. Still quiet a minute later: reconnect.
      A drop logs at Normal; still down after 4 tries warns once; recovery logs.
     -Tile stays under the dashboard's 1024-character limit.
*/

metadata {
    definition(name: "Bambu Lab Printer", namespace: "jdthomas24", author: "jdthomas24") {
        capability "Initialize"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "Sensor"
        capability "Actuator"

        attribute "printState",       "string"
        attribute "stage",            "string"
        attribute "progress",         "number"
        attribute "timeRemaining",    "number"
        attribute "eta",              "string"
        attribute "currentLayer",     "number"
        attribute "totalLayers",      "number"
        attribute "fileName",         "string"
        attribute "nozzleTemp",       "number"
        attribute "nozzleTarget",     "number"
        attribute "nozzle2Temp",      "number"
        attribute "nozzle2Target",    "number"
        attribute "bedTemp",          "number"
        attribute "bedTarget",        "number"
        attribute "chamberTemp",      "number"
        attribute "speed",            "string"
        attribute "chamberLight",     "string"
        attribute "mode",             "string"
        attribute "activeTray",       "string"
        attribute "errorCount",       "number"
        attribute "errors",           "string"
        attribute "wifiSignal",       "string"
        attribute "firmware",         "string"
        attribute "connectionStatus", "string"
        attribute "tile",             "string"

        command "pause"
        command "resume"
        command "stop"
        command "setSpeed", [[name: "Speed*", type: "ENUM", constraints: ["Silent", "Standard", "Sport", "Ludicrous"]]]
    }

    preferences {
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String VERSION     = "0.1.0"
@Field static final String COMM_LINK   = "https://community.hubitat.com/"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]
@Field static final List BACKOFF       = [5, 15, 30, 60, 120, 300]
@Field static final int FULL_REPORT    = 3000
@Field static final Map STATES = [IDLE: "Idle", INIT: "Starting", PREPARE: "Preparing", SLICING: "Slicing", RUNNING: "Printing",
                                  PAUSE: "Paused", FINISH: "Finished", FAILED: "Failed", OFFLINE: "Offline"]
@Field static final List SPEEDS = ["", "Silent", "Standard", "Sport", "Ludicrous"]
@Field static final Map STAGES = [1: "Auto bed leveling", 2: "Heatbed preheating", 3: "Sweeping XY mech mode", 4: "Changing filament",
    5: "M400 pause", 6: "Paused: filament runout", 7: "Heating hotend", 8: "Calibrating extrusion", 9: "Scanning bed surface",
    10: "Inspecting first layer", 11: "Identifying build plate", 12: "Calibrating micro lidar", 13: "Homing toolhead",
    14: "Cleaning nozzle tip", 15: "Checking extruder temperature", 16: "Paused by user", 17: "Paused: front cover fell",
    18: "Calibrating micro lidar", 19: "Calibrating extrusion flow", 20: "Paused: nozzle temperature fault",
    21: "Paused: bed temperature fault", 22: "Unloading filament", 23: "Paused: skipped step", 24: "Loading filament",
    25: "Calibrating motor noise", 26: "Paused: AMS lost", 27: "Paused: heatbreak fan too slow",
    28: "Paused: chamber temperature fault", 29: "Cooling chamber", 30: "Paused by G-code", 31: "Motor noise showoff",
    32: "Paused: nozzle filament covered", 33: "Paused: cutter error", 34: "Paused: first layer error", 35: "Paused: nozzle clog"]
@Field static final String DEV_MODE_ERR = "84033543"
// Per-device working data: merged report, last message time, sequence id. Lost on a driver save, which is safe.
@Field static final java.util.concurrent.ConcurrentHashMap RUNTIME = new java.util.concurrent.ConcurrentHashMap()

String fmtHelpInfo() {
    String info     = "Bambu Lab Integration v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

private Map mem() {
    String k = device.id.toString()
    Map m = RUNTIME.get(k) as Map
    if (m == null) {
        m = new java.util.concurrent.ConcurrentHashMap()
        m.rep = new java.util.concurrent.ConcurrentHashMap()
        RUNTIME.put(k, m)
    }
    return m
}

// ============================================================
// Lifecycle
// ============================================================

def installed() { }

def updated() { initialize() }

def initialize() {
    unschedule()
    Map m = mem()
    m.attempt = 0
    m.reconnectPending = false
    m.remove("opts")
    m.remove("cat")
    m.remove("skip")
    if (state.connected) state.connected = false
    if (!state.printer?.serial || !state.printer?.ip) {
        setStatus("Not Configured")
        logE "not set up. Open the Bambu Lab Integration app and tap Done."
        return
    }
    schedule("20 * * * * ?", "everyMinute")
    runIn(1, "connect")
}

def refresh() { pushAll() }

def uninstalled() {
    mem().closingAt = now()
    try { interfaces.mqtt.disconnect() } catch (e) { }
    RUNTIME.remove(device.id.toString())
}

// From the app on Done: printer identity (no access code) and options.
void configure(Map printer, Map opts) {
    if (state.printer != printer) state.printer = printer
    if (state.opts != opts) state.opts = opts
    mem().remove("opts")
    report("mode", opts?.control ? "Monitor and control" : "Monitor only")
    if (!opts?.control) dropLight()
}

// Monitor only: no light switch device. Forgotten, not excluded, so control mode recreates it.
private void dropLight() {
    def child = getChildDevice(dniFor("light"))
    if (child) {
        try { deleteChildDevice(child.deviceNetworkId); logN "removed ${child.displayName} (monitor only)" } catch (e) { }
    }
    Map c = (state.catalog ?: [:]) as Map
    if (c.containsKey("light")) { c.remove("light"); state.catalog = c; mem().remove("cat") }
    if (((state.known ?: []) as List).contains("light")) state.known = ((state.known ?: []) as List) - "light"
    if (((state.excluded ?: []) as List).contains("light")) state.excluded = ((state.excluded ?: []) as List) - "light"
}

private opt(String k) {
    Map m = mem()
    Map o = m.opts as Map
    if (o == null) { o = (state.opts ?: [:]) as Map; m.opts = o }
    return o[k]
}

private String serial() { state.printer?.serial?.toString() }

// Every minute: reconnect a dropped client; a quiet printer gets a pushall, then a reconnect.
def everyMinute() {
    Map m = mem()
    boolean up = false
    try { up = interfaces.mqtt.isConnected() } catch (e) { }
    if (state.connected && !up) {
        markDown("connection lost")
        scheduleReconnect()
        return
    }
    if (!state.connected) {
        if (!m.reconnectPending && now() - ((m.connectAt ?: 0L) as long) > 60000L) scheduleReconnect()
        return
    }
    if (m.lastRx == null) { m.lastRx = now(); return }   // memory cleared by a driver save
    long quiet = now() - (m.lastRx as long)
    if (quiet > 420000L) {
        markDown("no message for ${(quiet / 60000) as int} min")
        scheduleReconnect()
    } else if (quiet > 360000L) {
        logF "quiet for 6 min, asking for a full status"
        pushAll()
    }
}

// ============================================================
// Connection
// ============================================================

def connect() {
    Map m = mem()
    m.reconnectPending = false
    m.connectAt = now()
    Map p = state.printer
    if (!p?.serial) return
    String code = null
    try { code = parent?.getAccessCode(p.serial as String) } catch (e) { logE "could not get the access code from the app: ${e.message}" }
    if (!code) {
        setStatus("No access code")
        logE "no LAN access code. Enter it in the Bambu Lab Integration app."
        return
    }
    m.closingAt = now()
    try { interfaces.mqtt.disconnect() } catch (e) { }
    setStatus(state.downSince != null ? "Reconnecting" : "Connecting")
    String broker = "ssl://${p.ip}:8883"
    logF "connecting to ${broker}"
    try {
        interfaces.mqtt.connect(broker, "hubitat-bambu-${device.id}", "bblp", code, ignoreSSLIssues: true, tlsVersion: "1.2", cleanSession: true)
        interfaces.mqtt.subscribe("device/${p.serial}/report")
        onConnected()
    } catch (e) {
        String why = e.message ?: e.toString()
        if (why.toLowerCase().contains("not authorized") || why.contains("(5)")) {
            setStatus("Access code rejected")
            logE "the printer rejected the access code. Check it on the printer (Settings, WLAN or LAN Only) and update it in the app."
            m.authFailed = true
        }
        markDown("connect failed: ${why}")
        scheduleReconnect()
    }
}

private void onConnected() {
    Map m = mem()
    m.attempt = 0
    m.lastRx = now()
    m.remove("authFailed")
    state.connected = true
    setStatus("Connected")
    if (state.downSince != null) {
        int mins = ((now() - (state.downSince as long)) / 60000) as int
        if (state.downWarned) slog("info", "printer back online after ${mins} min")
        else logN "reconnected"
        state.remove("downSince")
        state.remove("downWarned")
    } else {
        logN "connected to ${state.printer.ip}"
    }
    pushAll()
    publish([info: [sequence_id: seq(), command: "get_version"]])
}

def mqttClientStatus(String s) {
    logF "MQTT ${s}"
    if (s.startsWith("Error") || s.toLowerCase().contains("connection lost")) {
        if (now() - ((mem().closingAt ?: 0L) as long) < 10000L && !s.toLowerCase().contains("lost")) return
        markDown(s.replaceFirst(/^Error:\s*/, ""))
        scheduleReconnect()
    }
}

private void markDown(String why) {
    if (state.connected) state.connected = false
    String st = device.currentValue("connectionStatus")
    if (st != "Access code rejected") setStatus("Reconnecting")
    if (state.downSince == null) {
        state.downSince = now()
        logN "printer connection dropped (${why}). Reconnecting."
    } else {
        logF "still offline: ${why}"
    }
}

private void scheduleReconnect() {
    Map m = mem()
    if (m.reconnectPending) return
    int a = (m.attempt ?: 0) as int
    int secs = BACKOFF[Math.min(a, BACKOFF.size() - 1)] as int
    m.attempt = a + 1
    if (a == 3 && !state.downWarned) {
        state.downWarned = true
        logE "can't reach the printer at ${state.printer?.ip}. Is it on and on the network? Retrying on its own, at most every 5 minutes."
    }
    m.reconnectPending = true
    runIn(secs, "connect")
}

private String seq() {
    Map m = mem()
    long n = ((m.seq ?: 0L) as long) + 1
    m.seq = n
    return n.toString()
}

private boolean publish(Map msg) {
    if (!state.connected) return false
    try {
        interfaces.mqtt.publish("device/${serial()}/request", JsonOutput.toJson(msg))
        return true
    } catch (e) {
        logE "could not send to the printer: ${e.message}"
        return false
    }
}

private void pushAll() {
    publish([pushing: [sequence_id: seq(), command: "pushall", version: 1, push_target: 1]])
}

// ============================================================
// Reports
// ============================================================

def parse(String raw) {
    Map m = mem()
    m.lastRx = now()
    String payload
    try { payload = interfaces.mqtt.parseMessage(raw)?.payload } catch (e) { logF "unreadable MQTT message"; return }
    if (!payload) return
    if (payload.length() > FULL_REPORT && now() - ((m.lastFull ?: 0L) as long) < intervalMs()) {
        def mt = payload =~ /"gcode_state"\s*:\s*"(\w+)"/
        String gs = mt.find() ? mt.group(1) : null
        if (gs == null || gs == m.lastGs) return   // nothing important changed since the last processed report
    }
    if (lvl() >= 2) logF "received ${payload.length() > 400 ? payload.take(400) + '...' : payload}"
    def j
    try { j = new JsonSlurper().parseText(payload) } catch (e) { logF "unreadable report: ${payload.take(120)}"; return }
    if (!(j instanceof Map)) return
    if (j.print instanceof Map) {
        Map pr = j.print as Map
        checkResult(pr, "print")
        if (pr.command == "push_status" || pr.containsKey("gcode_state") || pr.containsKey("mc_percent")) {
            if (payload.length() > FULL_REPORT) m.lastFull = now()
            merge(m.rep as Map, pr)
            render()
        }
    }
    if (j.system instanceof Map) checkResult(j.system as Map, "system")
    if (j.info instanceof Map && j.info.command == "get_version") readVersion(j.info as Map)
}

private long intervalMs() { (((opt("interval") ?: 5) as int) * 1000L) }

private void merge(Map into, Map from) {
    from.each { k, v ->
        if (k in ["command", "sequence_id", "msg"]) return
        if (v instanceof Map && into[k] instanceof Map) merge(into[k] as Map, v as Map)
        else if (v != null) into[k] = (v instanceof Map) ? deepCopy(v as Map) : v
    }
}

private Map deepCopy(Map src) {
    Map out = new java.util.concurrent.ConcurrentHashMap()
    src.each { k, v -> if (v != null) out[k] = (v instanceof Map) ? deepCopy(v as Map) : v }
    return out
}

// Command replies: result "fail"/"failed", a reason, or the unsigned-command error.
private void checkResult(Map r, String kind) {
    String res = r.result?.toString()?.toLowerCase()
    String err = (r.err_code ?: r.reason ?: "").toString()
    if (!(res in ["fail", "failed", "error"]) && !err.contains(DEV_MODE_ERR)) return
    if (err.contains(DEV_MODE_ERR) || (r.reason ?: "").toString().toLowerCase().contains("sign")) {
        if (!mem().devModeWarned) {
            mem().devModeWarned = true
            logE "the printer refused ${r.command ?: 'a command'}: control from Hubitat needs Developer Mode on the printer (Settings, LAN Only, Developer Mode), which turns off Bambu Cloud. Or set this printer to Monitor only in the app. Monitoring keeps working."
        }
        return
    }
    logE "the printer refused ${r.command ?: kind + ' command'}${r.reason ? ' (' + r.reason + ')' : ''}"
}

private void readVersion(Map info) {
    def mod = (info.module instanceof List) ? info.module.find { it.name == "ota" } : null
    if (mod?.sw_ver) report("firmware", mod.sw_ver.toString())
}

// Merged report -> attributes, children and tile.
private void render() {
    Map r = mem().rep as Map
    String gs = r.gcode_state?.toString()
    if (gs) {
        String ps = STATES[gs] ?: gs.toLowerCase().capitalize()
        String old = device.currentValue("printState")
        if (old != ps) logN "print state: ${ps}"
        report("printState", ps)
        mem().lastGs = gs
    }
    String ps = device.currentValue("printState") ?: ""
    boolean active = ps in ["Printing", "Paused", "Preparing"]
    Integer stg = toInt(r.stg_cur)
    report("stage", active ? (stg && stg != 255 && stg > 0 ? (STAGES[stg] ?: "Stage ${stg}") : (ps == "Printing" ? "Printing" : ps)) : ps)
    if (r.containsKey("mc_percent")) report("progress", toInt(r.mc_percent) ?: 0, "%")
    if (r.containsKey("mc_remaining_time")) {
        int mins = toInt(r.mc_remaining_time) ?: 0
        report("timeRemaining", mins, "min")
        report("eta", active && mins > 0 ? new Date(now() + mins * 60000L).format("h:mm a", location.timeZone) : "")
    }
    if (r.containsKey("layer_num")) report("currentLayer", toInt(r.layer_num) ?: 0)
    if (r.containsKey("total_layer_num")) report("totalLayers", toInt(r.total_layer_num) ?: 0)
    String fn = (r.subtask_name ?: r.gcode_file ?: "").toString()
    if (fn || r.containsKey("subtask_name")) report("fileName", fn)
    if (r.containsKey("spd_lvl")) { Integer s = toInt(r.spd_lvl); report("speed", s in 1..4 ? SPEEDS[s] : "Standard") }
    if (r.wifi_signal) report("wifiSignal", r.wifi_signal.toString())
    temps(r)
    errors(r)
    ams(r)
    light(r)
    tile()
}

private void temps(Map r) {
    BigDecimal step = (opt("tempStep") ?: 1) as BigDecimal
    List ext = (r.device instanceof Map && r.device.extruder instanceof Map && r.device.extruder.info instanceof List) ? r.device.extruder.info as List : []
    def n1 = r.nozzle_temper, n1t = r.nozzle_target_temper
    if (n1 == null && ext) { Map e = ext.find { toInt(it.id) == 0 } as Map ?: ext[0] as Map; n1 = packedCur(e?.temp); n1t = packedTarget(e?.temp) }
    if (n1 != null) { temp("nozzleTemp", n1, step); temp("temperature", n1, step) }
    if (n1t != null) temp("nozzleTarget", n1t, 0)
    if (ext.size() > 1) {
        Map e2 = ext.find { toInt(it.id) == 1 } as Map
        if (e2) { temp("nozzle2Temp", packedCur(e2.temp), step); temp("nozzle2Target", packedTarget(e2.temp), 0) }
    }
    def b = r.bed_temper, bt = r.bed_target_temper
    if (b == null && r.device instanceof Map && r.device.bed instanceof Map && r.device.bed.info instanceof Map) {
        b = packedCur(r.device.bed.info.temp); bt = packedTarget(r.device.bed.info.temp)
    }
    if (b != null) temp("bedTemp", b, step)
    if (bt != null) temp("bedTarget", bt, 0)
    def c = r.chamber_temper
    if (c == null && r.device instanceof Map && r.device.ctc instanceof Map && r.device.ctc.info instanceof Map) c = packedCur(r.device.ctc.info.temp)
    if (c != null) temp("chamberTemp", c, step)
}

// H2 series: current in the low 16 bits, target in the high 16 bits.
private Integer packedCur(v) { Long n = toLong(v); n == null ? null : (int) (n & 0xFFFF) }
private Integer packedTarget(v) { Long n = toLong(v); n == null ? null : (int) ((n >> 16) & 0xFFFF) }

private void temp(String name, c, BigDecimal step) {
    BigDecimal v = num(c)
    if (v == null) return
    String scale = location.temperatureScale ?: "C"
    if (scale == "F") v = v * 9 / 5 + 32
    report(name, v.setScale(1, java.math.RoundingMode.HALF_UP), "°${scale}", step)
}

// HMS list -> count and codes (HMS_AAAA_BBBB_CCCC_DDDD, Bambu's wiki format); print_error as a fallback.
private void errors(Map r) {
    List codes = []
    if (r.hms instanceof List) {
        r.hms.each { h ->
            Long a = toLong(h.attr), c = toLong(h.code)
            if (a != null && c != null) {
                String s = String.format("%08X%08X", a, c)
                codes << "HMS_${s[0..3]}_${s[4..7]}_${s[8..11]}_${s[12..15]}"
            }
        }
    }
    Long pe = toLong(r.print_error)
    if (pe && pe != 0) codes << String.format("ERR_%08X", pe)
    if (!r.containsKey("hms") && !r.containsKey("print_error")) return
    String joined = codes.unique().join(", ")
    if (joined != (device.currentValue("errors") ?: "") && codes) logE "printer reports ${codes.size() == 1 ? 'an error' : codes.size() + ' errors'}: ${joined}"
    report("errorCount", codes.unique().size())
    report("errors", joined ?: "none")
}

// ============================================================
// AMS and light (child devices)
// ============================================================

private void ams(Map r) {
    if (!(r.ams instanceof Map)) return
    Map a = r.ams as Map
    String now_ = a.tray_now?.toString()
    if (now_ != null) {
        Integer t = toInt(now_)
        String label = t == null || t == 255 ? "none" : t == 254 ? "external spool" : "AMS ${(t / 4 as int) + 1} tray ${(t % 4) + 1}"
        report("activeTray", label)
    }
    if (!(a.ams instanceof List)) return
    a.ams.each { u ->
        if (!(u instanceof Map)) return
        String id = u.id?.toString()
        if (id == null) return
        String key = "ams${id}"
        catalogPut(key, [k: "ams", n: "AMS ${(toInt(id) ?: 0) + 1}"])
        def child = childFor(key)
        if (!child) return
        List ev = []
        BigDecimal hum = num(u.humidity_raw)
        if (hum != null) ev << [name: "humidity", value: hum.setScale(0, java.math.RoundingMode.HALF_UP), unit: "%"]
        if (u.humidity != null) ev << [name: "dryness", value: "${u.humidity}/5"]
        BigDecimal t = num(u.temp)
        if (t != null) {
            String scale = location.temperatureScale ?: "C"
            if (scale == "F") t = t * 9 / 5 + 32
            ev << [name: "temperature", value: t.setScale(1, java.math.RoundingMode.HALF_UP), unit: "°${scale}", delta: (opt("tempStep") ?: 1)]
        }
        (u.tray instanceof List ? u.tray : []).each { tr ->
            Integer ti = toInt(tr.id)
            if (ti == null || ti > 3) return
            String type = tr.tray_type?.toString()
            String desc = type ? "${type}${tr.tray_sub_brands ? ' (' + tr.tray_sub_brands + ')' : ''}" : "empty"
            def rem = toInt(tr.remain)
            if (type && rem != null && rem >= 0) desc += ", ${rem}%"
            String col = tr.tray_color ? "#${tr.tray_color.toString().take(6)}" : ""
            ev << [name: "tray${ti + 1}", value: desc]
            ev << [name: "tray${ti + 1}Color", value: col ?: "none"]
        }
        if (ev) child.parse(ev)
    }
}

private void light(Map r) {
    if (!(r.lights_report instanceof List)) return
    Map l = r.lights_report.find { it.node == "chamber_light" } as Map
    if (!l) return
    String v = l.mode == "off" ? "off" : "on"
    report("chamberLight", v)
    if (!opt("control")) return
    catalogPut("light", [k: "light", n: "Chamber light"])
    def child = childFor("light")
    if (child) child.parse([[name: "switch", value: v]])
}

void componentOn(cd)  { setLight(true) }
void componentOff(cd) { setLight(false) }
void componentRefresh(cd) { pushAll() }

private void setLight(boolean on) {
    if (!opt("control")) { logE "monitor only: control the light from the Bambu apps, or set this printer to Monitor and control in the app."; return }
    publish([system: [sequence_id: seq(), command: "ledctrl", led_node: "chamber_light", led_mode: on ? "on" : "off",
                      led_on_time: 500, led_off_time: 500, loop_times: 0, interval_time: 0]])
    logN "chamber light ${on ? 'on' : 'off'}"
}

// ============================================================
// Print control (Monitor and control mode only)
// ============================================================

def pause()  { printCommand("pause", "") }
def resume() { printCommand("resume", "") }
def stop()   { printCommand("stop", "") }

def setSpeed(String speed) {
    int i = SPEEDS.indexOf(speed)
    if (i < 1) { logE "unknown speed ${speed}. Use Silent, Standard, Sport or Ludicrous."; return }
    printCommand("print_speed", "${i}")
}

private void printCommand(String cmd, String param) {
    if (!opt("control")) {
        logE "monitor only: ${cmd} not sent. Use the Bambu apps, or set this printer to Monitor and control in the Bambu Lab Integration app (needs Developer Mode)."
        return
    }
    if (!state.connected) { logE "not connected to the printer, ${cmd} not sent"; return }
    if (publish([print: [sequence_id: seq(), command: cmd, param: param]])) logN "${cmd}${param ? ' ' + param : ''} sent"
}

// ============================================================
// Tile (under 1024 characters)
// ============================================================

private void tile() {
    Map m = mem()
    String ps = device.currentValue("printState") ?: "Unknown"
    int pct = (device.currentValue("progress") ?: 0) as int
    boolean active = ps in ["Printing", "Paused", "Preparing"]
    String u = location.temperatureScale ?: "C"
    def t = { String a -> def v = device.currentValue(a); v == null ? "-" : "${Math.round(v as double)}" }
    String name = esc(device.displayName).take(40)
    String file = esc(device.currentValue("fileName") ?: "").take(40)
    String line2 = active ? "${pct}% &middot; layer ${device.currentValue('currentLayer') ?: 0}/${device.currentValue('totalLayers') ?: 0}" +
        ((device.currentValue("timeRemaining") ?: 0) > 0 ? " &middot; ${hm(device.currentValue('timeRemaining') as int)} left" : "") : ""
    String bar = active ? "<div style='height:6px;border-radius:3px;background:rgba(127,127,127,.3);margin:4px 0'>" +
        "<div style='height:6px;border-radius:3px;width:${pct}%;background:currentColor'></div></div>" : ""
    String html = "<div style='font-size:13px;line-height:1.35;text-align:left'><b>${name}</b><br>${ps}" +
        (active && file ? ": ${file}" : "") + bar + (line2 ? "${line2}<br>" : "<br>") +
        "Nozzle ${t('nozzleTemp')}&deg; Bed ${t('bedTemp')}&deg;" + (device.currentValue("chamberTemp") != null ? " Chamber ${t('chamberTemp')}&deg;" : "") +
        ((device.currentValue("errorCount") ?: 0) > 0 ? "<br>&#9888; ${device.currentValue('errorCount')} error(s)" : "") + "</div>"
    if (html.length() > 1024) html = html.take(1018) + "</div>"
    if (html != m.lastTile) { m.lastTile = html; report("tile", html) }
}

private String hm(int mins) { mins >= 60 ? "${(mins / 60) as int}h ${mins % 60}m" : "${mins}m" }

private String esc(v) { (v == null ? "" : v.toString()).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;") }

// ============================================================
// Catalog, children and exclusions
// ============================================================

private Map catalog() {
    Map m = mem()
    Map c = m.cat as Map
    if (c == null) { c = (state.catalog ?: [:]) as Map; m.cat = c }
    return c
}

private void catalogPut(String key, Map e) {
    if (catalog()[key] == e) return
    Map c = (state.catalog ?: [:]) as Map
    c[key] = e
    state.catalog = c
    mem().remove("cat")
}

private String dniFor(String key) { "bambu-${serial()}-${key}" }

private childFor(String key) {
    String dni = dniFor(key)
    def child = getChildDevice(dni)
    if (child) return child
    Map m = mem()
    Set skip = (m.skip ?: (m.skip = java.util.concurrent.ConcurrentHashMap.newKeySet())) as Set
    if (skip.contains(key)) return null
    Map c = catalog()[key] as Map
    String label = "${device.displayName} ${c?.n ?: key}"
    if (((state.excluded ?: []) as List).contains(key)) { skip.add(key); return null }
    if (((state.known ?: []) as List).contains(key)) {
        exclude(key)
        skip.add(key)
        logN "${label} was deleted from the Devices page, so it won't be recreated. Turn it back on in the app's Equipment page."
        return null
    }
    String ns = c?.k == "light" ? "hubitat" : "jdthomas24"
    String drv = c?.k == "light" ? "Generic Component Switch" : "Bambu Lab AMS"
    try {
        child = addChildDevice(ns, drv, dni, [label: label, isComponent: false])
        state.known = ((state.known ?: []) as List) + key
        logN "created ${label}"
        try { child.setLogLevel(state.logLevel ?: "Errors Only") } catch (e) { }
    } catch (e) {
        skip.add(key)
        String why = "${e}".contains("UnknownDeviceType") ? "the ${drv} driver isn't installed (update with HPM)" : e.message
        logE "could not create ${label}: ${why}"
        return null
    }
    return child
}

private void exclude(String key) {
    List x = (state.excluded ?: []) as List
    if (!x.contains(key)) state.excluded = x + key
    state.known = ((state.known ?: []) as List) - key
}

Map getCatalog() { (state.catalog ?: [:]) as Map }
List getExcluded() { (state.excluded ?: []) as List }

void setExcluded(List list) {
    List old = (state.excluded ?: []) as List
    List want = (list ?: []).collect { it.toString() }.unique()
    state.excluded = want
    state.known = ((state.known ?: []) as List) - (old - want) - (want - old)
    mem().remove("skip")
    (want - old).each { key ->
        def child = getChildDevice(dniFor(key))
        if (child) {
            String label = child.displayName
            try { deleteChildDevice(child.deviceNetworkId); logN "removed ${label} (turned off in Equipment)" }
            catch (e) { logE "could not remove ${label}: ${e.message}" }
        }
    }
    if (old - want) runIn(1, "rerender")
}

def rerender() { render() }

void removeAllChildren() {
    getChildDevices().each { try { deleteChildDevice(it.deviceNetworkId) } catch (e) { logE "could not remove ${it.displayName}: ${e.message}" } }
}

// ============================================================
// Events and helpers
// ============================================================

private void report(String name, value, String unit = null, delta = 0) {
    if (!moved(device.currentValue(name), value, delta)) return
    Map e = [name: name, value: value]
    if (unit) e.unit = unit
    sendEvent(e)
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

private void setStatus(String v) {
    if (device.currentValue("connectionStatus") != v) sendEvent(name: "connectionStatus", value: v)
}

private BigDecimal num(v) {
    if (v == null || v == "") return null
    try { return new BigDecimal(v.toString()) } catch (e) { return null }
}

private Integer toInt(v) { BigDecimal n = num(v); n == null ? null : n.intValue() }
private Long toLong(v) { BigDecimal n = num(v); n == null ? null : n.longValue() }

// ============================================================
// Logging (level comes from the app)
// ============================================================

void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
    mem().lvl = LOG_LEVELS.indexOf(state.logLevel)
    getChildDevices().each { try { it.setLogLevel(state.logLevel) } catch (e) { } }
}

void childLog(String level, String source, String msg) {
    try { parent.childLog(level, source, msg) } catch (e) { localLog(level, "${source}: ${msg}") }
}

private int lvl() {
    Map m = mem()
    if (m.lvl == null) m.lvl = LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only")
    return m.lvl as int
}

private void logE(String s) { slog("warn", s) }
private void logN(String s) { if (lvl() >= 1) slog("info", s) }
private void logF(String s) { if (lvl() >= 2) slog("debug", s) }

private void slog(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) } catch (e) { localLog(level, "${device.displayName}: ${msg}") }
}

private void localLog(String level, String line) {
    switch (level) {
        case "error": log.error line; break
        case "warn": log.warn line; break
        case "info": log.info line; break
        default: log.debug line
    }
}
