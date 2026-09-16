/*
    Sofabaton Integration Bridge
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: part of the Sofabaton Integration, whose X1/X1S local receive
    path (Sofabaton Remote driver) is a fork of Derek Osborn's (dJOS1475)
    Hubitat community driver, built on push-command building blocks
    originated by Mike Maxwell (mike.maxwell).

    2026-09-10 jdthomas24
        -Initial publication

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

def version() { return "1.1.0" }

metadata {
    definition (name: "Sofabaton Integration Bridge", namespace: "jdthomas24", author: "Jason Thomas") {
        capability "Actuator"
        attribute "mqttStatus", "string"
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
    if (child) deleteChildDevice(dni)
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
            interfaces.mqtt.subscribe("activity/+/activity_control_up")
        } catch (e) {
            log.error "Sofabaton Bridge: MQTT subscribe failed: ${e.message}"
        }
        return
    }
    if (logEnable) log.debug "Sofabaton Bridge: MQTT status -- $message"
}

// interfaces.mqtt.parseMessage() incoming handler. Confirmed real payload
// shape against hardware: topic activity/{MAC}/activity_control_up, JSON
// body {"activity_id":<id>,"state":"on"/"off"}. activity_id 255 is a
// hub-wide Power Off -- confirmed to mean every activity on that hub is
// now off, not just activity 255.
void parse(String description) {
    try {
        def msg = interfaces.mqtt.parseMessage(description)
        String topic = msg.topic
        String payload = msg.payload
        def parts = topic.split("/")
        if (parts.length < 3 || parts[0] != "activity" || parts[2] != "activity_control_up") {
            if (logEnable) log.debug "Sofabaton Bridge: ignoring unrecognized topic $topic"
            return
        }
        String mac = parts[1]
        def json = new JsonSlurper().parseText(payload)
        Integer activityId = json.activity_id as Integer
        String activityState = json.state as String

        def hub = getChildDevice(mac)
        if (!hub) {
            if (logEnable) log.debug "Sofabaton Bridge: no Remote child matches MAC $mac, ignoring"
            return
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
