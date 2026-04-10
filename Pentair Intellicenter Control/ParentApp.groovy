definition(
    name: "Pentair IntelliCenter",
    namespace: "intellicenter",
    author: "jdthomas24",
    description: "Pentair IntelliCenter local integration for Hubitat",
    version: "1.5.9",
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

        // ============================================================
        // ===================== SUPPORT & DONATE ====================
        // ============================================================
        section("<b>Support & Community</b>") {
            href url: "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31",
                 style: "external",
                 title: "💬 Hubitat Community Thread",
                 description: "Questions, feedback, bug reports, and release notes"
            href url: "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US",
                 style: "external",
                 title: "☕ Buy Me a Coffee",
                 description: "Enjoying the integration? Any amount is appreciated — thank you!"
        }
    }
}

// ============================================================
// ===================== MAPPINGS ============================
// ============================================================
mappings {
    path("/body/:dni/on")                         { action: [GET: "endpointOn"] }
    path("/body/:dni/off")                        { action: [GET: "endpointOff"] }
    path("/body/:dni/heatoff")                    { action: [GET: "endpointHeatOff"] }
    path("/body/:dni/setpoint/:temp")             { action: [GET: "endpointSetPoint"] }
    path("/body/:dni/heatsource/:source")         { action: [GET: "endpointHeatSource"] }
    path("/body/:dni/setpointup")                 { action: [GET: "endpointSetPointUp"] }
    path("/body/:dni/setpointdown")               { action: [GET: "endpointSetPointDown"] }
    path("/body/:dni/heatandstart/:temp")         { action: [GET: "endpointHeatAndStart"] }
    path("/body/:dni/heatandstart/:temp/:source") { action: [GET: "endpointHeatAndStartWithSource"] }
}

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
    child.on()
    render status: 200, data: "OK"
}

def endpointHeatAndStartWithSource() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def dni = params.dni
    def temp = params.temp?.toInteger()
    if (temp) {
        child.setHeatingSetpoint(temp)
        setBodySetPoint(dni, temp)
    }
    def source = params.source?.replaceAll("_"," ")?.split(" ")?.collect{it.capitalize()}?.join(" ")
    if (source && source != "Off") {
        child.setHeatSource(source)
        setBodyHeatSource(params.dni, source)
    }
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
    child."⚙ Stop Heat - Keep Pump On"()
    render status: 200, data: "OK"
}

def endpointSetPoint() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def temp = params.temp.toInteger()
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

def endpointHeatSource() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def source = params.source?.replaceAll("_", " ")
                                ?.split(" ")
                                ?.collect { it.capitalize() }
                                ?.join(" ")
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

    def hubIP        = location.hubs[0].localIP
    def endpointBase = "http://${hubIP}:8080/apps/api/${app.id}"

    bridge.updateSetting("ipAddress",    [value: intellicenterIP,           type: "text"])
    bridge.updateSetting("portNumber",   [value: intellicenterPort ?: 6680, type: "number"])
    bridge.updateSetting("debugMode",    [value: debugMode ?: false,        type: "bool"])
    bridge.updateSetting("endpointBase", [value: endpointBase,              type: "text"])

    runIn(2, "initBridge")
}

def initBridge() {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.initialize()
}

def uninstalled() {
    log.info "IntelliCenter app uninstalled"
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.getChildDevices()?.each {
        try { bridge.deleteChildDevice(it.deviceNetworkId) }
        catch (e) { log.warn "Could not delete bridge child ${it.deviceNetworkId}: ${e.message}" }
    }
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) }
        catch (e) { log.warn "Could not delete ${it.deviceNetworkId}: ${e.message}" }
    }
}

// ============================================================
// ===================== BODY COMMAND RELAY ==================
// ============================================================
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
    bridge?.componentRefresh(child)
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


