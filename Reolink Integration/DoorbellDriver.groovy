/**
 * Reolink Doorbell (Component Driver)
 * Version: 1.6.0
 *
 * Same delegation pattern as Reolink Camera, plus a "visitor" (button press)
 * event so Rule Machine can trigger straight off "pushed 1" for a doorbell
 * ring, separate from AI person/motion detection.
 *
 * v1.6.0 -- Added RTSPStream support using the same hub video stream service
 * and parent-managed source settings as the camera driver.
 * v1.5.1 -- No functional change to this driver (version kept in sync with
 * the rest of the release); the actual fix was six missing passthrough
 * methods in ReolinkStandaloneDevices.groovy -- see that file's header for
 * details.
 * v1.5.0 -- NVR recording-control support: excludeFromRecordingPresets
 * preference (below) is read directly by the app (ch.getSetting(...), same
 * technique already used for batteryCheckEnabled etc.) inside
 * componentLoadPreset() -- when true, no preset will ever write a new
 * schedule to this device, regardless of what that preset specifies. This
 * is the primary intended use case (excluding a battery-class WiFi
 * doorbell from a preset meant for wired channels) -- see ParentApp.groovy
 * for the enforcement side and the Recording Presets page's 🔒 display.
 * Also added checkRecordingSchedule() -- a diagnostic-only command that
 * reads and logs this channel's current NVR recording schedule without
 * changing anything (requires Full logging to see the result).
 * v1.4.2 -- HOTFIX: bare paragraph("text") calls in preferences are App-DSL
 * only and don't exist on a driver's compiled script -- caused a fatal
 * "No signature of method: Script1.paragraph()" on save/update, blocking the
 * 1.4.1 update entirely (same bug as CameraDriver.groovy). Fixed via
 * input(type: "paragraph").
 * v1.4.1 -- Added chargingStatus attribute (charging/not_charging/unknown)
 * from GetBatteryInfo's Battery.chargeStatus, same confirmed field as the
 * camera driver. batteryMode self-heal was app-side only, no change needed
 * here.
 * v1.3.9 -- Added Battery capability so a battery-powered doorbell can show
 * a percentage and get pulled into the app's auto battery-check scheduler
 * (keyed off hasCapability("Battery"), no app-side change needed for that
 * part). Added receiveBatteryInfo() with the confirmed nested
 * Battery.batteryPercent field, and receiveBatteryMode() (called once at
 * device creation).
 * Full history prior to 1.3.9 is in GitHub commit history.
 */
metadata {
    definition(name: "Reolink Doorbell", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Motion Sensor"
        capability "PushableButton"
        capability "Refresh"
        capability "Sensor"
        // v1.3.9: added so a battery-powered doorbell can show a % and get
        // pulled into the app's auto battery-check scheduler, which keys
        // off hasCapability("Battery") rather than device type.
        capability "Battery"
        capability "ImageUrl"
        capability "RTSPStream"
        attribute "person", "enum", ["active", "inactive"]
        attribute "vehicle", "enum", ["active", "inactive"]
        attribute "pet", "enum", ["active", "inactive"]
        attribute "package", "enum", ["active", "inactive"]
        attribute "snapshotUrl", "string"
        attribute "rtspUrl", "string"
        attribute "status", "string"
        attribute "width", "number"
        attribute "height", "number"
        attribute "cpuUsage", "number"
        attribute "streamSubscribers", "number"
        attribute "batteryMode", "enum", ["wired", "battery", "unknown"]
        // Both "charging" and "not_charging" confirmed against real
        // hardware -- see CameraDriver.groovy's matching attribute comment.
        attribute "chargingStatus", "enum", ["unknown", "not_charging", "charging"]
        attribute "sleepStatus", "enum", ["awake", "asleep", "unknown"]
        // Tracks whether the most recent state update came from the
        // real-time event push path or the plain polling fallback.
        attribute "lastUpdateSource", "enum", ["event", "poll"]
        attribute "supportedFeatures", "string"
        command "takeSnapshot"
        command "checkAbilities", [[name: "Refreshes the supportedFeatures attribute from the doorbell's current GetAbility data"]]
        command "checkRecordingSchedule", [[name: "Diagnostic only -- reads and logs this channel's current NVR recording schedule, does NOT change anything. Set logging to Full to see the result."]]
        command "checkBattery", [[name: "Battery-mode devices only"]]
        command "setPollInterval", [[name: "seconds", type: "NUMBER"]]
        command "setSnapshotInterval", [[name: "seconds", type: "NUMBER"]]
    }
    preferences {
        // v1.4.2 follow-up: reordered so each paragraph header is the FIRST
        // of its own 3-item row in this 3-column grid -- see
        // CameraDriver.groovy's matching preferences comment for why
        // (input(type: "paragraph") doesn't span the full row on a driver
        // the way App-DSL paragraph() does).
        input name: "battChkHdr", type: "paragraph", title: "<b>Scheduled battery check</b>"
        input name: "batteryCheckEnabled", type: "bool", title: "Enable auto battery check", defaultValue: false,
            description: "Battery devices only, OFF by default. When ON, auto-checks and updates battery level " +
                "on the interval below. Checking briefly wakes the device (negligible power at default " +
                "interval). Ignored for wired devices. Check Battery still works manually any time regardless " +
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
        input name: "pollIntervalSec", type: "number", title: "Poll interval (sec)", defaultValue: 5,
            description: "Controls how often motion/AI/visitor state is polled. Does NOT control snapshot image " +
                "freshness -- see Snapshot interval below."
        input name: "snapshotIntervalSec", type: "number", title: "Snapshot interval (sec)", defaultValue: 30,
            description: "Controls how often the cached dashboard snapshot image refreshes. A dashboard tile's " +
                "own refresh rate does NOT make the image any fresher than this -- it just re-displays whatever " +
                "was last cached at this interval. Kept separate from poll interval so motion/visitor detection " +
                "can stay fast without forcing a full image download that often."
        // Permanent, per-device lock -- see CameraDriver.groovy's matching
        // preference for the full comment. Same behavior here.
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
def installed() {
    sendEvent(name: "numberOfButtons", value: 1)
}
def refresh() {
    parent?.componentRefresh(this, device.deviceNetworkId)
    refreshRtsp()
}

/** Applies changes to the RTSP port or MJPEG output width on device save. */
def updated() {
    refreshRtsp()
}

/** Rebuilds the doorbell's Reolink stream settings after source or channel discovery. */
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
    [port: config.rtspPort ?: 554, outputWidth: config.outputWidth ?: 640].each { key, value ->
        if (settings[key] == null) {
            device.updateSetting(key, [type: "number", value: value])
            changed = true
        }
    }
    if (changed || !device.currentValue("imageUrl")) refreshRtsp(config + [rtspPath: path])
}

/** Publishes the hub MJPEG endpoint and asks the hub to validate its RTSP source. */
private void refreshRtsp(Map config = [:]) {
    String host = (config.host ?: settings.ipAddress)?.toString()?.trim()
    String path = (config.rtspPath ?: settings.rtspPath)?.toString()?.trim()
    String user = (config.username ?: settings.cameraUser)?.toString()
    String password = (config.password ?: settings.cameraPassword)?.toString()
    Integer rtspPort
    try { rtspPort = (settings.port ?: 554) as Integer } catch (Exception ignored) { rtspPort = null }
    if (!host || !path?.startsWith("/") || !rtspPort || rtspPort < 1 || rtspPort > 65535) {
        sendEvent(name: "status", value: "invalid RTSP settings")
        return
    }
    String encodedUser = user ? URLEncoder.encode(user, "UTF-8").replace("+", "%20") : null
    String credentials = encodedUser ? "${encodedUser}${password ? ':********' : ''}@" : ""
    sendEvent(name: "rtspUrl", value: "rtsp://${credentials}${host}:${rtspPort}${path}")
    sendEvent(name: "refreshRate", value: 86400)
    if (getNumericHubVersion() < 9) {
        sendEvent(name: "status", value: "not supported on C8 or earlier hubs")
        return
    }
    sendEvent(name: "imageUrl", value: "/hub2/videoStream/${device.id}.mjpg")
    String generation = UUID.randomUUID().toString()
    state.rtspValidationGeneration = generation
    sendEvent(name: "status", value: "validating")
    try {
        asynchttpPost("rtspValidationHandler", [
            uri: "http://127.0.0.1:8080/hub2/videoStream/${device.id}/validate",
            contentType: "application/json", timeout: 12
        ], [generation: generation])
    } catch (Exception ignored) {
        if (state.rtspValidationGeneration == generation)
            sendEvent(name: "status", value: "unable to start RTSP validation")
    }
}

/** Handles the current validation request and exposes detected stream dimensions. */
def rtspValidationHandler(response, Map data) {
    if (!data?.generation || state.rtspValidationGeneration != data.generation) return
    state.remove("rtspValidationGeneration")
    if (response.status != 200 || !(response.json instanceof Map)) {
        sendEvent(name: "status", value: "RTSP validation request failed")
        return
    }
    Map result = response.json as Map
    if (result.success == true) {
        if (result.width instanceof Number && result.height instanceof Number) {
            sendEvent(name: "width", value: result.width)
            sendEvent(name: "height", value: result.height)
        }
        sendEvent(name: "status", value: "validated")
    } else {
        sendEvent(name: "status", value: result.message ?: "RTSP validation failed")
    }
}
def takeSnapshot() {
    parent?.componentTakeSnapshot(this, device.deviceNetworkId)
}
def setPollInterval(seconds) {
    parent?.componentSetPollInterval(this, seconds as Integer, device.deviceNetworkId)
}
def setSnapshotInterval(seconds) {
    parent?.componentSetSnapshotInterval(this, seconds as Integer, device.deviceNetworkId)
}
def checkAbilities() {
    parent?.componentCheckAbilities(this, device.deviceNetworkId)
}
/**
 * Diagnostic only: reads and logs this channel's current NVR recording
 * schedule, never writes anything. Requires the app's Log level set to
 * Full to see the result (same as any other logNormal/logFull-routed
 * message).
 */
def checkRecordingSchedule() {
    parent?.componentCheckRecordingSchedule(this, device.deviceNetworkId)
}
def checkBattery() {
    parent?.componentCheckBattery(this, device.deviceNetworkId)
}
/**
 * v1.3.9: battery% reads the confirmed nested reolink_aio field
 * Battery.batteryPercent first, same fix already validated on the camera
 * driver, with flat fallbacks kept for firmware variants that return it
 * unnested. chargingStatus (v1.4.1) reads Battery.chargeStatus -- see
 * CameraDriver.groovy's matching comment for the confirmed hardware detail.
 */
def receiveBatteryInfo(battInfo) {
    def pct = battInfo?.Battery?.batteryPercent ?: battInfo?.batteryPercent ?: battInfo?.batteryPercentage
    if (pct != null) sendEvent(name: "battery", value: pct)

    def chargeStatus = battInfo?.Battery?.chargeStatus
    def chargingLabel = (chargeStatus == 1) ? "charging" : (chargeStatus == 0) ? "not_charging" : "unknown"
    if (chargeStatus != null) sendEvent(name: "chargingStatus", value: chargingLabel)
}
/**
 * Required by the PushableButton capability -- declaring the capability adds
 * the Push command/attributes to the device page, but does NOT auto-implement
 * this method; without it, clicking Push (or any app/rule calling push())
 * throws MissingMethodException. Untyped buttonNumber parameter deliberately
 * -- Hubitat's own Commands-tab test UI can pass this as a String rather than
 * a Number, and a typed/coerced parameter would reject that.
 */
def push(buttonNumber) {
    sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
}
/**
 * Called by the app after GetAbility, both at discovery/creation time and on
 * a manual checkAbilities command. Informational only -- see the app's Tips
 * page ("Supported Features") for what this does and doesn't mean. Does NOT
 * hide or disable any command on this device; Hubitat has no way to do that
 * for an individual device instance.
 */
def receiveSupportedFeatures(List features) {
    sendEvent(name: "supportedFeatures", value: features ? features.join(", ") : "None detected")
}
/** Called by the app after either a poll or a real-time event push -- see CameraDriver.groovy's matching note. */
def parseReolinkState(aiState, mdState, String source = "poll") {
    sendIfChanged("sleepStatus", "awake")
    sendIfChanged("lastUpdateSource", source)
    // TODO confirm the visitor/doorbell-press field name in your firmware's GetAiState/GetMdState payload
    def visitorPressed = aiState?.visitor?.alarm_state == 1
    if (visitorPressed) {
        push(1)
    }
    def motionActive = mdState?.state == 1
    sendIfChanged("motion", motionActive ? "active" : "inactive")
    ["people", "vehicle", "dog_cat"].each { key ->
        def attr = key == "people" ? "person" : (key == "dog_cat" ? "pet" : key)
        def active = aiState?.getAt(key)?.alarm_state == 1
        sendIfChanged(attr, active ? "active" : "inactive")
    }
    def pkgActive = aiState?.package?.alarm_state == 1
    sendIfChanged("package", pkgActive ? "active" : "inactive")
}
/** See camera driver for why this exists -- cuts redundant sendEvent() calls to reduce load on lower-spec hubs. */
private void sendIfChanged(String name, value) {
    if (device.currentValue(name)?.toString() != value?.toString()) {
        sendEvent(name: name, value: value)
    }
}
/** Called by the app when a poll gets no response -- see camera driver for the reasoning. */
def markAsleep() {
    sendIfChanged("sleepStatus", "asleep")
}
/**
 * v1.3.9: called once by the app at device creation time with the discovery-
 * time battery probe result -- see CameraDriver.groovy's matching note.
 * v1.4.1: the app's scheduler can also call this later to backfill a device
 * that ended up without batteryMode set -- no change needed here either way.
 */
def receiveBatteryMode(String mode) {
    sendEvent(name: "batteryMode", value: mode)
}
def receiveSnapshotUrl(url) {
    sendEvent(name: "snapshotUrl", value: url)
}
