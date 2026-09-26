/*
    Sofabaton - Remote Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: this driver's X1/X1S local HTTP listener and DNI-routing logic
    is a fork of Derek Osborn's (dJOS1475) Hubitat community driver, which
    itself built on push-command building blocks originated by Mike Maxwell
    (mike.maxwell). Full credit retained, see author field below.

    Notes:
     -Fork of dJOS's driver, renamed "Sofabaton Remote". Created by the app as a
      child of the Bridge, so hubs nest together in the Devices list.
     -X1S: DNI = ipToHex(ip) so Hubitat's local listener (port 39501) routes each
      hub's PUT here. Body = a number 1-20, a user slot matchString, or on/off.
      Activity sync matches the fired button's label to an Activity child's name.
     -X2: DNI = bare uppercase MAC (matches the MQTT topic). No listener; the
      Bridge calls receiveMqttActivityUpdate(). Sync matches the numeric
      activity_id. 255 = hub-wide Power Off, every activity goes off.
     -The original X1 isn't supported (dJOS's driver starts at X1S).
     -Configured by the app. Driver preferences can't show/hide fields by model
      (platform limit) and render in a fixed grid by declaration order, so the X2
      pill sits on the MAC field's description rather than its own paragraph,
      which would shift every field after it. Field titles can't take HTML.
     -mqttHost/Port/User are published as attributes so the app can prefill Edit
      Hub. The password is never exposed.
     -lastMqttMessage is the only connection signal. The X2 has no keepalive topic.
     -removeAllActivityDevices() must run before the Bridge deletes this device,
      or Hubitat can leave an orphan that blocks re-adding the same DNI.
*/

def version() { return "1.0.0" }

metadata {
    definition (name: "Sofabaton Remote", namespace: "jdthomas24", author: "Jason Thomas (fork of Derek Osborn/dJOS1475, building on Mike Maxwell/mike.maxwell, Gassgs, SViel)", importUrl: "") {
        capability "Actuator"
        capability "PushableButton"
        capability "Switch"
        attribute "lastButtonValue", "string"
        attribute "lastButtonLabel", "string"
        attribute "remoteIp", "string"
        attribute "remoteMac", "string"
        attribute "hubModel", "string"
        attribute "mqttHost", "string"
        attribute "mqttPort", "string"
        attribute "mqttUser", "string"
        attribute "lastMqttMessage", "string"
        preferences {
            input name: "deviceInfo", type: "paragraph", element: "paragraph", title: "Sofabaton Remote", description: "Driver Version: ${version()}<br><b>Configured via the Sofabaton Integration app. Add or edit hubs there, not here.</b> Manual edits here can get out of sync with the app."
            input name: "hubModel", type: "enum", title: "Hub Model", options: ["X1S", "X2"], required: true
            input name: "appConfig", type: "paragraph", element: "paragraph", title: "X1S Setup (one-time, in the Sofabaton app)", description: "<span style='background:#e8a33d;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.85em;font-weight:bold'>X1S</span><br>Devices &rarr; Add Device &rarr; Wi-Fi &rarr; 'Create a virtual device for IP control'. URL: http://[Hubitat IP]:39501/, method PUT, body = a number 1-20, a string matching a slot below, or on/off. Repeat per activity."
            input name:"ip", type:"text", title: "Remote IP Address (X1S only)"
            input name: "mac", type: "text", title: "Hub MAC Address (X2 only)", description: "<span style='background:#5f8b6f;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.85em;font-weight:bold'>X2</span> Connects this hub to the MQTT broker, along with the fields below."
            input name: "mqttHost", type: "text", title: "MQTT Broker Host/IP (X2 only)"
            input name: "mqttPort", type: "text", title: "MQTT Broker Port (X2 only)", defaultValue: "1883"
            input name: "mqttUser", type: "text", title: "MQTT Broker Username (X2 only)"
            input name: "mqttPass", type: "password", title: "MQTT Broker Password (X2 only)"
            input name: "userInfo", type: "paragraph", element: "paragraph", title: "User Definable Buttons (X1S only)", description: "<span style='background:#e8a33d;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.85em;font-weight:bold'>X1S</span><br>matchString|Description, e.g. watchTV|Watch TV. Fires buttons 11-20. A Description matching an Activity's name keeps that Activity in sync."
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
            input name:"logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            input name:"txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        }
    }
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void installed(){
    log.info "installed..."
    updated()
}

void updated(){
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
    sendEvent(name:"numberOfButtons", value:20)
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
                log.error "$device.label: MAC '$mac' is not a valid 12-character hex MAC, DNI not updated"
            }
        }
        if (mqttHost) {
            sendEvent(name: "mqttHost", value: mqttHost)
            sendEvent(name: "mqttPort", value: mqttPort ?: "1883")
            sendEvent(name: "mqttUser", value: mqttUser ?: "")
            parent?.ensureMqttConnected(mqttHost, mqttPort ?: "1883", mqttUser, mqttPass)
        }
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
            log.warn "$slot has a description but no match string, it will never fire"
            continue
        }
        if (match.equalsIgnoreCase("on") || match.equalsIgnoreCase("off")) {
            log.warn "$slot match string '$match' is reserved for the switch state and will never fire this button"
        }
        Integer n = toButtonNumber(match)
        if (n != null && n >= 1 && n <= 20) {
            log.warn "$slot match string '$match' is a plain number and will fire button $n instead"
        }
        String key = match.toLowerCase()
        if (seen[key]) log.warn "$slot match string '$match' duplicates User ${seen[key]}, only the first will fire"
        else seen[key] = i
    }
}

// X1S local HTTP. Order: on/off, button number, user slot match.
void parse(String description) {
    def msg = parseLanMessage(description)
    if (logEnable) log.debug "$device.label received header: $msg.header, body: $msg.body"
    def data = msg.body?.trim()
    if (!data) {
        if (logEnable) log.debug "$device.label empty body received, ignoring"
        return
    }
    if (data.equalsIgnoreCase("on")) {
        sendEvent(name:"switch", value:"on")
        return
    } else if (data.equalsIgnoreCase("off")) {
        sendEvent(name:"switch", value:"off")
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
    log.warn "$device.label No match found for received body value: $data"
}

// pushed event for PushableButton, plus raw/label attributes for rules. Drives Activity sync.
private void firePushed(Integer btn, String lbl, String raw) {
    if (txtEnable) log.info "$device.label Button $btn${lbl ? ' (' + lbl + ')' : ''} Pushed"
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
    if (btn != null) {
        if (btn < 1 || btn > 20) {
            log.warn "$device.label Button $btn is out of range (1-20), ignoring"
            return
        }
        firePushed(btn, labelForButton(btn), str)
        return
    }
    def hit = matchUserButton(str)
    if (hit) {
        firePushed(hit[0] as Integer, hit[1] as String, str)
    } else {
        log.warn "$device.label No user definable button matches '$str', ignoring"
    }
}

void on() {
    if (txtEnable) log.info "$device.label Switch On"
    sendEvent(name:"switch", value:"on")
}

void off() {
    if (txtEnable) log.info "$device.label Switch Off"
    sendEvent(name:"switch", value:"off")
}

// Hex DNI for an IPv4 address, or null (with an error) if malformed. The app mirrors this.
String ipToHex(String ipAddress) {
    List<String> quad = (ipAddress ?: "").trim().split(/\./)
    boolean valid = quad.size() == 4 && quad.every {
        it.isInteger() && it.toInteger() >= 0 && it.toInteger() <= 255
    }
    if (!valid) {
        log.error "$device.label Remote IP Address '${ipAddress}' is not a valid IPv4 address, the remote will not be able to reach this device"
        return null
    }
    return quad.collect { Integer.toHexString(it.toInteger()).padLeft(2,"0").toUpperCase() }.join()
}

// Called by the Bridge on every message for this MAC.
void markMqttMessageSeen() {
    sendEvent(name: "lastMqttMessage", value: new Date().format("yyyy-MM-dd h:mm:ss a"))
}

// ============================================================
// Activity child management
// ============================================================

// Sets webhook URLs and/or Activity ID directly so the device works without a preferences visit.
def createActivityDevice(String name, String urlOn = null, String urlOff = null, Integer sofabatonActivityId = null) {
    String dni = "${device.deviceNetworkId}-activity-${name.replaceAll(/[^A-Za-z0-9]/, '')}"
    def existing = getChildDevice(dni)
    if (existing) return existing
    def child = addChildDevice("jdthomas24", "Sofabaton Activity", dni, [label: name])
    if (child) {
        if (urlOn) child.updateSetting("webhookUrlOn", [value: urlOn, type: "text"])
        if (urlOff) child.updateSetting("webhookUrlOff", [value: urlOff, type: "text"])
        if (sofabatonActivityId != null) child.updateSetting("sofabatonActivityId", [value: sofabatonActivityId, type: "number"])
        child.updated()
    }
    return child
}

// Activities are grandchildren of the Bridge, so the Bridge asks this device to clear them first.
void removeAllActivityDevices() {
    def activityChildren = getChildDevices()?.findAll { it.typeName == "Sofabaton Activity" }
    activityChildren?.each { act ->
        try {
            deleteChildDevice(act.deviceNetworkId)
        } catch (e) {
            log.error "$device.label: failed to remove Activity child ${act.getLabel()}: ${e.message}"
        }
    }
}

void removeActivityDevice(String name) {
    String dni = "${device.deviceNetworkId}-activity-${name.replaceAll(/[^A-Za-z0-9]/, '')}"
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

// Preferred by the app: uses the real DNI instead of recomputing it from the name.
void removeActivityDeviceByDni(String dni) {
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

// X1S sync. One activity on at a time per hub. The remote only reports the
// activity that started, never a "stopped" event for the previous one.
private void handleActivityStateSync(String activityKey) {
    if (!activityKey) return
    def activityChildren = getChildDevices()?.findAll { it.typeName == "Sofabaton Activity" }
    if (!activityChildren) return

    def matched = activityChildren.find { it.getLabel()?.equalsIgnoreCase(activityKey) }
    if (!matched) {
        if (logEnable) log.debug "$device.label: no Activity matches '$activityKey', skipping state sync"
        return
    }
    activityChildren.findAll { it.deviceNetworkId != matched.deviceNetworkId && it.currentValue("switch") == "on" }.each {
        it.syncOff()
    }
    matched.syncOn()
}

// ============================================================
// X2 MQTT sync (called by the Bridge)
// ============================================================

void receiveMqttActivityUpdate(Integer activityId, String activityState) {
    def activityChildren = getChildDevices()?.findAll { it.typeName == "Sofabaton Activity" }
    if (!activityChildren) return

    if (activityId == 255) {
        if (txtEnable) log.info "$device.label: hub-wide Power Off received, turning off all activities"
        activityChildren.each { it.syncOff() }
        return
    }

    def matched = activityChildren.find { (it.currentValue("sofabatonActivityId") as Integer) == activityId }
    if (!matched) {
        if (logEnable) log.debug "$device.label: no Activity configured with ID $activityId, skipping state sync"
        return
    }

    if (activityState == "on") {
        activityChildren.findAll { it.deviceNetworkId != matched.deviceNetworkId && it.currentValue("switch") == "on" }.each { it.syncOff() }
        matched.syncOn()
    } else if (activityState == "off") {
        matched.syncOff()
    }
}

// Middle hop of the Activity -> Remote -> Bridge publish path.
void componentPublishActivityControl(childDevice, Integer activityId, String desiredState) {
    if (hubModel != "X2" || !device.deviceNetworkId) {
        log.error "$device.label: cannot publish MQTT activity control, hub is not X2 or has no MAC-based DNI"
        return
    }
    if (logEnable) log.debug "$device.label: forwarding to Bridge mac=${device.deviceNetworkId}, activityId=$activityId, state=$desiredState"
    parent?.publishMqttActivityControl(device.deviceNetworkId, activityId, desiredState)
}
