/*
    Sofabaton - Remote Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: this driver's X1/X1S local HTTP listener and DNI-routing logic
    is a fork of Derek Osborn's (dJOS1475) Hubitat community driver, which
    itself built on push-command building blocks originated by Mike Maxwell
    (mike.maxwell). Full credit retained -- see author field below.

	2026-09-10 jdthomas24  -- FORK, renamed "Sofabaton Remote"
		-Converted to a child device created by the Sofabaton Integration app
		 (via the Sofabaton Integration Bridge grouping device), so multiple
		 hubs nest together in the Devices list instead of standing alone.
	2026-09-16 jdthomas24
		-Changed the hubModel preference dropdown from "X1/X1S" to "X1S",
		 since the plain X1 isn't supported by the underlying driver this
		 forks (matches the same change in the parent app).
		-Added mqttHost/mqttPort/mqttUser as published attributes (X2 only)
		 so the parent app's Edit Hub page can read back and pre-fill the
		 broker connection fields. Password intentionally not exposed as a
		 readable attribute.
		-Added removeActivityDeviceByDni() so the parent app can remove an
		 Activity child cleanly by its actual DNI, rather than needing to
		 recompute the name-derived DNI itself.

	*OVERVIEW
	 Represents one physical Sofabaton hub. X1S and X2 hubs share this
	 driver but use different local mechanisms, both confirmed against real
	 hardware:

	   -X1S: unchanged from the original fork. IP is entered in this
	    device's own preferences; the app pre-fills it and pre-computes the
	    matching DNI (ipToHex()) at creation time so Hubitat's built-in
	    local HTTP listener routes each hub's inbound PUT to the correct
	    device. Activity state sync (handleActivityStateSync()) matches a
	    fired button's label against a Sofabaton Activity child's name.

	   -X2: identified by MAC address instead of IP (DNI = the bare
	    uppercase MAC, matching the MQTT topic's MAC segment exactly, e.g.
	    activity/14639332AA40/activity_control_up). No local HTTP listener
	    is used for activity state -- the Bridge device holds one shared
	    MQTT connection for all X2 hubs and calls receiveMqttActivityUpdate()
	    on this device when a message for this hub's MAC arrives. Activity
	    state sync matches the payload's numeric activity_id against a
	    Sofabaton Activity child's configured Sofabaton Activity ID (there's
	    no button-label matching for X2 the way X1S has, since MQTT reports
	    ids, not labels). Confirmed: a normal activity start reports
	    {"activity_id":<id>,"state":"on"}; the hardware Power Off key
	    reports {"activity_id":255,"state":"off"} meaning every activity on
	    the hub just went off, not just id 255.

	 Both models can still have Sofabaton Activity children created under
	 them by the parent app; which command path (webhook vs MQTT) each
	 Activity uses is decided on the Activity device itself.

	 NOTE: this is a fork of dJOS's driver, not a from-scratch rewrite for
	 the X1S HTTP path. Full credit above retained.
*/

def version() {
    return "1.2.0-jdthomas24"
}

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
        preferences {
            input name: "deviceInfo", type: "paragraph", element: "paragraph", title: "Sofabaton Remote", description: "Driver Version: ${version()}<br>Compatible Hardware: X1S and above"
            input name: "hubModel", type: "enum", title: "Hub Model", options: ["X1S", "X2"], required: true
            input name: "appConfig", type: "paragraph", element: "paragraph", title: "X1S: Sofabaton App Configuration", description: "1. In the Sofabaton app, go to Devices and tap Add Device, then select Wi-Fi<br>2. Tap the link at the bottom: 'Create a virtual device for IP control'<br>3. Enter the URL: http://[your Hubitat IP]:39501/<br>4. Set the request method to PUT<br>5. Leave Connect Type and Additional Headers blank<br>6. In the Body field enter either:<br>&nbsp;&nbsp;&nbsp;- A number 1-10 for a numeric button, or 11-20 for a user definable button<br>&nbsp;&nbsp;&nbsp;- Any string (e.g. watchTV) matching a user definable slot<br>&nbsp;&nbsp;&nbsp;- on or off to set this device's switch state<br>7. Repeat for each activity using a unique value each time"
            input name:"ip", type:"text", title: "Remote IP Address (X1S only)"
            input name: "mqttConfig", type: "paragraph", element: "paragraph", title: "X2: MQTT Configuration", description: "Enable Hubitat's built-in MQTT broker first (Integrations &rarr; Add Built-In App &rarr; MQTT Import Integration), then enter the same broker details below AND in the Sofabaton app's Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Add Home Assistant Remote screen."
            input name: "mac", type: "text", title: "Hub MAC Address (X2 only, e.g. 14639332AA40, no colons)"
            input name: "mqttHost", type: "text", title: "MQTT Broker Host/IP (X2 only, not 127.0.0.1)"
            input name: "mqttPort", type: "text", title: "MQTT Broker Port (X2 only)", defaultValue: "1883"
            input name: "mqttUser", type: "text", title: "MQTT Broker Username (X2 only, leave blank if none)"
            input name: "mqttPass", type: "password", title: "MQTT Broker Password (X2 only, leave blank if none)"
            input name: "userInfo", type: "paragraph", element: "paragraph", title: "User Definable Buttons (X1S only)", description: "Enter the match string the remote sends. Optionally add a pipe | followed by a description e.g. watchTV|Watch TV. The match string must match what you entered in the remote app.<br>These fire button numbers 11-20 (User 1 = button 11, User 10 = button 20). You can also trigger rules on the lastButtonValue or lastButtonLabel custom attributes if you prefer matching the string itself.<br><br>If a slot's description matches the name of a Sofabaton Activity child device (added via the parent app), that Activity device's state is kept in sync automatically."
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
            input name: "numericInfo", type: "paragraph", element: "paragraph", title: "Numeric Buttons (X1S only)", description: "Labels for buttons triggered by a number (1-10) in the request body."
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

    // Truncate numeric button labels to 40 chars
    for (int i = 1; i <= 10; i++) {
        def lbl = settings["btnLabel${i}"] ?: ""
        if (lbl.length() > 40) {
            device.updateSetting("btnLabel${i}", [value:lbl.take(40), type:"text"])
        }
    }
    // Truncate user definable button entries to 80 chars (match + pipe + description)
    for (int i = 1; i <= 10; i++) {
        def val = settings["usrBtn${i}"] ?: ""
        if (val.length() > 80) {
            device.updateSetting("usrBtn${i}", [value:val.take(80), type:"text"])
        }
    }
    validateUserButtons()

    if (hubModel == "X2") {
        if (mac) {
            String dni = mac.replaceAll(/[^A-Fa-f0-9]/, "").toUpperCase()
            if (dni.length() == 12) {
                device.deviceNetworkId = dni
                sendEvent(name: "remoteMac", value: dni)
            } else {
                log.error "$device.label: MAC '$mac' does not look like a valid 12-character hex MAC, DNI not updated"
            }
        }
        if (mqttHost) {
            sendEvent(name: "mqttHost", value: mqttHost)
            sendEvent(name: "mqttPort", value: mqttPort ?: "1883")
            sendEvent(name: "mqttUser", value: mqttUser ?: "")
            parent?.ensureMqttConnected(mqttHost, mqttPort ?: "1883", mqttUser, mqttPass)
        }
    } else {
        // Set the DNI last so a malformed IP cannot prevent any of the above from running
        if (ip) {
            String dni = ipToHex(ip)
            if (dni) device.deviceNetworkId = dni
            sendEvent(name:"remoteIp", value: ip)
        }
    }
}

// Warns about user definable slots that can never fire, so silent misconfiguration
// shows up in the logs at save time rather than as a mystery later
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

void parse(String description) {
    def msg = parseLanMessage(description)
    if (logEnable) log.debug "String is: $msg"
    if (logEnable) log.debug "String Header is: $msg.header"
    if (logEnable) log.debug "String Body is: $msg.body"
    def data = msg.body?.trim()
    if (!data) {
        if (logEnable) log.warn "$device.label Empty body received, ignoring"
        return
    }

    // 1. Check for on/off switch commands
    if (data.equalsIgnoreCase("on")) {
        sendEvent(name:"switch", value:"on")
        return
    } else if (data.equalsIgnoreCase("off")) {
        sendEvent(name:"switch", value:"off")
        return
    }

    // 2. Check if body is a button number (1-10 numeric, 11-20 user definable)
    Integer btn = toButtonNumber(data)
    if (btn != null && btn >= 1 && btn <= 20) {
        firePushed(btn, labelForButton(btn), data)
        return
    }

    // 3. Check against user definable match strings -> buttons 11-20
    def hit = matchUserButton(data)
    if (hit) {
        firePushed(hit[0] as Integer, hit[1] as String, data)
        return
    }

    // 4. No match found
    log.warn "$device.label No match found for received body value: $data"
}

// Fires the numeric pushed event required by PushableButton, plus the string
// attributes so rules can trigger on the raw value or the friendly label.
// Also drives Activity child state sync -- see handleActivityStateSync().
private void firePushed(Integer btn, String lbl, String raw) {
    if (txtEnable) log.info "$device.label Button $btn${lbl ? ' (' + lbl + ')' : ''} Pushed"
    sendEvent(name:"pushed", value:btn, isStateChange: true, descriptionText:"$device.label button $btn was pushed")
    sendEvent(name:"lastButtonValue", value:raw, isStateChange: true)
    sendEvent(name:"lastButtonLabel", value:(lbl ?: raw), isStateChange: true)
    handleActivityStateSync(lbl ?: raw)
}

// Coerces a button number from a String, Integer or BigDecimal ("11", "11.0", 11).
// Returns null if the value is not a whole number.
private Integer toButtonNumber(def val) {
    String s = val?.toString()?.trim()
    if (!s) return null
    if (s.isInteger()) return s.toInteger()
    if (s.isBigDecimal() && s.toBigDecimal().stripTrailingZeros().scale() <= 0) {
        return s.toBigDecimal().intValue()
    }
    return null
}

// Label for any button 1-20: numeric labels for 1-10, user descriptions for 11-20.
private String labelForButton(Integer btn) {
    if (btn <= 10) return settings["btnLabel${btn}"] ?: ""
    def val = settings["usrBtn${btn - 10}"] ?: ""
    if (!val) return ""
    def parts = val.split(/\|/, 2)
    return parts.size() > 1 ? parts[1].trim() : parts[0].trim()
}

// Returns [button, label] for a user definable slot matching str, or null.
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

    // Numeric buttons: 1-10 are the numeric slots, 11-20 the user definable ones
    Integer btn = toButtonNumber(str)
    if (btn != null) {
        if (btn < 1 || btn > 20) {
            log.warn "$device.label Button $btn is out of range (1-20), ignoring"
            return
        }
        firePushed(btn, labelForButton(btn), str)
        return
    }

    // String input: resolve it to its user definable button number
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

// Returns the hex DNI for an IPv4 address, or null (with an error logged) if malformed
String ipToHex(String ipAddress) {
    List<String> quad = (ipAddress ?: "").trim().split(/\./)
    boolean valid = quad.size() == 4 && quad.every {
        it.isInteger() && it.toInteger() >= 0 && it.toInteger() <= 255
    }
    if (!valid) {
        log.error "$device.label Remote IP Address '${ipAddress}' is not a valid IPv4 address - the remote will not be able to reach this device"
        return null
    }
    return quad.collect { Integer.toHexString(it.toInteger()).padLeft(2,"0").toUpperCase() }.join()
}

// ============================================================
// ============= ACTIVITY CHILD MANAGEMENT (jdthomas24) =========
// ============================================================

// Called by the parent app when an activity is added for this hub.
// Sets webhookUrlOn/webhookUrlOff (X1S) or sofabatonActivityId (X2)
// directly so the Activity device is immediately usable without a manual
// preferences visit.
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

void removeActivityDevice(String name) {
    String dni = "${device.deviceNetworkId}-activity-${name.replaceAll(/[^A-Za-z0-9]/, '')}"
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

// Preferred removal path for the parent app: takes the child's actual DNI
// directly (the app already has the device object from getChildDevices()),
// avoiding any risk of the name-sanitization in removeActivityDevice()
// above not exactly matching what was used at creation time.
void removeActivityDeviceByDni(String dni) {
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

// If the fired button's resolved label matches a Sofabaton Activity child's
// name, mark it on (local sync only, no cloud call) and mark any other
// Activity child under THIS hub that was on as off the same way. X1S only
// -- X2 state sync comes through receiveMqttActivityUpdate() instead, since
// MQTT reports numeric activity ids, not button labels. Mirrors Harmony's
// one-activity-at-a-time behaviour without assuming the remote sends an
// explicit "stopped" event for the previous activity -- it doesn't, this
// driver only ever learns about the activity that just started.
private void handleActivityStateSync(String activityKey) {
    if (!activityKey) return
    def activityChildren = getChildDevices()?.findAll { it.typeName == "Sofabaton Activity" }
    if (!activityChildren) return

    def matched = activityChildren.find { it.getLabel()?.equalsIgnoreCase(activityKey) }
    if (!matched) {
        if (logEnable) log.debug "$device.label: no Activity device matches '$activityKey', skipping state sync"
        return
    }

    activityChildren.findAll { it.deviceNetworkId != matched.deviceNetworkId && it.currentValue("switch") == "on" }.each {
        it.syncOff()
    }
    matched.syncOn()
}

// ============================================================
// ================= X2 MQTT ACTIVITY SYNC (jdthomas24) ===========
// The Bridge parses incoming MQTT activity_control_up messages and calls
// this method on the matching Remote child, keyed by MAC (this device's
// DNI for X2 hubs). Confirmed against real hardware:
//   - normal activity start: the reported activity_id, state "on"
//   - hub-wide Power Off: activity_id 255, state "off" -- means every
//     activity on this hub is now off, not just activity 255
// ============================================================

void receiveMqttActivityUpdate(Integer activityId, String activityState) {
    def activityChildren = getChildDevices()?.findAll { it.typeName == "Sofabaton Activity" }
    if (!activityChildren) return

    if (activityId == 255) {
        if (txtEnable) log.info "$device.label: MQTT hub-wide Power Off received, turning off all activities"
        activityChildren.each { it.syncOff() }
        return
    }

    def matched = activityChildren.find { (it.currentValue("sofabatonActivityId") as Integer) == activityId }
    if (!matched) {
        if (logEnable) log.debug "$device.label: no Activity device configured with Sofabaton Activity ID $activityId, skipping state sync"
        return
    }

    if (activityState == "on") {
        activityChildren.findAll { it.deviceNetworkId != matched.deviceNetworkId && it.currentValue("switch") == "on" }.each { it.syncOff() }
        matched.syncOn()
    } else if (activityState == "off") {
        matched.syncOff()
    }
}

// Called by an Activity child's on()/off() for MQTT-controlled (X2)
// activities. Delegates the actual publish up to the Bridge, which owns
// the shared MQTT connection.
void componentPublishActivityControl(childDevice, Integer activityId, String desiredState) {
    if (hubModel != "X2" || !device.deviceNetworkId) {
        log.error "$device.label: cannot publish MQTT activity control, this hub is not configured as X2 or has no MAC-based DNI set"
        return
    }
    parent?.publishMqttActivityControl(device.deviceNetworkId, activityId, desiredState)
}
