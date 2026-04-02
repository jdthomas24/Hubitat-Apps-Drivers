metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Combined pool/spa controller — on/off, current temp, set point, heater mode and source"
    ) {
        attribute "switch",          "string"
        attribute "temperature",     "number"
        attribute "heatingSetpoint", "number"
        attribute "maxSetTemp",      "number"
        attribute "heaterMode",      "string"
        attribute "heatSource",      "string"
        attribute "bodyStatus",      "string"
        attribute "pendingOn",       "string"
        attribute "tile",            "string"

        attribute "heatLock", "string"   // "locked" or "unlocked"

        command "refresh"

        // ── Primary control ──────────────────────────────────
        command "Turn On",          []   // Starts 10-second confirmation countdown — tap Confirm Turn On to complete
        command "Confirm Turn On",  []   // Must be tapped within 10 seconds of Turn On or the request cancels
        command "Turn Off",         []   // Immediately turns off — no confirmation required

        // ── Temperature ──────────────────────────────────────
        command "Set Temperature",  [[name: "degrees*", type: "NUMBER", description: "Set point in °F (40–104)"]]

        // ── Heat source ──────────────────────────────────────
        // Disabled when heat is locked — use Disable Heat / Enable Heat to control access
        command "Set Heat Source",  [[name: "source*",  type: "ENUM",   description: "Heat source (locked out when heat is disabled)",
            constraints: ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]]

        // ── Heat lockout ─────────────────────────────────────
        command "Disable Heat"      // Locks out all heat source controls — pool/spa will still run normally
        command "Enable Heat"       // Unlocks heat source controls
    }

    preferences {
        input "minSetPoint",    "number", title: "Minimum Set Point (°F)",      defaultValue: 40,  required: true
        input "maxSetPoint",    "number", title: "Maximum Set Point (°F)",       defaultValue: 104, required: true
        input "confirmTimeout", "number", title: "Confirm On timeout (seconds)", defaultValue: 10,  required: true
        input "endpointBase",   "text",   title: "App Endpoint Base (auto-set)", required: false
        input "debugMode",      "bool",   title: "Debug Logging",                defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Body installed: ${device.displayName}"
    sendEvent(name: "pendingOn", value: "false")
    sendEvent(name: "heatLock",  value: "unlocked")
    debounceTile()
}

def updated() {
    log.info "IntelliCenter Body updated: ${device.displayName}"
    debounceTile()
}

// ============================================================
// ===================== ON / OFF WITH CONFIRMATION ==========
// These are called by the app endpoint, not directly by users
// ============================================================
def on() {
    if (device.currentValue("switch") == "on") return
    def timeout = (confirmTimeout ?: 10).toInteger()
    sendEvent(name: "pendingOn", value: "true")
    runIn(timeout, cancelOn)
    debounceTile()
}

def confirmOn() {
    if (device.currentValue("pendingOn") != "true") return
    unschedule(cancelOn)
    sendEvent(name: "pendingOn",  value: "false")
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    // Relay via app endpoint — no parent needed
    def dni = device.deviceNetworkId
    def base = endpointBase ?: ""
    if (base) httpGet("${base}/body/${dni}/confirmbody") { }
    debounceTile()
}

def cancelOn() {
    sendEvent(name: "pendingOn", value: "false")
    debounceTile()
}

def off() {
    unschedule(cancelOn)
    sendEvent(name: "pendingOn", value: "false")
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "bodyStatus", value: "Off")
    debounceTile()
}

// ============================================================
// ===================== SET POINT ===========================
// ============================================================
def setHeatingSetpoint(temp) {
    sendEvent(name: "heatingSetpoint", value: temp.toInteger(), unit: "°F")
    debounceTile()
}

// ============================================================
// ===================== HEAT SOURCE =========================
// ============================================================
def setHeatSource(source) {
    sendEvent(name: "heatSource", value: source)
    debounceTile()
}

// ============================================================
// ===================== HEAT LOCKOUT ========================
// ============================================================
def "Disable Heat"() {
    log.info "${device.displayName} — heat disabled"
    sendEvent(name: "heatLock", value: "locked")
    debounceTile()
}

def "Enable Heat"() {
    log.info "${device.displayName} — heat enabled"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

// ============================================================
// ===================== FRIENDLY COMMAND WRAPPERS ===========
// ============================================================
def "Turn On"()         { on() }
def "Confirm Turn On"() { confirmOn() }
def "Turn Off"()        { off() }

def "Set Temperature"(degrees) {
    def temp = degrees.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()
    if (temp < minT || temp > maxT) {
        log.warn "Set point ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    def base = endpointBase ?: ""
    def dni  = device.deviceNetworkId
    if (base) {
        httpGet("${base}/body/${dni}/setpoint/${temp}") { }
    }
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    debounceTile()
}

def "Set Heat Source"(source) {
    if (device.currentValue("heatLock") == "locked") {
        log.warn "${device.displayName} — heat is disabled, set heat source ignored. Use Enable Heat first."
        return
    }
    def base = endpointBase ?: ""
    def dni  = device.deviceNetworkId
    if (base) {
        httpGet("${base}/body/${dni}/heatsource/${source.replaceAll(' ','_').toLowerCase()}") { }
    }
    sendEvent(name: "heatSource", value: source)
    debounceTile()
}

// ============================================================
// ===================== REFRESH =============================
// ============================================================
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
// ============================================================
def renderTile() {
    def sw       = device.currentValue("switch")           ?: "off"
    def temp     = (device.currentValue("temperature")     ?: 0).toDouble()
    def setpt    = (device.currentValue("heatingSetpoint") ?: 0).toDouble()
    def maxTemp  = (device.currentValue("maxSetTemp")      ?: 104).toDouble()
    def htmode   = device.currentValue("heaterMode")       ?: "—"
    def htsrc    = device.currentValue("heatSource")       ?: "Off"
    def pending  = device.currentValue("pendingOn")        ?: "false"
    def heatLock = device.currentValue("heatLock")         ?: "unlocked"
    def isOn       = (sw == "on")
    def isPending  = (pending == "true")
    def isLocked   = (heatLock == "locked")
    def name     = device.displayName
    def dni      = device.deviceNetworkId
    def base     = endpointBase ?: ""

    // URL builder
    def url = { String cmd -> "${base}/body/${dni}/${cmd}" }
    def srcUrl = { String src -> "${base}/body/${dni}/heatsource/${src.replaceAll(' ','_').toLowerCase()}" }

    // Arc geometry — 220° span, SVG 220x220, cx=110 cy=110 r=88
    def minT     = (minSetPoint ?: 40).toDouble()
    def maxT     = (maxSetPoint ?: 104).toDouble()
    def arcStart = 125.0
    def arcEnd   = 415.0
    def arcRange = arcEnd - arcStart
    def clamp    = { v, lo, hi -> Math.max((double)lo, Math.min((double)hi, (double)v)) }
    def tempFrac  = clamp((temp  - minT) / (maxT - minT), 0.0, 1.0)
    def setptFrac = clamp((setpt - minT) / (maxT - minT), 0.0, 1.0)

    def toRad = { deg -> deg * Math.PI / 180.0 }
    def arcPath = { double startDeg, double endDeg ->
        double cx = 110, cy = 110, r = 88
        double x1 = cx + r * Math.cos(toRad(startDeg - 90))
        double y1 = cy + r * Math.sin(toRad(startDeg - 90))
        double x2 = cx + r * Math.cos(toRad(endDeg   - 90))
        double y2 = cy + r * Math.sin(toRad(endDeg   - 90))
        int large = ((endDeg - startDeg) > 180) ? 1 : 0
        "M ${x1.round(2)} ${y1.round(2)} A ${r} ${r} 0 ${large} 1 ${x2.round(2)} ${y2.round(2)}"
    }

    def tempAngle  = arcStart + tempFrac  * arcRange
    def setptAngle = arcStart + setptFrac * arcRange
    def dotX = (110 + 88 * Math.cos(toRad(setptAngle - 90))).round(2)
    def dotY = (110 + 88 * Math.sin(toRad(setptAngle - 90))).round(2)

    def trackPath = arcPath(arcStart, arcEnd)
    def setptPath = arcPath(arcStart, setptAngle)
    def tempPath  = arcPath(arcStart, tempAngle)

    def switchColor = isOn ? "#4ade80" : "#ef4444"
    def switchLabel = isOn ? "● On"    : "● Off"

    // Heat source buttons
    def sources      = ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]
    def sourceValues = ["OFF", "HEATER", "SOLAR ONLY", "SOLAR PREFERRED", "HEAT PUMP", "HEAT PUMP PREFERRED"]
    def srcBtns = [sourceValues, sources].transpose().collect { val, lbl ->
        def active   = (htsrc?.toUpperCase() == val) ? "ic-src-active" : ""
        def fetchUrl = base ? "fetch('${srcUrl(val)}');" : ""
        "<button class='ic-src ${active}' onclick=\"${fetchUrl}\">${lbl}</button>"
    }.join("")

    // Power button states
    def btnOnBg      = isPending ? "#fbbf24" : (isOn ? "#166534" : "#15803d")
    def btnOnColor   = isPending ? "#0a1628" : "#ffffff"
    def btnOnLabel   = isPending ? "⏳ Waiting — Tap Confirm to Turn On" : (isOn ? "● Running — Tap to Restart" : "▶  Turn On")
    def btnOnFetch   = base ? "fetch('${url('on')}');"        : ""
    def confirmDisp  = isPending ? "block"  : "none"
    def btnConfFetch = base ? "fetch('${url('confirmOn')}');" : ""
    def btnOffFetch  = base ? "fetch('${url('off')}');"       : ""
    def btnUpFetch   = base ? "fetch('${url('setpointup')}');"   : ""
    def btnDnFetch   = base ? "fetch('${url('setpointdown')}');" : ""

    def noBase = !base ? "<div style='color:#fbbf24;font-size:10px;text-align:center;margin-bottom:6px;'>⚠ Open app and click Done to enable controls</div>" : ""

    def html = """<style>
.ic{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#112240;border-radius:20px;padding:16px 14px 14px;color:#fff;max-width:260px;margin:0 auto;box-sizing:border-box;}
.ic *{box-sizing:border-box;}
.ic-title{font-size:15px;font-weight:700;text-align:center;margin-bottom:10px;color:#e2e8f0;}
.ic-gauge{position:relative;width:200px;height:128px;margin:0 auto 6px;}
.ic-gauge svg{width:200px;height:200px;overflow:visible;}
.ic-center{position:absolute;top:18px;left:50%;transform:translateX(-50%);text-align:center;pointer-events:none;white-space:nowrap;}
.ic-mode{font-size:9px;color:#94a3b8;letter-spacing:1px;text-transform:uppercase;}
.ic-temp{font-size:40px;font-weight:800;line-height:1;color:#fff;}
.ic-unit{font-size:11px;color:#94a3b8;}
.ic-setlbl{font-size:10px;color:#38bdf8;margin-top:2px;}
.ic-row{display:flex;gap:7px;margin-bottom:7px;}
.ic-box{flex:1;background:#1e3a5f;border-radius:11px;padding:8px 6px;text-align:center;}
.ic-blbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.5px;margin-bottom:2px;}
.ic-bval{font-size:14px;font-weight:700;color:#e2e8f0;}
.ic-block{background:#1e3a5f;border-radius:11px;padding:10px 11px;margin-bottom:7px;}
.ic-hdr{display:flex;justify-content:space-between;font-size:9px;color:#94a3b8;text-transform:uppercase;letter-spacing:.5px;margin-bottom:7px;}
.ic-adj-row{display:flex;align-items:center;gap:7px;}
.ic-adj{width:36px;height:36px;border-radius:50%;border:none;background:#0f3460;color:#38bdf8;font-size:20px;font-weight:700;cursor:pointer;flex-shrink:0;display:flex;align-items:center;justify-content:center;}
.ic-setval{flex:1;text-align:center;font-size:22px;font-weight:800;color:#38bdf8;}
.ic-srclbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px;}
.ic-srcbtns{display:flex;flex-wrap:wrap;gap:4px;}
.ic-src{padding:4px 8px;border-radius:7px;border:1.5px solid #2d4a6f;background:#0a1628;color:#64748b;font-size:9px;font-weight:600;cursor:pointer;}
.ic-src-active{border-color:#38bdf8;color:#38bdf8;background:#0f3460;}
.ic-pwrow{display:flex;gap:7px;margin-bottom:6px;}
.ic-btn{flex:1;padding:10px 4px;border-radius:11px;border:none;font-size:12px;font-weight:700;cursor:pointer;}
.ic-status{text-align:center;font-size:12px;font-weight:700;margin-top:6px;}
.ic-btn-on{width:100%;padding:14px;border-radius:12px;border:none;font-size:15px;font-weight:800;cursor:pointer;margin-bottom:8px;letter-spacing:.3px;box-shadow:0 4px 12px rgba(0,0,0,0.3);}
.ic-btn-confirm{width:100%;padding:11px;border-radius:12px;border:2px solid #fbbf24;background:transparent;color:#fbbf24;font-size:13px;font-weight:700;cursor:pointer;margin-bottom:8px;letter-spacing:.3px;}
.ic-btn-off{width:100%;padding:10px;border-radius:12px;border:1.5px solid #374151;background:transparent;color:#6b7280;font-size:12px;font-weight:600;cursor:pointer;margin-bottom:6px;}
.ic-src-disabled{opacity:0.35;cursor:not-allowed;}
.ic-heat-lock{background:#422006;border:1px solid #92400e;border-radius:8px;padding:6px 8px;font-size:9px;color:#fbbf24;margin-bottom:6px;text-align:center;}
</style>
<div class='ic'>
  <div class='ic-title'>${name}</div>
  ${noBase}
  <div class='ic-gauge'>
    <svg viewBox='0 0 220 220'>
      <path d='${trackPath}' stroke='#1e3a5f' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${setptPath}' stroke='#1d4080' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${tempPath}'  stroke='#1d6fbf' stroke-width='13' fill='none' stroke-linecap='round'/>
      <circle cx='${dotX}' cy='${dotY}' r='7' fill='#38bdf8' stroke='#112240' stroke-width='3'/>
    </svg>
    <div class='ic-center'>
      <div class='ic-mode'>${htmode}</div>
      <div class='ic-temp'>${Math.round(temp)}</div>
      <div class='ic-unit'>°F current</div>
      <div class='ic-setlbl'>Set ${Math.round(setpt)}°F</div>
    </div>
  </div>
  <div class='ic-row'>
    <div class='ic-box'><div class='ic-blbl'>Set Point</div><div class='ic-bval'>${Math.round(setpt)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Max Temp</div><div class='ic-bval'>${Math.round(maxTemp)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Status</div><div class='ic-bval' style='color:${switchColor};'>${isOn ? "On" : "Off"}</div></div>
  </div>
  <div class='ic-block'>
    <div class='ic-hdr'><span>Set Point</span><span style='color:#38bdf8;'>${Math.round(setpt)}°F</span></div>
    <div class='ic-adj-row'>
      <button class='ic-adj' onclick="${btnDnFetch}">−</button>
      <div class='ic-setval'>${Math.round(setpt)}°</div>
      <button class='ic-adj' onclick="${btnUpFetch}">+</button>
    </div>
  </div>
  <div class='ic-block'>
    <div class='ic-srclbl'>Heat Source</div>
    ${heatLockBanner}
    <div class='ic-srcbtns'>${srcBtns}</div>
  </div>
  <button class='ic-btn-on' style='background:${btnOnBg};color:${btnOnColor};' onclick="${btnOnFetch}">${btnOnLabel}</button>
  <button class='ic-btn-confirm' style='display:${confirmDisp};' onclick="${btnConfFetch}">✓ Confirm Turn On</button>
  <button class='ic-btn-off' onclick="${btnOffFetch}">■  Turn Off</button>
  <div class='ic-status' style='color:${switchColor};'>${switchLabel}</div>
</div>"""

    sendEvent(name: "tile", value: html, displayed: false)
}
