definition(
    name: "Pentair IntelliCenter",
    namespace: "intellicenter",
    author: "Custom Integration",
    description: "Pentair IntelliCenter local integration for Hubitat",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    version: "1.0.0"
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
                  defaultValue: true
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
    // Create or update the bridge driver device
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

    // Pass settings to the bridge driver
    bridge.updateSetting("ipAddress",  [value: intellicenterIP,          type: "text"])
    bridge.updateSetting("portNumber", [value: intellicenterPort ?: 6680, type: "number"])
    bridge.updateSetting("debugMode",  [value: debugMode ?: false,        type: "bool"])

    // Initialize bridge — clears old schedules and reconnects cleanly
    bridge.initialize()
}

def uninstalled() {
    log.info "IntelliCenter app uninstalled"
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) } catch (e) { }
    }
}

// ============================================================
// ===================== CHILD DEVICE HELPERS ================
// ============================================================
// Called by the bridge driver to create/update pool child devices
def getOrCreatePoolDevice(String driver, String dni, String label) {
    def child = getChildDevice(dni)
    if (!child) {
        try {
            log.info "Creating pool device: ${label} (${dni})"
            child = addChildDevice("hubitat", driver, dni, [label: label, isComponent: false])
        } catch (e) {
            log.warn "Could not create ${label}: ${e.message}"
            return null
        }
    }
    return child
}

def sendEventToChild(String dni, String name, value, String unit = null) {
    def child = getChildDevice(dni)
    if (!child) return
    if (unit) {
        child.sendEvent(name: name, value: value, unit: unit)
    } else {
        child.sendEvent(name: name, value: value)
    }
}

// Called by child switch devices to control circuits
def childOn(String dni) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.circuitOn(dni)
}

def childOff(String dni) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.circuitOff(dni)
}
