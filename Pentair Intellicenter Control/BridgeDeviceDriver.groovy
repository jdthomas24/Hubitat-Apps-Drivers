/*
    Pentair IntelliCenter - Bridge Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -Created by the app. Holds the WebSocket to the controller (ws://IP:6680, IC2 may use 6681) and
      creates the equipment devices under itself: Body (pool/spa), Pump, Chlorinator, Generic Component
      Switch (circuits, features, groups) and Generic Component Temperature Sensor (air, solar).
     -Startup reads each type with GetParamList (circuits, bodies, heaters, pumps, sensors, chlorinator),
      then RequestParamList subscribes to live NotifyList pushes. A light poll (default 30s) re-reads
      state in case a push is missed.
     -The controller answers a key it doesn't have with "": "<KEY>" (no WATTS on some pumps, no HTRLST),
      so numbers are parsed safely and blank keys dropped. CIRCGRP queries return junk; groups already
      arrive in the CIRCUIT list as GRPxx. Internal circuits (X00xx, _xxxx) and body circuits are skipped.
     -Catalog (objnam -> kind, name, subtype, type) lives in state, written only when it changes. It feeds
      the app's Equipment page and supplies OBJTYP for pushes that omit it.
     -Excluded objnams are never created. A device created once and later deleted from the Devices page
      is excluded automatically. On the first run after updating from 1.6.x, equipment with no device
      counts as deleted, so devices users removed before stay removed.
     -Health: every message stamps lastRx in memory. An open socket with no data for max(3 polls, 3 min)
      reconnects (a half-open socket looks connected). Reconnect check every 2 min. Offline warns once,
      back online logs once.
     -Commands return true only when sent. Devices update only then, so a dropped command never shows a
      state that didn't happen; the controller's push confirms it within about a second.
     -Temperature units follow the hub's scale; values pass through as the controller reports them.
     -Log level comes from the app (setLogLevel) and is passed to the Body, Pump and Chlorinator.
      All log lines go up to the app's childLog().
*/

metadata {
    definition(name: "Pentair IntelliCenter Bridge", namespace: "intellicenter", author: "jdthomas24") {
        capability "Initialize"
        capability "Refresh"

        attribute "connectionStatus", "string"

        command "connect"
        command "disconnect"
        command "circuitOn",  [[name: "DNI*", type: "STRING", description: "Child device DNI"]]
        command "circuitOff", [[name: "DNI*", type: "STRING", description: "Child device DNI"]]
    }

    preferences {
        input "ipAddress",    "text",   title: "IntelliCenter IP address", description: "Set by the Pentair IntelliCenter app", required: true
        input "portNumber",   "number", title: "Port", description: "Set by the app. 6680, or 6681 on some IntelliCenter 2 systems", defaultValue: 6680
        input "pollInterval", "number", title: "Poll interval (seconds)", description: "Backup re-read of all equipment. Live changes arrive instantly either way.", defaultValue: 30
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.transform.Field

@Field static final String VERSION     = "1.7.0"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]
@Field static final Map PREFIX = [body: "intellicenter-body-", pump: "intellicenter-pump-", circuit: "intellicenter-circuit-",
                                  sensor: "intellicenter-sensor-", chem: "intellicenter-chem-"]
@Field static final Map HTMODE_NAMES = ["0": "Off", "1": "Heater", "2": "Solar Only", "3": "Solar Preferred",
                                        "4": "Heat Pump", "5": "Heat Pump Preferred", "OFF": "Off"]
@Field static final Map HTSRC_NAMES = ["00000": "Off", "H0001": "Heater", "S0001": "Solar Only",
                                       "H0002": "Solar Preferred", "H0003": "Heat Pump", "H0004": "Heat Pump Preferred"]
@Field static final Map HTSRC_IDS = ["Heater": "H0001", "Solar Only": "S0001", "Solar Preferred": "H0002",
                                     "Heat Pump": "H0003", "Heat Pump Preferred": "H0004"]
// Per-device working data (last message time, expected close). Lost on a driver save, which is safe.
@Field static final java.util.concurrent.ConcurrentHashMap RUNTIME = new java.util.concurrent.ConcurrentHashMap()

String fmtHelpInfo() {
    String info     = "Pentair IntelliCenter v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

private Map mem() {
    String k = device.id.toString()
    Map m = RUNTIME.get(k) as Map
    if (m == null) { m = new java.util.concurrent.ConcurrentHashMap(); RUNTIME.put(k, m) }
    return m
}

// ============================================================
// Lifecycle
// ============================================================

def installed() { initialize() }

def updated() {
    ["debugMode", "endpointBase"].each { device.removeSetting(it) }
    initialize()
}

def initialize() {
    ["msgBuffer", "objectMap", "pendingCmds", "lastPollTime", "htsrcIds"].each { state.remove(it) }
    migrate()
    unschedule()
    schedule("0 0/2 * * * ?", "reconnectIfNeeded")
    schedule("30 * * * * ?", "healthCheck")
    connect()
}

// First run of 1.7.0. On an existing install, equipment with no device was deleted by the user.
private void migrate() {
    if (state.known != null) return
    List kids = getChildDevices()*.deviceNetworkId
    state.known = kids.collect { objnamFromDni(it) }.findAll { it }
    if (kids) state.seeding = true
    if (state.excluded == null) state.excluded = []
}

// Jobs scheduled by 1.6.x run until the next Done or reboot; these hand them to 1.7.0.
def pollWatchdog() { initialize() }
def requestGroups() { requestBodies() }
def disableDebugLogging() { }

def refresh() {
    if (state.connected) requestEquipment() else connect()
}

// ============================================================
// Connection
// ============================================================

def connect() {
    if (!ipAddress) {
        logE "No IP address. Open the Pentair IntelliCenter app, enter it and tap Done."
        setStatus("Not Configured")
        return
    }
    String uri = "ws://${ipAddress}:${portNumber ?: 6680}"
    logF "connecting to ${uri}"
    mem().closing = true   // the previous socket's close is expected
    try { interfaces.webSocket.close() } catch (e) { }
    try {
        interfaces.webSocket.connect(uri)
    } catch (e) {
        markDown("connect failed: ${e.message}")
    }
}

def disconnect() {
    mem().closing = true
    unschedule("pollState")
    try { interfaces.webSocket.close() } catch (e) { }
    state.connected = false
    setStatus("Disconnected")
    logN "disconnected (Disconnect command)"
}

def webSocketStatus(String message) {
    logF "WebSocket ${message}"
    Map m = mem()
    if (message.startsWith("status: open")) {
        m.closing = false
        m.lastRx = now()
        state.connected = true
        setStatus("Connected")
        if (state.downSince != null) {
            int mins = ((now() - (state.downSince as long)) / 60000) as int
            slog("info", "IntelliCenter back online after ${mins} min")
            state.remove("downSince")
        } else {
            logN "connected to ${ipAddress}"
        }
        runIn(1, "requestEquipment")
    } else if (message.contains("clos") || message.contains("failure") || message.contains("error")) {
        if (m.closing && message.contains("clos")) { m.closing = false; return }
        markDown(message)
    }
}

private void markDown(String why) {
    state.connected = false
    unschedule("pollState")
    setStatus("Disconnected")
    if (state.downSince == null) {
        state.downSince = now()
        logE "IntelliCenter connection lost (${why}). Retrying every 2 minutes."
    } else {
        logF "still offline: ${why}"
    }
}

def reconnectIfNeeded() {
    if (!state.connected) {
        logF "reconnecting"
        connect()
    }
}

// Every minute: reconnect a silent socket, and restart the poll chain if it stopped.
def healthCheck() {
    if (!state.connected) return
    Map m = mem()
    if (m.lastRx == null) { m.lastRx = now(); return }   // memory cleared by a driver save
    long quietMs = now() - (m.lastRx as long)
    if (quietMs > Math.max(3 * pollSec(), 180) * 1000L) {
        markDown("no data for ${(quietMs / 1000) as int}s")
        connect()
        return
    }
    if (now() - ((m.lastPoll ?: 0L) as long) > 2 * pollSec() * 1000L) {
        logF "poll restarted"
        runIn(1, "pollState")
    }
}

private int pollSec() {
    Integer s = toInt(pollInterval)
    return (s && s >= 10) ? s : 30
}

private void setStatus(String v) {
    if (device.currentValue("connectionStatus") != v) sendEvent(name: "connectionStatus", value: v)
}

// ============================================================
// Poll
// ============================================================

def pollState() {
    if (!state.connected) return
    mem().lastPoll = now()
    logF "poll"
    sendCommand(query("CIRCUIT", ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "FEATR"]))
    runIn(2, "pollBodies")
    runIn(pollSec(), "pollState")
}

def pollBodies() {
    if (!state.connected) return
    sendCommand(query("BODY", ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "TEMP", "LOTMP", "HITMP", "HTMODE", "HTSRC"]))
    runIn(2, "pollPumps")
}

def pollPumps() {
    if (!state.connected) return
    sendCommand(query("PUMP", ["OBJTYP", "SNAME", "STATUS", "RPM", "GPM", "WATTS"]))
    runIn(2, "pollSensors")
}

def pollSensors() {
    if (!state.connected) return
    sendCommand(query("SENSE", ["OBJTYP", "SUBTYP", "SNAME", "SOURCE"]))
    if (state.catalog?.any { k, v -> v.k == "chem" }) runIn(2, "pollChem")
}

def pollChem() {
    if (!state.connected) return
    sendCommand(query("CHEM", ["OBJTYP", "SUBTYP", "SNAME", "SALT", "PRIM", "SEC", "SUPER"]))
}

private Map query(String type, List keys, String objnam = "ALL") {
    Map q = [command: "GetParamList", objectList: [[objnam: objnam, keys: keys]]]
    if (type) q.condition = "OBJTYP=${type}"
    return q
}

// ============================================================
// Equipment scan
// ============================================================

def requestEquipment() {
    logF "reading equipment"
    sendCommand(query("CIRCUIT", ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "FEATR"]))
    runIn(2, "requestBodies")
}

def requestBodies() {
    sendCommand(query("BODY", ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "TEMP", "LOTMP", "HITMP", "HTMODE", "HTSRC"]))
    runIn(2, "requestHeaters")
}

def requestHeaters() {
    sendCommand(query("HEATER", ["OBJTYP", "SNAME"]))
    runIn(2, "requestPumps")
}

def requestPumps() {
    sendCommand(query("PUMP", ["OBJTYP", "SNAME", "STATUS", "RPM", "GPM", "WATTS"]))
    runIn(2, "requestSensors")
}

def requestSensors() {
    sendCommand(query("SENSE", ["OBJTYP", "SUBTYP", "SNAME", "SOURCE"]))
    runIn(2, "requestChem")
}

def requestChem() {
    sendCommand(query("CHEM", ["OBJTYP", "SUBTYP", "SNAME", "SALT", "PRIM", "SEC", "SUPER"]))
    runIn(3, "subscribeToUpdates")
}

def subscribeToUpdates() {
    sendCommand([command: "RequestParamList", condition: "OBJTYP=CIRCUIT,BODY,PUMP,SENSE,CHEM",
                 objectList: [[objnam: "ALL", keys: ["STATUS", "TEMP", "RPM", "WATTS", "GPM", "SALT", "SOURCE",
                                                     "LOTMP", "HITMP", "HTMODE", "HTSRC", "PRIM", "SEC", "SUPER"]]]])
    if (state.seeding) {
        state.remove("seeding")
        logF "first equipment scan after update finished"
    }
    mem().lastPoll = now()
    runIn(pollSec(), "pollState")
    logN "equipment read, live updates on, backup poll every ${pollSec()}s"
}

// ============================================================
// Incoming data
// ============================================================

def parse(String message) {
    mem().lastRx = now()
    message = message?.trim()
    if (!message?.startsWith("{")) { logF "non-JSON message: ${message?.take(100)}"; return }
    logF "RX: ${message}"
    def json
    try {
        json = new groovy.json.JsonSlurper().parseText(message)
    } catch (e) {
        logE "unreadable message from IntelliCenter: ${message.take(100)}"
        return
    }
    switch (json?.command) {
        case "SendParamList":
        case "NotifyList":
            json.objectList?.each { obj -> apply(obj.objnam as String, obj.params as Map) }
            break
        case "WriteParamList":
            // Some IC2 firmware sends a single object instead of a list.
            json.objectList?.each { obj ->
                def changes = obj.changes
                if (changes == null) return
                (changes instanceof List ? changes : [changes]).each { c -> apply(obj.objnam as String, c?.params as Map) }
            }
            break
        case "SetParamList":
            logF "command accepted (response ${json.response})"
            break
        case "Error":
            logE "IntelliCenter error ${json.response}: ${json.description}"
            break
        default:
            logF "unhandled message: ${json?.command}"
    }
}

private void apply(String objnam, Map params) {
    if (!objnam || !params) return
    Map p = params.findAll { k, v -> k && !(v instanceof String && v == k) }   // drop unknown-key echoes
    if (!p) return
    String type = p.OBJTYP ?: state.catalog?.get(objnam)?.t
    try {
        switch (type) {
            case "CIRCUIT": processCircuit(objnam, p); break
            case "BODY":    processBody(objnam, p);    break
            case "PUMP":    processPump(objnam, p);    break
            case "SENSE":   processSensor(objnam, p);  break
            case "CHEM":    processChem(objnam, p);    break
            case "HEATER":  processHeater(objnam, p);  break
        }
    } catch (e) {
        logE "could not process ${objnam}: ${e.message}"
    }
}

// ============================================================
// Equipment
// ============================================================

private void processCircuit(String objnam, Map p) {
    if (!(objnam ==~ /C\d+|GRP\d+|CIRCGRP\d+|FTR\d+/)) return   // internal (X00xx, _xxxx, lowercase)
    String sub = p.SUBTYP ?: cat(objnam)?.s ?: ""
    if (sub in ["POOL", "SPA"]) return                            // body circuits; the Body device covers them
    String label = ((p.SNAME ?: cat(objnam)?.n ?: objnam) as String).trim()
    catalogPut(objnam, "circuit", label, sub, "CIRCUIT")
    if (p.STATUS == null) return
    def child = childFor("Generic Component Switch", "circuit", objnam, label, false)
    if (!child) return
    String sw = p.STATUS == "ON" ? "on" : "off"
    if (sendIf(child, "switch", sw)) logN "${label} turned ${sw}"
}

private void processBody(String objnam, Map p) {
    String sub = p.SUBTYP ?: cat(objnam)?.s ?: ""
    String label = ((p.SNAME ?: cat(objnam)?.n ?: objnam) as String).trim()
    catalogPut(objnam, "body", label, sub, "BODY")
    def body = childFor("Pentair IntelliCenter Body", "body", objnam, label, false)
    if (!body) return
    String u = tempUnit()
    if (p.STATUS != null) {
        boolean on = p.STATUS == "ON"
        if (sendIf(body, "switch", on ? "on" : "off")) logN "${label} turned ${on ? 'on' : 'off'}"
        sendIf(body, "bodyStatus", on ? "On" : "Off")
    }
    Integer temp = toInt(p.TEMP)
    if (temp != null) sendIf(body, "temperature", temp, u)
    Integer lo = toInt(p.LOTMP)
    if (lo != null && sendIf(body, "heatingSetpoint", lo, u)) logN "${label} set point ${lo}${u}"
    Integer hi = toInt(p.HITMP)
    if (hi != null) sendIf(body, "maxSetTemp", hi, u)
    if (p.HTMODE != null) {
        String mode = HTMODE_NAMES[p.HTMODE.toString()] ?: p.HTMODE.toString()
        sendIf(body, "heaterMode", mode)
        if (mode == "Off" && p.HTSRC == null) sendIf(body, "heatSource", "Off")
    }
    if (p.HTSRC != null) {
        String src = HTSRC_NAMES[p.HTSRC] ?: state.heaterNames?.get(p.HTSRC) ?: p.HTSRC
        if (sendIf(body, "heatSource", src)) logN "${label} heat source ${src}"
        Map ids = (state.srcIds ?: [:]) as Map
        if (ids[src] != p.HTSRC) { ids[src] = p.HTSRC; state.srcIds = ids }
    }
    // Pool and spa share the pump, so the pump shows the running body's water temperature.
    String status = p.STATUS ?: (body.currentValue("switch") == "on" ? "ON" : "OFF")
    if (temp != null && status == "ON") {
        getChildDevices().findAll { it.deviceNetworkId.startsWith(PREFIX.pump) }.each { pump ->
            if (sendIf(pump, "temperature", temp, u)) pump.debounceTile()
        }
    }
    logF "body ${label}: status=${p.STATUS} temp=${p.TEMP} setpoint=${p.LOTMP} mode=${p.HTMODE} source=${p.HTSRC}"
    body.debounceTile()
}

private void processPump(String objnam, Map p) {
    String label = ((p.SNAME ?: cat(objnam)?.n ?: objnam) as String).trim()
    catalogPut(objnam, "pump", label, "", "PUMP")
    def pump = childFor("Pentair IntelliCenter Pump", "pump", objnam, label, false)
    if (!pump) return
    Integer rpm = toInt(p.RPM), watts = toInt(p.WATTS), gpm = toInt(p.GPM)
    boolean changed = false
    if (pump.currentValue("temperature") == null) {   // new pump: bodies were read before it existed
        def running = getChildDevices().find { it.deviceNetworkId.startsWith(PREFIX.body) && it.currentValue("switch") == "on" }
        Integer t = toInt(running?.currentValue("temperature"))
        if (t != null) changed |= sendIf(pump, "temperature", t, tempUnit())
    }
    if (rpm != null) changed |= sendIf(pump, "rpm", rpm, "RPM")
    if (watts != null) { changed |= sendIf(pump, "watts", watts, "W"); sendIf(pump, "power", watts, "W") }
    if (gpm != null && gpm > 0) changed |= sendIf(pump, "gpm", gpm, "GPM")
    logF "pump ${label}: rpm=${p.RPM} watts=${p.WATTS} gpm=${p.GPM}"
    if (changed) pump.debounceTile()
}

private void processSensor(String objnam, Map p) {
    String sub = p.SUBTYP ?: cat(objnam)?.s ?: ""
    if (sub in ["POOL", "SPA"]) return   // water temperature is on the Body device
    String label = ((p.SNAME ?: cat(objnam)?.n ?: objnam) as String).trim()
    catalogPut(objnam, "sensor", label, sub, "SENSE")
    Integer t = toInt(p.SOURCE)
    if (t == null) return
    def c = childFor("Generic Component Temperature Sensor", "sensor", objnam, label, true)
    if (c) sendIf(c, "temperature", t, tempUnit())
}

private void processChem(String objnam, Map p) {
    String sub = p.SUBTYP ?: cat(objnam)?.s ?: ""
    String label = ((p.SNAME ?: cat(objnam)?.n ?: "Chlorinator") as String).trim()
    catalogPut(objnam, "chem", label, sub, "CHEM")
    def c = childFor("Pentair IntelliCenter Chlorinator", "chem", objnam, label, false)
    if (!c) return
    Integer salt = toInt(p.SALT), prim = toInt(p.PRIM), sec = toInt(p.SEC)
    if (salt != null) sendIf(c, "saltLevel", salt, "ppm")
    if (prim != null) sendIf(c, "poolOutput", prim, "%")
    if (sec != null) sendIf(c, "spaOutput", sec, "%")
    if (p.SUPER != null) sendIf(c, "superChlorinate", p.SUPER == "ON" ? "on" : "off")
    logF "chlorinator ${label}: salt=${p.SALT} pool=${p.PRIM}% spa=${p.SEC}% super=${p.SUPER}"
}

private void processHeater(String objnam, Map p) {
    String label = p.SNAME
    if (!label) return
    Map names = (state.heaterNames ?: [:]) as Map
    if (names[objnam] != label) { names[objnam] = label; state.heaterNames = names }
    logF "heater ${objnam} = ${label}"
}

// ============================================================
// Devices, catalog and exclusions
// ============================================================

private Map cat(String objnam) { state.catalog?.get(objnam) as Map }

private void catalogPut(String objnam, String kind, String name, String sub, String type) {
    Map c = (state.catalog ?: [:]) as Map
    Map e = [k: kind, n: name, s: sub ?: "", t: type]
    if (c[objnam] != e) { c[objnam] = e; state.catalog = c }
}

// Existing device, a new one, or null when excluded (by the user, or deleted from the Devices page).
private childFor(String driver, String kind, String objnam, String label, boolean isComponent) {
    migrate()   // first, so devices found below don't look like an already-migrated install
    String dni = PREFIX[kind] + objnam
    def child = getChildDevice(dni)
    if (child) { addKnown(objnam); return child }
    if ((state.excluded ?: []).contains(objnam)) return null
    if (state.seeding || (state.known ?: []).contains(objnam)) {
        exclude(objnam)
        logN "${label} was deleted from the Devices page, so it won't be recreated. Turn it back on in the app's Equipment page."
        return null
    }
    Map m = mem()
    Set failed = (m.failed ?: (m.failed = java.util.concurrent.ConcurrentHashMap.newKeySet())) as Set
    if (failed.contains(dni)) return null
    try {
        String ns = driver.startsWith("Pentair") ? "intellicenter" : "hubitat"
        child = addChildDevice(ns, driver, dni, [label: label, isComponent: isComponent])
        addKnown(objnam)
        logN "created ${label}"
        try { child.setLogLevel(state.logLevel ?: "Errors Only") } catch (e) { }
    } catch (e) {
        failed.add(dni)
        String why = "${e}".contains("UnknownDeviceType") ? "the ${driver} driver isn't installed (update with HPM)" : e.message
        logE "could not create ${label}: ${why}"
    }
    return child
}

private void addKnown(String objnam) {
    List k = (state.known ?: []) as List
    if (!k.contains(objnam)) state.known = k + objnam
}

private void exclude(String objnam) {
    List x = (state.excluded ?: []) as List
    if (!x.contains(objnam)) state.excluded = x + objnam
    state.known = ((state.known ?: []) as List) - objnam
}

// For the app's Equipment page.
Map getCatalog() { (state.catalog ?: [:]) as Map }
List getExcluded() { (state.excluded ?: []) as List }

// From the app's Equipment page: removes newly excluded devices, re-reads equipment to create re-included ones.
void setExcluded(List list) {
    List old = (state.excluded ?: []) as List
    List want = (list ?: []).collect { it.toString() }.unique()
    state.excluded = want
    state.known = ((state.known ?: []) as List) - (old - want) - (want - old)
    (want - old).each { objnam ->
        def child = getChildDevices().find { objnamFromDni(it.deviceNetworkId) == objnam }
        if (child) {
            String label = child.displayName
            try { deleteChildDevice(child.deviceNetworkId); logN "removed ${label} (turned off in Equipment)" }
            catch (e) { logE "could not remove ${label}: ${e.message}" }
        }
    }
    if ((old - want) && state.connected) runIn(1, "requestEquipment")
}

// ============================================================
// Commands (return true when sent)
// ============================================================

boolean setBodyStatus(String childDni, String status) {
    setParam(childDni, [STATUS: status], "status ${status}")
}

boolean setBodySetPoint(String childDni, Integer temp) {
    setParam(childDni, [LOTMP: temp.toString()], "set point ${temp}")
}

boolean setBodyHeatSource(String childDni, String source) {
    String objnam = objnamFromDni(childDni)
    String id = source == "Off" ? "00000" : (state.srcIds?.get(source) ?: state.heaterNames?.find { k, v -> v?.equalsIgnoreCase(source) }?.key ?: HTSRC_IDS[source])
    if (!id) { logE "unknown heat source '${source}' for ${objnam}"; return false }
    setParam(childDni, [HTSRC: id], "heat source ${source} (${id})")
}

boolean setPumpSpeed(String childDni, Integer rpm) {
    setParam(childDni, [RPM: rpm.toString()], "speed ${rpm} RPM")
}

boolean circuitOn(String childDni) { setCircuit(childDni, "ON") }
boolean circuitOff(String childDni) { setCircuit(childDni, "OFF") }

private boolean setCircuit(String childDni, String status) {
    if (childDni?.startsWith(PREFIX.chem)) {
        logE "turning the chlorinator on or off from Hubitat isn't supported"
        return false
    }
    boolean ok = setParam(childDni, [STATUS: status], status)
    if (ok) getChildDevice(childDni)?.sendEvent(name: "switch", value: status == "ON" ? "on" : "off")
    return ok
}

private boolean setParam(String childDni, Map params, String what) {
    String objnam = objnamFromDni(childDni)
    if (!objnam) { logE "no equipment for ${childDni}"; return false }
    String name = getChildDevice(childDni)?.displayName ?: objnam
    boolean ok = sendCommand([command: "SetParamList", objectList: [[objnam: objnam, params: params]]])
    if (ok) logN "${name}: ${what}"
    return ok
}

def componentOn(child)  { circuitOn(child.deviceNetworkId) }
def componentOff(child) { circuitOff(child.deviceNetworkId) }

def componentRefresh(child) {
    String dni = child?.deviceNetworkId
    String objnam = dni ? objnamFromDni(dni) : null
    if (!objnam) return
    Map keys = [(PREFIX.sensor): ["SENSE", ["OBJTYP", "SUBTYP", "SNAME", "SOURCE"]],
                (PREFIX.body): ["BODY", ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "TEMP", "LOTMP", "HITMP", "HTMODE", "HTSRC"]],
                (PREFIX.pump): ["PUMP", ["OBJTYP", "SNAME", "STATUS", "RPM", "GPM", "WATTS"]],
                (PREFIX.circuit): ["CIRCUIT", ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "FEATR"]],
                (PREFIX.chem): ["CHEM", ["OBJTYP", "SUBTYP", "SNAME", "SALT", "PRIM", "SEC", "SUPER"]]]
    def k = keys.find { pre, v -> dni.startsWith(pre) }?.value
    if (k) sendCommand(query(k[0] as String, k[1] as List, objnam))
}

private boolean sendCommand(Map payload) {
    if (!state.connected) {
        logE "not connected to IntelliCenter, command not sent"
        return false
    }
    payload.messageID = UUID.randomUUID().toString()
    String json = groovy.json.JsonOutput.toJson(payload)
    logF "TX: ${json}"
    try {
        interfaces.webSocket.sendMessage(json)
        return true
    } catch (e) {
        markDown("send failed: ${e.message}")
        return false
    }
}

// ============================================================
// Helpers
// ============================================================

String objnamFromDni(String dni) {
    String pre = PREFIX.values().find { dni?.startsWith(it) }
    if (pre) return dni.substring(pre.length())
    int i = dni?.lastIndexOf("-") ?: -1
    return i > 0 ? dni.substring(i + 1) : null
}

private Integer toInt(v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString().trim()).setScale(0, java.math.RoundingMode.HALF_UP).intValue() } catch (e) { return null }
}

private String tempUnit() { "°${location?.temperatureScale ?: 'F'}" }

// Sends only real changes; returns true when the value changed.
private boolean sendIf(dev, String name, value, String unit = null) {
    if (dev.currentValue(name)?.toString() == value?.toString()) return false
    Map e = [name: name, value: value]
    if (unit) e.unit = unit
    dev.sendEvent(e)
    return true
}

// ============================================================
// Logging (level comes from the app)
// ============================================================

void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
    getChildDevices().each { try { it.setLogLevel(state.logLevel) } catch (e) { } }
}

// Body, Pump and Chlorinator log through here up to the app.
void childLog(String level, String source, String msg) {
    try { parent.childLog(level, source, msg) } catch (e) { localLog(level, "${source}: ${msg}") }
}

private int lvl() { LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only") }
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
