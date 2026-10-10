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
     -Topics (confirmed on hardware):
        activity/{mac}/activity_control_up    hub state   {"activity_id":101,"state":"on"}
        activity/{mac}/activity_control_down  control     {"data":{"activity_id":101,"state":"on"}}
        activity/{mac}/list_request           request     {"data":"activity_list"}
        activity/{mac}/list                   response    {"data":[{activity_id,state,activity_name}]}
        device/{mac}/list_request             request     {"data":"device_list"}
        device/{mac}/list                     response    {"data":[{device_id,device_name}]}
        device/{mac}/keys_request             request     {"data":{"device_id":4}}
        device/{mac}/keys_list                response    {"data":[{key_id,key_name}],"device_id":4}
        {mac}/up                              button      {"device_id":4,"key_id":1}
      Commands must be wrapped in "data" or the hub silently ignores them.
      Any off confirms as activity_id 255 (hub-wide off).
     -{mac}/up key_id is the command number on the Home Assistant Remote, not the
      physical key ID the spec lists.
     -The hub is single-threaded: 200ms pause after every publish. List requests sent back
      to back lose a reply, so they're queued: one in flight, the next sent on its reply
      or after a 5s timeout (one retry). Duplicates are skipped. The queue lives in memory under a lock:
      atomicState went stale across Remote -> Bridge calls and lost writes. A reboot just
      empties it. A request still in flight after 7s is dropped on the next request.
      Activity control is sent directly.
     -The hub doesn't announce list changes, so lists refresh every 3 hours.
     -Subscriptions are versioned. Any refresh adds new ones after a driver update, no reconnect needed.
     -Log level comes from the app and cascades Bridge -> Remotes -> Activities. Log lines
      from every Sofabaton device go up to the app via childLog, labeled by source.
     -Unknown MACs seen on activity_control_up are kept for Add Hub's "Find My X2".
     -connectToBuiltInBroker may not fire mqttClientStatus, so a 3s check subscribes if needed.
     -Connection state comes from interfaces.mqtt.isConnected(). A state flag raced.
     -initialize() reconnects at hub startup, since MQTT connections don't survive a reboot.
     -Any failed connect or dropped connection retries on its own (30s, 60s, then every 5 min)
      until connected: the broker can start after drivers at boot, and isBuiltInBrokerRunning()
      can read false while it's starting, so a failed check still attempts the connect.
      One warning per outage, one info line on recovery.
     -Remotes must clear their Activity children before deletion, or Hubitat can
      orphan them and block re-adding the same DNI.
*/

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import hubitat.helper.MQTTHelper

def version() { return "1.1.1" }

@Field static final Integer SUBS_VERSION = 2
@Field static final Integer REQ_TIMEOUT = 5
@Field static final List RETRY_DELAYS = [30, 60, 300]
// Last broker settings, in memory only (never in state). Retries reuse them; empty after a reboot.
@Field static final java.util.concurrent.ConcurrentHashMap LAST_CONNECT = new java.util.concurrent.ConcurrentHashMap()
@Field static final java.util.concurrent.Semaphore REQ_LOCK = new java.util.concurrent.Semaphore(1)
@Field static final java.util.concurrent.ConcurrentHashMap REQ_STATE = new java.util.concurrent.ConcurrentHashMap()
@Field static final List SUBS = ["activity/+/activity_control_up", "activity/+/list", "device/+/list", "device/+/keys_list", "+/up"]

metadata {
    definition (name: "Sofabaton Integration Bridge", namespace: "jdthomas24", author: "Jason Thomas", importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/SofaBaton%20Integration/BridgeDevice.groovy") {
        capability "Actuator"
        capability "Initialize"
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
    slog("info", "Sofabaton Integration Bridge installed")
}

// Hub startup (Initialize capability). MQTT connections don't survive a reboot.
void initialize() {
    atomicState.remove("mqttTarget")
    state.remove("retryCount")
    clearRequests()
    List hubs = x2Hubs()
    if (!hubs) return
    if (logNormal()) slog("info", "reconnecting MQTT after startup")
    sendEvent(name: "mqttStatus", value: "connecting")
    hubs[0].updated()   // resupplies broker settings and connects
}

void updated() {
    ["mqttConnected", "mqttUrl", "learnActive", "learnMac", "learnResult", "reqQueue", "reqInFlight"].each { state.remove(it) }
    ["mqttUrl", "reqQueue", "reqInFlight"].each { atomicState.remove(it) }
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

// All log lines go to the app, so they show under the app labeled by source.
private void slog(String level, String msg) {
    try { parent.childLog(level, "Bridge", msg) } catch (e) { localLog(level, "Sofabaton Bridge: ${msg}") }
}

// Relays Remote and Activity log lines up to the app.
void childLog(String level, String source, String msg) {
    try { parent.childLog(level, source, msg) } catch (e) { localLog(level, "${source}: ${msg}") }
}

private void localLog(String level, String line) {
    switch (level) {
        case "error": log.error line; break
        case "warn": log.warn line; break
        case "info": log.info line; break
        default: log.debug line
    }
}

void uninstalled() {
    try { interfaces.mqtt.disconnect() } catch (e) { }
    getChildDevices()?.each { child ->
        try {
            child.removeAllActivityDevices()
        } catch (e) {
            slog("warn", "failed to clear Activity children of ${child.displayName} on uninstall: ${e.message}")
        }
        try {
            deleteChildDevice(child.deviceNetworkId)
        } catch (e) {
            slog("warn", "failed to remove ${child.displayName} on uninstall: ${e.message}")
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
        slog("error", "failed to clear Activity children of ${child.getLabel()} before removal: ${e.message}")
    }
    try {
        deleteChildDevice(dni)
    } catch (e) {
        slog("error", "failed to remove hub ${child.getLabel()} (dni $dni): ${e.message}")
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
        slog("error", "external broker selected but no host set")
        return
    }
    String target = builtIn ? "built-in" : "tcp://${host}:${port ?: '1883'}"
    LAST_CONNECT.put(device.id.toString(), [builtIn: builtIn, host: host, port: port, user: user, pass: pass])
    if (mqttUp() && atomicState.mqttTarget == target) {
        if (logFull()) slog("debug", "MQTT already connected to $target")
        ensureSubscriptions()
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
            boolean brokerOff = (checkBuiltInBroker() == false)
            if (!interfaces.mqtt.connectToBuiltInBroker(clientId)) {
                sendEvent(name: "mqttStatus", value: brokerOff ? "broker off" : "connect failed")
                scheduleRetry(brokerOff ? "built-in broker not running" : "connectToBuiltInBroker returned false")
                return
            }
        } else {
            interfaces.mqtt.connect(target, clientId, user ?: null, pass ?: null)
        }
        runIn(3, "verifyConnected")
    } catch (e) {
        sendEvent(name: "mqttStatus", value: "connect failed")
        scheduleRetry("connect to $target failed: ${e.message}${builtIn ? ' (the built-in broker option needs Hubitat 2.5.2.126 or newer)' : ''}")
    }
}

// Retries until connected. Repeated runIn calls replace the pending one, so retries never stack.
private void scheduleRetry(String reason) {
    int n = (state.retryCount ?: 0) as int
    if (n == 0) slog("warn", "MQTT not connected (${reason}). Retrying automatically.")
    else if (logFull()) slog("debug", "MQTT retry ${n} failed: ${reason}")
    state.retryCount = n + 1
    runIn(RETRY_DELAYS[Math.min(n, RETRY_DELAYS.size() - 1)] as Integer, "retryConnect")
}

void retryConnect() {
    if (mqttUp()) return
    atomicState.remove("mqttTarget")
    Map c = LAST_CONNECT.get(device.id.toString()) as Map
    if (c) {
        ensureMqttConnected(c.builtIn as Boolean, c.host as String, c.port as String, c.user as String, c.pass as String)
    } else {
        List hubs = x2Hubs()
        if (hubs) hubs[0].updated()   // resupplies broker settings and connects
    }
}

void mqttClientStatus(String message) {
    if (message.startsWith("Error")) {
        sendEvent(name: "mqttStatus", value: "error")
        scheduleRetry("MQTT error: $message")
        return
    }
    if (message.contains("Connection succeeded")) {
        onConnected()
        return
    }
    if (logFull()) slog("debug", "MQTT status: $message")
}

// Fallback for connectToBuiltInBroker if no status callback arrived.
void verifyConnected() {
    if (mqttUp()) {
        if (device.currentValue("mqttStatus") != "connected") onConnected()
    } else {
        scheduleRetry("no connection after connect attempt")
    }
}

private void onConnected() {
    sendEvent(name: "mqttStatus", value: "connected")
    if (state.retryCount) {
        slog("info", "MQTT reconnected after ${state.retryCount} ${state.retryCount == 1 ? 'retry' : 'retries'}")
        state.remove("retryCount")
    }
    unschedule("retryConnect")
    checkBuiltInBroker()   // refresh brokerRunning for the app's MQTT card
    clearRequests()   // stale requests from before the reconnect
    if (!subscribeAll()) return
    if (logNormal()) slog("info", "MQTT connected and subscribed")
    runIn(1, "refreshAllActivities")
    runEvery3Hours("refreshAllActivities")   // hub doesn't announce list changes
}

private boolean subscribeAll() {
    try {
        SUBS.each { interfaces.mqtt.subscribe(it) }
        state.subsVersion = SUBS_VERSION
        return true
    } catch (e) {
        slog("error", "MQTT subscribe failed: ${e.message}")
        return false
    }
}

// Adds subscriptions introduced by a driver update without a reconnect.
private void ensureSubscriptions() {
    if (state.subsVersion == SUBS_VERSION || !mqttUp()) return
    if (subscribeAll() && logNormal()) slog("info", "MQTT subscriptions updated")
}

private boolean mqttUp() {
    try { return interfaces.mqtt.isConnected() } catch (e) { return false }
}

void forceReconnectMqtt() {
    slog("info", "forcing MQTT reconnect")
    state.remove("retryCount")
    unschedule("retryConnect")
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
        slog("warn", "no X2 hub found to resupply broker details")
    }
}

// Returns null if the check fails. Called by the app's MQTT card.
def checkBuiltInBroker() {
    try {
        boolean running = MQTTHelper.isBuiltInBrokerRunning()
        if (logFull()) slog("debug", "isBuiltInBrokerRunning() = $running")
        sendEvent(name: "brokerRunning", value: running.toString())
        return running
    } catch (e) {
        slog("error", "isBuiltInBrokerRunning() failed: ${e.message}")
        sendEvent(name: "brokerRunning", value: "error")
        return null
    }
}

// ============================================================
// Publishing
// ============================================================

private boolean publishJson(String topic, Map payload) {
    if (!mqttUp()) {
        slog("warn", "MQTT not connected, can't publish to $topic")
        return false
    }
    String json = JsonOutput.toJson(payload)
    try {
        interfaces.mqtt.publish(topic, json)
        if (logFull()) slog("debug", "published $json to $topic")
        pauseExecution(200)
        return true
    } catch (e) {
        slog("error", "MQTT publish to $topic failed: ${e.message}")
        return false
    }
}

boolean publishMqttActivityControl(String mac, Integer activityId, String desiredState) {
    if (logNormal()) slog("info", "sending activity $activityId $desiredState to $mac")
    return publishJson("activity/${mac}/activity_control_down", [data: [activity_id: activityId, state: desiredState]])
}

boolean requestActivityList(String mac) {
    if (!mqttUp()) {
        slog("warn", "MQTT not connected, can't refresh $mac. Run Force Reconnect on the Bridge.")
        return false
    }
    ensureSubscriptions()
    return queueRequest(mac, "activity/${mac}/list_request", [data: "activity_list"], "activity/list", "activity list")
}

boolean requestDeviceList(String mac) {
    if (!mqttUp()) return false
    return queueRequest(mac, "device/${mac}/list_request", [data: "device_list"], "device/list", "device list")
}

boolean requestDeviceKeys(String mac, Integer deviceId) {
    if (!mqttUp() || deviceId == null) return false
    return queueRequest(mac, "device/${mac}/keys_request", [data: [device_id: deviceId]], "device/keys_list", "keys of device $deviceId")
}

// ============================================================
// Request queue. One list request in flight at a time. Held in memory (REQ_STATE), not
// atomicState: calls arriving from the Remote saw stale atomicState and lost writes.
// Every change runs under REQ_LOCK, since refreshes and replies arrive on different threads.
// ============================================================

private def withReqLock(Closure body) {
    if (!REQ_LOCK.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS)) {
        slog("warn", "request queue busy, skipping")
        return null
    }
    try {
        return body()
    } finally {
        REQ_LOCK.release()
    }
}

// Caller must hold REQ_LOCK.
private Map reqState() {
    String key = device.id.toString()
    Map st = REQ_STATE.get(key)
    if (st == null) {
        st = [queue: [], inFlight: null]
        REQ_STATE.put(key, st)
    }
    return st
}

// Caller must hold REQ_LOCK. Moves the next request in flight and returns it, or null.
private Map claimNextLocked(Map st) {
    if (!st.queue || !mqttUp()) {
        st.queue = []
        st.inFlight = null
        return null
    }
    Map r = (st.queue as List).remove(0)
    r.sentAt = now()
    st.inFlight = r
    return r
}

private void clearRequests() {
    withReqLock {
        Map st = reqState()
        st.queue = []
        st.inFlight = null
    }
}

private void publishRequest(Map r) {
    if (!r) return
    if (logFull()) slog("debug", "requesting ${r.what} from ${r.mac}")
    if (publishJson(r.topic as String, r.payload as Map)) {
        runIn(REQ_TIMEOUT, "requestTimeout", [data: [sentAt: r.sentAt]])
    } else {
        clearRequests()
    }
}

private boolean queueRequest(String mac, String topic, Map payload, String reply, String what) {
    Map next = withReqLock {
        Map st = reqState()
        Map inFlight = st.inFlight
        // A missed timer can't stall the queue: anything in flight too long is dropped here.
        if (inFlight && now() - ((inFlight.sentAt ?: 0) as Long) > (REQ_TIMEOUT + 2) * 1000L) {
            slog("warn", "${inFlight.what} for ${inFlight.mac} got no reply, moving on")
            st.inFlight = null
            inFlight = null
        }
        boolean dup = (inFlight?.topic == topic && inFlight?.payload == payload) || st.queue.any { it.topic == topic && it.payload == payload }
        if (dup) {
            if (logFull()) slog("debug", "$what already queued for $mac")
        } else {
            st.queue << [mac: mac, topic: topic, payload: payload, reply: reply, what: what]
        }
        return inFlight ? null : claimNextLocked(st)
    } as Map
    publishRequest(next)
    return true
}

// Retries once, then moves on. Only acts on the request it was scheduled for.
void requestTimeout(data) {
    Map next = withReqLock {
        Map st = reqState()
        Map r = st.inFlight
        if (!r || (r.sentAt as Long) != (data?.sentAt as Long)) return null
        if (((r.retries ?: 0) as Integer) < 1) {
            slog("warn", "no reply to ${r.what} from ${r.mac} within ${REQ_TIMEOUT}s, retrying once")
            r.retries = ((r.retries ?: 0) as Integer) + 1
            (st.queue as List).add(0, r)
        } else {
            slog("warn", "no reply to ${r.what} from ${r.mac} after a retry, moving on")
        }
        return claimNextLocked(st)
    } as Map
    publishRequest(next)
}

private void requestAnswered(String mac, String kind) {
    Map next = withReqLock {
        Map st = reqState()
        Map r = st.inFlight
        if (r && r.mac == mac && r.reply == kind) return claimNextLocked(st)
        if (logFull()) slog("debug", "got $kind from $mac while waiting for ${r ? r.what + ' from ' + r.mac : 'nothing'}")
        return null
    } as Map
    publishRequest(next)
}

// Each Remote requests its activities, devices, and button names.
void refreshAllActivities() {
    if (!mqttUp()) {
        scheduleRetry("connection lost")
        return
    }
    x2Hubs().each { it.requestActivityList() }
}

// ============================================================
// Receiving
// ============================================================

void parse(String description) {
    try {
        def msg = interfaces.mqtt.parseMessage(description)
        def parts = msg.topic.split("/")
        String mac
        String kind
        if (parts.length == 2 && parts[1] == "up") {
            mac = parts[0].toUpperCase()
            kind = "button"
        } else if (parts.length == 3 && parts[0] in ["activity", "device"]) {
            mac = parts[1].toUpperCase()
            kind = "${parts[0]}/${parts[2]}".toString()
        } else {
            return
        }

        def hub = getChildDevice(mac)
        if (!hub) {
            if (kind == "activity/activity_control_up") rememberMac(mac)
            return
        }
        if (logFull()) slog("debug", "MQTT topic=${msg.topic}, payload=${msg.payload}")
        def json = new JsonSlurper().parseText(msg.payload)
        hub.markMqttMessageSeen()
        List data = json.data instanceof List ? json.data : []

        switch (kind) {
            case "activity/activity_control_up":
                hub.receiveMqttActivityUpdate(json.activity_id as Integer, json.state as String)
                break
            case "activity/list":
                List items = data.collect {
                    [activity_id: it.activity_id as Integer, activity_name: it.activity_name?.toString()?.trim(), state: it.state?.toString()]
                }
                if (logNormal()) slog("info", "received ${items.size()} activities from ${hub.displayName}")
                hub.receiveActivityList(items)
                break
            case "device/list":
                hub.receiveDeviceList(data.collect { [device_id: it.device_id as Integer, device_name: it.device_name?.toString()?.trim()] })
                break
            case "device/keys_list":
                hub.receiveKeyList(json.device_id as Integer, data.collect { [key_id: it.key_id as Integer, key_name: it.key_name?.toString()?.trim()] })
                break
            case "button":
                hub.receiveButtonPress(json.device_id as Integer, json.key_id as Integer)
                break
        }
        if (kind in ["activity/list", "device/list", "device/keys_list"]) requestAnswered(mac, kind)
    } catch (e) {
        slog("error", "failed to parse MQTT message: ${e.message}")
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
    if (!seen[mac] && logNormal()) slog("info", "found X2 hub $mac")
    seen[mac] = now()
    if (seen.size() > 5) seen = seen.sort { a, b -> b.value <=> a.value }.take(5)
    state.discoveredMacs = seen
}
