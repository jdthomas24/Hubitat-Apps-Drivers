metadata {
    definition(
        name: "Pentair IntelliCenter Body",
        namespace: "intellicenter",
        author: "jdthomas24",
        description: "Pool / Spa controller — pump, temperature and heat control"
    ) {
        attribute "switch",          "string"
        attribute "temperature",     "number"
        attribute "heatingSetpoint", "number"
        attribute "maxSetTemp",      "number"
        attribute "heaterMode",      "string"
        attribute "heatSource",      "string"
        attribute "bodyStatus",      "string"
        attribute "tile",            "string"
        attribute "heatLock",        "string"

        // ── The 4 commands a user ever needs ─────────────────────
        // These appear prominently on the device page Commands tab.
        // Language matches the tile exactly so there's no confusion.
        command "Pump On"   // Start the pump (no change to heat)
        command "Pump Off"  // Stop the pump AND the heater completely
        command "Heat Off"  // Stop the heater only — pump keeps running
        command "refresh"

        // ── Advanced — for automations and Rules only ─────────────
        // Prefixed with "⚙ " so they sort last and look secondary.
        // Still fully callable from Rules/automations.
        command "⚙ Set Target Temp",  [[name: "degrees*", type: "NUMBER", description: "Target °F (40–104)"]]
        command "⚙ Set Heat Source",  [[name: "source*",  type: "ENUM",
            constraints: ["Off", "Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]]]
        command "⚙ Disable Heat"
        command "⚙ Enable Heat"
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
    log.info "IntelliCenter Body installed: ${device.displayName}"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

def updated() {
    log.info "IntelliCenter Body updated: ${device.displayName}"
    debounceTile()
}

// ============================================================
// ===================== PUMP ON / OFF =======================
// ============================================================
def on() {
    if (debugMode) log.debug "${device.displayName}: Pump On"
    sendEvent(name: "switch",     value: "on")
    sendEvent(name: "bodyStatus", value: "On")
    parent?.setBodyStatus(device.deviceNetworkId, "ON")
    debounceTile()
}

def off() {
    if (debugMode) log.debug "${device.displayName}: Pump Off"
    sendEvent(name: "switch",     value: "off")
    sendEvent(name: "bodyStatus", value: "Off")
    parent?.setBodyStatus(device.deviceNetworkId, "OFF")
    debounceTile()
}

// ============================================================
// ===================== HEAT OFF ============================
// Stops the heater. Pump keeps running.
// Sends HTMODE=0 (disables active heating mode) to controller.
// ============================================================
def "Heat Off"() {
    if (debugMode) log.debug "${device.displayName}: Heat Off"
    sendEvent(name: "heatSource", value: "Off")
    sendEvent(name: "heaterMode", value: "Off")
    // setBodyHeatSource with "Off" sends HTMODE=0 + HTSRC=00000 to the controller
    parent?.setBodyHeatSource(device.deviceNetworkId, "Off")
    debounceTile()
}

// ============================================================
// ===================== TEMPERATURE =========================
// ============================================================
def setHeatingSetpoint(temp) {
    sendEvent(name: "heatingSetpoint", value: temp.toInteger(), unit: "°F")
    debounceTile()
}

def adjustSetPointUp() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    "⚙ Set Target Temp"(current + 1)
}

def adjustSetPointDown() {
    def current = (device.currentValue("heatingSetpoint") ?: 80).toInteger()
    "⚙ Set Target Temp"(current - 1)
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
def "⚙ Disable Heat"() {
    log.info "${device.displayName} — heat disabled"
    sendEvent(name: "heatLock", value: "locked")
    debounceTile()
}

def "⚙ Enable Heat"() {
    log.info "${device.displayName} — heat enabled"
    sendEvent(name: "heatLock", value: "unlocked")
    debounceTile()
}

// ============================================================
// ===================== COMMAND WRAPPERS ====================
// ============================================================
def "Pump On"()  { on() }
def "Pump Off"() { off() }

def "⚙ Set Target Temp"(degrees) {
    def temp = degrees.toInteger()
    def minT = (minSetPoint ?: 40).toInteger()
    def maxT = (maxSetPoint ?: 104).toInteger()
    if (temp < minT || temp > maxT) {
        log.warn "${device.displayName}: ${temp}°F out of range (${minT}–${maxT}°F) — ignoring"
        return
    }
    sendEvent(name: "heatingSetpoint", value: temp, unit: "°F")
    parent?.setBodySetPoint(device.deviceNetworkId, temp)
    if (debugMode) log.debug "${device.displayName}: target temp ${temp}°F sent"
    debounceTile()
}

def "⚙ Set Heat Source"(source) {
    if (device.currentValue("heatLock") == "locked") {
        log.warn "${device.displayName} — heat is disabled. Use ⚙ Enable Heat first."
        return
    }
    sendEvent(name: "heatSource", value: source)
    parent?.setBodyHeatSource(device.deviceNetworkId, source)
    if (debugMode) log.debug "${device.displayName}: heat source '${source}' sent"
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
    def heatLock = device.currentValue("heatLock")         ?: "unlocked"

    def isOn      = (sw == "on")
    def isLocked  = (heatLock == "locked")
    def isHeating = isOn && htsrc != "Off" && htsrc != "—"

    def name   = device.displayName
    def dni    = device.deviceNetworkId
    def base   = endpointBase ?: ""
    def hasUrl = base ? true : false

    def url    = { String cmd -> "${base}/body/${dni}/${cmd}" }
    def srcUrl = { String src -> "${base}/body/${dni}/heatsource/${src.replaceAll(' ','_').toLowerCase()}" }

    // ── Arc gauge ─────────────────────────────────────────────
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
    def btnUpFetch   = hasUrl ? "fetch('${url('setpointup')}');"   : ""
    def btnDnFetch   = hasUrl ? "fetch('${url('setpointdown')}');" : ""
    def heatStartFetch = hasUrl ? "fetch('${url('on')}');"         : ""
    def heatOffFetch   = hasUrl ? "fetch('${url('heatoff')}');"    : ""
    def pumpOnFetch    = hasUrl ? "fetch('${url('on')}');"         : ""
    def pumpOffFetch   = hasUrl ? "fetch('${url('off')}');"        : ""

    // ── Heat source chips ──────────────────────────────────────
    // Tapping selects the source on the controller only.
    // Does NOT start the pump — user must tap Heat & Start Pump.
    def heatSources = ["Heater", "Solar Only", "Solar Preferred", "Heat Pump", "Heat Pump Preferred"]
    def srcBtns = heatSources.collect { lbl ->
        def active    = (htsrc?.equalsIgnoreCase(lbl)) ? "ic-src-active" : ""
        def disClass  = isLocked ? "ic-src-disabled" : ""
        def fetchCall = (!isLocked && hasUrl) ? "fetch('${srcUrl(lbl)}');" : ""
        "<button class='ic-src ${active} ${disClass}' onclick=\"${fetchCall}\" ${isLocked ? 'disabled' : ''}>${lbl}</button>"
    }.join("")

    // ── Heat section ───────────────────────────────────────────
    def heatSectionHtml
    if (isLocked) {
        heatSectionHtml = """
    <div class='ic-disabled-banner'>
      🔥 HEATING IS TURNED OFF
      <div class='ic-disabled-sub'>Use "⚙ Enable Heat" command on device page to restore</div>
    </div>"""
    } else {
        def heatBtnLabel = isHeating ? "🔥 Heating Active — Tap to Update" : "🔥 Heat &amp; Start Pump"
        def heatBtnClass = isHeating ? "ic-btn-heat-active" : "ic-btn-heat"
        heatSectionHtml = """
    <div class='ic-step'>STEP 1 — Set target temperature</div>
    <div class='ic-adj-row'>
      <button class='ic-adj' onclick="${btnDnFetch}">−</button>
      <div class='ic-setval'>${Math.round(setpt)}°F</div>
      <button class='ic-adj' onclick="${btnUpFetch}">+</button>
    </div>
    <div class='ic-step' style='margin-top:10px;'>STEP 2 — Choose heat source</div>
    <div class='ic-srcbtns'>${srcBtns}</div>
    <div class='ic-step' style='margin-top:10px;'>STEP 3 — Go!</div>
    <button class='${heatBtnClass}' onclick="${heatStartFetch}">${heatBtnLabel}</button>
    <button class='ic-btn-heatoff' onclick="${heatOffFetch}">❄  Heat Off  (pump keeps running)</button>"""
    }

    // ── Pump section ───────────────────────────────────────────
    def pumpSectionHtml
    if (isOn) {
        def badge = isHeating
            ? "🔥 Pump running — heating to ${Math.round(setpt)}°F"
            : "🏊 Pump running — no heat"
        pumpSectionHtml = """
  <div class='ic-running-badge'>${badge}</div>
  <button class='ic-btn-stop' onclick="${pumpOffFetch}">⏹  Stop Pump &amp; Heat</button>"""
    } else {
        pumpSectionHtml = """
  <button class='ic-btn-pumponly' onclick="${pumpOnFetch}">▶  Start Pump Only  (no heat)</button>
  <div class='ic-off-badge'>Everything is off</div>"""
    }

    def noBase  = !base ? "<div class='ic-warn'>⚠ Open app → click Done to activate controls</div>" : ""
    def pumpClr = isOn  ? "#4ade80" : "#ef4444"

    def html = """<style>
.ic{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#0f172a;border-radius:20px;padding:16px 14px 16px;color:#fff;max-width:260px;margin:0 auto;box-sizing:border-box;}
.ic *{box-sizing:border-box;}
.ic-title{font-size:16px;font-weight:800;text-align:center;margin-bottom:8px;color:#e2e8f0;letter-spacing:.3px;}
.ic-warn{color:#fbbf24;font-size:10px;text-align:center;margin-bottom:6px;}
.ic-gauge{position:relative;width:200px;height:128px;margin:0 auto 8px;}
.ic-gauge svg{width:200px;height:200px;overflow:visible;}
.ic-center{position:absolute;top:18px;left:50%;transform:translateX(-50%);text-align:center;pointer-events:none;white-space:nowrap;}
.ic-htmode{font-size:9px;color:#94a3b8;letter-spacing:1px;text-transform:uppercase;}
.ic-bigtemp{font-size:42px;font-weight:800;line-height:1;color:#fff;}
.ic-unit{font-size:11px;color:#94a3b8;}
.ic-target{font-size:10px;color:#38bdf8;margin-top:2px;}
.ic-row{display:flex;gap:6px;margin-bottom:10px;}
.ic-box{flex:1;background:#1e3a5f;border-radius:10px;padding:7px 5px;text-align:center;}
.ic-blbl{font-size:8px;color:#64748b;text-transform:uppercase;letter-spacing:.4px;margin-bottom:2px;}
.ic-bval{font-size:13px;font-weight:700;color:#e2e8f0;}
.ic-div{border:none;border-top:1px solid #1e3a5f;margin:10px 0;}
.ic-step{font-size:9px;font-weight:700;color:#475569;text-transform:uppercase;letter-spacing:.8px;margin-bottom:6px;}
.ic-adj-row{display:flex;align-items:center;gap:8px;margin-bottom:4px;}
.ic-adj{width:44px;height:44px;border-radius:50%;border:none;background:#1e3a5f;color:#38bdf8;font-size:24px;font-weight:700;cursor:pointer;flex-shrink:0;line-height:1;}
.ic-setval{flex:1;text-align:center;font-size:28px;font-weight:800;color:#38bdf8;}
.ic-srcbtns{display:flex;flex-wrap:wrap;gap:5px;}
.ic-src{padding:6px 10px;border-radius:8px;border:1.5px solid #1e3a5f;background:#0f172a;color:#64748b;font-size:10px;font-weight:600;cursor:pointer;}
.ic-src-active{border-color:#f97316;color:#f97316;background:#1a0800;}
.ic-src-disabled{opacity:0.3;cursor:not-allowed;}
.ic-btn-heat{width:100%;padding:15px;border-radius:12px;border:none;background:#c2410c;color:#fff;font-size:15px;font-weight:800;cursor:pointer;margin-top:4px;box-shadow:0 4px 14px rgba(194,65,12,0.45);}
.ic-btn-heat:active{background:#9a3412;}
.ic-btn-heat-active{width:100%;padding:14px;border-radius:12px;border:2px solid #f97316;background:#1a0800;color:#f97316;font-size:14px;font-weight:800;cursor:pointer;margin-top:4px;}
.ic-btn-heatoff{width:100%;padding:10px;border-radius:10px;border:1.5px solid #1e3a5f;background:transparent;color:#94a3b8;font-size:11px;font-weight:600;cursor:pointer;margin-top:6px;}
.ic-btn-heatoff:active{border-color:#38bdf8;color:#38bdf8;}
.ic-disabled-banner{background:#7f1d1d;border:2px solid #ef4444;border-radius:10px;padding:12px;text-align:center;font-size:14px;font-weight:900;color:#fca5a5;letter-spacing:1px;text-transform:uppercase;}
.ic-disabled-sub{font-size:9px;font-weight:400;color:#f87171;margin-top:6px;letter-spacing:0;text-transform:none;line-height:1.4;}
.ic-btn-pumponly{width:100%;padding:14px;border-radius:12px;border:2px solid #166534;background:#052e16;color:#4ade80;font-size:14px;font-weight:700;cursor:pointer;margin-bottom:6px;}
.ic-btn-pumponly:active{background:#14532d;}
.ic-running-badge{width:100%;padding:11px;border-radius:12px;border:2px solid #16a34a;background:#052e16;color:#4ade80;font-size:12px;font-weight:800;text-align:center;margin-bottom:6px;line-height:1.3;}
.ic-btn-stop{width:100%;padding:14px;border-radius:12px;border:none;background:#991b1b;color:#fff;font-size:15px;font-weight:800;cursor:pointer;box-shadow:0 4px 14px rgba(153,27,27,0.4);}
.ic-btn-stop:active{background:#7f1d1d;}
.ic-off-badge{text-align:center;font-size:11px;color:#475569;margin-top:8px;padding:4px;}
</style>
<div class='ic'>
  <div class='ic-title'>${name}</div>
  ${noBase}
  <div class='ic-gauge'>
    <svg viewBox='0 0 220 220'>
      <path d='${trackPath}' stroke='#1e3a5f' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${setptPath}' stroke='#1d3460' stroke-width='13' fill='none' stroke-linecap='round'/>
      <path d='${tempPath}'  stroke='#1d6fbf' stroke-width='13' fill='none' stroke-linecap='round'/>
      <circle cx='${dotX}' cy='${dotY}' r='7' fill='#38bdf8' stroke='#0f172a' stroke-width='3'/>
    </svg>
    <div class='ic-center'>
      <div class='ic-htmode'>${htmode}</div>
      <div class='ic-bigtemp'>${Math.round(temp)}</div>
      <div class='ic-unit'>°F current</div>
      <div class='ic-target'>Target ${Math.round(setpt)}°F</div>
    </div>
  </div>
  <div class='ic-row'>
    <div class='ic-box'><div class='ic-blbl'>Target</div><div class='ic-bval'>${Math.round(setpt)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Max</div><div class='ic-bval'>${Math.round(maxTemp)}°</div></div>
    <div class='ic-box'><div class='ic-blbl'>Pump</div><div class='ic-bval' style='color:${pumpClr};'>${isOn ? "On" : "Off"}</div></div>
  </div>
  <hr class='ic-div'>
  ${heatSectionHtml}
  <hr class='ic-div'>
  ${pumpSectionHtml}
</div>"""

    sendEvent(name: "tile", value: html, displayed: false)
}
