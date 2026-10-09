/**
 *  Moonraker / Klipper 3D Printer Driver for Hubitat
 *
 *  Author:  jdthomas24
 *  Version: 1.0.49
 *  Date:    2026-10-09
 *
 *  Copyright 2026
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Supports any Moonraker/Klipper installation:
 *
 *    SONICPAD (multiple printers, one device):
 *      Create one Hubitat device per printer, all pointing to the same IP but different ports:
 *        Printer 1 -> IP: 192.168.1.x  Port: 7125
 *        Printer 2 -> IP: 192.168.1.x  Port: 7127
 *        Printer 3 -> IP: 192.168.1.x  Port: 7128
 *
 *    STANDARD INSTALL (Raspberry Pi, BTT Pi, CB1, etc.):
 *      One Hubitat device per printer, each with its own IP:
 *        Printer 1 -> IP: 192.168.1.10  Port: 7125
 *        Printer 2 -> IP: 192.168.1.11  Port: 7125
 *        Printer 3 -> IP: 192.168.1.12  Port: 7125
 *
 *    AUTHENTICATION:
 *      Most local installs require no API key.
 *      If your Moonraker install requires one, enter it in the API Key preference.
 *      Find your API key in moonraker.conf or via http://<ip>:<port>/access/api_key
 *
 *  Thanks to:
 *    NonaSuomy - Moonraker-Home-Assistant
 *    marcolivierarsenault - moonraker-home-assistant
 *    Arksine - Moonraker
 *
 *  Changes in 1.0.49:
 *    - Dashboard tiles now display. Dashboards only show attribute values up to 1024 bytes,
 *      and the old tiles were ~3700. aaStatusTile and filesListTile are now compact tiles that
 *      stay under the limit (works on local and remote dashboards), scaling with tile width.
 *      Standby shows the last completed print. Files list shows as many recent prints as fit.
 *    - "Buy me a coffee" link at the top of Preferences, under the setup tip.
 *
 *  Changes in 1.0.48:
 *    - Consecutive failure threshold before marking offline (3 failures required)
 *      Transient timeouts and brief Moonraker restarts no longer immediately flip status
 *      Failure counter resets on any successful poll
 *    - Auto-reconnect: statusCallback now detects a successful 200 response while
 *      marked offline and calls getInfo() to properly restore online state without
 *      manual intervention. Hub fully self-heals after internet or Moonraker outage
 *    - Status tile now correctly reflects offline state. Tile rebuilds immediately
 *      when setOffline() fires showing offline badge instead of stuck "online"
 *    - Offline tile rebuilds with last known temps and state rather than blank values
 *
 *  Changes in 1.0.47:
 *    - healthStatus now only fires on transition (offline->online, online->offline)
 *    - Fixed double setOnline() per poll cycle
 *    - aaStatusTile fingerprint suppression (Option D)
 *    - filesListTile suppressed when content unchanged
 */
public static String version() { return "1.0.49" }

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(name: "Moonraker Klipper Printer", namespace: "Jdthomas24", author: "Jdthomas24") {
        capability "Refresh"
        capability "Initialize"
        capability "Actuator"
        capability "Sensor"
        capability "TemperatureMeasurement"

        command "pause"
        command "resume"
        command "cancel"
        command "emergencyStop"
        command "firmwareRestart"
        command "executeGcode", [[name: "gcode*", type: "STRING", description: "Raw GCode command to send to printer"]]
        command "startPrint",     [[name: "filename*", type: "STRING", description: "Enter the NUMBER from filesList (e.g. 1, 2, 3) or the full path (e.g. folder/myfile.gcode)"]]
        command "startLastPrint"

        attribute "aaStatusTile",          "string"
        attribute "filesListTile",         "string"
        attribute "printState",            "enum",   ["standby", "printing", "paused", "complete", "error", "cancelled"]
        attribute "filename",              "string"
        attribute "error",                 "string"
        attribute "currentLayer",          "number"
        attribute "totalLayers",           "number"
        attribute "chamberTemp (°C)",      "number"
        attribute "mcuTemp (°C)",          "number"
        attribute "filamentDetected",      "enum",   ["true", "false", "unknown"]
        attribute "lastPrint",             "string"
        attribute "filesList (1-10)",      "string"
        attribute "filesList (11-20)",     "string"
    }
}

preferences {
    input(name: "setupTip", type: "hidden", title: """<div style='background:#fff3cd;border:1px solid #ffc107;border-radius:4px;padding:8px;margin-bottom:8px;font-size:12px;'>
        <b>⚠️ Setup Tip:</b> Name this Hubitat device to match your printer <i>before</i> saving preferences, e.g. "3D - CR10 Pro 1".<br>
        The device name appears in <b>Printer Info</b> so you can confirm you have the correct port assigned to the right printer.<br>
        <b>Sonicpad users:</b> create one Hubitat device per printer, each with the same IP but a different port.
        </div>""")
    input(name: "supportLink", type: "hidden", title: """<div style='font-size:13px;color:#555;margin:-2px 0 8px;'>Enjoying this driver? <a href='https://www.paypal.com/paypalme/jdthomas24' target='_blank' rel='noopener'>Buy me a coffee &#9749;</a></div>""")
    input(name: "ipAddress",      type: "string",   title: "<b>Printer IP Address:</b>",
          description: "<i>IP address of your Moonraker host.<br>Sonicpad users: all printers share the same IP. Use Port to differentiate.</i>",
          required: true, width: 4)
    input(name: "port",           type: "string",   title: "<b>Moonraker Port:</b>",
          description: "<i>Default is 7125.<br><b>Sonicpad (4 USB ports):</b> Port 1=7125, Port 2=7126, Port 3=7127, Port 4=7128<br>Use the port matching your printer's USB port. Unconnected ports will show offline.<br><b>Standard install:</b> check your moonraker.conf for the configured port</i>",
          defaultValue: "7125", required: true, width: 4)
    input(name: "useSSL",         type: "bool",     title: "<b>Use HTTPS:</b>",
          description: "<i>Enable only if your Moonraker uses SSL. Most local installs do not.</i>",
          defaultValue: false, width: 4)
    input(name: "apiKey",         type: "string",   title: "<b>API Key (optional):</b>",
          description: "<i>Only required if your Moonraker install enforces API key authentication.<br>Find yours at http://&lt;ip&gt;:&lt;port&gt;/access/api_key</i>",
          required: false, width: 4)
    input(name: "pollInterval",   type: "enum",     title: "<b>Poll Interval:</b>",
          options: ["10": "10 Seconds", "30": "30 Seconds", "60": "1 Minute", "300": "5 Minutes"],
          defaultValue: "30", required: true, width: 4,
          description: "<i>Standby poll rate, how often to check when the printer is idle.<br>While printing or paused, the driver automatically switches to 30s regardless of this setting.</i>")
    input(name: "offlineThreshold", type: "number", title: "<b>Offline Failure Threshold:</b>",
          description: "<i>Number of consecutive failed polls before marking printer offline. Default 3. Increase to tolerate brief Moonraker restarts without flipping status.</i>",
          defaultValue: 3, required: true, width: 4)
    input(name: "recentFilesLimit", type: "number",  title: "<b>Recent Files Limit:</b> (Top 20 Recommended)",
          description: "<i>Max number of recent files to show in filesList. Sorted most recent first.</i>",
          defaultValue: 20, required: true, width: 4)
    input(name: "tempUnit",       type: "enum",     title: "<b>Temperature Unit:</b>",
          options: ["F": "Fahrenheit (°F)", "C": "Celsius (°C)"],
          defaultValue: "C", required: true, width: 4)
    input(name: "deviceDebugEnable",  type: "bool", title: "Enable Debug logging:", description: "<i>Auto-disables after 30 minutes.</i>", defaultValue: false, width: 4)
}

// ============================================================
//  LIFECYCLE
// ============================================================
def installed() {
    initialize()
}

def updated() {
    cleanStaleState()
    cleanStaleAttributes()
    cleanStaleTileFiles()
    initialize()
}

// Pre-release 1.0.49 builds saved tiles to File Manager; remove any left behind.
void cleanStaleTileFiles() {
    ["aaStatusTile", "aaStatusTileFull", "filesListTile", "filesListTileFull"].each { a ->
        try { deleteHubFile("moonraker-${device.id}-${a}.html") } catch (e) { }
    }
}

void cleanStaleState() {
    if (state?.moonrakerVersion != null) state.remove("moonrakerVersion")
}

void cleanStaleAttributes() {
    List stale = [
        "healthStatus", "printerInfo", "statusTile", "printerStatusTile", "message",
        "progress (%)", "printTime (min)", "printTimeLeft (min)", "printETA",
        "filamentUsed (mm)", "fanSpeed (%)", "printsCompleted",
        "hotendTemp (°C)", "hotendTarget (°C)", "bedTemp (°C)", "bedTarget (°C)", "hotendTemp (° C)", "hotendTarget (° C)", "bedTemp (° C)", "bedTarget (° C)",
        "hotendTemp (°F)", "hotendTarget (°F)", "bedTemp (°F)", "bedTarget (°F)",
        "temperature", "filesCount",
        "chamberTemp (°F)", "mcuTemp (°F)"
    ]
    device.currentStates?.collect{ ((new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(it)))?.name) }
        ?.each{ n -> if (stale.contains(n)) { device.deleteCurrentState(n); logInfo "removed stale attribute: $n" } }
}

def logsOff() {
    device.updateSetting("deviceDebugEnable", [value: "false", type: "bool"])
    logInfo "debug logging disabled automatically"
}

void autoLogsOff() {
    if (settings?.deviceDebugEnable) runIn(1800, "logsOff")
    else unschedule("logsOff")
}

def initialize() {
    unschedule()
    autoLogsOff()
    state.consecutiveFailures = 0
    logInfo "initializing Moonraker driver v${version()} at ${getBaseUrl()}"
    sendEvent(name: "healthStatus", value: "offline")
    state.remove("lastTileFingerprint")
    state.remove("lastFilesListHash")
    buildOfflineTile()
    getInfo()
    runIn(2, "discoverObjects")
    schedulePoll()
}

void schedulePoll() {
    unschedule("refresh")
    Integer interval = (settings?.pollInterval ?: "30").toInteger()
    if (interval == 10)       { schedule("0/10 * * * * ?", refresh) }
    else if (interval == 30)  { schedule("0/30 * * * * ?", refresh) }
    else if (interval == 60)  { runEvery1Minute(refresh) }
    else                      { runEvery5Minutes(refresh) }
}

void scheduleActivePoll() {
    unschedule("refresh")
    schedule("0/30 * * * * ?", refresh)
    logDebug "switched to active poll (30s)"
}

void scheduleStandbyPoll() {
    schedulePoll()
    logDebug "switched to standby poll"
}

// ============================================================
//  REFRESH / POLL
// ============================================================
def refresh() {
    logDebug "executing refresh()"
    getStatus()
}

// ============================================================
//  API CALLS
// ============================================================
String getBaseUrl() {
    String scheme = settings?.useSSL ? "https" : "http"
    return "${scheme}://${settings?.ipAddress}:${settings?.port}"
}

Map getHeaders() {
    Map headers = ["Content-Type": "application/json"]
    if (settings?.apiKey) headers["X-Api-Key"] = settings.apiKey
    return headers
}

void getInfo() {
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/info",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("infoCallback", params, [method: "getInfo"])
    } catch (e) {
        logWarn "getInfo() error: $e"
        recordFailure()
    }
}

void getStatus() {
    String queryString = [
        "print_stats",
        "virtual_sdcard",
        "extruder",
        "heater_bed",
        "display_status",
        "fan",
        "toolhead"
    ].join("&")

    if (state?.filamentSensorName) queryString += "&${URLEncoder.encode(state.filamentSensorName, 'UTF-8').replace('+','%20')}"
    if (state?.chamberSensorName)  queryString += "&${URLEncoder.encode(state.chamberSensorName,  'UTF-8').replace('+','%20')}"
    if (state?.mcuSensorName)      queryString += "&${URLEncoder.encode(state.mcuSensorName,       'UTF-8').replace('+','%20')}"

    Map params = [
        uri:     "${getBaseUrl()}/printer/objects/query?${queryString}",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("statusCallback", params, [method: "getStatus"])
    } catch (e) {
        logWarn "getStatus() error: $e"
        recordFailure()
    }
}

void discoverObjects() {
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/objects/list",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("discoverCallback", params, [method: "discoverObjects"])
    } catch (e) {
        logWarn "discoverObjects() error: $e"
    }
    runIn(3, "refreshFileList")
    Map serverParams = [
        uri:     getBaseUrl(),
        path:    "/server/info",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("serverInfoCallback", serverParams, [method: "serverInfo"])
    } catch (e) {
        logWarn "serverInfo() error: $e"
    }
}

void serverInfoCallback(resp, data) {
    logDebug "serverInfoCallback() status:${resp.status}"
    if (resp.status != 200) return
    try {
        Map json   = new JsonSlurper().parseText(resp.data)
        Map result = json?.result ?: [:]
        String version = result?.moonraker_version ?: result?.api_version_string ?: ""
        if (version && version != "?") { logDebug "moonraker version: $version" }
        else { logDebug "moonraker version unavailable (Sonicpad returns ?, this is normal)" }
    } catch (e) {
        logWarn "serverInfoCallback() parse error: $e"
    }
}

void discoverCallback(resp, data) {
    logDebug "discoverCallback() status:${resp.status}"
    if (resp.status != 200) return
    try {
        Map json = new JsonSlurper().parseText(resp.data)
        List objects = json?.result?.objects ?: []
        String filament = objects.find{ it.startsWith("filament_switch_sensor") }
        if (filament) { state.filamentSensorName = filament; logDebug "found filament sensor: $filament" }
        String chamber = objects.find{ it.startsWith("temperature_sensor") && it.toLowerCase().contains("chamber") }
        if (chamber) { state.chamberSensorName = chamber; logDebug "found chamber sensor: $chamber" }
        String mcu = objects.find{ it.startsWith("temperature_sensor") && (it.toLowerCase().contains("mcu") || it.toLowerCase().contains("rpi") || it.toLowerCase().contains("host")) }
        if (mcu) { state.mcuSensorName = mcu; logDebug "found MCU sensor: $mcu" }
    } catch (e) {
        logWarn "discoverCallback() parse error: $e"
    }
}

// ============================================================
//  FAILURE TRACKING
// ============================================================
void recordFailure() {
    Integer failures  = (state.consecutiveFailures ?: 0) + 1
    Integer threshold = (settings?.offlineThreshold ?: 3).toInteger()
    state.consecutiveFailures = failures
    logDebug "consecutive failures: ${failures}/${threshold}"
    if (failures >= threshold) {
        setOffline()
    }
}

void recordSuccess() {
    if ((state.consecutiveFailures ?: 0) > 0) {
        logDebug "consecutive failures reset, poll succeeded"
    }
    state.consecutiveFailures = 0
}

// ============================================================
//  CALLBACKS
// ============================================================
void infoCallback(resp, data) {
    logDebug "infoCallback() status:${resp.status}"
    if (resp.status == 200) {
        recordSuccess()
        try {
            Map json = new JsonSlurper().parseText(resp.data)
            Map result = json?.result ?: [:]
            String klipperState = result?.state ?: "disconnected"
            String prevKlipperState = device.currentValue("klipperState") ?: ""
            if (prevKlipperState != klipperState) {
                if (klipperState == "ready") logInfo "klipper state is ready"
                else logWarn "klipper state changed to: $klipperState"
            }
            sendEventX(name: "klipperState", value: klipperState)
            state.lastKlipperState = klipperState
            String hostname = result?.hostname ?: "unknown"
            state.hostname  = hostname
            if (klipperState == "ready") {
                setOnline()
                getStatus()
            } else {
                setOffline()
            }
        } catch (e) {
            logWarn "infoCallback() parse error: $e"
            recordFailure()
        }
    } else {
        logWarn "infoCallback() HTTP ${resp.status}: ${resp?.errorMessage}"
        recordFailure()
    }
}

void statusCallback(resp, data) {
    logDebug "statusCallback() status:${resp.status}"
    if (resp.status != 200) {
        logWarn "statusCallback() HTTP ${resp.status}: ${resp?.errorMessage}"
        recordFailure()
        return
    }

    recordSuccess()

    // v1.0.48: auto-reconnect. If we get a successful response while marked offline,
    // call getInfo() to properly restore online state without manual intervention
    if (device.currentValue("healthStatus") == "offline") {
        logInfo "printer responding again, re-establishing online state"
        getInfo()
        return
    }

    try {
        Map json    = new JsonSlurper().parseText(resp.data)
        Map status  = json?.result?.status ?: [:]

        Map printStats = status?.print_stats ?: [:]
        String printState = printStats?.state ?: "standby"
        String prevPrintState = device.currentValue("printState") ?: ""
        Boolean stateChanged = (prevPrintState != printState)

        if (stateChanged) {
            if (printState == "complete") {
                logInfo "*** PRINT COMPLETE: ${device.currentValue('filename')} ***"
                runIn(5, "refreshFileList")
                scheduleStandbyPoll()
            } else if (printState == "error") {
                logWarn "*** PRINT ERROR on ${device.displayName} ***"
                scheduleStandbyPoll()
            } else if (printState == "printing") {
                logInfo "print started: ${printStats?.filename ?: 'unknown'}"
                runIn(5, "refreshFileList")
                scheduleActivePoll()
            } else if (printState == "paused") {
                logInfo "print paused"
                scheduleActivePoll()
            } else if (printState == "cancelled") {
                logInfo "print cancelled"
                scheduleStandbyPoll()
            }
        }
        sendEventX(name: "printState", value: printState,
                   logLevel: (printState == "error" ? "warn" : null))

        String filename = printStats?.filename ?: ""
        String filenameDisplay = filename ? (filename.contains("/") ? filename.tokenize("/").last() : filename) : "none"
        String filenameClean   = filenameDisplay.replaceAll(/(?i)\.gcode$/, "")
        sendEventX(name: "filename", value: (filenameClean ?: "none"))

        Integer printTimeSec = printStats?.print_duration?.toInteger() ?: 0
        Integer printTimeMin = Math.ceil(printTimeSec / 60).toInteger()
        Double filamentUsed  = printStats?.filament_used ?: 0.0

        Integer currentLayer = printStats?.info?.current_layer ?: 0
        Integer totalLayers  = printStats?.info?.total_layer   ?: 0
        if (currentLayer > 0) sendEventX(name: "currentLayer", value: currentLayer)
        if (totalLayers  > 0) sendEventX(name: "totalLayers",  value: totalLayers)

        String errorMsg = printStats?.message ?: ""
        if (errorMsg) sendEventX(name: "error", value: errorMsg, descriptionText: "printer error: $errorMsg", logLevel: "warn")
        else          sendEventX(name: "error", value: "none")

        Map vsd = status?.virtual_sdcard ?: [:]
        Double progress    = ((vsd?.progress ?: 0.0) * 100)
        Integer progressInt = Math.round(progress).toInteger()

        Map extruder = status?.extruder   ?: [:]
        Map bed      = status?.heater_bed ?: [:]
        Map fan      = status?.fan        ?: [:]
        Integer fanPct = fan?.speed != null ? Math.round((fan.speed.toDouble()) * 100).toInteger() : 0

        Double hotendC     = extruder?.temperature ?: 0.0
        Double hotendTargC = extruder?.target      ?: 0.0
        Double bedC        = bed?.temperature      ?: 0.0
        Double bedTargC    = bed?.target           ?: 0.0
        Double hotendVal   = convertTemp(hotendC).round(1)
        Double hotendTarg  = convertTemp(hotendTargC).round(1)
        Double bedVal      = convertTemp(bedC).round(1)
        Double bedTarg     = convertTemp(bedTargC).round(1)

        // Store last known temps for offline tile
        state.lastHotendVal  = hotendVal
        state.lastHotendTarg = hotendTarg
        state.lastBedVal     = bedVal
        state.lastBedTarg    = bedTarg

        Map filament = status?.find{ it.key?.startsWith("filament_switch_sensor") }?.value ?: [:]
        String filamentDetected = "unknown"
        if (filament?.filament_detected != null) {
            filamentDetected = filament.filament_detected ? "true" : "false"
            String prevDetected = device.currentValue("filamentDetected") ?: "unknown"
            if (filamentDetected == "false" && prevDetected != "false" && printState == "printing") {
                logWarn "*** FILAMENT RUNOUT on ${device.displayName} ***"
            }
        }
        sendEventX(name: "filamentDetected", value: filamentDetected)

        Map chamber = status?.find{ it.key?.startsWith("temperature_sensor") && it.key?.contains("chamber") }?.value ?: [:]
        if (chamber?.temperature != null) sendEventX(name: "chamberTemp (°C)", value: convertTemp(chamber.temperature.toDouble()).round(1))
        Map mcu = status?.find{ it.key?.startsWith("temperature_sensor") && it.key?.contains("mcu") }?.value ?: [:]
        if (mcu?.temperature != null) sendEventX(name: "mcuTemp (°C)", value: convertTemp(mcu.temperature.toDouble()).round(1))

        Map display = status?.display_status ?: [:]
        String msg  = display?.message ?: ""

        String etaNow = "--"
        Integer remainingNow = 0
        if (progressInt > 0 && progressInt < 100 && printTimeSec > 0) {
            Integer totalEstSec  = (printTimeSec / (progress / 100)).toInteger()
            Integer remainingSec = totalEstSec - printTimeSec
            remainingNow = Math.ceil(remainingSec / 60).toInteger()
            Long etaMillis = now() + (remainingSec * 1000L)
            etaNow = new Date(etaMillis).format("h:mm a", location.timeZone)
        } else if (progressInt == 100) {
            etaNow = "complete"
        }

        Integer progressRounded  = progressInt
        Integer hotendRounded    = hotendVal.toInteger()
        Integer bedRounded       = bedVal.toInteger()
        String tileFingerprint   = "${printState}|${filenameClean}|${progressRounded}|${hotendRounded}|${bedRounded}"
        String lastFingerprint   = state?.lastTileFingerprint ?: ""
        Boolean fingerprintChanged = (tileFingerprint != lastFingerprint)

        if (stateChanged || fingerprintChanged) {
            state.lastTileFingerprint = tileFingerprint
            buildOnlineTile(printState, filenameClean, progressInt, progressRounded,
                            hotendVal, hotendTarg, bedVal, bedTarg,
                            fanPct, filamentDetected, filamentUsed,
                            printTimeMin, remainingNow, etaNow)
            logDebug "tile updated, fingerprint: ${tileFingerprint}"
        } else {
            logDebug "tile suppressed, fingerprint unchanged: ${tileFingerprint}"
        }

    } catch (e) {
        logWarn "statusCallback() parse error: $e"
    }
}

// ============================================================
//  TILE BUILDERS
// ============================================================
void buildOnlineTile(String printState, String filenameClean, Integer progressInt,
                     Integer progressRounded, Double hotendVal, Double hotendTarg,
                     Double bedVal, Double bedTarg, Integer fanPct,
                     String filamentDetected, Double filamentUsed,
                     Integer printTimeMin, Integer remainingNow, String etaNow) {
    boolean ready = (state?.lastKlipperState ?: device.currentValue("klipperState")) == "ready"
    String errorVal = device.currentValue("error") ?: "none"
    Map m = [state: printState, file: filenameClean, progress: progressRounded, hotend: hotendVal, hotendTarg: hotendTarg,
             bed: bedVal, bedTarg: bedTarg, elapsed: printTimeMin, remaining: remainingNow, eta: etaNow,
             filament: filamentDetected, ready: ready, error: errorVal]
    String html = compactStatusTile(m, 26, 40)
    if (html.getBytes("UTF-8").length > 1024) html = compactStatusTile(m, 12, 0)
    sendCompact("aaStatusTile", html)
}

void buildOfflineTile() {
    String hotendStr = state?.lastHotendVal != null ? "${state.lastHotendVal}&deg;" : "--"
    String bedStr    = state?.lastBedVal    != null ? "${state.lastBedVal}&deg;"    : "--"
    sendCompact("aaStatusTile", compactWrap(
        "<div><b style='color:#00b4d8'>${shorten(device.displayName, 28)}</b> <span style='color:#ff6b6b'>&#9679;</span></div>" +
        "<div style='color:#ff6b6b'>&#9888; Offline, reconnecting</div>" +
        "<div style='color:#888'>Last known: Hotend ${hotendStr} &middot; Bed ${bedStr}</div>"))
    logDebug "offline tile built"
}

// ============================================================
//  DASHBOARD TILES
// ============================================================
// Dashboards only show attribute values up to 1024 bytes, so tiles are kept compact and inline.
// Outer div is the size container; inner text scales with it (cqw is relative to the parent container).
String compactWrap(String inner) {
    return "<div style='height:100%;box-sizing:border-box;padding:4%;container-type:inline-size;background:#1a1a2e;border-radius:10px'>" +
        "<div style='height:100%;font:4.6cqw/1.35 sans-serif;color:#ddd;text-align:left;text-shadow:none;" +
        "display:flex;flex-direction:column;justify-content:space-around'>${inner}</div></div>"
}

String shorten(String text, int max) {
    if (!text || max <= 0) return ""
    return text.length() > max ? text.substring(0, max - 1) + "&hellip;" : text
}

// fileMax/errorMax shrink on a second pass if the tile would pass 1024 bytes.
String compactStatusTile(Map m, int fileMax, int errorMax) {
    String st = m.state
    String stateCol = st == "printing" ? "#00ff87" : st == "paused" ? "#ffd166" : st == "error" ? "#ff6b6b" : "#aaa"
    String icon = st == "printing" ? "&#9654; " : st == "paused" ? "&#9646;&#9646; " : ""
    boolean active = st in ["printing", "paused"]
    StringBuilder t = new StringBuilder()
    t.append("<div><b style='color:#00b4d8'>${shorten(device.displayName, 28)}</b> <span style='color:${m.ready ? '#00ff87' : '#ffd166'}'>&#9679;</span></div>")
    t.append("<div style='color:${stateCol};white-space:nowrap;overflow:hidden;text-overflow:ellipsis'>${icon}${st}")
    if (active && m.file && m.file != "none") t.append(" &middot; <span style='color:#eee'>${shorten(m.file, fileMax)}</span>")
    t.append("</div>")
    t.append("<div>Hotend <b style='color:#ff6b6b'>${m.hotend}&deg;</b>/${(m.hotendTarg as Double).toInteger()} &middot; " +
             "Bed <b style='color:#ffa94d'>${m.bed}&deg;</b>/${(m.bedTarg as Double).toInteger()}</div>")
    if (active) {
        t.append("<div style='background:#0f3460;height:.4em;border-radius:1em'><div style='background:#00b4d8;width:${m.progress}%;height:100%;border-radius:1em'></div></div>")
        t.append("<div>${m.progress}% &middot; ${m.elapsed}m &middot; <span style='color:#00ff87'>${m.remaining}m left</span> &middot; ${m.eta}</div>")
    } else {
        String last = device.currentValue("lastPrint") ?: ""
        if (last && last != "none") t.append("<div style='color:#888'>Last: <span style='color:#ddd'>${shorten(last, fileMax)}</span></div>")
    }
    if (m.filament == "false") t.append("<div style='color:#ff6b6b'>&#9888; Filament runout</div>")
    if (errorMax > 0 && m.error && m.error != "none") t.append("<div style='color:#ff6b6b'>&#9888; ${shorten(m.error, errorMax)}</div>")
    return compactWrap(t.toString())
}

// Adds rows until the next one would pass the limit.
String compactFilesTile(List files, Integer total) {
    String head = "<div style='color:#00b4d8;font-weight:bold'>Recent prints <span style='color:#888;font-weight:normal'>(${total} jobs)</span></div>"
    StringBuilder rows = new StringBuilder()
    for (int i = 0; i < files.size(); i++) {
        Map f = files[i]
        String row = "<div><span style='color:#00b4d8'>${i + 1}</span> ${shorten(f.name, 30)}${f.status == 'in_progress' ? ' &#9654;' : ''}</div>"
        if (compactWrap(head + rows + row).getBytes("UTF-8").length > 1000) break
        rows.append(row)
    }
    return compactWrap(head + rows)
}

void sendCompact(String attr, String html) {
    int size = html.getBytes("UTF-8").length
    if (size > 1024) logWarn "${attr} is ${size} bytes, over the dashboard limit"
    sendEventX(name: attr, value: html)
}

// ============================================================
//  COMMANDS
// ============================================================
def pause() {
    logInfo "executing pause()"
    postGcode("PAUSE")
}

def resume() {
    logInfo "executing resume()"
    postGcode("RESUME")
}

def cancel() {
    logInfo "executing cancel()"
    postCommand("/printer/print/cancel")
}

def emergencyStop() {
    logWarn "executing emergencyStop()"
    postCommand("/printer/emergency_stop")
}

def firmwareRestart() {
    logInfo "executing firmwareRestart()"
    postCommand("/printer/firmware_restart")
}

def executeGcode(String gcode) {
    logInfo "executing gcode: $gcode"
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/gcode/script",
        headers: getHeaders(),
        body:    [script: gcode],
        timeout: 10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: "executeGcode", gcode: gcode])
    } catch (e) {
        logWarn "executeGcode() error: $e"
    }
}

void postCommand(String path) {
    Map params = [
        uri:     getBaseUrl(),
        path:    path,
        headers: getHeaders(),
        body:    [:],
        timeout: 10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: path])
    } catch (e) {
        logWarn "postCommand($path) error: $e"
    }
}

void postGcode(String gcode) {
    Map params = [
        uri:     getBaseUrl(),
        path:    "/printer/gcode/script",
        headers: getHeaders(),
        body:    [script: gcode],
        timeout: 10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: "postGcode", gcode: gcode])
    } catch (e) {
        logWarn "postGcode() error: $e"
    }
}

void commandCallback(resp, data) {
    logDebug "commandCallback() method:${data?.method} status:${resp.status}"
    if (resp.status == 200) {
        logInfo "command '${data?.method}' accepted"
        runIn(2, "refresh")
    } else {
        logWarn "command '${data?.method}' failed: HTTP ${resp.status} ${resp?.errorMessage}"
    }
}

// ============================================================
//  HELPERS
// ============================================================
void setOnline() {
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online")
        logInfo "printer is online"
        // Clear fingerprint so tile rebuilds immediately on next poll
        state.remove("lastTileFingerprint")
    }
}

void setOffline() {
    if (device.currentValue("healthStatus") != "offline") {
        sendEvent(name: "healthStatus", value: "offline")
        logWarn "printer is offline"
        sendEventX(name: "klipperState", value: "disconnected")
        state.remove("lastTileFingerprint")
        buildOfflineTile()
    }
}

Double convertTemp(Double celsius) {
    if (settings?.tempUnit == "C") return celsius
    return (celsius * 9/5) + 32
}

void sendEventX(Map x) {
    if (x?.value != null && (device.currentValue(x?.name)?.toString() != x?.value?.toString() || x?.isStateChange)) {
        if (x?.logLevel == "warn" && x?.descriptionText)       logWarn(x.descriptionText)
        else if (x?.logLevel == "info" && x?.descriptionText)  logInfo(x.descriptionText)
        sendEvent(name: x.name, value: x.value, unit: x?.unit, descriptionText: x?.descriptionText, isStateChange: (x?.isStateChange ?: false))
    }
}

// ============================================================
//  FILE MANAGEMENT
// ============================================================
void refreshFileList() {
    logDebug "refreshing file list from print history"
    Integer limit = (settings?.recentFilesLimit ?: 20).toInteger()
    Map params = [
        uri:     "${getBaseUrl()}/server/history/list?limit=100&order=desc",
        headers: getHeaders(),
        timeout: 10
    ]
    try {
        asynchttpGet("filesCallback", params, [method: "refreshFileList"])
    } catch (e) {
        logWarn "refreshFileList() error: $e"
    }
}

void filesCallback(resp, data) {
    logDebug "filesCallback() status:${resp.status}"
    if (resp.status != 200) {
        logWarn "filesCallback() HTTP ${resp.status}: ${resp?.errorMessage}"
        return
    }
    try {
        Map json  = new JsonSlurper().parseText(resp.data)
        List jobs = json?.result?.jobs ?: []
        Integer limit     = (settings?.recentFilesLimit ?: 20).toInteger()
        Integer totalJobs = (json?.result?.count ?: jobs.size()).toInteger()

        List seen       = []
        List inProgress = []
        List recentUnique = []

        jobs.each { job ->
            String fname = job?.filename ?: ""
            if (!fname) return
            if (job?.status == "in_progress") {
                if (!seen.contains(fname)) { inProgress << job; seen << fname }
            } else if (!seen.contains(fname)) {
                recentUnique << job
                seen << fname
            }
        }

        List allFiles    = (inProgress + recentUnique).take(limit)
        Integer totalCount = seen.size()
        List recentFiles = allFiles

        Map fileMap = [:]
        List displayList = []
        recentFiles.eachWithIndex { file, idx ->
            String fullPath  = file?.path ?: file?.filename ?: ""
            String number    = (idx + 1).toString()
            fileMap[number]  = fullPath
            String name      = fullPath.contains("/") ? fullPath.tokenize("/").last() : fullPath
            String cleanName = name.replaceAll(/(?i)\.gcode$/, "")
            displayList << "${number}: ${cleanName}"
        }
        state.filePathCache = groovy.json.JsonOutput.toJson(fileMap)
        state.remove("fileMap")

        List recentFilesForTile = allFiles.collect { job ->
            String fullPath  = job?.filename ?: ""
            String name      = fullPath.contains("/") ? fullPath.tokenize("/").last() : fullPath
            String folder    = fullPath.contains("/") ? fullPath.tokenize("/").dropRight(1).join("/") : ""
            String cleanName = name.replaceAll(/(?i)\.gcode$/, "")
            [path: fullPath, name: cleanName, folder: folder, status: job?.status ?: ""]
        }

        List firstTen  = displayList.size() >= 10 ? displayList[0..9]   : displayList
        List secondTen = displayList.size() > 10  ? displayList[10..-1] : []

        Integer completedCount = jobs.count{ it?.status == "completed" }.toInteger()
        state.completedCount   = completedCount
        sendEventX(name: "printsCompleted", value: completedCount)

        String lastPrintFile = jobs.find{ it?.status == "completed" }?.filename ?: ""
        if (lastPrintFile) {
            String lastPrintName  = lastPrintFile.contains("/") ? lastPrintFile.tokenize("/").last() : lastPrintFile
            String lastPrintClean = lastPrintName.replaceAll(/(?i)\.gcode$/, "")
            sendEventX(name: "lastPrint", value: lastPrintClean)
        }

        sendEventX(name: "filesList (1-10)",  value: (firstTen.join(" | ")  ?: "none"))
        sendEventX(name: "filesList (11-20)", value: (secondTen ? secondTen.join(" | ") : "none"))

        Integer totalJobCount = totalJobs ?: totalCount
        String newHtml  = compactFilesTile(recentFilesForTile, totalJobCount)
        String newHash  = newHtml.hashCode().toString()
        if (newHash != (state?.lastFilesListHash ?: "")) {
            state.lastFilesListHash = newHash
            sendCompact("filesListTile", newHtml)
            logDebug "filesListTile updated, ${displayList.size()} files"
        } else {
            logDebug "filesListTile suppressed, content unchanged"
        }

    } catch (e) {
        logWarn "filesCallback() parse error: $e"
    }
}

def startPrint(String filename) {
    if (!filename) { logWarn "startPrint() no filename provided"; return }
    if (device.currentValue("printState") == "printing") {
        logWarn "startPrint() rejected, printer is already printing"
        return
    }
    String resolvedFile = filename.trim()
    if (resolvedFile.isInteger()) {
        Map _fmMap = state?.filePathCache ? (new groovy.json.JsonSlurper().parseText(state.filePathCache)) : [:]
        String mapped = _fmMap?.get(resolvedFile)
        if (mapped) {
            logInfo "startPrint() resolved #${resolvedFile} -> ${mapped}"
            resolvedFile = mapped
        } else {
            logWarn "startPrint() no file found for number ${resolvedFile}, run refreshFileList first"
            return
        }
    }
    logInfo "starting print: $resolvedFile"
    Map params = [
        uri:         getBaseUrl(),
        path:        "/printer/print/start",
        headers:     getHeaders() + ["Content-Type": "application/json"],
        body:        groovy.json.JsonOutput.toJson([filename: resolvedFile]),
        contentType: "application/json",
        timeout:     10
    ]
    try {
        asynchttpPost("commandCallback", params, [method: "startPrint", filename: resolvedFile])
    } catch (e) {
        logWarn "startPrint() error: $e"
    }
}

def startLastPrint() {
    Map _fmMap = state?.filePathCache ? (new groovy.json.JsonSlurper().parseText(state.filePathCache)) : [:]
    String lastFile = _fmMap?.get("1") ?: device.currentValue("filename") ?: ""
    if (!lastFile || lastFile == "none") {
        logWarn "startLastPrint() no previous filename found"
        return
    }
    logInfo "re-printing last file: $lastFile"
    startPrint(lastFile)
}

// ============================================================
//  LOG HELPERS
// ============================================================
def logInfo(msg)  { log.info  "${device.displayName} ${msg}" }
def logDebug(msg) { if (deviceDebugEnable) log.debug "${device.displayName} ${msg}" }
def logWarn(msg)  { log.warn  "${device.displayName} ${msg}" }
def logError(msg) { log.error "${device.displayName} ${msg}" }
