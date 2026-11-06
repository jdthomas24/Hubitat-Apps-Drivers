/*
    Bambu Lab Integration - Printer Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -Created by the app, one per printer slot (DNI bambu-pN). Local only: MQTT over TLS to ssl://IP:8883, user
      bblp, password = the printer's LAN access code (asked from the app at connect time, never stored here).
      The printer's certificate is self-signed, so ignoreSSLIssues (Hubitat 2.2.5+).
     -Topics: device/<serial>/report (in), device/<serial>/request (out). The serial is learned from the first
      report on device/+/report (the printer accepts the wildcard; H2D confirmed), so setup needs no serial.
     -Sampling, not streaming (0.1.1): X1/H2 push an 8 KB status every second while printing. The connection
      stays up, but the driver only subscribes for one report per cycle (pushall, first full report, unsubscribe):
      every 30 s (app setting) while active, every 5 min idle. Unsubscribed, the printer sends the hub nothing.
      3 cycles without a reply reconnect. The merged report lives in memory (RUNTIME); a driver save clears it.
     -Temperatures: classic print.* fields first; H2 series fall back to print.device (extruder.info[n].temp,
      ctc.info.temp), packed as current = low 16 bits, target = high 16 bits (H2D confirmed). Heaters report a
      status (Heating to <target>, At temp, Cooling, Off) and the current temperature only at temp or off, in the
      app's step. Celsius by default to match the printer; the app can switch to the hub's scale.
     -Times move in 5 minute steps; wifiSignal in 5 dB steps; AMS humidity in 5% steps.
     -Preferences (0.1.3): check interval, temperature step and units. adoptOptions() copies the app's old
      shared values in once on update.
     -Any status received marks the printer Connected (0.1.4), so the label can't stay on Connecting while data
      flows. statusAge() tells the app how long the label has been unchanged. model comes from get_version.
     -Mode is per printer, from the app. Monitor only (default): works alongside Bambu Cloud and the Bambu
      apps; Printer Control is refused locally. Monitor and control: needs Developer Mode (LAN Only, no Bambu
      Cloud) and adds Printer Control. Stop only when the app's Allow Stop is on (off by default).
     -The chamber light works in both modes (0.1.5): the printer accepts ledctrl without Developer Mode (H2D
      confirmed). H2D has two nodes (chamber_light, chamber_light2); the driver sends to every chamber_light*
      node the printer reports, and the light is on when any of them is.
     -Refused commands (0.1.5): unsigned commands come back as err_code 84033543 or as HMS 0500_0500_0001_0007
      (H2D). That code is not counted as a printer error. After a command the driver checks the next status
      (speed, state, light) at the end of the listen window and logs whether it took, was refused (Developer Mode) or was ignored. The listen
      window after a command ends on time even if an idle printer sends only one status (0.1.7).
     -Errors in plain English (0.1.5): on a new HMS or print error code the driver fetches that module's small
      table from this integration's GitHub folder (HMS/<module>.json, made from ha-bambulab's MIT-licensed
      English HMS list; GitHub paths are case sensitive, so HMS) and keeps only the matching line in state.hmsText. errorText and the log get the text
      and a Bambu Lab Wiki link. No lookup goes to Bambu.
     -Door (door attribute, 0.2.0; contact in 0.1.5-0.1.9) from print.stat bit 0x00800000 (H2D confirmed).
      firmwareUpdate from upgrade_state.
     -0.2.0: fileName is "Not printing" when idle and lastPrint keeps the last finished or failed file. ETA
      (was eta) is "--" when not printing, with the weekday when not today. Old eta and contact states are
      deleted once. Tile in the Moonraker driver's style: scales with the tile (cqw), stays under 1024 bytes.
      External spools from vir_slot ids 254/255 (or vt_tray), shown only once loaded. H2D has no
      gcode_start_time, so elapsedTime counts from when the driver saw the print start (blank if it joined
      mid-print).
     -Children (chosen on the app's Equipment page): one AMS device per unit, plus the chamber light
      (Generic Component Switch). Exclusions as in IntelliCenter.
     -Reconnect backoff 5 s to 5 min. Quiet for 6 min: pushall. Still quiet a minute later: reconnect.
      A drop logs at Normal; still down after 4 tries warns once; recovery logs.
     -Tile stays under the dashboard's 1024-character limit.
*/

metadata {
    definition(name: "Bambu Lab Printer", namespace: "jdthomas24", author: "jdthomas24") {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "Actuator"

        attribute "printState",       "string"
        attribute "stage",            "string"
        attribute "progress",         "number"
        attribute "timeRemaining",    "number"
        attribute "elapsedTime",      "number"
        attribute "ETA",              "string"
        attribute "currentLayer",     "number"
        attribute "totalLayers",      "number"
        attribute "fileName",         "string"
        attribute "lastPrint",        "string"
        attribute "door",             "string"
        attribute "nozzleTemp",       "number"
        attribute "nozzleStatus",     "string"
        attribute "nozzle2Temp",      "number"
        attribute "nozzle2Status",    "string"
        attribute "bedTemp",          "number"
        attribute "bedStatus",        "string"
        attribute "chamberTemp",      "number"
        attribute "speed",            "string"
        attribute "chamberLight",     "string"
        attribute "mode",             "string"
        attribute "activeTray",       "string"
        attribute "errorCount",       "number"
        attribute "errors",           "string"
        attribute "errorText",        "string"
        attribute "externalSpool",    "string"
        attribute "externalSpoolColor", "string"
        attribute "externalSpool2",   "string"
        attribute "externalSpool2Color", "string"
        attribute "firmwareUpdate",   "string"
        attribute "wifiSignal",       "string"
        attribute "firmware",         "string"
        attribute "model",            "string"
        attribute "connectionStatus", "string"
        attribute "tile",             "string"

        command "printerControl", [[name: "Action*", type: "ENUM", description: "Needs Monitor and control mode in the app",
                                    constraints: ["Pause", "Resume", "Stop", "Speed: Silent", "Speed: Standard", "Speed: Sport", "Speed: Ludicrous"]]]
    }

    preferences {
        input name: "tempUnit", type: "enum", title: "Temperature units", options: ["C": "Celsius (matches the printer)", "hub": "Hub's setting"], defaultValue: "C"
        input name: "tempStep", type: "enum", title: "At temperature, report changes of", options: ["0.5": "0.5°", "1": "1°", "2": "2°", "5": "5°"], defaultValue: "5",
            description: "While heating or cooling only the status changes"
        input name: "checkEvery", type: "enum", title: "Check while printing every", options: ["15": "15 seconds", "30": "30 seconds", "60": "1 minute", "120": "2 minutes"],
            defaultValue: "30", description: "Idle printers are checked every 5 minutes"
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String VERSION     = "0.2.0"
@Field static final String COMM_LINK   = "https://community.hubitat.com/"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]
@Field static final List BACKOFF       = [5, 15, 30, 60, 120, 300]
@Field static final int IDLE_SECS      = 300
@Field static final int SAMPLE_WAIT    = 20
@Field static final BigDecimal AT_TEMP_C = 5      // within this of the target = At temp
@Field static final BigDecimal OFF_C     = 45     // no target and below this = Off
@Field static final List ACTIVE        = ["Printing", "Paused", "Preparing", "Starting", "Slicing"]
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
@Field static final Long DEV_MODE_PE    = 84033543L
@Field static final List REFUSED_HMS    = ["0500050000010007"]   // MQTT command verification failed (unsigned)
@Field static final Long DOOR_BIT       = 0x00800000L
@Field static final String HMS_URL      = "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/refs/heads/main/Bambu%20Lab%20Integration/HMS/"
@Field static final String WIKI         = "https://wiki.bambulab.com"
@Field static final String WIKI_HOME    = "https://wiki.bambulab.com/en/hms/home"
@Field static final Map AMS_FAMILY      = ["07": "AMS ", "18": "AMS-HT ", "12": "AMS Lite "]   // one table per family, unit A
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

// Preferences saved: new options take effect on the next check. Reconnect only if not connected.
def updated() {
    mem().remove("opts")
    if (!state.connected) initialize()
    else render()
}

// From the app once, on update to 0.1.3: its old shared options become this printer's preferences.
void adoptOptions(Map o) {
    if (state.optsAdopted) return
    if (o?.tempUnit) device.updateSetting("tempUnit", [value: o.tempUnit, type: "enum"])
    if (o?.tempStep) device.updateSetting("tempStep", [value: o.tempStep, type: "enum"])
    if (o?.checkEvery) device.updateSetting("checkEvery", [value: o.checkEvery, type: "enum"])
    state.optsAdopted = true
    mem().remove("opts")
}

def initialize() {
    unschedule()
    if (state.attrs != "0.2.0") {   // renamed in 0.2.0
        ["eta", "contact"].each { try { device.deleteCurrentState(it) } catch (e) { } }
        state.attrs = "0.2.0"
    }
    Map m = mem()
    m.attempt = 0
    m.reconnectPending = false
    ["opts", "cat", "skip", "sampling", "missed", "nextSampleAt", "holdUntil"].each { m.remove(it) }
    if (state.connected) state.connected = false
    if (!state.printer?.ip) {
        setStatus("Not Configured")
        logE "not set up. Open the Bambu Lab Integration app and tap Done."
        return
    }
    schedule("20 * * * * ?", "everyMinute")
    runIn(1, "connect")
}

def refresh() {
    boolean up = false
    try { up = interfaces.mqtt.isConnected() } catch (e) { }
    if (!state.connected && !up) { logN "not connected, reconnecting"; connect(); return }
    if (!state.connected) state.connected = true
    logN "refresh requested"
    unschedule("startSample")
    startSample()
}

// For the app's status line.
String getSerial() { serial() }

def uninstalled() {
    mem().closingAt = now()
    try { interfaces.mqtt.disconnect() } catch (e) { }
    RUNTIME.remove(device.id.toString())
}

// From the app on Done: printer identity (no access code) and options.
void configure(Map printer, Map opts) {
    if (state.printer != printer) {
        if (printer?.ip != state.printer?.ip) state.remove("serial")   // different printer, learn again
        state.printer = printer
    }
    if (state.opts != opts) state.opts = opts
    mem().remove("opts")
    report("mode", opts?.control ? "Monitor and control" : "Monitor only")
}

private opt(String k) {
    if (k == "interval") return ((settings?.checkEvery ?: "30") as String).toInteger()
    if (k == "tempStep") return ((settings?.tempStep ?: "5") as String).toBigDecimal()
    if (k == "unit") return settings?.tempUnit ?: "C"
    Map m = mem()
    Map o = m.opts as Map
    if (o == null) { o = (state.opts ?: [:]) as Map; m.opts = o }
    return o[k]
}

private String serial() { (state.serial ?: state.printer?.serial)?.toString() }

// Every minute: reconnect a dropped client, and restart sampling if its timer was lost (driver save).
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
    if (!m.sampling && (m.nextSampleAt == null || now() > (m.nextSampleAt as long) + 60000L)) startSample()
}

// ============================================================
// Connection
// ============================================================

def connect() {
    Map m = mem()
    m.reconnectPending = false
    m.connectAt = now()
    Map p = state.printer
    if (!p?.ip) return
    String code = null
    try { code = parent?.getAccessCode(device.deviceNetworkId) } catch (e) { logE "could not get the access code from the app: ${e.message}" }
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
    m.sampling = false
    m.missed = 0
    m.verPending = true
    startSample()
}

// ============================================================
// Sampling: subscribe, one full report, unsubscribe
// ============================================================

// Wildcard until the printer itself has told us its serial, so a mistyped serial in the app can't block data.
private String reportTopic() { state.serial ? "device/${state.serial}/report" : "device/+/report" }

def startSample() {
    Map m = mem()
    if (!state.connected || m.sampling) return
    String t = reportTopic()
    try { interfaces.mqtt.subscribe(t) } catch (e) {
        markDown("subscribe failed: ${e.message}")
        scheduleReconnect()
        return
    }
    m.sampling = true
    m.subTopic = t
    m.sampleAt = now()
    pushAll()
    if (m.verPending && state.serial) { m.verPending = false; publish([info: [sequence_id: seq(), command: "get_version"]]) }
    runIn(SAMPLE_WAIT, "sampleTimeout")
}

// End of the listen window after a command, for printers that sent their one status early.
def holdEnd() { if (mem().sampling) endSample() }

private void endSample() {
    Map m = mem()
    if (!m.sampling) return
    m.sampling = false
    m.missed = 0
    try { interfaces.mqtt.unsubscribe(m.subTopic as String) } catch (e) { }
    unschedule("sampleTimeout")
    markAlive()
    checkExpect(m.rep as Map)   // after the whole listen window, so a refusal sent a few seconds late counts
    int secs = nextSecs()
    m.nextSampleAt = now() + secs * 1000L
    runIn(secs, "startSample")
}

def sampleTimeout() {
    Map m = mem()
    if (!m.sampling) return
    m.sampling = false
    try { interfaces.mqtt.unsubscribe(m.subTopic as String) } catch (e) { }
    int missed = ((m.missed ?: 0) as int) + 1
    m.missed = missed
    if (!state.serial) {
        // Never heard from it: an idle printer may send nothing until asked, and asking needs the serial.
        setStatus("Waiting for printer")
        if (missed == 1) logE "connected, but no status from the printer yet. If it stays this way, enter its serial in the app (printer Settings, Device)."
    } else if (missed >= 3) {
        markDown("no reply to 3 status requests")
        scheduleReconnect()
        return
    } else {
        logF "no status reply (${missed} of 3)"
    }
    int secs = state.serial ? 30 : IDLE_SECS
    m.nextSampleAt = now() + secs * 1000L
    runIn(secs, "startSample")
}

private int nextSecs() {
    String ps = (mem().ps ?: device.currentValue("printState")) as String
    (ps in ACTIVE) ? Math.max(15, ((opt("interval") ?: 30) as int)) : IDLE_SECS
}

// After a command: sample now and keep listening a few seconds for the printer's reply.
private void sampleAfterCommand() {
    Map m = mem()
    m.holdUntil = now() + 5000L
    if (!m.sampling) { unschedule("startSample"); runIn(1, "startSample") }
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
    if (!state.connected || !serial()) return false
    try {
        interfaces.mqtt.publish("device/${serial()}/request", JsonOutput.toJson(msg))
        return true
    } catch (e) {
        logE "could not send to the printer: ${e.message}"
        return false
    }
}

private void pushAll() {
    if (serial()) publish([pushing: [sequence_id: seq(), command: "pushall", version: 1, push_target: 1]])
}

// ============================================================
// Reports
// ============================================================

def parse(String raw) {
    Map m = mem()
    if (!m.sampling) return   // stragglers after unsubscribe
    m.lastRx = now()
    Map pm
    try { pm = interfaces.mqtt.parseMessage(raw) } catch (e) { logF "unreadable MQTT message"; return }
    String payload = pm?.payload
    if (!payload) return
    learnSerial(pm.topic?.toString())
    if (lvl() >= 2) logF "received ${payload.length() > 400 ? payload.take(400) + '...' : payload}"
    def j
    try { j = new JsonSlurper().parseText(payload) } catch (e) { logF "unreadable report: ${payload.take(120)}"; return }
    if (!(j instanceof Map)) return
    if (j.print instanceof Map) {
        Map pr = j.print as Map
        checkResult(pr, "print")
        if (pr.command == "push_status" || pr.containsKey("gcode_state") || pr.containsKey("mc_percent")) {
            markAlive()
            merge(m.rep as Map, pr)
            render()
            if (pr.containsKey("gcode_state")) {
                long wait = ((m.holdUntil ?: 0L) as long) - now()
                if (wait <= 0) endSample()
                else runIn(Math.max(1, Math.ceil(wait / 1000.0) as int), "holdEnd")   // an idle printer may not send another
            }
        }
    }
    if (j.system instanceof Map) checkResult(j.system as Map, "system")
    if (j.info instanceof Map && j.info.command == "get_version") readVersion(j.info as Map)
}

private void learnSerial(String topic) {
    def mt = (topic ?: "") =~ /^device\/([^\/]+)\/report$/
    if (!mt.find()) return
    String s = mt.group(1)
    if (s == state.serial) return
    String typed = state.printer?.serial?.toString()
    state.serial = s
    if (typed && typed != s) logE "the serial in the app (${typed}) doesn't match the printer (${s}). Using the printer's. You can clear the serial in the app."
    else logN "printer serial is ${s}"
    Map m = mem()
    String t = "device/${s}/report"
    if (m.sampling && m.subTopic != t) {
        try { interfaces.mqtt.unsubscribe(m.subTopic as String); interfaces.mqtt.subscribe(t); m.subTopic = t } catch (e) { }
    }
}

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
        refused(r.command?.toString())
        return
    }
    logE "the printer refused ${r.command ?: kind + ' command'}${r.reason ? ' (' + r.reason + ')' : ''}"
}

private void readVersion(Map info) {
    def mod = (info.module instanceof List) ? info.module.find { it.name == "ota" } : null
    if (mod?.sw_ver) report("firmware", mod.sw_ver.toString())
    String pn = mod?.product_name?.toString()
    if (pn) report("model", pn.replaceFirst(/^Bambu Lab\s+/, ""))
}

// Merged report -> attributes, children and tile.
private void render() {
    Map r = mem().rep as Map
    String gs = r.gcode_state?.toString()
    // This run's value: currentValue can still return the old one right after the event.
    String ps = device.currentValue("printState") ?: ""
    if (gs) {
        String now_ = STATES[gs] ?: gs.toLowerCase().capitalize()
        String old = device.currentValue("printState")
        if (old != now_) {
            logN "print state: ${now_}"
            if (now_ in ACTIVE && old != null && !(old in ACTIVE)) state.printStartAt = now()
        }
        if (!(now_ in ACTIVE) && state.printStartAt != null) state.remove("printStartAt")
        report("printState", now_)
        ps = now_
        mem().lastGs = gs
    }
    mem().ps = ps
    boolean active = ps in ACTIVE
    Integer stg = toInt(r.stg_cur)
    report("stage", active ? (stg && stg != 255 && stg > 0 ? (STAGES[stg] ?: "Stage ${stg}") : (ps == "Printing" ? "Printing" : ps)) : ps)
    if (r.containsKey("mc_percent")) report("progress", toInt(r.mc_percent) ?: 0, "%")
    if (r.containsKey("mc_remaining_time")) {
        int mins = active ? (toInt(r.mc_remaining_time) ?: 0) : 0
        report("timeRemaining", mins, "min", 5)
        String eta = "--"
        if (active && mins > 0) {
            Date at = new Date(now() + mins * 60000L)
            boolean today = at.format("yyyyMMdd", location.timeZone) == new Date(now()).format("yyyyMMdd", location.timeZone)
            eta = at.format(today ? "h:mm a" : "EEE h:mm a", location.timeZone)
        }
        if (eta == "--" || (cv("timeRemaining") as Integer) == mins) report("ETA", eta)   // ETA moves with timeRemaining's 5 min steps
    } else if (!active) report("ETA", "--")
    Long start = toLong(r.gcode_start_time)
    Long startMs = (start && start > 0) ? start * 1000L : (state.printStartAt as Long)
    if (active && startMs) report("elapsedTime", Math.max(0, ((now() - startMs) / 60000) as int), "min", 5)
    else if (ps == "Idle") report("elapsedTime", 0, "min")
    if (r.containsKey("layer_num")) report("currentLayer", toInt(r.layer_num) ?: 0)
    if (r.containsKey("total_layer_num")) report("totalLayers", toInt(r.total_layer_num) ?: 0)
    String fn = (r.subtask_name ?: r.gcode_file ?: "").toString()
    if (gs || fn) report("fileName", active && fn ? fn : "Not printing")
    if (fn && ps in ["Finished", "Failed"]) report("lastPrint", fn)
    if (r.containsKey("spd_lvl")) { Integer s = toInt(r.spd_lvl); report("speed", s in 1..4 ? SPEEDS[s] : "Standard") }
    if (r.wifi_signal) {
        Integer db = toInt(r.wifi_signal.toString().replaceAll(/[^\-0-9]/, ""))
        Integer last = mem().wifiDb as Integer
        if (db == null || last == null || Math.abs(db - last) >= 5) { mem().wifiDb = db; report("wifiSignal", r.wifi_signal.toString()) }
    }
    temps(r)
    errors(r)
    ams(r)
    spools(r)
    light(r)
    door(r)
    firmwareUpdate(r)
    tile()
}

// Door from print.stat (hex), bit 0x00800000 set = open. H2D confirmed.
private void door(Map r) {
    if (r.stat == null) return
    Long s
    try { s = Long.parseLong(r.stat.toString(), 16) } catch (e) { return }
    String v = (s & DOOR_BIT) != 0L ? "open" : "closed"
    if (device.currentValue("door") != v) logN "door ${v}"
    report("door", v)
}

private void firmwareUpdate(Map r) {
    if (!(r.upgrade_state instanceof Map)) return
    Map u = r.upgrade_state as Map
    String nv = (u.ota_new_version_number ?: "").toString()
    boolean avail = toInt(u.new_version_state) == 1 || nv
    String v = avail ? (nv ? "available: ${nv}" : "available") : "none"
    if (avail && device.currentValue("firmwareUpdate") != v) logN "printer firmware update ${nv ? nv + ' ' : ''}available"
    report("firmwareUpdate", v)
}

// External spool holders: vir_slot ids 254 and 255 (H2D), or vt_tray (one holder). Shown once a spool is loaded.
private void spools(Map r) {
    List slots = r.vir_slot instanceof List ? r.vir_slot as List : (r.vt_tray instanceof Map ? [r.vt_tray] : [])
    slots.each { tr ->
        if (!(tr instanceof Map)) return
        Integer id = toInt(tr.id)
        if (id == null || id < 254) return
        String name = id == 254 ? "externalSpool" : "externalSpool2"
        String type = tr.tray_type?.toString()
        if (!type && device.currentValue(name) == null) return
        String desc = type ? "${type}${tr.tray_sub_brands ? ' (' + tr.tray_sub_brands + ')' : ''}" : "empty"
        Integer rem = toInt(tr.remain)
        if (type && rem != null && rem > 0) desc += ", ${rem}%"
        String col = type && tr.tray_color ? "#${tr.tray_color.toString().take(6)}" : "none"
        report(name, desc)
        report("${name}Color", col)
    }
}

private void temps(Map r) {
    BigDecimal step = (opt("tempStep") ?: 5) as BigDecimal
    List ext = (r.device instanceof Map && r.device.extruder instanceof Map && r.device.extruder.info instanceof List) ? r.device.extruder.info as List : []
    def n1 = r.nozzle_temper, n1t = r.nozzle_target_temper
    if (n1 == null && ext) { Map e = ext.find { toInt(it.id) == 0 } as Map ?: ext[0] as Map; n1 = packedCur(e?.temp); n1t = packedTarget(e?.temp) }
    heater("nozzle", n1, n1t, step, true)
    if (ext.size() > 1) {
        Map e2 = ext.find { toInt(it.id) == 1 } as Map
        if (e2) heater("nozzle2", packedCur(e2.temp), packedTarget(e2.temp), step, false)
    }
    def b = r.bed_temper, bt = r.bed_target_temper
    if (b == null && r.device instanceof Map && r.device.bed instanceof Map && r.device.bed.info instanceof Map) {
        b = packedCur(r.device.bed.info.temp); bt = packedTarget(r.device.bed.info.temp)
    }
    heater("bed", b, bt, step, false)
    def c = r.chamber_temper
    if (c == null && r.device instanceof Map && r.device.ctc instanceof Map && r.device.ctc.info instanceof Map) c = packedCur(r.device.ctc.info.temp)
    if (c != null) temp("chamberTemp", c, step)
}

// Status always (with the target while heating); current temperature only at temp or off, in the app's step.
private void heater(String n, cur, target, BigDecimal step, boolean main) {
    BigDecimal c = num(cur), t = num(target)
    if (c == null) return
    t = t ?: 0
    String st = t > 0 ? (c < t - AT_TEMP_C ? "Heating" : c > t + AT_TEMP_C ? "Cooling" : "At temp") : (c > OFF_C ? "Cooling" : "Off")
    String shown = st == "Heating" ? "Heating to ${Math.round(toScale(t) as double)}°" : st
    boolean changed = device.currentValue("${n}Status") != shown
    report("${n}Status", shown)
    if (st in ["At temp", "Off"]) temp("${n}Temp", c, changed ? 0 : step)
}

// Celsius unless the app is set to follow the hub's scale.
private String unitScale() { opt("unit") == "hub" ? (location.temperatureScale ?: "C") : "C" }
private BigDecimal toScale(BigDecimal c) { unitScale() == "F" ? c * 9 / 5 + 32 : c }

// H2 series: current in the low 16 bits, target in the high 16 bits.
private Integer packedCur(v) { Long n = toLong(v); n == null ? null : (int) (n & 0xFFFF) }
private Integer packedTarget(v) { Long n = toLong(v); n == null ? null : (int) ((n >> 16) & 0xFFFF) }

private void temp(String name, c, BigDecimal step) {
    BigDecimal v = num(c)
    if (v == null) return
    report(name, toScale(v).setScale(1, java.math.RoundingMode.HALF_UP), "°${unitScale()}", step)
}

// HMS list -> count and codes (HMS_AAAA_BBBB_CCCC_DDDD, Bambu's wiki format); print_error as a fallback.
// A refused unsigned command is not a printer fault, so it's left out.
private void errors(Map r) {
    if (!r.containsKey("hms") && !r.containsKey("print_error")) return
    List codes = []
    boolean wasRefused = false
    if (r.hms instanceof List) {
        r.hms.each { h ->
            Long a = toLong(h.attr), c = toLong(h.code)
            if (a != null && c != null) {
                String s = String.format("%08X%08X", a, c)
                if (s in REFUSED_HMS) { wasRefused = true; return }
                codes << "HMS_${s[0..3]}_${s[4..7]}_${s[8..11]}_${s[12..15]}"
            }
        }
    }
    Long pe = toLong(r.print_error)
    if (pe && pe != 0) {
        if (pe == DEV_MODE_PE) wasRefused = true
        else codes << String.format("ERR_%08X", pe)
    }
    // The code can sit in the list for a while: note it every time, tell the user once per appearance.
    Map m = mem()
    if (wasRefused) {
        m.refusedAt = now()
        if (!m.refusedPresent) { m.refusedPresent = true; if (!m.expect) refusedNotice("a command") }
    } else if (m.refusedPresent) m.remove("refusedPresent")
    codes = codes.unique()
    String joined = codes.join(", ")
    String was = device.currentValue("errors") ?: "none"
    if ((joined ?: "none") != was && codes) {
        List old = was == "none" ? [] : was.split(", ").toList()
        (codes - old).each { announce(it as String) }
    }
    report("errorCount", codes.size())
    report("errors", joined ?: "none")
    errorText(codes)
}

// ============================================================
// Error text: per-module tables in this integration's GitHub folder
// ============================================================

private Map hmsCache() { (state.hmsText ?: [:]) as Map }

// Log one new code: with its text if known, else after the lookup.
private void announce(String code) {
    Map hit = hmsCache()[code] as Map
    if (hit?.t) { logE "printer error ${code}: ${hit.t} ${hit.u}"; return }
    if (!hmsLookup(code)) logE "printer error ${code}. Look it up: ${WIKI_HOME}"
}

private void errorText(List codes) {
    Map c = hmsCache()
    String t = codes.collect { (c[it] as Map)?.t ?: it }.join(" | ")
    report("errorText", t ?: "none")
}

// For the app's card: [code, text, link] per current error.
List getErrorDetails() {
    String e = device.currentValue("errors") ?: "none"
    if (e == "none") return []
    Map c = hmsCache()
    return e.split(", ").collect { String k -> Map h = c[k] as Map; [k, h?.t ?: "", h?.u ?: WIKI_HOME] }
}

private String hexOf(String code) { code.replaceAll(/^(HMS|ERR)_/, "").replace("_", "") }

private String hmsFile(String hex) {
    String p = hex.take(2)
    return (AMS_FAMILY.containsKey(p) && hex.length() >= 4 && hex[2..3] ==~ /0[0-7]/) ? p + "00" : hex.take(4)
}

// true if a lookup started (once per code per driver run).
private boolean hmsLookup(String code) {
    Map m = mem()
    Set asked = (m.hmsAsked ?: (m.hmsAsked = java.util.concurrent.ConcurrentHashMap.newKeySet())) as Set
    if (!asked.add(code)) return false
    try {
        asynchttpGet("hmsFetched", [uri: HMS_URL + hmsFile(hexOf(code)) + ".json", contentType: "text/plain", timeout: 20], [code: code])
        return true
    } catch (e) {
        logF "error text lookup failed: ${e.message}"
        return false
    }
}

def hmsFetched(resp, Map data) {
    String code = data?.code
    if (!code) return
    Map hit = null
    try {
        if (resp.status == 200) {
            def tbl = new JsonSlurper().parseText(resp.data as String)
            if (tbl instanceof Map) hit = hmsFind(tbl as Map, hexOf(code))
        } else logF "no error text for ${code} (HTTP ${resp.status})"
    } catch (e) { logF "error text lookup failed: ${e.message}" }
    if (!hit) { logE "printer error ${code}. Look it up: ${WIKI_HOME}"; return }
    Map c = hmsCache()
    c[code] = hit
    while (c.size() > 40) c.remove(c.keySet().iterator().next())
    state.hmsText = c
    logE "printer error ${code}: ${hit.t} ${hit.u}"
    String e = device.currentValue("errors") ?: "none"
    errorText(e == "none" ? [] : e.split(", ").toList())
}

// Table rows: [text, wiki, h2 wiki]. A wiki value is a model folder (x1, h2), "folder|-" for dashed codes,
// or a full path. AMS units B to H share unit A's row with the letter changed.
private Map hmsFind(Map tbl, String hex) {
    List v = tbl[hex] as List
    String base = hex
    String text = v ? v[0] as String : null
    if (!v && hex.length() == 16 && AMS_FAMILY.containsKey(hex.take(2)) && hex[2..3] != "00") {
        base = hex.take(2) + "00" + hex.substring(4)
        v = tbl[base] as List
        if (v) {
            String label = AMS_FAMILY[hex.take(2)] as String
            int unit = Integer.parseInt(hex[2..3], 16)
            text = (v[0] as String).replaceFirst(label + "A", label + ((char) (65 + unit)).toString())
        }
    }
    if (!v || !text) return null
    return [t: text, u: wikiUrl(base, v)]
}

private String wikiUrl(String hex, List v) {
    if (hex.length() != 16) return WIKI_HOME
    boolean h2 = (device.currentValue("model") ?: "").toString().startsWith("H2")
    String seg = (h2 && v.size() > 2 && v[2]) ? v[2] as String : (v.size() > 1 ? v[1] as String : "")
    if (!seg) return WIKI_HOME
    if (seg.contains("/")) return WIKI + seg
    boolean dash = seg.endsWith("|-")
    String folder = seg - "|-"
    String c = [hex[0..3], hex[4..7], hex[8..11], hex[12..15]].join(dash ? "-" : "_")
    return "${WIKI}/en/${folder}/troubleshooting/hmscode/${c}"
}

// ============================================================
// Command checks: did the printer act on it?
// ============================================================

private void expect(String what, String kind, want) { mem().expect = [what: what, kind: kind, want: want, at: now(), tries: 0] }

private void checkExpect(Map r) {
    Map m = mem()
    Map x = m.expect as Map
    if (!x || ((m.sampleAt ?: 0L) as long) <= (x.at as long)) return   // only a status asked for after the command
    boolean ok
    switch (x.kind) {
        case "speed": ok = toInt(r.spd_lvl) == (x.want as int); break
        case "state": ok = (x.want as List).contains(r.gcode_state?.toString()); break
        case "light": ok = lightNow(r) == x.want; break
        default: ok = true
    }
    if (ok) { m.remove("expect"); logN "${x.what}: done"; return }
    if (((m.refusedAt ?: 0L) as long) >= (x.at as long) - 1000L) { m.remove("expect"); refusedNotice(x.what as String); return }
    if ((x.tries as int) < 1) { x.tries = 1; x.at = now(); return }   // one more status before giving up
    m.remove("expect")
    logE "the printer didn't act on ${x.what}. If it keeps happening, check Developer Mode on the printer (Settings, LAN Only)."
}

// Seen in a reply or the HMS list. Logged by checkExpect against the command, or here if none is pending.
private void refused(String cmd) {
    Map m = mem()
    m.refusedAt = now()
    if (!m.expect) refusedNotice(cmd ?: "a command")
}

private void refusedNotice(String what) {
    Map m = mem()
    if (now() - ((m.refusedLogged ?: 0L) as long) < 60000L) return
    m.refusedLogged = now()
    if (!m.devModeWarned) {
        m.devModeWarned = true
        logE "the printer refused ${what}: Printer Control from Hubitat needs Developer Mode on the printer (Settings, LAN Only, Developer Mode), which turns off Bambu Cloud. Or set this printer to Monitor only in the app. Monitoring and the light keep working."
    } else logE "the printer refused ${what} (needs Developer Mode)"
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
        if (hum != null) ev << [name: "humidity", value: hum.setScale(0, java.math.RoundingMode.HALF_UP), unit: "%", delta: 5]
        if (u.humidity != null) ev << [name: "dryness", value: "${u.humidity}/5"]
        BigDecimal t = num(u.temp)
        if (t != null) {
            ev << [name: "temperature", value: toScale(t).setScale(1, java.math.RoundingMode.HALF_UP), unit: "°${unitScale()}", delta: (opt("tempStep") ?: 5)]
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

// Every chamber_light* node (H2D has two). On when any of them is.
private List lightNodes(Map r) {
    (r.lights_report instanceof List) ? r.lights_report.findAll { it instanceof Map && it.node?.toString()?.startsWith("chamber_light") } : []
}
private String lightNow(Map r) { List n = lightNodes(r); n ? (n.any { it.mode != "off" } ? "on" : "off") : null }

private void light(Map r) {
    List nodes = lightNodes(r)
    if (!nodes) return
    List names = nodes.collect { it.node.toString() }.sort()
    if (state.lightNodes != names) state.lightNodes = names
    String v = lightNow(r)
    report("chamberLight", v)
    catalogPut("light", [k: "light", n: "Chamber light"])
    def child = childFor("light")
    if (child) child.parse([[name: "switch", value: v]])
}

void componentOn(cd)  { setLight(true) }
void componentOff(cd) { setLight(false) }
void componentRefresh(cd) { refresh() }

// Works in both modes: the printer takes ledctrl without Developer Mode.
private void setLight(boolean on) {
    List nodes = (state.lightNodes ?: ["chamber_light"]) as List
    boolean sent = false
    nodes.each { n ->
        if (publish([system: [sequence_id: seq(), command: "ledctrl", led_node: n, led_mode: on ? "on" : "off",
                              led_on_time: 500, led_off_time: 500, loop_times: 0, interval_time: 0]])) sent = true
    }
    if (!sent) { logE "not connected, light command not sent"; return }
    logN "chamber light ${on ? 'on' : 'off'}"
    expect("chamber light ${on ? 'on' : 'off'}", "light", on ? "on" : "off")
    sampleAfterCommand()
}

// ============================================================
// Print control (Monitor and control mode only)
// ============================================================

def printerControl(String action) {
    switch (action) {
        case "Pause": pause(); break
        case "Resume": resume(); break
        case "Stop": stop(); break
        default:
            if (action?.startsWith("Speed: ")) setSpeed(action - "Speed: ")
            else logE "unknown Printer Control action ${action}"
    }
}

def pause()  { if (printCommand("pause", "")) expect("pause", "state", ["PAUSE"]) }
def resume() { if (printCommand("resume", "")) expect("resume", "state", ["RUNNING", "PREPARE"]) }
def stop() {
    if (opt("control") && !opt("allowStop")) { logE "Stop is turned off for this printer. Turn on Allow Stop in the app to use it."; return }
    if (printCommand("stop", "")) expect("stop", "state", ["FINISH", "FAILED", "IDLE"])
}

def setSpeed(String speed) {
    int i = SPEEDS.indexOf(speed)
    if (i < 1) { logE "unknown speed ${speed}. Use Silent, Standard, Sport or Ludicrous."; return }
    if (printCommand("print_speed", "${i}")) expect("speed ${speed}", "speed", i)
}

private boolean printCommand(String cmd, String param) {
    if (!opt("control")) {
        logE "monitor only: ${cmd} not sent. Use the Bambu apps, or set this printer to Monitor and control in the Bambu Lab Integration app (needs Developer Mode)."
        return false
    }
    if (!state.connected) { logE "not connected to the printer, ${cmd} not sent"; return false }
    if (publish([print: [sequence_id: seq(), command: cmd, param: param]])) { logN "${cmd}${param ? ' ' + param : ''} sent"; sampleAfterCommand(); return true }
    logE "${cmd} not sent: the printer's serial isn't known yet"
    return false
}

// ============================================================
// Tile (under 1024 characters)
// ============================================================

private void tile() {
    String html = null
    for (List lim in [[40, 60], [28, 40], [20, 24], [16, 0]]) {   // shrink file and error text until it fits
        html = tileHtml(lim[0] as int, lim[1] as int)
        if (html.getBytes("UTF-8").length <= 1024) break
    }
    Map m = mem()
    if (html != m.lastTile) { m.lastTile = html; report("tile", html) }
}

// Moonraker driver style: dark card, text scales with the tile (cqw).
private String tileHtml(int fileMax, int errMax) {
    String ps = cv("printState") ?: "Unknown"
    boolean active = ps in ACTIVE
    int pct = (cv("progress") ?: 0) as int
    String col = ps == "Printing" ? "#00ff87" : ps in ["Paused", "Preparing", "Starting"] ? "#ffd166" : ps == "Failed" ? "#ff6b6b" : ps == "Finished" ? "#00b4d8" : "#aaa"
    String icon = ps == "Printing" ? "&#9654; " : ps == "Paused" ? "&#9646;&#9646; " : ""
    boolean up = cv("connectionStatus") == "Connected"
    StringBuilder t = new StringBuilder()
    t << "<div><b style='color:#00ae42'>${short_(device.displayName, 28)}</b> <span style='color:${up ? '#00ff87' : '#ffd166'}'>&#9679;</span></div>"
    t << "<div style='color:${col}'>${icon}${ps}"
    String file = cv("fileName")
    if (active && file && file != "Not printing") t << " &middot; <span style='color:#eee'>${short_(file, fileMax)}</span>"
    t << "</div>"
    String noz = heat("nozzle") + (cv("nozzle2Status") != null ? "/" + heat("nozzle2") : "")
    t << "<div>Nozzle <b style='color:#ff6b6b'>${noz}</b> &middot; Bed <b style='color:#ffa94d'>${heat('bed')}</b>" +
        (cv("chamberTemp") != null ? " &middot; Ch <b>${Math.round(cv('chamberTemp') as double)}&deg;</b>" : "") + "</div>"
    if (active) {
        int left = (cv("timeRemaining") ?: 0) as int
        String eta = cv("ETA")
        t << "<div style='background:#0f3460;height:.4em;border-radius:1em'><div style='background:#00ae42;width:${pct}%;height:100%;border-radius:1em'></div></div>"
        t << "<div>${pct}% &middot; L${cv('currentLayer') ?: 0}/${cv('totalLayers') ?: 0}" +
            (left > 0 ? " &middot; <span style='color:#00ff87'>${hm(left)} left</span>" : "") + (eta && eta != "--" ? " &middot; ${eta}" : "") + "</div>"
    } else {
        String last = cv("lastPrint")
        if (last) t << "<div style='color:#888'>Last: <span style='color:#ddd'>${short_(last, fileMax)}</span></div>"
    }
    if (cv("door") == "open") t << "<div style='color:#ffd166'>Door open</div>"
    int errs = (cv("errorCount") ?: 0) as int
    if (errs > 0) {
        String et = cv("errorText")
        t << "<div style='color:#ff6b6b'>&#9888; " + (errMax > 0 && et && et != "none" ? short_(et, errMax) : "${errs} error${errs == 1 ? '' : 's'}") + "</div>"
    }
    return "<div style='height:100%;box-sizing:border-box;padding:4%;container-type:inline-size;background:#1a1a2e;border-radius:10px'>" +
        "<div style='height:100%;font:4.6cqw/1.35 sans-serif;color:#ddd;text-align:left;text-shadow:none;" +
        "display:flex;flex-direction:column;justify-content:space-around'>${t}</div></div>"
}

// Heater for the tile: arrow and target while heating, else the temperature.
private String heat(String n) {
    String st = cv("${n}Status")
    def mt = (st ?: "") =~ /^Heating to (\d+)/
    if (mt.find()) return "&rarr;${mt.group(1)}&deg;"
    def v = cv("${n}Temp")
    return v == null ? "-" : "${Math.round(v as double)}&deg;"
}

// Cut raw text, then escape, so an entity is never split.
private String short_(text, int max) {
    String s = (text ?: "").toString()
    if (max <= 0) return ""
    return s.length() > max ? esc(s.take(max - 1)) + "&hellip;" : esc(s)
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

private boolean report(String name, value, String unit = null, delta = 0) {
    if (!moved(cv(name), value, delta)) return false
    Map e = [name: name, value: value]
    if (unit) e.unit = unit
    sendEvent(e)
    Map c = (mem().cv ?: (mem().cv = new java.util.concurrent.ConcurrentHashMap())) as Map
    c[name] = value
    return true
}

// This run's value if it was just reported (currentValue can lag right after sendEvent).
private cv(String name) {
    Map c = mem().cv as Map
    return (c != null && c.containsKey(name)) ? c[name] : device.currentValue(name)
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
    if (device.currentValue("connectionStatus") != v) { sendEvent(name: "connectionStatus", value: v); state.statusAt = now() }
}

// For the app: ms since connectionStatus last changed.
Long statusAge() { state.statusAt ? now() - (state.statusAt as long) : null }

// A status arrived, so the link is up whatever the label says.
private void markAlive() {
    if (!state.connected) state.connected = true
    if (state.downSince != null) { state.remove("downSince"); state.remove("downWarned") }
    setStatus("Connected")
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
