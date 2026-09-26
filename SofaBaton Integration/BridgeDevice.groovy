/*
    Sofabaton Integration - Bridge Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: part of the Sofabaton Integration, whose X1/X1S local receive
    path (Sofabaton Remote driver) is a fork of Derek Osborn's (dJOS1475)
    Hubitat community driver, built on push-command building blocks
    originated by Mike Maxwell (mike.maxwell).

    Notes:
     -Grouping anchor. The app creates one Bridge; each hub is a Remote child,
      and activities nest under their Remote.
     -X1S needs nothing here beyond nesting. Its HTTP listener lives in the Remote driver.
     -X2: all hubs share one MQTT connection. Wildcard subscriptions route
      messages to Remote children by MAC (their DNI).
     -Payload {"activity_id":<id>,"state":"on"/"off"}. activity_id 255 = hub-wide Power Off.
     -Subscribes to both _up and _down until real delivery confirms which carries
      state (hardware showed _up; yomonpet/ha-sofabaton-hub documents _down).
     -KNOWN ISSUE: interfaces.mqtt connects and subscribes, but parse() never
      receives messages (platform bug, minimal repro sent to gopher.ny).
      Publishing to activity_control_down is also unconfirmed.
     -Exact per-MAC topics were tested and also received nothing, ruling out
      wildcards. Reverted, since exact topics also missed hubs added after connect.
     -forceReconnectMqtt exists because ensureMqttConnected skips same-URL
      reconnects, so a silently failed subscribe never retried. Commands need a
      command "..." declaration to show as buttons.
     -MQTTHelper needs a platform build that includes it. Older builds fail with
      "unable to resolve class", and the sandbox blocks Class.forName as a workaround.
     -Connection state comes from interfaces.mqtt.isConnected(), not a state flag.
      A hand-kept state.mqttConnected raced: Force Reconnect's execution saved its
      "false" after the connect callback had already saved "true".
     -Remotes must clear their Activity children before deletion, or Hubitat
      can leave orphans that block re-adding the same DNI.
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
    }
    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void installed() {
    log.info "Sofabaton Integration Bridge installed"
}

// MQTT connects on demand via ensureMqttConnected(), called by X2 Remote children.
void updated() {
    if (logEnable) runIn(1800, "logsOff")
    state.remove("mqttConnected")   // retired, see Notes
    state.remove("mqttUrl")         // moved to atomicState
}

void logsOff() {
    log.warn "Sofabaton Bridge: debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

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
    return addChildDevice("jdthomas24", "Sofabaton Remote", dni, [label: label, isComponent: false])
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

// ============================================================
// X2 MQTT
// ============================================================

// Idempotent. Only reconnects if the broker URL changed. Wildcard subscriptions
// cover hubs added later, so no resubscribe is needed per hub.
void ensureMqttConnected(String host, String port, String user = null, String pass = null) {
    String url = "tcp://${host}:${port}"
    if (mqttUp() && atomicState.mqttUrl == url) {
        if (logEnable) log.debug "Sofabaton Bridge: MQTT already connected to $url"
        return
    }
    try {
        if (mqttUp()) {
            try { interfaces.mqtt.disconnect() } catch (e) { }
        }
        atomicState.mqttUrl = url
        interfaces.mqtt.connect(url, "sofabaton-hubitat-${device.id}", user ?: null, pass ?: null)
    } catch (e) {
        log.error "Sofabaton Bridge: MQTT connection to $url failed: ${e.message}"
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
        log.info "Sofabaton Bridge: MQTT connected"
        sendEvent(name: "mqttStatus", value: "connected")
        try {
            interfaces.mqtt.subscribe("activity/+/activity_control_up")
            interfaces.mqtt.subscribe("activity/+/activity_control_down")
            if (txtEnable) log.info "Sofabaton Bridge: MQTT subscribed"
        } catch (e) {
            log.error "Sofabaton Bridge: MQTT subscribe failed: ${e.message}"
        }
        return
    }
    if (logEnable) log.debug "Sofabaton Bridge: MQTT status: $message"
}

// Live connection check. Replaces the old state.mqttConnected flag.
private boolean mqttUp() {
    try { return interfaces.mqtt.isConnected() } catch (e) { return false }
}

// Full disconnect/reconnect/resubscribe. An X2 Remote resupplies broker credentials.
void forceReconnectMqtt() {
    log.info "Sofabaton Bridge: forcing MQTT reconnect"
    try { interfaces.mqtt.disconnect() } catch (e) { }
    atomicState.remove("mqttUrl")
    sendEvent(name: "mqttStatus", value: "reconnecting")
    def x2Hub = getChildDevices()?.find { it.currentValue("hubModel") == "X2" }
    if (x2Hub) {
        x2Hub.updated()
    } else {
        log.warn "Sofabaton Bridge: no X2 hub found to resupply broker credentials"
    }
}

// Built-in broker only. Returns null if the check fails. Called by the app's MQTT card.
def checkBuiltInBroker() {
    try {
        boolean running = MQTTHelper.isBuiltInBrokerRunning()
        if (logEnable) log.debug "Sofabaton Bridge: isBuiltInBrokerRunning() = $running"
        sendEvent(name: "brokerRunning", value: running.toString())
        return running
    } catch (e) {
        log.error "Sofabaton Bridge: isBuiltInBrokerRunning() failed: ${e.message}"
        sendEvent(name: "brokerRunning", value: "error")
        return null
    }
}

void parse(String description) {
    try {
        def msg = interfaces.mqtt.parseMessage(description)
        if (logEnable) log.debug "Sofabaton Bridge: RAW MQTT topic=${msg.topic}, payload=${msg.payload}"
        def parts = msg.topic.split("/")
        if (parts.length < 3 || parts[0] != "activity" || !(parts[2] in ["activity_control_up", "activity_control_down"])) {
            if (logEnable) log.debug "Sofabaton Bridge: ignoring topic ${msg.topic}"
            return
        }
        // Tells us which direction carries state once delivery works.
        if (logEnable) log.debug "Sofabaton Bridge: message arrived on '${parts[2]}'"
        String mac = parts[1]
        def json = new JsonSlurper().parseText(msg.payload)
        Integer activityId = json.activity_id as Integer
        String activityState = json.state as String

        def hub = getChildDevice(mac)
        if (!hub) {
            if (logEnable) log.debug "Sofabaton Bridge: no Remote matches MAC $mac"
            return
        }
        // Any real message proves the hub is talking. Drives "last message" in the app.
        hub.markMqttMessageSeen()

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

// Called via an X2 Remote on behalf of an Activity. Only the hub-wide Power Off (255)
// shape is confirmed; starting a specific activity this way is unverified.
void publishMqttActivityControl(String mac, Integer activityId, String desiredState) {
    if (logEnable) log.debug "Sofabaton Bridge: publish requested mac=$mac, activityId=$activityId, state=$desiredState, connected=${mqttUp()}, url=${atomicState.mqttUrl}"
    if (!mqttUp()) {
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
// X2 Activity learn mode (Add Activity helper)
// Captures the next "on" message for one MAC, so a press on another hub's
// remote isn't picked up by mistake. The app reads it on the next page load.
// ============================================================

void startActivityLearn(String mac) {
    state.learnActive = true
    state.learnMac = mac
    state.remove("learnResult")
    if (logEnable) log.debug "Sofabaton Bridge: learn mode started for MAC $mac"
}

void cancelActivityLearn() {
    state.learnActive = false
    state.remove("learnMac")
    state.remove("learnResult")
    if (logEnable) log.debug "Sofabaton Bridge: learn mode cancelled"
}

def getActivityLearnResult() {
    return state.learnResult
}

void clearActivityLearnResult() {
    state.remove("learnResult")
}
