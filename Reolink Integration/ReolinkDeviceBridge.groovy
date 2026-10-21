/**
 * Reolink Device Bridge (Internal Parent Driver)
 * Version: 1.6.14
 *
 * Not user-facing. One per source, managed by the app. Holds the Baichuan TCP event subscription
 * (cmd_id 31) and is the parent of that source's Camera/Doorbell devices (Hubitat only nests
 * device-owned children). componentX() calls from children pass straight up to the app.
 *
 * v1.6.14 -- Version sync (hub load changes are in the app).
 * v1.6.13 -- Version sync (dark mode is in the app).
 * v1.6.12 -- Version sync (the app splits event status/AItype lists).
 * v1.6.11 -- Events stopped decoding if the in-memory AES key was cleared on a live connection
 *    (Hub pushes are 'c800', so the lost-key check never fired). Any keyless message now
 *    reconnects once (recovers within 25s). sendBaichuanCommand(): settings over this connection,
 *    reply matched by message id, 15s timeout (spotlight on Home Hub Mini battery cams).
 *    cmd_id 291 (floodlight status) passed to the app.
 * v1.6.10 -- Per-message data (buffer, last status, resync, AES key, nonce) moved from state to
 *    memory, so pushes don't write the hub database. AES key no longer in State Variables.
 * v1.6.9 -- On/Off bare (a param label made Off throw), Push without a number warns, blank Load
 *    Preset loads the Preferences pick, Recording and Event Connection dropdowns. Old methods
 *    still callable.
 * v1.6.6 -- Password held in memory only (SOURCE_SECRETS), re-fetched from the app; State shows
 *    "Password: Saved, N characters", Login and Last Good Login. scrubState() clears old values.
 * v1.6.5 -- RTSP queue passthroughs. "unable to decrypt body" ('c800' is a status code) moved to
 *    Full logging.
 * v1.6.4 -- Failed socket open, mid-handshake drop and nonce-less reply now use the reconnect
 *    ladder (five standalone cameras sat polling for a day). retryEventSubscription(). Offline
 *    and give-up messages log once per outage.
 * v1.6.3 -- Traffic time in memory (LAST_REAL_TRAFFIC): state saves overwrote it and every link
 *    looked stale at ~25s. Stale threshold 180s. Quiet routine reconnects; warn once offline,
 *    info when back, error on give-up, warn on flapping (>5 in 10 min). setLogRank from the app.
 *    Keepalive replies (93) handled quietly. isEventConnectionAlive().
 * v1.5.3 -- Stale watchdog restored after a 5-day silent outage; keepalive always reschedules.
 *    Reconnect logging escalates: attempt 1 silent, 2+ warn, give-up after 10 is error.
 * v1.5.0 -- Switch = source master record (every channel), presets per channel. Rule Machine
 *    passes NUMBER args as BigDecimal; a named untyped param becomes an argument slot.
 * v1.3.8 -- Header resync scans forward to the next real header (cap 20/read), resync noise at
 *    debug. Logging goes through the app's Log level.
 * UNCONFIRMED: translateToLegacyShape() mapping in the app. Sleep pushes (145) logged, not used.
 */
import groovy.transform.Field
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

metadata {
    definition(name: "Reolink Device Bridge", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Actuator"
        // Switch = NVR/Hub master record on/off; PushableButton = load preset by button number.
        // On/Off come from the capability, bare (a param label renders an input box; see header).
        capability "Switch"
        capability "PushableButton"
        command "recording", [[name: "state", type: "ENUM", constraints: ["on", "off"],
            description: "Master recording, every channel on this Home Hub or NVR. Same as On/Off."]]
        command "push", [[name: "button", type: "NUMBER", description: "Preset button number (shown on the app's Recording Presets page)"]]
        command "loadPreset", [[name: "presetName", type: "STRING", description: "Preset name. Leave blank to load the one picked under Preferences."]]
        command "eventConnection", [[name: "action", type: "ENUM", constraints: ["restart", "start", "stop"],
            description: "Live event connection. Restart if events stop arriving."]]
        attribute "connectionStatus", "enum", ["disconnected", "connecting", "connected", "reconnecting"]
        attribute "recordingMode", "string"          // last preset loaded
        attribute "recordingEnabled", "enum", ["enabled", "disabled"]
        attribute "lastRecordingResult", "string"    // e.g. "6/6 OK"
    }
    preferences {
        input name: "bridgeInfo", type: "paragraph", element: "paragraph", title: "<b>About this device</b>",
            description: "Holds this source's live event connection. On a Home Hub or NVR, <b>Recording</b> (or On/Off) is the master " +
                "record switch for every channel, and presets (Push, Load Preset) set which hours each channel records. " +
                "Not used for a standalone camera. Logging is set in the Reolink Integration app."
        input name: "presetToLoad", type: "enum", title: "Preset for a blank Load Preset",
            options: getAvailablePresetNames(), required: false
    }
}

// ============================================================================
// Child (Camera/Doorbell) management -- this bridge creates/removes Camera
// and Doorbell as ITS OWN children, called by the app's createSelectedChildren().
// ============================================================================
/**
 * If a camera/doorbell device with this exact DNI already exists anywhere
 * else on the hub (e.g. an orphan from a partial removal), Hubitat's global
 * DNI-uniqueness rule rejects the create -- caught here rather than left to
 * throw straight up through createSelectedChildren() -> discoverPage() and
 * crash the whole app page with no indication what went wrong. Fails loudly
 * but safely: logs a clear actionable warning and returns null instead.
 */
def createChannelDevice(String driverName, String dni, String name, Integer pollDefault, List supportedFeatures) {
    def child
    try {
        child = addChildDevice("jdthomas24", driverName, dni, [
            name: name, label: name, isComponent: true
        ])
    } catch (com.hubitat.device.exception.DuplicateDNIException e) {
        log.warn "Reolink Device Bridge (source ${state.sourceId}): a device with DNI '${dni}' already exists " +
            "somewhere on this hub but isn't reachable as this channel's device -- likely an orphaned device " +
            "from an earlier partial removal or reinstall. Search your full Devices list for Device Network Id " +
            "'${dni}' and delete it, then re-run discovery for this source. (${e.message})"
        return null
    }
    child.updateDataValue("sourceId", "${state.sourceId}")
    // dni format: "reolink-{sourceId}-{channel}" -- channel is the 3rd token.
    def channelToken = dni?.tokenize("-")?.getAt(2)
    if (channelToken != null) child.updateDataValue("channel", channelToken)
    child.updateSetting("pollIntervalSec", [type: "number", value: pollDefault])
    child.receiveSupportedFeatures(supportedFeatures ?: [])
    return child
}

def removeChannelDevice(String dni) {
    deleteChildDevice(dni)
}

// ============================================================================
// componentX() passthrough -- Camera/Doorbell call parent?.componentX(...)
// unchanged; since THIS bridge is now their real parent, each call lands
// here first and is simply forwarded up to the bridge's own parent (the
// app), which still does the actual HTTP/API work exactly as before.
// ============================================================================
def componentRefresh(child, String dni = null) { parent?.componentRefresh(child, dni) }
def componentTakeSnapshot(child, String dni = null) { parent?.componentTakeSnapshot(child, dni) }
def componentPtz(child, String direction, String dni = null) { parent?.componentPtz(child, direction, dni) }
def componentPtzGoToPreset(child, Integer presetId, String dni = null) { parent?.componentPtzGoToPreset(child, presetId, dni) }
def componentSavePreset(child, Integer presetId, String name, String dni = null) { parent?.componentSavePreset(child, presetId, name, dni) }
def componentSetSpotlight(child, Boolean on, String dni = null) { parent?.componentSetSpotlight(child, on, dni) }
def componentSetSpotlightAuto(child, Boolean on, String dni = null) { parent?.componentSetSpotlightAuto(child, on, dni) }
def componentSetNightVision(child, String mode, String dni = null) { parent?.componentSetNightVision(child, mode, dni) }
def componentSetSiren(child, Boolean on, String dni = null) { parent?.componentSetSiren(child, on, dni) }
def componentSetPir(child, Boolean on, String dni = null) { parent?.componentSetPir(child, on, dni) }
def componentSetManualRecord(child, Boolean on, Integer seconds, String dni = null) { parent?.componentSetManualRecord(child, on, seconds, dni) }
def componentCheckBattery(child, String dni = null) { parent?.componentCheckBattery(child, dni) }
def componentCheckAbilities(child, String dni = null) { parent?.componentCheckAbilities(child, dni) }
/** Diagnostic passthrough -- see ParentApp.groovy's componentCheckRecordingSchedule(). */
def componentCheckRecordingSchedule(child, String dni = null) { parent?.componentCheckRecordingSchedule(child, dni) }
def componentCalibratePtz(child, String dni = null) { parent?.componentCalibratePtz(child, dni) }
def componentCheckPtzCalibrationStatus(child, String dni = null) { parent?.componentCheckPtzCalibrationStatus(child, dni) }
def componentSetPollInterval(child, Integer seconds, String dni = null) { parent?.componentSetPollInterval(child, seconds, dni) }
def componentSetSnapshotInterval(child, Integer seconds, String dni = null) { parent?.componentSetSnapshotInterval(child, seconds, dni) }
// v1.6.5: RTSP validation queue.
def componentQueueRtspValidation(child, String dni = null) { parent?.componentQueueRtspValidation(child, dni) }
def componentRtspValidationDone(child, String dni, Boolean success, String message) { return parent?.componentRtspValidationDone(child, dni, success, message) }

// ============================================================================
// v1.5.0: recording control for this source, reachable via the standard
// Switch/PushableButton capabilities plus the "Preset to load" Preferences
// dropdown -- see ParentApp.groovy's componentSetRecordingEnabled()/
// componentLoadPreset()/componentBridgeButtonPushed() for the real logic.
// This driver only forwards each command and reflects the result.
// ============================================================================

/** Loads a preset by name; blank loads the one picked under Preferences (v1.6.9). */
def loadPreset(String presetName = null) {
    if (!presetName?.trim()) { loadSelectedPreset(); return }
    parent?.componentLoadPreset(this, state.sourceId, presetName.trim())
}

/** Loads the preset picked under Preferences. Undeclared since 1.6.9 (blank Load Preset does this); kept for old rules. */
def loadSelectedPreset() {
    def name = settings?.presetToLoad
    if (!name || name.startsWith("(no presets")) {
        log.warn "Reolink Device Bridge (source ${state.sourceId}): no preset selected (or none exist yet) -- " +
            "add one on the app's Recording Presets page, then pick it under Preferences or type its name in Load Preset"
        return
    }
    parent?.componentLoadPreset(this, state.sourceId, name)
}

/**
 * Backs the "Preset to load" dropdown above -- asks the app for this
 * source's current preset names every time the Preferences page is opened,
 * so the list is always live rather than fixed at driver-install time. A
 * failure (or zero presets defined yet) falls back to a single obviously-
 * not-a-real-preset placeholder option rather than an empty/broken dropdown;
 * loadSelectedPreset() above recognizes and rejects that placeholder.
 */
private List<String> getAvailablePresetNames() {
    def names = []
    try {
        names = parent?.componentGetPresetNames(state.sourceId) ?: []
    } catch (e) { /* fall through to placeholder below */ }
    return names ?: ["(no presets defined yet -- add one on the Recording Presets page in the app)"]
}

/** Switch capability: master record enable/disable for every channel of this source. */
def on() {
    parent?.componentSetRecordingEnabled(this, state.sourceId, true)
}

def off() {
    parent?.componentSetRecordingEnabled(this, state.sourceId, false)
}

// v1.6.9: a stray value (pre-1.6.9 input box, webCoRE, Custom Action) no longer throws.
// Clearly named alternative to the capability's bare On/Off buttons.
def recording(String value) {
    switch (value?.toLowerCase()) {
        case "on": on(); break
        case "off": off(); break
        default: log.warn "Reolink Device Bridge (source ${state.sourceId}): Recording needs on or off"
    }
}

def on(ignored) { on() }
def off(ignored) { off() }

/**
 * PushableButton: loads the preset that owns this button number. Untyped because Rule Machine
 * passes BigDecimal and other callers may pass Integer or String; a missing number only warns.
 */
def push(btn) {
    Integer btnInt = null
    try { if (btn != null && "${btn}".trim()) btnInt = new BigDecimal("${btn}".trim()).toInteger() } catch (e) { }
    if (btnInt == null) {
        log.warn "Reolink Device Bridge (source ${state.sourceId}): Push needs a button number -- see the app's Recording Presets page"
        return
    }
    sendEvent(name: "pushed", value: btnInt, isStateChange: true)
    parent?.componentBridgeButtonPushed(this, state.sourceId, btnInt)
}

/** v1.6.9: one dropdown for the event connection. start/stopEventSubscription stay callable (app and old rules). */
def eventConnection(String action) {
    switch (action?.toLowerCase()) {
        case "start":
            startEventSubscription()
            break
        case "stop":
            stopEventSubscription()
            break
        case "restart":
            stopEventSubscription()
            runIn(3, "startEventSubscription")
            break
        default:
            log.warn "Reolink Device Bridge (source ${state.sourceId}): Event Connection needs restart, start or stop"
    }
}

/** Called by the app once the master record switch has been set. Updates both the standard "switch" attribute (Rule Machine, dashboards) and the custom "recordingEnabled" attribute. */
def receiveRecordingEnabled(Boolean enabled) {
    sendEvent(name: "switch", value: enabled ? "on" : "off")
    sendEvent(name: "recordingEnabled", value: enabled ? "enabled" : "disabled")
}

/** Called by the app once a named preset has been applied across every channel of this source. */
def receiveRecordingMode(String mode) {
    sendEvent(name: "recordingMode", value: mode)
}

/** Called by the app whenever a new preset is assigned a button number, so this device's numberOfButtons attribute (part of the PushableButton capability) reflects the highest number currently in use. */
def receiveNumberOfButtons(Integer n) {
    sendEvent(name: "numberOfButtons", value: n)
}

/**
 * Quick-reference summary of every preset defined for this source, plus
 * its permanent button number (e.g. "Away (Button 1), Home (Button 2)") --
 * stored in state (not sendEvent) so it shows up in this device's own
 * State Variables panel, next to Host/Aes Key Hex/etc., without needing to
 * open the app's Recording Presets page. Pushed fresh by the app every
 * time that page renders, so it can't go stale.
 */
def receivePresetsSummary(String summary) {
    state.availablePresets = summary
}

/** Per-channel success/failure/skipped summary from the app, e.g. "6/6 OK" or "5/6 OK, failed: ch3, skipped (no data): ch7". */
def receiveRecordingResult(String summary) {
    sendEvent(name: "lastRecordingResult", value: summary)
}

/** v1.6.6: last login result from the app, shown in State Variables ("OK, 10:42 AM" / "Failed: ..."). */
def receiveLoginStatus(String login, String lastGood = null) {
    state.login = login
    if (lastGood) state.lastGoodLogin = lastGood
}

// ============================================================================
// Persistent event subscription -- reconnect, per-channel push handling.
// ============================================================================

@Field static final String HEADER_MAGIC_HEX = "f0debc0a"
@Field static final List<Integer> XML_KEY = [0x1F, 0x2D, 0x3C, 0x4B, 0x5A, 0x69, 0x78, 0xFF]
@Field static final byte[] AES_IV_BYTES = "0123456789abcdef".getBytes("UTF-8")
@Field static final int HOST_CH_ID = 250

// v1.5.3: how long sendKeepalive() will tolerate zero real traffic
// (processBuffer() successfully parsing a message) before concluding the
// connection is dead -- likely half-open (remote side or a NAT mapping
// disappeared without a clean close/error) -- and forcing a reconnect,
// even though the socket itself may still report open. This watchdog
// existed since 1.4.4 but was found missing from this method entirely
// during a real 5-day-silent production outage; see the v1.5.3 header
// note and ParentApp.groovy's matching note for the full incident.
@Field static final int STALE_CONNECTION_THRESHOLD_SEC = 180

// v1.6.3: last genuine inbound traffic per bridge, in memory only. Never
// persisted, so no state/atomicState save can overwrite it with a stale copy.
@Field static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_REAL_TRAFFIC =
    new java.util.concurrent.ConcurrentHashMap<String, Long>()

// v1.6.6: source password, in memory only (never in state). Empty after a reboot
// or driver save; sourcePassword() then fetches it from the app again.
@Field static final java.util.concurrent.ConcurrentHashMap<String, String> SOURCE_SECRETS =
    new java.util.concurrent.ConcurrentHashMap<String, String>()

private String sourcePassword() {
    String key = device.id.toString()
    String pw = SOURCE_SECRETS.get(key)
    if (pw == null) {
        try { pw = parent?.componentGetSourceSecret(state.sourceId) } catch (e) { pw = null }
        if (pw != null) SOURCE_SECRETS.put(key, pw)
    }
    return pw ?: ""
}

private void markRealTraffic() {
    LAST_REAL_TRAFFIC.put(device.id.toString(), now())
}

/** Millis since last real traffic; a missing entry is treated as fresh and stamped now. */
private long msSinceRealTraffic() {
    Long last = LAST_REAL_TRAFFIC.get(device.id.toString())
    if (last == null) {
        markRealTraffic()
        return 0L
    }
    return now() - last
}

// v1.6.3: app log level, cached per bridge (0 Errors Only, 1 Normal, 2 Full).
// Missing (reboot/driver save) = unknown, so messages forward and the app decides.
@Field static final java.util.concurrent.ConcurrentHashMap<String, Integer> LOG_RANK =
    new java.util.concurrent.ConcurrentHashMap<String, Integer>()

void setLogRank(Integer rank) {
    if (rank != null) LOG_RANK.put(device.id.toString(), rank)
}

private boolean wantLog(int tier) {
    Integer rank = LOG_RANK.get(device.id.toString())
    return rank == null || rank >= tier
}

private void logN(msg) { if (wantLog(1)) parent?.logNormal(msg) }
private void logF(msg) { if (wantLog(2)) parent?.logFull(msg) }

// v1.6.10: per-message working data (receive buffer, last status per channel, AES key, nonce,
// resync bookkeeping), in memory only. Was in state, which Hubitat saves to its database after
// every run, i.e. on every push. Lost on reboot/driver save: the buffer just starts empty, and a
// missing AES key on a live connection triggers one clean reconnect (recoverLostKey()).
@Field static final java.util.concurrent.ConcurrentHashMap<String, Map> RUNTIME =
    new java.util.concurrent.ConcurrentHashMap<String, Map>()
@Field static final List<String> RETIRED_HOT_STATE = ["rxBufferHex", "chunkMsgCount", "resyncAttempts",
    "lastMsg", "last33", "last145", "corruptionEvents", "aesKeyHex", "nonce"]

private Map rt() {
    String k = device.id.toString()
    Map m = RUNTIME.get(k)
    if (m == null) {
        m = new java.util.concurrent.ConcurrentHashMap()
        Map prev = RUNTIME.putIfAbsent(k, m)
        if (prev != null) m = prev
    }
    return m
}

/** ConcurrentHashMap rejects null values; null removes the key. */
private void rtPut(String key, value) {
    if (value == null) rt().remove(key) else rt().put(key, value)
}

/** One-time move of pre-1.6.10 values from state into memory (so an update doesn't drop the live connection). */
private void migrateHotState() {
    Map r = rt()
    if (r.migrated) return
    r.migrated = true
    ["rxBufferHex": "rx", "last33": "last33", "last145": "last145", "aesKeyHex": "aesKeyHex", "nonce": "nonce"].each { sk, mk ->
        def v = state[sk]
        if (v != null && r[mk] == null) r.put(mk, v)
    }
    RETIRED_HOT_STATE.each { if (state[it] != null) state.remove(it) }
}

/** Message on a live connection but no key in memory (memory cleared): reconnect once for a fresh key. */
private void recoverLostKey() {
    Map r = rt()
    if (r.keyRecovery) return
    r.keyRecovery = true
    logN "Reolink Device Bridge (source ${state.sourceId}): encryption key no longer in memory, reconnecting for a fresh one"
    runIn(1, "reconnectAfterMemoryLoss")
}

def reconnectAfterMemoryLoss() {
    rt().remove("keyRecovery")
    if (state.eventSubscriptionWanted == false) return
    stopEventSubscription()
    startEventSubscription()
}

// v1.6.3: flapping detection, in memory. Warn once past FLAP_LIMIT reconnects
// in FLAP_WINDOW_MS; cleared (with an info line) once the window is quiet.
@Field static final int OFFLINE_WARN_ATTEMPT = 3
@Field static final int FLAP_LIMIT = 5
@Field static final long FLAP_WINDOW_MS = 600000L
@Field static final java.util.concurrent.ConcurrentHashMap<String, List> RECONNECT_TIMES =
    new java.util.concurrent.ConcurrentHashMap<String, List>()
@Field static final java.util.concurrent.ConcurrentHashMap<String, Boolean> FLAP_WARNED =
    new java.util.concurrent.ConcurrentHashMap<String, Boolean>()

private List recentReconnects() {
    long n = now()
    return (RECONNECT_TIMES.get(device.id.toString()) ?: []).findAll { n - (it as Long) < FLAP_WINDOW_MS }
}

private void noteReconnect() {
    String id = device.id.toString()
    List recent = recentReconnects()
    recent << now()
    RECONNECT_TIMES.put(id, recent)
    if (recent.size() > FLAP_LIMIT && !FLAP_WARNED.containsKey(id)) {
        FLAP_WARNED.put(id, true)
        log.warn "Reolink Device Bridge (source ${state.sourceId}): event connection unstable, " +
            "${recent.size()} reconnects in the last 10 minutes"
    }
}

private void clearFlapIfCalm() {
    String id = device.id.toString()
    if (!FLAP_WARNED.containsKey(id)) return
    List recent = recentReconnects()
    RECONNECT_TIMES.put(id, recent)
    if (!recent) {
        FLAP_WARNED.remove(id)
        log.info "Reolink Device Bridge (source ${state.sourceId}): event connection stable again"
    }
}

/** For the app: true only with live evidence (subscribed and real traffic within the stale threshold). */
def isEventConnectionAlive() {
    scrubState()
    if (state.stage != "SUBSCRIBED") return false
    Long last = LAST_REAL_TRAFFIC.get(device.id.toString())
    return last != null && (now() - last) <= (STALE_CONNECTION_THRESHOLD_SEC * 1000L)
}

@Field static final String LOGIN_XML =
    '<?xml version="1.0" encoding="UTF-8" ?><body><LoginUser version="1.1"><userName>%s</userName>' +
    '<password>%s</password><userVer>1</userVer></LoginUser><LoginNet version="1.1"><type>LAN</type>' +
    '<udpPort>0</udpPort></LoginNet></body>'

// v1.6.11: settings sent over this connection when a camera refuses them over HTTP (rspCode -13
// on Home Hub Mini battery cameras), as reolink_aio does. Replies are matched by message id.
@Field static final String CHANNEL_EXT_XML =
    '<?xml version="1.0" encoding="UTF-8" ?>\n<Extension version="1.1">\n<channelId>%d</channelId>\n</Extension>\n'
@Field static final long COMMAND_REPLY_TIMEOUT_MS = 15000L

private Map pendingCommands() {
    Map r = rt()
    if (r.pending == null) r.putIfAbsent("pending", new java.util.concurrent.ConcurrentHashMap())
    return r.pending
}

/** Sends cmdId for a channel; the reply goes to the app's componentBaichuanReply(ctx, status, body). False if not connected. */
def sendBaichuanCommand(Integer cmdId, Integer channel, String bodyXml, Map ctx) {
    if (state.stage != "SUBSCRIBED" || !rt().aesKeyHex || cmdId == null || channel == null) return false
    int chId = channel + 1
    byte[] extB = String.format(CHANNEL_EXT_XML, channel).getBytes("UTF-8")
    byte[] bodyB = (bodyXml ?: "").getBytes("UTF-8")
    int messId = nextMessId()
    byte[] header = buildHeader1464(cmdId, extB.length + bodyB.length, chId, messId, extB.length)
    String key = bytesToHex(buildMessIdBytes(chId, messId))
    pendingCommands().put(key, (ctx ?: [:]) + [cmdId: cmdId, at: now()])
    try {
        sendRaw(concatBytes(header, aesEncrypt(extB), aesEncrypt(bodyB)))
    } catch (e) {
        pendingCommands().remove(key)
        logF "Reolink Device Bridge (source ${state.sourceId}): cmd_id ${cmdId} send failed for ch ${channel}: ${e.message}"
        return false
    }
    logF "Reolink Device Bridge (source ${state.sourceId}): sent cmd_id ${cmdId} for ch ${channel}"
    return true
}

/** Reports commands with no reply (status null) so the app can warn. Runs on every keepalive. */
private void expirePendingCommands() {
    Map p = pendingCommands()
    long cutoff = now() - COMMAND_REPLY_TIMEOUT_MS
    p.keySet().toList().each { k ->
        Map ctx = p.get(k)
        if (ctx && (ctx.at as Long) < cutoff) {
            p.remove(k)
            parent?.componentBaichuanReply(this, ctx, null, null)
        }
    }
}

def installed() {}
def updated() {}

/** v1.6.6: password goes to memory only; state.password just shows "Saved, N characters". */
def configureConnection(String host, Integer port, String username, String password, Integer sourceId) {
    state.host = host
    state.port = port
    state.username = username
    state.sourceId = sourceId
    if (password != null) SOURCE_SECRETS.put(device.id.toString(), password)
    state.password = password ? "Saved, ${password.length()} characters" : "Not set"
    scrubState()
}

/**
 * v1.6.6: replaces a plain-text password left in state by an older driver (whatever order the
 * files were updated in) and drops retired keys. Cheap; runs on every keepalive and liveness check.
 */
private void scrubState() {
    String saved = state.password?.toString()
    if (saved && !saved.startsWith("Saved, ") && saved != "Not set") {
        SOURCE_SECRETS.putIfAbsent(device.id.toString(), saved)
        state.password = "Saved, ${saved.length()} characters"
    }
    if (state.lastRawReceiveTime != null) state.remove("lastRawReceiveTime")
    if (state.lastRealMessageAt != null) state.remove("lastRealMessageAt")
    migrateHotState()
}

/** Opens the event socket after cancelling any obsolete delayed close.
 * @param isReconnect true when continuing a retry cycle
 */
def startEventSubscription(boolean isReconnect = false) {
    // A previous intentional stop must never close this new socket later.
    unschedule("closeSocket")
    state.eventSubscriptionWanted = true

    String startMsg = "Reolink Device Bridge (source ${state.sourceId}): ${isReconnect ? 'reconnecting' : 'starting'}"
    if (isReconnect) { logF(startMsg) } else { logN(startMsg) }
    if (!isReconnect) {
        state.reconnectAttempts = 0
        unschedule("reconnectEventSubscription")
    }
    sendEvent(name: "connectionStatus", value: isReconnect ? "reconnecting" : "connecting")
    state.messIdCounter = 0
    state.stage = "CONNECTING"
    Map r = rt()
    r.migrated = true
    ["rx", "aesKeyHex", "nonce", "lastMsg", "corruption", "keyRecovery", "pending"].each { r.remove(it) }
    r.last33 = [:]
    r.last145 = [:]
    r.last291 = [:]
    RETIRED_HOT_STATE.each { if (state[it] != null) state.remove(it) }
    // v1.5.3: reset on every (re)connect so a fresh connection never starts
    // out already looking stale to sendKeepalive()'s watchdog below.
    markRealTraffic()
    atomicState.remove("lastRealMessageAt")   // retired, see v1.6.3 note
    try {
        interfaces.rawSocket.connect(state.host, state.port as int, byteInterface: true)
    } catch (e) {
        // v1.6.4: was a silent dead end (no retry, app never told). Now uses the reconnect ladder.
        logF "Reolink Device Bridge (source ${state.sourceId}): could not open socket -- ${e.message}"
        state.stage = null
        sendEvent(name: "connectionStatus", value: "reconnecting")
        parent?.componentEventConnectionStatus(this, state.sourceId, "reconnecting")
        scheduleReconnect()
        return
    }
    runIn(1, "sendNonceRequest")
    runIn(15, "flowTimeoutCheck")
}

/** Cancels pending connection work and closes the event socket immediately. */
def stopEventSubscription() {
    state.eventSubscriptionWanted = false
    unschedule("sendNonceRequest")
    unschedule("closeSocket")

    logN "Reolink Device Bridge (source ${state.sourceId}): stopping event subscription"
    unschedule("sendKeepalive")
    unschedule("flowTimeoutCheck")
    unschedule("reconnectEventSubscription")
    state.reconnectAttempts = 0
    if (state.stage == "SUBSCRIBED") {
        try {
            def messId = nextMessId()
            byte[] header = buildHeader1464(2, 0, HOST_CH_ID, messId, 0)
            sendRaw(header)
        } catch (e) { /* best effort logout, fine either way */ }
    }
    state.stage = "DONE"
    closeSocket()
    sendEvent(name: "connectionStatus", value: "disconnected")
    parent?.componentEventConnectionStatus(this, state.sourceId, "disconnected")
}

def socketStatus(String status) {
    if (status?.contains("error") || status?.contains("close")) {
        if (state.stage == "SUBSCRIBED") {
            unschedule("sendKeepalive")
            // v1.5.3 refinement: this is the TRIGGER for a reconnect
            // cycle, not yet evidence of a real problem -- a closed/errored
            // socket is routine over a long-lived connection (network
            // blip, a lease renewal, etc.) and self-heals via the normal
            // reconnect flow below almost always. scheduleReconnect()
            // itself escalates to log.warn/log.error if it actually takes
            // more than one attempt.
            logF "Reolink Device Bridge (source ${state.sourceId}): connection lost, scheduling reconnect"
            noteReconnect()
            sendEvent(name: "connectionStatus", value: "reconnecting")
            parent?.componentEventConnectionStatus(this, state.sourceId, "reconnecting")
            scheduleReconnect()
        } else if (state.stage && state.stage != "DONE") {
            // v1.6.4: was a dead end; now retries like any other failure.
            logF "Reolink Device Bridge (source ${state.sourceId}): socket closed/errored mid-handshake (stage was ${state.stage}), scheduling reconnect"
            unschedule("flowTimeoutCheck")
            state.stage = null
            sendEvent(name: "connectionStatus", value: "reconnecting")
            parent?.componentEventConnectionStatus(this, state.sourceId, "reconnecting")
            scheduleReconnect()
        }
        state.stage = null
    }
}

@Field static final int MAX_RECONNECT_ATTEMPTS = 10

/**
 * Attempts are silent; the 3rd consecutive attempt warns that the source is
 * offline, giving up is an error, and the next successful subscribe logs "back
 * online". v1.6.4: warning and error fire once per outage, not per retry cycle.
 */
private void scheduleReconnect() {
    int attempt = (state.reconnectAttempts ?: 0) + 1
    state.reconnectAttempts = attempt
    if (attempt > MAX_RECONNECT_ATTEMPTS) {
        if (!state.giveUpLogged) {
            state.giveUpLogged = true
            log.error "Reolink Device Bridge (source ${state.sourceId}): event connection offline, giving up after ${MAX_RECONNECT_ATTEMPTS} attempts -- polling until it can reconnect (retried automatically)"
        } else {
            logF "Reolink Device Bridge (source ${state.sourceId}): still offline after another ${MAX_RECONNECT_ATTEMPTS} attempts, polling"
        }
        sendEvent(name: "connectionStatus", value: "disconnected")
        parent?.componentEventConnectionStatus(this, state.sourceId, "disconnected")
        return
    }
    int delaySec = Math.min(300, 5 * (int) Math.pow(2, attempt - 1))
    if (attempt == OFFLINE_WARN_ATTEMPT && !state.offlineWarned) {
        state.offlineWarned = true
        log.warn "Reolink Device Bridge (source ${state.sourceId}): event connection offline after ${attempt - 1} " +
            "failed reconnects, still retrying (polling meanwhile)"
    }
    logF "Reolink Device Bridge (source ${state.sourceId}): reconnect attempt ${attempt}/${MAX_RECONNECT_ATTEMPTS} in ${delaySec}s"
    runIn(delaySec, "reconnectEventSubscription")
}

/** v1.6.4: app-driven retry of a given-up or stuck source. Fresh attempt ladder; warn-once flags kept. */
def retryEventSubscription() {
    unschedule("reconnectEventSubscription")
    state.reconnectAttempts = 0
    startEventSubscription(true)
}

/** Retries only while event subscription remains requested. */
def reconnectEventSubscription() {
    if (state.eventSubscriptionWanted == false) return
    startEventSubscription(true)
}

/**
 * A timeout during the handshake itself (e.g. the very first connection
 * attempt never getting a response) gets the same backoff-retry treatment
 * as a post-subscribe drop, so a stuck-at-"disconnected" source always
 * eventually retries instead of going permanently silent.
 */
def flowTimeoutCheck() {
    if (state.stage && state.stage != "DONE" && state.stage != "SUBSCRIBED") {
        // v1.5.3 refinement: trigger-level event, same reasoning as
        // socketStatus() above -- silent by default, scheduleReconnect()
        // escalates if it doesn't resolve on the first attempt.
        logF "Reolink Device Bridge (source ${state.sourceId}): timed out waiting for handshake response (stage ${state.stage}), scheduling reconnect"
        try { interfaces.rawSocket.close() } catch (e) { }
        state.stage = null
        sendEvent(name: "connectionStatus", value: "reconnecting")
        parent?.componentEventConnectionStatus(this, state.sourceId, "reconnecting")
        scheduleReconnect()
    }
}

def sendNonceRequest() {
    state.stage = "AWAITING_NONCE"
    byte[] header = buildHeader1465(1, HOST_CH_ID, nextMessId())
    sendRaw(header)
}

def sendLoginRequest() {
    state.stage = "AWAITING_LOGIN"
    def userHash = md5Modern("${state.username}${rt().nonce}")
    def passHash = md5Modern("${sourcePassword()}${rt().nonce}")
    String xml = String.format(LOGIN_XML, userHash, passHash)
    byte[] bodyBytes = xml.getBytes("UTF-8")
    byte[] encBody = xorBaichuan(bodyBytes, HOST_CH_ID)
    byte[] header = buildHeader1464(1, bodyBytes.length, HOST_CH_ID, nextMessId(), 0)
    sendRaw(concatBytes(header, encBody))
}

def sendSubscribe() {
    state.stage = "AWAITING_SUBSCRIBE"
    byte[] header = buildHeader1464(31, 0, 251, nextMessId(), 0)
    sendRaw(header)
}

/**
 * Full-tier heartbeat line here (via parent?.logFull(...)) -- without it, a
 * genuinely-connected-but-quiet source (nothing has triggered a real event
 * push in a while) looked identical in the logs whether it was working
 * perfectly or silently stuck, even with Log level set to Full. This gives
 * a real "still alive" signal on a predictable cadence, throttled to once
 * per CONNECTION rather than once per channel, so it doesn't reproduce the
 * old per-camera poll-spam problem event mode was built to avoid.
 */
def sendKeepalive() {
    scrubState()
    if (state.stage == "SUBSCRIBED") {
        // v1.5.3: staleness watchdog, restored -- see
        // STALE_CONNECTION_THRESHOLD_SEC's declaration for the full
        // incident this addresses. Checked BEFORE sending a fresh
        // keepalive: a half-open connection can still successfully queue
        // an outbound send even though nothing real has come back in a
        // long time, so "the send succeeded" is not evidence the
        // connection is alive.
        if (msSinceRealTraffic() > (STALE_CONNECTION_THRESHOLD_SEC * 1000L)) {
            // v1.5.3 refinement: this is the TRIGGER for a reconnect, not
            // yet evidence of a real problem -- silent by default
            // (logNormal, not log.warn). scheduleReconnect() below is what
            // actually escalates to log.warn/log.error, based on whether
            // this resolves on the first attempt or needs more.
            logF "Reolink Device Bridge (source ${state.sourceId}): no real traffic received in " +
                "${STALE_CONNECTION_THRESHOLD_SEC}s despite reporting connected, treating as a dead " +
                "(likely half-open) connection and forcing a reconnect"
            noteReconnect()
            unschedule("sendKeepalive")
            try { interfaces.rawSocket.close() } catch (e) { }
            state.stage = null
            sendEvent(name: "connectionStatus", value: "reconnecting")
            parent?.componentEventConnectionStatus(this, state.sourceId, "reconnecting")
            scheduleReconnect()
            return
        }
        try {
            byte[] header = buildHeader1464(93, 0, HOST_CH_ID, nextMessId(), 0)
            sendRaw(header)
            logF "Reolink Device Bridge (source ${state.sourceId}): keepalive sent, connection healthy"
            clearFlapIfCalm()
            expirePendingCommands()
        } catch (e) {
            log.warn "Reolink Device Bridge (source ${state.sourceId}): keepalive send failed: ${e.message}"
        }
    }
    // v1.5.3: moved outside/after the SUBSCRIBED branch above -- this used
    // to sit inside an early `if (state.stage != "SUBSCRIBED") return`,
    // which meant an unexpected stage change between ticks could silently
    // stop this job from ever rescheduling itself, with nothing left to
    // notice or recover. Now always re-arms regardless of which branch
    // ran above (including the stale-reconnect branch, which already
    // returns separately via scheduleReconnect()'s own path).
    runIn(25, "sendKeepalive")
}

/**
 * v1.5.3: read-only liveness check for the app's independent audit job
 * (ParentApp.groovy's auditEventConnections()) -- deliberately separate
 * ground truth from this bridge's own self-reported connectionStatus
 * attribute, which is exactly what silently lied "connected" during the
 * incident this whole v1.5.3 release addresses.
 */
def isEventConnectionStale(Integer thresholdSec) {
    if (state.stage != "SUBSCRIBED") return false
    return msSinceRealTraffic() > (thresholdSec * 1000L)
}

private void handlePushedEvent(int cmdId, String bodyText) {
    // v1.6.3: keepalive replies are expected; processBuffer() already counted them as traffic.
    if (cmdId == 93) {
        logF "Reolink Device Bridge (source ${state.sourceId}): keepalive acknowledged (cmd_id 93)"
        return
    }
    if (!bodyText?.trim()) {
        logF "Reolink Device Bridge (source ${state.sourceId}): cmd_id ${cmdId} body empty/undecoded, skipping"
        return
    }
    if (cmdId == 33) {
        def elements = findAllChannelElements(bodyText, "AlarmEvent")
        if (!elements) return
        logF "Reolink Device Bridge (source ${state.sourceId}): cmd_id 33 push parsed OK, channels present: ${elements.keySet().sort()}"
        def last = rt().last33 ?: [:]
        elements.each { chId, elem ->
            def status = elem?.status?.text()
            def aiType = elem?.AItype?.text()
            // status/AItype are comma-separated lists ("MD,visitor"); the app splits them (v1.6.12).
            logF "Reolink Device Bridge (source ${state.sourceId}) ch ${chId}: cmd_id 33 raw event -- status='${status}', AItype='${aiType}'"
            def key = chId.toString()
            def prev = last[key]
            if (prev == null || prev.status != status || prev.aiType != aiType) {
                last[key] = [status: status, aiType: aiType]
                parent?.componentEventChannelUpdate(this, state.sourceId, chId, status, aiType)
            }
        }
        rt().last33 = last
    } else if (cmdId == 145) {
        def elements = findAllChannelElements(bodyText, "ChannelInfo")
        if (!elements) return
        def last = rt().last145 ?: [:]
        elements.each { chId, elem ->
            def sleepState = elem?.state?.text()
            def key = chId.toString()
            if (last[key] != sleepState) {
                last[key] = sleepState
                parent?.componentEventSleepUpdate(this, state.sourceId, chId, sleepState)
            }
        }
        rt().last145 = last
    } else if (cmdId == 291) {
        // v1.6.11: floodlight status (<channel>/<status> pairs), light on/off in real time, as reolink_aio reads it.
        Map last = rt().last291 ?: [:]
        try {
            new XmlSlurper().parseText(bodyText).depthFirst().findAll { it.channel.size() == 1 && it.status.size() == 1 }.each { e ->
                String ch = e.channel.text()
                String st = e.status.text()
                if (ch?.isInteger() && st?.isInteger() && last[ch] != st) {
                    last[ch] = st
                    parent?.componentEventSpotlightUpdate(this, state.sourceId, ch.toInteger(), st.toInteger())
                }
            }
        } catch (ex) {
            logF "Reolink Device Bridge (source ${state.sourceId}): failed to parse floodlight push: ${ex.message}"
        }
        rt().last291 = last
    } else {
        // Any cmd_id other than 33/145 is logged (raw cmd_id + a body
        // snippet) rather than dropped silently -- meaning if a doorbell
        // ring ever arrives under some OTHER cmd_id, it's discoverable
        // instead of vanishing without a trace.
        logF "Reolink Device Bridge (source ${state.sourceId}): unrecognized cmd_id ${cmdId} pushed " +
            "(not currently handled) -- body (first 300 chars): ${bodyText.take(300)}"
    }
}

private Map findAllChannelElements(String xml, String elementName) {
    if (!xml) return [:]
    try {
        def root = new XmlSlurper().parseText(xml)
        def result = [:]
        root.depthFirst().findAll { it.name() == elementName }.each { elem ->
            def chIdText = elem.channelId?.text()
            if (chIdText != null && chIdText.isInteger()) {
                result[chIdText.toInteger()] = elem
            }
        }
        return result
    } catch (e) {
        logF "Reolink Device Bridge (source ${state.sourceId}): failed to parse pushed event XML: ${e.message}"
        return [:]
    }
}

def parse(String message) {
    if (!message) return
    // Tracks which message number within THIS TCP read is currently being
    // processed, and how many resync attempts have been made for this read
    // -- see processBuffer() below.
    Map r = rt()
    if (!r.migrated) migrateHotState()
    r.chunkMsgCount = 0
    r.resyncAttempts = 0
    r.rx = (r.rx ?: "") + message
    processBuffer()
}

private void processBuffer() {
    Map r = rt()
    String hex = r.rx ?: ""
    if (hex.length() < 40) return
    if (hex.substring(0, 8).toLowerCase() != HEADER_MAGIC_HEX) {
        // Confirmed via multi-day soak testing: this pattern is benign,
        // self-recovering Hub-side wire noise under load (large multi-
        // channel broadcasts), not a client-side parsing bug -- kept at
        // debug tier accordingly. Still tracks the rolling 60s count for
        // the log detail, useful context if a report ever comes in.
        def nowMs = now()
        def recent = (r.corruption ?: []).findAll { (nowMs - (it as Long)) < 60000 }
        recent << nowMs
        r.corruption = recent
        def chunkPos = (r.chunkMsgCount ?: 0)
        def detail = "Chunk position ${chunkPos} (0 = first message in this TCP read, 1+ = Nth message " +
            "after successfully parsing ${chunkPos} earlier message(s) from the SAME read). Previous msg: " +
            "${r.lastMsg}. Failing buffer (first 240 hex chars): ${hex.take(240)}"
        logF "Reolink Device Bridge (source ${state.sourceId}): invalid magic header, dropping buffer " +
            "(${recent.size()} in the last 60s). ${detail}"

        // Scans forward within the SAME buffer for the next real occurrence
        // of the magic header and resyncs from there instead of discarding
        // everything -- these failures tend to happen deep inside large
        // pushes, so the rest of the buffer often still contains a genuine,
        // recoverable message. Capped at 20 resync attempts per incoming
        // read so a buffer that's genuinely all noise can't loop
        // indefinitely -- that case IS still worth a warn, since it means
        // real data was lost, not just a routine self-recovering blip.
        def attempts = (r.resyncAttempts ?: 0) + 1
        r.resyncAttempts = attempts
        if (attempts > 20) {
            log.warn "Reolink Device Bridge (source ${state.sourceId}): giving up resyncing after 20 attempts " +
                "this read, dropping buffer entirely"
            r.rx = ""
            return
        }
        int nextIdx = findNextMagicHeaderIndex(hex, 2)
        if (nextIdx > 0) {
            logF "Reolink Device Bridge (source ${state.sourceId}): resyncing -- found next magic header " +
                "${(nextIdx / 2) as Integer} bytes in, discarding only the leading garbage instead of the whole buffer"
            r.rx = hex.substring(nextIdx)
            processBuffer()
        } else {
            r.rx = hex.length() > 8 ? hex.substring(hex.length() - 8) : hex
        }
        return
    }
    int cmdId = leHexToInt(hex.substring(8, 16))
    int bodyLen = leHexToInt(hex.substring(16, 24))
    int chId = Integer.parseInt(hex.substring(24, 26), 16)
    String encTypeMarker = hex.substring(32, 36).toLowerCase()
    String messageClass = hex.substring(36, 40).toLowerCase()

    int headerLenBytes
    int payloadOffset
    if (messageClass == "1466") {
        headerLenBytes = 20
        payloadOffset = bodyLen
    } else if (messageClass == "1464" || messageClass == "0000") {
        headerLenBytes = 24
        if (hex.length() < 48) return
        payloadOffset = leHexToInt(hex.substring(40, 48))
        if (payloadOffset == 0) payloadOffset = bodyLen
    } else {
        // A genuinely unrecognized message type -- a real protocol
        // surprise, kept at warn.
        log.warn "Reolink Device Bridge (source ${state.sourceId}): unknown message class '${messageClass}', dropping buffer"
        r.rx = ""
        return
    }

    int totalHexNeeded = (headerLenBytes + bodyLen) * 2
    if (hex.length() < totalHexNeeded) return

    String bodyHex = hex.substring(headerLenBytes * 2, totalHexNeeded)
    r.rx = hex.substring(totalHexNeeded)

    r.lastMsg = [cmdId: cmdId, bodyLen: bodyLen, headerLenBytes: headerLenBytes,
        payloadOffset: payloadOffset, messageClass: messageClass, totalHexNeeded: totalHexNeeded]
    r.chunkMsgCount = (r.chunkMsgCount ?: 0) + 1

    String bodyText = decryptBody(bodyHex, encTypeMarker, chId, cmdId)
    Map cmdCtx = r.pending ? pendingCommands().remove(hex.substring(24, 32).toLowerCase()) : null
    if (cmdCtx != null) {
        int status = leHexToInt(hex.substring(32, 36))
        logF "Reolink Device Bridge (source ${state.sourceId}): reply to cmd_id ${cmdId}, status ${status}"
        parent?.componentBaichuanReply(this, cmdCtx, status, bodyText)
    } else {
        handleMessage(cmdId, bodyText)
    }
    // v1.5.3: real ground truth for sendKeepalive()'s staleness watchdog --
    // a successfully-parsed message is genuine traffic, regardless of
    // cmd_id, so this is stamped here rather than only on cmd_id 33/145
    // pushes.
    markRealTraffic()

    if ((r.rx ?: "").length() >= 40) {
        processBuffer()
    }
}

private String decryptBody(String bodyHex, String encTypeMarker, int chId, int cmdId = -1) {
    if (!bodyHex) return ""
    // v1.6.11: any body, not just 02dd/03dd. Hub pushes arrive marked 'c800' and are AES too.
    if (!rt().aesKeyHex && state.stage == "SUBSCRIBED") {
        recoverLostKey()
        return ""
    }
    byte[] bodyBytes = hexToBytes(bodyHex)
    String result = null
    try {
        if (encTypeMarker in ["01dd", "12dd"]) {
            result = new String(xorBaichuan(bodyBytes, chId), "UTF-8")
        } else if (encTypeMarker in ["02dd", "03dd"]) {
            result = new String(aesDecrypt(bodyBytes), "UTF-8")
        } else if (encTypeMarker == "00dd") {
            result = new String(bodyBytes, "UTF-8")
        }
    } catch (e) { result = null }
    if (result?.trim()?.startsWith("<?xml")) return result

    if (encTypeMarker != "02dd" && encTypeMarker != "03dd") {
        try {
            def aesTry = new String(aesDecrypt(bodyBytes), "UTF-8")
            if (aesTry?.trim()?.startsWith("<?xml")) return aesTry
        } catch (e) { /* fall through */ }
    }
    if (encTypeMarker != "01dd" && encTypeMarker != "12dd") {
        try {
            def bcTry = new String(xorBaichuan(bodyBytes, chId), "UTF-8")
            if (bcTry?.trim()?.startsWith("<?xml")) return bcTry
        } catch (e) { /* fall through */ }
    }
    // v1.6.5: harmless ('c800' is a reply status code, not an encryption marker); Full only.
    logF "Reolink Device Bridge (source ${state.sourceId}): unable to decrypt body (marker '${encTypeMarker}', cmd_id ${cmdId})"
    return ""
}

@Field static final Map<String, Integer> STAGE_EXPECTED_CMD_ID = [
    "AWAITING_NONCE": 1,
    "AWAITING_LOGIN": 1,
    "AWAITING_SUBSCRIBE": 31
]

private void handleMessage(int cmdId, String bodyText) {
    if (state.stage == "SUBSCRIBED") {
        handlePushedEvent(cmdId, bodyText)
        return
    }
    Integer expectedCmdId = STAGE_EXPECTED_CMD_ID[state.stage]
    if (expectedCmdId != null && cmdId != expectedCmdId) return

    switch (state.stage) {
        case "AWAITING_NONCE":
            def nonce = findXmlValue(bodyText, "nonce")
            if (!nonce) {
                // v1.6.4: was a dead end; now retries (the ladder warns if it persists).
                logF "Reolink Device Bridge (source ${state.sourceId}): no nonce in handshake response, scheduling reconnect"
                unschedule("flowTimeoutCheck")
                state.stage = null
                sendEvent(name: "connectionStatus", value: "reconnecting")
                parent?.componentEventConnectionStatus(this, state.sourceId, "reconnecting")
                scheduleReconnect()
                return
            }
            rtPut("nonce", nonce)
            rtPut("aesKeyHex", bytesToHex(deriveAesKey(nonce, sourcePassword())))
            sendLoginRequest()
            break
        case "AWAITING_LOGIN":
            sendSubscribe()
            break
        case "AWAITING_SUBSCRIBE":
            def wasReconnect = (state.reconnectAttempts ?: 0) > 0
            def wasOffline = state.offlineWarned == true
            state.stage = "SUBSCRIBED"
            state.reconnectAttempts = 0
            state.remove("offlineWarned")
            state.remove("giveUpLogged")
            unschedule("flowTimeoutCheck")
            runIn(25, "sendKeepalive")
            if (wasOffline) {
                log.info "Reolink Device Bridge (source ${state.sourceId}): event connection back online"
            } else if (wasReconnect) {
                logF "Reolink Device Bridge (source ${state.sourceId}): event subscription ACTIVE (reconnected)"
            } else {
                logN "Reolink Device Bridge (source ${state.sourceId}): event subscription ACTIVE"
            }
            sendEvent(name: "connectionStatus", value: "connected")
            parent?.componentEventConnectionStatus(this, state.sourceId, "connected")
            break
        default:
            break
    }
}

private int nextMessId() {
    state.messIdCounter = ((state.messIdCounter ?: 0) + 1) % 16777216
    return state.messIdCounter
}

private void sendRaw(byte[] data) {
    interfaces.rawSocket.sendMessage(bytesToHex(data))
}

private byte[] buildHeader1465(int cmdId, int chId, int messId) {
    byte[] magic = hexToBytes(HEADER_MAGIC_HEX)
    byte[] cmdIdB = intToBytesLE(cmdId, 4)
    byte[] messLenB = intToBytesLE(0, 4)
    byte[] messIdB = buildMessIdBytes(chId, messId)
    byte[] encAndClass = hexToBytes("12dc1465")
    return concatBytes(magic, cmdIdB, messLenB, messIdB, encAndClass)
}

private byte[] buildHeader1464(int cmdId, int bodyLen, int chId, int messId, int payloadOffset) {
    byte[] magic = hexToBytes(HEADER_MAGIC_HEX)
    byte[] cmdIdB = intToBytesLE(cmdId, 4)
    byte[] messLenB = intToBytesLE(bodyLen, 4)
    byte[] messIdB = buildMessIdBytes(chId, messId)
    byte[] statusAndClass = hexToBytes("00001464")
    byte[] payloadOffB = intToBytesLE(payloadOffset, 4)
    return concatBytes(magic, cmdIdB, messLenB, messIdB, statusAndClass, payloadOffB)
}

private byte[] buildMessIdBytes(int chId, int messId) {
    byte[] out = new byte[4]
    out[0] = (byte)(chId & 0xFF)
    byte[] counter = intToBytesLE(messId, 3)
    out[1] = counter[0]; out[2] = counter[1]; out[3] = counter[2]
    return out
}

private byte[] concatBytes(byte[]... arrays) {
    def out = new java.io.ByteArrayOutputStream()
    arrays.each { out.write(it) }
    return out.toByteArray()
}

private byte[] intToBytesLE(int value, int numBytes) {
    byte[] out = new byte[numBytes]
    for (int i = 0; i < numBytes; i++) {
        out[i] = (byte)((value >> (8 * i)) & 0xFF)
    }
    return out
}

/**
 * Scans a hex string for the next byte-aligned occurrence of the magic
 * header, starting at fromHexIndex (must itself be even/byte-aligned).
 * Returns the hex-string index if found, or -1 if the header doesn't appear
 * anywhere in the remaining buffer. Used by processBuffer()'s magic-header
 * failure handling to resync from mid-buffer instead of discarding
 * everything.
 */
private int findNextMagicHeaderIndex(String hex, int fromHexIndex) {
    int i = fromHexIndex
    while (i + 8 <= hex.length()) {
        if (hex.substring(i, i + 8).equalsIgnoreCase(HEADER_MAGIC_HEX)) {
            return i
        }
        i += 2
    }
    return -1
}

private int leHexToInt(String hex) {
    int numBytes = hex.length() / 2
    long value = 0
    for (int i = numBytes - 1; i >= 0; i--) {
        int b = Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16)
        value = (value << 8) | b
    }
    return (int) value
}

private byte[] xorBaichuan(byte[] buf, int offset) {
    offset = offset % 256
    byte[] out = new byte[buf.length]
    for (int idx = 0; idx < buf.length; idx++) {
        int key = XML_KEY[(offset + idx) % XML_KEY.size()]
        int b = (buf[idx] & 0xFF) ^ key ^ offset
        out[idx] = (byte)(b & 0xFF)
    }
    return out
}

private byte[] aesDecrypt(byte[] body) {
    if (!body || body.length == 0) return new byte[0]
    def cipher = Cipher.getInstance("AES/CFB/NoPadding")
    String keyHex = rt().aesKeyHex
    if (!keyHex) throw new IllegalStateException("no AES key in memory")
    def keySpec = new SecretKeySpec(hexToBytes(keyHex), "AES")
    def ivSpec = new IvParameterSpec(AES_IV_BYTES)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
    return cipher.doFinal(body)
}

private byte[] aesEncrypt(byte[] body) {
    if (!body || body.length == 0) return new byte[0]
    String keyHex = rt().aesKeyHex
    if (!keyHex) throw new IllegalStateException("no AES key in memory")
    def cipher = Cipher.getInstance("AES/CFB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(hexToBytes(keyHex), "AES"), new IvParameterSpec(AES_IV_BYTES))
    return cipher.doFinal(body)
}

private String md5Modern(String input) {
    def digest = MessageDigest.getInstance("MD5")
    def bytes = digest.digest(input.getBytes("UTF-8"))
    def hex = bytes.collect { String.format("%02x", it & 0xFF) }.join()
    return hex.substring(0, 31).toUpperCase()
}

private byte[] deriveAesKey(String nonce, String pw) {
    String keyStr = md5Modern("${nonce}-${pw}").substring(0, 16)
    return keyStr.getBytes("UTF-8")
}

private byte[] hexToBytes(String hex) {
    if (!hex) return new byte[0]
    byte[] result = new byte[hex.length() / 2]
    for (int i = 0; i < result.length; i++) {
        result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16)
    }
    return result
}

private String bytesToHex(byte[] bytes) {
    return bytes.collect { String.format("%02x", it & 0xFF) }.join()
}

private String findXmlValue(String xml, String tagName) {
    if (!xml) return null
    try {
        def root = new XmlSlurper().parseText(xml)
        def node = root.depthFirst().find { it.name() == tagName }
        return node?.text()?.trim()
    } catch (e) {
        return null
    }
}

def closeSocket() {
    try { interfaces.rawSocket.close() } catch (e) { }
}
