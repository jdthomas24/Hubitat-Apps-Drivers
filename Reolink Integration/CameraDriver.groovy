/**
 * Reolink Camera (Component Driver)
 * Version: 1.6.9
 *
 * Thin device: no HTTP of its own. Delegates everything to the parent app via
 * parent.componentX(this, ...), using data values sourceId/channel to
 * identify which source/channel this device maps to.
 *
 * v1.6.9 -- Manual Record command (on/off, optional seconds) and manualRecord attribute.
 * v1.6.8 -- Commands grouped into dropdowns (21 to 9): Check, Set Interval,
 * Ptz (now includes Calibrate, which follows up on its own status), Ptz Preset,
 * Set Spotlight, Set Siren, Set Pir. The old command names are kept as plain
 * methods (not listed on the page) so existing rules keep working.
 * Set Spotlight "auto on"/"auto off" control whether the spotlight turns on by
 * itself for motion at night (Reolink app: Spotlight > Modes). New spotlightAuto
 * attribute (on/off), filled in by Refresh or Check > abilities on Spotlight cameras.
 * v1.6.6 -- PIR fixes. pirOn()/pirOff() never changed the camera (it rejected
 * every request) but updated PIR Enabled anyway. They now just ask the app,
 * and receivePirState() updates PIR Enabled only once the camera confirms it.
 * pirStatusNote retired (PIR Enabled says the same; the note couldn't clear
 * without a page reload). batteryWired drops the percentage, which the
 * Battery row already shows.
 * v1.6.5 -- RTSP validation is queued through the app (one device at a time,
 * retried on timeout) instead of firing immediately; refresh() only
 * revalidates when not already validated. Added lastMotionTime and
 * lastMotionType (most important type wins within one motion event:
 * person > vehicle > pet > package > motion). New "batteryWired" summary
 * attribute ("Wired", "Battery, 87%, charging", "Battery, 100%, plugged in",
 * "Battery, 64%") replaces batteryMode; wired/battery is now kept in the
 * powerMode data value. chargingStatus adds "plugged_in" (adapter connected,
 * not charging, e.g. already full), which previously showed as not_charging.
 * Wired devices never show battery values (clearBatteryInfo()).
 * v1.5.0 -- NVR recording-control support: excludeFromRecordingPresets
 * preference (below) is read directly by the app inside
 * componentLoadPreset() -- when true, no preset will ever write a new
 * schedule to this device. Also added checkRecordingSchedule(), a
 * read-only diagnostic (Full logging to see the result).
 * v1.4.2 -- HOTFIX: bare paragraph("text") calls in preferences are App-DSL
 * only; fixed via input(type: "paragraph").
 * v1.4.1 -- Added chargingStatus attribute from GetBatteryInfo.
 * Full history prior to 1.4.1 is in GitHub commit history.
 */
metadata {
    definition(name: "Reolink Camera", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Motion Sensor"
        capability "Refresh"
        capability "Sensor"
        capability "Battery"
        capability "ImageUrl"

        try {
            capability "RTSPStream"
        } catch (Exception e) {
            // not supported on 2.5.2.121 and earlier
        }

        attribute "person", "enum", ["active", "inactive"]
        attribute "vehicle", "enum", ["active", "inactive"]
        attribute "pet", "enum", ["active", "inactive"]
        attribute "package", "enum", ["active", "inactive"]
        attribute "lastMotionTime", "string"
        attribute "lastMotionType", "enum", ["person", "vehicle", "pet", "package", "motion"]
        attribute "snapshotUrl", "string"
        attribute "rtspUrl", "string"
        attribute "status", "string"
        attribute "width", "number"
        attribute "height", "number"
        attribute "cpuUsage", "number"
        attribute "streamSubscribers", "number"
        // v1.6.5: one-line power summary, replaces batteryMode (sorts right after Battery).
        attribute "batteryWired", "string"
        attribute "chargingStatus", "enum", ["unknown", "not_charging", "charging", "plugged_in"]
        attribute "sleepStatus", "enum", ["awake", "asleep", "unknown"]
        // Whether the most recent state update came from an event push or a poll.
        attribute "lastUpdateSource", "enum", ["event", "poll"]
        attribute "spotlight", "enum", ["on", "off"]
        attribute "spotlightAuto", "enum", ["on", "off"]
        attribute "manualRecord", "enum", ["on", "off"]
        attribute "nightVision", "enum", ["auto", "on", "off"]
        attribute "siren", "enum", ["on", "off"]
        attribute "ptzCalibrationStatus", "enum", ["unknown", "required", "running", "done"]
        attribute "supportedFeatures", "string"
        attribute "pirEnabled", "enum", ["true", "false"]

        // v1.6.8: grouped into dropdowns. Pre-1.6.8 names still work as methods (see Legacy below).
        command "takeSnapshot"
        command "check", [[name: "item", type: "ENUM",
            description: "Reads from the camera, changes nothing. Battery wakes a battery camera; recording schedule logs at Full.",
            constraints: ["battery", "abilities", "ptz calibration", "recording schedule"]]]
        command "setInterval", [[name: "interval", type: "ENUM", constraints: ["poll", "snapshot"]],
            [name: "seconds", type: "NUMBER"]]
        command "ptz", [[name: "action", type: "ENUM", description: "PTZ cameras only. Calibrate fixes preset drift.",
            constraints: ["Left", "Right", "Up", "Down", "ZoomInc", "ZoomDec", "Stop", "Calibrate"]]]
        command "ptzPreset", [[name: "action", type: "ENUM", constraints: ["go to", "save here"]],
            [name: "presetId", type: "NUMBER", description: "Preset ID (e.g. 1 for your home position)"],
            [name: "name", type: "STRING", description: "Optional, used by save here"]]
        command "setSpotlight", [[name: "mode", type: "ENUM",
            description: "Auto on/off: whether motion turns the light on at night",
            constraints: ["on", "off", "auto on", "auto off"]]]
        command "manualRecord", [[name: "state", type: "ENUM", constraints: ["on", "off"],
            description: "Record now. Stops by itself after the seconds below."],
            [name: "seconds", type: "NUMBER", description: "Optional, 1 to 600 (default 600)"]]
        command "setNightVision", [[name: "mode", type: "ENUM", constraints: ["auto", "on", "off"]]]
        command "setSiren", [[name: "state", type: "ENUM", constraints: ["on", "off"]]]
        command "setPir", [[name: "state", type: "ENUM", description: "PIR motion trigger (battery cameras)",
            constraints: ["on", "off"]]]
    }
    preferences {
        // Each header is the FIRST of its own 3-item row in this 3-column grid
        // (input(type: "paragraph") on a driver does not span the full row).
        input name: "battChkHdr", type: "paragraph", title: "<b>Scheduled battery check</b>"
        input name: "batteryCheckEnabled", type: "bool", title: "Enable auto battery check", defaultValue: false,
            description: "Battery devices only, OFF by default. When ON, auto-checks and updates battery level " +
                "on the interval below. Checking briefly wakes the device (negligible power at default " +
                "interval). Ignored for wired devices. Check > battery still works manually any time regardless " +
                "of this setting."
        input name: "batteryCheckIntervalHours", type: "number", title: "Auto battery check interval (hours)", defaultValue: 12,
            description: "Only used if the setting above is ON."
        input name: "eventWakeHdr", type: "paragraph", title: "<b>Event-triggered battery check</b>"
        input name: "checkBatteryOnEventWake", type: "bool", title: "Also check battery/charging on real motion/AI events", defaultValue: false,
            description: "Battery devices only, OFF by default. When ON, a real motion/AI event (device " +
                "already awake) also triggers a battery/charging check -- free, unlike the interval above, " +
                "since it doesn't force an extra wakeup."
        input name: "eventWakeBatteryThrottleSec", type: "number", title: "Minimum seconds between event-triggered checks", defaultValue: 60,
            description: "Only used if the setting above is ON. Keeps a burst of events (motion, person, " +
                "vehicle in seconds) from triggering more than one check."
        input name: "pollIntervalSec", type: "number", title: "Poll interval (sec)", defaultValue: 30,
            description: "Controls how often motion/AI state is polled. Does NOT control snapshot image " +
                "freshness -- see Snapshot interval below."
        input name: "snapshotIntervalSec", type: "number", title: "Snapshot interval (sec)", defaultValue: 30,
            description: "Controls how often the cached dashboard snapshot image refreshes. A dashboard tile's " +
                "own refresh rate does NOT make the image any fresher than this -- it just re-displays whatever " +
                "was last cached at this interval. Kept separate from poll interval so motion detection can " +
                "stay fast without forcing a full image download that often."
        // Permanent per-device lock, read by the app inside componentLoadPreset().
        // Only blocks preset-driven writes; direct manual commands still work.
        input name: "excludeFromRecordingPresets", type: "bool",
            title: "🔒 Exclude this device from ALL recording presets", defaultValue: false,
            description: "When ON, loading ANY preset will never write a new recording schedule to this " +
                "device, no matter what that preset specifies for it. Direct manual commands aimed at this " +
                "device are unaffected. Off by default."
        input name: "rtspInfo", type: "paragraph", title: "<b>RTSP live stream</b>",
            description: "The source address and login come from the Reolink app. Enable RTSP on the source. " +
                "Live video uses Hubitat's video stream service (supported hubs only)."
        input name: "cameraUser", type: "text", title: "RTSP username (managed by app)"
        input name: "cameraPassword", type: "password", title: "RTSP password (managed by app)"
        input name: "ipAddress", type: "text", title: "RTSP source IP (managed by app)"
        input name: "port", type: "number", title: "RTSP port", defaultValue: 554, range: "1..65535"
        input name: "rtspPath", type: "text", title: "RTSP path (managed by app)"
        input name: "outputWidth", type: "number", title: "Live video width", defaultValue: 640, range: "640..1920"
    }
}

/** v1.6.5: only revalidates the stream when it isn't already validated. */
def refresh() {
    parent?.componentRefresh(this, device.deviceNetworkId)
    if (device.currentValue("status") != "validated") refreshRtsp()
}

/** Applies changes to the RTSP port or MJPEG output width on device save. */
def updated() {
    refreshRtsp()
}

/** Rebuilds the camera's Reolink stream settings after source or channel discovery. */
def receiveRtspConfig(Map config) {
    if (!config?.host || config.channel == null) return
    String path = "/Preview_${String.format('%02d', (config.channel as Integer) + 1)}_sub"
    boolean changed = false
    [cameraUser: config.username, cameraPassword: config.password,
     ipAddress: config.host, rtspPath: path].each { key, value ->
        String type = key == "cameraPassword" ? "password" : "text"
        if (settings[key]?.toString() != value?.toString()) {
            device.updateSetting(key, [type: type, value: value?.toString() ?: ""])
            changed = true
        }
    }
    // v1.6.5: also retry a stream left unvalidated. Returns true so the APP queues it
    // (a device calling back into the app mid-call is unsafe with singleThreaded).
    if (changed || !device.currentValue("imageUrl") || device.currentValue("status") != "validated")
        return refreshRtsp(config + [rtspPath: path], true)
    return false
}

/** Publishes the hub MJPEG endpoint and queues RTSP validation. fromApp: the app queues it itself. */
private boolean refreshRtsp(Map config = [:], boolean fromApp = false) {
    String host = (config.host ?: settings.ipAddress)?.toString()?.trim()
    String path = (config.rtspPath ?: settings.rtspPath)?.toString()?.trim()
    String user = (config.username ?: settings.cameraUser)?.toString()
    String password = (config.password ?: settings.cameraPassword)?.toString()
    Integer rtspPort
    try { rtspPort = (settings.port ?: 554) as Integer } catch (Exception ignored) { rtspPort = null }
    if (!host || !path?.startsWith("/") || !rtspPort || rtspPort < 1 || rtspPort > 65535) {
        sendEvent(name: "status", value: "invalid RTSP settings")
        return false
    }
    String encodedUser = user ? URLEncoder.encode(user, "UTF-8").replace("+", "%20") : null
    String credentials = encodedUser ? "${encodedUser}${password ? ':********' : ''}@" : ""
    sendEvent(name: "rtspUrl", value: "rtsp://${credentials}${host}:${rtspPort}${path}")
    sendEvent(name: "refreshRate", value: 86400)
    if (getNumericHubVersion() < 9) {
        sendEvent(name: "status", value: "not supported on C8 or earlier hubs")
        return false
    }
    sendEvent(name: "imageUrl", value: "/hub2/videoStream/${device.id}.mjpg")
    // v1.6.5: validated one device at a time by the app (a Hub/NVR times out on simultaneous streams).
    sendEvent(name: "status", value: "queued for validation")
    if (!fromApp) parent?.componentQueueRtspValidation(this, device.deviceNetworkId)
    return true
}

/** v1.6.5: called by the app when this device's turn comes up. Returns false if it couldn't start. */
def startRtspValidation() {
    String generation = UUID.randomUUID().toString()
    state.rtspValidationGeneration = generation
    sendEvent(name: "status", value: "validating")
    try {
        asynchttpPost("rtspValidationHandler", [
            uri: "http://127.0.0.1:8080/hub2/videoStream/${device.id}/validate",
            contentType: "application/json", timeout: 15
        ], [generation: generation])
        return true
    } catch (Exception ignored) {
        state.remove("rtspValidationGeneration")
        sendEvent(name: "status", value: "unable to start RTSP validation")
        return false
    }
}

/** Handles the current validation request, exposes stream dimensions, and reports back to the queue. */
def rtspValidationHandler(response, Map data) {
    if (!data?.generation || state.rtspValidationGeneration != data.generation) return
    state.remove("rtspValidationGeneration")
    boolean success = false
    String message
    if (response.status != 200 || !(response.json instanceof Map)) {
        message = "RTSP validation request failed"
    } else {
        Map result = response.json as Map
        if (result.success == true) {
            if (result.width instanceof Number && result.height instanceof Number) {
                sendEvent(name: "width", value: result.width)
                sendEvent(name: "height", value: result.height)
            }
            success = true
            message = "validated"
        } else {
            message = result.message ?: "RTSP validation failed"
        }
    }
    sendEvent(name: "status", value: message)
    // The app answers with [attempt, delay] when it schedules a retry.
    def retry = parent?.componentRtspValidationDone(this, device.deviceNetworkId, success, message)
    if (retry instanceof Map && retry.attempt) {
        sendEvent(name: "status", value: "timed out, retry ${retry.attempt} in ${retry.delay}s")
    }
}

def takeSnapshot() {
    parent?.componentTakeSnapshot(this, device.deviceNetworkId)
}

// ---- v1.6.8 grouped commands ----

def check(item) {
    switch (item?.toString()?.toLowerCase()) {
        case "battery": checkBattery(); break
        case "abilities": checkAbilities(); break
        case "ptz calibration": checkPtzCalibrationStatus(); break
        case "recording schedule": checkRecordingSchedule(); break
        default: log.warn "${device.displayName}: unknown check '${item}'"
    }
}

def setInterval(interval, seconds) {
    Integer secs = toWholeNumber(seconds, "seconds", 1)
    if (secs == null) return
    if (interval?.toString()?.toLowerCase() == "snapshot") setSnapshotInterval(secs)
    else setPollInterval(secs)
}

def ptz(action) {
    if (action?.toString()?.equalsIgnoreCase("Calibrate")) { calibratePtz(); return }
    parent?.componentPtz(this, action, device.deviceNetworkId)
}

def ptzPreset(action, presetId, name = null) {
    Integer id = toWholeNumber(presetId, "preset ID", 0)
    if (id == null) return
    if (action?.toString()?.toLowerCase() == "save here") savePresetHere(id, name)
    else ptzGoToPreset(id)
}

def setSpotlight(mode) {
    switch (mode?.toString()?.toLowerCase()) {
        case "on": spotlightOn(); break
        case "off": spotlightOff(); break
        case "auto on": parent?.componentSetSpotlightAuto(this, true, device.deviceNetworkId); break
        case "auto off": parent?.componentSetSpotlightAuto(this, false, device.deviceNetworkId); break
        default: log.warn "${device.displayName}: unknown spotlight mode '${mode}'"
    }
}

def setNightVision(mode) {
    parent?.componentSetNightVision(this, mode, device.deviceNetworkId)
    sendEvent(name: "nightVision", value: mode)
}

def setSiren(value) {
    if (value?.toString()?.toLowerCase() == "on") sirenOn() else sirenOff()
}

def setPir(value) {
    if (value?.toString()?.toLowerCase() == "on") pirOn() else pirOff()
}

/** Rule Machine sends NUMBER args as BigDecimal; returns null (and warns) if missing or below min. */
private Integer toWholeNumber(value, String label, int min) {
    try {
        Integer n = new BigDecimal(value.toString()).intValue()
        if (n >= min) return n
    } catch (e) { }
    log.warn "${device.displayName}: ${label} must be a number of at least ${min} (got '${value}')"
    return null
}

/** v1.6.9: record now; the app stops it after seconds (default and max 600). */
def manualRecord(value, seconds = null) {
    boolean on = value?.toString()?.toLowerCase() == "on"
    Integer secs = null
    if (on && seconds != null && "${seconds}".trim()) {
        secs = toWholeNumber(seconds, "seconds", 1)
        if (secs == null) return
    }
    parent?.componentSetManualRecord(this, on, secs, device.deviceNetworkId)
}

/** v1.6.9: called by the app once the camera accepts the change. */
def receiveManualRecord(Boolean on) {
    sendEvent(name: "manualRecord", value: on ? "on" : "off")
}

// ---- Legacy (pre-1.6.8) command names: no longer listed, kept so existing rules keep working ----

def ptzGoToPreset(presetId) {
    parent?.componentPtzGoToPreset(this, presetId as Integer, device.deviceNetworkId)
}

def savePresetHere(presetId, name = null) {
    parent?.componentSavePreset(this, presetId as Integer, name, device.deviceNetworkId)
}

def spotlightOn() {
    parent?.componentSetSpotlight(this, true, device.deviceNetworkId)
    sendEvent(name: "spotlight", value: "on")
}

def spotlightOff() {
    parent?.componentSetSpotlight(this, false, device.deviceNetworkId)
    sendEvent(name: "spotlight", value: "off")
}

def sirenOn() {
    parent?.componentSetSiren(this, true, device.deviceNetworkId)
    sendEvent(name: "siren", value: "on")
}

def sirenOff() {
    parent?.componentSetSiren(this, false, device.deviceNetworkId)
    sendEvent(name: "siren", value: "off")
}

/** Does NOT stop an in-progress or scheduled recording. */
def pirOff() {
    parent?.componentSetPir(this, false, device.deviceNetworkId)
}

def pirOn() {
    parent?.componentSetPir(this, true, device.deviceNetworkId)
}

def checkBattery() {
    parent?.componentCheckBattery(this, device.deviceNetworkId)
}

def checkAbilities() {
    parent?.componentCheckAbilities(this, device.deviceNetworkId)
}

/** Diagnostic only: logs this channel's NVR recording schedule (Full logging). */
def checkRecordingSchedule() {
    parent?.componentCheckRecordingSchedule(this, device.deviceNetworkId)
}

/** v1.6.8: also checks progress on its own (every 60s while running, at most 5 times). */
def calibratePtz() {
    parent?.componentCalibratePtz(this, device.deviceNetworkId)
    runIn(60, "ptzCalibrationFollowUp", [data: [n: 1]])
}

def ptzCalibrationFollowUp(Map data) {
    checkPtzCalibrationStatus()
    int n = (data?.n ?: 1) as Integer
    if (device.currentValue("ptzCalibrationStatus") == "running" && n < 5) {
        runIn(60, "ptzCalibrationFollowUp", [data: [n: n + 1]])
    }
}

def checkPtzCalibrationStatus() {
    parent?.componentCheckPtzCalibrationStatus(this, device.deviceNetworkId)
}

def setPollInterval(seconds) {
    parent?.componentSetPollInterval(this, seconds as Integer, device.deviceNetworkId)
}

def setSnapshotInterval(seconds) {
    parent?.componentSetSnapshotInterval(this, seconds as Integer, device.deviceNetworkId)
}

// ---- Called by the app ----

/** v1.6.8: the camera's confirmed spotlight mode (0 = off). Remembers the last auto mode. */
def receiveSpotlightAuto(Integer mode) {
    if (mode == null) return
    if (mode > 0) device.updateDataValue("spotlightAutoMode", mode.toString())
    sendIfChanged("spotlightAuto", mode > 0 ? "on" : "off")
}

/** v1.6.6: called once the device confirms the change (failures are logged by the app). */
def receivePirState(Boolean enabled, String error = null) {
    if (error) return
    sendEvent(name: "pirEnabled", value: enabled ? "true" : "false")
    if (!enabled) log.warn "${device.displayName}: PIR disabled -- motion trigger suppressed until turned back on"
}

/**
 * Called by the app with "battery" or "wired" (at creation, scheduler
 * backfill, and the v1.6.5 migration). Stored in the powerMode data value;
 * the retired batteryMode attribute is removed from the device.
 */
def receiveBatteryMode(String mode) {
    device.updateDataValue("powerMode", mode)
    updatePowerSummary()
    safeDelete("batteryMode")
    safeDelete("power")
    safeDelete("batteryWiredMode")
    if (mode == "wired") clearBatteryInfo()
}

/**
 * Called by the app after GetBatteryInfo. Battery.chargeStatus nonzero =
 * charging; zero with Battery.adapterStatus nonzero = plugged_in (adapter
 * connected, not charging, confirmed on a full battery: adapterStatus=1,
 * chargeStatus=0, current=0); both zero = not_charging. Ignored on a wired device.
 */
def receiveBatteryInfo(battInfo) {
    if (device.getDataValue("powerMode") == "wired") return
    def pct = battInfo?.Battery?.batteryPercent ?: battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)

    def charge = battInfo?.Battery?.chargeStatus
    def adapter = battInfo?.Battery?.adapterStatus
    String label = null
    if (charge != null) {
        label = (charge as Integer) != 0 ? "charging" :
            (adapter != null && (adapter as Integer) != 0) ? "plugged_in" : "not_charging"
        sendEvent(name: "chargingStatus", value: label)
    }
    updatePowerSummary(pct, label)
}

/** v1.6.5: removes battery values from a wired device. */
def clearBatteryInfo() {
    safeDelete("battery")
    safeDelete("chargingStatus")
}

/** Removes a stale attribute; never lets a cleanup failure stop the caller. */
private void safeDelete(String name) {
    try { device.deleteCurrentState(name) } catch (e) { /* not present or not supported */ }
}

/** Builds batteryWired: Wired, Battery, Battery, charging, or Battery, plugged in (percentage is on the Battery row). */
private void updatePowerSummary(pct = null, String charging = null) {
    String mode = device.getDataValue("powerMode")
    String text
    if (mode == "wired") {
        text = "Wired"
    } else if (mode == "battery") {
        String c = charging ?: device.currentValue("chargingStatus")
        List parts = ["Battery"]
        if (c == "charging") parts << "charging"
        else if (c == "plugged_in") parts << "plugged in"
        text = parts.join(", ")
    } else {
        text = "Unknown"
    }
    sendIfChanged("batteryWired", text)
}

/**
 * Called by the app after GetAbility. Informational only (see the app's Tips
 * page); does not hide or disable any command on this device.
 */
def receiveSupportedFeatures(List features) {
    sendEvent(name: "supportedFeatures", value: features ? features.join(", ") : "None detected")
}

/** Called by the app after GetPtzCheckState. 0=required, 1=running, 2=done. */
def receivePtzCalibrationState(state) {
    def statusMap = [0: "required", 1: "running", 2: "done"]
    sendEvent(name: "ptzCalibrationStatus", value: statusMap[state] ?: "unknown")
}

/** Called by the app after a poll (source "poll") or a real-time event push (source "event"). */
def parseReolinkState(aiState, mdState, String source = "poll") {
    boolean wasActive = anyMotionActive()
    List<String> activeTypes = []
    sendIfChanged("sleepStatus", "awake")
    sendIfChanged("lastUpdateSource", source)
    // v1.6.5: self-heal if the power line was never written.
    if (device.getDataValue("powerMode") && device.currentValue("batteryWired") == null) updatePowerSummary()
    // v1.6.6: one-time cleanup on the next update: retired note, battery line without the percentage.
    if (state.v166Cleanup == null) {
        safeDelete("pirStatusNote")
        if (device.getDataValue("powerMode")) updatePowerSummary()
        state.v166Cleanup = true
    }

    // TODO map real field names once GetAiState/GetMdState payloads are confirmed
    def motionActive = mdState?.state == 1
    sendIfChanged("motion", motionActive ? "active" : "inactive")
    if (motionActive) activeTypes << "motion"

    ["people", "vehicle", "dog_cat"].each { key ->
        def attr = key == "people" ? "person" : (key == "dog_cat" ? "pet" : key)
        def active = aiState?.getAt(key)?.alarm_state == 1
        sendIfChanged(attr, active ? "active" : "inactive")
        if (active) activeTypes << attr
    }
    def pkgActive = aiState?.package?.alarm_state == 1
    sendIfChanged("package", pkgActive ? "active" : "inactive")
    if (pkgActive) activeTypes << "package"

    updateLastMotion(wasActive, activeTypes)
}

private boolean anyMotionActive() {
    ["motion", "person", "vehicle", "pet", "package"].any { device.currentValue(it) == "active" }
}

/** v1.6.5: stamps a new motion event, or upgrades the type if a more important one appears mid-event. */
private void updateLastMotion(boolean wasActive, List<String> activeTypes) {
    if (!activeTypes) return
    List<String> rank = ["person", "vehicle", "pet", "package", "motion"]
    String best = rank.find { it in activeTypes }
    String current = device.currentValue("lastMotionType")
    boolean upgrade = current in rank && rank.indexOf(best) < rank.indexOf(current)
    if (!wasActive || !current || upgrade) {
        sendEvent(name: "lastMotionType", value: best)
        sendEvent(name: "lastMotionTime", value: new Date().format("yyyy-MM-dd h:mm:ss a", location?.timeZone ?: TimeZone.getDefault()))
    }
}

/** Only sends when the value changed, to avoid needless events and hub load. */
private void sendIfChanged(String name, value) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        sendEvent(name: name, value: value)
    }
}

/**
 * Called by the app when a poll gets no response. Normal for a battery
 * device, a real problem for a wired one. Motion/AI attributes keep their
 * last-known value.
 */
def markAsleep() {
    sendIfChanged("sleepStatus", "asleep")
}

def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}
