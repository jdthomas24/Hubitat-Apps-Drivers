// ============================================================
// Pentair IntelliCenter Body Driver
// Version: 1.5.1
// All files in this integration share this version number.
//
// CHANGES FROM 1.5.1:
//   - Added capability "Switch", "Thermostat", "TemperatureMeasurement"
//     so Hubitat shows a proper pool/thermostat icon instead of "?"
//   - renderTile() fully rewritten with all CSS inlined via style=""
//     attributes — no <style> block. Hubitat's dashboard sanitizer
//     strips <style> blocks, which was preventing the tile from
//     rendering any visual output.
// ============================================================

metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Pool / Spa controller — pump, temperature and heat control",
        version: "1.5.1"
    ) {
        // ── Standard capabilities ─────────────────────────────
        // These give Hubitat enough context to show a thermostat
        // icon on the device page instead of a "?" placeholder.
        // They also make the device selectable in standard apps
        // (Google Home, Alexa, Rule Machine) that filter by cap.
        capability "Switch"
        capability "Thermostat"
        capability "TemperatureMeasurement"
        capability "Refresh"

        // ── Custom attributes ─────────────────────────────────
        attribute "heatingSetpoint", "number"   // already in Thermostat cap but kept for clarity
        attribute "maxSetTemp",      "number"
        attribute "heaterMode",      "string"
        attribute "heatSource",      "string"
        attribute "bodyStatus",      "string"
        attribute "tile",            "string"
        attribute "heatLock",        "string"

        // ── Main controls — labels match tile buttons exactly ──
        command "🔥 Heat and Start Pump", [[name: "degrees*", type: "NUMBER", description: "Target temp °F"]]
        command "▶ Start Pump Only"
        command "⏹ Stop Pump and Heat"
        command "refresh"

        // ── Advanced ──────────────────────────────────────────
        command "⚙ Set Heat Source", [[name: "source*", type: "ENUM",
            constraints: ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]]
        command "⚙ Disable Heat Lock"
        command "⚙ Enable Heat Lock"
        command "⚙ Heat Off, Keep Pump On"
    }

    preferences {
        input "minSetPoint",  "number", title: "Minimum Set Point (°F)",      defaultValue: 40,  required: true
        input "maxSetPoint",  "number", title: "Maximum Set Point (°F)",       defaultValue: 104, required: true
        input "endpointBase", "text",   title: "App Endpoint Base (auto-set)", required: false
        input "debugMode",    "bool",   title: "Debug Logging",                defaultValue: false
    }
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter Body v1.5.2 installed: ${device.displayName}"
    sendEvent(name: "heatLock", value: "unlocked")
    // Thermostat capability requires thermostatMode — set a safe default
    sendEvent(name: "thermostatMode",         value: "off")
    sendEvent(name: "thermostatOperatingState", value: "idle")
    renderTile()
}

def updated() {
    log.info "IntelliCenter Body v1.5.2 updated: ${device.displayName}"
    unschedule(disableDebugLogging)
    if (debugMode) {
        log.info "${device.displayName}: debug logging enabled — will auto-disable in 60 minutes"
        runIn(3600, disableDebugLogging)
    }
    renderTile()
}

def disableDebugLogging() {
    log.info "${device.displayName}: auto-disabling debug logging after 60 minutes"
    device.updateSetting("debugMode", [value: false, type: "bool"])
}

// ============================================================
// ===================== THERMOSTAT CAPABILITY STUBS =========
// Hubitat's Thermostat capability requires these commands.
// They are stubs — pool/spa heat is controlled via the custom
// commands below, not through the generic thermostat interface.
// ============================================================
def setThermostatMode(mode) {
    // No-op: use "⚙ Set Heat Source" for heat control
    if (debugMode) log.debug "${device.displayName}: setThermostatMode(${mode}) — stub, use custom commands"
}
def setThermostatFanMode(mode) {
    if (debugMode) log.debug "${device.displayName}: setThermostatFanMode(${mode}) — stub"
}
def setCoolingSetpoint(temp) {
    if (debugMode) log.debug "${device.displayName}: setCoolingSetpoint(${temp}) — stub (no cooling)"
}
def heat()  { "🔥 Heat and Start Pump"(device.currentValue("heatingSetpoint") ?: 80) }
def cool()  { /* no cooling */ }
def auto()  { /* no auto mode */ }
def emergencyHeat() { /* no emergency heat */ }
def fanAuto()  { /* no fan */ }
def fanCirculate() { /* no fan */ }
def fanOn()  { /* no fan */ }

// ============================================================
// ===================== MAIN COMMANDS =======================
// ============================================================
def "🔥 Heat and Start Pump"(degrees) {
    def temp = degrees.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()
    if (temp < minT || temp > maxT) {
        log.warn "${device.displayName}: ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    if (debugMode) log.debug "${device.displayName}: Heat and Start Pump — target ${temp}°F"

    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)

    def source = device.currentValue("heatSource") ?: "Heater"
    if (source == "Off" || source == "—") source = "Heater"
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
    if (debugMode) log.debug "${device.displayName}: heat source '${source}' sent with HTMODE"

    sendEvent(name: "switch",                    value: "on")
    sendEvent(name: "bodyStatus",                value: "On")
    sendEvent(name: "thermostatMode",            value: "heat")
    sendEvent(name: "thermostatOperatingState",  value: "heating")
    parent?.setBodyStatus(device.deviceNetworkId, "ON")

    debounceTile()
}

def "⚙ Heat Off, Keep Pump On"() {
    if (debugMode) log.debug "${device.displayName}: Heat Off"
    sendEvent(name: "heatSource",               value: "Off")
    sendEvent(name: "heaterMode",               value: "Off")
    sendEvent(name: "thermostatMode",           value: "off")
    sendEvent(name: "thermostatOperatingState", value: "idle")
    parent?.setBodyHeatSource(device.deviceNetworkId, "Off")
    debounceTile()
}

def "▶ Start Pump Only"() {
    if (debugMode) log.debug "${device.displayName}: Start Pump Only"
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
    debounceTile()
}

def "⏹ Stop Pump and Heat"() {
    if (debugMode) log.debug "${device.displayName}: Stop Pump and Heat"
    sendEvent(name: "switch",                    value: "off")
    sendEvent(name: "bodyStatus",                value: "Off")
    sendEvent(name: "thermostatMode",            value: "off")
    sendEvent(name: "thermostatOperatingState",  value: "idle")
    parent?.setBodyStatus(device.deviceNetworkId, "OFF")
    debounceTile()
}

// ============================================================
// ===================== INTERNAL METHODS ====================
// ============================================================
def on()  { "▶ Start Pump Only"() }
def off() { "⏹ Stop Pump and Heat"() }

def setHeatingSetpoint(temp) {
    sendEvent(name: "heatingSetpoint", value: temp.toInteger(), unit: "°F")
    debounceTile()
}

def setHeatSource(source) {
    sendEvent(name: "heatSource", value: source)
    debounceTile()
}

def adjustSetPointUp() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    def maxT    = (maxSetPoint ?: 104).toInteger()
    def next    = Math.min(current + 1, maxT)
    sendEvent(name: "heatingSetpoint", value: next, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, next)
    debounceTile()
}

def adjustSetPointDown() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    def minT    = (minSetPoint ?: 40).toInteger()
    def next    = Math.max(current - 1, minT)
    sendEvent(name: "heatingSetpoint", value: next, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, next)
    debounceTile()
}

// ============================================================
// ===================== ADVANCED COMMANDS ===================
// ============================================================
def "⚙ Set Heat Source"(source) {
    if (device.currentValue("heatLock") == "locked") {
        log.warn "${device.displayName} — Heat Lock is active. Use '⚙ Enable Heat Lock' command to restore."
        return
    }
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
    if (debugMode) log.debug "${device.displayName}: heat source '${source}' sent"
    debounceTile()
}

def "⚙ Disable Heat Lock"() {
    log.info "${device.displayName} — Heat Lock removed (heating controls unlocked)"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

def "⚙ Enable Heat Lock"() {
    log.info "${device.displayName} — Heat Lock applied (heating controls locked)"
    sendEvent(name: "heatLock", value: "locked")
    debounceTile()
}

// ============================================================
// ===================== REFRESH =============================
// ============================================================
def refresh() {
    if (!device?.deviceNetworkId) {
        log.warn "${device?.displayName ?: 'Body'}: refresh skipped — device not fully initialised"
        return
    }
    if (debugMode) log.debug "${device.displayName}: refresh requested"
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
// v1.5.2: All CSS is inline style="" — no <style> block.
// Hubitat's dashboard tile sanitizer strips <style> blocks,
// so class-based rules never reached the browser. Every rule
// is now attached directly to the element that needs it.
// ============================================================
def renderTile() {
    def sw       = device.currentValue("switch")           ?: "off"
    def temp     = (device.currentValue("temperature")     ?: 0).toDouble()
    def setpt    = (device.currentValue("heatingSetpoint") ?: 0).toDouble()
    def maxTemp  = (device.currentValue("maxSetTemp")      ?: 104).toDouble()
    def htmode   = device.currentValue("heaterMode")       ?: "—"
    def htsrc    = device.currentValue("heatSource")       ?: "Off"
    def heatLock = device.currentValue("heatLock")         ?: "unlocked"

    def isOn      = (sw == "on")
    def isLocked  = (heatLock == "locked")
    def isHeating = isOn && htmode != "Off" && htmode != "—" && htmode != "0" && htsrc != "Off"

    def name   = device.displayName
    def dni    = device.deviceNetworkId
    def base   = endpointBase ?: ""
    def hasUrl = base ? true : false

    def url    = { String cmd -> "${base}/body/${dni}/${cmd}" }
    def srcUrl = { String src -> "${base}/body/${dni}/heatsource/${src.replaceAll(' ','_').toLowerCase()}" }

    // ── Arc gauge math ────────────────────────────────────────
    def minT      = (minSetPoint ?: 40).toDouble()
    def maxT      = (maxSetPoint ?: 104).toDouble()
    def arcStart  = 125.0; def arcEnd = 415.0; def arcRange = arcEnd - arcStart
    def clamp     = { v, lo, hi -> Math.max((double)lo, Math.min((double)hi, (double)v)) }
    def tempFrac  = clamp((temp  - minT) / (maxT - minT), 0.0, 1.0)
    def setptFrac = clamp((setpt - minT) / (maxT - minT), 0.0, 1.0)
    def toRad     = { deg -> deg * Math.PI / 180.0 }
    def arcPath   = { double s, double e ->
        double cx = 110, cy = 110, r = 88
        double x1 = cx + r * Math.cos(toRad(s-90)); double y1 = cy + r * Math.sin(toRad(s-90))
        double x2 = cx + r * Math.cos(toRad(e-90)); double y2 = cy + r * Math.sin(toRad(e-90))
        "M ${x1.round(2)} ${y1.round(2)} A ${r} ${r} 0 ${((e-s)>180)?1:0} 1 ${x2.round(2)} ${y2.round(2)}"
    }
    def tempAngle  = arcStart + tempFrac  * arcRange
    def setptAngle = arcStart + setptFrac * arcRange
    def dotX = (110 + 88 * Math.cos(toRad(setptAngle - 90))).round(2)
    def dotY = (110 + 88 * Math.sin(toRad(setptAngle - 90))).round(2)
    def trackPath = arcPath(arcStart, arcEnd)
    def setptPath = arcPath(arcStart, setptAngle)
    def tempPath  = arcPath(arcStart, tempAngle)

    // ── Fetch URLs ─────────────────────────────────────────────
    def btnUpFetch     = hasUrl ? "fetch('${url('setpointup')}');"   : ""
    def btnDnFetch     = hasUrl ? "fetch('${url('setpointdown')}');" : ""
    def currentSrcSlug = (htsrc && htsrc != "Off" && htsrc != "—")
        ? htsrc.replaceAll(' ','_').toLowerCase()
        : 'heater'
    def heatStartFetch = hasUrl
        ? "fetch('${url('heatandstart/' + Math.round(setpt).toInteger() + '/' + currentSrcSlug)}');"
        : ""
    def heatOffFetch = hasUrl ? "fetch('${url('heatoff')}');"  : ""
    def pumpOnFetch  = hasUrl ? "fetch('${url('on')}');"       : ""
    def pumpOffFetch = hasUrl ? "fetch('${url('off')}');"      : ""

    // ── Heat source chip row ───────────────────────────────────
    def heatSources = ["Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]
    def srcBtns = heatSources.collect { lbl ->
        def isActive = htsrc?.equalsIgnoreCase(lbl)
        def btnStyle = isLocked
            ? "padding:6px 10px;border-radius:8px;border:1.5px solid #1e3a5f;background:#0f172a;color:#64748b;font-size:10px;font-weight:600;cursor:not-allowed;opacity:.3;"
            : isActive
                ? "padding:6px 10px;border-radius:8px;border:1.5px solid #f97316;background:#1a0800;color:#f97316;font-size:10px;font-weight:600;cursor:pointer;"
                : "padding:6px 10px;border-radius:8px;border:1.5px solid #1e3a5f;background:#0f172a;color:#64748b;font-size:10px;font-weight:600;cursor:pointer;"
        def fetchCall = (!isLocked && hasUrl) ? "fetch('${srcUrl(lbl)}');" : ""
        "<button style='${btnStyle}' onclick=\"${fetchCall}\" ${isLocked ? 'disabled' : ''}>${lbl}</button>"
    }.join("")

    // ── Heat section ───────────────────────────────────────────
    def heatSectionHtml
    if (isLocked) {
        heatSectionHtml = """
<div style='background:#7f1d1d;border:2px solid #ef4444;border-radius:10px;padding:12px;text-align:center;font-size:14px;font-weight:900;color:#fca5a5;letter-spacing:1px;text-transform:uppercase;'>
  🔒 HEAT LOCK IS ON
  <div style='font-size:9px;font-weight:400;color:#f87171;margin-top:6px;letter-spacing:0;text-transform:none;line-height:1.4;'>Go to device Commands page and tap "⚙ Enable Heat Lock" to restore heating</div>
</div>"""
    } else {
        def heatBtnLabel = isHeating ? "🔥 Heating Active — Tap to Update" : "🔥 Heat &amp; Start Pump"
        def heatBtnStyle = isHeating
            ? "width:100%;padding:14px;border-radius:12px;border:2px solid #f97316;background:#1a0800;color:#f97316;font-size:14px;font-weight:800;cursor:pointer;margin-top:4px;"
            : "width:100%;padding:15px;border-radius:12px;border:none;background:#c2410c;color:#fff;font-size:15px;font-weight:800;cursor:pointer;margin-top:4px;box-shadow:0 4px 14px rgba(194,65,12,.45);"
        heatSectionHtml = """
<div style='font-size:9px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.8px;margin-bottom:6px;'>STEP 1 — Set target temperature</div>
<div style='display:flex;align-items:center;gap:8px;margin-bottom:4px;'>
  <button style='width:44px;height:44px;border-radius:50%;border:none;background:#1e3a5f;color:#38bdf8;font-size:24px;font-weight:700;cursor:pointer;flex-shrink:0;line-height:1;' onclick="${btnDnFetch}">−</button>
  <div style='flex:1;text-align:center;font-size:28px;font-weight:800;color:#38bdf8;'>${Math.round(setpt)}°F</div>
  <button style='width:44px;height:44px;border-radius:50%;border:none;background:#1e3a5f;color:#38bdf8;font-size:24px;font-weight:700;cursor:pointer;flex-shrink:0;line-height:1;' onclick="${btnUpFetch}">+</button>
</div>
<div style='font-size:9px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.8px;margin-top:10px;margin-bottom:6px;'>STEP 2 — Choose heat source</div>
<div style='display:flex;flex-wrap:wrap;gap:5px;'>${srcBtns}</div>
<div style='font-size:9px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.8px;margin-top:10px;margin-bottom:6px;'>STEP 3 — Go!</div>
<button style='${heatBtnStyle}' onclick="${heatStartFetch}">${heatBtnLabel}</button>
<button style='width:100%;padding:10px;border-radius:10px;border:1.5px solid #1e3a5f;background:transparent;color:#94a3b8;font-size:11px;font-weight:600;cursor:pointer;margin-top:6px;' onclick="${heatOffFetch}">❄  Heat Off  (pump keeps running)</button>"""
    }

    // ── Pump section ───────────────────────────────────────────
    def pumpSectionHtml
    if (isOn) {
        def badge = isHeating
            ? "🔥 Pump running — heating to ${Math.round(setpt)}°F"
            : "🏊 Pump running — no heat"
        pumpSectionHtml = """
<div style='width:100%;padding:11px;border-radius:12px;border:2px solid #16a34a;background:#052e16;color:#4ade80;font-size:12px;font-weight:800;text-align:center;margin-bottom:6px;line-height:1.3;box-sizing:border-box;'>${badge}</div>
<button style='width:100%;padding:14px;border-radius:12px;border:none;background:#991b1b;color:#fff;font-size:15px;font-weight:800;cursor:pointer;box-shadow:0 4px 14px rgba(153,27,27,.4);' onclick="${pumpOffFetch}">⏹  Stop Pump &amp; Heat</button>"""
    } else {
        pumpSectionHtml = """
<button style='width:100%;padding:14px;border-radius:12px;border:2px solid #166534;background:#052e16;color:#4ade80;font-size:14px;font-weight:700;cursor:pointer;margin-bottom:6px;' onclick="${pumpOnFetch}">▶  Start Pump Only  (no heat)</button>
<div style='text-align:center;font-size:11px;color:#475569;margin-top:8px;padding:4px;'>Everything is off</div>"""
    }

    def noBase  = !base ? "<div style='color:#fbbf24;font-size:10px;text-align:center;margin-bottom:6px;'>⚠ Open app → click Done to activate controls</div>" : ""
    def pumpClr = isOn  ? "#4ade80" : "#ef4444"

    def html = """<div style='font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#0f172a;border-radius:20px;padding:16px 14px;color:#fff;max-width:260px;margin:0 auto;box-sizing:border-box;'>
<div style='font-size:16px;font-weight:800;text-align:center;margin-bottom:8px;color:#e2e8f0;letter-spacing:.3px;'>${name}</div>
${noBase}
<div style='position:relative;width:200px;height:128px;margin:0 auto 8px;'>
  <svg style='width:200px;height:200px;overflow:visible;' viewBox='0 0 220 220'>
    <path d='${trackPath}' stroke='#1e3a5f' stroke-width='13' fill='none' stroke-linecap='round'/>
    <path d='${setptPath}' stroke='#1d3460' stroke-width='13' fill='none' stroke-linecap='round'/>
    <path d='${tempPath}'  stroke='#1d6fbf' stroke-width='13' fill='none' stroke-linecap='round'/>
    <circle cx='${dotX}' cy='${dotY}' r='7' fill='#38bdf8' stroke='#0f172a' stroke-width='3'/>
  </svg>
  <div style='position:absolute;top:18px;left:50%;transform:translateX(-50%);text-align:center;pointer-events:none;white-space:nowrap;'>
    <div style='font-size:9px;color:#94a3b8;letter-spacing:1px;text-transform:uppercase;'>${htmode}</div>
    <div style='font-size:42px;font-weight:800;line-height:1;color:#fff;'>${Math.round(temp)}</div>
    <div style='font-size:11px;color:#94a3b8;'>°F current</div>
    <div style='font-size:10px;color:#38bdf8;margin-top:2px;'>Target ${Math.round(setpt)}°F</div>
  </div>
</div>
<div style='display:flex;gap:6px;margin-bottom:10px;'>
  <div style='flex:1;background:#1e3a5f;border-radius:10px;padding:7px 5px;text-align:center;'>
    <div style='font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.4px;margin-bottom:2px;'>Target</div>
    <div style='font-size:13px;font-weight:700;color:#e2e8f0;'>${Math.round(setpt)}°</div>
  </div>
  <div style='flex:1;background:#1e3a5f;border-radius:10px;padding:7px 5px;text-align:center;'>
    <div style='font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.4px;margin-bottom:2px;'>Max</div>
    <div style='font-size:13px;font-weight:700;color:#e2e8f0;'>${Math.round(maxTemp)}°</div>
  </div>
  <div style='flex:1;background:#1e3a5f;border-radius:10px;padding:7px 5px;text-align:center;'>
    <div style='font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.4px;margin-bottom:2px;'>Pump</div>
    <div style='font-size:13px;font-weight:700;color:${pumpClr};'>${isOn ? "On" : "Off"}</div>
  </div>
</div>
<hr style='border:none;border-top:1px solid #1e3a5f;margin:10px 0;'/>
${heatSectionHtml}
<hr style='border:none;border-top:1px solid #1e3a5f;margin:10px 0;'/>
${pumpSectionHtml}
</div>"""

    sendEvent(name: "tile", value: html, displayed: false)
}

