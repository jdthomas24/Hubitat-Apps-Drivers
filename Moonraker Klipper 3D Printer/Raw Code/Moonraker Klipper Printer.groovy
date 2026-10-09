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
 *    - Dashboard tiles now display. Hubitat dashboards only show attribute values up to
 *      1024 characters, and the status tile is ~3700. Each tile is saved to the hub's
 *      File Manager (moonraker-<device id>-<tile>.html) and the attribute holds a short
 *      iframe to it. Falls back to inline HTML if the file can't be written. Files are
 *      removed when the device is deleted.
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

def uninstalled() {
    ["aaStatusTile", "filesListTile"].each { attr ->
        try { deleteHubFile(tileFileName(attr)) } catch (e) { }
    }
}

def updated() {
    cleanStaleState()
    cleanStaleAttributes()
    initialize()
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

    String klipperStateNow = state?.lastKlipperState ?: device.currentValue("klipperState") ?: "unknown"
    String printerName     = device.displayName
    String hostname        = state?.hostname ?: ""
    String port            = settings?.port ?: ""
    String filenameNow     = filenameClean ?: "none"

    String stateBg    = printState == "printing" ? "#1b4332" : printState == "paused" ? "#3d2b00" : printState == "error" ? "#3d0000" : "#16213e"
    String stateColor = printState == "printing" ? "#00ff87" : printState == "paused" ? "#ffd166" : printState == "error" ? "#ff6b6b" : "#aaa"
    String stateIcon  = printState == "printing" ? "&#9654; " : printState == "paused" ? "&#9646;&#9646; " : printState == "error" ? "&#9888; " : ""
    String klipperBg  = klipperStateNow == "ready" ? "#16213e" : "#3d0000"
    String klipperCol = klipperStateNow == "ready" ? "#00b4d8" : "#ff6b6b"

    StringBuilder tile = new StringBuilder()
    tile.append("<div style=\"font-family:sans-serif;font-size:12px;background:#1a1a2e;border-radius:10px;overflow:hidden;\">")

    tile.append("<div style=\"background:#0f3460;padding:8px 12px;display:flex;align-items:center;justify-content:space-between;\">")
    tile.append("<div><div style=\"color:#00b4d8;font-weight:bold;font-size:13px;\">${printerName}</div>")
    if (hostname) tile.append("<div style=\"color:#aaa;font-size:10px;\">${hostname} &middot; port ${port}</div>")
    tile.append("</div>")
    tile.append("<div style=\"display:flex;gap:6px;align-items:center;\">")
    tile.append("<span style=\"background:#1b4332;color:#00ff87;font-size:10px;padding:2px 8px;border-radius:10px;\">&#9679; online</span>")
    tile.append("<span style=\"background:${klipperBg};color:${klipperCol};font-size:10px;padding:2px 8px;border-radius:10px;\">klipper: ${klipperStateNow}</span>")
    tile.append("</div></div>")

    tile.append("<div style=\"background:#16213e;border-bottom:1px solid #0f3460;padding:6px 12px;display:flex;align-items:center;gap:8px;\">")
    tile.append("<span style=\"background:${stateBg};color:${stateColor};font-size:10px;padding:2px 10px;border-radius:10px;font-weight:bold;\">${stateIcon}${printState}</span>")
    if (filenameNow != "none") tile.append("<span style=\"color:#eee;font-size:11px;font-weight:bold;\">${filenameNow}</span>")
    tile.append("</div>")

    String errorVal = device.currentValue("error") ?: "none"
    if (errorVal && errorVal != "none") {
        tile.append("<div style=\"background:#3d0000;border-left:3px solid #ff6b6b;padding:6px 12px;\">")
        tile.append("<span style=\"color:#ffaaaa;font-size:11px;\">&#9888; ${errorVal}</span>")
        tile.append("</div>")
    }

    tile.append("<div style=\"padding:8px 12px;\">")
    tile.append("<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-bottom:8px;\">")
    tile.append("<div style=\"background:#0f3460;border-radius:6px;padding:6px 10px;\">")
    tile.append("<div style=\"color:#aaa;font-size:9px;margin-bottom:2px;\">HOTEND</div>")
    tile.append("<div style=\"display:flex;align-items:baseline;gap:4px;\"><span style=\"color:#ff6b6b;font-size:16px;font-weight:bold;\">${hotendVal}°</span>")
    tile.append("<span style=\"color:#888;font-size:10px;\">/ ${hotendTarg}° target</span></div></div>")
    tile.append("<div style=\"background:#0f3460;border-radius:6px;padding:6px 10px;\">")
    tile.append("<div style=\"color:#aaa;font-size:9px;margin-bottom:2px;\">BED</div>")
    tile.append("<div style=\"display:flex;align-items:baseline;gap:4px;\"><span style=\"color:#ffa94d;font-size:16px;font-weight:bold;\">${bedVal}°</span>")
    tile.append("<span style=\"color:#888;font-size:10px;\">/ ${bedTarg}° target</span></div></div>")
    tile.append("</div>")

    if (printState in ["printing", "paused"]) {
        tile.append("<div style=\"margin-bottom:6px;\">")
        tile.append("<div style=\"display:flex;justify-content:space-between;margin-bottom:3px;\"><span style=\"color:#aaa;font-size:10px;\">progress</span><span style=\"color:#eee;font-size:10px;font-weight:bold;\">${progressRounded}%</span></div>")
        tile.append("<div style=\"background:#0f3460;border-radius:3px;height:5px;\"><div style=\"background:#00b4d8;width:${progressRounded}%;height:5px;border-radius:3px;\"></div></div></div>")

        tile.append("<div style=\"display:grid;grid-template-columns:1fr 1fr 1fr;gap:4px;margin-bottom:8px;\">")
        tile.append("<div style=\"background:#0f3460;border-radius:5px;padding:5px 8px;text-align:center;\"><div style=\"color:#aaa;font-size:9px;\">elapsed</div><div style=\"color:#eee;font-size:11px;font-weight:bold;\">${printTimeMin} min</div></div>")
        tile.append("<div style=\"background:#0f3460;border-radius:5px;padding:5px 8px;text-align:center;\"><div style=\"color:#aaa;font-size:9px;\">remaining</div><div style=\"color:#00ff87;font-size:11px;font-weight:bold;\">${remainingNow} min</div></div>")
        tile.append("<div style=\"background:#0f3460;border-radius:5px;padding:5px 8px;text-align:center;\"><div style=\"color:#aaa;font-size:9px;\">ETA</div><div style=\"color:#eee;font-size:11px;font-weight:bold;\">${etaNow}</div></div>")
        tile.append("</div>")
    }

    String filamentStatus = filamentDetected == "true" ? "OK" : filamentDetected == "false" ? "RUNOUT!" : "unknown"
    String filamentCol    = filamentDetected == "false" ? "#ff6b6b" : "#888"
    Integer filamentInt   = filamentUsed.toInteger()
    tile.append("<div style=\"border-top:1px solid #0f3460;padding:5px 12px;display:flex;justify-content:space-between;align-items:center;margin-top:2px;\">")
    tile.append("<span style=\"color:#888;font-size:9px;\">fan: ${fanPct}% &nbsp;&middot;&nbsp; filament: <span style=\"color:${filamentCol}\">${filamentStatus}</span> &nbsp;&middot;&nbsp; ${filamentInt}mm used</span>")
    tile.append("<span style=\"color:#888;font-size:9px;\">")
    Integer completedCount = state?.completedCount ?: 0
    if (completedCount > 0) tile.append("${completedCount} prints done")
    tile.append("</span>")
    tile.append("</div></div></div>")

    publishTile("aaStatusTile", tile.toString())
}

void buildOfflineTile() {
    String printerName = device.displayName
    String hostname    = state?.hostname ?: ""
    String port        = settings?.port ?: ""

    // Use last known temps if available, otherwise show --
    String hotendStr = state?.lastHotendVal != null ? "${state.lastHotendVal}°" : "--"
    String bedStr    = state?.lastBedVal    != null ? "${state.lastBedVal}°"    : "--"

    StringBuilder tile = new StringBuilder()
    tile.append("<div style=\"font-family:sans-serif;font-size:12px;background:#1a1a2e;border-radius:10px;overflow:hidden;\">")

    // Header, offline badge
    tile.append("<div style=\"background:#0f3460;padding:8px 12px;display:flex;align-items:center;justify-content:space-between;\">")
    tile.append("<div><div style=\"color:#00b4d8;font-weight:bold;font-size:13px;\">${printerName}</div>")
    if (hostname) tile.append("<div style=\"color:#aaa;font-size:10px;\">${hostname} &middot; port ${port}</div>")
    tile.append("</div>")
    tile.append("<div style=\"display:flex;gap:6px;align-items:center;\">")
    tile.append("<span style=\"background:#3d0000;color:#ff6b6b;font-size:10px;padding:2px 8px;border-radius:10px;\">&#9679; offline</span>")
    tile.append("</div></div>")

    // Offline banner
    tile.append("<div style=\"background:#3d0000;border-left:3px solid #ff6b6b;padding:8px 12px;\">")
    tile.append("<span style=\"color:#ffaaaa;font-size:11px;\">&#9888; Printer is offline, attempting to reconnect</span>")
    tile.append("</div>")

    // Last known temps
    tile.append("<div style=\"padding:8px 12px;\">")
    tile.append("<div style=\"display:grid;grid-template-columns:1fr 1fr;gap:6px;margin-bottom:8px;\">")
    tile.append("<div style=\"background:#0f3460;border-radius:6px;padding:6px 10px;\">")
    tile.append("<div style=\"color:#aaa;font-size:9px;margin-bottom:2px;\">HOTEND (last known)</div>")
    tile.append("<div style=\"color:#888;font-size:16px;font-weight:bold;\">${hotendStr}</div></div>")
    tile.append("<div style=\"background:#0f3460;border-radius:6px;padding:6px 10px;\">")
    tile.append("<div style=\"color:#aaa;font-size:9px;margin-bottom:2px;\">BED (last known)</div>")
    tile.append("<div style=\"color:#888;font-size:16px;font-weight:bold;\">${bedStr}</div></div>")
    tile.append("</div></div></div>")

    publishTile("aaStatusTile", tile.toString())
    logDebug "offline tile built"
}

// ============================================================
//  DASHBOARD TILES
// ============================================================
String tileFileName(String attr) { return "moonraker-${device.id}-${attr}.html" }

// Dashboards cap attribute values at 1024 chars, so the full tile lives in a hub file
// and the attribute is a short iframe. The ?t= value makes dashboards reload the frame.
void publishTile(String attr, String html) {
    String fname = tileFileName(attr)
    String page = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<style>html,body{margin:0;padding:0;background:transparent;}</style></head><body>${html}</body></html>"
    try {
        uploadHubFile(fname, page.getBytes("UTF-8"))
        sendEvent(name: attr, value: "<iframe src='/local/${fname}?t=${now()}' style='width:100%;height:100%;border:0;' scrolling='auto'></iframe>", displayed: false)
    } catch (e) {
        logWarn "couldn't save ${fname} (${e.message}), sending inline HTML (too long for dashboard tiles)"
        sendEvent(name: attr, value: html, displayed: false)
    }
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
        StringBuilder html = new StringBuilder()
        html.append("<div style=\"font-family:sans-serif;font-size:11px;background:#1a1a2e;color:#eee;border-radius:8px;padding:8px;\">")
        html.append("<div style=\"text-align:center;font-weight:bold;font-size:12px;color:#00b4d8;margin-bottom:6px;\">")
        html.append("&#128438; Recent Prints <span style=\"color:#aaa;font-size:10px;\">(top ${recentFilesForTile.size()} of ${totalJobCount} jobs)</span></div>")

        String lastPrintVal        = device.currentValue("lastPrint") ?: ""
        String currentPrintState   = device.currentValue("printState") ?: "standby"
        if (lastPrintVal && lastPrintVal != "none" && !(currentPrintState in ["printing", "paused"])) {
            html.append("<div style=\"background:#16213e;border-left:3px solid #00ff87;padding:4px 8px;margin-bottom:6px;\">")
            html.append("<div style=\"font-size:9px;color:#aaa;\">last completed &middot; use startLastPrint to reprint</div>")
            html.append("<div style=\"color:#00ff87;font-weight:bold;font-size:11px;margin-top:1px;\">${lastPrintVal}</div>")
            html.append("</div>")
        }

        html.append("<table style=\"width:100%;border-collapse:collapse;\">")
        recentFilesForTile.eachWithIndex { file, idx ->
            String number      = (idx + 1).toString()
            Boolean isPrinting = file.status == "in_progress"
            String rowBg       = isPrinting ? "#1b4332" : (idx % 2 == 0 ? "#16213e" : "#0f3460")
            String numColor    = isPrinting ? "#00ff87" : "#00b4d8"
            String nameColor   = isPrinting ? "#00ff87" : "#eee"
            String indicator   = isPrinting ? " &#9654; printing" : ""
            html.append("<tr style=\"background:${rowBg};\">")
            html.append("<td style=\"padding:3px 5px;color:${numColor};font-weight:bold;width:24px;min-width:24px;text-align:center;\">${number}</td>")
            html.append("<td style=\"padding:3px 5px;color:${nameColor};\">${file.name}${indicator}</td></tr>")
        }
        html.append("</table>")
        html.append("<div style=\"color:#888;font-size:9px;margin-top:4px;text-align:center;\">Use number with startPrint command</div>")
        html.append("</div>")

        String newHtml = html.toString()
        String newHash  = newHtml.hashCode().toString()
        String lastHash = state?.lastFilesListHash ?: ""
        if (newHash != lastHash) {
            state.lastFilesListHash = newHash
            publishTile("filesListTile", newHtml)
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
