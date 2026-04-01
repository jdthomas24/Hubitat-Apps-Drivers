metadata {
    definition(
        name: "Pentair IntelliCenter Bridge",
        namespace: "intellicenter",
        author: "Custom Integration",
        description: "Bridge driver for Pentair IntelliCenter TCP connection"
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
        input "ipAddress",  "text",   title: "IntelliCenter IP",   required: true
        input "portNumber", "number", title: "Port",               defaultValue: 6680
        input "debugMode",  "bool",   title: "Debug Logging",      defaultValue: true
    }
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
    initialize()
}

def initialize() {
    state.msgBuffer     = ""
    state.objectMap     = [:]
    state.pendingCmds   = [:]
    // lastPingTime removed — WebSocket handles keepalive natively
    state.connected     = false

    unschedule()
    // Watchdog every 2 minutes — WebSocket handles keepalive natively
    schedule("0 0/2 * * * ?", reconnectIfNeeded)

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
        log.warn "No IP address configured"
        sendEvent(name: "connectionStatus", value: "Not Configured")
        return
    }

    try {
        def uri = "ws://${ipAddress}:${portNumber ?: 6680}"
        if (debugMode) log.debug "Connecting via WebSocket to ${uri}"
        interfaces.webSocket.connect(uri)
        // connected/disconnected confirmed via webSocketStatus callback
    } catch (e) {
        log.error "Connection failed: ${e.message}"
        state.connected = false
        sendEvent(name: "connectionStatus", value: "Disconnected")
    }
}

def disconnect() {
    try {
        interfaces.webSocket.close()
    } catch (e) { }
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
    }
}

def reconnectIfNeeded() {
    if (!state.connected) {
        log.info "Watchdog: reconnecting to IntelliCenter"
        connect()
    }
}

// Keepalive is handled by the WebSocket protocol natively — no application-level ping needed

// ============================================================
// ===================== INCOMING DATA =======================
// ============================================================
def parse(String message) {
    // WebSocket delivers complete frames — no buffering needed
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
        case "SendParamList":
            handleParamList(json)
            break
        case "NotifyList":
            handleNotifyList(json)
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
    // Request GRP objects explicitly — they have OBJTYP=CIRCUIT but
    // may not always be returned reliably in a general CIRCUIT query
    sendCommand([
        command: "GetParamList",
        objectList: [
            [objnam: "GRP01", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS"]],
            [objnam: "GRP02", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS"]],
            [objnam: "GRP03", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS"]],
            [objnam: "GRP04", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS"]],
            [objnam: "GRP05", keys: ["OBJTYP", "SUBTYP", "SNAME", "STATUS"]]
        ]
    ])
    runIn(2, "requestBodies")
}

def requestBodies() {
    if (debugMode) log.debug "Requesting equipment — bodies"
    sendCommand([
        command: "GetParamList",
        condition: "OBJTYP=BODY",
        objectList: [[objnam: "ALL", keys: ["OBJTYP", "SNAME", "STATUS", "TEMP", "LOTMP", "HITMP", "HTMODE", "HTSRC"]]]
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
        objectList: [[objnam: "ALL", keys: ["STATUS", "TEMP", "RPM", "WATTS", "GPM", "SALT", "SOURCE", "LOTMP", "HITMP", "HTMODE"]]]
    ])
}

// ============================================================
// ===================== PROCESS RESPONSES ===================
// ============================================================
def handleParamList(json) {
    json?.objectList?.each { obj ->
        def name   = obj.objnam
        def params = obj.params
        if (!name || !params) return

        // Merge into object map
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

def routeUpdate(String objnam, Map params) {
    def objType = params.OBJTYP ?: state.objectMap?.get(objnam)?.OBJTYP
    if (!objType) return

    switch (objType) {
        case "CIRCUIT": processCircuit(objnam, params); break
        case "BODY":    processBody(objnam, params);    break
        case "PUMP":    processPump(objnam, params);    break
        case "SENSE":   processSensor(objnam, params);  break
        case "CHEM":    processChem(objnam, params);    break
    }
}

// ============================================================
// ===================== DEVICE UPDATES ======================
// ============================================================
def processCircuit(String objnam, Map params) {
    def subtyp = params.SUBTYP ?: state.objectMap?.get(objnam)?.SUBTYP ?: ""

    // Allow real user circuits (C####) and circuit groups (GRP####)
    // Skip everything else: X#### virtual, _#### system, FTR features
    def isUserCircuit = objnam.matches("C\\d+")
    def isGroup       = objnam.matches("GRP\\d+")
    if (!isUserCircuit && !isGroup) {
        if (debugMode) log.debug "Skipping internal circuit: ${objnam}"
        return
    }

    // Skip POOL and SPA subtype circuits — already represented as body devices
    if (subtyp == "POOL" || subtyp == "SPA") {
        if (debugMode) log.debug "Skipping body circuit (handled as body): ${objnam} (${subtyp})"
        return
    }

    def label  = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: objnam
    def status = params.STATUS
    if (status == null) return

    def dni   = "intellicenter-circuit-${objnam}"
    def child = getOrCreateChild("Generic Component Switch", dni, label)
    if (!child) return

    child.sendEvent(name: "switch", value: (status == "ON" ? "on" : "off"))
    if (debugMode) log.debug "Circuit [${label}] (${subtyp}): ${status}"
}

def processBody(String objnam, Map params) {
    def label  = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: objnam
    def status = params.STATUS
    def temp   = params.TEMP
    def hitmp  = params.HITMP
    def lotmp  = params.LOTMP
    def htmode = params.HTMODE

    // Body on/off switch
    if (status != null) {
        def swDni = "intellicenter-body-${objnam}"
        def sw    = getOrCreateChild("Generic Component Switch", swDni, label)
        sw?.sendEvent(name: "switch", value: (status == "ON" ? "on" : "off"))
    }

    // Current temperature
    if (temp != null) {
        def tDni = "intellicenter-bodytemp-${objnam}"
        def tc   = getOrCreateChild("Generic Component Temperature Sensor", tDni, "${label} Temp")
        tc?.sendEvent(name: "temperature", value: temp.toInteger(), unit: "°F")
    }

    // Set point (use HITMP, fall back to LOTMP)
    def setpt = hitmp ?: lotmp
    if (setpt != null) {
        def spDni = "intellicenter-setpt-${objnam}"
        def spc   = getOrCreateChild("Generic Component Temperature Sensor", spDni, "${label} Set Point")
        spc?.sendEvent(name: "temperature", value: setpt.toInteger(), unit: "°F")
    }

    // Heater mode
    if (htmode != null) {
        def htDni = "intellicenter-heater-${objnam}"
        def htc   = getOrCreateChild("Generic Component Switch", htDni, "${label} Heater")
        if (htc) {
            htc.sendEvent(name: "switch", value: (htmode == "OFF" ? "off" : "on"))
            htc.sendEvent(name: "heaterMode", value: htmode)
        }
    }

    if (debugMode) log.debug "Body [${label}]: status=${status} temp=${temp} setpt=${setpt} htmode=${htmode}"
}

def processPump(String objnam, Map params) {
    def label = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: objnam
    def rpm   = params.RPM
    // Guard against malformed WATTS key seen in some firmware responses: {"":"WATTS"}
    def watts = (params.WATTS && params.WATTS != "WATTS") ? params.WATTS : null
    def gpm   = params.GPM

    if (rpm != null) {
        def c = getOrCreateChild("Generic Component Power Meter", "intellicenter-pump-rpm-${objnam}", "${label} RPM")
        c?.sendEvent(name: "power", value: rpm.toInteger(), unit: "RPM")
    }
    if (watts != null) {
        def c = getOrCreateChild("Generic Component Power Meter", "intellicenter-pump-watts-${objnam}", "${label} Watts")
        c?.sendEvent(name: "power", value: watts.toInteger(), unit: "W")
    }
    // Only create GPM device if we have a non-zero value — avoids empty placeholder devices
    if (gpm != null && gpm.toInteger() > 0) {
        def c = getOrCreateChild("Generic Component Power Meter", "intellicenter-pump-gpm-${objnam}", "${label} GPM")
        c?.sendEvent(name: "power", value: gpm.toInteger(), unit: "GPM")
    }

    if (debugMode) log.debug "Pump [${label}]: rpm=${rpm} watts=${watts} gpm=${gpm}"
}

def processSensor(String objnam, Map params) {
    // Skip POOL and SPA subtype sensors — identical to body temperatures already created
    def subtyp = params.SUBTYP ?: state.objectMap?.get(objnam)?.SUBTYP ?: ""
    if (subtyp == "POOL" || subtyp == "SPA") {
        if (debugMode) log.debug "Skipping body sensor (handled by body temp device): ${objnam} (${subtyp})"
        return
    }

    def label  = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: objnam
    def source = params.SOURCE
    if (source == null) return

    def c = getOrCreateChild("Generic Component Temperature Sensor", "intellicenter-sensor-${objnam}", label)
    c?.sendEvent(name: "temperature", value: source.toInteger(), unit: "°F")
    if (debugMode) log.debug "Sensor [${label}]: ${source}°F"
}

def processChem(String objnam, Map params) {
    def label  = params.SNAME ?: state.objectMap?.get(objnam)?.SNAME ?: "Chlorinator"
    def status = params.STATUS
    def salt   = params.SALT

    def c = getOrCreateChild("Generic Component Switch", "intellicenter-chem-${objnam}", label)
    if (!c) return

    if (status != null) c.sendEvent(name: "switch", value: (status == "ON" ? "on" : "off"))
    if (salt != null)   c.sendEvent(name: "saltLevel", value: salt.toInteger(), unit: "PPM")

    if (debugMode) log.debug "Chem [${label}]: status=${status} salt=${salt}"
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
    // Optimistic update
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

def componentOn(child) {
    circuitOn(child.deviceNetworkId)
}

def componentOff(child) {
    circuitOff(child.deviceNetworkId)
}

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
// Called by Generic Component child drivers when they need a refresh
def componentRefresh(child) {
    if (debugMode) log.debug "componentRefresh called for ${child.displayName}"
    // Re-request state for this specific device
    def objnam = objnamFromDni(child.deviceNetworkId)
    if (!objnam) return
    sendCommand([
        command: "GetParamList",
        objectList: [[objnam: objnam, keys: ["STATUS", "TEMP", "RPM", "WATTS", "GPM", "SALT", "SOURCE"]]]
    ])
}

// ============================================================
// ===================== HELPERS =============================
// ============================================================
def objnamFromDni(String dni) {
    // DNI format: intellicenter-{type}-{objnam}
    // objnam may itself contain dashes e.g. C0001
    def idx = dni.indexOf("-", "intellicenter-".length())
    return idx > 0 ? dni.substring(idx + 1) : null
}

def getOrCreateChild(String driver, String dni, String label) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("hubitat", driver, dni, [label: label, isComponent: true])
            if (debugMode) log.debug "Created child: ${label} (${dni})"
        } catch (e) {
            log.warn "Could not create ${label}: ${e.message}"
        }
    }
    return child
}
