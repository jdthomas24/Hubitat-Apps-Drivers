/*
    Sofabaton Integration - Bridge Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: part of the Sofabaton Integration, whose X1/X1S local receive
    path (Sofabaton Remote driver) is a fork of Derek Osborn's (dJOS1475)
    Hubitat community driver, built on push-command building blocks
    originated by Mike Maxwell (mike.maxwell).

    2026-09-10 jdthomas24
        -Initial publication
    2026-09-16 jdthomas24
        -Added "activity learn" capture mode: startActivityLearn(mac) arms
         a one-shot capture of the next activity_control_up "on" message
         for that MAC, getActivityLearnResult()/clearActivityLearnResult()
         let the parent app poll for and consume it, cancelActivityLearn()
         disarms it. Lets the Add Activity page auto-fill a numeric
         Sofabaton Activity ID by watching the remote get pressed, instead
         of requiring the user to read it out of an external MQTT client.
        -parse() now calls hub.markMqttMessageSeen() on every recognized
         message for that hub's MAC, regardless of whether it matches a
         configured Activity, so the Remote driver can expose a real
         "last seen" timestamp as a stand-in for true connection status
         (the X2 has no known keepalive/presence topic, so this is the
         best available signal: proof of a real message at a known time).
        -Fixed removeRemoteDevice(): it was deleting a Remote device
         without first removing its Activity children, which Hubitat can
         refuse or only partially complete, silently leaving an orphaned
         device behind that then blocked re-adding a hub with the same
         DNI. Now calls the Remote's new removeAllActivityDevices() first,
         and both delete steps are wrapped so a real failure logs loudly
         instead of leaving a zombie device with no error shown anywhere.
        -parse() now logs every raw MQTT message it receives (topic +
         payload) at debug level, before any topic filtering. This means
         Live Logs, with debug logging enabled on this device, now shows
         ANY traffic arriving from the broker -- no need for a separate
         tool like MQTT Explorer just to confirm whether the X2 is
         publishing at all.
        -Added an explicit success log on subscribe(), and a new
         forceReconnectMqtt() command (button on the Commands tab) that
         does a full disconnect + reconnect + resubscribe cycle.
         ensureMqttConnected() normally skips reconnecting when the broker
         URL hasn't changed, so a subscribe that silently failed once
         would otherwise never get retried, leaving mqttStatus stuck on
         "connected" forever with no messages ever actually arriving.
        -FIXED: forceReconnectMqtt() needed an explicit command "..."
         declaration in the metadata block to actually appear as a button
         on the Commands tab (matches the same pattern already used
         correctly for syncOn/syncOff in the Activity driver -- missed it
         here on first pass).
        -MAJOR FINDING, LATE TONIGHT: a real, actively-maintained Home
         Assistant integration (yomonpet/ha-sofabaton-hub) documents
         activity_control_up as the COMMAND/publish topic and
         activity_control_down as the STATE-BROADCAST/subscribe topic --
         the opposite of what this file assumed (based on an earlier
         direct MQTT Explorer observation on this same hardware showing
         traffic on _up when pressing the remote). Rather than commit to
         either direction, now subscribes to BOTH _up and _down, and
         parse() logs which suffix each message actually arrives on. This
         is the next real test to run: if messages start arriving on
         _down, our whole subscribe/publish direction has been backwards.
         Outbound publish direction (publishMqttActivityControl) left
         unchanged pending that result, to avoid changing two unverified
         things at once.
        -SECOND FINDING from the same source: its documented startup
         sequence actively PUBLISHES a request (to .../list_request) after
         subscribing, rather than only passively waiting for broadcasts --
         never tried before tonight. Added a subscribe to activity/+/list
         and, right after connecting, an untested publish of an empty
         message to activity/{mac}/list_request for each known X2 hub, to
         test whether the hub only starts talking to a client that first
         announces itself this way.
        -FIXED: uninstalled() deleted each Remote child without first
         clearing ITS Activity children -- the same "device still has
         children" bug already fixed in removeRemoteDevice() earlier
         tonight, just never applied here too. Now calls
         removeAllActivityDevices() on each Remote before deleting it, so
         a full app removal genuinely leaves nothing orphaned behind.
    2026-09-17 jdthomas24
        -Added a confirmation log at the top of publishMqttActivityControl()
         showing the mac/activityId/state being requested plus the current
         mqttConnected/mqttUrl state, BEFORE the connected-check can bail
         out early. Final hop of a three-file logging trail (Activity ->
         Remote -> Bridge) added tonight: a test command's path can now be
         traced end to end (request received here -> connection state at
         that moment -> exact topic/payload published, or the exact reason
         it wasn't) instead of only knowing "nothing happened" with no
         visibility into which hop stopped it or why.
        -parse() now logs the raw topic + payload of EVERY message at
         debug level as the very first thing it does, before the existing
         topic-filter check that only recognizes activity_control_up,
         activity_control_down, and list. Previously an unrecognized topic
         (e.g. a list_response, or any topic name not yet accounted for)
         only logged "ignoring unrecognized topic" -- the topic name was
         visible, but the actual payload on it was not, which matters if
         the X2 hub is responding somewhere unexpected.
        -TEST: replaced the activity/+/... wildcard subscribes with exact
         per-hub topics (activity/{mac}/...). Confirmed problem: connect
         and subscribe both report success, but zero messages have ever
         been received by this device, even while MQTT Explorer -- same
         broker, same credentials -- shows the X2 hub actively publishing.
         Testing whether Hubitat's built-in MQTT broker (officially beta)
         has an issue with + wildcard subscriptions. If exact topics also
         receive nothing, this isn't a wildcard issue and should be
         reverted. Removed the untested list_request startup publish and
         its dead-end late-night direction-guessing comments -- neither
         led anywhere and they were cluttering this method.
    2026-09-20 jdthomas24
        -Added checkBuiltInBroker command, testing
         hubitat.helper.MQTTHelper.isBuiltInBrokerRunning() -- the proper
         broker-status API gopher.ny mentioned adding, in response to a
         platform issue reported this week: interfaces.mqtt connects and
         subscribes successfully but never delivers incoming messages to
         parse(), confirmed with a standalone minimal test driver
         (isolated from all Sofabaton code) on a completely unrelated
         topic. Reported to Hubitat support with the isolated test case.

    *OVERVIEW
     Grouping anchor for the Sofabaton Integration, and (for X2 hubs) the
     single shared holder of the local MQTT connection. The parent app
     creates exactly one of these on install. Each physical Sofabaton hub
     added via the app becomes a "Sofabaton Remote" child device of THIS
     device (not of the app directly), so hubs nest and collapse together
     in the Devices list.

     X1S hubs need nothing from this device beyond the nesting -- their
     local HTTP listener lives entirely in the Remote driver. X2 hubs all
     share ONE MQTT connection here (they all talk to the same Hubitat
     broker), with a single wildcard subscription
     (activity/+/activity_control_up) covering every X2 hub's MAC at once
     -- confirmed working against real hardware. Incoming messages are
     routed to the matching Remote child by MAC (its deviceNetworkId);
     outbound commands from an Activity child are published here too, via
     the owning Remote child.
*/

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def version() { return "1.5.0" }

metadata {
    definition (name: "Sofabaton Integration Bridge", namespace: "jdthomas24", author: "Jason Thomas") {
        capability "Actuator"
        attribute "mqttStatus", "string"
        attribute "brokerRunning", "string"
        command "forceReconnectMqtt"
        command "checkBuiltInBroker"
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void installed() {
    log.info "Sofabaton Integration Bridge installed"
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    // Nothing else to configure directly; grouping anchor only. The MQTT
    // connection is established on demand by ensureMqttConnected(), called
    // by an X2 Remote child whenever it's added or its broker details change.
}

void logsOff() {
    log.warn "Sofabaton Bridge: debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

void uninstalled() {
    try { interfaces.mqtt.disconnect() } catch (e) { }
    getChildDevices()?.each { child ->
        // Must clear each Remote's own Activity children FIRST -- same
        // reasoning as removeRemoteDevice() below: Hubitat can refuse or
        // only partially complete deleting a device that still has
        // children attached. Missed applying this fix here earlier
        // tonight even though it was already fixed in removeRemoteDevice().
        try {
            child.removeAllActivityDevices()
        } catch (e) {
            log.warn "Failed to clear Activity children of ${child.displayName} on uninstall: ${e.message}"
        }
        try {
            deleteChildDevice(child.deviceNetworkId)
        } catch (e) {
            log.warn "Failed to remove child ${child.displayName} on uninstall: ${e.message}"
        }
    }
}

// Called by the parent app when a hub is added. dni must already be the
// correct value for that hub's model -- ipToHex()-derived for X1S (so
// Hubitat's built-in local listener dispatches by DNI matching the
// sender's IP), or the bare uppercase MAC for X2 (so incoming MQTT
// messages route by MAC match).
def createRemoteDevice(String dni, String label) {
    def existing = getChildDevice(dni)
    if (existing) return existing
    return addChildDevice(
        "jdthomas24",
        "Sofabaton Remote",
        dni,
        [label: label, isComponent: false]
    )
}

void removeRemoteDevice(String dni) {
    def child = getChildDevice(dni)
    if (!child) return
    // Must remove the Remote's own Activity children FIRST -- Hubitat can
    // refuse (or only partially complete) deleting a device that still has
    // children attached, which was silently leaving an orphaned Remote
    // device behind, blocking a later add from reusing the same DNI.
    try {
        child.removeAllActivityDevices()
    } catch (e) {
        log.error "Sofabaton Bridge: failed to clear Activity children of ${child.getLabel()} before removal: ${e.message}"
    }
    try {
        deleteChildDevice(dni)
    } catch (e) {
        log.error "Sofabaton Bridge: failed to remove hub ${child.getLabel()} (dni $dni): ${e.message}"
    }
}

// ============================================================
// ================= X2 MQTT (jdthomas24) =======================
// ============================================================

// Called by an X2 Remote child on installed()/updated(). Idempotent --
// safe to call every time a hub's settings are saved, only reconnects if
// the broker URL actually changed.
void ensureMqttConnected(String host, String port, String user = null, String pass = null) {
    String url = "tcp://${host}:${port}"
    if (state.mqttConnected && state.mqttUrl == url) {
        if (logEnable) log.debug "Sofabaton Bridge: MQTT already connected to $url"
        return
    }
    try {
        if (state.mqttConnected) {
            try { interfaces.mqtt.disconnect() } catch (e) { }
        }
        String clientId = "sofabaton-hubitat-${device.id}"
        interfaces.mqtt.connect(url, clientId, user ?: null, pass ?: null)
        state.mqttUrl = url
        // subscribe() happens in mqttClientStatus() once connection is confirmed
    } catch (e) {
        log.error "Sofabaton Bridge: MQTT connection to $url failed: ${e.message}"
        state.mqttConnected = false
        sendEvent(name: "mqttStatus", value: "connect failed")
    }
}

void mqttClientStatus(String message) {
    if (message.startsWith("Error")) {
        log.error "Sofabaton Bridge: MQTT error -- $message"
        state.mqttConnected = false
        sendEvent(name: "mqttStatus", value: "error")
        return
    }
    if (message.contains("Connection succeeded")) {
        log.info "Sofabaton Bridge: MQTT connected"
        state.mqttConnected = true
        sendEvent(name: "mqttStatus", value: "connected")
        try {
            // 2026-09-17 TEST: switched from wildcard subscribes
            // (activity/+/...) to exact per-hub MAC topics. Wildcard
            // subscribes reported success but zero messages were ever
            // received, even with hardware confirmed actively publishing
            // on the same broker/credentials (verified via MQTT Explorer).
            // Testing whether Hubitat's built-in MQTT broker (beta) has an
            // issue with + wildcard subscriptions specifically.
            getChildDevices()?.findAll { it.currentValue("hubModel") == "X2" }?.each { x2 ->
                String mac = x2.deviceNetworkId
                interfaces.mqtt.subscribe("activity/${mac}/activity_control_up")
                interfaces.mqtt.subscribe("activity/${mac}/activity_control_down")
                interfaces.mqtt.subscribe("activity/${mac}/list")
                if (txtEnable) log.info "Sofabaton Bridge: MQTT subscribed to exact topics for MAC $mac"
            }
        } catch (e) {
            log.error "Sofabaton Bridge: MQTT subscribe failed: ${e.message}"
        }
        return
    }
    if (logEnable) log.debug "Sofabaton Bridge: MQTT status -- $message"
}

// Manual admin command (shows as a button on the Commands tab): forces a
// full disconnect + reconnect + resubscribe cycle. ensureMqttConnected()
// normally skips reconnecting if the broker URL hasn't changed, so if a
// subscribe ever silently failed while the connection itself stayed up,
// mqttStatus would keep showing "connected" forever with no messages ever
// actually arriving, and nothing would ever retry it on its own. This
// clears that stuck state and re-triggers a clean connect+subscribe by
// asking an X2 Remote child to resupply its broker credentials.
void forceReconnectMqtt() {
    log.info "Sofabaton Bridge: forcing MQTT reconnect"
    try { interfaces.mqtt.disconnect() } catch (e) { }
    state.mqttConnected = false
    state.remove("mqttUrl")
    def x2Hub = getChildDevices()?.find { it.currentValue("hubModel") == "X2" }
    if (x2Hub) {
        x2Hub.updated()
    } else {
        log.warn "Sofabaton Bridge: no X2 hub child found to resupply broker credentials for reconnect"
    }
}

// 2026-09-20: tests hubitat.helper.MQTTHelper.isBuiltInBrokerRunning(),
// the proper broker-status API gopher.ny mentioned adding (previously
// there was no way to check this at all short of attempting a real
// connection). Deliberately does NOT use a compile-time "import
// hubitat.helper.MQTTHelper" -- confirmed the hard way that if the class
// doesn't exist on this platform build, an import failure can break the
// WHOLE FILE from compiling/saving, not just this one command. Class.
// forName() at runtime, inside try/catch, means the rest of the driver
// stays safe regardless of whether this platform has the class yet.
void checkBuiltInBroker() {
    try {
        def helperClass = Class.forName("hubitat.helper.MQTTHelper")
        boolean running = helperClass."isBuiltInBrokerRunning"()
        log.info "Sofabaton Bridge: isBuiltInBrokerRunning() = $running"
        sendEvent(name: "brokerRunning", value: running.toString())
    } catch (e) {
        log.error "Sofabaton Bridge: isBuiltInBrokerRunning() call failed -- ${e.message} (a ClassNotFoundException means this Hubitat build doesn't have the new helper method yet)"
        sendEvent(name: "brokerRunning", value: "method unavailable")
    }
}

// interfaces.mqtt.parseMessage() incoming handler. Confirmed real payload
// shape against hardware: topic activity/{MAC}/activity_control_up, JSON
// body {"activity_id":<id>,"state":"on"/"off"}. activity_id 255 is a
// hub-wide Power Off -- confirmed to mean every activity on that hub is
// now off, not just activity 255.
void parse(String description) {
    try {
        def msg = interfaces.mqtt.parseMessage(description)
        // 2026-09-17: logged as the very first thing, before the topic
        // filter below. Previously an unrecognized topic only logged
        // "ignoring unrecognized topic <name>" -- the payload itself was
        // never visible. This catches anything arriving on a topic this
        // driver doesn't yet know about (e.g. a list_response), not just
        // the three topics currently filtered for.
        if (logEnable) log.debug "Sofabaton Bridge: RAW MQTT message -- topic=${msg.topic}, payload=${msg.payload}"
        String topic = msg.topic
        String payload = msg.payload
        def parts = topic.split("/")
        if (parts.length < 3 || parts[0] != "activity" || !(parts[2] in ["activity_control_up", "activity_control_down", "list"])) {
            if (logEnable) log.debug "Sofabaton Bridge: ignoring unrecognized topic $topic"
            return
        }
        if (txtEnable) log.info "Sofabaton Bridge: message arrived on suffix '${parts[2]}' -- this tells us which direction is actually correct"
        String mac = parts[1]
        def json = new JsonSlurper().parseText(payload)
        Integer activityId = json.activity_id as Integer
        String activityState = json.state as String

        def hub = getChildDevice(mac)
        if (!hub) {
            if (logEnable) log.debug "Sofabaton Bridge: no Remote child matches MAC $mac, ignoring"
            return
        }

        // Any real message for this MAC, regardless of whether it matches
        // a configured Activity, is proof the hub is actually connected
        // and talking right now -- stamp it so there's a real "last seen"
        // readout instead of no visibility at all into connection health.
        hub.markMqttMessageSeen()

        // Activity learn mode: if armed for this MAC and this message is a
        // real activity turning on (not the 255 hub-wide power-off), stash
        // it for the parent app's Add Activity page to pick up.
        if (state.learnActive && mac == state.learnMac && activityState == "on" && activityId != 255) {
            state.learnResult = activityId
            state.learnActive = false
            if (txtEnable) log.info "Sofabaton Bridge: learn mode captured activity_id $activityId for MAC $mac"
        }

        hub.receiveMqttActivityUpdate(activityId, activityState)
    } catch (e) {
        log.error "Sofabaton Bridge: failed to parse MQTT message: ${e.message}"
    }
}

// Called by an X2 Remote child (on behalf of one of its Activity children's
// on()/off()) to command that hub over MQTT. NOTE: only the hub-wide Power
// Off (activity_id 255) has been confirmed as a real command path against
// hardware so far -- publishing to start a specific activity via
// activity_control_down is built to the spec doc's documented shape but
// has not yet been confirmed to actually work against real hardware.
void publishMqttActivityControl(String mac, Integer activityId, String desiredState) {
    // 2026-09-17: final hop of the three-file logging trail (Activity ->
    // Remote -> Bridge). Logged BEFORE the connected-check below so this
    // line always appears even if the publish is about to be rejected --
    // shows exactly what was requested and what this device believed its
    // own connection state to be at that moment.
    if (txtEnable) log.info "Sofabaton Bridge: publish requested -- mac=$mac, activityId=$activityId, state=$desiredState, mqttConnected=${state.mqttConnected}, mqttUrl=${state.mqttUrl}"
    if (!state.mqttConnected) {
        log.error "Sofabaton Bridge: cannot publish, MQTT is not connected"
        return
    }
    String topic = "activity/${mac}/activity_control_down"
    String payload = JsonOutput.toJson([activity_id: activityId, state: desiredState])
    try {
        interfaces.mqtt.publish(topic, payload)
        if (txtEnable) log.info "Sofabaton Bridge: published $payload to $topic"
    } catch (e) {
        log.error "Sofabaton Bridge: MQTT publish failed: ${e.message}"
    }
}

// ============================================================
// ============= X2 Activity "Learn" mode (setup helper) ======
// ============================================================
// Lets the parent app's Add Activity page capture a numeric activity_id
// without the user needing an external MQTT client: the user presses
// "Listen" (which calls startActivityLearn(mac) below), then presses the
// activity's button on the physical remote. The next activity_control_up
// "on" message parse() sees for that MAC gets stashed in state.learnResult
// for the app to read on its next page refresh via getActivityLearnResult().
//
// Scoped to one MAC at a time (state.learnMac) so that with multiple X2
// hubs sharing this Bridge's one MQTT connection, pressing a button on the
// wrong hub's remote doesn't get mistakenly captured.

void startActivityLearn(String mac) {
    state.learnActive = true
    state.learnMac = mac
    state.remove("learnResult")
    if (logEnable) log.debug "Sofabaton Bridge: activity learn mode started for MAC $mac"
}

void cancelActivityLearn() {
    state.learnActive = false
    state.remove("learnMac")
    state.remove("learnResult")
    if (logEnable) log.debug "Sofabaton Bridge: activity learn mode cancelled"
}

def getActivityLearnResult() {
    return state.learnResult
}

void clearActivityLearnResult() {
    state.remove("learnResult")
}
