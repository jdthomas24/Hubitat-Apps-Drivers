/*
    Sofabaton Activity
    Copyright 2026 Jason Thomas. All Rights Reserved

    2026-09-10 jdthomas24
        -Initial publication

    *OVERVIEW
     Represents one Sofabaton activity as a standard Hubitat switch. Two
     completely separate paths set this device's state, and it matters
     which one is in play:

       -REAL COMMANDS: on()/off() called by a person, Rule Machine, or a
        dashboard hit Sofabaton's cloud API webhook for this activity. This
        is the ONLY path that talks to the network.

       -STATE SYNC: syncOn()/syncOff(), called only by the parent "Sofabaton
        Remote" device when the physical remote or hub changes activity on
        its own, just update the switch attribute to reflect reality. No
        network call is made on this path -- the remote/hub already did the
        real work (flipping inputs, lighting, etc via its own setup).

     UNCONFIRMED PENDING REAL HARDWARE (flagged here rather than guessed
     at): whether Sofabaton's cloud API exposes a genuine per-activity
     "stop" webhook distinct from "start", or whether stopping an activity
     needs to point at a different activity/webhook entirely (e.g. a
     dedicated "Off"/"Goodnight" activity someone builds in the Sofabaton
     app). webhookUrlOff is optional for exactly this reason -- leave it
     blank until confirmed against real hardware; off() will log a clear
     warning and fall back to a local-only state change instead of silently
     doing nothing or guessing at an API shape.
*/

def version() { return "1.0.0" }

metadata {
    definition (name: "Sofabaton Activity", namespace: "jdthomas24", author: "Jason Thomas") {
        capability "Actuator"
        capability "Switch"
        attribute "lastCallStatus", "string"
        attribute "lastCallTime", "string"

        command "syncOn"
        command "syncOff"
    }
    preferences {
        input name: "webhookUrlOn", type: "text", title: "Start Activity Webhook URL", required: true
        input name: "webhookUrlOff", type: "text", title: "Stop Activity Webhook URL (optional -- unconfirmed feature, see driver notes)", required: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void installed() {
    updated()
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
}

void logsOff() {
    log.warn "$device.label: debug logging disabled..."
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ============================================================
// ================= REAL COMMANDS (cloud) =====================
// ============================================================

void on() {
    if (!webhookUrlOn) {
        log.error "$device.label: no Start Activity Webhook URL configured, cannot start this activity"
        return
    }
    sendWebhookCall(webhookUrlOn, "on", 1)
}

void off() {
    if (!webhookUrlOff) {
        log.warn "$device.label: no Stop Activity Webhook URL configured -- this Sofabaton feature is unconfirmed against real hardware, see driver notes. Setting local state only, no command sent."
        syncOff()
        return
    }
    sendWebhookCall(webhookUrlOff, "off", 1)
}

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
// ============= STATE SYNC (local, no network) =================
// Called only by the parent Remote device when the physical
// remote or hub changed activity on its own.
// ============================================================

void syncOn() {
    if (txtEnable) log.info "$device.label: state sync -- now on (via remote/hub, no cloud call made)"
    sendEvent(name: "switch", value: "on")
}

void syncOff() {
    if (txtEnable) log.info "$device.label: state sync -- now off (via remote/hub, no cloud call made)"
    sendEvent(name: "switch", value: "off")
}

