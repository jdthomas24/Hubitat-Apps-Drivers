/*
    Sofabaton - Activity Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: part of the Sofabaton Integration, whose X1/X1S local receive
    path (Sofabaton Remote driver) is a fork of Derek Osborn's (dJOS1475)
    Hubitat community driver, built on push-command building blocks
    originated by Mike Maxwell (mike.maxwell).

    2026-09-10 jdthomas24
        -Initial publication

    *OVERVIEW
     Represents one Sofabaton activity as a standard Hubitat switch. Two
     hub types command it two different ways, and this device picks
     whichever is configured:

       -X1S (cloud webhook): on()/off() hit Sofabaton's cloud API webhook
        for this activity. webhookUrlOff is optional -- whether Sofabaton's
        cloud API even exposes a genuine per-activity "stop" webhook
        distinct from "start" is unconfirmed, so off() without one falls
        back to a local-only state change with a clear warning instead of
        guessing at an API shape.

       -X2 (local MQTT): on()/off() publish to the parent Sofabaton Remote
        device (which forwards to the Bridge's shared MQTT connection)
        instead of calling out to the cloud. Confirmed against real
        hardware: normal activity state broadcasts use
        {"activity_id":<id>,"state":"on"/"off"}, and a hub-wide Power Off
        broadcasts {"activity_id":255,"state":"off"} meaning EVERY activity
        on that hub just went off, not just id 255. No per-activity MQTT
        "stop" command has been confirmed to exist -- only that hub-wide
        Power Off -- so off() on an MQTT-controlled activity powers off the
        whole hub and says so in the log, rather than silently pretending
        to stop just this one activity.

     Which path is used is decided by which fields are populated: set
     webhookUrlOn for X1S, or sofabatonActivityId for X2. A given Activity
     device is expected to use one path, not both.

       -STATE SYNC: syncOn()/syncOff(), called only by the parent Sofabaton
        Remote device when the physical remote or hub changes activity on
        its own (HTTP button match for X1S, or a parsed MQTT broadcast for
        X2), just update the switch attribute to reflect reality. No
        network call is made on this path.
*/

import groovy.json.JsonOutput

def version() { return "1.1.0" }

metadata {
    definition (name: "Sofabaton Activity", namespace: "jdthomas24", author: "Jason Thomas") {
        capability "Actuator"
        capability "Switch"
        attribute "lastCallStatus", "string"
        attribute "lastCallTime", "string"
        attribute "sofabatonActivityId", "number"

        command "syncOn"
        command "syncOff"
    }
    preferences {
        input name: "webhookUrlOn", type: "text", title: "Start Activity Webhook URL (X1S only)", required: false
        input name: "webhookUrlOff", type: "text", title: "Stop Activity Webhook URL (X1S only, optional -- unconfirmed feature, see driver notes)", required: false
        input name: "sofabatonActivityId", type: "number", title: "Sofabaton Activity ID (X2 only -- read from the activity_control_up MQTT payload while triggering this activity)", required: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void installed() {
    updated()
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    if (sofabatonActivityId != null) {
        sendEvent(name: "sofabatonActivityId", value: sofabatonActivityId)
    }
}

void logsOff() {
    log.warn "$device.label: debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ============================================================
// ================= REAL COMMANDS =============================
// Picks X1S cloud webhook or X2 local MQTT based on which fields
// are configured on this device.
// ============================================================

void on() {
    if (webhookUrlOn) {
        sendWebhookCall(webhookUrlOn, "on", 1)
        return
    }
    if (sofabatonActivityId != null) {
        publishMqttCommand(sofabatonActivityId as Integer, "on")
        return
    }
    log.error "$device.label: no Start Activity Webhook URL or Sofabaton Activity ID configured, cannot start this activity"
}

void off() {
    if (webhookUrlOff) {
        sendWebhookCall(webhookUrlOff, "off", 1)
        return
    }
    if (sofabatonActivityId != null) {
        // UNCONFIRMED: no per-activity MQTT "stop" has been observed against real
        // hardware -- only a hub-wide Power Off (activity_id 255, confirmed via
        // testing) that turns every activity on the hub off at once. Until/unless
        // a real per-activity stop is confirmed, off() here mirrors that: it powers
        // off the WHOLE hub, not just this activity, and says so.
        log.warn "$device.label: X2's MQTT protocol has no confirmed per-activity stop -- off() will power off the entire hub, turning off any other running activity on it too"
        publishMqttCommand(255, "off")
        return
    }
    if (!webhookUrlOn) {
        log.error "$device.label: no Stop Activity Webhook URL or Sofabaton Activity ID configured, cannot stop this activity"
        return
    }
    log.warn "$device.label: no Stop Activity Webhook URL configured -- this Sofabaton feature is unconfirmed against real hardware, see driver notes. Setting local state only, no command sent."
    syncOff()
}

// ============================================================
// ================= X1S CLOUD WEBHOOK PATH =====================
// ============================================================

private void sendWebhookCall(String url, String intendedState, Integer attempt) {
    if (txtEnable) log.info "$device.label: calling Sofabaton webhook (attempt $attempt) to set $intendedState"
    def params = [uri: url, timeout: 8]
    asynchttpGet("handleWebhookResponse", params, [intendedState: intendedState, attempt: attempt, url: url])
}

void handleWebhookResponse(resp, data) {
    Integer status = resp?.status
    boolean ok = (status != null && status >= 200 && status < 300)
    if (ok) {
        if (txtEnable) log.info "$device.label: Sofabaton webhook call succeeded (${status})"
        sendEvent(name: "switch", value: data.intendedState)
        sendEvent(name: "lastCallStatus", value: "success (${status})")
    } else if ((data.attempt as Integer) < 2) {
        log.warn "$device.label: Sofabaton webhook call failed (${status}), retrying once"
        runIn(2, "retryWebhookCall", [data: [url: data.url, intendedState: data.intendedState, attempt: (data.attempt as Integer) + 1]])
    } else {
        log.error "$device.label: Sofabaton webhook call failed after retry (${status})"
        sendEvent(name: "lastCallStatus", value: "failed (${status})")
    }
    sendEvent(name: "lastCallTime", value: new Date().toString())
}

void retryWebhookCall(data) {
    sendWebhookCall(data.url, data.intendedState, data.attempt as Integer)
}

// ============================================================
// ================= X2 LOCAL MQTT PATH =========================
// Delegates to the parent Sofabaton Remote device, which forwards to the
// Bridge's shared MQTT connection. MQTT publish has no synchronous
// delivery confirmation, so state here is set optimistically rather than
// waiting on a response the way the webhook path does.
// ============================================================

private void publishMqttCommand(Integer activityId, String desiredState) {
    if (!parent) {
        log.error "$device.label: no parent Sofabaton Remote device found, cannot send MQTT activity command"
        return
    }
    parent.componentPublishActivityControl(device, activityId, desiredState)
    sendEvent(name: "switch", value: desiredState)
    sendEvent(name: "lastCallStatus", value: "sent (MQTT, unconfirmed delivery)")
    sendEvent(name: "lastCallTime", value: new Date().toString())
}

// ============================================================
// ============= STATE SYNC (local, no network) =================
// Called only by the parent Remote device when the physical remote or hub
// changed activity on its own (X1S: HTTP button match. X2: parsed MQTT
// broadcast).
// ============================================================

void syncOn() {
    if (txtEnable) log.info "$device.label: state sync -- now on (via remote/hub, no cloud/MQTT call made)"
    sendEvent(name: "switch", value: "on")
}

void syncOff() {
    if (txtEnable) log.info "$device.label: state sync -- now off (via remote/hub, no cloud/MQTT call made)"
    sendEvent(name: "switch", value: "off")
}
   
