/**
 * Reolink Standalone Devices (Internal Group Driver)
 * Version: 1.6.16
 *
 * Not user-facing. One shared instance, managed by the app. A nesting anchor only: each standalone
 * camera/doorbell still has its own Device Bridge (rawSocket is one connection per driver), and
 * those bridges nest under this device. Because this device is their real parent, every
 * parent?.componentX() call a bridge makes must have a passthrough here to the app; a missing one
 * throws MissingMethodException inside the bridge and never reaches the app's logs.
 *
 * v1.6.16 -- Version sync (navigation fix is in the app).
 * v1.6.15 -- Version sync (setting descriptions are in the drivers).
 * v1.6.14 -- Version sync (hub load changes are in the app).
 * v1.6.13 -- Version sync (dark mode is in the app).
 * v1.6.12 -- Version sync.
 * v1.6.11 -- componentBaichuanReply(), componentEventSpotlightUpdate() passthroughs.
 * v1.6.9 -- componentSetManualRecord(). v1.6.8 -- componentSetSpotlightAuto().
 * v1.6.6 -- componentGetSourceSecret() (password fetched from the app after reboot/driver save).
 * v1.6.5 -- RTSP validation queue passthroughs.
 * v1.5.1 -- Six missing passthroughs: componentEventConnectionStatus() (crashed when a standalone
 *    doorbell's events came up), componentEventSleepUpdate(), componentLoadPreset(),
 *    componentSetRecordingEnabled(), componentBridgeButtonPushed(), componentGetPresetNames()
 *    (bridge Switch/Button capabilities are declared on standalone bridges too).
 * v1.4.6 -- componentEventChannelUpdate() (every standalone event push failed).
 * v1.4.3 -- Full set of device command passthroughs (snapshot, refresh, PTZ, spotlight, siren,
 *    PIR, checks, intervals all failed on standalone sources since v1.3.8).
 * v1.4.1 -- logNormal()/logFull() passthroughs (standalone sockets never started).
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
def componentSetSpotlightAuto(child, Boolean on, String dni = null) { parent?.componentSetSpotlightAuto(child, on, dni) }
def componentSetNightVision(child, String mode, String dni = null) { parent?.componentSetNightVision(child, mode, dni) }
def componentSetSiren(child, Boolean on, String dni = null) { parent?.componentSetSiren(child, on, dni) }
def componentSetPir(child, Boolean on, String dni = null) { parent?.componentSetPir(child, on, dni) }
def componentSetManualRecord(child, Boolean on, Integer seconds, String dni = null) { parent?.componentSetManualRecord(child, on, seconds, dni) }
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

// v1.6.5: RTSP validation queue.
def componentQueueRtspValidation(child, String dni = null) { parent?.componentQueueRtspValidation(child, dni) }
def componentRtspValidationDone(child, String dni, Boolean success, String message) { return parent?.componentRtspValidationDone(child, dni, success, message) }

// v1.6.6: standalone bridges re-fetch the source password from the app (kept out of bridge state).
def componentGetSourceSecret(sourceId) { return parent?.componentGetSourceSecret(sourceId) }

// v1.6.11: replies to settings a bridge sent over its event connection.
def componentBaichuanReply(bridge, Map ctx, Integer status, String body) { parent?.componentBaichuanReply(bridge, ctx, status, body) }
def componentEventSpotlightUpdate(child, sourceId, channelId, Integer status) { parent?.componentEventSpotlightUpdate(child, sourceId, channelId, status) }
