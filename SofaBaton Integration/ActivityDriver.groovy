/*
    Sofabaton - Activity Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: part of the Sofabaton Integration, whose X1/X1S local receive
    path (Sofabaton Remote driver) is a fork of Derek Osborn's (dJOS1475)
    Hubitat community driver, built on push-command building blocks
    originated by Mike Maxwell (mike.maxwell).

    Notes:
     -One Sofabaton activity as a Switch. Path is picked by which field is set:
      webhookUrlOn = cloud webhook (X1S, or X2 workaround), sofabatonActivityId =
      local MQTT (X2). Webhook wins when both are set.
     -X1S off() with no Stop URL sets local state only and warns. A per-activity
      stop webhook is unconfirmed.
     -X2 off() over MQTT sends activity_id 255 (hub-wide Power Off), turning off
      every activity on that hub. No per-activity MQTT stop is confirmed.
     -Webhook 408s were never timeouts: Sofabaton URLs embed raw activity names
      with spaces (&id=Watch Apple TV), which Hubitat's HTTP client rejects instantly.
      Spaces are encoded before every call. Timeout stays at 20s for the cloud round trip.
     -AsyncResponse throws (not null) when a field like errorMessage or getData()
      has no value. Every resp.* read is wrapped separately.
     -MQTT publish has no delivery confirmation, so state is set optimistically.
     -syncOn()/syncOff() are local state only, called by the Remote when the
      physical remote or hub changes activity.
*/

import groovy.json.JsonOutput

def version() { return "1.0.0" }

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
        input name: "webhookUrlOn", type: "text", title: "Start Activity Webhook URL", required: false
        input name: "webhookUrlOff", type: "text", title: "Stop Activity Webhook URL (optional, unconfirmed feature)", required: false
        input name: "sofabatonActivityId", type: "number", title: "Sofabaton Activity ID (X2 only)", required: false
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
// Commands
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
    log.error "$device.label: no webhook URL or Sofabaton Activity ID configured, cannot start this activity"
}

void off() {
    if (webhookUrlOff) {
        sendWebhookCall(webhookUrlOff, "off", 1)
        return
    }
    if (sofabatonActivityId != null) {
        log.warn "$device.label: X2 has no confirmed per-activity stop, off() powers off the entire hub"
        publishMqttCommand(255, "off")
        return
    }
    if (!webhookUrlOn) {
        log.error "$device.label: no webhook URL or Sofabaton Activity ID configured, cannot stop this activity"
        return
    }
    log.warn "$device.label: no Stop Activity Webhook URL configured, setting local state only"
    syncOff()
}

// ============================================================
// Cloud webhook (X1S, or X2 workaround). One retry after 2s.
// ============================================================

private void sendWebhookCall(String url, String intendedState, Integer attempt) {
    String safeUrl = url?.trim()?.replace(" ", "%20")
    if (safeUrl != url && logEnable) log.debug "$device.label: encoded webhook URL: $safeUrl"
    if (txtEnable) log.info "$device.label: calling Sofabaton webhook (attempt $attempt) to set $intendedState"
    asynchttpGet("handleWebhookResponse", [uri: safeUrl, timeout: 20], [intendedState: intendedState, attempt: attempt, url: safeUrl])
}

void handleWebhookResponse(resp, data) {
    Integer status = null
    try { status = resp?.status } catch (e) { }
    String errMsg = "unavailable"
    try { errMsg = resp?.errorMessage ?: "none" } catch (e) { }
    String bodyText = "unavailable"
    try { bodyText = resp?.getData() ?: "none" } catch (e) { }
    boolean ok = (status != null && status >= 200 && status < 300)
    if (ok) {
        if (txtEnable) log.info "$device.label: Sofabaton webhook call succeeded (${status})"
        sendEvent(name: "switch", value: data.intendedState)
        sendEvent(name: "lastCallStatus", value: "success (${status})")
    } else if ((data.attempt as Integer) < 2) {
        log.warn "$device.label: Sofabaton webhook call failed (status=${status}, error=${errMsg}, data=${bodyText}), retrying once"
        runIn(2, "retryWebhookCall", [data: [url: data.url, intendedState: data.intendedState, attempt: (data.attempt as Integer) + 1]])
    } else {
        log.error "$device.label: Sofabaton webhook call failed after retry (status=${status}, error=${errMsg}, data=${bodyText}, url=${data.url})"
        sendEvent(name: "lastCallStatus", value: "failed (${status})")
    }
    sendEvent(name: "lastCallTime", value: new Date().toString())
}

void retryWebhookCall(data) {
    sendWebhookCall(data.url, data.intendedState, data.attempt as Integer)
}

// ============================================================
// Local MQTT (X2). Goes Remote -> Bridge, which owns the connection.
// Debug logs at each hop trace a command end to end.
// ============================================================

private void publishMqttCommand(Integer activityId, String desiredState) {
    if (!parent) {
        log.error "$device.label: no parent Sofabaton Remote device found, cannot send MQTT command"
        return
    }
    if (logEnable) log.debug "$device.label: requesting MQTT activityId=$activityId state=$desiredState via ${parent.displayName}"
    parent.componentPublishActivityControl(device, activityId, desiredState)
    sendEvent(name: "switch", value: desiredState)
    sendEvent(name: "lastCallStatus", value: "sent (MQTT, unconfirmed delivery)")
    sendEvent(name: "lastCallTime", value: new Date().toString())
}

// ============================================================
// State sync (local only, no network call)
// ============================================================

void syncOn() {
    if (txtEnable) log.info "$device.label: now on (changed from remote/hub)"
    sendEvent(name: "switch", value: "on")
}

void syncOff() {
    if (txtEnable) log.info "$device.label: now off (changed from remote/hub)"
    sendEvent(name: "switch", value: "off")
}
