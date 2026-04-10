// ============================================================
// Pentair IntelliCenter Bridge Driver
// Version: 1.5.9
// All files in this integration share this version number.
// ============================================================

import groovy.transform.Field

@Field static final String VERSION     = "1.5.9"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"

metadata {
    definition(
        name: "Pentair IntelliCenter Bridge",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Bridge driver for Pentair IntelliCenter TCP connection",
        version: "1.5.9"
    ) {
        capability "Initialize"
        capability "Refresh"

        attribute "connectionStatus", "string"

        command "connect"
        command "disconnect"
        command "circuitOn",  [[name: "DNI*", type: "STRING", description: "Child device DNI"]]
        command "circuitOff", [[name: "DNI*", type: "STRING", description: "Child device DNI"]]
    }

    preferences {
        input "ipAddress",    "text",   title: "IntelliCenter IP Address",                                                      required: true
        input "portNumber",   "number", title: "Port (IntelliCenter 1 = 6680 / IC2 try 6681)",                                  defaultValue: 6680
        input "pollInterval", "number", title: "State Poll Interval in seconds (default: 30) — syncs HE with changes made in the Pentair app or panel. Increase to reduce traffic.", defaultValue: 30
        input "debugMode",    "bool",   title: "Debug Logging (auto-disables after 60 min)",                                    defaultValue: false
        input "endpointBase", "text",   title: "App Endpoint Base (set automatically by app)",                                  required: false
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

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
    log.info "IntelliCenter Bridge installed"
    initialize()
}

def updated() {
    log.info "IntelliCenter Bridge updated"
    unschedule(disableDebugLogging)
    initialize()
}

def initialize() {
    state.msgBuffer    = ""
    state.objectMap    = [:]
    state.pendingCmds  = [:]
    state.connected    = false
    state.lastPollTime = now()

    unschedule()
    schedule("0 0/2 * * * ?", reconnectIfNeeded)
    schedule("0 0/1 * * * ?", pollWatchdog)

    if (debugMode) {
        log.info "Debug logging enabled — will auto-disable in 60 minutes"
        runIn(3600, disableDebugLogging)
    }

    connect()
}

def refresh() {
    if (state.connected) {
        requestEquipment()
    } else {
        connect()
    }
}

// ============================================================
// ===================== CONNECTION ==========================
// ============================================================
def connect() {
    if (!ipAddress) {
        log.warn "No IP address configured — open the Pentair IntelliCenter app and click Done"
        sendEvent(name: "connectionStatus", value: "Not Configured — open app and click Done")
        return
    }

    try {
        def uri = "ws://${ipAddress}:${portNumber ?: 6680}"
        if (debugMode) log.debug "Connecting via WebSocket to ${uri}"
        interfaces.webSocket.connect(uri)
    } catch (e) {
        log.error "Connection failed: ${e.message}"
        state.connected = false
        sendEvent(name: "connectionStatus", value: "Disconnected")
    }
}

def disconnect() {
    unschedule(pollState)
    try { interfaces.webSocket.close() } catch (e) { }
    state.connected = false
    sendEvent(name: "connectionStatus", value: "Disconnected")
    log.info "IntelliCenter disconnected"
}

def webSocketStatus(String message) {
    if (debugMode) log.debug "WebSocket status: ${message}"
    if (message.contains("open")) {
        log.info "WebSocket open — IntelliCenter connected"
        state.connected = true
        state.msgBuffer = ""
        sendEvent(name: "connectionStatus", value: "Connected")
        runIn(1, requestEquipment)
    } else if (message.contains("failure") || message.contains("error") || message.contains("clos")) {
        log.warn "WebSocket disconnected: ${message}"
        state.connected = false
        sendEvent(name: "connectionStatus", value: "Disconnected")
        unschedule(pollState)
    }
}

def reconnectIfNeeded() {
    if (!state.connected) {
        log.info "Watchdog: reconnecting to IntelliCenter"
        connect()
    }
}

def disableDebugLogging() {
    log.info "IntelliCenter Bridge: auto-disabling debug logging after 60 minutes"
    device.updateSetting("debugMode", [value: false, type: "bool"])
}

// ============================================================
// ===================== POLL WATCHDOG =======================
// ============================================================
def pollWatchdog() {
    if (!state.connected) return
    def interval = (pollInterval ?: 30).toInteger()
    def elapsed  = (now() - (state.lastPollTime ?: now())) / 1000

    if (elapsed > (interval * 2)) {
        log.warn "Poll watchdog: last poll was ${elapsed.toInteger()}s ago — restarting poll chain"
        runIn(1, pollState)
    }
}

// ============================================================
// ===================== POLL STATE ==========================
// ============================================================
def pollState() {
    if (!state.connected) return
    try {
        if (debugMode) log.debug "Polling state — circuits"
        state.lastPollTime = now()
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=CIRCUIT",
            objectList: [[objnam: "ALL", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "FEATR"]]]
        ])
        runIn(2, pollBodies)
    } catch (e) {
        log.error "pollState error: ${e.message}"
    }
    runIn((pollInterval ?: 30).toInteger(), pollState)
}

def pollBodies() {
    if (!state.connected) return
    try {
        if (debugMode) log.debug "Polling state — bodies"
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=BODY",
            objectList: [[objnam: "ALL", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "TEMP", "LOTMP", "HITMP", "HTMODE", "HTSRC", "HTRLST"]]]
        ])
        runIn(2, pollPumps)
    } catch (e) {
        log.error "pollBodies error: ${e.message}"
    }
}

def pollPumps() {
    if (!state.connected) return
    try {
        if (debugMode) log.debug "Polling state — pumps"
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=PUMP",
            objectList: [[objnam: "ALL", keys: ["OBJTYP", "SNAME", "STATUS", "RPM", "GPM", "WATTS"]]]
        ])
        runIn(2, pollSensors)
    } catch (e) {
        log.error "pollPumps error: ${e.message}"
    }
}

def pollSensors() {
    if (!state.connected) return
    try {
        if (debugMode) log.debug "Polling state — sensors"
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=SENSE",
            objectList: [[objnam: "ALL", keys: ["OBJTYP", "SUBTYP", "SNAME", "SOURCE"]]]
        ])
    } catch (e) {
        log.error "pollSensors error: ${e.message}"
    }
}

// ============================================================
// ===================== INCOMING DATA =======================
// ============================================================
def parse(String message) {
    message = message.trim()
    if (message.startsWith("{")) {
        processMessage(message)
    } else {
        if (debugMode) log.debug "Non-JSON message: ${message}"
    }
}

def processMessage(String raw) {
    if (debugMode) log.debug "RX: ${raw}"

    def json
    try {
        json = new groovy.json.JsonSlurper().parseText(raw)
    } catch (e) {
        log.warn "JSON parse error on: ${raw?.take(100)}"
        return
    }

    switch (json?.command) {
        case "SendParamList":    handleParamList(json);      break
        case "WriteParamList":   handleWriteParamList(json); break
        case "NotifyList":       handleNotifyList(json);     break
        case "SetParamList":
            if (debugMode) log.debug "SetParamList ack: response=${json?.response}"
            break
        case "Error":
            log.warn "Controller error ${json.response}: ${json.description}"
            break
        default:
            if (debugMode) log.debug "Unhandled command: ${json?.command}"
    }
}

// ============================================================
// ===================== REQUEST EQUIPMENT ===================
// ============================================================
def requestEquipment() {
    if (debugMode) log.debug "Requesting equipment — circuits"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=CIRCUIT",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "FEATR"]]]
    ])
    runIn(2, "requestGroups")
}

def requestGroups() {
    if (debugMode) log.debug "Requesting equipment — circuit groups"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=CIRCGRP",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS"]]]
    ])
    runIn(2, "requestBodies")
}

def requestBodies() {
    if (debugMode) log.debug "Requesting equipment — bodies"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=BODY",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "TEMP", "LOTMP", "HITMP", "HTMODE", "HTSRC", "HTRLST"]]]
    ])
    runIn(2, "requestHeaters")
}

def requestHeaters() {
    if (debugMode) log.debug "Requesting equipment — heaters"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=HEATER",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SNAME"]]]
    ])
    runIn(2, "requestPumps")
}

def requestPumps() {
    if (debugMode) log.debug "Requesting equipment — pumps"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=PUMP",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SNAME", "STATUS", "RPM", "GPM", "WATTS"]]]
    ])
    runIn(2, "requestSensors")
}

def requestSensors() {
    if (debugMode) log.debug "Requesting equipment — sensors"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=SENSE",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SUBTYP", "SNAME", "SOURCE"]]]
    ])
    runIn(2, "requestChem")
}

def requestChem() {
    if (debugMode) log.debug "Requesting equipment — chlorinator"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=CHEM",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SNAME", "STATUS", "SALT", "LOTMP"]]]
    ])
    runIn(3, "subscribeToUpdates")
}

def subscribeToUpdates() {
    if (debugMode) log.debug "Subscribing to live updates"
    sendCommand([
        command: "RequestParamList",
        condition: "OBJTYP=CIRCUIT,BODY,PUMP,SENSE,CHEM",
        objectList: [[objnam: "ALL", keys: ["STATUS", "TEMP", "RPM", "WATTS", "GPM", "SALT", "SOURCE", "LOTMP", "HITMP", "HTMODE", "HTSRC"]]]
    ])
    def interval = (pollInterval ?: 30).toInteger()
    runIn(interval, pollState)
    log.info "State polling scheduled — every ${interval} seconds"
}

// ============================================================
// ===================== PROCESS RESPONSES ===================
// ============================================================
def handleParamList(json) {
    json?.objectList?.each { obj ->
        def name   = obj.objnam
        def params = obj.params
        if (!name || !params) return
        if (!state.objectMap) state.objectMap = [:]
        if (!state.objectMap[name]) state.objectMap[name] = [:]
        params.each { k, v -> state.objectMap[name][k] = v }
        state.objectMap[name].objnam = name
        routeUpdate(name, params)
    }
}

def handleNotifyList(json) {
    json?.objectList?.each { obj ->
        def name   = obj.objnam
        def params = obj.params
        if (!name || !params) return
        if (!state.objectMap) state.objectMap = [:]
        if (!state.objectMap[name]) state.objectMap[name] = [:]
        params.each { k, v -> state.objectMap[name][k] = v }
        routeUpdate(name, params)
    }
}

// WriteParamList — full state push from controller after a SetParamList.
// Guard changes as a List — some IC2 firmware versions send a single object.
def handleWriteParamList(json) {
    json?.objectList?.each { obj ->
        def name       = obj.objnam
        def rawChanges = obj.changes
        if (!name || rawChanges == null) return
        def changeList = rawChanges instanceof List ? rawChanges : [rawChanges]
        changeList.each { change ->
            def params = change.params
            if (!params) return
            if (!state.objectMap) state.objectMap = [:]
            if (!state.objectMap[name]) state.objectMap[name] = [:]
            params.each { k, v -> state.objectMap[name][k] = v }
            routeUpdate(name, params)
        }
    }
}

def routeUpdate(String objnam, Map params) {
    def objType = params.OBJTYP ?: state.objectMap?.get(objnam)?.OBJTYP
    if (!objType) return
    switch (objType) {
        case "CIRCUIT":  processCircuit(objnam, params); break
        case "CIRCGRP":  processCircuit(objnam, params); break
        case "BODY":     processBody(objnam, params);    break
        case "PUMP":     processPump(objnam, params);    break
        case "SENSE":    processSensor(objnam, params);  break
        case "CHEM":     processChem(objnam, params);    break
        case "HEATER":   processHeater(objnam, params);  break
    }
}

// ============================================================
// ===================== DEVICE UPDATES ======================
// ============================================================
def processCircuit(String objnam, Map params) {
    def subtyp = params.SUBTYP ?: state.objectMap?.get(objnam)?.SUBTYP ?: ""

    def isUserCircuit = objnam.matches("C\\d+")
    def isGroup       = objnam.matches("GRP\\d+") || objnam.matches("CIRCGRP\\d+")
    def isFeature     = objnam.matches("FTR\\d+")
    if (!isUserCircuit && !isGroup && !isFeature) {
        if (debugMode) log.debug "Skipping internal circuit: ${objnam}"
        return
    }
    if (subtyp == "POOL" || subtyp == "SPA") {
        if (debugMode) log.debug "Skipping body circuit (subtyp): ${objnam} (${subtyp})"
        return
    }
    if (state.bodyObjnams?.contains(objnam)) {
        if (debugMode) log.debug "Skipping body circuit (bodyObjnams): ${objnam}"
        return
    }
    if (getChildDevice("intellicenter-body-${objnam}")) {
        if (debugMode) log.debug "Skipping body circuit (body device exists): ${objnam}"
        if (!state.bodyObjnams) state.bodyObjnams = []
        if (!state.bodyObjnams.contains(objnam)) state.bodyObjnams << objnam
        return
    }

    def label  = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: objnam
    def status = params.STATUS
    if (status == null) return

    def dni   = "intellicenter-circuit-${objnam}"
    def child = getOrCreateChild("Generic Component Switch", dni, label, false)
    if (!child) return

    child.sendEvent(name: "switch", value: (status == "ON" ? "on" : "off"))
    if (debugMode) log.debug "Circuit [${label}] (${subtyp}): ${status}"
}

def processBody(String objnam, Map params) {
    def subtyp = params.SUBTYP ?: state.objectMap?.get(objnam)?.SUBTYP ?: ""
    def label  = params.SNAME  ?: state.objectMap?.get(objnam)?.SNAME  ?: objnam
    def status = params.STATUS
    def temp   = params.TEMP
    def lotmp  = params.LOTMP
    def hitmp  = params.HITMP
    def htmode = params.HTMODE
    def htsrc  = params.HTSRC

    if (!state.bodyObjnams) state.bodyObjnams = []
    if (!state.bodyObjnams.contains(objnam)) state.bodyObjnams << objnam

    def dni  = "intellicenter-body-${objnam}"
    def body = getOrCreateChild("Pentair IntelliCenter Body", dni, label, false)
    if (!body) {
        log.warn "processBody: could not get/create body device ${label} (${dni})"
        return
    }

    if (endpointBase && body.getSetting("endpointBase") != endpointBase) {
        body.updateSetting("endpointBase", [value: endpointBase, type: "text"])
    }

    if (status != null) {
        body.sendEvent(name: "switch",     value: (status == "ON" ? "on" : "off"))
        body.sendEvent(name: "bodyStatus", value: (status == "ON" ? "On" : "Off"))
    }
    if (temp  != null) body.sendEvent(name: "temperature",     value: temp.toInteger(),  unit: "°F")
    if (lotmp != null) body.sendEvent(name: "heatingSetpoint", value: lotmp.toInteger(), unit: "°F")
    if (hitmp != null) body.sendEvent(name: "maxSetTemp",      value: hitmp.toInteger(), unit: "°F")

    if (htmode != null) {
        def modeMap = ["0":"Off","1":"Heater","2":"Solar Only","3":"Solar Preferred",
                       "4":"Heat Pump","5":"Heat Pump Preferred","OFF":"Off"]
        def modeFriendly = modeMap[htmode.toString()] ?: htmode
        body.sendEvent(name: "heaterMode", value: modeFriendly)
        if (modeFriendly == "Off" && htsrc == null) {
            body.sendEvent(name: "heatSource", value: "Off")
        }
    }

    if (htsrc != null) {
        def staticSrcMap = ["00000":"Off","H0001":"Heater","S0001":"Solar Only",
                            "H0002":"Solar Preferred","H0003":"Heat Pump","H0004":"Heat Pump Preferred"]
        def friendlyName = staticSrcMap[htsrc] ?: htsrc
        if (!state.htsrcIds) state.htsrcIds = [:]
        if (!state.htsrcIds[objnam]) state.htsrcIds[objnam] = [:]
        state.htsrcIds[objnam][friendlyName] = htsrc
        body.sendEvent(name: "heatSource", value: friendlyName)
    }

    if (debugMode) log.debug "Body [${label}] (${subtyp}): status=${status} temp=${temp} setpt=${lotmp} htmode=${htmode} htsrc=${htsrc}"

    if (temp != null) {
        getChildDevices()
            .findAll { it.deviceNetworkId.startsWith("intellicenter-pump-") }
            .each { pump ->
                pump.sendEvent(name: "temperature", value: temp.toInteger(), unit: "°F")
                pump.debounceTile()
            }
    }

    body.debounceTile()
}

def processPump(String objnam, Map params) {
    def label = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: objnam
    def rpm   = params.RPM
    def watts = (params.WATTS && params.WATTS != "WATTS") ? params.WATTS : null
    def gpm   = params.GPM

    def pumpDni = "intellicenter-pump-${objnam}"
    def pump    = getOrCreateChild("Pentair IntelliCenter Pump", pumpDni, label, false)
    if (pump) {
        if (rpm   != null)                        pump.sendEvent(name: "rpm",   value: rpm.toInteger(),   unit: "RPM")
        if (watts != null)                        pump.sendEvent(name: "watts", value: watts.toInteger(), unit: "W")
        if (gpm   != null && gpm.toInteger() > 0) pump.sendEvent(name: "gpm",   value: gpm.toInteger(),   unit: "GPM")
    }
    if (debugMode) log.debug "Pump [${label}]: rpm=${rpm} watts=${watts} gpm=${gpm}"
    if (pump) pump.debounceTile()
}

def processSensor(String objnam, Map params) {
    def subtyp = params.SUBTYP ?: state.objectMap?.get(objnam)?.SUBTYP ?: ""
    if (subtyp == "POOL" || subtyp == "SPA") { return }

    def label  = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: objnam
    def source = params.SOURCE
    if (source == null) return

    def c = getOrCreateChild("Generic Component Temperature Sensor", "intellicenter-sensor-${objnam}", label, true)
    c?.sendEvent(name: "temperature", value: source.toInteger(), unit: "°F")
    if (debugMode) log.debug "Sensor [${label}]: ${source}°F"
}

def processChem(String objnam, Map params) {
    def label  = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: "Chlorinator"
    def status = params.STATUS
    def salt   = params.SALT

    def c = getOrCreateChild("Generic Component Switch", "intellicenter-chem-${objnam}", label, true)
    if (!c) return

    if (status != null) c.sendEvent(name: "switch",    value: (status == "ON" ? "on" : "off"))
    if (salt   != null) c.sendEvent(name: "saltLevel", value: salt.toInteger(), unit: "PPM")
    if (debugMode) log.debug "Chem [${label}]: status=${status} salt=${salt}"
}

def processHeater(String objnam, Map params) {
    def label = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME
    if (!label) return
    if (!state.heaterNames) state.heaterNames = [:]
    state.heaterNames[objnam] = label
    if (debugMode) log.debug "Heater registered: ${objnam} = '${label}'"
}

// ============================================================
// ===================== BODY COMMANDS =======================
// ============================================================
def setBodyStatus(String childDni, String status) {
    def objnam = objnamFromDni(childDni)
    if (!objnam) { log.warn "setBodyStatus: no objnam for DNI ${childDni}"; return }
    if (debugMode) log.debug "setBodyStatus: ${objnam} STATUS=${status}"
    sendCommand([
        command: "SetParamList",
        objectList: [[objnam: objnam, params: [STATUS: status]]]
    ])
}

def setBodySetPoint(String childDni, Integer temp) {
    def objnam = objnamFromDni(childDni)
    if (!objnam) { log.warn "setBodySetPoint: no objnam for DNI ${childDni}"; return }
    if (debugMode) log.debug "setBodySetPoint: ${objnam} LOTMP=${temp}"
    sendCommand([
        command: "SetParamList",
        objectList: [[objnam: objnam, params: [LOTMP: temp.toString()]]]
    ])
}

def setBodyHeatSource(String childDni, String source) {
    def objnam = objnamFromDni(childDni)
    if (!objnam) { log.warn "setBodyHeatSource: no objnam for DNI ${childDni}"; return }

    if (source == "Off") {
        if (debugMode) log.debug "setBodyHeatSource: ${objnam} — heat off (HTSRC=00000)"
        sendCommand([
            command: "SetParamList",
            objectList: [[objnam: objnam, params: [HTSRC: "00000"]]]
        ])
        return
    }

    def htsrcId = state.htsrcIds?.get(objnam)?.get(source)

    if (!htsrcId) {
        htsrcId = state.heaterNames?.find { k, v -> v?.equalsIgnoreCase(source) }?.key
    }

    if (!htsrcId) {
        def staticSrcMap = ["Heater":"H0001","Solar Only":"S0001","Solar Preferred":"H0002",
                            "Heat Pump":"H0003","Heat Pump Preferred":"H0004"]
        htsrcId = staticSrcMap[source]
        if (htsrcId) log.warn "setBodyHeatSource: using static fallback '${htsrcId}' for '${source}'"
    }

    if (!htsrcId) { log.warn "setBodyHeatSource: unknown source '${source}' for ${objnam}"; return }

    if (debugMode) log.debug "setBodyHeatSource: ${objnam} HTSRC=${htsrcId} (${source})"
    sendCommand([
        command: "SetParamList",
        objectList: [[objnam: objnam, params: [HTSRC: htsrcId]]]
    ])
}

// ============================================================
// ===================== PUMP SPEED COMMAND ==================
// ============================================================
def setPumpSpeed(String childDni, Integer rpm) {
    def objnam = objnamFromDni(childDni)
    if (!objnam) { log.warn "setPumpSpeed: no objnam for DNI ${childDni}"; return }
    if (debugMode) log.debug "setPumpSpeed: ${objnam} RPM=${rpm}"
    sendCommand([
        command: "SetParamList",
        objectList: [[objnam: objnam, params: [RPM: rpm.toString()]]]
    ])
}

// ============================================================
// ===================== CIRCUIT COMMANDS ====================
// ============================================================
def circuitOn(String childDni) {
    def objnam = objnamFromDni(childDni)
    if (!objnam) { log.warn "No objnam for DNI: ${childDni}"; return }
    if (debugMode) log.debug "Circuit ON: ${objnam}"
    sendCommand([
        command: "SetParamList",
        objectList: [[objnam: objnam, params: [STATUS: "ON"]]]
    ])
    getChildDevice(childDni)?.sendEvent(name: "switch", value: "on")
}

def circuitOff(String childDni) {
    def objnam = objnamFromDni(childDni)
    if (!objnam) { log.warn "No objnam for DNI: ${childDni}"; return }
    if (debugMode) log.debug "Circuit OFF: ${objnam}"
    sendCommand([
        command: "SetParamList",
        objectList: [[objnam: objnam, params: [STATUS: "OFF"]]]
    ])
    getChildDevice(childDni)?.sendEvent(name: "switch", value: "off")
}

def componentOn(child)  { circuitOn(child.deviceNetworkId) }
def componentOff(child) { circuitOff(child.deviceNetworkId) }

// ============================================================
// ===================== SEND COMMAND ========================
// ============================================================
def sendCommand(Map payload) {
    if (!state.connected) {
        log.warn "Not connected — cannot send command"
        return
    }
    payload.messageID = java.util.UUID.randomUUID().toString()
    def json = groovy.json.JsonOutput.toJson(payload)
    if (debugMode) log.debug "TX: ${json}"
    try {
        interfaces.webSocket.sendMessage(json)
    } catch (e) {
        log.error "Send failed: ${e.message}"
        state.connected = false
        sendEvent(name: "connectionStatus", value: "Disconnected")
    }
}

// ============================================================
// ===================== COMPONENT CALLBACKS =================
// ============================================================
def componentRefresh(child) {
    if (!child?.deviceNetworkId) {
        if (debugMode) log.debug "componentRefresh: child or DNI not yet available — skipping"
        return
    }
    def dni = child.deviceNetworkId
    if (debugMode) log.debug "componentRefresh: ${child.displayName} (${dni})"

    if (dni.startsWith("intellicenter-sensor-")) {
        def objnam = objnamFromDni(dni)
        if (!objnam) return
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=SENSE",
            objectList: [[objnam: objnam, keys: ["OBJTYP", "SUBTYP", "SNAME", "SOURCE"]]]
        ])
    } else if (dni.startsWith("intellicenter-body-")) {
        refreshBody(dni)
    } else if (dni.startsWith("intellicenter-pump-")) {
        def objnam = objnamFromDni(dni)
        if (!objnam) return
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=PUMP",
            objectList: [[objnam: objnam, keys: ["OBJTYP", "SNAME", "STATUS", "RPM", "GPM", "WATTS"]]]
        ])
    } else if (dni.startsWith("intellicenter-circuit-")) {
        def objnam = objnamFromDni(dni)
        if (!objnam) return
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=CIRCUIT",
            objectList: [[objnam: objnam, keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "FEATR"]]]
        ])
    } else if (dni.startsWith("intellicenter-chem-")) {
        def objnam = objnamFromDni(dni)
        if (!objnam) return
        sendCommand([
            command: "GetParamList",
            condition: "OBJTYP=CHEM",
            objectList: [[objnam: objnam, keys: ["OBJTYP", "SNAME", "STATUS", "SALT", "LOTMP"]]]
        ])
    } else {
        def objnam = objnamFromDni(dni)
        if (!objnam) return
        sendCommand([
            command: "GetParamList",
            objectList: [[objnam: objnam, keys: ["STATUS","TEMP","RPM","WATTS","GPM","SALT","SOURCE","LOTMP","HITMP","HTMODE","HTSRC"]]]
        ])
    }
}

def refreshBody(String dni) {
    def objnam = objnamFromDni(dni)
    if (!objnam) return
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=BODY",
        objectList: [[objnam: objnam, keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS", "TEMP", "LOTMP", "HITMP", "HTMODE", "HTSRC"]]]
    ])
}

// ============================================================
// ===================== HELPERS =============================
// ============================================================
def objnamFromDni(String dni) {
    for (prefix in ["intellicenter-body-", "intellicenter-circuit-", "intellicenter-pump-",
                     "intellicenter-sensor-", "intellicenter-chem-"]) {
        if (dni.startsWith(prefix)) return dni.substring(prefix.length())
    }
    def idx = dni.lastIndexOf("-")
    return idx > 0 ? dni.substring(idx + 1) : null
}

def getOrCreateChild(String driver, String dni, String label, Boolean isComponent = false) {
    def child = getChildDevice(dni)
    if (!child) {
        def namespace = driver.startsWith("Pentair") ? "intellicenter" : "hubitat"
        try {
            child = addChildDevice(namespace, driver, dni, [label: label, isComponent: isComponent])
            if (debugMode) log.debug "Created child: ${label} (${dni}) [${namespace}] isComponent=${isComponent}"
        } catch (e) {
            log.warn "Could not create ${label}: ${e.message}"
        }
    }
    return child
}



