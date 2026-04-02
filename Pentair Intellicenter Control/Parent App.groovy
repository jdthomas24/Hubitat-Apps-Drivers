definition(
    name: "Pentair IntelliCenter",
    namespace: "intellicenter",
    author: "jdthomas24",
    description: "Pentair IntelliCenter local integration for Hubitat",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    version: "1.3.0"
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
// All calls are local hub-to-hub on port 80 — no external
// access is required or implied. The app ID in the URL is
// the only gate; these endpoints are not reachable externally
// unless the hub is explicitly port-forwarded (not recommended).

mappings {
    path("/body/:dni/on")                 { action: [GET: "endpointOn"] }
    path("/body/:dni/confirmOn")          { action: [GET: "endpointConfirmOn"] }
    path("/body/:dni/off")                { action: [GET: "endpointOff"] }
    path("/body/:dni/setpoint/:temp")     { action: [GET: "endpointSetPoint"] }
    path("/body/:dni/heatsource/:source") { action: [GET: "endpointHeatSource"] }
    path("/body/:dni/setpointup")         { action: [GET: "endpointSetPointUp"] }
    path("/body/:dni/setpointdown")       { action: [GET: "endpointSetPointDown"] }
}

// ── On / Off / Confirm ──────────────────────────────────────

def endpointOn() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.on()
    render status: 200, data: "OK"
}

def endpointConfirmOn() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    // confirmOn() updates local state and then relays STATUS:ON
    // to the bridge via the app's setBodyStatus relay below.
    child.confirmOn()
    render status: 200, data: "OK"
}

def endpointOff() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.off()
    render status: 200, data: "OK"
}

// ── Set Point ───────────────────────────────────────────────

def endpointSetPoint() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def temp = params.temp.toInteger()
    // Update device attribute and send to controller
    child.setHeatingSetpoint(temp)
    setBodySetPoint(params.dni, temp)
    render status: 200, data: "OK"
}

def endpointSetPointUp() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
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
    // Convert URL slug: solar_preferred → Solar Preferred
    def source = params.source?.replaceAll("_", " ")
                                ?.split(" ")
                                ?.collect { it.capitalize() }
                                ?.join(" ")
    // Update device attribute
    child.setHeatSource(source)
    // Relay to controller via bridge
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

    // Build the local endpoint base URL — no token needed.
    // Format: http://[hub-ip]/apps/api/[app-id]
    def hubIP        = location.hubs[0].localIP
    def endpointBase = "http://${hubIP}/apps/api/${app.id}"

    bridge.updateSetting("ipAddress",     [value: intellicenterIP,           type: "text"])
    bridge.updateSetting("portNumber",    [value: intellicenterPort ?: 6680, type: "number"])
    bridge.updateSetting("debugMode",     [value: debugMode ?: false,        type: "bool"])
    // Bridge needs endpointBase so it can stamp it into body child devices as they are discovered
    bridge.updateSetting("endpointBase",  [value: endpointBase,              type: "text"])

    // Small delay so updateSetting calls propagate before the bridge connects
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
        try { bridge.deleteChildDevice(it.deviceNetworkId) } catch (e) { log.warn "Could not delete bridge child ${it.deviceNetworkId}: ${e.message}" }
    }
    // Then delete all app children (body devices + bridge itself)
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) } catch (e) { log.warn "Could not delete ${it.deviceNetworkId}: ${e.message}" }
    }
}

// ============================================================
// ===================== BODY DEVICE MANAGEMENT ==============
// ============================================================
// Body devices are created directly under the app (not the bridge)
// to avoid Hubitat's grandchild device limitation and to keep
// the app-endpoint relay chain direct (app ↔ body ↔ bridge).

def getOrCreateBodyDevice(String dni, String label) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            log.info "Creating body device: ${label} (${dni})"
            child = addChildDevice("intellicenter", "Pentair IntelliCenter Body", dni,
                [label: label, isComponent: false])
        } catch (e) {
            log.warn "Could not create body device ${label}: ${e.message}"
            return null
        }
    }
    // Stamp current endpoint base — refreshed every time in case hub IP or app ID changed
    def hubIP        = location.hubs[0].localIP
    def endpointBase = "http://${hubIP}/apps/api/${app.id}"
    child.updateSetting("endpointBase", [value: endpointBase, type: "text"])
    return child
}

// ============================================================
// ===================== BODY COMMAND RELAY ==================
// ============================================================
// Body devices call these on their parent (the app).
// The app relays to the bridge, which sends to IntelliCenter.

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
