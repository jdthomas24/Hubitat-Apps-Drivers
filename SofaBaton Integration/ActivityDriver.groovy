/*
    Sofabaton - Activity Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: part of the Sofabaton Integration, whose X1/X1S local receive
    path (Sofabaton Remote driver) is a fork of Derek Osborn's (dJOS1475)
    Hubitat community driver, built on push-command building blocks
    originated by Mike Maxwell (mike.maxwell).

    Notes:
     -One Sofabaton activity as a Switch. sofabatonActivityId set = X2 over local
      MQTT (created automatically from the hub's list). Otherwise cloud webhook (X1S).
      X1S without a webhook is "follow only": state syncs from the hub, on/off just warn.
     -Label defaults to "<Hub label>-<Activity>". sofabatonName holds the plain activity
      name for both models; the Remote uses it to relabel on hub renames.
     -X2 commands confirm in ~8-10s, after the hub's start/stop sequence. activityStatus
      shows starting/stopping until then; switch flips only on confirmation.
      No confirmation within 20s triggers a resync from the hub's activity list.
     -X2 off() is skipped when already off, since any activity off powers off the hub.
     -Webhook: spaces in Sofabaton URLs are encoded (raw spaces were rejected
      instantly and looked like 408s). Each resp.* read is wrapped separately,
      since AsyncResponse throws on empty fields.
     -X1S: bodyValue is what the Sofabaton IP control device sends. The Remote matches it
      directly to turn this activity on.
     -syncOn()/syncOff() are local state only, called by the Remote. Not declared as
      commands on purpose: as buttons they'd change Hubitat's state without the hub.
     -Log level comes from the app via the Remote. Log lines go up the chain (childLog) and
      appear under the app, labeled with this device's name. Falls back to local logging. Log lines go up the chain (childLog) and
      appear under the app, labeled with this device's name. Falls back to local logging.
*/

import groovy.transform.Field

def version() { return "1.1.0" }

@Field static final Integer CONFIRM_TIMEOUT = 20

metadata {
    definition (name: "Sofabaton Activity", namespace: "jdthomas24", author: "Jason Thomas", importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/SofaBaton%20Integration/ActivityDriver.groovy") {
        capability "Actuator"
        capability "Switch"
        attribute "activityStatus", "string"
        attribute "sofabatonActivityId", "number"
        attribute "sofabatonName", "string"
        attribute "onHub", "string"
        attribute "lastCallStatus", "string"
        attribute "lastCallTime", "string"
        attribute "bodyValue", "string"
    }
    preferences {
        input name: "webhookUrlOn", type: "text", title: "Start Activity Webhook URL (X1S)", required: false
        input name: "webhookUrlOff", type: "text", title: "Stop Activity Webhook URL (X1S, optional)", required: false
        input name: "bodyValue", type: "text", title: "Body Value (X1S, sent by the Sofabaton IP control device)", required: false
        input name: "sofabatonActivityId", type: "number", title: "Sofabaton Activity ID (X2, set automatically)", required: false
    }
}

void installed() {
    updated()
}

void updated() {
    if (sofabatonActivityId != null) {
        sendEvent(name: "sofabatonActivityId", value: sofabatonActivityId)
    }
    if (bodyValue) sendEvent(name: "bodyValue", value: bodyValue.trim())
}

// ============================================================
// Logging. Level is set by the app via the parent Remote.
// ============================================================

void setLogLevel(String level) {
    state.logLevel = level
}

private boolean logNormal() { return state.logLevel in ["Normal", "Full"] }
private boolean logFull() { return state.logLevel == "Full" }

// All log lines go up the chain to the app, so they show under the app labeled by source.
private void slog(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) } catch (e) { localLog(level, "${device.displayName}: ${msg}") }
}

private void localLog(String level, String line) {
    switch (level) {
        case "error": log.error line; break
        case "warn": log.warn line; break
        case "info": log.info line; break
        default: log.debug line
    }
}

// ============================================================
// Commands
// ============================================================

void on() {
    if (isX2()) {
        sendMqtt("on")
        return
    }
    if (webhookUrlOn) {
        sendWebhookCall(webhookUrlOn, "on", 1)
        return
    }
    slog("warn", "follow only (no webhook URL), Hubitat can't start this activity. Add one in the app to enable control.")
}

void off() {
    if (isX2()) {
        if (device.currentValue("switch") == "off" && !pending()) {
            if (logNormal()) slog("info", "already off, nothing sent")
            return
        }
        sendMqtt("off")
        return
    }
    if (webhookUrlOff) {
        sendWebhookCall(webhookUrlOff, "off", 1)
        return
    }
    if (!webhookUrlOn) {
        slog("warn", "follow only (no webhook URL), Hubitat can't stop this activity. Add one in the app to enable control.")
        return
    }
    slog("warn", "no Stop Activity Webhook URL configured, setting local state only")
    syncOff()
}

private boolean isX2() {
    return sofabatonActivityId != null
}

private boolean pending() {
    return device.currentValue("activityStatus") in ["starting", "stopping"]
}

// ============================================================
// X2 local MQTT. Activity -> Remote -> Bridge, which owns the connection.
// ============================================================

private void sendMqtt(String desired) {
    if (!parent) {
        slog("error", "no parent Sofabaton Remote found, cannot send MQTT command")
        return
    }
    Integer id = sofabatonActivityId as Integer
    if (logFull()) slog("debug", "requesting activityId=$id state=$desired via ${parent.displayName}")
    boolean sent = parent.componentPublishActivityControl(device, id, desired) ?: false
    sendEvent(name: "lastCallTime", value: new Date().toString())
    if (!sent) {
        sendEvent(name: "lastCallStatus", value: "failed (MQTT not connected)")
        return
    }
    if (logNormal()) slog("info", "${desired == 'on' ? 'starting' : 'stopping'}, waiting for the hub to confirm")
    sendEvent(name: "activityStatus", value: desired == "on" ? "starting" : "stopping")
    sendEvent(name: "lastCallStatus", value: "sent, waiting for hub")
    runIn(CONFIRM_TIMEOUT, "confirmTimeout")
}

void confirmTimeout() {
    if (!pending()) return
    slog("warn", "hub didn't confirm within ${CONFIRM_TIMEOUT}s, resyncing from the hub")
    sendEvent(name: "activityStatus", value: device.currentValue("switch") ?: "off")
    sendEvent(name: "lastCallStatus", value: "no confirmation, resynced")
    parent?.requestActivityList()
}

// Called by the Remote: on each X2 list sync, and when an X1S activity is named.
void setHubInfo(String name, Boolean present) {
    if (name) sendEvent(name: "sofabatonName", value: name)
    sendEvent(name: "onHub", value: present ? "true" : "false")
}

// ============================================================
// Cloud webhook (X1S). One retry after 2s.
// ============================================================

private void sendWebhookCall(String url, String intendedState, Integer attempt) {
    String safeUrl = url?.trim()?.replace(" ", "%20")
    if (safeUrl != url && logFull()) slog("debug", "encoded webhook URL: $safeUrl")
    if (logNormal()) slog("info", "calling Sofabaton webhook (attempt $attempt) to set $intendedState")
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
        if (logNormal()) slog("info", "Sofabaton webhook call succeeded (${status})")
        sendEvent(name: "switch", value: data.intendedState)
        sendEvent(name: "activityStatus", value: data.intendedState)
        sendEvent(name: "lastCallStatus", value: "success (${status})")
    } else if ((data.attempt as Integer) < 2) {
        slog("warn", "Sofabaton webhook call failed (status=${status}, error=${errMsg}, data=${bodyText}), retrying once")
        runIn(2, "retryWebhookCall", [data: [url: data.url, intendedState: data.intendedState, attempt: (data.attempt as Integer) + 1]])
    } else {
        slog("error", "Sofabaton webhook call failed after retry (status=${status}, error=${errMsg}, data=${bodyText}, url=${data.url})")
        sendEvent(name: "lastCallStatus", value: "failed (${status})")
    }
    sendEvent(name: "lastCallTime", value: new Date().toString())
}

void retryWebhookCall(data) {
    sendWebhookCall(data.url, data.intendedState, data.attempt as Integer)
}

// ============================================================
// State sync (local only). Also confirms a pending X2 command.
// ============================================================

void syncOn() {
    boolean wasPending = pending()
    unschedule("confirmTimeout")
    if (device.currentValue("switch") != "on" && logNormal()) slog("info", "now on")
    sendEvent(name: "switch", value: "on")
    sendEvent(name: "activityStatus", value: "on")
    if (wasPending) sendEvent(name: "lastCallStatus", value: "confirmed by hub")
}

void syncOff() {
    boolean wasPending = pending()
    unschedule("confirmTimeout")
    if (device.currentValue("switch") != "off" && logNormal()) slog("info", "now off")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "activityStatus", value: "off")
    if (wasPending) sendEvent(name: "lastCallStatus", value: "confirmed by hub")
}
