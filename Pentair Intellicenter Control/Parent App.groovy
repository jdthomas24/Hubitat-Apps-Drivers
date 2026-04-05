definition(
    name: "Pentair IntelliCenter",
    namespace: "intellicenter",
    author: "jdthomas24",
    description: "Pentair IntelliCenter local integration for Hubitat",
    version: "1.5.2",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "IntelliCenter Setup", install: true, uninstall: true) {

        section("<b>Controller</b>") {
            input "intellicenterIP",
                  "text",
                  title: "IntelliCenter IP Address",
                  description: "e.g. 192.168.1.50",
                  required: true
            input "intellicenterPort",
                  "number",
                  title: "Port",
                  defaultValue: 6680,
                  required: true
        }

        section("<b>Options</b>") {
            input "debugMode",
                  "bool",
                  title: "Enable Debug Logging",
                  defaultValue: false
            label title: "App Name", required: false
        }

        if (intellicenterIP) {
            section("<b>Status</b>") {
                def bridge = getChildDevice("intellicenter-bridge-${app.id}")
                if (bridge) {
                    paragraph "Bridge device: <b>${bridge.displayName}</b>"
                    paragraph "Connection: <b>${bridge.currentValue('connectionStatus') ?: 'Unknown'}</b>"
                } else {
                    paragraph "Bridge device not yet created — click Done to initialize."
                }
            }
        }
    }
}

// ============================================================
// ===================== MAPPINGS ============================
// ============================================================
// These endpoints allow body device tiles to send commands
// back to Hubitat without requiring a Maker API token.
// All calls are local hub-to-hub on port 8080.
// The app ID in the URL is the only gate; these endpoints are
// not reachable externally unless the hub is port-forwarded.

mappings {
    path("/body/:dni/on")                        { action: [GET: "endpointOn"] }
    path("/body/:dni/off")                       { action: [GET: "endpointOff"] }
    path("/body/:dni/heatoff")                   { action: [GET: "endpointHeatOff"] }
    path("/body/:dni/setpoint/:temp")            { action: [GET: "endpointSetPoint"] }
    path("/body/:dni/heatsource/:source")        { action: [GET: "endpointHeatSource"] }
    path("/body/:dni/setpointup")                { action: [GET: "endpointSetPointUp"] }
    path("/body/:dni/setpointdown")              { action: [GET: "endpointSetPointDown"] }
    path("/body/:dni/heatandstart/:temp")        { action: [GET: "endpointHeatAndStart"] }
    path("/body/:dni/heatandstart/:temp/:source") { action: [GET: "endpointHeatAndStartWithSource"] }
}

// ── On / Off ────────────────────────────────────────────────

def endpointOn() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.on()
    render status: 200, data: "OK"
}

def endpointHeatAndStart() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def temp = params.temp?.toInteger()
    if (temp) {
        child.setHeatingSetpoint(temp)
        setBodySetPoint(params.dni, temp)
    }
    // Start pump
    child.on()
    render status: 200, data: "OK"
}

def endpointHeatAndStartWithSource() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }

    def dni = params.dni

    // 1. Set target temperature
    def temp = params.temp?.toInteger()
    if (temp) {
        child.setHeatingSetpoint(temp)
        setBodySetPoint(dni, temp)
    }

    // 2. Set heat source AFTER pump on
    def source = params.source?.replaceAll("_"," ")?.split(" ")?.collect{it.capitalize()}?.join(" ")
    if (source && source != "Off") {
        child.setHeatSource(source)
        setBodyHeatSource(params.dni, source)
    }

    // 3. Start pump
    child.on()

    render status: 200, data: "OK"
}

def endpointOff() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.off()
    render status: 200, data: "OK"
}

def endpointHeatOff() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    // "Heat Off" on the body driver calls parent?.setBodyHeatSource("Off")
    // which reaches the bridge and sends HTMODE=0 + HTSRC=00000.
    // No need to call setBodyHeatSource here separately.
    child."⚙ Heat Off, Keep Pump On"()
    render status: 200, data: "OK"
}

// ── Set Point ───────────────────────────────────────────────

def endpointSetPoint() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def temp = params.temp.toInteger()
    // Update local attribute AND write to controller
    child.setHeatingSetpoint(temp)
    setBodySetPoint(params.dni, temp)
    render status: 200, data: "OK"
}

def endpointSetPointUp() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    // adjustSetPointUp() increments the set point and sends to controller
    child.adjustSetPointUp()
    render status: 200, data: "OK"
}

def endpointSetPointDown() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.adjustSetPointDown()
    render status: 200, data: "OK"
}

// ── Heat Source ─────────────────────────────────────────────

def endpointHeatSource() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    // Convert URL slug back to title case: solar_preferred → Solar Preferred
    def source = params.source?.replaceAll("_", " ")
                                ?.split(" ")
                                ?.collect { it.capitalize() }
                                ?.join(" ")
    // Update local attribute AND write to controller
    child.setHeatSource(source)
    setBodyHeatSource(params.dni, source)
    render status: 200, data: "OK"
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter app installed"
    initialize()
}

def updated() {
    log.info "IntelliCenter app updated"
    initialize()
}

def initialize() {
    def bridgeDni = "intellicenter-bridge-${app.id}"
    def bridge    = getChildDevice(bridgeDni)

    if (!bridge) {
        log.info "Creating IntelliCenter bridge device"
        bridge = addChildDevice(
            "intellicenter",
            "Pentair IntelliCenter Bridge",
            bridgeDni,
            [label: "IntelliCenter Bridge", isComponent: false]
        )
    }

    // Hubitat's local app API runs on port 8080 (not port 80).
    // Format: http://[hub-ip]:8080/apps/api/[app-id]
    def hubIP        = location.hubs[0].localIP
    def endpointBase = "http://${hubIP}:8080/apps/api/${app.id}"

    bridge.updateSetting("ipAddress",    [value: intellicenterIP,           type: "text"])
    bridge.updateSetting("portNumber",   [value: intellicenterPort ?: 6680, type: "number"])
    bridge.updateSetting("debugMode",    [value: debugMode ?: false,        type: "bool"])
    bridge.updateSetting("endpointBase", [value: endpointBase,              type: "text"])

    // Small delay so settings propagate before the WebSocket connects
    runIn(2, "initBridge")
}

def initBridge() {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.initialize()
}

def uninstalled() {
    log.info "IntelliCenter app uninstalled"
    // Delete bridge children first (circuits, pumps, sensors)
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.getChildDevices()?.each {
        try { bridge.deleteChildDevice(it.deviceNetworkId) }
        catch (e) { log.warn "Could not delete bridge child ${it.deviceNetworkId}: ${e.message}" }
    }
    // Delete all app children (body devices + bridge itself)
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) }
        catch (e) { log.warn "Could not delete ${it.deviceNetworkId}: ${e.message}" }
    }
}

// ============================================================
// ===================== BODY COMMAND RELAY ==================
// ============================================================
// Body devices are bridge children. Their parent is the bridge,
// which has setBodyStatus/SetPoint/HeatSource methods directly.
// These app-level relays are kept so that app HTTP endpoints
// (endpointOn, endpointHeatSource, etc.) can also drive the
// bridge without needing a direct reference to it.

def setBodyStatus(String bodyDni, String status) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.setBodyStatus(bodyDni, status)
}

def setBodySetPoint(String bodyDni, Integer temp) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.setBodySetPoint(bodyDni, temp)
}

def setBodyHeatSource(String bodyDni, String source) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.setBodyHeatSource(bodyDni, source)
}

def componentRefresh(child) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.refreshBody(child.deviceNetworkId)
}

// ============================================================
// ===================== CIRCUIT COMMAND RELAY ===============
// ============================================================
def childOn(String dni) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.circuitOn(dni)
}

def childOff(String dni) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.circuitOff(dni)
}
