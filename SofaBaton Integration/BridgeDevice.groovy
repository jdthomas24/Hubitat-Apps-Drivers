/*
    Sofabaton Integration - Bridge Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: part of the Sofabaton Integration, whose X1/X1S local receive
    path (Sofabaton Remote driver) is a fork of Derek Osborn's (dJOS1475)
    Hubitat community driver, built on push-command building blocks
    originated by Mike Maxwell (mike.maxwell).

    Notes:
     -Grouping anchor: one Bridge, one Remote child per hub, Activities under each Remote.
     -X2: one shared MQTT connection for all hubs, routed to Remotes by MAC (their DNI).
      Requires Hubitat 2.5.2.126+ (MQTT delivery fix and connectToBuiltInBroker).
     -Topics (from yomonpet/ha-sofabaton-hub source, confirmed on hardware 2026-09-29):
        activity/{mac}/activity_control_up    hub state   {"activity_id":101,"state":"on"}
        activity/{mac}/activity_control_down  control     {"data":{"activity_id":101,"state":"on"}}
        activity/{mac}/list_request           request     {"data":"activity_list"}
        activity/{mac}/list                   response    {"data":[{activity_id,state,activity_name}],"activity_count":n}
      Commands must be wrapped in "data" or the hub silently ignores them.
      Any off confirms as activity_id 255 (hub-wide off).
     -The hub is single-threaded: 200ms pause after every publish.
 -The hub doesn't announce activity list changes, so lists refresh every 3 hours.
 -Log level comes from the app and cascades Bridge -> Remotes -> Activities.
     -Unknown MACs seen on activity_control_up are kept for Add Hub's "Find My X2".
     -connectToBuiltInBroker may not fire mqttClientStatus, so a 3s check subscribes if needed.
     -Connection state comes from interfaces.mqtt.isConnected(). A state flag raced.
     -Remotes must clear their Activity children before deletion, or Hubitat can
      orphan them and block re-adding the same DNI.
*/

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import hubitat.helper.MQTTHelper

def version() { return "1.0.0" }

metadata {
    definition (name: "Sofabaton Integration Bridge", namespace: "jdthomas24", author: "Jason Thomas") {
        capability "Actuator"
        attribute "mqttStatus", "string"
        attribute "brokerRunning", "string"
        command "forceReconnectMqtt"
        command "checkBuiltInBroker"
        command "refreshAllActivities"
    }
    preferences {
        input name: "loggingNote", type: "paragraph", element: "paragraph", title: "Logging", description: "Set in the Sofabaton Integration app (Log level). It applies to every Sofabaton device."
    }
}

void installed() {
    log.info "Sofabaton Integration Bridge installed"
}

void updated() {
    ["mqttConnected", "mqttUrl", "learnActive", "learnMac", "learnResult"].each { state.remove(it) }
    atomicState.remove("mqttUrl")
}

// ============================================================
// Logging. Level is set by the app and cascades to children.
// ============================================================

void setLogLevel(String level) {
    state.logLevel = level
    getChildDevices()?.each { child ->
        try { child.setLogLevel(level) } catch (e) { }
    }
}

private boolean logNormal() { return state.logLevel in ["Normal", "Full"] }
private boolean logFull() { return state.logLevel == "Full" }

void uninstalled() {
    try { interfaces.mqtt.disconnect() } catch (e) { }
    getChildDevices()?.each { child ->
        try {
            child.removeAllActivityDevices()
        } catch (e) {
            log.warn "Sofabaton Bridge: failed to clear Activity children of ${child.displayName} on uninstall: ${e.message}"
        }
        try {
            deleteChildDevice(child.deviceNetworkId)
        } catch (e) {
            log.warn "Sofabaton Bridge: failed to remove ${child.displayName} on uninstall: ${e.message}"
        }
    }
}

// dni is ipToHex(ip) for X1S, bare uppercase MAC for X2.
def createRemoteDevice(String dni, String label) {
    def existing = getChildDevice(dni)
    if (existing) return existing
    def child = addChildDevice("jdthomas24", "Sofabaton Remote", dni, [label: label, isComponent: false])
    child?.setLogLevel(state.logLevel)
    return child
}

void removeRemoteDevice(String dni) {
    def child = getChildDevice(dni)
    if (!child) return
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

private List x2Hubs() {
    return getChildDevices()?.findAll { it.currentValue("hubModel") == "X2" } ?: []
}

// ============================================================
// X2 MQTT connection
// ============================================================

// Idempotent: reconnects only when the target broker changes.
void ensureMqttConnected(Boolean builtIn, String host = null, String port = null, String user = null, String pass = null) {
    if (!builtIn && !host) {
        log.error "Sofabaton Bridge: external broker selected but no host set"
        return
    }
    String target = builtIn ? "built-in" : "tcp://${host}:${port ?: '1883'}"
    if (mqttUp() && atomicState.mqttTarget == target) {
        if (logFull()) log.debug "Sofabaton Bridge: MQTT already connected to $target"
        return
    }
    if (mqttUp()) {
        try { interfaces.mqtt.disconnect() } catch (e) { }
    }
    atomicState.mqttTarget = target
    sendEvent(name: "mqttStatus", value: "connecting")
    String clientId = "sofabaton-hubitat-${device.id}"
    try {
        if (builtIn) {
            if (checkBuiltInBroker() == false) {
                log.error "Sofabaton Bridge: Hubitat's built-in MQTT broker isn't running. Enable it in MQTT Import Integration."
                sendEvent(name: "mqttStatus", value: "broker off")
                return
            }
            if (!interfaces.mqtt.connectToBuiltInBroker(clientId)) {
                log.error "Sofabaton Bridge: connectToBuiltInBroker returned false"
                sendEvent(name: "mqttStatus", value: "connect failed")
                return
            }
            runIn(3, "verifyConnected")
        } else {
            interfaces.mqtt.connect(target, clientId, user ?: null, pass ?: null)
        }
    } catch (e) {
        log.error "Sofabaton Bridge: MQTT connect to $target failed: ${e.message}${builtIn ? ' (the built-in broker option needs Hubitat 2.5.2.126 or newer)' : ''}"
        sendEvent(name: "mqttStatus", value: "connect failed")
    }
}

void mqttClientStatus(String message) {
    if (message.startsWith("Error")) {
        log.error "Sofabaton Bridge: MQTT error: $message"
        sendEvent(name: "mqttStatus", value: "error")
        return
    }
    if (message.contains("Connection succeeded")) {
        onConnected()
        return
    }
    if (logFull()) log.debug "Sofabaton Bridge: MQTT status: $message"
}

// Fallback for connectToBuiltInBroker if no status callback arrived.
void verifyConnected() {
    if (mqttUp() && device.currentValue("mqttStatus") != "connected") onConnected()
}

private void onConnected() {
    sendEvent(name: "mqttStatus", value: "connected")
    try {
        interfaces.mqtt.subscribe("activity/+/activity_control_up")
        interfaces.mqtt.subscribe("activity/+/list")
        if (logNormal()) log.info "Sofabaton Bridge: MQTT connected and subscribed"
    } catch (e) {
        log.error "Sofabaton Bridge: MQTT subscribe failed: ${e.message}"
        return
    }
    runIn(1, "refreshAllActivities")
    runEvery3Hours("refreshAllActivities")   // hub doesn't announce list changes
}

private boolean mqttUp() {
    try { return interfaces.mqtt.isConnected() } catch (e) { return false }
}

void forceReconnectMqtt() {
    log.info "Sofabaton Bridge: forcing MQTT reconnect"
    String target = atomicState.mqttTarget
    try { interfaces.mqtt.disconnect() } catch (e) { }
    atomicState.remove("mqttTarget")
    sendEvent(name: "mqttStatus", value: "reconnecting")
    List hubs = x2Hubs()
    if (hubs) {
        hubs[0].updated()   // resupplies broker settings and reconnects
    } else if (target == "built-in") {
        ensureMqttConnected(true)
    } else {
        log.warn "Sofabaton Bridge: no X2 hub found to resupply broker details"
    }
}

// Returns null if the check fails. Called by the app's MQTT card.
def checkBuiltInBroker() {
    try {
        boolean running = MQTTHelper.isBuiltInBrokerRunning()
        if (logFull()) log.debug "Sofabaton Bridge: isBuiltInBrokerRunning() = $running"
        sendEvent(name: "brokerRunning", value: running.toString())
        return running
    } catch (e) {
        log.error "Sofabaton Bridge: isBuiltInBrokerRunning() failed: ${e.message}"
        sendEvent(name: "brokerRunning", value: "error")
        return null
    }
}

// ============================================================
// Publishing
// ============================================================

private boolean publishJson(String topic, Map payload) {
    if (!mqttUp()) {
        log.warn "Sofabaton Bridge: MQTT not connected, can't publish to $topic"
        return false
    }
    String json = JsonOutput.toJson(payload)
    try {
        interfaces.mqtt.publish(topic, json)
        if (logFull()) log.debug "Sofabaton Bridge: published $json to $topic"
        pauseExecution(200)
        return true
    } catch (e) {
        log.error "Sofabaton Bridge: MQTT publish to $topic failed: ${e.message}"
        return false
    }
}

boolean publishMqttActivityControl(String mac, Integer activityId, String desiredState) {
    if (logNormal()) log.info "Sofabaton Bridge: sending activity $activityId $desiredState to $mac"
    return publishJson("activity/${mac}/activity_control_down", [data: [activity_id: activityId, state: desiredState]])
}

boolean requestActivityList(String mac) {
    if (!mqttUp()) return false   // onConnected() requests every hub's list
    if (logFull()) log.debug "Sofabaton Bridge: requesting activity list from $mac"
    return publishJson("activity/${mac}/list_request", [data: "activity_list"])
}

void refreshAllActivities() {
    x2Hubs().each { requestActivityList(it.deviceNetworkId) }
}

// ============================================================
// Receiving
// ============================================================

void parse(String description) {
    try {
        def msg = interfaces.mqtt.parseMessage(description)
        if (logFull()) log.debug "Sofabaton Bridge: MQTT topic=${msg.topic}, payload=${msg.payload}"
        def parts = msg.topic.split("/")
        if (parts.length != 3 || parts[0] != "activity") return
        String mac = parts[1].toUpperCase()
        String leaf = parts[2]
        def json = new JsonSlurper().parseText(msg.payload)

        def hub = getChildDevice(mac)
        if (!hub) {
            if (leaf == "activity_control_up") rememberMac(mac)
            return
        }
        hub.markMqttMessageSeen()

        if (leaf == "activity_control_up") {
            hub.receiveMqttActivityUpdate(json.activity_id as Integer, json.state as String)
        } else if (leaf == "list") {
            List items = (json.data instanceof List ? json.data : []).collect {
                [activity_id: it.activity_id as Integer, activity_name: it.activity_name?.toString()?.trim(), state: it.state?.toString()]
            }
            if (logNormal()) log.info "Sofabaton Bridge: received ${items.size()} activities from ${hub.displayName}"
            hub.receiveActivityList(items)
        }
    } catch (e) {
        log.error "Sofabaton Bridge: failed to parse MQTT message: ${e.message}"
    }
}

// ============================================================
// Find My X2 (Add Hub helper)
// ============================================================

// Connects if nothing is connected yet, so a first hub can be found.
void startDiscovery(Boolean builtIn, String host = null, String port = null, String user = null, String pass = null) {
    if (mqttUp()) return
    ensureMqttConnected(builtIn, host, port, user, pass)
}

List getDiscoveredMacs() {
    Set known = (getChildDevices()*.deviceNetworkId ?: []) as Set
    return ((state.discoveredMacs ?: [:]).keySet().findAll { !(it in known) }) as List
}

private void rememberMac(String mac) {
    Map seen = state.discoveredMacs ?: [:]
    if (!seen[mac] && logNormal()) log.info "Sofabaton Bridge: found X2 hub $mac"
    seen[mac] = now()
    if (seen.size() > 5) seen = seen.sort { a, b -> b.value <=> a.value }.take(5)
    state.discoveredMacs = seen
}
