/**
 * Reolink Standalone Devices (Internal Group Driver)
 * Version: 1.5.3
 *
 * NOT user-facing. Created and managed automatically by the Reolink
 * Integration parent app -- exactly ONE instance total, shared across every
 * standalone (non-Hub/NVR) source.
 *
 * Purpose: purely a nesting anchor in the Devices list. Each standalone
 * camera/doorbell still gets its own independent "Reolink Device Bridge"
 * (its own persistent event connection -- Hubitat's rawSocket is one-
 * connection-per-driver-instance, so that part can't be shared). What CAN
 * be shared is where those bridges nest: instead of each standalone bridge
 * sitting directly under the app (unnested, unlike an NVR/Hub's channels
 * which all nest under one shared bridge), every standalone source's bridge
 * becomes a child of THIS device instead -- one collapsible entry holding
 * every standalone camera/doorbell's bridge, instead of N separate unnested
 * bridges.
 *
 * v1.5.1 -- HOTFIX: six passthrough gaps found and closed, all the same
 * root-cause pattern as v1.4.3/v1.4.6 below -- ReolinkDeviceBridge.groovy
 * calls parent?.componentX(...) for a method this file never mirrored.
 *  1. componentEventConnectionStatus() -- reported in production:
 *     MissingMethodException the moment a standalone doorbell's event
 *     subscription came up (also fires on every connecting/reconnecting/
 *     disconnected transition). Every standalone source has been silently
 *     losing this call since whichever release first added it to the
 *     bridge (before v1.5.0; exact version not pinned down).
 *  2. componentEventSleepUpdate() -- same gap for sleep-status pushes
 *     (cmd_id=145). Lower-probability in practice, since standalone
 *     battery devices are a closed, unsupported case (see the app's Tips
 *     page), but a wired standalone doorbell could still trigger this.
 *  3-6. componentLoadPreset(), componentSetRecordingEnabled(),
 *     componentBridgeButtonPushed(), componentGetPresetNames() -- all four
 *     added to the bridge in v1.5.0 for NVR recording control. The app's
 *     UI only ever links to that feature for Hub/NVR sources
 *     (discoverPage()'s Recording Control section is gated on src.isHub),
 *     so none of v1.5.0's beta testing (done entirely against a real
 *     RLN16-410 NVR) ever exercised these against a standalone bridge.
 *     The bridge's Switch/PushableButton capabilities are declared
 *     unconditionally on every bridge though (standalone included), so
 *     on()/off()/push()/loadPreset() were reachable right now via Rule
 *     Machine or the device page on a standalone source and would have
 *     thrown the same MissingMethodException as #1 above.
 *     componentGetPresetNames() doesn't crash -- the bridge's own
 *     getAvailablePresetNames() wraps that call in a try/catch -- but
 *     silently left the "Preset to load" dropdown permanently empty for a
 *     standalone source instead of erroring loudly.
 *
 * v1.4.6 -- HOTFIX: a standalone wired doorbell was throwing
 * MissingMethodException on componentEventChannelUpdate() for every real-
 * time event push. Same root cause as the v1.4.3 fix, just a different
 * method: ReolinkDeviceBridge.groovy's own componentEventChannelUpdate()
 * calls parent?.componentEventChannelUpdate(...), which for a standalone
 * source resolves to THIS device -- and this driver never forwarded that
 * one method, only the componentX() set that existed as of 1.4.3. That set
 * predates componentEventChannelUpdate(), which was added to the app/bridge
 * later for the real-time push path and never got mirrored here. Motion/AI
 * polling was unaffected (push-based, doesn't route through this passthrough
 * at all), but every real event push for a standalone source failed inside
 * the bridge driver itself, before ever reaching the app. Fixed by adding
 * the missing passthrough, mirroring the exact signature used everywhere
 * else in this integration.
 * v1.4.3 -- HOTFIX: a standalone source's bridge has THIS device as its
 * real Hubitat parent (not the app directly) -- same root cause as the
 * v1.4.1 logNormal()/logFull() fix, but this time for every OTHER
 * component command a device sends upward (takeSnapshot, refresh, PTZ,
 * spotlight, night vision, siren, PIR, battery/abilities checks, poll and
 * snapshot interval). ReolinkDeviceBridge.groovy's own componentX()
 * passthroughs call parent?.componentX(...), which for a standalone
 * source's bridge resolves to THIS device -- and this driver never defined
 * any of them, only logNormal()/logFull(). Every one of those calls threw
 * a MissingMethodException inside the bridge driver itself, before ever
 * reaching the app -- which is why the app's own logs showed nothing at
 * all for e.g. a failed Take Snapshot (the app method is never reached;
 * the failure shows up as a device error under the BRIDGE's own Logs tab
 * instead). Hub/NVR bridges (parented directly off the app) were never
 * affected. Reported against a standalone doorbell whose snapshotUrl
 * attribute never populated and Take Snapshot logged nothing, but this
 * affected every standalone source since the v1.3.8 bridge/group
 * restructuring, not something v1.4.2 introduced -- just newly surfaced.
 * Fixed by adding the full set of componentX() passthroughs already used
 * by ReolinkDeviceBridge.groovy's own equivalent forwarding to the app,
 * mirrored here one level up.
 * v1.4.1 -- FIX: a standalone source's bridge has THIS device as its real
 * Hubitat parent (not the app directly), so its parent?.logNormal()/
 * logFull() calls (used throughout ReolinkDeviceBridge.groovy, including
 * the first line of startEventSubscription()) threw a MissingMethodException
 * every time -- this driver had no such methods at all, meaning the actual
 * socket connection never even started for ANY standalone source. Hub/NVR
 * bridges (parented directly off the app) were never affected. Found via
 * real-hardware testing of a standalone battery camera (Argus 4 Pro, whose
 * HTTP CGI API is fully absent -- see the app's Tips page) while testing
 * whether its Baichuan event port might still respond, which surfaced this
 * as a blocking bug before the socket attempt could even happen. Fixed by
 * adding logNormal()/logFull() passthroughs that forward to this device's
 * own parent (the app) -- same pattern already used for
 * createBridgeDevice()/removeBridgeDevice() below.
 */
metadata {
    definition(name: "Reolink Standalone Devices", namespace: "jdthomas24", author: "Jason", component: true) {
        capability "Actuator"
    }
}
def installed() {}
def updated() {}
/**
 * Creates a standalone source's "Reolink Device Bridge" as THIS device's
 * own child, called by the app's ensureSourceBridge(). Mirrors the exact
 * pattern ReolinkDeviceBridge.groovy itself uses for createChannelDevice()
 * -- addChildDevice() must run in the owning device's own execution
 * context, so this method exists here rather than the app calling
 * addChildDevice() on a held reference from outside.
 */
def createBridgeDevice(String dni, String label, Integer sourceId) {
    def bridge = addChildDevice("jdthomas24", "Reolink Device Bridge", dni, [
        name: label, label: label, isComponent: true
    ])
    bridge.updateDataValue("sourceId", "${sourceId}")
    return bridge
}
def removeBridgeDevice(String dni) {
    deleteChildDevice(dni)
}

/**
 * v1.4.1: logging passthrough for standalone bridges -- see the header note
 * above. This device's own parent is always the app (never another group
 * device), so it simply forwards up one level, same pattern used elsewhere
 * in this integration (e.g. ReolinkDeviceBridge.groovy's componentX()
 * passthroughs).
 */
void logNormal(msg) {
    parent?.logNormal(msg)
}

/** See logNormal() above -- same reasoning, Full-tier. */
void logFull(msg) {
    parent?.logFull(msg)
}

// ============================================================================
// v1.4.3: componentX() passthrough -- ReolinkDeviceBridge.groovy's own
// componentX() methods call parent?.componentX(...), which for a standalone
// source resolves to THIS device (its real Hubitat parent), not the app.
// This driver never forwarded any of these before -- see the header note's
// v1.4.3 entry for why that broke every standalone-source command that
// isn't push-based polling. Mirrors ReolinkDeviceBridge.groovy's own
// forwarding to the app, one level up.
// ============================================================================
def componentRefresh(child, String dni = null) { parent?.componentRefresh(child, dni) }
def componentTakeSnapshot(child, String dni = null) { parent?.componentTakeSnapshot(child, dni) }
def componentPtz(child, String direction, String dni = null) { parent?.componentPtz(child, direction, dni) }
def componentPtzGoToPreset(child, Integer presetId, String dni = null) { parent?.componentPtzGoToPreset(child, presetId, dni) }
def componentSavePreset(child, Integer presetId, String name, String dni = null) { parent?.componentSavePreset(child, presetId, name, dni) }
def componentSetSpotlight(child, Boolean on, String dni = null) { parent?.componentSetSpotlight(child, on, dni) }
def componentSetNightVision(child, String mode, String dni = null) { parent?.componentSetNightVision(child, mode, dni) }
def componentSetSiren(child, Boolean on, String dni = null) { parent?.componentSetSiren(child, on, dni) }
def componentSetPir(child, Boolean on, String dni = null) { parent?.componentSetPir(child, on, dni) }
def componentCheckBattery(child, String dni = null) { parent?.componentCheckBattery(child, dni) }
def componentCheckAbilities(child, String dni = null) { parent?.componentCheckAbilities(child, dni) }
/**
 * v1.5.0: same root cause as the v1.4.3/1.4.6 fixes above --
 * componentCheckRecordingSchedule() didn't exist yet when either of those
 * hotfixes shipped, so there was no prior moment this file could have
 * caught it. Now that the Camera/Doorbell drivers have a real
 * checkRecordingSchedule() command, this passthrough is required or
 * running it on any standalone (non-Hub) camera/doorbell throws
 * MissingMethodException inside the bridge before ever reaching the app.
 */
def componentCheckRecordingSchedule(child, String dni = null) { parent?.componentCheckRecordingSchedule(child, dni) }
def componentCalibratePtz(child, String dni = null) { parent?.componentCalibratePtz(child, dni) }
def componentCheckPtzCalibrationStatus(child, String dni = null) { parent?.componentCheckPtzCalibrationStatus(child, dni) }
def componentSetPollInterval(child, Integer seconds, String dni = null) { parent?.componentSetPollInterval(child, seconds, dni) }
def componentSetSnapshotInterval(child, Integer seconds, String dni = null) { parent?.componentSetSnapshotInterval(child, seconds, dni) }
def componentEventChannelUpdate(child, sourceId, channelId, String status, String aiType) { parent?.componentEventChannelUpdate(child, sourceId, channelId, status, aiType) }

// ============================================================================
// v1.5.1: six more gaps in the SAME passthrough class -- see the header
// note's v1.5.1 entry above for the full breakdown of each one.
// ============================================================================
def componentEventConnectionStatus(child, sourceId, String status) { parent?.componentEventConnectionStatus(child, sourceId, status) }
def componentEventSleepUpdate(child, sourceId, channelId, String sleepState) { parent?.componentEventSleepUpdate(child, sourceId, channelId, sleepState) }
def componentLoadPreset(child, sourceId, String presetName) { parent?.componentLoadPreset(child, sourceId, presetName) }
def componentSetRecordingEnabled(child, sourceId, Boolean enabled) { parent?.componentSetRecordingEnabled(child, sourceId, enabled) }
def componentBridgeButtonPushed(child, sourceId, Integer btn) { parent?.componentBridgeButtonPushed(child, sourceId, btn) }
def componentGetPresetNames(sourceId) { return parent?.componentGetPresetNames(sourceId) }
