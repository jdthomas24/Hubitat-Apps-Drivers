// ============================================================
// Pentair IntelliCenter Pump Driver
// Version: 1.5.0
// ============================================================

metadata {
    definition(
        name: "Pentair IntelliCenter Pump",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Variable speed pump — RPM, watts, GPM and water temperature display",
        version: "1.5.0"
    ) {
        attribute "rpm",         "number"
        attribute "watts",       "number"
        attribute "gpm",         "number"
        attribute "temperature", "number"   // water temp pushed from associated body
        attribute "tile",        "string"   // dashboard HTML tile

        command "setSpeed", [[name: "rpm*", type: "NUMBER", description: "Target RPM (450–3450)"]]
        command "refresh"
    }

    preferences {
        input "minRPM",    "number", title: "Minimum RPM",   defaultValue: 450,  required: true
        input "maxRPM",    "number", title: "Maximum RPM",   defaultValue: 3450, required: true
        input "debugMode", "bool",   title: "Debug Logging", defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Pump installed: ${device.displayName}"
    debounceTile()
}

def updated() {
    log.info "IntelliCenter Pump updated: ${device.displayName}"
    unschedule(disableDebugLogging)
    if (debugMode) {
        log.info "${device.displayName}: debug logging enabled — will auto-disable in 60 minutes"
        runIn(3600, disableDebugLogging)
    }
    debounceTile()
}

def disableDebugLogging() {
    log.info "${device.displayName}: auto-disabling debug logging after 60 minutes"
    device.updateSetting("debugMode", [value: false, type: "bool"])
}

// ============================================================
// ===================== COMMANDS ============================
// ============================================================
def setSpeed(rpm) {
    def target = rpm.toInteger()
    def minR   = (minRPM ?: 450).toInteger()
    def maxR   = (maxRPM ?: 3450).toInteger()

    if (target < minR || target > maxR) {
        log.warn "${device.displayName}: RPM ${target} out of range (${minR}–${maxR}) — ignoring"
        return
    }
    if (debugMode) log.debug "setSpeed: ${target} RPM"
    sendEvent(name: "rpm", value: target, unit: "RPM")
    parent?.setPumpSpeed(device.deviceNetworkId, target)
}

def refresh() {
    parent?.componentRefresh(this)
}

// ============================================================
// ===================== TILE DEBOUNCE =======================
// ============================================================
def debounceTile() {
    unschedule(renderTile)
    runIn(3, renderTile)
}

// ============================================================
// ===================== TILE RENDERER =======================
// Add this device to a dashboard as an Attribute tile
// and select the "tile" attribute to display the card.
// ============================================================
def renderTile() {
    def rpm   = device.currentValue("rpm")
    def watts = device.currentValue("watts")
    def gpm   = device.currentValue("gpm")
    def temp  = device.currentValue("temperature")

    def rpmVal   = rpm   != null ? "${rpm} RPM"   : "— RPM"
    def wattsVal = watts != null ? "${watts} W"   : "— W"
    def gpmVal   = gpm   != null ? "${gpm} GPM"   : "— GPM"
    def tempVal  = temp  != null ? "${temp}°F"    : "—°F"

    // Simple running indicator based on RPM
    def isRunning = rpm != null && rpm.toInteger() > 0
    def statusColor = isRunning ? "#4ade80" : "#64748b"
    def statusLabel = isRunning ? "● Running" : "● Off"

    def name = device.displayName

    def html = """<style>
.pu{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0f172a;border-radius:20px;padding:16px 14px;color:#fff;max-width:220px;margin:0 auto;box-sizing:border-box;}
.pu *{box-sizing:border-box;}
.pu-title{font-size:14px;font-weight:800;text-align:center;margin-bottom:4px;color:#e2e8f0;}
.pu-status{text-align:center;font-size:11px;font-weight:700;margin-bottom:12px;}
.pu-rpm{text-align:center;margin-bottom:12px;}
.pu-rpm-val{font-size:42px;font-weight:800;color:#38bdf8;line-height:1;}
.pu-rpm-lbl{font-size:11px;color:#64748b;margin-top:2px;text-transform:uppercase;letter-spacing:.5px;}
.pu-row{display:flex;gap:6px;}
.pu-box{flex:1;background:#1e3a5f;border-radius:10px;padding:8px 6px;text-align:center;}
.pu-blbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.4px;margin-bottom:3px;}
.pu-bval{font-size:14px;font-weight:700;color:#e2e8f0;}
</style>
<div class='pu'>
  <div class='pu-title'>${name}</div>
  <div class='pu-status' style='color:${statusColor};'>${statusLabel}</div>
  <div class='pu-rpm'>
    <div class='pu-rpm-val'>${rpm ?: '—'}</div>
    <div class='pu-rpm-lbl'>RPM</div>
  </div>
  <div class='pu-row'>
    <div class='pu-box'><div class='pu-blbl'>Watts</div><div class='pu-bval'>${watts ?: '—'}</div></div>
    <div class='pu-box'><div class='pu-blbl'>GPM</div><div class='pu-bval'>${gpm ?: '—'}</div></div>
    <div class='pu-box'><div class='pu-blbl'>Water</div><div class='pu-bval'>${tempVal}</div></div>
  </div>
</div>"""

    sendEvent(name: "tile", value: html, displayed: false)
}
