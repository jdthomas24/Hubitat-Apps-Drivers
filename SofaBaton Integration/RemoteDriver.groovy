/*
    Sofabaton - Remote Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: this driver's X1/X1S local HTTP listener and DNI-routing logic
    is a fork of Derek Osborn's (dJOS1475) Hubitat community driver, which
    itself built on push-command building blocks originated by Mike Maxwell
    (mike.maxwell). Full credit retained, see author field below.

    Notes:
     -Fork of dJOS's driver, renamed "Sofabaton Remote". Created by the app as a
      child of the Bridge.
     -X1S: DNI = ipToHex(ip) so Hubitat's listener (port 39501) routes each hub's
      PUT here. Body = an activity's bodyValue (preferred, set in the app), a number
      1-20, a user slot matchString, or on/off. Button slots are optional, kept for
      rules and older setups; a slot label matching an activity name still syncs it.
     -X2: DNI = bare uppercase MAC (matches the MQTT topic). No listener; the Bridge
      calls the receive* methods. Activities are created from the hub's list, DNI
      <MAC>-activity-<id>, matched by ID. Missing ones are flagged onHub=false, not deleted.
     -Activity labels default to "<Hub label>-<Activity>" so they group in device pickers.
      The plain name lives in the Activity's sofabatonName. applyActivityLabels() prefixes
      old labels once and follows hub renames. Custom labels are never touched.
     -X2 remote buttons: commands on the Sofabaton "Home Assistant Remote" publish
      {mac}/up {"device_id":n,"key_id":n}. key_id = button number. The device is learned
      from the first press (or set in Edit Hub), then its key list names the buttons.
      numberOfButtons follows the highest key_id. X1S keeps a fixed 20.
     -255 = hub-wide off, every activity goes off.
     -The original X1 isn't supported.
     -Driver preferences can't show/hide by model and render in declaration order,
      so the X2 pill sits on the MAC field's description. Titles can't take HTML.
     -mqttHost/Port/User are attributes so the app can prefill Edit Hub. Never the password.
     -Log level comes from the app via the Bridge and cascades to Activities. Log lines
      (this device's and its Activities') go up to the app via childLog, labeled by source.
     -removeAllActivityDevices() must run before the Bridge deletes this device,
      or Hubitat can leave an orphan that blocks re-adding the same DNI.
*/

def version() { return "1.1.0" }

metadata {
    definition (name: "Sofabaton Remote", namespace: "jdthomas24", author: "Jason Thomas (fork of Derek Osborn/dJOS1475, building on Mike Maxwell/mike.maxwell, Gassgs, SViel)", importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/SofaBaton%20Integration/RemoteDriver.groovy") {
        capability "Actuator"
        capability "PushableButton"
        capability "Switch"
        attribute "lastButtonValue", "string"
        attribute "lastButtonLabel", "string"
        attribute "remoteIp", "string"
        attribute "remoteMac", "string"
        attribute "hubModel", "string"
        attribute "mqttBroker", "string"
        attribute "mqttHost", "string"
        attribute "mqttPort", "string"
        attribute "mqttUser", "string"
        attribute "lastMqttMessage", "string"
        attribute "lastActivitySync", "string"
        command "requestActivityList"
        preferences {
            input name: "deviceInfo", type: "paragraph", element: "paragraph", title: "Sofabaton Remote", description: "Driver Version: ${version()}<br><b>Configured via the Sofabaton Integration app. Add or edit hubs there, not here.</b> Manual edits here can get out of sync with the app."
            input name: "hubModel", type: "enum", title: "Hub Model", options: ["X1S", "X2"], required: true
            input name: "appConfig", type: "paragraph", element: "paragraph", title: "X1S Setup (one-time, in the Sofabaton app)", description: "<span style='background:#e8a33d;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.85em;font-weight:bold'>X1S</span><br>Devices &rarr; Add Device &rarr; Wi-Fi &rarr; 'Create a virtual device for IP control'. URL: http://[Hubitat IP]:39501/, method PUT, body = a number 1-20, a string matching a slot below, or on/off. Repeat per activity."
            input name:"ip", type:"text", title: "Remote IP Address (X1S only)"
            input name: "mac", type: "text", title: "Hub MAC Address (X2 only)", description: "<span style='background:#5f8b6f;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.85em;font-weight:bold'>X2</span> Connects this hub over MQTT, along with the fields below. Remote buttons are set up in the Sofabaton app (see the app's Tips)."
            input name: "useBuiltInBroker", type: "bool", title: "Use Hubitat's built-in MQTT broker (X2 only)", defaultValue: true
            input name: "mqttHost", type: "text", title: "External Broker Host/IP (X2, external broker only)"
            input name: "mqttPort", type: "text", title: "External Broker Port (X2, external broker only)", defaultValue: "1883"
            input name: "mqttUser", type: "text", title: "External Broker Username (X2, external broker only)"
            input name: "mqttPass", type: "password", title: "External Broker Password (X2, external broker only)"
            input name: "userInfo", type: "paragraph", element: "paragraph", title: "User Definable Buttons (X1S only)", description: "<span style='background:#e8a33d;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.85em;font-weight:bold'>X1S</span><br><b>Optional.</b> Activities sync from their Body Value, set in the app. These slots only add button events for rules. Format: matchString|Description, fires buttons 11-20."
            input name:"usrBtn1", type:"text", title:"User 1 (11):", description:"matchString|Description", required:false
            input name:"usrBtn2", type:"text", title:"User 2 (12):", description:"matchString|Description", required:false
            input name:"usrBtn3", type:"text", title:"User 3 (13):", description:"matchString|Description", required:false
            input name:"usrBtn4", type:"text", title:"User 4 (14):", description:"matchString|Description", required:false
            input name:"usrBtn5", type:"text", title:"User 5 (15):", description:"matchString|Description", required:false
            input name:"usrBtn6", type:"text", title:"User 6 (16):", description:"matchString|Description", required:false
            input name:"usrBtn7", type:"text", title:"User 7 (17):", description:"matchString|Description", required:false
            input name:"usrBtn8", type:"text", title:"User 8 (18):", description:"matchString|Description", required:false
            input name:"usrBtn9", type:"text", title:"User 9 (19):", description:"matchString|Description", required:false
            input name:"usrBtn10", type:"text", title:"User 10 (20):", description:"matchString|Description", required:false
            input name: "numericInfo", type: "paragraph", element: "paragraph", title: "Numeric Buttons (X1S only)", description: "<span style='background:#e8a33d;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.85em;font-weight:bold'>X1S</span><br>Labels for buttons 1-10."
            input name:"btnLabel1", type:"text", title:"1:", description:"Button 1 label", required:false
            input name:"btnLabel2", type:"text", title:"2:", description:"Button 2 label", required:false
            input name:"btnLabel3", type:"text", title:"3:", description:"Button 3 label", required:false
            input name:"btnLabel4", type:"text", title:"4:", description:"Button 4 label", required:false
            input name:"btnLabel5", type:"text", title:"5:", description:"Button 5 label", required:false
            input name:"btnLabel6", type:"text", title:"6:", description:"Button 6 label", required:false
            input name:"btnLabel7", type:"text", title:"7:", description:"Button 7 label", required:false
            input name:"btnLabel8", type:"text", title:"8:", description:"Button 8 label", required:false
            input name:"btnLabel9", type:"text", title:"9:", description:"Button 9 label", required:false
            input name:"btnLabel10", type:"text", title:"10:", description:"Button 10 label", required:false
        }
    }
}

void installed(){
    updated()
}

void updated(){
    if (logFull()) slog("debug", "updated")
    if (hubModel != "X2") sendEvent(name:"numberOfButtons", value:20)   // X2 follows its key list
    if (hubModel) sendEvent(name: "hubModel", value: hubModel)

    // Numeric labels capped at 40 chars, user slots at 80 (match + pipe + description)
    for (int i = 1; i <= 10; i++) {
        def lbl = settings["btnLabel${i}"] ?: ""
        if (lbl.length() > 40) device.updateSetting("btnLabel${i}", [value:lbl.take(40), type:"text"])
        def val = settings["usrBtn${i}"] ?: ""
        if (val.length() > 80) device.updateSetting("usrBtn${i}", [value:val.take(80), type:"text"])
    }
    validateUserButtons()

    if (hubModel == "X2") {
        if (mac) {
            String dni = mac.replaceAll(/[^A-Fa-f0-9]/, "").toUpperCase()
            if (dni.length() == 12) {
                device.deviceNetworkId = dni
                sendEvent(name: "remoteMac", value: dni)
            } else {
                slog("error", "MAC '$mac' is not a valid 12-character hex MAC, DNI not updated")
            }
        }
        boolean builtIn = useBuiltInBroker != false
        sendEvent(name: "mqttBroker", value: builtIn ? "built-in" : "external")
        if (builtIn) {
            parent?.ensureMqttConnected(true, null, null, null, null)
        } else if (mqttHost) {
            sendEvent(name: "mqttHost", value: mqttHost)
            sendEvent(name: "mqttPort", value: mqttPort ?: "1883")
            sendEvent(name: "mqttUser", value: mqttUser ?: "")
            parent?.ensureMqttConnected(false, mqttHost, mqttPort ?: "1883", mqttUser, mqttPass)
        } else {
            slog("warn", "external broker selected but no host set")
        }
        requestActivityList()
    } else {
        // DNI set last so a malformed IP can't block the steps above
        if (ip) {
            String dni = ipToHex(ip)
            if (dni) device.deviceNetworkId = dni
            sendEvent(name:"remoteIp", value: ip)
        }
    }
}

// Warns at save time about user slots that can never fire.
private void validateUserButtons() {
    def seen = [:]
    for (int i = 1; i <= 10; i++) {
        def val = settings["usrBtn${i}"] ?: ""
        if (!val) continue
        String match = val.split(/\|/, 2)[0].trim()
        String slot = "User ${i} (${i + 10})"
        if (!match) {
            slog("warn", "$slot has a description but no match string, it will never fire")
            continue
        }
        if (match.equalsIgnoreCase("on") || match.equalsIgnoreCase("off")) {
            slog("warn", "$slot match string '$match' is reserved for the switch state and will never fire this button")
        }
        Integer n = toButtonNumber(match)
        if (n != null && n >= 1 && n <= 20) {
            slog("warn", "$slot match string '$match' is a plain number and will fire button $n instead")
        }
        String key = match.toLowerCase()
        if (seen[key]) slog("warn", "$slot match string '$match' duplicates User ${seen[key]}, only the first will fire")
        else seen[key] = i
    }
}

// X1S local HTTP. Order: on/off, activity body value, button number, user slot match.
void parse(String description) {
    def msg = parseLanMessage(description)
    if (logFull()) slog("debug", "received header: $msg.header, body: $msg.body")
    def data = msg.body?.trim()
    if (!data) {
        if (logFull()) slog("debug", "empty body received, ignoring")
        return
    }
    if (data.equalsIgnoreCase("on")) {
        sendEvent(name:"switch", value:"on")
        return
    } else if (data.equalsIgnoreCase("off")) {
        sendEvent(name:"switch", value:"off")
        return
    }
    def act = activityChildren().find { it.currentValue("bodyValue")?.equalsIgnoreCase(data) }
    if (act) {
        if (logNormal()) slog("info", "'$data' received, ${act.getLabel()} is now active")
        sendEvent(name:"lastButtonValue", value:data, isStateChange: true)
        sendEvent(name:"lastButtonLabel", value:act.getLabel(), isStateChange: true)
        activityChildren().findAll { it.deviceNetworkId != act.deviceNetworkId && it.currentValue("switch") == "on" }.each { it.syncOff() }
        act.syncOn()
        return
    }
    Integer btn = toButtonNumber(data)
    if (btn != null && btn >= 1 && btn <= 20) {
        firePushed(btn, labelForButton(btn), data)
        return
    }
    def hit = matchUserButton(data)
    if (hit) {
        firePushed(hit[0] as Integer, hit[1] as String, data)
        return
    }
    slog("warn", "No match found for received body value: $data")
}

// X1S pushed event, plus raw/label attributes for rules. Drives Activity sync.
private void firePushed(Integer btn, String lbl, String raw) {
    if (logNormal()) slog("info", "Button $btn${lbl ? ' (' + lbl + ')' : ''} Pushed")
    sendEvent(name:"pushed", value:btn, isStateChange: true, descriptionText:"$device.label button $btn was pushed")
    sendEvent(name:"lastButtonValue", value:raw, isStateChange: true)
    sendEvent(name:"lastButtonLabel", value:(lbl ?: raw), isStateChange: true)
    handleActivityStateSync(lbl ?: raw)
}

// Accepts "11", "11.0", or 11. Returns null if not a whole number.
private Integer toButtonNumber(def val) {
    String s = val?.toString()?.trim()
    if (!s) return null
    if (s.isInteger()) return s.toInteger()
    if (s.isBigDecimal() && s.toBigDecimal().stripTrailingZeros().scale() <= 0) {
        return s.toBigDecimal().intValue()
    }
    return null
}

// Numeric labels for 1-10, user descriptions for 11-20.
private String labelForButton(Integer btn) {
    if (btn <= 10) return settings["btnLabel${btn}"] ?: ""
    def val = settings["usrBtn${btn - 10}"] ?: ""
    if (!val) return ""
    def parts = val.split(/\|/, 2)
    return parts.size() > 1 ? parts[1].trim() : parts[0].trim()
}

// Returns [button, label] for a matching user slot, or null.
private List matchUserButton(String str) {
    for (int i = 1; i <= 10; i++) {
        def val = settings["usrBtn${i}"] ?: ""
        if (!val) continue
        def parts = val.split(/\|/, 2)
        def match = parts[0].trim()
        if (match && match.equalsIgnoreCase(str)) {
            return [i + 10, parts.size() > 1 ? parts[1].trim() : match]
        }
    }
    return null
}

void push(data) {
    String str = data?.toString()?.trim() ?: ""
    Integer btn = toButtonNumber(str)
    if (hubModel == "X2") {
        if (btn == null || btn < 1) {
            slog("warn", "'$str' is not a button number, ignoring")
            return
        }
        fireX2Pushed(btn, (state.buttonMap ?: [:])[btn.toString()])
        return
    }
    if (btn != null) {
        if (btn < 1 || btn > 20) {
            slog("warn", "Button $btn is out of range (1-20), ignoring")
            return
        }
        firePushed(btn, labelForButton(btn), str)
        return
    }
    def hit = matchUserButton(str)
    if (hit) {
        firePushed(hit[0] as Integer, hit[1] as String, str)
    } else {
        slog("warn", "No user definable button matches '$str', ignoring")
    }
}

void on() {
    if (logNormal()) slog("info", "Switch On")
    sendEvent(name:"switch", value:"on")
}

void off() {
    if (logNormal()) slog("info", "Switch Off")
    sendEvent(name:"switch", value:"off")
}

// Hex DNI for an IPv4 address, or null (with an error) if malformed. The app mirrors this.
String ipToHex(String ipAddress) {
    List<String> quad = (ipAddress ?: "").trim().split(/\./)
    boolean valid = quad.size() == 4 && quad.every {
        it.isInteger() && it.toInteger() >= 0 && it.toInteger() <= 255
    }
    if (!valid) {
        slog("error", "Remote IP Address '${ipAddress}' is not a valid IPv4 address, the remote will not be able to reach this device")
        return null
    }
    return quad.collect { Integer.toHexString(it.toInteger()).padLeft(2,"0").toUpperCase() }.join()
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

// All log lines go up the chain to the app, so they show under the app labeled by source.
private void slog(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) } catch (e) { localLog(level, "${device.displayName}: ${msg}") }
}

// Relays Activity log lines up to the Bridge.
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

// Called by the Bridge on every message for this MAC.
void markMqttMessageSeen() {
    sendEvent(name: "lastMqttMessage", value: new Date().format("yyyy-MM-dd h:mm:ss a"))
}

// ============================================================
// Activity child management
// ============================================================

private List activityChildren() {
    return getChildDevices()?.findAll { it.typeName == "Sofabaton Activity" } ?: []
}

private String defaultLabel(String base, String hubLabel = null) {
    return "${hubLabel ?: device.displayName}-${base}".toString()
}

// X1S: created by the app with webhook URLs.
def createActivityDevice(String name, String urlOn = null, String urlOff = null, Integer sofabatonActivityId = null, String bodyValue = null) {
    String dni = "${device.deviceNetworkId}-activity-${name.replaceAll(/[^A-Za-z0-9]/, '')}"
    def existing = getChildDevice(dni)
    if (existing) return existing
    def child = addChildDevice("jdthomas24", "Sofabaton Activity", dni, [label: defaultLabel(name)])
    if (child) {
        child.setLogLevel(state.logLevel)
        child.setHubInfo(name, true)
        if (urlOn) child.updateSetting("webhookUrlOn", [value: urlOn, type: "text"])
        if (urlOff) child.updateSetting("webhookUrlOff", [value: urlOff, type: "text"])
        if (sofabatonActivityId != null) child.updateSetting("sofabatonActivityId", [value: sofabatonActivityId, type: "number"])
        if (bodyValue) child.updateSetting("bodyValue", [value: bodyValue, type: "text"])
        child.updated()
    }
    return child
}

// Prefixes old unprefixed labels once (migration), and follows a hub rename when
// oldHubLabel is given. Labels that don't match a default pattern are left alone.
void applyActivityLabels(String oldHubLabel = null, String hubLabel = null) {
    boolean migrate = !state.labelsApplied
    if (!migrate && !oldHubLabel) return
    activityChildren().each { act ->
        String label = act.getLabel() ?: act.displayName
        String base = act.currentValue("sofabatonName")
        if (!base) {
            if (hubModel == "X2") return   // named on the next list sync
            base = label
            act.setHubInfo(base, true)
        }
        String target = defaultLabel(base, hubLabel)
        if (label == target) return
        boolean wasPlain = migrate && label == base
        boolean wasOldDefault = oldHubLabel && label == (oldHubLabel + "-" + base)
        if (wasPlain || wasOldDefault) {
            act.setLabel(target)
            if (logNormal()) slog("info", "renamed activity '$label' to '$target'")
        }
    }
    state.labelsApplied = true
}

// Activities are grandchildren of the Bridge, so the Bridge asks this device to clear them first.
void removeAllActivityDevices() {
    activityChildren().each { act ->
        try {
            deleteChildDevice(act.deviceNetworkId)
        } catch (e) {
            slog("error", "failed to remove Activity child ${act.getLabel()}: ${e.message}")
        }
    }
}

void removeActivityDevice(String name) {
    String dni = "${device.deviceNetworkId}-activity-${name.replaceAll(/[^A-Za-z0-9]/, '')}"
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

void removeActivityDeviceByDni(String dni) {
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

// X1S sync. One activity on at a time per hub. The remote only reports the
// activity that started, never a "stopped" event for the previous one.
private void handleActivityStateSync(String activityKey) {
    if (!activityKey) return
    def children = activityChildren()
    if (!children) return

    def matched = children.find {
        it.getLabel()?.equalsIgnoreCase(activityKey) || it.currentValue("sofabatonName")?.equalsIgnoreCase(activityKey)
    }
    if (!matched) {
        if (logFull()) slog("debug", "no Activity matches '$activityKey', skipping state sync")
        return
    }
    children.findAll { it.deviceNetworkId != matched.deviceNetworkId && it.currentValue("switch") == "on" }.each {
        it.syncOff()
    }
    matched.syncOn()
}

// ============================================================
// X2 MQTT (called by the Bridge)
// ============================================================

// Activities, the hub's device list, and button names in one refresh.
void requestActivityList() {
    if (hubModel != "X2") return
    String mac = device.deviceNetworkId
    parent?.requestActivityList(mac)
    parent?.requestDeviceList(mac)
    requestButtonNames()
}

// Creates missing activities, syncs every state, flags ones no longer on the hub.
void receiveActivityList(List items) {
    if (hubModel != "X2" || items == null) return
    def children = activityChildren()
    Set seenIds = [] as Set
    int created = 0
    items.each { item ->
        Integer id = item.activity_id as Integer
        if (id == null) return
        seenIds << id
        String name = item.activity_name ?: "Activity ${id}"
        def child = children.find { (it.currentValue("sofabatonActivityId") as Integer) == id }
        if (!child) {
            try {
                child = addChildDevice("jdthomas24", "Sofabaton Activity", "${device.deviceNetworkId}-activity-${id}", [label: defaultLabel(name), isComponent: false])
                child.setLogLevel(state.logLevel)
                child.updateSetting("sofabatonActivityId", [value: id, type: "number"])
                child.updated()
                created++
                if (logNormal()) slog("info", "added activity '$name' (ID $id)")
            } catch (e) {
                slog("error", "failed to create activity '$name' (ID $id): ${e.message}")
                return
            }
        } else {
            // Renamed in the Sofabaton app: follow it only if the Hubitat label is still the default.
            String oldName = child.currentValue("sofabatonName")
            if (oldName && oldName != name && child.getLabel() == defaultLabel(oldName)) {
                child.setLabel(defaultLabel(name))
                if (logNormal()) slog("info", "activity '$oldName' renamed on the hub to '$name'")
            }
        }
        child.setHubInfo(name, true)
        if (item.state == "on") child.syncOn() else child.syncOff()
    }
    children.findAll { !((it.currentValue("sofabatonActivityId") as Integer) in seenIds) }.each {
        slog("warn", "activity '${it.getLabel()}' is no longer on the hub. Remove it in the app if it was deleted.")
        it.setHubInfo(null, false)
    }
    applyActivityLabels()
    sendEvent(name: "lastActivitySync", value: new Date().format("yyyy-MM-dd h:mm:ss a"))
    if (logFull()) slog("debug", "activity list synced, ${items.size()} on hub, $created new")
}

void receiveMqttActivityUpdate(Integer activityId, String activityState) {
    def children = activityChildren()
    if (!children) return

    if (activityId == 255) {
        if (logNormal()) slog("info", "hub powered off, turning off all activities")
        children.each { it.syncOff() }
        return
    }

    def matched = children.find { (it.currentValue("sofabatonActivityId") as Integer) == activityId }
    if (!matched) {
        if (logFull()) slog("debug", "unknown activity ID $activityId, refreshing list")
        requestActivityList()
        return
    }

    if (activityState == "on") {
        children.findAll { it.deviceNetworkId != matched.deviceNetworkId && it.currentValue("switch") == "on" }.each { it.syncOff() }
        matched.syncOn()
    } else if (activityState == "off") {
        matched.syncOff()
    }
}

// Middle hop of the Activity -> Remote -> Bridge publish path.
boolean componentPublishActivityControl(childDevice, Integer activityId, String desiredState) {
    if (hubModel != "X2" || !device.deviceNetworkId) {
        slog("error", "cannot send MQTT activity control, hub is not X2 or has no MAC-based DNI")
        return false
    }
    if (logFull()) slog("debug", "forwarding to Bridge mac=${device.deviceNetworkId}, activityId=$activityId, state=$desiredState")
    return parent?.publishMqttActivityControl(device.deviceNetworkId, activityId, desiredState) ?: false
}

// ============================================================
// X2 remote buttons (Sofabaton "Home Assistant Remote" commands)
// ============================================================

void receiveButtonPress(Integer deviceId, Integer keyId) {
    if (hubModel != "X2" || keyId == null) return
    Map names = state.buttonMap ?: [:]
    Integer known = state.buttonDeviceId as Integer
    if (known == null && deviceId != null) {
        state.buttonDeviceId = deviceId
        if (logNormal()) slog("info", "learned the Hubitat Control device (ID $deviceId), loading its buttons")
        requestButtonNames()
    } else if (deviceId == known && !names[keyId.toString()]) {
        requestButtonNames()   // command added since the last list
    } else if (deviceId != known && logFull()) {
        slog("debug", "button from device $deviceId, not the Hubitat Control device ($known)")
    }
    Integer count = (device.currentValue("numberOfButtons") ?: 0) as Integer
    if (keyId > count) sendEvent(name: "numberOfButtons", value: keyId)
    fireX2Pushed(keyId, names[keyId.toString()] as String)
}

private void fireX2Pushed(Integer btn, String name) {
    String lbl = name ?: "Button ${btn}"
    if (logNormal()) slog("info", "button $btn ($lbl) pushed")
    sendEvent(name: "pushed", value: btn, isStateChange: true, descriptionText: "$device.label button $btn ($lbl) was pushed")
    sendEvent(name: "lastButtonValue", value: btn.toString(), isStateChange: true)
    sendEvent(name: "lastButtonLabel", value: lbl, isStateChange: true)
}

private void requestButtonNames() {
    Integer id = state.buttonDeviceId as Integer
    if (id != null) parent?.requestDeviceKeys(device.deviceNetworkId, id)
}

void receiveDeviceList(List items) {
    if (hubModel != "X2" || items == null) return
    state.hubDevices = items.findAll { it.device_id != null }.collectEntries { [(it.device_id.toString()): it.device_name ?: "Device ${it.device_id}"] }
}

void receiveKeyList(Integer deviceId, List items) {
    if (hubModel != "X2" || deviceId == null || deviceId != (state.buttonDeviceId as Integer)) return
    Map map = [:]
    (items ?: []).each { if (it.key_id != null) map[it.key_id.toString()] = (it.key_name ?: "Button ${it.key_id}").toString() }
    if (map != (state.buttonMap ?: [:]) && logNormal()) slog("info", "loaded ${map.size()} buttons from the Hubitat Control device")
    state.buttonMap = map
    Integer max = map ? map.keySet().collect { it as Integer }.max() : 0
    if (max) sendEvent(name: "numberOfButtons", value: max)
}

// Set from Edit Hub. null = learn from the next press.
void setButtonDevice(Integer id) {
    if (id == (state.buttonDeviceId as Integer)) return
    state.remove("buttonMap")
    if (id == null) {
        state.remove("buttonDeviceId")
        if (logNormal()) slog("info", "Hubitat Control device will be learned from the next button press")
        return
    }
    state.buttonDeviceId = id
    if (logNormal()) slog("info", "Hubitat Control device set to ID $id")
    requestButtonNames()
}

// Read by the app for the hub card and Edit Hub.
Map getButtonMap() { return state.buttonMap ?: [:] }
Integer getButtonDeviceId() { return state.buttonDeviceId as Integer }
Map getHubDevices() { return state.hubDevices ?: [:] }

